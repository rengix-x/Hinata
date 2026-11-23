package com.edgeflow.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.edgeflow.data.EdgeActionType
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.EdgeExclusionHelper
import com.edgeflow.data.EdgeSide
import com.edgeflow.data.ImeAvoidanceMode
import com.edgeflow.data.LandscapeMode
import com.edgeflow.data.QuickArcTriggerMethod
import com.edgeflow.data.QuickArcZoneSide
import com.edgeflow.data.RuntimeStateRegistry
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Coordinates all [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] windows
 * (every single window strictly includes [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE]):
 * - Left, Right, and Bottom translucent trigger strips (with configurable idle guide/pill alpha)
 * - Zero-GC [EdgeFeedbackView]
 * - Zero-GC [QuickArcMenuView] & [RecentSwitcherView] with instant distance/hold one-stroke touch hand-off
 * - Instant overlay suppression for Circle to Search, Screenshot, and conflict-free GLOBAL_ACTION_RECENTS
 * - Smart IME, landscape, and per-app blacklist avoidance
 */
class EdgeOverlayController(
    private val context: Context,
    private val actionExecutor: ActionExecutor,
    private val hapticController: HapticController,
    private val iconCache: QuickMenuIconCache,
    private val recentAppsTracker: RecentAppsTracker,
    private val backdropCapturer: LiquidBackdropCapturer
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var config: EdgeConfig = EdgeConfig()
    private var density: Float = context.resources.displayMetrics.density
    private var screenWidthPx: Int = 1080
    private var screenHeightPx: Int = 2400

    // Smart avoidance runtime states
    private var imeVisible: Boolean = false
    private var imeTopPx: Int = 0
    private var isLandscape: Boolean = false
    private var foregroundPackage: String = ""
    private var isBlacklisted: Boolean = false
    private var isAttached: Boolean = false
    private var captureSuppressed: Boolean = false

    // Fullscreen overlay views — attached strictly ON-DEMAND and detached 1500ms after idle (0 idle GPU buffers)
    private val feedbackView = EdgeFeedbackView(
        context = context,
        backdropCapturer = backdropCapturer,
        onIdleCallback = { scheduleFeedbackDetach() }
    )
    private var bottomFeedbackInitialized: Boolean = false
    private val bottomFeedbackView: BottomBarFeedbackView by lazy {
        bottomFeedbackInitialized = true
        BottomBarFeedbackView(
            context = context,
            backdropCapturer = backdropCapturer
        ) { isIdle ->
            bottomTrigger.setGestureActive(!isIdle)
            if (isIdle) {
                scheduleBottomFeedbackDetach()
            }
        }
    }
    private val quickArcMenuView = QuickArcMenuView(
        context = context,
        iconCache = iconCache,
        hapticController = hapticController,
        backdropCapturer = backdropCapturer,
        onIdleCallback = { scheduleQuickMenuDetach() }
    )
    private val recentSwitcherView = RecentSwitcherView(
        context = context,
        recentAppsTracker = recentAppsTracker,
        hapticController = hapticController,
        backdropCapturer = backdropCapturer,
        onIdleCallback = { scheduleRecentSwitcherDetach() }
    )
    private val overlayParams = createFullOverlayLayoutParams()
    private val bottomOverlayParams = createFullOverlayLayoutParams()
    private val quickMenuParams = createFullOverlayLayoutParams()
    private val recentSwitcherParams = createFullOverlayLayoutParams()

    private val leftTrigger = EdgeTriggerView(context, EdgeSide.LEFT)
    private val rightTrigger = EdgeTriggerView(context, EdgeSide.RIGHT)
    private val bottomTrigger = EdgeTriggerView(context, EdgeSide.BOTTOM)
    private val leftQuickArcTrigger = QuickArcTriggerStripView(context, EdgeSide.LEFT)
    private val rightQuickArcTrigger = QuickArcTriggerStripView(context, EdgeSide.RIGHT)

    private val leftParams = createTriggerLayoutParams(Gravity.TOP or Gravity.START)
    private val rightParams = createTriggerLayoutParams(Gravity.TOP or Gravity.END)
    private val bottomParams = createTriggerLayoutParams(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
    private val leftArcParams = createTriggerLayoutParams(Gravity.TOP or Gravity.START)
    private val rightArcParams = createTriggerLayoutParams(Gravity.TOP or Gravity.END)

    private var leftAdded = false
    private var rightAdded = false
    private var bottomAdded = false
    private var leftArcAdded = false
    private var rightArcAdded = false
    private var feedbackAdded = false
    private var bottomFeedbackAdded = false
    private var quickMenuAdded = false
    private var recentSwitcherAdded = false

    // Pre-allocated lazy detach Runnables (1500ms after idle) — zero allocations
    private val detachFeedbackRunnable = Runnable {
        if (feedbackAdded && feedbackView.visibility != View.VISIBLE) {
            if (feedbackView.isAttachedToWindow) {
                removeIfAttached(feedbackView, true)
            }
            feedbackAdded = false
        }
        scheduleBackdropIdleReleaseIfAllIdle()
    }
    private val detachBottomFeedbackRunnable = Runnable {
        if (bottomFeedbackAdded && bottomFeedbackView.visibility != View.VISIBLE) {
            if (bottomFeedbackView.isAttachedToWindow) {
                removeIfAttached(bottomFeedbackView, true)
            }
            bottomFeedbackAdded = false
        }
        scheduleBackdropIdleReleaseIfAllIdle()
    }
    private val detachQuickMenuRunnable = Runnable {
        if (quickMenuAdded && quickArcMenuView.visibility != View.VISIBLE) {
            setMenuTouchable(quickArcMenuView, quickMenuParams, quickMenuAdded, touchable = false)
            if (quickArcMenuView.isAttachedToWindow) {
                removeIfAttached(quickArcMenuView, true)
            }
            quickMenuAdded = false
        }
        scheduleBackdropIdleReleaseIfAllIdle()
    }
    private val detachRecentSwitcherRunnable = Runnable {
        if (recentSwitcherAdded && recentSwitcherView.visibility != View.VISIBLE) {
            setMenuTouchable(recentSwitcherView, recentSwitcherParams, recentSwitcherAdded, touchable = false)
            if (recentSwitcherView.isAttachedToWindow) {
                removeIfAttached(recentSwitcherView, true)
            }
            recentSwitcherAdded = false
        }
        scheduleBackdropIdleReleaseIfAllIdle()
    }

    private val restoreGuidesAfterCaptureRunnable = Runnable {
        captureSuppressed = false
        leftTrigger.setCaptureHidden(false)
        rightTrigger.setCaptureHidden(false)
        bottomTrigger.setCaptureHidden(false)
        leftQuickArcTrigger.setCaptureHidden(false)
        rightQuickArcTrigger.setCaptureHidden(false)
    }

    init {
        refreshScreenBounds()
        quickArcMenuView.onTapSelectionAction = { action ->
            setMenuTouchable(quickArcMenuView, quickMenuParams, quickMenuAdded, touchable = false)
            if (!action.isNone) {
                actionExecutor.execute(action)
            }
        }
        quickArcMenuView.onTapSelectionDismiss = {
            setMenuTouchable(quickArcMenuView, quickMenuParams, quickMenuAdded, touchable = false)
        }
        recentSwitcherView.onTapSelectionAction = { action ->
            setMenuTouchable(recentSwitcherView, recentSwitcherParams, recentSwitcherAdded, touchable = false)
            if (!action.isNone) {
                actionExecutor.execute(action)
            }
        }
        recentSwitcherView.onTapSelectionDismiss = {
            setMenuTouchable(recentSwitcherView, recentSwitcherParams, recentSwitcherAdded, touchable = false)
        }
    }

    fun attach(initialConfig: EdgeConfig) {
        config = initialConfig.clamped()
        refreshScreenBounds()
        isAttached = true
        // Only attach lightweight edge/bottom trigger strips while idle;
        // fullscreen feedback & menu overlays attach strictly on-demand!
        applyConfig(config)
    }

    private fun setMenuTouchable(
        view: View,
        params: WindowManager.LayoutParams,
        isAdded: Boolean,
        touchable: Boolean
    ) {
        val desiredFlags = if (touchable) {
            FEEDBACK_FLAGS and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            FEEDBACK_FLAGS
        }
        if (params.flags == desiredFlags) return
        params.flags = desiredFlags
        if (isAdded && view.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun dismissAnyTapSelectionMenus() {
        if (quickArcMenuView.isTapSelectionActive()) {
            setMenuTouchable(quickArcMenuView, quickMenuParams, quickMenuAdded, touchable = false)
            quickArcMenuView.hideImmediately()
        }
        if (recentSwitcherView.isTapSelectionActive()) {
            setMenuTouchable(recentSwitcherView, recentSwitcherParams, recentSwitcherAdded, touchable = false)
            recentSwitcherView.hideImmediately()
        }
    }

    private fun openQuickMenuForTapSelection(side: EdgeSide, anchorX: Float, anchorY: Float) {
        dismissAnyTapSelectionMenus()
        feedbackView.hideImmediately()
        if (bottomFeedbackAdded) {
            bottomFeedbackView.hideImmediately()
        }
        if (config.liquidGlassEnabled) {
            backdropCapturer.captureIfMissing()
        }
        ensureQuickMenuAttached()
        setMenuTouchable(quickArcMenuView, quickMenuParams, quickMenuAdded, touchable = true)
        quickArcMenuView.openForTapSelection(
            side = side,
            anchorX = anchorX,
            anchorY = anchorY,
            screenW = screenWidthPx,
            screenH = screenHeightPx
        )
    }

    private fun openRecentSwitcherForTapSelection(side: EdgeSide, anchorX: Float, anchorY: Float) {
        dismissAnyTapSelectionMenus()
        feedbackView.hideImmediately()
        if (bottomFeedbackAdded) {
            bottomFeedbackView.hideImmediately()
        }
        if (config.liquidGlassEnabled) {
            backdropCapturer.captureIfMissing()
        }
        ensureRecentSwitcherAttached()
        setMenuTouchable(recentSwitcherView, recentSwitcherParams, recentSwitcherAdded, touchable = true)
        recentSwitcherView.openForTapSelection(
            side = side,
            anchorX = anchorX,
            anchorY = anchorY,
            screenW = screenWidthPx,
            screenH = screenHeightPx
        )
    }

    private fun ensureFeedbackAttached() {
        mainHandler.removeCallbacks(detachFeedbackRunnable)
        backdropCapturer.cancelIdleRelease()
        if (!isAttached) return
        if (feedbackAdded && feedbackView.isAttachedToWindow) return
        runCatching {
            if (!feedbackView.isAttachedToWindow) {
                windowManager.addView(feedbackView, overlayParams)
            }
            feedbackAdded = true
        }
    }

    private fun ensureBottomFeedbackAttached() {
        mainHandler.removeCallbacks(detachBottomFeedbackRunnable)
        backdropCapturer.cancelIdleRelease()
        if (!isAttached) return
        if (bottomFeedbackAdded && bottomFeedbackView.isAttachedToWindow) return
        runCatching {
            if (!bottomFeedbackView.isAttachedToWindow) {
                windowManager.addView(bottomFeedbackView, bottomOverlayParams)
            }
            bottomFeedbackAdded = true
        }
    }

    private fun ensureQuickMenuAttached() {
        mainHandler.removeCallbacks(detachQuickMenuRunnable)
        backdropCapturer.cancelIdleRelease()
        if (!isAttached) return
        if (quickMenuAdded && quickArcMenuView.isAttachedToWindow) return
        quickMenuParams.flags = FEEDBACK_FLAGS
        runCatching {
            if (!quickArcMenuView.isAttachedToWindow) {
                windowManager.addView(quickArcMenuView, quickMenuParams)
            }
            quickMenuAdded = true
        }
    }

    private fun ensureRecentSwitcherAttached() {
        mainHandler.removeCallbacks(detachRecentSwitcherRunnable)
        backdropCapturer.cancelIdleRelease()
        if (!isAttached) return
        if (recentSwitcherAdded && recentSwitcherView.isAttachedToWindow) return
        recentSwitcherParams.flags = FEEDBACK_FLAGS
        runCatching {
            if (!recentSwitcherView.isAttachedToWindow) {
                windowManager.addView(recentSwitcherView, recentSwitcherParams)
            }
            recentSwitcherAdded = true
        }
    }

    private fun scheduleFeedbackDetach() {
        mainHandler.removeCallbacks(detachFeedbackRunnable)
        if (feedbackAdded) {
            mainHandler.postDelayed(detachFeedbackRunnable, OVERLAY_IDLE_DETACH_MS)
        }
        scheduleBackdropIdleReleaseIfAllIdle()
    }

    private fun scheduleBottomFeedbackDetach() {
        mainHandler.removeCallbacks(detachBottomFeedbackRunnable)
        if (bottomFeedbackAdded) {
            mainHandler.postDelayed(detachBottomFeedbackRunnable, OVERLAY_IDLE_DETACH_MS)
        }
        scheduleBackdropIdleReleaseIfAllIdle()
    }

    private fun scheduleQuickMenuDetach() {
        mainHandler.removeCallbacks(detachQuickMenuRunnable)
        if (quickMenuAdded) {
            mainHandler.postDelayed(detachQuickMenuRunnable, OVERLAY_IDLE_DETACH_MS)
        }
        scheduleBackdropIdleReleaseIfAllIdle()
    }

    private fun scheduleRecentSwitcherDetach() {
        mainHandler.removeCallbacks(detachRecentSwitcherRunnable)
        if (recentSwitcherAdded) {
            mainHandler.postDelayed(detachRecentSwitcherRunnable, OVERLAY_IDLE_DETACH_MS)
        }
        scheduleBackdropIdleReleaseIfAllIdle()
    }

    private fun scheduleBackdropIdleReleaseIfAllIdle() {
        val bottomVisible = bottomFeedbackInitialized && bottomFeedbackView.visibility == View.VISIBLE
        val anyActive = feedbackView.visibility == View.VISIBLE ||
            bottomVisible ||
            quickArcMenuView.visibility == View.VISIBLE ||
            recentSwitcherView.visibility == View.VISIBLE
        if (!anyActive) {
            backdropCapturer.scheduleIdleRelease()
        }
    }

    private fun detachFullscreenOverlaysNow() {
        mainHandler.removeCallbacks(detachFeedbackRunnable)
        mainHandler.removeCallbacks(detachBottomFeedbackRunnable)
        mainHandler.removeCallbacks(detachQuickMenuRunnable)
        mainHandler.removeCallbacks(detachRecentSwitcherRunnable)

        setMenuTouchable(quickArcMenuView, quickMenuParams, quickMenuAdded, touchable = false)
        setMenuTouchable(recentSwitcherView, recentSwitcherParams, recentSwitcherAdded, touchable = false)

        feedbackView.hideImmediately()
        if (bottomFeedbackAdded) {
            bottomFeedbackView.hideImmediately()
        }
        quickArcMenuView.hideImmediately()
        recentSwitcherView.hideImmediately()

        removeIfAttached(recentSwitcherView, recentSwitcherAdded || recentSwitcherView.isAttachedToWindow)
        recentSwitcherAdded = false
        removeIfAttached(quickArcMenuView, quickMenuAdded || quickArcMenuView.isAttachedToWindow)
        quickMenuAdded = false
        removeIfAttached(bottomFeedbackView, bottomFeedbackAdded || bottomFeedbackView.isAttachedToWindow)
        bottomFeedbackAdded = false
        removeIfAttached(feedbackView, feedbackAdded || feedbackView.isAttachedToWindow)
        feedbackAdded = false
    }

    fun onScreenOff() {
        mainHandler.removeCallbacks(restoreGuidesAfterCaptureRunnable)
        leftTrigger.cancelActiveGesture()
        rightTrigger.cancelActiveGesture()
        bottomTrigger.cancelActiveGesture()
        leftQuickArcTrigger.cancelActiveGesture()
        rightQuickArcTrigger.cancelActiveGesture()
        detachFullscreenOverlaysNow()
    }

    fun detach() {
        isAttached = false
        mainHandler.removeCallbacks(restoreGuidesAfterCaptureRunnable)
        leftTrigger.cancelActiveGesture()
        rightTrigger.cancelActiveGesture()
        bottomTrigger.cancelActiveGesture()
        leftQuickArcTrigger.cancelActiveGesture()
        rightQuickArcTrigger.cancelActiveGesture()

        detachFullscreenOverlaysNow()
        feedbackView.release()
        if (bottomFeedbackInitialized) {
            bottomFeedbackView.release()
        }
        quickArcMenuView.release()
        recentSwitcherView.release()

        removeIfAttached(leftQuickArcTrigger, leftArcAdded).also { leftArcAdded = false }
        removeIfAttached(rightQuickArcTrigger, rightArcAdded).also { rightArcAdded = false }
        removeIfAttached(leftTrigger, leftAdded).also { leftAdded = false }
        removeIfAttached(rightTrigger, rightAdded).also { rightAdded = false }
        removeIfAttached(bottomTrigger, bottomAdded).also { bottomAdded = false }
    }

    /**
     * Immediately hides all visual overlays (side feedback, bottom pill feedback, arc menu,
     * recent switcher) without delaying idle guide restoration — used prior to
     * GLOBAL_ACTION_RECENTS to prevent HyperOS window-transition contention.
     */
    fun hideActiveVisualsImmediately() {
        setMenuTouchable(quickArcMenuView, quickMenuParams, quickMenuAdded, touchable = false)
        setMenuTouchable(recentSwitcherView, recentSwitcherParams, recentSwitcherAdded, touchable = false)
        feedbackView.hideImmediately()
        if (bottomFeedbackAdded) {
            bottomFeedbackView.hideImmediately()
        }
        quickArcMenuView.hideImmediately()
        recentSwitcherView.hideImmediately()
    }

    /**
     * Immediately hides all Hinata visual overlays including idle edge guides & bottom pill
     * before Circle to Search or Screenshot captures the screen.
     */
    fun hideAllVisualsForCapture() {
        captureSuppressed = true
        hideActiveVisualsImmediately()
        leftTrigger.setCaptureHidden(true)
        rightTrigger.setCaptureHidden(true)
        bottomTrigger.setCaptureHidden(true)
        leftQuickArcTrigger.setCaptureHidden(true)
        rightQuickArcTrigger.setCaptureHidden(true)

        mainHandler.removeCallbacks(restoreGuidesAfterCaptureRunnable)
        mainHandler.postDelayed(restoreGuidesAfterCaptureRunnable, CAPTURE_RESTORE_DELAY_MS)
    }

    fun applyConfig(newConfig: EdgeConfig) {
        config = newConfig.clamped()
        actionExecutor.updateConfig(config)
        hapticController.updateStrength(config.hapticStrength)
        feedbackView.applyConfig(config)
        bottomFeedbackView.applyConfig(config)
        quickArcMenuView.applyConfig(config)
        recentSwitcherView.applyConfig(config)

        iconCache.refreshForSlots(config.quickMenuSlots) {
            quickArcMenuView.refreshSlotLabels()
        }

        leftTrigger.updateConfig(config, density, screenWidthPx, screenHeightPx)
        rightTrigger.updateConfig(config, density, screenWidthPx, screenHeightPx)
        bottomTrigger.updateConfig(config, density, screenWidthPx, screenHeightPx)
        leftQuickArcTrigger.updateConfig(config, density)
        rightQuickArcTrigger.updateConfig(config, density)

        isBlacklisted = foregroundPackage.isNotEmpty() &&
            config.blacklistedPackages.contains(foregroundPackage)

        if (isAttached) {
            syncOverlayWindows()
        }
        publishTelemetry()
    }

    fun onDisplayMetricsChanged(landscape: Boolean) {
        isLandscape = landscape
        // Clean up any active gestures/overlays immediately on rotation so coordinates never desync
        leftTrigger.cancelActiveGesture()
        rightTrigger.cancelActiveGesture()
        bottomTrigger.cancelActiveGesture()
        leftQuickArcTrigger.cancelActiveGesture()
        rightQuickArcTrigger.cancelActiveGesture()
        detachFullscreenOverlaysNow()
        backdropCapturer.releaseBackdropNow()

        refreshScreenBounds()
        feedbackView.refreshTheme()
        bottomFeedbackView.refreshTheme()
        quickArcMenuView.refreshTheme()
        recentSwitcherView.refreshTheme()
        leftTrigger.updateConfig(config, density, screenWidthPx, screenHeightPx)
        rightTrigger.updateConfig(config, density, screenWidthPx, screenHeightPx)
        bottomTrigger.updateConfig(config, density, screenWidthPx, screenHeightPx)
        leftQuickArcTrigger.updateConfig(config, density)
        rightQuickArcTrigger.updateConfig(config, density)

        if (isAttached) {
            syncOverlayWindows()
        }
        publishTelemetry()
    }

    fun updateImeState(visible: Boolean, topPx: Int) {
        if (imeVisible == visible && (!visible || imeTopPx == topPx)) return
        imeVisible = visible
        imeTopPx = topPx
        if (isAttached) {
            syncOverlayWindows()
        }
        publishTelemetry()
    }

    fun updateForegroundPackage(pkg: String) {
        if (pkg.isEmpty() || pkg == foregroundPackage) return
        foregroundPackage = pkg
        val nowBlacklisted = config.blacklistedPackages.contains(pkg)
        if (nowBlacklisted != isBlacklisted) {
            isBlacklisted = nowBlacklisted
            if (isAttached) {
                syncOverlayWindows()
            }
        }
        publishTelemetry()
    }

    private fun publishTelemetry() {
        RuntimeStateRegistry.update(
            serviceActive = isAttached,
            imeVisible = imeVisible,
            landscapeActive = isLandscape,
            foregroundPackage = foregroundPackage,
            blacklistedActive = isBlacklisted
        )
    }

    private fun refreshScreenBounds() {
        density = context.resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidthPx = bounds.width()
            screenHeightPx = bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            screenWidthPx = dm.widthPixels
            screenHeightPx = dm.heightPixels
        }
        if (screenWidthPx <= 0) screenWidthPx = context.resources.displayMetrics.widthPixels
        if (screenHeightPx <= 0) screenHeightPx = context.resources.displayMetrics.heightPixels
    }

    private fun syncOverlayWindows() {
        val disableAllForLandscape = isLandscape && config.landscapeMode == LandscapeMode.DISABLE
        val doubleSwipeConfirm = isLandscape && config.landscapeMode == LandscapeMode.DOUBLE_SWIPE_CONFIRM
        val disableAllForIme = imeVisible && config.imeAvoidanceMode == ImeAvoidanceMode.DISABLE_TOUCH

        leftTrigger.setDoubleSwipeConfirmRequired(doubleSwipeConfirm)
        rightTrigger.setDoubleSwipeConfirmRequired(doubleSwipeConfirm)
        bottomTrigger.setDoubleSwipeConfirmRequired(doubleSwipeConfirm)
        leftQuickArcTrigger.setDoubleSwipeConfirmRequired(doubleSwipeConfirm)
        rightQuickArcTrigger.setDoubleSwipeConfirmRequired(doubleSwipeConfirm)

        val globalNotTouchable = isBlacklisted || disableAllForLandscape || disableAllForIme
        val sideFlags = if (globalNotTouchable) {
            TRIGGER_BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            TRIGGER_BASE_FLAGS
        }
        val allowBottomDuringIme = config.showBottomBarDuringIme ||
            config.imeAvoidanceMode == ImeAvoidanceMode.NONE
        val suppressBottomForIme = imeVisible && !allowBottomDuringIme
        val bottomFlags = if (globalNotTouchable || suppressBottomForIme) {
            TRIGGER_BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            TRIGGER_BASE_FLAGS
        }

        val useDedicatedArc = config.quickArcTriggerMethod == QuickArcTriggerMethod.DEDICATED_ZONE
        val enableLeftArc = useDedicatedArc &&
            (config.quickArcZoneSide == QuickArcZoneSide.BOTH || config.quickArcZoneSide == QuickArcZoneSide.LEFT_ONLY)
        val enableRightArc = useDedicatedArc &&
            (config.quickArcZoneSide == QuickArcZoneSide.BOTH || config.quickArcZoneSide == QuickArcZoneSide.RIGHT_ONLY)

        val widthPx = (config.edgeWidthDp * density).toInt().coerceAtLeast(16)
        var heightPx = (screenHeightPx * config.edgeHeightPercent).toInt()
            .coerceIn((64f * density).toInt(), screenHeightPx)
        val bottomMarginPx = (screenHeightPx * config.edgeBottomOffsetPercent).toInt()
        var topY = (screenHeightPx - bottomMarginPx - heightPx).coerceAtLeast(0)

        var arcHeightPx = (screenHeightPx * config.quickArcZoneHeightPercent).toInt()
            .coerceIn((56f * density).toInt(), screenHeightPx)
        val arcBottomMarginPx = (screenHeightPx * config.quickArcZoneBottomOffsetPercent).toInt()
        var arcTopY = (screenHeightPx - arcBottomMarginPx - arcHeightPx).coerceAtLeast(0)

        if (imeVisible && config.imeAvoidanceMode == ImeAvoidanceMode.SHIFT_UP && imeTopPx > 0) {
            val statusTopPx = (STATUS_SAFE_TOP_DP * density).toInt()
            val safeBottomY = (imeTopPx - (IME_MARGIN_DP * density).toInt()).coerceAtLeast((80f * density).toInt())
            val availH = (safeBottomY - statusTopPx).coerceAtLeast((48f * density).toInt())
            if (useDedicatedArc) {
                val gapPx = (4f * density).toInt()
                val minArcH = (44f * density).toInt()
                val maxArcH = (availH * 0.30f).toInt().coerceAtLeast(minArcH).coerceAtMost(availH)
                arcHeightPx = arcHeightPx.coerceIn(minArcH, maxArcH)
                arcTopY = statusTopPx
                val mainTopMin = (arcTopY + arcHeightPx + gapPx).coerceAtMost(safeBottomY - (48f * density).toInt())
                val remainingH = (safeBottomY - mainTopMin).coerceAtLeast((48f * density).toInt())
                heightPx = heightPx.coerceAtMost(remainingH)
                topY = (safeBottomY - heightPx).coerceAtLeast(mainTopMin)
            } else {
                if (heightPx > availH) {
                    heightPx = availH
                }
                if (topY + heightPx > safeBottomY) {
                    topY = (safeBottomY - heightPx).coerceAtLeast(statusTopPx)
                }
            }
        }

        leftParams.width = widthPx
        leftParams.height = heightPx
        leftParams.x = 0
        leftParams.y = topY
        leftParams.flags = sideFlags
        leftTrigger.setIdleVisible(!globalNotTouchable && !captureSuppressed)
        leftAdded = syncSingleView(leftTrigger, leftParams, config.leftEnabled, leftAdded) {
            leftTrigger.cancelActiveGesture()
        }

        rightParams.width = widthPx
        rightParams.height = heightPx
        rightParams.x = 0
        rightParams.y = topY
        rightParams.flags = sideFlags
        rightTrigger.setIdleVisible(!globalNotTouchable && !captureSuppressed)
        rightAdded = syncSingleView(rightTrigger, rightParams, config.rightEnabled, rightAdded) {
            rightTrigger.cancelActiveGesture()
        }

        val bottomWidthPx = (screenWidthPx * config.bottomBarWidthPercent).toInt()
            .coerceIn((56f * density).toInt(), screenWidthPx)
        val bottomHeightPx = (config.bottomBarHeightDp * density).toInt().coerceAtLeast(16)
        val maxShiftPx = ((screenWidthPx - bottomWidthPx) / 2).coerceAtLeast(0)
        val desiredShiftPx = (screenWidthPx * config.bottomBarHorizontalOffsetPercent).toInt()
        bottomParams.width = bottomWidthPx
        bottomParams.height = bottomHeightPx
        bottomParams.x = desiredShiftPx.coerceIn(-maxShiftPx, maxShiftPx)
        bottomParams.y = (config.bottomBarBottomOffsetDp * density).toInt().coerceAtLeast(0)
        bottomParams.flags = bottomFlags
        bottomTrigger.setIdleVisible(!globalNotTouchable && !suppressBottomForIme && !captureSuppressed)
        bottomAdded = syncSingleView(bottomTrigger, bottomParams, config.bottomEnabled, bottomAdded) {
            bottomTrigger.cancelActiveGesture()
        }

        val arcStripWidthPx = (widthPx + (4f * density).toInt()).coerceAtLeast(20)
        leftArcParams.width = arcStripWidthPx
        leftArcParams.height = arcHeightPx
        leftArcParams.x = 0
        leftArcParams.y = arcTopY
        leftArcParams.flags = sideFlags
        leftQuickArcTrigger.setIdleVisible(!globalNotTouchable && !captureSuppressed)
        leftArcAdded = syncSingleView(leftQuickArcTrigger, leftArcParams, enableLeftArc, leftArcAdded) {
            leftQuickArcTrigger.cancelActiveGesture()
        }

        rightArcParams.width = arcStripWidthPx
        rightArcParams.height = arcHeightPx
        rightArcParams.x = 0
        rightArcParams.y = arcTopY
        rightArcParams.flags = sideFlags
        rightQuickArcTrigger.setIdleVisible(!globalNotTouchable && !captureSuppressed)
        rightArcAdded = syncSingleView(rightQuickArcTrigger, rightArcParams, enableRightArc, rightArcAdded) {
            rightQuickArcTrigger.cancelActiveGesture()
        }

        if (suppressBottomForIme) {
            bottomTrigger.cancelActiveGesture()
        }

        if (globalNotTouchable) {
            leftTrigger.cancelActiveGesture()
            rightTrigger.cancelActiveGesture()
            bottomTrigger.cancelActiveGesture()
            leftQuickArcTrigger.cancelActiveGesture()
            rightQuickArcTrigger.cancelActiveGesture()
            hideActiveVisualsImmediately()
        }
    }

    private fun syncSingleView(
        view: View,
        params: WindowManager.LayoutParams,
        enabled: Boolean,
        currentlyAdded: Boolean,
        onBeforeRemove: () -> Unit
    ): Boolean {
        return if (enabled) {
            if (!currentlyAdded) {
                runCatching {
                    windowManager.addView(view, params)
                    true
                }.getOrDefault(false)
            } else {
                runCatching { windowManager.updateViewLayout(view, params) }
                true
            }
        } else {
            if (currentlyAdded) {
                onBeforeRemove()
                removeIfAttached(view, true)
            }
            false
        }
    }

    private fun removeIfAttached(view: View, attached: Boolean) {
        if (!attached) return
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun createTriggerLayoutParams(gravity: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            TRIGGER_BASE_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun createFullOverlayLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            FEEDBACK_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /**
     * Ultra-thin transparent edge/bottom touch strip with optional zero-GC translucent idle guide.
     * Pre-allocates its [GestureDetectorEngine], Runnables, Paints, and RectF for zero allocations
     * inside [onTouchEvent] and [onDraw].
     */
    private inner class EdgeTriggerView(
        context: Context,
        private val side: EdgeSide
    ) : View(context) {

        private val engine = GestureDetectorEngine(side, density, screenWidthPx, screenHeightPx)
        private val palette = GlassPalette()
        private val guideFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val guideRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (if (side == EdgeSide.BOTTOM) 1.0f else 0.8f) * density
        }
        private val guideRect = RectF()
        private val exclusionRect = Rect()
        private val exclusionList = listOf(exclusionRect)

        private var idleGuideAlpha: Float = 0f
        private var idleVisible: Boolean = true
        private var captureHidden: Boolean = false
        private var gestureActive: Boolean = false
        private var activeMenuHandOff: MenuHandOffTarget = MenuHandOffTarget.NONE
        private var lastRawX: Float = 0f
        private var lastRawY: Float = 0f

        private val holdTimeoutRunnable = Runnable {
            val update = engine.onHoldTimeout(SystemClock.uptimeMillis())
            handleEngineUpdate(update)
        }

        private val longPressTimeoutRunnable = Runnable {
            val update = engine.onLongPressTimeout(SystemClock.uptimeMillis())
            if (side == EdgeSide.BOTTOM) {
                bottomFeedbackView.hideImmediately()
            }
            handleEngineUpdate(update)
            if (update.commitType == GestureCommitType.EXECUTE && !update.activeAction.isNone) {
                hideActiveVisualsImmediately()
                actionExecutor.execute(update.activeAction)
            } else if (update.commitType == GestureCommitType.ARMED_CONFIRM) {
                hapticController.performPrimaryTrigger()
            }
        }

        init {
            setWillNotDraw(true)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && right > left && bottom > top) {
                val w = right - left
                val h = bottom - top
                if (side == EdgeSide.BOTTOM) {
                    exclusionRect.set(0, 0, w, h)
                } else {
                    val maxBudgetDp = EdgeExclusionHelper.ANDROID_EXCLUSION_LIMIT_DP
                    val range = EdgeExclusionHelper.computeBottomPrioritizedRangePx(
                        viewHeightPx = h,
                        budgetPx = (maxBudgetDp * density).toInt()
                    )
                    exclusionRect.set(0, range.first, w, range.second)
                }
                systemGestureExclusionRects = exclusionList
            }
        }

        fun updateConfig(cfg: EdgeConfig, currentDensity: Float, widthPx: Int, heightPx: Int) {
            engine.updateConfig(cfg, currentDensity, widthPx, heightPx)
            palette.resolve(context, cfg.visualTheme)
            idleGuideAlpha = if (side == EdgeSide.BOTTOM) cfg.bottomPillAlpha else cfg.edgeGuideAlpha
            refreshDrawState()
        }

        fun setIdleVisible(visible: Boolean) {
            if (idleVisible == visible) return
            idleVisible = visible
            refreshDrawState()
        }

        fun setCaptureHidden(hidden: Boolean) {
            captureHidden = hidden
            alpha = if (hidden) 0f else 1f
            refreshDrawState()
        }

        fun setGestureActive(active: Boolean) {
            if (gestureActive == active) return
            gestureActive = active
            refreshDrawState()
        }

        private fun refreshDrawState() {
            val shouldDraw = !captureHidden && !gestureActive && idleVisible && idleGuideAlpha > 0.005f
            setWillNotDraw(!shouldDraw)
            if (shouldDraw) invalidate()
        }

        fun setDoubleSwipeConfirmRequired(required: Boolean) {
            engine.setDoubleSwipeConfirmRequired(required)
        }

        fun cancelActiveGesture() {
            mainHandler.removeCallbacks(holdTimeoutRunnable)
            mainHandler.removeCallbacks(longPressTimeoutRunnable)
            if (side == EdgeSide.BOTTOM) {
                bottomFeedbackView.hideImmediately()
            }
            when (activeMenuHandOff) {
                MenuHandOffTarget.QUICK_MENU -> quickArcMenuView.cancelAndClose()
                MenuHandOffTarget.RECENT_SWITCHER -> recentSwitcherView.cancelAndClose()
                MenuHandOffTarget.NONE -> Unit
            }
            activeMenuHandOff = MenuHandOffTarget.NONE
            engine.onCancel()
        }

        override fun onDraw(canvas: Canvas) {
            if (captureHidden || gestureActive || !idleVisible || idleGuideAlpha <= 0.005f) return

            if (side == EdgeSide.BOTTOM) {
                // High-contrast neutral charcoal bottom bar with crisp light rim on all backgrounds
                guideFillPaint.color = GlassPalette.argb(
                    BottomBarFeedbackView.NEUTRAL_BAR_IDLE_RGB,
                    idleGuideAlpha
                )
                guideRimPaint.color = GlassPalette.argb(
                    BottomBarFeedbackView.NEUTRAL_BAR_RIM_RGB,
                    (idleGuideAlpha * 0.45f).coerceAtMost(0.45f)
                )
                val pillH = 4.5f * density
                val pillW = (width * 0.72f).coerceAtLeast(36f * density)
                val left = (width - pillW) * 0.5f
                val bottom = height - 4.5f * density
                guideRect.set(left, bottom - pillH, left + pillW, bottom)
                val r = pillH * 0.5f
                canvas.drawRoundRect(guideRect, r, r, guideFillPaint)
                canvas.drawRoundRect(guideRect, r, r, guideRimPaint)
            } else {
                guideFillPaint.color = GlassPalette.argb(palette.guideRgb, idleGuideAlpha)
                guideRimPaint.color = GlassPalette.argb(palette.rimRgb, (idleGuideAlpha * 0.45f).coerceAtMost(0.32f))
                val barW = 2.6f * density
                val vMargin = 10f * density
                val left = if (side == EdgeSide.LEFT) 1.5f * density else width - barW - 1.5f * density
                guideRect.set(left, vMargin, left + barW, (height - vMargin).coerceAtLeast(vMargin + barW))
                val r = barW * 0.5f
                canvas.drawRoundRect(guideRect, r, r, guideFillPaint)
                canvas.drawRoundRect(guideRect, r, r, guideRimPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val actionMasked = event.actionMasked
            if (actionMasked == MotionEvent.ACTION_OUTSIDE) {
                return false
            }
            lastRawX = event.rawX
            lastRawY = event.rawY

            // Self-healing failsafe: if a previous touch stream was dropped (e.g. incoming call/screen off)
            // and the menu is no longer open, immediately recover activeMenuHandOff before routing.
            if (actionMasked == MotionEvent.ACTION_DOWN) {
                dismissAnyTapSelectionMenus()
                if (activeMenuHandOff != MenuHandOffTarget.NONE) {
                    val targetStillOpen = when (activeMenuHandOff) {
                        MenuHandOffTarget.QUICK_MENU -> quickArcMenuView.isOpen()
                        MenuHandOffTarget.RECENT_SWITCHER -> recentSwitcherView.isOpen()
                        MenuHandOffTarget.NONE -> false
                    }
                    if (!targetStillOpen) {
                        activeMenuHandOff = MenuHandOffTarget.NONE
                    } else {
                        cancelActiveGesture()
                    }
                }
            }

            // Seamless one-stroke touch routing when QuickArcMenuView or RecentSwitcherView owns the stream
            if (activeMenuHandOff != MenuHandOffTarget.NONE) {
                return handleHandedOffTouch(actionMasked, event.eventTime)
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mainHandler.removeCallbacks(holdTimeoutRunnable)
                    mainHandler.removeCallbacks(longPressTimeoutRunnable)
                    // Capture clean Liquid Glass backdrop BEFORE any fullscreen overlay becomes visible
                    if (config.liquidGlassEnabled) {
                        backdropCapturer.captureForNewGesture()
                    }
                    val update = engine.onDown(lastRawX, lastRawY, event.eventTime)
                    if (side == EdgeSide.BOTTOM) {
                        ensureBottomFeedbackAttached()
                        if (update.shouldScheduleLongPress) {
                            mainHandler.postDelayed(longPressTimeoutRunnable, update.longPressDelayMs)
                            bottomFeedbackView.startLongPressProgress(update.longPressDelayMs)
                        }
                        bottomFeedbackView.onGestureUpdate(update)
                    } else {
                        ensureFeedbackAttached()
                        if (update.shouldScheduleLongPress) {
                            mainHandler.postDelayed(longPressTimeoutRunnable, update.longPressDelayMs)
                        }
                        feedbackView.onGestureUpdate(side, update)
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val update = engine.onMove(lastRawX, lastRawY, event.eventTime)
                    if (update.shouldCancelLongPress) {
                        mainHandler.removeCallbacks(longPressTimeoutRunnable)
                        if (side == EdgeSide.BOTTOM) {
                            bottomFeedbackView.cancelLongPressProgress()
                        }
                    }
                    if (update.shouldCancelHold) {
                        mainHandler.removeCallbacks(holdTimeoutRunnable)
                    }
                    if (update.shouldScheduleHold) {
                        mainHandler.removeCallbacks(holdTimeoutRunnable)
                        mainHandler.postDelayed(holdTimeoutRunnable, update.holdDelayMs)
                    }
                    handleEngineUpdate(update)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(holdTimeoutRunnable)
                    mainHandler.removeCallbacks(longPressTimeoutRunnable)
                    val update = engine.onUp(lastRawX, lastRawY, event.eventTime)
                    val shouldInstantHide = update.commitType == GestureCommitType.EXECUTE &&
                        (update.activeAction.type == EdgeActionType.CIRCLE_TO_SEARCH ||
                            update.activeAction.type == EdgeActionType.SCREENSHOT ||
                            update.activeAction.type == EdgeActionType.RECENTS ||
                            update.activeAction.type == EdgeActionType.FLOAT_CURRENT_APP ||
                            update.activeAction.type == EdgeActionType.LAUNCH_APP_FLOATING)
                    if (side == EdgeSide.BOTTOM) {
                        if (shouldInstantHide) {
                            bottomFeedbackView.hideImmediately()
                        } else {
                            bottomFeedbackView.onGestureRelease()
                        }
                    } else {
                        if (shouldInstantHide) {
                            feedbackView.hideImmediately()
                        } else {
                            feedbackView.onGestureRelease()
                        }
                    }
                    when (update.commitType) {
                        GestureCommitType.EXECUTE -> {
                            when (update.activeAction.type) {
                                EdgeActionType.OPEN_QUICK_MENU -> {
                                    openQuickMenuForTapSelection(side, update.anchorX, update.anchorY)
                                }
                                EdgeActionType.OPEN_RECENT_SWITCHER -> {
                                    openRecentSwitcherForTapSelection(side, update.anchorX, update.anchorY)
                                }
                                else -> actionExecutor.execute(update.activeAction)
                            }
                        }
                        GestureCommitType.ARMED_CONFIRM -> {
                            hapticController.performPrimaryTrigger()
                        }
                        GestureCommitType.NONE -> Unit
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(holdTimeoutRunnable)
                    mainHandler.removeCallbacks(longPressTimeoutRunnable)
                    engine.onCancel()
                    if (side == EdgeSide.BOTTOM) {
                        bottomFeedbackView.onGestureRelease()
                    } else {
                        feedbackView.onGestureRelease()
                    }
                    return true
                }
            }
            return false
        }

        private fun handleHandedOffTouch(actionMasked: Int, eventTime: Long): Boolean {
            when (actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val shouldHandBack = when (activeMenuHandOff) {
                        MenuHandOffTarget.QUICK_MENU ->
                            quickArcMenuView.shouldHandBackToLowerStage(lastRawX, lastRawY)
                        MenuHandOffTarget.RECENT_SWITCHER ->
                            recentSwitcherView.shouldHandBackToLowerStage(lastRawX, lastRawY)
                        MenuHandOffTarget.NONE -> false
                    }
                    if (shouldHandBack) {
                        val prevTarget = activeMenuHandOff
                        activeMenuHandOff = MenuHandOffTarget.NONE
                        when (prevTarget) {
                            MenuHandOffTarget.QUICK_MENU -> quickArcMenuView.hideImmediately()
                            MenuHandOffTarget.RECENT_SWITCHER -> recentSwitcherView.hideImmediately()
                            MenuHandOffTarget.NONE -> Unit
                        }
                        val resumed = engine.resumeFromMenuCancel(lastRawX, lastRawY, eventTime)
                        if (resumed.shouldCancelHold) {
                            mainHandler.removeCallbacks(holdTimeoutRunnable)
                        }
                        if (resumed.shouldScheduleHold) {
                            mainHandler.removeCallbacks(holdTimeoutRunnable)
                            mainHandler.postDelayed(holdTimeoutRunnable, resumed.holdDelayMs)
                        }
                        handleEngineUpdate(resumed)
                        return true
                    }

                    when (activeMenuHandOff) {
                        MenuHandOffTarget.QUICK_MENU -> quickArcMenuView.updateTouch(lastRawX, lastRawY)
                        MenuHandOffTarget.RECENT_SWITCHER -> recentSwitcherView.updateTouch(lastRawX, lastRawY)
                        MenuHandOffTarget.NONE -> Unit
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val target = activeMenuHandOff
                    activeMenuHandOff = MenuHandOffTarget.NONE
                    val committedAction = when (target) {
                        MenuHandOffTarget.QUICK_MENU -> quickArcMenuView.releaseAndCommit()
                        MenuHandOffTarget.RECENT_SWITCHER -> recentSwitcherView.releaseAndCommit()
                        MenuHandOffTarget.NONE -> com.edgeflow.data.EdgeAction.NONE
                    }
                    if (!committedAction.isNone) {
                        actionExecutor.execute(committedAction)
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    val target = activeMenuHandOff
                    activeMenuHandOff = MenuHandOffTarget.NONE
                    when (target) {
                        MenuHandOffTarget.QUICK_MENU -> quickArcMenuView.cancelAndClose()
                        MenuHandOffTarget.RECENT_SWITCHER -> recentSwitcherView.cancelAndClose()
                        MenuHandOffTarget.NONE -> Unit
                    }
                    return true
                }
            }
            return true
        }

        private fun handleEngineUpdate(update: GestureUpdate) {
            when (update.hapticEvent) {
                GestureHapticEvent.PRIMARY_TRIGGERED -> hapticController.performPrimaryTrigger()
                GestureHapticEvent.SECONDARY_TRIGGERED -> hapticController.performHoldTrigger()
                GestureHapticEvent.TERTIARY_TRIGGERED -> hapticController.performTertiaryTrigger()
                GestureHapticEvent.NONE -> Unit
            }

            if (update.handOffTarget != MenuHandOffTarget.NONE) {
                mainHandler.removeCallbacks(holdTimeoutRunnable)
                mainHandler.removeCallbacks(longPressTimeoutRunnable)
                activeMenuHandOff = update.handOffTarget
                feedbackView.hideImmediately()
                if (bottomFeedbackAdded) {
                    bottomFeedbackView.hideImmediately()
                }
                if (config.liquidGlassEnabled) {
                    backdropCapturer.captureIfMissing()
                }
                when (update.handOffTarget) {
                    MenuHandOffTarget.QUICK_MENU -> {
                        ensureQuickMenuAttached()
                        setMenuTouchable(quickArcMenuView, quickMenuParams, quickMenuAdded, touchable = false)
                        quickArcMenuView.open(
                            side = side,
                            anchorX = update.anchorX,
                            anchorY = update.anchorY,
                            fingerX = lastRawX,
                            fingerY = lastRawY,
                            screenW = screenWidthPx,
                            screenH = screenHeightPx,
                            launchedStage = update.handOffLaunchedStage,
                            retreatBoundaryPx = update.handOffRetreatBoundaryPx
                        )
                    }

                    MenuHandOffTarget.RECENT_SWITCHER -> {
                        ensureRecentSwitcherAttached()
                        setMenuTouchable(recentSwitcherView, recentSwitcherParams, recentSwitcherAdded, touchable = false)
                        recentSwitcherView.open(
                            side = side,
                            anchorX = update.anchorX,
                            anchorY = update.anchorY,
                            fingerX = lastRawX,
                            fingerY = lastRawY,
                            screenW = screenWidthPx,
                            screenH = screenHeightPx,
                            launchedStage = update.handOffLaunchedStage,
                            retreatBoundaryPx = update.handOffRetreatBoundaryPx
                        )
                    }

                    MenuHandOffTarget.NONE -> Unit
                }
                return
            }

            if (side == EdgeSide.BOTTOM) {
                ensureBottomFeedbackAttached()
                bottomFeedbackView.onGestureUpdate(update)
            } else {
                ensureFeedbackAttached()
                feedbackView.onGestureUpdate(side, update)
            }
        }
    }

    /**
     * Dedicated zero-conflict Quick Arc sub-zone strip (used in [QuickArcTriggerMethod.DEDICATED_ZONE]).
     * Pulling inward >= 14dp immediately opens [QuickArcMenuView] with 0ms wait and hands off the
     * live touch stream without ever interfering with Back, Hold, or Long-Swipe gestures.
     */
    private inner class QuickArcTriggerStripView(
        context: Context,
        private val side: EdgeSide
    ) : View(context) {

        private val palette = GlassPalette()
        private val guideFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val guideRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.9f * density
        }
        private val guideRect = RectF()
        private val exclusionRect = Rect()
        private val exclusionList = listOf(exclusionRect)

        private var idleGuideAlpha: Float = 0f
        private var idleVisible: Boolean = true
        private var captureHidden: Boolean = false
        private var requireDoubleSwipeConfirm: Boolean = false
        private var armedConfirmUntilMs: Long = 0L

        private var downX: Float = 0f
        private var downY: Float = 0f
        private var arcOpened: Boolean = false
        private var cancelled: Boolean = false
        private var openThresholdPx: Float = DEDICATED_ARC_OPEN_DP * density
        private var touchSlopPx: Float = DEDICATED_ARC_SLOP_DP * density

        init {
            setWillNotDraw(true)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && right > left && bottom > top) {
                val w = right - left
                val h = bottom - top
                val mainEdgeHeightPx = (screenHeightPx * config.edgeHeightPercent).toInt()
                val totalBudgetPx = (EdgeExclusionHelper.ANDROID_EXCLUSION_LIMIT_DP * density).toInt()
                val remainingBudgetPx = (totalBudgetPx - mainEdgeHeightPx).coerceAtLeast(0)
                if (remainingBudgetPx > 0) {
                    val range = EdgeExclusionHelper.computeBottomPrioritizedRangePx(
                        viewHeightPx = h,
                        budgetPx = remainingBudgetPx
                    )
                    exclusionRect.set(0, range.first, w, range.second)
                    systemGestureExclusionRects = exclusionList
                } else {
                    systemGestureExclusionRects = emptyList()
                }
            }
        }

        fun updateConfig(cfg: EdgeConfig, currentDensity: Float) {
            palette.resolve(context, cfg.visualTheme)
            idleGuideAlpha = cfg.edgeGuideAlpha
            openThresholdPx = DEDICATED_ARC_OPEN_DP * currentDensity
            touchSlopPx = DEDICATED_ARC_SLOP_DP * currentDensity
            refreshDrawState()
        }

        fun setIdleVisible(visible: Boolean) {
            if (idleVisible == visible) return
            idleVisible = visible
            refreshDrawState()
        }

        fun setCaptureHidden(hidden: Boolean) {
            captureHidden = hidden
            alpha = if (hidden) 0f else 1f
            refreshDrawState()
        }

        fun setDoubleSwipeConfirmRequired(required: Boolean) {
            requireDoubleSwipeConfirm = required
            if (!required) armedConfirmUntilMs = 0L
        }

        fun cancelActiveGesture() {
            if (arcOpened) {
                quickArcMenuView.cancelAndClose()
            }
            arcOpened = false
            cancelled = false
        }

        private fun refreshDrawState() {
            val shouldDraw = !captureHidden && idleVisible && idleGuideAlpha > 0.005f
            setWillNotDraw(!shouldDraw)
            if (shouldDraw) invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (captureHidden || !idleVisible || idleGuideAlpha <= 0.005f) return

            // Subtle accent-tinted glass handle so users can distinguish the dedicated Arc zone
            guideFillPaint.color = GlassPalette.argb(palette.accentRgb, (idleGuideAlpha * 0.92f).coerceAtMost(0.75f))
            guideRimPaint.color = GlassPalette.argb(palette.rimRgb, (idleGuideAlpha * 0.55f).coerceAtMost(0.40f))

            val barW = 3.0f * density
            val vMargin = 8f * density
            val left = if (side == EdgeSide.LEFT) 1.5f * density else width - barW - 1.5f * density
            guideRect.set(left, vMargin, left + barW, (height - vMargin).coerceAtLeast(vMargin + barW))
            val r = barW * 0.5f
            canvas.drawRoundRect(guideRect, r, r, guideFillPaint)
            canvas.drawRoundRect(guideRect, r, r, guideRimPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val actionMasked = event.actionMasked
            if (actionMasked == MotionEvent.ACTION_OUTSIDE) return false

            val rawX = event.rawX
            val rawY = event.rawY

            when (actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dismissAnyTapSelectionMenus()
                    if (arcOpened && !quickArcMenuView.isOpen()) {
                        arcOpened = false
                    }
                    downX = rawX
                    downY = rawY
                    arcOpened = false
                    cancelled = false
                    if (config.liquidGlassEnabled) {
                        backdropCapturer.captureForNewGesture()
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (cancelled) return true
                    if (arcOpened) {
                        quickArcMenuView.updateTouch(rawX, rawY)
                        return true
                    }

                    val dx = rawX - downX
                    val dy = rawY - downY
                    val inwardDx = if (side == EdgeSide.LEFT) dx else -dx
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                    // Guard against vertical scrolling along the bezel
                    if (dist >= touchSlopPx && (inwardDx <= 0f || abs(dy) > inwardDx * 2.2f)) {
                        cancelled = true
                        return true
                    }

                    if (inwardDx >= openThresholdPx) {
                        val now = event.eventTime
                        if (requireDoubleSwipeConfirm && now > armedConfirmUntilMs) {
                            armedConfirmUntilMs = now + GestureDetectorEngine.DOUBLE_SWIPE_CONFIRM_WINDOW_MS
                            hapticController.performPrimaryTrigger()
                            cancelled = true
                            return true
                        }
                        if (requireDoubleSwipeConfirm) armedConfirmUntilMs = 0L

                        arcOpened = true
                        feedbackView.hideImmediately()
                        ensureQuickMenuAttached()
                        hapticController.performHoldTrigger()
                        quickArcMenuView.open(
                            side = side,
                            anchorX = downX,
                            anchorY = downY,
                            fingerX = rawX,
                            fingerY = rawY,
                            screenW = screenWidthPx,
                            screenH = screenHeightPx,
                            launchedStage = 0,
                            retreatBoundaryPx = 0f
                        )
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (arcOpened) {
                        arcOpened = false
                        val committed = quickArcMenuView.releaseAndCommit()
                        if (!committed.isNone) {
                            actionExecutor.execute(committed)
                        }
                    }
                    cancelled = false
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (arcOpened) {
                        arcOpened = false
                        quickArcMenuView.cancelAndClose()
                    }
                    cancelled = false
                    return true
                }
            }
            return false
        }
    }

    companion object {
        // FLAG_NOT_FOCUSABLE is strictly enforced on all overlay windows so focus never conflicts with Recents
        private const val TRIGGER_BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        private const val FEEDBACK_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        private const val IME_MARGIN_DP = 8f
        private const val STATUS_SAFE_TOP_DP = 28f
        private const val DEDICATED_ARC_OPEN_DP = 14f
        private const val DEDICATED_ARC_SLOP_DP = 10f
        private const val CAPTURE_RESTORE_DELAY_MS = 380L
        private const val OVERLAY_IDLE_DETACH_MS = 1500L
    }
}
