package com.clearit

object EnhancementCommandBuilder {
    internal const val VIDEO_FILTER =
        "scale='if(gte(iw,ih),max(iw,3840),-2)':'if(gte(iw,ih),-2,max(ih,2160))':flags=lanczos,eq=contrast=1.08:saturation=1.06:brightness=0.01,unsharp=7:7:1.4:7:7:0.8"

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
