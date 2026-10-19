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
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test

class RegistrationScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun chooseTarget(alreadyVisible: Boolean = false) {
        val filter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("application/json") }
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK,
            Intent().setDataAndType(backupUri("output"), "application/json").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)), true)
        try {
            val button = compose.onNodeWithText("选择恢复材料包保存位置")
            if (!alreadyVisible) button.performScrollTo()
            button.performClick()
            compose.waitUntil(10000) { monitor.hits > 0 }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun copyNode(text: String) = compose.onNodeWithTag("registration").performScrollToNode(hasText(text))
    private fun clean(namespace: String) {
        File(context.noBackupFilesDir, namespace).deleteRecursively()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (suffix in listOf("storage", "device")) if (keys.containsAlias("$namespace.$suffix")) keys.deleteEntry("$namespace.$suffix")
    }

    @Test fun offlineCopySavesMatchingKitAndRequiresBothConfirmations() {
        assumeFalse(File(context.noBackupFilesDir, "authbox.offline.v1/backup").exists())
        val user = "ui_copy_" + UUID.randomUUID().toString().take(8)
        prepareBackupProvider()
        try {
            val input = org.json.JSONObject(instrumentation.context.assets.open("protocol.json").bufferedReader().use { it.readText() })
            OfflineVaultStore(context).use {
                it.prepareImport(input.getString("backup"), input.getString("password"), "password"); it.confirmImport(false)
            }
            UiFixture(compose).use { fixture ->
                fixture.waitText("备份与保险库")
                compose.onNodeWithText("备份与保险库").performClick()
                compose.onNodeWithTag("backup").performScrollToNode(hasText("接入新账号同步"))
                compose.onNodeWithText("接入新账号同步").performClick()
                copyNode("选择恢复材料包保存位置"); chooseTarget(true)
                copyNode("新平台账号"); compose.onNodeWithText("新平台账号").performTextReplacement(user)
                copyNode("源副本使用 ABR1"); compose.onNodeWithTag("source-recovery").performClick()
                copyNode("源副本密码或恢复密钥"); compose.onNodeWithText("源副本密码或恢复密钥").performTextInput(fixture.input.getString("recoveryKey"))
                copyNode("新同步保险库密码"); compose.onNodeWithText("新同步保险库密码").performTextInput("AuthBox copied UI vault password")
                copyNode("再次输入新同步保险库密码"); compose.onNodeWithText("再次输入新同步保险库密码").performTextInput("mismatch")
                copyNode("生成新账号保险库与恢复包"); compose.onNodeWithText("生成新账号保险库与恢复包").assertIsNotEnabled()
                copyNode("再次输入新同步保险库密码"); compose.onNodeWithText("再次输入新同步保险库密码").performTextReplacement("AuthBox copied UI vault password")
                copyNode("确认创建独立副本，原账号和离线副本保持独立"); compose.onNodeWithTag("copy-confirm").performClick()
                copyNode("生成新账号保险库与恢复包"); compose.onNodeWithText("生成新账号保险库与恢复包").performClick()
                compose.waitUntil(20000) { !fixture.state().busy && fixture.state().registrationMaterialId != null }
                val encoded = context.contentResolver.openInputStream(backupUri("output"))!!.bufferedReader().use { it.readText() }
                val kit = RecoveryKit.inspect(encoded)
                val info = requireNotNull(fixture.state().registration)
                assertEquals(info.vaultId, kit.getString("vaultId")); assertEquals(user, kit.getString("username"))
                assertNotEquals(fixture.backupState().stored?.vaultId, info.vaultId)
                assertEquals(4, info.entities)
                assertTrue(fixture.state().recovery.contains("服务地址：https://ui.example.invalid"))
                val prepared = parseObject(decodeUtf8(requireNotNull(KeystoreBox(context, accountNamespace("https://ui.example.invalid", user)).read("state"))))
                File(context.noBackupFilesDir, "registration-ui-result.json").writeText(canonical(obj("kit" to encoded, "backup" to prepared.getJSONObject("registration").getString("backup"))))
                copyNode("确认注册新同步账号"); compose.onNodeWithText("确认注册新同步账号").assertIsNotEnabled()
                copyNode("新账号登录密码"); compose.onNodeWithText("新账号登录密码").performTextInput("Synthetic UI login password")
                copyNode("再次输入新账号登录密码"); compose.onNodeWithText("再次输入新账号登录密码").performTextInput("Synthetic UI login password")
                copyNode("新账号注册邀请码"); compose.onNodeWithText("新账号注册邀请码").performTextInput(b64(ByteArray(32)))
                copyNode("已核对并在手机以外保存完整新恢复包"); compose.onNodeWithTag("kit-saved").performClick()
                copyNode("确认注册新同步账号"); compose.onNodeWithText("确认注册新同步账号").assertIsEnabled()
                fixture.shell("input keyevent 3")
                compose.waitUntil(10000) { fixture.state().recovery.isEmpty() && fixture.state().registrationMaterialId == null }
            }
        } finally {
            clean("authbox.offline.v1"); clean(accountNamespace("https://ui.example.invalid", user))
        }
    }

    @Test fun freshRegistrationAlsoWritesCompleteRecoveryKit() {
        prepareBackupProvider()
        UiFixture(compose, withVault = false).use { fixture ->
            fixture.waitText("新建平台账号")
            compose.onNodeWithText("新建平台账号").performScrollTo().performClick()
            chooseTarget()
            compose.onNodeWithText("保险库密码（可离线解锁）").performScrollTo().performTextInput("Fresh UI vault password")
            compose.onNodeWithText("生成保险库与恢复材料").performScrollTo().performClick()
            compose.waitUntil(15000) { !fixture.state().busy && fixture.state().registrationMaterialId != null }
            val encoded = context.contentResolver.openInputStream(backupUri("output"))!!.bufferedReader().use { it.readText() }
            val kit = RecoveryKit.inspect(encoded)
            assertEquals(fixture.state().registrationMaterialId, kit.getString("vaultId"))
            assertEquals(0, fixture.state().registration?.entities)
            assertEquals(8, kit.getJSONArray("accountRecoveryCodes").length())
            assertFalse(encoded.contains("Fresh UI vault password"))
            compose.onNodeWithText("确认注册").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("registration-saved").performScrollTo().performClick()
            compose.onNodeWithText("确认注册").performScrollTo().assertIsEnabled()
        }
    }
}
