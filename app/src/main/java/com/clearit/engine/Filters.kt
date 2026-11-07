package com.clearit.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Called periodically by long-running filters with overall progress in `0..1`.
 * Implementations may throw (e.g. a coroutine `CancellationException`) to abort processing.
 */
fun interface ProgressCallback {
    fun onProgress(fraction: Float)

    companion object {
        val NONE = ProgressCallback { }
    }
}

/** Neighbourhood (local) filters. All operate on packed ARGB buffers and preserve alpha. */
object Filters {

    // ---------------------------------------------------------------- blur primitives

    /**
     * In-place separable box blur of the RGB channels with clamp-to-edge borders.
     * Repeating it [passes] times approximates a Gaussian (2 passes ≈ triangle, 3 ≈ Gaussian).
     * Uses O(1) work per pixel regardless of [radius] and only one row/column scratch buffer.
     */
    fun boxBlurInPlace(data: IntArray, width: Int, height: Int, radius: Int, passes: Int = 2) {
        if (radius <= 0) return
        val rowBuf = IntArray(width)
        val colBuf = IntArray(height)
        repeat(passes) {
            for (y in 0 until height) {
                val offset = y * width
                System.arraycopy(data, offset, rowBuf, 0, width)
                blurLine(rowBuf, width, radius) { i, v -> data[offset + i] = v }
            }
            for (x in 0 until width) {
                for (y in 0 until height) colBuf[y] = data[y * width + x]
                blurLine(colBuf, height, radius) { i, v -> data[i * width + x] = v }
            }
        }
    }

    private inline fun blurLine(src: IntArray, n: Int, radius: Int, write: (Int, Int) -> Unit) {
        val window = 2 * radius + 1
        var sr = 0
        var sg = 0
        var sb = 0
        for (k in -radius..radius) {
            val p = src[k.coerceIn(0, n - 1)]
            sr += Px.r(p); sg += Px.g(p); sb += Px.b(p)
        }
        for (i in 0 until n) {
            val a = Px.a(src[i])
            write(i, Px.pack(a, (sr + radius) / window, (sg + radius) / window, (sb + radius) / window))
            val outP = src[(i - radius).coerceIn(0, n - 1)]
            val inP = src[(i + radius + 1).coerceIn(0, n - 1)]
            sr += Px.r(inP) - Px.r(outP)
            sg += Px.g(inP) - Px.g(outP)
            sb += Px.b(inP) - Px.b(outP)
        }
    }

    /** In-place separable box blur of a single-channel plane. */
    fun boxBlurPlaneInPlace(data: FloatArray, width: Int, height: Int, radius: Int, passes: Int = 2) {
        if (radius <= 0) return
        val rowBuf = FloatArray(width)
        val colBuf = FloatArray(height)
        repeat(passes) {
            for (y in 0 until height) {
                val offset = y * width
                System.arraycopy(data, offset, rowBuf, 0, width)
                blurPlaneLine(rowBuf, width, radius) { i, v -> data[offset + i] = v }
            }
            for (x in 0 until width) {
                for (y in 0 until height) colBuf[y] = data[y * width + x]
                blurPlaneLine(colBuf, height, radius) { i, v -> data[i * width + x] = v }
            }
        }
    }

    private inline fun blurPlaneLine(src: FloatArray, n: Int, radius: Int, write: (Int, Float) -> Unit) {
        val window = (2 * radius + 1).toFloat()
        var s = 0f
        for (k in -radius..radius) s += src[k.coerceIn(0, n - 1)]
        for (i in 0 until n) {
            write(i, s / window)
            s += src[(i + radius + 1).coerceIn(0, n - 1)] - src[(i - radius).coerceIn(0, n - 1)]
        }
    }

    fun blurred(image: PixelImage, radius: Int, passes: Int = 2): PixelImage {
        val copy = image.pixels.copyOf()
        boxBlurInPlace(copy, image.width, image.height, radius, passes)
        return PixelImage(image.width, image.height, copy)
    }

