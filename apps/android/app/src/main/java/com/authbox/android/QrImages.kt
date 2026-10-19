package com.authbox.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import java.io.ByteArrayOutputStream

object QrImages {
    fun read(context: Context, uri: Uri): List<String> {
        require(uri.scheme == "content") { "仅支持用户选择的本地图片" }
        val data = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= 8 * 1024 * 1024) { "单张二维码图片最多 8 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("无法读取所选图片")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        require(options.outWidth in 1..20000 && options.outHeight in 1..20000 && options.outWidth.toLong() * options.outHeight <= 100000000) { "图片尺寸不受支持" }
        options.inJustDecodeBounds = false; options.inSampleSize = 1
        while (options.outWidth / options.inSampleSize > 2048 || options.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size, options))
        try { return decode(bitmap) }
        finally { bitmap.recycle(); data.fill(0) }
    }

    fun decode(bitmap: Bitmap): List<String> {
        require(bitmap.width.toLong() * bitmap.height <= 4194304)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")
        try {
            for (luminance in listOf(source, source.invert())) {
                val result = runCatching { QRCodeMultiReader().decodeMultiple(BinaryBitmap(HybridBinarizer(luminance)), hints) }.getOrNull()
                if (!result.isNullOrEmpty()) return result.map { it.text }.distinct().also { require(it.size <= 100) }
            }
            throw IllegalArgumentException("图片中没有可识别的二维码")
        } finally { pixels.fill(0) }
    }
}
