package com.authbox.android

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable internal fun VaultManagementScreen(state: ScreenState, onLock: () -> Unit, onSync: () -> Unit,
    edit: ((MutableList<VaultEntity>) -> Unit) -> Unit, onSettings: (() -> Unit)? = null, settingsLabel: String = "账号与设备",
    onReview: (() -> Unit)? = null, onInteraction: ((Boolean) -> Unit)? = null, importContent: (@Composable () -> Unit)? = null) {
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    var tag by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(false) }
    var trash by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var accountEditor by remember { mutableStateOf<VaultEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var categories by remember { mutableStateOf(false) }
    var bulk by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    DisposableEffect(adding, accountEditor, categories, bulk, confirmTrash, selected.isNotEmpty()) {
        onInteraction?.invoke(adding || accountEditor != null || categories || bulk || confirmTrash || selected.isNotEmpty())
        onDispose { onInteraction?.invoke(false) }
    }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val groups = state.entities.filter { it.type == "group" }.sortedWith(compareBy({ it.payload.getLong("order") }, { it.id }))
    val tags = state.entities.filter { it.type == "tag" }.sortedBy { it.payload.getString("name") }
    val groupNames = groups.associate { it.id to it.payload.getString("name") }
    val groupOrder = groups.mapIndexed { index, entry -> entry.id to index }.toMap()
    val tagNames = tags.associate { it.id to it.payload.getString("name") }
    val editable = state.canEdit && !state.busy
    val accounts = state.entities.filter { entry ->
        if (entry.type != "account") false else {
            val p = entry.payload
            val search = listOf(p.getString("issuer"), p.getString("name"), p.getString("account"), p.getString("notes"),
                groupNames[p.optString("groupId")] ?: "未分组", VaultEditor.tags(p).mapNotNull { tagNames[it] }.joinToString(" ")).joinToString(" ")
            (p.isNull("deletedAt") != trash) && (!favorites || p.getBoolean("favorite")) &&
                (group == null || (group == "" && p.isNull("groupId")) || p.optString("groupId") == group) &&
                (tag == null || tag in VaultEditor.tags(p)) && search.contains(query, true)
        }
    }.sortedWith(compareBy({ groupOrder[it.payload.optString("groupId")] ?: -1 }, { it.payload.getLong("order") }, { it.id }))
    LaunchedEffect(state.entities) { selected = selected.intersect(state.entities.filter { it.type == "account" }.map { it.id }.toSet()) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("AuthBox", style = MaterialTheme.typography.headlineLarge)
            Row {
                if (onReview != null && state.role == "manager") TextButton(onReview, enabled = !state.busy) { Text("冲突与历史") }
                if (onSettings != null) TextButton(onSettings) { Text(settingsLabel) }
            }
        }
        Text(if (state.role == "offline") "离线副本 · 本机版本 ${state.revision ?: "—"}" else if (state.pending) "修改已加密保存在本机，等待同步" else "云端版本 ${state.revision ?: "—"}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onLock) { Text("锁定") }
            if (state.role != "offline") Button(onSync, enabled = !state.busy && state.pending && !state.hasReview && state.role == "manager") { Text("同步修改") }
            TextButton({ onInteraction?.invoke(true); categories = true }, enabled = editable) { Text("分组与标签") }
        }
        Text(if (state.role == "offline") state.message else "${state.message}\n${state.autoSync.message}", style = MaterialTheme.typography.bodySmall)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedTextField(query, { query = it }, label = { Text("搜索账号或分组") }, supportingText = { Text("也可搜索备注和标签") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice("分组", listOf(null to "全部", "" to "未分组") + groups.map { it.id to it.payload.getString("name") }, group, { group = it }, Modifier.weight(1f))
            Choice("标签", listOf(null to "全部") + tags.map { it.id to it.payload.getString("name") }, tag, { tag = it }, Modifier.weight(1f))
            FilterChip(favorites, { favorites = !favorites }, label = { Text("收藏") })
            FilterChip(trash, { trash = !trash; selected = emptySet() }, label = { Text("回收站") })
        }
        LazyColumn(Modifier.weight(1f).testTag("accounts"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.role in setOf("manager", "offline")) item(key = "actions") {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ onInteraction?.invoke(true); adding = true }, enabled = editable) { Text("添加账号") }
                        OutlinedButton({ onInteraction?.invoke(true); bulk = true }, enabled = editable && selected.isNotEmpty()) { Text("批量管理 (${selected.size})") }
                    }
                    importContent?.invoke()
                }
            }
            if (accounts.isEmpty()) item { Text("没有匹配的账号") }
            items(accounts, key = { it.id }) { entry ->
                val p = entry.payload
                Card(Modifier.fillMaxWidth().testTag("account-${entry.id}")) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            if (state.role in setOf("manager", "offline")) Checkbox(entry.id in selected, { checked -> if (checked) onInteraction?.invoke(true); selected = if (checked) selected + entry.id else selected - entry.id }, enabled = editable)
                            Column(Modifier.weight(1f)) {
                                Text("${groupNames[p.optString("groupId")] ?: "未分组"} · ${p.getString("name").ifBlank { p.getString("issuer") }}")
                                Text(p.getString("account"))
                                Text(VaultEditor.tags(p).mapNotNull { tagNames[it] }.joinToString(" · "))
                            }
                        }
                        if (!trash) {
                            val code = state.rows.find { it.id == entry.id }?.code ?: ""
                            TextButton({
                                if ((context as MainActivity).copyCode(code)) Toast.makeText(context, "验证码已复制", Toast.LENGTH_SHORT).show()
                            }, Modifier.testTag("copy-code-${entry.id}"), enabled = state.unlocked) { Text(code, style = MaterialTheme.typography.headlineMedium) }
                            val seconds = p.getLong("period") - System.currentTimeMillis() / 1000 % p.getLong("period")
                            Text("${seconds} 秒后刷新")
                        }
                        if (state.role in setOf("manager", "offline")) Row {
                            if (trash) TextButton({ edit { VaultEditor.bulk(it, setOf(entry.id), trash = false) } }, enabled = editable) { Text("恢复账号") }
                            else {
                                TextButton({ onInteraction?.invoke(true); accountEditor = entry }, enabled = editable) { Text("编辑") }
                                TextButton({ edit { VaultEditor.bulk(it, setOf(entry.id), favorite = !p.getBoolean("favorite")) } }, enabled = editable) { Text(if (p.getBoolean("favorite")) "取消收藏" else "收藏") }
                                TextButton({ edit { VaultEditor.move(it, entry.id, -1) } }, enabled = editable) { Text("上移") }
                                TextButton({ edit { VaultEditor.move(it, entry.id, 1) } }, enabled = editable) { Text("下移") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding || accountEditor != null) AccountDialog(accountEditor, groups, tags, onDismiss = { adding = false; accountEditor = null }) { id, payload, confirmed ->
        edit { VaultEditor.saveAccount(it, id, payload, confirmed) }; adding = false; accountEditor = null
    }
    if (categories) CategoryDialog(groups, tags, edit) { categories = false }
    if (bulk) BulkDialog(groups, tags, trash, { bulk = false }, { groupId, move, tagsToSet, favorite, remove ->
        if (remove) { bulk = false; confirmTrash = true }
        else { val targets = selected; val restore = trash; edit { VaultEditor.bulk(it, targets, groupId, move, tagsToSet, favorite, if (restore) false else null) }; bulk = false; selected = emptySet() }
    })
    if (confirmTrash) AlertDialog(onDismissRequest = { confirmTrash = false }, title = { Text("移入回收站？") },
        text = { Text("已选 ${selected.size} 个账号，可从回收站恢复。") },
        confirmButton = { TextButton({ val targets = selected; edit { VaultEditor.bulk(it, targets, trash = true) }; selected = emptySet(); confirmTrash = false }) { Text("确认移入") } },
        dismissButton = { TextButton({ confirmTrash = false }) { Text("取消") } })
}

@Composable internal fun Choice(label: String, options: List<Pair<String?, String>>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) { Text("$label：${options.find { it.first == selected }?.second ?: "—"}") }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false }) }
        }
    }
}

