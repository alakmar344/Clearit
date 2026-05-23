package com.clearit

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoEnhancer {
    fun enhance(context: Context, inputUri: Uri): Result<Uri> {
        return runCatching {
            val inputFile = copyInputToCache(context, inputUri)
            val outputFile = createTempOutputFile(context)
            val args = EnhancementCommandBuilder.build(inputFile.absolutePath, outputFile.absolutePath)
            val session = FFmpegKit.executeWithArguments(args.toTypedArray())

            if (!ReturnCode.isSuccess(session.returnCode)) {
                throw IllegalStateException("Video enhancement failed: ${session.allLogsAsString}")
            }

            saveToGallery(context, outputFile)
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

    private fun createTempOutputFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(context.cacheDir, "clearit_${stamp}.mp4")
    }

    private fun saveToGallery(context: Context, outputFile: File): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "clearit_${stamp}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Clearit")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Unable to create output in gallery")

        try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                FileInputStream(outputFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IllegalStateException("Unable to write output video")
        } catch (error: Exception) {
            deleteUri(context, uri)
            throw error
        } finally {
            outputFile.delete()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val publish = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, publish, null, null)
        }

        return uri
    }

    @Suppress("DEPRECATION")
    private fun deleteUri(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.contentResolver.delete(uri, null)
        } else {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
