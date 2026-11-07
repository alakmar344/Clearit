package com.clearit.engine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** sRGB transfer functions (IEC 61966-2-1). */
object Srgb {
    fun toLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    fun fromLinear(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f else (1.055 * c.toDouble().pow(1 / 2.4) - 0.055).toFloat()
    }

    /** 8-bit sRGB code value → linear light, precomputed. */
    val LINEAR_LUT: FloatArray = FloatArray(256) { toLinear(it / 255f) }
}

/**
 * Monotone cubic interpolation (Fritsch–Carlson) through control points. Guaranteed not to
 * overshoot, so a tone curve built from it never inverts tones or produces banding loops.
 */
class MonotoneCurve(xs: FloatArray, ys: FloatArray) {
    private val x = xs.copyOf()
    private val y = ys.copyOf()
    private val m: FloatArray

    init {
        require(x.size == y.size && x.size >= 2) { "Need at least two control points" }
        for (i in 1 until x.size) require(x[i] > x[i - 1]) { "Control x values must increase" }
        for (i in 1 until y.size) require(y[i] >= y[i - 1]) { "Control y values must not decrease" }
        val n = x.size
        val delta = FloatArray(n - 1) { (y[it + 1] - y[it]) / (x[it + 1] - x[it]) }
        m = FloatArray(n)
        m[0] = delta[0]
        m[n - 1] = delta[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (delta[i - 1] * delta[i] <= 0f) 0f else (delta[i - 1] + delta[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (delta[i] == 0f) {
                m[i] = 0f
                m[i + 1] = 0f
                continue
            }
            val a = m[i] / delta[i]
            val b = m[i + 1] / delta[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[i] = t * a * delta[i]
                m[i + 1] = t * b * delta[i]
            }
        }
    }

    fun eval(v: Float): Float {
        if (v <= x[0]) return y[0] + m[0] * (v - x[0])
        val last = x.size - 1
        if (v >= x[last]) return y[last] + m[last] * (v - x[last])
        var i = 0
        while (v > x[i + 1]) i++
        val h = x[i + 1] - x[i]
        val t = (v - x[i]) / h
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * y[i] + (t3 - 2 * t2 + t) * h * m[i] +
            (-2 * t3 + 3 * t2) * y[i + 1] + (t3 - t2) * h * m[i + 1]
    }
}

/**
 * Converts the global (per-pixel) parts of an [Adjustments] — white balance, exposure and the
 * parametric tone curve — into three 256-entry lookup tables, one per channel. Applying an edit
 * then costs three array reads per pixel regardless of how many sliders are active.
 */
object ToneMapping {
    private const val TEMPERATURE_STRENGTH = 0.30f
    private const val TINT_STRENGTH = 0.25f
    private const val SHOULDER_KNEE = 0.7f

    data class ChannelGains(val red: Float, val green: Float, val blue: Float) {
        val max: Float get() = max(red, max(green, blue))
    }

    /** Linear-light channel multipliers for white balance + exposure, luminance-normalised. */
    fun gains(adj: Adjustments): ChannelGains {
        val t = adj.temperature / 100f
        val m = adj.tint / 100f
        var r = exp(TEMPERATURE_STRENGTH * t) * exp(TINT_STRENGTH * 0.5f * m)
        var g = exp(-TINT_STRENGTH * m)
        var b = exp(-TEMPERATURE_STRENGTH * t) * exp(TINT_STRENGTH * 0.5f * m)
        // Keep overall brightness constant so WB does not double as an exposure control.
        val norm = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val ev = 2f.pow(adj.exposure)
        r = r / norm * ev
        g = g / norm * ev
        b = b / norm * ev
        return ChannelGains(r, g, b)
    }

    /**
     * Highlight shoulder: identity below [SHOULDER_KNEE], then smoothly compresses
     * `[knee, maxValue]` into `[knee, 1]` with a continuous first derivative. When nothing can
     * exceed 1.0 (maxValue ≤ 1) it is the identity, so neutral edits stay bit-exact.
     */
    fun shoulder(x: Float, maxValue: Float): Float {
        if (maxValue <= 1f || x <= SHOULDER_KNEE) return min(x, 1f).coerceAtLeast(0f)
        val k = SHOULDER_KNEE
        val p = (maxValue - k) / (1f - k)
        val t = ((x - k) / (maxValue - k)).coerceIn(0f, 1f)
        return k + (1f - k) * (1f - (1f - t).pow(p))
    }

    /** The parametric tone curve (contrast, highlights, shadows, whites, blacks) in sRGB space. */
    fun toneCurve(adj: Adjustments): MonotoneCurve {
        val c = adj.contrast / 100f
        val hi = adj.highlights / 100f
        val sh = adj.shadows / 100f
        val wh = adj.whites / 100f
        val bl = adj.blacks / 100f
        val xs = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val ys = floatArrayOf(
            0f + bl * 0.12f,
            0.25f + sh * 0.13f - c * 0.07f + bl * 0.03f,
            0.5f + (sh + hi) * 0.03f,
            0.75f + hi * 0.13f + c * 0.07f + wh * 0.03f,
            1f + wh * 0.12f,
        )
        for (i in 1 until ys.size) ys[i] = max(ys[i], ys[i - 1] + 1e-4f)
        return MonotoneCurve(xs, ys)
    }

    /** Builds the three per-channel LUTs (input 8-bit code → output 8-bit code). */
    fun buildLuts(adj: Adjustments): Array<IntArray> {
        val gains = gains(adj)
        val curve = toneCurve(adj)
        val maxValue = gains.max
        val channelGains = floatArrayOf(gains.red, gains.green, gains.blue)
        return Array(3) { channel ->
            val gain = channelGains[channel]
            IntArray(256) { code ->
                val linear = shoulder(Srgb.LINEAR_LUT[code] * gain, maxValue)
                val encoded = Srgb.fromLinear(linear)
                Px.clamp8(curve.eval(encoded).coerceIn(0f, 1f) * 255f)
            }
        }
    }
}
