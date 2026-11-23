package com.edgeflow.service

import com.edgeflow.data.EdgeAction
import com.edgeflow.data.EdgeActionType
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.EdgeSide
import com.edgeflow.data.EdgeVisualStyle
import com.edgeflow.data.FeedbackStyle
import com.edgeflow.data.HapticStrength
import com.edgeflow.data.ImeAvoidanceMode
import com.edgeflow.data.LandscapeMode
import com.edgeflow.data.QuickArcTriggerMethod
import com.edgeflow.data.QuickArcZoneSide
import com.edgeflow.data.QuickMenuRetreatMode
import com.edgeflow.data.QuickSlotConfig
import com.edgeflow.data.SecondaryTriggerMode
import com.edgeflow.data.VisualTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDetectorEngineTest {

    // Density = 3f (480dpi / POCO F7): 28dp = 84px, 14dp hysteresis = 42px
    // Screen = 1080x2400 -> side 28% = 302.4px, bottom 20% = 480px
    private val density = 3f
    private val screenW = 1080
    private val screenH = 2400

    @Test
    fun shortTap_doesNotMisfire() {
        val engine = GestureDetectorEngine(EdgeSide.LEFT, density, screenW, screenH)
        engine.onDown(10f, 500f, 1000L)
        val move = engine.onMove(25f, 502f, 1020L)
        assertEquals(GestureState.TRACKING, move.state)
        assertEquals(GestureHapticEvent.NONE, move.hapticEvent)
        assertEquals(GestureCommitType.NONE, move.commitType)

        val up = engine.onUp(25f, 502f, 1040L)
        assertEquals(GestureCommitType.NONE, up.commitType)
        assertEquals(EdgeAction.NONE, up.activeAction)
    }

    @Test
    fun verticalBezelScroll_isCancelledAndNeverMisfires() {
        val engine = GestureDetectorEngine(EdgeSide.RIGHT, density, screenW, screenH)
        engine.onDown(1070f, 800f, 1000L)
        val move1 = engine.onMove(1065f, 860f, 1020L)
        assertEquals(GestureState.CANCELLED, move1.state)

        val move2 = engine.onMove(900f, 860f, 1040L)
        assertEquals(GestureState.CANCELLED, move2.state)

        val up = engine.onUp(900f, 860f, 1060L)
        assertEquals(GestureCommitType.NONE, up.commitType)
    }

    @Test
    fun leftEdge_shortHorizontalSwipe_preparesPrimaryWithoutMidDragFireAndExecutesOnUp() {
        val engine = GestureDetectorEngine(EdgeSide.LEFT, density, screenW, screenH)
        engine.updateConfig(
            EdgeConfig(sideTriggerMode = SecondaryTriggerMode.BOTH),
            density,
            screenW,
            screenH
        )
        engine.onDown(8f, 600f, 1000L)

        // BOTH mode -> schedules hold fallback while also supporting 0ms distance trigger
        val move = engine.onMove(120f, 605f, 1030L)
        assertEquals(GestureState.PRIMARY_READY, move.state)
        assertEquals(1, move.stageLevel)
        assertEquals(GestureDirection.HORIZONTAL, move.direction)
        assertEquals(EdgeAction.BACK, move.activeAction)
        assertEquals(GestureHapticEvent.PRIMARY_TRIGGERED, move.hapticEvent)
        assertEquals(GestureCommitType.NONE, move.commitType)
        assertTrue(move.shouldScheduleHold)

        val up = engine.onUp(120f, 605f, 1080L)
        assertEquals(GestureCommitType.EXECUTE, up.commitType)
        assertEquals(EdgeAction.BACK, up.activeAction)
    }

    @Test
    fun dedicatedZoneDefault_longHorizontalSwipeExecutesPreviousAppWithoutArcConflict() {
        val engine = GestureDetectorEngine(EdgeSide.RIGHT, density, screenW, screenH)
        engine.updateConfig(
            EdgeConfig(
                quickArcTriggerMethod = QuickArcTriggerMethod.DEDICATED_ZONE,
                sideTriggerMode = SecondaryTriggerMode.BOTH,
                sideStage2DistanceRatio = 0.28f,
                horizontalHoldAction = EdgeAction.PREVIOUS_APP,
                rightStage3Action = EdgeAction.NONE
            ),
            density,
            screenW,
            screenH
        )
        engine.onDown(1075f, 700f, 1000L)

        // Stage 1: inward 105px -> BACK
        val stage1 = engine.onMove(970f, 700f, 1016L)
        assertEquals(GestureState.PRIMARY_READY, stage1.state)
        assertEquals(EdgeAction.BACK, stage1.activeAction)

        // Stage 2: inward >= 28% of 1080px -> PREVIOUS_APP
        val stage2 = engine.onMove(760f, 700f, 1032L)
        assertEquals(MenuHandOffTarget.NONE, stage2.handOffTarget)
        assertEquals(GestureState.SECONDARY_READY, stage2.state)
        assertEquals(2, stage2.stageLevel)
        assertEquals(EdgeAction.PREVIOUS_APP, stage2.activeAction)
        assertEquals(GestureHapticEvent.SECONDARY_TRIGGERED, stage2.hapticEvent)

        val up = engine.onUp(760f, 700f, 1050L)
        assertEquals(GestureCommitType.EXECUTE, up.commitType)
        assertEquals(EdgeAction.PREVIOUS_APP, up.activeAction)
    }

    @Test
    fun splitTriggerModes_sideDistanceAndBottomHoldOperateIndependently() {
        val cfg = EdgeConfig(
            sideTriggerMode = SecondaryTriggerMode.DISTANCE,
            bottomTriggerMode = SecondaryTriggerMode.HOLD,
            sideStage2DistanceRatio = 0.25f,
            bottomStage2DistanceRatio = 0.20f,
            bottomStage2HoldMs = 300L,
            horizontalHoldAction = EdgeAction.PREVIOUS_APP,
            bottomSwipeHoldAction = EdgeAction.RECENTS
        )

        // Side engine (DISTANCE only): promotes by distance, never schedules hold
        val sideEngine = GestureDetectorEngine(EdgeSide.LEFT, density, screenW, screenH)
        sideEngine.updateConfig(cfg, density, screenW, screenH)
        sideEngine.onDown(10f, 700f, 1000L)
        val sideS1 = sideEngine.onMove(120f, 700f, 1016L)
        assertEquals(GestureState.PRIMARY_READY, sideS1.state)
        assertFalse(sideS1.shouldScheduleHold)

        val sideS2 = sideEngine.onMove(10f + screenW * 0.28f, 700f, 1032L)
        assertEquals(GestureState.SECONDARY_READY, sideS2.state)
        assertEquals(EdgeAction.PREVIOUS_APP, sideS2.activeAction)

        // Bottom engine (HOLD only): pulling past 20% distance does NOT promote; hold timeout DOES
        val bottomEngine = GestureDetectorEngine(EdgeSide.BOTTOM, density, screenW, screenH)
        bottomEngine.updateConfig(cfg, density, screenW, screenH)
        bottomEngine.onDown(540f, 2380f, 1000L)
        val bottomDragFar = bottomEngine.onMove(540f, 2380f - screenH * 0.35f, 1032L)
        assertEquals(GestureState.PRIMARY_READY, bottomDragFar.state)
        assertEquals(EdgeAction.HOME, bottomDragFar.activeAction)
        assertTrue(bottomDragFar.shouldScheduleHold)
        assertEquals(300L, bottomDragFar.holdDelayMs)

        val bottomHold = bottomEngine.onHoldTimeout(1332L)
        assertEquals(GestureState.SECONDARY_READY, bottomHold.state)
        assertEquals(EdgeAction.RECENTS, bottomHold.activeAction)
        assertEquals(GestureHapticEvent.SECONDARY_TRIGGERED, bottomHold.hapticEvent)
    }

    @Test
    fun threeStageDistance_promotesThroughStages1To3_andSupportsHysteresisStepDown() {
        val cfg = EdgeConfig(
            sideTriggerMode = SecondaryTriggerMode.DISTANCE,
            sideStage2DistanceRatio = 0.30f,
            sideStage3DistanceRatio = 0.72f, // >50% distance unlocked!
            horizontalAction = EdgeAction.BACK,
            horizontalHoldAction = EdgeAction.PREVIOUS_APP,
            leftStage3Action = EdgeAction.SCREENSHOT
        )
        val engine = GestureDetectorEngine(EdgeSide.LEFT, density, screenW, screenH)
        engine.updateConfig(cfg, density, screenW, screenH)

        val s2Threshold = engine.sideStage2ThresholdPx() // 324px
        val s3Threshold = engine.sideStage3ThresholdPx() // 777.6px
        val hyst = engine.hysteresisMarginPx() // 42px
        assertEquals(screenW * 0.72f, s3Threshold, 1f)

        engine.onDown(10f, 800f, 1000L)

        // Stage 1
        val s1 = engine.onMove(120f, 800f, 1016L)
        assertEquals(GestureState.PRIMARY_READY, s1.state)
        assertEquals(1, s1.stageLevel)
        assertEquals(EdgeAction.BACK, s1.activeAction)
        assertEquals(GestureHapticEvent.PRIMARY_TRIGGERED, s1.hapticEvent)
        assertTrue(s1.hasStage3Configured)

        // Stage 2
        val s2 = engine.onMove(10f + s2Threshold + 12f, 800f, 1032L)
        assertEquals(GestureState.SECONDARY_READY, s2.state)
        assertEquals(2, s2.stageLevel)
        assertEquals(EdgeAction.PREVIOUS_APP, s2.activeAction)
        assertEquals(GestureHapticEvent.SECONDARY_TRIGGERED, s2.hapticEvent)

        // Stage 3 (>50% reach!)
        val s3 = engine.onMove(10f + s3Threshold + 12f, 800f, 1048L)
        assertEquals(GestureState.TERTIARY_READY, s3.state)
        assertEquals(3, s3.stageLevel)
        assertEquals(EdgeAction.SCREENSHOT, s3.activeAction)
        assertEquals(GestureHapticEvent.TERTIARY_TRIGGERED, s3.hapticEvent)

        // Small pullback inside 14dp hysteresis band stays in Stage 3
        val stayS3 = engine.onMove(10f + s3Threshold - hyst * 0.4f, 800f, 1064L)
        assertEquals(GestureState.TERTIARY_READY, stayS3.state)
        assertEquals(EdgeAction.SCREENSHOT, stayS3.activeAction)

        // Pull back past Stage 3 hysteresis -> demotes cleanly to Stage 2
        val demoteS2 = engine.onMove(10f + s3Threshold - hyst - 10f, 800f, 1080L)
        assertEquals(GestureState.SECONDARY_READY, demoteS2.state)
        assertEquals(2, demoteS2.stageLevel)
        assertEquals(EdgeAction.PREVIOUS_APP, demoteS2.activeAction)
        assertEquals(GestureHapticEvent.SECONDARY_TRIGGERED, demoteS2.hapticEvent)

        // Pull back past Stage 2 hysteresis -> demotes cleanly to Stage 1
        val demoteS1 = engine.onMove(10f + s2Threshold - hyst - 10f, 800f, 1096L)
        assertEquals(GestureState.PRIMARY_READY, demoteS1.state)
        assertEquals(1, demoteS1.stageLevel)
        assertEquals(EdgeAction.BACK, demoteS1.activeAction)
        assertEquals(GestureHapticEvent.PRIMARY_TRIGGERED, demoteS1.hapticEvent)

        // Push back to Stage 3 and commit on UP
        val backToS3 = engine.onMove(10f + s3Threshold + 20f, 800f, 1112L)
        assertEquals(GestureState.TERTIARY_READY, backToS3.state)
        val up = engine.onUp(10f + s3Threshold + 20f, 800f, 1128L)
        assertEquals(GestureCommitType.EXECUTE, up.commitType)
        assertEquals(EdgeAction.SCREENSHOT, up.activeAction)
    }

    @Test
    fun smartAuto2Stage_whenStage3IsNone_neverEntersTertiaryEvenAt85PercentDrag() {
        val cfg = EdgeConfig(
            sideTriggerMode = SecondaryTriggerMode.BOTH,
            sideStage2DistanceRatio = 0.30f,
            sideStage3DistanceRatio = 0.85f,
            horizontalAction = EdgeAction.BACK,
            horizontalHoldAction = EdgeAction.PREVIOUS_APP,
            leftStage3Action = EdgeAction.NONE
        )
        val engine = GestureDetectorEngine(EdgeSide.LEFT, density, screenW, screenH)
        engine.updateConfig(cfg, density, screenW, screenH)

        engine.onDown(10f, 700f, 1000L)
        val deep = engine.onMove(10f + screenW * 0.90f, 700f, 1040L)
        assertFalse(deep.hasStage3Configured)
        assertEquals(GestureState.SECONDARY_READY, deep.state)
        assertEquals(2, deep.stageLevel)
        assertEquals(EdgeAction.PREVIOUS_APP, deep.activeAction)
        assertFalse(deep.shouldScheduleHold) // No Stage 3 hold timer scheduled!

        val up = engine.onUp(10f + screenW * 0.90f, 700f, 1060L)
        assertEquals(EdgeAction.PREVIOUS_APP, up.activeAction)
    }

    @Test
    fun threeStageHold_promotesFromStage1ToStage2ToStage3Sequentially() {
        val cfg = EdgeConfig(
            bottomTriggerMode = SecondaryTriggerMode.HOLD,
            bottomStage2HoldMs = 250L,
            bottomStage3HoldMs = 650L,
            bottomSwipeUpAction = EdgeAction.HOME,
            bottomSwipeHoldAction = EdgeAction.RECENTS,
            bottomStage3Action = EdgeAction.LOCK_SCREEN
        )
        val engine = GestureDetectorEngine(EdgeSide.BOTTOM, density, screenW, screenH)
        engine.updateConfig(cfg, density, screenW, screenH)

        engine.onDown(540f, 2380f, 1000L)
        val s1 = engine.onMove(540f, 2240f, 1020L)
        assertEquals(GestureState.PRIMARY_READY, s1.state)
        assertTrue(s1.shouldScheduleHold)
        assertEquals(250L, s1.holdDelayMs)

        // Stage 2 hold fires -> schedules Stage 3 delta hold (650 - 250 = 400ms)
        val s2 = engine.onHoldTimeout(1270L)
        assertEquals(GestureState.SECONDARY_READY, s2.state)
        assertEquals(EdgeAction.RECENTS, s2.activeAction)
        assertEquals(GestureHapticEvent.SECONDARY_TRIGGERED, s2.hapticEvent)
        assertTrue(s2.shouldScheduleHold)
        assertEquals(400L, s2.holdDelayMs)

        // Stage 3 hold fires -> TERTIARY_READY with TERTIARY_TRIGGERED haptic
        val s3 = engine.onHoldTimeout(1670L)
        assertEquals(GestureState.TERTIARY_READY, s3.state)
        assertEquals(3, s3.stageLevel)
        assertEquals(EdgeAction.LOCK_SCREEN, s3.activeAction)
        assertEquals(GestureHapticEvent.TERTIARY_TRIGGERED, s3.hapticEvent)

        val up = engine.onUp(540f, 2240f, 1700L)
        assertEquals(GestureCommitType.EXECUTE, up.commitType)
        assertEquals(EdgeAction.LOCK_SCREEN, up.activeAction)
    }

    @Test
    fun smartOneStrokeHandOff_whenStage3Configured_allowsFastPassThroughToStage3_orHandsOffOnSettle() {
        val cfg = EdgeConfig(
            bottomTriggerMode = SecondaryTriggerMode.DISTANCE,
            bottomStage2DistanceRatio = 0.20f,
            bottomStage3DistanceRatio = 0.55f,
            bottomSwipeUpAction = EdgeAction.HOME,
            bottomSwipeHoldAction = EdgeAction.OPEN_RECENT_SWITCHER,
            bottomStage3Action = EdgeAction.SCREENSHOT
        )
        val engine = GestureDetectorEngine(EdgeSide.BOTTOM, density, screenW, screenH)
        engine.updateConfig(cfg, density, screenW, screenH)

        // Case A: Continuous swipe passes Stage 2 without hijacking and commits Stage 3 SCREENSHOT
        engine.onDown(540f, 2380f, 1000L)
        engine.onMove(540f, 2240f, 1016L)
        val passS2 = engine.onMove(540f, 2380f - screenH * 0.25f, 1032L)
        assertEquals(GestureState.SECONDARY_READY, passS2.state)
        assertEquals(MenuHandOffTarget.NONE, passS2.handOffTarget)
        assertTrue(passS2.shouldScheduleHold)

        val reachS3 = engine.onMove(540f, 2380f - screenH * 0.60f, 1048L)
        assertEquals(GestureState.TERTIARY_READY, reachS3.state)
        assertEquals(EdgeAction.SCREENSHOT, reachS3.activeAction)
        assertEquals(GestureHapticEvent.TERTIARY_TRIGGERED, reachS3.hapticEvent)

        val upS3 = engine.onUp(540f, 2380f - screenH * 0.60f, 1064L)
        assertEquals(EdgeAction.SCREENSHOT, upS3.activeAction)

        // Case B: Pausing briefly at Stage 2 triggers one-stroke RECENT_SWITCHER hand-off
        engine.onDown(540f, 2380f, 2000L)
        engine.onMove(540f, 2240f, 2016L)
        val atS2 = engine.onMove(540f, 2380f - screenH * 0.25f, 2032L)
        assertEquals(GestureState.SECONDARY_READY, atS2.state)
        val settled = engine.onHoldTimeout(2260L)
        assertEquals(MenuHandOffTarget.RECENT_SWITCHER, settled.handOffTarget)
        assertEquals(EdgeAction.OPEN_RECENT_SWITCHER, settled.activeAction)
    }

    @Test
    fun edgeStationaryHold_opensQuickArcWithoutMoving_andCancelsCleanlyOnSwipe() {
        val cfg = EdgeConfig(
            quickArcTriggerMethod = QuickArcTriggerMethod.EDGE_STATIONARY_HOLD,
            quickArcEdgeHoldMs = 300,
            sideStage2DistanceRatio = 0.28f,
            horizontalHoldAction = EdgeAction.PREVIOUS_APP,
            rightStage3Action = EdgeAction.NONE
        )
        val engine = GestureDetectorEngine(EdgeSide.RIGHT, density, screenW, screenH)
        engine.updateConfig(cfg, density, screenW, screenH)

        // Case A: Hold stationary on edge bezel -> opens Quick Arc without moving
        val down = engine.onDown(1075f, 700f, 1000L)
        assertTrue(down.shouldScheduleLongPress)
        assertEquals(300L, down.longPressDelayMs)

        // Tiny micro-jitter within slop does not cancel stationary hold
        val jitter = engine.onMove(1070f, 702f, 1100L)
        assertFalse(jitter.shouldCancelLongPress)

        val timeout = engine.onLongPressTimeout(1300L)
        assertEquals(MenuHandOffTarget.QUICK_MENU, timeout.handOffTarget)
        assertEquals(EdgeAction.OPEN_QUICK_MENU, timeout.activeAction)
        assertEquals(GestureHapticEvent.SECONDARY_TRIGGERED, timeout.hapticEvent)

        // Case B: Immediate inward swipe cancels edge hold timer and performs normal Back / Long-Swipe cleanly
        val down2 = engine.onDown(1075f, 700f, 2000L)
        assertTrue(down2.shouldScheduleLongPress)

        val swipe = engine.onMove(960f, 700f, 2020L)
        assertTrue(swipe.shouldCancelLongPress)
        assertEquals(GestureState.PRIMARY_READY, swipe.state)
        assertEquals(EdgeAction.BACK, swipe.activeAction)

        val longSwipe = engine.onMove(750f, 700f, 2040L)
        assertEquals(MenuHandOffTarget.NONE, longSwipe.handOffTarget)
        assertEquals(GestureState.SECONDARY_READY, longSwipe.state)
        assertEquals(EdgeAction.PREVIOUS_APP, longSwipe.activeAction)
    }

    @Test
    fun pullAndReturn_uTurnOpensQuickArcInstantly_whileShortAndLongSwipeStayIntact() {
        val cfg = EdgeConfig(
            quickArcTriggerMethod = QuickArcTriggerMethod.PULL_AND_RETURN,
            sideStage2DistanceRatio = 0.28f,
            horizontalHoldAction = EdgeAction.PREVIOUS_APP,
            leftStage3Action = EdgeAction.NONE
        )
        val engine = GestureDetectorEngine(EdgeSide.LEFT, density, screenW, screenH)
        engine.updateConfig(cfg, density, screenW, screenH)

        // Case A: Pull horizontally past Stage 1 (inward 115px), then slide back toward edge -> 0ms U-turn Quick Arc
        engine.onDown(8f, 700f, 1000L)
        val stage1 = engine.onMove(123f, 700f, 1016L)
        assertEquals(GestureState.PRIMARY_READY, stage1.state)
        assertEquals(EdgeAction.BACK, stage1.activeAction)

        val uTurn = engine.onMove(38f, 700f, 1036L)
        assertEquals(MenuHandOffTarget.QUICK_MENU, uTurn.handOffTarget)
        assertEquals(EdgeAction.OPEN_QUICK_MENU, uTurn.activeAction)
        assertEquals(GestureHapticEvent.SECONDARY_TRIGGERED, uTurn.hapticEvent)
        assertEquals(GestureState.IDLE, uTurn.state)

        // Case B: Normal pull and release executes BACK with zero interference
        engine.onDown(8f, 700f, 2000L)
        engine.onMove(123f, 700f, 2020L)
        val backUp = engine.onUp(123f, 700f, 2040L)
        assertEquals(GestureCommitType.EXECUTE, backUp.commitType)
        assertEquals(EdgeAction.BACK, backUp.activeAction)

        // Case C: Deep pull executes Stage-2 PREVIOUS_APP with zero interference
        engine.onDown(8f, 700f, 3000L)
        engine.onMove(123f, 700f, 3020L)
        val deep = engine.onMove(340f, 700f, 3040L)
        assertEquals(GestureState.SECONDARY_READY, deep.state)
        assertEquals(EdgeAction.PREVIOUS_APP, deep.activeAction)
        assertEquals(EdgeAction.PREVIOUS_APP, engine.onUp(340f, 700f, 3060L).activeAction)
    }

    @Test
    fun classicGestureActionMode_shortSwipeAndHoldOpensQuickArcMenu() {
        val engine = GestureDetectorEngine(EdgeSide.RIGHT, density, screenW, screenH)
        engine.updateConfig(
            EdgeConfig(
                sideTriggerMode = SecondaryTriggerMode.BOTH,
                sideStage2DistanceRatio = 0.28f,
                quickArcTriggerMethod = QuickArcTriggerMethod.GESTURE_ACTION,
                horizontalHoldAction = EdgeAction.OPEN_QUICK_MENU,
                rightStage3Action = EdgeAction.NONE
            ),
            density,
            screenW,
            screenH
        )
        engine.onDown(1075f, 700f, 1000L)

        val stage1 = engine.onMove(970f, 700f, 1020L)
        assertEquals(GestureState.PRIMARY_READY, stage1.state)
        assertTrue(stage1.shouldScheduleHold)

        val hold = engine.onHoldTimeout(1370L)
        assertEquals(MenuHandOffTarget.QUICK_MENU, hold.handOffTarget)
        assertEquals(EdgeAction.OPEN_QUICK_MENU, hold.activeAction)
        assertEquals(GestureHapticEvent.SECONDARY_TRIGGERED, hold.hapticEvent)
    }

    @Test
    fun classicGestureActionMode_longHorizontalPullWithSlightThumbCurvature_staysHorizontalAndOpensQuickArc() {
        val engine = GestureDetectorEngine(EdgeSide.RIGHT, density, screenW, screenH)
        engine.updateConfig(
            EdgeConfig(
                sideTriggerMode = SecondaryTriggerMode.BOTH,
                sideStage2DistanceRatio = 0.28f,
                diagonalAngleDeg = 30f,
                quickArcTriggerMethod = QuickArcTriggerMethod.GESTURE_ACTION,
                horizontalHoldAction = EdgeAction.OPEN_QUICK_MENU,
                rightStage3Action = EdgeAction.NONE
            ),
            density,
            screenW,
            screenH
        )
        engine.onDown(1075f, 800f, 1000L)

        // Start cleanly horizontal (inward 110px)
        val stage1 = engine.onMove(965f, 800f, 1016L)
        assertEquals(GestureState.PRIMARY_READY, stage1.state)
        assertEquals(GestureDirection.HORIZONTAL, stage1.direction)

        // Pull deeper inward (315px) with natural ~34-degree thumb upward drift
        val stage2 = engine.onMove(760f, 590f, 1032L)
        assertEquals(MenuHandOffTarget.QUICK_MENU, stage2.handOffTarget)
        assertEquals(EdgeAction.OPEN_QUICK_MENU, stage2.activeAction)
        assertEquals(GestureHapticEvent.SECONDARY_TRIGGERED, stage2.hapticEvent)
    }

    @Test
    fun classicGestureActionMode_distanceLongSwipe_handsOffInstantlyToQuickArcMenuWithZeroWait() {
        val engine = GestureDetectorEngine(EdgeSide.RIGHT, density, screenW, screenH)
        engine.updateConfig(
            EdgeConfig(
                sideTriggerMode = SecondaryTriggerMode.DISTANCE,
                sideStage2DistanceRatio = 0.28f,
                quickArcTriggerMethod = QuickArcTriggerMethod.GESTURE_ACTION,
                horizontalHoldAction = EdgeAction.OPEN_QUICK_MENU,
                rightStage3Action = EdgeAction.NONE
            ),
            density,
            screenW,
            screenH
        )
        engine.onDown(1075f, 700f, 1000L)

        // Stage 1: inward 105px
        val stage1 = engine.onMove(970f, 700f, 1016L)
        assertEquals(GestureState.PRIMARY_READY, stage1.state)
        assertEquals(EdgeAction.BACK, stage1.activeAction)
        assertEquals(MenuHandOffTarget.NONE, stage1.handOffTarget)

        // Stage 2: inward >= 28% of 1080px (302.4px) -> 0ms instant hand-off to QUICK_MENU
        val stage2 = engine.onMove(760f, 700f, 1032L)
        assertEquals(MenuHandOffTarget.QUICK_MENU, stage2.handOffTarget)
        assertEquals(EdgeAction.OPEN_QUICK_MENU, stage2.activeAction)
        assertEquals(GestureHapticEvent.SECONDARY_TRIGGERED, stage2.hapticEvent)
        assertEquals(GestureState.IDLE, stage2.state)
    }

    @Test
    fun leftEdge_distanceLongSwipeWithHysteresis_promotesHoldsAcrossSmallPullbackAndDemotesPast14dp() {
        val engine = GestureDetectorEngine(EdgeSide.LEFT, density, screenW, screenH)
        engine.updateConfig(
            EdgeConfig(
                sideTriggerMode = SecondaryTriggerMode.DISTANCE,
                sideStage2DistanceRatio = 0.28f,
                horizontalHoldAction = EdgeAction.RECENTS
            ),
            density,
            screenW,
            screenH
        )
        val stage2Threshold = engine.sideStage2ThresholdPx() // 302.4px
        val hysteresis = engine.hysteresisMarginPx() // 42px

        engine.onDown(10f, 800f, 1000L)
        val s1 = engine.onMove(120f, 800f, 1016L)
        assertEquals(GestureState.PRIMARY_READY, s1.state)
        assertEquals(EdgeAction.BACK, s1.activeAction)

        // Cross Stage-2 threshold -> immediate promotion with SECONDARY_TRIGGERED haptic
        val s2 = engine.onMove(10f + stage2Threshold + 10f, 800f, 1032L)
        assertEquals(GestureState.SECONDARY_READY, s2.state)
        assertEquals(EdgeAction.RECENTS, s2.activeAction)
        assertEquals(GestureHapticEvent.SECONDARY_TRIGGERED, s2.hapticEvent)
        assertEquals(GestureCommitType.NONE, s2.commitType)

        // Small pullback inside 14dp hysteresis band -> MUST stay SECONDARY_READY
        val stayS2 = engine.onMove(10f + stage2Threshold - (hysteresis * 0.5f), 800f, 1048L)
        assertEquals(GestureState.SECONDARY_READY, stayS2.state)
        assertEquals(EdgeAction.RECENTS, stayS2.activeAction)
        assertEquals(GestureHapticEvent.NONE, stayS2.hapticEvent)

        // Pull back past (threshold - 14dp) -> smoothly demotes to PRIMARY_READY (BACK)
        val demote = engine.onMove(10f + stage2Threshold - hysteresis - 5f, 800f, 1064L)
        assertEquals(GestureState.PRIMARY_READY, demote.state)
        assertEquals(EdgeAction.BACK, demote.activeAction)
        assertEquals(GestureHapticEvent.PRIMARY_TRIGGERED, demote.hapticEvent)

        // Pull back all the way into dead-zone near edge -> TRACKING (cancel on release)
        val cancelZone = engine.onMove(30f, 800f, 1080L)
        assertEquals(GestureState.TRACKING, cancelZone.state)
        assertEquals(EdgeAction.NONE, cancelZone.activeAction)

        val up = engine.onUp(30f, 800f, 1096L)
        assertEquals(GestureCommitType.NONE, up.commitType)
        assertEquals(EdgeAction.NONE, up.activeAction)
    }

    @Test
    fun holdMode_triggersSecondaryOnHoldTimeout() {
        val engine = GestureDetectorEngine(EdgeSide.RIGHT, density, screenW, screenH)
        engine.updateConfig(
            EdgeConfig(
                sideTriggerMode = SecondaryTriggerMode.HOLD,
                horizontalHoldAction = EdgeAction.RECENTS
            ),
            density,
            screenW,
            screenH
        )

        engine.onDown(1075f, 700f, 1000L)
        val move = engine.onMove(970f, 700f, 1030L)
        assertEquals(GestureState.PRIMARY_READY, move.state)
        assertTrue(move.shouldScheduleHold)

        val hold = engine.onHoldTimeout(1380L)
        assertEquals(MenuHandOffTarget.NONE, hold.handOffTarget)
        assertEquals(GestureState.SECONDARY_READY, hold.state)
        assertEquals(EdgeAction.RECENTS, hold.activeAction)
        assertEquals(GestureHapticEvent.SECONDARY_TRIGGERED, hold.hapticEvent)

        val up = engine.onUp(970f, 700f, 1450L)
        assertEquals(GestureCommitType.EXECUTE, up.commitType)
        assertEquals(EdgeAction.RECENTS, up.activeAction)
    }

    @Test
    fun diagonalUpAndDown_executeHomeAndNotifications() {
        val cfg = EdgeConfig(
            diagonalUpAction = EdgeAction.HOME,
            diagonalDownAction = EdgeAction.NOTIFICATIONS
        )
        val leftEngine = GestureDetectorEngine(EdgeSide.LEFT, density, screenW, screenH)
        leftEngine.updateConfig(cfg, density, screenW, screenH)
        leftEngine.onDown(10f, 900f, 1000L)
        val moveUp = leftEngine.onMove(110f, 800f, 1040L)
        assertEquals(GestureDirection.DIAGONAL_UP, moveUp.direction)
        assertEquals(EdgeAction.HOME, moveUp.activeAction)
        assertEquals(EdgeAction.HOME, leftEngine.onUp(110f, 800f, 1090L).activeAction)

        val rightEngine = GestureDetectorEngine(EdgeSide.RIGHT, density, screenW, screenH)
        rightEngine.updateConfig(cfg, density, screenW, screenH)
        rightEngine.onDown(1070f, 500f, 1000L)
        val moveDown = rightEngine.onMove(970f, 600f, 1040L)
        assertEquals(GestureDirection.DIAGONAL_DOWN, moveDown.direction)
        assertEquals(EdgeAction.NOTIFICATIONS, moveDown.activeAction)
        assertEquals(EdgeAction.NOTIFICATIONS, rightEngine.onUp(970f, 600f, 1080L).activeAction)
    }

    @Test
    fun bottomPill_stationaryLongPress_triggersCircleToSearchWithoutSwipeMisfire() {
        val engine = GestureDetectorEngine(EdgeSide.BOTTOM, density, screenW, screenH)
        val down = engine.onDown(540f, 2380f, 1000L)
        assertTrue(down.shouldScheduleLongPress)
        assertEquals(EdgeConfig.DEFAULT_BOTTOM_LONG_PRESS_MS.toLong(), down.longPressDelayMs)

        // Small micro-jitter within slop does not cancel long press
        val jitter = engine.onMove(544f, 2376f, 1120L)
        assertFalse(jitter.shouldCancelLongPress)

        val longPress = engine.onLongPressTimeout(1400L)
        assertEquals(GestureState.TRIGGERED_LONG_PRESS, longPress.state)
        assertEquals(GestureCommitType.EXECUTE, longPress.commitType)
        assertEquals(EdgeAction.CIRCLE_TO_SEARCH, longPress.activeAction)
        assertEquals(GestureHapticEvent.SECONDARY_TRIGGERED, longPress.hapticEvent)

        // Releasing finger afterward does NOT re-execute a second action
        val up = engine.onUp(544f, 2376f, 1500L)
        assertEquals(GestureCommitType.NONE, up.commitType)
    }

    @Test
    fun bottomPill_shortSwipeUp_neverFiresHomeMidDragAndExecutesHomeOnUp() {
        val engine = GestureDetectorEngine(EdgeSide.BOTTOM, density, screenW, screenH)
        val down = engine.onDown(540f, 2380f, 1000L)
        assertTrue(down.shouldScheduleLongPress)

        // Swipe up 120px -> cancels long press & enters PRIMARY_READY without firing HOME mid-drag
        val move = engine.onMove(542f, 2260f, 1040L)
        assertTrue(move.shouldCancelLongPress)
        assertEquals(GestureState.PRIMARY_READY, move.state)
        assertEquals(EdgeAction.HOME, move.activeAction)
        assertEquals(GestureCommitType.NONE, move.commitType)

        val up = engine.onUp(542f, 2260f, 1080L)
        assertEquals(GestureCommitType.EXECUTE, up.commitType)
        assertEquals(EdgeAction.HOME, up.activeAction)
    }

    @Test
    fun bottomPill_distanceLongSwipe_defaultHandsOffToMiniTaskSwitcherWithoutFiringHome() {
        val engine = GestureDetectorEngine(EdgeSide.BOTTOM, density, screenW, screenH)
        engine.updateConfig(
            EdgeConfig(
                bottomTriggerMode = SecondaryTriggerMode.BOTH,
                bottomStage2DistanceRatio = 0.20f,
                bottomSwipeUpAction = EdgeAction.HOME,
                bottomSwipeHoldAction = EdgeAction.OPEN_RECENT_SWITCHER,
                bottomStage3Action = EdgeAction.NONE
            ),
            density,
            screenW,
            screenH
        )
        val threshold = engine.bottomStage2ThresholdPx() // 20% of 2400 = 480px

        engine.onDown(540f, 2380f, 1000L)
        val stage1 = engine.onMove(540f, 2240f, 1016L)
        assertEquals(GestureState.PRIMARY_READY, stage1.state)
        assertEquals(EdgeAction.HOME, stage1.activeAction)
        assertEquals(GestureCommitType.NONE, stage1.commitType)

        // Pull past 20% height -> instant one-stroke hand-off to RECENT_SWITCHER (since bottomStage3Action is NONE)
        val stage2 = engine.onMove(540f, 2380f - threshold - 12f, 1032L)
        assertEquals(MenuHandOffTarget.RECENT_SWITCHER, stage2.handOffTarget)
        assertEquals(EdgeAction.OPEN_RECENT_SWITCHER, stage2.activeAction)
        assertEquals(GestureHapticEvent.SECONDARY_TRIGGERED, stage2.hapticEvent)
        assertEquals(GestureCommitType.NONE, stage2.commitType)
    }

    @Test
    fun bottomPill_distanceLongSwipeRecents_supportsHysteresisDemotionBackToHome() {
        val engine = GestureDetectorEngine(EdgeSide.BOTTOM, density, screenW, screenH)
        engine.updateConfig(
            EdgeConfig(
                bottomTriggerMode = SecondaryTriggerMode.DISTANCE,
                bottomStage2DistanceRatio = 0.22f,
                bottomSwipeHoldAction = EdgeAction.RECENTS
            ),
            density,
            screenW,
            screenH
        )
        val threshold = engine.bottomStage2ThresholdPx()
        val hysteresis = engine.hysteresisMarginPx()

        engine.onDown(540f, 2380f, 1000L)
        engine.onMove(540f, 2240f, 1016L)

        val stage2 = engine.onMove(540f, 2380f - threshold - 20f, 1032L)
        assertEquals(GestureState.SECONDARY_READY, stage2.state)
        assertEquals(EdgeAction.RECENTS, stage2.activeAction)

        // Pull back within hysteresis -> stays RECENTS
        val stayS2 = engine.onMove(540f, 2380f - threshold + hysteresis * 0.4f, 1048L)
        assertEquals(GestureState.SECONDARY_READY, stayS2.state)

        // Pull back past hysteresis -> returns to HOME
        val demoted = engine.onMove(540f, 2380f - threshold + hysteresis + 10f, 1064L)
        assertEquals(GestureState.PRIMARY_READY, demoted.state)
        assertEquals(EdgeAction.HOME, demoted.activeAction)

        val up = engine.onUp(540f, 2380f - threshold + hysteresis + 10f, 1080L)
        assertEquals(GestureCommitType.EXECUTE, up.commitType)
        assertEquals(EdgeAction.HOME, up.activeAction)
    }

    @Test
    fun landscapeDoubleSwipeConfirm_armsOnFirstSwipeAndExecutesOnSecond() {
        val engine = GestureDetectorEngine(EdgeSide.LEFT, density, screenW, screenH)
        engine.setDoubleSwipeConfirmRequired(true)

        engine.onDown(10f, 400f, 1000L)
        engine.onMove(120f, 400f, 1030L)
        val firstUp = engine.onUp(120f, 400f, 1060L)
        assertEquals(GestureCommitType.ARMED_CONFIRM, firstUp.commitType)
        assertEquals(EdgeAction.NONE, firstUp.activeAction)
        assertTrue(engine.isDoubleSwipeArmed(1500L))

        engine.onDown(10f, 400f, 1600L)
        engine.onMove(120f, 400f, 1630L)
        val secondUp = engine.onUp(120f, 400f, 1660L)
        assertEquals(GestureCommitType.EXECUTE, secondUp.commitType)
        assertEquals(EdgeAction.BACK, secondUp.activeAction)
        assertFalse(engine.isDoubleSwipeArmed(1700L))
    }

    @Test
    fun edgeConfig_serializationRoundTrip_preservesAll3StageSplitCustomMediaAndArcFields() {
        val original = EdgeConfig(
            leftEnabled = true,
            rightEnabled = false,
            bottomEnabled = true,
            edgeWidthDp = 22f,
            edgeHeightPercent = 0.60f,
            edgeBottomOffsetPercent = 0.15f,
            bottomBarWidthPercent = 0.44f,
            bottomBarHeightDp = 18f,
            swipeThresholdDp = 34f,
            diagonalAngleDeg = 35f,
            sideTriggerMode = SecondaryTriggerMode.DISTANCE,
            bottomTriggerMode = SecondaryTriggerMode.HOLD,
            sideStage2DistanceRatio = 0.34f,
            sideStage3DistanceRatio = 0.78f,
            bottomStage2DistanceRatio = 0.26f,
            bottomStage3DistanceRatio = 0.64f,
            sideStage2HoldMs = 300L,
            sideStage3HoldMs = 825L,
            bottomStage2HoldMs = 250L,
            bottomStage3HoldMs = 700L,
            hyperOsRecentsCompatMode = true,
            hyperOsRecentsDelayMs = 140,
            horizontalAction = EdgeAction.BACK,
            horizontalHoldAction = EdgeAction.OPEN_QUICK_MENU,
            leftStage3Action = EdgeAction.SCREENSHOT,
            rightStage3Action = EdgeAction.SPLIT_SCREEN,
            diagonalUpAction = EdgeAction.CIRCLE_TO_SEARCH,
            diagonalDownAction = EdgeAction.TOGGLE_TORCH,
            bottomSwipeUpAction = EdgeAction.HOME,
            bottomSwipeHoldAction = EdgeAction.OPEN_RECENT_SWITCHER,
            bottomStage3Action = EdgeAction.LOCK_SCREEN,
            bottomLongPressEnabled = true,
            bottomLongPressDurationMs = 450,
            bottomLongPressAction = EdgeAction.CIRCLE_TO_SEARCH,
            feedbackStyle = FeedbackStyle.MINIMAL_ARROW,
            visualTheme = VisualTheme.DARK_GLASS,
            feedbackAlpha = 0.55f,
            edgeGuideAlpha = 0.20f,
            bottomPillAlpha = 0.42f,
            hapticStrength = HapticStrength.STRONG,
            edgeVisualStyle = EdgeVisualStyle.DROPLET_WITH_CUSTOM_MEDIA,
            customMediaPath = "/data/user/0/com.edgeflow/files/custom_media/edge_indicator.media",
            customMediaVersion = 1760001234567L,
            customMediaSizeDp = 72,
            customMediaAlpha = 0.88f,
            customMediaMirrorRight = false,
            quickMenuSlots = listOf(
                QuickSlotConfig(EdgeAction.CIRCLE_TO_SEARCH, ""),
                QuickSlotConfig(EdgeAction.launchApp("com.android.chrome"), "Chrome"),
                QuickSlotConfig(EdgeAction.OPEN_RECENT_SWITCHER, ""),
                QuickSlotConfig(EdgeAction.PREVIOUS_APP, "")
            ),
            quickMenuRadiusDp = 128f,
            quickMenuDimAlpha = 0.22f,
            quickArcTriggerMethod = QuickArcTriggerMethod.PULL_AND_RETURN,
            quickArcZoneSide = QuickArcZoneSide.RIGHT_ONLY,
            quickArcZoneHeightPercent = 0.24f,
            quickArcZoneBottomOffsetPercent = 0.62f,
            quickArcEdgeHoldMs = 375,
            imeAvoidanceMode = ImeAvoidanceMode.DISABLE_TOUCH,
            landscapeMode = LandscapeMode.DISABLE,
            blacklistedPackages = setOf("com.miHoYo.GenshinImpact", "com.android.camera")
        )
        val restored = EdgeConfig.deserialize(original.serialize())
        assertEquals(original, restored)
        assertEquals(SecondaryTriggerMode.DISTANCE, restored.sideTriggerMode)
        assertEquals(SecondaryTriggerMode.HOLD, restored.bottomTriggerMode)
        assertEquals(0.78f, restored.sideStage3DistanceRatio, 0.0001f)
        assertEquals(0.64f, restored.bottomStage3DistanceRatio, 0.0001f)
        assertEquals(EdgeAction.SCREENSHOT, restored.leftStage3Action)
        assertEquals(EdgeAction.SPLIT_SCREEN, restored.rightStage3Action)
        assertEquals(EdgeAction.LOCK_SCREEN, restored.bottomStage3Action)
        assertEquals(EdgeVisualStyle.DROPLET_WITH_CUSTOM_MEDIA, restored.edgeVisualStyle)
        assertEquals(72, restored.customMediaSizeDp)
        assertEquals(0.88f, restored.customMediaAlpha, 0.0001f)
        assertFalse(restored.customMediaMirrorRight)
        assertEquals(1760001234567L, restored.customMediaVersion)
    }

    @Test
    fun edgeConfig_legacyDeserialization_migratesSharedTriggerModeAndRatiosCleanly() {
        val legacyPayload = """
            secondaryTriggerMode=DISTANCE
            horizontalLongSwipeRatio=0.32
            bottomLongSwipeRatio=0.24
            holdDurationMs=400
        """.trimIndent()
        val migrated = EdgeConfig.deserialize(legacyPayload)
        assertEquals(SecondaryTriggerMode.DISTANCE, migrated.sideTriggerMode)
        assertEquals(SecondaryTriggerMode.DISTANCE, migrated.bottomTriggerMode)
        assertEquals(0.32f, migrated.sideStage2DistanceRatio, 0.0001f)
        assertEquals(0.24f, migrated.bottomStage2DistanceRatio, 0.0001f)
        assertEquals(400L, migrated.sideStage2HoldMs)
        assertEquals(400L, migrated.bottomStage2HoldMs)
    }

    @Test
    fun floatingWindowActions_encodeDecodeAndConfigRoundTrip() {
        val floatCurrent = EdgeAction.FLOAT_CURRENT_APP
        val floatApp = EdgeAction.launchAppFloating("com.whatsapp")
        assertEquals("FLOAT_CURRENT_APP", floatCurrent.encode())
        assertEquals("LAUNCH_APP_FLOATING:com.whatsapp", floatApp.encode())
        assertEquals(floatCurrent, EdgeAction.decode(floatCurrent.encode(), EdgeAction.NONE))
        assertEquals(floatApp, EdgeAction.decode(floatApp.encode(), EdgeAction.NONE))
        // Missing package name falls back safely
        assertEquals(EdgeAction.NONE, EdgeAction.decode("LAUNCH_APP_FLOATING", EdgeAction.NONE))
        assertEquals(EdgeAction.NONE, EdgeAction.decode("LAUNCH_APP_FLOATING:", EdgeAction.NONE))

        val original = EdgeConfig(
            leftStage3Action = EdgeAction.FLOAT_CURRENT_APP,
            rightStage3Action = EdgeAction.launchAppFloating("com.android.calculator2"),
            quickMenuSlots = listOf(
                QuickSlotConfig(EdgeAction.FLOAT_CURRENT_APP, ""),
                QuickSlotConfig(EdgeAction.launchAppFloating("com.whatsapp"), "WhatsApp"),
                QuickSlotConfig(EdgeAction.launchApp("com.android.chrome"), "Chrome")
            )
        )
        val restored = EdgeConfig.deserialize(original.serialize())
        assertEquals(original, restored)
        assertEquals(EdgeAction.FLOAT_CURRENT_APP, restored.leftStage3Action)
        assertEquals(EdgeAction.launchAppFloating("com.android.calculator2"), restored.rightStage3Action)
        assertEquals(EdgeActionType.LAUNCH_APP_FLOATING, restored.quickMenuSlots[1].action.type)
        assertEquals("com.whatsapp", restored.quickMenuSlots[1].action.packageName)
        assertEquals("WhatsApp", restored.quickMenuSlots[1].customLabel)
    }

    @Test
    fun bidirectionalHandback_stage2QuickMenuCancelResumesStage1Back_andCanReEnterMenu() {
        val cfg = EdgeConfig(
            sideTriggerMode = SecondaryTriggerMode.DISTANCE,
            sideStage2DistanceRatio = 0.28f,
            horizontalAction = EdgeAction.BACK,
            horizontalHoldAction = EdgeAction.OPEN_QUICK_MENU,
            rightStage3Action = EdgeAction.NONE,
            quickArcTriggerMethod = QuickArcTriggerMethod.GESTURE_ACTION
        )
        val engine = GestureDetectorEngine(EdgeSide.RIGHT, density, screenW, screenH)
        engine.updateConfig(cfg, density, screenW, screenH)

        val stage2Px = engine.sideStage2ThresholdPx()
        val hyst = engine.hysteresisMarginPx()

        engine.onDown(1075f, 700f, 1000L)
        val s1 = engine.onMove(960f, 700f, 1016L)
        assertEquals(GestureState.PRIMARY_READY, s1.state)

        // Push past Stage 2 -> hands off to QUICK_MENU and records retreat boundary
        val handOff = engine.onMove(1075f - stage2Px - 20f, 700f, 1032L)
        assertEquals(MenuHandOffTarget.QUICK_MENU, handOff.handOffTarget)
        assertEquals(2, handOff.handOffLaunchedStage)
        assertEquals(stage2Px - hyst, handOff.handOffRetreatBoundaryPx, 1f)

        // Finger retreats back past boundary into Stage 1 -> resumeFromMenuCancel restores Stage 1 BACK
        val resumed = engine.resumeFromMenuCancel(1075f - stage2Px + hyst + 25f, 700f, 1060L)
        assertEquals(GestureState.PRIMARY_READY, resumed.state)
        assertEquals(1, resumed.stageLevel)
        assertEquals(EdgeAction.BACK, resumed.activeAction)
        assertEquals(GestureHapticEvent.PRIMARY_TRIGGERED, resumed.hapticEvent)

        // Pushing forward again seamlessly re-triggers OPEN_QUICK_MENU
        val reEntered = engine.onMove(1075f - stage2Px - 25f, 700f, 1090L)
        assertEquals(MenuHandOffTarget.QUICK_MENU, reEntered.handOffTarget)

        // Retreat once more and release -> commits BACK
        val resumedAgain = engine.resumeFromMenuCancel(950f, 700f, 1120L)
        assertEquals(GestureState.PRIMARY_READY, resumedAgain.state)
        val up = engine.onUp(950f, 700f, 1140L)
        assertEquals(GestureCommitType.EXECUTE, up.commitType)
        assertEquals(EdgeAction.BACK, up.activeAction)
    }

    @Test
    fun bidirectionalHandback_stage3QuickMenuCancelResumesStage2PreviousApp_andRetreatsToStage1() {
        val cfg = EdgeConfig(
            sideTriggerMode = SecondaryTriggerMode.DISTANCE,
            sideStage2DistanceRatio = 0.28f,
            sideStage3DistanceRatio = 0.65f,
            horizontalAction = EdgeAction.BACK,
            horizontalHoldAction = EdgeAction.PREVIOUS_APP,
            leftStage3Action = EdgeAction.OPEN_QUICK_MENU,
            quickArcTriggerMethod = QuickArcTriggerMethod.GESTURE_ACTION
        )
        val engine = GestureDetectorEngine(EdgeSide.LEFT, density, screenW, screenH)
        engine.updateConfig(cfg, density, screenW, screenH)

        val stage2Px = engine.sideStage2ThresholdPx()
        val stage3Px = engine.sideStage3ThresholdPx()
        val hyst = engine.hysteresisMarginPx()

        engine.onDown(10f, 800f, 1000L)
        engine.onMove(120f, 800f, 1016L)
        val s2 = engine.onMove(10f + stage2Px + 20f, 800f, 1032L)
        assertEquals(GestureState.SECONDARY_READY, s2.state)

        // Cross Stage 3 -> launches QUICK_MENU from Stage 3
        val handOff3 = engine.onMove(10f + stage3Px + 20f, 800f, 1048L)
        assertEquals(MenuHandOffTarget.QUICK_MENU, handOff3.handOffTarget)
        assertEquals(3, handOff3.handOffLaunchedStage)
        assertEquals(stage3Px - hyst, handOff3.handOffRetreatBoundaryPx, 1f)

        // Retreat back into Stage 2 band -> resumes Stage 2 PREVIOUS_APP
        val resumedS2 = engine.resumeFromMenuCancel(10f + stage2Px + 30f, 800f, 1080L)
        assertEquals(GestureState.SECONDARY_READY, resumedS2.state)
        assertEquals(2, resumedS2.stageLevel)
        assertEquals(EdgeAction.PREVIOUS_APP, resumedS2.activeAction)
        assertEquals(GestureHapticEvent.SECONDARY_TRIGGERED, resumedS2.hapticEvent)

        // Further retreat back to Stage 1 -> demotes cleanly to BACK
        val retreatedS1 = engine.onMove(10f + stage2Px - hyst - 20f, 800f, 1110L)
        assertEquals(GestureState.PRIMARY_READY, retreatedS1.state)
        assertEquals(EdgeAction.BACK, retreatedS1.activeAction)

        // Releasing in Stage 1 commits BACK
        val up = engine.onUp(10f + stage2Px - hyst - 20f, 800f, 1130L)
        assertEquals(GestureCommitType.EXECUTE, up.commitType)
        assertEquals(EdgeAction.BACK, up.activeAction)
    }

    @Test
    fun edgeConfig_roundTrip_preserves16SlotsUrlShortcutsLiquidGlassAndBottomGeometry() {
        val specialUrl = "https://example.com/path?a=1&b=2;c|d=e#frag"
        val shortcutUri = "intent:#Intent;action=android.intent.action.VIEW;component=com.example.demo/.DeepActivity;S.extra=a|b;end"

        val urlAction = EdgeAction.openUrl(specialUrl)
        val shortcutAction = EdgeAction.launchShortcut("com.example.demo", shortcutUri)
        assertEquals(urlAction, EdgeAction.decode(urlAction.encode(), EdgeAction.NONE))
        assertEquals(shortcutAction, EdgeAction.decode(shortcutAction.encode(), EdgeAction.NONE))

        val sixteenSlots = (1..16).map { idx ->
            when (idx) {
                1 -> QuickSlotConfig(urlAction, "Docs")
                2 -> QuickSlotConfig(shortcutAction, "Compose")
                else -> QuickSlotConfig(EdgeAction.launchApp("com.app.slot$idx"), "Slot $idx")
            }
        }
        val original = EdgeConfig(
            liquidGlassEnabled = false,
            liquidGlassRefraction = 1.35f,
            bottomBarWidthPercent = 0.82f,
            bottomBarHeightDp = 42f,
            bottomBarBottomOffsetDp = 18f,
            bottomBarHorizontalOffsetPercent = -0.22f,
            horizontalAction = urlAction,
            horizontalHoldAction = shortcutAction,
            quickMenuSlots = sixteenSlots
        )

        val restored = EdgeConfig.deserialize(original.serialize())
        assertEquals(original, restored)
        assertEquals(16, restored.quickMenuSlots.size)
        assertEquals(specialUrl, restored.quickMenuSlots[0].action.targetData)
        assertEquals(shortcutUri, restored.quickMenuSlots[1].action.targetData)
        assertEquals("com.example.demo", restored.quickMenuSlots[1].action.packageName)
        assertFalse(restored.liquidGlassEnabled)
        assertEquals(1.35f, restored.liquidGlassRefraction, 0.0001f)
        assertEquals(0.82f, restored.bottomBarWidthPercent, 0.0001f)
        assertEquals(42f, restored.bottomBarHeightDp, 0.0001f)
        assertEquals(18f, restored.bottomBarBottomOffsetDp, 0.0001f)
        assertEquals(-0.22f, restored.bottomBarHorizontalOffsetPercent, 0.0001f)
    }

    @Test
    fun stage3MenuCancel_reopenLockPreventsFlickerNearStage3Coords_andCommitsStage2OnUp() {
        val cfg = EdgeConfig(
            sideTriggerMode = SecondaryTriggerMode.DISTANCE,
            sideStage2DistanceRatio = 0.28f,
            sideStage3DistanceRatio = 0.65f,
            horizontalAction = EdgeAction.BACK,
            horizontalHoldAction = EdgeAction.PREVIOUS_APP,
            leftStage3Action = EdgeAction.OPEN_QUICK_MENU,
            quickArcTriggerMethod = QuickArcTriggerMethod.GESTURE_ACTION
        )
        val engine = GestureDetectorEngine(EdgeSide.LEFT, density, screenW, screenH)
        engine.updateConfig(cfg, density, screenW, screenH)

        val stage3Px = engine.sideStage3ThresholdPx()
        val hyst = engine.hysteresisMarginPx()

        engine.onDown(10f, 800f, 1000L)
        engine.onMove(120f, 800f, 1016L)
        engine.onMove(10f + engine.sideStage2ThresholdPx() + 20f, 800f, 1032L)
        val handOff3 = engine.onMove(10f + stage3Px + 20f, 800f, 1048L)
        assertEquals(MenuHandOffTarget.QUICK_MENU, handOff3.handOffTarget)
        assertEquals(3, handOff3.handOffLaunchedStage)

        // Cancel menu right near Stage 3 coords (even still above stage3Px!):
        // Reopen lock must restore Stage 2 cleanly and suppress immediate flicker re-hand-off.
        val cancelX = 10f + stage3Px + 8f
        val resumed = engine.resumeFromMenuCancel(cancelX, 800f, 1080L)
        assertEquals(GestureState.SECONDARY_READY, resumed.state)
        assertEquals(2, resumed.stageLevel)
        assertEquals(EdgeAction.PREVIOUS_APP, resumed.activeAction)
        assertEquals(MenuHandOffTarget.NONE, resumed.handOffTarget)

        // Small jitter moves (+12px, +24px < 28dp=84px) MUST stay in Stage 2 without flickering back into menu
        val jitter1 = engine.onMove(cancelX + 12f, 804f, 1096L)
        assertEquals(GestureState.SECONDARY_READY, jitter1.state)
        assertEquals(MenuHandOffTarget.NONE, jitter1.handOffTarget)

        val jitter2 = engine.onMove(cancelX + 24f, 796f, 1112L)
        assertEquals(GestureState.SECONDARY_READY, jitter2.state)
        assertEquals(MenuHandOffTarget.NONE, jitter2.handOffTarget)

        // Releasing right here executes Stage 2 (PREVIOUS_APP) reliably
        val up = engine.onUp(cancelX + 24f, 796f, 1130L)
        assertEquals(GestureCommitType.EXECUTE, up.commitType)
        assertEquals(EdgeAction.PREVIOUS_APP, up.activeAction)

        // Now verify deliberate re-push (>= 28dp = 84px) unlocks and reopens Stage 3 menu
        engine.onDown(10f, 800f, 2000L)
        engine.onMove(10f + stage3Px + 20f, 800f, 2032L)
        engine.resumeFromMenuCancel(cancelX, 800f, 2064L)
        val deliberatePush = engine.onMove(cancelX + 28f * density + 10f, 800f, 2096L)
        assertEquals(MenuHandOffTarget.QUICK_MENU, deliberatePush.handOffTarget)
        assertEquals(3, deliberatePush.handOffLaunchedStage)

        // Also verify radial pullback below Stage 2 threshold restores Stage 2 stably and releases on up
        val radialPullbackX = 10f + 150f // < sideStage2ThresholdPx (~302px), > cancelThresholdPx
        val resumedRadial = engine.resumeFromMenuCancel(radialPullbackX, 800f, 2130L)
        assertEquals(GestureState.SECONDARY_READY, resumedRadial.state)
        val steadyFrame = engine.onMove(radialPullbackX + 4f, 800f, 2146L)
        assertEquals(GestureState.SECONDARY_READY, steadyFrame.state)
        assertEquals(MenuHandOffTarget.NONE, steadyFrame.handOffTarget)
        // Retreating clearly further below (stage3Px - hyst) unlocks reopen lock for future push
        assertTrue(radialPullbackX - 10f < stage3Px - hyst)
        val radialUp = engine.onUp(radialPullbackX + 4f, 800f, 2160L)
        assertEquals(GestureCommitType.EXECUTE, radialUp.commitType)
        assertEquals(EdgeAction.PREVIOUS_APP, radialUp.activeAction)
    }

    @Test
    fun menuRetreatEvaluator_radialArcZeroMisfireAlongTopBottomArc_andCustomLineArcGuard() {
        val radiusPx = 112f * density // 336px
        val deadZonePx = 36f * density // 108px
        val boundaryRatio = 0.42f
        val retreatR = MenuRetreatEvaluator.computeRadialRetreatRadiusPx(
            radiusPx = radiusPx,
            deadZoneRadiusPx = deadZonePx,
            boundaryRatio = boundaryRatio
        )
        assertEquals(radiusPx * 0.42f, retreatR, 0.5f)

        val originX = 1080f
        val originY = 1200f

        // 1. RADIAL_ARC: sliding to top/bottom slots (±65°) or rotary overscroll (±75°) along radius R
        //    has small horizontal pull, yet large radial distance -> MUST NEVER falsely hand back!
        for (deg in listOf(-75f, -65f, 65f, 75f)) {
            val rad = deg * PI / 180.0
            val localX = originX - (cos(rad) * radiusPx).toFloat()
            val localY = originY + (sin(rad) * radiusPx).toFloat()
            val triggered = MenuRetreatEvaluator.shouldHandBack(
                mode = QuickMenuRetreatMode.RADIAL_ARC,
                side = EdgeSide.RIGHT,
                launchedStage = 3,
                localX = localX,
                localY = localY,
                originX = originX,
                originY = originY,
                rawX = localX,
                rawY = localY,
                anchorRawX = originX,
                anchorRawY = originY,
                radiusPx = radiusPx,
                deadZoneRadiusPx = deadZonePx,
                boundaryRatio = boundaryRatio,
                screenAxisPx = screenW.toFloat(),
                density = density,
                hasExitedRetreatZone = true,
                initialRadialDistPx = radiusPx
            )
            assertFalse("RADIAL_ARC must not misfire at $deg deg", triggered)
        }

        // 2. RADIAL_ARC: pulling radially inward inside retreatR -> MUST hand back cleanly
        val insideTriggered = MenuRetreatEvaluator.shouldHandBack(
            mode = QuickMenuRetreatMode.RADIAL_ARC,
            side = EdgeSide.RIGHT,
            launchedStage = 3,
            localX = originX - (retreatR - 18f),
            localY = originY,
            originX = originX,
            originY = originY,
            rawX = originX - (retreatR - 18f),
            rawY = originY,
            anchorRawX = originX,
            anchorRawY = originY,
            radiusPx = radiusPx,
            deadZoneRadiusPx = deadZonePx,
            boundaryRatio = boundaryRatio,
            screenAxisPx = screenW.toFloat(),
            density = density,
            hasExitedRetreatZone = true,
            initialRadialDistPx = radiusPx
        )
        assertTrue("RADIAL_ARC must trigger when radially inside retreatR", insideTriggered)

        // 3. Immediate-cancel guard: if menu opened near retreatR (hasExitedRetreatZone=false)
        //    and finger only drifted 4px (< 16dp=48px), must NOT hand back prematurely
        val immediateBlocked = MenuRetreatEvaluator.shouldHandBack(
            mode = QuickMenuRetreatMode.RADIAL_ARC,
            side = EdgeSide.RIGHT,
            launchedStage = 2,
            localX = originX - (retreatR - 4f),
            localY = originY,
            originX = originX,
            originY = originY,
            rawX = originX - (retreatR - 4f),
            rawY = originY,
            anchorRawX = originX,
            anchorRawY = originY,
            radiusPx = radiusPx,
            deadZoneRadiusPx = deadZonePx,
            boundaryRatio = boundaryRatio,
            screenAxisPx = screenW.toFloat(),
            density = density,
            hasExitedRetreatZone = false,
            initialRadialDistPx = retreatR
        )
        assertFalse("Pullback guard must block immediate cancel on open", immediateBlocked)

        // 4. EDGE_DEADZONE: triggers only inside deadZone * 0.85
        val edgeOutside = MenuRetreatEvaluator.shouldHandBack(
            mode = QuickMenuRetreatMode.EDGE_DEADZONE,
            side = EdgeSide.RIGHT,
            launchedStage = 3,
            localX = originX - (deadZonePx * 0.95f),
            localY = originY,
            originX = originX,
            originY = originY,
            rawX = originX - (deadZonePx * 0.95f),
            rawY = originY,
            anchorRawX = originX,
            anchorRawY = originY,
            radiusPx = radiusPx,
            deadZoneRadiusPx = deadZonePx,
            boundaryRatio = boundaryRatio,
            screenAxisPx = screenW.toFloat(),
            density = density,
            hasExitedRetreatZone = true,
            initialRadialDistPx = radiusPx
        )
        assertFalse(edgeOutside)

        val edgeInside = MenuRetreatEvaluator.shouldHandBack(
            mode = QuickMenuRetreatMode.EDGE_DEADZONE,
            side = EdgeSide.RIGHT,
            launchedStage = 3,
            localX = originX - (deadZonePx * 0.70f),
            localY = originY,
            originX = originX,
            originY = originY,
            rawX = originX - (deadZonePx * 0.70f),
            rawY = originY,
            anchorRawX = originX,
            anchorRawY = originY,
            radiusPx = radiusPx,
            deadZoneRadiusPx = deadZonePx,
            boundaryRatio = boundaryRatio,
            screenAxisPx = screenW.toFloat(),
            density = density,
            hasExitedRetreatZone = true,
            initialRadialDistPx = radiusPx
        )
        assertTrue(edgeInside)

        // 5. CUSTOM_LINE: wide-angle arc guard (>= 35 deg) blocks misfire even when primaryPull < line,
        //    while straight horizontal pullback (< customLinePx) triggers cleanly
        val customRatio = 0.25f // 270px on 1080px screen
        val angleRad45 = 50.0 * PI / 180.0
        val arcX = originX - (cos(angleRad45) * radiusPx).toFloat() // primaryPull ~= 216px < 270px
        val arcY = originY - (sin(angleRad45) * radiusPx).toFloat()
        val customArcBlocked = MenuRetreatEvaluator.shouldHandBack(
            mode = QuickMenuRetreatMode.CUSTOM_LINE,
            side = EdgeSide.RIGHT,
            launchedStage = 3,
            localX = arcX,
            localY = arcY,
            originX = originX,
            originY = originY,
            rawX = arcX,
            rawY = arcY,
            anchorRawX = originX,
            anchorRawY = originY,
            radiusPx = radiusPx,
            deadZoneRadiusPx = deadZonePx,
            boundaryRatio = customRatio,
            screenAxisPx = screenW.toFloat(),
            density = density,
            hasExitedRetreatZone = true,
            initialRadialDistPx = radiusPx
        )
        assertFalse("CUSTOM_LINE arc guard must prevent wide-angle misfire", customArcBlocked)

        val customStraightTriggered = MenuRetreatEvaluator.shouldHandBack(
            mode = QuickMenuRetreatMode.CUSTOM_LINE,
            side = EdgeSide.RIGHT,
            launchedStage = 3,
            localX = originX - 200f,
            localY = originY + 5f,
            originX = originX,
            originY = originY,
            rawX = originX - 200f,
            rawY = originY + 5f,
            anchorRawX = originX,
            anchorRawY = originY,
            radiusPx = radiusPx,
            deadZoneRadiusPx = deadZonePx,
            boundaryRatio = customRatio,
            screenAxisPx = screenW.toFloat(),
            density = density,
            hasExitedRetreatZone = true,
            initialRadialDistPx = radiusPx
        )
        assertTrue("CUSTOM_LINE must trigger on straight horizontal retreat", customStraightTriggered)

        // 6. Dynamic rotary overscroll start angle scales sensibly with rotarySpeed
        assertEquals(80f, MenuRetreatEvaluator.computeDynamicOverscrollStartDeg(0.5f), 0.01f)
        assertEquals(74f, MenuRetreatEvaluator.computeDynamicOverscrollStartDeg(1.0f), 0.01f)
        assertEquals(68f, MenuRetreatEvaluator.computeDynamicOverscrollStartDeg(2.0f), 0.01f)
    }

    @Test
    fun edgeConfig_roundTrip_preservesQuickMenuRetreatModeBoundaryDeadZoneAndRotarySpeed() {
        val original = EdgeConfig(
            quickMenuRetreatMode = QuickMenuRetreatMode.CUSTOM_LINE,
            quickMenuRetreatBoundaryRatio = 0.28f,
            quickMenuDeadZoneDp = 44f,
            quickMenuRotarySpeed = 1.45f,
            appLanguageTag = "ja"
        )
        val restored = EdgeConfig.deserialize(original.serialize())
        assertEquals(original, restored)
        assertEquals(QuickMenuRetreatMode.CUSTOM_LINE, restored.quickMenuRetreatMode)
        assertEquals(0.28f, restored.quickMenuRetreatBoundaryRatio, 0.0001f)
        assertEquals(44f, restored.quickMenuDeadZoneDp, 0.0001f)
        assertEquals(1.45f, restored.quickMenuRotarySpeed, 0.0001f)
        assertEquals("ja", restored.appLanguageTag)

        // Clamping verification for out-of-range values
        val clamped = EdgeConfig(
            quickMenuRetreatMode = QuickMenuRetreatMode.RADIAL_ARC,
            quickMenuRetreatBoundaryRatio = 1.99f,
            quickMenuDeadZoneDp = 4f,
            quickMenuRotarySpeed = 9.0f
        ).clamped()
        assertEquals(0.72f, clamped.quickMenuRetreatBoundaryRatio, 0.0001f)
        assertEquals(20f, clamped.quickMenuDeadZoneDp, 0.0001f)
        assertEquals(2.0f, clamped.quickMenuRotarySpeed, 0.0001f)
    }

    @Test
    fun diagonalMenuActions_doNotHandOffAtZeroMs_andCommitOnRelease() {
        val config = EdgeConfig(
            horizontalAction = EdgeAction.BACK,
            diagonalUpAction = EdgeAction.OPEN_RECENT_SWITCHER,
            diagonalDownAction = EdgeAction.OPEN_QUICK_MENU,
            swipeThresholdDp = 28f,
            diagonalAngleDeg = 28f,
            sideTriggerMode = SecondaryTriggerMode.DISTANCE
        )
        val engine = GestureDetectorEngine(EdgeSide.LEFT, density, screenW, screenH)
        engine.updateConfig(config, density, screenW, screenH)

        // 1. Diagonal-up swipe -> must NOT trigger 0ms one-stroke hand-off during move, commits OPEN_RECENT_SWITCHER on up
        engine.onDown(8f, 1200f, 1000L)
        val diagUpMove = engine.onMove(110f, 1050f, 1040L)
        assertEquals("Diagonal Stage-1 Recent Switcher must not 0ms-handoff during move", MenuHandOffTarget.NONE, diagUpMove.handOffTarget)
        assertEquals(EdgeAction.OPEN_RECENT_SWITCHER, diagUpMove.activeAction)
        val diagUpCommit = engine.onUp(110f, 1050f, 1060L)
        assertEquals(GestureCommitType.EXECUTE, diagUpCommit.commitType)
        assertEquals(EdgeAction.OPEN_RECENT_SWITCHER, diagUpCommit.activeAction)

        // 2. Diagonal-down swipe -> must NOT trigger 0ms hand-off during move, commits OPEN_QUICK_MENU on up
        engine.onDown(8f, 1200f, 2000L)
        val diagDownMove = engine.onMove(110f, 1360f, 2040L)
        assertEquals("Diagonal Stage-1 Quick Menu must not 0ms-handoff during move", MenuHandOffTarget.NONE, diagDownMove.handOffTarget)
        assertEquals(EdgeAction.OPEN_QUICK_MENU, diagDownMove.activeAction)
        val diagDownCommit = engine.onUp(110f, 1360f, 2060L)
        assertEquals(GestureCommitType.EXECUTE, diagDownCommit.commitType)
        assertEquals(EdgeAction.OPEN_QUICK_MENU, diagDownCommit.activeAction)

        // 3. Passing briefly through diagonal then correcting back to horizontal -> commits BACK without misfire
        engine.onDown(8f, 1200f, 3000L)
        val briefDiag = engine.onMove(100f, 1080f, 3030L)
        assertEquals(MenuHandOffTarget.NONE, briefDiag.handOffTarget)
        val correctedHorizontal = engine.onMove(120f, 1205f, 3060L)
        assertEquals(EdgeAction.BACK, correctedHorizontal.activeAction)
        val horizontalCommit = engine.onUp(120f, 1205f, 3090L)
        assertEquals(GestureCommitType.EXECUTE, horizontalCommit.commitType)
        assertEquals(EdgeAction.BACK, horizontalCommit.activeAction)
    }

    @Test
    fun resumeFromMenuCancel_threeConsecutiveCycles_rearmsCleanly() {
        val config = EdgeConfig(
            horizontalAction = EdgeAction.BACK,
            horizontalHoldAction = EdgeAction.OPEN_QUICK_MENU,
            leftStage3Action = EdgeAction.NONE,
            sideTriggerMode = SecondaryTriggerMode.DISTANCE,
            sideStage2DistanceRatio = 0.28f
        )
        val engine = GestureDetectorEngine(EdgeSide.LEFT, density, screenW, screenH)
        engine.updateConfig(config, density, screenW, screenH)

        engine.onDown(8f, 1200f, 1000L)
        var t = 1020L
        repeat(3) { cycle ->
            val openUpdate = engine.onMove(360f, 1200f, t)
            assertEquals("Cycle $cycle must hand off to Quick Menu at Stage 2", MenuHandOffTarget.QUICK_MENU, openUpdate.handOffTarget)
            t += 40L
            val resumed = engine.resumeFromMenuCancel(120f, 1200f, t)
            assertEquals("Cycle $cycle resume must settle cleanly on Stage 1 BACK", EdgeAction.BACK, resumed.activeAction)
            assertEquals("Cycle $cycle resume must not immediately re-open Quick Menu", MenuHandOffTarget.NONE, resumed.handOffTarget)
            t += 40L
        }
        val finalCommit = engine.onUp(120f, 1200f, t)
        assertEquals(GestureCommitType.EXECUTE, finalCommit.commitType)
        assertEquals(EdgeAction.BACK, finalCommit.activeAction)
    }

    @Test
    fun actionExecutor_normalizeAndValidateUrl_handlesValidAndInvalidInputs() {
        assertEquals("https://google.com", ActionExecutor.normalizeAndValidateUrl("google.com"))
        assertEquals("https://example.com/path?a=1", ActionExecutor.normalizeAndValidateUrl("https://example.com/path?a=1"))
        assertEquals("http://sub.domain.org", ActionExecutor.normalizeAndValidateUrl("  http://sub.domain.org  "))
        assertNull(ActionExecutor.normalizeAndValidateUrl("test"))
        assertNull(ActionExecutor.normalizeAndValidateUrl("a b"))
        assertNull(ActionExecutor.normalizeAndValidateUrl(""))
        assertNull(ActionExecutor.normalizeAndValidateUrl("   "))
        assertNull(ActionExecutor.normalizeAndValidateUrl("javascript:alert(1)"))
    }

    @Test
    fun menuRetreatEvaluator_orangeProtectedZoneVsGreenHorizontalCorridorAndSafetyValve() {
        val radiusPx = 112f * density // 336px
        val deadZonePx = 36f * density // 108px
        val originX = 1080f
        val originY = 1200f
        val boundaryPx = 220f
        val initialPull = 320f

        // 1. Orange Protected Zone: moving vertically beyond 44dp (132px) while inside boundary
        //    MUST NEVER hand back (protects top/bottom slots & rotary scroll)
        val orangeVerticalBlocked = MenuRetreatEvaluator.shouldHandBack(
            mode = QuickMenuRetreatMode.RADIAL_ARC,
            side = EdgeSide.RIGHT,
            launchedStage = 2,
            localX = originX - 150f,
            localY = originY + 145f, // > 44dp (132px)
            originX = originX,
            originY = originY,
            rawX = originX - 150f,
            rawY = originY + 145f,
            anchorRawX = originX,
            anchorRawY = originY,
            radiusPx = radiusPx,
            deadZoneRadiusPx = deadZonePx,
            boundaryRatio = 0.42f,
            screenAxisPx = screenW.toFloat(),
            density = density,
            hasExitedRetreatZone = true,
            initialRadialDistPx = initialPull,
            retreatBoundaryPx = boundaryPx,
            initialPullPx = initialPull
        )
        assertFalse("Orange Protected Zone (perp > 44dp) must never cancel menu", orangeVerticalBlocked)

        // 2. Orange Protected Zone: angle > 26 deg even when perp <= 44dp
        val orangeAngleBlocked = MenuRetreatEvaluator.shouldHandBack(
            mode = QuickMenuRetreatMode.RADIAL_ARC,
            side = EdgeSide.RIGHT,
            launchedStage = 2,
            localX = originX - 120f,
            localY = originY - 90f, // atan2(90,120)=36.8° > 26°
            originX = originX,
            originY = originY,
            rawX = originX - 120f,
            rawY = originY - 90f,
            anchorRawX = originX,
            anchorRawY = originY,
            radiusPx = radiusPx,
            deadZoneRadiusPx = deadZonePx,
            boundaryRatio = 0.42f,
            screenAxisPx = screenW.toFloat(),
            density = density,
            hasExitedRetreatZone = true,
            initialRadialDistPx = initialPull,
            retreatBoundaryPx = boundaryPx,
            initialPullPx = initialPull
        )
        assertFalse("Orange Protected Zone (angle > 26°) must never cancel menu", orangeAngleBlocked)

        // 3. Green Horizontal Return Corridor: inside |perp| <= 44dp && |angle| <= 26°,
        //    crossed boundary (< 220px), pulled back >= 22dp (66px) -> MUST hand back
        val greenCorridorTriggered = MenuRetreatEvaluator.shouldHandBack(
            mode = QuickMenuRetreatMode.RADIAL_ARC,
            side = EdgeSide.RIGHT,
            launchedStage = 2,
            localX = originX - 180f, // pullback = 320 - 180 = 140px >= 66px
            localY = originY + 30f,  // perp = 30px <= 132px, angle = 9.5° <= 26°
            originX = originX,
            originY = originY,
            rawX = originX - 180f,
            rawY = originY + 30f,
            anchorRawX = originX,
            anchorRawY = originY,
            radiusPx = radiusPx,
            deadZoneRadiusPx = deadZonePx,
            boundaryRatio = 0.42f,
            screenAxisPx = screenW.toFloat(),
            density = density,
            hasExitedRetreatZone = true,
            initialRadialDistPx = initialPull,
            retreatBoundaryPx = boundaryPx,
            initialPullPx = initialPull
        )
        assertTrue("Green Horizontal Return Corridor must hand back cleanly", greenCorridorTriggered)

        // 4. Edge Safety Valve: returning all the way back to edge (< 18dp && dist < deadZone*0.72)
        //    MUST trigger even with larger angle
        val safetyValveTriggered = MenuRetreatEvaluator.shouldHandBack(
            mode = QuickMenuRetreatMode.RADIAL_ARC,
            side = EdgeSide.RIGHT,
            launchedStage = 2,
            localX = originX - 24f, // 8dp < 18dp
            localY = originY + 45f, // dist = 51px < 77.7px, angle = 62°
            originX = originX,
            originY = originY,
            rawX = originX - 24f,
            rawY = originY + 45f,
            anchorRawX = originX,
            anchorRawY = originY,
            radiusPx = radiusPx,
            deadZoneRadiusPx = deadZonePx,
            boundaryRatio = 0.42f,
            screenAxisPx = screenW.toFloat(),
            density = density,
            hasExitedRetreatZone = true,
            initialRadialDistPx = initialPull,
            retreatBoundaryPx = boundaryPx,
            initialPullPx = initialPull
        )
        assertTrue("Edge Safety Valve must safely hand back near screen edge", safetyValveTriggered)
    }

    @Test
    fun defaultConfig_matchesBeginnerFriendlyPreset() {
        val cfg = EdgeConfig()

        // 1. Geometry & thresholds
        assertEquals(15f, cfg.edgeWidthDp, 0.0001f)
        assertEquals(0.83f, cfg.edgeHeightPercent, 0.0001f)
        assertEquals(0.03f, cfg.edgeBottomOffsetPercent, 0.0001f)
        assertEquals(0.36f, cfg.bottomBarWidthPercent, 0.0001f)
        assertEquals(35f, cfg.bottomBarHeightDp, 0.0001f)
        assertEquals(4f, cfg.bottomBarBottomOffsetDp, 0.0001f)
        assertEquals(0f, cfg.bottomBarHorizontalOffsetPercent, 0.0001f)
        assertEquals(28f, cfg.swipeThresholdDp, 0.0001f)
        assertEquals(34f, cfg.diagonalAngleDeg, 0.0001f)

        // 2. Trigger modes, 3-stage ratios & bottom long-press duration
        assertEquals(SecondaryTriggerMode.DISTANCE, cfg.sideTriggerMode)
        assertEquals(SecondaryTriggerMode.DISTANCE, cfg.bottomTriggerMode)
        assertEquals(0.64f, cfg.sideStage2DistanceRatio, 0.0001f)
        assertEquals(0.79f, cfg.sideStage3DistanceRatio, 0.0001f)
        assertEquals(0.27f, cfg.bottomStage2DistanceRatio, 0.0001f)
        assertEquals(0.50f, cfg.bottomStage3DistanceRatio, 0.0001f)
        assertEquals(300, cfg.bottomLongPressDurationMs)

        // 3. Action assignments
        assertEquals(EdgeAction.BACK, cfg.horizontalAction)
        assertEquals(EdgeAction.RECENTS, cfg.horizontalHoldAction)
        assertEquals(EdgeAction.OPEN_QUICK_MENU, cfg.leftStage3Action)
        assertEquals(EdgeAction.OPEN_QUICK_MENU, cfg.rightStage3Action)
        assertEquals(EdgeAction.FLOAT_CURRENT_APP, cfg.diagonalUpAction)
        assertEquals(EdgeAction.NONE, cfg.diagonalDownAction)
        assertEquals(EdgeAction.HOME, cfg.bottomSwipeUpAction)
        assertEquals(EdgeAction.RECENTS, cfg.bottomSwipeHoldAction)
        assertEquals(EdgeAction.FLOAT_CURRENT_APP, cfg.bottomStage3Action)
        assertTrue(cfg.bottomLongPressEnabled)
        assertEquals(EdgeAction.CIRCLE_TO_SEARCH, cfg.bottomLongPressAction)
        assertFalse(cfg.hyperOsRecentsCompatMode)

        // 4. Visuals, haptics & Liquid Glass
        assertEquals(FeedbackStyle.BEZIER_BUBBLE, cfg.feedbackStyle)
        assertEquals(VisualTheme.AUTO, cfg.visualTheme)
        assertEquals(0.60f, cfg.feedbackAlpha, 0.0001f)
        assertEquals(0.0f, cfg.edgeGuideAlpha, 0.0001f)
        assertEquals(0.78f, cfg.bottomPillAlpha, 0.0001f)
        assertEquals(HapticStrength.MEDIUM, cfg.hapticStrength)
        assertTrue(cfg.liquidGlassEnabled)
        assertEquals(1.50f, cfg.liquidGlassRefraction, 0.0001f)
        assertEquals(34f, cfg.dropletPeakWidthDp, 0.0001f)
        assertEquals(173f, cfg.dropletBaseWidthDp, 0.0001f)
        assertEquals(EdgeVisualStyle.DEFAULT_DROPLET, cfg.edgeVisualStyle)

        // 5. Quick Menu (5 default slots)
        assertEquals(QuickArcTriggerMethod.GESTURE_ACTION, cfg.quickArcTriggerMethod)
        assertEquals(142f, cfg.quickMenuRadiusDp, 0.0001f)
        assertEquals(0.30f, cfg.quickMenuDimAlpha, 0.0001f)
        assertEquals(
            listOf(
                EdgeAction.RECENTS,
                EdgeAction.HOME,
                EdgeAction.SCREENSHOT,
                EdgeAction.FLOAT_CURRENT_APP,
                EdgeAction.TRANSLATE_SCREEN_ONCE
            ),
            cfg.quickMenuSlots.map { it.action }
        )

        // 6. Smart avoidance
        assertEquals(ImeAvoidanceMode.SHIFT_UP, cfg.imeAvoidanceMode)
        assertTrue(cfg.showBottomBarDuringIme)
        assertEquals(LandscapeMode.DOUBLE_SWIPE_CONFIRM, cfg.landscapeMode)

        // 7. Clamped & partial deserialize fallbacks match defaults identically
        assertEquals(cfg, cfg.clamped())
        assertEquals(cfg, EdgeConfig.deserialize("leftEnabled=true\n"))

        // 8. End-to-end GestureDetectorEngine verification on default EdgeConfig()
        val sideEngine = GestureDetectorEngine(EdgeSide.LEFT, density, screenW, screenH)
        sideEngine.onDown(10f, 900f, 1000L)
        val sideS1 = sideEngine.onMove(120f, 900f, 1016L)
        assertEquals(GestureState.PRIMARY_READY, sideS1.state)
        assertEquals(EdgeAction.BACK, sideS1.activeAction)
        assertFalse(sideS1.shouldScheduleHold) // DISTANCE mode

        val sideS2 = sideEngine.onMove(10f + screenW * 0.66f, 900f, 1032L)
        assertEquals(GestureState.SECONDARY_READY, sideS2.state)
        assertEquals(EdgeAction.RECENTS, sideS2.activeAction)

        val sideS3 = sideEngine.onMove(10f + screenW * 0.81f, 900f, 1048L)
        assertEquals(MenuHandOffTarget.QUICK_MENU, sideS3.handOffTarget)
        assertEquals(EdgeAction.OPEN_QUICK_MENU, sideS3.activeAction)
        assertEquals(3, sideS3.handOffLaunchedStage)

        val bottomEngine = GestureDetectorEngine(EdgeSide.BOTTOM, density, screenW, screenH)
        bottomEngine.onDown(540f, 2380f, 2000L)
        val bottomS1 = bottomEngine.onMove(540f, 2240f, 2016L)
        assertEquals(GestureState.PRIMARY_READY, bottomS1.state)
        assertEquals(EdgeAction.HOME, bottomS1.activeAction)
        assertFalse(bottomS1.shouldScheduleHold) // DISTANCE mode

        val bottomS2 = bottomEngine.onMove(540f, 2380f - screenH * 0.30f, 2032L)
        assertEquals(GestureState.SECONDARY_READY, bottomS2.state)
        assertEquals(EdgeAction.RECENTS, bottomS2.activeAction)

        val bottomS3 = bottomEngine.onMove(540f, 2380f - screenH * 0.54f, 2048L)
        assertEquals(GestureState.TERTIARY_READY, bottomS3.state)
        assertEquals(EdgeAction.FLOAT_CURRENT_APP, bottomS3.activeAction)
        assertEquals(EdgeAction.FLOAT_CURRENT_APP, bottomEngine.onUp(540f, 2380f - screenH * 0.54f, 2064L).activeAction)
    }
}



