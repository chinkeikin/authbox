package com.authbox.android

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class ImportScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun imageBatchPreviewRequiresExplicitUnsupportedChoice() {
        prepareQrProvider()
        UiFixture(compose).use { fixture ->
            fixture.unlock()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val first = Uri.parse("content://com.authbox.android.test.qr/page1")
            val clip = ClipData(ClipDescription("public test", arrayOf("image/png")), ClipData.Item(first))
            clip.addItem(ClipData.Item(Uri.parse("content://com.authbox.android.test.qr/page0")))
            val result = Intent().setDataAndType(first, "image/png").apply { clipData = clip }
            val filter = IntentFilter(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("image/*") }
            val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, result), true)
            try {
                compose.onNodeWithText("二维码图片").performClick()
                compose.waitForIdle()
                if (!fixture.state().unlocked) fixture.unlock()
                compose.waitUntil(15000) { !fixture.state().busy }
                assertTrue(fixture.state().message, fixture.state().importPreview.complete)
                compose.onNodeWithTag("accounts").performScrollToNode(hasText("查看并确认导入"))
                compose.onNodeWithText("查看并确认导入").performClick()
                compose.onNodeWithText("确认保存导入").assertIsNotEnabled()
                compose.onNodeWithText("HOTP 不受支持", substring = true).assertExists()
                compose.onAllNodes(isToggleable()).onLast().performScrollTo().performClick()
                compose.onNodeWithText("确认保存导入").assertIsEnabled().performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("查看并确认导入").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithText("搜索账号或分组").performTextInput("part1@example.invalid")
                compose.onNodeWithText("搜索账号或分组").performImeAction()
                compose.onNodeWithTag("accounts").performScrollToNode(hasText("part1@example.invalid"))
                compose.onNode(hasText("part1@example.invalid") and hasAnyAncestor(hasTestTag("accounts")), useUnmergedTree = true).assertExists()
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }

    @Test fun partialBatchCannotBeConfirmedAndLockClearsPreview() {
        UiFixture(compose).use { fixture ->
            fixture.unlock()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val input = JSONObject(instrumentation.context.assets.open("import.json").bufferedReader().use { it.readText() })
            compose.onNodeWithText("粘贴二维码").performClick()
            compose.onNodeWithText("otpauth 或迁移 URI").performTextInput(input.getJSONArray("migration").getString(0))
            compose.onNodeWithText("加入导入预览").performClick()
            fixture.waitText("批次尚未收齐，请继续扫描剩余二维码")
            compose.onNodeWithText("查看并确认导入").performClick()
            compose.onNodeWithText("确认保存导入").assertIsNotEnabled()
            compose.onNodeWithText("返回继续扫描").performClick()
            compose.onNodeWithText("锁定").performClick()
            fixture.unlock()
            compose.onNodeWithText("查看并确认导入").assertDoesNotExist()
        }
    }
}
