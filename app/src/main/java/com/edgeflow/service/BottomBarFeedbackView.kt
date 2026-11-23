package com.edgeflow.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.edgeflow.data.EdgeAction
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.EdgeSide
import com.edgeflow.data.FeedbackStyle
import com.edgeflow.data.VisualTheme
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sign

/**
 * Zero-GC, 120fps native-style bottom gesture bar feedback overlay (abolishing the droplet on
 * the bottom edge in favor of Pixel / iOS / HyperOS restrained pill physics):
 *
 * - **Swipe-up motion**: Exponential rubber-band vertical lift strictly clamped to `20dp`
 *   (`16dp..22dp` range), subtle horizontal follow clamped to `±10dp`, gentle width stretch
 *   (`1.0x -> 1.12x`), crisp 3-stage spring micro-bounce, and a discreet floating stage pill
 *   badge `10dp` above the bar showing the action glyph + 2 or 3 stage dots.
 * - **Stationary long-press (Circle to Search etc.)**: Smooth horizontal expansion (`1.0x -> 1.15x`)
 *   with a center-out luminous progress shimmer driven at display refresh rate via [Choreographer].
 * - **Seamless idle hand-off**: Notifies [onIdleStateChanged] so the static bottom idle pill hides
 *   while this animated view is active, preventing any double-pill artifact.
 */
