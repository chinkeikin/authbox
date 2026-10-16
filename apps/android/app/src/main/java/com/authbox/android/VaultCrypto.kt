package com.authbox.android

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.util.encoders.Base32
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class VaultEntity(val id: String, val type: String, var payload: JSONObject)
class OpenVault internal constructor(val vmk: ByteArray, var snapshot: JSONObject, val entities: MutableList<VaultEntity>) : AutoCloseable {
    override fun close() { vmk.fill(0); entities.clear() }
}
data class NewVault(val vault: OpenVault, val recoveryKey: String)

object VaultCrypto {
    private val entityTypes = setOf("account", "group", "tag", "settings")
    private fun aad(value: JSONObject, wrap: Boolean): ByteArray = canonical(if (wrap)
        array("AuthBox", 1, "vmk-wrap", value.getString("vaultId"), value.getLong("keyGeneration"), value.getString("purpose"), value.getJSONObject("kdf"))
        else array("AuthBox", 1, "entity", value.getString("vaultId"), value.getString("id"), value.getString("type"), value.getLong("revision"), value.getLong("keyGeneration")))
        .toByteArray(Charsets.UTF_8)

    private fun crypt(key: ByteArray, value: JSONObject, wrap: Boolean, plaintext: ByteArray? = null): ByteArray {
        require(key.size == 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(if (plaintext == null) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"), GCMParameterSpec(128, binary(value, "nonce", 12)))
        cipher.updateAAD(aad(value, wrap))
        return cipher.doFinal(plaintext ?: unb64(value.getString("ciphertext")))
    }

    private fun validateWrap(value: JSONObject, purpose: String, vaultId: String, generation: Long) {
        fields(value, "version", "vaultId", "keyGeneration", "purpose", "kdf", "nonce", "ciphertext")
        require(number(value, "version") == 1L && value.getString("vaultId") == vaultId && number(value, "keyGeneration") == generation)
        require(value.getString("purpose") == purpose)
        binary(value, "nonce", 12); binary(value, "ciphertext", 48)
        val kdf = value.getJSONObject("kdf")
        if (purpose == "password") {
            fields(kdf, "name", "version", "memoryKiB", "iterations", "parallelism", "hashBytes", "salt")
            require(text(kdf, "name") == "argon2id" && number(kdf, "version") == 19L && number(kdf, "memoryKiB") == 65536L &&
                number(kdf, "iterations") == 3L && number(kdf, "parallelism") == 1L && number(kdf, "hashBytes") == 32L)
            binary(kdf, "salt", 16)
        } else {
            fields(kdf, "name", "info", "salt")
            require(text(kdf, "name") == "HKDF-SHA256" && text(kdf, "info") == "AuthBox/v1/vmk-recovery")
            binary(kdf, "salt", 32)
        }
    }

    private fun derive(wrap: JSONObject, secret: String): ByteArray {
        val kdf = wrap.getJSONObject("kdf")
        val key = ByteArray(32)
        if (wrap.getString("purpose") == "password") {
            canonical(secret)
            val bytes = secret.toByteArray(Charsets.UTF_8)
            require(bytes.size in 8..1024)
            try {
                val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(19)
                    .withMemoryAsKB(65536).withIterations(3).withParallelism(1).withSalt(binary(kdf, "salt", 16)).build()
                try { Argon2BytesGenerator().apply { init(parameters) }.generateBytes(bytes, key) }
                finally { parameters.clear() }
            } finally { bytes.fill(0) }
        } else {
            require(secret.matches(Regex("ABR1-[A-Z2-7]{52}-[A-Za-z0-9_-]{8}")))
            val raw = Base32.decode(secret.substring(5, 57) + "====")
            try {
                require(raw.size == 32 && Base32.toBase32String(raw).trimEnd('=') == secret.substring(5, 57))
                require(sha(raw).take(8) == secret.substring(58))
                HKDFBytesGenerator(SHA256Digest()).apply {
                    init(HKDFParameters(raw, binary(kdf, "salt", 32), "AuthBox/v1/vmk-recovery".toByteArray()))
                }.generateBytes(key, 0, 32)
            } finally { raw.fill(0) }
        }
        return key
    }

    private fun validateRecord(record: JSONObject, vaultId: String, revision: Long, generation: Long) {
        fields(record, "version", "vaultId", "id", "type", "revision", "keyGeneration", "nonce", "ciphertext")
        require(number(record, "version") == 1L && text(record, "vaultId") == vaultId)
        uuid(text(record, "id"))
        require(number(record, "revision") <= revision && number(record, "keyGeneration") == generation)
        require(text(record, "type") in entityTypes + "manifest")
        binary(record, "nonce", 12)
        require(unb64(text(record, "ciphertext")).size in 18..65552)
    }

    fun inspect(encoded: String): JSONObject {
        require(encoded.toByteArray(Charsets.UTF_8).size <= 16 * 1024 * 1024)
        val backup = parseObject(encoded)
        fields(backup, "magic", "version", "vaultId", "revision", "keyGeneration", "createdAt", "appVersion", "wraps", "entities", "manifest", "digest")
        require(text(backup, "magic") == "AUTHBOX-BACKUP" && number(backup, "version") == 1L)
        val vaultId = text(backup, "vaultId"); uuid(vaultId)
        val revision = number(backup, "revision"); val generation = number(backup, "keyGeneration")
        timestamp(text(backup, "createdAt"))
        require(text(backup, "appVersion").length <= 64 && text(backup, "appVersion").matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.-]+)?")))
        val wraps = backup.getJSONObject("wraps"); fields(wraps, "password", "recovery")
        for (purpose in listOf("password", "recovery")) validateWrap(wraps.getJSONObject(purpose), purpose, vaultId, generation)
        val records = backup.getJSONArray("entities"); require(records.length() <= 10000)
        var previous = ""
        val nonces = mutableSetOf<String>()
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            validateRecord(record, vaultId, revision, generation)
            val id = record.getString("id")
            require(id > previous && record.getString("type") in entityTypes && nonces.add(record.getString("nonce")))
            previous = id
        }
        val manifest = backup.getJSONObject("manifest")
        validateRecord(manifest, vaultId, revision, generation)
        require(manifest.getString("id") == vaultId && manifest.getString("type") == "manifest" && manifest.getLong("revision") == revision)
        require(nonces.add(manifest.getString("nonce")))
        binary(backup, "digest", 32)
        val body = backup.copy().apply { remove("digest") }
        require(digest(body) == backup.getString("digest")) { "备份校验失败" }
        return backup
    }

