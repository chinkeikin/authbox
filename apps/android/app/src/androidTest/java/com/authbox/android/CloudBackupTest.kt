package com.authbox.android

import android.content.Intent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudBackupTest {
    @Test fun changedWrapsCommitAndRecoveryDownload() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = JSONObject(instrumentation.context.assets.open("cloud-config.json").bufferedReader().use { it.readText() })
        val api = ApiClient(config.getString("origin"), mapOf("X-AuthBox-Fixture" to config.getString("fixtureToken"), "X-AuthBox-Case" to "android-backup"))
        val repository = AccountRepository(context, api, config.getString("username") + "_m", config.getString("namespace") + ".security.recovery")
        val original = config.getString("vaultPassword")
        val next = original + " changed"
        val recovery = VaultCrypto.newRecoveryKey()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
            repository.download(original)
            val previous = repository.entities().map { canonical(it.payload) }
            repository.rewrap(next, "password")
            repository.rewrap(recovery, "recovery", true)
            assertTrue(repository.hasDraft()); assertFalse(repository.hasPending())
            val pending = repository.exportBackup()
            repository.upload()
            assertFalse(repository.hasLocalChanges()); assertEquals(3L, repository.revision())
            repository.lock()
            repository.download(recovery, "recovery")
            assertEquals(previous, repository.entities().map { canonical(it.payload) })
            assertEquals(pending, repository.exportBackup())
            assertThrows(Exception::class.java) { repository.download(original) }
            repository.lock(); repository.unlock(next)
            assertEquals(6, repository.entities().size)
            File(context.noBackupFilesDir, "cloud-backup-result.json").writeText(canonical(obj("backup" to repository.exportBackup(), "recoveryKey" to recovery)))
        } finally {
            repository.dispose()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
