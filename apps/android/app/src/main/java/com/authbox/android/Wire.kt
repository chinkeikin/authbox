package com.authbox.android

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal fun obj(vararg values: Pair<String, Any?>): JSONObject = JSONObject().apply {
    values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}
internal fun array(vararg values: Any?): JSONArray = JSONArray().apply { values.forEach { put(it ?: JSONObject.NULL) } }
internal fun JSONObject.copy(): JSONObject = JSONObject(canonical(this))
internal fun fields(value: JSONObject, vararg names: String) {
    require(value.keys().asSequence().toSet() == names.toSet()) { "字段不符合协议" }
}
internal fun number(value: JSONObject, name: String, minimum: Long = 1): Long {
    val raw = value.get(name)
    require(raw is Int || raw is Long)
    return (raw as Number).toLong().also { require(it in minimum..9007199254740991L) }
}
internal fun text(value: JSONObject, name: String): String = (value.get(name) as? String)
    ?: throw IllegalArgumentException("字符串字段不符合协议")
internal fun uuid(value: String) { require(value.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}"))) }
internal fun randomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }
internal fun sha(bytes: ByteArray): String = b64(MessageDigest.getInstance("SHA-256").digest(bytes))
internal fun digest(value: Any): String = sha(canonical(value).toByteArray(Charsets.UTF_8))
internal fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
internal fun binary(value: JSONObject, name: String, size: Int): ByteArray = unb64(text(value, name)).also { require(it.size == size) }
internal fun timestamp(value: String) {
    require(value.matches(Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z")))
    require(utc(Instant.parse(value)) == value)
}
internal fun utc(time: Instant = Instant.now()): String = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
    .withZone(ZoneOffset.UTC).format(time)

internal fun canonical(value: Any?, depth: Int = 0): String {
    require(depth <= 32)
    return when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") {
            canonical(it, depth + 1) + ":" + canonical(value.get(it), depth + 1)
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it), depth + 1) }
        is String -> buildString {
            append('"')
            var index = 0
            while (index < value.length) {
                val c = value[index++]
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else {
                        require(!c.isLowSurrogate())
                        append(c)
                        if (c.isHighSurrogate()) {
                            require(index < value.length && value[index].isLowSurrogate())
                            append(value[index++])
                        }
                    }
                }
            }
            append('"')
        }
        is Boolean -> value.toString()
        is Int, is Long -> (value as Number).toLong().also { require(it in -9007199254740991L..9007199254740991L) }.toString()
        else -> throw IllegalArgumentException("JSON 类型不符合协议")
    }
}

internal fun parseObject(encoded: String): JSONObject = JSONObject(encoded).also { require(canonical(it) == encoded) { "需要规范 JSON" } }
