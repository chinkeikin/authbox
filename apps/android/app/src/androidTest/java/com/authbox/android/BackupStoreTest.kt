package com.authbox.android

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupStoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val input get() = JSONObject(instrumentation.context.assets.open("protocol.json").bufferedReader().use { it.readText() })

    @Test fun rewrapPreservesRecordsAndInteroperates() {
        val fixture = input
        val original = fixture.getString("backup")
        val password = fixture.getString("password")
        val nextPassword = password + " changed"
        val newRecovery = VaultCrypto.newRecoveryKey()
        val changed = VaultCrypto.restore(original, password).use { vault -> VaultCrypto.rewrap(vault, nextPassword, "password", 2) }
        assertEquals(canonical(VaultCrypto.inspect(original).getJSONArray("entities")), canonical(VaultCrypto.inspect(changed).getJSONArray("entities")))
        assertThrows(Exception::class.java) { VaultCrypto.restore(changed, password).close() }
        VaultCrypto.restore(changed, fixture.getString("recoveryKey"), "recovery").close()
        val rotated = VaultCrypto.restore(changed, nextPassword).use { VaultCrypto.rewrap(it, newRecovery, "recovery", 3) }
        assertThrows(Exception::class.java) { VaultCrypto.restore(rotated, fixture.getString("recoveryKey"), "recovery").close() }
        VaultCrypto.restore(rotated, newRecovery, "recovery").use { assertEquals(4, it.entities.size) }
        VaultCrypto.restore(rotated, nextPassword).close()
        File(context.noBackupFilesDir, "backup-store-result.json").writeText(canonical(obj("changed" to changed, "rotated" to rotated, "recoveryKey" to newRecovery)))
    }

    @Test fun accountRewrapAndExportProtectPendingAndReader() {
        TestVault().use { test ->
            val repository = test.repository
            val key = test.input.getString("recoveryKey")
            val password = test.input.getString("password") + " changed"
            repository.rewrap(password, "password")
            val encoded = repository.exportBackup()
            assertTrue(repository.hasDraft()); assertFalse(repository.hasPending())
            assertEquals(test.input.getString("backup"), test.saved().getString("backup"))
            repository.lock(); repository.unlock(key, "recovery")
            assertEquals(4, repository.entities().size)
            val recovery = VaultCrypto.newRecoveryKey()
            assertThrows(IllegalArgumentException::class.java) { repository.rewrap(recovery, "recovery", false) }
            assertEquals(encoded, repository.exportBackup())
            repository.rewrap(recovery, "recovery", true)
            VaultCrypto.restore(repository.exportBackup(), recovery, "recovery").close()
            assertThrows(Exception::class.java) { repository.upload() }
            assertTrue(repository.hasPending())
            val pending = repository.exportBackup()
            assertThrows(IllegalArgumentException::class.java) { repository.rewrap(password, "password") }
            assertEquals(pending, repository.exportBackup())
        }
        TestVault(role = "reader").use {
            assertThrows(IllegalArgumentException::class.java) { it.repository.exportBackup() }
            assertThrows(IllegalArgumentException::class.java) { it.repository.rewrap("New synthetic password", "password") }
        }
    }

    @Test fun standaloneImportRequiresConfirmationAndSurvivesNewStore() {
        val first = "authbox.test.offline." + UUID.randomUUID()
        val second = "authbox.test.offline." + UUID.randomUUID()
        val fixture = input
        val backup = fixture.getString("backup")
        val password = fixture.getString("password")
        val recovery = fixture.getString("recoveryKey")
        try {
            OfflineVaultStore(context, first).use { store ->
                assertNull(store.info())
                store.prepareImport(backup, recovery, "recovery")
                assertEquals(1, store.previewInfo()?.accounts)
                assertNull(store.info())
                store.lock()
                assertThrows(IllegalArgumentException::class.java) { store.confirmImport(false) }
                store.prepareImport(backup, password, "password")
                store.confirmImport(false)
                assertEquals(4, store.entities().size)
                store.edit { entries -> entries.single { it.type == "group" }.payload.put("name", "离线工作组") }
                val exported = store.exportBackup()
                assertThrows(Exception::class.java) { store.prepareImport(backup, "Wrong synthetic password", "password") }
                assertEquals(exported, store.exportBackup())
                store.prepareImport(backup, password, "password")
                assertThrows(IllegalArgumentException::class.java) { store.confirmImport(false) }
                store.lock()
                store.unlock(recovery, "recovery")
                assertEquals(exported, store.exportBackup())
                OfflineVaultStore(context, second).use { fresh ->
                    fresh.prepareImport(exported, recovery, "recovery"); fresh.confirmImport(false)
                    assertEquals("离线工作组", fresh.entities().single { it.type == "group" }.payload.getString("name"))
                    assertEquals(2L, fresh.info()?.revision)
                }
            }
            OfflineVaultStore(context, second).use {
                assertFalse(it.isUnlocked()); it.unlock(password, "password"); assertEquals(4, it.entities().size)
                it.rewrap(password + " offline", "password")
                assertEquals(3L, it.info()?.revision)
            }
        } finally {
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (namespace in listOf(first, second)) {
                File(context.noBackupFilesDir, namespace).deleteRecursively()
                for (suffix in listOf("storage", "device")) if (keys.containsAlias("$namespace.$suffix")) keys.deleteEntry("$namespace.$suffix")
            }
        }
    }
}