@Composable private fun AccountDialog(entry: VaultEntity?, groups: List<VaultEntity>, tags: List<VaultEntity>, onDismiss: () -> Unit,
    onSave: (String?, JSONObject, Boolean) -> Unit) {
    val p = entry?.payload
    var issuer by remember { mutableStateOf(p?.getString("issuer") ?: "") }
    var name by remember { mutableStateOf(p?.getString("name") ?: "") }
    var account by remember { mutableStateOf(p?.getString("account") ?: "") }
    var secret by remember { mutableStateOf(p?.getString("secret") ?: "") }
    var algorithm by remember { mutableStateOf(p?.getString("algorithm") ?: "SHA1") }
    var digits by remember { mutableStateOf(p?.getInt("digits")?.toString() ?: "6") }
    var period by remember { mutableStateOf(p?.getInt("period")?.toString() ?: "30") }
    var notes by remember { mutableStateOf(p?.getString("notes") ?: "") }
    var group by remember { mutableStateOf(if (p == null || p.isNull("groupId")) null else p.getString("groupId")) }
    var selectedTags by remember { mutableStateOf(p?.let { VaultEditor.tags(it).toSet() } ?: emptySet()) }
    var favorite by remember { mutableStateOf(p?.getBoolean("favorite") ?: false) }
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (entry == null) "添加账号" else "编辑账号") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(issuer, { issuer = it }, label = { Text("平台") }, singleLine = true)
            OutlinedTextField(name, { name = it }, label = { Text("自定义名称") }, singleLine = true)
            OutlinedTextField(account, { account = it }, label = { Text("验证账号") }, singleLine = true)
            OutlinedTextField(secret, { secret = it }, label = { Text("Base32 密钥") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Choice("算法", listOf("SHA1", "SHA256", "SHA512").map { it to it }, algorithm, { algorithm = requireNotNull(it) })
            OutlinedTextField(digits, { digits = it }, label = { Text("位数（6 或 8）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(period, { period = it }, label = { Text("周期（秒）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Choice("所属分组", listOf(null to "未分组") + groups.map { it.id to it.payload.getString("name") }, group, { group = it })
            tags.forEach { tag -> Row { Checkbox(tag.id in selectedTags, { checked -> selectedTags = if (checked) selectedTags + tag.id else selectedTags - tag.id }); Text(tag.payload.getString("name"), Modifier.padding(top = 12.dp)) } }
            Row { Checkbox(favorite, { favorite = it }); Text("收藏此账号", Modifier.padding(top = 12.dp)) }
            OutlinedTextField(notes, { notes = it }, label = { Text("备注") })
            if (entry != null) Row { Checkbox(confirm, { confirm = it }); Text("确认更换密钥或算法参数", Modifier.padding(top = 12.dp)) }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton({
            try {
                val next = VaultEditor.account(issuer, name, account, secret, algorithm, digits.toInt(), period.toInt(), group,
                    selectedTags.sorted(), favorite, notes, p?.getLong("order") ?: 0)
                val probe = (groups + tags).map { it.copy(payload = it.payload.copy()) }.toMutableList()
                VaultEditor.saveAccount(probe, null, next)
                VaultCrypto.validateEntities(probe)
                require(entry == null || confirm || listOf("secret", "algorithm", "digits", "period").none { p!!.get(it) != next.get(it) })
                onSave(entry?.id, next, confirm)
            } catch (_: Exception) { error = "请检查名称、密钥、位数、周期及密钥变更确认" }
        }) { Text("保存到本机") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}

@Composable private fun CategoryDialog(groups: List<VaultEntity>, tags: List<VaultEntity>, edit: ((MutableList<VaultEntity>) -> Unit) -> Unit, dismiss: () -> Unit) {
    var type by remember { mutableStateOf("group") }
    var id by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<VaultEntity?>(null) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("分组与标签") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Choice("类型", listOf("group" to "分组", "tag" to "标签"), type, { type = requireNotNull(it); id = null; name = "" })
            (if (type == "group") groups else tags).forEach { entry -> Row {
                TextButton({ id = entry.id; name = entry.payload.getString("name") }, Modifier.weight(1f)) { Text(entry.payload.getString("name")) }
                if (type == "group") TextButton({ edit { VaultEditor.move(it, entry.id, -1) } }) { Text("上移") }
                TextButton({ deleting = entry }) { Text("删除") }
            } }
            OutlinedTextField(name, { name = it }, label = { Text(if (id == null) "新名称" else "重命名") }, singleLine = true)
            TextButton({ id = null; name = "" }) { Text("新建") }
            if (error.isNotEmpty()) Text(error)
        }
    }, confirmButton = { TextButton({
        if (name.isBlank() || name.toByteArray().size > 256 || name.any { it < ' ' || it == '\u007f' }) error = "名称不能为空、包含控制字符或超过 256 字节"
        else { val identity = id; val kind = type; val label = name; edit { VaultEditor.saveCategory(it, kind, identity, label) }; id = null; name = ""; error = "" }
    }) { Text("保存名称") } }, dismissButton = { TextButton(dismiss) { Text("完成") } })
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除${if (entry.type == "group") "分组" else "标签"}？") },
        text = { Text(if (entry.type == "group") "其账号将移入未分组，不删除账号。" else "只移除标签和关联，不删除账号。") },
        confirmButton = { TextButton({ edit { VaultEditor.deleteCategory(it, entry.id) }; deleting = null; id = null; name = "" }) { Text("确认删除") } },
        dismissButton = { TextButton({ deleting = null }) { Text("取消") } }) }
}

