package com.clearit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancementCommandBuilderTest {
    @Test
    fun `build creates expected ffmpeg args order`() {
        val args = EnhancementCommandBuilder.build("/tmp/in.mp4", "/tmp/out.mp4")

        assertEquals("-y", args[0])
        assertEquals("-i", args[1])
        assertEquals("/tmp/in.mp4", args[2])
        assertEquals("-vf", args[3])
        assertEquals(EnhancementCommandBuilder.VIDEO_FILTER, args[4])
        assertEquals("/tmp/out.mp4", args.last())
    }

    @Test
    fun `build includes hd upscale and sharpening filter`() {
        val filter = EnhancementCommandBuilder.build("in", "out")[4]

        assertTrue(filter.contains("max(iw,1920)"))
        assertTrue(filter.contains("max(ih,1080)"))
        assertTrue(filter.contains("unsharp=5:5:1.0"))
    }
}
