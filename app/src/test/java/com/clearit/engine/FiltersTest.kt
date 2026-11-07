package com.clearit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersTest {
    private val grey = TestImages.rgb(120, 120, 120)

    @Test
    fun `box blur leaves a flat image unchanged and keeps alpha`() {
        val flat = PixelImage.filled(37, 23, TestImages.rgb(90, 140, 200, a = 128))
        val blurred = Filters.blurred(flat, 4, 3)
        for (p in blurred.pixels) {
            assertEquals(128, Px.a(p))
            assertEquals(90, Px.r(p))
            assertEquals(140, Px.g(p))
            assertEquals(200, Px.b(p))
        }
    }

    @Test
    fun `box blur smooths a step edge`() {
        val img = PixelImage(20, 1, IntArray(20) { if (it < 10) TestImages.rgb(0, 0, 0) else TestImages.rgb(255, 255, 255) })
        val b = Filters.blurred(img, 2, 1)
        assertTrue(Px.r(b.pixels[9]) in 1..254)
        assertEquals(0, Px.r(b.pixels[0]))
        assertEquals(255, Px.r(b.pixels[19]))
    }

    @Test
    fun `noise reduction lowers noise but keeps mean`() {
        val clean = PixelImage.filled(120, 90, grey)
        val noisy = TestImages.withNoise(clean, 10f)
        val denoised = Filters.reduceNoise(noisy, 0.8f, 0.8f)
        assertTrue(TestImages.lumaStdDev(denoised) < TestImages.lumaStdDev(noisy) * 0.7f)
        assertEquals(TestImages.meanLuma(noisy), TestImages.meanLuma(denoised), 1.5f)
    }

    @Test
    fun `noise reduction preserves strong edges`() {
        val img = PixelImage(40, 40, IntArray(1600) { if (it % 40 < 20) TestImages.rgb(20, 20, 20) else TestImages.rgb(230, 230, 230) })
        val out = Filters.reduceNoise(img, 1f, 0f)
        assertEquals(20f, Px.r(out[19, 10]).toFloat(), 3f)
        assertEquals(230f, Px.r(out[20, 10]).toFloat(), 3f)
    }

    @Test
    fun `color noise reduction removes chroma blotches but keeps luma`() {
        val px = IntArray(64 * 64) { i -> if ((i / 64 + i % 64) % 2 == 0) TestImages.rgb(160, 100, 100) else TestImages.rgb(100, 130, 160) }
        val img = PixelImage(64, 64, px)
        val out = Filters.reduceNoise(img, 0f, 1f)
        val p = out[32, 32]
        val spread = maxOf(Px.r(p), Px.g(p), Px.b(p)) - minOf(Px.r(p), Px.g(p), Px.b(p))
        assertTrue("chroma spread $spread", spread < 25)
        assertEquals(Px.luma(img[32, 32]), Px.luma(p), 3f)
    }

    @Test
    fun `zero amounts return the same instance`() {
        val img = TestImages.scene()
        assertSame(img, Filters.reduceNoise(img, 0f, 0f))
        assertSame(img, Filters.dehaze(img, 0f))
        assertSame(img, Filters.vignette(img, 0f))
        assertSame(img, Filters.localContrast(img, 3, 0f, true))
    }

    @Test
    fun `sharpening increases edge contrast`() {
        val img = Filters.blurred(PixelImage(30, 30, IntArray(900) { if (it % 30 < 15) TestImages.rgb(60, 60, 60) else TestImages.rgb(190, 190, 190) }), 1, 1)
        val sharp = Filters.localContrast(img, 1, 1.5f, midtoneMask = false)
        assertTrue(Px.r(sharp[13, 15]) <= Px.r(img[13, 15]))
        assertTrue(Px.r(sharp[16, 15]) >= Px.r(img[16, 15]))
        assertTrue(Px.r(sharp[14, 15]) - Px.r(sharp[15, 15]) <= Px.r(img[14, 15]) - Px.r(img[15, 15]))
    }

    @Test
    fun `dehaze increases contrast of a hazy image`() {
        val hazy = Filters.dehaze(TestImages.scene(), -0.8f)
        val cleared = Filters.dehaze(hazy, 0.8f)
        assertTrue(TestImages.lumaStdDev(hazy) < TestImages.lumaStdDev(TestImages.scene()))
        assertTrue(TestImages.lumaStdDev(cleared) > TestImages.lumaStdDev(hazy))
    }

    @Test
    fun `vignette darkens corners not centre`() {
        val img = PixelImage.filled(101, 81, grey)
        val out = Filters.vignette(img, -1f)
        assertEquals(120, Px.r(out[50, 40]))
        assertTrue(Px.r(out[0, 0]) < 60)
        val light = Filters.vignette(img, 1f)
        assertTrue(Px.r(light[0, 0]) > 180)
    }
}
