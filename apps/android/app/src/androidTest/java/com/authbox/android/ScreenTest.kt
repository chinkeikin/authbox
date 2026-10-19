package com.authbox.android

import android.view.WindowManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class ScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun offlineUnlockAndBackgroundLock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = JSONObject(instrumentation.context.assets.open("protocol.json").bufferedReader().use { it.readText() })
        val origin = "https://ui.example.invalid"
        val username = "ui_" + UUID.randomUUID().toString().take(8)
        val preferences = context.getSharedPreferences("endpoint", 0)
        val previousOrigin = preferences.getString("origin", null)
        val previousUsername = preferences.getString("username", null)
        val box = KeystoreBox(context, accountNamespace(origin, username))
        AccountRepository(context, ApiClient(origin), username).dispose()
        val stored = parseObject(decodeUtf8(requireNotNull(box.read("state")))).put("backup", fixture.getString("backup"))
        box.write("state", canonical(stored).toByteArray())
        preferences.edit().putString("origin", origin).putString("username", username).commit()
        val current = AtomicReference<MainActivity>()
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && stage != Stage.DESTROYED) current.set(activity)
        }
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        fun waitStage(stage: Stage) {
            compose.waitUntil(10000) {
                var reached = false
                instrumentation.runOnMainSync { reached = current.get()?.let { monitor.getLifecycleStageOf(it) == stage } == true }
                reached
            }
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitStage(Stage.RESUMED)
            instrumentation.runOnMainSync { assertTrue(current.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            compose.onNodeWithText("AuthBox").assertIsDisplayed()
            compose.onNodeWithText("保险库密码（可离线解锁）").performScrollTo().performTextInput(fixture.getString("password"))
            compose.onNodeWithText("离线解锁").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("fixture@example.invalid").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("accounts").performScrollToNode(hasText("fixture@example.invalid"))
            compose.onNodeWithText("fixture@example.invalid").assertIsDisplayed()
            compose.onNodeWithText("工作 · 测试").assertExists()
            compose.onNodeWithText("搜索账号或分组").performTextInput("不存在的账号")
            compose.onNodeWithText("fixture@example.invalid").assertDoesNotExist()
            compose.onNodeWithText("搜索账号或分组").performTextClearance()
            compose.onNodeWithText("fixture@example.invalid").assertExists()
            shell("input keyevent 3")
            waitStage(Stage.STOPPED)
            shell("am start -n com.authbox.android/.MainActivity")
            waitStage(Stage.RESUMED)
            compose.waitForIdle()
            compose.onNodeWithText("fixture@example.invalid").assertDoesNotExist()
            val previous = current.get()
            instrumentation.runOnMainSync { previous.recreate() }
            compose.waitUntil(10000) { current.get() !== previous }
            waitStage(Stage.RESUMED)
            compose.waitForIdle()
            compose.onNodeWithText("fixture@example.invalid").assertDoesNotExist()
        } finally {
            instrumentation.runOnMainSync { current.get()?.finish(); monitor.removeLifecycleCallback(callback) }
            preferences.edit().putString("origin", previousOrigin).putString("username", previousUsername).commit()
            val namespace = accountNamespace(origin, username)
            File(context.noBackupFilesDir, namespace).deleteRecursively()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (suffix in listOf("storage", "device")) if (keys.containsAlias("$namespace.$suffix")) keys.deleteEntry("$namespace.$suffix")
        }
    }
}
