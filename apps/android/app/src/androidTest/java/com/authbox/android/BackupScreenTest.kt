package com.authbox.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test

class BackupScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun picker(action: String, name: String): Instrumentation.ActivityMonitor {
        val mime = if (action == Intent.ACTION_OPEN_DOCUMENT) "*/*" else "application/vnd.authbox.backup+json"
        val filter = IntentFilter(action).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType(mime) }
        return instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK,
            Intent().setDataAndType(backupUri(name), "application/vnd.authbox.backup+json").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)), true)
    }
    private fun chooseAndPreview(fixture: UiFixture, key: String) {
        val monitor = picker(Intent.ACTION_OPEN_DOCUMENT, "input")
        try {
            compose.onNodeWithTag("backup").performScrollToNode(hasText("选择加密备份文件"))
            compose.onNodeWithText("选择加密备份文件").performClick()
            compose.waitUntil(15000) { !fixture.backupState().busy && fixture.backupState().selected != null }
        } finally { instrumentation.removeMonitor(monitor) }
        compose.onNodeWithTag("backup").performScrollToNode(hasText("解密并预览文件"))
        compose.onNodeWithText("解密并预览文件").performClick()
        compose.onNode(isToggleable() and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("保险库恢复密钥 ABR1").performTextInput(key)
        compose.onNodeWithText("解密", useUnmergedTree = true).performClick()
        compose.waitUntil(15000) { !fixture.backupState().busy && fixture.backupState().preview != null }
    }

    @Test fun passwordChangeExportAndPreviewDoNotReplaceOfflineData() {
        prepareBackupProvider()
        UiFixture(compose).use { fixture ->
            fixture.unlock()
            compose.onNodeWithText("账号与设备").performClick()
            compose.onNodeWithTag("security").performScrollToNode(hasText("备份与保险库"))
            compose.onNodeWithText("备份与保险库").performClick()
            compose.onAllNodesWithText("更换保险库密码").onFirst().performClick()
            compose.onNodeWithText("新保险库密码").performTextInput("AuthBox UI new vault password")
            compose.onNodeWithText("再次输入新保险库密码").performTextInput("different")
            compose.onNodeWithText("确认更换保险库密码").assertIsNotEnabled()
            compose.onNodeWithText("再次输入新保险库密码").performTextReplacement("AuthBox UI new vault password")
            compose.onNodeWithText("确认更换保险库密码").performClick()
            compose.waitUntil(15000) { !fixture.state().busy && fixture.state().pending }
            val monitor = picker(Intent.ACTION_CREATE_DOCUMENT, "output")
            try {
                compose.onNodeWithText("导出账号加密备份").performClick()
                compose.waitUntil(15000) { fixture.backupState().message.contains("已导出并重新读取核对") }
            } finally { instrumentation.removeMonitor(monitor) }
            val encoded = BackupFiles.read(fixture.context, backupUri("output"))
            VaultCrypto.restore(encoded, "AuthBox UI new vault password").use { assertEquals(4, it.entities.size) }
            File(fixture.context.noBackupFilesDir, "backup-ui-result.json").writeText(canonical(obj("backup" to encoded)))
            val before = fixture.backupState().stored
            chooseAndPreview(fixture, fixture.input.getString("recoveryKey"))
            assertEquals(1, fixture.backupState().preview?.accounts)
            assertEquals(before, fixture.backupState().stored)
            fixture.shell("input keyevent 3")
            compose.waitUntil(10000) { fixture.backupState().preview == null && fixture.backupState().local.entities.isEmpty() }
        }
    }

    @Test fun offlineImportProvidesCodesAndLocalManagement() {
        val context = instrumentation.targetContext
        val directory = File(context.noBackupFilesDir, "authbox.offline.v1")
        assumeFalse(File(directory, "backup").exists())
        prepareBackupProvider()
        try {
            UiFixture(compose).use { fixture ->
                fixture.waitText("备份与保险库")
                compose.onNodeWithText("备份与保险库").performClick()
                chooseAndPreview(fixture, fixture.input.getString("recoveryKey"))
                compose.onNodeWithTag("backup").performScrollToNode(hasText("确认保存离线副本"))
                compose.onNodeWithText("确认保存离线副本").performClick()
                compose.waitUntil(15000) { !fixture.backupState().busy && fixture.backupState().local.unlocked }
                compose.onNodeWithTag("backup").performScrollToNode(hasText("查看离线账号"))
                compose.onNodeWithText("查看离线账号").performClick()
                compose.onNodeWithTag("accounts").performScrollToNode(hasText("fixture@example.invalid"))
                compose.onNodeWithText("fixture@example.invalid", useUnmergedTree = true).assertExists()
                assertTrue(fixture.backupState().local.rows.single().code.matches(Regex("[0-9]{8}")))
                compose.onNodeWithText("取消收藏").performClick()
                compose.waitUntil(15000) { !fixture.backupState().busy && fixture.backupState().local.rows.single().favorite == false }
                assertEquals(2L, fixture.backupState().stored?.revision)
                compose.onNodeWithText("同步修改").assertDoesNotExist()
                fixture.context.getSharedPreferences("protection", 0).edit().putInt("background", 15).commit()
                fixture.shell("input keyevent 3")
                compose.waitUntil(10000) { !fixture.resumed() && fixture.backupState().local.entities.isEmpty() }
                assertFalse(fixture.backupState().local.unlocked)
                fixture.shell("am start -n com.authbox.android/.MainActivity")
                compose.waitUntil(10000) { fixture.backupState().local.unlocked && !fixture.backupState().busy }
                assertEquals(false, fixture.backupState().local.rows.single().favorite)
                compose.onNodeWithText("编辑").performClick()
                fixture.shell("input keyevent 3")
                compose.waitUntil(10000) { !fixture.resumed() }
                fixture.shell("am start -n com.authbox.android/.MainActivity")
                compose.waitUntil(10000) { fixture.resumed() && !fixture.backupState().busy }
                compose.waitUntil(10000) { !fixture.backupState().local.unlocked && fixture.backupState().local.rows.isEmpty() }
            }
        } finally {
            directory.deleteRecursively()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (suffix in listOf("storage", "device")) if (keys.containsAlias("authbox.offline.v1.$suffix")) keys.deleteEntry("authbox.offline.v1.$suffix")
        }
    }
}
