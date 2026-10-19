package com.authbox.android

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

internal fun prepareQrProvider() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val command = instrumentation.uiAutomation.executeShellCommand("content query --uri content://com.authbox.android.test.qr/standard")
    val result = ParcelFileDescriptor.AutoCloseInputStream(command).use { String(it.readBytes()) }
    assertTrue("Synthetic gallery provider must be running", result.contains("public-test-qr.png"))
}

class QrImageTest {
    @Test fun imagesUseActualContentResolverAndDecoder() {
        prepareQrProvider()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun read(name: String) = QrImages.read(context, Uri.parse("content://com.authbox.android.test.qr/$name"))
        val standard = OtpImport.parse(read("standard").single())
        assertEquals("alice@example.invalid", standard.entries.single().payload!!.getString("account"))
        val migration = ImportSession()
        migration.addAll(read("page1").map { OtpImport.parse(it) } + read("page0").map { OtpImport.parse(it) })
        assertTrue(migration.preview(emptyList()).complete)
        assertEquals(3, migration.preview(emptyList()).rows.size)
        assertThrows(Exception::class.java) { read("blank") }
        assertThrows(Exception::class.java) { read("oversized") }
        assertThrows(Exception::class.java) { QrImages.read(context, Uri.parse("https://must-not-be-requested.invalid/qr.png")) }
    }
}
