package com.clearit.io

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.clearit.engine.PixelImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Supported output formats. */
enum class ExportFormat(val mimeType: String, val extension: String, val lossy: Boolean) {
    JPEG("image/jpeg", "jpg", true),
    PNG("image/png", "png", false),
    WEBP("image/webp", "webp", true);

    @Suppress("DEPRECATION")
    fun compressFormat(): Bitmap.CompressFormat = when (this) {
        JPEG -> Bitmap.CompressFormat.JPEG
        PNG -> Bitmap.CompressFormat.PNG
        WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
    }
}

data class ImageSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
    override fun toString(): String = "$width × $height"
}

/** Decoding, encoding and gallery storage. Everything here must be called off the main thread. */
object BitmapIO {
    const val ALBUM = "Clearit"

    /**
     * Largest image (in pixels) we dare to process in memory. Processing needs ~5 ARGB buffers of
     * the output size on the Java heap, so we budget heap / 20 bytes per pixel.
     */
    fun maxProcessingPixels(context: Context): Long {
        val heap = Runtime.getRuntime().maxMemory()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val budget = heap / 20 / (if (am.isLowRamDevice) 2 else 1)
        return budget.coerceIn(4_000_000L, 64_000_000L)
    }

    /** Orientation-corrected dimensions without decoding pixels, or null if unreadable. */
    fun readSize(context: Context, uri: Uri): ImageSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val rotation = readExif(context, uri)?.rotationDegrees ?: 0
        return if (rotation == 90 || rotation == 270) {
            ImageSize(options.outHeight, options.outWidth)
        } else {
            ImageSize(options.outWidth, options.outHeight)
        }
    }

    private fun readExif(context: Context, uri: Uri): ExifInterface? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
    }.getOrNull()

    /** Scale factor (≤ 1) so the image fits both limits. */
    private fun fitScale(width: Int, height: Int, maxLongEdge: Int, maxPixels: Long): Float {
        var s = 1f
        val longEdge = max(width, height)
        if (longEdge > maxLongEdge) s = maxLongEdge.toFloat() / longEdge
        val pixels = width.toLong() * height * s * s
        if (pixels > maxPixels) s *= sqrt(maxPixels / pixels.toDouble()).toFloat()
        return s
    }

    /**
     * Decodes [uri] into a mutable-independent ARGB_8888 sRGB bitmap, EXIF orientation applied,
     * downsampled if needed so it fits within [maxLongEdge] and [maxPixels].
     */
    fun decode(context: Context, uri: Uri, maxLongEdge: Int = Int.MAX_VALUE, maxPixels: Long = Long.MAX_VALUE): Bitmap {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                decodeWithImageDecoder(context, uri, maxLongEdge, maxPixels)
            } catch (primary: java.io.IOException) {
                // Some files/providers that ImageDecoder rejects still decode with BitmapFactory.
                runCatching { decodeLegacy(context, uri, maxLongEdge, maxPixels) }
                    .getOrElse { throw primary }
            }
        } else {
            decodeLegacy(context, uri, maxLongEdge, maxPixels)
        }
        return ensureArgb(bitmap)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(context: Context, uri: Uri, maxLongEdge: Int, maxPixels: Long): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Software allocation is required to read pixels; hardware bitmaps are GPU-only.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setOnPartialImageListener { true } // show what we can of truncated files
            val s = fitScale(info.size.width, info.size.height, maxLongEdge, maxPixels)
            if (s < 1f) {
                decoder.setTargetSize(
                    max(1, (info.size.width * s).roundToInt()),
                    max(1, (info.size.height * s).roundToInt()),
                )
            }
        }
    }

    private fun decodeLegacy(context: Context, uri: Uri, maxLongEdge: Int, maxPixels: Long): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw IllegalArgumentException("Unsupported or corrupt image")
        val s = fitScale(bounds.outWidth, bounds.outHeight, maxLongEdge, maxPixels)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= bounds.outWidth * s && bounds.outHeight / (sample * 2) >= bounds.outHeight * s) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("Unable to decode image")
        val targetW = max(1, (bounds.outWidth * s).roundToInt())
        val targetH = max(1, (bounds.outHeight * s).roundToInt())
        if (bitmap.width > targetW || bitmap.height > targetH) {
            val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        val exif = readExif(context, uri) ?: return bitmap
        val rotation = exif.rotationDegrees
        val flipped = exif.isFlipped
        if (rotation == 0 && !flipped) return bitmap
        val matrix = Matrix().apply {
            if (flipped) postScale(-1f, 1f)
            postRotate(rotation.toFloat())
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    private fun ensureArgb(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw IllegalStateException("Unable to convert image to ARGB_8888")
        bitmap.recycle()
        return copy
    }

    fun toPixelImage(bitmap: Bitmap): PixelImage {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return PixelImage(bitmap.width, bitmap.height, pixels)
    }

    fun toBitmap(image: PixelImage): Bitmap =
        Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)

    /**
     * Writes [bitmap] to `Pictures/Clearit` through MediaStore and returns its content URI.
     * Basic camera EXIF (date, camera, exposure) is copied from [sourceUri] for JPEG output.
     */
    fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        format: ExportFormat,
        quality: Int,
        sourceUri: Uri?,
    ): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "clearit_$stamp.${format.extension}"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
                if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Cannot create ${dir.absolutePath}")
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, displayName).absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create gallery entry")
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(format.compressFormat(), quality.coerceIn(1, 100), stream)) {
                    throw IllegalStateException("Image encoder failed")
                }
            } ?: throw IllegalStateException("Unable to open gallery file for writing")
            if (format == ExportFormat.JPEG && sourceUri != null) copyExif(context, sourceUri, uri)
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    private val EXIF_TAGS_TO_COPY = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
    )

    /** Best effort: GPS is intentionally *not* copied, and failures never fail the export. */
    private fun copyExif(context: Context, sourceUri: Uri, targetUri: Uri) {
        runCatching {
            val source = readExif(context, sourceUri) ?: return
            context.contentResolver.openFileDescriptor(targetUri, "rw")?.use { pfd ->
                val target = ExifInterface(pfd.fileDescriptor)
                for (tag in EXIF_TAGS_TO_COPY) source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
                target.setAttribute(ExifInterface.TAG_SOFTWARE, "Clearit")
                // Pixels are already upright.
                target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                target.saveAttributes()
            }
        }
    }
}
