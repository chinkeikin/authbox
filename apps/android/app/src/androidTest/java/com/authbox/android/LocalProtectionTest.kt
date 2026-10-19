package com.authbox.android

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalProtectionTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun graceHidesImmediatelyReturnsBeforeDeadlineAndExpires() {
        UiFixture(compose).use { fixture ->
            fixture.unlock()
            fixture.context.getSharedPreferences("protection", 0).edit().putInt("background", 15).commit()
            fixture.shell("input keyevent 3")
            compose.waitUntil(10000) { !fixture.resumed() }
            assertFalse(fixture.state().unlocked); assertTrue(fixture.state().entities.isEmpty())
            fixture.shell("am start -n com.authbox.android/.MainActivity")
            compose.waitUntil(10000) { fixture.state().unlocked && !fixture.state().busy }
            fixture.model { it.onHidden(100) }
            Thread.sleep(300)
            fixture.model { it.onVisible() }
            compose.waitUntil(10000) { !fixture.state().busy }
            assertFalse(fixture.state().unlocked)
            fixture.unlock()
            fixture.model { it.onHidden(15000); it.lock(); it.onVisible() }
            assertFalse(fixture.state().unlocked)
        }
    }
    @Test fun sensitiveMaterialsNeverSurviveGraceAndIdleDoesNotFollowSyncTick() {
        UiFixture(compose).use { fixture ->
            fixture.unlock()
            fixture.model { it.prepareVaultRecoveryKey(fixture.origin, fixture.username) }
            compose.waitUntil(10000) { !fixture.state().busy && fixture.state().recovery.isNotEmpty() }
            fixture.model { it.onHidden(15000); it.onVisible() }
            compose.waitUntil(10000) { !fixture.state().busy }
            assertFalse(fixture.state().unlocked); assertTrue(fixture.state().recovery.isEmpty())
            fixture.unlock()
            fixture.context.getSharedPreferences("protection", 0).edit().putInt("idle", 60).commit()
            fixture.activity { activity ->
                val field = MainActivity::class.java.getDeclaredField("lastInteraction").apply { isAccessible = true }
                field.setLong(activity, SystemClock.elapsedRealtime() - 61000)
            }
            fixture.model { repeat(10) { _ -> it.tick() } }
            fixture.activity { it.onUserInteraction() }
            assertFalse(fixture.state().unlocked); assertTrue(fixture.state().rows.isEmpty())
        }
    }
    @Test fun immediateHomeAfterImportEntryNeverRetainsUnlock() {
        UiFixture(compose).use { fixture ->
            fixture.unlock()
            fixture.context.getSharedPreferences("protection", 0).edit().putInt("background", 15).commit()
            compose.onNodeWithTag("accounts").performScrollToNode(hasText("粘贴二维码"))
            compose.onNodeWithText("粘贴二维码").performClick()
            fixture.shell("input keyevent 3")
            compose.waitUntil(10000) { !fixture.resumed() }
            fixture.shell("am start -n com.authbox.android/.MainActivity")
            compose.waitUntil(10000) { fixture.resumed() && !fixture.state().busy }
            assertFalse(fixture.state().unlocked); assertTrue(fixture.state().importPreview.rows.isEmpty())
        }
    }
    @Test fun settingsPersistAndClipboardClearsOnlyTheOriginalOwnedClip() {
        UiFixture(compose).use { fixture ->
            fixture.unlock()
            compose.onNodeWithText("账号与设备").performClick()
            compose.onNodeWithText("后台锁定：立即").performClick(); compose.onNodeWithText("30 秒").performClick()
            assertEquals(30, ProtectionSettings(fixture.context).backgroundSeconds)
            val manager = fixture.context.getSystemService(ClipboardManager::class.java)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            var previous: ClipData? = null
            var clipboard: SensitiveClipboard? = null
            var owned: String? = null
            val preferenceName = "test.clipboard." + UUID.randomUUID()
            var now = 1000L
            try {
                instrumentation.runOnMainSync {
                    previous = manager.primaryClip
                    clipboard = SensitiveClipboard(fixture.context, preferenceName) { now }
                    clipboard!!.focus(true); clipboard!!.copy("123456")
                    assertTrue(manager.primaryClip!!.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
                    manager.setPrimaryClip(ClipData.newPlainText("User", "later synthetic copy"))
                    now += 61000; clipboard!!.clearIfDue()
                    assertEquals("later synthetic copy", manager.primaryClip!!.getItemAt(0).text.toString())
                    clipboard!!.copy("87654321"); owned = manager.primaryClip!!.description.extras?.getString("com.authbox.clip.id")
                    clipboard!!.focus(false); now += 61000
                    clipboard!!.clearIfDue(); assertEquals("87654321", manager.primaryClip!!.getItemAt(0).text.toString())
                    clipboard!!.close()
                    clipboard = SensitiveClipboard(fixture.context, preferenceName) { now }
                    clipboard!!.focus(true)
                    assertFalse(manager.hasPrimaryClip())
                }
            } finally {
                instrumentation.runOnMainSync {
                    clipboard?.close()
                    val current = manager.primaryClip
                    if (current == null || current.description.extras?.getString("com.authbox.clip.id") == owned ||
                        (current.description.label == "User" && current.getItemAt(0).text == "later synthetic copy")) {
                        if (previous != null) manager.setPrimaryClip(previous!!) else manager.clearPrimaryClip()
                    }
                    fixture.context.getSharedPreferences(preferenceName, 0).edit().clear().commit()
                }
            }
        }
    }
}
