package com.clearit

import kotlin.math.pow

object EnhancementPreset {
    const val CONTRAST = 8
    const val HIGHLIGHTS = -28
    const val SHADOWS = 18
    const val BLACKS = -12
    const val EXPOSURE = -0.2f
    const val WHITES = -10
    const val TEMPERATURE = -8
    const val TINT = 2
    const val VIBRANCE = 18
    const val SATURATION = 6
    const val CLARITY = 8
    const val TEXTURE = 5
    const val SHARPENING = 12
    const val NOISE_REDUCTION = 10

    fun contrastFactor(): Float = 1f + CONTRAST / 100f

    fun saturationFactor(): Float = 1f + SATURATION / 100f

    fun vibranceAmount(): Float = VIBRANCE / 100f

    fun exposureFactor(): Float = 2.0.pow(EXPOSURE.toDouble()).toFloat()

    fun shadowsAmount(): Float = SHADOWS / 100f

    fun highlightsAmount(): Float = HIGHLIGHTS / 100f

    fun blacksAmount(): Float = BLACKS / 100f

    fun whitesAmount(): Float = WHITES / 100f

    fun temperatureShift(): Float = TEMPERATURE / 100f

    fun tintShift(): Float = TINT / 100f

    fun clarityAmount(): Float = CLARITY / 100f

    fun textureAmount(): Float = TEXTURE / 100f

    fun sharpeningAmount(): Float = SHARPENING / 100f

    fun noiseReductionAmount(): Float = NOISE_REDUCTION / 100f

    // FFmpeg eq brightness is offset-based (-1..1), so convert multiplicative exposure to an offset.
    fun videoBrightness(): Float = exposureFactor() - 1f
}
