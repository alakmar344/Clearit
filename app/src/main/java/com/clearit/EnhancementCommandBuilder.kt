package com.clearit

object EnhancementCommandBuilder {
    private const val BASE_SCALE_FILTER =
        "scale='if(gte(iw,ih),max(iw,3840),-2)':'if(gte(iw,ih),-2,max(ih,2160))':flags=lanczos"
    private const val VIDEO_TEMPERATURE_BALANCE_SCALE = 0.1f
    private const val VIDEO_TINT_BALANCE_SCALE = 0.01f

    internal val VIDEO_FILTER by lazy {
        buildString {
            append(BASE_SCALE_FILTER)
            append(",eq=contrast=")
            append(EnhancementPreset.contrastFactor())
            append(":saturation=")
            append(EnhancementPreset.saturationFactor())
            append(":brightness=")
            append(EnhancementPreset.videoBrightness())
            append(",vibrance=intensity=")
            append(EnhancementPreset.vibranceAmount())
            append(",colorbalance=rs=")
            append(EnhancementPreset.temperatureShift() * VIDEO_TEMPERATURE_BALANCE_SCALE)
            append(":bs=")
            // Cool temperature shift decreases red and increases blue by the mirrored amount.
            append(-EnhancementPreset.temperatureShift() * VIDEO_TEMPERATURE_BALANCE_SCALE)
            append(":gm=")
            append(EnhancementPreset.tintShift() * VIDEO_TINT_BALANCE_SCALE)
            // Tone curve maps the requested blacks/shadows/highlights/whites adjustments.
            append(",curves=all='0/0:0.1/0.024:0.25/0.305:0.75/0.70:1/0.62'")
        }
    }

    fun build(inputPath: String, outputPath: String): List<String> =
        listOf(
            "-y",
            "-i",
            inputPath,
            "-vf",
            VIDEO_FILTER,
            "-c:v",
            "libx264",
            "-preset",
            "slow",
            "-crf",
            "14",
            "-pix_fmt",
            "yuv420p",
            "-profile:v",
            "high",
            "-level",
            "5.1",
            "-movflags",
            "+faststart",
            "-c:a",
            "copy",
            outputPath
        )
}
