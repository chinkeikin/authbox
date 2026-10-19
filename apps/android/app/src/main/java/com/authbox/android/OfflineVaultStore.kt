package com.authbox.android

import android.content.Context

data class BackupInfo(val vaultId: String, val revision: Long, val entities: Int, val accounts: Int? = null, val groups: Int? = null, val tags: Int? = null)

internal fun backupInfo(encoded: String, entities: List<VaultEntity>? = null): BackupInfo {
    val header = VaultCrypto.inspect(encoded)
    return BackupInfo(header.getString("vaultId"), header.getLong("revision"), header.getJSONArray("entities").length(),
        entities?.count { it.type == "account" }, entities?.count { it.type == "group" }, entities?.count { it.type == "tag" })
}

class OfflineVaultStore(context: Context, namespace: String = "authbox.offline.v1") : AutoCloseable {
    private val box = KeystoreBox(context, namespace)
    private val quick = QuickUnlockStore(context, namespace)
    private val identity = namespace
    private var encoded: String? = box.read("backup")?.let { decodeUtf8(it) }?.also { VaultCrypto.inspect(it) }
    private var opened: OpenVault? = null
    private var preview: OpenVault? = null

    @Synchronized fun info(): BackupInfo? = encoded?.let { backupInfo(it, opened?.entities) }
    @Synchronized fun previewInfo(): BackupInfo? = preview?.let { backupInfo(canonical(it.snapshot), it.entities) }
    @Synchronized fun isUnlocked(): Boolean = opened != null
    @Synchronized internal fun quickEnabled(): Boolean = encoded?.let { quick.enabled(quickBinding(VaultCrypto.inspect(it))) } ?: false
    @Synchronized internal fun prepareQuick(enable: Boolean): QuickUnlockRequest = quick.prepare(quickBinding(VaultCrypto.inspect(requireNotNull(encoded))),
        if (enable) requireNotNull(opened) { "请先使用密码解锁离线副本" }.vmk else null)
    @Synchronized internal fun completeQuick(request: QuickUnlockRequest, cipher: javax.crypto.Cipher) {
        try {
            val header = VaultCrypto.inspect(requireNotNull(encoded))
            require(request.namespace == identity && canonical(request.binding) == canonical(quickBinding(header))) { "离线副本已变化，请重新认证" }
            val vmk = request.finish(cipher)
            val next = try { VaultCrypto.authenticate(header, vmk) } finally { vmk.fill(0) }
            lock(); opened = next
        } finally { request.close() }
    }
    @Synchronized internal fun disableQuick() = quick.disable()
    @Synchronized fun entities(): List<VaultEntity> = requireNotNull(opened) { "请先解锁离线保险库" }.entities.map { it.copy(payload = it.payload.copy()) }

    @Synchronized fun prepareImport(value: String, secret: String, purpose: String) {
        val candidate = VaultCrypto.restore(value, secret, purpose)
        lock()
        preview = candidate
    }

    @Synchronized fun confirmImport(replace: Boolean) {
        require(encoded == null || replace) { "请确认替换已有离线副本，原同步账号不受影响" }
        val candidate = requireNotNull(preview) { "请重新解密并确认备份预览" }
        val value = canonical(candidate.snapshot)
        quick.disable()
        box.write("backup", value.toByteArray(Charsets.UTF_8))
        encoded = value
        opened?.close(); opened = candidate; preview = null
    }

    @Synchronized fun unlock(secret: String, purpose: String) {
        val candidate = VaultCrypto.restore(requireNotNull(encoded) { "请先导入备份文件" }, secret, purpose)
        lock(); opened = candidate
    }

    @Synchronized fun exportBackup(): String = canonical(requireNotNull(opened) { "请先解锁离线保险库" }.snapshot)
    @Synchronized internal fun registrationBackup(): String = requireNotNull(encoded) { "请先导入并确认保存离线副本" }

    @Synchronized fun edit(operation: (MutableList<VaultEntity>) -> Unit) {
        val current = requireNotNull(opened) { "请先解锁离线保险库" }
        val candidate = OpenVault(current.vmk.copyOf(), current.snapshot.copy(), current.entities.map { it.copy(payload = it.payload.copy()) }.toMutableList())
        var accepted = false
        try {
            operation(candidate.entities)
            val value = VaultCrypto.snapshot(candidate, current.snapshot.getLong("revision") + 1)
            candidate.snapshot = VaultCrypto.inspect(value)
            box.write("backup", value.toByteArray(Charsets.UTF_8))
            encoded = value; current.close(); opened = candidate; accepted = true
        } finally { if (!accepted) candidate.close() }
    }

    @Synchronized fun rewrap(secret: String, purpose: String, saved: Boolean = true) {
        require(purpose != "recovery" || saved) { "请先离线保存新保险库恢复密钥" }
        val current = requireNotNull(opened) { "请先解锁离线保险库" }
        val value = VaultCrypto.rewrap(current, secret, purpose, current.snapshot.getLong("revision") + 1)
        val next = VaultCrypto.inspect(value)
        box.write("backup", value.toByteArray(Charsets.UTF_8))
        encoded = value; current.snapshot = next
    }

    @Synchronized fun lock() { opened?.close(); opened = null; preview?.close(); preview = null }
    override fun close() = lock()
}
