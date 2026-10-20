package com.authbox.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class KeyRotationScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun attach(fixture: UiFixture): RotationApi {
        val backup = fixture.input.getString("backup")
        val delegate = ReviewApi(backup, backup, fixture.origin)
        val api = RotationApi(delegate)
        fixture.attachRepository(api) { saved ->
            saved.remove("refreshUncertain")
            delegate.deviceId = saved.getString("identityId"); delegate.userId = saved.getJSONObject("auth").getJSONObject("user").getString("id")
            saved.getJSONObject("auth").put("session", obj("accessToken" to "ab1a.${UUID.randomUUID()}.${b64(ByteArray(32))}",
                "accessExpiresAt" to System.currentTimeMillis() + 3600000))
        }
        compose.onNodeWithText("账号与设备").performClick()
        compose.onNodeWithTag("security").performScrollToNode(hasText("轮换主密钥"))
        return api
    }
    private fun prepare(fixture: UiFixture) {
        compose.onNodeWithText("轮换主密钥").performClick()
        compose.onNodeWithText("生成轮换候选").assertIsNotEnabled()
        compose.onNodeWithText("当前保险库密码").performScrollTo().performTextInput(fixture.input.getString("password"))
        compose.onNodeWithText("轮换后的保险库密码").performScrollTo().performTextInput("UI new rotation password")
        compose.onNodeWithText("再次输入轮换后的密码").performScrollTo().performTextInput("mismatch")
        compose.onNodeWithText("生成轮换候选").assertIsNotEnabled()
        compose.onNodeWithText("再次输入轮换后的密码").performTextReplacement("UI new rotation password")
        compose.onNodeWithText("生成轮换候选").performClick()
        compose.waitUntil(20000) { !fixture.state().busy && fixture.state().rotation != null }
    }

    @Test fun savedMaterialConfirmationAndSuccessfulRotationLockVault() {
        UiFixture(compose).use { fixture ->
            val api = attach(fixture)
            prepare(fixture)
            compose.onNodeWithTag("security").performScrollToNode(hasText("确认轮换并重新授权设备"))
            compose.onNodeWithText("确认轮换并重新授权设备").assertIsNotEnabled()
            compose.onNodeWithTag("rotation-saved").performClick()
            compose.onNodeWithText("确认轮换并重新授权设备").performClick()
            compose.waitUntil(15000) { !fixture.state().busy && fixture.state().rotation == null }
            assertEquals(2L, fixture.state().revision); assertFalse(fixture.state().unlocked)
            assertTrue(fixture.state().recovery.isEmpty()); assertEquals(1, api.requests.size)
            fixture.model { it.unlock(fixture.origin, fixture.username, "UI new rotation password") }
            compose.waitUntil(15000) { !fixture.state().busy && fixture.state().unlocked }
            assertEquals(4, fixture.state().entities.size)
        }
    }

    @Test fun backgroundHidesRecoveryAndUnsubmittedCandidateCanBeCancelled() {
        UiFixture(compose).use { fixture ->
            val api = attach(fixture)
            prepare(fixture)
            assertTrue(fixture.state().recovery.single().startsWith("ABR1-"))
            fixture.shell("input keyevent 3")
            compose.waitUntil(10000) { !fixture.state().unlocked && fixture.state().recovery.isEmpty() }
            fixture.shell("am start -n com.authbox.android/.MainActivity")
            compose.waitUntil(10000) { fixture.resumed() }
            fixture.model { it.unlock(fixture.origin, fixture.username, fixture.input.getString("password")) }
            compose.waitUntil(15000) { !fixture.state().busy && fixture.state().unlocked }
            compose.onNodeWithTag("security").performScrollToNode(hasText("取消未提交轮换"))
            compose.onNodeWithTag("rotation-recovery-key").assertDoesNotExist()
            compose.onNodeWithText("取消未提交轮换").performClick()
            compose.waitUntil(10000) { !fixture.state().busy && fixture.state().rotation == null }
            assertEquals(1L, fixture.state().revision); assertTrue(api.requests.isEmpty())
        }
    }
}
