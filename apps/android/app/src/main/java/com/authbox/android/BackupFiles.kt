package com.authbox.android

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream

object BackupFiles {
    fun read(context: Context, uri: Uri): String {
        require(uri.scheme == "content") { "请选择系统文件选择器中的备份文件" }
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 16 * 1024 * 1024) { "备份文件超过 16 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        try { return decodeUtf8(bytes).also { VaultCrypto.inspect(it) } }
        finally { bytes.fill(0) }
    }

    fun write(context: Context, uri: Uri, encoded: String) {
        require(uri.scheme == "content") { "请选择系统文件选择器中的保存位置" }
        VaultCrypto.inspect(encoded)
        val bytes = encoded.toByteArray(Charsets.UTF_8)
        try {
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes); it.flush() }
            require(read(context, uri) == encoded) { "导出文件复核失败，请重新保存" }
        } finally { bytes.fill(0) }
    }
}
