package com.edgeflow.service.translate

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure-Kotlin block-level Diff Merge & ease-out fade engine for [TranslationOverlayView].
 *
 * Zero Android framework dependencies so every spatial-anchor matching, zero-flicker re-use,
 * fade-in, off-viewport pruning, and `MISS_TOLERANCE = 2` rule is 100% pure-JVM unit testable.
 */
object TranslationBlockDiffMerger {

    const val FADE_IN_DURATION_MS = 120L
    const val MISS_TOLERANCE = 2
    private const val MIN_SPATIAL_MATCH_DISTANCE_PX = 420f
    private const val SPATIAL_MATCH_VIEWPORT_RATIO = 0.45f

    data class LiveBlock<T>(
        val key: String,
        var rendered: T,
        var baseLeft: Float,
        var baseTop: Float,
        var baseRight: Float,
        var baseBottom: Float,
        var offsetX: Float = 0f,
        var offsetY: Float = 0f,
        var fadeStartUptimeMs: Long = 0L,
        var currentAlpha: Float = 1f,
        var missCount: Int = 0
    ) {
        val currentLeft: Float get() = baseLeft + offsetX
        val currentTop: Float get() = baseTop + offsetY
        val currentRight: Float get() = baseRight + offsetX
        val currentBottom: Float get() = baseBottom + offsetY
        val currentCenterY: Float get() = (currentTop + currentBottom) * 0.5f

        fun toCurrentScreenBounds(): BlockBounds = BlockBounds(
            left = currentLeft.roundToInt(),
            top = currentTop.roundToInt(),
            right = currentRight.roundToInt(),
            bottom = currentBottom.roundToInt()
        )
    }

    data class IncomingBlock<T>(
        val key: String,
        val rendered: T,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val centerY: Float get() = (top + bottom) * 0.5f
    }

    /**
     * Performs zero-flicker Spatial Anchor Diff Merge:
     * 1. Matches each incoming block to the closest unmatched existing block with `existing.key == inc.key`
     *    whose vertical distance is `<= max(viewportHeightPx * 0.45f, 420f)`.
     * 2. Matched existing blocks update in place with `offsetX = 0f`, `offsetY = 0f`, `missCount = 0`,
     *    `fadeStartUptimeMs = 0L`, and `currentAlpha = 1f` (NEVER re-faded = zero flicker!).
     * 3. Newly appearing blocks fade in over [FADE_IN_DURATION_MS] (`120ms` ease-out quad) when
     *    [animateNewBlocks] is `true`, or start at `1f` otherwise.
     * 4. Unmatched existing blocks outside `[viewportTopPx, viewportBottomPx]` are removed immediately;
     *    in-viewport unmatched blocks are kept when `removeUnseen == false`, or allowed up to
     *    [MISS_TOLERANCE] (`2`) consecutive misses before removal when `removeUnseen == true`.
     */
    fun <T> mergeBlocks(
        existing: List<LiveBlock<T>>,
        incoming: List<IncomingBlock<T>>,
        viewportTopPx: Int,
        viewportBottomPx: Int,
        viewportHeightPx: Int,
        removeUnseen: Boolean,
        animateNewBlocks: Boolean,
        nowUptimeMs: Long
    ): MutableList<LiveBlock<T>> {
        val maxDistY = max(viewportHeightPx * SPATIAL_MATCH_VIEWPORT_RATIO, MIN_SPATIAL_MATCH_DISTANCE_PX)
        val matchedExisting = BooleanArray(existing.size)
        val nextList = ArrayList<LiveBlock<T>>(existing.size + incoming.size)

        for (i in incoming.indices) {
            val inc = incoming[i]
            val incCenterY = inc.centerY
            var bestIdx = -1
            var bestDist = Float.MAX_VALUE

            for (j in existing.indices) {
                if (matchedExisting[j]) continue
                val ex = existing[j]
                if (ex.key != inc.key) continue
                val dist = abs(ex.currentCenterY - incCenterY)
                if (dist <= maxDistY && dist < bestDist) {
                    bestDist = dist
                    bestIdx = j
                }
            }

            if (bestIdx >= 0) {
                matchedExisting[bestIdx] = true
                val reused = existing[bestIdx]
                reused.rendered = inc.rendered
                reused.baseLeft = inc.left
                reused.baseTop = inc.top
                reused.baseRight = inc.right
                reused.baseBottom = inc.bottom
                reused.offsetX = 0f
                reused.offsetY = 0f
                reused.missCount = 0
                // Strictly preserve full opacity — NEVER re-fade existing blocks!
                reused.fadeStartUptimeMs = 0L
                reused.currentAlpha = 1f
                nextList.add(reused)
            } else {
                nextList.add(
                    LiveBlock(
                        key = inc.key,
                        rendered = inc.rendered,
                        baseLeft = inc.left,
                        baseTop = inc.top,
                        baseRight = inc.right,
                        baseBottom = inc.bottom,
                        offsetX = 0f,
                        offsetY = 0f,
                        fadeStartUptimeMs = if (animateNewBlocks) nowUptimeMs else 0L,
                        currentAlpha = if (animateNewBlocks) 0f else 1f,
                        missCount = 0
                    )
                )
            }
        }

        val clipTopF = viewportTopPx.toFloat()
        val clipBottomF = viewportBottomPx.toFloat()

        for (j in existing.indices) {
            if (matchedExisting[j]) continue
            val ex = existing[j]

            // Immediately drop blocks that have scrolled completely outside the visible viewport
            if (ex.currentBottom < clipTopF || ex.currentTop > clipBottomF) {
                continue
            }

            if (!removeUnseen) {
                nextList.add(ex)
            } else {
                ex.missCount += 1
                if (ex.missCount <= MISS_TOLERANCE) {
                    nextList.add(ex)
                }
            }
        }

        return nextList
    }

    /**
     * Computes ease-out quad opacity in `[0f, 1f]` for a block at [nowUptimeMs].
     */
    fun computeFadeAlpha(fadeStartUptimeMs: Long, nowUptimeMs: Long): Float {
        if (fadeStartUptimeMs <= 0L) return 1f
        val elapsed = (nowUptimeMs - fadeStartUptimeMs).coerceAtLeast(0L)
        if (elapsed >= FADE_IN_DURATION_MS) return 1f
        val t = (elapsed.toFloat() / FADE_IN_DURATION_MS.toFloat()).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t)
    }
}
