package com.authbox.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GalleryFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realSystemPickerToConfirmedDraft() {
        UiFixture(compose).use { fixture ->
            fixture.unlock()
            compose.onNodeWithText("二维码图片").performClick()
            compose.waitUntil(15000) { !fixture.resumed() }
            compose.waitUntil(300000) { fixture.resumed() }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("已选择 1 张图片", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(!fixture.state().unlocked)
            fixture.unlock()
            compose.waitUntil(15000) { fixture.state().importPreview.rows.isNotEmpty() }
            compose.onNodeWithTag("accounts").performScrollToNode(hasText("查看并确认导入"))
            compose.onNodeWithText("查看并确认导入").performClick()
            compose.onNodeWithText("AuthBox Camera", substring = true).assertExists()
            compose.onNodeWithText("确认保存导入").assertIsEnabled().performClick()
            compose.waitUntil(15000) { !fixture.state().busy && fixture.state().importPreview.rows.isEmpty() }
            compose.onNodeWithTag("accounts").performScrollToNode(hasText("camera@example.invalid"))
            compose.onNode(hasText("camera@example.invalid") and hasAnyAncestor(hasTestTag("accounts")), useUnmergedTree = true).assertExists()
            assertTrue(fixture.state().pending)
        }
    }
}
