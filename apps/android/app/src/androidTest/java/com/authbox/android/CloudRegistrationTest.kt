package com.authbox.android

import android.content.Intent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudRegistrationTest {
    @Test fun offlineCopyRegistersAndSyncsWithoutChangingSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = JSONObject(instrumentation.context.assets.open("cloud-config.json").bufferedReader().use { it.readText() })
        val api = ApiClient(config.getString("origin"), mapOf("X-AuthBox-Fixture" to config.getString("fixtureToken"), "X-AuthBox-Case" to "android-registration"))
        val namespace = config.getString("namespace") + ".copy"
        val repository = AccountRepository(context, api, config.getString("username") + "_copy", namespace)
        val reader = AccountRepository(context, api, config.getString("username") + "_copy", "$namespace.reader")
        val offline = OfflineVaultStore(context, "$namespace.offline")
        val exported = JSONObject(File(context.noBackupFilesDir, "cloud-backup-result.json").readText())
        val vaultPassword = config.getString("vaultPassword") + " copied"
        val loginPassword = config.getString("password") + " copied"
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
            offline.prepareImport(exported.getString("backup"), exported.getString("recoveryKey"), "recovery"); offline.confirmImport(false)
            offline.edit { it.single { entity -> entity.type == "group" }.payload.put("name", "Offline recovered workspace") }
            val source = offline.exportBackup()
            offline.lock()
            val material = repository.prepareRegistrationFromBackup(offline.registrationBackup(), exported.getString("recoveryKey"), "recovery", vaultPassword)
            val kit = repository.registrationKit(material)
            assertThrows(IllegalArgumentException::class.java) { repository.register(loginPassword, config.getString("registrationInvite"), false) }
            repository.register(loginPassword, config.getString("registrationInvite"), true)
            assertEquals("manager", repository.role()); assertEquals(1L, repository.revision())
            repository.download(vaultPassword)
            val initial = repository.exportBackup()
            VaultCrypto.restore(source, exported.getString("recoveryKey"), "recovery").use {
                assertEquals(it.entities.map { entity -> entity.id to canonical(entity.payload) }, repository.entities().map { entity -> entity.id to canonical(entity.payload) })
                assertNotEquals(it.snapshot.getString("vaultId"), VaultCrypto.inspect(initial).getString("vaultId"))
            }
            reader.login(loginPassword, false)
            assertEquals("reader", reader.role())
            reader.download(material.first, "recovery")
            assertEquals(6, reader.entities().size)
            assertThrows(IllegalArgumentException::class.java) { reader.edit {} }
            reader.logout()
            repository.edit { it.single { entity -> entity.type == "group" }.payload.put("name", "Copied cloud workspace") }
            repository.upload()
            assertEquals(2L, repository.revision())
            assertEquals(source, offline.registrationBackup())
            File(context.noBackupFilesDir, "cloud-registration-result.json").writeText(canonical(obj("source" to source, "registered" to initial,
                "backup" to repository.exportBackup(), "kit" to kit)))
        } finally {
            repository.dispose(); reader.dispose(); offline.close()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
