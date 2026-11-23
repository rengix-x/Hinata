package com.edgeflow.service.translate

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

sealed class ScreenMotionResult {
    data object FirstFrame : ScreenMotionResult()
    data object Static : ScreenMotionResult()
    data class Scrolled(val deltaYPx: Int, val confidence: Float) : ScreenMotionResult()
    data object SceneChanged : ScreenMotionResult()
}

/**
 * Ultra-fast (`< 1ms`), Zero-GC 1D vertical luminance-profile cross-correlator that classifies
 * consecutive clean screen frames into [ScreenMotionResult.FirstFrame], [ScreenMotionResult.Static],
 * [ScreenMotionResult.Scrolled] (with pixel-accurate signed `deltaYPx`), or
 * [ScreenMotionResult.SceneChanged].
 *
 * Sign convention: when the user scrolls DOWN, underlying content moves UP on screen so
 * `deltaYPx < 0` (matches `overlayView.applyScrollShift(dyPx)`).
 *
 * Pure Kotlin / Android Bitmap sampling only — ZERO Jetpack Compose imports.
 */
class ImageScrollEstimator {

    private val prevProfile = IntArray(MAX_PROFILE_LEN)
    private val currProfile = IntArray(MAX_PROFILE_LEN)
    private var hasPrev: Boolean = false
    private var prevLength: Int = 0
    private var prevRowStepPx: Int = DEFAULT_ROW_STEP_PX

    fun reset() {
        hasPrev = false
        prevLength = 0
    }

