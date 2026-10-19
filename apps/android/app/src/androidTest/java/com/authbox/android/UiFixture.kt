package com.authbox.android

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.lifecycle.ViewModelProvider
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal class UiFixture(private val compose: ComposeTestRule) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val input = JSONObject(instrumentation.context.assets.open("protocol.json").bufferedReader().use { it.readText() })
    private val origin = "https://ui.example.invalid"
    private val username = "ui_mgmt_" + UUID.randomUUID().toString().take(8)
    private val namespace = accountNamespace(origin, username)
    private val preferences = context.getSharedPreferences("endpoint", 0)
    private val previousOrigin = preferences.getString("origin", null)
    private val previousUsername = preferences.getString("username", null)
    private val current = AtomicReference<MainActivity>()
    private val monitor = ActivityLifecycleMonitorRegistry.getInstance()
    private val callback = ActivityLifecycleCallback { activity, stage -> if (activity is MainActivity && stage != Stage.DESTROYED) current.set(activity) }
    init {
        val box = KeystoreBox(context, namespace)
        AccountRepository(context, ApiClient(origin), username).dispose()
        val state = parseObject(decodeUtf8(requireNotNull(box.read("state"))))
        state.put("backup", input.getString("backup")).put("refreshUncertain", true)
            .put("auth", obj("user" to obj("id" to UUID.randomUUID().toString(), "username" to username),
                "device" to obj("id" to state.getString("identityId"), "role" to "manager"),
                "session" to obj("accessToken" to "synthetic-not-used")))
        box.write("state", canonical(state).toByteArray())
        preferences.edit().putString("origin", origin).putString("username", username).commit()
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
    fun unlock() {
        compose.onNodeWithText("保险库密码（可离线解锁）").performScrollTo().performTextInput(input.getString("password"))
        compose.onNodeWithText("离线解锁").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("accounts").fetchSemanticsNodes().isNotEmpty() }
    }
    fun waitText(text: String) = compose.waitUntil(10000) { compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    fun state(): ScreenState {
        var state: ScreenState? = null
        instrumentation.runOnMainSync { state = ViewModelProvider(current.get())[AuthBoxViewModel::class.java].state.value }
        return requireNotNull(state)
    }
    fun backupState(): BackupState {
        var state: BackupState? = null
        instrumentation.runOnMainSync { state = ViewModelProvider(current.get())[BackupViewModel::class.java].state.value }
        return requireNotNull(state)
    }
    fun resumed(): Boolean {
        var resumed = false
        instrumentation.runOnMainSync { resumed = monitor.getActivitiesInStage(Stage.RESUMED).contains(current.get()) }
        return resumed
    }
    fun shell(command: String) { ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() } }
    override fun close() {
        instrumentation.runOnMainSync { current.get()?.finish(); monitor.removeLifecycleCallback(callback) }
        preferences.edit().putString("origin", previousOrigin).putString("username", previousUsername).commit()
        File(context.noBackupFilesDir, namespace).deleteRecursively()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (suffix in listOf("storage", "device")) if (keys.containsAlias("$namespace.$suffix")) keys.deleteEntry("$namespace.$suffix")
    }
}
