package com.clearit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneMappingTest {
    @Test
    fun `neutral luts are exact identity`() {
        val luts = ToneMapping.buildLuts(Adjustments())
        for (c in 0 until 3) for (v in 0..255) assertEquals(v, luts[c][v])
    }

    @Test
    fun `srgb transfer round trips`() {
        for (v in 0..255) {
            val back = Srgb.fromLinear(Srgb.toLinear(v / 255f)) * 255f
            assertEquals(v.toFloat(), back, 0.01f)
        }
    }

    @Test
    fun `luts are monotonic for extreme settings`() {
        val extremes = listOf(
            Adjustments(contrast = 100f, highlights = -100f, shadows = 100f, whites = -100f, blacks = 100f),
            Adjustments(contrast = -100f, highlights = 100f, shadows = -100f, whites = 100f, blacks = -100f),
            Adjustments(exposure = 3f, contrast = 100f),
            Adjustments(exposure = -3f, temperature = 100f, tint = -100f),
            Adjustments(exposure = 1.5f, temperature = -100f, tint = 100f, highlights = -100f),
        )
        for (adj in extremes) {
            val luts = ToneMapping.buildLuts(adj)
            for (c in 0 until 3) for (v in 1..255) {
                assertTrue("$adj channel $c not monotonic at $v", luts[c][v] >= luts[c][v - 1])
            }
        }
    }

    @Test
    fun `exposure brightens and darkens`() {
        val up = ToneMapping.buildLuts(Adjustments(exposure = 1f))[1]
        val down = ToneMapping.buildLuts(Adjustments(exposure = -1f))[1]
        assertTrue(up[100] > 120)
        assertTrue(down[100] < 80)
        // highlight shoulder: +1 EV must not clip everything above mid-grey
        assertTrue(up[200] < 255)
        assertTrue(up[230] > up[200])
    }

    @Test
    fun `temperature warms red and cools blue`() {
        val luts = ToneMapping.buildLuts(Adjustments(temperature = 50f))
        assertTrue(luts[0][128] > 128)
        assertTrue(luts[2][128] < 128)
    }

    @Test
    fun `tint positive is magenta`() {
        val luts = ToneMapping.buildLuts(Adjustments(tint = 50f))
        assertTrue(luts[1][128] < 128)
        assertTrue(luts[0][128] > 128)
    }

    @Test
    fun `tone curve controls move the expected regions`() {
        fun lut(a: Adjustments) = ToneMapping.buildLuts(a)[1]
        assertTrue(lut(Adjustments(shadows = 60f))[64] > 64)
        assertTrue(lut(Adjustments(highlights = -60f))[192] < 192)
        assertTrue(lut(Adjustments(blacks = -60f))[10] < 10)
        assertTrue(lut(Adjustments(whites = 60f))[245] > 245)
        val c = lut(Adjustments(contrast = 60f))
        assertTrue(c[64] < 64 && c[192] > 192)
    }

    @Test
    fun `monotone curve never overshoots`() {
        val curve = MonotoneCurve(floatArrayOf(0f, 0.2f, 0.4f, 1f), floatArrayOf(0f, 0.6f, 0.6f, 1f))
        var prev = -1f
        for (i in 0..1000) {
            val v = curve.eval(i / 1000f)
            assertTrue(v >= prev - 1e-6f)
            assertTrue(v <= 1f + 1e-6f)
            prev = v
        }
    }
}
