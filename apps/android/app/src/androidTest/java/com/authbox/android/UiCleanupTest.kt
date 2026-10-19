package com.authbox.android

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import org.junit.Assert.assertTrue
import org.junit.Test

class UiCleanupTest {
    @Test fun removeInterruptedSyntheticProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("endpoint", 0)
        val origin = preferences.getString("origin", null)
        val username = preferences.getString("username", null)
        if (origin != "https://ui.example.invalid" || username?.matches(Regex("ui_mgmt_[a-f0-9]{8}")) != true) return
        val namespace = accountNamespace(origin, username)
        val box = KeystoreBox(context, namespace)
        val state = parseObject(decodeUtf8(requireNotNull(box.read("state"))))
        assertTrue(state.getJSONObject("auth").getJSONObject("user").getString("username") == username)
        assertTrue(preferences.edit().remove("origin").remove("username").commit())
        val directory = File(context.noBackupFilesDir, namespace)
        assertTrue(directory.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
        assertTrue(directory.deleteRecursively())
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (suffix in listOf("storage", "device")) if (keys.containsAlias("$namespace.$suffix")) keys.deleteEntry("$namespace.$suffix")
    }
}