    /**
     * Extracts a horizontal-mean 1D vertical luminance profile inside `[contentTopPx, contentBottomPx]`
     * (sampling 28 columns across 12%..88% width, every `rowStep = 4` px) and evaluates motion.
     */
    fun estimate(
        bitmap: Bitmap,
        contentTopPx: Int,
        contentBottomPx: Int,
        forceReset: Boolean = false
    ): ScreenMotionResult {
        if (bitmap.isRecycled) return ScreenMotionResult.SceneChanged
        val bw = bitmap.width
        val bh = bitmap.height
        if (bw < 16 || bh < 32) return ScreenMotionResult.SceneChanged

        val safeTop = contentTopPx.coerceIn(0, bh - 1)
        val safeBottom = contentBottomPx.coerceIn(safeTop + 1, bh)
        val spanH = safeBottom - safeTop
        if (spanH < MIN_SPAN_PX) return ScreenMotionResult.SceneChanged

        var rowStep = DEFAULT_ROW_STEP_PX
        while (spanH / rowStep > MAX_PROFILE_LEN) {
            rowStep++
        }

        val xStart = (bw * HORIZONTAL_MARGIN_START).roundToInt().coerceIn(0, bw - 1)
        val xEnd = (bw * HORIZONTAL_MARGIN_END).roundToInt().coerceIn(xStart + 1, bw)
        val xSpan = (xEnd - xStart).coerceAtLeast(1)

        var len = 0
        var y = safeTop
        while (y < safeBottom && len < MAX_PROFILE_LEN) {
            var lumSum = 0
            for (col in 0 until HORIZONTAL_SAMPLES_PER_ROW) {
                val sx = (xStart + (xSpan * col) / HORIZONTAL_SAMPLES_PER_ROW).coerceIn(0, bw - 1)
                val px = bitmap.getPixel(sx, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                lumSum += (r * 77 + g * 150 + b * 29) shr 8
            }
            currProfile[len] = lumSum / HORIZONTAL_SAMPLES_PER_ROW
            len++
            y += rowStep
        }

        return evaluateProfiles(
            profile = currProfile,
            length = len,
            rowStepPx = rowStep,
            forceReset = forceReset
        )
    }

    /**
     * Pure-JVM testable 1D profile motion classifier.
     */
    fun evaluateProfiles(
        profile: IntArray,
        length: Int,
        rowStepPx: Int,
        forceReset: Boolean = false
    ): ScreenMotionResult {
        val safeLen = length.coerceIn(0, min(profile.size, MAX_PROFILE_LEN))
        if (safeLen < MIN_PROFILE_ROWS || rowStepPx <= 0) {
            hasPrev = false
            return ScreenMotionResult.SceneChanged
        }

        if (!hasPrev || forceReset || safeLen != prevLength || rowStepPx != prevRowStepPx) {
            System.arraycopy(profile, 0, prevProfile, 0, safeLen)
            prevLength = safeLen
            prevRowStepPx = rowStepPx
            hasPrev = true
            return ScreenMotionResult.FirstFrame
        }

        // 1. Zero-shift difference check (Static fast path)
        var diffSumZero = 0L
        var changedCountZero = 0
        for (i in 0 until safeLen) {
            val d = abs(profile[i] - prevProfile[i])
            diffSumZero += d
            if (d > PIXEL_CHANGE_THRESHOLD) {
                changedCountZero++
            }
        }
        val madZero = diffSumZero.toFloat() / safeLen.toFloat()
        val changedRatioZero = changedCountZero.toFloat() / safeLen.toFloat()

        if (madZero < STATIC_MAD_THRESHOLD && changedRatioZero < STATIC_CHANGED_RATIO_THRESHOLD) {
            System.arraycopy(profile, 0, prevProfile, 0, safeLen)
            return ScreenMotionResult.Static
        }

        // 2. Flat-image / blank-screen variance guard
        if (computeMeanAbsDeviation(profile, safeLen) < FLAT_VARIANCE_THRESHOLD ||
            computeMeanAbsDeviation(prevProfile, safeLen) < FLAT_VARIANCE_THRESHOLD
        ) {
            System.arraycopy(profile, 0, prevProfile, 0, safeLen)
            return ScreenMotionResult.SceneChanged
        }

        // 3. 2-stage 1D cross-correlation (coarse step 3 -> fine step 1)
        val maxShiftRows = (safeLen * MAX_SHIFT_RATIO).toInt()
            .coerceIn(MIN_SEARCH_SHIFT_ROWS, min(MAX_SEARCH_SHIFT_ROWS, safeLen - 4))
        val minOverlapRows = (safeLen * MIN_OVERLAP_RATIO).toInt().coerceAtLeast(8)

        var coarseBestShift = 0
        var coarseBestMad = Float.MAX_VALUE

        var shift = -maxShiftRows
        while (shift <= maxShiftRows) {
            if (shift != 0) {
                val mad = computeShiftMad(profile, prevProfile, safeLen, shift, minOverlapRows)
                if (mad < coarseBestMad) {
                    coarseBestMad = mad
                    coarseBestShift = shift
                }
            }
            shift += COARSE_STEP_ROWS
        }

        var bestShift = coarseBestShift
        var bestMad = coarseBestMad
        val fineStart = (coarseBestShift - COARSE_STEP_ROWS).coerceAtLeast(-maxShiftRows)
        val fineEnd = (coarseBestShift + COARSE_STEP_ROWS).coerceAtMost(maxShiftRows)
        for (s in fineStart..fineEnd) {
            if (s == 0) continue
            val mad = computeShiftMad(profile, prevProfile, safeLen, s, minOverlapRows)
            if (mad < bestMad) {
                bestMad = mad
                bestShift = s
            }
        }

        System.arraycopy(profile, 0, prevProfile, 0, safeLen)

        if (bestShift != 0 &&
            bestMad < SCROLLED_MAX_MAD &&
            bestMad < madZero * SCROLLED_IMPROVEMENT_RATIO
        ) {
            val confidence = (1f - (bestMad / max(madZero, 1f))).coerceIn(0f, 1f)
            return ScreenMotionResult.Scrolled(
                deltaYPx = bestShift * rowStepPx,
                confidence = confidence
            )
        }

        return ScreenMotionResult.SceneChanged
    }

    private fun computeMeanAbsDeviation(arr: IntArray, len: Int): Float {
        var sum = 0L
        for (i in 0 until len) sum += arr[i]
        val mean = sum.toFloat() / len.toFloat()
        var dev = 0f
        for (i in 0 until len) dev += abs(arr[i] - mean)
        return dev / len.toFloat()
    }

    /**
     * Evaluates `MAD` for candidate displacement `shift` where `curr[y + shift] ≈ prev[y]`.
     * Negative `shift` means content moved UP on screen (user scrolled down).
     */
    private fun computeShiftMad(
        curr: IntArray,
        prev: IntArray,
        len: Int,
        shift: Int,
        minOverlap: Int
    ): Float {
        val yStart = max(0, -shift)
        val yEnd = min(len, len - shift)
        val overlap = yEnd - yStart
        if (overlap < minOverlap) return Float.MAX_VALUE

        var diffSum = 0L
        for (y in yStart until yEnd) {
            diffSum += abs(curr[y + shift] - prev[y])
        }
        return diffSum.toFloat() / overlap.toFloat()
    }

    companion object {
        private const val MAX_PROFILE_LEN = 1024
        private const val DEFAULT_ROW_STEP_PX = 4
        private const val MIN_SPAN_PX = 48
        private const val MIN_PROFILE_ROWS = 12
        private const val HORIZONTAL_MARGIN_START = 0.12f
        private const val HORIZONTAL_MARGIN_END = 0.88f
        private const val HORIZONTAL_SAMPLES_PER_ROW = 28

        private const val PIXEL_CHANGE_THRESHOLD = 10
        private const val STATIC_MAD_THRESHOLD = 3.2f
        private const val STATIC_CHANGED_RATIO_THRESHOLD = 0.03f
        private const val FLAT_VARIANCE_THRESHOLD = 3.0f

        private const val MAX_SHIFT_RATIO = 0.55f
        private const val MIN_SEARCH_SHIFT_ROWS = 20
        private const val MAX_SEARCH_SHIFT_ROWS = 320
        private const val MIN_OVERLAP_RATIO = 0.35f
        private const val COARSE_STEP_ROWS = 3

        private const val SCROLLED_MAX_MAD = 8.5f
        private const val SCROLLED_IMPROVEMENT_RATIO = 0.58f
    }
}
