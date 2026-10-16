package com.authbox.android

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CloudTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun config() = JSONObject(instrumentation.context.assets.open("cloud-config.json").bufferedReader().use { it.readText() })

    @Test fun realAccountAndSync() {
        val c = config()
        val api = ApiClient(c.getString("origin"), mapOf("X-AuthBox-Fixture" to c.getString("fixtureToken"), "X-AuthBox-Case" to "android-integration"))
        val namespace = c.getString("namespace")
        val repository = AccountRepository(context, api, c.getString("username"), namespace)
        val phase = InstrumentationRegistry.getArguments().getString("phase", "write")
        val password = c.getString("password")
        val vaultPassword = c.getString("vaultPassword")
        try {
            if (phase == "write") {
                val recovery = repository.prepareRegistration(vaultPassword)
                assertEquals(8, recovery.second.size)
                assertThrows(Exception::class.java) { repository.register(password, c.getString("invite"), false) }
                repository.register(password, c.getString("invite"), true)
                assertEquals("manager", repository.role()); assertEquals(1L, repository.revision())
                repository.download(vaultPassword)
                val input = JSONObject(instrumentation.context.assets.open("protocol.json").bufferedReader().use { it.readText() })
                val payloads = input.getJSONArray("payloads")
                val entities = (0 until payloads.length()).map { payloads.getJSONObject(it) }.map {
                    VaultEntity(it.getString("id"), it.getString("type"), it.getJSONObject("payload"))
                }
                repository.stage(entities); repository.upload()
                assertFalse(repository.hasPending()); assertEquals(2L, repository.revision())
                val reader = AccountRepository(context, api, c.getString("username"), "$namespace.reader")
                reader.login(password, false)
                assertEquals("reader", reader.role())
                reader.download(vaultPassword)
                assertEquals("94287082", VaultCrypto.totp(reader.entities().first { it.type == "account" }.payload, 59000))
                assertThrows(Exception::class.java) { reader.stage(reader.entities()) }
                val readerState = parseObject(decodeUtf8(requireNotNull(KeystoreBox(context, "$namespace.reader").read("state"))))
                val readerToken = readerState.getJSONObject("auth").getJSONObject("session").getString("accessToken")
                val denied = assertThrows(ApiFailure::class.java) { api.request("/v1/vault/commit", JSONObject(), readerToken) }
                assertEquals(403, denied.status)
                reader.refresh(); reader.logout(); reader.dispose()
                val before = parseObject(decodeUtf8(requireNotNull(KeystoreBox(context, namespace).read("state"))))
                repository.refresh()
                val after = parseObject(decodeUtf8(requireNotNull(KeystoreBox(context, namespace).read("state"))))
                assertNotEquals(before.getJSONObject("auth").getJSONObject("session").getString("refreshToken"),
                    after.getJSONObject("auth").getJSONObject("session").getString("refreshToken"))
                repository.lock()
                assertThrows(Exception::class.java) { repository.entities() }
                repository.unlock(vaultPassword)
                val edits = repository.entities()
                edits.first { it.type == "account" }.payload.put("favorite", false)
                repository.stage(edits)
                assertTrue(repository.hasPending())
                val saved = parseObject(decodeUtf8(requireNotNull(KeystoreBox(context, namespace).read("state"))))
                val pending = saved.getJSONObject("pending")
                val artifact = obj("backup" to saved.getString("backup"), "pending" to pending,
                    "recoveryKey" to recovery.first, "publicKey" to KeystoreBox(context, namespace).publicKey())
                File(context.noBackupFilesDir, "cloud-result.json").writeText(canonical(artifact))
                assertFalse(String(File(File(context.noBackupFilesDir, namespace), "state").readBytes()).contains(password))
            } else if (phase == "read") {
                assertFalse(repository.isUnlocked()); assertTrue(repository.hasPending())
                repository.unlock(vaultPassword)
                assertFalse(repository.entities().first { it.type == "account" }.payload.getBoolean("favorite"))
                repository.upload()
                assertFalse(repository.hasPending()); assertEquals(3L, repository.revision())
                repository.download(vaultPassword)
                assertFalse(repository.entities().first { it.type == "account" }.payload.getBoolean("favorite"))
                repository.logout()
                val denied = assertThrows(ApiFailure::class.java) { repository.login("Synthetic wrong password!", true) }
                assertEquals(401, denied.status)
                repository.login(password, true)
                assertEquals("manager", repository.role())
                repository.download(vaultPassword)
                val edits = repository.entities()
                edits.first { it.type == "group" }.payload.put("name", "Android 本地待解决")
                repository.stage(edits)
                val saved = parseObject(decodeUtf8(requireNotNull(KeystoreBox(context, namespace).read("state"))))
                val artifact = JSONObject(File(context.noBackupFilesDir, "cloud-result.json").readText())
                artifact.put("backup", saved.getString("backup")).put("pending", saved.getJSONObject("pending"))
                File(context.noBackupFilesDir, "cloud-result.json").writeText(canonical(artifact))
            } else {
                assertFalse(repository.isUnlocked()); assertTrue(repository.hasPending())
                repository.unlock(vaultPassword)
                val saved = parseObject(decodeUtf8(requireNotNull(KeystoreBox(context, namespace).read("state"))))
                val auth = saved.getJSONObject("auth")
                val token = auth.getJSONObject("session").getString("accessToken")
                val peer = c.getJSONObject("peerPayload")
                val hash = digest(peer)
                val challenge = JSONObject(api.request("/v1/challenges", obj("operation" to "sync.commit", "requestHash" to hash), token))
                val proof = repository.checkedProof(challenge, "sync.commit", hash, auth.getJSONObject("user").getString("id"),
                    auth.getJSONObject("device").getString("id"), token.split('.')[1])
                val committed = JSONObject(api.request("/v1/vault/commit", obj("payload" to peer, "proof" to proof), token))
                assertEquals(4L, committed.getLong("revision"))
                assertThrows(SyncConflict::class.java) { repository.upload() }
                assertTrue(repository.hasPending()); assertEquals(3L, repository.revision())
                assertEquals("Android 本地待解决", repository.entities().first { it.type == "group" }.payload.getString("name"))
                assertThrows(Exception::class.java) { repository.download(vaultPassword) }
                repository.lock()
            }
        } finally { repository.dispose() }
    }

    @Test fun refusesForeignSigningChallenge() {
        val c = config()
        val api = ApiClient(c.getString("origin"))
        val repository = AccountRepository(context, api, c.getString("username"), c.getString("namespace") + ".proof")
        val deviceId = "10000000-0000-4000-8000-000000000001"
        val id = "20000000-0000-4000-8000-000000000001"
        val expires = System.currentTimeMillis() + 90000
        val hash = b64(ByteArray(32))
        for ((index, changed) in listOf(3 to "https://evil.invalid", 4 to deviceId, 5 to "foreign_user", 6 to id,
            7 to id, 8 to "auth.recover", 9 to b64(ByteArray(32) { 1 }), 10 to expires + 1)) {
            val payload = array("AuthBox", 1, "device-proof", api.origin, id, c.getString("username"), deviceId,
                "", "auth.device-login", hash, expires, b64(ByteArray(32)))
            payload.put(index, changed)
            val challenge = obj("id" to id, "payload" to canonical(payload), "expiresAt" to expires)
            assertThrows(Exception::class.java) { repository.checkedProof(challenge, "auth.device-login", hash,
                c.getString("username"), deviceId, "") }
        }
    }
}

class CloudCleanupTest {
    @Test fun removesSyntheticAccountOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val config = JSONObject(instrumentation.context.assets.open("cloud-config.json").bufferedReader().use { it.readText() })
        val namespace = config.getString("namespace")
        require(namespace.matches(Regex("authbox\\.test\\.cloud\\.[a-f0-9]{10}")))
        val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (name in listOf(namespace, "$namespace.reader", "$namespace.proof")) {
            File(instrumentation.targetContext.noBackupFilesDir, name).deleteRecursively()
            for (suffix in listOf("storage", "device")) if (keys.containsAlias("$name.$suffix")) keys.deleteEntry("$name.$suffix")
        }
    }
}
