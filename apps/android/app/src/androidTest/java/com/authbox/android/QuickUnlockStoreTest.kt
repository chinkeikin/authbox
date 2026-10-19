package com.authbox.android

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import android.app.KeyguardManager
import org.junit.Assume.assumeTrue

class QuickUnlockStoreTest {
    private var activity: Activity? = null
    @Before fun foreground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("需要已配置安全锁屏，当前设备不得跳过系统认证", instrumentation.targetContext.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.runOnMainSync { activity!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @After fun closeForeground() { InstrumentationRegistry.getInstrumentation().runOnMainSync { activity?.finish() } }
    @Test fun actualKeystoreRequiresPerOperationAuthentication() {
        ReviewFixture().use { fixture ->
            try {
                val request = fixture.repository.prepareQuick(true)
                val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("${fixture.namespace}.unlock", null) as SecretKey
                val info = SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
                assertNull(key.encoded); assertTrue(info.isUserAuthenticationRequired)
                assertEquals(0, info.userAuthenticationValidityDurationSeconds)
                assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL, info.userAuthenticationType)
                assertThrows(Exception::class.java) { request.finish(request.cipher) }
                assertFalse(fixture.repository.quickEnabled())
                assertNull(fixture.box.read("quick-unlock"))
                assertEquals(fixture.source, fixture.repository.exportBackup())
            } finally { fixture.repository.disableQuick() }
        }
    }
    @Test fun cancelledAndStaleRequestsCannotChangeTheVault() {
        ReviewFixture().use { fixture ->
            try {
                val cancelled = fixture.repository.prepareQuick(true)
                cancelled.close()
                assertThrows(IllegalStateException::class.java) { fixture.repository.completeQuick(cancelled, cancelled.cipher) }
                val stale = fixture.repository.prepareQuick(true)
                fixture.repository.rewrap("Public synthetic rewrapped password", "password")
                val before = fixture.repository.exportBackup()
                assertThrows(IllegalArgumentException::class.java) { fixture.repository.completeQuick(stale, stale.cipher) }
                assertEquals(before, fixture.repository.exportBackup()); assertFalse(fixture.repository.quickEnabled())
            } finally { fixture.repository.disableQuick() }
        }
    }
    @Test fun offlineKeyAlsoRejectsUnauthenticatedUse() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val input = JSONObject(instrumentation.context.assets.open("protocol.json").bufferedReader().use { it.readText() })
        val namespace = "authbox.test.quick." + UUID.randomUUID()
        val store = OfflineVaultStore(context, namespace)
        try {
            store.prepareImport(input.getString("backup"), input.getString("password"), "password"); store.confirmImport(false)
            val original = store.exportBackup()
            val request = store.prepareQuick(true)
            assertThrows(Exception::class.java) { store.completeQuick(request, request.cipher) }
            assertEquals(original, store.exportBackup()); assertFalse(store.quickEnabled())
        } finally {
            store.disableQuick(); store.close(); File(context.noBackupFilesDir, namespace).deleteRecursively()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (suffix in listOf("storage", "device", "unlock")) if (keys.containsAlias("$namespace.$suffix")) keys.deleteEntry("$namespace.$suffix")
        }
    }
}
