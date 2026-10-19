package com.authbox.android

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

internal class TestVault(val namespace: String = "authbox.test.management." + UUID.randomUUID(), val role: String = "manager") : AutoCloseable {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val input = JSONObject(instrumentation.context.assets.open("protocol.json").bufferedReader().use { it.readText() })
    val api = ApiClient("https://must-not-be-contacted.invalid")
    val box = KeystoreBox(context, namespace)
    var repository = AccountRepository(context, api, "manage_fixture", namespace)
    init {
        val saved = parseObject(decodeUtf8(requireNotNull(box.read("state"))))
        saved.put("backup", input.getString("backup")).put("auth", obj("user" to obj("id" to UUID.randomUUID().toString(), "username" to "manage_fixture"),
            "device" to obj("id" to saved.getString("identityId"), "role" to role), "session" to obj("accessToken" to "synthetic-not-used")))
            .put("refreshUncertain", true)
        box.write("state", canonical(saved).toByteArray())
        repository.dispose()
        reopen()
    }
    fun saved(): JSONObject = parseObject(decodeUtf8(requireNotNull(box.read("state"))))
    fun reopen() {
        repository.dispose()
        repository = AccountRepository(context, api, "manage_fixture", namespace)
        repository.unlock(input.getString("password"))
    }
    override fun close() {
        repository.dispose()
        File(context.noBackupFilesDir, namespace).deleteRecursively()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (suffix in listOf("storage", "device")) if (keys.containsAlias("$namespace.$suffix")) keys.deleteEntry("$namespace.$suffix")
    }
}

class ManagementTest {
    @Test fun offlineDraftEditsAndImmutableRetry() {
        TestVault().use { test ->
            val repository = test.repository
            var group = ""; var tag = ""; var first = ""; var second = ""
            repository.edit { group = VaultEditor.saveCategory(it, "group", null, "新的工作组"); tag = VaultEditor.saveCategory(it, "tag", null, "待处理") }
            val payload = VaultEditor.account("测试平台", "相同名称", "same@example.invalid", "gezdgnbvgy3tqojqgezdgnbvgy3tqojq", groupId = group, tagIds = listOf(tag))
            repository.edit { first = VaultEditor.saveAccount(it, null, payload) }
            repository.edit { second = VaultEditor.saveAccount(it, null, payload) }
            assertNotEquals(first, second)
            repository.edit { VaultEditor.bulk(it, setOf(first, second), favorite = true); VaultEditor.move(it, second, -1) }
            assertTrue(repository.hasDraft()); assertFalse(repository.hasPending()); assertEquals(1L, repository.revision())
            assertEquals(test.input.getString("backup"), test.saved().getString("backup"))
            test.reopen()
            assertEquals(8, test.repository.entities().size)
            assertEquals(0L, test.repository.entities().single { it.id == second }.payload.getLong("order"))
            val state = test.saved()
            File(test.context.noBackupFilesDir, "management-result.json").writeText(canonical(obj("backup" to state.getString("draft"))))
            assertThrows(IllegalArgumentException::class.java) { test.repository.upload() }
            val operation = test.saved().getJSONObject("pending").getString("operationId")
            assertFalse(test.repository.hasDraft()); assertTrue(test.repository.hasPending())
            assertThrows(Exception::class.java) { test.repository.edit { it.clear() } }
            assertThrows(Exception::class.java) { test.repository.download(test.input.getString("password")) }
            assertThrows(IllegalArgumentException::class.java) { test.repository.upload() }
            assertEquals(operation, test.saved().getJSONObject("pending").getString("operationId"))
        }
    }

    @Test fun deleteCategoriesAndRestoreKeepAccountIdentity() {
        TestVault().use { test ->
            val r = test.repository
            val id = r.entities().single { it.type == "account" }.id
            val group = r.entities().single { it.type == "group" }.id
            val tag = r.entities().single { it.type == "tag" }.id
            r.edit { VaultEditor.bulk(it, setOf(id), trash = true) }
            assertFalse(r.entities().single { it.id == id }.payload.isNull("deletedAt"))
            r.edit { VaultEditor.deleteCategory(it, group); VaultEditor.deleteCategory(it, tag) }
            val deleted = r.entities().single { it.id == id }.payload
            assertTrue(deleted.isNull("groupId")); assertTrue(VaultEditor.tags(deleted).isEmpty())
            r.edit { VaultEditor.bulk(it, setOf(id), trash = false) }
            assertTrue(r.entities().single { it.id == id }.payload.isNull("deletedAt"))
            test.reopen()
            assertEquals(id, test.repository.entities().single { it.type == "account" }.id)
            VaultCrypto.validateEntities(test.repository.entities())
        }
    }

    @Test fun editsAreAtomicAndKeyChangesRequireConfirmation() {
        TestVault().use { test ->
            val r = test.repository
            val entry = r.entities().single { it.type == "account" }
            val changed = entry.payload.copy().put("period", 60)
            assertThrows(Exception::class.java) { r.edit { VaultEditor.saveAccount(it, entry.id, changed) } }
            assertFalse(r.hasDraft())
            r.edit { VaultEditor.saveAccount(it, entry.id, changed, true) }
            assertEquals(test.input.getString("backup"), test.saved().getString("backup"))
            val snapshot = test.saved().getString("draft")
            assertThrows(Exception::class.java) { r.edit { VaultEditor.bulk(it, setOf(entry.id), tagIds = listOf(UUID.randomUUID().toString())) } }
            assertEquals(snapshot, test.saved().getString("draft"))
            assertEquals(60L, r.entities().single { it.id == entry.id }.payload.getLong("period"))
        }
        TestVault(role = "reader").use { test ->
            assertThrows(Exception::class.java) { test.repository.edit { VaultEditor.saveCategory(it, "group", null, "越权") } }
            assertFalse(test.repository.hasLocalChanges())
        }
    }
}
