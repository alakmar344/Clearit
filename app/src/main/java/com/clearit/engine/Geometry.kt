package com.clearit.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Lossless rotations and mirror flips. */
object Geometry {
    fun apply(image: PixelImage, rotation: Int, flipHorizontal: Boolean, flipVertical: Boolean): PixelImage {
        var result = image
        if (flipHorizontal) result = flipHorizontal(result)
        if (flipVertical) result = flipVertical(result)
        result = when (rotation) {
            0 -> result
            90 -> rotate90(result)
            180 -> rotate180(result)
            270 -> rotate270(result)
            else -> throw IllegalArgumentException("Unsupported rotation $rotation")
        }
        return result
    }

    /** Output size after applying [rotation] to a `width × height` image. */
    fun rotatedSize(width: Int, height: Int, rotation: Int): Pair<Int, Int> =
        if (rotation == 90 || rotation == 270) height to width else width to height

    fun flipHorizontal(image: PixelImage): PixelImage {
        val w = image.width
        val out = IntArray(image.pixelCount)
        for (y in 0 until image.height) {
            val row = y * w
            for (x in 0 until w) out[row + x] = image.pixels[row + (w - 1 - x)]
        }
        return PixelImage(w, image.height, out)
    }

    fun flipVertical(image: PixelImage): PixelImage {
        val w = image.width
        val h = image.height
        val out = IntArray(image.pixelCount)
        for (y in 0 until h) System.arraycopy(image.pixels, (h - 1 - y) * w, out, y * w, w)
        return PixelImage(w, h, out)
    }

    /** Clockwise quarter turn. */
    fun rotate90(image: PixelImage): PixelImage {
        val w = image.width
        val h = image.height
        val out = IntArray(image.pixelCount)
        // Output is h wide, w tall. out(x', y') = in(y', h - 1 - x')
        for (y in 0 until h) {
            for (x in 0 until w) out[x * h + (h - 1 - y)] = image.pixels[y * w + x]
        }
        return PixelImage(h, w, out)
    }

    fun rotate180(image: PixelImage): PixelImage {
        val out = image.pixels.copyOf()
        out.reverse()
        return PixelImage(image.width, image.height, out)
    }

    /** Counter-clockwise quarter turn. */
    fun rotate270(image: PixelImage): PixelImage {
        val w = image.width
        val h = image.height
        val out = IntArray(image.pixelCount)
        for (y in 0 until h) {
            for (x in 0 until w) out[(w - 1 - x) * h + y] = image.pixels[y * w + x]
        }
        return PixelImage(h, w, out)
    }
}

/**
 * High-quality separable resampling.
 * - Upscaling uses Catmull-Rom bicubic interpolation (sharp, no ringing beyond 1 px).
 * - Downscaling uses an area-correct, support-widened bicubic filter (no aliasing/moire).
 */
object Resampler {
    private fun cubic(x: Float): Float {
        // Catmull-Rom (Keys, a = -0.5)
        val ax = abs(x)
        return when {
            ax < 1f -> 1.5f * ax * ax * ax - 2.5f * ax * ax + 1f
            ax < 2f -> -0.5f * ax * ax * ax + 2.5f * ax * ax - 4f * ax + 2f
            else -> 0f
        }
    }

    private class Taps(val start: IntArray, val weights: Array<FloatArray>)

    private fun computeTaps(inSize: Int, outSize: Int): Taps {
        val scale = outSize.toFloat() / inSize
        val support = if (scale < 1f) 2f / scale else 2f
        val filterScale = if (scale < 1f) scale else 1f
        val tapCount = (2 * kotlin.math.ceil(support)).toInt() + 1
        val start = IntArray(outSize)
        val weights = Array(outSize) { FloatArray(tapCount) }
        for (o in 0 until outSize) {
            val center = (o + 0.5f) / scale - 0.5f
            val first = floor(center - support).toInt() + 1
            start[o] = first
            var sum = 0f
            val w = weights[o]
            for (t in 0 until tapCount) {
                val v = cubic((first + t - center) * filterScale)
                w[t] = v
                sum += v
            }
            if (sum != 0f) for (t in 0 until tapCount) w[t] /= sum
        }
        return Taps(start, weights)
    }

    fun resize(image: PixelImage, newWidth: Int, newHeight: Int, progress: ProgressCallback = ProgressCallback.NONE): PixelImage {
        require(newWidth > 0 && newHeight > 0)
        if (newWidth == image.width && newHeight == image.height) return image
        val w = image.width
        val h = image.height
        val src = image.pixels
        // Horizontal pass: w×h → newWidth×h
        val hx = computeTaps(w, newWidth)
        val mid = IntArray(newWidth * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until newWidth) {
                val wts = hx.weights[x]
                val s = hx.start[x]
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (t in wts.indices) {
                    val wt = wts[t]
                    if (wt == 0f) continue
                    val p = src[row + (s + t).coerceIn(0, w - 1)]
                    a += Px.a(p) * wt; r += Px.r(p) * wt; g += Px.g(p) * wt; b += Px.b(p) * wt
                }
                mid[y * newWidth + x] = Px.pack(Px.clamp8(a), Px.clamp8(r), Px.clamp8(g), Px.clamp8(b))
            }
            if (y and 63 == 0) progress.onProgress(0.5f * y / h)
        }
        // Vertical pass: newWidth×h → newWidth×newHeight
        val vy = computeTaps(h, newHeight)
        val out = IntArray(newWidth * newHeight)
        for (y in 0 until newHeight) {
            val wts = vy.weights[y]
            val s = vy.start[y]
            for (x in 0 until newWidth) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (t in wts.indices) {
                    val wt = wts[t]
                    if (wt == 0f) continue
                    val p = mid[(s + t).coerceIn(0, h - 1) * newWidth + x]
                    a += Px.a(p) * wt; r += Px.r(p) * wt; g += Px.g(p) * wt; b += Px.b(p) * wt
                }
                out[y * newWidth + x] = Px.pack(Px.clamp8(a), Px.clamp8(r), Px.clamp8(g), Px.clamp8(b))
            }
            if (y and 63 == 0) progress.onProgress(0.5f + 0.5f * y / newHeight)
        }
        return PixelImage(newWidth, newHeight, out)
    }

    /** Downscales so the long edge is at most [maxLongEdge] (never upscales). */
    fun fitLongEdge(image: PixelImage, maxLongEdge: Int): PixelImage {
        if (image.longEdge <= maxLongEdge) return image
        val s = maxLongEdge.toFloat() / image.longEdge
        return resize(image, max(1, (image.width * s).roundToInt()), max(1, (image.height * s).roundToInt()))
    }
}