    /** Radius that scales with resolution so previews and full-size exports look the same. */
    fun scaledRadius(longEdge: Int, fraction: Float, minimum: Int = 1): Int =
        max(minimum, (longEdge * fraction).roundToInt())

    // ---------------------------------------------------------------- noise reduction

    /**
     * Edge-preserving noise reduction.
     * - Luminance: each pixel is blended towards a small-radius blur, but the blend weight falls
     *   off with the local luma difference (a range kernel, like a bilateral filter) so edges stay.
     * - Colour: chroma is replaced by a larger-radius blur while the pixel's own luma is kept,
     *   which removes red/green/blue blotches without softening detail.
     */
    fun reduceNoise(image: PixelImage, luminance: Float, color: Float): PixelImage {
        val l = luminance.coerceIn(0f, 1f)
        val c = color.coerceIn(0f, 1f)
        if (l <= 0f && c <= 0f) return image
        val w = image.width
        val h = image.height
        val src = image.pixels
        val lumaRadius = max(1, (image.longEdge / 1500f).roundToInt())
        val lumaBlur = if (l > 0f) src.copyOf().also { boxBlurInPlace(it, w, h, lumaRadius, 3) } else null
        val chromaBlur = if (c > 0f) {
            src.copyOf().also { boxBlurInPlace(it, w, h, max(2, (image.longEdge / 600f).roundToInt()), 2) }
        } else null
        val sigma = 4f + 26f * l
        val inv2Sigma2 = 1f / (2f * sigma * sigma)
        val out = lumaBlur ?: chromaBlur!!
        for (i in src.indices) {
            val p = src[i]
            var r = Px.r(p).toFloat()
            var g = Px.g(p).toFloat()
            var b = Px.b(p).toFloat()
            if (lumaBlur != null) {
                val q = lumaBlur[i]
                val d = Px.luma(p) - Px.luma(q)
                val weight = l * exp(-d * d * inv2Sigma2)
                r += (Px.r(q) - r) * weight
                g += (Px.g(q) - g) * weight
                b += (Px.b(q) - b) * weight
            }
            if (chromaBlur != null) {
                val q = chromaBlur[i]
                val y = Px.luma(r, g, b)
                val qy = Px.luma(q)
                r += ((Px.r(q) - qy + y) - r) * c
                g += ((Px.g(q) - qy + y) - g) * c
                b += ((Px.b(q) - qy + y) - b) * c
            }
            out[i] = Px.pack(Px.a(p), Px.clamp8(r), Px.clamp8(g), Px.clamp8(b))
        }
        return PixelImage(w, h, out)
    }

    // ---------------------------------------------------------------- dehaze

    /**
     * Dark-channel-prior dehazing (He et al.), simplified for speed: the dark channel is the
     * per-pixel channel minimum, smoothed with a wide box blur instead of a min-filter + guided
     * filter. Positive amounts remove haze; negative amounts add a soft atmospheric veil.
     */
    fun dehaze(image: PixelImage, amount: Float): PixelImage {
        val a = amount.coerceIn(-1f, 1f)
        if (a == 0f) return image
        val src = image.pixels
        val n = src.size
        // Atmospheric light: the brightest 0.1 % of luma.
        val hist = IntArray(256)
        for (p in src) hist[Px.luma(p).toInt().coerceIn(0, 255)]++
        var remaining = max(1, n / 1000)
        var airCode = 255
        while (airCode > 0 && remaining > 0) {
            remaining -= hist[airCode]
            if (remaining > 0) airCode--
        }
        val air = max(airCode / 255f, 0.6f)
        val out = IntArray(n)
        if (a < 0f) {
            val k = -a * 0.45f
            for (i in 0 until n) {
                val p = src[i]
                out[i] = Px.pack(
                    Px.a(p),
                    Px.clamp8(Px.r(p) + (air * 255f - Px.r(p)) * k),
                    Px.clamp8(Px.g(p) + (air * 255f - Px.g(p)) * k),
                    Px.clamp8(Px.b(p) + (air * 255f - Px.b(p)) * k),
                )
            }
            return PixelImage(image.width, image.height, out)
        }
        val dark = FloatArray(n) { i ->
            val p = src[i]
            min(Px.r(p), min(Px.g(p), Px.b(p))) / 255f
        }
        boxBlurPlaneInPlace(dark, image.width, image.height, scaledRadius(image.longEdge, 0.02f, 2), 2)
        val strength = 0.95f * a
        val air255 = air * 255f
        for (i in 0 until n) {
            val p = src[i]
            val t = max(1f - strength * (dark[i] / air), 0.3f)
            out[i] = Px.pack(
                Px.a(p),
                Px.clamp8((Px.r(p) - air255) / t + air255),
                Px.clamp8((Px.g(p) - air255) / t + air255),
                Px.clamp8((Px.b(p) - air255) / t + air255),
            )
        }
        return PixelImage(image.width, image.height, out)
    }

