package com.edgeflow.service.translate

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

data class FrameDiffResult(
    val hasScreenChanged: Boolean,
    val mad: Float,
    val changedRatio: Float
)

/**
 * Pure-Kotlin, zero-allocation 32x32 frame luminance difference detector.
 *
 * Pre-allocates two [IntArray] buffers of 1,024 samples and swaps them between frames.
 * When a screen is static (`mad < 3.5f && changedRatio < 0.025f`), returns
 * `hasScreenChanged = false` so OCR and ML Kit translation are 100% skipped (0% CPU/heat).
 */
class FrameDiffDetector {

    private var prevLuma = IntArray(GRID_CELLS)
    private var currLuma = IntArray(GRID_CELLS)
    private var hasPreviousFrame: Boolean = false

    fun reset() {
        hasPreviousFrame = false
    }

    /**
     * Samples a 32x32 grid from [bitmap] directly into the pre-allocated luminance buffer
     * with zero heap allocations and evaluates whether the screen changed meaningfully.
     */
    fun evaluateBitmap(bitmap: Bitmap, force: Boolean = false): FrameDiffResult {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) {
            return FrameDiffResult(hasScreenChanged = true, mad = 255f, changedRatio = 1f)
        }
        // Focus on central content area: exclude top 10% (status bar / floating clock) & 6% side edges
        val startX = w * EDGE_MARGIN_X_RATIO
        val spanX = w * (1f - EDGE_MARGIN_X_RATIO * 2f)
        val startY = h * TOP_MARGIN_Y_RATIO
        val spanY = h * (1f - TOP_MARGIN_Y_RATIO)

        val target = currLuma
        var idx = 0
        for (gy in 0 until GRID_SIZE) {
            val py = (startY + ((gy + 0.5f) * spanY) / GRID_SIZE).toInt().coerceIn(0, h - 1)
            for (gx in 0 until GRID_SIZE) {
                val px = (startX + ((gx + 0.5f) * spanX) / GRID_SIZE).toInt().coerceIn(0, w - 1)
                val pixel = bitmap.getPixel(px, py)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                target[idx] = (r * 77 + g * 150 + b * 29) shr 8
                idx++
            }
        }
        return evaluateCurrentBuffer(force)
    }

    /**
     * Pure-JVM entry point for deterministic unit testing without requiring Android [Bitmap].
     * Expects an [IntArray] of at least [GRID_CELLS] (1024) luminance values in `0..255`.
     */
    fun evaluateLumaGrid(sampleGrid: IntArray, force: Boolean = false): FrameDiffResult {
        require(sampleGrid.size >= GRID_CELLS) {
            "Expected at least $GRID_CELLS luminance samples, got ${sampleGrid.size}"
        }
        System.arraycopy(sampleGrid, 0, currLuma, 0, GRID_CELLS)
        return evaluateCurrentBuffer(force)
    }

    private fun evaluateCurrentBuffer(force: Boolean): FrameDiffResult {
        if (force || !hasPreviousFrame) {
            swapBuffers()
            hasPreviousFrame = true
            return FrameDiffResult(hasScreenChanged = true, mad = 255f, changedRatio = 1f)
        }

        var diffSum = 0L
        var changedCount = 0
        val prev = prevLuma
        val curr = currLuma
        for (i in 0 until GRID_CELLS) {
            val d = abs(curr[i] - prev[i])
            diffSum += d
            if (d > CELL_DIFF_THRESHOLD) {
                changedCount++
            }
        }

        val mad = diffSum.toFloat() / GRID_CELLS.toFloat()
        val changedRatio = changedCount.toFloat() / GRID_CELLS.toFloat()
        val changed = !(mad < STATIC_MAD_THRESHOLD && changedRatio < STATIC_CHANGED_RATIO_THRESHOLD)
        if (changed) {
            swapBuffers()
        }
        return FrameDiffResult(
            hasScreenChanged = changed,
            mad = mad,
            changedRatio = changedRatio
        )
    }

    private fun swapBuffers() {
        val tmp = prevLuma
        prevLuma = currLuma
        currLuma = tmp
    }

    companion object {
        const val GRID_SIZE = 32
        const val GRID_CELLS = GRID_SIZE * GRID_SIZE
        const val CELL_DIFF_THRESHOLD = 14
        const val STATIC_MAD_THRESHOLD = 3.5f
        const val STATIC_CHANGED_RATIO_THRESHOLD = 0.025f
        private const val TOP_MARGIN_Y_RATIO = 0.10f
        private const val EDGE_MARGIN_X_RATIO = 0.06f
    }
}
