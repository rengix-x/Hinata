package com.edgeflow.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class ImageScrollEstimatorTest {

    private fun buildTexturedProfile(length: Int, phaseOffsetRows: Int = 0): IntArray {
        return IntArray(length) { y ->
            val shifted = y + phaseOffsetRows
            val wave1 = sin(shifted * 0.13) * 65.0
            val wave2 = sin(shifted * 0.37 + 1.1) * 35.0
            val stripe = if (((shifted / 11) and 1) == 0) 28 else -22
            (128.0 + wave1 + wave2 + stripe).toInt().coerceIn(8, 248)
        }
    }

    @Test
    fun firstFrameThenStatic_returnsFirstFrameThenStatic() {
        val estimator = ImageScrollEstimator()
        val p1 = buildTexturedProfile(200, phaseOffsetRows = 0)
        val p2 = p1.copyOf()

        assertEquals(ScreenMotionResult.FirstFrame, estimator.evaluateProfiles(p1, p1.size, rowStepPx = 4))
        assertEquals(ScreenMotionResult.Static, estimator.evaluateProfiles(p2, p2.size, rowStepPx = 4))
    }

    @Test
    fun scrollDown_contentMovesUp_producesNegativeDeltaYPx() {
        val estimator = ImageScrollEstimator()
        // Frame 1: feature at row y; Frame 2: same feature appears at y - 24 (content moved UP by 96px)
        val frame1 = buildTexturedProfile(220, phaseOffsetRows = 0)
        val frame2 = buildTexturedProfile(220, phaseOffsetRows = 24)

        assertEquals(ScreenMotionResult.FirstFrame, estimator.evaluateProfiles(frame1, frame1.size, rowStepPx = 4))
        val res = estimator.evaluateProfiles(frame2, frame2.size, rowStepPx = 4)
        assertTrue("Expected Scrolled, got $res", res is ScreenMotionResult.Scrolled)
        val scrolled = res as ScreenMotionResult.Scrolled
        assertEquals(-96, scrolled.deltaYPx)
        assertTrue("Expected high confidence, got ${scrolled.confidence}", scrolled.confidence > 0.7f)
    }

    @Test
    fun scrollUp_contentMovesDown_producesPositiveDeltaYPx() {
        val estimator = ImageScrollEstimator()
        // Frame 1 -> Frame 2: content shifted DOWN by +18 rows (+72px)
        val frame1 = buildTexturedProfile(220, phaseOffsetRows = 40)
        val frame2 = buildTexturedProfile(220, phaseOffsetRows = 22)

        assertEquals(ScreenMotionResult.FirstFrame, estimator.evaluateProfiles(frame1, frame1.size, rowStepPx = 4))
        val res = estimator.evaluateProfiles(frame2, frame2.size, rowStepPx = 4)
        assertTrue("Expected Scrolled, got $res", res is ScreenMotionResult.Scrolled)
        val scrolled = res as ScreenMotionResult.Scrolled
        assertEquals(72, scrolled.deltaYPx)
    }

    @Test
    fun completelyDifferentScene_orFlatBlankScreen_returnsSceneChanged() {
        val estimator = ImageScrollEstimator()
        val frame1 = buildTexturedProfile(200, phaseOffsetRows = 0)
        val completelyDifferent = IntArray(200) { y -> if ((y % 7) < 3) 240 else 20 }
        val flatWhite = IntArray(200) { 250 }

        estimator.evaluateProfiles(frame1, frame1.size, rowStepPx = 4)
        val sceneRes = estimator.evaluateProfiles(completelyDifferent, completelyDifferent.size, rowStepPx = 4)
        assertEquals(ScreenMotionResult.SceneChanged, sceneRes)

        val flatRes = estimator.evaluateProfiles(flatWhite, flatWhite.size, rowStepPx = 4)
        assertEquals(ScreenMotionResult.SceneChanged, flatRes)

        estimator.reset()
        assertEquals(
            ScreenMotionResult.FirstFrame,
            estimator.evaluateProfiles(frame1, frame1.size, rowStepPx = 4)
        )
        assertTrue(abs(0) == 0)
    }
}
