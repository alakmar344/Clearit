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
    fun `build includes cinema upscale and sharpening filter`() {
        val filter = EnhancementCommandBuilder.build("in", "out")[4]

        assertTrue(filter.contains("max(iw,3840)"))
        assertTrue(filter.contains("max(ih,2160)"))
        assertTrue(filter.contains("eq=contrast=1.08:saturation=1.06:brightness=0.01"))
        assertTrue(filter.contains("unsharp=7:7:1.4"))
    }
}
