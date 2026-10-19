package com.authbox.android

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable internal fun OfflineRegistrationScreen(state: ScreenState, source: BackupInfo, initialOrigin: String, initialUser: String,
    target: Uri?, chooseTarget: () -> Unit, back: () -> Unit, openAccount: () -> Unit,
    prepare: (String, String, String, String, String, Uri) -> Unit, register: (String, String, String, String, Boolean) -> Unit) {
    var origin by remember { mutableStateOf(initialOrigin) }
    var user by remember { mutableStateOf(initialUser) }
    var sourceSecret by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var loginRepeated by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var candidateVaultId by remember { mutableStateOf<String?>(null) }
    val info = state.registration
    val ready = state.registrationMaterialId != null && state.registrationMaterialId == info?.vaultId && state.recovery.isNotEmpty()
    val registered = candidateVaultId != null && state.role == "manager" && state.revision == 1L && info == null
    CloseOnBackground { sourceSecret = ""; password = ""; repeated = ""; login = ""; loginRepeated = ""; invite = ""; saved = false; confirmed = false }
    LaunchedEffect(state.recovery) { saved = false }
    LaunchedEffect(state.registrationMaterialId) {
        if (ready && info?.sourceVaultId == source.vaultId && info.sourceRevision == source.revision) candidateVaultId = info.vaultId
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("离线副本接入新账号", style = MaterialTheme.typography.titleLarge)
            TextButton(back, enabled = !state.busy) { Text("返回备份") }
        }
        Text(state.message)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).testTag("registration"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("源离线副本：${source.vaultId} · 本机版本 ${source.revision}")
                Text("为新账号创建独立保险库，保留条目和关联，生成新主密钥及恢复材料。原副本和原账号不变，原历史版本不复制，源 TOTP 密钥继续有效。")
                Text("先选择恢复材料包保存位置，再填写秘密。材料包为明文，须保存到手机以外的安全位置；它不包含加密备份，后续还需另行导出。")
                OutlinedButton(chooseTarget, enabled = !state.busy && !ready && !registered) { Text("选择恢复材料包保存位置") }
                Text(if (target == null) "尚未选择保存位置" else "已选择保存位置，生成后写入并读回复核")
            }
            if (!registered) {
                item {
                    OutlinedTextField(origin, { origin = it }, label = { Text("新账号服务地址") }, singleLine = true, enabled = !state.busy && !ready)
                    OutlinedTextField(user, { user = it }, label = { Text("新平台账号") }, singleLine = true, enabled = !state.busy && !ready)
                    Row { Checkbox(recovery, { recovery = it; sourceSecret = "" }, enabled = !state.busy && !ready, modifier = Modifier.testTag("source-recovery")); Text("源副本使用 ABR1", Modifier.padding(top = 12.dp)) }
                    RegistrationSecret(sourceSecret, { sourceSecret = it }, "源副本密码或恢复密钥", !state.busy && !ready)
                    RegistrationSecret(password, { password = it }, "新同步保险库密码", !state.busy && !ready)
                    RegistrationSecret(repeated, { repeated = it }, "再次输入新同步保险库密码", !state.busy && !ready)
                    Row { Checkbox(confirmed, { confirmed = it }, enabled = !state.busy && !ready, modifier = Modifier.testTag("copy-confirm")); Text("确认创建独立副本，原账号和离线副本保持独立", Modifier.padding(top = 12.dp)) }
                    Button({
                        val secret = sourceSecret; val next = password
                        sourceSecret = ""; password = ""; repeated = ""; saved = false; candidateVaultId = null
                        prepare(origin, user, secret, if (recovery) "recovery" else "password", next, requireNotNull(target))
                    }, enabled = !state.busy && !ready && target != null && confirmed && sourceSecret.isNotEmpty() &&
                        password.toByteArray(Charsets.UTF_8).size in 8..1024 && password == repeated && user.isNotBlank() && origin.isNotBlank()) { Text("生成新账号保险库与恢复包") }
                }
                if (info != null) item { Text("候选新保险库：${info.vaultId} · ${info.entities} 个实体 · 来源版本 ${info.sourceRevision ?: "—"}") }
                if (ready) item {
                    state.recovery.forEach { Text(it) }
                    Text("ABAC1 用于恢复平台访问；ABR1 用于解密匹配备份。注册成功后才能登录新账号，原恢复材料继续对应原副本。")
                    Row { Checkbox(saved, { saved = it }, modifier = Modifier.testTag("kit-saved")); Text("已核对并在手机以外保存完整新恢复包", Modifier.padding(top = 12.dp)) }
                    RegistrationSecret(login, { login = it }, "新账号登录密码", !state.busy)
                    RegistrationSecret(loginRepeated, { loginRepeated = it }, "再次输入新账号登录密码", !state.busy)
                    RegistrationSecret(invite, { invite = it }, "新账号注册邀请码", !state.busy)
                    Button({ val secret = login; val code = invite; login = ""; loginRepeated = ""; invite = ""; register(origin, user, secret, code, saved) },
                        enabled = !state.busy && saved && login.toByteArray(Charsets.UTF_8).size in 8..256 && login == loginRepeated && invite.isNotEmpty()) { Text("确认注册新同步账号") }
                }
            } else item {
                Text("新账号注册已确认，保险库为版本 1。请返回账号，使用新保险库密码下载并解锁，随后另存新账号的加密备份。")
                Button(openAccount) { Text("返回新账号并解锁") }
            }
        }
    }
}

@Composable private fun RegistrationSecret(value: String, change: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, change, label = { Text(label) }, enabled = enabled, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
}
