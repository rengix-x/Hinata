package com.edgeflow.service.translate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.edgeflow.service.HapticController
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Ultra-lightweight, pure Android [View] draggable & edge-snapping **One-Tap Translate Button**
 * with expandable 5-button mini toolbar (ZERO Jetpack Compose imports, ZERO GC inside [onDraw]).
 *
 * - **Collapsed vertical dock (`46dp × 68dp`)**:
 *   - Upper main circular button (`44dp` diameter):
 *     - Standby (no visible translation): dark glass circle + crisp white "文A" -> 1-tap translates screen.
 *     - Processing: vivid blue rotating spinner arc.
 *     - Translation visible: vivid emerald green ring + clear glyph -> 1-tap clears translation.
 *   - Lower mini tab (`38dp × 18dp`, `⋯` 3 dots): 1-tap expands the 5-button horizontal toolbar.
 *   - Dragging from either part (> [dragSlopPx]) smoothly repositions and snaps to left/right edge.
 *   - Auto-dims to `alpha = 0.55f` after 3 seconds idle.
 * - **Expanded horizontal toolbar (`214dp × 48dp`, 5 buttons)**:
 *   0. Retranslate (force bypass cache)
 *   1. Hold "Peek Original" (temporarily transparent while pressed)
 *   2. Language Swap (`A→JA`, 1-tap swap + retranslate)
 *   3. Engine Toggle (Cloud = Online High Quality `#38BDF8` vs Device = Offline `#CBD5E1`)
 *   4. Close (`✕`, dismisses overlay & button and releases ML Kit -> 0.0% idle)
 */