    fun restore(encoded: String, secret: String, purpose: String = "password"): OpenVault {
        require(purpose in setOf("password", "recovery"))
        val backup = inspect(encoded)
        val wrap = backup.getJSONObject("wraps").getJSONObject(purpose)
        val wrappingKey = derive(wrap, secret)
        val vmk = try { crypt(wrappingKey, wrap, true) } finally { wrappingKey.fill(0) }
        try { return authenticate(backup, vmk) }
        finally { vmk.fill(0) }
    }

    fun authenticate(backup: JSONObject, vmk: ByteArray): OpenVault {
        inspect(canonical(backup))
        val body = backup.copy().apply { remove("manifest"); remove("digest") }
        val manifest = parseObject(decodeUtf8(crypt(vmk, backup.getJSONObject("manifest"), false)))
        fields(manifest, "digest", "entityCount")
        val records = backup.getJSONArray("entities")
        require(text(manifest, "digest") == digest(body) && number(manifest, "entityCount", 0) == records.length().toLong()) { "备份完整性验证失败" }
        val entities = (0 until records.length()).map { index ->
            val record = records.getJSONObject(index)
            VaultEntity(record.getString("id"), record.getString("type"), parseObject(decodeUtf8(crypt(vmk, record, false))))
        }.toMutableList()
        validateEntities(entities)
        return OpenVault(vmk.copyOf(), backup.copy(), entities)
    }

    private fun seal(vmk: ByteArray, vaultId: String, id: String, type: String, revision: Long, generation: Long, payload: JSONObject): JSONObject {
        val record = obj("version" to 1, "vaultId" to vaultId, "id" to id, "type" to type,
            "revision" to revision, "keyGeneration" to generation, "nonce" to b64(randomBytes(12)))
        record.put("ciphertext", b64(crypt(vmk, record, false, canonical(payload).toByteArray(Charsets.UTF_8))))
        return record
    }

    fun snapshot(vault: OpenVault, revision: Long): String {
        validateEntities(vault.entities)
        val previous = vault.snapshot
        val vaultId = previous.getString("vaultId")
        val generation = previous.getLong("keyGeneration")
        val old = previous.getJSONArray("entities")
        val records = JSONArray()
        for (entity in vault.entities.sortedBy { it.id }) {
            val existing = (0 until old.length()).map { old.getJSONObject(it) }.find { it.getString("id") == entity.id }
            val unchanged = existing != null && existing.getString("type") == entity.type &&
                decodeUtf8(crypt(vault.vmk, existing, false)) == canonical(entity.payload)
            records.put(if (unchanged) existing else seal(vault.vmk, vaultId, entity.id, entity.type, revision, generation, entity.payload))
        }
        val body = obj("magic" to "AUTHBOX-BACKUP", "version" to 1, "vaultId" to vaultId,
            "revision" to revision, "keyGeneration" to generation, "createdAt" to utc(), "appVersion" to "0.0.0",
            "wraps" to previous.getJSONObject("wraps"), "entities" to records)
        val manifest = obj("digest" to digest(body), "entityCount" to records.length())
        body.put("manifest", seal(vault.vmk, vaultId, vaultId, "manifest", revision, generation, manifest))
        body.put("digest", digest(body))
        return canonical(body).also { inspect(it) }
    }

