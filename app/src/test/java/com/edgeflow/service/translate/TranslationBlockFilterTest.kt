package com.edgeflow.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBlockFilterTest {

    @Test
    fun noiseFilterRejectsUrlsNumbersPunctuationAndSingleLatinChar() {
        assertTrue(TranslationBlockFilter.isNoiseText(""))
        assertTrue(TranslationBlockFilter.isNoiseText("   "))
        assertTrue(TranslationBlockFilter.isNoiseText("12:34"))
        assertTrue(TranslationBlockFilter.isNoiseText("99.5%"))
        assertTrue(TranslationBlockFilter.isNoiseText("$1,250.00"))
        assertTrue(TranslationBlockFilter.isNoiseText("https://example.com/news"))
        assertTrue(TranslationBlockFilter.isNoiseText("example.jp/article"))
        assertTrue(TranslationBlockFilter.isNoiseText("A"))
        assertTrue(TranslationBlockFilter.isNoiseText("..."))

        // Single CJK kanji/kana & real words must pass
        assertFalse(TranslationBlockFilter.isNoiseText("開"))
        assertFalse(TranslationBlockFilter.isNoiseText("Play"))
        assertFalse(TranslationBlockFilter.isNoiseText("Hello world"))
    }

    @Test
    fun excludesStatusBarNavBarAndFloatingControlPill() {
        val raw = listOf(
            RawOcrBlock(BlockBounds(20, 10, 200, 50), "Status text"),
            RawOcrBlock(BlockBounds(20, 2320, 300, 2380), "Nav label"),
            RawOcrBlock(BlockBounds(900, 900, 1050, 980), "Pill overlap"),
            RawOcrBlock(BlockBounds(60, 400, 700, 460), "Real dialogue line")
        )
        val filtered = TranslationBlockFilter.filterAndMerge(
            rawBlocks = raw,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            statusBarGuardPx = 90,
            navBarGuardPx = 120,
            controlPillBounds = BlockBounds(880, 880, 1070, 1000),
            targetLang = "ja",
            skipTargetLanguage = true
        )
        assertEquals(1, filtered.size)
        assertEquals("Real dialogue line", filtered[0].text)
    }

    @Test
    fun skipsAlreadyTargetLanguageWhenEnabled() {
        assertTrue(TranslationBlockFilter.isAlreadyTargetLanguageByScript("これは日本語のテキストです", "ja"))
        assertFalse(TranslationBlockFilter.isAlreadyTargetLanguageByScript("This is English text", "ja"))
        assertTrue(TranslationBlockFilter.isAlreadyTargetLanguageByScript("这是中文段落", "zh"))
        assertTrue(TranslationBlockFilter.isAlreadyTargetLanguageByScript("한국어 문장입니다", "ko"))
        assertTrue(TranslationBlockFilter.isAlreadyTargetLanguageByScript("यह हिंदी पाठ है", "hi"))

        assertTrue(TranslationBlockFilter.shouldSkipByDetectedLanguage("ja-JP", 0.85f, "ja"))
        assertFalse(TranslationBlockFilter.shouldSkipByDetectedLanguage("ja", 0.40f, "ja"))
        assertFalse(TranslationBlockFilter.shouldSkipByDetectedLanguage("und", 0.99f, "ja"))
        assertFalse(TranslationBlockFilter.shouldSkipByDetectedLanguage("en", 0.95f, "ja"))
    }

    @Test
    fun mergesConsecutiveParagraphLinesButKeepsSeparateColumnsDistinct() {
        val raw = listOf(
            RawOcrBlock(BlockBounds(80, 300, 820, 350), "Welcome to the guild hall,"),
            RawOcrBlock(BlockBounds(80, 362, 790, 412), "traveler from afar."),
            RawOcrBlock(BlockBounds(80, 620, 500, 670), "Separate paragraph below")
        )
        val filtered = TranslationBlockFilter.filterAndMerge(
            rawBlocks = raw,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            statusBarGuardPx = 80,
            navBarGuardPx = 100,
            controlPillBounds = null,
            targetLang = "ja",
            skipTargetLanguage = true
        )
        assertEquals(2, filtered.size)
        assertEquals("Welcome to the guild hall, traveler from afar.", filtered[0].text)
        assertEquals(BlockBounds(80, 300, 820, 412), filtered[0].bounds)
        assertEquals(2, filtered[0].lineCount)
        assertEquals(50, filtered[0].averageLineHeightPx)
        assertEquals("Separate paragraph below", filtered[1].text)
    }

    @Test
    fun mergesFragmentedInlineLinksOnSameLineIncludingSingleLetterArticles() {
        val raw = listOf(
            RawOcrBlock(BlockBounds(40, 300, 72, 344), "A"),
            RawOcrBlock(BlockBounds(80, 300, 430, 344), "family listening to the"),
            RawOcrBlock(BlockBounds(440, 298, 540, 346), "radio"),
            RawOcrBlock(BlockBounds(550, 300, 620, 344), "in"),
            RawOcrBlock(BlockBounds(40, 420, 390, 464), "This week's article is"),
            RawOcrBlock(BlockBounds(400, 420, 495, 464), "mass"),
            RawOcrBlock(BlockBounds(505, 420, 620, 464), "media")
        )
        val filtered = TranslationBlockFilter.filterAndMerge(
            rawBlocks = raw,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            statusBarGuardPx = 80,
            navBarGuardPx = 100,
            controlPillBounds = null,
            targetLang = "ja",
            skipTargetLanguage = true
        )
        assertEquals(2, filtered.size)
        assertEquals("A family listening to the radio in", filtered[0].text)
        assertEquals("This week's article is mass media", filtered[1].text)
    }

    @Test
    fun mergesWideLineSpacingParagraphAndSeparatesBulletItems() {
        // Line height = 40px, vertical gap = 38px (0.95 * lineHeight <= 1.18 * lineHeight)
        val raw = listOf(
            RawOcrBlock(BlockBounds(60, 300, 960, 340), "Wikipedia articles often use generous"),
            RawOcrBlock(BlockBounds(60, 378, 940, 418), "line spacing across mobile browsers"),
            RawOcrBlock(BlockBounds(60, 456, 260, 496), "sections"),
            // Bullet list right below -> must NOT merge into the paragraph above
            RawOcrBlock(BlockBounds(60, 520, 800, 560), "• First bullet item"),
            RawOcrBlock(BlockBounds(60, 580, 800, 620), "• Second bullet item")
        )
        val filtered = TranslationBlockFilter.filterAndMerge(
            rawBlocks = raw,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            statusBarGuardPx = 80,
            navBarGuardPx = 100,
            controlPillBounds = null,
            targetLang = "ja",
            skipTargetLanguage = true
        )
        assertEquals(3, filtered.size)
        assertEquals(
            "Wikipedia articles often use generous line spacing across mobile browsers sections",
            filtered[0].text
        )
        assertEquals(3, filtered[0].lineCount)
        assertEquals(40, filtered[0].averageLineHeightPx)
        assertEquals("• First bullet item", filtered[1].text)
        assertEquals("• Second bullet item", filtered[2].text)
    }

    @Test
    fun keepsDistinctGroupColumnsAndWideHorizontalGapsSeparate() {
        // Two side-by-side Wikipedia infobox columns on the same horizontal row
        // Height = 36px; gap = 90px (> 1.10 * 36) AND distinct groupIds (0 vs 1)
        val raw = listOf(
            RawOcrBlock(BlockBounds(60, 300, 260, 336), "Born", groupId = 0),
            RawOcrBlock(BlockBounds(350, 300, 760, 336), "January 1, 1990", groupId = 1),
            RawOcrBlock(BlockBounds(60, 370, 260, 406), "Occupation", groupId = 2),
            RawOcrBlock(BlockBounds(350, 370, 760, 406), "Software Engineer", groupId = 3)
        )
        val filtered = TranslationBlockFilter.filterAndMerge(
            rawBlocks = raw,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            statusBarGuardPx = 80,
            navBarGuardPx = 100,
            controlPillBounds = null,
            targetLang = "ja",
            skipTargetLanguage = true
        )
        assertEquals(4, filtered.size)
        assertEquals("Born", filtered[0].text)
        assertEquals("January 1, 1990", filtered[1].text)
        assertEquals("Occupation", filtered[2].text)
        assertEquals("Software Engineer", filtered[3].text)
    }
}
