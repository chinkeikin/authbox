package com.authbox.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Rule
import org.junit.Test

class CameraFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun realCameraScanToConfirmedDraft() {
        UiFixture(compose).use { fixture ->
            fixture.unlock()
            if (fixture.context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                compose.onNodeWithText("相机扫码").performClick()
                compose.waitUntil(180000) { fixture.context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
                compose.waitUntil(15000) { fixture.resumed() }
                compose.waitForIdle()
                if (!fixture.state().unlocked) fixture.unlock()
            }
            compose.onNodeWithText("相机扫码").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("相机已就绪，请对准二维码").fetchSemanticsNodes().isNotEmpty() ||
                fixture.state().importPreview.rows.isNotEmpty() }
            compose.waitUntil(300000) { fixture.state().importPreview.rows.isNotEmpty() }
            compose.onNodeWithTag("accounts").performScrollToNode(hasText("查看并确认导入"))
            compose.onNodeWithText("查看并确认导入").performClick()
            compose.onNodeWithText("AuthBox Camera", substring = true).assertExists()
            compose.onNodeWithText("确认保存导入").assertIsEnabled().performClick()
            compose.waitUntil(15000) { !fixture.state().busy && fixture.state().importPreview.rows.isEmpty() }
            compose.onNodeWithTag("accounts").performScrollToNode(hasText("camera@example.invalid"))
            compose.onNode(hasText("camera@example.invalid") and hasAnyAncestor(hasTestTag("accounts")), useUnmergedTree = true).assertExists()
            org.junit.Assert.assertTrue(fixture.state().pending)
        }
    }
}
