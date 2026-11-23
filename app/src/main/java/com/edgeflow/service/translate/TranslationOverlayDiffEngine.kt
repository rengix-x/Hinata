package com.edgeflow.service.translate

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin Spatial Anchor Matching, Block-Level Diff Merge, 2-Stage Scroll Tracking
 * (dead-reckoning + WebView lag-guarded bounds snapping + `MISS_TOLERANCE = 2` grace),
 * and OCR overlap exclusion (`overlapRatio > 0.40f`).
 *
 * Kept 100% free of Android framework dependencies so every state transition can be
 * exhaustively verified in pure-JVM unit tests.
 */
class TranslationOverlayDiffEngine<T> {

    class TrackedEntry<T>(
        val normalizedText: String,
        var payload: T,
        var sourceLeft: Int,
        var sourceTop: Int,
        var sourceRight: Int,
        var sourceBottom: Int,
        var bgOffsetLeft: Float,
        var bgOffsetTop: Float,
        var boxWidth: Float,
        var boxHeight: Float,
        var targetLeft: Float,
        var targetTop: Float,
        var curLeft: Float,
        var curTop: Float,
        var fadeStartNs: Long,
        var missCount: Int = 0,
        var lastRawNodeLeft: Int = sourceLeft,
        var lastRawNodeTop: Int = sourceTop,
        var shiftedSinceLastFresh: Boolean = false
    ) {
        val currentCenterX: Float get() = curLeft + boxWidth * 0.5f
        val currentCenterY: Float get() = curTop + boxHeight * 0.5f

        fun currentOccupiedBounds(): BlockBounds = BlockBounds(
            left = curLeft.toInt(),
            top = curTop.toInt(),
            right = (curLeft + boxWidth).toInt(),
            bottom = (curTop + boxHeight).toInt()
        )
    }

    data class IncomingItem<T>(
        val normalizedText: String,
        val payload: T,
        val sourceBounds: BlockBounds,
        val bgLeft: Float,
        val bgTop: Float,
        val bgWidth: Float,
        val bgHeight: Float
    )

    val entries: MutableList<TrackedEntry<T>> = ArrayList(48)

    var trackingMode: Boolean = false

    fun clear() {
        entries.clear()
        trackingMode = false
    }

    fun hasEntries(): Boolean = entries.isNotEmpty()

    fun getWantedNormalizedKeys(): Set<String> {
        if (entries.isEmpty()) return emptySet()
        val set = HashSet<String>(entries.size * 2)
        for (i in entries.indices) {
            val k = entries[i].normalizedText
            if (k.isNotEmpty()) set.add(k)
        }
        return set
    }

    fun getOccupiedRects(): List<BlockBounds> {
        if (entries.isEmpty()) return emptyList()
        val out = ArrayList<BlockBounds>(entries.size)
        for (i in entries.indices) {
            out.add(entries[i].currentOccupiedBounds())
        }
        return out
    }

    /**
     * Stage 1 (0ms dead-reckoning): immediately translates every active overlay block's
     * target and current drawing coordinates by ([dx], [dy]).
     */
    fun shiftAll(dx: Float, dy: Float) {
        if (entries.isEmpty() || (dx == 0f && dy == 0f)) return
        for (i in entries.indices) {
            val e = entries[i]
            e.targetLeft += dx
            e.targetTop += dy
            e.curLeft += dx
            e.curTop += dy
            e.shiftedSinceLastFresh = true
        }
    }

