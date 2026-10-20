package com.authbox.android

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

internal class RotationApi(val delegate: ReviewApi) : ApiClient(delegate.origin) {
    var loseResponse = false
    val requests = mutableListOf<String>()
    override fun request(path: String, body: JSONObject?, access: String?): String {
        if (path != "/v1/vault/rotate") return delegate.request(path, body, access)
        requests.add(canonical(requireNotNull(body).getJSONObject("payload")))
        val result = delegate.request("/v1/vault/commit", body, access)
        if (loseResponse) { loseResponse = false; throw IOException("Synthetic lost rotation response") }
        return result
    }
}

class KeyRotationTest {
    private var activity: Activity? = null
    @Before fun foreground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.runOnMainSync { activity!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @After fun closeForeground() { InstrumentationRegistry.getInstrumentation().runOnMainSync { activity?.finish() } }

    private fun prepareFixture(test: ReviewFixture): Pair<AccountRepository, RotationApi> {
        test.api.remote = test.source; test.api.headRevision = 1; test.api.retired = emptyList()
        test.repository.dispose()
        val api = RotationApi(test.api)
        val repo = AccountRepository(test.context, api, "review_user", test.namespace)
        repo.unlock(test.password)
        return repo to api
    }

    @Test fun fullReencryptionPreservesBusinessAndRetiresOldMaterial() {
        ReviewFixture().use { test ->
            VaultCrypto.restore(test.source, test.password).use { original ->
                val before = canonical(original.snapshot)
                val newPassword = "New rotation password 🔐"
                assertThrows(IllegalArgumentException::class.java) { VaultCrypto.rotate(original, test.password) }
                val created = VaultCrypto.rotate(original, newPassword)
                try {
                    val encoded = canonical(created.vault.snapshot)
                    assertEquals(before, canonical(original.snapshot)); assertTrue(original.vmk.any { it != 0.toByte() })
                    assertFalse(original.vmk.contentEquals(created.vault.vmk))
                    assertEquals(2L, created.vault.snapshot.getLong("keyGeneration"))
                    assertEquals(original.entities.map { canonical(it.payload) }, created.vault.entities.map { canonical(it.payload) })
                    for ((secret, purpose) in listOf(newPassword to "password", created.recoveryKey to "recovery")) {
                        VaultCrypto.restore(encoded, secret, purpose).use { assertArrayEquals(created.vault.vmk, it.vmk) }
                    }
                    assertThrows(javax.crypto.AEADBadTagException::class.java) { VaultCrypto.authenticate(created.vault.snapshot, original.vmk) }
                    assertThrows(javax.crypto.AEADBadTagException::class.java) { VaultCrypto.restore(encoded, test.password) }
                    assertThrows(javax.crypto.AEADBadTagException::class.java) { VaultCrypto.restore(encoded, test.input.getString("recoveryKey"), "recovery") }
                    File(test.context.noBackupFilesDir, "rotation-result.json").writeText(canonical(obj("before" to test.source,
                        "backup" to encoded, "password" to newPassword, "recoveryKey" to created.recoveryKey)))
                } finally { created.vault.close() }
                assertTrue(created.vault.vmk.all { it == 0.toByte() })
            }
        }
    }

    @Test fun candidateRequiresCurrentSecretCleanStateAndExplicitConfirmation() {
        ReviewFixture().use { test ->
            val (repo, _) = prepareFixture(test)
            try {
                val before = canonical(test.saved())
                assertThrows(javax.crypto.AEADBadTagException::class.java) { repo.prepareRotation("Wrong synthetic password", "password", "New rotation password") }
                assertEquals(before, canonical(test.saved()))
                test.api.headRevision = 2
                assertThrows(SyncConflict::class.java) { repo.prepareRotation(test.password, "password", "New rotation password") }
                test.api.headRevision = 1
                val key = repo.prepareRotation(test.input.getString("recoveryKey"), "recovery", "New rotation password")
                val candidate = requireNotNull(repo.rotationInfo())
                assertFalse(candidate.submitted); assertTrue(repo.hasLocalChanges()); assertFalse(repo.canSync())
                assertFalse(canonical(test.saved()).contains(key))
                assertEquals(test.source, repo.exportBackup())
                assertEquals(test.source, repo.exportSavedVersion(repo.savedVersions().single().id))
                assertThrows(IllegalArgumentException::class.java) { repo.confirmRotation(candidate.id, false) }
                assertThrows(IllegalArgumentException::class.java) { repo.confirmRotation(java.util.UUID.randomUUID().toString(), true) }
                assertThrows(IllegalArgumentException::class.java) { repo.edit {} }
                assertThrows(IllegalArgumentException::class.java) { repo.upload() }
                assertThrows(IllegalArgumentException::class.java) { repo.download(test.password) }
                repo.cancelRotation(); assertFalse(repo.hasRotation()); assertEquals(test.source, repo.exportBackup())
                repo.edit { it.first { row -> row.type == "group" }.payload.put("name", "Unsynced rotation guard") }
                assertThrows(IllegalArgumentException::class.java) { repo.prepareRotation(test.password, "password", "New rotation password") }
            } finally { repo.dispose() }
        }
        ReviewFixture(role = "reader").use { test ->
            assertThrows(IllegalArgumentException::class.java) { test.repository.prepareRotation(test.password, "password", "New rotation password") }
        }
    }

    @Test fun uncertainSubmissionSurvivesReopenAndRetriesExactlyOneCandidate() {
        ReviewFixture().use { test ->
            val (repo, api) = prepareFixture(test)
            val password = "New rotation after lost response"
            val key = repo.prepareRotation(test.password, "password", password)
            val id = requireNotNull(repo.rotationInfo()).id
            api.loseResponse = true
            try {
                assertThrows(IllegalStateException::class.java) { repo.confirmRotation(id, true) }
                assertTrue(requireNotNull(repo.rotationInfo()).submitted)
                assertEquals(1L, repo.revision())
                assertThrows(IllegalArgumentException::class.java) { repo.cancelRotation() }
            } finally { repo.dispose() }
            val reopened = AccountRepository(test.context, api, "review_user", test.namespace)
            try {
                assertFalse(reopened.isUnlocked()); reopened.unlock(test.password)
                assertThrows(IllegalArgumentException::class.java) { reopened.rewrap("Another password", "password") }
                test.box.write("quick-unlock", canonical(obj("version" to 1)).toByteArray())
                reopened.confirmRotation(id, false)
                assertEquals(2, api.requests.size); assertEquals(api.requests[0], api.requests[1])
                assertFalse(reopened.hasRotation()); assertFalse(reopened.isUnlocked()); assertNull(test.box.read("quick-unlock"))
                assertEquals(2L, reopened.revision())
                assertThrows(javax.crypto.AEADBadTagException::class.java) { reopened.unlock(test.password) }
                reopened.unlock(key, "recovery"); assertEquals(4, reopened.entities().size)
                reopened.lock(); reopened.unlock(password)
                assertEquals(test.source, reopened.exportSavedVersion(reopened.savedVersions().single().id))
            } finally { reopened.dispose() }
        }
    }

    @Test fun rejectedCasPreservesBothSnapshotsAndAllowsSafeCancellation() {
        ReviewFixture().use { test ->
            val (repo, _) = prepareFixture(test)
            try {
                repo.prepareRotation(test.password, "password", "New conflict rotation password")
                val id = requireNotNull(repo.rotationInfo()).id
                val candidate = test.saved().getJSONObject("rotation").getJSONObject("payload").getString("backup")
                test.api.rejectCommit = true
                assertThrows(SyncConflict::class.java) { repo.confirmRotation(id, true) }
                assertFalse(requireNotNull(repo.rotationInfo()).submitted); assertTrue(repo.hasConflict())
                assertEquals(setOf(test.source, candidate), repo.savedVersions().map { repo.exportSavedVersion(it.id) }.toSet())
                repo.cancelRotation(); assertFalse(repo.hasRotation()); assertEquals(1L, repo.revision())
                assertFalse(repo.hasConflict()); assertTrue(repo.canSync())
                assertEquals(test.source, repo.exportBackup())
            } finally { repo.dispose() }
        }
    }
}
