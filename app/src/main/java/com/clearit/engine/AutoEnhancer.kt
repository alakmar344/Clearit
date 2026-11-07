package com.clearit.engine

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** Which slider groups the auto-enhancer is allowed to change, and how strongly. */
data class AutoOptions(
    val light: Boolean = true,
    val color: Boolean = true,
    val detail: Boolean = true,
    /** 0 = no change, 1 = full suggestion. Values above 1 exaggerate (capped at 1.5). */
    val strength: Float = 1f,
)

/**
 * Derives slider values from an [ImageAnalysis]. The result is a normal [Adjustments], so the
 * user can inspect and fine-tune every suggestion manually afterwards.
 *
 * Strategy (each step is deliberately partial — auto should improve, never "flatten", a photo):
 * 1. Exposure: move median luma towards 0.46 sRGB (≈ 18 % grey) in linear light.
 * 2. Black/white points: stretch the 1st/99th percentiles towards 0.02/0.96; back off if clipping.
 * 3. Shadows/highlights: lift crowded shadows, recover crowded highlights.
 * 4. Contrast: add contrast if the 5–95 % tonal range is narrow.
 * 5. White balance: grey-world + white-patch estimate on low-chroma pixels → temperature & tint
 *    (partial correction, scaled by how many neutral pixels were found).
 * 6. Vibrance for flat colour.
 * 7. Detail: noise reduction from the measured noise sigma, sharpening from measured sharpness,
 *    dehaze when the blacks are lifted and colours are washed out (typical haze/fog).
 */
object AutoEnhancer {
    private const val TARGET_MEDIAN = 0.46f
    private const val TEMPERATURE_LOG_RANGE = 0.6f // ln(R/B) change at temperature=100
    private const val TINT_LOG_RANGE = 0.375f // ln(sqrt(RB)/G) change at tint=100

    fun suggest(analysis: ImageAnalysis, options: AutoOptions = AutoOptions()): Adjustments {
        var adj = Adjustments()
        if (options.light) adj = suggestLight(analysis, adj)
        if (options.color) adj = suggestColor(analysis, adj)
        if (options.detail) adj = suggestDetail(analysis, adj)
        val strength = options.strength.coerceIn(0f, 1.5f)
        return Adjustments.NEUTRAL.blend(adj, strength)
    }

    private fun log2(v: Float): Float = (ln(v.toDouble()) / ln(2.0)).toFloat()

    private fun suggestLight(a: ImageAnalysis, base: Adjustments): Adjustments {
        val median = max(a.median, 0.01f)
        var ev = log2(Srgb.toLinear(TARGET_MEDIAN) / Srgb.toLinear(median)) * 0.75f
        if (ev > 0f) ev *= 1f - (a.highlightFraction * 2f).coerceIn(0f, 0.7f)
        ev = ev.coerceIn(-1.5f, 1.5f)
        if (abs(ev) < 0.08f) ev = 0f
        val gain = 2f.pow(ev)
        fun predict(v: Float) = Srgb.fromLinear(Srgb.toLinear(v) * gain)
        val p01 = predict(a.p01)
        val p05 = predict(a.p05)
        val p95 = predict(a.p95)
        val p99 = predict(a.p99)

        var blacks = 0f
        if (p01 > 0.04f) blacks = -((p01 - 0.03f) / 0.2f * 50f).coerceIn(0f, 30f)
        if (a.clippedShadows > 0.03f) blacks += ((a.clippedShadows - 0.03f) * 400f).coerceIn(0f, 30f)

        var whites = 0f
        if (p99 < 0.92f) whites = ((0.96f - p99) / 0.3f * 50f).coerceIn(0f, 40f)
        if (a.clippedHighlights > 0.02f) whites -= ((a.clippedHighlights - 0.02f) * 300f).coerceIn(0f, 30f)

        var highlights = 0f
        if (a.highlightFraction > 0.08f) highlights -= (a.highlightFraction * 120f).coerceIn(0f, 60f)
        if (ev > 0f) highlights -= ev * 15f

        var shadows = 0f
        if (a.shadowFraction > 0.1f) shadows += (a.shadowFraction * 90f).coerceIn(0f, 50f)

        val range = p95 - p05
        val contrast = when {
            range < 0.65f -> ((0.7f - range) * 70f).coerceIn(0f, 30f)
            range > 0.92f -> -10f
            else -> 0f
        }

        return base.copy(
            exposure = Adjustment.EXPOSURE.clamp(ev),
            contrast = Adjustment.CONTRAST.clamp(contrast),
            highlights = Adjustment.HIGHLIGHTS.clamp(highlights),
            shadows = Adjustment.SHADOWS.clamp(shadows),
            whites = Adjustment.WHITES.clamp(whites),
            blacks = Adjustment.BLACKS.clamp(blacks),
        )
    }

    private fun suggestColor(a: ImageAnalysis, base: Adjustments): Adjustments {
        // Trust the estimate less when few neutral pixels were found.
        val confidence = (a.neutralFraction * 4f).coerceIn(0.25f, 1f)
        var temperature = (-a.warmCast / TEMPERATURE_LOG_RANGE * 100f * 0.7f * confidence).coerceIn(-40f, 40f)
        var tint = (-a.magentaCast / TINT_LOG_RANGE * 100f * 0.6f * confidence).coerceIn(-30f, 30f)
        if (abs(temperature) < 3f) temperature = 0f
        if (abs(tint) < 3f) tint = 0f
        val vibrance = if (a.meanSaturation < 0.35f) ((0.4f - a.meanSaturation) * 80f).coerceIn(0f, 30f) else 5f
        val saturation = if (a.meanSaturation > 0.55f) -5f else 0f
        return base.copy(
            temperature = Adjustment.TEMPERATURE.clamp(temperature),
            tint = Adjustment.TINT.clamp(tint),
            vibrance = Adjustment.VIBRANCE.clamp(vibrance),
            saturation = Adjustment.SATURATION.clamp(saturation),
        )
    }

    private fun suggestDetail(a: ImageAnalysis, base: Adjustments): Adjustments {
        val sigma = a.noiseSigma
        val noise = ((sigma - 1.2f) * 14f).coerceIn(0f, 60f)
        val colorNoise = if (sigma > 1.0f) (noise * 0.8f + 10f).coerceIn(0f, 60f) else 0f
        var sharpening = if (a.sharpness < 4f) 40f else 25f
        sharpening = (sharpening - noise * 0.3f).coerceIn(10f, 50f)
        val clarity = if (a.tonalRange < 0.7f) 12f else 6f
        val texture = (6f - noise * 0.1f).coerceIn(0f, 10f)
        var dehaze = 0f
        if (a.p01 > 0.12f && a.meanSaturation < 0.3f) dehaze = ((a.p01 - 0.08f) * 150f).coerceIn(0f, 35f)
        return base.copy(
            dehaze = Adjustment.DEHAZE.clamp(dehaze),
            noiseReduction = Adjustment.NOISE_REDUCTION.clamp(noise),
            colorNoiseReduction = Adjustment.COLOR_NOISE_REDUCTION.clamp(colorNoise),
            sharpening = Adjustment.SHARPENING.clamp(sharpening),
            clarity = Adjustment.CLARITY.clamp(clarity),
            texture = Adjustment.TEXTURE.clamp(texture),
        )
    }
}