    fun create(password: String): NewVault {
        canonical(password); require(password.toByteArray(Charsets.UTF_8).size in 8..1024)
        val raw = randomBytes(32)
        val recovery = "ABR1-" + Base32.toBase32String(raw).trimEnd('=') + "-" + sha(raw).take(8)
        raw.fill(0)
        val vmk = randomBytes(32); val id = UUID.randomUUID().toString()
        try {
            val wraps = JSONObject()
            for (purpose in listOf("password", "recovery")) {
                val kdf = if (purpose == "password") obj("name" to "argon2id", "version" to 19,
                    "memoryKiB" to 65536, "iterations" to 3, "parallelism" to 1, "hashBytes" to 32, "salt" to b64(randomBytes(16)))
                    else obj("name" to "HKDF-SHA256", "info" to "AuthBox/v1/vmk-recovery", "salt" to b64(randomBytes(32)))
                val wrap = obj("version" to 1, "vaultId" to id, "keyGeneration" to 1, "purpose" to purpose,
                    "kdf" to kdf, "nonce" to b64(randomBytes(12)))
                val key = derive(wrap, if (purpose == "password") password else recovery)
                try { wrap.put("ciphertext", b64(crypt(key, wrap, true, vmk))) } finally { key.fill(0) }
                wraps.put(purpose, wrap)
            }
            val vault = OpenVault(vmk.copyOf(), obj("vaultId" to id, "keyGeneration" to 1, "wraps" to wraps, "entities" to JSONArray()), mutableListOf())
            vault.snapshot = inspect(snapshot(vault, 1))
            return NewVault(vault, recovery)
        } finally { vmk.fill(0) }
    }

    fun validateEntities(entities: List<VaultEntity>) {
        require(entities.size <= 10000 && entities.map { it.id }.toSet().size == entities.size)
        require(entities.count { it.type == "settings" } <= 1)
        for (entity in entities) {
            uuid(entity.id)
            val p = entity.payload
            require(number(p, "schemaVersion") == 1L && canonical(p).toByteArray(Charsets.UTF_8).size <= 65536)
            when (entity.type) {
                "account" -> {
                    fields(p, "schemaVersion", "issuer", "name", "account", "secret", "algorithm", "digits", "period", "groupId", "tagIds", "favorite", "order", "notes", "deletedAt")
                    for (field in listOf("issuer", "name", "account")) businessText(p, field, 256)
                    require(listOf("issuer", "name", "account").any { p.getString(it).isNotBlank() })
                    businessText(p, "notes", 4096)
                    secretBytes(text(p, "secret")).fill(0)
                    require(text(p, "algorithm") in setOf("SHA1", "SHA256", "SHA512") && number(p, "digits") in setOf(6L, 8L) && number(p, "period") in 1..86400)
                    require(p.get("favorite") is Boolean)
                    number(p, "order", 0)
                    if (!p.isNull("groupId")) {
                        val group = text(p, "groupId"); uuid(group)
                        require(entities.any { it.id == group && it.type == "group" })
                    }
                    val tags = p.getJSONArray("tagIds"); require(tags.length() <= 64)
                    val seen = mutableSetOf<String>()
                    for (i in 0 until tags.length()) {
                        val tag = tags.get(i) as String; uuid(tag)
                        require(seen.add(tag) && entities.any { it.id == tag && it.type == "tag" })
                    }
                    if (!p.isNull("deletedAt")) timestamp(text(p, "deletedAt"))
                }
                "group", "tag" -> {
                    if (entity.type == "group") { fields(p, "schemaVersion", "name", "order"); number(p, "order", 0) }
                    else fields(p, "schemaVersion", "name")
                    businessText(p, "name", 256); require(text(p, "name").isNotBlank())
                }
                "settings" -> { fields(p, "schemaVersion", "defaultView"); require(text(p, "defaultView") in setOf("all", "favorites")) }
                else -> throw IllegalArgumentException("未知业务类型")
            }
        }
    }

    private fun businessText(value: JSONObject, field: String, maximum: Int) {
        val string = text(value, field); canonical(string)
        require(string.toByteArray(Charsets.UTF_8).size <= maximum && string.none { it < ' ' || it == '\u007f' })
    }

    private fun secretBytes(secret: String): ByteArray {
        require(secret.matches(Regex("[A-Z2-7]+")))
        val bytes = Base32.decode(secret + "=".repeat((8 - secret.length % 8) % 8))
        require(bytes.size in 10..128 && Base32.toBase32String(bytes).trimEnd('=') == secret)
        return bytes
    }

    fun totp(payload: JSONObject, millis: Long = System.currentTimeMillis()): String {
        require(millis >= 0)
        val algorithm = text(payload, "algorithm"); require(algorithm in setOf("SHA1", "SHA256", "SHA512"))
        val digits = number(payload, "digits").toInt(); require(digits == 6 || digits == 8)
        val period = number(payload, "period"); require(period in 1..86400)
        val secret = secretBytes(text(payload, "secret"))
        try {
            val mac = Mac.getInstance("Hmac$algorithm").apply { init(SecretKeySpec(secret, "Hmac$algorithm")) }
            val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(millis / 1000 / period).array())
            val offset = hash.last().toInt() and 15
            val code = ByteBuffer.wrap(hash, offset, 4).int and 0x7fffffff
            return (code % if (digits == 6) 1000000 else 100000000).toString().padStart(digits, '0')
        } finally { secret.fill(0) }
    }
}
