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

internal fun accountNamespace(origin: String, username: String): String = "authbox.account." + MessageDigest.getInstance("SHA-256")
    .digest("$origin/$username".toByteArray()).take(12).joinToString("") { "%02x".format(it) }

data class DeviceInfo(val id: String, val name: String, val role: String, val revoked: Boolean, val hasKey: Boolean)

internal fun accountRecoveryCodes(): List<String> = (1..8).map { "ABAC1-" + b64(randomBytes(16)) }
internal fun recoveryHashes(codes: List<String>): JSONArray {
    require(codes.size == 8 && codes.toSet().size == 8)
    return JSONArray().apply { codes.forEach {
        require(it.startsWith("ABAC1-") && unb64(it.removePrefix("ABAC1-")).size == 16)
        put(sha(("AuthBox/account-recovery/v1/" + it).toByteArray(Charsets.UTF_8)))
    } }
}

class AccountRepository(context: Context, val api: ApiClient, val username: String, testNamespace: String? = null) {
    init { require(username.matches(Regex("[a-z][a-z0-9_]{2,31}"))) }
    private val namespace = testNamespace ?: accountNamespace(api.origin, username)
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
    @Synchronized fun role(): String? = state.optJSONObject("auth")?.getJSONObject("device")?.getString("role")
    @Synchronized fun deviceId(): String = state.getString("identityId")
    @Synchronized fun revision(): Long? = state.optString("backup").takeIf { it.isNotEmpty() }?.let { VaultCrypto.inspect(it).getLong("revision") }
    @Synchronized fun hasPending(): Boolean = state.has("pending")
    @Synchronized fun hasDraft(): Boolean = state.has("draft")
    @Synchronized fun hasLocalChanges(): Boolean = hasPending() || hasDraft()
    @Synchronized fun isUnlocked(): Boolean = unlocked != null
    @Synchronized fun lock() { unlocked?.close(); unlocked = null }
    @Synchronized fun entities(): List<VaultEntity> = requireNotNull(unlocked) { "请先解锁保险库" }.entities.map { it.copy(payload = it.payload.copy()) }

    private fun checkLoginPassword(password: String) {
        canonical(password); require(password.toByteArray(Charsets.UTF_8).size in 8..256)
    }

