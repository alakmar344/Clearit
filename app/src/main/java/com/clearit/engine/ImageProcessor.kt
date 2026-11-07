package com.clearit.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The full enhancement pipeline. Stages run in a fixed, photographically sensible order:
 *
 * 1. Geometry (rotate / flip) — lossless
 * 2. Noise reduction (luminance + colour) — before anything amplifies noise
 * 3. Dehaze
 * 4. White balance, exposure and tone curve (per-channel LUTs) + vibrance / saturation
 * 5. Clarity (large-radius, midtone local contrast) and Texture (medium-radius detail)
 * 6. Upscale (Catmull-Rom bicubic) — export only
 * 7. Output sharpening (small-radius, thresholded luma unsharp mask)
 * 8. Vignette
 *
 * All radii are proportional to the image size, so a 1600 px preview and a 48 MP export of the
 * same edit look the same. The input image is never mutated.
 */
object ImageProcessor {

    /** Size of the image [process] will produce for a `width × height` source. */
    fun outputSize(width: Int, height: Int, adj: Adjustments, includeUpscale: Boolean = true): Pair<Int, Int> {
        val (w, h) = Geometry.rotatedSize(width, height, adj.rotation)
        val s = if (includeUpscale) adj.upscale else 1
        return w * s to h * s
    }

    fun process(
        source: PixelImage,
        adj: Adjustments,
        includeUpscale: Boolean = true,
        progress: ProgressCallback = ProgressCallback.NONE,
    ): PixelImage {
        val stages = Stages(progress)
        var img = Geometry.apply(source, adj.rotation, adj.flipHorizontal, adj.flipVertical)
        stages.done(0.05f)

        img = Filters.reduceNoise(img, adj.noiseReduction / 100f, adj.colorNoiseReduction / 100f)
        stages.done(0.15f)

        img = Filters.dehaze(img, adj.dehaze / 100f)
        stages.done(0.22f)

        img = applyColor(img, adj)
        stages.done(0.32f)

        if (adj.clarity != 0f) {
            img = Filters.localContrast(
                img,
                radius = Filters.scaledRadius(img.longEdge, 0.012f, 2),
                amount = adj.clarity / 100f * 0.9f,
                midtoneMask = true,
                haloLimit = 40f,
            )
        }
        stages.done(0.42f)

        if (adj.texture != 0f) {
            img = Filters.localContrast(
                img,
                radius = Filters.scaledRadius(img.longEdge, 0.0035f, 1),
                amount = adj.texture / 100f * 0.8f,
                midtoneMask = false,
                haloLimit = 32f,
            )
        }
        stages.done(0.5f)

        val detailLongEdge = img.longEdge
        val scale = if (includeUpscale) adj.upscale else 1
        if (scale > 1) {
            img = Resampler.resize(img, img.width * scale, img.height * scale) { f ->
                stages.within(0.5f, 0.8f, f)
            }
        }
        stages.done(0.8f)

        // Upscaling softens edges; restore some acutance proportional to the factor.
        val sharpen = (adj.sharpening / 100f + 0.12f * (scale - 1)).coerceIn(0f, 1.4f)
        if (sharpen > 0f) {
            val radius = max(1, (detailLongEdge / 3000f * scale).roundToInt())
            img = Filters.localContrast(
                img,
                radius = radius,
                amount = sharpen * 1.6f,
                midtoneMask = false,
                threshold = 1.5f,
                haloLimit = 64f,
            )
        }
        stages.done(0.95f)

        img = Filters.vignette(img, adj.vignette / 100f)
        stages.done(1f)
        return img
    }

    /** Stage 4: LUT-based tone/white balance plus saturation and vibrance. */
    fun applyColor(image: PixelImage, adj: Adjustments): PixelImage {
        val toneNeutral = adj.exposure == 0f && adj.contrast == 0f && adj.highlights == 0f &&
            adj.shadows == 0f && adj.whites == 0f && adj.blacks == 0f &&
            adj.temperature == 0f && adj.tint == 0f
        val satFactor = 1f + adj.saturation / 100f
        val vibrance = adj.vibrance / 100f
        if (toneNeutral && satFactor == 1f && vibrance == 0f) return image

        val luts = ToneMapping.buildLuts(adj)
        val lr = luts[0]
        val lg = luts[1]
        val lb = luts[2]
        val src = image.pixels
        val out = IntArray(src.size)
        val colorNeutral = satFactor == 1f && vibrance == 0f
        for (i in src.indices) {
            val p = src[i]
            val r8 = lr[Px.r(p)]
            val g8 = lg[Px.g(p)]
            val b8 = lb[Px.b(p)]
            if (colorNeutral) {
                out[i] = Px.pack(Px.a(p), r8, g8, b8)
                continue
            }
            var r = r8.toFloat()
            var g = g8.toFloat()
            var b = b8.toFloat()
            val y = Px.luma(r, g, b)
            var factor = satFactor
            if (vibrance != 0f) {
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val sat = if (mx <= 0f) 0f else (mx - mn) / mx
                // Protect skin tones (r > g > b) from over-saturation when boosting.
                val skin = if (vibrance > 0f && r > g && g > b) 0.5f else 1f
                factor *= 1f + vibrance * (1f - sat) * skin
            }
            r = y + (r - y) * factor
            g = y + (g - y) * factor
            b = y + (b - y) * factor
            out[i] = Px.pack(Px.a(p), Px.clamp8(r), Px.clamp8(g), Px.clamp8(b))
        }
        return PixelImage(image.width, image.height, out)
    }

    private class Stages(private val callback: ProgressCallback) {
        fun done(fraction: Float) = callback.onProgress(fraction)
        fun within(from: Float, to: Float, f: Float) = callback.onProgress(from + (to - from) * f)
    }
}
