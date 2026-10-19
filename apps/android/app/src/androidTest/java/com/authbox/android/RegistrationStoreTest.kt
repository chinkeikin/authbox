package com.authbox.android

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RegistrationStoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val input get() = JSONObject(instrumentation.context.assets.open("protocol.json").bufferedReader().use { it.readText() })

    @Test fun copiedVaultHasIndependentIdentityKeysAndRecoveryKit() {
        val fixture = input
        val namespace = "authbox.test.registration." + UUID.randomUUID()
        val offlineNamespace = "$namespace.offline"
        val nextPassword = "AuthBox copied vault password"
        val api = ApiClient("https://registration.example.invalid")
        try {
            OfflineVaultStore(context, offlineNamespace).use { offline ->
                offline.prepareImport(fixture.getString("backup"), fixture.getString("password"), "password"); offline.confirmImport(false)
                offline.edit { it.single { entry -> entry.type == "group" }.payload.put("name", "恢复后的工作组") }
                val source = offline.exportBackup()
                offline.lock()
                assertEquals(source, offline.registrationBackup())
                AccountRepository(context, api, "copied_user", namespace).run {
                    assertThrows(Exception::class.java) { prepareRegistrationFromBackup(source, "wrong password", "password", nextPassword) }
                    assertNull(registrationInfo())
                    val material = prepareRegistrationFromBackup(source, fixture.getString("recoveryKey"), "recovery", nextPassword)
                    val info = requireNotNull(registrationInfo())
                    assertEquals(VaultCrypto.inspect(source).getString("vaultId"), info.sourceVaultId)
                    assertEquals(2L, info.sourceRevision)
                    assertNotEquals(info.sourceVaultId, info.vaultId)
                    assertEquals(4, info.entities)
                    val kit = registrationKit(material)
                    val parsed = RecoveryKit.inspect(kit)
                    assertEquals(api.origin, parsed.getString("origin")); assertEquals("copied_user", parsed.getString("username"))
                    assertEquals(info.vaultId, parsed.getString("vaultId")); assertEquals(8, parsed.getJSONArray("accountRecoveryCodes").length())
                    assertFalse(kit.contains(nextPassword)); assertFalse(kit.contains(fixture.getString("password")))
                    val saved = parseObject(decodeUtf8(requireNotNull(KeystoreBox(context, namespace).read("state"))))
                    val backup = saved.getJSONObject("registration").getString("backup")
                    assertFalse(canonical(saved).contains(material.first))
                    assertTrue(material.second.none { canonical(saved).contains(it) })
                    VaultCrypto.restore(source, fixture.getString("password")).use { old ->
                        VaultCrypto.restore(backup, nextPassword).use { fresh ->
                            assertEquals(1L, fresh.snapshot.getLong("revision")); assertEquals(1L, fresh.snapshot.getLong("keyGeneration"))
                            assertFalse(old.vmk.contentEquals(fresh.vmk))
                            assertEquals(old.entities.map { it.id to canonical(it.payload) }, fresh.entities.map { it.id to canonical(it.payload) })
                            assertEquals("94287082", VaultCrypto.totp(fresh.entities.single { it.type == "account" }.payload, 59000))
                        }
                    }
                    assertThrows(Exception::class.java) { VaultCrypto.restore(backup, fixture.getString("recoveryKey"), "recovery").close() }
                    assertThrows(IllegalArgumentException::class.java) { register("Synthetic login password", b64(ByteArray(32)), false) }
                    assertThrows(IllegalArgumentException::class.java) { registrationKit(material.first to accountRecoveryCodes()) }
                    File(context.noBackupFilesDir, "registration-result.json").writeText(canonical(obj("source" to source, "backup" to backup, "kit" to kit)))
                    val guarded = saved.copy().put("registrationSubmitted", true)
                    KeystoreBox(context, namespace).write("state", canonical(guarded).toByteArray())
                    dispose()
                    AccountRepository(context, api, "copied_user", namespace).run {
                        assertThrows(IllegalArgumentException::class.java) { prepareRegistration(nextPassword) }
                        assertThrows(IllegalArgumentException::class.java) { prepareRegistrationFromBackup(source, fixture.getString("password"), "password", nextPassword) }
                        assertEquals(info, registrationInfo()); dispose()
                    }
                }
                assertEquals(source, offline.registrationBackup())
            }
        } finally {
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (name in listOf(namespace, offlineNamespace)) {
                File(context.noBackupFilesDir, name).deleteRecursively()
                for (suffix in listOf("storage", "device")) if (keys.containsAlias("$name.$suffix")) keys.deleteEntry("$name.$suffix")
            }
        }
    }

    @Test fun existingAccountAndDraftCannotBeReplaced() {
        TestVault().use {
            val before = canonical(it.saved())
            val fixture = input
            assertThrows(IllegalArgumentException::class.java) { it.repository.prepareRegistration("New synthetic password") }
            assertThrows(IllegalArgumentException::class.java) { it.repository.prepareRegistrationFromBackup(fixture.getString("backup"), fixture.getString("password"), "password", "New synthetic password") }
            assertEquals(before, canonical(it.saved()))
        }
    }
}
