package com.clearit

object EnhancementCommandBuilder {
    internal const val VIDEO_FILTER =
        "scale='if(gte(iw,ih),max(iw,1920),-2)':'if(gte(iw,ih),-2,max(ih,1080))':flags=lanczos,unsharp=5:5:1.0:5:5:0.0"

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
            "medium",
            "-crf",
            "17",
            "-c:a",
            "copy",
            outputPath
        )
}
