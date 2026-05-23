package com.clearit

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoEnhancer {
    fun enhance(context: Context, inputUri: Uri): Result<Uri> {
        return runCatching {
            val inputFile = copyInputToCache(context, inputUri)
            val outputFile = createOutputFile(context)
            val args = EnhancementCommandBuilder.build(inputFile.absolutePath, outputFile.absolutePath)
            val session = FFmpegKit.executeWithArguments(args.toTypedArray())

            if (!ReturnCode.isSuccess(session.returnCode)) {
                throw IllegalStateException("Video enhancement failed: ${session.allLogsAsString}")
            }

            Uri.fromFile(outputFile)
        }
    }

    private fun copyInputToCache(context: Context, inputUri: Uri): File {
        val inputFile = File(context.cacheDir, "selected_input.mp4")
        context.contentResolver.openInputStream(inputUri)?.use { input ->
            inputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Unable to read selected video")
        return inputFile
    }

    private fun createOutputFile(context: Context): File {
        val baseDirectory = context.getExternalFilesDir(null) ?: context.filesDir
        val directory = File(baseDirectory, "enhanced_videos")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(directory, "clearit_${stamp}.mp4")
    }
}
