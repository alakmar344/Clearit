package com.clearit.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** RGB + luma histograms, 256 bins each. */
class Histogram(val red: IntArray, val green: IntArray, val blue: IntArray, val luma: IntArray) {
    val total: Long = luma.fold(0L) { acc, v -> acc + v }

    /** Luma code (0..255) below which [fraction] of pixels fall. */
    fun lumaPercentile(fraction: Float): Int {
        if (total == 0L) return 0
        val target = (total * fraction.coerceIn(0f, 1f)).toLong()
        var acc = 0L
        for (i in 0 until 256) {
            acc += luma[i]
            if (acc > target) return i
        }
        return 255
    }

    fun lumaFractionBelow(code: Int): Float {
        if (total == 0L) return 0f
        var acc = 0L
        for (i in 0 until code.coerceIn(0, 256)) acc += luma[i]
        return acc.toFloat() / total
    }

    fun lumaFractionAtOrAbove(code: Int): Float = 1f - lumaFractionBelow(code)

    companion object {
        fun of(image: PixelImage, maxSamples: Int = 1_000_000): Histogram {
            val r = IntArray(256)
            val g = IntArray(256)
            val b = IntArray(256)
            val l = IntArray(256)
            val stride = max(1, image.pixelCount / maxSamples)
            var i = 0
            val px = image.pixels
            while (i < px.size) {
                val p = px[i]
                r[Px.r(p)]++
                g[Px.g(p)]++
                b[Px.b(p)]++
                l[Px.clamp8(Px.luma(p))]++
                i += stride
            }
            return Histogram(r, g, b, l)
        }
    }
}

/** Measurements the auto-enhancer bases its decisions on. All tonal values are 0..1 (sRGB). */
data class ImageAnalysis(
    val width: Int,
    val height: Int,
    val histogram: Histogram,
    /** Luma percentiles. */
    val p01: Float,
    val p05: Float,
    val median: Float,
    val p95: Float,
    val p99: Float,
    val meanLuma: Float,
    /** Fraction of pixels that are crushed (≤ 2) or blown (≥ 253). */
    val clippedShadows: Float,
    val clippedHighlights: Float,
    /** Fraction of pixels in deep shadow (< 0.15) and bright highlight (> 0.85). */
    val shadowFraction: Float,
    val highlightFraction: Float,
    /**
     * Estimated colour of a neutral surface in linear light, normalised to green = 1
     * (blend of grey-world and white-patch over low-chroma pixels).
     */
    val neutralRed: Float,
    val neutralGreen: Float,
    val neutralBlue: Float,
    /** Share of pixels that were usable neutral candidates; low values mean an unreliable WB estimate. */
    val neutralFraction: Float,
    /** Mean HSV-style saturation 0..1. */
    val meanSaturation: Float,
    /** Estimated Gaussian noise sigma, in 8-bit code values (Immerkær 1996). */
    val noiseSigma: Float,
    /** Mean absolute Laplacian of luma — higher means more fine detail/sharper. */
    val sharpness: Float,
) {
    val tonalRange: Float get() = p95 - p05

    /** Colour cast as (warmth, magenta) — positive warmth = too yellow/red, positive magenta = too pink. */
    val warmCast: Float
        get() = if (neutralBlue > 0f && neutralRed > 0f) kotlin.math.ln(neutralRed / neutralBlue) else 0f
    val magentaCast: Float
        get() = if (neutralGreen > 0f && neutralRed > 0f && neutralBlue > 0f) {
            kotlin.math.ln(sqrt(neutralRed * neutralBlue) / neutralGreen)
        } else 0f
}

