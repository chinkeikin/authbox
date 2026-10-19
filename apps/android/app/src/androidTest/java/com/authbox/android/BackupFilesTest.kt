package com.authbox.android

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

internal fun backupUri(name: String): Uri = Uri.parse("content://com.authbox.android.test.backup/$name")
internal fun prepareBackupProvider() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("content query --uri content://com.authbox.android.test.backup/input")).use {
        assertTrue(String(it.readBytes()).contains("public-test.authbox-backup"))
    }
}

class BackupFilesTest {
    @Test fun boundedRealContentResolverRoundTrip() {
        prepareBackupProvider()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val encoded = BackupFiles.read(context, backupUri("input"))
        BackupFiles.write(context, backupUri("output"), encoded)
        assertEquals(encoded, BackupFiles.read(context, backupUri("output")))
        assertThrows(Exception::class.java) { BackupFiles.write(context, backupUri("output"), "invalid") }
        assertEquals(encoded, BackupFiles.read(context, backupUri("output")))
        assertThrows(IllegalArgumentException::class.java) { BackupFiles.read(context, backupUri("oversized")) }
        assertThrows(Exception::class.java) { BackupFiles.read(context, backupUri("bad-utf8")) }
        assertThrows(IllegalArgumentException::class.java) { BackupFiles.read(context, Uri.parse("https://must-not-be-contacted.invalid/backup")) }
        assertThrows(IllegalArgumentException::class.java) { BackupFiles.read(context, Uri.parse("file:///sdcard/backup")) }
    }
}
