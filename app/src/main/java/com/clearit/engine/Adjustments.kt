package com.clearit.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The complete, immutable description of an edit. Every slider in the app maps to one field.
 *
 * Ranges (enforced by [Adjustment.clamp]):
 * - [exposure]: -3..+3 EV (stops of light, applied in linear light)
 * - [noiseReduction], [colorNoiseReduction], [sharpening]: 0..100
 * - everything else: -100..+100, 0 = neutral
 */
data class Adjustments(
    // Light
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    // Color
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    // Detail
    val clarity: Float = 0f,
    val texture: Float = 0f,
    val dehaze: Float = 0f,
    val sharpening: Float = 0f,
    val noiseReduction: Float = 0f,
    val colorNoiseReduction: Float = 0f,
    // Effects
    val vignette: Float = 0f,
    // Geometry / output
    val rotation: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val upscale: Int = 1,
) {
    init {
        require(rotation in VALID_ROTATIONS) { "Rotation must be one of $VALID_ROTATIONS, was $rotation" }
        require(upscale in UPSCALE_FACTORS) { "Upscale must be one of $UPSCALE_FACTORS, was $upscale" }
    }

    operator fun get(field: Adjustment): Float = field.read(this)

    fun with(field: Adjustment, value: Float): Adjustments = field.write(this, field.clamp(value))

    /** True when no slider deviates from neutral (geometry/upscale are ignored). */
    val isToneNeutral: Boolean
        get() = Adjustment.entries.all { abs(it.read(this)) < 1e-4f }

    val hasGeometry: Boolean get() = rotation != 0 || flipHorizontal || flipVertical

    val isIdentity: Boolean get() = isToneNeutral && !hasGeometry && upscale == 1

    fun rotatedClockwise(): Adjustments = copy(rotation = (rotation + 90) % 360)

    fun rotatedCounterClockwise(): Adjustments = copy(rotation = (rotation + 270) % 360)

    /** Keeps geometry/upscale from this edit but takes all slider values from [other]. */
    fun withSlidersFrom(other: Adjustments): Adjustments =
        other.copy(rotation = rotation, flipHorizontal = flipHorizontal, flipVertical = flipVertical, upscale = upscale)

    /** Only geometry of this edit, every slider neutral. */
    fun geometryOnly(): Adjustments =
        Adjustments(rotation = rotation, flipHorizontal = flipHorizontal, flipVertical = flipVertical, upscale = upscale)

    /** Linear blend of slider values between this edit (t=0) and [target] (t=1). */
    fun blend(target: Adjustments, t: Float): Adjustments {
        var result = this
        for (field in Adjustment.entries) {
            val a = field.read(this)
            val b = field.read(target)
            result = result.with(field, a + (b - a) * t)
        }
        return result
    }

    /** Compact, stable `key=value;...` encoding used for saved state. */
    fun encode(): String = buildString {
        for (field in Adjustment.entries) {
            val v = field.read(this@Adjustments)
            if (v != 0f) append(field.key).append('=').append(v).append(';')
        }
        if (rotation != 0) append("rotation=").append(rotation).append(';')
        if (flipHorizontal) append("flipH=1;")
        if (flipVertical) append("flipV=1;")
        if (upscale != 1) append("upscale=").append(upscale).append(';')
    }

    companion object {
        val VALID_ROTATIONS = setOf(0, 90, 180, 270)
        val UPSCALE_FACTORS = listOf(1, 2, 3, 4)
        val NEUTRAL = Adjustments()

        /** Inverse of [encode]. Unknown keys and malformed values are ignored. */
        fun decode(encoded: String?): Adjustments {
            if (encoded.isNullOrBlank()) return NEUTRAL
            var result = NEUTRAL
            for (token in encoded.split(';')) {
                val eq = token.indexOf('=')
                if (eq <= 0) continue
                val key = token.substring(0, eq)
                val raw = token.substring(eq + 1)
                when (key) {
                    "rotation" -> raw.toIntOrNull()?.takeIf { it in VALID_ROTATIONS }
                        ?.let { result = result.copy(rotation = it) }
                    "flipH" -> result = result.copy(flipHorizontal = raw == "1")
                    "flipV" -> result = result.copy(flipVertical = raw == "1")
                    "upscale" -> raw.toIntOrNull()?.takeIf { it in UPSCALE_FACTORS }
                        ?.let { result = result.copy(upscale = it) }
                    else -> {
                        val field = Adjustment.fromKey(key) ?: continue
                        val value = raw.toFloatOrNull() ?: continue
                        result = result.with(field, value)
                    }
                }
            }
            return result
        }
    }
}

