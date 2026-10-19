package com.authbox.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

@Composable internal fun BackupScreen(account: ScreenState, state: BackupState, model: AuthBoxViewModel, backups: BackupViewModel,
    origin: String, username: String, back: () -> Unit, pick: () -> Unit, openLocal: () -> Unit, openRegistration: () -> Unit) {
    var decrypt by remember { mutableStateOf<String?>(null) }
    var replace by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("备份与保险库", style = MaterialTheme.typography.headlineSmall)
            TextButton(back) { Text("返回账号") }
        }
        Text(state.message)
        if (state.busy || account.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).testTag("backup"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("同步账号的保险库", style = MaterialTheme.typography.titleMedium)
                Text(account.message)
                Text(if (account.pending) "导出包含本机最新的待同步修改，文件不代表云端已提交。" else "导出当前完整加密快照，恢复不依赖本机 Keystore。")
                Button({ model.prepareBackupExport(origin, username) }, enabled = account.unlocked && account.role == "manager" && !account.busy && !state.busy) { Text("导出账号加密备份") }
                VaultWrapControls(account.canEdit && account.unlocked && !account.busy && !state.busy,
                    { model.rewrap(origin, username, it, "password") }, { model.prepareVaultRecoveryKey(origin, username) },
                    account.recovery.singleOrNull()?.takeIf { it.startsWith("ABR1-") }, { model.confirmVaultRecoveryKey(origin, username, it) })
                Text("封装变更先保存在本机，返回账号点击同步后才更新云端。账号未解锁或没有管理权限时，这些操作不可用。")
                HorizontalDivider()
            }
            item {
                Text("从文件恢复离线副本", style = MaterialTheme.typography.titleMedium)
                Text("仅导入 AuthBox 完整加密备份。离线副本可独立取码和管理，保留原保险库身份；同步账号及待上传内容不受影响。")
                OutlinedButton({ replace = false; pick() }, enabled = !state.busy && !account.busy) { Text("选择加密备份文件") }
                state.selected?.let {
                    Text("文件头（尚未解密）：${it.vaultId} · 版本 ${it.revision} · ${it.entities} 个加密实体")
                    Button({ replace = false; decrypt = "preview" }, enabled = !state.busy) { Text("解密并预览文件") }
                }
                state.preview?.let {
                    Text("已验证预览：账号 ${it.accounts} · 分组 ${it.groups} · 标签 ${it.tags} · 共 ${it.entities} 个实体")
                    if (state.stored != null) Row {
                        Checkbox(replace, { replace = it })
                        Text("确认替换已有离线副本，已自行保存旧副本", Modifier.padding(top = 12.dp))
                    }
                    Button({ backups.confirm(replace) }, enabled = !state.busy && (state.stored == null || replace)) { Text("确认保存离线副本") }
                }
                HorizontalDivider()
            }
            item {
                Text("本机离线保险库", style = MaterialTheme.typography.titleMedium)
                state.stored?.let {
                    Text("${it.vaultId} · 本机版本 ${it.revision}")
                    OutlinedButton(openRegistration, enabled = !state.busy && !account.busy) { Text("接入新账号同步") }
                    if (!state.local.unlocked) OutlinedButton({ decrypt = "unlock" }, enabled = !state.busy) { Text("解锁离线副本") }
                    else {
                        Row {
                            Button(openLocal, enabled = !state.busy) { Text("查看离线账号") }
                            TextButton(backups::lock, enabled = !state.busy) { Text("锁定离线副本") }
                        }
                        OutlinedButton(backups::prepareExport, enabled = !state.busy) { Text("导出离线加密备份") }
                    }
                } ?: Text("尚未导入离线副本")
                VaultWrapControls(state.local.unlocked && !state.busy, { backups.rewrap(it, "password") }, backups::generateRecoveryKey,
                    state.recoveryKey, backups::confirmRecoveryKey)
                Text("离线修改不会自动上传；请重新导出并另存。旧备份仍需导出时的密码或恢复密钥。")
            }
        }
    }
    decrypt?.let { target ->
        BackupSecretDialog(if (target == "preview") "解密备份文件" else "解锁离线副本", { decrypt = null }) { secret, purpose ->
            decrypt = null
            if (target == "preview") backups.preview(secret, purpose) else backups.unlock(secret, purpose)
        }
    }
}

@Composable private fun VaultWrapControls(enabled: Boolean, password: (String) -> Unit, generate: () -> Unit, key: String?, confirm: (Boolean) -> Unit) {
    var changing by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(key) { saved = false }
    OutlinedButton({ changing = true }, enabled = enabled) { Text("更换保险库密码") }
    OutlinedButton(generate, enabled = enabled) { Text("生成新保险库恢复密钥") }
    if (key != null) {
        Text("请离线保存新 ABR1 密钥，确认后才更新封装。它不能代替账号恢复码。")
        Text(key)
        Row { Checkbox(saved, { saved = it }); Text("已离线保存新保险库恢复密钥", Modifier.padding(top = 12.dp)) }
        Button({ confirm(saved) }, enabled = enabled && saved) { Text("确认启用新恢复密钥") }
    }
    if (changing) {
        var next by remember { mutableStateOf("") }
        var repeated by remember { mutableStateOf("") }
        CloseOnBackground { next = ""; repeated = ""; changing = false }
        AlertDialog(onDismissRequest = { changing = false }, title = { Text("更换保险库密码") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已解锁的保险库可设置新密码，不改变平台登录密码。旧备份仍可用旧密码解密，请保留对应关系。")
                VaultSecretField(next, { next = it }, "新保险库密码")
                VaultSecretField(repeated, { repeated = it }, "再次输入新保险库密码")
            }
        }, confirmButton = { TextButton({ val secret = next; next = ""; repeated = ""; changing = false; password(secret) },
            enabled = enabled && next.toByteArray(Charsets.UTF_8).size in 8..1024 && next == repeated) { Text("确认更换保险库密码") } },
            dismissButton = { TextButton({ changing = false }) { Text("取消") } })
    }
}

@Composable private fun VaultSecretField(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
}

@Composable private fun BackupSecretDialog(title: String, close: () -> Unit, confirm: (String, String) -> Unit) {
    var secret by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf(false) }
    CloseOnBackground { secret = ""; close() }
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { Checkbox(recovery, { recovery = it; secret = "" }); Text("使用保险库恢复密钥 ABR1", Modifier.padding(top = 12.dp)) }
            VaultSecretField(secret, { secret = it }, if (recovery) "保险库恢复密钥 ABR1" else "备份的保险库密码")
        }
    }, confirmButton = { TextButton({ val value = secret; secret = ""; confirm(value, if (recovery) "recovery" else "password") }, enabled = secret.isNotEmpty()) { Text("解密") } },
        dismissButton = { TextButton(close) { Text("取消") } })
}
