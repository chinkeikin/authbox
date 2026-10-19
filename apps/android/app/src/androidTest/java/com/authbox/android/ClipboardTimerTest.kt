package com.authbox.android

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ClipboardTimerTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun realCopyButtonClearsAfterFifteenSecondsAndLockRejectsStaleCopy() {
        UiFixture(compose).use { fixture ->
            fixture.unlock()
            fixture.context.getSharedPreferences("protection", 0).edit().putInt("clipboard", 15).commit()
            val manager = fixture.context.getSystemService(ClipboardManager::class.java)
            var previous: ClipData? = null
            fixture.activity { previous = manager.primaryClip }
            try {
                val row = fixture.state().rows.single()
                val tag = "copy-code-${row.id}"
                compose.onNodeWithTag("accounts").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).performClick()
                fixture.activity { assertTrue(manager.primaryClip!!.getItemAt(0).text.toString().matches(Regex("[0-9]{8}"))) }
                compose.waitUntil(22000) {
                    var empty = false
                    fixture.activity { empty = !manager.hasPrimaryClip() }
                    empty
                }
                fixture.model { it.lock() }
                fixture.activity { assertFalse(it.copyCode(row.code)); assertFalse(manager.hasPrimaryClip()) }
            } finally {
                fixture.activity {
                    val current = manager.primaryClip
                    val owner = fixture.context.getSharedPreferences("clipboard", 0).getString("id", null)
                    if (current == null || (owner != null && current.description.extras?.getString("com.authbox.clip.id") == owner)) {
                        if (previous != null) manager.setPrimaryClip(previous!!) else manager.clearPrimaryClip()
                    }
                }
            }
        }
    }
}
