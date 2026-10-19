package com.authbox.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AutoSyncScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun attach(fixture: UiFixture, api: ReviewApi) {
        fixture.attachRepository(api) { saved ->
            saved.remove("refreshUncertain")
            api.deviceId = saved.getString("identityId"); api.userId = saved.getJSONObject("auth").getJSONObject("user").getString("id")
            saved.getJSONObject("auth").put("session", obj("accessToken" to "ab1a.${UUID.randomUUID()}.${b64(ByteArray(32))}",
                "accessExpiresAt" to System.currentTimeMillis() + 3600000))
        }
    }
    private fun enable(fixture: UiFixture) { fixture.model { it.networkChanged(true); it.setAutoSync(true) } }

    @Test fun automaticPullEditPauseUploadAndPersistedSwitch() {
        UiFixture(compose).use { fixture ->
            val original = fixture.input.getString("backup")
            val remote = VaultCrypto.restore(original, fixture.input.getString("password")).use { vault ->
                vault.entities.single { it.type == "group" }.payload.put("name", "自动拉取分组")
                VaultCrypto.snapshot(vault, 3)
            }
            val api = ReviewApi(remote, original, fixture.origin)
            attach(fixture, api); enable(fixture)
            compose.waitUntil(15000) { fixture.state().revision == 3L && !fixture.state().busy }
            assertTrue(fixture.state().autoSync.lastSuccessAt != null)
            assertTrue(fixture.state().entities.any { it.payload.optString("name") == "自动拉取分组" })
            compose.onNodeWithText("分组与标签").performClick()
            compose.onNodeWithText("自动拉取分组").performClick()
            compose.onNodeWithText("重命名").performTextReplacement("自动提交分组")
            compose.onNodeWithText("保存名称").performClick()
            compose.waitUntil(10000) { fixture.state().pending && !fixture.state().busy }
            fixture.model { it.retryAutoSync(); it.tick() }
            Thread.sleep(3000)
            fixture.model { it.tick() }
            compose.waitUntil(4000) { !fixture.state().busy }
            assertNull(api.lastCommit)
            compose.onNodeWithText("完成").performClick()
            compose.waitUntil(15000) { fixture.state().revision == 4L && !fixture.state().pending && !fixture.state().busy }
            assertEquals("自动提交分组", fixture.state().entities.single { it.type == "group" }.payload.getString("name"))
            compose.onNodeWithText("账号与设备").performClick()
            val checked = AtomicInteger()
            api.beforeHead = { checked.incrementAndGet() }
            compose.onNodeWithTag("security").performScrollToNode(hasText("立即检查并同步"))
            compose.onNodeWithText("立即检查并同步").performClick()
            compose.waitUntil(10000) { checked.get() == 1 && !fixture.state().busy }
            compose.onNodeWithTag("security").performScrollToNode(hasTestTag("auto-sync-enabled"))
            compose.onNodeWithTag("auto-sync-enabled").performClick()
            assertFalse(fixture.state().autoSync.enabled)
            assertFalse(fixture.context.getSharedPreferences("sync", 0).getBoolean("enabled", true))
            fixture.waitText("自动同步：已暂停")
        }
    }

    @Test fun offlineRecoveryAndBackoffDoNotHotLoop() {
        UiFixture(compose).use { fixture ->
            val original = fixture.input.getString("backup")
            val attempts = AtomicInteger()
            val failed = AtomicBoolean(true)
            val api = object : ReviewApi(original, original, fixture.origin) {
                override fun request(path: String, body: JSONObject?, access: String?): String {
                    attempts.incrementAndGet()
                    if (failed.get()) throw IOException("Synthetic network failure")
                    return super.request(path, body, access)
                }
            }
            attach(fixture, api)
            fixture.model { it.setAutoSync(true); it.networkChanged(false) }
            Thread.sleep(2500)
            assertEquals(0, attempts.get())
            fixture.model { it.networkChanged(true) }
            compose.waitUntil(10000) { attempts.get() == 1 && !fixture.state().busy }
            repeat(20) { fixture.model { it.networkChanged(false); it.networkChanged(true); it.tick() } }
            assertEquals(1, attempts.get())
            failed.set(false)
            compose.waitUntil(12000) { fixture.state().autoSync.lastSuccessAt != null && !fixture.state().busy }
            assertEquals(2, attempts.get())
            repeat(20) { fixture.model { it.tick() } }
            assertEquals(2, attempts.get())
        }
    }

    @Test fun lateAutomaticResponseCannotUnlockAfterBackgroundAndResume() {
        UiFixture(compose).use { fixture ->
            val original = fixture.input.getString("backup")
            val remote = VaultCrypto.restore(original, fixture.input.getString("password")).use { VaultCrypto.snapshot(it, 3) }
            val snapshots = AtomicInteger()
            val api = object : ReviewApi(remote, original, fixture.origin) {
                override fun request(path: String, body: JSONObject?, access: String?): String {
                    if (path.startsWith("/v1/vault/snapshot")) snapshots.incrementAndGet()
                    return super.request(path, body, access)
                }
            }
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            api.beforeHead = { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
            attach(fixture, api)
            try {
                enable(fixture)
                compose.waitUntil(10000) { entered.count == 0L }
                fixture.shell("input keyevent 3")
                compose.waitUntil(10000) { !fixture.resumed() }
                fixture.shell("am start -n com.authbox.android/.MainActivity")
                compose.waitUntil(10000) { fixture.resumed() }
                release.countDown()
                compose.waitUntil(10000) { !fixture.state().busy }
                assertFalse(fixture.state().unlocked); assertTrue(fixture.state().entities.isEmpty()); assertTrue(fixture.state().rows.isEmpty())
                assertTrue(fixture.state().recovery.isEmpty()); assertNull(fixture.state().review)
                assertEquals(0, snapshots.get())
            } finally { release.countDown() }
        }
    }

    @Test fun recoveryMaterialsPauseSyncAndRejectedSessionLocksPlaintext() {
        UiFixture(compose).use { fixture ->
            val source = fixture.input.getString("backup")
            val attempts = AtomicInteger()
            val api = object : ReviewApi(source, source, fixture.origin) {
                override fun request(path: String, body: JSONObject?, access: String?): String {
                    attempts.incrementAndGet(); throw ApiFailure(401, "invalid_session")
                }
            }
            attach(fixture, api)
            fixture.model { it.prepareVaultRecoveryKey(fixture.origin, fixture.username) }
            compose.waitUntil(10000) { !fixture.state().busy && fixture.state().recovery.size == 1 }
            val materials = fixture.state().recovery.toList()
            enable(fixture)
            Thread.sleep(3000)
            fixture.model { it.tick() }
            assertEquals(0, attempts.get()); assertEquals(materials, fixture.state().recovery)
            fixture.model { it.lock() }
            fixture.unlock()
            compose.waitUntil(12000) { !fixture.state().busy && !fixture.state().unlocked && attempts.get() == 1 }
            assertTrue(fixture.state().entities.isEmpty()); assertTrue(fixture.state().rows.isEmpty())
            assertTrue(fixture.state().recovery.isEmpty()); assertTrue(fixture.state().autoSync.message.contains("失效"))
            repeat(10) { fixture.model { it.tick() } }
            assertEquals(1, attempts.get())
        }
    }

    @Test fun retryPolicyKeepsBoundsAndNetworkWakeRespectsDelay() {
        val policy = SyncSchedule { 1.0 }
        policy.reset(1000); assertEquals(3000L, policy.nextAttempt)
        policy.failure(1000, "", true); assertEquals(7250L, policy.nextAttempt)
        policy.wake(2000); assertEquals(7250L, policy.nextAttempt)
        repeat(20) { policy.failure(1000, "", true) }
        assertEquals(301000L, policy.nextAttempt)
        policy.failure(2000, "需要重新登录", false); assertEquals("需要重新登录", policy.blocked)
        policy.wake(400000); assertNotNull(policy.blocked)
        policy.reset(5000); assertNull(policy.blocked); assertEquals(0, policy.failures)
        policy.success(5000); assertEquals(65000L, policy.nextAttempt)
    }
}
