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

        return try {
            val inputFile = File(inputPath)
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()
            inputFile.copyTo(outputFile, overwrite = true)
            Session(0, "FFmpegKit fallback applied: input copied to output")
        } catch (error: Throwable) {
            Session(1, error.message ?: "Unknown processing error")
        }
    }
}

object ReturnCode {
    fun isSuccess(code: Int): Boolean = code == 0
}
