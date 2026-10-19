package com.authbox.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object VaultEditor {
    fun account(issuer: String, name: String, account: String, secret: String, algorithm: String = "SHA1",
        digits: Int = 6, period: Int = 30, groupId: String? = null, tagIds: List<String> = emptyList(),
        favorite: Boolean = false, notes: String = "", order: Long = 0): JSONObject = obj(
        "schemaVersion" to 1, "issuer" to issuer, "name" to name, "account" to account,
        "secret" to normalizeSecret(secret), "algorithm" to algorithm, "digits" to digits, "period" to period,
        "groupId" to groupId, "tagIds" to JSONArray(tagIds), "favorite" to favorite, "notes" to notes,
        "order" to order, "deletedAt" to null)

    fun normalizeSecret(value: String): String {
        require(value.length <= 208 && value.matches(Regex("[A-Za-z2-7]+={0,6}"))) { "密钥必须是有效的 Base32" }
        val upper = value.uppercase(java.util.Locale.ROOT)
        val raw = upper.trimEnd('=')
        val bytes = org.bouncycastle.util.encoders.Base32.decode(raw + "=".repeat((8 - raw.length % 8) % 8))
        try {
            require(bytes.size in 10..128 && org.bouncycastle.util.encoders.Base32.toBase32String(bytes).trimEnd('=') == raw)
            require(!upper.contains('=') || upper == raw.padEnd((raw.length + 7) / 8 * 8, '='))
            return raw
        } finally { bytes.fill(0) }
    }

    fun saveAccount(entities: MutableList<VaultEntity>, id: String?, payload: JSONObject, confirmKeyChange: Boolean = false): String {
        val previous = id?.let { key -> entities.single { it.id == key && it.type == "account" } }
        require(previous == null || previous.payload.isNull("deletedAt")) { "请先从回收站恢复账号" }
        if (previous != null) {
            val changed = listOf("secret", "algorithm", "digits", "period").any { previous.payload.get(it) != payload.get(it) }
            require(!changed || confirmKeyChange) { "更换密钥或算法参数需要确认" }
        }
        val identity = id ?: UUID.randomUUID().toString()
        val next = payload.copy()
        next.put("deletedAt", JSONObject.NULL)
        if (previous == null) {
            next.put("order", nextOrder(entities, "account", group(next)))
            entities.add(VaultEntity(identity, "account", next))
        } else entities[entities.indexOf(previous)] = VaultEntity(identity, "account", next)
        return identity
    }

    fun saveCategory(entities: MutableList<VaultEntity>, type: String, id: String?, name: String): String {
        require(type in setOf("group", "tag"))
        val identity = id ?: UUID.randomUUID().toString()
        val previous = id?.let { key -> entities.single { it.id == key && it.type == type } }
        val payload = obj("schemaVersion" to 1, "name" to name)
        if (type == "group") payload.put("order", previous?.payload?.getLong("order") ?: nextOrder(entities, type))
        val entry = VaultEntity(identity, type, payload)
        if (previous == null) entities.add(entry) else entities[entities.indexOf(previous)] = entry
        return identity
    }

    fun deleteCategory(entities: MutableList<VaultEntity>, id: String) {
        val category = entities.single { it.id == id && it.type in setOf("group", "tag") }
        for (entry in entities.filter { it.type == "account" }) {
            val p = entry.payload
            if (category.type == "group" && p.optString("groupId") == id) p.put("groupId", JSONObject.NULL)
            if (category.type == "tag") p.put("tagIds", JSONArray(tags(p).filter { it != id }))
        }
        entities.remove(category)
    }

    fun bulk(entities: MutableList<VaultEntity>, ids: Set<String>, groupId: String? = null,
        move: Boolean = false, tagIds: List<String>? = null, favorite: Boolean? = null, trash: Boolean? = null) {
        require(ids.isNotEmpty())
        val targets = entities.filter { it.id in ids && it.type == "account" }
        require(targets.size == ids.size)
        val time = utc()
        for (entry in targets) {
            val p = entry.payload
            if (move) { p.put("groupId", groupId ?: JSONObject.NULL); p.put("order", nextOrder(entities, "account", groupId ?: "")) }
            if (tagIds != null) p.put("tagIds", JSONArray(tagIds))
            if (favorite != null) p.put("favorite", favorite)
            if (trash != null) p.put("deletedAt", if (trash) time else JSONObject.NULL)
        }
    }

    fun move(entities: MutableList<VaultEntity>, id: String, offset: Int) {
        require(offset == -1 || offset == 1)
        val entry = entities.single { it.id == id }
        require(entry.type in setOf("account", "group"))
        val siblings = entities.filter { it.type == entry.type && (entry.type == "group" ||
            (group(it.payload) == group(entry.payload) && it.payload.isNull("deletedAt"))) }
            .sortedWith(compareBy({ it.payload.getLong("order") }, { it.id })).toMutableList()
        val index = siblings.indexOf(entry); require(index >= 0)
        val destination = index + offset
        if (destination !in siblings.indices) return
        java.util.Collections.swap(siblings, index, destination)
        siblings.forEachIndexed { order, sibling -> sibling.payload.put("order", order) }
    }

    fun tags(payload: JSONObject): List<String> = payload.getJSONArray("tagIds").let { array -> (0 until array.length()).map { array.getString(it) } }
    private fun group(payload: JSONObject): String = if (payload.isNull("groupId")) "" else payload.getString("groupId")
    private fun nextOrder(entities: List<VaultEntity>, type: String, group: String = ""): Long = entities.filter {
        it.type == type && (type != "account" || group(it.payload) == group)
    }.maxOfOrNull { it.payload.getLong("order") }?.let { Math.addExact(it, 1) } ?: 0
}