class BottomBarFeedbackView(
    context: Context,
    private val backdropCapturer: LiquidBackdropCapturer? = null,
    private val onIdleStateChanged: (isIdle: Boolean) -> Unit
) : View(context) {

    private val density: Float = resources.displayMetrics.density
    private val palette = GlassPalette()
    private val liquidShader: LiquidGlassShader? = LiquidGlassShader.createIfSupported()

    // Pre-allocated drawing primitives — ZERO allocations inside onDraw()
    private val pillRect = RectF()
    private val shimmerRect = RectF()
    private val badgeRect = RectF()
    private val shaderBounds = RectF()
    private val iconPath = Path()
    private val iconRect = RectF()
    private val windowScreenOffset = IntArray(2)

    private var liquidGlassEnabled: Boolean = true
    private var liquidGlassRefraction: Float = EdgeConfig.DEFAULT_LIQUID_GLASS_REFRACTION
    private var backdropFadeStartMs: Long = 0L
    private var lastBackdropGeneration: Long = -1L
    private var activeSessionHasBackdrop: Boolean = false
    private val backdropReadyListener: () -> Unit = {
        if (!activeSessionHasBackdrop) {
            backdropFadeStartMs = SystemClock.uptimeMillis()
            activeSessionHasBackdrop = true
        }
        if (visibility == VISIBLE) {
            invalidate()
        }
    }

    private val pillFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pillRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val badgeRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }
    private val badgeIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.85f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Config parameters
    private var feedbackStyle: FeedbackStyle = FeedbackStyle.BEZIER_BUBBLE
    private var visualTheme: VisualTheme = VisualTheme.AUTO
    private var feedbackAlpha: Float = EdgeConfig.DEFAULT_FEEDBACK_ALPHA
    private var bottomPillAlpha: Float = EdgeConfig.DEFAULT_BOTTOM_PILL_ALPHA
    private var bottomBarWidthPercent: Float = EdgeConfig.DEFAULT_BOTTOM_WIDTH_PERCENT
    private var bottomBarBottomOffsetDp: Float = EdgeConfig.DEFAULT_BOTTOM_OFFSET_DP
    private var bottomBarHorizontalOffsetPercent: Float = EdgeConfig.DEFAULT_BOTTOM_HORIZONTAL_OFFSET_PERCENT

    // Gesture motion state (primitives only)
    private var liftPx: Float = 0f
    private var horizontalOffsetPx: Float = 0f
    private var pullIntensity: Float = 0f       // 0..1 continuous swipe-up progress
    private var stageBoost: Float = 0f          // 0=Stage0/1, 1=Stage2, 2=Stage3
    private var activeStageLevel: Int = 0
    private var hasStage3Configured: Boolean = false
    private var isConfirmArmed: Boolean = false
    private var activeAction: EdgeAction = EdgeAction.NONE
    private var isSwipeActive: Boolean = false
    private var smoothedBgLum: Float = -1f
    private var lastLumUpdateUptimeMs: Long = 0L

    // Stationary long-press shimmer state (0..1)
    private var longPressActive: Boolean = false
    private var longPressStartMs: Long = 0L
    private var longPressDurationMs: Long = 400L
    private var longPressProgress: Float = 0f

    // Pre-allocated Choreographer callback for 120Hz zero-GC long-press shimmer
    private val longPressFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!longPressActive) return
            val elapsed = (SystemClock.uptimeMillis() - longPressStartMs).coerceAtLeast(0L)
            val duration = longPressDurationMs.coerceAtLeast(1L)
            longPressProgress = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            invalidate()
            if (longPressProgress < 1f) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    // Pre-allocated release retraction springs
    private val liftHolder = FloatValueHolder(0f)
    private val liftRetractSpring = SpringAnimation(liftHolder).apply {
        spring = SpringForce(0f).apply {
            stiffness = SpringForce.STIFFNESS_MEDIUM
            dampingRatio = 0.78f
        }
        minimumVisibleChange = 0.05f
        addUpdateListener { _, value, _ ->
            liftPx = value.coerceAtLeast(0f)
            pullIntensity = (liftPx / maxLiftPx()).coerceIn(0f, 1f)
            horizontalOffsetPx *= 0.84f
            if (liftPx <= 0.35f && !longPressActive) {
                finishIdleState()
            } else {
                invalidate()
            }
        }
        addEndListener { _, canceled, _, _ ->
            if (!canceled && !longPressActive && liftPx <= 0.5f) {
                finishIdleState()
            }
        }
    }

    private val stageHolder = FloatValueHolder(0f)
    private val stageSpring = SpringAnimation(stageHolder).apply {
        spring = SpringForce(0f).apply {
            stiffness = SpringForce.STIFFNESS_MEDIUM
            dampingRatio = 0.54f
        }
        minimumVisibleChange = 0.002f
        addUpdateListener { _, value, _ ->
            stageBoost = value.coerceIn(0f, 2.35f)
            if (visibility == VISIBLE) invalidate()
        }
    }

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
        palette.resolve(context, visualTheme)
        backdropCapturer?.addBackdropReadyListener(backdropReadyListener)
    }

    fun release() {
        hideImmediately()
        backdropCapturer?.removeBackdropReadyListener(backdropReadyListener)
    }

    fun applyConfig(config: EdgeConfig) {
        feedbackStyle = config.feedbackStyle
        visualTheme = config.visualTheme
        feedbackAlpha = config.feedbackAlpha
        bottomPillAlpha = config.bottomPillAlpha
        bottomBarWidthPercent = config.bottomBarWidthPercent
        bottomBarBottomOffsetDp = config.bottomBarBottomOffsetDp
        bottomBarHorizontalOffsetPercent = config.bottomBarHorizontalOffsetPercent
        liquidGlassEnabled = config.liquidGlassEnabled
        liquidGlassRefraction = config.liquidGlassRefraction
        palette.resolve(context, visualTheme)
        if (feedbackStyle == FeedbackStyle.HIDDEN) {
            hideImmediately()
        }
    }

    fun refreshTheme() {
        palette.resolve(context, visualTheme)
    }

    fun startLongPressProgress(durationMs: Long) {
        if (feedbackStyle == FeedbackStyle.HIDDEN) return
        if (liftRetractSpring.isRunning) liftRetractSpring.cancel()
        longPressActive = true
        longPressStartMs = SystemClock.uptimeMillis()
        longPressDurationMs = durationMs.coerceAtLeast(150L)
        longPressProgress = 0.01f
        ensureVisible()
        Choreographer.getInstance().removeFrameCallback(longPressFrameCallback)
        Choreographer.getInstance().postFrameCallback(longPressFrameCallback)
        invalidate()
    }

    fun cancelLongPressProgress() {
        if (!longPressActive) return
        longPressActive = false
        longPressProgress = 0f
        Choreographer.getInstance().removeFrameCallback(longPressFrameCallback)
        if (!isSwipeActive && liftPx <= 0.35f) {
            finishIdleState()
        } else {
            invalidate()
        }
    }

    fun onGestureUpdate(update: GestureUpdate) {
        if (feedbackStyle == FeedbackStyle.HIDDEN) {
            if (visibility != GONE) hideImmediately()
            return
        }

        val upwardDy = (-update.dragDeltaY).coerceAtLeast(0f)
        val rawDx = update.dragDeltaX

        if (upwardDy <= 1f && !longPressActive) {
            if (!isSwipeActive && visibility != GONE) {
                finishIdleState()
            }
            return
        }

        if (upwardDy > 6f * density && longPressActive) {
            longPressActive = false
            longPressProgress = 0f
            Choreographer.getInstance().removeFrameCallback(longPressFrameCallback)
        }

        if (liftRetractSpring.isRunning) {
            liftRetractSpring.cancel()
        }

        isSwipeActive = upwardDy > 2f
        val maxLift = maxLiftPx()
        val damping = LIFT_DAMPING_DP * density
        // Restrained rubber-band vertical lift: strictly clamped to 20dp (16..22dp range)
        liftPx = maxLift * (1f - exp((-upwardDy / damping).toDouble()).toFloat())
        pullIntensity = (liftPx / maxLift).coerceIn(0f, 1f)

        // Subtle rubber-band horizontal follow clamped to ±10dp
        val maxHoriz = MAX_HORIZ_FOLLOW_DP * density
        val absDx = abs(rawDx)
        val horizDamping = HORIZ_DAMPING_DP * density
        horizontalOffsetPx = sign(rawDx) * maxHoriz * (1f - exp((-absDx / horizDamping).toDouble()).toFloat())

        activeAction = update.activeAction
        hasStage3Configured = update.hasStage3Configured
        isConfirmArmed = update.isDoubleSwipeConfirmPending

        val newStage = update.stageLevel.coerceIn(0, 3)
        if (newStage != activeStageLevel) {
            val targetBoost = when (newStage) {
                3 -> 2f
                2 -> 1f
                else -> 0f
            }
            stageSpring.cancel()
            stageHolder.value = stageBoost
            stageSpring.spring.finalPosition = targetBoost
            stageSpring.setStartValue(stageBoost)
            stageSpring.start()
            activeStageLevel = newStage
        }

        ensureVisible()
        invalidate()
    }

    fun onGestureRelease() {
        isSwipeActive = false
        cancelLongPressProgress()

        if (feedbackStyle == FeedbackStyle.HIDDEN || liftPx <= 0.35f) {
            hideImmediately()
            return
        }

        stageSpring.cancel()
        stageHolder.value = stageBoost
        stageSpring.spring.finalPosition = 0f
        stageSpring.setStartValue(stageBoost)
        stageSpring.start()

        liftRetractSpring.cancel()
        liftHolder.value = liftPx
        liftRetractSpring.spring.finalPosition = 0f
        liftRetractSpring.setStartValue(liftPx)
        liftRetractSpring.start()
    }

    fun hideImmediately() {
        isSwipeActive = false
        longPressActive = false
        longPressProgress = 0f
        Choreographer.getInstance().removeFrameCallback(longPressFrameCallback)
        liftRetractSpring.cancel()
        stageSpring.cancel()
        finishIdleState()
    }

    private fun ensureVisible() {
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            onIdleStateChanged(false)
        }
    }

    private fun finishIdleState() {
        val wasVisible = visibility == VISIBLE
        stopAnimationsAndResetInternal()
        if (wasVisible) {
            onIdleStateChanged(true)
        }
    }

    private fun stopAnimationsAndResetInternal() {
        isSwipeActive = false
        liftPx = 0f
        horizontalOffsetPx = 0f
        pullIntensity = 0f
        stageBoost = 0f
        activeStageLevel = 0
        longPressActive = false
        longPressProgress = 0f
        activeAction = EdgeAction.NONE
        activeSessionHasBackdrop = false
        smoothedBgLum = -1f
        lastLumUpdateUptimeMs = 0L
        Choreographer.getInstance().removeFrameCallback(longPressFrameCallback)
        liftRetractSpring.cancel()
        stageSpring.cancel()
        if (visibility != GONE) {
            visibility = GONE
        }
    }

    override fun onDetachedFromWindow() {
        // Pure internal cleanup — NEVER fire onIdleStateChanged during detach
        stopAnimationsAndResetInternal()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (feedbackStyle == FeedbackStyle.HIDDEN) return
        if (liftPx <= 0.25f && !longPressActive) return

        val screenW = width.toFloat()
        val screenH = height.toFloat()
        if (screenW <= 0f || screenH <= 0f) return

        // Base pill dimensions & offsets matching EdgeTriggerView(BOTTOM) to the exact pixel
        val triggerW = (screenW * bottomBarWidthPercent).coerceIn(56f * density, screenW)
        val basePillW = (triggerW * 0.72f).coerceAtLeast(36f * density)
        val basePillH = 4.5f * density
        val baseBottomOffsetPx = bottomBarBottomOffsetDp * density
        val idleBottomY = screenH - baseBottomOffsetPx - 4.5f * density
        val baseCenterX = (screenW * (0.5f + bottomBarHorizontalOffsetPercent))
            .coerceIn(triggerW * 0.5f, screenW - triggerW * 0.5f)

        // Subtle width stretch during swipe-up (1.0x -> 1.12x) or long-press (1.0x -> 1.15x)
        val stageBounceExtra = (stageBoost.coerceIn(0f, 2.2f)) * 0.03f
        val swipeStretch = 1f + 0.08f * pullIntensity + stageBounceExtra
        val longPressStretch = if (longPressActive) 1f + 0.15f * easeOutQuad(longPressProgress) else 1f
        val finalWidthScale = if (longPressActive && !isSwipeActive) longPressStretch else swipeStretch

        val pillW = basePillW * finalWidthScale
        val pillH = basePillH * (1f + 0.22f * pullIntensity + (if (longPressActive) 0.28f * longPressProgress else 0f))

        val pillCx = (baseCenterX + horizontalOffsetPx)
            .coerceIn(pillW * 0.5f + 8f * density, screenW - pillW * 0.5f - 8f * density)
        val pillBottomY = idleBottomY - liftPx
        val pillTopY = pillBottomY - pillH
        val pillRadius = pillH * 0.5f

        pillRect.set(
            pillCx - pillW * 0.5f,
            pillTopY,
            pillCx + pillW * 0.5f,
            pillBottomY
        )

        // Dark neutral charcoal bottom pill with crisp light rim for high contrast on both light & dark UIs
        val baseAlpha = (bottomPillAlpha.coerceAtLeast(0.58f) + 0.30f * pullIntensity + 0.18f * longPressProgress)
            .coerceIn(0.45f, 1.0f)
        val pillRgb = when {
            activeStageLevel >= 3 -> NEUTRAL_BAR_STAGE3_RGB
            activeStageLevel == 2 || longPressActive -> NEUTRAL_BAR_ACTIVE_RGB
            activeStageLevel == 1 -> NEUTRAL_BAR_LIFT_RGB
            else -> NEUTRAL_BAR_IDLE_RGB
        }
        pillFillPaint.color = GlassPalette.argb(pillRgb, baseAlpha)
        pillRimPaint.color = GlassPalette.argb(
            NEUTRAL_BAR_RIM_RGB,
            (baseAlpha * (if (activeStageLevel >= 2 || longPressActive) 0.55f else 0.45f)).coerceAtMost(0.55f)
        )

        // Subtle neutral luminous halo behind pill on Stage 2 / Stage 3 or long-press
        if (stageBoost > 0.05f || longPressActive) {
            val glowStrength = if (longPressActive) {
                0.28f * easeOutQuad(longPressProgress)
            } else {
                (0.14f * min(stageBoost, 2f)).coerceAtMost(0.28f)
            }
            glowPaint.color = GlassPalette.argb(NEUTRAL_BAR_RIM_RGB, glowStrength)
            val padX = 4.5f * density
            val padY = 3.0f * density
            shimmerRect.set(
                pillRect.left - padX,
                pillRect.top - padY,
                pillRect.right + padX,
                pillRect.bottom + padY
            )
            val glowR = shimmerRect.height() * 0.5f
            canvas.drawRoundRect(shimmerRect, glowR, glowR, glowPaint)
        }

        canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillFillPaint)
        canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillRimPaint)

        // Center-out neutral luminous progress shimmer during stationary long-press (Circle to Search)
        if (longPressActive && longPressProgress > 0.01f) {
            val shimmerFrac = easeOutQuad(longPressProgress)
            val shimmerHalfW = (pillW * 0.5f) * shimmerFrac
            shimmerRect.set(
                pillCx - shimmerHalfW,
                pillTopY,
                pillCx + shimmerHalfW,
                pillBottomY
            )
            glowPaint.color = GlassPalette.argb(
                NEUTRAL_BAR_RIM_RGB,
                (0.45f + 0.42f * shimmerFrac).coerceAtMost(0.88f)
            )
            canvas.drawRoundRect(shimmerRect, pillRadius, pillRadius, glowPaint)
        }

        // Discreet floating action glyph + stage dots badge 10dp above lifted pill
        if (isSwipeActive && pullIntensity > 0.14f && feedbackAlpha > 0.01f) {
            drawFloatingStageBadge(canvas, pillCx, pillTopY - BADGE_GAP_DP * density)
        }
    }

    private fun drawFloatingStageBadge(canvas: Canvas, cx: Float, badgeBottomY: Float) {
        val visibilityFade = easeOutQuad(((pullIntensity - 0.14f) / 0.40f).coerceIn(0f, 1f))
        if (visibilityFade <= 0.02f) return

        val stageScale = 1f + 0.06f * min(stageBoost, 2f)
        val badgeH = 26f * density * stageScale
        val dotCount = if (hasStage3Configured) 3 else 2
        val badgeW = (if (hasStage3Configured) 58f else 50f) * density * stageScale

        val safeCx = cx.coerceIn(badgeW * 0.5f + 12f * density, width - badgeW * 0.5f - 12f * density)
        val topY = badgeBottomY - badgeH
        val centerY = topY + badgeH * 0.5f

        badgeRect.set(
            safeCx - badgeW * 0.5f,
            topY,
            safeCx + badgeW * 0.5f,
            badgeBottomY
        )

        val useLiquid = shouldUseLiquidGlass()
        val bgLum: Float
        if (useLiquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val shader = liquidShader
            if (shader != null) {
                getLocationOnScreen(windowScreenOffset)
                val rawBgLum = backdropCapturer?.sampleLuminanceAt(
                    safeCx + windowScreenOffset[0],
                    centerY + windowScreenOffset[1]
                ) ?: -1f
                bgLum = updateSmoothedBgLum(rawBgLum)

                val bgShader = backdropCapturer?.currentShader()
                shader.bindBackdrop(bgShader)
                val backdropMix = computeBackdropMix()
                val backdropScale = backdropCapturer?.currentBackdropScale() ?: 1f

                // >= 24dp padding on all 4 sides guarantees smoothstep shadow never clips into a box
                val pad = BADGE_SHADER_BOUNDS_PAD_DP * density
                shaderBounds.set(
                    (badgeRect.left - pad).coerceAtLeast(0f),
                    (badgeRect.top - pad).coerceAtLeast(0f),
                    (badgeRect.right + pad).coerceAtMost(width.toFloat()),
                    (badgeRect.bottom + pad).coerceAtMost(height.toFloat())
                )

                val surfaceRgb = if (activeStageLevel >= 1) palette.fillTriggeredRgb else palette.fillRgb
                val opacity = ((0.78f + 0.22f * feedbackAlpha) * visibilityFade).coerceIn(0f, 0.98f)
                val resolvedLum = if (bgLum >= 0f) bgLum else if (isLightGlassActive()) 0.85f else 0.15f

                shader.drawSquircle(
                    canvas = canvas,
                    bounds = shaderBounds,
                    centerX = safeCx,
                    centerY = centerY,
                    halfW = badgeW * 0.5f,
                    halfH = badgeH * 0.5f,
                    refraction = liquidGlassRefraction,
                    stageBoost = stageBoost.coerceIn(0f, 1.5f),
                    backdropMix = backdropMix,
                    isLightTheme = isLightGlassActive(),
                    fillRgb = surfaceRgb,
                    accentRgb = palette.accentRgb,
                    opacity = opacity,
                    screenOffsetX = windowScreenOffset[0].toFloat(),
                    screenOffsetY = windowScreenOffset[1].toFloat(),
                    backdropScale = backdropScale,
                    shadowRangePx = BADGE_SHADOW_RANGE_DP * density,
                    localBgLum = resolvedLum
                )
            } else {
                bgLum = -1f
                drawLegacyBadgeSurface(canvas, badgeH * 0.5f, visibilityFade)
            }
        } else {
            bgLum = -1f
            drawLegacyBadgeSurface(canvas, badgeH * 0.5f, visibilityFade)
        }

        // Resolve foreground ink colors: continuous smoothstep adaptive contrast when Liquid Glass is active
        val effectiveIconRgb: Int
        val effectiveIdleDotRgb: Int
        val effectiveAccentRgb: Int
        if (useLiquid) {
            val effectiveLum = if (bgLum >= 0f) bgLum else if (isLightGlassActive()) 0.72f else 0.25f
            val darkWeight = GlassPalette.smoothstep(0.42f, 0.72f, effectiveLum)
            val blendedInk = GlassPalette.lerpRgb(LIGHT_INK_RGB, DARK_INK_RGB, darkWeight)
            val blendedAccent = GlassPalette.lerpRgb(LIGHT_ACCENT_RGB, DARK_ACCENT_RGB, darkWeight)
            effectiveIconRgb = if (activeStageLevel >= 2) blendedAccent else blendedInk
            effectiveIdleDotRgb = blendedInk
            effectiveAccentRgb = blendedAccent
        } else {
            effectiveIconRgb = when {
                activeStageLevel >= 2 -> palette.accentRgb
                activeStageLevel == 1 -> palette.iconActiveRgb
                else -> palette.iconIdleRgb
            }
            effectiveIdleDotRgb = palette.iconIdleRgb
            effectiveAccentRgb = palette.accentRgb
        }

        // Small action glyph on the left half of the discreet pill badge
        val glyphCx = safeCx - (if (hasStage3Configured) 13f else 10f) * density * stageScale
        badgeIconPaint.color = GlassPalette.argb(effectiveIconRgb, 0.96f * visibilityFade)
        val glyphType = if (activeStageLevel >= 1 && !activeAction.isNone) {
            activeAction.type
        } else {
            com.edgeflow.data.EdgeActionType.HOME
        }
        ActionGlyphPathBuilder.build(
            iconPath,
            iconRect,
            glyphType,
            EdgeSide.BOTTOM,
            glyphCx,
            centerY,
            6.2f * density * stageScale
        )
        canvas.drawPath(iconPath, badgeIconPaint)

        // Stage indicator dots (2 or 3 dots depending on bottomStage3Action)
        val dotSpacing = 5.4f * density * stageScale
        val dotStartX = safeCx + (if (hasStage3Configured) 4.5f else 7.5f) * density * stageScale
        for (i in 0 until dotCount) {
            val isLit = (i + 1) <= activeStageLevel
            dotPaint.color = GlassPalette.argb(
                if (isLit) effectiveAccentRgb else effectiveIdleDotRgb,
                (if (isLit) 0.98f else if (useLiquid) 0.36f else 0.32f) * visibilityFade
            )
            val dotRadius = if (isLit && (i + 1) == activeStageLevel) 2.1f * density else 1.55f * density
            canvas.drawCircle(dotStartX + i * dotSpacing, centerY, dotRadius, dotPaint)
        }

        if (isConfirmArmed) {
            dotPaint.color = GlassPalette.argb(effectiveAccentRgb, 0.92f * visibilityFade)
            canvas.drawCircle(safeCx, topY - 4.5f * density, 2.0f * density, dotPaint)
        }
    }

    private fun drawLegacyBadgeSurface(canvas: Canvas, radius: Float, visibilityFade: Float) {
        val fillAlpha = (feedbackAlpha.coerceAtLeast(0.45f) + if (activeStageLevel >= 2) 0.18f else 0.08f)
            .coerceIn(0f, 0.92f) * visibilityFade
        badgeFillPaint.color = GlassPalette.argb(
            if (activeStageLevel >= 1) palette.fillTriggeredRgb else palette.fillRgb,
            fillAlpha
        )
        badgeRimPaint.color = GlassPalette.argb(
            if (activeStageLevel >= 2) palette.accentRgb else palette.rimRgb,
            (if (activeStageLevel >= 3) 0.85f else if (activeStageLevel == 2) 0.68f else 0.36f) * visibilityFade
        )
        canvas.drawRoundRect(badgeRect, radius, radius, badgeFillPaint)
        canvas.drawRoundRect(badgeRect, radius, radius, badgeRimPaint)
    }

    private fun shouldUseLiquidGlass(): Boolean =
        liquidGlassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && liquidShader != null

    private fun isLightGlassActive(): Boolean {
        return when (visualTheme) {
            VisualTheme.LIGHT_GLASS -> true
            VisualTheme.DARK_GLASS -> false
            VisualTheme.AUTO, VisualTheme.MATERIAL_YOU -> {
                val nightFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightFlags != Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    private fun computeBackdropMix(): Float {
        backdropCapturer?.currentShader() ?: run {
            activeSessionHasBackdrop = false
            return 0f
        }
        val gen = backdropCapturer.currentGeneration()
        if (gen <= 0L) return 0f
        val now = SystemClock.uptimeMillis()
        if (!activeSessionHasBackdrop || gen != lastBackdropGeneration) {
            lastBackdropGeneration = gen
            backdropFadeStartMs = now
            activeSessionHasBackdrop = true
        }
        val elapsed = (now - backdropFadeStartMs).coerceAtLeast(0L)
        val baseMix = if (elapsed >= BACKDROP_FADE_MS) {
            1f
        } else {
            val t = (elapsed.toFloat() / BACKDROP_FADE_MS.toFloat()).coerceIn(0f, 1f)
            val inv = 1f - t
            postInvalidateOnAnimation()
            1f - inv * inv * inv
        }
        val scrollMul = backdropCapturer.currentScrollMixMultiplier()
        if (backdropCapturer.isScrollMixAnimating() && visibility == VISIBLE) {
            postInvalidateOnAnimation()
        }
        return (baseMix * scrollMul).coerceIn(0f, 1f)
    }

    private fun maxLiftPx(): Float = MAX_LIFT_DP * density

    private fun easeOutQuad(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return 1f - (1f - c) * (1f - c)
    }

    private fun updateSmoothedBgLum(rawLum: Float): Float {
        if (rawLum < 0f) {
            return if (smoothedBgLum >= 0f) smoothedBgLum else -1f
        }
        val now = SystemClock.uptimeMillis()
        if (smoothedBgLum < 0f || lastLumUpdateUptimeMs == 0L) {
            smoothedBgLum = rawLum
            lastLumUpdateUptimeMs = now
            return smoothedBgLum
        }
        val dtMs = (now - lastLumUpdateUptimeMs).coerceIn(1L, 64L)
        lastLumUpdateUptimeMs = now
        val alpha = 1f - kotlin.math.exp((-dtMs.toDouble() / LUM_SMOOTH_TAU_MS)).toFloat()
        smoothedBgLum += (rawLum - smoothedBgLum) * alpha
        if (kotlin.math.abs(rawLum - smoothedBgLum) > LUM_CONVERGENCE_EPS && visibility == VISIBLE) {
            postInvalidateOnAnimation()
        }
        return smoothedBgLum
    }

    companion object {
        // High-contrast neutral charcoal gray bottom pill colors (crisp on both light and dark UIs)
        const val NEUTRAL_BAR_IDLE_RGB = 0x4A505A
        private const val NEUTRAL_BAR_LIFT_RGB = 0x3A3F47
        private const val NEUTRAL_BAR_ACTIVE_RGB = 0x2D3138
        private const val NEUTRAL_BAR_STAGE3_RGB = 0x1F2329
        const val NEUTRAL_BAR_RIM_RGB = 0xD8DDE6

        // Strictly clamped inside 16dp..22dp for restrained Pixel/iOS/HyperOS feel
        private const val MAX_LIFT_DP = 20f
        private const val LIFT_DAMPING_DP = 110f
        private const val MAX_HORIZ_FOLLOW_DP = 10f
        private const val HORIZ_DAMPING_DP = 90f
        private const val BADGE_GAP_DP = 10f

        // Liquid Glass badge geometry & luminance-adaptive contrast constants
        private const val BACKDROP_FADE_MS = 60L
        private const val BADGE_SHADOW_RANGE_DP = 14f
        private const val BADGE_SHADER_BOUNDS_PAD_DP = 26f
        private const val BRIGHT_BG_LUM_THRESHOLD = 0.56f
        private const val DARK_INK_RGB = 0x15171A
        private const val DARK_ACCENT_RGB = 0x1D4ED8
        private const val LIGHT_INK_RGB = 0xFFFFFF
        private const val LIGHT_ACCENT_RGB = 0x60A5FA
        private const val LUM_SMOOTH_TAU_MS = 180.0
        private const val LUM_CONVERGENCE_EPS = 0.004f
    }
}
