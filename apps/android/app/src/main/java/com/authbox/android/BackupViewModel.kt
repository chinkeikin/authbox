package com.authbox.android

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupState(val busy: Boolean = false, val message: String = "请选择 AuthBox 加密备份文件", val stored: BackupInfo? = null,
    val selected: BackupInfo? = null, val preview: BackupInfo? = null, val recoveryKey: String? = null, val preparedExport: String? = null,
    val local: ScreenState = ScreenState(role = "offline"))

class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val store by lazy { OfflineVaultStore(application) }
    private val mutable = MutableStateFlow(BackupState())
    val state = mutable.asStateFlow()
    private var selected: String? = null
    private var recovery: String? = null
    private var export: String? = null
    @Volatile private var foreground = true
    @Volatile private var generation = 0

    init { perform {} }

    private fun perform(success: String = "操作完成", operation: () -> Unit) {
        if (mutable.value.busy) return
        val epoch = generation
        mutable.value = mutable.value.copy(busy = true, message = "正在处理…", recoveryKey = null, local = mutable.value.local.copy(busy = true))
        recovery = null
        viewModelScope.launch {
            var message = success
            try { withContext(Dispatchers.IO) { operation() } }
            catch (error: Exception) {
                message = when (error) {
                    is javax.crypto.AEADBadTagException -> "解密失败：密码、恢复密钥不正确或备份损坏"
                    is java.io.IOException -> "文件操作未确认，原保险库已保留；请检查目标文件后重试"
                    else -> error.message?.takeIf { it.any { c -> c.code > 127 } } ?: "备份内容或输入不符合协议"
                }
            }
            val snapshot = try { withContext(Dispatchers.IO) {
                synchronized(store) {
                    if (!foreground || epoch != generation) { store.lock(); recovery = null }
                    val entities = if (foreground && epoch == generation && store.isUnlocked()) store.entities() else emptyList()
                    BackupState(message = message, stored = store.info(), selected = selected?.let { backupInfo(it) },
                        preview = if (foreground && epoch == generation) store.previewInfo() else null,
                        recoveryKey = if (foreground && epoch == generation) recovery else null, preparedExport = export,
                        local = ScreenState(message = message, role = "offline", unlocked = foreground && store.isUnlocked(),
                            canEdit = foreground && store.isUnlocked(), revision = store.info()?.revision, entities = entities, rows = rows(entities)))
                }
            } } catch (_: Exception) {
                mutable.value.copy(busy = false, message = "离线缓存无法读取，原文件已保留，请保留备份后再处理", local = ScreenState(role = "offline"))
            }
            mutable.value = snapshot
        }
    }

    fun selectFile(uri: Uri) = perform("文件已读取，请输入保险库密码或恢复密钥解密预览") {
        val encoded = BackupFiles.read(getApplication(), uri)
        store.lock(); selected = encoded
    }
    fun preview(secret: String, purpose: String) = perform("已完整解密校验，请核对后确认保存") {
        store.prepareImport(requireNotNull(selected) { "请先选择备份文件" }, secret, purpose)
    }
    fun confirm(replace: Boolean) = perform("已保存为独立离线副本，可离线查看和管理") { store.confirmImport(replace); selected = null }
    fun unlock(secret: String, purpose: String) = perform("离线保险库已解锁") { store.unlock(secret, purpose) }
    internal fun registrationBackup(): String {
        require(foreground) { "请返回前台后重试" }
        return store.registrationBackup()
    }
    fun edit(operation: (MutableList<VaultEntity>) -> Unit) = perform("修改已加密保存在离线副本，请及时再导出备份") { store.edit(operation) }
    fun rewrap(secret: String, purpose: String, saved: Boolean = true) = perform("离线封装已更新，请重新导出备份") { store.rewrap(secret, purpose, saved) }
    fun generateRecoveryKey() = perform("新密钥仅在本次界面显示，保存后再确认更新") {
        require(store.isUnlocked()) { "请先解锁离线保险库" }; recovery = VaultCrypto.newRecoveryKey()
    }
    fun confirmRecoveryKey(saved: Boolean) {
        val key = recovery
        perform("离线恢复密钥已更新，请重新导出备份") { store.rewrap(requireNotNull(key) { "请重新生成恢复密钥" }, "recovery", saved) }
    }
    fun prepareExport() = perform("请选择导出文件保存位置") { export = store.exportBackup() }
    fun clearExport() { export = null; mutable.value = mutable.value.copy(preparedExport = null) }
    fun exportNotCompleted(cancelled: Boolean) {
        mutable.value = mutable.value.copy(message = if (cancelled) "已取消导出" else "本次导出未完成，请重新解锁后导出；目标文件可能为空")
    }
    fun writeExport(uri: Uri, encoded: String) = perform("加密备份已导出并重新读取核对") { BackupFiles.write(getApplication(), uri, encoded) }
    fun lock() {
        generation++; recovery = null
        mutable.value = mutable.value.copy(preview = null, recoveryKey = null,
            local = mutable.value.local.copy(unlocked = false, canEdit = false, entities = emptyList(), rows = emptyList()))
        viewModelScope.launch(Dispatchers.IO) { store.lock() }
    }
    fun onHidden() { foreground = false; lock() }
    fun onVisible() { foreground = true }
    private fun rows(entities: List<VaultEntity>): List<AccountRow> {
        val groups = entities.filter { it.type == "group" }.associate { it.id to it.payload.getString("name") }
        return entities.filter { it.type == "account" && it.payload.isNull("deletedAt") }.map {
            val p = it.payload
            AccountRow(it.id, p.getString("name").ifBlank { p.getString("issuer") }, p.getString("account"),
                groups[p.optString("groupId")] ?: "未分组", VaultCrypto.totp(p), p.getBoolean("favorite"))
        }
    }
    fun tick() {
        val value = mutable.value
        if (foreground && value.local.unlocked && !value.busy) mutable.value = value.copy(local = value.local.copy(rows = rows(value.local.entities)))
    }
    override fun onCleared() { foreground = false; CoroutineScope(Dispatchers.IO).launch { store.close() } }
}
