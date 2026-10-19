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

data class AccountRow(val id: String, val title: String, val account: String, val group: String, val code: String, val favorite: Boolean)
data class ScreenState(val busy: Boolean = false, val message: String = "请配置服务地址并登录", val role: String? = null,
    val unlocked: Boolean = false, val pending: Boolean = false, val revision: Long? = null,
    val rows: List<AccountRow> = emptyList(), val recovery: List<String> = emptyList(),
    val entities: List<VaultEntity> = emptyList(), val canEdit: Boolean = false,
    val importPreview: ImportPreview = ImportPreview(), val deviceId: String? = null, val devices: List<DeviceInfo> = emptyList(), val preparedExport: String? = null)

class AuthBoxViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("endpoint", 0)
    val initialOrigin: String = preferences.getString("origin", "") ?: ""
    val initialUsername: String = preferences.getString("username", "") ?: ""
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    @Volatile private var repository: AccountRepository? = null
    @Volatile private var foreground = true
    private var imports = ImportSession()
    @Volatile private var devices = emptyList<DeviceInfo>()
    @Volatile private var preparedExport: String? = null

    init {
        if (initialOrigin.isNotBlank() && initialUsername.isNotBlank()) perform(initialOrigin, initialUsername) { null }
    }

    private fun perform(origin: String, username: String, operation: (AccountRepository) -> List<String>?) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = "正在处理…", recovery = emptyList())
        viewModelScope.launch {
            var message = "操作完成"
            var materials: List<String>? = null
            try {
                withContext(Dispatchers.IO) {
                    var active = repository
                    if (active == null || active.api.origin != origin || active.username != username) {
                        active?.dispose()
                        synchronized(this@AuthBoxViewModel) { imports = ImportSession() }
                        devices = emptyList()
                        preparedExport = null
                        active = AccountRepository(getApplication(), ApiClient(origin), username)
                        repository = active
                        preferences.edit().putString("origin", origin).putString("username", username).apply()
                    }
                    try { materials = operation(active) }
                    finally { if (!foreground) active.lock() }
                }
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
            if (!foreground) withContext(Dispatchers.IO) { repository?.lock() }
            if (repository?.role() != "manager") devices = emptyList()
            val snapshot = withContext(Dispatchers.IO) { readState() }
            mutable.value = if (foreground) snapshot.copy(message = message, recovery = materials ?: emptyList())
                else snapshot.copy(message = message, unlocked = false, rows = emptyList(), entities = emptyList(), recovery = emptyList(), importPreview = ImportPreview())
        }
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
                entities = entities, canEdit = active.role() == "manager" && !active.hasPending(), deviceId = active.deviceId(),
                devices = if (foreground && active.role() == "manager") devices else emptyList(),
                preparedExport = preparedExport,
                importPreview = if (foreground && active.isUnlocked()) synchronized(this) { imports.preview(entities) } else ImportPreview())
        }
    }

    fun login(origin: String, user: String, password: String, existing: Boolean) = perform(origin, user) { it.login(password, existing); null }
    fun download(origin: String, user: String, password: String, purpose: String = "password") = perform(origin, user) { it.download(password, purpose); null }
    fun unlock(origin: String, user: String, password: String, purpose: String = "password") = perform(origin, user) { it.unlock(password, purpose); null }
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
    fun upload(origin: String, user: String) = perform(origin, user) { it.upload(); null }
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
    fun prepare(origin: String, user: String, password: String) = perform(origin, user) {
        val recovery = it.prepareRegistration(password)
        listOf("保险库恢复密钥：", recovery.first, "账号恢复码（每个只能用一次）：") + recovery.second
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
        synchronized(this) { imports = ImportSession() }
        devices = emptyList()
        mutable.value = mutable.value.copy(unlocked = false, rows = emptyList(), entities = emptyList(), recovery = emptyList(), devices = emptyList(), importPreview = ImportPreview())
        viewModelScope.launch(Dispatchers.IO) { repository?.lock() }
    }
    fun onHidden() { foreground = false; lock() }
    fun onVisible() { foreground = true }
    fun tick() {
        if (!foreground || mutable.value.busy || !mutable.value.unlocked) return
        val current = mutable.value
        val accounts = current.entities.filter { it.type == "account" }.associateBy { it.id }
        val rows = current.rows.map { row -> row.copy(code = accounts[row.id]?.let { VaultCrypto.totp(it.payload) } ?: "") }
        mutable.value = current.copy(rows = rows)
    }
    override fun onCleared() {
        foreground = false
        CoroutineScope(Dispatchers.IO).launch { repository?.dispose() }
    }
}
