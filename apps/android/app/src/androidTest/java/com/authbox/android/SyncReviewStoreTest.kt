package com.authbox.android

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After

internal open class ReviewApi(var remote: String, val historical: String, origin: String = "https://review.example.invalid") : ApiClient(origin) {
    var retired = emptyList<String>()
    var headRevision = VaultCrypto.inspect(remote).getLong("revision")
    var rejectCommit = false
    var lastCommit: JSONObject? = null
    var userId = UUID.randomUUID().toString()
    var deviceId = ""
    var beforeHead: (() -> Unit)? = null
    override fun request(path: String, body: JSONObject?, access: String?): String {
        val header = VaultCrypto.inspect(remote)
        val head = obj("vaultId" to header.getString("vaultId"), "revision" to headRevision, "keyGeneration" to header.getLong("keyGeneration"))
        return when {
            path == "/v1/vault/retired" -> canonical(head.put("retiredIds", JSONArray(retired.sorted())))
            path == "/v1/vault/head" -> { beforeHead?.invoke(); canonical(head) }
            path == "/v1/vault/history" -> canonical(head.put("entries", JSONArray().put(obj("revision" to header.getLong("revision"),
                "createdAt" to System.currentTimeMillis(), "bytes" to remote.toByteArray().size, "digest" to header.getString("digest")))).put("nextBefore", JSONObject.NULL))
            path == "/v1/vault/snapshot?revision=${header.getLong("revision")}" -> remote
            path == "/v1/vault/snapshot?revision=1" -> historical
            path == "/v1/challenges" -> {
                val id = UUID.randomUUID().toString(); val expires = System.currentTimeMillis() + 90000
                canonical(obj("id" to id, "expiresAt" to expires, "payload" to canonical(array("AuthBox", 1, "device-proof", origin, id,
                    userId, deviceId, requireNotNull(access).split('.')[1], requireNotNull(body).getString("operation"), body.getString("requestHash"), expires, b64(ByteArray(32))))))
            }
            path == "/v1/vault/commit" -> {
                val pending = requireNotNull(body).getJSONObject("payload"); lastCommit = pending.copy()
                if (rejectCommit) throw ApiFailure(409, "revision_conflict")
                remote = pending.getString("backup"); headRevision = VaultCrypto.inspect(remote).getLong("revision"); retired = emptyList()
                canonical(obj("operationId" to pending.getString("operationId"), "revision" to headRevision, "digest" to VaultCrypto.inspect(remote).getString("digest")))
            }
            else -> error("Unexpected synthetic endpoint")
        }
    }
}

internal class ReviewFixture(role: String = "manager", remotePassword: String? = null, startRemote: Boolean = false) : AutoCloseable {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val input = JSONObject(instrumentation.context.assets.open("protocol.json").bufferedReader().use { it.readText() })
    val source = input.getString("backup")
    val namespace = "authbox.test.review." + UUID.randomUUID()
    val box = KeystoreBox(context, namespace)
    val api: ReviewApi
    var repository: AccountRepository
    val password = input.getString("password")
    val cachedPassword = if (startRemote) remotePassword ?: password else password
    init {
        val remote = VaultCrypto.restore(source, password).use { vault ->
            if (remotePassword != null) vault.snapshot = VaultCrypto.inspect(VaultCrypto.rewrap(vault, remotePassword, "password", 2))
            vault.entities.removeAll { it.type == "account" }
            VaultCrypto.snapshot(vault, 3)
        }
        api = ReviewApi(remote, source).apply { retired = listOf(VaultCrypto.inspect(source).getJSONArray("entities").getJSONObject(0).getString("id")) }
        repository = AccountRepository(context, api, "review_user", namespace)
        val state = saved()
        api.deviceId = state.getString("identityId")
        state.put("backup", if (startRemote) remote else source).put("auth", obj("user" to obj("id" to api.userId, "username" to "review_user"),
            "device" to obj("id" to api.deviceId, "role" to role), "session" to obj("accessToken" to "ab1a.${UUID.randomUUID()}.${b64(ByteArray(32))}",
                "accessExpiresAt" to System.currentTimeMillis() + 3600000)))
        box.write("state", canonical(state).toByteArray()); reopen()
    }
    fun saved(): JSONObject = parseObject(decodeUtf8(requireNotNull(box.read("state"))))
    fun reopen() { repository.dispose(); repository = AccountRepository(context, api, "review_user", namespace); repository.unlock(cachedPassword) }
    override fun close() {
        repository.dispose(); File(context.noBackupFilesDir, namespace).deleteRecursively()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (suffix in listOf("storage", "device")) if (keys.containsAlias("$namespace.$suffix")) keys.deleteEntry("$namespace.$suffix")
    }
}

