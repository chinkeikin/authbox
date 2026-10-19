package com.authbox.android

import com.google.protobuf.CodedInputStream
import org.bouncycastle.util.encoders.Base32
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64

data class ImportEntry(val label: String, val payload: JSONObject?, val reason: String = "")
data class ImportChunk(val entries: List<ImportEntry>, val batchId: Int? = null, val size: Int = 1, val index: Int = 0, val fingerprint: String)
data class ImportRow(val label: String, val payload: JSONObject?, val reason: String, val duplicate: Boolean)
data class ImportPreview(val rows: List<ImportRow> = emptyList(), val received: Int = 0, val expected: Int = 0, val complete: Boolean = false)

object OtpImport {
    private val parameters = setOf("secret", "issuer", "algorithm", "digits", "period")
    private fun decoded(value: String, form: Boolean): String {
        val output = ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            val c = value[index]
            if (c == '%') {
                require(index + 2 < value.length)
                output.write(value.substring(index + 1, index + 3).toInt(16)); index += 3
            } else {
                val count = if (c.isHighSurrogate()) 2 else 1
                val part = if (c == '+' && form) " " else value.substring(index, index + count)
                output.write(part.toByteArray(Charsets.UTF_8)); index += count
            }
        }
        return decodeUtf8(output.toByteArray())
    }

    private fun query(uri: URI): Map<String, String> {
        val values = linkedMapOf<String, String>()
        for (pair in (uri.rawQuery ?: "").split('&')) {
            if (pair.isEmpty()) continue
            val parts = pair.split('=', limit = 2)
            require(parts.size == 2)
            val key = decoded(parts[0], true)
            require(!values.containsKey(key)) { "二维码包含重复参数" }
            values[key] = decoded(parts[1], true)
        }
        return values
    }

    fun parse(encoded: String): ImportChunk {
        require(encoded.length in 1..24000 && encoded.none { it.isWhitespace() || it < ' ' || it == '\u007f' }) { "二维码格式或大小不受支持" }
        canonical(encoded)
        val uri = URI(encoded)
        require(uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null)
        return when (uri.scheme) {
            "otpauth" -> {
                require(encoded.length <= 4096 && uri.host == "totp" && uri.rawPath.startsWith('/')) { "仅支持 TOTP，不支持 HOTP 或外部链接" }
                val values = query(uri); require(values.keys.all { it in parameters }) { "二维码包含不支持的参数" }
                val label = decoded(uri.rawPath.substring(1), false)
                val separator = label.indexOf(':')
                val labelIssuer = if (separator < 0) "" else label.substring(0, separator)
                val account = if (separator < 0) label else label.substring(separator + 1).trimStart()
                require(account.isNotBlank() && !account.contains(':') && (separator < 0 || labelIssuer.isNotBlank()))
                val issuer = values["issuer"] ?: labelIssuer
                require(labelIssuer.isEmpty() || issuer == labelIssuer) { "二维码平台名称不一致" }
                fun integer(name: String, default: Int): Int = values[name]?.let { require(it.matches(Regex("[1-9][0-9]{0,4}"))); it.toInt() } ?: default
                val payload = VaultEditor.account(issuer, "", account, requireNotNull(values["secret"]), values["algorithm"] ?: "SHA1", integer("digits", 6), integer("period", 30))
                validate(payload)
                ImportChunk(listOf(ImportEntry(listOf(issuer, account).filter { it.isNotEmpty() }.joinToString(" · "), payload)), fingerprint = digest(payload))
            }
            "otpauth-migration" -> {
                require(uri.host == "offline" && uri.rawPath in setOf("", "/")) { "不是离线迁移二维码" }
                val values = query(uri); require(values.keys == setOf("data"))
                val data = requireNotNull(values["data"])
                require(data.matches(Regex("[A-Za-z0-9+/]+={0,2}")))
                val bytes = Base64.getDecoder().decode(data)
                require(bytes.size in 1..16384 && Base64.getEncoder().encodeToString(bytes).trimEnd('=') == data.trimEnd('='))
                migration(bytes)
            }
            else -> throw IllegalArgumentException("仅支持标准 TOTP 和 Google 离线迁移二维码，不会打开外部链接")
        }
    }

    private fun input(bytes: ByteArray): CodedInputStream = CodedInputStream.newInstance(bytes).apply { setSizeLimit(16384); setRecursionLimit(8) }
    private fun migration(bytes: ByteArray): ImportChunk {
        val source = input(bytes)
        val entries = mutableListOf<ImportEntry>()
        val seen = mutableSetOf<Int>()
        var version = 0; var size = 1; var index = 0; var batch = 0
        while (!source.isAtEnd) {
            val tag = source.readTag(); val field = tag ushr 3
            require(field in 1..5 && (tag and 7) == if (field == 1) 2 else 0) { "不支持的迁移字段" }
            if (field == 1) {
                require(entries.size < 1000)
                entries.add(entry(source.readByteArray()))
            } else {
                require(seen.add(field)) { "迁移元数据重复" }
                val number = source.readInt32()
                when (field) { 2 -> version = number; 3 -> size = number; 4 -> index = number; 5 -> batch = number }
            }
        }
        require(version == 1 && size in 1..100 && index in 0 until size && entries.isNotEmpty()) { "迁移版本、批次或条目数不受支持" }
        require(size == 1 || 5 in seen) { "迁移批次缺少身份" }
        return ImportChunk(entries, batch, size, index, sha(bytes))
    }

    private fun entry(bytes: ByteArray): ImportEntry {
        val source = input(bytes); val seen = mutableSetOf<Int>()
        var secret = ByteArray(0); var name = ""; var issuer = ""; var algorithm = 0; var digits = 0; var type = 0; var counter = 0L
        try {
            while (!source.isAtEnd) {
                val tag = source.readTag(); val field = tag ushr 3
                require(field in 1..8 && seen.add(field) && (tag and 7) == if (field in setOf(1, 2, 3, 8)) 2 else 0)
                when (field) {
                    1 -> secret = source.readByteArray()
                    2 -> name = source.readStringRequireUtf8()
                    3 -> issuer = source.readStringRequireUtf8()
                    4 -> algorithm = source.readEnum()
                    5 -> digits = source.readEnum()
                    6 -> type = source.readEnum()
                    7 -> counter = source.readInt64()
                    8 -> require(source.readByteArray().size <= 256)
                }
            }
            val label = (if (issuer.isNotBlank()) "$issuer · $name" else name).take(256)
            val reason = when {
                type != 2 -> if (type == 1) "HOTP 不受支持" else "未声明或未知的验证类型"
                algorithm !in 0..3 -> "不支持的哈希算法"
                digits !in 0..2 -> "不支持的验证码位数"
                counter != 0L -> "TOTP 条目包含非零计数器"
                secret.size !in 10..128 -> "密钥长度不受支持"
                else -> ""
            }
            if (reason.isNotEmpty()) return ImportEntry(label, null, reason)
            val colon = name.indexOf(':')
            var account = name
            if (colon >= 0 && (issuer.isEmpty() || issuer == name.substring(0, colon))) {
                if (issuer.isEmpty()) issuer = name.substring(0, colon)
                account = name.substring(colon + 1).trimStart()
            }
            val payload = VaultEditor.account(issuer, "", account, Base32.toBase32String(secret).trimEnd('='),
                when (algorithm) { 2 -> "SHA256"; 3 -> "SHA512"; else -> "SHA1" }, if (digits == 2) 8 else 6)
            return try { validate(payload); ImportEntry(label, payload) }
            catch (_: Exception) { ImportEntry(label, null, "名称或参数不符合账号规则") }
        } finally { secret.fill(0) }
    }

    private fun validate(payload: JSONObject) = VaultCrypto.validateEntities(listOf(VaultEntity("10000000-0000-4000-8000-000000000001", "account", payload)))
    fun identity(payload: JSONObject): String = digest(obj("secret" to payload.getString("secret"), "algorithm" to payload.getString("algorithm"),
        "digits" to payload.getInt("digits"), "period" to payload.getInt("period")))
}

