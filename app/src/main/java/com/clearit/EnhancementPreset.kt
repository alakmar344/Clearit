package com.clearit

import kotlin.math.pow

object EnhancementPreset {
    const val CONTRAST = 2
    const val HIGHLIGHTS = -20
    const val SHADOWS = 22
    const val BLACKS = -38
    const val EXPOSURE = -0.9f
    const val WHITES = -38
    const val TEMPERATURE = -81
    const val TINT = 1
    const val VIBRANCE = 42
    const val SATURATION = 24

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

    fun videoBrightness(): Float = exposureFactor() - 1f
}
