package com.authbox.android

import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import android.os.Build

class QuickUnlockUnavailableTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun missingScreenLockNeverCreatesAnUnprotectedKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeFalse(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        UiFixture(compose).use { ui ->
            ui.unlock()
            ReviewFixture().use { fixture ->
                val before = fixture.repository.exportBackup()
                assertThrows(IllegalArgumentException::class.java) { fixture.repository.prepareQuick(true) }
                assertFalse(fixture.repository.quickEnabled()); assertEquals(before, fixture.repository.exportBackup())
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                assertFalse(keys.containsAlias("${fixture.namespace}.unlock"))
                assertNull(fixture.box.read("quick-unlock"))
                fixture.repository.lock(); fixture.repository.unlock(fixture.password)
                assertEquals(before, fixture.repository.exportBackup())
            }
            File(context.noBackupFilesDir, "quick-device-result.json").writeText(canonical(obj("deviceSecure" to false,
                "authenticatorStatus" to context.getSystemService(BiometricManager::class.java).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL),
                "positiveAuthenticationVerified" to false)))
        }
    }
    @Test fun unavailableUiKeepsPasswordUnlockAndNeverChangesTheVault() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeFalse(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        UiFixture(compose).use { fixture ->
            fixture.unlock()
            val before = fixture.state().entities.map { canonical(it.payload) }
            compose.onNodeWithText("账号与设备").performClick()
            compose.onNodeWithTag("security").performScrollToNode(hasText("启用系统解锁"))
            compose.onNodeWithText("启用系统解锁").performClick()
            assertFalse(fixture.state().quickEnabled); assertFalse(fixture.state().busy)
            assertEquals(before, fixture.state().entities.map { canonical(it.payload) })
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertFalse(keys.containsAlias("${accountNamespace(fixture.origin, fixture.username)}.unlock"))
            compose.onNodeWithText("返回保险库").performClick()
            fixture.model { it.lock() }; fixture.unlock()
            assertEquals(before, fixture.state().entities.map { canonical(it.payload) })
        }
    }
}

class LocalProtectionDeviceTest {
    @Test fun reportsOnlyAuthenticationAvailability() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val supported = Build.VERSION.SDK_INT >= 30
        val status = if (supported) context.getSystemService(BiometricManager::class.java).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) else -1
        File(context.noBackupFilesDir, "protection-device-result.json").writeText(canonical(obj("api" to Build.VERSION.SDK_INT,
            "deviceSecure" to context.getSystemService(KeyguardManager::class.java).isDeviceSecure,
            "authenticatorStatus" to status, "systemAuthenticationSupported" to supported)))
    }
}
