package com.authbox.android

import android.content.Intent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudKeyRotationTest {
    @Test fun rotationRevokesPeersAndPreservesRecoverableHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = JSONObject(instrumentation.context.assets.open("cloud-config.json").bufferedReader().use { it.readText() })
        val input = JSONObject(instrumentation.context.assets.open("protocol.json").bufferedReader().use { it.readText() })
        val api = ApiClient(config.getString("origin"), mapOf("X-AuthBox-Fixture" to config.getString("fixtureToken"), "X-AuthBox-Case" to "android-key-rotation"))
        val namespace = config.getString("namespace")
        val username = config.getString("username")
        val password = config.getString("password")
        val vaultPassword = config.getString("vaultPassword")
        val rotatedPassword = vaultPassword + " rotated"
        val manager = AccountRepository(context, api, username, namespace)
        val peer = AccountRepository(context, api, username, "$namespace.rotation-peer")
        val reader = AccountRepository(context, api, username, "$namespace.rotation-reader")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        fun stage(value: String) { android.util.Log.i("AuthBoxRotationTest", value) }
        try {
            val materials = manager.prepareRegistration(vaultPassword)
            manager.register(password, config.getString("invite"), true)
            manager.download(vaultPassword)
            VaultCrypto.restore(input.getString("backup"), input.getString("password")).use { manager.stage(it.entities) }
            manager.upload()
            val before = manager.exportBackup()
            val business = manager.entities().map { it.id to canonical(it.payload) }
            peer.login(password, false)
            manager.updateDevice(peer.deviceId(), true)
            peer.login(password, true); peer.download(vaultPassword)
            assertEquals("manager", peer.role())
            reader.login(password, false); reader.download(vaultPassword)
            stage("registered-and-authorized")
            val newRecovery = manager.prepareRotation(vaultPassword, "password", rotatedPassword)
            val candidate = requireNotNull(manager.rotationInfo())
            assertEquals(2L, candidate.generation)
            assertThrows(IllegalArgumentException::class.java) { manager.confirmRotation(candidate.id, false) }
            manager.confirmRotation(candidate.id, true)
            assertEquals(3L, manager.revision()); assertFalse(manager.isUnlocked())
            manager.unlock(newRecovery, "recovery")
            assertEquals(business, manager.entities().map { it.id to canonical(it.payload) })
            val rotated = manager.exportBackup()
            assertThrows(javax.crypto.AEADBadTagException::class.java) { VaultCrypto.restore(rotated, vaultPassword) }
            assertThrows(javax.crypto.AEADBadTagException::class.java) { VaultCrypto.restore(rotated, materials.first, "recovery") }
            VaultCrypto.restore(rotated, rotatedPassword).use { assertEquals(2L, it.snapshot.getLong("keyGeneration")) }
            assertEquals(before, manager.exportSavedVersion(manager.savedVersions().single().id))
            stage("rotated-and-recoverable")
            val revokedRefresh = assertThrows(IllegalStateException::class.java) { peer.refresh() }
            assertEquals(401, (revokedRefresh.cause as ApiFailure).status)
            assertEquals(401, assertThrows(ApiFailure::class.java) { reader.download(vaultPassword) }.status)
            peer.login(password, true)
            assertEquals("reader", peer.role())
            assertThrows(IllegalArgumentException::class.java) { peer.edit {} }
            peer.download(rotatedPassword)
            manager.updateDevice(peer.deviceId(), true)
            peer.login(password, true); assertEquals("manager", peer.role())
            assertTrue(manager.devices().filter { it.id != manager.deviceId() && it.id != peer.deviceId() && !it.revoked }.all { it.role == "reader" })
            stage("peer-reauthorized")
            assertThrows(IllegalArgumentException::class.java) { manager.prepareReview(rotatedPassword, historyRevision = 2) }
            manager.prepareReview(rotatedPassword, historyRevision = 2, oldSecret = vaultPassword)
            val review = requireNotNull(manager.reviewView())
            assertTrue(review.wrapsChanged); assertTrue(review.differences.isEmpty())
            manager.confirmReview(review.id, true, true); manager.upload()
            assertEquals(4L, manager.revision())
            assertEquals(2L, VaultCrypto.inspect(manager.exportBackup()).getLong("keyGeneration"))
            assertEquals(business, manager.entities().map { it.id to canonical(it.payload) })
            peer.download(rotatedPassword)
            assertEquals(4L, peer.revision())
            File(context.noBackupFilesDir, "cloud-rotation-result.json").writeText(canonical(obj("before" to before, "rotated" to rotated,
                "backup" to manager.exportBackup(), "oldRecoveryKey" to materials.first, "recoveryKey" to newRecovery)))
            stage("history-restored-with-current-key")
        } finally {
            manager.dispose(); peer.dispose(); reader.dispose()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
