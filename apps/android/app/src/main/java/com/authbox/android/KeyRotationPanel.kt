package com.authbox.android

import androidx.compose.foundation.layout.*
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

@Composable internal fun KeyRotationPanel(state: ScreenState, prepare: (String, String, String) -> Unit,
    confirm: (String, Boolean) -> Unit, cancel: () -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val candidate = state.rotation
    val key = state.recovery.singleOrNull()?.takeIf { it.startsWith("ABR1-") }
    val enabled = state.unlocked && state.role == "manager" && !state.busy
    LaunchedEffect(candidate?.id, key) { saved = false }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("保险库主密钥", style = MaterialTheme.typography.titleMedium)
        Text("轮换会重新加密全部数据，并更换保险库密码和恢复密钥。其他设备须重新登录及申请管理权限，本机系统解锁需重新启用。平台登录密码不变。")
        Text("轮换前的加密快照会保留在本机，可在版本比较页导出。旧备份仍用旧密码或旧恢复密钥；已被其他设备取得的验证码密钥不会失效。")
        if (candidate == null) {
            OutlinedButton({ dialog = true }, enabled = enabled && !state.pending && !state.hasReview && !state.conflict) { Text("轮换主密钥") }
            Text("请先同步所有修改并处理冲突，再开始轮换。")
        } else {
            Text("候选密钥代次：${candidate.generation}")
            if (candidate.submitted) {
                Text("轮换已提交，结果尚未确认。请保留新旧密码和恢复密钥，重试只确认同一次轮换。")
                Button({ confirm(candidate.id, true) }, enabled = enabled) { Text("重试确认轮换") }
            } else {
                if (key != null) {
                    Text("新保险库恢复密钥，请保存到手机以外：")
                    Text(key, Modifier.testTag("rotation-recovery-key"))
                    Row {
                        Checkbox(saved, { saved = it }, Modifier.testTag("rotation-saved"))
                        Text("已离线保存新恢复密钥，并确认其他设备重新授权", Modifier.padding(top = 12.dp))
                    }
                    Button({ confirm(candidate.id, saved) }, enabled = enabled && saved && !state.conflict) { Text("确认轮换并重新授权设备") }
                } else Text("恢复密钥已隐藏。请取消未提交候选，再重新生成并保存恢复材料。")
                TextButton(cancel, enabled = enabled) { Text("取消未提交轮换") }
            }
        }
    }
    if (dialog) RotationDialog(state.busy, { dialog = false }) { secret, purpose, password ->
        dialog = false; prepare(secret, purpose, password)
    }
}

@Composable private fun RotationDialog(busy: Boolean, close: () -> Unit, confirm: (String, String, String) -> Unit) {
    var current by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    CloseOnBackground { current = ""; password = ""; repeated = ""; close() }
    AlertDialog(onDismissRequest = close, title = { Text("准备主密钥轮换") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("先验证当前解密材料，再生成候选。保存新恢复密钥并确认后才更新云端。新保险库密码必须与当前不同。")
            Row { Checkbox(recovery, { recovery = it; current = "" }); Text("使用当前保险库恢复密钥", Modifier.padding(top = 12.dp)) }
            RotationSecret(current, { current = it }, if (recovery) "当前保险库恢复密钥" else "当前保险库密码")
            RotationSecret(password, { password = it }, "轮换后的保险库密码")
            RotationSecret(repeated, { repeated = it }, "再次输入轮换后的密码")
        }
    }, confirmButton = { TextButton({
        val secret = current; val next = password; current = ""; password = ""; repeated = ""
        confirm(secret, if (recovery) "recovery" else "password", next)
    }, enabled = !busy && current.isNotEmpty() && password.toByteArray(Charsets.UTF_8).size in 8..1024 && password == repeated && (recovery || current != password)) { Text("生成轮换候选") } },
        dismissButton = { TextButton(close) { Text("取消") } })
}

@Composable private fun RotationSecret(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
}
