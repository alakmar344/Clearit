package com.clearit.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessorAndAutoTest {
    @Test
    fun `identity edit returns pixels unchanged`() {
        val img = TestImages.scene()
        val out = ImageProcessor.process(img, Adjustments())
        assertArrayEquals(img.pixels, out.pixels)
    }

    @Test
    fun `processing never mutates the source`() {
        val img = TestImages.scene()
        val before = img.pixels.copyOf()
        ImageProcessor.process(img, Preset.LANDSCAPE.adjustments.copy(rotation = 90, upscale = 2, vignette = -30f))
        assertArrayEquals(before, img.pixels)
    }

    @Test
    fun `output size accounts for rotation and upscale`() {
        val img = TestImages.scene(40, 30)
        val adj = Adjustments(rotation = 90, upscale = 3)
        val out = ImageProcessor.process(img, adj)
        assertEquals(90, out.width)
        assertEquals(120, out.height)
        assertEquals(90 to 120, ImageProcessor.outputSize(40, 30, adj))
        val preview = ImageProcessor.process(img, adj, includeUpscale = false)
        assertEquals(30, preview.width)
    }

    @Test
    fun `every preset produces a valid image and reports progress`() {
        val img = TestImages.scene(80, 60)
        for (preset in Preset.entries) {
            var last = -1f
            val out = ImageProcessor.process(img, preset.adjustments) { f ->
                assertTrue(f >= last)
                last = f
            }
            assertEquals(img.width, out.width)
            assertEquals(1f, last, 0f)
        }
    }

    @Test
    fun `black and white preset removes colour`() {
        val out = ImageProcessor.process(TestImages.scene(), Preset.BLACK_AND_WHITE.adjustments)
        for (p in out.pixels) {
            assertTrue(maxOf(Px.r(p), Px.g(p), Px.b(p)) - minOf(Px.r(p), Px.g(p), Px.b(p)) <= 2)
        }
    }

    @Test
    fun `progress callback can cancel processing`() {
        class Cancelled : RuntimeException()
        var thrown = false
        try {
            ImageProcessor.process(TestImages.scene(), Preset.VIVID.adjustments) { f -> if (f > 0.3f) throw Cancelled() }
        } catch (e: Cancelled) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `auto brightens an underexposed image`() {
        val dark = TestImages.scene(brightness = 0.3f)
        val adj = AutoEnhancer.suggest(ImageAnalyzer.analyze(dark))
        assertTrue("exposure ${adj.exposure}", adj.exposure > 0.3f)
        val out = ImageProcessor.process(dark, adj)
        assertTrue(TestImages.meanLuma(out) > TestImages.meanLuma(dark) * 1.4f)
    }

    @Test
    fun `auto darkens an overexposed image`() {
        val bright = ImageProcessor.process(TestImages.scene(), Adjustments(exposure = 1.6f))
        val adj = AutoEnhancer.suggest(ImageAnalyzer.analyze(bright))
        assertTrue("exposure ${adj.exposure}", adj.exposure < 0f || adj.highlights < 0f)
    }

    @Test
    fun `auto white balance corrects a blue cast`() {
        val blue = TestImages.scene(cast = Triple(0.75f, 0.95f, 1.25f))
        val analysis = ImageAnalyzer.analyze(blue)
        val adj = AutoEnhancer.suggest(analysis, AutoOptions(light = false, detail = false))
        assertTrue("temperature ${adj.temperature}", adj.temperature > 5f)
        val out = ImageProcessor.process(blue, adj)
        val before = TestImages.meanChannel(blue, 0) - TestImages.meanChannel(blue, 16)
        val after = TestImages.meanChannel(out, 0) - TestImages.meanChannel(out, 16)
        assertTrue("blue-red gap $before -> $after", after < before)
    }

    @Test
    fun `auto white balance corrects a warm cast`() {
        val warm = TestImages.scene(cast = Triple(1.25f, 1f, 0.7f))
        val adj = AutoEnhancer.suggest(ImageAnalyzer.analyze(warm), AutoOptions(light = false, detail = false))
        assertTrue("temperature ${adj.temperature}", adj.temperature < -5f)
    }

    @Test
    fun `auto reduces noise on a noisy image only`() {
        val clean = TestImages.scene(200, 150)
        val noisy = TestImages.withNoise(clean, 9f)
        val cleanNr = AutoEnhancer.suggest(ImageAnalyzer.analyze(clean)).noiseReduction
        val noisyNr = AutoEnhancer.suggest(ImageAnalyzer.analyze(noisy)).noiseReduction
        assertTrue("clean $cleanNr noisy $noisyNr", noisyNr > cleanNr + 20f)
        assertEquals(9f, ImageAnalyzer.estimateNoise(noisy), 3.5f)
    }

    @Test
    fun `auto respects option groups and strength`() {
        val analysis = ImageAnalyzer.analyze(TestImages.scene(brightness = 0.4f, cast = Triple(0.8f, 1f, 1.2f)))
        val lightOnly = AutoEnhancer.suggest(analysis, AutoOptions(color = false, detail = false))
        assertEquals(0f, lightOnly.temperature, 0f)
        assertEquals(0f, lightOnly.sharpening, 0f)
        val none = AutoEnhancer.suggest(analysis, AutoOptions(strength = 0f))
        assertTrue(none.isToneNeutral)
        val full = AutoEnhancer.suggest(analysis)
        val half = AutoEnhancer.suggest(analysis, AutoOptions(strength = 0.5f))
        assertTrue(kotlin.math.abs(half.exposure) <= kotlin.math.abs(full.exposure))
    }

    @Test
    fun `auto suggestions are always valid slider values`() {
        val images = listOf(
            PixelImage.filled(50, 50, TestImages.rgb(0, 0, 0)),
            PixelImage.filled(50, 50, TestImages.rgb(255, 255, 255)),
            PixelImage.filled(50, 50, TestImages.rgb(255, 0, 0)),
            TestImages.scene(brightness = 0.05f),
            TestImages.withNoise(TestImages.scene(), 40f),
        )
        for (img in images) {
            val adj = AutoEnhancer.suggest(ImageAnalyzer.analyze(img))
            for (field in Adjustment.entries) {
                val v = adj[field]
                assertTrue("${field.key}=$v", v in field.min..field.max && !v.isNaN())
                assertEquals(field.clamp(v), v, 0f)
            }
        }
    }
}
