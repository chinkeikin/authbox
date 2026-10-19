package com.authbox.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val model: AuthBoxViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { AuthBoxScreen(model) } } }
    }
}

@Composable private fun AuthBoxScreen(model: AuthBoxViewModel) {
    val state by model.state.collectAsState()
    var origin by remember { mutableStateOf(model.initialOrigin) }
    var username by remember { mutableStateOf(model.initialUsername) }
    var password by remember { mutableStateOf("") }
    var vaultPassword by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var existing by remember { mutableStateOf(false) }
    var registration by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf(false) }
    var pendingImages by remember { mutableStateOf(emptyList<Uri>()) }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected -> pendingImages = selected }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                password = ""; vaultPassword = ""; invite = ""; saved = false
                model.onHidden()
            } else if (event == Lifecycle.Event.ON_RESUME) model.onVisible()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); model.tick() } }
    LaunchedEffect(state.unlocked, pendingImages) {
        if (state.unlocked && pendingImages.isNotEmpty()) {
            val selected = pendingImages; pendingImages = emptyList()
            model.importImages(origin, username, selected)
        }
    }
    if (settings) {
        AccountSecurityScreen(state, { settings = false }, { model.loadDevices(origin, username) },
            { id, approve -> model.updateDevice(origin, username, id, approve) },
            { old, next -> model.changePassword(origin, username, old, next) },
            { model.prepareRecoveryCodes(origin, username) }, { model.rotateRecoveryCodes(origin, username, it) })
        return
    }
    if (state.unlocked) {
        VaultManagementScreen(state, model::lock, { model.upload(origin, username) }, { operation -> model.edit(origin, username, operation) }, { settings = true }) {
            ImportControls(state, state.importPreview, { model.importQr(origin, username, it) }, { images.launch("image/*") },
                { keep, skip -> model.confirmImport(origin, username, keep, skip) }, model::cancelImport)
        }
        return
    }
    Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AuthBox", style = MaterialTheme.typography.headlineLarge)
        Text("Android 开发版", style = MaterialTheme.typography.labelLarge)
        TextButton({ settings = true }, enabled = !state.busy) { Text("账号与设备") }
        TextButton({ recovery = true }, enabled = !state.busy) { Text("用账号恢复码恢复") }
        if (pendingImages.isNotEmpty()) Text("已选择 ${pendingImages.size} 张图片，解锁后在本机解析；新的图片选择会替换当前导入预览。")
        OutlinedTextField(origin, { origin = it }, label = { Text("服务地址（https://…）") }, singleLine = true, enabled = !state.busy)
        OutlinedTextField(username, { username = it }, label = { Text("平台账号") }, singleLine = true, enabled = !state.busy)
        OutlinedTextField(password, { password = it }, label = { Text("登录密码") }, singleLine = true, enabled = !state.busy,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Row {
            Checkbox(existing, { existing = it }, enabled = !state.busy)
            Text("使用本机已有设备身份重新登录", Modifier.padding(top = 12.dp))
        }
        Button(onClick = { val secret = password; password = ""; model.login(origin, username, secret, existing) }, enabled = !state.busy) { Text("登录") }
        if (state.role == "reader") Text("当前设备只有只读权限，需要已授权管理设备批准后重新登录。")
        if (state.role != null) Text("设备权限：${state.role} · 云端版本：${state.revision ?: "未下载"}")
        OutlinedTextField(vaultPassword, { vaultPassword = it }, label = { Text("保险库密码（可离线解锁）") }, singleLine = true, enabled = !state.busy,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { val secret = vaultPassword; vaultPassword = ""; model.download(origin, username, secret) }, enabled = !state.busy && state.role != null && !state.pending) { Text("下载并解锁") }
            OutlinedButton(onClick = { val secret = vaultPassword; vaultPassword = ""; model.unlock(origin, username, secret) }, enabled = !state.busy) { Text("离线解锁") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.lock() }, enabled = !state.busy) { Text("锁定") }
            OutlinedButton(onClick = { model.refresh(origin, username) }, enabled = !state.busy && state.role != null) { Text("刷新登录") }
            OutlinedButton(onClick = { model.logout(origin, username) }, enabled = !state.busy && state.role != null) { Text("退出登录") }
        }
        if (state.pending) {
            Text("有待上传的本地修改；冲突或网络失败时会保留。")
            Button(onClick = { model.upload(origin, username) }, enabled = !state.busy && state.role == "manager") { Text("重试上传") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.message)
        HorizontalDivider()
        TextButton(onClick = { registration = !registration }, enabled = !state.busy) { Text("新建平台账号") }
        if (registration) {
            Text("先填写账号和保险库密码，生成并离线保存恢复材料，再使用部署管理员签发的邀请码注册。重新生成会替换尚未注册的材料。")
            Button(onClick = { val secret = vaultPassword; vaultPassword = ""; saved = false; model.prepare(origin, username, secret) }, enabled = !state.busy && state.role == null) { Text("生成保险库与恢复材料") }
            state.recovery.forEach { Text(it) }
            Row {
                Checkbox(saved, { saved = it }, enabled = state.recovery.isNotEmpty() && !state.busy)
                Text("已离线保存当前全部恢复材料", Modifier.padding(top = 12.dp))
            }
            OutlinedTextField(invite, { invite = it }, label = { Text("注册邀请码") }, singleLine = true, enabled = !state.busy,
                visualTransformation = PasswordVisualTransformation())
            Button(onClick = { val secret = password; val code = invite; password = ""; invite = ""; model.register(origin, username, secret, code, saved) },
                enabled = !state.busy && saved && state.recovery.isNotEmpty()) { Text("确认注册") }
        }
    }
    if (recovery) AccountRecoveryDialog(state.busy, { recovery = false }) { code, next, confirmed ->
        recovery = false; model.recover(origin, username, code, next, confirmed)
    }
}
