package com.edgeflow.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingAndCtsHelpersTest {

    @Test
    fun controlCenterFloatingMacro_matchesAllFourLocaleTileKeywords() {
        assertTrue(ControlCenterFloatingMacro.matchesFloatingWindowTileLabel("フローティングウィンドウ", null))
        assertTrue(ControlCenterFloatingMacro.matchesFloatingWindowTileLabel(null, "フローティング ウィンドウ"))
        assertTrue(ControlCenterFloatingMacro.matchesFloatingWindowTileLabel("Floating windows", null))
        assertTrue(ControlCenterFloatingMacro.matchesFloatingWindowTileLabel(null, "Floating Window"))
        assertTrue(ControlCenterFloatingMacro.matchesFloatingWindowTileLabel("小窗", null))
        assertTrue(ControlCenterFloatingMacro.matchesFloatingWindowTileLabel("悬浮窗", null))
        assertTrue(ControlCenterFloatingMacro.matchesFloatingWindowTileLabel("फ़्लोटिंग विंडो", null))

        assertFalse(ControlCenterFloatingMacro.matchesFloatingWindowTileLabel("Wi-Fi", "Bluetooth"))
        assertFalse(ControlCenterFloatingMacro.matchesFloatingWindowTileLabel("", null))
        assertFalse(ControlCenterFloatingMacro.matchesFloatingWindowTileLabel("Auto-rotate", "Flashlight"))
    }

    @Test
    fun circleToSearchTrigger_evaluatesVoidBooleanAndIntReturnValuesAccurately() {
        assertTrue(CircleToSearchTrigger.isSuccessfulShowSessionReturn(null, Void.TYPE))
        assertTrue(CircleToSearchTrigger.isSuccessfulShowSessionReturn(null, Void::class.java))
        assertTrue(CircleToSearchTrigger.isSuccessfulShowSessionReturn(true, Boolean::class.javaPrimitiveType!!))
        assertFalse(CircleToSearchTrigger.isSuccessfulShowSessionReturn(false, Boolean::class.javaPrimitiveType!!))
        assertTrue(CircleToSearchTrigger.isSuccessfulShowSessionReturn(0, Int::class.javaPrimitiveType!!))
        assertTrue(CircleToSearchTrigger.isSuccessfulShowSessionReturn(1, Int::class.javaPrimitiveType!!))
        assertFalse(CircleToSearchTrigger.isSuccessfulShowSessionReturn(-1, Int::class.javaPrimitiveType!!))
    }

    @Test
    fun controlCenterFloatingMacro_matchesOcrFloatingKeywordsAcrossLocales() {
        assertTrue(ControlCenterFloatingMacro.matchesOcrFloatingKeyword("Floating windows"))
        assertTrue(ControlCenterFloatingMacro.matchesOcrFloatingKeyword("フローティング"))
        assertTrue(ControlCenterFloatingMacro.matchesOcrFloatingKeyword("小窓"))
        assertTrue(ControlCenterFloatingMacro.matchesOcrFloatingKeyword("小窗"))
        assertTrue(ControlCenterFloatingMacro.matchesOcrFloatingKeyword("悬浮窗"))
        assertTrue(ControlCenterFloatingMacro.matchesOcrFloatingKeyword("फ़्लोटिंग विंडो"))
        assertFalse(ControlCenterFloatingMacro.matchesOcrFloatingKeyword("Bluetooth"))
        assertFalse(ControlCenterFloatingMacro.matchesOcrFloatingKeyword("Flashlight"))
    }

    @Test
    fun controlCenterFloatingMacro_detectsFloatingWindowBoundsBySizeAndEdgeMargins() {
        val screenW = 1080
        val screenH = 2400

        // Fullscreen window (and fullscreen inset only by standard status/nav bars) -> false
        assertFalse(ControlCenterFloatingMacro.isFloatingWindowBounds(0, 0, 1080, 2400, screenW, screenH))
        assertFalse(ControlCenterFloatingMacro.isFloatingWindowBounds(0, 110, 1080, 2320, screenW, screenH))

        // Standard smaller freeform window -> true
        assertTrue(ControlCenterFloatingMacro.isFloatingWindowBounds(100, 300, 920, 1700, screenW, screenH))

        // HyperOS 3 scaled floating window reporting near-full dimensions but shifted from screen edges -> true
        assertTrue(ControlCenterFloatingMacro.isFloatingWindowBounds(24, 0, 1080, 2400, screenW, screenH))
        assertTrue(ControlCenterFloatingMacro.isFloatingWindowBounds(0, 180, 1080, 2400, screenW, screenH))
        assertTrue(ControlCenterFloatingMacro.isFloatingWindowBounds(0, 0, 1050, 2400, screenW, screenH))
        assertTrue(ControlCenterFloatingMacro.isFloatingWindowBounds(0, 0, 1080, 2240, screenW, screenH))

        // Degenerate/tiny window -> false
        assertFalse(ControlCenterFloatingMacro.isFloatingWindowBounds(200, 200, 280, 280, screenW, screenH))
    }
}
