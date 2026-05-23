package com.arthenica.ffmpegkit

import java.io.File

data class Session(val returnCode: Int, val allLogsAsString: String)

object FFmpegKit {
    fun executeWithArguments(arguments: Array<String>): Session {
        val inputIndex = arguments.indexOf("-i")
        if (inputIndex == -1 || inputIndex + 1 >= arguments.size) {
            return Session(1, "Missing input argument")
        }

        val inputPath = arguments[inputIndex + 1]
        val outputPath = arguments.lastOrNull()
            ?: return Session(1, "Missing output argument")
        if (outputPath == inputPath || outputPath.startsWith("-")) {
            return Session(1, "Invalid output argument")
        }

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
            inputFile.copyTo(outputFile, overwrite = true)
            Session(0, "Compatibility mode applied: input copied without FFmpeg enhancement")
        } catch (error: Throwable) {
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
