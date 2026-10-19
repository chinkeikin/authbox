package com.authbox.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Rule
import org.junit.Test

class ManagementScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun createEditSearchAndRecycle() {
        UiFixture(compose).use { fixture ->
            fixture.unlock()
            compose.onNodeWithText("分组与标签").performClick()
            compose.onNodeWithText("新名称").performTextInput("QA 分组")
            compose.onNodeWithText("保存名称").performClick()
            fixture.waitText("QA 分组")
            compose.onNodeWithText("完成").performClick()
            compose.onNodeWithText("添加账号").performClick()
            compose.onNodeWithText("平台").performScrollTo().performTextInput("QA 平台")
            compose.onNodeWithText("自定义名称").performScrollTo().performTextInput("QA 新账号")
            compose.onNodeWithText("验证账号").performScrollTo().performTextInput("qa@example.invalid")
            compose.onNodeWithText("Base32 密钥").performScrollTo().performTextInput("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
            compose.onNodeWithText("所属分组：未分组").performScrollTo().performClick()
            compose.onNodeWithText("QA 分组").performClick()
            compose.onNodeWithText("备注").performScrollTo().performTextInput("可搜索备注")
            compose.onNodeWithText("保存到本机").performClick()
            fixture.waitText("qa@example.invalid")
            compose.onNodeWithText("搜索账号或分组").performTextInput("可搜索备注")
            compose.onNodeWithText("搜索账号或分组").performImeAction()
            compose.onNodeWithTag("accounts").performScrollToNode(hasText("qa@example.invalid"))
            compose.onNodeWithText("qa@example.invalid", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("accounts").performScrollToNode(hasText("编辑"))
            compose.onNodeWithText("编辑").performClick()
            compose.onNodeWithText("自定义名称").performScrollTo().performTextReplacement("QA 已编辑")
            compose.onNodeWithText("保存到本机").performClick()
            fixture.waitText("QA 分组 · QA 已编辑")
            compose.onAllNodes(isToggleable()).filterToOne(hasAnyAncestor(hasTestTag("accounts"))).performClick()
            compose.onNodeWithTag("accounts").performScrollToIndex(0)
            compose.onNodeWithText("批量管理 (1)").performClick()
            compose.onNodeWithText("移入回收站").performClick()
            compose.onNodeWithText("确认移入").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("qa@example.invalid", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("回收站").performClick()
            fixture.waitText("qa@example.invalid")
            compose.onNodeWithText("恢复账号").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("qa@example.invalid", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("回收站").performClick()
            fixture.waitText("qa@example.invalid")
            compose.onNodeWithText("修改已加密保存在本机，等待同步").assertExists()
        }
    }
}
