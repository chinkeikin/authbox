package com.authbox.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

class ApiFailure(val status: Int, val code: String) : Exception("服务请求失败（$status / $code）")
class SyncConflict : Exception("云端已有新版本；本地待上传内容已保留，请处理冲突")

class ApiClient(val origin: String, private val headers: Map<String, String> = emptyMap()) {
    init {
        val uri = URI(origin)
        require(uri.scheme == "https" && uri.rawAuthority != null && uri.host != null && uri.userInfo == null &&
            uri.rawPath.isEmpty() && uri.rawQuery == null && uri.rawFragment == null && uri.port == -1)
    }

    fun request(path: String, body: JSONObject? = null, access: String? = null): String {
        require(path.startsWith("/v1/") && !path.startsWith("//"))
        val connection = URL(origin + path).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20000; connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (access != null) connection.setRequestProperty("Authorization", "Bearer $access")
            if (body != null) {
                val bytes = canonical(body).toByteArray(Charsets.UTF_8)
                require(bytes.size <= 2 * 1024 * 1024 + 16384)
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1024 * 1024) { "服务响应过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            require(bytes.size <= 1024 * 1024) { "服务响应过大" }
            val encoded = decodeUtf8(bytes)
            if (status !in 200..299) {
                val code = runCatching { JSONObject(encoded).getString("error") }.getOrDefault("request_failed")
                throw ApiFailure(status, if (code.matches(Regex("[a-z_]{1,64}"))) code else "request_failed")
            }
            return encoded
        } finally { connection.disconnect() }
    }
}

