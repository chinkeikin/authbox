package com.authbox.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

data class AccountRow(val id: String, val title: String, val account: String, val group: String, val code: String, val favorite: Boolean)
data class ScreenState(val busy: Boolean = false, val message: String = "请配置服务地址并登录", val role: String? = null,
    val unlocked: Boolean = false, val pending: Boolean = false, val revision: Long? = null,
    val rows: List<AccountRow> = emptyList(), val recovery: List<String> = emptyList(),
    val entities: List<VaultEntity> = emptyList(), val canEdit: Boolean = false,
    val importPreview: ImportPreview = ImportPreview(), val deviceId: String? = null, val devices: List<DeviceInfo> = emptyList(), val preparedExport: String? = null,
    val registration: RegistrationInfo? = null, val registrationMaterialId: String? = null,
    val history: HistoryPage? = null, val review: ReviewView? = null, val savedVersions: List<SavedVersionInfo> = emptyList(),
    val hasReview: Boolean = false, val conflict: Boolean = false, val syncReady: Boolean = false,
    val autoSync: AutoSyncInfo = AutoSyncInfo(), val quickEnabled: Boolean = false, val rotation: RotationInfo? = null)

class AuthBoxViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("endpoint", 0)
    private val syncPreferences = application.getSharedPreferences("sync", 0)
    private val schedule = SyncSchedule()
    @Volatile private var syncEnabled = syncPreferences.getBoolean("enabled", true)
    private var online = false
    private var screenAllowsSync = false
    private var interacting = false
    private var importing = false
    private var graceJob: Job? = null
    private var graceDeadline = 0L
    private val network = SyncNetwork(application, ::networkChanged)
    val initialOrigin: String = preferences.getString("origin", "") ?: ""
    val initialUsername: String = preferences.getString("username", "") ?: ""
    private val mutable = MutableStateFlow(ScreenState(autoSync = AutoSyncInfo(enabled = syncEnabled)))
    val state = mutable.asStateFlow()
    @Volatile private var repository: AccountRepository? = null
    @Volatile private var foreground = true
    @Volatile private var generation = 0
    @Volatile private var registrationMaterialId: String? = null
    @Volatile private var history: HistoryPage? = null
    private var imports = ImportSession()
    @Volatile private var devices = emptyList<DeviceInfo>()
    @Volatile private var preparedExport: String? = null

    init {
        network.start()
        if (initialOrigin.isNotBlank() && initialUsername.isNotBlank()) perform(initialOrigin, initialUsername) { null }
    }

    private fun perform(origin: String, username: String, resumeSync: Boolean = false, completion: ((Boolean) -> Unit)? = null, operation: (AccountRepository) -> List<String>?) {
        if (mutable.value.busy) { completion?.invoke(false); return }
        val epoch = generation
        registrationMaterialId = null
        mutable.value = mutable.value.copy(busy = true, message = "正在处理…", recovery = emptyList(), registrationMaterialId = null)
        val job = viewModelScope.launch {
            var message = "操作完成"
            var materials: List<String>? = null
            var success = false
            var switched = false
            try {
                withContext(Dispatchers.IO) {
                    var active = repository
                    if (active == null || active.api.origin != origin || active.username != username) {
                        switched = true
                        active?.dispose()
                        synchronized(this@AuthBoxViewModel) { imports = ImportSession() }
                        devices = emptyList()
                        history = null
                        preparedExport = null
                        active = AccountRepository(getApplication(), ApiClient(origin), username)
                        repository = active
                        preferences.edit().putString("origin", origin).putString("username", username).apply()
                    }
                    try { materials = operation(active) }
                    finally { if (!foreground || epoch != generation) active.lock() }
                }
                success = true
            } catch (error: Exception) {
                message = when (error) {
                    is ApiFailure -> when {
                        error.code == "invalid_credentials" -> "账号、密码、恢复码或设备身份不正确"
                        error.code == "invalid_invite" -> "注册邀请码无效或已使用"
                        error.status == 401 -> "登录已失效，请重新登录"
                        error.status == 403 -> "当前设备未获授权，或设备签名已失效"
                        error.status == 429 -> "请求过于频繁，请稍后再试"
                        else -> "服务暂时无法完成操作，请稍后重试"
                    }
                    is SyncConflict, is IllegalStateException -> error.message ?: "操作失败"
                    is IllegalArgumentException -> error.message?.takeIf { it.any { char -> char.code > 127 } } ?: "数据或输入不符合协议，请检查参数"
                    is javax.crypto.AEADBadTagException -> "解锁失败：密码不正确或密文损坏"
                    is java.io.IOException -> "网络连接失败；本地待上传内容已保留"
                    else -> "数据或输入不符合协议，请检查地址、账号与参数"
                }
            }
            if (!foreground || epoch != generation) {
                registrationMaterialId = null
                withContext(Dispatchers.IO) { repository?.lock() }
            }
            if (repository?.role() != "manager") devices = emptyList()
            val snapshot = withContext(Dispatchers.IO) { readState() }
            mutable.value = if (foreground && epoch == generation) snapshot.copy(message = message, recovery = materials ?: emptyList())
                else snapshot.copy(message = message, unlocked = false, rows = emptyList(), entities = emptyList(), recovery = emptyList(), registrationMaterialId = null,
                    history = null, review = null, savedVersions = emptyList(), importPreview = ImportPreview())
            if (switched) mutable.value = mutable.value.copy(autoSync = AutoSyncInfo(enabled = syncEnabled))
            if (switched || (success && resumeSync)) schedule.reset(SystemClock.elapsedRealtime())
            else if (success) schedule.wake(SystemClock.elapsedRealtime())
            updateSyncStatus()
            completion?.invoke(success && foreground && epoch == generation)
        }
        job.invokeOnCompletion { error -> if (error != null) completion?.invoke(false) }
    }

    private fun readState(): ScreenState {
        val active = repository ?: return ScreenState()
        return synchronized(active) {
            val entities = if (foreground && active.isUnlocked()) active.entities() else emptyList()
            val groups = entities.filter { it.type == "group" }.associate { it.id to it.payload.getString("name") }
            val rows = entities.filter { it.type == "account" && it.payload.isNull("deletedAt") }.sortedBy { it.payload.getLong("order") }.map {
                val p = it.payload
                AccountRow(it.id, p.getString("name").ifBlank { p.getString("issuer") }, p.getString("account"),
                    groups[p.optString("groupId")] ?: "未分组", VaultCrypto.totp(p), p.getBoolean("favorite"))
            }
            ScreenState(role = active.role(), unlocked = foreground && active.isUnlocked(), pending = active.hasLocalChanges(), revision = active.revision(), rows = rows,
                entities = entities, canEdit = active.role() == "manager" && !active.hasPending() && !active.hasReview() && !active.hasRotation(), deviceId = active.deviceId(),
                devices = if (foreground && active.role() == "manager") devices else emptyList(),
                preparedExport = preparedExport,
                registration = active.registrationInfo(), registrationMaterialId = if (foreground) registrationMaterialId else null,
                history = if (foreground && active.isUnlocked()) history else null, review = if (foreground) active.reviewView() else null,
                savedVersions = if (foreground && active.role() == "manager" && active.isUnlocked()) active.savedVersions() else emptyList(),
                hasReview = active.hasReview(), conflict = active.hasConflict(), syncReady = active.canSync(), autoSync = mutable.value.autoSync, quickEnabled = active.quickEnabled(), rotation = active.rotationInfo(),
                importPreview = if (foreground && active.isUnlocked()) synchronized(this) { imports.preview(entities) } else ImportPreview())
        }
    }

    fun login(origin: String, user: String, password: String, existing: Boolean) = perform(origin, user, true) { it.login(password, existing); null }
    internal fun prepareQuick(origin: String, user: String, enable: Boolean, ready: (QuickUnlockRequest) -> Unit) {
        var request: QuickUnlockRequest? = null
        perform(origin, user, completion = { success -> if (success && request != null) ready(request!!) else request?.close() }) {
            request = it.prepareQuick(enable); null
        }
    }
    internal fun completeQuick(origin: String, user: String, request: QuickUnlockRequest, cipher: javax.crypto.Cipher, done: () -> Unit) {
        if (repository?.api?.origin != origin || repository?.username != user) { request.close(); done(); return }
        perform(origin, user, true, completion = { request.close(); done() }) { it.completeQuick(request, cipher); null }
    }
    fun disableQuick(origin: String, user: String) = perform(origin, user) { it.disableQuick(); null }
    fun download(origin: String, user: String, password: String, purpose: String = "password") = perform(origin, user, true) { it.download(password, purpose); null }
    fun unlock(origin: String, user: String, password: String, purpose: String = "password") = perform(origin, user, true) { it.unlock(password, purpose); null }
    fun prepareBackupExport(origin: String, user: String) = perform(origin, user) { preparedExport = it.exportBackup(); null }
    fun clearBackupExport() { preparedExport = null; mutable.value = mutable.value.copy(preparedExport = null) }
    fun rewrap(origin: String, user: String, secret: String, purpose: String, saved: Boolean = true) = perform(origin, user) { it.rewrap(secret, purpose, saved); null }
    fun prepareVaultRecoveryKey(origin: String, user: String) = perform(origin, user) {
        require(it.role() == "manager" && it.isUnlocked() && !it.hasPending()) { "请先解锁管理设备并处理待确认上传" }
        listOf(VaultCrypto.newRecoveryKey())
    }
    fun confirmVaultRecoveryKey(origin: String, user: String, saved: Boolean) {
        val key = mutable.value.recovery.singleOrNull()?.takeIf { it.startsWith("ABR1-") }
        perform(origin, user) { it.rewrap(requireNotNull(key) { "请重新生成保险库恢复密钥" }, "recovery", saved); null }
    }
    fun prepareRotation(origin: String, user: String, secret: String, purpose: String, password: String) = perform(origin, user) {
        listOf(it.prepareRotation(secret, purpose, password))
    }
    fun cancelRotation(origin: String, user: String) = perform(origin, user) { it.cancelRotation(); null }
    fun confirmRotation(origin: String, user: String, id: String, saved: Boolean) = perform(origin, user) {
        it.confirmRotation(id, saved); devices = emptyList(); history = null; null
    }
    fun upload(origin: String, user: String) = perform(origin, user) { it.upload(); history = null; null }
    fun loadHistory(origin: String, user: String, before: Long? = null) = perform(origin, user) { history = it.history(before); null }
    fun prepareReview(origin: String, user: String, secret: String, purpose: String, revision: Long? = null, oldSecret: String? = null, oldPurpose: String = "password") = perform(origin, user) {
        it.prepareReview(secret, purpose, revision, oldSecret, oldPurpose); null
    }
    fun confirmReview(origin: String, user: String, id: String, source: Boolean, confirmed: Boolean) = perform(origin, user, true) {
        it.confirmReview(id, source, confirmed); history = null; null
    }
    fun cancelReview(origin: String, user: String) = perform(origin, user) { it.cancelReview(); null }
    fun exportSavedVersion(origin: String, user: String, id: String) = perform(origin, user) { preparedExport = it.exportSavedVersion(id); null }
    fun deleteSavedVersion(origin: String, user: String, id: String, confirmed: Boolean) = perform(origin, user) { it.deleteSavedVersion(id, confirmed); null }
    fun refresh(origin: String, user: String) = perform(origin, user) { it.refresh(); null }
    fun logout(origin: String, user: String) = perform(origin, user) { it.logout(); null }
    fun loadDevices(origin: String, user: String) = perform(origin, user) { devices = it.devices(); null }
    fun updateDevice(origin: String, user: String, id: String, approve: Boolean) = perform(origin, user) {
        it.updateDevice(id, approve); devices = if (it.role() == "manager") it.devices() else emptyList(); null
    }
    fun changePassword(origin: String, user: String, old: String, next: String) = perform(origin, user) {
        it.changePassword(old, next); devices = emptyList(); null
    }
    fun prepareRecoveryCodes(origin: String, user: String) = perform(origin, user) {
        require(it.role() == "manager") { "当前设备没有管理权限" }; accountRecoveryCodes()
    }
    fun rotateRecoveryCodes(origin: String, user: String, saved: Boolean) {
        val codes = mutable.value.recovery.toList()
        perform(origin, user) { it.rotateRecoveryCodes(codes, saved); null }
    }
    fun recover(origin: String, user: String, code: String, password: String, confirmed: Boolean) = perform(origin, user) {
        it.recover(code, password, confirmed); devices = emptyList(); null
    }
    private fun registrationMaterials(active: AccountRepository, materials: Pair<String, List<String>>, target: Uri?, epoch: Int): List<String> {
        val kit = active.registrationKit(materials)
        require(foreground && epoch == generation) { "界面已锁定，请返回后重新生成恢复材料" }
        if (target != null) try { RecoveryKit.write(getApplication(), target, kit) }
        catch (error: Exception) { throw IllegalStateException("恢复材料保存未确认，文件可能不完整；请重新生成并保存后再注册，原离线副本已保留", error) }
        require(foreground && epoch == generation) { "材料保存期间界面已锁定，请检查文件并重新生成后确认" }
        val value = RecoveryKit.inspect(kit)
        registrationMaterialId = value.getString("vaultId")
        return listOf("服务地址：${active.api.origin}", "平台账号：${active.username}", "保险库 UUID：${registrationMaterialId}",
            "保存日期：${value.getString("createdAt")}", "材料包格式：v1 · 备份格式：v1",
            if (target == null) "请将当前全部材料抄写并保存到手机以外" else "恢复材料文件已写入并读回复核，请另存到手机以外",
            "保险库恢复密钥（解密备份）：", materials.first, "账号恢复码（重设登录，每个只能用一次）：") + materials.second
    }
    fun prepare(origin: String, user: String, password: String, target: Uri? = null) {
        val epoch = generation
        perform(origin, user) { registrationMaterials(it, it.prepareRegistration(password), target, epoch) }
    }
    fun prepareFromOffline(origin: String, user: String, source: () -> String, secret: String, purpose: String, password: String, target: Uri) {
        val epoch = generation
        perform(origin, user) {
            registrationMaterials(it, it.prepareRegistrationFromBackup(source(), secret, purpose, password), target, epoch)
        }
    }
    fun register(origin: String, user: String, password: String, invite: String, saved: Boolean) = perform(origin, user) {
        it.register(password, invite, saved); emptyList()
    }
    fun favorite(origin: String, user: String, id: String) = perform(origin, user) {
        it.edit { entries ->
            val account = entries.first { entry -> entry.id == id }
            account.payload.put("favorite", !account.payload.getBoolean("favorite"))
        }; null
    }

    fun edit(origin: String, user: String, operation: (MutableList<VaultEntity>) -> Unit) = perform(origin, user) { it.edit(operation); null }
    fun importQr(origin: String, user: String, qr: String) = perform(origin, user) {
        require(it.isUnlocked() && it.role() == "manager" && !it.hasPending())
        val chunk = OtpImport.parse(qr)
        synchronized(this) { require(foreground); imports.add(chunk) }; null
    }
    fun importImages(origin: String, user: String, images: List<Uri>) = perform(origin, user) {
        require(it.isUnlocked() && it.role() == "manager" && !it.hasPending())
        require(images.size in 1..100)
        val chunks = mutableListOf<ImportChunk>()
        for (image in images) for (raw in QrImages.read(getApplication(), image)) {
            require(chunks.size < 100) { "本次最多读取 100 张二维码" }; chunks.add(OtpImport.parse(raw))
        }
        synchronized(this) { require(foreground); val session = ImportSession(); session.addAll(chunks); imports = session }; null
    }
    fun confirmImport(origin: String, user: String, keep: Boolean, skip: Boolean) = perform(origin, user) {
        val session = synchronized(this) { imports }
        it.edit { entries -> session.apply(entries, keep, skip) }
        synchronized(this) { if (imports === session) imports = ImportSession() }; null
    }
    fun cancelImport() { synchronized(this) { imports = ImportSession() }; mutable.value = mutable.value.copy(importPreview = ImportPreview()) }

    fun lock() {
        graceJob?.cancel(); graceJob = null; graceDeadline = 0
        hidePlaintext()
        viewModelScope.launch(Dispatchers.IO) { repository?.lock() }
    }
    private fun hidePlaintext() {
        importing = false
        generation++; registrationMaterialId = null; history = null
        synchronized(this) { imports = ImportSession() }
        devices = emptyList()
        mutable.value = mutable.value.copy(unlocked = false, rows = emptyList(), entities = emptyList(), recovery = emptyList(), registrationMaterialId = null,
            history = null, review = null, savedVersions = emptyList(), devices = emptyList(), importPreview = ImportPreview())
        updateSyncStatus()
    }
    fun onHidden(graceMillis: Long = 0) {
        val state = mutable.value
        val retain = graceMillis in 1..60000 && !state.busy && state.unlocked && !interacting && !importing && state.recovery.isEmpty() &&
            !state.hasReview && state.importPreview.received == 0 && state.registration == null && preparedExport == null
        foreground = false; network.close()
        if (!retain) lock() else {
            graceJob?.cancel(); hidePlaintext()
            graceDeadline = SystemClock.elapsedRealtime() + graceMillis
            graceJob = viewModelScope.launch { delay(graceMillis); lock() }
        }
    }
    fun onVisible() {
        foreground = true; network.start()
        val deadline = graceDeadline
        graceJob?.cancel(); graceJob = null; graceDeadline = 0
        if (deadline > 0) repository?.let { active -> perform(active.api.origin, active.username) {
            if (SystemClock.elapsedRealtime() >= deadline) it.lock(); null
        } }
        schedule.wake(SystemClock.elapsedRealtime()); updateSyncStatus()
    }
    internal fun networkChanged(connected: Boolean) {
        if (connected && !online) schedule.wake(SystemClock.elapsedRealtime())
        online = connected
        updateSyncStatus()
    }
    fun setSyncScreenAllowed(allowed: Boolean) { screenAllowsSync = allowed }
    fun setSyncInteraction(active: Boolean) { interacting = active }
    fun setImportInteraction(active: Boolean) { importing = active }
    fun setAutoSync(enabled: Boolean) {
        syncEnabled = enabled
        syncPreferences.edit().putBoolean("enabled", enabled).apply()
        if (enabled) schedule.reset(SystemClock.elapsedRealtime())
        updateSyncStatus()
    }
    fun retryAutoSync() { schedule.reset(SystemClock.elapsedRealtime()); automaticSync(requested = true) }
    private fun updateSyncStatus() {
        val state = mutable.value
        val message = when {
            state.autoSync.running -> "自动同步：正在检查与同步…"
            !syncEnabled -> "自动同步：已暂停"
            state.rotation != null -> "自动同步：等待确认或取消主密钥轮换"
            state.conflict || state.hasReview -> "自动同步：等待处理冲突或比较"
            schedule.blocked != null -> requireNotNull(schedule.blocked)
            !foreground || !state.unlocked -> "自动同步：锁定中，解锁后检查"
            !state.syncReady -> "自动同步：请先重新登录以恢复会话"
            !online -> "自动同步：无可用网络，修改保存在本机"
            !screenAllowsSync || interacting || state.recovery.isNotEmpty() || state.importPreview.received > 0 -> "自动同步：编辑或敏感操作期间暂停"
            schedule.failures > 0 -> "自动同步：网络或服务暂不可用，${((schedule.nextAttempt - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000} 秒后重试"
            else -> "自动同步：已启用，前台每 60 秒检查"
        }
        mutable.value = state.copy(autoSync = state.autoSync.copy(enabled = syncEnabled, message = message))
    }
    private fun automaticSync(requested: Boolean = false) {
        val before = mutable.value
        val now = SystemClock.elapsedRealtime()
        updateSyncStatus()
        if (!syncEnabled || !foreground || !online || (!screenAllowsSync && !requested) || interacting || importing || before.busy || !before.unlocked ||
            !before.syncReady || before.recovery.isNotEmpty() || before.importPreview.received > 0 || before.preparedExport != null ||
            before.registration != null || schedule.blocked != null || (!requested && now < schedule.nextAttempt)) return
        val active = repository ?: return
        val epoch = generation
        mutable.value = before.copy(busy = true, autoSync = before.autoSync.copy(running = true, message = "自动同步：正在检查与同步…"))
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        if (foreground && epoch == generation) active.synchronize { foreground && epoch == generation && syncEnabled } else null
                    } finally { if (!foreground || epoch != generation) active.lock() }
                }
                if (result != null) {
                    schedule.success(SystemClock.elapsedRealtime())
                    if (result != SyncResult.CURRENT) history = null
                    mutable.value = mutable.value.copy(autoSync = mutable.value.autoSync.copy(lastSuccessAt = System.currentTimeMillis()))
                }
            } catch (error: Exception) {
                val retry = error is java.io.IOException || (error is ApiFailure && (error.status >= 500 || error.status in setOf(408, 429)))
                val message = when {
                    error is SyncConflict -> "自动同步：等待处理冲突，本地修改已保留"
                    error is SyncUnlockRequired -> "自动同步：主密钥已变化，请用新密码或恢复密钥下载"
                    error is ApiFailure && error.status in setOf(401, 403) -> "自动同步：登录或设备授权已失效，请重新登录"
                    error is IllegalStateException -> "自动同步：会话未确认，请重新登录"
                    retry -> "自动同步：网络或服务暂不可用"
                    else -> "自动同步：数据校验或协议异常；本地内容已保留，请手动检查后重试"
                }
                if (foreground && epoch == generation && syncEnabled) schedule.failure(SystemClock.elapsedRealtime(), message, retry)
                if (error is ApiFailure && error.status in setOf(401, 403)) {
                    lock()
                    withContext(Dispatchers.IO) { active.lock() }
                }
            }
            if (!foreground || epoch != generation) withContext(Dispatchers.IO) { active.lock() }
            val snapshot = withContext(Dispatchers.IO) { readState() }
            val current = mutable.value
            mutable.value = if (foreground && epoch == generation) snapshot.copy(message = current.message, recovery = current.recovery,
                autoSync = current.autoSync.copy(running = false))
            else current.copy(busy = false, autoSync = current.autoSync.copy(running = false))
            updateSyncStatus()
        }
    }
    fun tick() {
        automaticSync()
        if (!foreground || mutable.value.busy || !mutable.value.unlocked) return
        val current = mutable.value
        val accounts = current.entities.filter { it.type == "account" }.associateBy { it.id }
        val rows = current.rows.map { row -> row.copy(code = accounts[row.id]?.let { VaultCrypto.totp(it.payload) } ?: "") }
        mutable.value = current.copy(rows = rows)
    }
    override fun onCleared() {
        foreground = false
        network.close()
        CoroutineScope(Dispatchers.IO).launch { repository?.dispose() }
    }
}
