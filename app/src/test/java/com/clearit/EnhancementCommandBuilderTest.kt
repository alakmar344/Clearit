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
    fun `build includes requested enhancement preset in video filter`() {
        val filter = EnhancementCommandBuilder.build("in", "out")[4]

        assertTrue(filter.contains("max(iw,3840)"))
        assertTrue(filter.contains("max(ih,2160)"))
        assertTrue(filter.contains("eq=contrast=1.02:saturation=1.24:brightness=-0.46411324"))
        assertTrue(filter.contains("vibrance=intensity=0.42"))
        assertTrue(filter.contains("colorbalance=rs=-0.081"))
        assertTrue(filter.contains("curves=all='0/0:0.1/0.024:0.25/0.305:0.75/0.70:1/0.62'"))
    }
}
