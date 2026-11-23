package com.edgeflow.service.translate

import com.edgeflow.service.translate.LiveScreenTranslateController.DriftResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for Live Screen Translate Pro scroll-drift race guard and partial-band
 * crop OCR viewport math.
 */
class ScrollDriftAndBandMathTest {

    @Test
    fun classifyScrollDrift_zeroAndSubPixelDrift_returnsApplyExact() {
        val (modeZero, dyZero) = LiveScreenTranslateController.classifyScrollDrift(100f, 100f)
        assertEquals(DriftResolution.APPLY_EXACT, modeZero)
        assertEquals(0f, dyZero, 0.001f)

        val (modeTiny, dyTiny) = LiveScreenTranslateController.classifyScrollDrift(250f, 250.8f)
        assertEquals(DriftResolution.APPLY_EXACT, modeTiny)
        assertEquals(0f, dyTiny, 0.001f)
    }

    @Test
    fun classifyScrollDrift_moderateScrollInEitherDirection_returnsApplyWithExactSignedDrift() {
        val (modeUp, dyUp) = LiveScreenTranslateController.classifyScrollDrift(1000f, 920f)
        assertEquals(DriftResolution.APPLY_WITH_DRIFT, modeUp)
        assertEquals(-80f, dyUp, 0.001f)

        val (modeDown, dyDown) = LiveScreenTranslateController.classifyScrollDrift(1000f, 1140f)
        assertEquals(DriftResolution.APPLY_WITH_DRIFT, modeDown)
        assertEquals(140f, dyDown, 0.001f)
    }

    @Test
    fun classifyScrollDrift_largeScrollExceedingThreshold_returnsDropAndRescan() {
        val (modeNeg, dyNeg) = LiveScreenTranslateController.classifyScrollDrift(500f, 359f)
        assertEquals(DriftResolution.DROP_AND_RESCAN, modeNeg)
        assertEquals(-141f, dyNeg, 0.001f)

        val (modePos, dyPos) = LiveScreenTranslateController.classifyScrollDrift(500f, 760f)
        assertEquals(DriftResolution.DROP_AND_RESCAN, modePos)
        assertEquals(260f, dyPos, 0.001f)
    }

    @Test
    fun computeExposedCropBandBmp_downwardScroll_cropsBottomNewlyExposedBandWithMargin() {
        // Content moved up by 300px (deltaYPx = -300): bottom band [1800 - 300 - 140, 1800] = [1360, 1800]
        val band = LiveScreenTranslateController.computeExposedCropBandBmp(
            deltaYPx = -300,
            confidence = 0.85f,
            bmpClipTop = 120,
            bmpClipBottom = 1800,
            bmpHeight = 1920,
            marginPx = 140
        )
        assertNotNull(band)
        assertEquals(1360, band!!.first)
        assertEquals(1800, band.second)
        assertTrue(band.second - band.first < (1920 * 0.72f).toInt())
    }

    @Test
    fun computeExposedCropBandBmp_upwardScroll_cropsTopNewlyExposedBandWithMargin() {
        // Content moved down by 240px (deltaYPx = +240): top band [120, 120 + 240 + 140] = [120, 500]
        val band = LiveScreenTranslateController.computeExposedCropBandBmp(
            deltaYPx = 240,
            confidence = 0.90f,
            bmpClipTop = 120,
            bmpClipBottom = 1800,
            bmpHeight = 1920,
            marginPx = 140
        )
        assertNotNull(band)
        assertEquals(120, band!!.first)
        assertEquals(500, band.second)
    }

    @Test
    fun computeExposedCropBandBmp_lowConfidenceOrTooSmallOrHugeScroll_returnsNullForFullOcr() {
        // Low motion confidence -> fall back to full OCR
        assertNull(
            LiveScreenTranslateController.computeExposedCropBandBmp(
                deltaYPx = -250,
                confidence = 0.40f,
                bmpClipTop = 120,
                bmpClipBottom = 1800,
                bmpHeight = 1920
            )
        )

        // Too small delta (< 24px) -> full OCR
        assertNull(
            LiveScreenTranslateController.computeExposedCropBandBmp(
                deltaYPx = -12,
                confidence = 0.95f,
                bmpClipTop = 120,
                bmpClipBottom = 1800,
                bmpHeight = 1920
            )
        )

        // Huge scroll (> 68% of viewport) -> full OCR
        assertNull(
            LiveScreenTranslateController.computeExposedCropBandBmp(
                deltaYPx = -1300,
                confidence = 0.95f,
                bmpClipTop = 120,
                bmpClipBottom = 1800,
                bmpHeight = 1920
            )
        )

        // Band + margin exceeding 72% of bitmap height -> full OCR
        assertNull(
            LiveScreenTranslateController.computeExposedCropBandBmp(
                deltaYPx = -1100,
                confidence = 0.95f,
                bmpClipTop = 100,
                bmpClipBottom = 1820,
                bmpHeight = 1920,
                marginPx = 400
            )
        )
    }
}