class SyncReviewStoreTest {
    private var activity: Activity? = null
    @Before fun foreground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.runOnMainSync { activity!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @After fun closeForeground() { InstrumentationRegistry.getInstrumentation().runOnMainSync { activity?.finish() } }
    @Test fun localResolutionPreservesBothSidesAndRestoresRetiredIds() {
        ReviewFixture().use { test ->
            val repo = test.repository
            repo.edit { it.single { row -> row.type == "account" }.payload.put("favorite", false) }
            test.api.rejectCommit = true
            assertThrows(SyncConflict::class.java) { repo.upload() }
            val originalPending = canonical(test.saved().getJSONObject("pending"))
            assertTrue(repo.hasConflict())
            repo.prepareReview(test.password)
            assertEquals(originalPending, canonical(test.saved().getJSONObject("pending")))
            val view = requireNotNull(repo.reviewView())
            assertEquals(1, view.differences.size); assertEquals(4, view.sourceCount); assertEquals(3, view.remoteCount)
            assertThrows(IllegalArgumentException::class.java) { repo.confirmReview(view.id, true, false) }
            val before = canonical(test.saved())
            test.api.headRevision = 4
            assertThrows(SyncConflict::class.java) { repo.confirmReview(view.id, true, true) }
            assertEquals(before, canonical(test.saved()))
            test.api.headRevision = 3
            repo.confirmReview(view.id, true, true)
            assertEquals(3L, repo.revision()); assertTrue(repo.hasDraft()); assertFalse(repo.hasPending()); assertFalse(repo.hasReview())
            assertEquals(2, repo.savedVersions().size)
            val candidate = repo.exportBackup()
            val accountId = repo.entities().single { it.type == "account" }.id
            assertEquals(accountId, test.saved().getJSONArray("draftRestoredIds").getString(0))
            test.api.rejectCommit = false; repo.upload()
            assertEquals(4L, repo.revision()); assertEquals(accountId, test.api.lastCommit!!.getJSONArray("restoredIds").getString(0))
            File(test.context.noBackupFilesDir, "review-result.json").writeText(canonical(obj("remote" to test.api.historical,
                "removed" to parseObject(before).getJSONObject("review").getString("remote"), "backup" to candidate,
                "pending" to test.api.lastCommit)))
        }
    }

    @Test fun historyUsesCurrentWrapsAndNeverRewindsRevision() {
        ReviewFixture(remotePassword = "Current synthetic vault password", startRemote = true).use { test ->
            val repo = test.repository
            assertEquals(3L, repo.history().entries.single().revision)
            repo.prepareReview(test.cachedPassword, historyRevision = 1)
            val view = requireNotNull(repo.reviewView()); assertTrue(view.wrapsChanged)
            repo.confirmReview(view.id, true, true)
            val restored = repo.exportBackup()
            VaultCrypto.restore(restored, test.cachedPassword).use { assertEquals(4L, it.snapshot.getLong("revision")); assertEquals(4, it.entities.size) }
            assertThrows(Exception::class.java) { VaultCrypto.restore(restored, test.password).close() }
            assertEquals(canonical(VaultCrypto.inspect(test.api.remote).getJSONObject("wraps")), canonical(VaultCrypto.inspect(restored).getJSONObject("wraps")))
            assertThrows(IllegalArgumentException::class.java) { repo.prepareReview(test.cachedPassword, historyRevision = 1) }
            repo.upload(); assertEquals(4L, repo.revision())
        }
    }

    @Test fun cancelReopenAndCloudSelectionRetainLocalVersion() {
        ReviewFixture().use { test ->
            test.repository.edit { it.single { row -> row.type == "group" }.payload.put("name", "本地待选择") }
            val local = test.repository.exportBackup()
            test.repository.prepareReview(test.password)
            test.reopen()
            assertTrue(test.repository.hasReview()); assertNull(test.repository.reviewView())
            assertThrows(IllegalArgumentException::class.java) { test.repository.edit {} }
            test.repository.cancelReview(); assertEquals(local, test.repository.exportBackup())
            test.repository.prepareReview(test.password)
            test.repository.confirmReview(requireNotNull(test.repository.reviewView()).id, false, true)
            assertFalse(test.repository.hasLocalChanges()); assertEquals(3L, test.repository.revision())
            assertTrue(test.repository.savedVersions().any { test.repository.exportSavedVersion(it.id) == local })
            val selected = test.repository.savedVersions().first()
            assertThrows(IllegalArgumentException::class.java) { test.repository.deleteSavedVersion(selected.id, false) }
            test.repository.deleteSavedVersion(selected.id, true)
            assertTrue(test.repository.savedVersions().none { it.id == selected.id })
        }
        ReviewFixture(role = "reader").use {
            assertThrows(IllegalArgumentException::class.java) { it.repository.history() }
            assertThrows(IllegalArgumentException::class.java) { it.repository.prepareReview(it.password) }
            assertThrows(IllegalArgumentException::class.java) { it.repository.savedVersions() }
        }
    }

    @Test fun fullPreservationStoreRefusesToDiscardCandidates() {
        ReviewFixture().use { test ->
            val state = test.saved()
            VaultCrypto.restore(test.source, test.password).use { vault ->
                state.put("savedVersions", JSONArray().apply { for (revision in 10L..25L) put(obj("id" to UUID.randomUUID().toString(),
                    "createdAt" to utc(), "reason" to "公开合成保留版本", "backup" to VaultCrypto.snapshot(vault, revision))) })
            }
            test.box.write("state", canonical(state).toByteArray()); test.reopen()
            test.repository.edit { it.single { row -> row.type == "account" }.payload.put("favorite", false) }
            test.repository.prepareReview(test.password)
            val before = canonical(test.saved())
            assertThrows(IllegalArgumentException::class.java) { test.repository.confirmReview(requireNotNull(test.repository.reviewView()).id, true, true) }
            assertEquals(before, canonical(test.saved()))
            assertEquals(16, test.repository.savedVersions().size)
        }
    }
}

class SyncReviewCleanupTest {
    @Test fun removesOnlySyntheticReviewNamespaces() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pattern = Regex("authbox\\.test\\.review\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        context.noBackupFilesDir.listFiles()?.filter { pattern.matches(it.name) }?.forEach { assertTrue(it.deleteRecursively()) }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { alias -> listOf(".storage", ".device").any { alias.endsWith(it) && pattern.matches(alias.removeSuffix(it)) } }.forEach { keys.deleteEntry(it) }
    }
}
