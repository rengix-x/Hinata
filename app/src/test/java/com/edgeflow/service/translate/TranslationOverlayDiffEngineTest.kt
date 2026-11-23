package com.edgeflow.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationOverlayDiffEngineTest {

    private fun makeItem(
        key: String,
        payload: String,
        left: Int,
        top: Int,
        width: Int = 200,
        height: Int = 60,
        bgOffsetX: Float = -4f,
        bgOffsetY: Float = -2f
    ): TranslationOverlayDiffEngine.IncomingItem<String> {
        val bounds = BlockBounds(left, top, left + width, top + height)
        return TranslationOverlayDiffEngine.IncomingItem(
            normalizedText = key,
            payload = payload,
            sourceBounds = bounds,
            bgLeft = left + bgOffsetX,
            bgTop = top + bgOffsetY,
            bgWidth = width + 8f,
            bgHeight = height + 4f
        )
    }

    @Test
    fun mergeBlocks_spatialAnchorMatching_existingBlocksNeverReFade_newBlocksFade() {
        val engine = TranslationOverlayDiffEngine<String>()
        val t1 = 1_000_000_000L

        // Initial render: two distinct blocks plus duplicate label at two different Y coordinates
        engine.mergeBlocks(
            incoming = listOf(
                makeItem("hello", "こんにちは", left = 40, top = 100),
                makeItem("ok", "はい-上", left = 40, top = 300),
                makeItem("ok", "はい-下", left = 40, top = 700)
            ),
            removeUnseen = true,
            nowNs = t1
        )
        assertEquals(3, engine.entries.size)
        assertTrue(engine.entries.all { it.fadeStartNs == t1 })

        // Complete initial fade-in
        val t2 = t1 + TranslationOverlayDiffEngine.FADE_IN_DURATION_NS + 50_000_000L
        engine.stepFrame(t2)
        assertTrue(engine.entries.all { it.fadeStartNs == 0L })

        // Subsequent update: existing blocks moved slightly, plus one brand-new block
        val t3 = t2 + 100_000_000L
        engine.mergeBlocks(
            incoming = listOf(
                makeItem("hello", "こんにちは", left = 40, top = 120),
                makeItem("ok", "はい-下-moved", left = 40, top = 710),
                makeItem("ok", "はい-上-moved", left = 40, top = 310),
                makeItem("newitem", "新着", left = 40, top = 920)
            ),
            removeUnseen = true,
            nowNs = t3
        )

        assertEquals(4, engine.entries.size)

        val hello = engine.entries.first { it.normalizedText == "hello" }
        assertEquals(0L, hello.fadeStartNs)
        assertEquals(1f, engine.blockAlpha(hello, t3), 0.0001f)
        assertEquals(118f, hello.targetTop, 0.01f)

        // Spatial anchor matching paired each "ok" with its nearest prior instance
        val okUpper = engine.entries.first { it.payload == "はい-上-moved" }
        val okLower = engine.entries.first { it.payload == "はい-下-moved" }
        assertEquals(0L, okUpper.fadeStartNs)
        assertEquals(0L, okLower.fadeStartNs)
        assertEquals(308f, okUpper.targetTop, 0.01f)
        assertEquals(708f, okLower.targetTop, 0.01f)

        // Newly added block fades in smoothly from t3
        val added = engine.entries.first { it.normalizedText == "newitem" }
        assertEquals(t3, added.fadeStartNs)
        assertEquals(0f, engine.blockAlpha(added, t3), 0.0001f)
        assertTrue(engine.blockAlpha(added, t3 + TranslationOverlayDiffEngine.FADE_IN_DURATION_NS / 2) > 0.5f)
        assertEquals(1f, engine.blockAlpha(added, t3 + TranslationOverlayDiffEngine.FADE_IN_DURATION_NS), 0.0001f)
    }

    @Test
    fun missTolerance2_retainsMissingBlockForTwoCycles_andRemovesOnThird() {
        val engine = TranslationOverlayDiffEngine<String>()
        val t0 = 10_000_000_000L

        engine.mergeBlocks(
            incoming = listOf(
                makeItem("keep", "残る", left = 20, top = 100),
                makeItem("transient", "一時欠落", left = 20, top = 260)
            ),
            removeUnseen = true,
            nowNs = t0
        )
        assertEquals(2, engine.entries.size)

        // Miss #1 -> retained (missCount == 1)
        engine.mergeBlocks(
            incoming = listOf(makeItem("keep", "残る", left = 20, top = 100)),
            removeUnseen = true,
            nowNs = t0 + 100_000_000L
        )
        assertEquals(2, engine.entries.size)
        assertEquals(1, engine.entries.first { it.normalizedText == "transient" }.missCount)

        // Miss #2 -> still retained (missCount == 2 == MISS_TOLERANCE)
        engine.mergeBlocks(
            incoming = listOf(makeItem("keep", "残る", left = 20, top = 100)),
            removeUnseen = true,
            nowNs = t0 + 200_000_000L
        )
        assertEquals(2, engine.entries.size)
        assertEquals(2, engine.entries.first { it.normalizedText == "transient" }.missCount)

        // Miss #3 -> exceeded MISS_TOLERANCE (3 > 2), removed!
        engine.mergeBlocks(
            incoming = listOf(makeItem("keep", "残る", left = 20, top = 100)),
            removeUnseen = true,
            nowNs = t0 + 300_000_000L
        )
        assertEquals(1, engine.entries.size)
        assertEquals("keep", engine.entries.single().normalizedText)
    }

    @Test
    fun webViewLagGuard_preservesShiftAllCoordsUntilFreshNodeBoundsMove() {
        val engine = TranslationOverlayDiffEngine<String>()
        engine.trackingMode = true
        engine.mergeBlocks(
            incoming = listOf(makeItem("article", "記事", left = 50, top = 400)),
            removeUnseen = true,
            nowNs = 5_000_000_000L
        )

        val initialCurTop = engine.entries.single().curTop
        assertEquals(398f, initialCurTop, 0.01f)

        // Stage 1: instant 0ms dead-reckoning scroll by -120px
        engine.shiftAll(dx = 0f, dy = -120f)
        assertEquals(278f, engine.entries.single().curTop, 0.01f)
        assertTrue(engine.entries.single().shiftedSinceLastFresh)

        // Stage 2: stale WebView tree still reports old (50, 400) -> MUST NOT rewind!
        val staleChanged = engine.applyBoundsCorrection(
            mapOf("article" to listOf(BlockBounds(50, 400, 250, 460)))
        )
        assertFalse(staleChanged)
        assertEquals(278f, engine.entries.single().curTop, 0.01f)
        assertEquals(278f, engine.entries.single().targetTop, 0.01f)

        // Stage 2: WebView tree finally updates to (50, 274) -> snaps cleanly!
        val freshChanged = engine.applyBoundsCorrection(
            mapOf("article" to listOf(BlockBounds(50, 274, 250, 334)))
        )
        assertTrue(freshChanged)
        assertEquals(272f, engine.entries.single().curTop, 0.01f)
        assertEquals(272f, engine.entries.single().targetTop, 0.01f)
        assertFalse(engine.entries.single().shiftedSinceLastFresh)
    }

    @Test
    fun excludeOverlappingOcrBlocks_dropsOver40PercentAndKeepsAtOrBelow40Percent() {
        val occupied = listOf(BlockBounds(100, 100, 300, 200))

        // 50% overlap (x: 100..200 of 100..300 = 100x100 out of 200x100) -> excluded
        val coveredHalf = RawOcrBlock(
            text = "Already translated",
            bounds = BlockBounds(200, 100, 400, 200)
        )
        assertEquals(0.50f, TranslationOverlayDiffEngine.overlapRatio(coveredHalf.bounds, occupied[0]), 0.001f)

        // Exactly 40% overlap (x: 220..300 = 80px of 200px width) -> kept (strictly > 0.40f drops)
        val coveredForty = RawOcrBlock(
            text = "Edge adjacent",
            bounds = BlockBounds(220, 100, 420, 200)
        )
        assertEquals(0.40f, TranslationOverlayDiffEngine.overlapRatio(coveredForty.bounds, occupied[0]), 0.001f)

        // 0% overlap -> kept
        val disjoint = RawOcrBlock(
            text = "Canvas caption",
            bounds = BlockBounds(100, 320, 300, 400)
        )

        val kept = TranslationOverlayDiffEngine.excludeOverlappingOcrBlocks(
            ocrBlocks = listOf(coveredHalf, coveredForty, disjoint),
            occupiedRects = occupied
        )
        assertEquals(listOf("Edge adjacent", "Canvas caption"), kept.map { it.text })
    }

    @Test
    fun isStructurallyUnchanged_ignoresUpTo4PxJitter_andDetectsLargerMoves() {
        val prev = listOf(
            "title" to BlockBounds(20, 100, 400, 160),
            "body" to BlockBounds(20, 200, 400, 280)
        )
        val jitterWithin4Px = listOf(
            "body" to BlockBounds(23, 197, 402, 284),
            "title" to BlockBounds(20, 104, 396, 158)
        )
        val movedBeyond4Px = listOf(
            "title" to BlockBounds(20, 106, 400, 166),
            "body" to BlockBounds(20, 200, 400, 280)
        )

        assertTrue(TranslationOverlayDiffEngine.isStructurallyUnchanged(prev, jitterWithin4Px))
        assertFalse(TranslationOverlayDiffEngine.isStructurallyUnchanged(prev, movedBeyond4Px))
    }
}
