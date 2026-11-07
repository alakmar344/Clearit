package com.clearit.engine

import kotlin.math.sqrt
import kotlin.random.Random

/** Deterministic synthetic test images. */
object TestImages {
    fun rgb(r: Int, g: Int, b: Int, a: Int = 255): Int = Px.pack(a, r, g, b)

    /** A smooth diagonal gradient with some colour variation and a few hard edges. */
    fun scene(width: Int = 160, height: Int = 120, brightness: Float = 1f, cast: Triple<Float, Float, Float> = Triple(1f, 1f, 1f)): PixelImage {
        val px = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val t = (x + y).toFloat() / (width + height)
                var r = 40f + 180f * t
                var g = 50f + 160f * (1f - (x.toFloat() / width - 0.5f).let { it * it } * 2f) * t + 20f
                var b = 60f + 140f * (y.toFloat() / height)
                if ((x / 20 + y / 20) % 2 == 0) { r *= 0.8f; g *= 0.8f; b *= 0.8f } // checker edges
                r *= brightness * cast.first
                g *= brightness * cast.second
                b *= brightness * cast.third
                px[y * width + x] = rgb(Px.clamp8(r), Px.clamp8(g), Px.clamp8(b))
            }
        }
        return PixelImage(width, height, px)
    }

    fun withNoise(image: PixelImage, sigma: Float, seed: Int = 7): PixelImage {
        val rnd = Random(seed)
        val out = IntArray(image.pixelCount) { i ->
            val p = image.pixels[i]
            fun n(): Float {
                // Box–Muller
                val u1 = rnd.nextDouble().coerceAtLeast(1e-9)
                val u2 = rnd.nextDouble()
                return (sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)).toFloat() * sigma
            }
            val d = n()
            rgb(Px.clamp8(Px.r(p) + d), Px.clamp8(Px.g(p) + d + n() * 0.3f), Px.clamp8(Px.b(p) + d + n() * 0.3f), Px.a(p))
        }
        return PixelImage(image.width, image.height, out)
    }

    fun meanLuma(image: PixelImage): Float = image.pixels.map { Px.luma(it) }.average().toFloat()

    fun meanChannel(image: PixelImage, shift: Int): Float =
        image.pixels.map { (it shr shift) and 0xFF }.average().toFloat()

    fun lumaStdDev(image: PixelImage): Float {
        val values = image.pixels.map { Px.luma(it).toDouble() }
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size).toFloat()
    }

    fun maxChannelDiff(a: PixelImage, b: PixelImage): Int {
        require(a.width == b.width && a.height == b.height)
        var m = 0
        for (i in a.pixels.indices) {
            val p = a.pixels[i]
            val q = b.pixels[i]
            m = maxOf(m, kotlin.math.abs(Px.r(p) - Px.r(q)), kotlin.math.abs(Px.g(p) - Px.g(q)), kotlin.math.abs(Px.b(p) - Px.b(q)))
        }
        return m
    }
}
