package com.clearit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class ImageEnhancer {
    fun enhance(context: Context, inputUri: Uri): Result<Uri> {
        return runCatching {
            val sourceBitmap = decodeBitmap(context, inputUri)
            val enhancedBitmap = sharpenAndBoostContrast(sourceBitmap)
            saveToAlbum(context, enhancedBitmap)
        }
    }

    private fun decodeBitmap(context: Context, inputUri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, inputUri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } else {
            context.contentResolver.openInputStream(inputUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: null
        } ?: throw IllegalArgumentException("Unable to decode selected image")
    }

    private fun sharpenAndBoostContrast(source: Bitmap): Bitmap {
        val src = source.copy(Bitmap.Config.ARGB_8888, false)
        val width = src.width
        val height = src.height
        val sourcePixels = IntArray(width * height)
        val resultPixels = IntArray(width * height)
        src.getPixels(sourcePixels, 0, width, 0, 0, width, height)

        fun clamp(value: Float): Int = value.roundToInt().coerceIn(0, 255)
        fun red(pixel: Int): Int = pixel shr 16 and 0xFF
        fun green(pixel: Int): Int = pixel shr 8 and 0xFF
        fun blue(pixel: Int): Int = pixel and 0xFF

        val contrast = 1.12f
        fun applyContrast(channel: Int): Int = clamp((channel - 128f) * contrast + 128f)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    resultPixels[index] = sourcePixels[index]
                    continue
                }

                val center = sourcePixels[index]
                val left = sourcePixels[index - 1]
                val right = sourcePixels[index + 1]
                val top = sourcePixels[index - width]
                val bottom = sourcePixels[index + width]

                val enhancedRed = applyContrast(clamp((5f * red(center)) - red(left) - red(right) - red(top) - red(bottom)))
                val enhancedGreen =
                    applyContrast(clamp((5f * green(center)) - green(left) - green(right) - green(top) - green(bottom)))
                val enhancedBlue =
                    applyContrast(clamp((5f * blue(center)) - blue(left) - blue(right) - blue(top) - blue(bottom)))

                val alpha = center ushr 24 and 0xFF
                resultPixels[index] = (alpha shl 24) or (enhancedRed shl 16) or (enhancedGreen shl 8) or enhancedBlue
            }
        }

        return Bitmap.createBitmap(resultPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun saveToAlbum(context: Context, bitmap: Bitmap): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "clearit_${stamp}.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Clearit")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Unable to create output image in album")

        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                    throw IllegalStateException("Unable to compress enhanced image")
                }
            } ?: throw IllegalStateException("Unable to write enhanced image")
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val publish = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, publish, null, null)
        }

        return uri
    }
}
