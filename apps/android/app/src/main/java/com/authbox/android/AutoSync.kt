package com.authbox.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlin.math.min
import kotlin.random.Random

data class AutoSyncInfo(val enabled: Boolean = true, val message: String = "自动同步：解锁后检查", val running: Boolean = false,
    val lastSuccessAt: Long? = null)

internal class SyncSchedule(private val jitter: () -> Double = { Random.nextDouble() }) {
    var nextAttempt = 0L
        private set
    var failures = 0
        private set
    var blocked: String? = null
        private set
    fun wake(now: Long) { if (failures == 0 && blocked == null) nextAttempt = min(nextAttempt, now + 2000) }
    fun reset(now: Long) { failures = 0; blocked = null; nextAttempt = now + 2000 }
    fun success(now: Long) { failures = 0; blocked = null; nextAttempt = now + 60000 }
    fun failure(now: Long, message: String, retry: Boolean) {
        if (!retry) { blocked = message; return }
        failures = min(failures + 1, 7)
        val delay = min(240000L, 5000L * (1L shl (failures - 1)))
        nextAttempt = now + (delay * (1 + jitter().coerceIn(0.0, 1.0) * .25)).toLong()
    }
}

internal class SyncNetwork(context: Context, private val changed: (Boolean) -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update()
        override fun onLost(network: Network) = update()
    }
    private fun update() {
        handler.post {
            if (registered) {
                val caps = manager.getNetworkCapabilities(manager.activeNetwork)
                changed(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        }
    }
    fun start() {
        if (registered) return
        registered = true
        try { manager.registerDefaultNetworkCallback(callback); update() }
        catch (_: RuntimeException) { registered = false; changed(false) }
    }
    override fun close() {
        if (!registered) return
        registered = false
        manager.unregisterNetworkCallback(callback)
    }
}
