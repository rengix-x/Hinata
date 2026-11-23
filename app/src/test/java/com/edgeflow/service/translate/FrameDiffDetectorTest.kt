package com.edgeflow.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDiffDetectorTest {

    @Test
    fun firstFrameAlwaysReportsChanged_andIdenticalNextFrameIsSkipped() {
        val detector = FrameDiffDetector()
        val frameA = IntArray(FrameDiffDetector.GRID_CELLS) { (it * 7) % 256 }

        val first = detector.evaluateLumaGrid(frameA)
        assertTrue("First frame must trigger OCR", first.hasScreenChanged)

        val identical = detector.evaluateLumaGrid(frameA.copyOf())
        assertFalse("Identical static frame must skip OCR (0% CPU)", identical.hasScreenChanged)
        assertEquals(0f, identical.mad, 0.001f)
        assertEquals(0f, identical.changedRatio, 0.001f)
    }

    @Test
    fun tinyNoiseBelowThresholdsIsTreatedAsStatic() {
        val detector = FrameDiffDetector()
        val base = IntArray(FrameDiffDetector.GRID_CELLS) { 120 }
        detector.evaluateLumaGrid(base)

        // Small jitter of +2 across all cells (MAD = 2.0 < 3.5, changedRatio = 0.0 < 0.025)
        val noisy = IntArray(FrameDiffDetector.GRID_CELLS) { 122 }
        val res = detector.evaluateLumaGrid(noisy)
        assertFalse("Sub-threshold noise must be treated as static", res.hasScreenChanged)
        assertTrue(res.mad < FrameDiffDetector.STATIC_MAD_THRESHOLD)
        assertEquals(0f, res.changedRatio, 0.001f)
    }

    @Test
    fun smallSubtitleBannerChangeTriggersChangedRatio() {
        val detector = FrameDiffDetector()
        val base = IntArray(FrameDiffDetector.GRID_CELLS) { 20 }
        detector.evaluateLumaGrid(base)

        // Change 36 cells (~3.5% > 2.5%) by +60 luma (like a new subtitle line appearing)
        val withSubtitle = base.copyOf()
        for (i in 0 until 36) {
            withSubtitle[i] = 80
        }
        val res = detector.evaluateLumaGrid(withSubtitle)
        assertTrue("Subtitle/dialog change must trigger re-translation", res.hasScreenChanged)
        assertTrue(res.changedRatio >= FrameDiffDetector.STATIC_CHANGED_RATIO_THRESHOLD)
    }

    @Test
    fun forceAndResetAlwaysTriggerProcessing() {
        val detector = FrameDiffDetector()
        val base = IntArray(FrameDiffDetector.GRID_CELLS) { 90 }
        detector.evaluateLumaGrid(base)
        assertFalse(detector.evaluateLumaGrid(base.copyOf()).hasScreenChanged)

        val forced = detector.evaluateLumaGrid(base.copyOf(), force = true)
        assertTrue("force=true must bypass static skip", forced.hasScreenChanged)

        detector.reset()
        val afterReset = detector.evaluateLumaGrid(base.copyOf())
        assertTrue("reset() must force next frame to be treated as changed", afterReset.hasScreenChanged)
    }
}
