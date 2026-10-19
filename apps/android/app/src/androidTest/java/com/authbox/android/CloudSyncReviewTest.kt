package com.authbox.android

import android.content.Intent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudSyncReviewTest {
    @Test fun concurrentReviewAndHistoryRestorePreserveAllCandidates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val config = JSONObject(instrumentation.context.assets.open("cloud-config.json").bufferedReader().use { it.readText() })
        val api = ApiClient(config.getString("origin"), mapOf("X-AuthBox-Fixture" to config.getString("fixtureToken"), "X-AuthBox-Case" to "android-sync-review"))
        val namespace = config.getString("namespace")
        val manager = AccountRepository(context, api, config.getString("username"), namespace)
        val peer = AccountRepository(context, api, config.getString("username"), "$namespace.review-peer")
        val box = KeystoreBox(context, namespace)
        val password = config.getString("vaultPassword")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
            manager.unlock(password)
            assertTrue(manager.hasPending()); assertTrue(manager.hasConflict())
            val original = canonical(parseObject(decodeUtf8(requireNotNull(box.read("state")))).getJSONObject("pending"))
            peer.login(config.getString("password"), false)
            manager.updateDevice(peer.deviceId(), true)
            peer.login(config.getString("password"), true); peer.download(password)
            manager.prepareReview(password)
            val first = requireNotNull(manager.reviewView())
            peer.edit { it.single { row -> row.type == "account" }.payload.put("notes", "Concurrent review peer") }; peer.upload()
            assertThrows(SyncConflict::class.java) { manager.confirmReview(first.id, true, true) }
            assertEquals(original, canonical(parseObject(decodeUtf8(requireNotNull(box.read("state")))).getJSONObject("pending")))
            assertTrue(manager.hasReview())
            manager.prepareReview(password)
            manager.confirmReview(requireNotNull(manager.reviewView()).id, true, true)
            manager.upload(); assertEquals(6L, manager.revision())
            assertEquals("Android 本地待解决", manager.entities().single { it.type == "group" }.payload.getString("name"))
            val removedId = manager.entities().single { it.type == "account" }.id
            manager.edit { it.removeAll { row -> row.id == removedId } }; manager.upload()
            val beforeHistory = manager.exportBackup()
            assertEquals(7L, manager.revision())
            assertTrue(manager.history().entries.any { it.revision == 2L })
            manager.prepareReview(password, historyRevision = 2)
            assertEquals(4, manager.reviewView()?.sourceCount); assertEquals(3, manager.reviewView()?.remoteCount)
            manager.confirmReview(requireNotNull(manager.reviewView()).id, true, true)
            val restoredIds = parseObject(decodeUtf8(requireNotNull(box.read("state")))).getJSONArray("draftRestoredIds")
            assertEquals(removedId, restoredIds.getString(0))
            manager.upload(); assertEquals(8L, manager.revision()); assertEquals(4, manager.entities().size)
            val saved = JSONArray().apply { manager.savedVersions().forEach { put(manager.exportSavedVersion(it.id)) } }
            assertTrue(saved.length() >= 5)
            File(context.noBackupFilesDir, "cloud-review-result.json").writeText(canonical(obj("backup" to manager.exportBackup(),
                "beforeHistory" to beforeHistory, "restoredIds" to restoredIds, "saved" to saved)))
        } finally {
            manager.dispose(); peer.dispose(); instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
