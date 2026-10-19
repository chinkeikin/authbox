package com.authbox.android

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImportTest {
    private fun fixture() = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("import.json").bufferedReader().use { it.readText() })

    @Test fun standardUrisMatchSharedProtocol() {
        val values = fixture().getJSONArray("standards")
        for (index in 0 until values.length()) {
            val vector = values.getJSONObject(index)
            val result = OtpImport.parse(vector.getString("encoded"))
            val payload = requireNotNull(result.entries.single().payload)
            val expected = vector.getJSONObject("expected")
            for (name in expected.keys()) assertEquals(expected.get(name), payload.get(name))
            VaultCrypto.validateEntities(listOf(VaultEntity("10000000-0000-4000-8000-000000000001", "account", payload)))
        }
        val invalid = fixture().getJSONArray("invalid")
        for (index in 0 until invalid.length()) assertThrows(Exception::class.java) { OtpImport.parse(invalid.getString(index)) }
    }

    @Test fun migrationBatchCompletenessDuplicatesAndUnsupportedRows() {
        val input = fixture(); val pages = input.getJSONArray("migration")
        val session = ImportSession()
        session.add(OtpImport.parse(pages.getString(1)))
        assertFalse(session.preview(emptyList()).complete)
        assertThrows(Exception::class.java) { session.apply(mutableListOf(), false, true) }
        assertThrows(Exception::class.java) { session.add(OtpImport.parse(input.getString("differentBatch"))) }
        session.add(OtpImport.parse(pages.getString(0)))
        session.add(OtpImport.parse(pages.getString(0)))
        val complete = session.preview(emptyList())
        assertTrue(complete.complete); assertEquals(2, complete.received); assertEquals(3, complete.rows.size)
        assertEquals(1, complete.rows.count { it.payload == null && it.reason.contains("HOTP") })
        assertThrows(Exception::class.java) { session.apply(mutableListOf(), false, false) }
        assertThrows(Exception::class.java) { session.add(OtpImport.parse(input.getString("conflictingPage"))) }
        val entries = mutableListOf<VaultEntity>()
        session.apply(entries, false, true); assertEquals(2, entries.size)
        assertEquals("alice@example.invalid", entries.first().payload.getString("account"))
        session.apply(entries, false, true); assertEquals(2, entries.size)
        session.apply(entries, true, true); assertEquals(4, entries.size)
        assertEquals(4, entries.map { it.id }.toSet().size)
        val single = ImportSession().apply { add(OtpImport.parse(input.getString("single"))) }
        assertTrue(single.preview(emptyList()).complete)
        val rollback = ImportSession()
        assertThrows(Exception::class.java) { rollback.addAll(listOf(OtpImport.parse(pages.getString(0)), OtpImport.parse(input.getString("differentBatch")))) }
        assertEquals(0, rollback.preview(emptyList()).received)
    }

    @Test fun confirmedImportUsesEncryptedOfflineTransaction() {
        TestVault().use { test ->
            val values = fixture().getJSONArray("standards")
            val session = ImportSession().apply { for (index in 0 until values.length()) add(OtpImport.parse(values.getJSONObject(index).getString("encoded"))) }
            test.repository.edit { session.apply(it, false, false) }
            test.reopen()
            assertEquals(4, test.repository.entities().count { it.type == "account" })
            assertTrue(test.repository.hasDraft()); assertFalse(test.repository.hasPending())
        }
    }
}