class TranslateFloatingControlView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val hapticController: HapticController,
    private val onPrimaryTranslateToggle: () -> Unit,
    private val onRetranslate: () -> Unit,
    private val onPeekChanged: (peeking: Boolean) -> Unit,
    private val onQuickSwapLanguages: () -> Unit,
    private val onToggleEngine: () -> Unit,
    private val onClose: () -> Unit
) : View(context) {

    private val density: Float = resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    private val collapsedWidthPx: Int = (COLLAPSED_WIDTH_DP * density).roundToInt()
    private val collapsedHeightPx: Int = (COLLAPSED_HEIGHT_DP * density).roundToInt()
    private val expandedWidthPx: Int = (EXPANDED_WIDTH_DP * density).roundToInt()
    private val expandedHeightPx: Int = (EXPANDED_HEIGHT_DP * density).roundToInt()

    private val mainButtonDiameterPx: Float = MAIN_BUTTON_DIAMETER_DP * density
    private val mainButtonRadiusPx: Float = mainButtonDiameterPx * 0.5f
    private val mainButtonCenterYPx: Float = (MAIN_BUTTON_TOP_MARGIN_DP + MAIN_BUTTON_DIAMETER_DP * 0.5f) * density
    private val miniTabWidthPx: Float = MINI_TAB_WIDTH_DP * density
    private val miniTabHeightPx: Float = MINI_TAB_HEIGHT_DP * density
    private val miniTabTopPx: Float = MINI_TAB_TOP_DP * density

    private val barPaddingPx: Float = BAR_PADDING_DP * density
    private val buttonSlotWidthPx: Float = (expandedWidthPx - barPaddingPx * 2f) / BUTTON_COUNT
    private val edgeMarginPx: Int = (EDGE_MARGIN_DP * density).roundToInt()
    private val dragSlopPx: Float = 8f * density

    // Pre-allocated Paints, Paths & RectFs — ZERO allocations inside onDraw()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_GLASS_BG
    }
    private val miniTabBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_MINI_TAB_BG
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = COLOR_GLASS_RIM
    }
    private val activeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
        color = COLOR_ACTIVE_EMERALD
    }
    private val activeInnerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_ACTIVE_EMERALD_FILL
    }
    private val spinnerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        color = COLOR_SPINNER_TRACK
    }
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f * density
        color = COLOR_SPINNER_BLUE
    }
    private val buttonHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.9f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_MINI_DOTS
    }
    private val mainGlyphHanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13.5f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = COLOR_ICON_WHITE
    }
    private val mainGlyphLatinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = COLOR_ICON_WHITE
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.8f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = COLOR_ICON_WHITE
    }

    private val bgRect = RectF()
    private val tempRect = RectF()
    private val glyphPath = Path()
    private val screenLoc = IntArray(2)
    private val cachedScreenBounds = Rect()

    private var screenWidthPx: Int = resources.displayMetrics.widthPixels
    private var screenHeightPx: Int = resources.displayMetrics.heightPixels

    private var isExpanded: Boolean = false
    private var snappedRight: Boolean = true
    private var isProcessing: Boolean = false
    private var hasVisibleTranslation: Boolean = false
    private var isOnlineEngineActive: Boolean = false
    private var languageBadgeText: String = "A→JA"

    private var pressedCollapsedZone: Int = ZONE_NONE
    private var pressedButtonIndex: Int = -1
    private var isPeekingHoldActive: Boolean = false

    // Drag tracking
    private var touchDownRawX: Float = 0f
    private var touchDownRawY: Float = 0f
    private var touchDownLocalX: Float = 0f
    private var touchDownLocalY: Float = 0f
    private var dragStartWinX: Int = 0
    private var dragStartWinY: Int = 0
    private var isDragging: Boolean = false

    // Spinner & snap animations
    private var spinnerStartMs: Long = 0L
    private var snapAnimating: Boolean = false
    private var snapFromX: Int = 0
    private var snapToX: Int = 0
    private var snapStartMs: Long = 0L

    private val dimRunnable = Runnable {
        if (!isExpanded && !isDragging && !isProcessing) {
            animate().alpha(IDLE_DIM_ALPHA).setDuration(240L).start()
        }
    }

    private val autoCollapseRunnable = Runnable {
        if (isExpanded && !isPeekingHoldActive && !isDragging) {
            setExpanded(false)
        }
    }

    private val spinnerFrameRunnable = object : Runnable {
        override fun run() {
            if (isProcessing && isAttachedToWindow) {
                invalidate()
                postOnAnimation(this)
            }
        }
    }

    private val snapFrameRunnable = object : Runnable {
        override fun run() {
            if (!snapAnimating || !isAttachedToWindow) return
            val elapsed = (SystemClock.uptimeMillis() - snapStartMs).coerceAtLeast(0L)
            val t = (elapsed.toFloat() / SNAP_ANIM_DURATION_MS).coerceIn(0f, 1f)
            val eased = 1f - (1f - t) * (1f - t) * (1f - t)
            layoutParams.x = (snapFromX + (snapToX - snapFromX) * eased).roundToInt()
            safeUpdateWindowLayout()
            if (t < 1f) {
                postOnAnimation(this)
            } else {
                snapAnimating = false
                scheduleIdleDim()
            }
        }
    }

    init {
        isClickable = true
        isFocusable = false
        contentDescription = "Translate control"
        scheduleIdleDim()
    }

    fun updateDisplayBounds(widthPx: Int, heightPx: Int) {
        screenWidthPx = widthPx.coerceAtLeast(320)
        screenHeightPx = heightPx.coerceAtLeast(480)
        val w = if (isExpanded) expandedWidthPx else collapsedWidthPx
        val h = if (isExpanded) expandedHeightPx else collapsedHeightPx
        layoutParams.width = w
        layoutParams.height = h
        layoutParams.x = if (snappedRight) {
            (screenWidthPx - w - edgeMarginPx).coerceAtLeast(edgeMarginPx)
        } else {
            edgeMarginPx
        }
        val minY = (48f * density).roundToInt()
        val maxY = (screenHeightPx - h - (72f * density).roundToInt()).coerceAtLeast(minY)
        layoutParams.y = layoutParams.y.coerceIn(minY, maxY)
        safeUpdateWindowLayout()
        invalidate()
    }

    fun setHasVisibleTranslation(hasTranslation: Boolean) {
        if (hasVisibleTranslation == hasTranslation) return
        hasVisibleTranslation = hasTranslation
        wakeAlpha()
        invalidate()
    }

    fun setProcessing(processing: Boolean) {
        if (isProcessing == processing) return
        isProcessing = processing
        if (processing) {
            spinnerStartMs = SystemClock.uptimeMillis()
            wakeAlpha()
            mainHandler.removeCallbacks(dimRunnable)
            removeCallbacks(spinnerFrameRunnable)
            postOnAnimation(spinnerFrameRunnable)
        } else {
            removeCallbacks(spinnerFrameRunnable)
            scheduleIdleDim()
        }
        invalidate()
    }

    fun setOnlineEngineActive(online: Boolean) {
        if (isOnlineEngineActive == online) return
        isOnlineEngineActive = online
        invalidate()
    }

    fun setLanguageBadge(sourceCode: String, targetCode: String) {
        val src = if (sourceCode.equals("auto", ignoreCase = true)) {
            "A"
        } else {
            sourceCode.uppercase().take(2)
        }
        val tgt = targetCode.uppercase().take(2)
        languageBadgeText = "$src→$tgt"
        invalidate()
    }

    fun getCurrentScreenBounds(): Rect {
        getLocationOnScreen(screenLoc)
        val left = if (screenLoc[0] > 0 || layoutParams.x == 0) screenLoc[0] else layoutParams.x
        val top = if (screenLoc[1] > 0 || layoutParams.y == 0) screenLoc[1] else layoutParams.y
        val w = if (width > 0) width else layoutParams.width
        val h = if (height > 0) height else layoutParams.height
        cachedScreenBounds.set(left, top, left + w, top + h)
        return cachedScreenBounds
    }

    fun cleanup() {
        mainHandler.removeCallbacks(dimRunnable)
        mainHandler.removeCallbacks(autoCollapseRunnable)
        removeCallbacks(spinnerFrameRunnable)
        removeCallbacks(snapFrameRunnable)
        snapAnimating = false
        if (isPeekingHoldActive) {
            isPeekingHoldActive = false
            onPeekChanged(false)
        }
    }

    private fun wakeAlpha() {
        mainHandler.removeCallbacks(dimRunnable)
        animate().cancel()
        alpha = 1.0f
    }

    private fun scheduleIdleDim() {
        mainHandler.removeCallbacks(dimRunnable)
        if (!isExpanded && !isProcessing) {
            mainHandler.postDelayed(dimRunnable, IDLE_DIM_DELAY_MS)
        }
    }

    private fun resetAutoCollapseTimer() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        if (isExpanded) {
            mainHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_DELAY_MS)
        }
    }

    private fun setExpanded(expand: Boolean) {
        if (isExpanded == expand) return
        isExpanded = expand
        pressedButtonIndex = -1
        pressedCollapsedZone = ZONE_NONE
        mainHandler.removeCallbacks(autoCollapseRunnable)
        if (isPeekingHoldActive) {
            isPeekingHoldActive = false
            onPeekChanged(false)
        }

        val targetW = if (expand) expandedWidthPx else collapsedWidthPx
        val targetH = if (expand) expandedHeightPx else collapsedHeightPx
        val minY = (48f * density).roundToInt()
        val maxY = (screenHeightPx - targetH - (72f * density).roundToInt()).coerceAtLeast(minY)

        // Keep vertical center aligned smoothly between collapsed dock and expanded bar
        val currentCenterY = layoutParams.y + layoutParams.height / 2
        layoutParams.width = targetW
        layoutParams.height = targetH
        layoutParams.y = (currentCenterY - targetH / 2).coerceIn(minY, maxY)
        layoutParams.x = if (snappedRight) {
            (screenWidthPx - targetW - edgeMarginPx).coerceAtLeast(edgeMarginPx)
        } else {
            edgeMarginPx
        }
        safeUpdateWindowLayout()
        wakeAlpha()
        if (expand) {
            resetAutoCollapseTimer()
        } else {
            scheduleIdleDim()
        }
        invalidate()
    }

    private fun safeUpdateWindowLayout() {
        if (!isAttachedToWindow) return
        runCatching { windowManager.updateViewLayout(this, layoutParams) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wakeAlpha()
                snapAnimating = false
                removeCallbacks(snapFrameRunnable)
                touchDownRawX = event.rawX
                touchDownRawY = event.rawY
                touchDownLocalX = event.x
                touchDownLocalY = event.y
                dragStartWinX = layoutParams.x
                dragStartWinY = layoutParams.y
                isDragging = false

                if (isExpanded) {
                    resetAutoCollapseTimer()
                    val idx = hitTestExpandedButton(event.x)
                    pressedButtonIndex = idx
                    if (idx == BTN_PEEK_ORIGINAL) {
                        isPeekingHoldActive = true
                        hapticController.performPrimaryTrigger()
                        onPeekChanged(true)
                    }
                } else {
                    pressedCollapsedZone = hitTestCollapsedZone(event.y)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchDownRawX
                val dy = event.rawY - touchDownRawY
                if (!isDragging && !isPeekingHoldActive && hypot(dx.toDouble(), dy.toDouble()) > dragSlopPx) {
                    isDragging = true
                    pressedButtonIndex = -1
                    pressedCollapsedZone = ZONE_NONE
                    if (isExpanded) {
                        // Collapse cleanly when user initiates drag from expanded bar
                        setExpanded(false)
                        dragStartWinX = layoutParams.x
                        dragStartWinY = layoutParams.y
                        touchDownRawX = event.rawX
                        touchDownRawY = event.rawY
                    }
                    invalidate()
                }
                if (isDragging) {
                    val maxX = (screenWidthPx - layoutParams.width).coerceAtLeast(0)
                    val minY = (36f * density).roundToInt()
                    val maxY = (screenHeightPx - layoutParams.height - (56f * density).roundToInt())
                        .coerceAtLeast(minY)
                    layoutParams.x = (dragStartWinX + (event.rawX - touchDownRawX)).roundToInt()
                        .coerceIn(0, maxX)
                    layoutParams.y = (dragStartWinY + (event.rawY - touchDownRawY)).roundToInt()
                        .coerceIn(minY, maxY)
                    safeUpdateWindowLayout()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isPeekingHoldActive) {
                    isPeekingHoldActive = false
                    pressedButtonIndex = -1
                    onPeekChanged(false)
                    resetAutoCollapseTimer()
                    invalidate()
                    return true
                }

                if (isDragging) {
                    isDragging = false
                    val centerX = layoutParams.x + layoutParams.width / 2
                    snappedRight = centerX >= screenWidthPx / 2
                    snapFromX = layoutParams.x
                    snapToX = if (snappedRight) {
                        (screenWidthPx - layoutParams.width - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                    } else {
                        edgeMarginPx
                    }
                    snapStartMs = SystemClock.uptimeMillis()
                    snapAnimating = true
                    postOnAnimation(snapFrameRunnable)
                    invalidate()
                    return true
                }

                if (!isExpanded) {
                    val releaseZone = hitTestCollapsedZone(event.y)
                    val zone = if (releaseZone != ZONE_NONE) releaseZone else pressedCollapsedZone
                    pressedCollapsedZone = ZONE_NONE
                    hapticController.performHoldTrigger()
                    if (zone == ZONE_MINI_TAB) {
                        setExpanded(true)
                    } else {
                        onPrimaryTranslateToggle()
                        scheduleIdleDim()
                    }
                    invalidate()
                } else {
                    val idx = hitTestExpandedButton(event.x)
                    pressedButtonIndex = -1
                    if (idx >= 0) {
                        handleExpandedButtonTap(idx)
                    } else {
                        setExpanded(false)
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                pressedButtonIndex = -1
                pressedCollapsedZone = ZONE_NONE
                if (isPeekingHoldActive) {
                    isPeekingHoldActive = false
                    onPeekChanged(false)
                }
                scheduleIdleDim()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTestCollapsedZone(localY: Float): Int {
        // Boundary right below main circle (45.5dp) separates main button from lower 3-dot mini tab
        val splitY = (MAIN_BUTTON_TOP_MARGIN_DP + MAIN_BUTTON_DIAMETER_DP + 1.5f) * density
        return if (localY >= splitY) ZONE_MINI_TAB else ZONE_MAIN_BUTTON
    }

    private fun hitTestExpandedButton(localX: Float): Int {
        val rel = localX - barPaddingPx
        if (rel < 0f) return -1
        val idx = (rel / buttonSlotWidthPx).toInt()
        return if (idx in 0 until BUTTON_COUNT) idx else -1
    }

    private fun handleExpandedButtonTap(index: Int) {
        when (index) {
            BTN_RETRANSLATE -> {
                hapticController.performHoldTrigger()
                setExpanded(false)
                onRetranslate()
            }
            BTN_PEEK_ORIGINAL -> {
                // Handled on press-and-hold; single tap just resets collapse timer
                resetAutoCollapseTimer()
            }
            BTN_SWAP_LANG -> {
                hapticController.performPrimaryTrigger()
                setExpanded(false)
                onQuickSwapLanguages()
            }
            BTN_TOGGLE_ENGINE -> {
                hapticController.performHoldTrigger()
                setExpanded(false)
                onToggleEngine()
            }
            BTN_CLOSE -> {
                hapticController.performHoldTrigger()
                onClose()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (!isExpanded) {
            drawCollapsedDock(canvas, w)
        } else {
            drawExpandedToolbar(canvas, w, h)
        }
    }

    private fun drawCollapsedDock(canvas: Canvas, viewW: Float) {
        val cx = viewW * 0.5f
        val mainCy = mainButtonCenterYPx
        val mainR = mainButtonRadiusPx - 1.2f * density

        // 1. Upper main circular button (44dp diameter)
        bgPaint.color = if (pressedCollapsedZone == ZONE_MAIN_BUTTON) COLOR_GLASS_BG_PRESSED else COLOR_GLASS_BG
        canvas.drawCircle(cx, mainCy, mainR, bgPaint)

        when {
            isProcessing -> {
                // Subtle track + rotating vivid blue spinner arc
                val arcR = mainR - 1.6f * density
                canvas.drawCircle(cx, mainCy, arcR, spinnerTrackPaint)
                val elapsed = (SystemClock.uptimeMillis() - spinnerStartMs) % 900L
                val startAngle = (elapsed.toFloat() / 900f) * 360f - 90f
                tempRect.set(cx - arcR, mainCy - arcR, cx + arcR, mainCy + arcR)
                canvas.drawArc(tempRect, startAngle, 230f, false, spinnerPaint)
                drawWenAGlyph(canvas, cx, mainCy, COLOR_SPINNER_BLUE)
            }
            hasVisibleTranslation -> {
                // Active translation visible: emerald green ring + clear (✕) glyph
                canvas.drawCircle(cx, mainCy, mainR - 1.3f * density, activeInnerFillPaint)
                canvas.drawCircle(cx, mainCy, mainR - 1.3f * density, activeRingPaint)
                drawClearCrossGlyph(canvas, cx, mainCy, mainR * 0.36f, COLOR_ICON_WHITE)
            }
            else -> {
                // Idle standby: dark glass rim + crisp white "文A"
                rimPaint.color = COLOR_GLASS_RIM
                canvas.drawCircle(cx, mainCy, mainR - 0.6f * density, rimPaint)
                drawWenAGlyph(canvas, cx, mainCy, COLOR_ICON_WHITE)
            }
        }

        // 2. Lower mini tab (38dp × 18dp, 3 dots ⋯)
        val tabLeft = (viewW - miniTabWidthPx) * 0.5f
        val tabTop = miniTabTopPx
        val tabRight = tabLeft + miniTabWidthPx
        val tabBottom = tabTop + miniTabHeightPx
        val tabRadius = miniTabHeightPx * 0.5f
        bgRect.set(tabLeft, tabTop, tabRight, tabBottom)

        miniTabBgPaint.color = if (pressedCollapsedZone == ZONE_MINI_TAB) {
            COLOR_GLASS_BG_PRESSED
        } else {
            COLOR_MINI_TAB_BG
        }
        canvas.drawRoundRect(bgRect, tabRadius, tabRadius, miniTabBgPaint)
        rimPaint.color = COLOR_MINI_TAB_RIM
        canvas.drawRoundRect(bgRect, tabRadius, tabRadius, rimPaint)

        // 3 horizontal dots (⋯)
        val dotsCy = (tabTop + tabBottom) * 0.5f
        val dotR = 1.65f * density
        val dotSpacing = 6.2f * density
        dotPaint.color = COLOR_MINI_DOTS
        canvas.drawCircle(cx - dotSpacing, dotsCy, dotR, dotPaint)
        canvas.drawCircle(cx, dotsCy, dotR, dotPaint)
        canvas.drawCircle(cx + dotSpacing, dotsCy, dotR, dotPaint)
    }

    private fun drawExpandedToolbar(canvas: Canvas, w: Float, h: Float) {
        val inset = 1.2f * density
        bgRect.set(inset, inset, w - inset, h - inset)
        val corner = h * 0.5f

        bgPaint.color = COLOR_GLASS_BG
        canvas.drawRoundRect(bgRect, corner, corner, bgPaint)
        rimPaint.color = COLOR_GLASS_RIM
        canvas.drawRoundRect(bgRect, corner, corner, rimPaint)

        val cy = h * 0.5f
        val hlRadius = (buttonSlotWidthPx * 0.42f).coerceAtMost(h * 0.40f)
        for (i in 0 until BUTTON_COUNT) {
            val cx = barPaddingPx + buttonSlotWidthPx * (i + 0.5f)
            val isPressed = pressedButtonIndex == i
            val isActiveHighlight = (i == BTN_PEEK_ORIGINAL && isPeekingHoldActive) ||
                (i == BTN_TOGGLE_ENGINE && isOnlineEngineActive)
            if (isPressed || isActiveHighlight) {
                buttonHighlightPaint.color = when {
                    i == BTN_CLOSE && isPressed -> COLOR_CLOSE_HIGHLIGHT
                    isActiveHighlight -> COLOR_ACTIVE_BLUE_HIGHLIGHT
                    else -> COLOR_PRESS_HIGHLIGHT
                }
                canvas.drawCircle(cx, cy, hlRadius, buttonHighlightPaint)
            }

            when (i) {
                BTN_RETRANSLATE -> drawRefreshIcon(canvas, cx, cy)
                BTN_PEEK_ORIGINAL -> drawEyeIcon(canvas, cx, cy, isPeekingHoldActive)
                BTN_SWAP_LANG -> drawLanguageBadge(canvas, cx, cy)
                BTN_TOGGLE_ENGINE -> drawEngineIcon(canvas, cx, cy, isOnlineEngineActive)
                BTN_CLOSE -> drawClearCrossGlyph(canvas, cx, cy, 5.8f * density, COLOR_CLOSE_ICON)
            }
        }
    }

    private fun drawWenAGlyph(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        mainGlyphHanPaint.color = color
        mainGlyphLatinPaint.color = color
        // Crisp compact "文A" arranged cleanly inside the 44dp circle (zero allocations)
        canvas.drawText("文", cx - 5.2f * density, cy + 3.2f * density, mainGlyphHanPaint)
        canvas.drawText("A", cx + 6.4f * density, cy + 5.8f * density, mainGlyphLatinPaint)
    }

    private fun drawClearCrossGlyph(canvas: Canvas, cx: Float, cy: Float, half: Float, color: Int) {
        glyphPaint.color = color
        glyphPaint.strokeWidth = 2.2f * density
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, glyphPaint)
        canvas.drawLine(cx + half, cy - half, cx - half, cy + half, glyphPaint)
    }

    private fun drawRefreshIcon(canvas: Canvas, cx: Float, cy: Float) {
        val r = 6.8f * density
        glyphPaint.color = COLOR_ICON_WHITE
        glyphPaint.strokeWidth = 1.9f * density
        tempRect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(tempRect, -45f, 290f, false, glyphPaint)
        // Arrowhead at top-right
        val ax = cx + r * 0.72f
        val ay = cy - r * 0.68f
        glyphPath.reset()
        glyphPath.moveTo(ax - 3.6f * density, ay - 0.6f * density)
        glyphPath.lineTo(ax + 0.6f * density, ay + 0.8f * density)
        glyphPath.lineTo(ax - 0.8f * density, ay - 3.6f * density)
        canvas.drawPath(glyphPath, glyphPaint)
    }

    private fun drawEyeIcon(canvas: Canvas, cx: Float, cy: Float, active: Boolean) {
        val rx = 8.4f * density
        val ry = 4.9f * density
        glyphPaint.color = if (active) COLOR_SPINNER_BLUE else COLOR_ICON_WHITE
        glyphPaint.strokeWidth = 1.85f * density
        glyphPath.reset()
        glyphPath.moveTo(cx - rx, cy)
        glyphPath.quadTo(cx, cy - ry * 1.8f, cx + rx, cy)
        glyphPath.quadTo(cx, cy + ry * 1.8f, cx - rx, cy)
        canvas.drawPath(glyphPath, glyphPaint)
        dotPaint.color = glyphPaint.color
        canvas.drawCircle(cx, cy, 2.3f * density, dotPaint)
    }

    private fun drawLanguageBadge(canvas: Canvas, cx: Float, cy: Float) {
        badgeTextPaint.color = COLOR_ICON_WHITE
        val baseline = cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) * 0.5f
        canvas.drawText(languageBadgeText, cx, baseline, badgeTextPaint)
    }

    private fun drawEngineIcon(canvas: Canvas, cx: Float, cy: Float, online: Boolean) {
        glyphPaint.strokeWidth = 1.85f * density
        if (online) {
            // Cloud icon — Online High-Quality Engine (#38BDF8)
            glyphPaint.color = COLOR_SPINNER_BLUE
            val s = density
            glyphPath.reset()
            glyphPath.moveTo(cx - 5.2f * s, cy + 4.4f * s)
            glyphPath.lineTo(cx + 5.6f * s, cy + 4.4f * s)
            glyphPath.cubicTo(
                cx + 8.6f * s, cy + 4.4f * s,
                cx + 9.0f * s, cy - 0.4f * s,
                cx + 5.8f * s, cy - 0.8f * s
            )
            glyphPath.cubicTo(
                cx + 5.4f * s, cy - 5.4f * s,
                cx - 1.2f * s, cy - 6.0f * s,
                cx - 2.6f * s, cy - 1.8f * s
            )
            glyphPath.cubicTo(
                cx - 6.8f * s, cy - 2.0f * s,
                cx - 8.4f * s, cy + 4.4f * s,
                cx - 5.2f * s, cy + 4.4f * s
            )
            canvas.drawPath(glyphPath, glyphPaint)
        } else {
            // Device / smartphone icon — Offline On-Device ML Kit (#CBD5E1)
            glyphPaint.color = COLOR_OFFLINE_ICON
            val halfW = 4.6f * density
            val halfH = 7.4f * density
            tempRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
            canvas.drawRoundRect(tempRect, 2.0f * density, 2.0f * density, glyphPaint)
            dotPaint.color = COLOR_OFFLINE_ICON
            canvas.drawCircle(cx, cy + halfH - 2.3f * density, 1.0f * density, dotPaint)
        }
    }

    companion object {
        const val COLLAPSED_WIDTH_DP = 46f
        const val COLLAPSED_HEIGHT_DP = 68f
        const val EXPANDED_WIDTH_DP = 214f
        const val EXPANDED_HEIGHT_DP = 48f

        private const val MAIN_BUTTON_DIAMETER_DP = 44f
        private const val MAIN_BUTTON_TOP_MARGIN_DP = 1f
        private const val MINI_TAB_WIDTH_DP = 38f
        private const val MINI_TAB_HEIGHT_DP = 18f
        private const val MINI_TAB_TOP_DP = 49f

        private const val BAR_PADDING_DP = 7f
        private const val EDGE_MARGIN_DP = 8f
        private const val BUTTON_COUNT = 5

        private const val ZONE_NONE = -1
        private const val ZONE_MAIN_BUTTON = 0
        private const val ZONE_MINI_TAB = 1

        private const val BTN_RETRANSLATE = 0
        private const val BTN_PEEK_ORIGINAL = 1
        private const val BTN_SWAP_LANG = 2
        private const val BTN_TOGGLE_ENGINE = 3
        private const val BTN_CLOSE = 4

        private const val IDLE_DIM_DELAY_MS = 3000L
        private const val IDLE_DIM_ALPHA = 0.55f
        private const val AUTO_COLLAPSE_DELAY_MS = 6500L
        private const val SNAP_ANIM_DURATION_MS = 210L

        private const val COLOR_GLASS_BG = 0xE8161C28.toInt()
        private const val COLOR_GLASS_BG_PRESSED = 0xF2263248.toInt()
        private const val COLOR_MINI_TAB_BG = 0xDC141925.toInt()
        private const val COLOR_GLASS_RIM = 0x55FFFFFF
        private const val COLOR_MINI_TAB_RIM = 0x42FFFFFF
        private const val COLOR_MINI_DOTS = 0xDDF1F5F9.toInt()
        private const val COLOR_ICON_WHITE = 0xFFF8FAFC.toInt()
        private const val COLOR_OFFLINE_ICON = 0xFFCBD5E1.toInt()
        private const val COLOR_ACTIVE_EMERALD = 0xFF22C55E.toInt()
        private const val COLOR_ACTIVE_EMERALD_FILL = 0x2622C55E
        private const val COLOR_SPINNER_BLUE = 0xFF38BDF8.toInt()
        private const val COLOR_SPINNER_TRACK = 0x3338BDF8
        private const val COLOR_CLOSE_ICON = 0xFFF87171.toInt()
        private const val COLOR_PRESS_HIGHLIGHT = 0x2EFFFFFF
        private const val COLOR_ACTIVE_BLUE_HIGHLIGHT = 0x3338BDF8
        private const val COLOR_CLOSE_HIGHLIGHT = 0x38EF4444
    }
}
