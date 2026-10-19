package com.authbox.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class ManagementScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun categoryRenameDeleteAndReferenceCleanup() {
        UiFixture(compose).use { fixture ->
            fixture.unlock()
            compose.onNodeWithText("分组与标签").performClick()
            compose.onNodeWithText("工作").performClick()
            compose.onNodeWithText("重命名").performTextReplacement("工作改名")
            compose.onNodeWithText("保存名称").performClick()
            fixture.waitText("工作改名")
            compose.onNodeWithText("类型：分组").performClick()
            compose.onNodeWithText("标签").performClick()
            compose.onNode(hasText("常用") and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithText("重命名").performTextReplacement("常用改名")
            compose.onNodeWithText("保存名称").performClick()
            fixture.waitText("常用改名")
            compose.onNodeWithText("删除").performClick()
            compose.onNodeWithText("确认删除").performClick()
            compose.waitUntil(10000) { fixture.state().entities.none { it.type == "tag" } }
            assertTrue(VaultEditor.tags(fixture.state().entities.single { it.type == "account" }.payload).isEmpty())
            compose.onNodeWithText("类型：标签").performClick()
            compose.onNodeWithText("分组").performClick()
            compose.onNodeWithText("删除").performClick()
            compose.onNodeWithText("确认删除").performClick()
            compose.waitUntil(10000) { fixture.state().entities.none { it.type == "group" } }
            assertTrue(fixture.state().entities.single { it.type == "account" }.payload.isNull("groupId"))
            compose.onNodeWithText("完成").performClick()
            compose.onNodeWithTag("accounts").performScrollToNode(hasText("未分组 · 测试"))
            compose.onNodeWithText("未分组 · 测试", useUnmergedTree = true).assertExists()
        }
    }
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
            compose.waitUntil(10000) { !fixture.state().busy && fixture.state().entities.any { it.type == "account" && it.payload.getString("account") == "qa@example.invalid" } }
            compose.onNodeWithTag("accounts").performScrollToNode(hasText("qa@example.invalid"))
            compose.onNodeWithText("搜索账号或分组").performTextInput("可搜索备注")
            compose.onNodeWithText("搜索账号或分组").performImeAction()
            compose.onNodeWithTag("accounts").performScrollToNode(hasText("qa@example.invalid"))
            compose.onNodeWithText("qa@example.invalid", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("accounts").performScrollToNode(hasText("编辑"))
            compose.onNodeWithText("编辑").performClick()
            compose.onNodeWithText("自定义名称").performScrollTo().performTextReplacement("QA 已编辑")
            compose.onNodeWithText("保存到本机").performClick()
            compose.waitUntil(10000) { !fixture.state().busy && fixture.state().entities.any { it.type == "account" && it.payload.getString("name") == "QA 已编辑" } }
            compose.onNodeWithTag("accounts").performScrollToNode(hasText("QA 分组 · QA 已编辑"))
            compose.onAllNodes(isToggleable()).filterToOne(hasAnyAncestor(hasTestTag("accounts"))).performClick()
            compose.onNodeWithTag("accounts").performScrollToIndex(0)
            compose.onNodeWithText("批量管理 (1)").performClick()
            compose.onNodeWithText("移入回收站").performClick()
            compose.onNodeWithText("确认移入").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("qa@example.invalid", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("回收站").performClick()
            compose.onNodeWithTag("accounts").performScrollToNode(hasText("qa@example.invalid"))
            compose.onNodeWithText("恢复账号").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("qa@example.invalid", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("回收站").performClick()
            compose.onNodeWithTag("accounts").performScrollToNode(hasText("qa@example.invalid"))
            compose.onNodeWithText("修改已加密保存在本机，等待同步").assertExists()
        }
    }
}