    private fun acceptAuth(encoded: String, expectedDeviceId: String = deviceId()) {
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
        require(device.getString("role") != "manager" || device.getString("id") == expectedDeviceId)
        val next = state.copy().put("auth", auth).put("identityId", device.getString("id"))
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

    private fun publicProof(operation: String, context: JSONObject, invite: String? = null, deviceId: String = deviceId()): JSONObject {
        val hash = digest(context)
        val challenge = JSONObject(api.request("/v1/auth/challenge", obj("operation" to operation, "username" to username,
            "deviceId" to deviceId, "requestHash" to hash, "invite" to invite)))
        return checkedProof(challenge, operation, hash, username, deviceId, "")
    }

    @Synchronized fun login(password: String, existingDevice: Boolean = true) {
        checkLoginPassword(password)
        lock()
        if (state.optBoolean("refreshUncertain")) save(state.copy().apply { remove("auth"); remove("refreshUncertain") })
        val identity = state.optString("recoveryDeviceId").takeIf { it.isNotEmpty() } ?: deviceId()
        val encoded = if (existingDevice) {
            val context = obj("username" to username, "deviceId" to identity)
            val proof = publicProof("auth.device-login", context, deviceId = identity)
            api.request("/v1/auth/device-login", context.put("password", password).put("proof", proof))
        } else api.request("/v1/auth/login", obj("username" to username, "password" to password,
            "deviceName" to "AuthBox Android", "publicKey" to box.publicKey()))
        acceptAuth(encoded, identity)
        save(state.copy().apply { remove("recoveryDeviceId") })
    }

    @Synchronized fun prepareRegistration(vaultPassword: String): Pair<String, List<String>> {
        require(!state.has("auth"))
        val created = VaultCrypto.create(vaultPassword)
        try {
            val codes = accountRecoveryCodes()
            val hashes = recoveryHashes(codes)
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

    private fun managerAccess(): String {
        require(role() == "manager") { "当前设备没有管理权限" }
        return freshAccess()
    }

    private fun managementProof(operation: String, payload: JSONObject, token: String): JSONObject {
        val hash = digest(payload)
        val challenge = JSONObject(api.request("/v1/challenges", obj("operation" to operation, "requestHash" to hash), token))
        return sessionProof(challenge, operation, hash, token)
    }

    private fun sessionProof(challenge: JSONObject, operation: String, hash: String, token: String): JSONObject {
        val auth = session()
        return checkedProof(challenge, operation, hash, auth.getJSONObject("user").getString("id"),
            auth.getJSONObject("device").getString("id"), token.split('.')[1])
    }

    @Synchronized fun devices(): List<DeviceInfo> {
        val response = JSONObject(api.request("/v1/devices", access = managerAccess()))
        fields(response, "devices")
        val devices = response.getJSONArray("devices")
        require(devices.length() <= 200)
        return (0 until devices.length()).map { index ->
            val entry = devices.getJSONObject(index)
            fields(entry, "id", "name", "role", "revoked", "hasKey")
            val id = text(entry, "id"); uuid(id)
            val role = text(entry, "role"); require(role in setOf("reader", "manager"))
            val revoked = number(entry, "revoked", 0); val key = number(entry, "hasKey", 0)
            require(revoked in 0L..1L && key in 0L..1L)
            DeviceInfo(id, text(entry, "name"), role, revoked == 1L, key == 1L)
        }.also { require(it.map { device -> device.id }.toSet().size == it.size) }
    }

    @Synchronized fun updateDevice(id: String, approve: Boolean) {
        uuid(id)
        val token = managerAccess()
        val payload = obj("deviceId" to id)
        val operation = if (approve) "device.approve" else "device.revoke"
        val proof = managementProof(operation, payload, token)
        val response = JSONObject(api.request(if (approve) "/v1/devices/approve" else "/v1/devices/revoke", obj("payload" to payload, "proof" to proof), token))
        require(response.getBoolean("updated"))
        if (!approve && id == deviceId()) {
            save(state.copy().apply { remove("auth"); remove("refreshUncertain") })
            lock()
        }
    }

    @Synchronized fun changePassword(currentPassword: String, newPassword: String) {
        checkLoginPassword(currentPassword); checkLoginPassword(newPassword)
        val token = managerAccess()
        val challenge = JSONObject(api.request("/v1/account/password/prepare", obj("currentPassword" to currentPassword, "newPassword" to newPassword), token))
        val hash = JSONArray(challenge.getString("payload")).getString(9)
        require(unb64(hash).size == 32)
        val proof = sessionProof(challenge, "password.change", hash, token)
        save(state.copy().put("refreshUncertain", true))
        try {
            acceptAuth(api.request("/v1/account/password/confirm", obj("proof" to proof), token))
            save(state.copy().apply { remove("refreshUncertain") })
        } catch (error: Exception) {
            lock()
            throw IllegalStateException("密码修改结果未确认，请保留新旧密码并重新登录；本地数据已保留", error)
        }
    }

    @Synchronized fun rotateRecoveryCodes(codes: List<String>, saved: Boolean) {
        require(saved) { "请先离线保存新恢复码" }
        val payload = obj("hashes" to recoveryHashes(codes))
        val token = managerAccess()
        val proof = managementProof("recovery.rotate", payload, token)
        try {
            require(JSONObject(api.request("/v1/account/recovery/rotate", obj("payload" to payload, "proof" to proof), token)).getBoolean("rotated"))
        } catch (error: Exception) {
            throw IllegalStateException("恢复码轮换未确认，请保留新旧恢复码；联网后重新生成一组并确认轮换", error)
        }
    }

    @Synchronized fun recover(code: String, password: String, confirmed: Boolean) {
        require(confirmed) { "请确认恢复将撤销全部旧设备和会话" }
        checkLoginPassword(password)
        require(code.startsWith("ABAC1-") && unb64(code.removePrefix("ABAC1-")).size == 16) { "账号恢复码格式不正确" }
        lock()
        val identity = UUID.randomUUID().toString()
        save(state.copy().put("recoveryDeviceId", identity))
        val context = obj("username" to username, "device" to obj("id" to identity, "name" to "AuthBox Android recovered", "publicKey" to box.publicKey()))
        var submitted = false
        try {
            val proof = publicProof("auth.recover", context, deviceId = identity)
            submitted = true
            acceptAuth(api.request("/v1/auth/recover", context.put("code", code).put("password", password).put("proof", proof)), identity)
            save(state.copy().apply { remove("recoveryDeviceId"); remove("refreshUncertain") })
        } catch (error: ApiFailure) {
            if (submitted && error.status >= 500) {
                save(state.copy().put("refreshUncertain", true))
                throw IllegalStateException("恢复结果未确认，请先使用新密码与本机已有设备身份登录；不要反复消耗恢复码", error)
            }
            save(state.copy().apply { remove("recoveryDeviceId") })
            throw error
        } catch (error: Exception) {
            if (!submitted) {
                save(state.copy().apply { remove("recoveryDeviceId") })
                throw error
            }
            save(state.copy().put("refreshUncertain", true))
            throw IllegalStateException("恢复结果未确认，请先使用新密码与本机已有设备身份登录；不要反复消耗恢复码", error)
        }
    }

    @Synchronized fun download(vaultPassword: String, purpose: String = "password") {
        require(!hasLocalChanges()) { "本地存在待上传内容，请先完成上传或处理冲突" }
        val encoded = api.request("/v1/vault/snapshot", access = freshAccess())
        val opened = VaultCrypto.restore(encoded, vaultPassword, purpose)
        try {
            val prior = state.optString("backup").takeIf { it.isNotEmpty() }?.let { VaultCrypto.inspect(it) }
            require(prior == null || (prior.getString("vaultId") == opened.snapshot.getString("vaultId") &&
                opened.snapshot.getLong("revision") >= prior.getLong("revision") &&
                opened.snapshot.getLong("keyGeneration") >= prior.getLong("keyGeneration") &&
                (opened.snapshot.getLong("revision") != prior.getLong("revision") || opened.snapshot.getString("digest") == prior.getString("digest")))) {
                "云端版本回退或保险库身份变化"
            }
            save(state.copy().put("backup", encoded))
            lock(); unlocked = opened
        } catch (error: Throwable) { opened.close(); throw error }
    }

    @Synchronized fun unlock(vaultPassword: String, purpose: String = "password") {
        val encoded = state.optString("draft").takeIf { it.isNotEmpty() }
            ?: state.optJSONObject("pending")?.getString("backup") ?: state.getString("backup")
        val opened = VaultCrypto.restore(encoded, vaultPassword, purpose)
        lock(); unlocked = opened
    }

    @Synchronized fun exportBackup(): String {
        require(role() == "manager") { "当前设备没有导出权限" }
        val vault = requireNotNull(unlocked) { "请先解锁保险库" }
        val encoded = state.optString("draft").takeIf { it.isNotEmpty() }
            ?: state.optJSONObject("pending")?.getString("backup") ?: state.getString("backup")
        VaultCrypto.authenticate(VaultCrypto.inspect(encoded), vault.vmk).close()
        return encoded
    }

    @Synchronized fun rewrap(secret: String, purpose: String, saved: Boolean = true) {
        require(role() == "manager") { "当前设备没有管理权限" }
        require(!hasPending()) { "已有上传请求待确认，请先同步或处理冲突" }
        require(purpose != "recovery" || saved) { "请先离线保存新保险库恢复密钥" }
        val vault = requireNotNull(unlocked) { "请先解锁保险库" }
        val encoded = VaultCrypto.rewrap(vault, secret, purpose, requireNotNull(revision()) + 1)
        require(encoded.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "保险库超过在线同步大小限制" }
        save(state.copy().put("draft", encoded))
        vault.snapshot = VaultCrypto.inspect(encoded)
    }

    @Synchronized fun stage(entities: List<VaultEntity>) {
        require(role() == "manager") { "当前设备只有只读权限" }
        require(!hasLocalChanges()) { "已有待上传内容" }
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

    @Synchronized fun edit(operation: (MutableList<VaultEntity>) -> Unit) {
        require(role() == "manager") { "当前设备只有只读权限" }
        require(!hasPending()) { "已有上传请求待确认，请先同步或处理冲突" }
        val vault = requireNotNull(unlocked) { "请先解锁保险库" }
        val next = vault.entities.map { it.copy(payload = it.payload.copy()) }.toMutableList()
        operation(next)
        VaultCrypto.validateEntities(next)
        val candidate = OpenVault(vault.vmk.copyOf(), vault.snapshot.copy(), next)
        try {
            val backup = VaultCrypto.snapshot(candidate, requireNotNull(revision()) + 1)
            require(backup.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "保险库超过在线同步大小限制" }
            save(state.copy().put("draft", backup))
            vault.entities.clear(); vault.entities.addAll(next.map { it.copy(payload = it.payload.copy()) })
            vault.snapshot = VaultCrypto.inspect(backup)
        } finally { candidate.close() }
    }

    @Synchronized fun upload() {
        require(role() == "manager") { "当前设备只有只读权限" }
        if (!hasPending() && hasDraft()) {
            val payload = obj("operationId" to UUID.randomUUID().toString(), "baseRevision" to requireNotNull(revision()),
                "restoredIds" to JSONArray(), "backup" to state.getString("draft"))
            save(state.copy().put("pending", payload).apply { remove("draft") })
        }
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
