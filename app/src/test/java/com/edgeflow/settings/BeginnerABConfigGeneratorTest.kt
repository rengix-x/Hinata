package com.edgeflow.settings

import com.edgeflow.data.EdgeAction
import com.edgeflow.data.EdgeActionType
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.FeedbackStyle
import com.edgeflow.data.QuickArcTriggerMethod
import com.edgeflow.data.SecondaryTriggerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeginnerABConfigGeneratorTest {

    @Test
    fun featureToggles_disableQuickMenuRecentSwitcherBottomBarThreeStageAndLiquidGlass() {
        val base = EdgeConfig(
            horizontalHoldAction = EdgeAction.OPEN_QUICK_MENU,
            diagonalUpAction = EdgeAction.OPEN_QUICK_MENU,
            diagonalDownAction = EdgeAction.OPEN_QUICK_MENU,
            leftStage3Action = EdgeAction.SCREENSHOT,
            rightStage3Action = EdgeAction.LOCK_SCREEN,
            bottomStage3Action = EdgeAction.NOTIFICATIONS,
            bottomEnabled = true,
            liquidGlassEnabled = true
        )

        val generated = BeginnerABConfigGenerator.generate(
            baseConfig = base,
            features = BeginnerABFeatureSelection(
                useQuickMenu = false,
                useRecentSwitcher = false,
                useBottomBar = false,
                useThreeStage = false,
                useLiquidGlass = false
            ),
            answers = BeginnerABAnswers()
        )

        assertEquals(QuickArcTriggerMethod.GESTURE_ACTION, generated.quickArcTriggerMethod)
        assertEquals(EdgeAction.PREVIOUS_APP, generated.horizontalHoldAction)
        assertEquals(EdgeAction.HOME, generated.diagonalUpAction)
        assertEquals(EdgeAction.NOTIFICATIONS, generated.diagonalDownAction)
        assertEquals(EdgeAction.RECENTS, generated.bottomSwipeHoldAction)
        assertFalse(generated.bottomEnabled)
        assertTrue(generated.leftStage3Action.isNone)
        assertTrue(generated.rightStage3Action.isNone)
        assertTrue(generated.bottomStage3Action.isNone)
        assertFalse(generated.liquidGlassEnabled)
    }

    @Test
    fun featureToggles_enableThreeStagePopulatesSensibleStage3Actions() {
        val base = EdgeConfig(
            leftStage3Action = EdgeAction.NONE,
            rightStage3Action = EdgeAction.NONE,
            bottomStage3Action = EdgeAction.NONE
        )

        val generated = BeginnerABConfigGenerator.generate(
            baseConfig = base,
            features = BeginnerABFeatureSelection(
                useQuickMenu = true,
                useRecentSwitcher = true,
                useBottomBar = true,
                useThreeStage = true,
                useLiquidGlass = true
            ),
            answers = BeginnerABAnswers()
        )

        assertEquals(QuickArcTriggerMethod.DEDICATED_ZONE, generated.quickArcTriggerMethod)
        assertEquals(EdgeAction.OPEN_RECENT_SWITCHER, generated.bottomSwipeHoldAction)
        assertTrue(generated.bottomEnabled)
        assertFalse(generated.leftStage3Action.isNone)
        assertFalse(generated.rightStage3Action.isNone)
        assertFalse(generated.bottomStage3Action.isNone)
        assertTrue(generated.liquidGlassEnabled)
    }

    @Test
    fun abChoices_mapSlimVsWide_FlushVsCushioned_DropletVsMinimal_DistanceVsHold() {
        val base = EdgeConfig()

        val choiceA = BeginnerABConfigGenerator.generate(
            baseConfig = base,
            features = BeginnerABFeatureSelection(useLiquidGlass = true),
            answers = BeginnerABAnswers(
                sideWidthChoice = ABChoice.A,
                bottomComfortChoice = ABChoice.A,
                visualSurfaceChoice = ABChoice.A,
                visualShapeChoice = ABChoice.A,
                stage2ModeChoice = ABChoice.A
            )
        )
        assertEquals(14f, choiceA.edgeWidthDp, 0.01f)
        assertEquals(20f, choiceA.bottomBarHeightDp, 0.01f)
        assertEquals(0f, choiceA.bottomBarBottomOffsetDp, 0.01f)
        assertTrue(choiceA.liquidGlassEnabled)
        assertEquals(FeedbackStyle.BEZIER_BUBBLE, choiceA.feedbackStyle)
        assertEquals(SecondaryTriggerMode.DISTANCE, choiceA.sideTriggerMode)
        assertEquals(SecondaryTriggerMode.DISTANCE, choiceA.bottomTriggerMode)

        val choiceB = BeginnerABConfigGenerator.generate(
            baseConfig = base,
            features = BeginnerABFeatureSelection(useLiquidGlass = true),
            answers = BeginnerABAnswers(
                sideWidthChoice = ABChoice.B,
                bottomComfortChoice = ABChoice.B,
                visualSurfaceChoice = ABChoice.B,
                visualShapeChoice = ABChoice.B,
                stage2ModeChoice = ABChoice.B
            )
        )
        assertEquals(24f, choiceB.edgeWidthDp, 0.01f)
        assertEquals(28f, choiceB.bottomBarHeightDp, 0.01f)
        assertEquals(6f, choiceB.bottomBarBottomOffsetDp, 0.01f)
        assertFalse(choiceB.liquidGlassEnabled)
        assertEquals(FeedbackStyle.MINIMAL_ARROW, choiceB.feedbackStyle)
        assertEquals(SecondaryTriggerMode.HOLD, choiceB.sideTriggerMode)
        assertEquals(SecondaryTriggerMode.HOLD, choiceB.bottomTriggerMode)
    }

    @Test
    fun liveThumbTelemetry_calibratesBottomOffsetAndSwipeThreshold() {
        val base = EdgeConfig()

        val lowThumb = BeginnerABConfigGenerator.generate(
            baseConfig = base,
            features = BeginnerABFeatureSelection(),
            answers = BeginnerABAnswers(),
            sample = BeginnerABSample(
                thumbYRatioSamples = listOf(0.82f, 0.80f, 0.84f),
                swipeDistanceDpSamples = listOf(64f, 68f, 62f)
            )
        )
        val highThumb = BeginnerABConfigGenerator.generate(
            baseConfig = base,
            features = BeginnerABFeatureSelection(),
            answers = BeginnerABAnswers(),
            sample = BeginnerABSample(
                thumbYRatioSamples = listOf(0.32f, 0.35f, 0.30f),
                swipeDistanceDpSamples = listOf(110f, 108f, 112f)
            )
        )

        assertTrue(
            "Lower thumb Y ratio (near bottom) should produce smaller edgeBottomOffsetPercent than higher thumb",
            lowThumb.edgeBottomOffsetPercent < highThumb.edgeBottomOffsetPercent
        )
        assertTrue(
            "Shorter natural swipe distance should calibrate a smaller swipeThresholdDp than longer swipes",
            lowThumb.swipeThresholdDp < highThumb.swipeThresholdDp
        )
        assertTrue(lowThumb.swipeThresholdDp in 22f..40f)
        assertTrue(highThumb.swipeThresholdDp in 22f..40f)
    }
}