class ImportSession {
    private val chunks = linkedMapOf<Int, ImportChunk>()
    private var migration = false
    fun add(chunk: ImportChunk) {
        if (chunks.isNotEmpty()) {
            val first = chunks.values.first()
            require((chunk.batchId != null) == migration) { "请先确认或取消当前导入，再导入其他类型二维码" }
            if (migration) require(chunk.batchId == first.batchId && chunk.size == first.size) { "二维码属于不同迁移批次" }
        } else migration = chunk.batchId != null
        val index = if (migration) chunk.index else chunks.values.indexOfFirst { it.fingerprint == chunk.fingerprint }.let { if (it < 0) chunks.size else it }
        val previous = chunks[index]
        require(chunks.size < 100 || previous != null) { "本次导入最多 100 张二维码" }
        require(previous == null || previous.fingerprint == chunk.fingerprint) { "同一批次位置的二维码内容冲突" }
        require(chunks.values.sumOf { it.entries.size } - (previous?.entries?.size ?: 0) + chunk.entries.size <= 1000) { "本次导入最多 1000 条" }
        chunks[index] = chunk
    }
    fun addAll(values: List<ImportChunk>) {
        val previous = LinkedHashMap(chunks); val mode = migration
        try { values.forEach { add(it) } }
        catch (error: Exception) { chunks.clear(); chunks.putAll(previous); migration = mode; throw error }
    }
    fun preview(entities: List<VaultEntity>): ImportPreview {
        if (chunks.isEmpty()) return ImportPreview()
        val seen = entities.filter { it.type == "account" }.map { OtpImport.identity(it.payload) }.toMutableSet()
        val rows = chunks.toSortedMap().values.flatMap { it.entries }.map { entry ->
            val duplicate = entry.payload?.let { !seen.add(OtpImport.identity(it)) } ?: false
            ImportRow(entry.label, entry.payload?.copy(), entry.reason, duplicate)
        }
        val expected = if (migration) chunks.values.first().size else chunks.size
        return ImportPreview(rows, chunks.size, expected, chunks.size == expected)
    }
    fun apply(entities: MutableList<VaultEntity>, keepDuplicates: Boolean, skipUnsupported: Boolean) {
        val preview = preview(entities)
        require(preview.complete) { "迁移批次尚未收齐" }
        require(skipUnsupported || preview.rows.all { it.payload != null }) { "请确认跳过不支持的条目" }
        for (row in preview.rows) {
            val payload = row.payload ?: continue
            if (!row.duplicate || keepDuplicates) VaultEditor.saveAccount(entities, null, payload)
        }
    }
}
