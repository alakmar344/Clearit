package com.clearit.engine

import kotlin.math.max

/**
 * A platform-independent ARGB_8888 image (one packed `0xAARRGGBB` int per pixel, row-major).
 *
 * The engine works exclusively on this type so that every algorithm can be unit-tested on the
 * JVM without an Android device. The Android layer converts to/from `Bitmap` at the edges.
 */
class PixelImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive: ${width}x$height" }
        require(pixels.size == width * height) {
            "Pixel buffer size ${pixels.size} does not match ${width}x$height"
        }
    }

    val pixelCount: Int get() = width * height
    val longEdge: Int get() = max(width, height)

    fun copy(): PixelImage = PixelImage(width, height, pixels.copyOf())

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    companion object {
        fun filled(width: Int, height: Int, argb: Int): PixelImage =
            PixelImage(width, height, IntArray(width * height) { argb })
    }
}

/** Small, inlined helpers for packed ARGB pixels. */
internal object Px {
    @Suppress("NOTHING_TO_INLINE")
    inline fun a(p: Int): Int = p ushr 24

    @Suppress("NOTHING_TO_INLINE")
    inline fun r(p: Int): Int = (p shr 16) and 0xFF

    @Suppress("NOTHING_TO_INLINE")
    inline fun g(p: Int): Int = (p shr 8) and 0xFF

    @Suppress("NOTHING_TO_INLINE")
    inline fun b(p: Int): Int = p and 0xFF

    @Suppress("NOTHING_TO_INLINE")
    inline fun pack(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Suppress("NOTHING_TO_INLINE")
    inline fun clamp8(v: Float): Int {
        val i = (v + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun clamp8(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    /** Rec.709 luma of a packed pixel, 0..255 range. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun luma(p: Int): Float = 0.2126f * r(p) + 0.7152f * g(p) + 0.0722f * b(p)

    @Suppress("NOTHING_TO_INLINE")
    inline fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b
}