/** Slider groups, used by the UI to build its tabs. */
enum class AdjustmentGroup { LIGHT, COLOR, DETAIL, EFFECTS }

/**
 * Metadata for every slider: stable key, range, step and group, plus accessors on [Adjustments].
 * The UI is generated from this table, so adding an entry here adds a slider to the app.
 */
enum class Adjustment(
    val key: String,
    val group: AdjustmentGroup,
    val min: Float,
    val max: Float,
    val step: Float,
    val read: (Adjustments) -> Float,
    val write: (Adjustments, Float) -> Adjustments,
) {
    EXPOSURE("exposure", AdjustmentGroup.LIGHT, -3f, 3f, 0.05f, { it.exposure }, { a, v -> a.copy(exposure = v) }),
    CONTRAST("contrast", AdjustmentGroup.LIGHT, -100f, 100f, 1f, { it.contrast }, { a, v -> a.copy(contrast = v) }),
    HIGHLIGHTS("highlights", AdjustmentGroup.LIGHT, -100f, 100f, 1f, { it.highlights }, { a, v -> a.copy(highlights = v) }),
    SHADOWS("shadows", AdjustmentGroup.LIGHT, -100f, 100f, 1f, { it.shadows }, { a, v -> a.copy(shadows = v) }),
    WHITES("whites", AdjustmentGroup.LIGHT, -100f, 100f, 1f, { it.whites }, { a, v -> a.copy(whites = v) }),
    BLACKS("blacks", AdjustmentGroup.LIGHT, -100f, 100f, 1f, { it.blacks }, { a, v -> a.copy(blacks = v) }),
    TEMPERATURE("temperature", AdjustmentGroup.COLOR, -100f, 100f, 1f, { it.temperature }, { a, v -> a.copy(temperature = v) }),
    TINT("tint", AdjustmentGroup.COLOR, -100f, 100f, 1f, { it.tint }, { a, v -> a.copy(tint = v) }),
    VIBRANCE("vibrance", AdjustmentGroup.COLOR, -100f, 100f, 1f, { it.vibrance }, { a, v -> a.copy(vibrance = v) }),
    SATURATION("saturation", AdjustmentGroup.COLOR, -100f, 100f, 1f, { it.saturation }, { a, v -> a.copy(saturation = v) }),
    CLARITY("clarity", AdjustmentGroup.DETAIL, -100f, 100f, 1f, { it.clarity }, { a, v -> a.copy(clarity = v) }),
    TEXTURE("texture", AdjustmentGroup.DETAIL, -100f, 100f, 1f, { it.texture }, { a, v -> a.copy(texture = v) }),
    DEHAZE("dehaze", AdjustmentGroup.DETAIL, -100f, 100f, 1f, { it.dehaze }, { a, v -> a.copy(dehaze = v) }),
    SHARPENING("sharpening", AdjustmentGroup.DETAIL, 0f, 100f, 1f, { it.sharpening }, { a, v -> a.copy(sharpening = v) }),
    NOISE_REDUCTION("noise_reduction", AdjustmentGroup.DETAIL, 0f, 100f, 1f, { it.noiseReduction }, { a, v -> a.copy(noiseReduction = v) }),
    COLOR_NOISE_REDUCTION("color_noise_reduction", AdjustmentGroup.DETAIL, 0f, 100f, 1f, { it.colorNoiseReduction }, { a, v -> a.copy(colorNoiseReduction = v) }),
    VIGNETTE("vignette", AdjustmentGroup.EFFECTS, -100f, 100f, 1f, { it.vignette }, { a, v -> a.copy(vignette = v) });

    /** Clamps into range and snaps onto the step grid (required by Material sliders). */
    fun clamp(value: Float): Float {
        if (value.isNaN()) return 0f
        val bounded = value.coerceIn(min, max)
        val steps = ((bounded - min) / step).roundToInt()
        val snapped = min + steps * step
        // Remove float noise such as 0.35000002 so values round-trip cleanly.
        return ((snapped * 10000f).roundToInt() / 10000f).coerceIn(min, max)
    }

    companion object {
        fun fromKey(key: String): Adjustment? = entries.firstOrNull { it.key == key }

        fun inGroup(group: AdjustmentGroup): List<Adjustment> = entries.filter { it.group == group }
    }
}
