package com.clearit.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    // 3x2 image with distinct pixels:  a b c / d e f
    private val img = PixelImage(3, 2, intArrayOf(1, 2, 3, 4, 5, 6))

    @Test
    fun `rotate 90 clockwise`() {
        val r = Geometry.rotate90(img)
        assertEquals(2, r.width)
        assertEquals(3, r.height)
        assertArrayEquals(intArrayOf(4, 1, 5, 2, 6, 3), r.pixels)
    }

    @Test
    fun `rotate 270 is inverse of 90`() {
        val r = Geometry.rotate270(img)
        assertArrayEquals(intArrayOf(3, 6, 2, 5, 1, 4), r.pixels)
        assertArrayEquals(img.pixels, Geometry.rotate270(Geometry.rotate90(img)).pixels)
    }

    @Test
    fun `rotate 180 and flips`() {
        assertArrayEquals(intArrayOf(6, 5, 4, 3, 2, 1), Geometry.rotate180(img).pixels)
        assertArrayEquals(intArrayOf(3, 2, 1, 6, 5, 4), Geometry.flipHorizontal(img).pixels)
        assertArrayEquals(intArrayOf(4, 5, 6, 1, 2, 3), Geometry.flipVertical(img).pixels)
    }

    @Test
    fun `upscale doubles dimensions and preserves flat colour`() {
        val flat = PixelImage.filled(17, 9, TestImages.rgb(10, 200, 99))
        val up = Resampler.resize(flat, 34, 18)
        assertEquals(34, up.width)
        assertEquals(18, up.height)
        assertTrue(up.pixels.all { it == TestImages.rgb(10, 200, 99) })
    }

    @Test
    fun `downscale averages instead of aliasing`() {
        val checker = PixelImage(64, 64, IntArray(64 * 64) { i -> if ((i / 64 + i % 64) % 2 == 0) TestImages.rgb(0, 0, 0) else TestImages.rgb(255, 255, 255) })
        val small = Resampler.resize(checker, 16, 16)
        for (p in small.pixels) assertEquals(127.5f, Px.r(p).toFloat(), 12f)
    }

    @Test
    fun `upscale interpolates smoothly`() {
        val ramp = PixelImage(8, 1, IntArray(8) { TestImages.rgb(it * 30, it * 30, it * 30) })
        val up = Resampler.resize(ramp, 32, 1)
        for (x in 1 until 31) assertTrue(Px.r(up.pixels[x]) >= Px.r(up.pixels[x - 1]) - 2)
    }

    @Test
    fun `fit long edge never upscales`() {
        val i = PixelImage.filled(100, 50, 0)
        assertEquals(100, Resampler.fitLongEdge(i, 400).width)
        val s = Resampler.fitLongEdge(i, 40)
        assertEquals(40, s.width)
        assertEquals(20, s.height)
    }
}
