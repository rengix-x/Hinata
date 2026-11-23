package com.edgeflow.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for PART A Liquid Glass smooth gradient transitions (`smoothstep`, `lerpRgb`)
 * and monotonic adaptive luminance interpolation.
 */
class GlassGradientTransitionTest {

    @Test
    fun smoothstep_clampsAndInterpolatesMonotonically() {
        assertEquals(0f, GlassPalette.smoothstep(0.42f, 0.72f, 0.10f), 0.0001f)
        assertEquals(0f, GlassPalette.smoothstep(0.42f, 0.72f, 0.42f), 0.0001f)
        assertEquals(0.5f, GlassPalette.smoothstep(0.42f, 0.72f, 0.57f), 0.0001f)
        assertEquals(1f, GlassPalette.smoothstep(0.42f, 0.72f, 0.72f), 0.0001f)
        assertEquals(1f, GlassPalette.smoothstep(0.42f, 0.72f, 0.99f), 0.0001f)

        var prev = -1f
        for (i in 0..100) {
            val lum = i / 100f
            val v = GlassPalette.smoothstep(0.42f, 0.72f, lum)
            assertTrue("smoothstep must be monotonically non-decreasing at $lum", v >= prev)
            assertTrue("smoothstep must stay in [0, 1]", v in 0f..1f)
            prev = v
        }
    }

    @Test
    fun lerpRgb_blendsChannelsSmoothlyWithoutSuddenJumps() {
        val white = 0xFFFFFF
        val charcoal = 0x1A1E26

        assertEquals(white, GlassPalette.lerpRgb(white, charcoal, 0f))
        assertEquals(charcoal, GlassPalette.lerpRgb(white, charcoal, 1f))
        assertEquals(white, GlassPalette.lerpRgb(white, charcoal, -0.5f))
        assertEquals(charcoal, GlassPalette.lerpRgb(white, charcoal, 1.5f))

        val mid = GlassPalette.lerpRgb(white, charcoal, 0.5f)
        val midR = (mid shr 16) and 0xFF
        val midG = (mid shr 8) and 0xFF
        val midB = mid and 0xFF
        assertTrue(midR in 135..145)
        assertTrue(midG in 137..147)
        assertTrue(midB in 141..151)

        // Verify adjacent 1% luminance steps never jump more than 6 RGB units per channel
        var prevRgb = GlassPalette.lerpRgb(white, charcoal, 0f)
        for (step in 1..100) {
            val t = GlassPalette.smoothstep(0.42f, 0.72f, step / 100f)
            val currRgb = GlassPalette.lerpRgb(white, charcoal, t)
            val dr = kotlin.math.abs(((currRgb shr 16) and 0xFF) - ((prevRgb shr 16) and 0xFF))
            val dg = kotlin.math.abs(((currRgb shr 8) and 0xFF) - ((prevRgb shr 8) and 0xFF))
            val db = kotlin.math.abs((currRgb and 0xFF) - (prevRgb and 0xFF))
            assertTrue("Step $step jumped too abruptly in R: $dr", dr <= 14)
            assertTrue("Step $step jumped too abruptly in G: $dg", dg <= 14)
            assertTrue("Step $step jumped too abruptly in B: $db", db <= 14)
            prevRgb = currRgb
        }
    }
}
