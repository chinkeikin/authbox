package com.authbox.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable internal fun SyncReviewScreen(state: ScreenState, fileMessage: String, back: () -> Unit, history: (Long?) -> Unit,
    prepare: (String, String, Long?, String?, String) -> Unit, confirm: (String, Boolean, Boolean) -> Unit,
    cancel: () -> Unit, upload: () -> Unit, export: (String) -> Unit, delete: (String, Boolean) -> Unit) {
    var preparing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var choice by remember { mutableStateOf<Boolean?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val enabled = state.role == "manager" && state.unlocked && !state.busy
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("冲突与历史", style = MaterialTheme.typography.headlineSmall)
            TextButton(back, enabled = !state.busy) { Text("返回账号") }
        }
        Text(state.message)
        if (fileMessage.contains("导出")) Text(fileMessage)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!state.unlocked) Text("比较明文已锁定，请返回账号离线解锁，再重新读取比较。密文候选和保留副本仍在本机。")
        LazyColumn(Modifier.weight(1f).testTag("sync-review"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("本机已确认云端版本：${state.revision ?: "—"}")
                if (state.conflict) Text("同步发生冲突，原请求和本地修改已保留。")
                if (state.hasReview) Text("已有比较密文；确认或取消后才能继续修改和同步。")
                OutlinedButton({ selected = null; preparing = true }, enabled = enabled && state.pending) { Text("比较本地与云端") }
                OutlinedButton({ history(null) }, enabled = enabled) { Text("读取云端历史") }
                if (state.hasReview) TextButton(cancel, enabled = enabled) { Text("取消比较并保留双方副本") }
                if (state.pending && !state.hasReview) Button(upload, enabled = enabled) { Text("提交已确认内容") }
            }
            state.review?.let { view ->
                item {
                    Text(if (view.kind == "history") "历史恢复预览" else "冲突双方预览", style = MaterialTheme.typography.titleMedium)
                    Text("${if (view.kind == "history") "选中历史" else "本地候选"}版本 ${view.sourceRevision} · ${view.sourceCount} 个实体；云端版本 ${view.remoteRevision} · ${view.remoteCount} 个实体")
                    Text("按整份业务内容选择，确认前会重新检查云端版本；本地、云端和选中历史均保留为本机加密副本。沿用最新云端密钥及密码封装，本地未同步的封装变更须在处理后重新设置。")
                    if (view.wrapsChanged) Text("两份内容的密钥封装不同，恢复不会重新启用旧密码或旧恢复密钥。")
                    Text("差异条目：${view.differences.size}；验证密钥仅提示是否变更，不显示密钥原文。")
                    Button({ choice = true }, enabled = enabled) { Text(if (view.kind == "history") "恢复为新版本" else "保留本地业务内容") }
                    if (view.kind == "conflict") OutlinedButton({ choice = false }, enabled = enabled) { Text("采用最新云端") }
                    Text("保留本地或恢复历史会先生成待同步内容，点击“提交已确认内容”后才更新云端。采用云端无需另行上传。")
                }
                items(view.differences, key = { "diff-${it.id}" }) { difference ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${difference.type} · ${difference.id}")
                        Text("变更：${difference.changed}")
                        Text("${if (view.kind == "history") "历史" else "本地"}：${difference.source ?: "不存在"}")
                        Text("云端：${difference.remote ?: "不存在"}")
                    } }
                }
            }
            state.history?.let { page ->
                item { HorizontalDivider(); Text("云端历史 · 当前版本 ${page.revision}", style = MaterialTheme.typography.titleMedium); Text("服务端保留窗口内历史及当前版本；时间为服务端保存时间。") }
                items(page.entries, key = { "history-${it.revision}" }) { entry ->
                    Column {
                        Text("版本 ${entry.revision} · ${java.time.Instant.ofEpochMilli(entry.createdAt)} · ${entry.bytes} 字节")
                        OutlinedButton({ selected = entry.revision; preparing = true }, enabled = enabled && !state.pending && entry.revision < page.revision) { Text("预览历史 ${entry.revision}") }
                    }
                }
                page.nextBefore?.let { before -> item { TextButton({ history(before) }, enabled = enabled) { Text("读取更早历史") } } }
            }
            item {
                HorizontalDivider(); Text("本机加密保留副本（${state.savedVersions.size}/16）", style = MaterialTheme.typography.titleMedium)
                Text("最多 16 份、合计 16 MiB，满时拒绝继续，不自动删除。导出仍需原副本对应的密码或恢复密钥解密；可在“备份与保险库”独立导入。")
            }
            items(state.savedVersions, key = { "saved-${it.id}" }) { item ->
                Column {
                    Text("${item.reason} · 版本 ${item.revision} · ${item.createdAt}")
                    Row {
                        OutlinedButton({ export(item.id) }, enabled = enabled) { Text("导出保留副本 ${item.revision}") }
                        TextButton({ deleting = item.id }, enabled = enabled) { Text("删除保留副本 ${item.revision}") }
                    }
                }
            }
        }
    }
    if (preparing) ReviewSecretDialog(selected, { preparing = false }) { secret, purpose, old, oldPurpose ->
        preparing = false; prepare(secret, purpose, selected, old, oldPurpose)
    }
    choice?.let { keep ->
        val view = state.review
        if (view != null) ReviewConfirmDialog("确认整份内容取舍", "${if (keep) "将选中的${if (view.kind == "history") "历史" else "本地"}业务内容作为新的待同步版本" else "采用当前云端内容并结束本地待上传请求"}。未选内容仍在本机加密保留；这会影响整份账号、分组、标签和回收站。", { choice = null }) {
            choice = null; confirm(view.id, keep, true)
        }
    }
    deleting?.let { id -> ReviewConfirmDialog("删除本机保留副本", "请先导出并另存这份加密副本，确认删除后本机不再保留它。", { deleting = null }) {
        deleting = null; delete(id, true)
    } }
}

