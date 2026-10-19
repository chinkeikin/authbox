package com.authbox.android

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After

class AutoSyncStoreTest {
    private var activity: Activity? = null
    @Before fun foreground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.runOnMainSync { activity!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @After fun closeForeground() { InstrumentationRegistry.getInstrumentation().runOnMainSync { activity?.finish() } }

    @Test fun pullAuthenticatesWithoutPasswordAndReaderNeverWrites() {
        ReviewFixture(role = "reader", remotePassword = "Changed synthetic password").use { test ->
            assertEquals(SyncResult.PULLED, test.repository.synchronize())
            assertEquals(3L, test.repository.revision()); assertEquals(3, test.repository.entities().size)
            assertNull(test.api.lastCommit)
            assertEquals(SyncResult.CURRENT, test.repository.synchronize())
            assertThrows(IllegalArgumentException::class.java) { test.repository.edit {} }
            test.repository.lock(); assertFalse(test.repository.canSync())
            assertThrows(IllegalArgumentException::class.java) { test.repository.synchronize() }
            test.repository.unlock("Changed synthetic password")
        }
    }

    @Test fun draftPreflightPreservesLocalChangesAndReviewStopsSync() {
        ReviewFixture().use { test ->
            test.repository.edit { it.single { row -> row.type == "group" }.payload.put("name", "公开自动同步草稿") }
            val before = test.saved().getString("draft")
            assertThrows(SyncConflict::class.java) { test.repository.synchronize() }
            assertTrue(test.repository.hasConflict()); assertFalse(test.repository.hasPending())
            assertEquals(before, test.saved().getString("draft")); assertNull(test.api.lastCommit)
            test.repository.prepareReview(test.password)
            assertFalse(test.repository.canSync())
            assertThrows(IllegalArgumentException::class.java) { test.repository.synchronize() }
            test.repository.confirmReview(requireNotNull(test.repository.reviewView()).id, false, true)
            test.repository.edit { it.single { row -> row.type == "group" }.payload.put("name", "公开已确认修改") }
            assertEquals(SyncResult.UPLOADED, test.repository.synchronize())
            assertEquals(4L, test.repository.revision()); assertFalse(test.repository.hasLocalChanges())
        }
    }

    @Test fun unconfirmedCommitRetriesOriginalOperationEvenWhenHeadAdvanced() {
        ReviewFixture().use { test ->
            test.api.remote = test.source; test.api.headRevision = 1
            test.repository.edit { it.single { row -> row.type == "group" }.payload.put("name", "公开重试修改") }
            val delegate = test.api
            var first = true
            val transport = object : ApiClient(delegate.origin) {
                override fun request(path: String, body: JSONObject?, access: String?): String {
                    if (path == "/v1/vault/head") error("Immutable retry must use receipt before checking head")
                    val result = delegate.request(path, body, access)
                    if (path == "/v1/vault/commit" && first) { first = false; throw IOException("Synthetic lost response") }
                    return result
                }
            }
            test.repository.dispose()
            val repo = AccountRepository(test.context, transport, "review_user", test.namespace)
            try {
                repo.unlock(test.password)
                assertThrows(IOException::class.java) { repo.upload() }
                val pending = canonical(test.saved().getJSONObject("pending"))
                delegate.headRevision = 9
                assertEquals(SyncResult.UPLOADED, repo.synchronize())
                assertEquals(pending, canonical(delegate.lastCommit!!)); assertFalse(repo.hasPending())
            } finally { repo.dispose() }
        }
    }

    @Test fun invalidRemoteAndNewGenerationNeverReplaceCache() {
        ReviewFixture().use { test ->
            val prior = canonical(test.saved())
            test.api.headRevision = 0
            assertThrows(IllegalArgumentException::class.java) { test.repository.synchronize() }
            assertEquals(prior, canonical(test.saved()))
            test.api.headRevision = 3
            val remote = test.api.remote
            val header = VaultCrypto.inspect(remote)
            val forged = OpenVault(ByteArray(32) { 7 }, header, mutableListOf())
            try { test.api.remote = VaultCrypto.snapshot(forged, 3) } finally { forged.close() }
            assertThrows(javax.crypto.AEADBadTagException::class.java) { test.repository.synchronize() }
            assertEquals(prior, canonical(test.saved())); assertTrue(test.repository.isUnlocked())
            val api = object : ApiClient(test.api.origin) {
                override fun request(path: String, body: JSONObject?, access: String?): String {
                    require(path == "/v1/vault/head")
                    return canonical(obj("vaultId" to header.getString("vaultId"), "revision" to 4, "keyGeneration" to 2))
                }
            }
            val repo = AccountRepository(test.context, api, "review_user", test.namespace)
            try {
                repo.unlock(test.password)
                assertThrows(SyncUnlockRequired::class.java) { repo.synchronize() }
                assertEquals(prior, canonical(test.saved()))
            } finally { repo.dispose() }
        }
    }

    @Test fun pauseAfterChallengeKeepsFixedRequestAndPreventsCommit() {
        ReviewFixture().use { test ->
            test.api.remote = test.source; test.api.headRevision = 1
            test.repository.edit { it.single { row -> row.type == "group" }.payload.put("name", "公开暂停请求") }
            test.repository.dispose()
            val allowed = AtomicBoolean(true); val pauseOnce = AtomicBoolean(true)
            val api = object : ApiClient(test.api.origin) {
                override fun request(path: String, body: JSONObject?, access: String?): String {
                    val response = test.api.request(path, body, access)
                    if (path == "/v1/challenges" && pauseOnce.compareAndSet(true, false)) allowed.set(false)
                    return response
                }
            }
            val repo = AccountRepository(test.context, api, "review_user", test.namespace)
            try {
                repo.unlock(test.password)
                assertThrows(IllegalArgumentException::class.java) { repo.synchronize { allowed.get() } }
                assertNull(test.api.lastCommit); assertTrue(repo.hasPending())
                val pending = canonical(test.saved().getJSONObject("pending"))
                allowed.set(true)
                assertEquals(SyncResult.UPLOADED, repo.synchronize { allowed.get() })
                assertEquals(pending, canonical(test.api.lastCommit!!)); assertFalse(repo.hasLocalChanges())
            } finally { repo.dispose() }
        }
    }
}
