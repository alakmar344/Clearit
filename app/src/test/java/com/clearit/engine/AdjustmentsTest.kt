package com.clearit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustmentsTest {
    @Test
    fun `default adjustments are identity`() {
        assertTrue(Adjustments().isIdentity)
        assertTrue(Adjustments().isToneNeutral)
    }

    @Test
    fun `clamp keeps values in range and on the step grid`() {
        assertEquals(3f, Adjustment.EXPOSURE.clamp(10f), 0f)
        assertEquals(-100f, Adjustment.CONTRAST.clamp(-500f), 0f)
        assertEquals(0f, Adjustment.SHARPENING.clamp(-5f), 0f)
        assertEquals(0.45f, Adjustment.EXPOSURE.clamp(0.4371f), 0f)
        assertEquals(13f, Adjustment.CLARITY.clamp(12.6f), 0f)
        assertEquals(0f, Adjustment.CLARITY.clamp(Float.NaN), 0f)
        for (field in Adjustment.entries) {
            val v = field.clamp(field.min + field.step * 3.3f)
            val steps = (v - field.min) / field.step
            assertEquals("${field.key} not on grid", Math.round(steps).toFloat(), steps, 1e-3f)
        }
    }

    @Test
    fun `encode decode round trips every field`() {
        var adj = Adjustments(rotation = 270, flipHorizontal = true, flipVertical = true, upscale = 3)
        Adjustment.entries.forEachIndexed { i, field -> adj = adj.with(field, field.min + field.step * (i + 1)) }
        assertEquals(adj, Adjustments.decode(adj.encode()))
        assertEquals(Adjustments(), Adjustments.decode(""))
        assertEquals(Adjustments(), Adjustments.decode(null))
    }

    @Test
    fun `decode ignores garbage`() {
        val adj = Adjustments.decode("contrast=12;nope=4;exposure=abc;rotation=45;upscale=9;;=;shadows=200")
        assertEquals(12f, adj.contrast, 0f)
        assertEquals(0f, adj.exposure, 0f)
        assertEquals(0, adj.rotation)
        assertEquals(1, adj.upscale)
        assertEquals(100f, adj.shadows, 0f)
    }

    @Test
    fun `rotation helpers wrap around`() {
        val a = Adjustments()
        assertEquals(90, a.rotatedClockwise().rotation)
        assertEquals(270, a.rotatedCounterClockwise().rotation)
        assertEquals(0, a.rotatedClockwise().rotatedClockwise().rotatedClockwise().rotatedClockwise().rotation)
    }

    @Test
    fun `presets keep geometry and stay in range`() {
        val current = Adjustments(rotation = 90, upscale = 2, contrast = 40f)
        for (preset in Preset.entries) {
            val applied = current.withSlidersFrom(preset.adjustments)
            assertEquals(90, applied.rotation)
            assertEquals(2, applied.upscale)
            for (field in Adjustment.entries) {
                val v = applied[field]
                assertTrue("${preset.key}.${field.key}=$v", v in field.min..field.max)
            }
        }
        assertTrue(Preset.ORIGINAL.adjustments.isIdentity)
        assertFalse(Preset.CLEARIT.adjustments.isToneNeutral)
    }

    @Test
    fun `blend interpolates sliders`() {
        val target = Adjustments(contrast = 40f, exposure = 1f)
        val half = Adjustments().blend(target, 0.5f)
        assertEquals(20f, half.contrast, 0f)
        assertEquals(0.5f, half.exposure, 0f)
    }
}
