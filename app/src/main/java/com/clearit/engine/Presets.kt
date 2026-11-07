package com.clearit.engine

/**
 * Built-in looks. Applying a preset replaces all slider values but keeps the current
 * rotation/flip/upscale settings (see [Adjustments.withSlidersFrom]).
 */
enum class Preset(val key: String, val adjustments: Adjustments) {
    ORIGINAL("original", Adjustments()),

    /** The tuned look Clearit shipped with before v1 (kept for continuity). */
    CLEARIT(
        "clearit",
        Adjustments(
            exposure = -0.2f, contrast = 8f, highlights = -28f, shadows = 18f, whites = -10f, blacks = -12f,
            temperature = -8f, tint = 2f, vibrance = 18f, saturation = 6f,
            clarity = 8f, texture = 5f, sharpening = 12f, noiseReduction = 10f,
        )
    ),
    VIVID(
        "vivid",
        Adjustments(
            contrast = 18f, highlights = -15f, shadows = 12f, whites = 8f, blacks = -10f,
            vibrance = 35f, saturation = 12f, clarity = 12f, sharpening = 20f,
        )
    ),
    PORTRAIT(
        "portrait",
        Adjustments(
            exposure = 0.1f, contrast = -5f, highlights = -20f, shadows = 20f,
            temperature = 6f, vibrance = 10f, saturation = -4f,
            clarity = -12f, texture = -15f, sharpening = 10f, noiseReduction = 15f, colorNoiseReduction = 20f,
            vignette = -10f,
        )
    ),
    LANDSCAPE(
        "landscape",
        Adjustments(
            contrast = 15f, highlights = -40f, shadows = 30f, whites = 10f, blacks = -12f,
            vibrance = 30f, saturation = 5f, clarity = 22f, texture = 12f, dehaze = 18f, sharpening = 25f,
        )
    ),
    LOW_LIGHT(
        "low_light",
        Adjustments(
            exposure = 0.8f, contrast = 5f, highlights = -35f, shadows = 45f, blacks = 8f,
            vibrance = 10f, clarity = 5f, sharpening = 10f, noiseReduction = 45f, colorNoiseReduction = 60f,
        )
    ),
    CRISP(
        "crisp",
        Adjustments(
            contrast = 10f, clarity = 30f, texture = 25f, dehaze = 10f, sharpening = 45f, noiseReduction = 5f,
        )
    ),
    SOFT_MATTE(
        "soft_matte",
        Adjustments(
            contrast = -20f, highlights = -20f, shadows = 15f, blacks = 35f, whites = -15f,
            temperature = 8f, vibrance = -5f, saturation = -10f, clarity = -10f, vignette = -15f,
        )
    ),
    WARM(
        "warm",
        Adjustments(temperature = 25f, tint = 4f, vibrance = 12f, contrast = 5f, shadows = 8f)
    ),
    COOL(
        "cool",
        Adjustments(temperature = -25f, tint = -3f, vibrance = 8f, contrast = 6f, highlights = -8f)
    ),
    BLACK_AND_WHITE(
        "bw",
        Adjustments(
            saturation = -100f, contrast = 22f, highlights = -15f, shadows = 10f, whites = 10f, blacks = -15f,
            clarity = 18f, sharpening = 20f,
        )
    );

    companion object {
        fun fromKey(key: String): Preset? = entries.firstOrNull { it.key == key }
    }
}
