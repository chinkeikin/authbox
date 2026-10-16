package com.authbox.android

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VaultTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun fixture(): JSONObject = JSONObject(instrumentation.context.assets.open("protocol.json").bufferedReader().use { it.readText() })

    @Test fun crossPlatformRoundTrip() {
        val input = fixture()
        val vault = VaultCrypto.restore(input.getString("backup"), input.getString("password"))
        try {
            assertEquals(input.getString("vmkHex"), vault.vmk.joinToString("") { "%02x".format(it) })
            assertEquals(4, vault.entities.size)
            assertEquals("94287082", VaultCrypto.totp(vault.entities.first { it.type == "account" }.payload, 59000))
            val recovered = VaultCrypto.restore(input.getString("backup"), input.getString("recoveryKey"), "recovery")
            try { assertArrayEquals(vault.vmk, recovered.vmk) } finally { recovered.close() }
            vault.entities.first { it.type == "group" }.payload.put("name", "Android 工作🔐")
            val encoded = VaultCrypto.snapshot(vault, 2)
            val result = VaultCrypto.restore(encoded, input.getString("password"))
            try { assertEquals("Android 工作🔐", result.entities.first { it.type == "group" }.payload.getString("name")) }
            finally { result.close() }
            File(instrumentation.targetContext.noBackupFilesDir, "protocol-result.json").writeText(canonical(obj("backup" to encoded)))
        } finally { vault.close() }
        assertTrue(vault.vmk.all { it == 0.toByte() })
    }

    @Test fun rejectsUntrustedSnapshots() {
        val input = fixture()
        val original = VaultCrypto.inspect(input.getString("backup"))
        fun rehash(value: JSONObject): String {
            value.remove("digest"); value.put("digest", digest(value)); return canonical(value)
        }
        val missing = original.copy().apply { getJSONArray("entities").remove(0) }
        assertThrows(Exception::class.java) { VaultCrypto.restore(rehash(missing), input.getString("password")) }
        val weakened = original.copy().apply { getJSONObject("wraps").getJSONObject("password").getJSONObject("kdf").put("memoryKiB", 8192) }
        assertThrows(Exception::class.java) { VaultCrypto.inspect(rehash(weakened)) }
        val unknown = original.copy().put("version", 2)
        assertThrows(Exception::class.java) { VaultCrypto.inspect(rehash(unknown)) }
        assertThrows(Exception::class.java) { VaultCrypto.restore(input.getString("backup"), "synthetic-wrong-password") }
        val aad = original.copy().apply { getJSONArray("entities").getJSONObject(0).put("revision", 2); put("revision", 2); getJSONObject("manifest").put("revision", 2) }
        assertThrows(Exception::class.java) { VaultCrypto.restore(rehash(aad), input.getString("password")) }
        val vault = VaultCrypto.restore(input.getString("backup"), input.getString("password"))
        try {
            vault.entities.removeAll { it.type == "group" }
            assertThrows(Exception::class.java) { VaultCrypto.snapshot(vault, 2) }
        } finally { vault.close() }
        val cases = input.getJSONArray("canonicalCases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertEquals(case.getString("expected"), canonical(case.get("value")))
        }
        assertThrows(Exception::class.java) { parseObject("{\"x\":1,\"x\":2}") }
        assertThrows(Exception::class.java) { canonical("\ud800") }
    }

    @Test fun createsRecoverableVault() {
        val created = VaultCrypto.create("Android synthetic vault 🔐")
        try {
            val encoded = canonical(created.vault.snapshot)
            val restored = VaultCrypto.restore(encoded, created.recoveryKey, "recovery")
            try { assertArrayEquals(created.vault.vmk, restored.vmk); assertTrue(restored.entities.isEmpty()) }
            finally { restored.close() }
            File(instrumentation.targetContext.noBackupFilesDir, "created-result.json").writeText(canonical(
                obj("backup" to encoded, "recoveryKey" to created.recoveryKey)))
        } finally { created.vault.close() }
    }
}
