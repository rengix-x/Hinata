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
import android.view.Choreographer
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
import com.edgeflow.data.QuickSlotConfig
import com.edgeflow.data.VisualTheme
import com.example.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Zero-GC, 120fps Apple iOS 26 Liquid Glass semicircular Quick Arc Menu overlay.
 *
 * Capabilities:
 * - Up to 16 slots ([EdgeConfig.MAX_QUICK_MENU_SLOTS]):
 *   - `<= 6` slots: fixed semicircular fan arc (zero accidental scrolling).
 *   - `>= 7` slots: continuous 120fps rotary scroll wheel (`VISIBLE_WINDOW_SLOTS = 6`) driven by
 *     pushing past `±52°` edge bands with smooth Choreographer velocity, tick haptics on every
 *     integer slot crossing, edge alpha/scale feathering, and an outer translucent scroll arc.
 * - API 33+ [LiquidGlassShader] circular lenses (`uShapeMode = 2`, prism-boosted when focused) and
 *   squircle floating label pill (`uShapeMode = 1`), strictly bounded to local rects with instant
 *   Canvas fallback when disabled or on API <= 32.
 * - Bidirectional Stage 2 / Stage 3 boundary retreat detection on the primary pull axis so vertical
 *   rotary scrolling never falsely cancels the menu.
 * - Strictly zero heap allocations inside [onDraw]; GONE (0% CPU/GPU) while closed.
 */
