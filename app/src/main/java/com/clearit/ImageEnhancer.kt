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
    companion object {
        // Mild global contrast boost after sharpening to improve perceived detail without heavy clipping.
        private const val CONTRAST_FACTOR = 1.12f
        // 5-center Laplacian sharpen kernel weight: [0,-1,0; -1,5,-1; 0,-1,0].
        private const val SHARPEN_CENTER_WEIGHT = 5f
        private const val JPEG_QUALITY = 95
    }

    fun enhance(context: Context, inputUri: Uri): Result<Uri> {
        return runCatching {
            val sourceBitmap = decodeBitmap(context, inputUri)
            val enhancedBitmap = sharpenAndBoostContrast(sourceBitmap)
            saveToAlbum(context, enhancedBitmap)
        }
    }

    private fun decodeBitmap(context: Context, inputUri: Uri): Bitmap {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, inputUri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } else {
            context.contentResolver.openInputStream(inputUri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inMutable = true
                }
                BitmapFactory.decodeStream(stream, null, options)
            }
        }
        return bitmap ?: throw IllegalArgumentException("Unable to decode image from URI")
    }

    private fun sharpenAndBoostContrast(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val sourcePixels = IntArray(width * height)
        val resultPixels = IntArray(width * height)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    // Keep edges unchanged because the 3x3 kernel requires neighbors on all sides.
                    resultPixels[index] = sourcePixels[index]
                    continue
                }

                val center = sourcePixels[index]
                val left = sourcePixels[index - 1]
                val right = sourcePixels[index + 1]
                val top = sourcePixels[index - width]
                val bottom = sourcePixels[index + width]

                val enhancedRed = sharpenChannel(center, left, right, top, bottom, 16)
                val enhancedGreen = sharpenChannel(center, left, right, top, bottom, 8)
                val enhancedBlue = sharpenChannel(center, left, right, top, bottom, 0)

                val alpha = center ushr 24 and 0xFF
                resultPixels[index] = (alpha shl 24) or (enhancedRed shl 16) or (enhancedGreen shl 8) or enhancedBlue
            }
        }

        return Bitmap.createBitmap(resultPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun sharpenChannel(center: Int, left: Int, right: Int, top: Int, bottom: Int, shift: Int): Int {
        val sharpened =
            (SHARPEN_CENTER_WEIGHT * channel(center, shift)) -
                channel(left, shift) -
                channel(right, shift) -
                channel(top, shift) -
                channel(bottom, shift)
        return applyContrast(clamp(sharpened))
    }

    private fun channel(pixel: Int, shift: Int): Int = pixel shr shift and 0xFF

    private fun applyContrast(channel: Int): Int = clamp((channel - 128f) * CONTRAST_FACTOR + 128f)

    private fun clamp(value: Float): Int = value.roundToInt().coerceIn(0, 255)

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
            ?: throw IllegalStateException("Failed to insert image into MediaStore gallery")

        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                    throw IllegalStateException("Unable to compress enhanced image")
                }
            } ?: throw IllegalStateException("Unable to write enhanced image")
        } catch (error: Exception) {
            deleteUri(context, uri)
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

    @Suppress("DEPRECATION")
    private fun deleteUri(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.contentResolver.delete(uri, null)
        } else {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
