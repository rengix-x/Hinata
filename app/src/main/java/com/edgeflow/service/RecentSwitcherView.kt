package com.edgeflow.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.edgeflow.data.EdgeAction
import com.edgeflow.data.EdgeActionType
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.EdgeSide
import com.edgeflow.data.QuickMenuRetreatMode
import com.edgeflow.data.VisualTheme
import com.example.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Zero-GC, 120fps translucent Liquid Glass one-stroke Mini Task Switcher.
 *
 * Design guarantees:
 * - Pure Android [View] + [Canvas] + [SpringAnimation] (zero Jetpack Compose).
 * - Supports API 33+ [LiquidGlassShader] for slot bubbles, bottom dock capsule, and label pill
 *   with instant lightweight Canvas fallback when OFF or on API <= 32.
 * - Supports bidirectional boundary handback to lower stages along the primary pull axis.
 * - Strictly zero heap allocations inside [onDraw]; GONE (0% CPU/GPU) when closed.
 */
class RecentSwitcherView(
    context: Context,
    private val recentAppsTracker: RecentAppsTracker,
    private val hapticController: HapticController,
    private val backdropCapturer: LiquidBackdropCapturer? = null,
    private val onIdleCallback: () -> Unit = {}
) : View(context) {

    private val density: Float = resources.displayMetrics.density
    private val palette = GlassPalette()
    private val liquidShader: LiquidGlassShader? = LiquidGlassShader.createIfSupported()

    // Pre-allocated Paints, Paths, and RectFs — strictly ZERO allocations inside onDraw()
    private val dimPaint = Paint().apply { style = Paint.Style.FILL }
    private val slotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val slotRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }
    private val highlightRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.15f * density
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val deadZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val glyphPath = Path()
    private val tempRect = RectF()
    private val labelRect = RectF()
    private val dockRect = RectF()
    private val shaderBounds = RectF()
    private val windowScreenOffset = IntArray(2)

    // Pre-allocated fixed-capacity slot buffers (up to 6 recent apps + 1 trailing RECENTS slot = 7)
    private val slotPackages = Array(MAX_TOTAL_SLOTS) { "" }
    private val slotLabels = Array(MAX_TOTAL_SLOTS) { "" }
    private val slotIsRecents = BooleanArray(MAX_TOTAL_SLOTS)
    private val slotAnglesRad = FloatArray(MAX_TOTAL_SLOTS)
    private val slotCenterX = FloatArray(MAX_TOTAL_SLOTS)
    private val slotCenterY = FloatArray(MAX_TOTAL_SLOTS)
    private val slotScales = FloatArray(MAX_TOTAL_SLOTS) { 1f }
    private var slotCount: Int = 0

    // Configuration values
    private var visualTheme: VisualTheme = VisualTheme.AUTO
    private var feedbackAlpha: Float = EdgeConfig.DEFAULT_FEEDBACK_ALPHA
    private var liquidGlassEnabled: Boolean = true
    private var liquidGlassRefraction: Float = EdgeConfig.DEFAULT_LIQUID_GLASS_REFRACTION
    private var radiusPx: Float = EdgeConfig.DEFAULT_QUICK_MENU_RADIUS_DP * density
    private var dimAlpha: Float = EdgeConfig.DEFAULT_QUICK_MENU_DIM_ALPHA
    private var retreatMode: QuickMenuRetreatMode = QuickMenuRetreatMode.RADIAL_ARC
    private var retreatBoundaryRatio: Float = EdgeConfig.DEFAULT_QUICK_MENU_RETREAT_BOUNDARY_RATIO
    private val slotBaseRadiusPx: Float = SLOT_RADIUS_DP * density
    private var deadZoneRadiusPx: Float = EdgeConfig.DEFAULT_QUICK_MENU_DEAD_ZONE_DP * density

    // Active runtime state
    private var activeSide: EdgeSide = EdgeSide.BOTTOM
    private var anchorRawX: Float = 0f
    private var anchorRawY: Float = 0f
    private var lastFingerRawX: Float = 0f
    private var lastFingerRawY: Float = 0f
    private var fallbackScreenW: Int = resources.displayMetrics.widthPixels
    private var fallbackScreenH: Int = resources.displayMetrics.heightPixels
    private var originX: Float = 0f
    private var originY: Float = 0f
    private var expandProgress: Float = 0f
    private var isMenuOpen: Boolean = false
    private var focusedIndex: Int = -1
    private var launchedStage: Int = 0
    private var retreatBoundaryPx: Float = 0f
    private var hasExitedRetreatZone: Boolean = true
    private var initialRadialDistPx: Float = 0f
    private var initialPullPx: Float = 0f
    private var appLanguageTag: String = ""
    private var cancelHintText: String = context.getString(R.string.arc_deadzone_cancel)
    private var recentsSlotLabel: String = context.getString(R.string.action_recents)

    // Tap-Selection (Sticky) mode state for Stage-1 / diagonal commits
    var onTapSelectionAction: ((EdgeAction) -> Unit)? = null
    var onTapSelectionDismiss: (() -> Unit)? = null
    private var isTapSelectionMode: Boolean = false
    private var tapPressedSlotIndex: Int = -1

    private var backdropReadyAtMs: Long = 0L
    private var lastBackdropGen: Long = -1L
    private var activeSessionHasBackdrop: Boolean = false
    private val smoothedSlotLums = FloatArray(MAX_TOTAL_SLOTS) { -1f }
    private var smoothedDockLum: Float = -1f
    private var smoothedLabelLum: Float = -1f
    private var lastLumUpdateUptimeMs: Long = 0L
    private var frameLumAlpha: Float = 1f
    private var frameLumNeedsMoreFrames: Boolean = false
    private val backdropReadyListener: () -> Unit = {
        if (!activeSessionHasBackdrop) {
            backdropReadyAtMs = SystemClock.uptimeMillis()
            activeSessionHasBackdrop = true
        }
        if (visibility == VISIBLE) {
            invalidate()
        }
    }

    // Pre-allocated SpringAnimation for radial/dock expansion
    private val expandHolder = FloatValueHolder(0f)
    private val expandSpring = SpringAnimation(expandHolder).apply {
        spring = SpringForce(0f).apply {
            stiffness = SpringForce.STIFFNESS_MEDIUM
            dampingRatio = 0.72f
        }
        minimumVisibleChange = 0.002f
        addUpdateListener { _, value, _ ->
            expandProgress = value.coerceIn(0f, 1.15f)
            if (!isMenuOpen && expandProgress <= IDLE_EPSILON) {
                finishIdleState()
            } else {
                invalidate()
            }
        }
        addEndListener { _, canceled, _, _ ->
            if (!canceled && !isMenuOpen && expandProgress <= IDLE_EPSILON) {
                finishIdleState()
            }
        }
    }

    // Pre-allocated SpringAnimation for smooth 1.0x <-> 1.25x focus zoom
    private val focusHolder = FloatValueHolder(0f)
    private val focusSpring = SpringAnimation(focusHolder).apply {
        spring = SpringForce(1f).apply {
            stiffness = SpringForce.STIFFNESS_MEDIUM
            dampingRatio = 0.78f
        }
        minimumVisibleChange = 0.005f
        addUpdateListener { _, _, _ ->
            stepSlotScales()
            if (visibility == VISIBLE) invalidate()
        }
        addEndListener { _, canceled, _, _ ->
            if (!canceled) {
                for (i in 0 until slotCount) {
                    slotScales[i] = if (i == focusedIndex) FOCUSED_SCALE else 1f
                }
                if (visibility == VISIBLE) invalidate()
            }
        }
    }

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
        palette.resolve(context, visualTheme)
        backdropCapturer?.addBackdropReadyListener(backdropReadyListener)
    }

    fun applyConfig(config: EdgeConfig) {
        visualTheme = config.visualTheme
        feedbackAlpha = config.feedbackAlpha.coerceAtLeast(0.20f)
        liquidGlassEnabled = config.liquidGlassEnabled
        liquidGlassRefraction = config.liquidGlassRefraction
        radiusPx = config.quickMenuRadiusDp * density
        dimAlpha = config.quickMenuDimAlpha
        retreatMode = QuickMenuRetreatMode.RADIAL_ARC
        retreatBoundaryRatio = EdgeConfig.DEFAULT_QUICK_MENU_RETREAT_BOUNDARY_RATIO
        deadZoneRadiusPx = EdgeConfig.DEFAULT_QUICK_MENU_DEAD_ZONE_DP * density
        appLanguageTag = config.appLanguageTag
        palette.resolve(context, visualTheme)
        refreshLocalizedStrings()
    }

    fun refreshTheme() {
        palette.resolve(context, visualTheme)
        refreshLocalizedStrings()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        palette.resolve(context, visualTheme)
        refreshLocalizedStrings()
    }

    private fun refreshLocalizedStrings() {
        val localized = LocalizedContextHelper.forTag(context, appLanguageTag)
        cancelHintText = localized.getString(R.string.arc_deadzone_cancel)
        recentsSlotLabel = localized.getString(R.string.action_recents)
        for (i in 0 until slotCount) {
            if (slotIsRecents[i]) {
                slotLabels[i] = recentsSlotLabel
            }
        }
    }

    fun isOpen(): Boolean = isMenuOpen

    fun isTapSelectionActive(): Boolean = isMenuOpen && isTapSelectionMode

    fun shouldHandBackToLowerStage(rawX: Float, rawY: Float): Boolean {
        if (!isMenuOpen || launchedStage < 2) return false
        val localX = toLocalX(rawX)
        val localY = toLocalY(rawY)
        val dist = hypot((localX - originX).toDouble(), (localY - originY).toDouble()).toFloat()
        if (!hasExitedRetreatZone &&
            MenuRetreatEvaluator.shouldMarkExitedRetreatZone(
                mode = retreatMode,
                radialDistPx = dist,
                radiusPx = radiusPx,
                deadZoneRadiusPx = deadZoneRadiusPx,
                boundaryRatio = retreatBoundaryRatio,
                density = density
            )
        ) {
            hasExitedRetreatZone = true
        }
        val screenAxisPx = if (activeSide == EdgeSide.BOTTOM) {
            if (height > 0) height.toFloat() else fallbackScreenH.toFloat()
        } else {
            if (width > 0) width.toFloat() else fallbackScreenW.toFloat()
        }.coerceAtLeast(320f)
        return MenuRetreatEvaluator.shouldHandBack(
            mode = retreatMode,
            side = activeSide,
            launchedStage = launchedStage,
            localX = localX,
            localY = localY,
            originX = originX,
            originY = originY,
            rawX = rawX,
            rawY = rawY,
            anchorRawX = anchorRawX,
            anchorRawY = anchorRawY,
            radiusPx = radiusPx,
            deadZoneRadiusPx = deadZoneRadiusPx,
            boundaryRatio = retreatBoundaryRatio,
            screenAxisPx = screenAxisPx,
            density = density,
            hasExitedRetreatZone = hasExitedRetreatZone,
            initialRadialDistPx = initialRadialDistPx,
            retreatBoundaryPx = retreatBoundaryPx,
            initialPullPx = initialPullPx
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && isMenuOpen) {
            recomputeGeometry()
            updateTouch(lastFingerRawX, lastFingerRawY)
        }
    }

    override fun onDetachedFromWindow() {
        expandSpring.cancel()
        focusSpring.cancel()
        isMenuOpen = false
        isTapSelectionMode = false
        tapPressedSlotIndex = -1
        focusedIndex = -1
        expandProgress = 0f
        if (visibility != GONE) visibility = GONE
        super.onDetachedFromWindow()
    }

    fun release() {
        backdropCapturer?.removeBackdropReadyListener(backdropReadyListener)
        hideImmediately()
    }

    fun open(
        side: EdgeSide,
        anchorX: Float,
        anchorY: Float,
        fingerX: Float,
        fingerY: Float,
        screenW: Int = fallbackScreenW,
        screenH: Int = fallbackScreenH,
        launchedStage: Int = 0,
        retreatBoundaryPx: Float = 0f
    ) {
        activeSide = side
        anchorRawX = anchorX
        anchorRawY = anchorY
        lastFingerRawX = fingerX
        lastFingerRawY = fingerY
        this.launchedStage = launchedStage
        this.retreatBoundaryPx = retreatBoundaryPx
        if (screenW > 0) fallbackScreenW = screenW
        if (screenH > 0) fallbackScreenH = screenH

        // Snapshot up to 6 recent apps (index 0 = Previous App) + trailing native RECENTS slot
        val recent = recentAppsTracker.getRecentAppsExcludingCurrent(MAX_RECENT_APPS)
        var count = 0
        for (i in 0 until recent.size) {
            if (count >= MAX_RECENT_APPS) break
            val pkg = recent[i]
            slotPackages[count] = pkg
            slotLabels[count] = recentAppsTracker.getCachedLabel(pkg)
            slotIsRecents[count] = false
            slotScales[count] = 1f
            count++
        }
        // Always include trailing native RECENTS slot so switcher is always useful even on cold boot
        slotPackages[count] = ""
        slotLabels[count] = recentsSlotLabel
        slotIsRecents[count] = true
        slotScales[count] = 1f
        count++
        slotCount = count

        recomputeGeometry()

        val initialLocalX = toLocalX(fingerX)
        val initialLocalY = toLocalY(fingerY)
        initialRadialDistPx = hypot(
            (initialLocalX - originX).toDouble(),
            (initialLocalY - originY).toDouble()
        ).toFloat()
        initialPullPx = when (side) {
            EdgeSide.LEFT -> fingerX - anchorX
            EdgeSide.RIGHT -> anchorX - fingerX
            EdgeSide.BOTTOM -> anchorY - fingerY
        }
        hasExitedRetreatZone = MenuRetreatEvaluator.isInitiallyOutsideRetreatZone(
            mode = retreatMode,
            radialDistPx = initialRadialDistPx,
            radiusPx = radiusPx,
            deadZoneRadiusPx = deadZoneRadiusPx,
            boundaryRatio = retreatBoundaryRatio,
            density = density
        )

        val hasCachedBackdrop = backdropCapturer?.currentShader() != null
        activeSessionHasBackdrop = hasCachedBackdrop
        backdropReadyAtMs = 0L
        lastBackdropGen = backdropCapturer?.currentGeneration() ?: -1L

        isTapSelectionMode = false
        tapPressedSlotIndex = -1
        isMenuOpen = true
        focusedIndex = -1

        if (visibility != VISIBLE) {
            visibility = VISIBLE
        }

        val startVal = expandProgress.coerceAtLeast(INITIAL_OPEN_PROGRESS)
        expandProgress = startVal
        expandSpring.cancel()
        expandHolder.value = startVal
        expandSpring.spring.finalPosition = 1f
        expandSpring.spring.dampingRatio = 0.70f
        expandSpring.setStartValue(startVal)
        expandSpring.start()

        updateTouch(fingerX, fingerY)
    }

    /**
     * Opens the Recent Switcher in Tap-Selection (Sticky) mode after a Stage-1 / diagonal swipe commit.
     */
    fun openForTapSelection(
        side: EdgeSide,
        anchorX: Float,
        anchorY: Float,
        screenW: Int = fallbackScreenW,
        screenH: Int = fallbackScreenH
    ) {
        activeSide = side
        anchorRawX = anchorX
        anchorRawY = anchorY
        lastFingerRawX = anchorX
        lastFingerRawY = anchorY
        launchedStage = 0
        retreatBoundaryPx = 0f
        hasExitedRetreatZone = true
        initialRadialDistPx = 0f
        if (screenW > 0) fallbackScreenW = screenW
        if (screenH > 0) fallbackScreenH = screenH

        val recent = recentAppsTracker.getRecentAppsExcludingCurrent(MAX_RECENT_APPS)
        var count = 0
        for (i in 0 until recent.size) {
            if (count >= MAX_RECENT_APPS) break
            val pkg = recent[i]
            slotPackages[count] = pkg
            slotLabels[count] = recentAppsTracker.getCachedLabel(pkg)
            slotIsRecents[count] = false
            slotScales[count] = 1f
            count++
        }
        slotPackages[count] = ""
        slotLabels[count] = recentsSlotLabel
        slotIsRecents[count] = true
        slotScales[count] = 1f
        count++
        slotCount = count

        recomputeGeometry()

        val hasCachedBackdrop = backdropCapturer?.currentShader() != null
        activeSessionHasBackdrop = hasCachedBackdrop
        backdropReadyAtMs = 0L
        lastBackdropGen = backdropCapturer?.currentGeneration() ?: -1L

        isTapSelectionMode = true
        tapPressedSlotIndex = -1
        isMenuOpen = true
        focusedIndex = -1

        if (visibility != VISIBLE) {
            visibility = VISIBLE
        }

        val startVal = expandProgress.coerceAtLeast(INITIAL_OPEN_PROGRESS)
        expandProgress = startVal
        expandSpring.cancel()
        expandHolder.value = startVal
        expandSpring.spring.finalPosition = 1f
        expandSpring.spring.dampingRatio = 0.70f
        expandSpring.setStartValue(startVal)
        expandSpring.start()
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isMenuOpen || !isTapSelectionMode) return false
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = findTappedSlot(x, y)
                tapPressedSlotIndex = hit
                focusedIndex = hit
                for (i in 0 until slotCount) {
                    slotScales[i] = if (i == hit) TAP_PRESSED_SCALE else 1f
                }
                if (hit >= 0) {
                    hapticController.performPrimaryTrigger()
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (tapPressedSlotIndex >= 0) {
                    val hit = findTappedSlot(x, y, slopMultiplier = 1.45f)
                    if (hit != tapPressedSlotIndex) {
                        tapPressedSlotIndex = -1
                        focusedIndex = -1
                        for (i in 0 until slotCount) slotScales[i] = 1f
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val chosen = if (tapPressedSlotIndex in 0 until slotCount) {
                    tapPressedSlotIndex
                } else {
                    findTappedSlot(x, y)
                }
                tapPressedSlotIndex = -1
                if (chosen in 0 until slotCount) {
                    val action = if (slotIsRecents[chosen]) {
                        EdgeAction.RECENTS
                    } else {
                        EdgeAction.launchApp(slotPackages[chosen])
                    }
                    closeAfterTapCommit(action)
                    onTapSelectionAction?.invoke(action)
                } else {
                    cancelAndClose()
                    onTapSelectionDismiss?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                tapPressedSlotIndex = -1
                cancelAndClose()
                onTapSelectionDismiss?.invoke()
                return true
            }
        }
        return true
    }

    private fun findTappedSlot(localX: Float, localY: Float, slopMultiplier: Float = 1.28f): Int {
        val hitRadius = slotBaseRadiusPx * slopMultiplier
        var bestIdx = -1
        var bestDist = hitRadius
        for (i in 0 until slotCount) {
            val d = hypot((localX - slotCenterX[i]).toDouble(), (localY - slotCenterY[i]).toDouble()).toFloat()
            if (d <= bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun closeAfterTapCommit(action: EdgeAction) {
        isMenuOpen = false
        isTapSelectionMode = false
        tapPressedSlotIndex = -1
        focusedIndex = -1
        launchedStage = 0
        retreatBoundaryPx = 0f
        if (action.type == EdgeActionType.RECENTS) {
            hideImmediately()
        } else {
            focusSpring.cancel()
            expandSpring.cancel()
            expandHolder.value = expandProgress
            expandSpring.spring.finalPosition = 0f
            expandSpring.spring.dampingRatio = 0.88f
            expandSpring.setStartValue(expandProgress)
            expandSpring.start()
        }
    }

    private fun recomputeGeometry() {
        val viewW = if (width > 0) width.toFloat() else fallbackScreenW.toFloat().coerceAtLeast(320f)
        val viewH = if (height > 0) height.toFloat() else fallbackScreenH.toFloat().coerceAtLeast(480f)
        val localAnchorX = toLocalX(anchorRawX)
        val localAnchorY = toLocalY(anchorRawY)

        if (activeSide == EdgeSide.BOTTOM) {
            originX = safeCoerce(localAnchorX, viewW * 0.22f, viewW * 0.78f, viewW * 0.5f)
            originY = viewH
            computeBottomDockPositions(viewW, viewH)
        } else {
            originX = if (activeSide == EdgeSide.LEFT) 0f else viewW
            val marginY = max(radiusPx + slotBaseRadiusPx + 36f * density, 72f * density)
            originY = safeCoerce(localAnchorY, marginY, viewH - marginY, viewH * 0.5f)
            computeSideArcAngles()
        }
    }

    fun updateTouch(rawX: Float, rawY: Float) {
        if (!isMenuOpen || slotCount == 0) return
        lastFingerRawX = rawX
        lastFingerRawY = rawY

        val localX = toLocalX(rawX)
        val localY = toLocalY(rawY)

        val newFocus = if (activeSide == EdgeSide.BOTTOM) {
            findBottomDockFocus(localX, localY)
        } else {
            findSideArcFocus(localX, localY)
        }

        if (newFocus != focusedIndex) {
            focusedIndex = newFocus
            if (focusedIndex >= 0) {
                hapticController.performPrimaryTrigger()
            }
            focusSpring.cancel()
            focusHolder.value = 0f
            focusSpring.spring.finalPosition = 1f
            focusSpring.setStartValue(0f)
            focusSpring.start()
        }
        invalidate()
    }

    fun releaseAndCommit(): EdgeAction {
        if (!isMenuOpen) return EdgeAction.NONE
        val action = if (focusedIndex in 0 until slotCount) {
            if (slotIsRecents[focusedIndex]) {
                EdgeAction.RECENTS
            } else {
                EdgeAction.launchApp(slotPackages[focusedIndex])
            }
        } else {
            EdgeAction.NONE
        }

        isMenuOpen = false
        isTapSelectionMode = false
        tapPressedSlotIndex = -1
        focusedIndex = -1
        launchedStage = 0
        retreatBoundaryPx = 0f

        if (action.type == EdgeActionType.RECENTS) {
            hideImmediately()
        } else {
            focusSpring.cancel()
            expandSpring.cancel()
            expandHolder.value = expandProgress
            expandSpring.spring.finalPosition = 0f
            expandSpring.spring.dampingRatio = 0.88f
            expandSpring.setStartValue(expandProgress)
            expandSpring.start()
        }
        return action
    }

    fun cancelAndClose() {
        if (!isMenuOpen && visibility == GONE) return
        isMenuOpen = false
        isTapSelectionMode = false
        tapPressedSlotIndex = -1
        focusedIndex = -1
        launchedStage = 0
        retreatBoundaryPx = 0f
        focusSpring.cancel()
        expandSpring.cancel()
        expandHolder.value = expandProgress
        expandSpring.spring.finalPosition = 0f
        expandSpring.spring.dampingRatio = 0.90f
        expandSpring.setStartValue(expandProgress)
        expandSpring.start()
    }

    fun hideImmediately() {
        val wasVisible = visibility == VISIBLE || isMenuOpen
        isMenuOpen = false
        isTapSelectionMode = false
        tapPressedSlotIndex = -1
        focusedIndex = -1
        launchedStage = 0
        retreatBoundaryPx = 0f
        expandSpring.cancel()
        focusSpring.cancel()
        expandProgress = 0f
        for (i in 0 until MAX_TOTAL_SLOTS) slotScales[i] = 1f
        resetSmoothedLuminance()
        if (visibility != GONE) {
            visibility = GONE
        }
        if (wasVisible) {
            onIdleCallback()
        }
    }

    private fun finishIdleState() {
        val wasVisible = visibility == VISIBLE
        expandProgress = 0f
        resetSmoothedLuminance()
        if (visibility != GONE) {
            visibility = GONE
        }
        if (wasVisible) {
            onIdleCallback()
        }
    }

    private fun resetSmoothedLuminance() {
        for (i in 0 until MAX_TOTAL_SLOTS) smoothedSlotLums[i] = -1f
        smoothedDockLum = -1f
        smoothedLabelLum = -1f
        lastLumUpdateUptimeMs = 0L
        frameLumAlpha = 1f
        frameLumNeedsMoreFrames = false
    }

    private fun computeSideArcAngles() {
        if (slotCount <= 1) {
            slotAnglesRad[0] = 0f
            return
        }
        val sweepDeg = when {
            slotCount <= 3 -> 104f
            slotCount == 4 -> 118f
            slotCount == 5 -> 128f
            slotCount == 6 -> 136f
            else -> 144f
        }
        val startDeg = -sweepDeg / 2f
        val stepDeg = sweepDeg / (slotCount - 1)
        for (i in 0 until slotCount) {
            slotAnglesRad[i] = ((startDeg + stepDeg * i) * PI / 180.0).toFloat()
        }
    }

    private fun computeBottomDockPositions(viewW: Float, viewH: Float) {
        val horizontalMargin = 34f * density
        val usableWidth = (viewW - horizontalMargin * 2f).coerceAtLeast(160f * density)
        val desiredSpacing = 54f * density
        val totalSpan = ((slotCount - 1) * desiredSpacing).coerceAtMost(usableWidth)
        val stepX = if (slotCount > 1) totalSpan / (slotCount - 1) else 0f
        val startX = (viewW - totalSpan) * 0.5f
        val baseY = (viewH - radiusPx * 1.18f).coerceAtLeast(viewH * 0.45f)
        val archLift = 16f * density

        for (i in 0 until slotCount) {
            val t = if (slotCount > 1) (i.toFloat() / (slotCount - 1)) * 2f - 1f else 0f
            slotCenterX[i] = startX + stepX * i
            // Gentle ergonomic upward curve highest at the center
            slotCenterY[i] = baseY - (1f - t * t) * archLift
        }
    }

    private fun findSideArcFocus(rawX: Float, rawY: Float): Int {
        val dx = rawX - originX
        val dy = rawY - originY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist < deadZoneRadiusPx) return -1

        val inwardDx = if (activeSide == EdgeSide.LEFT) dx else -dx
        if (inwardDx < 10f * density) return -1

        val pointerAngle = atan2(dy.toDouble(), inwardDx.toDouble()).toFloat()
        var bestIdx = -1
        var bestDiff = Float.MAX_VALUE
        for (i in 0 until slotCount) {
            val diff = abs(pointerAngle - slotAnglesRad[i])
            if (diff < bestDiff) {
                bestDiff = diff
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun findBottomDockFocus(rawX: Float, rawY: Float): Int {
        val upwardFromBottom = originY - rawY
        if (upwardFromBottom < deadZoneRadiusPx * 1.15f) return -1

        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until slotCount) {
            val dx = rawX - slotCenterX[i]
            val dy = (rawY - slotCenterY[i]) * 0.45f
            val score = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (score < bestDist) {
                bestDist = score
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun stepSlotScales() {
        for (i in 0 until slotCount) {
            val target = if (i == focusedIndex) FOCUSED_SCALE else 1f
            slotScales[i] += (target - slotScales[i]) * 0.32f
        }
    }

    private fun shouldUseLiquidGlass(): Boolean =
        liquidGlassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && liquidShader != null

    private fun isLightGlassActive(): Boolean {
        if (visualTheme == VisualTheme.LIGHT_GLASS) return true
        if (visualTheme == VisualTheme.DARK_GLASS) return false
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return !isNight
    }

    private fun resolveBackdropMix(): Float {
        backdropCapturer?.currentShader() ?: return 0f
        val gen = backdropCapturer.currentGeneration()
        if (gen != lastBackdropGen) {
            lastBackdropGen = gen
            if (!activeSessionHasBackdrop) {
                backdropReadyAtMs = SystemClock.uptimeMillis()
                activeSessionHasBackdrop = true
            }
        }
        val baseMix = if (backdropReadyAtMs == 0L) {
            1f
        } else {
            val elapsed = (SystemClock.uptimeMillis() - backdropReadyAtMs).coerceAtLeast(0L)
            val raw = (elapsed / BACKDROP_FADE_MS.toFloat()).coerceIn(0f, 1f)
            val inv = 1f - raw
            val eased = 1f - inv * inv * inv
            if (raw < 1f && visibility == VISIBLE) {
                postInvalidateOnAnimation()
            } else if (raw >= 1f) {
                backdropReadyAtMs = 0L
            }
            eased
        }
        val scrollMul = backdropCapturer.currentScrollMixMultiplier()
        if (backdropCapturer.isScrollMixAnimating() && visibility == VISIBLE) {
            postInvalidateOnAnimation()
        }
        return (baseMix * scrollMul).coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f || expandProgress <= IDLE_EPSILON || slotCount == 0) return

        val p = expandProgress.coerceIn(0f, 1.15f)
        val alphaCurve = p.coerceAtMost(1f)
        val useLiquid = shouldUseLiquidGlass()
        val isLight = isLightGlassActive()
        val backdropMix = if (useLiquid) resolveBackdropMix() else 0f
        val backdropScale = backdropCapturer?.currentBackdropScale() ?: 1f
        val nowMs = SystemClock.uptimeMillis()
        frameLumAlpha = if (lastLumUpdateUptimeMs == 0L) {
            1f
        } else {
            val dtMs = (nowMs - lastLumUpdateUptimeMs).coerceIn(1L, 64L)
            1f - kotlin.math.exp((-dtMs.toDouble() / LUM_SMOOTH_TAU_MS)).toFloat()
        }
        if (useLiquid) {
            lastLumUpdateUptimeMs = nowMs
        }
        frameLumNeedsMoreFrames = false
        if (useLiquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            liquidShader?.bindBackdrop(backdropCapturer?.currentShader())
            if (isAttachedToWindow) {
                getLocationOnScreen(windowScreenOffset)
            } else {
                windowScreenOffset[0] = 0
                windowScreenOffset[1] = 0
            }
        }

        // 1. Subtle background dim layer
        if (dimAlpha > 0.005f) {
            dimPaint.color = GlassPalette.argb(0x000000, dimAlpha * alphaCurve)
            canvas.drawRect(0f, 0f, viewW, viewH, dimPaint)
        }

        if (activeSide == EdgeSide.BOTTOM) {
            drawBottomDock(canvas, p, alphaCurve, useLiquid, isLight, backdropMix, backdropScale)
        } else {
            drawSideArc(canvas, p, alphaCurve, useLiquid, isLight, backdropMix, backdropScale)
        }

        drawFloatingLabelPill(canvas, alphaCurve, useLiquid, isLight, backdropMix, backdropScale)

        if (frameLumNeedsMoreFrames && visibility == VISIBLE) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawSideArc(
        canvas: Canvas,
        p: Float,
        alphaCurve: Float,
        useLiquid: Boolean,
        isLight: Boolean,
        backdropMix: Float,
        backdropScale: Float
    ) {
        val guideR = if (launchedStage >= 2 && retreatMode == QuickMenuRetreatMode.RADIAL_ARC) {
            MenuRetreatEvaluator.computeRadialRetreatRadiusPx(radiusPx, deadZoneRadiusPx, retreatBoundaryRatio)
        } else if (launchedStage >= 2 && retreatMode == QuickMenuRetreatMode.EDGE_DEADZONE) {
            deadZoneRadiusPx * 0.85f
        } else {
            deadZoneRadiusPx
        }
        val localFingerX = lastFingerRawX - windowScreenOffset[0]
        val localFingerY = lastFingerRawY - windowScreenOffset[1]
        val fingerDist = hypot((localFingerX - originX).toDouble(), (localFingerY - originY).toDouble()).toFloat()
        val proximity = (1f - ((fingerDist - guideR).coerceAtLeast(0f) / (24f * density))).coerceIn(0f, 1f)
        val deadAlpha = (if (launchedStage >= 2) 0.16f + 0.28f * proximity else if (focusedIndex < 0) 0.30f else 0.14f) * alphaCurve
        deadZonePaint.color = GlassPalette.argb(
            if (launchedStage >= 2 && proximity > 0.35f) palette.accentRgb else palette.rimRgb,
            deadAlpha
        )
        canvas.drawCircle(originX, originY, guideR * alphaCurve, deadZonePaint)

        val dirSign = if (activeSide == EdgeSide.LEFT) 1f else -1f
        val currentRadius = radiusPx * p

        for (i in 0 until slotCount) {
            val angle = slotAnglesRad[i]
            val sx = originX + dirSign * cos(angle) * currentRadius
            val sy = originY + sin(angle) * currentRadius
            slotCenterX[i] = sx
            slotCenterY[i] = sy
            drawSlotBubble(canvas, i, sx, sy, alphaCurve, useLiquid, isLight, backdropMix, backdropScale)
        }
    }

    private fun drawBottomDock(
        canvas: Canvas,
        p: Float,
        alphaCurve: Float,
        useLiquid: Boolean,
        isLight: Boolean,
        backdropMix: Float,
        backdropScale: Float
    ) {
        val padX = slotBaseRadiusPx + 14f * density
        val padY = slotBaseRadiusPx + 14f * density
        val left = (slotCenterX[0] - padX).coerceAtLeast(10f * density)
        val right = (slotCenterX[slotCount - 1] + padX).coerceAtMost(width - 10f * density)
        val targetMidY = slotCenterY[slotCount / 2]
        val currentMidY = originY + (targetMidY - originY) * p
        val liftDelta = currentMidY - targetMidY

        dockRect.set(left, currentMidY - padY, right, currentMidY + padY + 6f * density)
        if (useLiquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rawDockLum = backdropCapturer?.sampleLuminanceAt(
                windowScreenOffset[0] + dockRect.centerX(),
                windowScreenOffset[1] + dockRect.centerY()
            ) ?: -1f
            val dockBgLum = if (rawDockLum >= 0f) {
                val prev = smoothedDockLum
                val next = if (prev < 0f) rawDockLum else prev + (rawDockLum - prev) * frameLumAlpha
                smoothedDockLum = next
                if (kotlin.math.abs(rawDockLum - next) > LUM_CONVERGENCE_EPS) frameLumNeedsMoreFrames = true
                next
            } else if (smoothedDockLum >= 0f) {
                smoothedDockLum
            } else {
                -1f
            }
            val pad = SHADER_BOUNDS_PAD_DP * density
            shaderBounds.set(
                (dockRect.left - pad).coerceAtLeast(0f),
                (dockRect.top - pad).coerceAtLeast(0f),
                (dockRect.right + pad).coerceAtMost(width.toFloat()),
                (dockRect.bottom + pad).coerceAtMost(height.toFloat())
            )
            val resolvedDockLum = if (dockBgLum >= 0f) dockBgLum else if (isLight) 0.85f else 0.15f
            liquidShader?.drawSquircle(
                canvas = canvas,
                bounds = shaderBounds,
                centerX = dockRect.centerX(),
                centerY = dockRect.centerY(),
                halfW = dockRect.width() * 0.5f,
                halfH = dockRect.height() * 0.5f,
                refraction = liquidGlassRefraction * 0.75f,
                stageBoost = 0.25f,
                backdropMix = backdropMix,
                isLightTheme = isLight,
                fillRgb = palette.fillRgb,
                accentRgb = palette.accentRgb,
                opacity = ((0.64f + 0.24f * feedbackAlpha) * alphaCurve).coerceIn(0f, 0.92f),
                screenOffsetX = windowScreenOffset[0].toFloat(),
                screenOffsetY = windowScreenOffset[1].toFloat(),
                backdropScale = backdropScale,
                shadowRangePx = SHADOW_RANGE_DP * density,
                localBgLum = resolvedDockLum
            )
        } else {
            val dockRadius = (dockRect.height() * 0.5f).coerceAtLeast(24f * density)
            slotFillPaint.color = GlassPalette.argb(palette.fillRgb, (feedbackAlpha * 0.62f) * alphaCurve)
            canvas.drawRoundRect(dockRect, dockRadius, dockRadius, slotFillPaint)
            slotRimPaint.color = GlassPalette.argb(palette.rimRgb, palette.rimBaseAlpha * alphaCurve)
            canvas.drawRoundRect(dockRect, dockRadius, dockRadius, slotRimPaint)
        }

        for (i in 0 until slotCount) {
            val sx = slotCenterX[i]
            val sy = slotCenterY[i] + liftDelta
            drawSlotBubble(canvas, i, sx, sy, alphaCurve, useLiquid, isLight, backdropMix, backdropScale)
        }
    }

    private fun drawSlotBubble(
        canvas: Canvas,
        index: Int,
        sx: Float,
        sy: Float,
        alphaCurve: Float,
        useLiquid: Boolean,
        isLight: Boolean,
        backdropMix: Float,
        backdropScale: Float
    ) {
        val isFocused = index == focusedIndex
        val scale = slotScales[index]
        val r = slotBaseRadiusPx * scale * (0.48f + 0.52f * alphaCurve)
        val fillRgb = if (isFocused) palette.fillTriggeredRgb else palette.fillRgb

        val rawSlotLum = if (useLiquid) {
            backdropCapturer?.sampleLuminanceAt(
                windowScreenOffset[0] + sx,
                windowScreenOffset[1] + sy
            ) ?: -1f
        } else {
            -1f
        }
        val slotBgLum = if (useLiquid && rawSlotLum >= 0f) {
            val prev = smoothedSlotLums[index]
            val next = if (prev < 0f) rawSlotLum else prev + (rawSlotLum - prev) * frameLumAlpha
            smoothedSlotLums[index] = next
            if (kotlin.math.abs(rawSlotLum - next) > LUM_CONVERGENCE_EPS) frameLumNeedsMoreFrames = true
            next
        } else if (useLiquid && smoothedSlotLums[index] >= 0f) {
            smoothedSlotLums[index]
        } else {
            -1f
        }

        if (useLiquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val pad = SHADER_BOUNDS_PAD_DP * density
            shaderBounds.set(
                (sx - r - pad).coerceAtLeast(0f),
                (sy - r - pad).coerceAtLeast(0f),
                (sx + r + pad).coerceAtMost(width.toFloat()),
                (sy + r + pad).coerceAtMost(height.toFloat())
            )
            val opacity = ((if (isFocused) 0.88f else 0.72f) * (0.55f + 0.45f * feedbackAlpha) * alphaCurve)
                .coerceIn(0f, 0.96f)
            val resolvedSlotLum = if (slotBgLum >= 0f) slotBgLum else if (isLight) 0.85f else 0.15f
            liquidShader?.drawCircleLens(
                canvas = canvas,
                bounds = shaderBounds,
                centerX = sx,
                centerY = sy,
                radius = r,
                refraction = liquidGlassRefraction,
                focusBoost = if (isFocused) 1.35f else 0.15f,
                backdropMix = backdropMix,
                isLightTheme = isLight,
                fillRgb = fillRgb,
                accentRgb = palette.accentRgb,
                opacity = opacity,
                screenOffsetX = windowScreenOffset[0].toFloat(),
                screenOffsetY = windowScreenOffset[1].toFloat(),
                backdropScale = backdropScale,
                shadowRangePx = SHADOW_RANGE_DP * density,
                localBgLum = resolvedSlotLum
            )
            if (isFocused) {
                val darkWeight = GlassPalette.smoothstep(0.42f, 0.72f, resolvedSlotLum)
                val ringRgb = GlassPalette.lerpRgb(palette.accentRgb, DARK_ACCENT_RGB, darkWeight)
                highlightRingPaint.color = GlassPalette.argb(ringRgb, 0.72f * alphaCurve)
                canvas.drawCircle(sx, sy, r + 3.2f * density, highlightRingPaint)
            }
        } else {
            val fillA = ((if (isFocused) feedbackAlpha + 0.18f else feedbackAlpha) * alphaCurve)
                .coerceIn(0f, 0.94f)
            slotFillPaint.color = GlassPalette.argb(fillRgb, fillA)
            canvas.drawCircle(sx, sy, r, slotFillPaint)

            val rimAlpha = (if (isFocused) palette.rimTriggeredAlpha else palette.rimBaseAlpha) * alphaCurve
            slotRimPaint.color = GlassPalette.argb(palette.rimRgb, rimAlpha)
            canvas.drawCircle(sx, sy, r, slotRimPaint)

            if (isFocused) {
                highlightRingPaint.color = GlassPalette.argb(palette.accentRgb, 0.70f * alphaCurve)
                canvas.drawCircle(sx, sy, r + 3.2f * density, highlightRingPaint)
            }
        }

        if (slotIsRecents[index]) {
            val rgb = if (useLiquid) {
                val effectiveLum = if (slotBgLum >= 0f) slotBgLum else if (isLight) 0.72f else 0.25f
                val darkWeight = GlassPalette.smoothstep(0.42f, 0.72f, effectiveLum)
                if (isFocused) {
                    GlassPalette.lerpRgb(palette.accentRgb, DARK_ACCENT_RGB, darkWeight)
                } else {
                    GlassPalette.lerpRgb(LIGHT_INK_RGB, DARK_INK_RGB, darkWeight)
                }
            } else {
                if (isFocused) palette.accentRgb else palette.iconIdleRgb
            }
            glyphPaint.color = GlassPalette.argb(rgb, (if (isFocused) 0.98f else 0.90f) * alphaCurve)
            ActionGlyphPathBuilder.build(
                glyphPath,
                tempRect,
                EdgeActionType.RECENTS,
                activeSide,
                sx,
                sy,
                r * 0.42f
            )
            canvas.drawPath(glyphPath, glyphPaint)
        } else {
            val bmp = recentAppsTracker.getCachedIcon(slotPackages[index])
            if (bmp != null) {
                val iconHalf = r * 0.62f
                tempRect.set(sx - iconHalf, sy - iconHalf, sx + iconHalf, sy + iconHalf)
                bitmapPaint.alpha = (240f * alphaCurve).toInt().coerceIn(0, 255)
                canvas.drawBitmap(bmp, null, tempRect, bitmapPaint)
            } else {
                val rgb = if (useLiquid) {
                    val effectiveLum = if (slotBgLum >= 0f) slotBgLum else if (isLight) 0.72f else 0.25f
                    val darkWeight = GlassPalette.smoothstep(0.42f, 0.72f, effectiveLum)
                    GlassPalette.lerpRgb(LIGHT_INK_RGB, DARK_INK_RGB, darkWeight)
                } else {
                    if (isFocused) palette.iconActiveRgb else palette.iconIdleRgb
                }
                glyphPaint.color = GlassPalette.argb(rgb, 0.90f * alphaCurve)
                ActionGlyphPathBuilder.build(
                    glyphPath,
                    tempRect,
                    EdgeActionType.LAUNCH_APP,
                    activeSide,
                    sx,
                    sy,
                    r * 0.40f
                )
                canvas.drawPath(glyphPath, glyphPaint)
            }

            // Tiny "#1" badge on slot 0 (instant Previous App)
            if (index == 0) {
                val bx = sx + r * 0.66f
                val by = sy - r * 0.66f
                val br = 7.5f * density
                badgeBgPaint.color = GlassPalette.argb(palette.accentRgb, 0.92f * alphaCurve)
                canvas.drawCircle(bx, by, br, badgeBgPaint)
                badgeTextPaint.color = GlassPalette.argb(0x080C14, alphaCurve)
                val base = by - (badgeTextPaint.descent() + badgeTextPaint.ascent()) * 0.5f
                canvas.drawText("1", bx, base, badgeTextPaint)
            }
        }
    }

    private fun drawFloatingLabelPill(
        canvas: Canvas,
        alphaCurve: Float,
        useLiquid: Boolean,
        isLight: Boolean,
        backdropMix: Float,
        backdropScale: Float
    ) {
        val labelText = if (focusedIndex in 0 until slotCount) {
            slotLabels[focusedIndex]
        } else {
            cancelHintText
        }
        if (labelText.isEmpty()) return

        val viewW = width.toFloat()
        val textWidth = labelTextPaint.measureText(labelText)
        val padH = 14f * density
        val pillHalfW = (textWidth * 0.5f + padH)
            .coerceAtLeast(48f * density)
            .coerceAtMost((viewW * 0.5f - 16f * density).coerceAtLeast(48f * density))
        val pillHalfH = 15f * density

        val labelX: Float
        val labelY: Float
        if (activeSide == EdgeSide.BOTTOM) {
            labelX = safeCoerce(viewW * 0.5f, pillHalfW + 16f * density, viewW - pillHalfW - 16f * density, viewW * 0.5f)
            labelY = (dockRect.top - 22f * density).coerceAtLeast(pillHalfH + 40f * density)
        } else {
            val dirSign = if (activeSide == EdgeSide.LEFT) 1f else -1f
            labelX = safeCoerce(
                originX + dirSign * (radiusPx * 0.55f),
                pillHalfW + 16f * density,
                viewW - pillHalfW - 16f * density,
                viewW * 0.5f
            )
            labelY = (originY - radiusPx - slotBaseRadiusPx - 22f * density)
                .coerceAtLeast(pillHalfH + 36f * density)
        }

        labelRect.set(
            labelX - pillHalfW,
            labelY - pillHalfH,
            labelX + pillHalfW,
            labelY + pillHalfH
        )
        val rawLabelLum = if (useLiquid) {
            backdropCapturer?.sampleLuminanceAt(
                windowScreenOffset[0] + labelX,
                windowScreenOffset[1] + labelY
            ) ?: -1f
        } else {
            -1f
        }
        val labelBgLum = if (useLiquid && rawLabelLum >= 0f) {
            val prev = smoothedLabelLum
            val next = if (prev < 0f) rawLabelLum else prev + (rawLabelLum - prev) * frameLumAlpha
            smoothedLabelLum = next
            if (kotlin.math.abs(rawLabelLum - next) > LUM_CONVERGENCE_EPS) frameLumNeedsMoreFrames = true
            next
        } else if (useLiquid && smoothedLabelLum >= 0f) {
            smoothedLabelLum
        } else {
            -1f
        }

        if (useLiquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val pad = SHADER_BOUNDS_PAD_DP * density
            shaderBounds.set(
                (labelRect.left - pad).coerceAtLeast(0f),
                (labelRect.top - pad).coerceAtLeast(0f),
                (labelRect.right + pad).coerceAtMost(viewW),
                (labelRect.bottom + pad).coerceAtMost(height.toFloat())
            )
            val resolvedLabelLum = if (labelBgLum >= 0f) labelBgLum else if (isLight) 0.85f else 0.15f
            liquidShader?.drawSquircle(
                canvas = canvas,
                bounds = shaderBounds,
                centerX = labelX,
                centerY = labelY,
                halfW = pillHalfW,
                halfH = pillHalfH,
                refraction = liquidGlassRefraction * 0.85f,
                stageBoost = if (focusedIndex >= 0) 0.85f else 0.10f,
                backdropMix = backdropMix,
                isLightTheme = isLight,
                fillRgb = palette.labelBgRgb,
                accentRgb = palette.accentRgb,
                opacity = ((0.76f + 0.20f * feedbackAlpha) * alphaCurve).coerceIn(0f, 0.96f),
                screenOffsetX = windowScreenOffset[0].toFloat(),
                screenOffsetY = windowScreenOffset[1].toFloat(),
                backdropScale = backdropScale,
                shadowRangePx = SHADOW_RANGE_DP * density,
                localBgLum = resolvedLabelLum
            )
        } else {
            val labelFillAlpha = (feedbackAlpha + 0.16f).coerceIn(0.30f, 0.92f) * alphaCurve
            labelBgPaint.color = GlassPalette.argb(palette.labelBgRgb, labelFillAlpha)
            canvas.drawRoundRect(labelRect, pillHalfH, pillHalfH, labelBgPaint)

            slotRimPaint.color = GlassPalette.argb(
                if (focusedIndex >= 0) palette.accentRgb else palette.rimRgb,
                (if (focusedIndex >= 0) 0.52f else 0.22f) * alphaCurve
            )
            canvas.drawRoundRect(labelRect, pillHalfH, pillHalfH, slotRimPaint)
        }

        val labelRgb = if (useLiquid) {
            val effectiveLum = if (labelBgLum >= 0f) labelBgLum else if (isLight) 0.72f else 0.25f
            val darkWeight = GlassPalette.smoothstep(0.42f, 0.72f, effectiveLum)
            GlassPalette.lerpRgb(LIGHT_INK_RGB, DARK_INK_RGB, darkWeight)
        } else {
            palette.labelTextRgb
        }
        labelTextPaint.color = GlassPalette.argb(
            labelRgb,
            (if (focusedIndex >= 0) 0.96f else 0.78f) * alphaCurve
        )
        val baseline = labelY - (labelTextPaint.descent() + labelTextPaint.ascent()) * 0.5f
        canvas.drawText(labelText, labelX, baseline, labelTextPaint)
    }

    private fun toLocalX(rawX: Float): Float {
        if (isAttachedToWindow && width > 0) {
            getLocationOnScreen(windowScreenOffset)
            return rawX - windowScreenOffset[0]
        }
        return rawX
    }

    private fun toLocalY(rawY: Float): Float {
        if (isAttachedToWindow && height > 0) {
            getLocationOnScreen(windowScreenOffset)
            return rawY - windowScreenOffset[1]
        }
        return rawY
    }

    private fun safeCoerce(value: Float, minVal: Float, maxVal: Float, fallback: Float): Float {
        return if (minVal <= maxVal) value.coerceIn(minVal, maxVal) else fallback
    }

    companion object {
        private const val MAX_RECENT_APPS = 6
        private const val MAX_TOTAL_SLOTS = MAX_RECENT_APPS + 1
        private const val SLOT_RADIUS_DP = 24f
        private const val FOCUSED_SCALE = 1.25f
        private const val TAP_PRESSED_SCALE = 1.15f
        private const val INITIAL_OPEN_PROGRESS = 0.08f
        private const val IDLE_EPSILON = 0.002f
        private const val BACKDROP_FADE_MS = 60L
        private const val SHADOW_RANGE_DP = 14f
        private const val SHADER_BOUNDS_PAD_DP = 24f
        private const val BRIGHT_BG_LUM_THRESHOLD = 0.56f
        private const val DARK_INK_RGB = 0x15171A
        private const val LIGHT_INK_RGB = 0xF8FAFC
        private const val DARK_ACCENT_RGB = 0x1D4ED8
        private const val LUM_SMOOTH_TAU_MS = 180.0
        private const val LUM_CONVERGENCE_EPS = 0.004f
    }
}