class QuickArcMenuView(
    context: Context,
    private val iconCache: QuickMenuIconCache,
    private val hapticController: HapticController,
    private val backdropCapturer: LiquidBackdropCapturer? = null,
    private val onIdleCallback: () -> Unit = {}
) : View(context) {

    private val density: Float = resources.displayMetrics.density
    private val palette = GlassPalette()
    private val liquidShader: LiquidGlassShader? = LiquidGlassShader.createIfSupported()

    // Pre-allocated Paints, Paths, RectFs, and screen-offset buffer — strictly ZERO allocations inside onDraw()
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
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.6f * density
    }
    private val scrollTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.4f * density
    }
    private val scrollThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.2f * density
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

    private val microBadgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val microBadgeRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }
    private val microBadgeGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.25f * density
    }

    private val glyphPath = Path()
    private val chevronPath = Path()
    private val tempRect = RectF()
    private val labelRect = RectF()
    private val scrollArcRect = RectF()
    private val shaderBounds = RectF()
    private val floatingBadgeRect = RectF()
    private val floatingInnerRect = RectF()
    private val windowScreenOffset = IntArray(2)

    // Pre-allocated slot arrays (up to 16 slots) — zero allocations per frame
    private val fixedSlotAnglesRad = FloatArray(MAX_SLOTS)
    private val slotCenterX = FloatArray(MAX_SLOTS)
    private val slotCenterY = FloatArray(MAX_SLOTS)
    private val slotScales = FloatArray(MAX_SLOTS) { 1f }
    private val slotLabels = Array(MAX_SLOTS) { "" }
    private var slots: List<QuickSlotConfig> = EdgeConfig.DEFAULT_QUICK_MENU_SLOTS
    private var slotCount: Int = slots.size

    // Configuration values
    private var visualTheme: VisualTheme = VisualTheme.AUTO
    private var feedbackAlpha: Float = EdgeConfig.DEFAULT_FEEDBACK_ALPHA
    private var liquidGlassEnabled: Boolean = true
    private var liquidGlassRefraction: Float = EdgeConfig.DEFAULT_LIQUID_GLASS_REFRACTION
    private var radiusPx: Float = EdgeConfig.DEFAULT_QUICK_MENU_RADIUS_DP * density
    private var dimAlpha: Float = EdgeConfig.DEFAULT_QUICK_MENU_DIM_ALPHA
    private var retreatMode: QuickMenuRetreatMode = QuickMenuRetreatMode.RADIAL_ARC
    private var retreatBoundaryRatio: Float = EdgeConfig.DEFAULT_QUICK_MENU_RETREAT_BOUNDARY_RATIO
    private var rotarySpeedMultiplier: Float = EdgeConfig.DEFAULT_QUICK_MENU_ROTARY_SPEED
    private val slotBaseRadiusPx: Float = SLOT_RADIUS_DP * density
    private var deadZoneRadiusPx: Float = EdgeConfig.DEFAULT_QUICK_MENU_DEAD_ZONE_DP * density

    // Active runtime session state
    private var activeSide: EdgeSide = EdgeSide.LEFT
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

    // Tap-Selection (Sticky) mode state for Stage-1 / diagonal commits
    var onTapSelectionAction: ((EdgeAction) -> Unit)? = null
    var onTapSelectionDismiss: (() -> Unit)? = null
    private var isTapSelectionMode: Boolean = false
    private var tapPressedSlotIndex: Int = -1
    private var tapDownX: Float = 0f
    private var tapDownY: Float = 0f
    private var tapLastAxis: Float = 0f
    private var tapDraggingRotary: Boolean = false
    private val tapTouchSlopPx: Float = 10f * density

    // Rotary scroll wheel state (active ONLY when slotCount > VISIBLE_WINDOW_SLOTS)
    private var dialScrollOffset: Float = 0f
    private var dialScrollVelocitySlotsPerSec: Float = 0f
    private var lastDialStepInt: Int = 0
    private var lastDialFrameNanos: Long = 0L
    private var dialChoreographerRunning: Boolean = false
    private var pendingOverscrollSign: Int = 0
    private var pendingOverscrollVelocity: Float = 0f
    private var overscrollEnteredAtMs: Long = 0L
    private val overscrollConfirmRunnable = Runnable {
        if (isMenuOpen && !isTapSelectionMode && isRotaryMode() &&
            pendingOverscrollSign != 0 && abs(pendingOverscrollVelocity) > 0.01f
        ) {
            dialScrollVelocitySlotsPerSec = pendingOverscrollVelocity
            startDialChoreographer()
        }
    }

    // Liquid Glass backdrop fade-in & per-slot temporal luminance smoothing state (Zero-GC)
    private var backdropReadyAtMs: Long = 0L
    private var lastBackdropGen: Long = -1L
    private var activeSessionHasBackdrop: Boolean = false
    private val smoothedSlotLums = FloatArray(MAX_SLOTS) { -1f }
    private var smoothedLabelLum: Float = -1f
    private var lastLumUpdateUptimeMs: Long = 0L
    private val backdropReadyListener: () -> Unit = {
        if (!activeSessionHasBackdrop) {
            backdropReadyAtMs = SystemClock.uptimeMillis()
            activeSessionHasBackdrop = true
        }
        if (visibility == VISIBLE) {
            invalidate()
        }
    }

    // Pre-allocated Choreographer callback for 120fps rotary wheel overscroll
    private val dialScrollFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isMenuOpen || !isRotaryMode() || abs(dialScrollVelocitySlotsPerSec) < 0.01f) {
                stopDialChoreographer()
                return
            }
            val dtSec = if (lastDialFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameTimeNanos - lastDialFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
            }
            lastDialFrameNanos = frameTimeNanos

            val maxScroll = maxDialScroll()
            val prevOffset = dialScrollOffset
            val nextOffset = (prevOffset + dialScrollVelocitySlotsPerSec * dtSec).coerceIn(0f, maxScroll)
            dialScrollOffset = nextOffset

            val currentStep = floor(nextOffset + 0.5f).toInt()
            if (currentStep != lastDialStepInt) {
                lastDialStepInt = currentStep
                hapticController.performPrimaryTrigger()
            }

            // Re-evaluate focused slot cleanly under current finger position
            updateFocusForCurrentFinger(allowHaptic = false)
            invalidate()

            val hitEdge = (dialScrollVelocitySlotsPerSec < 0f && nextOffset <= 0.0001f) ||
                (dialScrollVelocitySlotsPerSec > 0f && nextOffset >= maxScroll - 0.0001f)
            if (hitEdge) {
                dialScrollOffset = if (dialScrollVelocitySlotsPerSec < 0f) 0f else maxScroll
                stopDialChoreographer()
                updateFocusForCurrentFinger(allowHaptic = false)
                invalidate()
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    // Pre-allocated SpringAnimation for radial fan expansion & retraction
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

    // Pre-allocated SpringAnimation for smooth 1.0f <-> 1.25f slot focus scaling
    private val focusHolder = FloatValueHolder(0f)
    private val focusSpring = SpringAnimation(focusHolder).apply {
        spring = SpringForce(1f).apply {
            stiffness = SpringForce.STIFFNESS_MEDIUM
            dampingRatio = 0.80f
        }
        minimumVisibleChange = 0.004f
        addUpdateListener { _, _, _ ->
            stepSlotScales(0.36f)
            if (visibility == VISIBLE) invalidate()
        }
        addEndListener { _, canceled, _, _ ->
            if (!canceled) {
                snapSlotScales()
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
        rebuildFixedSlotAngles()
        refreshSlotLabels()
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
        rotarySpeedMultiplier = EdgeConfig.DEFAULT_QUICK_MENU_ROTARY_SPEED
        appLanguageTag = config.appLanguageTag
        slots = config.quickMenuSlots
        slotCount = slots.size.coerceIn(0, MAX_SLOTS)
        dialScrollOffset = dialScrollOffset.coerceIn(0f, maxDialScroll())
        palette.resolve(context, visualTheme)
        refreshLocalizedStrings()
        rebuildFixedSlotAngles()
        refreshSlotLabels()
    }

    fun refreshTheme() {
        palette.resolve(context, visualTheme)
        refreshLocalizedStrings()
        refreshSlotLabels()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        palette.resolve(context, visualTheme)
        refreshLocalizedStrings()
        refreshSlotLabels()
    }

    private fun refreshLocalizedStrings() {
        val localized = LocalizedContextHelper.forTag(context, appLanguageTag)
        cancelHintText = localized.getString(R.string.arc_deadzone_cancel)
    }

    fun refreshSlotLabels() {
        for (i in 0 until slotCount) {
            val slot = slots[i]
            slotLabels[i] = when {
                slot.customLabel.isNotBlank() -> slot.customLabel
                slot.action.isAppLaunch -> {
                    iconCache.getAppLabel(slot.action.packageName) ?: slot.action.packageName
                }
                slot.action.isShortcutLaunch -> {
                    iconCache.getAppLabel(slot.action.packageName)
                        ?: resolveSystemActionLabel(slot.action.type)
                }
                slot.action.isUrlLaunch -> {
                    formatShortUrlLabel(slot.action.targetData)
                }
                else -> resolveSystemActionLabel(slot.action.type)
            }
        }
    }

    fun isOpen(): Boolean = isMenuOpen

    fun isTapSelectionActive(): Boolean = isMenuOpen && isTapSelectionMode

    /**
     * Checks whether the finger has crossed the configured lower-stage return boundary
     * ([QuickMenuRetreatMode.RADIAL_ARC], [QuickMenuRetreatMode.EDGE_DEADZONE], or
     * [QuickMenuRetreatMode.CUSTOM_LINE]) without false misfires while selecting top/bottom
     * arc slots or spinning the rotary dial. Zero heap allocations.
     */
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
        val screenAxisPx = currentScreenAxisPx()
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

    private fun currentScreenAxisPx(): Float {
        return if (activeSide == EdgeSide.BOTTOM) {
            if (height > 0) height.toFloat() else fallbackScreenH.toFloat()
        } else {
            if (width > 0) width.toFloat() else fallbackScreenW.toFloat()
        }.coerceAtLeast(320f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && isMenuOpen) {
            recomputeOrigin()
            updateTouch(lastFingerRawX, lastFingerRawY)
        }
    }

    override fun onDetachedFromWindow() {
        stopDialChoreographer()
        expandSpring.cancel()
        focusSpring.cancel()
        isMenuOpen = false
        isTapSelectionMode = false
        tapPressedSlotIndex = -1
        tapDraggingRotary = false
        focusedIndex = -1
        expandProgress = 0f
        if (visibility != GONE) visibility = GONE
        super.onDetachedFromWindow()
    }

    fun release() {
        backdropCapturer?.removeBackdropReadyListener(backdropReadyListener)
        hideImmediately()
    }

    /**
     * Opens the semicircular arc menu anchored on [side] (LEFT, RIGHT, or BOTTOM).
     */
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

        recomputeOrigin()

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
        tapDraggingRotary = false
        stopDialChoreographer()
        dialScrollOffset = 0f
        lastDialStepInt = 0
        isMenuOpen = true
        focusedIndex = -1
        for (i in 0 until MAX_SLOTS) slotScales[i] = 1f

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
     * Opens the arc menu in Tap-Selection (Sticky) mode after a Stage-1 / diagonal swipe commit.
     * The menu stays open until the user taps a slot, taps outside / in the dead-zone, or
     * vertically drags to spin the rotary wheel (when 7+ slots exist).
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

        recomputeOrigin()

        val hasCachedBackdrop = backdropCapturer?.currentShader() != null
        activeSessionHasBackdrop = hasCachedBackdrop
        backdropReadyAtMs = 0L
        lastBackdropGen = backdropCapturer?.currentGeneration() ?: -1L

        stopDialChoreographer()
        dialScrollOffset = 0f
        lastDialStepInt = 0
        isTapSelectionMode = true
        tapPressedSlotIndex = -1
        tapDraggingRotary = false
        isMenuOpen = true
        focusedIndex = -1
        for (i in 0 until MAX_SLOTS) slotScales[i] = 1f

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
                tapDownX = x
                tapDownY = y
                tapLastAxis = if (activeSide == EdgeSide.BOTTOM) x else y
                tapDraggingRotary = false
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
                if (isRotaryMode()) {
                    val axisDeltaFromDown = if (activeSide == EdgeSide.BOTTOM) {
                        abs(x - tapDownX)
                    } else {
                        abs(y - tapDownY)
                    }
                    if (!tapDraggingRotary && axisDeltaFromDown >= tapTouchSlopPx) {
                        tapDraggingRotary = true
                        tapPressedSlotIndex = -1
                        focusedIndex = -1
                        for (i in 0 until slotCount) slotScales[i] = 1f
                    }
                    if (tapDraggingRotary) {
                        val currentAxis = if (activeSide == EdgeSide.BOTTOM) x else y
                        val stepDelta = currentAxis - tapLastAxis
                        tapLastAxis = currentAxis
                        val pxPerSlot = (slotBaseRadiusPx * 2.0f).coerceAtLeast(36f * density)
                        val maxScroll = maxDialScroll()
                        dialScrollOffset = (dialScrollOffset - stepDelta / pxPerSlot).coerceIn(0f, maxScroll)
                        val currentStep = floor(dialScrollOffset + 0.5f).toInt()
                        if (currentStep != lastDialStepInt) {
                            lastDialStepInt = currentStep
                            hapticController.performPrimaryTrigger()
                        }
                        invalidate()
                        return true
                    }
                }
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
                if (tapDraggingRotary) {
                    tapDraggingRotary = false
                    invalidate()
                    return true
                }
                val chosenIndex = if (tapPressedSlotIndex in 0 until slotCount) {
                    tapPressedSlotIndex
                } else {
                    findTappedSlot(x, y)
                }
                tapPressedSlotIndex = -1
                if (chosenIndex in 0 until slotCount) {
                    val action = slots[chosenIndex].action
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
                tapDraggingRotary = false
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
            if (slotEdgeVisibilityFor(i) < 0.25f) continue
            val d = hypot((localX - slotCenterX[i]).toDouble(), (localY - slotCenterY[i]).toDouble()).toFloat()
            if (d <= bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun closeAfterTapCommit(selected: EdgeAction) {
        isMenuOpen = false
        isTapSelectionMode = false
        tapPressedSlotIndex = -1
        tapDraggingRotary = false
        focusedIndex = -1
        launchedStage = 0
        retreatBoundaryPx = 0f
        stopDialChoreographer()
        if (selected.type == EdgeActionType.CIRCLE_TO_SEARCH ||
            selected.type == EdgeActionType.SCREENSHOT ||
            selected.type == EdgeActionType.RECENTS ||
            selected.type == EdgeActionType.FLOAT_CURRENT_APP ||
            selected.type == EdgeActionType.LAUNCH_APP_FLOATING ||
            selected.type == EdgeActionType.TOGGLE_SCREEN_TRANSLATE ||
            selected.type == EdgeActionType.TRANSLATE_SCREEN_ONCE
        ) {
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

    private fun recomputeOrigin() {
        val viewW = if (width > 0) width.toFloat() else fallbackScreenW.toFloat().coerceAtLeast(320f)
        val viewH = if (height > 0) height.toFloat() else fallbackScreenH.toFloat().coerceAtLeast(480f)
        val localAnchorX = toLocalX(anchorRawX)
        val localAnchorY = toLocalY(anchorRawY)

        if (activeSide == EdgeSide.BOTTOM) {
            val marginX = max(radiusPx + slotBaseRadiusPx + 18f * density, 72f * density)
            originX = safeCoerce(localAnchorX, marginX, viewW - marginX, viewW * 0.5f)
            originY = viewH
        } else {
            originX = if (activeSide == EdgeSide.LEFT) 0f else viewW
            val marginY = max(radiusPx + slotBaseRadiusPx + 36f * density, 72f * density)
            originY = safeCoerce(localAnchorY, marginY, viewH - marginY, viewH * 0.5f)
        }
    }

    /**
     * Processes ongoing finger coordinates from the live one-stroke touch stream.
     */
    fun updateTouch(rawX: Float, rawY: Float) {
        if (!isMenuOpen) return
        lastFingerRawX = rawX
        lastFingerRawY = rawY
        updateFocusForCurrentFinger(allowHaptic = true)
        invalidate()
    }

    private fun updateFocusForCurrentFinger(allowHaptic: Boolean) {
        val localX = toLocalX(lastFingerRawX)
        val localY = toLocalY(lastFingerRawY)
        val dx = localX - originX
        val dy = localY - originY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        var pointerAngleValid = false
        var pointerAngleRad = 0f

        val newFocus = if (dist < deadZoneRadiusPx || slotCount == 0) {
            -1
        } else if (activeSide == EdgeSide.BOTTOM) {
            val upwardDy = -dy
            if (upwardDy < 10f * density) {
                -1
            } else {
                pointerAngleRad = atan2(dx.toDouble(), upwardDy.toDouble()).toFloat()
                pointerAngleValid = true
                findClosestVisibleSlot(pointerAngleRad)
            }
        } else {
            val inwardDx = if (activeSide == EdgeSide.LEFT) dx else -dx
            if (inwardDx < 10f * density) {
                -1
            } else {
                pointerAngleRad = atan2(dy.toDouble(), inwardDx.toDouble()).toFloat()
                pointerAngleValid = true
                findClosestVisibleSlot(pointerAngleRad)
            }
        }

        // Rotary wheel continuous overscroll past dynamic start angle (±74° default) with 110ms hold confirmation
        if (pointerAngleValid && isRotaryMode() && !isTapSelectionMode) {
            val angleDeg = (pointerAngleRad * 180.0 / PI).toFloat()
            val overscrollStartDeg = MenuRetreatEvaluator.computeDynamicOverscrollStartDeg(rotarySpeedMultiplier)
            val maxSpeed = MAX_ROTARY_SLOTS_PER_SEC * rotarySpeedMultiplier
            val maxScroll = maxDialScroll()
            val nextVelocity = when {
                angleDeg > overscrollStartDeg && dialScrollOffset < maxScroll -> {
                    val depth = ((angleDeg - overscrollStartDeg) / OVERSCROLL_FULL_SPAN_DEG).coerceIn(0.15f, 1.4f)
                    depth * maxSpeed
                }
                angleDeg < -overscrollStartDeg && dialScrollOffset > 0f -> {
                    val depth = ((-angleDeg - overscrollStartDeg) / OVERSCROLL_FULL_SPAN_DEG).coerceIn(0.15f, 1.4f)
                    -depth * maxSpeed
                }
                else -> 0f
            }
            if (abs(nextVelocity) > 0.01f) {
                val sign = if (nextVelocity > 0f) 1 else -1
                pendingOverscrollVelocity = nextVelocity
                val nowMs = SystemClock.uptimeMillis()
                if (sign != pendingOverscrollSign) {
                    pendingOverscrollSign = sign
                    overscrollEnteredAtMs = nowMs
                    stopDialChoreographer(clearPending = false)
                    removeCallbacks(overscrollConfirmRunnable)
                    postDelayed(overscrollConfirmRunnable, OVERSCROLL_HOLD_CONFIRM_MS)
                } else if (dialChoreographerRunning) {
                    dialScrollVelocitySlotsPerSec = nextVelocity
                } else if (nowMs - overscrollEnteredAtMs >= OVERSCROLL_HOLD_CONFIRM_MS) {
                    removeCallbacks(overscrollConfirmRunnable)
                    dialScrollVelocitySlotsPerSec = nextVelocity
                    startDialChoreographer()
                }
            } else {
                stopDialChoreographer()
            }
        } else {
            stopDialChoreographer()
        }

        if (newFocus != focusedIndex) {
            focusedIndex = newFocus
            if (allowHaptic && focusedIndex >= 0) {
                hapticController.performPrimaryTrigger()
            }
            focusSpring.cancel()
            focusHolder.value = 0f
            focusSpring.spring.finalPosition = 1f
            focusSpring.setStartValue(0f)
            focusSpring.start()
        }
    }

    private fun startDialChoreographer() {
        if (dialChoreographerRunning) return
        dialChoreographerRunning = true
        lastDialFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(dialScrollFrameCallback)
    }

    private fun stopDialChoreographer(clearPending: Boolean = true) {
        dialScrollVelocitySlotsPerSec = 0f
        if (clearPending) {
            removeCallbacks(overscrollConfirmRunnable)
            pendingOverscrollSign = 0
            pendingOverscrollVelocity = 0f
            overscrollEnteredAtMs = 0L
        }
        if (!dialChoreographerRunning) return
        dialChoreographerRunning = false
        lastDialFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(dialScrollFrameCallback)
    }

    private fun isRotaryMode(): Boolean = slotCount > VISIBLE_WINDOW_SLOTS

    private fun maxDialScroll(): Float = (slotCount - VISIBLE_WINDOW_SLOTS).coerceAtLeast(0).toFloat()

    private fun slotAngleRadFor(index: Int): Float {
        if (!isRotaryMode()) {
            return fixedSlotAnglesRad[index]
        }
        val rel = index.toFloat() - dialScrollOffset
        val startDeg = -ROTARY_SWEEP_DEG * 0.5f
        val stepDeg = ROTARY_SWEEP_DEG / (VISIBLE_WINDOW_SLOTS - 1)
        val angleDeg = startDeg + rel * stepDeg
        return (angleDeg * PI / 180.0).toFloat()
    }

    private fun slotEdgeVisibilityFor(index: Int): Float {
        if (!isRotaryMode()) return 1f
        val rel = index.toFloat() - dialScrollOffset
        val maxVisible = (VISIBLE_WINDOW_SLOTS - 1).toFloat()
        return when {
            rel < -0.65f || rel > maxVisible + 0.65f -> 0f
            rel < 0f -> ((rel + 0.65f) / 0.65f).coerceIn(0f, 1f)
            rel > maxVisible -> ((maxVisible + 0.65f - rel) / 0.65f).coerceIn(0f, 1f)
            else -> 1f
        }
    }

    private fun findClosestVisibleSlot(pointerAngle: Float): Int {
        var bestIdx = -1
        var bestDiff = Float.MAX_VALUE
        for (i in 0 until slotCount) {
            if (slotEdgeVisibilityFor(i) < 0.25f) continue
            val diff = abs(pointerAngle - slotAngleRadFor(i))
            if (diff < bestDiff) {
                bestDiff = diff
                bestIdx = i
            }
        }
        return bestIdx
    }

    fun releaseAndCommit(): EdgeAction {
        if (!isMenuOpen) return EdgeAction.NONE
        val selected = if (focusedIndex in 0 until slotCount) {
            slots[focusedIndex].action
        } else {
            EdgeAction.NONE
        }
        isMenuOpen = false
        isTapSelectionMode = false
        tapPressedSlotIndex = -1
        tapDraggingRotary = false
        focusedIndex = -1
        launchedStage = 0
        retreatBoundaryPx = 0f
        stopDialChoreographer()

        if (selected.type == EdgeActionType.CIRCLE_TO_SEARCH ||
            selected.type == EdgeActionType.SCREENSHOT ||
            selected.type == EdgeActionType.RECENTS ||
            selected.type == EdgeActionType.FLOAT_CURRENT_APP ||
            selected.type == EdgeActionType.LAUNCH_APP_FLOATING ||
            selected.type == EdgeActionType.TOGGLE_SCREEN_TRANSLATE ||
            selected.type == EdgeActionType.TRANSLATE_SCREEN_ONCE
        ) {
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
        return selected
    }

    fun cancelAndClose() {
        if (!isMenuOpen && visibility == GONE) return
        isMenuOpen = false
        isTapSelectionMode = false
        tapPressedSlotIndex = -1
        tapDraggingRotary = false
        focusedIndex = -1
        launchedStage = 0
        retreatBoundaryPx = 0f
        stopDialChoreographer()
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
        tapDraggingRotary = false
        focusedIndex = -1
        launchedStage = 0
        retreatBoundaryPx = 0f
        stopDialChoreographer()
        expandSpring.cancel()
        focusSpring.cancel()
        expandProgress = 0f
        for (i in 0 until MAX_SLOTS) slotScales[i] = 1f
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
        stopDialChoreographer()
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
        for (i in 0 until MAX_SLOTS) smoothedSlotLums[i] = -1f
        smoothedLabelLum = -1f
        lastLumUpdateUptimeMs = 0L
    }

    private fun rebuildFixedSlotAngles() {
        if (slotCount <= 0) return
        if (slotCount == 1) {
            fixedSlotAnglesRad[0] = 0f
            return
        }
        val countForFixed = slotCount.coerceAtMost(VISIBLE_WINDOW_SLOTS)
        val sweepDeg = when {
            countForFixed <= 3 -> 104f
            countForFixed == 4 -> 118f
            countForFixed == 5 -> 128f
            else -> ROTARY_SWEEP_DEG
        }
        val startDeg = -sweepDeg / 2f
        val stepDeg = sweepDeg / (countForFixed - 1)
        for (i in 0 until countForFixed) {
            fixedSlotAnglesRad[i] = ((startDeg + stepDeg * i) * PI / 180.0).toFloat()
        }
    }

    private fun stepSlotScales(factor: Float) {
        for (i in 0 until slotCount) {
            val target = if (i == focusedIndex) FOCUSED_SCALE else 1f
            slotScales[i] += (target - slotScales[i]) * factor
        }
    }

    private fun snapSlotScales() {
        for (i in 0 until slotCount) {
            slotScales[i] = if (i == focusedIndex) FOCUSED_SCALE else 1f
        }
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
        val lumAlpha = if (lastLumUpdateUptimeMs == 0L) {
            1f
        } else {
            val dtMs = (nowMs - lastLumUpdateUptimeMs).coerceIn(1L, 64L)
            1f - kotlin.math.exp((-dtMs.toDouble() / LUM_SMOOTH_TAU_MS)).toFloat()
        }
        if (useLiquid) {
            lastLumUpdateUptimeMs = nowMs
        }
        var lumNeedsAnimFrame = false
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

        // 2. Intuitive return boundary / dead-zone guide (zero GC, brightens as finger approaches)
        drawRetreatGuide(canvas, viewW, viewH, alphaCurve)

        // 3. Compute semicircular fan slot positions
        val dirSign = if (activeSide == EdgeSide.LEFT) 1f else -1f
        val currentRadius = radiusPx * p

        for (i in 0 until slotCount) {
            val angle = slotAngleRadFor(i)
            if (activeSide == EdgeSide.BOTTOM) {
                slotCenterX[i] = originX + sin(angle) * currentRadius
                slotCenterY[i] = originY - cos(angle) * currentRadius
            } else {
                slotCenterX[i] = originX + dirSign * cos(angle) * currentRadius
                slotCenterY[i] = originY + sin(angle) * currentRadius
            }
        }

        // 3a. Rotary outer scroll indicator arc + subtle edge chevron hints (>= 7 slots)
        if (isRotaryMode()) {
            drawRotaryScrollIndicator(canvas, currentRadius, alphaCurve)
        }

        // 3b. Subtle translucent guide ray from origin to focused slot
        if (focusedIndex in 0 until slotCount && slotEdgeVisibilityFor(focusedIndex) > 0.15f) {
            val fx = slotCenterX[focusedIndex]
            val fy = slotCenterY[focusedIndex]
            rayPaint.color = GlassPalette.argb(palette.accentRgb, 0.38f * alphaCurve)
            canvas.drawLine(originX, originY, fx, fy, rayPaint)
        }

        // 4. Draw visible slots with Liquid Glass (API 33+) or lightweight Canvas glass fallback
        for (i in 0 until slotCount) {
            val edgeVis = slotEdgeVisibilityFor(i)
            if (edgeVis <= 0.02f) continue

            val sx = slotCenterX[i]
            val sy = slotCenterY[i]
            val isFocused = i == focusedIndex
            val scale = slotScales[i] * (0.72f + 0.28f * edgeVis)
            val r = slotBaseRadiusPx * scale * (0.45f + 0.55f * alphaCurve)
            val slotAlpha = alphaCurve * edgeVis

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
                val prev = smoothedSlotLums[i]
                val next = if (prev < 0f) rawSlotLum else prev + (rawSlotLum - prev) * lumAlpha
                smoothedSlotLums[i] = next
                if (abs(rawSlotLum - next) > LUM_CONVERGENCE_EPS) lumNeedsAnimFrame = true
                next
            } else if (useLiquid && smoothedSlotLums[i] >= 0f) {
                smoothedSlotLums[i]
            } else {
                -1f
            }

            if (useLiquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val pad = SHADER_BOUNDS_PAD_DP * density
                shaderBounds.set(
                    (sx - r - pad).coerceAtLeast(0f),
                    (sy - r - pad).coerceAtLeast(0f),
                    (sx + r + pad).coerceAtMost(viewW),
                    (sy + r + pad).coerceAtMost(viewH)
                )
                val opacity = ((if (isFocused) 0.88f else 0.72f) * (0.55f + 0.45f * feedbackAlpha) * slotAlpha)
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
                    highlightRingPaint.color = GlassPalette.argb(ringRgb, 0.72f * slotAlpha)
                    canvas.drawCircle(sx, sy, r + 3.2f * density, highlightRingPaint)
                }
            } else {
                val fillA = ((if (isFocused) feedbackAlpha + 0.18f else feedbackAlpha) * slotAlpha)
                    .coerceIn(0f, 0.94f)
                slotFillPaint.color = GlassPalette.argb(fillRgb, fillA)
                canvas.drawCircle(sx, sy, r, slotFillPaint)

                val rimAlpha = (if (isFocused) palette.rimTriggeredAlpha else palette.rimBaseAlpha) * slotAlpha
                slotRimPaint.color = GlassPalette.argb(palette.rimRgb, rimAlpha)
                canvas.drawCircle(sx, sy, r, slotRimPaint)

                if (isFocused) {
                    highlightRingPaint.color = GlassPalette.argb(palette.accentRgb, 0.72f * slotAlpha)
                    canvas.drawCircle(sx, sy, r + 3.2f * density, highlightRingPaint)
                }
            }

            // Draw cached 44dp app Bitmap OR crisp system vector glyph
            val slot = slots[i]
            val wantsAppIcon = (slot.action.isAppLaunch || slot.action.isShortcutLaunch) &&
                slot.action.packageName.isNotEmpty()
            if (wantsAppIcon) {
                val bmp = iconCache.getIcon(slot.action.packageName)
                if (bmp != null) {
                    val iconHalf = r * 0.62f
                    tempRect.set(sx - iconHalf, sy - iconHalf, sx + iconHalf, sy + iconHalf)
                    bitmapPaint.alpha = (238f * slotAlpha).toInt().coerceIn(0, 255)
                    canvas.drawBitmap(bmp, null, tempRect, bitmapPaint)
                } else {
                    drawSystemGlyph(canvas, slot.action.type, sx, sy, r * 0.42f, isFocused, slotAlpha, useLiquid, slotBgLum, isLight)
                }
                if (slot.action.type == EdgeActionType.LAUNCH_APP_FLOATING) {
                    drawFloatingWindowBadge(canvas, sx, sy, r, isFocused, slotAlpha)
                } else if (slot.action.type == EdgeActionType.LAUNCH_SHORTCUT) {
                    drawShortcutArrowBadge(canvas, sx, sy, r, isFocused, slotAlpha)
                }
            } else {
                drawSystemGlyph(canvas, slot.action.type, sx, sy, r * 0.42f, isFocused, slotAlpha, useLiquid, slotBgLum, isLight)
            }
        }

        // 5. Floating Liquid Glass / frosted label pill showing selected app/action name
        val labelText = if (focusedIndex in 0 until slotCount) {
            slotLabels[focusedIndex]
        } else {
            cancelHintText
        }
        if (labelText.isNotEmpty()) {
            val textWidth = labelTextPaint.measureText(labelText)
            val padH = 14f * density
            val pillHalfW = (textWidth * 0.5f + padH)
                .coerceAtLeast(48f * density)
                .coerceAtMost((viewW * 0.5f - 16f * density).coerceAtLeast(48f * density))
            val pillHalfH = 15f * density

            val targetX = if (activeSide == EdgeSide.BOTTOM) {
                originX
            } else {
                originX + dirSign * (radiusPx * 0.52f)
            }
            val labelX = safeCoerce(targetX, pillHalfW + 16f * density, viewW - pillHalfW - 16f * density, viewW * 0.5f)
            val labelY = (originY - radiusPx - slotBaseRadiusPx - 24f * density)
                .coerceAtLeast(pillHalfH + 36f * density)

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
                val next = if (prev < 0f) rawLabelLum else prev + (rawLabelLum - prev) * lumAlpha
                smoothedLabelLum = next
                if (abs(rawLabelLum - next) > LUM_CONVERGENCE_EPS) lumNeedsAnimFrame = true
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
                    (labelRect.bottom + pad).coerceAtMost(viewH)
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
                    (if (focusedIndex >= 0) 0.52f else 0.24f) * alphaCurve
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
            val textBaseline = labelY - (labelTextPaint.descent() + labelTextPaint.ascent()) * 0.5f
            canvas.drawText(labelText, labelX, textBaseline, labelTextPaint)
        }

        if (lumNeedsAnimFrame && visibility == VISIBLE) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawRetreatGuide(canvas: Canvas, viewW: Float, viewH: Float, alphaCurve: Float) {
        val localFingerX = lastFingerRawX - windowScreenOffset[0]
        val localFingerY = lastFingerRawY - windowScreenOffset[1]
        val fingerDist = hypot(
            (localFingerX - originX).toDouble(),
            (localFingerY - originY).toDouble()
        ).toFloat()
        val approachBandPx = 24f * density

        if (launchedStage >= 2) {
            when (retreatMode) {
                QuickMenuRetreatMode.RADIAL_ARC -> {
                    val retreatR = MenuRetreatEvaluator.computeRadialRetreatRadiusPx(
                        radiusPx = radiusPx,
                        deadZoneRadiusPx = deadZoneRadiusPx,
                        boundaryRatio = retreatBoundaryRatio
                    )
                    val proximity = (1f - ((fingerDist - retreatR).coerceAtLeast(0f) / approachBandPx))
                        .coerceIn(0f, 1f)
                    val ringAlpha = (0.16f + 0.30f * proximity) * alphaCurve
                    deadZonePaint.color = GlassPalette.argb(
                        if (proximity > 0.35f) palette.accentRgb else palette.rimRgb,
                        ringAlpha
                    )
                    canvas.drawCircle(originX, originY, retreatR * alphaCurve, deadZonePaint)
                    return
                }

                QuickMenuRetreatMode.EDGE_DEADZONE -> {
                    val edgeR = deadZoneRadiusPx * 0.85f
                    val proximity = (1f - ((fingerDist - edgeR).coerceAtLeast(0f) / approachBandPx))
                        .coerceIn(0f, 1f)
                    val ringAlpha = (0.16f + 0.28f * proximity) * alphaCurve
                    deadZonePaint.color = GlassPalette.argb(
                        if (proximity > 0.35f) palette.accentRgb else palette.rimRgb,
                        ringAlpha
                    )
                    canvas.drawCircle(originX, originY, edgeR * alphaCurve, deadZonePaint)
                    return
                }

                QuickMenuRetreatMode.CUSTOM_LINE -> {
                    val deadAlpha = (if (focusedIndex < 0) 0.30f else 0.14f) * alphaCurve
                    deadZonePaint.color = GlassPalette.argb(palette.rimRgb, deadAlpha)
                    canvas.drawCircle(originX, originY, deadZoneRadiusPx * alphaCurve, deadZonePaint)

                    val screenAxisPx = if (activeSide == EdgeSide.BOTTOM) viewH else viewW
                    val lineOffset = MenuRetreatEvaluator.computeCustomLinePx(screenAxisPx, retreatBoundaryRatio)
                    val primaryPull = when (activeSide) {
                        EdgeSide.LEFT -> lastFingerRawX - anchorRawX
                        EdgeSide.RIGHT -> anchorRawX - lastFingerRawX
                        EdgeSide.BOTTOM -> anchorRawY - lastFingerRawY
                    }
                    val proximity = (1f - (abs(primaryPull - lineOffset) / approachBandPx)).coerceIn(0f, 1f)
                    val lineAlpha = (0.12f + 0.32f * proximity) * alphaCurve
                    deadZonePaint.color = GlassPalette.argb(
                        if (proximity > 0.35f) palette.accentRgb else palette.rimRgb,
                        lineAlpha
                    )
                    when (activeSide) {
                        EdgeSide.LEFT -> {
                            val lx = lineOffset.coerceIn(0f, viewW)
                            canvas.drawLine(lx, (originY - radiusPx).coerceAtLeast(0f), lx, (originY + radiusPx).coerceAtMost(viewH), deadZonePaint)
                        }
                        EdgeSide.RIGHT -> {
                            val lx = (viewW - lineOffset).coerceIn(0f, viewW)
                            canvas.drawLine(lx, (originY - radiusPx).coerceAtLeast(0f), lx, (originY + radiusPx).coerceAtMost(viewH), deadZonePaint)
                        }
                        EdgeSide.BOTTOM -> {
                            val ly = (viewH - lineOffset).coerceIn(0f, viewH)
                            canvas.drawLine((originX - radiusPx).coerceAtLeast(0f), ly, (originX + radiusPx).coerceAtMost(viewW), ly, deadZonePaint)
                        }
                    }
                    return
                }
            }
        }

        val deadAlpha = (if (focusedIndex < 0) 0.34f else 0.14f) * alphaCurve
        deadZonePaint.color = GlassPalette.argb(palette.rimRgb, deadAlpha)
        canvas.drawCircle(originX, originY, deadZoneRadiusPx * alphaCurve, deadZonePaint)
    }

    private fun drawRotaryScrollIndicator(canvas: Canvas, currentRadius: Float, alphaCurve: Float) {
        val trackR = currentRadius + slotBaseRadiusPx + 11f * density
        scrollArcRect.set(originX - trackR, originY - trackR, originX + trackR, originY + trackR)

        val totalSweep = ROTARY_SWEEP_DEG
        val fracVisible = (VISIBLE_WINDOW_SLOTS.toFloat() / slotCount.toFloat()).coerceIn(0.18f, 0.92f)
        val thumbSweep = totalSweep * fracVisible
        val scrollRatio = if (maxDialScroll() > 0f) (dialScrollOffset / maxDialScroll()).coerceIn(0f, 1f) else 0f

        scrollTrackPaint.color = GlassPalette.argb(palette.rimRgb, 0.20f * alphaCurve)
        scrollThumbPaint.color = GlassPalette.argb(palette.accentRgb, 0.78f * alphaCurve)

        when (activeSide) {
            EdgeSide.LEFT -> {
                val trackStart = -totalSweep * 0.5f
                canvas.drawArc(scrollArcRect, trackStart, totalSweep, false, scrollTrackPaint)
                val thumbStart = trackStart + (totalSweep - thumbSweep) * scrollRatio
                canvas.drawArc(scrollArcRect, thumbStart, thumbSweep, false, scrollThumbPaint)
            }
            EdgeSide.RIGHT -> {
                val trackStart = 180f - totalSweep * 0.5f
                canvas.drawArc(scrollArcRect, trackStart, totalSweep, false, scrollTrackPaint)
                // Slot 0 is at top (-68deg -> canvas 248deg), max slot at bottom (+68deg -> canvas 112deg)
                val topEdgeDeg = 180f + totalSweep * 0.5f
                val thumbStart = topEdgeDeg - thumbSweep - (totalSweep - thumbSweep) * scrollRatio
                canvas.drawArc(scrollArcRect, thumbStart, thumbSweep, false, scrollThumbPaint)
            }
            EdgeSide.BOTTOM -> {
                val trackStart = -90f - totalSweep * 0.5f
                canvas.drawArc(scrollArcRect, trackStart, totalSweep, false, scrollTrackPaint)
                val thumbStart = trackStart + (totalSweep - thumbSweep) * scrollRatio
                canvas.drawArc(scrollArcRect, thumbStart, thumbSweep, false, scrollThumbPaint)
            }
        }

        // Subtle edge chevrons whenever more slots exist beyond top/bottom (or left/right)
        val hasMoreStart = dialScrollOffset > 0.05f
        val hasMoreEnd = dialScrollOffset < maxDialScroll() - 0.05f
        if (hasMoreStart) {
            drawEdgeScrollChevron(canvas, currentRadius, atStartEdge = true, alphaCurve = alphaCurve)
        }
        if (hasMoreEnd) {
            drawEdgeScrollChevron(canvas, currentRadius, atStartEdge = false, alphaCurve = alphaCurve)
        }
    }

    private fun drawEdgeScrollChevron(
        canvas: Canvas,
        currentRadius: Float,
        atStartEdge: Boolean,
        alphaCurve: Float
    ) {
        val edgeAngleDeg = if (atStartEdge) -(ROTARY_SWEEP_DEG * 0.5f + 10f) else (ROTARY_SWEEP_DEG * 0.5f + 10f)
        val rad = (edgeAngleDeg * PI / 180.0).toFloat()
        val r = currentRadius
        val cx: Float
        val cy: Float
        if (activeSide == EdgeSide.BOTTOM) {
            cx = originX + sin(rad) * r
            cy = originY - cos(rad) * r
        } else {
            val dirSign = if (activeSide == EdgeSide.LEFT) 1f else -1f
            cx = originX + dirSign * cos(rad) * r
            cy = originY + sin(rad) * r
        }
        val sz = 4.2f * density
        chevronPath.rewind()
        if (activeSide == EdgeSide.BOTTOM) {
            val s = if (atStartEdge) -1f else 1f
            chevronPath.moveTo(cx - s * sz * 0.4f, cy - sz)
            chevronPath.lineTo(cx + s * sz * 0.6f, cy)
            chevronPath.lineTo(cx - s * sz * 0.4f, cy + sz)
        } else {
            val s = if (atStartEdge) -1f else 1f
            chevronPath.moveTo(cx - sz, cy - s * sz * 0.4f)
            chevronPath.lineTo(cx, cy + s * sz * 0.6f)
            chevronPath.lineTo(cx + sz, cy - s * sz * 0.4f)
        }
        scrollThumbPaint.color = GlassPalette.argb(palette.accentRgb, 0.72f * alphaCurve)
        canvas.drawPath(chevronPath, scrollThumbPaint)
    }

    private fun drawSystemGlyph(
        canvas: Canvas,
        type: EdgeActionType,
        cx: Float,
        cy: Float,
        radius: Float,
        isFocused: Boolean,
        alphaCurve: Float,
        useLiquid: Boolean,
        bgLum: Float,
        isLight: Boolean
    ) {
        val rgb = if (useLiquid) {
            val effectiveLum = if (bgLum >= 0f) bgLum else if (isLight) 0.72f else 0.25f
            val darkWeight = GlassPalette.smoothstep(0.42f, 0.72f, effectiveLum)
            if (isFocused) {
                GlassPalette.lerpRgb(palette.accentRgb, DARK_ACCENT_RGB, darkWeight)
            } else {
                GlassPalette.lerpRgb(LIGHT_INK_RGB, DARK_INK_RGB, darkWeight)
            }
        } else {
            if (isFocused) palette.iconActiveRgb else palette.iconIdleRgb
        }
        val a = (if (isFocused) 0.98f else 0.90f) * alphaCurve
        glyphPaint.color = GlassPalette.argb(rgb, a)
        ActionGlyphPathBuilder.build(glyphPath, tempRect, type, activeSide, cx, cy, radius)
        canvas.drawPath(glyphPath, glyphPaint)
    }

    private fun drawFloatingWindowBadge(
        canvas: Canvas,
        slotCx: Float,
        slotCy: Float,
        slotRadius: Float,
        isFocused: Boolean,
        alphaCurve: Float
    ) {
        val badgeR = (slotRadius * 0.34f).coerceAtLeast(6.5f * density)
        val bx = slotCx + slotRadius * 0.58f
        val by = slotCy + slotRadius * 0.58f

        microBadgeFillPaint.color = GlassPalette.argb(palette.labelBgRgb, 0.88f * alphaCurve)
        canvas.drawCircle(bx, by, badgeR, microBadgeFillPaint)

        microBadgeRimPaint.color = GlassPalette.argb(
            if (isFocused) palette.accentRgb else palette.rimRgb,
            (if (isFocused) 0.78f else 0.42f) * alphaCurve
        )
        canvas.drawCircle(bx, by, badgeR, microBadgeRimPaint)

        val halfW = badgeR * 0.54f
        val halfH = badgeR * 0.44f
        val corner = badgeR * 0.18f
        floatingBadgeRect.set(bx - halfW, by - halfH, bx + halfW, by + halfH)
        microBadgeGlyphPaint.color = GlassPalette.argb(palette.iconActiveRgb, 0.96f * alphaCurve)
        canvas.drawRoundRect(floatingBadgeRect, corner, corner, microBadgeGlyphPaint)

        floatingInnerRect.set(
            bx - halfW * 0.05f,
            by - halfH * 0.08f,
            bx + halfW * 0.82f,
            by + halfH * 0.78f
        )
        canvas.drawRoundRect(floatingInnerRect, corner * 0.7f, corner * 0.7f, microBadgeGlyphPaint)
    }

    private fun drawShortcutArrowBadge(
        canvas: Canvas,
        slotCx: Float,
        slotCy: Float,
        slotRadius: Float,
        isFocused: Boolean,
        alphaCurve: Float
    ) {
        val badgeR = (slotRadius * 0.32f).coerceAtLeast(6.0f * density)
        val bx = slotCx + slotRadius * 0.58f
        val by = slotCy + slotRadius * 0.58f

        microBadgeFillPaint.color = GlassPalette.argb(palette.labelBgRgb, 0.90f * alphaCurve)
        canvas.drawCircle(bx, by, badgeR, microBadgeFillPaint)

        microBadgeRimPaint.color = GlassPalette.argb(
            if (isFocused) palette.accentRgb else palette.rimRgb,
            (if (isFocused) 0.82f else 0.48f) * alphaCurve
        )
        canvas.drawCircle(bx, by, badgeR, microBadgeRimPaint)

        // Crisp diagonal shortcut launch arrow inside the micro badge (zero allocations)
        val s = badgeR * 0.44f
        glyphPath.rewind()
        glyphPath.moveTo(bx - s * 0.8f, by + s * 0.8f)
        glyphPath.lineTo(bx + s * 0.85f, by - s * 0.85f)
        glyphPath.moveTo(bx + s * 0.05f, by - s * 0.85f)
        glyphPath.lineTo(bx + s * 0.85f, by - s * 0.85f)
        glyphPath.lineTo(bx + s * 0.85f, by - s * 0.05f)
        microBadgeGlyphPaint.color = GlassPalette.argb(
            if (isFocused) palette.accentRgb else palette.iconActiveRgb,
            0.96f * alphaCurve
        )
        canvas.drawPath(glyphPath, microBadgeGlyphPaint)
    }

    private fun formatShortUrlLabel(rawUrl: String): String {
        val cleaned = rawUrl.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
        val slash = cleaned.indexOf('/')
        val host = if (slash > 0) cleaned.substring(0, slash) else cleaned
        val localized = LocalizedContextHelper.forTag(context, appLanguageTag)
        return host.ifEmpty { localized.getString(R.string.action_open_url) }
    }

    private fun resolveSystemActionLabel(type: EdgeActionType): String {
        val resId = when (type) {
            EdgeActionType.BACK -> R.string.action_back
            EdgeActionType.HOME -> R.string.action_home
            EdgeActionType.RECENTS -> R.string.action_recents
            EdgeActionType.PREVIOUS_APP -> R.string.action_previous_app
            EdgeActionType.NOTIFICATIONS -> R.string.action_notifications
            EdgeActionType.QUICK_SETTINGS -> R.string.action_quick_settings
            EdgeActionType.LOCK_SCREEN -> R.string.action_lock_screen
            EdgeActionType.SCREENSHOT -> R.string.action_screenshot
            EdgeActionType.SPLIT_SCREEN -> R.string.action_split_screen
            EdgeActionType.FLOAT_CURRENT_APP -> R.string.action_float_current_app
            EdgeActionType.CIRCLE_TO_SEARCH -> R.string.action_circle_to_search
            EdgeActionType.OPEN_QUICK_MENU -> R.string.action_open_quick_menu
            EdgeActionType.OPEN_RECENT_SWITCHER -> R.string.action_open_recent_switcher
            EdgeActionType.TOGGLE_TORCH -> R.string.action_toggle_torch
            EdgeActionType.TOGGLE_SCREEN_TRANSLATE -> R.string.action_toggle_screen_translate
            EdgeActionType.TRANSLATE_SCREEN_ONCE -> R.string.action_translate_screen_once
            EdgeActionType.LAUNCH_APP -> R.string.action_launch_app
            EdgeActionType.LAUNCH_APP_FLOATING -> R.string.action_launch_app_floating
            EdgeActionType.LAUNCH_SHORTCUT -> R.string.action_launch_shortcut
            EdgeActionType.OPEN_URL -> R.string.action_open_url
            EdgeActionType.NONE -> R.string.action_none
        }
        return LocalizedContextHelper.forTag(context, appLanguageTag).getString(resId)
    }

    private fun safeCoerce(value: Float, minVal: Float, maxVal: Float, fallback: Float): Float {
        return if (minVal <= maxVal) value.coerceIn(minVal, maxVal) else fallback
    }

    companion object {
        private const val MAX_SLOTS = EdgeConfig.MAX_QUICK_MENU_SLOTS
        private const val VISIBLE_WINDOW_SLOTS = 6
        private const val ROTARY_SWEEP_DEG = 136f
        private const val OVERSCROLL_FULL_SPAN_DEG = 26f
        private const val OVERSCROLL_HOLD_CONFIRM_MS = 110L
        private const val MAX_ROTARY_SLOTS_PER_SEC = 6.2f
        private const val SLOT_RADIUS_DP = 25f
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
