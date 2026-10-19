package com.authbox.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HistoryEntry(val revision: Long, val createdAt: Long, val bytes: Long, val digest: String)
data class HistoryPage(val revision: Long, val entries: List<HistoryEntry>, val nextBefore: Long?)
data class SavedVersionInfo(val id: String, val revision: Long, val createdAt: String, val reason: String)
data class ReviewDifference(val id: String, val type: String, val source: String?, val remote: String?, val changed: String)
data class ReviewView(val id: String, val kind: String, val sourceRevision: Long, val remoteRevision: Long,
    val differences: List<ReviewDifference>, val sourceCount: Int, val remoteCount: Int, val wrapsChanged: Boolean)

internal class OpenReview(val stored: JSONObject, val source: OpenVault, val remote: OpenVault) : AutoCloseable {
    fun view(): ReviewView {
        val left = source.entities.associateBy { it.id }
        val right = remote.entities.associateBy { it.id }
        val differences = (left.keys + right.keys).sorted().mapNotNull { id ->
            val a = left[id]; val b = right[id]
            if (a != null && b != null && a.type == b.type && canonical(a.payload) == canonical(b.payload)) null
            else {
                val labels = mapOf("secret" to "验证密钥", "algorithm" to "算法", "digits" to "位数", "period" to "周期",
                    "groupId" to "分组", "tagIds" to "标签", "favorite" to "收藏", "order" to "排序", "deletedAt" to "回收站",
                    "issuer" to "平台", "name" to "名称", "account" to "账号", "notes" to "备注", "defaultView" to "默认视图")
                val changed = if (a == null || b == null) "一侧不存在" else labels.filterKeys {
                    canonical(a.payload.opt(it)) != canonical(b.payload.opt(it))
                }.values.joinToString("、")
                ReviewDifference(id, a?.type ?: requireNotNull(b).type, a?.let { summary(it, source.entities) },
                    b?.let { summary(it, remote.entities) }, changed)
            }
        }
        return ReviewView(stored.getString("id"), stored.getString("kind"), source.snapshot.getLong("revision"), remote.snapshot.getLong("revision"),
            differences, source.entities.size, remote.entities.size,
            canonical(source.snapshot.getJSONObject("wraps")) != canonical(remote.snapshot.getJSONObject("wraps")))
    }
    private fun summary(entity: VaultEntity, all: List<VaultEntity>): String {
        val p = entity.payload
        fun name(id: String): String = all.find { it.id == id }?.payload?.optString("name") ?: "未分组"
        return when (entity.type) {
            "account" -> listOf(p.getString("issuer"), p.getString("name"), p.getString("account"),
                "分组：${name(p.optString("groupId"))}", "标签：${VaultEditor.tags(p).joinToString { name(it) }}",
                "${p.getString("algorithm")} / ${p.getLong("digits")} 位 / ${p.getLong("period")} 秒",
                if (p.getBoolean("favorite")) "已收藏" else "未收藏", if (p.isNull("deletedAt")) "正常" else "回收站",
                "排序：${p.getLong("order")}", "备注：${p.getString("notes")}").filter { it.isNotEmpty() }.joinToString(" · ")
            "group" -> "${p.getString("name")} · 排序 ${p.getLong("order")}"
            "tag" -> p.getString("name")
            else -> "默认视图：${p.getString("defaultView")}"
        }
    }
    override fun close() { source.close(); remote.close() }
}

internal fun preserveReviewVersions(state: JSONObject, values: List<Pair<String, String>>) {
    val versions = state.optJSONArray("savedVersions") ?: JSONArray()
    val digests = (0 until versions.length()).map { VaultCrypto.inspect(versions.getJSONObject(it).getString("backup")).getString("digest") }.toMutableSet()
    var bytes = (0 until versions.length()).sumOf { versions.getJSONObject(it).getString("backup").toByteArray(Charsets.UTF_8).size.toLong() }
    for ((reason, encoded) in values) {
        val header = VaultCrypto.inspect(encoded)
        if (!digests.add(header.getString("digest"))) continue
        bytes += encoded.toByteArray(Charsets.UTF_8).size
        require(versions.length() < 16 && bytes <= 16 * 1024 * 1024) { "保留副本空间已满，请先导出并明确删除旧保留副本" }
        versions.put(obj("id" to UUID.randomUUID().toString(), "createdAt" to utc(), "reason" to reason, "backup" to encoded))
    }
    state.put("savedVersions", versions)
}

internal fun reviewVersions(value: JSONObject): List<Pair<String, String>> = listOf(
    "处理前本地内容" to value.getString("local"), "比较时云端版本" to value.getString("remote"),
    (if (value.getString("kind") == "history") "选中的历史版本" else "冲突本地候选") to value.getString("source"))