    /**
     * Stage 2 (120fps tracker-thread correction): snaps blocks to fresh `AccessibilityNodeInfo`
     * screen bounds via nearest spatial match (`pickNearest`), with WebView coordinate-lag guard
     * and `MISS_TOLERANCE = 2` grace before removal.
     */
    fun applyBoundsCorrection(fresh: Map<String, List<BlockBounds>>): Boolean {
        if (entries.isEmpty()) return false

        // Copy candidate lists per key so each fresh rect is claimed at most once
        val pools = HashMap<String, MutableList<BlockBounds>>(fresh.size * 2)
        for ((key, rects) in fresh) {
            if (rects.isNotEmpty()) {
                pools[key] = ArrayList(rects)
            }
        }

        // WebView lag guard: if shiftAll moved blocks since the last fresh snapshot and EVERY
        // matched candidate in `fresh` is still at the exact previous raw node coordinate
        // (< 1px movement), do NOT rewind to stale WebView coordinates.
        var anyShifted = false
        var matchedAny = false
        var anyFreshMoved = false
        for (i in entries.indices) {
            val e = entries[i]
            if (e.shiftedSinceLastFresh) anyShifted = true
            val list = pools[e.normalizedText] ?: continue
            val nearest = peekNearest(list, e.currentCenterX, e.currentCenterY) ?: continue
            matchedAny = true
            if (abs(nearest.left - e.lastRawNodeLeft) >= 1 || abs(nearest.top - e.lastRawNodeTop) >= 1) {
                anyFreshMoved = true
                break
            }
        }
        if (anyShifted && matchedAny && !anyFreshMoved) {
            // Tree has not updated yet — preserve dead-reckoned shiftAll coordinates
            return false
        }

        var changed = false
        val iter = entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            val pool = pools[e.normalizedText]
            val best = if (pool != null && pool.isNotEmpty()) {
                pickNearestAndRemove(pool, e.currentCenterX, e.currentCenterY)
            } else {
                null
            }

            if (best != null) {
                e.missCount = 0
                e.lastRawNodeLeft = best.left
                e.lastRawNodeTop = best.top
                e.sourceLeft = best.left
                e.sourceTop = best.top
                e.sourceRight = best.right
                e.sourceBottom = best.bottom
                e.shiftedSinceLastFresh = false

                val nextLeft = best.left + e.bgOffsetLeft
                val nextTop = best.top + e.bgOffsetTop
                if (abs(nextLeft - e.targetLeft) >= 0.5f || abs(nextTop - e.targetTop) >= 0.5f) {
                    changed = true
                }
                e.targetLeft = nextLeft
                e.targetTop = nextTop
                if (trackingMode) {
                    e.curLeft = nextLeft
                    e.curTop = nextTop
                }
            } else {
                e.missCount++
                if (e.missCount > MISS_TOLERANCE) {
                    iter.remove()
                    changed = true
                }
            }
        }
        return changed
    }

    /**
     * Block-level diff merge using Spatial Anchor Matching (`normalizedText` + nearest distance):
     * - Existing matched entries reuse the exact instance, keep `fadeStartNs = 0L` (constant
     *   `alpha = 1.0f`, ZERO re-fade flicker), and update target coordinates.
     * - Newly appearing entries receive `fadeStartNs = nowNs` for a gentle `120ms` fade-in.
     * - When [removeUnseen] is `true`, unmatched existing entries increment `missCount` and are
     *   only removed once `missCount > MISS_TOLERANCE` (`MISS_TOLERANCE = 2`).
     */
    fun mergeBlocks(
        incoming: List<IncomingItem<T>>,
        removeUnseen: Boolean,
        nowNs: Long
    ) {
        val matchedExisting = BooleanArray(entries.size)
        val newlyAdded = ArrayList<TrackedEntry<T>>()

        for (item in incoming) {
            val itemCx = item.bgLeft + item.bgWidth * 0.5f
            val itemCy = item.bgTop + item.bgHeight * 0.5f

            var bestIdx = -1
            var bestDist = Float.MAX_VALUE
            for (i in entries.indices) {
                if (matchedExisting[i]) continue
                val existing = entries[i]
                if (existing.normalizedText != item.normalizedText) continue
                val dist = hypot(
                    (existing.currentCenterX - itemCx).toDouble(),
                    (existing.currentCenterY - itemCy).toDouble()
                ).toFloat()
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = i
                }
            }

            if (bestIdx >= 0) {
                matchedExisting[bestIdx] = true
                val existing = entries[bestIdx]
                existing.payload = item.payload
                existing.sourceLeft = item.sourceBounds.left
                existing.sourceTop = item.sourceBounds.top
                existing.sourceRight = item.sourceBounds.right
                existing.sourceBottom = item.sourceBounds.bottom
                existing.lastRawNodeLeft = item.sourceBounds.left
                existing.lastRawNodeTop = item.sourceBounds.top
                existing.shiftedSinceLastFresh = false
                existing.bgOffsetLeft = item.bgLeft - item.sourceBounds.left
                existing.bgOffsetTop = item.bgTop - item.sourceBounds.top
                existing.boxWidth = item.bgWidth
                existing.boxHeight = item.bgHeight
                existing.targetLeft = item.bgLeft
                existing.targetTop = item.bgTop
                if (trackingMode) {
                    existing.curLeft = item.bgLeft
                    existing.curTop = item.bgTop
                }
                // Existing matched block MUST NOT re-fade (alpha = 1.0f fixed)
                existing.fadeStartNs = 0L
                existing.missCount = 0
            } else {
                newlyAdded.add(
                    TrackedEntry(
                        normalizedText = item.normalizedText,
                        payload = item.payload,
                        sourceLeft = item.sourceBounds.left,
                        sourceTop = item.sourceBounds.top,
                        sourceRight = item.sourceBounds.right,
                        sourceBottom = item.sourceBounds.bottom,
                        bgOffsetLeft = item.bgLeft - item.sourceBounds.left,
                        bgOffsetTop = item.bgTop - item.sourceBounds.top,
                        boxWidth = item.bgWidth,
                        boxHeight = item.bgHeight,
                        targetLeft = item.bgLeft,
                        targetTop = item.bgTop,
                        curLeft = item.bgLeft,
                        curTop = item.bgTop,
                        fadeStartNs = nowNs,
                        missCount = 0,
                        lastRawNodeLeft = item.sourceBounds.left,
                        lastRawNodeTop = item.sourceBounds.top,
                        shiftedSinceLastFresh = false
                    )
                )
            }
        }

        if (removeUnseen) {
            for (i in entries.size - 1 downTo 0) {
                if (!matchedExisting[i]) {
                    val e = entries[i]
                    e.missCount++
                    if (e.missCount > MISS_TOLERANCE) {
                        entries.removeAt(i)
                    }
                }
            }
        }

        if (newlyAdded.isNotEmpty()) {
            entries.addAll(newlyAdded)
        }
    }

    /**
     * Advances position interpolation & per-block fade-in for one frame.
     * Returns `true` if another animation frame should be scheduled.
     */
    fun stepFrame(nowNs: Long): Boolean {
        var needsNextFrame = false
        for (i in entries.indices) {
            val e = entries[i]
            val dx = e.targetLeft - e.curLeft
            val dy = e.targetTop - e.curTop
            if (trackingMode) {
                e.curLeft = e.targetLeft
                e.curTop = e.targetTop
            } else if (abs(dx) > 0.4f || abs(dy) > 0.4f) {
                e.curLeft += dx * SMOOTH_FACTOR
                e.curTop += dy * SMOOTH_FACTOR
                needsNextFrame = true
            } else {
                e.curLeft = e.targetLeft
                e.curTop = e.targetTop
            }

            if (e.fadeStartNs > 0L) {
                val elapsedNs = (nowNs - e.fadeStartNs).coerceAtLeast(0L)
                if (elapsedNs >= FADE_IN_DURATION_NS) {
                    e.fadeStartNs = 0L
                } else {
                    needsNextFrame = true
                }
            }
        }
        return needsNextFrame
    }

    fun blockAlpha(entry: TrackedEntry<T>, nowNs: Long): Float {
        val start = entry.fadeStartNs
        if (start <= 0L) return 1f
        val elapsedNs = (nowNs - start).coerceAtLeast(0L)
        if (elapsedNs >= FADE_IN_DURATION_NS) return 1f
        val t = (elapsedNs.toFloat() / FADE_IN_DURATION_NS.toFloat()).coerceIn(0f, 1f)
        val inv = 1f - t
        return 1f - inv * inv
    }

    private fun peekNearest(candidates: List<BlockBounds>, cx: Float, cy: Float): BlockBounds? {
        var best: BlockBounds? = null
        var bestDist = Float.MAX_VALUE
        for (i in candidates.indices) {
            val c = candidates[i]
            val ccx = (c.left + c.right) * 0.5f
            val ccy = (c.top + c.bottom) * 0.5f
            val d = hypot((ccx - cx).toDouble(), (ccy - cy).toDouble()).toFloat()
            if (d < bestDist) {
                bestDist = d
                best = c
            }
        }
        return best
    }

    private fun pickNearestAndRemove(candidates: MutableList<BlockBounds>, cx: Float, cy: Float): BlockBounds? {
        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        for (i in candidates.indices) {
            val c = candidates[i]
            val ccx = (c.left + c.right) * 0.5f
            val ccy = (c.top + c.bottom) * 0.5f
            val d = hypot((ccx - cx).toDouble(), (ccy - cy).toDouble()).toFloat()
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return if (bestIdx >= 0) candidates.removeAt(bestIdx) else null
    }

    companion object {
        const val MISS_TOLERANCE = 2
        const val SMOOTH_FACTOR = 0.45f
        const val FADE_IN_DURATION_MS = 120L
        const val FADE_IN_DURATION_NS = FADE_IN_DURATION_MS * 1_000_000L
        const val OVERLAY_OCR_EXCLUDE_OVERLAP_RATIO = 0.40f
        const val STRUCTURAL_IDLE_TOLERANCE_PX = 4

        /**
         * Computes `intersectionArea / blockArea` for [block] against [occupied].
         */
        fun overlapRatio(block: BlockBounds, occupied: BlockBounds): Float {
            val blockArea = block.width.toLong() * block.height.toLong()
            if (blockArea <= 0L) return 0f
            val interL = max(block.left, occupied.left)
            val interT = max(block.top, occupied.top)
            val interR = min(block.right, occupied.right)
            val interB = min(block.bottom, occupied.bottom)
            if (interR <= interL || interB <= interT) return 0f
            val interArea = (interR - interL).toLong() * (interB - interT).toLong()
            return (interArea.toFloat() / blockArea.toFloat()).coerceIn(0f, 1f)
        }

        /**
         * Excludes OCR blocks overlapping any currently displayed translation box by more than
         * [maxOverlapRatio] (`0.40f`), enabling zero-flicker fallback OCR without ever hiding overlays.
         */
        fun excludeOverlappingOcrBlocks(
            ocrBlocks: List<RawOcrBlock>,
            occupiedRects: List<BlockBounds>,
            maxOverlapRatio: Float = OVERLAY_OCR_EXCLUDE_OVERLAP_RATIO
        ): List<RawOcrBlock> {
            if (ocrBlocks.isEmpty() || occupiedRects.isEmpty()) return ocrBlocks
            val kept = ArrayList<RawOcrBlock>(ocrBlocks.size)
            for (block in ocrBlocks) {
                var covered = false
                for (occ in occupiedRects) {
                    if (overlapRatio(block.bounds, occ) > maxOverlapRatio) {
                        covered = true
                        break
                    }
                }
                if (!covered) {
                    kept.add(block)
                }
            }
            return kept
        }

        /**
         * Returns `true` when [current] and [previous] contain the exact same normalized text set
         * and every matching block moved by at most [tolerancePx] (`4px`).
         */
        fun isStructurallyUnchanged(
            previous: List<Pair<String, BlockBounds>>,
            current: List<Pair<String, BlockBounds>>,
            tolerancePx: Int = STRUCTURAL_IDLE_TOLERANCE_PX
        ): Boolean {
            if (previous.size != current.size) return false
            if (previous.isEmpty()) return true
            val matched = BooleanArray(previous.size)
            for (i in current.indices) {
                val (key, curB) = current[i]
                var found = false
                for (j in previous.indices) {
                    if (matched[j]) continue
                    val (prevKey, prevB) = previous[j]
                    if (prevKey == key &&
                        abs(prevB.left - curB.left) <= tolerancePx &&
                        abs(prevB.top - curB.top) <= tolerancePx &&
                        abs(prevB.right - curB.right) <= tolerancePx &&
                        abs(prevB.bottom - curB.bottom) <= tolerancePx
                    ) {
                        matched[j] = true
                        found = true
                        break
                    }
                }
                if (!found) return false
            }
            return true
        }
    }
}