@Composable private fun BulkDialog(groups: List<VaultEntity>, tags: List<VaultEntity>, trash: Boolean, dismiss: () -> Unit,
    save: (String?, Boolean, List<String>?, Boolean?, Boolean) -> Unit) {
    var group by remember { mutableStateOf<String?>(null) }
    var move by remember { mutableStateOf(false) }
    var changeTags by remember { mutableStateOf(false) }
    var selectedTags by remember { mutableStateOf(setOf<String>()) }
    var favorite by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("批量管理") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        if (!trash) {
            Row { Checkbox(move, { move = it }); Text("移动分组", Modifier.padding(top = 12.dp)) }
            Choice("目标分组", listOf(null to "未分组") + groups.map { it.id to it.payload.getString("name") }, group, { group = it })
            Row { Checkbox(changeTags, { changeTags = it }); Text("替换标签（不选则清空）", Modifier.padding(top = 12.dp)) }
            tags.forEach { entry -> Row { Checkbox(entry.id in selectedTags, { checked -> selectedTags = if (checked) selectedTags + entry.id else selectedTags - entry.id }); Text(entry.payload.getString("name"), Modifier.padding(top = 12.dp)) } }
            Choice("收藏设置", listOf(null to "保持原样", "yes" to "收藏", "no" to "取消收藏"), favorite, { favorite = it })
            TextButton({ save(null, false, null, null, true) }) { Text("移入回收站") }
        } else Text("将选中的账号从回收站恢复。")
    } }, confirmButton = { TextButton({ save(group, move, if (changeTags) selectedTags.sorted() else null, favorite?.let { it == "yes" }, false) }) { Text(if (trash) "恢复所选账号" else "应用修改") } },
        dismissButton = { TextButton(dismiss) { Text("取消") } })
}
