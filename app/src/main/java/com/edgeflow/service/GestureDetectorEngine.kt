package com.edgeflow.service

import com.edgeflow.data.EdgeAction
import com.edgeflow.data.EdgeActionType
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.EdgeSide
import com.edgeflow.data.QuickArcTriggerMethod
import com.edgeflow.data.SecondaryTriggerMode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

enum class GestureState {
    IDLE,
    TRACKING,
    PRIMARY_READY,
    SECONDARY_READY,
    TERTIARY_READY,
    TRIGGERED_LONG_PRESS,
    CANCELLED
}

enum class GestureDirection {
    NONE,
    HORIZONTAL,
    DIAGONAL_UP,
    DIAGONAL_DOWN,
    BOTTOM_UP,
    BOTTOM_LONG_PRESS
}

enum class GestureHapticEvent {
    NONE,
    PRIMARY_TRIGGERED,
    SECONDARY_TRIGGERED,
    TERTIARY_TRIGGERED
}

enum class GestureCommitType {
    NONE,
    EXECUTE,
    ARMED_CONFIRM
}

enum class MenuHandOffTarget {
    NONE,
    QUICK_MENU,
    RECENT_SWITCHER
}

/**
 * Mutable, pre-allocated event result returned by [GestureDetectorEngine] to guarantee
 * zero heap allocations per MotionEvent frame at 120Hz.
 */
class GestureUpdate {
    var state: GestureState = GestureState.IDLE
    var direction: GestureDirection = GestureDirection.NONE
    var activeAction: EdgeAction = EdgeAction.NONE
    var hapticEvent: GestureHapticEvent = GestureHapticEvent.NONE
    var commitType: GestureCommitType = GestureCommitType.NONE
    var handOffTarget: MenuHandOffTarget = MenuHandOffTarget.NONE
    var handOffLaunchedStage: Int = 0
    var handOffRetreatBoundaryPx: Float = 0f
    var stageLevel: Int = 0
    var hasStage3Configured: Boolean = false
    var pullProgress: Float = 0f
    var secondaryProgress: Float = 0f
    var tertiaryProgress: Float = 0f
    var dragDeltaX: Float = 0f
    var dragDeltaY: Float = 0f
    var anchorX: Float = 0f
    var anchorY: Float = 0f
    var currentX: Float = 0f
    var currentY: Float = 0f
    var shouldScheduleHold: Boolean = false
    var shouldCancelHold: Boolean = false
    var holdDelayMs: Long = 0L
    var shouldScheduleLongPress: Boolean = false
    var shouldCancelLongPress: Boolean = false
    var longPressDelayMs: Long = 0L
    var isDoubleSwipeConfirmPending: Boolean = false

    fun resetFrameFlags() {
        hapticEvent = GestureHapticEvent.NONE
        commitType = GestureCommitType.NONE
        handOffTarget = MenuHandOffTarget.NONE
        handOffLaunchedStage = 0
        handOffRetreatBoundaryPx = 0f
        shouldScheduleHold = false
        shouldCancelHold = false
        holdDelayMs = 0L
        shouldScheduleLongPress = false
        shouldCancelLongPress = false
        longPressDelayMs = 0L
    }
}

/**
 * Pure-Kotlin, zero-allocation 3-stage gesture state machine with:
 * - Independent Side vs Bottom trigger modes (DISTANCE / HOLD / BOTH)
 * - 3-stage distance ratios up to 85% and 3-stage hold timers (Stage 1 -> Stage 2 -> Stage 3)
 * - Smart auto-2-stage behavior when Stage 3 action is NONE (zero wasted stage)
 * - 14dp hysteresis step-down (Stage 3 -> Stage 2 -> Stage 1 -> Cancel dead-zone)
 * - Strict anti-misfire guarantee: standard actions NEVER fire mid-drag; only on ACTION_UP
 * - Smart one-stroke menu hand-off: instant 0ms when no higher stage exists, or brief pause
 *   settle when Stage 3 is configured so fast deep swipes cleanly pass through to Stage 3
 */
