package com.authbox.android

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import javax.crypto.Cipher

internal class LocalUnlockPrompt(private val activity: MainActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private var active: QuickUnlockRequest? = null
    private var signal: CancellationSignal? = null
    private var pending: (() -> Unit)? = null
    private var authenticated = false
    private val timeout = Runnable { cancel() }
    val running: Boolean get() = active != null
    fun available(): Boolean = Build.VERSION.SDK_INT >= 30 && activity.getSystemService(BiometricManager::class.java)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    fun show(request: QuickUnlockRequest, complete: (Cipher, () -> Unit) -> Unit) {
        cancel()
        if (Build.VERSION.SDK_INT < 30) { request.close(); return }
        active = request; signal = CancellationSignal()
        handler.postDelayed(timeout, 120000)
        try {
            BiometricPrompt.Builder(activity).setTitle("AuthBox 本机解锁")
                .setSubtitle("使用系统强生物识别或锁屏凭据，密码仍可解锁和恢复")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build().authenticate(BiometricPrompt.CryptoObject(request.cipher), signal!!, activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (active !== request || authenticated || result.cryptoObject?.cipher !== request.cipher) return
                            authenticated = true
                            pending = { complete(request.cipher) { if (active === request) cancel() } }
                            resume()
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (active !== request) return
                            cancel(); Toast.makeText(activity, "系统认证未完成，请重试或使用保险库密码", Toast.LENGTH_SHORT).show()
                        }
                    })
        } catch (_: Exception) { cancel(); Toast.makeText(activity, "系统认证不可用，请使用保险库密码", Toast.LENGTH_SHORT).show() }
    }
    fun resume() {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val action = pending ?: return
        pending = null; action()
    }
    fun cancel() {
        val previous = active
        active = null; pending = null; authenticated = false
        handler.removeCallbacks(timeout)
        signal?.cancel(); signal = null
        previous?.close()
    }
}
