package com.edgeflow.service.translate

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Snapshot of a currently displayed overlay block exported for single-cycle 1:1 reconciliation.
 */
data class ActiveTrackedBlock(
    val id: Long,
    val originalText: String,
    val normalizedOriginal: String,
    val currentBounds: BlockBounds
)

/**
 * Matched active overlay block that stays on screen (zero flicker, no re-translation needed).
 */
data class KeptBlockMatch(
    val id: Long,
    val freshBlock: FilteredOcrBlock,
    val boundsChangedSignificantly: Boolean
)

/**
 * Single-cycle 1:1 reconciliation output:
 * - [kept]: Existing blocks matched 1:1 to [freshBlock] (snap/shift only, zero re-translation).
 * - [toTranslate]: Newly appeared blocks that must be translated and added.
 * - [removedIds]: Active blocks no longer present on screen (`MISS_TOLERANCE = 0`, immediate prune).
 */
data class ReconcileResult(
    val kept: List<KeptBlockMatch>,
    val toTranslate: List<FilteredOcrBlock>,
    val removedIds: Set<Long>
)

/**
 * Pure-Kotlin 1:1 Scanline Reconciler eliminating ghost translations (`MISS_TOLERANCE = 0`) and
 * preventing redundant translation calls on already-displayed blocks during scrolling.
 *
 * ZERO Android framework or Compose dependencies — 100% pure-JVM unit testable.
 */
object ScanlineReconciler {

    const val MISS_TOLERANCE = 0
    const val TEXT_SIMILARITY_THRESHOLD = 0.75f

    private const val CENTER_DIST_HEIGHT_MULT = 1.4f
    private const val CENTER_DIST_BONUS_PX = 48f
    private const val SIGNIFICANT_POS_DELTA_PX = 10
    private const val SIGNIFICANT_SIZE_DELTA_PX = 16

    fun reconcile(
        activeBlocks: List<ActiveTrackedBlock>,
        freshBlocks: List<FilteredOcrBlock>
    ): ReconcileResult {
        if (activeBlocks.isEmpty()) {
            return ReconcileResult(
                kept = emptyList(),
                toTranslate = freshBlocks,
                removedIds = emptySet()
            )
        }
        if (freshBlocks.isEmpty()) {
            val allRemoved = HashSet<Long>(activeBlocks.size * 2)
            for (a in activeBlocks) allRemoved.add(a.id)
            return ReconcileResult(
                kept = emptyList(),
                toTranslate = emptyList(),
                removedIds = allRemoved
            )
        }

        val freshNorms = Array(freshBlocks.size) { i ->
            StructuredNodeTextExtractor.normalizeKey(freshBlocks[i].text)
        }

        data class CandidatePair(
            val activeIdx: Int,
            val freshIdx: Int,
            val score: Float
        )

        val candidates = ArrayList<CandidatePair>(activeBlocks.size * 2)

        for (ai in activeBlocks.indices) {
            val active = activeBlocks[ai]
            val aBounds = active.currentBounds
            val aNorm = active.normalizedOriginal.ifEmpty {
                StructuredNodeTextExtractor.normalizeKey(active.originalText)
            }
            val aCx = (aBounds.left + aBounds.right) * 0.5f
            val aCy = (aBounds.top + aBounds.bottom) * 0.5f

            for (fi in freshBlocks.indices) {
                val fresh = freshBlocks[fi]
                val fBounds = fresh.bounds

                val sim = textSimilarity(aNorm, freshNorms[fi])
                if (sim < TEXT_SIMILARITY_THRESHOLD) continue

                val spatialScore = computeSpatialCompatibility(aBounds, fBounds, aCx, aCy)
                if (spatialScore < 0f) continue

                val totalScore = sim * 0.7f + spatialScore * 0.3f
                candidates.add(CandidatePair(ai, fi, totalScore))
            }
        }

        candidates.sortByDescending { it.score }

        val usedActive = BooleanArray(activeBlocks.size)
        val usedFresh = BooleanArray(freshBlocks.size)
        val kept = ArrayList<KeptBlockMatch>(min(activeBlocks.size, freshBlocks.size))

        for (pair in candidates) {
            if (usedActive[pair.activeIdx] || usedFresh[pair.freshIdx]) continue
            usedActive[pair.activeIdx] = true
            usedFresh[pair.freshIdx] = true

            val active = activeBlocks[pair.activeIdx]
            val fresh = freshBlocks[pair.freshIdx]
            val oldB = active.currentBounds
            val newB = fresh.bounds

            val changedSignificantly =
                abs(oldB.left - newB.left) > SIGNIFICANT_POS_DELTA_PX ||
                    abs(oldB.top - newB.top) > SIGNIFICANT_POS_DELTA_PX ||
                    abs(oldB.width - newB.width) > SIGNIFICANT_SIZE_DELTA_PX ||
                    abs(oldB.height - newB.height) > SIGNIFICANT_SIZE_DELTA_PX

            kept.add(
                KeptBlockMatch(
                    id = active.id,
                    freshBlock = fresh,
                    boundsChangedSignificantly = changedSignificantly
                )
            )
        }

        val removedIds = HashSet<Long>()
        for (ai in activeBlocks.indices) {
            if (!usedActive[ai]) {
                removedIds.add(activeBlocks[ai].id)
            }
        }

        val toTranslate = ArrayList<FilteredOcrBlock>()
        for (fi in freshBlocks.indices) {
            if (!usedFresh[fi]) {
                toTranslate.add(freshBlocks[fi])
            }
        }

        return ReconcileResult(
            kept = kept,
            toTranslate = toTranslate,
            removedIds = removedIds
        )
    }

