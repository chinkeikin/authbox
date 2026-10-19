package com.authbox.android

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudManagementTest {
    @Test fun importsAndManagementDraftSync() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = JSONObject(instrumentation.context.assets.open("cloud-config.json").bufferedReader().use { it.readText() })
        val api = ApiClient(config.getString("origin"), mapOf("X-AuthBox-Fixture" to config.getString("fixtureToken"), "X-AuthBox-Case" to "android-management"))
        val repository = AccountRepository(context, api, config.getString("username") + "_m", config.getString("namespace") + ".management")
        val password = config.getString("vaultPassword")
        try {
            if (InstrumentationRegistry.getArguments().getString("phase", "write") == "write") {
                repository.prepareRegistration(password)
                repository.register(config.getString("password"), config.getString("managementInvite"), true)
                repository.download(password)
                val input = JSONObject(instrumentation.context.assets.open("import.json").bufferedReader().use { it.readText() })
                val session = ImportSession()
                val standard = input.getJSONArray("standards")
                for (index in 0 until standard.length()) session.add(OtpImport.parse(standard.getJSONObject(index).getString("encoded")))
                repository.edit { session.apply(it, false, false) }
                val migration = ImportSession()
                val pages = input.getJSONArray("migration")
                for (index in 0 until pages.length()) migration.add(OtpImport.parse(pages.getString(index)))
                repository.edit { migration.apply(it, false, true) }
                var group = ""; var tag = ""
                repository.edit { group = VaultEditor.saveCategory(it, "group", null, "Cloud QA"); tag = VaultEditor.saveCategory(it, "tag", null, "同步标签") }
                val ids = repository.entities().filter { it.type == "account" }.map { it.id }.toSet()
                assertEquals(4, ids.size)
                repository.edit { VaultEditor.bulk(it, ids, group, true, listOf(tag), true) }
                repository.edit { VaultEditor.saveCategory(it, "group", group, "Cloud QA renamed"); VaultEditor.bulk(it, setOf(ids.first()), trash = true) }
                repository.edit { VaultEditor.bulk(it, setOf(ids.first()), trash = false) }
                assertTrue(repository.hasDraft()); assertFalse(repository.hasPending()); assertEquals(1L, repository.revision())
            } else {
                assertFalse(repository.isUnlocked()); assertTrue(repository.hasDraft())
                repository.unlock(password)
                assertEquals(6, repository.entities().size)
                repository.upload()
                assertFalse(repository.hasLocalChanges()); assertEquals(2L, repository.revision())
                repository.download(password)
                assertEquals(6, repository.entities().size)
            }
        } finally { repository.dispose() }
    }
}
