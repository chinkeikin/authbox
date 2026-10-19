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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private fun validLoginPassword(value: String) = value.toByteArray(Charsets.UTF_8).size in 8..256

@Composable internal fun CloseOnBackground(close: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(close)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) latest() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

@Composable private fun SecretField(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
}

@Composable internal fun AccountSecurityScreen(state: ScreenState, back: () -> Unit, refresh: () -> Unit,
    update: (String, Boolean) -> Unit, changePassword: (String, String) -> Unit,
    generate: () -> Unit, rotate: (Boolean) -> Unit, onBackup: (() -> Unit)? = null) {
    var password by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Pair<DeviceInfo, Boolean>?>(null) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(state.recovery) { saved = false }
    val manager = state.role == "manager" && !state.busy
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("账号与设备", style = MaterialTheme.typography.headlineSmall)
            TextButton(back) { Text("返回保险库") }
        }
        Text(state.message)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).testTag("security"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onBackup != null) item { OutlinedButton(onBackup) { Text("备份与保险库") } }
            item {
                Text("本机设备 ID：${state.deviceId ?: "请先登录"}")
                Text("权限：${when (state.role) { "manager" -> "管理设备"; "reader" -> "只读设备"; else -> "未登录" }}")
                Text("审批前请与目标手机逐字核对设备 ID，设备名称不能作为身份依据。新手机批准后，须使用本机已有设备身份重新登录。")
                OutlinedButton(refresh, enabled = manager) { Text("刷新设备列表") }
            }
            items(state.devices, key = { it.id }) { device ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(device.name)
                    Text(device.id)
                    Text(if (device.revoked) "已撤销" else if (device.role == "manager") "管理设备" else if (device.hasKey) "只读 · 可申请管理" else "只读 · 不支持管理审批")
                    Row {
                        TextButton({ target = device to true }, enabled = manager && !device.revoked && device.role == "reader" && device.hasKey) { Text("批准管理") }
                        TextButton({ target = device to false }, enabled = manager && !device.revoked) { Text("撤销设备") }
                    }
                } }
            }
            item {
                HorizontalDivider()
                OutlinedButton({ password = true }, enabled = manager) { Text("修改平台登录密码") }
                Text("只修改账号登录密码，保险库密码和离线数据的解密方式不变；其他会话需重新登录。")
                OutlinedButton(generate, enabled = manager) { Text("生成新账号恢复码") }
                Text("账号恢复码用于重设登录密码并撤销旧设备，不能解密保险库。新码确认生效前旧码仍有效；确认后所有旧码作废。")
            }
            if (state.recovery.size == 8 && state.recovery.all { it.startsWith("ABAC1-") }) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请离线保存这 8 个新码，每个只能用一次。离开应用后不再显示。")
                    state.recovery.forEach { Text(it) }
                    Row { Checkbox(saved, { saved = it }); Text("已离线保存新账号恢复码", Modifier.padding(top = 12.dp)) }
                    Button({ rotate(saved) }, enabled = manager && saved) { Text("确认作废旧码并启用新码") }
                }
            }
            item { Text("设备撤销会阻止后续联网访问，无法擦除对方已下载的密文或已取得的密钥。") }
        }
    }
    if (password) PasswordChangeDialog(state.busy, { password = false }) { old, next -> password = false; changePassword(old, next) }
    target?.let { (device, approve) ->
        CloseOnBackground { target = null }
        AlertDialog(onDismissRequest = { target = null }, title = { Text(if (approve) "确认批准管理设备" else "确认撤销设备") },
            text = { Text("${device.name}\n${device.id}\n" + if (approve) "请确认已与目标手机核对 ID。此操作允许该设备修改保险库，原只读会话会被注销。"
                else if (device.id == state.deviceId) "这是当前设备。撤销后将注销本机管理会话，但不会删除本地缓存。"
                else "目标设备将不能继续联网同步；离线已取得的密钥无法远程擦除。") },
            confirmButton = { TextButton({ target = null; update(device.id, approve) }, enabled = manager) { Text("确认操作") } },
            dismissButton = { TextButton({ target = null }) { Text("取消") } })
    }
}

@Composable private fun PasswordChangeDialog(busy: Boolean, close: () -> Unit, confirm: (String, String) -> Unit) {
    var old by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    CloseOnBackground { old = ""; next = ""; repeated = ""; close() }
    AlertDialog(onDismissRequest = close, title = { Text("修改平台登录密码") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecretField(old, { old = it }, "当前平台密码")
            SecretField(next, { next = it }, "新平台密码")
            SecretField(repeated, { repeated = it }, "再次输入新平台密码")
            Text("新密码为 8–256 字节，两次输入必须一致。操作成功后其他会话被注销；网络中断时请保留新旧密码。")
        }
    }, confirmButton = { TextButton({ val current = old; val value = next; old = ""; next = ""; repeated = ""; confirm(current, value) },
        enabled = !busy && validLoginPassword(old) && validLoginPassword(next) && next == repeated) { Text("确认修改密码") } },
        dismissButton = { TextButton(close) { Text("取消") } })
}

@Composable internal fun AccountRecoveryDialog(busy: Boolean, close: () -> Unit, confirm: (String, String, Boolean) -> Unit) {
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }
    CloseOnBackground { code = ""; password = ""; repeated = ""; agreed = false; close() }
    AlertDialog(onDismissRequest = close, title = { Text("用账号恢复码恢复") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("使用已填写的服务地址和平台账号。此操作消耗一个恢复码，重设平台密码，撤销全部旧设备和会话，并授权本机为管理设备。仍需保险库密码或恢复密钥才能解密数据。")
            SecretField(code, { code = it }, "账号恢复码 ABAC1")
            SecretField(password, { password = it }, "恢复后的平台密码")
            SecretField(repeated, { repeated = it }, "再次输入恢复后的平台密码")
            Row { Checkbox(agreed, { agreed = it }); Text("确认撤销全部旧设备和会话", Modifier.padding(top = 12.dp)) }
        }
    }, confirmButton = { TextButton({ val material = code.trim(); val next = password; code = ""; password = ""; repeated = ""; confirm(material, next, agreed) },
        enabled = !busy && agreed && code.trim().matches(Regex("ABAC1-[A-Za-z0-9_-]{22}")) && validLoginPassword(password) && password == repeated) { Text("确认恢复账号") } },
        dismissButton = { TextButton(close) { Text("取消") } })
}
