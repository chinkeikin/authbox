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
import androidx.compose.ui.platform.testTag
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
    private val backups: BackupViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { AuthBoxScreen(model, backups) } } }
    }
}

@Composable private fun AuthBoxScreen(model: AuthBoxViewModel, backups: BackupViewModel) {
    val state by model.state.collectAsState()
    val backupState by backups.state.collectAsState()
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
    var recoveryUnlock by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf(false) }
    var offlineRegistration by remember { mutableStateOf(false) }
    var syncReview by remember { mutableStateOf(false) }
    var kitTarget by remember { mutableStateOf<Uri?>(null) }
    val recoveryDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { kitTarget = it }
    val chooseRecoveryTarget = { recoveryDocument.launch("authbox-${System.currentTimeMillis()}.authbox-recovery") }
    var exportPayload by remember { mutableStateOf<String?>(null) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) backups.selectFile(uri) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.authbox.backup+json")) { uri ->
        val payload = exportPayload; exportPayload = null
        if (uri != null && payload != null) backups.writeExport(uri, payload)
        else backups.exportNotCompleted(uri == null)
    }
    var pendingImages by remember { mutableStateOf(emptyList<Uri>()) }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected -> pendingImages = selected }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    SideEffect { model.setSyncScreenAllowed(!settings && !files && !syncReview && !recovery && !registration) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                password = ""; vaultPassword = ""; invite = ""; saved = false
                model.onHidden(); backups.onHidden()
            } else if (event == Lifecycle.Event.ON_RESUME) { model.onVisible(); backups.onVisible() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); model.tick(); backups.tick() } }
    LaunchedEffect(state.recovery) { saved = false }
    LaunchedEffect(state.unlocked, pendingImages) {
        if (state.unlocked && pendingImages.isNotEmpty()) {
            val selected = pendingImages; pendingImages = emptyList()
            model.importImages(origin, username, selected)
        }
    }
    LaunchedEffect(state.preparedExport, backupState.preparedExport) {
        val payload = state.preparedExport ?: backupState.preparedExport
        if (payload != null && exportPayload == null) {
            exportPayload = payload
            model.clearBackupExport(); backups.clearExport()
            backups.exportStarted()
            val header = VaultCrypto.inspect(payload)
            export.launch("authbox-${header.getString("vaultId")}-r${header.getLong("revision")}-${System.currentTimeMillis()}.authbox-backup")
        }
    }
    if (syncReview) {
        SyncReviewScreen(state, backupState.message, { syncReview = false }, { model.loadHistory(origin, username, it) },
            { secret, purpose, revision, old, oldPurpose -> model.prepareReview(origin, username, secret, purpose, revision, old, oldPurpose) },
            { id, source, confirmed -> model.confirmReview(origin, username, id, source, confirmed) },
            { model.cancelReview(origin, username) }, { model.upload(origin, username) }, { model.exportSavedVersion(origin, username, it) },
            { id, confirmed -> model.deleteSavedVersion(origin, username, id, confirmed) })
        return
    }
    if (files) {
        if (offlineRegistration && backupState.stored != null) {
            OfflineRegistrationScreen(state, requireNotNull(backupState.stored), origin, username, kitTarget, chooseRecoveryTarget,
                { model.lock(); offlineRegistration = false }, { model.lock(); files = false; offlineRegistration = false; local = false },
                { nextOrigin, nextUser, secret, purpose, nextPassword, target ->
                    origin = nextOrigin; username = nextUser
                    model.prepareFromOffline(nextOrigin, nextUser, backups::registrationBackup, secret, purpose, nextPassword, target)
                }, model::register)
        } else if (local && backupState.local.unlocked) {
            VaultManagementScreen(backupState.local, backups::lock, {}, backups::edit, { local = false }, "备份与保险库")
        } else BackupScreen(state, backupState, model, backups, origin, username,
            { backups.lock(); files = false; local = false }, { document.launch(arrayOf("*/*")) }, { model.lock(); local = true },
            { kitTarget = null; offlineRegistration = true; model.lock() })
        return
    }
    if (settings) {
        AccountSecurityScreen(state, { settings = false }, { model.loadDevices(origin, username) },
            { id, approve -> model.updateDevice(origin, username, id, approve) },
            { old, next -> model.changePassword(origin, username, old, next) },
            { model.prepareRecoveryCodes(origin, username) }, { model.rotateRecoveryCodes(origin, username, it) },
            { settings = false; files = true }, model::setAutoSync, model::retryAutoSync)
        return
    }
    if (state.unlocked) {
        VaultManagementScreen(state, model::lock, { model.upload(origin, username) }, { operation -> model.edit(origin, username, operation) }, { settings = true }, onReview = { syncReview = true }, onInteraction = model::setSyncInteraction) {
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
        TextButton({ files = true }, enabled = !state.busy) { Text("备份与保险库") }
        if (state.role == "manager") TextButton({ syncReview = true }, enabled = !state.busy) { Text("冲突与历史") }
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
        Row { Checkbox(recoveryUnlock, { recoveryUnlock = it; vaultPassword = "" }); Text("使用保险库恢复密钥 ABR1", Modifier.padding(top = 12.dp)) }
        OutlinedTextField(vaultPassword, { vaultPassword = it }, label = { Text(if (recoveryUnlock) "保险库恢复密钥 ABR1" else "保险库密码（可离线解锁）") }, singleLine = true, enabled = !state.busy,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { val secret = vaultPassword; vaultPassword = ""; model.download(origin, username, secret, if (recoveryUnlock) "recovery" else "password") }, enabled = !state.busy && state.role != null && !state.pending && !state.hasReview) { Text("下载并解锁") }
            OutlinedButton(onClick = { val secret = vaultPassword; vaultPassword = ""; model.unlock(origin, username, secret, if (recoveryUnlock) "recovery" else "password") }, enabled = !state.busy) { Text("离线解锁") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.lock() }, enabled = !state.busy) { Text("锁定") }
            OutlinedButton(onClick = { model.refresh(origin, username) }, enabled = !state.busy && state.role != null) { Text("刷新登录") }
            OutlinedButton(onClick = { model.logout(origin, username) }, enabled = !state.busy && state.role != null) { Text("退出登录") }
        }
        if (state.pending) {
            Text("有待上传的本地修改；冲突或网络失败时会保留。")
            Button(onClick = { model.upload(origin, username) }, enabled = !state.busy && state.role == "manager" && !state.hasReview) { Text("重试上传") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.message)
        HorizontalDivider()
        TextButton(onClick = { registration = !registration }, enabled = !state.busy) { Text("新建平台账号") }
        if (registration) {
            Text("先选择恢复材料包保存位置，再填写账号和保险库密码。材料包为明文，应另存到手机以外；不包含保险库备份或密码。重新生成会替换尚未注册的材料。")
            OutlinedButton(chooseRecoveryTarget, enabled = !state.busy) { Text("选择恢复材料包保存位置") }
            Text(if (kitTarget == null) "尚未选择恢复包保存位置" else "已选择恢复包保存位置")
            Button(onClick = { val secret = vaultPassword; vaultPassword = ""; saved = false; model.prepare(origin, username, secret, kitTarget) }, enabled = !state.busy && state.role == null && kitTarget != null) { Text("生成保险库与恢复材料") }
            state.recovery.forEach { Text(it) }
            Row {
                Checkbox(saved, { saved = it }, enabled = state.registrationMaterialId != null && state.recovery.isNotEmpty() && !state.busy, modifier = Modifier.testTag("registration-saved"))
                Text("已离线保存当前全部恢复材料", Modifier.padding(top = 12.dp))
            }
            OutlinedTextField(invite, { invite = it }, label = { Text("注册邀请码") }, singleLine = true, enabled = !state.busy,
                visualTransformation = PasswordVisualTransformation())
            Button(onClick = { val secret = password; val code = invite; password = ""; invite = ""; model.register(origin, username, secret, code, saved) },
                enabled = !state.busy && saved && state.registrationMaterialId != null && state.recovery.isNotEmpty()) { Text("确认注册") }
        }
    }
    if (recovery) AccountRecoveryDialog(state.busy, { recovery = false }) { code, next, confirmed ->
        recovery = false; model.recover(origin, username, code, next, confirmed)
    }
}