@Composable private fun ReviewConfirmDialog(title: String, message: String, close: () -> Unit, confirm: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    CloseOnBackground { checked = false; close() }
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        Column { Text(message); Row { Checkbox(checked, { checked = it }, modifier = Modifier.testTag("review-confirm")); Text("已核对并确认", Modifier.padding(top = 12.dp)) } }
    }, confirmButton = { TextButton(confirm, enabled = checked) { Text("确认执行") } }, dismissButton = { TextButton(close) { Text("取消") } })
}

@Composable private fun ReviewSecretDialog(revision: Long?, close: () -> Unit, confirm: (String, String, String?, String) -> Unit) {
    var secret by remember { mutableStateOf("") }; var recovery by remember { mutableStateOf(false) }
    var old by remember { mutableStateOf("") }; var oldRecovery by remember { mutableStateOf(false) }
    CloseOnBackground { secret = ""; old = ""; close() }
    AlertDialog(onDismissRequest = close, title = { Text(if (revision == null) "读取冲突双方" else "读取历史 $revision") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("输入最新云端保险库的密码或 ABR1，本机候选已由当前解锁认证。")
            Row { Checkbox(recovery, { recovery = it; secret = "" }); Text("云端使用 ABR1", Modifier.padding(top = 12.dp)) }
            ReviewSecret(secret, { secret = it }, "最新云端密码或恢复密钥")
            if (revision != null) {
                Text("同一主密钥的历史可直接认证；如果历史使用旧主密钥，补充旧密码或 ABR1。留空时认证失败会保留当前内容。")
                Row { Checkbox(oldRecovery, { oldRecovery = it; old = "" }); Text("旧历史使用 ABR1", Modifier.padding(top = 12.dp)) }
                ReviewSecret(old, { old = it }, "旧历史凭据（可选）")
            }
        }
    }, confirmButton = { TextButton({ val value = secret; val previous = old; secret = ""; old = ""; confirm(value, if (recovery) "recovery" else "password", previous.takeIf { it.isNotEmpty() }, if (oldRecovery) "recovery" else "password") },
        enabled = secret.isNotEmpty()) { Text("解密并比较") } }, dismissButton = { TextButton(close) { Text("取消") } })
}

@Composable private fun ReviewSecret(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
}
