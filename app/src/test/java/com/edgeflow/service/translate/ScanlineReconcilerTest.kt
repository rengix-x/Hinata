package com.edgeflow.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanlineReconcilerTest {

    @Test
    fun textSimilarity_handlesExactJitterAndDissimilarStrings() {
        assertEquals(1f, ScanlineReconciler.textSimilarity("hello world", "hello world"), 0.001f)
        assertEquals(0f, ScanlineReconciler.textSimilarity("", "hello"), 0.001f)
        assertEquals(1f, ScanlineReconciler.textSimilarity("a", "a"), 0.001f)
        assertEquals(0f, ScanlineReconciler.textSimilarity("a", "b"), 0.001f)

        // Slight OCR punctuation jitter still exceeds 0.75 threshold
        val jitterSim = ScanlineReconciler.textSimilarity(
            "welcome to the wikipedia encyclopedia",
            "welcome to the wikipedia encyclopedia."
        )
        assertTrue("Expected >= 0.75, got $jitterSim", jitterSim >= ScanlineReconciler.TEXT_SIMILARITY_THRESHOLD)

        val differentSim = ScanlineReconciler.textSimilarity("breaking news", "settings menu")
        assertTrue("Expected < 0.75, got $differentSim", differentSim < ScanlineReconciler.TEXT_SIMILARITY_THRESHOLD)
    }

    @Test
    fun reconcile_matchesKeptImmediatelyPurgesUnmatchedAndQueuesOnlyNew() {
        val active = listOf(
            ActiveTrackedBlock(
                id = 1L,
                originalText = "Top headline",
                normalizedOriginal = "top headline",
                currentBounds = BlockBounds(40, 200, 700, 260)
            ),
            ActiveTrackedBlock(
                id = 2L,
                originalText = "First body paragraph",
                normalizedOriginal = "first body paragraph",
                currentBounds = BlockBounds(40, 320, 960, 440)
            )
        )

        val fresh = listOf(
            // Block 2 shifted slightly with minor OCR jitter -> kept (1:1 match, no re-translate!)
            FilteredOcrBlock(
                bounds = BlockBounds(42, 324, 960, 444),
                text = "First body paragraph."
            ),
            // Newly scrolled-in block -> queued in toTranslate
            FilteredOcrBlock(
                bounds = BlockBounds(40, 520, 960, 640),
                text = "Second newly visible paragraph"
            )
        )

        val result = ScanlineReconciler.reconcile(active, fresh)

        // Block 1 disappeared -> immediately removed (MISS_TOLERANCE = 0)
        assertEquals(0, ScanlineReconciler.MISS_TOLERANCE)
        assertEquals(setOf(1L), result.removedIds)

        // Block 2 matched cleanly without significant bounds change
        assertEquals(1, result.kept.size)
        assertEquals(2L, result.kept[0].id)
        assertFalse(result.kept[0].boundsChangedSignificantly)

        // Only the newly appeared block needs translation
        assertEquals(1, result.toTranslate.size)
        assertEquals("Second newly visible paragraph", result.toTranslate[0].text)
    }

    @Test
    fun reconcile_flagsSignificantBoundsChangeOnKeptBlock() {
        val active = listOf(
            ActiveTrackedBlock(
                id = 10L,
                originalText = "Article summary",
                normalizedOriginal = "article summary",
                currentBounds = BlockBounds(40, 300, 600, 360)
            )
        )
        val fresh = listOf(
            FilteredOcrBlock(
                bounds = BlockBounds(40, 328, 600, 388),
                text = "Article summary"
            )
        )

        val result = ScanlineReconciler.reconcile(active, fresh)
        assertEquals(1, result.kept.size)
        assertTrue(result.kept[0].boundsChangedSignificantly)
        assertTrue(result.removedIds.isEmpty())
        assertTrue(result.toTranslate.isEmpty())
    }
}
