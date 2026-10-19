package com.authbox.android

import android.content.Intent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudAutoSyncTest {
    @Test fun authenticatedPullSubmitAndConcurrentDraftProtection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val config = JSONObject(instrumentation.context.assets.open("cloud-config.json").bufferedReader().use { it.readText() })
        val api = ApiClient(config.getString("origin"), mapOf("X-AuthBox-Fixture" to config.getString("fixtureToken"), "X-AuthBox-Case" to "android-auto-sync"))
        val namespace = config.getString("namespace")
        val manager = AccountRepository(context, api, config.getString("username"), namespace)
        val peer = AccountRepository(context, api, config.getString("username"), "$namespace.review-peer")
        val reader = AccountRepository(context, api, config.getString("username"), "$namespace.reader")
        val vaultPassword = config.getString("vaultPassword")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
            manager.unlock(vaultPassword)
            assertEquals(8L, manager.revision())
            reader.login(config.getString("password"), true); reader.download(vaultPassword)
            manager.edit { it.single { row -> row.type == "account" }.payload.put("notes", "Automatic synchronization QA") }
            assertEquals(SyncResult.UPLOADED, manager.synchronize())
            assertEquals(9L, manager.revision())
            assertEquals(SyncResult.PULLED, reader.synchronize()); assertEquals(SyncResult.CURRENT, reader.synchronize())
            assertEquals("Automatic synchronization QA", reader.entities().single { it.type == "account" }.payload.getString("notes"))
            peer.login(config.getString("password"), true); peer.download(vaultPassword)
            manager.edit { it.single { row -> row.type == "group" }.payload.put("name", "Local auto conflict") }
            val box = KeystoreBox(context, namespace)
            val draft = parseObject(decodeUtf8(requireNotNull(box.read("state")))).getString("draft")
            peer.edit { it.single { row -> row.type == "group" }.payload.put("name", "Remote automated peer") }
            assertEquals(SyncResult.UPLOADED, peer.synchronize()); assertEquals(10L, peer.revision())
            assertThrows(SyncConflict::class.java) { manager.synchronize() }
            assertEquals(draft, parseObject(decodeUtf8(requireNotNull(box.read("state")))).getString("draft"))
            assertTrue(manager.hasConflict()); assertFalse(manager.hasPending())
            assertEquals(SyncResult.PULLED, reader.synchronize())
            val downloaded = parseObject(decodeUtf8(requireNotNull(KeystoreBox(context, "$namespace.reader").read("state")))).getString("backup")
            assertEquals(peer.exportBackup(), downloaded)
            peer.updateDevice(reader.deviceId(), false)
            val denied = assertThrows(ApiFailure::class.java) { reader.synchronize() }
            assertTrue(denied.status in setOf(401, 403))
            File(context.noBackupFilesDir, "cloud-auto-sync-result.json").writeText(canonical(obj("backup" to downloaded, "draft" to draft)))
        } finally {
            manager.dispose(); peer.dispose(); reader.dispose()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
