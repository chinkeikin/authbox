package com.authbox.android

import android.content.Intent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudSecurityTest {
    @Test fun approvalRevocationPasswordAndRecovery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = JSONObject(instrumentation.context.assets.open("cloud-config.json").bufferedReader().use { it.readText() })
        val api = ApiClient(config.getString("origin"), mapOf("X-AuthBox-Fixture" to config.getString("fixtureToken"), "X-AuthBox-Case" to "android-security"))
        val username = config.getString("username") + "_m"
        val namespace = config.getString("namespace")
        val manager = AccountRepository(context, api, username, "$namespace.management")
        val candidate = AccountRepository(context, api, username, "$namespace.security.reader")
        val recovered = AccountRepository(context, api, username, "$namespace.security.recovery")
        val replay = AccountRepository(context, api, username, "$namespace.security.replay")
        val password = config.getString("password")
        val nextPassword = password + " changed"
        val resetPassword = password + " recovered"
        val vaultPassword = config.getString("vaultPassword")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        fun stage(value: String) { android.util.Log.i("AuthBoxSecurityTest", value) }
        try {
            stage("begin")
            manager.download(vaultPassword)
            val before = manager.entities().map { canonical(it.payload) }
            candidate.login(password, false)
            assertEquals("reader", candidate.role())
            assertThrows(IllegalArgumentException::class.java) { candidate.devices() }
            val listed = manager.devices()
            assertTrue(listed.single { it.id == candidate.deviceId() }.hasKey)
            manager.updateDevice(candidate.deviceId(), true)
            stage("approved")
            assertEquals(401, assertThrows(ApiFailure::class.java) { candidate.download(vaultPassword) }.status)
            candidate.login(password, true)
            assertEquals("manager", candidate.role())
            candidate.download(vaultPassword)
            manager.updateDevice(candidate.deviceId(), false)
            assertEquals(401, assertThrows(ApiFailure::class.java) { candidate.download(vaultPassword) }.status)
            assertTrue(manager.devices().single { it.id == candidate.deviceId() }.revoked)
            stage("revoked")
            assertEquals(401, assertThrows(ApiFailure::class.java) { manager.changePassword("wrong password", nextPassword) }.status)
            manager.changePassword(password, nextPassword)
            stage("password-changed")
            assertEquals(before, manager.entities().map { canonical(it.payload) })
            assertEquals(401, assertThrows(ApiFailure::class.java) { replay.login(password, false) }.status)
            val codes = accountRecoveryCodes()
            assertThrows(IllegalArgumentException::class.java) { manager.rotateRecoveryCodes(codes, false) }
            manager.rotateRecoveryCodes(codes, true)
            stage("codes-rotated")
            val oldIdentity = manager.deviceId()
            assertEquals(401, assertThrows(ApiFailure::class.java) { manager.recover("ABAC1-AAAAAAAAAAAAAAAAAAAAAA", resetPassword, true) }.status)
            assertEquals(oldIdentity, manager.deviceId())
            assertEquals("manager", manager.role())
            assertTrue(manager.devices().any { it.id == oldIdentity && !it.revoked })
            stage("wrong-code-preserved-identity")
            assertThrows(IllegalArgumentException::class.java) { recovered.recover(codes.first(), resetPassword, false) }
            recovered.recover(codes.first(), resetPassword, true)
            stage("recovered")
            assertEquals("manager", recovered.role())
            recovered.download(vaultPassword)
            assertEquals(2L, recovered.revision())
            assertEquals(before, recovered.entities().map { canonical(it.payload) })
            assertEquals(401, assertThrows(ApiFailure::class.java) { manager.download(vaultPassword) }.status)
            assertEquals(401, assertThrows(ApiFailure::class.java) { replay.recover(codes.first(), resetPassword, true) }.status)
            assertTrue(recovered.devices().filter { it.id != recovered.deviceId() }.all { it.revoked })
            stage("complete")
        } finally {
            manager.dispose(); candidate.dispose(); recovered.dispose(); replay.dispose()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