class GestureDetectorEngine(
    val side: EdgeSide,
    private var density: Float,
    private var screenWidthPx: Int = DEFAULT_SCREEN_WIDTH_PX,
    private var screenHeightPx: Int = DEFAULT_SCREEN_HEIGHT_PX
) {
    private var config: EdgeConfig = EdgeConfig()
    private var requireDoubleSwipeConfirm: Boolean = false

    private var state: GestureState = GestureState.IDLE
    private var direction: GestureDirection = GestureDirection.NONE
    private var activeAction: EdgeAction = EdgeAction.NONE

    private var downX: Float = 0f
    private var downY: Float = 0f
    private var holdAnchorX: Float = 0f
    private var holdAnchorY: Float = 0f
    private var peakInwardPx: Float = 0f
    private var holdScheduled: Boolean = false
    private var pendingStage2MenuHandOff: Boolean = false
    private var longPressScheduled: Boolean = false
    private var armedConfirmUntilMs: Long = 0L
    private var lastHandOffDirection: GestureDirection = GestureDirection.NONE
    private var lastHandOffStage: Int = 0
    private var menuReopenLocked: Boolean = false
    private var reopenLockedStage: Int = 0
    private var reopenResumePullPx: Float = 0f
    private var resumedStage2DemoteFloorPx: Float = Float.MAX_VALUE

    // Precomputed pixel thresholds
    private var swipeThresholdPx: Float = 0f
    private var cancelThresholdPx: Float = 0f
    private var visualMaxPullPx: Float = 0f
    private var sideStage2Px: Float = 0f
    private var sideStage3Px: Float = 0f
    private var bottomStage2Px: Float = 0f
    private var bottomStage3Px: Float = 0f
    private var hysteresisPx: Float = 0f
    private var holdJitterPx: Float = 0f
    private var longPressSlopPx: Float = 0f
    private var touchSlopPx: Float = 0f

    // Reusable output instance — zero heap allocations during 120Hz touch tracking
    private val update = GestureUpdate()

    init {
        recomputeMetrics()
    }

    fun updateConfig(
        newConfig: EdgeConfig,
        newDensity: Float = density,
        newScreenWidthPx: Int = screenWidthPx,
        newScreenHeightPx: Int = screenHeightPx
    ) {
        config = newConfig.clamped()
        density = newDensity
        if (newScreenWidthPx > 0) screenWidthPx = newScreenWidthPx
        if (newScreenHeightPx > 0) screenHeightPx = newScreenHeightPx
        recomputeMetrics()
    }

    fun setDoubleSwipeConfirmRequired(required: Boolean) {
        requireDoubleSwipeConfirm = required
        if (!required) {
            armedConfirmUntilMs = 0L
        }
    }

    fun isDoubleSwipeArmed(nowMs: Long): Boolean =
        requireDoubleSwipeConfirm && nowMs <= armedConfirmUntilMs

    fun sideStage2ThresholdPx(): Float = sideStage2Px
    fun sideStage3ThresholdPx(): Float = sideStage3Px
    fun bottomStage2ThresholdPx(): Float = bottomStage2Px
    fun bottomStage3ThresholdPx(): Float = bottomStage3Px
    fun hysteresisMarginPx(): Float = hysteresisPx

    // Compatibility aliases
    fun horizontalLongSwipeThresholdPx(): Float = sideStage2Px
    fun bottomLongSwipeThresholdPx(): Float = bottomStage2Px

    private fun recomputeMetrics() {
        swipeThresholdPx = config.swipeThresholdDp * density
        cancelThresholdPx = swipeThresholdPx * CANCEL_THRESHOLD_RATIO
        hysteresisPx = HYSTERESIS_DP * density
        holdJitterPx = HOLD_JITTER_DP * density
        longPressSlopPx = LONG_PRESS_SLOP_DP * density
        touchSlopPx = TOUCH_SLOP_DP * density

        val minStage2Px = swipeThresholdPx + MIN_STAGE2_EXTRA_DP * density
        val minStage3GapPx = MIN_STAGE3_EXTRA_DP * density

        sideStage2Px = max(minStage2Px, screenWidthPx * config.sideStage2DistanceRatio)
        sideStage3Px = max(sideStage2Px + minStage3GapPx, screenWidthPx * config.sideStage3DistanceRatio)

        bottomStage2Px = max(minStage2Px, screenHeightPx * config.bottomStage2DistanceRatio)
        bottomStage3Px = max(bottomStage2Px + minStage3GapPx, screenHeightPx * config.bottomStage3DistanceRatio)

        visualMaxPullPx = max(swipeThresholdPx * 2.4f, 64f * density)
    }

    fun onDown(x: Float, y: Float, timeMs: Long): GestureUpdate {
        downX = x
        downY = y
        holdAnchorX = x
        holdAnchorY = y
        peakInwardPx = 0f
        state = GestureState.TRACKING
        direction = GestureDirection.NONE
        activeAction = EdgeAction.NONE
        holdScheduled = false
        pendingStage2MenuHandOff = false
        longPressScheduled = false
        lastHandOffDirection = GestureDirection.NONE
        lastHandOffStage = 0
        menuReopenLocked = false
        reopenLockedStage = 0
        reopenResumePullPx = 0f
        resumedStage2DemoteFloorPx = Float.MAX_VALUE

        update.resetFrameFlags()
        update.state = state
        update.direction = direction
        update.activeAction = activeAction
        update.stageLevel = 0
        update.hasStage3Configured = config.hasStage3For(side)
        update.pullProgress = 0f
        update.secondaryProgress = 0f
        update.tertiaryProgress = 0f
        update.dragDeltaX = 0f
        update.dragDeltaY = 0f
        update.anchorX = downX
        update.anchorY = downY
        update.currentX = x
        update.currentY = y
        update.isDoubleSwipeConfirmPending = isDoubleSwipeArmed(timeMs)

        if (side == EdgeSide.BOTTOM &&
            config.bottomLongPressEnabled &&
            !config.bottomLongPressAction.isNone
        ) {
            longPressScheduled = true
            update.shouldScheduleLongPress = true
            update.longPressDelayMs = config.bottomLongPressDurationMs.toLong()
        } else if (side != EdgeSide.BOTTOM &&
            config.quickArcTriggerMethod == QuickArcTriggerMethod.EDGE_STATIONARY_HOLD
        ) {
            longPressScheduled = true
            update.shouldScheduleLongPress = true
            update.longPressDelayMs = config.quickArcEdgeHoldMs.toLong()
        }

        return update
    }

    fun onMove(x: Float, y: Float, timeMs: Long): GestureUpdate {
        update.resetFrameFlags()
        update.anchorX = downX
        update.anchorY = downY
        update.currentX = x
        update.currentY = y
        update.dragDeltaX = x - downX
        update.dragDeltaY = y - downY
        update.hasStage3Configured = config.hasStage3For(side)
        update.isDoubleSwipeConfirmPending = isDoubleSwipeArmed(timeMs)

        if (state == GestureState.IDLE ||
            state == GestureState.CANCELLED ||
            state == GestureState.TRIGGERED_LONG_PRESS
        ) {
            update.state = state
            update.direction = direction
            update.activeAction = activeAction
            update.stageLevel = 0
            update.pullProgress = 0f
            update.secondaryProgress = 0f
            update.tertiaryProgress = 0f
            return update
        }

        val dx = x - downX
        val dy = y - downY

        if (side == EdgeSide.BOTTOM) {
            processBottomMove(dx, dy, x, y, timeMs)
        } else {
            processSideMove(dx, dy, x, y, timeMs)
        }

        update.state = state
        update.direction = direction
        update.stageLevel = stageLevelFor(state)
        if (update.handOffTarget == MenuHandOffTarget.NONE) {
            update.activeAction = activeAction
        }
        return update
    }

    private fun processSideMove(dx: Float, dy: Float, x: Float, y: Float, timeMs: Long) {
        val inwardDx = if (side == EdgeSide.LEFT) dx else -dx
        val totalDistance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val hasStage3 = !resolveTertiaryAction(GestureDirection.HORIZONTAL).isNone

        // Cancel side-edge stationary long-press as soon as the finger starts swiping
        if (longPressScheduled && totalDistance >= longPressSlopPx) {
            cancelLongPressTimer()
        }

        // Guard against vertical bezel scrolling before primary stage locks in
        if (state == GestureState.TRACKING && totalDistance >= touchSlopPx) {
            if (inwardDx <= 0f) {
                cancelTracking()
                return
            }
            val angleDeg = Math.toDegrees(atan2(abs(dy).toDouble(), inwardDx.toDouble())).toFloat()
            if (angleDeg > MAX_SIDE_TRIGGER_ANGLE_DEG) {
                cancelTracking()
                return
            }
        }

        val effectivePull = inwardDx.coerceAtLeast(0f)
        updateReopenLockOnMove(effectivePull, sideStage2Px, sideStage3Px)
        if (effectivePull > peakInwardPx) {
            peakInwardPx = effectivePull
        }
        update.pullProgress = (effectivePull / visualMaxPullPx).coerceIn(0f, 1.35f)
        update.secondaryProgress = (effectivePull / sideStage2Px).coerceIn(0f, 1.25f)
        update.tertiaryProgress = if (hasStage3) {
            (effectivePull / sideStage3Px).coerceIn(0f, 1.25f)
        } else {
            0f
        }

        val meetsPrimaryThreshold = totalDistance >= swipeThresholdPx &&
            inwardDx >= swipeThresholdPx * MIN_INWARD_RATIO

        if (state == GestureState.TRACKING) {
            if (meetsPrimaryThreshold) {
                cancelLongPressTimer()
                val resolvedDir = classifySideDirection(inwardDx, dy)
                val primaryAct = resolvePrimaryAction(resolvedDir)
                direction = resolvedDir
                activeAction = primaryAct
                state = GestureState.PRIMARY_READY
                if (!primaryAct.isNone) {
                    update.hapticEvent = GestureHapticEvent.PRIMARY_TRIGGERED
                }
                if (resolvedDir == GestureDirection.HORIZONTAL) {
                    // Immediate jump to Stage 3 or Stage 2 on very fast single-frame flings
                    if (usesDistanceMode() && hasStage3 && inwardDx >= sideStage3Px) {
                        promoteToTertiary(GestureDirection.HORIZONTAL, timeMs)
                        return
                    }
                    if (usesDistanceMode() && inwardDx >= sideStage2Px && !config.horizontalHoldAction.isNone) {
                        promoteToSecondary(GestureDirection.HORIZONTAL, x, y, timeMs, fromHold = false)
                        return
                    }
                    if (usesHoldMode()) {
                        maybeScheduleHoldForStage2(GestureDirection.HORIZONTAL, x, y)
                    }
                }
            }
            return
        }

        // Zero-conflict PULL_AND_RETURN (U-turn): pulled horizontally past Stage 1, then returned near edge
        if (config.quickArcTriggerMethod == QuickArcTriggerMethod.PULL_AND_RETURN &&
            state == GestureState.PRIMARY_READY &&
            direction == GestureDirection.HORIZONTAL &&
            peakInwardPx >= swipeThresholdPx &&
            inwardDx <= swipeThresholdPx * PULL_RETURN_THRESHOLD_RATIO &&
            (peakInwardPx - inwardDx) >= swipeThresholdPx * PULL_RETURN_DELTA_RATIO &&
            canImmediatelyHandOff(timeMs)
        ) {
            update.hapticEvent = GestureHapticEvent.SECONDARY_TRIGGERED
            triggerMenuHandOff(EdgeAction.OPEN_QUICK_MENU, timeMs, launchedStage = 0)
            return
        }

        // Pull all the way back into origin dead-zone -> demote to TRACKING (cancel on release)
        if (inwardDx < cancelThresholdPx) {
            state = GestureState.TRACKING
            direction = GestureDirection.NONE
            activeAction = EdgeAction.NONE
            peakInwardPx = 0f
            cancelHoldTimer()
            return
        }

        // Currently in TERTIARY_READY (Stage 3) -> hysteresis step-down to Stage 2 (or Stage 1 if Stage 2 is NONE)
        if (state == GestureState.TERTIARY_READY) {
            val demoteFrom3 = (sideStage3Px - hysteresisPx).coerceAtLeast(sideStage2Px)
            if (usesDistanceMode() && inwardDx < demoteFrom3) {
                val secondaryAct = resolveSecondaryAction(GestureDirection.HORIZONTAL)
                val demoteFrom2 = (sideStage2Px - hysteresisPx).coerceAtLeast(swipeThresholdPx)
                if (!secondaryAct.isNone && inwardDx >= demoteFrom2) {
                    state = GestureState.SECONDARY_READY
                    direction = GestureDirection.HORIZONTAL
                    activeAction = secondaryAct
                    update.hapticEvent = GestureHapticEvent.SECONDARY_TRIGGERED
                    schedulePostSecondaryTimers(GestureDirection.HORIZONTAL, secondaryAct, x, y)
                } else {
                    state = GestureState.PRIMARY_READY
                    direction = GestureDirection.HORIZONTAL
                    activeAction = config.horizontalAction
                    update.hapticEvent = GestureHapticEvent.PRIMARY_TRIGGERED
                    if (usesHoldMode()) {
                        maybeScheduleHoldForStage2(GestureDirection.HORIZONTAL, x, y)
                    }
                }
            }
            return
        }

        // Currently in SECONDARY_READY (Stage 2) -> promote to Stage 3 OR hysteresis demote to Stage 1
        if (state == GestureState.SECONDARY_READY) {
            if (inwardDx >= sideStage2Px) {
                resumedStage2DemoteFloorPx = Float.MAX_VALUE
            }
            if (usesDistanceMode() && hasStage3 && inwardDx >= sideStage3Px) {
                if (!isStageReopenLocked(3, effectivePull)) {
                    promoteToTertiary(GestureDirection.HORIZONTAL, timeMs)
                    return
                }
            }
            val baseDemote = (sideStage2Px - hysteresisPx).coerceAtLeast(swipeThresholdPx)
            val demoteThreshold = if (resumedStage2DemoteFloorPx < baseDemote) {
                resumedStage2DemoteFloorPx
            } else {
                baseDemote
            }
            if (usesDistanceMode() && inwardDx < demoteThreshold) {
                resumedStage2DemoteFloorPx = Float.MAX_VALUE
                cancelHoldTimer()
                state = GestureState.PRIMARY_READY
                direction = GestureDirection.HORIZONTAL
                activeAction = config.horizontalAction
                update.hapticEvent = GestureHapticEvent.PRIMARY_TRIGGERED
                if (usesHoldMode()) {
                    maybeScheduleHoldForStage2(GestureDirection.HORIZONTAL, x, y)
                }
                return
            }
            if (holdScheduled) {
                val movedFromHoldAnchor = hypot(
                    (x - holdAnchorX).toDouble(),
                    (y - holdAnchorY).toDouble()
                ).toFloat()
                if (movedFromHoldAnchor > holdJitterPx) {
                    schedulePostSecondaryTimers(GestureDirection.HORIZONTAL, activeAction, x, y)
                }
            }
            return
        }

        // Currently in PRIMARY_READY (Stage 1)
        val newDir = if (direction == GestureDirection.HORIZONTAL &&
            inwardDx >= swipeThresholdPx * HORIZONTAL_LOCK_DISTANCE_RATIO
        ) {
            classifySideDirection(inwardDx, dy, config.diagonalAngleDeg + HORIZONTAL_LOCK_EXTRA_ANGLE_DEG)
        } else {
            classifySideDirection(inwardDx, dy, config.diagonalAngleDeg)
        }
        if (newDir != direction) {
            direction = newDir
            activeAction = resolvePrimaryAction(newDir)
            if (newDir == GestureDirection.HORIZONTAL && usesHoldMode()) {
                maybeScheduleHoldForStage2(newDir, x, y)
            } else {
                cancelHoldTimer()
            }
        }

        if (direction == GestureDirection.HORIZONTAL) {
            if (usesDistanceMode()) {
                if (hasStage3 && inwardDx >= sideStage3Px && !isStageReopenLocked(3, effectivePull)) {
                    promoteToTertiary(GestureDirection.HORIZONTAL, timeMs)
                    return
                }
                if (inwardDx >= sideStage2Px && !config.horizontalHoldAction.isNone &&
                    !isStageReopenLocked(2, effectivePull)
                ) {
                    promoteToSecondary(GestureDirection.HORIZONTAL, x, y, timeMs, fromHold = false)
                    return
                }
            }
            if (usesHoldMode()) {
                val movedFromHoldAnchor = hypot(
                    (x - holdAnchorX).toDouble(),
                    (y - holdAnchorY).toDouble()
                ).toFloat()
                if (movedFromHoldAnchor > holdJitterPx) {
                    maybeScheduleHoldForStage2(GestureDirection.HORIZONTAL, x, y)
                }
            }
        }
    }

    private fun processBottomMove(dx: Float, dy: Float, x: Float, y: Float, timeMs: Long) {
        val upwardDy = -dy
        val totalDistance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val hasStage3 = !config.bottomStage3Action.isNone

        // Cancel stationary long-press as soon as the finger glides beyond micro slop
        if (longPressScheduled && totalDistance >= longPressSlopPx) {
            cancelLongPressTimer()
        }

        if (state == GestureState.TRACKING && totalDistance >= touchSlopPx) {
            if (upwardDy <= 0f || abs(dx) > upwardDy * MAX_BOTTOM_HORIZONTAL_RATIO) {
                cancelTracking()
                return
            }
        }

        val effectivePull = upwardDy.coerceAtLeast(0f)
        updateReopenLockOnMove(effectivePull, bottomStage2Px, bottomStage3Px)
        update.pullProgress = (effectivePull / visualMaxPullPx).coerceIn(0f, 1.35f)
        update.secondaryProgress = (effectivePull / bottomStage2Px).coerceIn(0f, 1.25f)
        update.tertiaryProgress = if (hasStage3) {
            (effectivePull / bottomStage3Px).coerceIn(0f, 1.25f)
        } else {
            0f
        }

        val meetsPrimaryThreshold = upwardDy >= swipeThresholdPx &&
            abs(dx) <= upwardDy * MAX_BOTTOM_HORIZONTAL_RATIO

        if (state == GestureState.TRACKING) {
            if (meetsPrimaryThreshold) {
                cancelLongPressTimer()
                direction = GestureDirection.BOTTOM_UP
                activeAction = config.bottomSwipeUpAction
                state = GestureState.PRIMARY_READY
                if (!activeAction.isNone) {
                    update.hapticEvent = GestureHapticEvent.PRIMARY_TRIGGERED
                }
                if (usesDistanceMode() && hasStage3 && upwardDy >= bottomStage3Px) {
                    promoteToTertiary(GestureDirection.BOTTOM_UP, timeMs)
                    return
                }
                if (usesDistanceMode() && upwardDy >= bottomStage2Px && !config.bottomSwipeHoldAction.isNone) {
                    promoteToSecondary(GestureDirection.BOTTOM_UP, x, y, timeMs, fromHold = false)
                    return
                }
                if (usesHoldMode()) {
                    maybeScheduleHoldForStage2(GestureDirection.BOTTOM_UP, x, y)
                }
            }
            return
        }

        // Dead-zone cancel near bottom edge
        if (upwardDy < cancelThresholdPx) {
            state = GestureState.TRACKING
            direction = GestureDirection.NONE
            activeAction = EdgeAction.NONE
            cancelHoldTimer()
            return
        }

        // TERTIARY_READY (Stage 3) -> hysteresis step-down
        if (state == GestureState.TERTIARY_READY) {
            val demoteFrom3 = (bottomStage3Px - hysteresisPx).coerceAtLeast(bottomStage2Px)
            if (usesDistanceMode() && upwardDy < demoteFrom3) {
                val secondaryAct = config.bottomSwipeHoldAction
                val demoteFrom2 = (bottomStage2Px - hysteresisPx).coerceAtLeast(swipeThresholdPx)
                if (!secondaryAct.isNone && upwardDy >= demoteFrom2) {
                    state = GestureState.SECONDARY_READY
                    direction = GestureDirection.BOTTOM_UP
                    activeAction = secondaryAct
                    update.hapticEvent = GestureHapticEvent.SECONDARY_TRIGGERED
                    schedulePostSecondaryTimers(GestureDirection.BOTTOM_UP, secondaryAct, x, y)
                } else {
                    state = GestureState.PRIMARY_READY
                    direction = GestureDirection.BOTTOM_UP
                    activeAction = config.bottomSwipeUpAction
                    update.hapticEvent = GestureHapticEvent.PRIMARY_TRIGGERED
                    if (usesHoldMode()) {
                        maybeScheduleHoldForStage2(GestureDirection.BOTTOM_UP, x, y)
                    }
                }
            }
            return
        }

        // SECONDARY_READY (Stage 2) -> promote to Stage 3 OR hysteresis step-down to Stage 1
        if (state == GestureState.SECONDARY_READY) {
            if (upwardDy >= bottomStage2Px) {
                resumedStage2DemoteFloorPx = Float.MAX_VALUE
            }
            if (usesDistanceMode() && hasStage3 && upwardDy >= bottomStage3Px) {
                if (!isStageReopenLocked(3, effectivePull)) {
                    promoteToTertiary(GestureDirection.BOTTOM_UP, timeMs)
                    return
                }
            }
            val baseDemote = (bottomStage2Px - hysteresisPx).coerceAtLeast(swipeThresholdPx)
            val demoteThreshold = if (resumedStage2DemoteFloorPx < baseDemote) {
                resumedStage2DemoteFloorPx
            } else {
                baseDemote
            }
            if (usesDistanceMode() && upwardDy < demoteThreshold) {
                resumedStage2DemoteFloorPx = Float.MAX_VALUE
                cancelHoldTimer()
                state = GestureState.PRIMARY_READY
                direction = GestureDirection.BOTTOM_UP
                activeAction = config.bottomSwipeUpAction
                update.hapticEvent = GestureHapticEvent.PRIMARY_TRIGGERED
                if (usesHoldMode()) {
                    maybeScheduleHoldForStage2(GestureDirection.BOTTOM_UP, x, y)
                }
                return
            }
            if (holdScheduled) {
                val movedFromHoldAnchor = hypot(
                    (x - holdAnchorX).toDouble(),
                    (y - holdAnchorY).toDouble()
                ).toFloat()
                if (movedFromHoldAnchor > holdJitterPx) {
                    schedulePostSecondaryTimers(GestureDirection.BOTTOM_UP, activeAction, x, y)
                }
            }
            return
        }

        // PRIMARY_READY (Stage 1) -> check Stage 3 / Stage 2 promotion
        if (usesDistanceMode()) {
            if (hasStage3 && upwardDy >= bottomStage3Px && !isStageReopenLocked(3, effectivePull)) {
                promoteToTertiary(GestureDirection.BOTTOM_UP, timeMs)
                return
            }
            if (upwardDy >= bottomStage2Px && !config.bottomSwipeHoldAction.isNone &&
                !isStageReopenLocked(2, effectivePull)
            ) {
                promoteToSecondary(GestureDirection.BOTTOM_UP, x, y, timeMs, fromHold = false)
                return
            }
        }

        if (usesHoldMode()) {
            val movedFromHoldAnchor = hypot(
                (x - holdAnchorX).toDouble(),
                (y - holdAnchorY).toDouble()
            ).toFloat()
            if (movedFromHoldAnchor > holdJitterPx) {
                maybeScheduleHoldForStage2(GestureDirection.BOTTOM_UP, x, y)
            }
        }
    }

    fun onHoldTimeout(timeMs: Long): GestureUpdate {
        update.resetFrameFlags()
        update.isDoubleSwipeConfirmPending = isDoubleSwipeArmed(timeMs)
        update.hasStage3Configured = config.hasStage3For(side)
        holdScheduled = false

        if (state == GestureState.PRIMARY_READY && usesHoldMode()) {
            val secondaryAct = resolveSecondaryAction(direction)
            val tertiaryAct = resolveTertiaryAction(direction)
            if (!secondaryAct.isNone && !(menuReopenLocked && reopenLockedStage == 2)) {
                promoteToSecondary(direction, holdAnchorX, holdAnchorY, timeMs, fromHold = true)
                return update
            } else if (!tertiaryAct.isNone && !(menuReopenLocked && reopenLockedStage == 3)) {
                // Edge case: Stage 2 is NONE, Stage 3 is assigned
                promoteToTertiary(direction, timeMs)
                return update
            }
        } else if (state == GestureState.SECONDARY_READY) {
            if (pendingStage2MenuHandOff) {
                pendingStage2MenuHandOff = false
                if (activeAction.isOneStrokeMenu && canImmediatelyHandOff(timeMs) &&
                    !(menuReopenLocked && reopenLockedStage == 2)
                ) {
                    triggerMenuHandOff(activeAction, timeMs, launchedStage = 0)
                    return update
                }
            }
            if (usesHoldMode()) {
                val tertiaryAct = resolveTertiaryAction(direction)
                if (!tertiaryAct.isNone && !(menuReopenLocked && reopenLockedStage == 3)) {
                    promoteToTertiary(direction, timeMs)
                    return update
                }
            }
        }

        update.state = state
        update.direction = direction
        update.stageLevel = stageLevelFor(state)
        update.activeAction = activeAction
        return update
    }

    /**
     * Fired when the user holds stationary on:
     * - The bottom bar ([EdgeConfig.bottomLongPressDurationMs]) -> Circle to Search / configured action
     * - Left/Right edge bezel ([EdgeConfig.quickArcEdgeHoldMs]) when [QuickArcTriggerMethod.EDGE_STATIONARY_HOLD] is active
     */
    fun onLongPressTimeout(timeMs: Long): GestureUpdate {
        update.resetFrameFlags()
        update.isDoubleSwipeConfirmPending = isDoubleSwipeArmed(timeMs)
        update.hasStage3Configured = config.hasStage3For(side)
        longPressScheduled = false

        if (state != GestureState.TRACKING) {
            update.state = state
            update.direction = direction
            update.stageLevel = stageLevelFor(state)
            return update
        }

        if (side == EdgeSide.BOTTOM &&
            config.bottomLongPressEnabled &&
            !config.bottomLongPressAction.isNone
        ) {
            val action = config.bottomLongPressAction
            state = GestureState.TRIGGERED_LONG_PRESS
            direction = GestureDirection.BOTTOM_LONG_PRESS
            activeAction = action
            update.hapticEvent = GestureHapticEvent.SECONDARY_TRIGGERED
            update.pullProgress = 0f
            update.secondaryProgress = 0f
            update.tertiaryProgress = 0f

            if (requireDoubleSwipeConfirm && timeMs > armedConfirmUntilMs) {
                armedConfirmUntilMs = timeMs + DOUBLE_SWIPE_CONFIRM_WINDOW_MS
                update.commitType = GestureCommitType.ARMED_CONFIRM
                update.activeAction = EdgeAction.NONE
                update.isDoubleSwipeConfirmPending = true
            } else {
                if (requireDoubleSwipeConfirm) armedConfirmUntilMs = 0L
                if (action.isOneStrokeMenu) {
                    triggerMenuHandOff(action, timeMs, launchedStage = 0)
                    return update
                }
                update.commitType = GestureCommitType.EXECUTE
                update.activeAction = action
                update.isDoubleSwipeConfirmPending = false
            }
        } else if (side != EdgeSide.BOTTOM &&
            config.quickArcTriggerMethod == QuickArcTriggerMethod.EDGE_STATIONARY_HOLD
        ) {
            update.hapticEvent = GestureHapticEvent.SECONDARY_TRIGGERED
            if (requireDoubleSwipeConfirm && timeMs > armedConfirmUntilMs) {
                state = GestureState.TRIGGERED_LONG_PRESS
                armedConfirmUntilMs = timeMs + DOUBLE_SWIPE_CONFIRM_WINDOW_MS
                update.commitType = GestureCommitType.ARMED_CONFIRM
                update.activeAction = EdgeAction.NONE
                update.isDoubleSwipeConfirmPending = true
            } else {
                if (requireDoubleSwipeConfirm) armedConfirmUntilMs = 0L
                triggerMenuHandOff(EdgeAction.OPEN_QUICK_MENU, timeMs, launchedStage = 0)
                return update
            }
        }

        update.state = state
        update.direction = direction
        update.stageLevel = stageLevelFor(state)
        return update
    }

    fun onUp(x: Float, y: Float, timeMs: Long): GestureUpdate {
        update.resetFrameFlags()
        update.currentX = x
        update.currentY = y
        update.dragDeltaX = x - downX
        update.dragDeltaY = y - downY
        cancelHoldTimer()
        cancelLongPressTimer()

        val shouldCommit = (state == GestureState.PRIMARY_READY ||
            state == GestureState.SECONDARY_READY ||
            state == GestureState.TERTIARY_READY) &&
            !activeAction.isNone

        if (shouldCommit) {
            val resolvedCommitAction = activeAction
            if (requireDoubleSwipeConfirm) {
                if (timeMs <= armedConfirmUntilMs) {
                    armedConfirmUntilMs = 0L
                    update.commitType = GestureCommitType.EXECUTE
                    update.activeAction = resolvedCommitAction
                    update.isDoubleSwipeConfirmPending = false
                } else {
                    armedConfirmUntilMs = timeMs + DOUBLE_SWIPE_CONFIRM_WINDOW_MS
                    update.commitType = GestureCommitType.ARMED_CONFIRM
                    update.activeAction = EdgeAction.NONE
                    update.isDoubleSwipeConfirmPending = true
                }
            } else {
                update.commitType = GestureCommitType.EXECUTE
                update.activeAction = resolvedCommitAction
                update.isDoubleSwipeConfirmPending = false
            }
        } else {
            update.commitType = GestureCommitType.NONE
            update.activeAction = EdgeAction.NONE
            update.isDoubleSwipeConfirmPending = isDoubleSwipeArmed(timeMs)
        }

        state = GestureState.IDLE
        direction = GestureDirection.NONE
        activeAction = EdgeAction.NONE
        menuReopenLocked = false
        reopenLockedStage = 0
        reopenResumePullPx = 0f
        resumedStage2DemoteFloorPx = Float.MAX_VALUE
        update.state = state
        update.direction = direction
        update.stageLevel = 0
        update.pullProgress = 0f
        update.secondaryProgress = 0f
        update.tertiaryProgress = 0f
        return update
    }

    fun onCancel(): GestureUpdate {
        update.resetFrameFlags()
        cancelHoldTimer()
        cancelLongPressTimer()
        state = GestureState.IDLE
        direction = GestureDirection.NONE
        activeAction = EdgeAction.NONE
        menuReopenLocked = false
        reopenLockedStage = 0
        reopenResumePullPx = 0f
        resumedStage2DemoteFloorPx = Float.MAX_VALUE
        update.state = state
        update.direction = direction
        update.stageLevel = 0
        update.activeAction = EdgeAction.NONE
        update.pullProgress = 0f
        update.secondaryProgress = 0f
        update.tertiaryProgress = 0f
        return update
    }

    private fun promoteToSecondary(
        dir: GestureDirection,
        x: Float,
        y: Float,
        timeMs: Long,
        fromHold: Boolean
    ) {
        val secondaryAct = resolveSecondaryAction(dir)
        if (secondaryAct.isNone) return
        val hasStage3 = !resolveTertiaryAction(dir).isNone
        cancelHoldTimer()
        state = GestureState.SECONDARY_READY
        direction = dir
        activeAction = secondaryAct
        update.hapticEvent = GestureHapticEvent.SECONDARY_TRIGGERED

        // Smart one-stroke menu hand-off:
        // - If no Stage 3 action exists OR user settled via hold, hand off immediately at 0ms.
        // - If Stage 3 IS assigned and user arrived via distance drag, let fast swipes pass
        //   cleanly into Stage 3; pausing briefly in Stage 2 hands off to the one-stroke menu.
        if (secondaryAct.isOneStrokeMenu && canImmediatelyHandOff(timeMs)) {
            if (!hasStage3 || fromHold) {
                val launchedStage = if (!fromHold && usesDistanceMode()) 2 else 0
                triggerMenuHandOff(secondaryAct, timeMs, launchedStage = launchedStage)
                return
            }
        }

        schedulePostSecondaryTimers(dir, secondaryAct, x, y)
        update.state = state
        update.direction = direction
        update.stageLevel = 2
        update.activeAction = activeAction
    }

    private fun promoteToTertiary(dir: GestureDirection, timeMs: Long) {
        val tertiaryAct = resolveTertiaryAction(dir)
        if (tertiaryAct.isNone) return
        cancelHoldTimer()
        state = GestureState.TERTIARY_READY
        direction = dir
        activeAction = tertiaryAct
        update.hapticEvent = GestureHapticEvent.TERTIARY_TRIGGERED

        if (tertiaryAct.isOneStrokeMenu && canImmediatelyHandOff(timeMs)) {
            val launchedStage = if (usesDistanceMode()) 3 else 0
            triggerMenuHandOff(tertiaryAct, timeMs, launchedStage = launchedStage)
            return
        }

        update.state = state
        update.direction = direction
        update.stageLevel = 3
        update.activeAction = activeAction
    }

    private fun schedulePostSecondaryTimers(
        dir: GestureDirection,
        secondaryAct: EdgeAction,
        x: Float,
        y: Float
    ) {
        val hasStage3 = !resolveTertiaryAction(dir).isNone
        if (secondaryAct.isOneStrokeMenu && hasStage3) {
            holdAnchorX = x
            holdAnchorY = y
            holdScheduled = true
            pendingStage2MenuHandOff = true
            update.shouldScheduleHold = true
            update.holdDelayMs = STAGE2_MENU_SETTLE_MS
            return
        }
        pendingStage2MenuHandOff = false
        if (usesHoldMode() && hasStage3) {
            holdAnchorX = x
            holdAnchorY = y
            holdScheduled = true
            update.shouldScheduleHold = true
            update.holdDelayMs = stage3HoldDeltaMs()
        } else {
            cancelHoldTimer()
        }
    }

    private fun canImmediatelyHandOff(timeMs: Long): Boolean =
        !requireDoubleSwipeConfirm || timeMs <= armedConfirmUntilMs

    private fun computeRetreatBoundaryPx(launchedStage: Int): Float {
        if (launchedStage == 2) {
            val s2 = if (side == EdgeSide.BOTTOM) bottomStage2Px else sideStage2Px
            return (s2 - hysteresisPx).coerceAtLeast(swipeThresholdPx)
        }
        if (launchedStage == 3) {
            val s2 = if (side == EdgeSide.BOTTOM) bottomStage2Px else sideStage2Px
            val s3 = if (side == EdgeSide.BOTTOM) bottomStage3Px else sideStage3Px
            return (s3 - hysteresisPx).coerceAtLeast(s2)
        }
        return 0f
    }

    private fun triggerMenuHandOff(action: EdgeAction, timeMs: Long, launchedStage: Int) {
        if (requireDoubleSwipeConfirm && timeMs <= armedConfirmUntilMs) {
            armedConfirmUntilMs = 0L
        }
        cancelHoldTimer()
        cancelLongPressTimer()
        lastHandOffDirection = direction
        lastHandOffStage = launchedStage
        update.handOffTarget = when (action.type) {
            EdgeActionType.OPEN_QUICK_MENU -> MenuHandOffTarget.QUICK_MENU
            EdgeActionType.OPEN_RECENT_SWITCHER -> MenuHandOffTarget.RECENT_SWITCHER
            else -> MenuHandOffTarget.NONE
        }
        update.handOffLaunchedStage = launchedStage
        update.handOffRetreatBoundaryPx = computeRetreatBoundaryPx(launchedStage)
        update.activeAction = action
        update.stageLevel = 0
        update.pullProgress = 0f
        update.secondaryProgress = 0f
        update.tertiaryProgress = 0f
        // Yield cleanly to menu overlay while preserving downX/downY/lastHandOffDirection for bidirectional handback
        state = GestureState.IDLE
        direction = GestureDirection.NONE
        activeAction = EdgeAction.NONE
        update.state = state
        update.direction = direction
    }

    /**
     * Seamlessly resumes the gesture state machine when the user retreats their finger
     * back across the Stage 2 or Stage 3 activation boundary while a one-stroke menu is open.
     * Restores [GestureState.PRIMARY_READY], [GestureState.SECONDARY_READY], or [GestureState.TRACKING]
     * so releasing immediately commits the lower stage, retreating further cancels cleanly, and
     * pushing forward again re-opens the menu.
     */
    fun resumeFromMenuCancel(x: Float, y: Float, timeMs: Long): GestureUpdate {
        update.resetFrameFlags()
        val resumedDir = if (lastHandOffDirection != GestureDirection.NONE) {
            lastHandOffDirection
        } else if (side == EdgeSide.BOTTOM) {
            GestureDirection.BOTTOM_UP
        } else {
            GestureDirection.HORIZONTAL
        }

        val dx = x - downX
        val dy = y - downY
        val primaryPull = when (side) {
            EdgeSide.LEFT -> dx
            EdgeSide.RIGHT -> -dx
            EdgeSide.BOTTOM -> -dy
        }
        val stage2Px = if (side == EdgeSide.BOTTOM) bottomStage2Px else sideStage2Px
        val stage3Px = if (side == EdgeSide.BOTTOM) bottomStage3Px else sideStage3Px
        val hasStage3 = !resolveTertiaryAction(resumedDir).isNone
        val secondaryAct = resolveSecondaryAction(resumedDir)
        val primaryAct = resolvePrimaryAction(resumedDir)

        val effectivePull = primaryPull.coerceAtLeast(0f)
        update.anchorX = downX
        update.anchorY = downY
        update.currentX = x
        update.currentY = y
        update.dragDeltaX = dx
        update.dragDeltaY = dy
        update.hasStage3Configured = hasStage3
        update.isDoubleSwipeConfirmPending = isDoubleSwipeArmed(timeMs)
        update.pullProgress = (effectivePull / visualMaxPullPx).coerceIn(0f, 1.35f)
        update.secondaryProgress = (effectivePull / stage2Px).coerceIn(0f, 1.25f)
        update.tertiaryProgress = if (hasStage3) {
            (effectivePull / stage3Px).coerceIn(0f, 1.25f)
        } else {
            0f
        }

        val demoteFrom2 = (stage2Px - hysteresisPx).coerceAtLeast(swipeThresholdPx)
        val returningFromStage = lastHandOffStage

        when {
            primaryPull < cancelThresholdPx -> {
                state = GestureState.TRACKING
                direction = GestureDirection.NONE
                activeAction = EdgeAction.NONE
                peakInwardPx = 0f
                menuReopenLocked = false
                reopenLockedStage = 0
                reopenResumePullPx = 0f
                resumedStage2DemoteFloorPx = Float.MAX_VALUE
                cancelHoldTimer()
            }

            returningFromStage == 3 && !secondaryAct.isNone -> {
                state = GestureState.SECONDARY_READY
                direction = resumedDir
                activeAction = secondaryAct
                menuReopenLocked = true
                reopenLockedStage = 3
                reopenResumePullPx = effectivePull
                resumedStage2DemoteFloorPx = if (effectivePull < demoteFrom2) {
                    (effectivePull - hysteresisPx).coerceAtLeast(cancelThresholdPx)
                } else {
                    Float.MAX_VALUE
                }
                update.hapticEvent = GestureHapticEvent.SECONDARY_TRIGGERED
                cancelHoldTimer()
            }

            else -> {
                state = GestureState.PRIMARY_READY
                direction = resumedDir
                activeAction = primaryAct
                resumedStage2DemoteFloorPx = Float.MAX_VALUE
                if (returningFromStage >= 2) {
                    menuReopenLocked = true
                    reopenLockedStage = returningFromStage
                    reopenResumePullPx = effectivePull
                } else {
                    menuReopenLocked = false
                    reopenLockedStage = 0
                    reopenResumePullPx = 0f
                }
                update.hapticEvent = GestureHapticEvent.PRIMARY_TRIGGERED
                cancelHoldTimer()
            }
        }

        update.state = state
        update.direction = direction
        update.stageLevel = stageLevelFor(state)
        update.activeAction = activeAction
        return update
    }

    private fun updateReopenLockOnMove(
        currentPullPx: Float,
        stage2Px: Float,
        stage3Px: Float
    ) {
        if (!menuReopenLocked) return
        val lockedThresholdPx = when (reopenLockedStage) {
            3 -> stage3Px
            2 -> stage2Px
            else -> {
                menuReopenLocked = false
                return
            }
        }
        val retreatUnlockPx = (lockedThresholdPx - hysteresisPx).coerceAtLeast(swipeThresholdPx)
        if (currentPullPx < retreatUnlockPx) {
            menuReopenLocked = false
            reopenLockedStage = 0
            reopenResumePullPx = 0f
        }
    }

    private fun isStageReopenLocked(targetStage: Int, currentPullPx: Float): Boolean {
        if (!menuReopenLocked || targetStage != reopenLockedStage) return false
        val deliberatePushPx = REOPEN_DELIBERATE_PUSH_DP * density
        if (currentPullPx >= reopenResumePullPx + deliberatePushPx) {
            menuReopenLocked = false
            reopenLockedStage = 0
            reopenResumePullPx = 0f
            return false
        }
        return true
    }

    private fun usesDistanceMode(): Boolean {
        val mode = config.triggerModeFor(side)
        return mode == SecondaryTriggerMode.DISTANCE || mode == SecondaryTriggerMode.BOTH
    }

    private fun usesHoldMode(): Boolean {
        val mode = config.triggerModeFor(side)
        return mode == SecondaryTriggerMode.HOLD || mode == SecondaryTriggerMode.BOTH
    }

    private fun stage2HoldMs(): Long =
        if (side == EdgeSide.BOTTOM) config.bottomStage2HoldMs else config.sideStage2HoldMs

    private fun stage3HoldDeltaMs(): Long {
        val s2 = stage2HoldMs()
        val s3 = if (side == EdgeSide.BOTTOM) config.bottomStage3HoldMs else config.sideStage3HoldMs
        return (s3 - s2).coerceAtLeast(EdgeConfig.MIN_STAGE_HOLD_GAP_MS)
    }

    private fun cancelTracking() {
        state = GestureState.CANCELLED
        direction = GestureDirection.NONE
        activeAction = EdgeAction.NONE
        update.stageLevel = 0
        update.pullProgress = 0f
        update.secondaryProgress = 0f
        update.tertiaryProgress = 0f
        cancelHoldTimer()
        cancelLongPressTimer()
    }

    private fun classifySideDirection(
        inwardDx: Float,
        dy: Float,
        diagonalThresholdDeg: Float = config.diagonalAngleDeg
    ): GestureDirection {
        val angleDeg = Math.toDegrees(atan2((-dy).toDouble(), inwardDx.coerceAtLeast(1f).toDouble())).toFloat()
        return when {
            angleDeg >= diagonalThresholdDeg -> GestureDirection.DIAGONAL_UP
            angleDeg <= -diagonalThresholdDeg -> GestureDirection.DIAGONAL_DOWN
            else -> GestureDirection.HORIZONTAL
        }
    }

    private fun resolvePrimaryAction(dir: GestureDirection): EdgeAction = when (dir) {
        GestureDirection.HORIZONTAL -> config.horizontalAction
        GestureDirection.DIAGONAL_UP -> config.diagonalUpAction
        GestureDirection.DIAGONAL_DOWN -> config.diagonalDownAction
        GestureDirection.BOTTOM_UP -> config.bottomSwipeUpAction
        else -> EdgeAction.NONE
    }

    private fun resolveSecondaryAction(dir: GestureDirection): EdgeAction = when (dir) {
        GestureDirection.HORIZONTAL -> config.horizontalHoldAction
        GestureDirection.BOTTOM_UP -> config.bottomSwipeHoldAction
        else -> EdgeAction.NONE
    }

    private fun resolveTertiaryAction(dir: GestureDirection): EdgeAction = when (dir) {
        GestureDirection.HORIZONTAL -> config.stage3ActionFor(side)
        GestureDirection.BOTTOM_UP -> config.bottomStage3Action
        else -> EdgeAction.NONE
    }

    private fun maybeScheduleHoldForStage2(dir: GestureDirection, x: Float, y: Float) {
        val secondaryAct = resolveSecondaryAction(dir)
        val tertiaryAct = resolveTertiaryAction(dir)
        if (secondaryAct.isNone && tertiaryAct.isNone) {
            cancelHoldTimer()
            return
        }
        holdAnchorX = x
        holdAnchorY = y
        holdScheduled = true
        pendingStage2MenuHandOff = false
        update.shouldScheduleHold = true
        update.holdDelayMs = if (!secondaryAct.isNone) stage2HoldMs() else {
            if (side == EdgeSide.BOTTOM) config.bottomStage3HoldMs else config.sideStage3HoldMs
        }
    }

    private fun cancelHoldTimer() {
        pendingStage2MenuHandOff = false
        if (holdScheduled) {
            holdScheduled = false
            update.shouldCancelHold = true
        }
    }

    private fun cancelLongPressTimer() {
        if (longPressScheduled) {
            longPressScheduled = false
            update.shouldCancelLongPress = true
        }
    }

    private fun stageLevelFor(gestureState: GestureState): Int = when (gestureState) {
        GestureState.PRIMARY_READY -> 1
        GestureState.SECONDARY_READY -> 2
        GestureState.TERTIARY_READY -> 3
        else -> 0
    }

    companion object {
        private const val CANCEL_THRESHOLD_RATIO = 0.45f
        private const val PULL_RETURN_THRESHOLD_RATIO = 0.55f
        private const val PULL_RETURN_DELTA_RATIO = 0.55f
        private const val MIN_INWARD_RATIO = 0.50f
        private const val MAX_SIDE_TRIGGER_ANGLE_DEG = 72f
        private const val MAX_BOTTOM_HORIZONTAL_RATIO = 1.15f
        private const val HORIZONTAL_LOCK_DISTANCE_RATIO = 1.45f
        private const val HORIZONTAL_LOCK_EXTRA_ANGLE_DEG = 14f
        private const val HYSTERESIS_DP = 14f
        private const val MIN_STAGE2_EXTRA_DP = 24f
        private const val MIN_STAGE3_EXTRA_DP = 32f
        private const val HOLD_JITTER_DP = 14f
        private const val LONG_PRESS_SLOP_DP = 12f
        private const val TOUCH_SLOP_DP = 10f
        private const val REOPEN_DELIBERATE_PUSH_DP = 28f
        private const val STAGE2_MENU_SETTLE_MS = 220L
        private const val DEFAULT_SCREEN_WIDTH_PX = 1080
        private const val DEFAULT_SCREEN_HEIGHT_PX = 2400
        const val DOUBLE_SWIPE_CONFIRM_WINDOW_MS = 2000L
    }
}
