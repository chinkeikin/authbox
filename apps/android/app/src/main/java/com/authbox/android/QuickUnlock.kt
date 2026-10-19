package com.authbox.android

import android.content.Context
import android.app.KeyguardManager
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun quickBinding(header: JSONObject): JSONObject = obj("vaultId" to header.getString("vaultId"),
    "keyGeneration" to header.getLong("keyGeneration"), "wrapsDigest" to digest(header.getJSONObject("wraps")))

internal class QuickUnlockRequest(val namespace: String, val binding: JSONObject, val cipher: Cipher,
    private val enabling: Boolean, private val input: ByteArray, private val save: (JSONObject) -> Unit) : AutoCloseable {
    private val deadline = SystemClock.elapsedRealtime() + 120000
    private var closed = false
    @Synchronized fun finish(authenticated: Cipher): ByteArray {
        check(!closed && SystemClock.elapsedRealtime() < deadline && authenticated === cipher) { "认证已过期，请重试" }
        try {
            val result = cipher.doFinal(input)
            if (!enabling) return result.also { require(it.size == 32) }
            try {
                save(obj("version" to 1, "binding" to binding, "nonce" to b64(cipher.iv), "ciphertext" to b64(result)))
                return input.copyOf()
            } finally { result.fill(0) }
        } finally { close() }
    }
    @Synchronized override fun close() { input.fill(0); closed = true }
}

internal class QuickUnlockStore(context: Context, private val namespace: String) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val box = KeystoreBox(context, namespace)
    private val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "$namespace.unlock"
    private val packageName = context.packageName
    fun enabled(binding: JSONObject): Boolean = runCatching {
        val saved = box.read("quick-unlock")?.let { parseObject(decodeUtf8(it)) } ?: return false
        number(saved, "version") == 1L && keys.containsAlias(alias) && canonical(saved.getJSONObject("binding")) == canonical(binding)
    }.getOrDefault(false)
    fun disable() { box.delete("quick-unlock"); if (keys.containsAlias(alias)) keys.deleteEntry(alias) }
    fun prepare(binding: JSONObject, vmk: ByteArray? = null): QuickUnlockRequest {
        if (Build.VERSION.SDK_INT < 30) throw IllegalArgumentException("系统便捷解锁需要 Android 11 或以上，请使用保险库密码")
        require(keyguard.isDeviceSecure) { "请先设置系统安全锁屏，保险库密码可继续使用" }
        val enabling = vmk != null
        if (enabling && !keys.containsAlias(alias)) {
            KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true).setUserAuthenticationParameters(0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL).build())
            }.generateKey()
        }
        require(enabling || enabled(binding)) { "请先用密码解锁并启用本机便捷解锁" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val input: ByteArray
        try {
            val key = keys.getKey(alias, null) as SecretKey
            if (enabling) { cipher.init(Cipher.ENCRYPT_MODE, key); input = requireNotNull(vmk).copyOf() }
            else {
                val saved = parseObject(decodeUtf8(requireNotNull(box.read("quick-unlock"))))
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, binary(saved, "nonce", 12)))
                input = binary(saved, "ciphertext", 48)
            }
            cipher.updateAAD("AuthBox/quick-unlock/v1/$packageName/$namespace/${canonical(binding)}".toByteArray(Charsets.UTF_8))
        } catch (error: Exception) { disable(); throw IllegalStateException("本机便捷解锁不可用，请用密码解锁后重新启用", error) }
        return QuickUnlockRequest(namespace, binding.copy(), cipher, enabling, input) { value -> box.write("quick-unlock", canonical(value).toByteArray()) }
    }
}