    // ---------------------------------------------------------------- local contrast & sharpening

    /**
     * Luma-only unsharp mask: `out = in + amount * softclip(luma(in) - luma(blur(in)))`.
     * Working on luma only avoids colour fringes. [midtoneMask] restricts the effect to midtones
     * (used by Clarity), [threshold] ignores tiny differences (keeps noise from being sharpened).
     */
    fun localContrast(
        image: PixelImage,
        radius: Int,
        amount: Float,
        midtoneMask: Boolean,
        threshold: Float = 0f,
        haloLimit: Float = 48f,
    ): PixelImage {
        if (amount == 0f || radius <= 0) return image
        val src = image.pixels
        val blur = src.copyOf()
        boxBlurInPlace(blur, image.width, image.height, radius, if (radius > 2) 3 else 2)
        for (i in src.indices) {
            val p = src[i]
            val y = Px.luma(p)
            var detail = y - Px.luma(blur[i])
            if (threshold > 0f) {
                val mag = abs(detail)
                detail *= ((mag - threshold) / threshold).coerceIn(0f, 1f)
            }
            // Soft-clip large differences: strong edges get proportionally less boost (fewer halos).
            detail /= 1f + abs(detail) / haloLimit
            var weight = amount
            if (midtoneMask) {
                val t = 2f * y / 255f - 1f
                weight *= 1f - t * t
            }
            val delta = detail * weight
            blur[i] = Px.pack(
                Px.a(p),
                Px.clamp8(Px.r(p) + delta),
                Px.clamp8(Px.g(p) + delta),
                Px.clamp8(Px.b(p) + delta),
            )
        }
        return PixelImage(image.width, image.height, blur)
    }

    // ---------------------------------------------------------------- vignette

    /** Radial vignette. Negative darkens the corners, positive brightens them. */
    fun vignette(image: PixelImage, amount: Float): PixelImage {
        val v = amount.coerceIn(-1f, 1f)
        if (v == 0f) return image
        val w = image.width
        val h = image.height
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val src = image.pixels
        val out = IntArray(src.size)
        val invDiag = 1f / sqrt(2f)
        for (y in 0 until h) {
            val ny = if (cy > 0f) (y - cy) / cy else 0f
            for (x in 0 until w) {
                val nx = if (cx > 0f) (x - cx) / cx else 0f
                val d = sqrt(nx * nx + ny * ny) * invDiag // 0 at centre, 1 at the corners
                val t = ((d - 0.3f) / 0.7f).coerceIn(0f, 1f)
                val f = t * t * (3f - 2f * t)
                val i = y * w + x
                val p = src[i]
                out[i] = if (v < 0f) {
                    val k = 1f + v * 0.8f * f
                    Px.pack(Px.a(p), Px.clamp8(Px.r(p) * k), Px.clamp8(Px.g(p) * k), Px.clamp8(Px.b(p) * k))
                } else {
                    val k = v * 0.6f * f
                    Px.pack(
                        Px.a(p),
                        Px.clamp8(Px.r(p) + (255 - Px.r(p)) * k),
                        Px.clamp8(Px.g(p) + (255 - Px.g(p)) * k),
                        Px.clamp8(Px.b(p) + (255 - Px.b(p)) * k),
                    )
                }
            }
        }
        return PixelImage(w, h, out)
    }
}
