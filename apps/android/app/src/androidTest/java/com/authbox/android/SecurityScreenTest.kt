package com.authbox.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SecurityScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun passwordConfirmationAndRecoveryMaterialsClearOnBackground() {
        UiFixture(compose).use { fixture ->
            fixture.unlock()
            compose.onNodeWithText("账号与设备").performClick()
            compose.onNodeWithTag("security").performScrollToNode(hasText("修改平台登录密码"))
            compose.onNodeWithText("修改平台登录密码").performClick()
            compose.onNodeWithText("确认修改密码").assertIsNotEnabled()
            compose.onNodeWithText("当前平台密码").performTextInput("Old synthetic password")
            compose.onNodeWithText("新平台密码").performTextInput("New synthetic password")
            compose.onNodeWithText("再次输入新平台密码").performTextInput("mismatch")
            compose.onNodeWithText("确认修改密码").assertIsNotEnabled()
            compose.onNodeWithText("再次输入新平台密码").performTextReplacement("New synthetic password")
            compose.onNodeWithText("确认修改密码").assertIsEnabled()
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("生成新账号恢复码").performClick()
            compose.waitUntil(15000) { fixture.state().recovery.size == 8 }
            compose.onNodeWithTag("security").performScrollToNode(hasText("确认作废旧码并启用新码"))
            compose.onNodeWithText("确认作废旧码并启用新码").assertIsNotEnabled()
            compose.onAllNodes(isToggleable()).onLast().performScrollTo().performClick()
            compose.onNodeWithText("确认作废旧码并启用新码").assertIsEnabled()
            fixture.shell("input keyevent 3")
            compose.waitUntil(10000) { !fixture.state().unlocked && fixture.state().recovery.isEmpty() }
        }
    }

    @Test fun recoveryRequiresExplicitRevocationConfirmation() {
        UiFixture(compose).use { fixture ->
            fixture.waitText("用账号恢复码恢复")
            compose.onNodeWithText("用账号恢复码恢复").performClick()
            compose.onNodeWithText("账号恢复码 ABAC1").performScrollTo().performTextInput("ABAC1-AAAAAAAAAAAAAAAAAAAAAA")
            compose.onNodeWithText("恢复后的平台密码").performScrollTo().performTextInput("New recovery password")
            compose.onNodeWithText("再次输入恢复后的平台密码").performScrollTo().performTextInput("New recovery password")
            compose.onNodeWithText("确认恢复账号").assertIsNotEnabled()
            compose.onAllNodes(isToggleable()).onLast().performScrollTo().performClick()
            compose.onNodeWithText("确认恢复账号").assertIsEnabled()
            compose.onNodeWithText("取消").performClick()
        }
    }
}
