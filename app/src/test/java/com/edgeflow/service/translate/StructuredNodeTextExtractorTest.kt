package com.edgeflow.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredNodeTextExtractorTest {

    @Test
    fun extractFromTree_collectsVisibleContentAndSuppressesParentDuplicates() {
        val root = StructuredTextNode(
            text = "Breaking News Technology update",
            bounds = BlockBounds(0, 0, 1080, 2400),
            children = listOf(
                StructuredTextNode(
                    text = "Breaking News",
                    bounds = BlockBounds(40, 220, 960, 300)
                ),
                StructuredTextNode(
                    text = "Technology update",
                    bounds = BlockBounds(40, 340, 960, 420)
                )
            )
        )

        val blocks = StructuredNodeTextExtractor.extractFromTree(
            root = root,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            statusBarGuardPx = 110,
            navBarGuardPx = 120,
            controlPillBounds = null
        )

        assertEquals(2, blocks.size)
        assertEquals("Breaking News", blocks[0].text)
        assertEquals("Technology update", blocks[1].text)
        assertTrue(StructuredNodeTextExtractor.isRichEnoughToSkipOcr(blocks))
    }

    @Test
    fun extractFromTree_filtersInvisiblePasswordFocusedEditableAndSystemRegions() {
        val pillBounds = BlockBounds(900, 1000, 1060, 1140)
        val root = StructuredTextNode(
            text = "",
            bounds = BlockBounds(0, 0, 1080, 2400),
            children = listOf(
                // Status bar text -> excluded
                StructuredTextNode(
                    text = "Carrier 5G",
                    bounds = BlockBounds(20, 10, 300, 70)
                ),
                // Nav bar text -> excluded
                StructuredTextNode(
                    text = "Navigation",
                    bounds = BlockBounds(100, 2310, 500, 2390)
                ),
                // Overlaps control pill -> excluded
                StructuredTextNode(
                    text = "Behind pill",
                    bounds = BlockBounds(880, 1010, 1040, 1120)
                ),
                // Invisible -> excluded
                StructuredTextNode(
                    text = "Hidden label",
                    bounds = BlockBounds(40, 400, 500, 480),
                    isVisibleToUser = false
                ),
                // Password -> excluded
                StructuredTextNode(
                    text = "MySecretPassword",
                    bounds = BlockBounds(40, 500, 500, 580),
                    isPassword = true
                ),
                // Actively focused editable input -> excluded
                StructuredTextNode(
                    text = "Typing message",
                    bounds = BlockBounds(40, 600, 500, 680),
                    isEditable = true,
                    isFocused = true
                ),
                // Noise digits -> excluded
                StructuredTextNode(
                    text = "12:34",
                    bounds = BlockBounds(40, 700, 200, 760)
                ),
                // Real visible article paragraph -> kept
                StructuredTextNode(
                    text = "Android accessibility node extraction avoids OCR garbling.",
                    bounds = BlockBounds(40, 820, 980, 940)
                )
            )
        )

        val blocks = StructuredNodeTextExtractor.extractFromTree(
            root = root,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            statusBarGuardPx = 110,
            navBarGuardPx = 120,
            controlPillBounds = pillBounds
        )

        assertEquals(1, blocks.size)
        assertEquals(
            "Android accessibility node extraction avoids OCR garbling.",
            blocks[0].text
        )
        assertTrue(StructuredNodeTextExtractor.isRichEnoughToSkipOcr(blocks))
    }

    @Test
    fun isRichEnoughToSkipOcr_requiresTwoBlocksOrSixteenChars() {
        val singleShort = listOf(RawOcrBlock(BlockBounds(20, 200, 200, 260), "Menu"))
        assertFalse(StructuredNodeTextExtractor.isRichEnoughToSkipOcr(singleShort))

        val singleLong = listOf(
            RawOcrBlock(BlockBounds(20, 200, 700, 260), "Welcome to EdgeFlow")
        )
        assertTrue(StructuredNodeTextExtractor.isRichEnoughToSkipOcr(singleLong))

        val twoShort = listOf(
            RawOcrBlock(BlockBounds(20, 200, 200, 260), "Play"),
            RawOcrBlock(BlockBounds(20, 300, 200, 360), "Help")
        )
        assertTrue(StructuredNodeTextExtractor.isRichEnoughToSkipOcr(twoShort))
    }

    @Test
    fun extractFromTreeWithViewport_clipsHiddenNodesBehindBrowserAddressBar() {
        val root = StructuredTextNode(
            text = "",
            bounds = BlockBounds(0, 0, 1080, 2400),
            children = listOf(
                // Main WebView scroll container starting below Chrome address bar (top = 240)
                StructuredTextNode(
                    text = "",
                    bounds = BlockBounds(0, 240, 1080, 2280),
                    isScrollable = true,
                    className = "android.webkit.WebView",
                    children = listOf(
                        // Completely behind address bar -> must be dropped
                        StructuredTextNode(
                            text = "Hidden scrolled header",
                            bounds = BlockBounds(40, 120, 960, 200)
                        ),
                        // Mostly behind address bar (centerY = 225 < 240) -> must be dropped
                        StructuredTextNode(
                            text = "Partially hidden behind toolbar",
                            bounds = BlockBounds(40, 200, 960, 250)
                        ),
                        // Visible heading inside viewport -> kept
                        StructuredTextNode(
                            text = "Mass media",
                            bounds = BlockBounds(40, 280, 620, 360)
                        ),
                        // Visible body paragraph inside viewport -> kept
                        StructuredTextNode(
                            text = "Mass media encompasses a diverse array of technologies.",
                            bounds = BlockBounds(40, 420, 1020, 540)
                        ),
                        // Completely below viewport -> pruned
                        StructuredTextNode(
                            text = "Offscreen footer text",
                            bounds = BlockBounds(40, 2320, 900, 2400)
                        )
                    )
                )
            )
        )

        val result = StructuredNodeTextExtractor.extractFromTreeWithViewport(
            root = root,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            statusBarGuardPx = 90,
            navBarGuardPx = 120,
            controlPillBounds = null
        )

        assertEquals(240, result.contentClipTopPx)
        assertEquals(2280, result.contentClipBottomPx)
        assertEquals(2, result.blocks.size)
        assertEquals("Mass media", result.blocks[0].text)
        assertEquals("Mass media encompasses a diverse array of technologies.", result.blocks[1].text)
    }

    @Test
    fun extractFromTree_neverAdoptsContentDescriptionOnImageOrWebView() {
        val root = StructuredTextNode(
            text = "",
            bounds = BlockBounds(0, 0, 1080, 2400),
            children = listOf(
                StructuredTextNode(
                    text = "",
                    contentDescription = "Photo of golden retriever sitting in grass",
                    className = "android.widget.ImageView",
                    bounds = BlockBounds(60, 300, 980, 900)
                ),
                StructuredTextNode(
                    text = "",
                    contentDescription = "Banner WebView Description",
                    className = "android.webkit.WebView",
                    bounds = BlockBounds(60, 920, 980, 1100)
                ),
                StructuredTextNode(
                    text = "",
                    contentDescription = "Share Icon",
                    className = "android.widget.ImageButton",
                    bounds = BlockBounds(60, 1120, 200, 1200)
                ),
                StructuredTextNode(
                    text = "Real article caption below photo",
                    className = "android.widget.TextView",
                    bounds = BlockBounds(60, 1240, 920, 1320)
                )
            )
        )

        val blocks = StructuredNodeTextExtractor.extractFromTree(
            root = root,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            statusBarGuardPx = 90,
            navBarGuardPx = 120,
            controlPillBounds = null
        )

        assertEquals(1, blocks.size)
        assertEquals("Real article caption below photo", blocks[0].text)
    }

    @Test
    fun isBrowserPackage_identifiesCommonBrowsersAndExcludesNativeApps() {
        assertTrue(StructuredNodeTextExtractor.isBrowserPackage("com.android.chrome"))
        assertTrue(StructuredNodeTextExtractor.isBrowserPackage("org.mozilla.firefox"))
        assertTrue(StructuredNodeTextExtractor.isBrowserPackage("com.microsoft.emmx"))
        assertTrue(StructuredNodeTextExtractor.isBrowserPackage("com.brave.browser"))
        assertTrue(StructuredNodeTextExtractor.isBrowserPackage("com.sec.android.app.sbrowser"))
        assertTrue(StructuredNodeTextExtractor.isBrowserPackage("com.mi.globalbrowser"))
        assertFalse(StructuredNodeTextExtractor.isBrowserPackage("com.whatsapp"))
        assertFalse(StructuredNodeTextExtractor.isBrowserPackage("com.android.settings"))
    }
}
