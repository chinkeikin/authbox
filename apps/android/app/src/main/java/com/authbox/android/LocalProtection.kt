package com.authbox.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import java.util.UUID

internal class ProtectionSettings(context: Context) {
    private val preferences = context.getSharedPreferences("protection", 0)
    var backgroundSeconds: Int
        get() = preferences.getInt("background", 0).takeIf { it in listOf(0, 15, 30, 60) } ?: 0
        set(value) { require(value in listOf(0, 15, 30, 60)); preferences.edit().putInt("background", value).apply() }
    var idleSeconds: Int
        get() = preferences.getInt("idle", 300).takeIf { it in listOf(60, 300, 900) } ?: 300
        set(value) { require(value in listOf(60, 300, 900)); preferences.edit().putInt("idle", value).apply() }
    var clipboardSeconds: Int
        get() = preferences.getInt("clipboard", 30).takeIf { it in listOf(15, 30, 60) } ?: 30
        set(value) { require(value in listOf(15, 30, 60)); preferences.edit().putInt("clipboard", value).apply() }
}

internal class SensitiveClipboard(context: Context, preferenceName: String = "clipboard", private val elapsed: () -> Long = SystemClock::elapsedRealtime) {
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val preferences = context.getSharedPreferences(preferenceName, 0)
    private val settings = ProtectionSettings(context)
    private val handler = Handler(Looper.getMainLooper())
    private val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private var focused = false
    private val clear = Runnable { clearIfDue() }
    fun focus(value: Boolean) { focused = value; if (value) clearIfDue() }
    fun copy(code: String) {
        require(code.matches(Regex("[0-9]{6}|[0-9]{8}")))
        val id = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText("AuthBox code", code)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true); putString("com.authbox.clip.id", id) }
        manager.setPrimaryClip(clip)
        val written = manager.primaryClip ?: return
        if (written.description.extras?.getString("com.authbox.clip.id") != id) return
        preferences.edit().putString("id", id).putLong("timestamp", written.description.timestamp)
            .putLong("deadline", elapsed() + settings.clipboardSeconds * 1000L).putInt("boot", boot).apply()
        handler.removeCallbacks(clear)
        handler.postDelayed(clear, settings.clipboardSeconds * 1000L)
    }
    fun clearIfDue() {
        if (!focused || !preferences.contains("id")) return
        if (preferences.getInt("boot", -2) != boot) { forget(); return }
        val remaining = preferences.getLong("deadline", 0) - elapsed()
        if (remaining > 0) { handler.removeCallbacks(clear); handler.postDelayed(clear, remaining); return }
        try {
            val current = manager.primaryClip ?: return
            if (current.description.extras?.getString("com.authbox.clip.id") == preferences.getString("id", null) &&
                current.description.timestamp == preferences.getLong("timestamp", -1) && current.itemCount == 1 &&
                current.getItemAt(0).uri == null && current.getItemAt(0).intent == null) {
                if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip() else manager.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            forget()
        } catch (_: SecurityException) { }
    }
    private fun forget() { preferences.edit().clear().apply(); handler.removeCallbacks(clear) }
    fun close() { handler.removeCallbacks(clear); focused = false }
}
