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

data class AccountRow(val id: String, val title: String, val account: String, val group: String, val code: String, val favorite: Boolean)
data class ScreenState(val busy: Boolean = false, val message: String = "请配置服务地址并登录", val role: String? = null,
    val unlocked: Boolean = false, val pending: Boolean = false, val revision: Long? = null,
    val rows: List<AccountRow> = emptyList(), val recovery: List<String> = emptyList())

class AuthBoxViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("endpoint", 0)
    val initialOrigin: String = preferences.getString("origin", "") ?: ""
    val initialUsername: String = preferences.getString("username", "") ?: ""
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    @Volatile private var repository: AccountRepository? = null
    @Volatile private var foreground = true

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
                        error.code == "invalid_credentials" -> "账号、密码或设备身份不正确"
                        error.code == "invalid_invite" -> "注册邀请码无效或已使用"
                        error.status == 401 -> "登录已失效，请重新登录"
                        error.status == 403 -> "当前设备未获授权，或设备签名已失效"
                        error.status == 429 -> "请求过于频繁，请稍后再试"
                        else -> "服务暂时无法完成操作，请稍后重试"
                    }
                    is SyncConflict, is IllegalStateException -> error.message ?: "操作失败"
                    is javax.crypto.AEADBadTagException -> "解锁失败：密码不正确或密文损坏"
                    is java.io.IOException -> "网络连接失败；本地待上传内容已保留"
                    else -> "数据或输入不符合协议，请检查地址、账号与参数"
                }
            }
            if (!foreground) withContext(Dispatchers.IO) { repository?.lock() }
            val snapshot = withContext(Dispatchers.IO) { readState() }
            mutable.value = if (foreground) snapshot.copy(message = message, recovery = materials ?: emptyList())
                else snapshot.copy(message = message, unlocked = false, rows = emptyList(), recovery = emptyList())
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
            ScreenState(role = active.role(), unlocked = foreground && active.isUnlocked(), pending = active.hasPending(), revision = active.revision(), rows = rows)
        }
    }

    fun login(origin: String, user: String, password: String, existing: Boolean) = perform(origin, user) { it.login(password, existing); null }
    fun download(origin: String, user: String, password: String) = perform(origin, user) { it.download(password); null }
    fun unlock(origin: String, user: String, password: String) = perform(origin, user) { it.unlock(password); null }
    fun upload(origin: String, user: String) = perform(origin, user) { it.upload(); null }
    fun refresh(origin: String, user: String) = perform(origin, user) { it.refresh(); null }
    fun logout(origin: String, user: String) = perform(origin, user) { it.logout(); null }
    fun prepare(origin: String, user: String, password: String) = perform(origin, user) {
        val recovery = it.prepareRegistration(password)
        listOf("保险库恢复密钥：", recovery.first, "账号恢复码（每个只能用一次）：") + recovery.second
    }
    fun register(origin: String, user: String, password: String, invite: String, saved: Boolean) = perform(origin, user) {
        it.register(password, invite, saved); emptyList()
    }
    fun favorite(origin: String, user: String, id: String) = perform(origin, user) {
        val entities = it.entities()
        val account = entities.first { entry -> entry.id == id }
        account.payload.put("favorite", !account.payload.getBoolean("favorite"))
        it.stage(entities); it.upload(); null
    }

    fun lock() {
        mutable.value = mutable.value.copy(unlocked = false, rows = emptyList(), recovery = emptyList())
        viewModelScope.launch(Dispatchers.IO) { repository?.lock() }
    }
    fun onHidden() { foreground = false; lock() }
    fun onVisible() { foreground = true }
    fun tick() {
        if (!foreground || mutable.value.busy || !mutable.value.unlocked) return
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { readState() }
            if (foreground && !mutable.value.busy && mutable.value.unlocked) mutable.value = snapshot.copy(message = mutable.value.message)
        }
    }
    override fun onCleared() {
        foreground = false
        CoroutineScope(Dispatchers.IO).launch { repository?.dispose() }
    }
}
