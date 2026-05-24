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
        private const val JPEG_QUALITY = 98
        private const val TEMPERATURE_CHANNEL_SCALE = 0.1f
        private const val TINT_RB_SCALE = 0.0025f
        private const val TINT_G_SCALE = 0.005f
    }

    fun enhance(context: Context, inputUri: Uri): Result<Uri> {
        return runCatching {
            val sourceBitmap = decodeBitmap(context, inputUri)
            val enhancedBitmap = applyPreset(sourceBitmap)
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

    private fun applyPreset(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val sourcePixels = IntArray(width * height)
        val resultPixels = IntArray(width * height)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)

        for (index in sourcePixels.indices) {
            val pixel = sourcePixels[index]
            val alpha = pixel ushr 24 and 0xFF
            var red = channel(pixel, 16) / 255f
            var green = channel(pixel, 8) / 255f
            var blue = channel(pixel, 0) / 255f

            red += EnhancementPreset.temperatureShift() * TEMPERATURE_CHANNEL_SCALE
            blue -= EnhancementPreset.temperatureShift() * TEMPERATURE_CHANNEL_SCALE
            red += EnhancementPreset.tintShift() * TINT_RB_SCALE
            green -= EnhancementPreset.tintShift() * TINT_G_SCALE
            blue += EnhancementPreset.tintShift() * TINT_RB_SCALE
            red = red.coerceIn(0f, 1f)
            green = green.coerceIn(0f, 1f)
            blue = blue.coerceIn(0f, 1f)

            val exposureFactor = EnhancementPreset.exposureFactor()
            red *= exposureFactor
            green *= exposureFactor
            blue *= exposureFactor
            red = red.coerceIn(0f, 1f)
            green = green.coerceIn(0f, 1f)
            blue = blue.coerceIn(0f, 1f)

            val lumaForToneWeights = luma(red, green, blue).coerceIn(0f, 1f)
            val shadowWeight = square(1f - lumaForToneWeights)
            val highlightWeight = square(lumaForToneWeights)
            red += EnhancementPreset.shadowsAmount() * shadowWeight
            green += EnhancementPreset.shadowsAmount() * shadowWeight
            blue += EnhancementPreset.shadowsAmount() * shadowWeight
            red += EnhancementPreset.highlightsAmount() * highlightWeight
            green += EnhancementPreset.highlightsAmount() * highlightWeight
            blue += EnhancementPreset.highlightsAmount() * highlightWeight

            val blackWeight = cubic(1f - lumaForToneWeights)
            val whiteWeight = cubic(lumaForToneWeights)
            red += EnhancementPreset.blacksAmount() * blackWeight
            green += EnhancementPreset.blacksAmount() * blackWeight
            blue += EnhancementPreset.blacksAmount() * blackWeight
            red += EnhancementPreset.whitesAmount() * whiteWeight
            green += EnhancementPreset.whitesAmount() * whiteWeight
            blue += EnhancementPreset.whitesAmount() * whiteWeight

            red = applyContrast(red)
            green = applyContrast(green)
            blue = applyContrast(blue)

            val luma = luma(red, green, blue)
            red = luma + (red - luma) * EnhancementPreset.saturationFactor()
            green = luma + (green - luma) * EnhancementPreset.saturationFactor()
            blue = luma + (blue - luma) * EnhancementPreset.saturationFactor()

            val maxChannel = maxOf(red, green, blue)
            val minChannel = minOf(red, green, blue)
            val pixelSaturation = (maxChannel - minChannel).coerceIn(0f, 1f)
            val vibranceBoost = EnhancementPreset.vibranceAmount() * (1f - pixelSaturation)
            red = luma + (red - luma) * (1f + vibranceBoost)
            green = luma + (green - luma) * (1f + vibranceBoost)
            blue = luma + (blue - luma) * (1f + vibranceBoost)

            val packed =
                (clamp(red * 255f) shl 16) or
                    (clamp(green * 255f) shl 8) or
                    clamp(blue * 255f)
            resultPixels[index] = (alpha shl 24) or packed
        }

        return Bitmap.createBitmap(resultPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun channel(pixel: Int, shift: Int): Int = pixel shr shift and 0xFF

    private fun luma(red: Float, green: Float, blue: Float): Float = 0.299f * red + 0.587f * green + 0.114f * blue

    private fun square(value: Float): Float = value * value

    private fun cubic(value: Float): Float = value * value * value

    private fun applyContrast(channel: Float): Float =
        ((channel - 0.5f) * EnhancementPreset.contrastFactor() + 0.5f).coerceIn(0f, 1f)

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
