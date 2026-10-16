package com.authbox.android

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

class AccountStateTest {
    @Test fun uncertainRefreshAndCorruptionFailClosed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val namespace = "authbox.test.state." + UUID.randomUUID().toString()
        val api = ApiClient("https://must-not-be-contacted.invalid")
        val box = KeystoreBox(context, namespace)
        try {
            AccountRepository(context, api, "state_fixture", namespace).dispose()
            val fixture = JSONObject(instrumentation.context.assets.open("protocol.json").bufferedReader().use { it.readText() })
            val state = parseObject(decodeUtf8(requireNotNull(box.read("state"))))
            val token = "ab1r." + UUID.randomUUID() + "." + b64(randomBytes(32))
            val auth = obj("user" to obj("id" to UUID.randomUUID().toString(), "username" to "state_fixture"),
                "device" to obj("id" to state.getString("identityId"), "role" to "manager"),
                "session" to obj("refreshToken" to token))
            state.put("auth", auth).put("backup", fixture.getString("backup")).put("refreshUncertain", true)
            box.write("state", canonical(state).toByteArray())
            val restored = AccountRepository(context, api, "state_fixture", namespace)
            assertFalse(restored.isUnlocked())
            assertThrows(IllegalArgumentException::class.java) { restored.refresh() }
            assertThrows(IllegalArgumentException::class.java) { restored.logout() }
            restored.unlock(fixture.getString("password"))
            assertEquals(4, restored.entities().size)
            restored.stage(restored.entities())
            assertTrue(restored.hasPending())
            restored.dispose()
            val next = AccountRepository(context, api, "state_fixture", namespace)
            assertFalse(next.isUnlocked()); assertTrue(next.hasPending())
            assertThrows(IllegalArgumentException::class.java) { next.upload() }
            assertTrue(next.hasPending())
            val file = File(File(context.noBackupFilesDir, namespace), "state")
            val encrypted = file.readBytes()
            assertFalse(String(encrypted).contains(token))
            assertFalse(String(encrypted).contains("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            file.writeBytes(encrypted)
            assertThrows(Exception::class.java) { AccountRepository(context, api, "state_fixture", namespace) }
            assertArrayEquals(encrypted, file.readBytes())
        } finally {
            File(context.noBackupFilesDir, namespace).deleteRecursively()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (suffix in listOf("storage", "device")) if (keys.containsAlias("$namespace.$suffix")) keys.deleteEntry("$namespace.$suffix")
        }
    }
}
