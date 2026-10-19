package com.authbox.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.platform.app.InstrumentationRegistry

class SyncReviewScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun scroll(text: String) = compose.onNodeWithTag("sync-review").performScrollToNode(hasText(text))
    private fun setup(fixture: UiFixture, startRemote: Boolean): ReviewApi {
        val original = fixture.input.getString("backup")
        val remote = VaultCrypto.restore(original, fixture.input.getString("password")).use { vault ->
            vault.entities.removeAll { it.type == "account" }; VaultCrypto.snapshot(vault, 3)
        }
        val api = object : ReviewApi(remote, original, fixture.origin) {
            override fun request(path: String, body: JSONObject?, access: String?): String {
                if (path == "/v1/vault/history") {
                    val latest = VaultCrypto.inspect(this.remote); val old = VaultCrypto.inspect(historical)
                    fun row(header: JSONObject, encoded: String) = obj("revision" to header.getLong("revision"), "createdAt" to System.currentTimeMillis(),
                        "bytes" to encoded.toByteArray().size, "digest" to header.getString("digest"))
                    return canonical(obj("vaultId" to latest.getString("vaultId"), "revision" to latest.getLong("revision"), "keyGeneration" to 1,
                        "entries" to JSONArray().put(row(latest, this.remote)).put(row(old, historical)), "nextBefore" to JSONObject.NULL))
                }
                return super.request(path, body, access)
            }
        }
        api.retired = listOf(VaultCrypto.inspect(original).getJSONArray("entities").getJSONObject(0).getString("id"))
        fixture.attachRepository(api) { saved ->
            saved.remove("refreshUncertain")
            api.userId = saved.getJSONObject("auth").getJSONObject("user").getString("id"); api.deviceId = saved.getString("identityId")
            saved.getJSONObject("auth").put("session", obj("accessToken" to "ab1a.${UUID.randomUUID()}.${b64(ByteArray(32))}", "accessExpiresAt" to System.currentTimeMillis() + 3600000))
            if (startRemote) saved.put("backup", remote)
            else {
                val draft = VaultCrypto.restore(original, fixture.input.getString("password")).use { vault ->
                    vault.entities.single { it.type == "account" }.payload.put("favorite", false); VaultCrypto.snapshot(vault, 2)
                }
                saved.put("draft", draft)
            }
        }
        compose.onNodeWithText("冲突与历史").performClick()
        return api
    }
    private fun decrypt(fixture: UiFixture) {
        compose.onNodeWithText("最新云端密码或恢复密钥").performTextInput(fixture.input.getString("password"))
        compose.onNodeWithText("解密并比较").performClick()
        compose.waitUntil(15000) { !fixture.state().busy && fixture.state().review != null }
        compose.onNodeWithText("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", substring = true).assertDoesNotExist()
    }
    private fun confirmChoice() {
        compose.onNodeWithText("确认执行").assertIsNotEnabled()
        compose.onNodeWithTag("review-confirm").performClick()
        compose.onNodeWithText("确认执行").performClick()
    }
    @Test fun localChoiceConfirmationUploadArchiveExportAndBackgroundLock() {
        prepareBackupProvider()
        UiFixture(compose).use { fixture ->
            val api = setup(fixture, false)
            compose.onNodeWithText("比较本地与云端").performClick(); decrypt(fixture)
            scroll("保留本地业务内容"); compose.onNodeWithText("保留本地业务内容").performClick()
            compose.onNodeWithText("确认执行").assertIsNotEnabled(); compose.onNodeWithText("取消", useUnmergedTree = true).performClick()
            assertTrue(fixture.state().hasReview); assertEquals(0, fixture.state().savedVersions.size)
            compose.onNodeWithText("保留本地业务内容").performClick(); confirmChoice()
            compose.waitUntil(10000) { !fixture.state().busy && !fixture.state().hasReview }
            assertEquals(3L, fixture.state().revision); assertEquals(2, fixture.state().savedVersions.size)
            scroll("提交已确认内容"); compose.onNodeWithText("提交已确认内容").performClick()
            compose.waitUntil(10000) { !fixture.state().busy && fixture.state().revision == 4L && !fixture.state().pending }
            scroll("删除保留副本 3"); compose.onNodeWithText("删除保留副本 3").performClick()
            compose.onNodeWithText("确认执行").assertIsNotEnabled(); compose.onNodeWithText("取消", useUnmergedTree = true).performClick()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val filter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("application/vnd.authbox.backup+json") }
            val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setDataAndType(backupUri("output"),
                "application/vnd.authbox.backup+json").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)), true)
            try {
                scroll("导出保留副本 3"); compose.onNodeWithText("导出保留副本 3").performClick()
                compose.waitUntil(15000) { fixture.backupState().message.contains("已导出并重新读取核对") }
            } finally { instrumentation.removeMonitor(monitor) }
            val archived = BackupFiles.read(fixture.context, backupUri("output"))
            assertEquals(3L, VaultCrypto.inspect(archived).getLong("revision"))
            File(fixture.context.noBackupFilesDir, "review-ui-result.json").writeText(canonical(obj("backup" to api.remote, "removed" to archived, "pending" to api.lastCommit)))
            fixture.shell("input keyevent 3")
            compose.waitUntil(10000) { !fixture.state().unlocked && fixture.state().review == null && fixture.state().savedVersions.isEmpty() }
        }
    }
    @Test fun historyPreviewCreatesANewVersionAndRestoresMissingAccount() {
        UiFixture(compose).use { fixture ->
            val api = setup(fixture, true)
            compose.onNodeWithText("读取云端历史").performClick()
            compose.waitUntil(10000) { !fixture.state().busy && fixture.state().history != null }
            scroll("预览历史 1"); compose.onNodeWithText("预览历史 1").performClick(); decrypt(fixture)
            scroll("恢复为新版本"); compose.onNodeWithText("恢复为新版本").performClick(); confirmChoice()
            compose.waitUntil(10000) { !fixture.state().busy && fixture.state().pending && !fixture.state().hasReview }
            scroll("提交已确认内容"); compose.onNodeWithText("提交已确认内容").performClick()
            compose.waitUntil(10000) { !fixture.state().busy && fixture.state().revision == 4L && !fixture.state().pending }
            assertEquals(4, fixture.state().entities.size)
            assertEquals(1, api.lastCommit!!.getJSONArray("restoredIds").length())
        }
    }
    @Test fun backgroundAndResumeDuringComparisonCannotRedisplayPlaintext() {
        UiFixture(compose).use { fixture ->
            val api = setup(fixture, false)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            api.beforeHead = { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
            try {
                compose.onNodeWithText("比较本地与云端").performClick()
                compose.onNodeWithText("最新云端密码或恢复密钥").performTextInput(fixture.input.getString("password"))
                compose.onNodeWithText("解密并比较").performClick()
                compose.waitUntil(10000) { entered.count == 0L }
                fixture.shell("input keyevent 3")
                compose.waitUntil(10000) { !fixture.resumed() }
                fixture.shell("am start -n com.authbox.android/.MainActivity")
                compose.waitUntil(10000) { fixture.resumed() }
                release.countDown()
                compose.waitUntil(10000) { !fixture.state().busy }
                assertFalse(fixture.state().unlocked); assertNull(fixture.state().review)
                assertTrue(fixture.state().entities.isEmpty()); assertTrue(fixture.state().savedVersions.isEmpty())
                assertTrue(fixture.state().hasReview)
            } finally { release.countDown() }
        }
    }
}
