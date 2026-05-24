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
import kotlin.math.abs
import kotlin.math.roundToInt

class ImageEnhancer {
    companion object {
        private const val JPEG_QUALITY = 98
        private const val TEMPERATURE_CHANNEL_SCALE = 0.1f
        private const val TINT_RB_SCALE = 0.0025f
        private const val TINT_G_SCALE = 0.005f
        private const val CLARITY_STRENGTH_SCALE = 0.6f
        private const val TEXTURE_STRENGTH_SCALE = 0.45f
        private const val SHARPENING_STRENGTH_SCALE = 0.8f
        private const val NOISE_REDUCTION_SCALE = 0.25f
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
        val temperatureShift = EnhancementPreset.temperatureShift()
        val tintShift = EnhancementPreset.tintShift()
        val exposureFactor = EnhancementPreset.exposureFactor()
        val shadowsAmount = EnhancementPreset.shadowsAmount()
        val highlightsAmount = EnhancementPreset.highlightsAmount()
        val blacksAmount = EnhancementPreset.blacksAmount()
        val whitesAmount = EnhancementPreset.whitesAmount()
        val saturationFactor = EnhancementPreset.saturationFactor()
        val vibranceAmount = EnhancementPreset.vibranceAmount()

        for (index in sourcePixels.indices) {
            val pixel = sourcePixels[index]
            val alpha = pixel ushr 24 and 0xFF
            var red = channel(pixel, 16) / 255f
            var green = channel(pixel, 8) / 255f
            var blue = channel(pixel, 0) / 255f

            red += temperatureShift * TEMPERATURE_CHANNEL_SCALE
            blue -= temperatureShift * TEMPERATURE_CHANNEL_SCALE
            red += tintShift * TINT_RB_SCALE
            green -= tintShift * TINT_G_SCALE
            blue += tintShift * TINT_RB_SCALE
            red = red.coerceIn(0f, 1f)
            green = green.coerceIn(0f, 1f)
            blue = blue.coerceIn(0f, 1f)

            red *= exposureFactor
            green *= exposureFactor
            blue *= exposureFactor
            red = red.coerceIn(0f, 1f)
            green = green.coerceIn(0f, 1f)
            blue = blue.coerceIn(0f, 1f)

            val lumaForToneWeights = luma(red, green, blue).coerceIn(0f, 1f)
            val shadowWeight = square(1f - lumaForToneWeights)
            val highlightWeight = square(lumaForToneWeights)
            red += shadowsAmount * shadowWeight
            green += shadowsAmount * shadowWeight
            blue += shadowsAmount * shadowWeight
            red += highlightsAmount * highlightWeight
            green += highlightsAmount * highlightWeight
            blue += highlightsAmount * highlightWeight

            val blackWeight = cubic(1f - lumaForToneWeights)
            val whiteWeight = cubic(lumaForToneWeights)
            red += blacksAmount * blackWeight
            green += blacksAmount * blackWeight
            blue += blacksAmount * blackWeight
            red += whitesAmount * whiteWeight
            green += whitesAmount * whiteWeight
            blue += whitesAmount * whiteWeight

            red = applyContrast(red)
            green = applyContrast(green)
            blue = applyContrast(blue)

            val luma = luma(red, green, blue)
            red = luma + (red - luma) * saturationFactor
            green = luma + (green - luma) * saturationFactor
            blue = luma + (blue - luma) * saturationFactor

            val maxChannel = maxOf(red, green, blue)
            val minChannel = minOf(red, green, blue)
            val pixelSaturation = (maxChannel - minChannel).coerceIn(0f, 1f)
            val vibranceBoost = vibranceAmount * (1f - pixelSaturation)
            red = luma + (red - luma) * (1f + vibranceBoost)
            green = luma + (green - luma) * (1f + vibranceBoost)
            blue = luma + (blue - luma) * (1f + vibranceBoost)

            val packed =
                (clamp(red * 255f) shl 16) or
                    (clamp(green * 255f) shl 8) or
                    clamp(blue * 255f)
            resultPixels[index] = (alpha shl 24) or packed
        }

        val detailedPixels = applyDetailAdjustments(resultPixels, width, height)
        return Bitmap.createBitmap(detailedPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun channel(pixel: Int, shift: Int): Int = pixel shr shift and 0xFF

    private fun luma(red: Float, green: Float, blue: Float): Float = 0.299f * red + 0.587f * green + 0.114f * blue

    private fun square(value: Float): Float = value * value

    private fun cubic(value: Float): Float = value * value * value

    private fun applyContrast(channel: Float): Float =
        ((channel - 0.5f) * EnhancementPreset.contrastFactor() + 0.5f).coerceIn(0f, 1f)

    private fun clamp(value: Float): Int = value.roundToInt().coerceIn(0, 255)

    private fun applyDetailAdjustments(pixels: IntArray, width: Int, height: Int): IntArray {
        val noiseReduction = EnhancementPreset.noiseReductionAmount().coerceIn(-1f, 1f)
        val clarity = EnhancementPreset.clarityAmount().coerceIn(-1f, 1f)
        val texture = EnhancementPreset.textureAmount().coerceIn(-1f, 1f)
        val sharpening = EnhancementPreset.sharpeningAmount().coerceIn(-1f, 1f)
        if (noiseReduction == 0f && clarity == 0f && texture == 0f && sharpening == 0f) {
            return pixels
        }

        val result = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val center = pixels[index]
                val alpha = center ushr 24 and 0xFF
                val neighbors = neighboringAverage(pixels, width, height, x, y)

                val centerRed = channel(center, 16) / 255f
                val centerGreen = channel(center, 8) / 255f
                val centerBlue = channel(center, 0) / 255f
                val blurRed = neighbors.first
                val blurGreen = neighbors.second
                val blurBlue = neighbors.third

                val denoiseWeight = noiseReduction * NOISE_REDUCTION_SCALE
                val denoisedRed = centerRed + (blurRed - centerRed) * denoiseWeight
                val denoisedGreen = centerGreen + (blurGreen - centerGreen) * denoiseWeight
                val denoisedBlue = centerBlue + (blurBlue - centerBlue) * denoiseWeight

                val luma = luma(denoisedRed, denoisedGreen, denoisedBlue).coerceIn(0f, 1f)
                val clarityMask = (1f - abs(2f * luma - 1f)).coerceIn(0f, 1f)
                val detailStrength =
                    clarity * CLARITY_STRENGTH_SCALE * clarityMask +
                        texture * TEXTURE_STRENGTH_SCALE +
                        sharpening * SHARPENING_STRENGTH_SCALE

                val outRed = (denoisedRed + (denoisedRed - blurRed) * detailStrength).coerceIn(0f, 1f)
                val outGreen = (denoisedGreen + (denoisedGreen - blurGreen) * detailStrength).coerceIn(0f, 1f)
                val outBlue = (denoisedBlue + (denoisedBlue - blurBlue) * detailStrength).coerceIn(0f, 1f)

                val packed =
                    (clamp(outRed * 255f) shl 16) or
                        (clamp(outGreen * 255f) shl 8) or
                        clamp(outBlue * 255f)
                result[index] = (alpha shl 24) or packed
            }
        }

        return result
    }

    private fun neighboringAverage(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int
    ): Triple<Float, Float, Float> {
        var red = 0f
        var green = 0f
        var blue = 0f
        var count = 0
        for (dy in -1..1) {
            val ny = y + dy
            if (ny !in 0 until height) continue
            for (dx in -1..1) {
                val nx = x + dx
                if (nx !in 0 until width) continue
                val pixel = pixels[ny * width + nx]
                red += channel(pixel, 16) / 255f
                green += channel(pixel, 8) / 255f
                blue += channel(pixel, 0) / 255f
                count++
            }
        }
        return Triple(red / count, green / count, blue / count)
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
