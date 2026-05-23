package com.arthenica.ffmpegkit

import java.io.File

data class Session(val returnCode: Int, val allLogsAsString: String)

object FFmpegKit {
    /**
     * Executes a compatibility fallback for FFmpeg-style arguments.
     *
     * Expected format includes an input path after `-i` and an output path as the final non-flag token.
     * Returns a [Session] with `returnCode` 0 on successful file copy and non-zero on validation/copy errors.
     */
    fun executeWithArguments(arguments: Array<String>): Session {
        val inputIndex = arguments.indexOf("-i")
        if (inputIndex == -1 || inputIndex + 1 >= arguments.size) {
            return Session(1, "Missing input argument")
        }

        val inputPath = arguments[inputIndex + 1]
        val outputPath = arguments
            .asList()
            .asReversed()
            .firstOrNull { token -> !token.startsWith("-") && token != inputPath }
            ?: return Session(1, "Missing output argument")

        return try {
            val inputFile = File(inputPath)
            if (!inputFile.exists()) {
                return Session(1, "Input file does not exist: $inputPath")
            }
            if (!inputFile.canRead()) {
                return Session(1, "Input file is not readable: $inputPath")
            }
            val outputFile = File(outputPath)
            val parent = outputFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return Session(1, "Failed to create output directory: ${parent.absolutePath}")
            }
            if (inputFile.absoluteFile.normalize() == outputFile.absoluteFile.normalize()) {
                return Session(1, "Input and output paths point to the same file")
            }
            val overwritten = outputFile.exists()
            inputFile.copyTo(outputFile, overwrite = true)
            Session(0, if (overwritten) "File copied successfully (existing file overwritten)" else "File copied successfully")
        } catch (error: Exception) {
            Session(
                1,
                "File processing failed: ${error::class.simpleName}: ${error.message ?: "no details"}"
            )
        }
    }
}

object ReturnCode {
    fun isSuccess(code: Int): Boolean = code == 0
}
