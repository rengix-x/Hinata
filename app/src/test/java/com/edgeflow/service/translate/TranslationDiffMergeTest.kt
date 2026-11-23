package com.edgeflow.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationDiffMergeTest {

    @Test
    fun mergeBlocks_reusesMatchedExistingBlocksWithoutReFadingAndFadesOnlyNewBlocks() {
        val initial = TranslationBlockDiffMerger.mergeBlocks(
            existing = emptyList(),
            incoming = listOf(
                TranslationBlockDiffMerger.IncomingBlock(
                    key = "Existing paragraph",
                    rendered = "既存の段落",
                    left = 40f,
                    top = 300f,
                    right = 1000f,
                    bottom = 420f
                )
            ),
            viewportTopPx = 200,
            viewportBottomPx = 2200,
            viewportHeightPx = 2400,
            removeUnseen = true,
            animateNewBlocks = false,
            nowUptimeMs = 1000L
        )
        assertEquals(1, initial.size)
        assertEquals(0L, initial[0].fadeStartUptimeMs)
        assertEquals(1f, initial[0].currentAlpha, 0.001f)

        // Simulate scrolling up by 180px
        initial[0].offsetY = -180f

        val merged = TranslationBlockDiffMerger.mergeBlocks(
            existing = initial,
            incoming = listOf(
                TranslationBlockDiffMerger.IncomingBlock(
                    key = "Existing paragraph",
                    rendered = "既存の段落",
                    left = 40f,
                    top = 120f,
                    right = 1000f,
                    bottom = 240f
                ),
                TranslationBlockDiffMerger.IncomingBlock(
                    key = "Newly revealed paragraph",
                    rendered = "新しく表示された段落",
                    left = 40f,
                    top = 1800f,
                    right = 1000f,
                    bottom = 1920f
                )
            ),
            viewportTopPx = 100,
            viewportBottomPx = 2200,
            viewportHeightPx = 2400,
            removeUnseen = true,
            animateNewBlocks = true,
            nowUptimeMs = 2000L
        )

        assertEquals(2, merged.size)
        val matched = merged.first { it.key == "Existing paragraph" }
        assertEquals(0L, matched.fadeStartUptimeMs)
        assertEquals(1f, matched.currentAlpha, 0.001f)
        assertEquals(0f, matched.offsetY, 0.001f)
        assertEquals(0, matched.missCount)

        val fresh = merged.first { it.key == "Newly revealed paragraph" }
        assertEquals(2000L, fresh.fadeStartUptimeMs)
        assertEquals(0f, fresh.currentAlpha, 0.001f)
        val midFade = TranslationBlockDiffMerger.computeFadeAlpha(fresh.fadeStartUptimeMs, 2060L)
        assertTrue("Ease-out quad mid fade should be > 0.5f, was $midFade", midFade > 0.5f && midFade < 1f)
        assertEquals(1f, TranslationBlockDiffMerger.computeFadeAlpha(fresh.fadeStartUptimeMs, 2120L), 0.001f)
    }

    @Test
    fun mergeBlocks_respectsMissToleranceOfTwoAndRemovesOnThirdMiss() {
        var live = TranslationBlockDiffMerger.mergeBlocks(
            existing = emptyList(),
            incoming = listOf(
                TranslationBlockDiffMerger.IncomingBlock(
                    key = "Visible block",
                    rendered = "表示中のブロック",
                    left = 40f,
                    top = 500f,
                    right = 900f,
                    bottom = 620f
                )
            ),
            viewportTopPx = 200,
            viewportBottomPx = 2200,
            viewportHeightPx = 2400,
            removeUnseen = true,
            animateNewBlocks = false,
            nowUptimeMs = 1000L
        )
        assertEquals(1, live.size)

        // Miss #1 -> retained
        live = TranslationBlockDiffMerger.mergeBlocks(
            existing = live,
            incoming = emptyList(),
            viewportTopPx = 200,
            viewportBottomPx = 2200,
            viewportHeightPx = 2400,
            removeUnseen = true,
            animateNewBlocks = true,
            nowUptimeMs = 1500L
        )
        assertEquals(1, live.size)
        assertEquals(1, live[0].missCount)

        // Miss #2 -> retained (MISS_TOLERANCE = 2)
        live = TranslationBlockDiffMerger.mergeBlocks(
            existing = live,
            incoming = emptyList(),
            viewportTopPx = 200,
            viewportBottomPx = 2200,
            viewportHeightPx = 2400,
            removeUnseen = true,
            animateNewBlocks = true,
            nowUptimeMs = 2000L
        )
        assertEquals(1, live.size)
        assertEquals(2, live[0].missCount)

        // Miss #3 -> removed!
        live = TranslationBlockDiffMerger.mergeBlocks(
            existing = live,
            incoming = emptyList(),
            viewportTopPx = 200,
            viewportBottomPx = 2200,
            viewportHeightPx = 2400,
            removeUnseen = true,
            animateNewBlocks = true,
            nowUptimeMs = 2500L
        )
        assertTrue(live.isEmpty())
    }

    @Test
    fun mergeBlocks_immediatelyRemovesOffViewportUnmatchedBlocks() {
        val initial = TranslationBlockDiffMerger.mergeBlocks(
            existing = emptyList(),
            incoming = listOf(
                TranslationBlockDiffMerger.IncomingBlock(
                    key = "Scrolled past top",
                    rendered = "上部へスクロール済み",
                    left = 40f,
                    top = 300f,
                    right = 900f,
                    bottom = 420f
                )
            ),
            viewportTopPx = 240,
            viewportBottomPx = 2200,
            viewportHeightPx = 2400,
            removeUnseen = true,
            animateNewBlocks = false,
            nowUptimeMs = 1000L
        )
        // Scroll completely above viewport clipTop (240)
        initial[0].offsetY = -300f

        val afterScroll = TranslationBlockDiffMerger.mergeBlocks(
            existing = initial,
            incoming = emptyList(),
            viewportTopPx = 240,
            viewportBottomPx = 2200,
            viewportHeightPx = 2400,
            removeUnseen = false,
            animateNewBlocks = true,
            nowUptimeMs = 1500L
        )
        assertTrue(afterScroll.isEmpty())
    }

    @Test
    fun blockBounds_overlapRatioOfSelf_accuratelyDetectsAboveAndBelowFortyPercent() {
        val occupied = BlockBounds(100, 200, 500, 300) // 400 x 100

        // 50% horizontal overlap, 100% vertical overlap -> 0.50 > 0.40
        val halfOverlap = BlockBounds(300, 200, 700, 300)
        assertEquals(0.50f, halfOverlap.overlapRatioOfSelf(occupied), 0.001f)
        assertTrue(halfOverlap.overlapRatioOfSelf(occupied) > 0.40f)

        // 25% horizontal overlap, 100% vertical overlap -> 0.25 <= 0.40
        val quarterOverlap = BlockBounds(400, 200, 800, 300)
        assertEquals(0.25f, quarterOverlap.overlapRatioOfSelf(occupied), 0.001f)
        assertFalse(quarterOverlap.overlapRatioOfSelf(occupied) > 0.40f)

        // Disjoint -> 0.0f
        val disjoint = BlockBounds(600, 400, 900, 500)
        assertEquals(0.0f, disjoint.overlapRatioOfSelf(occupied), 0.001f)
    }
}