object ImageAnalyzer {
    /**
     * Analyses [image]. For speed the caller should pass a preview-sized image (~1–2 MP);
     * statistics are resolution independent except noise, which is naturally lower on a
     * downscaled preview (the auto-enhancer compensates for that via [ImageAnalysis.width]).
     */
    fun analyze(image: PixelImage): ImageAnalysis {
        val hist = Histogram.of(image)
        val px = image.pixels
        val stride = max(1, image.pixelCount / 500_000)
        var sumLuma = 0.0
        var sumSat = 0.0
        var count = 0
        // Pass 1: global stats + luma histogram of neutral candidates (mid/bright, unclipped, low chroma).
        val candidateHist = IntArray(256)
        var candidates = 0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val r = Px.r(p)
            val g = Px.g(p)
            val b = Px.b(p)
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val y = Px.luma(p) / 255f
            sumLuma += y
            val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            sumSat += sat
            if (isNeutralCandidate(y, mx, sat)) {
                candidateHist[(y * 255f).toInt().coerceIn(0, 255)]++
                candidates++
            }
            count++
            i += stride
        }
        // Pass 2: grey-world over all candidates and white-patch over the brightest 10 % of them.
        // Blending both is far more robust than grey-world alone, which a blue sky or green
        // foliage easily fools.
        var brightCut = 255
        run {
            var acc = 0
            val target = candidates / 10
            while (brightCut > 0 && acc + candidateHist[brightCut] <= target) {
                acc += candidateHist[brightCut]
                brightCut--
            }
        }
        var gr = 0.0; var gg = 0.0; var gb = 0.0
        var wr = 0.0; var wg = 0.0; var wb = 0.0
        var whiteCount = 0
        i = 0
        while (candidates > 0 && i < px.size) {
            val p = px[i]
            val r = Px.r(p)
            val g = Px.g(p)
            val b = Px.b(p)
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val y = Px.luma(p) / 255f
            val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            if (isNeutralCandidate(y, mx, sat)) {
                gr += Srgb.LINEAR_LUT[r]; gg += Srgb.LINEAR_LUT[g]; gb += Srgb.LINEAR_LUT[b]
                if ((y * 255f).toInt() >= brightCut) {
                    wr += Srgb.LINEAR_LUT[r]; wg += Srgb.LINEAR_LUT[g]; wb += Srgb.LINEAR_LUT[b]
                    whiteCount++
                }
            }
            i += stride
        }
        val gn = max(1, candidates).toDouble()
        val wn = max(1, whiteCount).toDouble()
        // Normalise each estimate to unit green before blending so brightness doesn't weight it.
        fun norm(r: Double, g: Double, b: Double): DoubleArray =
            if (g <= 0.0) doubleArrayOf(1.0, 1.0, 1.0) else doubleArrayOf(r / g, 1.0, b / g)
        val grey = norm(gr / gn, gg / gn, gb / gn)
        val white = if (whiteCount > 0) norm(wr / wn, wg / wn, wb / wn) else grey
        val nr = sqrt(grey[0] * white[0])
        val ng = 1.0
        val nb = sqrt(grey[2] * white[2])
        return ImageAnalysis(
            width = image.width,
            height = image.height,
            histogram = hist,
            p01 = hist.lumaPercentile(0.01f) / 255f,
            p05 = hist.lumaPercentile(0.05f) / 255f,
            median = hist.lumaPercentile(0.5f) / 255f,
            p95 = hist.lumaPercentile(0.95f) / 255f,
            p99 = hist.lumaPercentile(0.99f) / 255f,
            meanLuma = (sumLuma / max(1, count)).toFloat(),
            clippedShadows = hist.lumaFractionBelow(3),
            clippedHighlights = hist.lumaFractionAtOrAbove(253),
            shadowFraction = hist.lumaFractionBelow((0.15f * 255).toInt()),
            highlightFraction = hist.lumaFractionAtOrAbove((0.85f * 255).toInt()),
            neutralRed = nr.toFloat(),
            neutralGreen = ng.toFloat(),
            neutralBlue = nb.toFloat(),
            neutralFraction = if (count == 0) 0f else candidates.toFloat() / count,
            meanSaturation = (sumSat / max(1, count)).toFloat(),
            noiseSigma = estimateNoise(image),
            sharpness = estimateSharpness(image),
        )
    }

    /**
     * Immerkær's fast noise variance estimation: convolve luma with the difference-of-Laplacians
     * mask [1 -2 1; -2 4 -2; 1 -2 1], which cancels image structure up to second order, and
     * scale the mean absolute response. Strong edges are excluded so texture isn't read as noise.
     */
    fun estimateNoise(image: PixelImage): Float {
        val w = image.width
        val h = image.height
        if (w < 3 || h < 3) return 0f
        val px = image.pixels
        // Average the response per block, then take a low percentile of the block values:
        // flat areas (sky, walls) reveal the true noise, textured areas (foliage) are ignored.
        val block = max(8, min(32, min(w, h) / 8))
        val blockValues = ArrayList<Double>()
        var by = 1
        while (by + block <= h - 1) {
            var bx = 1
            while (bx + block <= w - 1) {
                var sum = 0.0
                for (y in by until by + block) {
                    for (x in bx until bx + block) {
                        val c = y * w + x
                        val v = Px.luma(px[c - w - 1]) - 2 * Px.luma(px[c - w]) + Px.luma(px[c - w + 1]) -
                            2 * Px.luma(px[c - 1]) + 4 * Px.luma(px[c]) - 2 * Px.luma(px[c + 1]) +
                            Px.luma(px[c + w - 1]) - 2 * Px.luma(px[c + w]) + Px.luma(px[c + w + 1])
                        sum += abs(v)
                    }
                }
                blockValues += sum / (block * block)
                bx += block
            }
            by += block
        }
        if (blockValues.isEmpty()) return 0f
        blockValues.sort()
        val robust = blockValues[(blockValues.size * 0.2).toInt().coerceAtMost(blockValues.size - 1)]
        return (sqrt(PI / 2) * robust / 6.0).toFloat()
    }

    private fun isNeutralCandidate(y: Float, max: Int, sat: Float): Boolean =
        y in 0.15f..0.95f && max < 250 && sat < 0.3f

    /** Mean absolute 4-neighbour Laplacian of luma. */
    fun estimateSharpness(image: PixelImage): Float {
        val w = image.width
        val h = image.height
        if (w < 3 || h < 3) return 0f
        val px = image.pixels
        val rowStep = max(1, h / 600)
        var sum = 0.0
        var count = 0L
        var y = 1
        while (y < h - 1) {
            for (x in 1 until w - 1) {
                val c = y * w + x
                val v = 4 * Px.luma(px[c]) - Px.luma(px[c - 1]) - Px.luma(px[c + 1]) -
                    Px.luma(px[c - w]) - Px.luma(px[c + w])
                sum += abs(v)
                count++
            }
            y += rowStep
        }
        return if (count == 0L) 0f else (sum / count).toFloat()
    }
}