    /**
     * Returns `[0f, 1f]` overlap/proximity score if [a] and [b] are spatially compatible, or `-1f`
     * if they represent distinct screen elements.
     */
    private fun computeSpatialCompatibility(
        a: BlockBounds,
        b: BlockBounds,
        aCx: Float,
        aCy: Float
    ): Float {
        if (a.intersects(b)) {
            val ix = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0).toLong()
            val iy = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0).toLong()
            val inter = ix * iy
            val union = a.width.toLong() * a.height.toLong() +
                b.width.toLong() * b.height.toLong() - inter
            return if (union > 0L) (inter.toFloat() / union.toFloat()).coerceIn(0f, 1f) else 1f
        }

        val hOverlap = min(a.right, b.right) - max(a.left, b.left)
        if (hOverlap <= 0) return -1f

        val bCx = (b.left + b.right) * 0.5f
        val bCy = (b.top + b.bottom) * 0.5f
        val dist = hypot((aCx - bCx).toDouble(), (aCy - bCy).toDouble()).toFloat()
        val maxAllowed = max(a.height, b.height).toFloat() * CENTER_DIST_HEIGHT_MULT + CENTER_DIST_BONUS_PX
        if (dist > maxAllowed) return -1f

        return (1f - (dist / max(maxAllowed, 1f))).coerceIn(0f, 0.85f)
    }

    /**
     * Pure-Kotlin character bigram Dice similarity coefficient (`[0f, 1f]`) with exact & 1-char
     * fast paths. Handles slight OCR punctuation/whitespace jitter reliably.
     */
    fun textSimilarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f
        if (a.length == 1 || b.length == 1) {
            return if (a == b) 1f else 0f
        }

        val countA = a.length - 1
        val countB = b.length - 1
        val bigramsA = HashMap<Int, Int>(countA * 2)
        for (i in 0 until countA) {
            val key = (a[i].code shl 16) or (a[i + 1].code and 0xFFFF)
            bigramsA[key] = (bigramsA[key] ?: 0) + 1
        }

        var intersection = 0
        for (i in 0 until countB) {
            val key = (b[i].code shl 16) or (b[i + 1].code and 0xFFFF)
            val rem = bigramsA[key] ?: 0
            if (rem > 0) {
                intersection++
                bigramsA[key] = rem - 1
            }
        }
        return (2f * intersection.toFloat()) / (countA + countB).toFloat()
    }
}