class AccountRepository(context: Context, val api: ApiClient, val username: String, testNamespace: String? = null) {
    init { require(username.matches(Regex("[a-z][a-z0-9_]{2,31}"))) }
    private val namespace = testNamespace ?: "authbox.account." + MessageDigest.getInstance("SHA-256")
        .digest("${api.origin}/$username".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    private val box = KeystoreBox(context, namespace)
    private var state = box.read("state")?.let { parseObject(decodeUtf8(it)) } ?: obj("version" to 1,
        "origin" to api.origin, "username" to username, "identityId" to UUID.randomUUID().toString())
    private var unlocked: OpenVault? = null

    init {
        require(number(state, "version") == 1L && text(state, "origin") == api.origin && text(state, "username") == username)
        uuid(text(state, "identityId"))
        save(state)
    }

    private fun save(next: JSONObject) {
        box.write("state", canonical(next).toByteArray(Charsets.UTF_8))
        state = next.copy()
    }

    private fun session(): JSONObject = state.optJSONObject("auth") ?: throw IllegalStateException("请先登录")
    private fun access(): String = session().getJSONObject("session").getString("accessToken")
    fun role(): String? = state.optJSONObject("auth")?.getJSONObject("device")?.getString("role")
    fun revision(): Long? = state.optString("backup").takeIf { it.isNotEmpty() }?.let { VaultCrypto.inspect(it).getLong("revision") }
    fun hasPending(): Boolean = state.has("pending")
    fun isUnlocked(): Boolean = unlocked != null
    @Synchronized fun lock() { unlocked?.close(); unlocked = null }
    @Synchronized fun entities(): List<VaultEntity> = requireNotNull(unlocked) { "请先解锁保险库" }.entities.map { it.copy(payload = it.payload.copy()) }

    private fun checkLoginPassword(password: String) {
        canonical(password); require(password.toByteArray(Charsets.UTF_8).size in 8..256)
    }

    private fun acceptAuth(encoded: String) {
        val auth = JSONObject(encoded)
        fields(auth, "user", "device", "session")
        val user = auth.getJSONObject("user"); val device = auth.getJSONObject("device"); val tokens = auth.getJSONObject("session")
        require(user.getString("username") == username); uuid(user.getString("id")); uuid(device.getString("id"))
        require(device.getString("role") in setOf("reader", "manager"))
        for ((field, prefix) in listOf("accessToken" to "ab1a", "refreshToken" to "ab1r")) {
            val parts = tokens.getString(field).split('.')
            require(parts.size == 3 && parts[0] == prefix); uuid(parts[1]); require(unb64(parts[2]).size == 32)
        }
        require(tokens.getString("accessToken").split('.')[1] == tokens.getString("refreshToken").split('.')[1])
        for (field in listOf("accessExpiresAt", "refreshExpiresAt", "absoluteExpiresAt")) number(tokens, field)
        require(device.getString("role") != "manager" || device.getString("id") == state.getString("identityId"))
        val next = state.copy().put("auth", auth)
        if (device.getString("role") == "reader") next.put("identityId", device.getString("id"))
        save(next)
    }

    internal fun checkedProof(challenge: JSONObject, operation: String, hash: String, subject: String,
        deviceId: String, sessionId: String): JSONObject {
        fields(challenge, "id", "payload", "expiresAt")
        val id = text(challenge, "id"); uuid(id)
        val payload = text(challenge, "payload"); val values = JSONArray(payload)
        require(canonical(values) == payload && values.length() == 12)
        val expected = array("AuthBox", 1, "device-proof", api.origin, id, subject, deviceId, sessionId, operation, hash)
        for (index in 0 until 10) require(canonical(values.get(index)) == canonical(expected.get(index))) { "设备签名上下文不匹配" }
        val expires = number(challenge, "expiresAt")
        require(values.get(10) is Number && values.getLong(10) == expires)
        require(expires > System.currentTimeMillis() && expires <= System.currentTimeMillis() + 120000) { "签名挑战已过期或时钟不准确" }
        require(unb64(values.get(11) as String).size == 32)
        return obj("id" to id, "signature" to box.sign(payload))
    }

    private fun publicProof(operation: String, context: JSONObject, invite: String? = null): JSONObject {
        val hash = digest(context)
        val challenge = JSONObject(api.request("/v1/auth/challenge", obj("operation" to operation, "username" to username,
            "deviceId" to state.getString("identityId"), "requestHash" to hash, "invite" to invite)))
        return checkedProof(challenge, operation, hash, username, state.getString("identityId"), "")
    }

    @Synchronized fun login(password: String, existingDevice: Boolean = true) {
        checkLoginPassword(password)
        lock()
        if (state.optBoolean("refreshUncertain")) save(state.copy().apply { remove("auth"); remove("refreshUncertain") })
        val encoded = if (existingDevice) {
            val context = obj("username" to username, "deviceId" to state.getString("identityId"))
            val proof = publicProof("auth.device-login", context)
            api.request("/v1/auth/device-login", context.put("password", password).put("proof", proof))
        } else api.request("/v1/auth/login", obj("username" to username, "password" to password,
            "deviceName" to "AuthBox Android", "publicKey" to box.publicKey()))
        acceptAuth(encoded)
    }

    @Synchronized fun prepareRegistration(vaultPassword: String): Pair<String, List<String>> {
        require(!state.has("auth"))
        val created = VaultCrypto.create(vaultPassword)
        try {
            val codes = (1..8).map { "ABAC1-" + b64(randomBytes(16)) }
            val hashes = JSONArray().apply { codes.forEach { put(sha(("AuthBox/account-recovery/v1/" + it).toByteArray())) } }
            val registration = obj("backup" to canonical(created.vault.snapshot), "recoveryHashes" to hashes)
            save(state.copy().put("registration", registration))
            return created.recoveryKey to codes
        } finally { created.vault.close() }
    }

    @Synchronized fun register(password: String, invite: String, recoverySaved: Boolean) {
        require(recoverySaved) { "请先离线保存恢复材料" }; checkLoginPassword(password)
        require(unb64(invite).size == 32)
        val registration = state.getJSONObject("registration")
        val device = obj("id" to state.getString("identityId"), "name" to "AuthBox Android", "publicKey" to box.publicKey())
        val context = obj("username" to username, "device" to device, "recoveryHashes" to registration.getJSONArray("recoveryHashes"), "backup" to registration.getString("backup"))
        val proof = publicProof("auth.register", context, invite)
        val response = api.request("/v1/auth/register", context.put("password", password).put("invite", invite).put("proof", proof))
        acceptAuth(response)
        save(state.copy().put("backup", registration.getString("backup")).apply { remove("registration") })
    }

    @Synchronized fun refresh() {
        val token = session().getJSONObject("session").getString("refreshToken")
        require(!state.optBoolean("refreshUncertain")) { "上次刷新结果不确定，请重新登录" }
        save(state.copy().put("refreshUncertain", true))
        try {
            acceptAuth(api.request("/v1/auth/refresh", obj("refreshToken" to token)))
            save(state.copy().apply { remove("refreshUncertain") })
        } catch (error: Throwable) {
            lock()
            throw IllegalStateException("刷新未确认，请使用密码重新登录", error)
        }
    }

    private fun freshAccess(): String {
        require(!state.optBoolean("refreshUncertain")) { "请重新登录以恢复会话" }
        if (session().getJSONObject("session").getLong("accessExpiresAt") <= System.currentTimeMillis() + 30000) refresh()
        return access()
    }

    @Synchronized fun download(vaultPassword: String) {
        require(!hasPending()) { "本地存在待上传内容，请先完成上传或处理冲突" }
        val encoded = api.request("/v1/vault/snapshot", access = freshAccess())
        val opened = VaultCrypto.restore(encoded, vaultPassword)
        try {
            val prior = state.optString("backup").takeIf { it.isNotEmpty() }?.let { VaultCrypto.inspect(it) }
            require(prior == null || (prior.getString("vaultId") == opened.snapshot.getString("vaultId") &&
                opened.snapshot.getLong("revision") >= prior.getLong("revision"))) { "云端版本回退或保险库身份变化" }
            save(state.copy().put("backup", encoded))
            lock(); unlocked = opened
        } catch (error: Throwable) { opened.close(); throw error }
    }

    @Synchronized fun unlock(vaultPassword: String) {
        val encoded = state.optJSONObject("pending")?.getString("backup") ?: state.getString("backup")
        val opened = VaultCrypto.restore(encoded, vaultPassword)
        lock(); unlocked = opened
    }

    @Synchronized fun stage(entities: List<VaultEntity>) {
        require(role() == "manager") { "当前设备只有只读权限" }
        require(!hasPending()) { "已有待上传内容" }
        val vault = requireNotNull(unlocked) { "请先解锁保险库" }
        val previous = vault.entities.map { it.copy(payload = it.payload.copy()) }
        try {
            vault.entities.clear(); vault.entities.addAll(entities.map { it.copy(payload = it.payload.copy()) })
            val base = requireNotNull(revision())
            val backup = VaultCrypto.snapshot(vault, base + 1)
            require(backup.toByteArray(Charsets.UTF_8).size <= 1024 * 1024)
            val pending = obj("operationId" to UUID.randomUUID().toString(), "baseRevision" to base,
                "restoredIds" to JSONArray(), "backup" to backup)
            save(state.copy().put("pending", pending))
        } catch (error: Throwable) { vault.entities.clear(); vault.entities.addAll(previous); throw error }
    }

    @Synchronized fun upload() {
        require(role() == "manager") { "当前设备只有只读权限" }
        val pending = state.getJSONObject("pending").copy()
        val hash = digest(pending)
        val token = freshAccess()
        val auth = session()
        val challenge = JSONObject(api.request("/v1/challenges", obj("operation" to "sync.commit", "requestHash" to hash), token))
        val proof = checkedProof(challenge, "sync.commit", hash, auth.getJSONObject("user").getString("id"),
            auth.getJSONObject("device").getString("id"), token.split('.')[1])
        val response = try { JSONObject(api.request("/v1/vault/commit", obj("payload" to pending, "proof" to proof), token)) }
        catch (error: ApiFailure) { if (error.status == 409) throw SyncConflict(); throw error }
        val snapshot = VaultCrypto.inspect(pending.getString("backup"))
        require(response.getString("operationId") == pending.getString("operationId") &&
            number(response, "revision") == snapshot.getLong("revision") && response.getString("digest") == snapshot.getString("digest"))
        save(state.copy().put("backup", pending.getString("backup")).apply { remove("pending") })
        unlocked?.snapshot = snapshot
    }

    @Synchronized fun logout() {
        api.request("/v1/auth/logout", JSONObject(), freshAccess())
        save(state.copy().apply { remove("auth"); remove("refreshUncertain") })
        lock()
    }

    @Synchronized fun dispose() { lock() }
}
