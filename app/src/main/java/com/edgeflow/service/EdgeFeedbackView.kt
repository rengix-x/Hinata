package com.edgeflow.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.edgeflow.data.EdgeAction
import com.edgeflow.data.EdgeActionType
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.EdgeSide
import com.edgeflow.data.EdgeVisualStyle
import com.edgeflow.data.FeedbackStyle
import com.edgeflow.data.VisualTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Zero-GC, 120fps side-edge visual feedback overlay supporting:
 * - Apple iOS 26-inspired AGSL [LiquidGlassShader] optical refraction (API 33+, bounded strictly
 *   to local bounding rect, 1-tap ON/OFF with instant lightweight Canvas fallback)
 * - Organic non-linear ease-out tension curve for immediate cling from the first millimeters
 * - Smooth exponential Y-axis tracking (`0.35f` lerp, snapped on gesture start)
 * - 3-stage spring bounce physics (Stage 1 icon pop, Stage 2 1.12x lift, Stage 3 1.22x/1.24x pulse)
 * - Native Android `AnimatedImageDrawable` (transparent GIF / Animated WebP / PNG / JPEG) in
 *   [EdgeVisualStyle.CUSTOM_MEDIA_ONLY] and [EdgeVisualStyle.DROPLET_WITH_CUSTOM_MEDIA] modes
 * - Strict 0% idle CPU guarantee: `AnimatedImageDrawable.start()` ONLY while visible during an
 *   active drag, and `stop()` immediately upon retract completion, hide, or detach
 */
class EdgeFeedbackView(
    context: Context,
    private val backdropCapturer: LiquidBackdropCapturer? = null,
    private val onIdleCallback: () -> Unit = {}
) : View(context) {

    private val density: Float = resources.displayMetrics.density
    private val palette = GlassPalette()
    private val liquidShader: LiquidGlassShader? = LiquidGlassShader.createIfSupported()

    // Pre-allocated drawing geometry & paints — ZERO allocations inside onDraw()
    private val bubblePath = Path()
    private val iconPath = Path()
    private val ringPath = Path()
    private val iconRect = RectF()
    private val badgeRect = RectF()
    private val shaderBounds = RectF()
    private val mediaBounds = Rect()
    private val windowScreenOffset = IntArray(2)

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.25f * density
    }
    private val badgeIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.85f * density
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }
    private val stageDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Configuration state
    private var activeSide: EdgeSide = EdgeSide.LEFT
    private var feedbackStyle: FeedbackStyle = FeedbackStyle.BEZIER_BUBBLE
    private var visualTheme: VisualTheme = VisualTheme.AUTO
    private var feedbackAlpha: Float = EdgeConfig.DEFAULT_FEEDBACK_ALPHA
    private var liquidGlassEnabled: Boolean = true
    private var liquidGlassRefraction: Float = EdgeConfig.DEFAULT_LIQUID_GLASS_REFRACTION
    private var dropletPeakWidthDp: Float = EdgeConfig.DEFAULT_DROPLET_PEAK_WIDTH_DP
    private var dropletBaseWidthDp: Float = EdgeConfig.DEFAULT_DROPLET_BASE_WIDTH_DP
    private var edgeVisualStyle: EdgeVisualStyle = EdgeVisualStyle.DEFAULT_DROPLET
    private var customMediaSizeDp: Int = EdgeConfig.DEFAULT_CUSTOM_MEDIA_SIZE_DP
    private var customMediaAlpha: Float = EdgeConfig.DEFAULT_CUSTOM_MEDIA_ALPHA
    private var customMediaMirrorRight: Boolean = true
    private var backdropReadyAtMs: Long = 0L
    private var lastBackdropGen: Long = -1L
    private var activeSessionHasBackdrop: Boolean = false
    private var smoothedBgLum: Float = -1f
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

    // Custom GIF / WebP / PNG drawable state
    private var customDrawable: Drawable? = null
    private var loadedMediaPath: String = ""
    private var loadedMediaVersion: Long = -1L
    private var mediaLoadGeneration: Int = 0

    // Active gesture rendering state (primitives only)
    private var anchorX: Float = 0f
    private var anchorY: Float = 0f
    private var smoothedVerticalShiftPx: Float = 0f
    private var pullProgress: Float = 0f
    private var stageBoost: Float = 0f      // 0f = Stage 0/1, 1f = Stage 2, 2f = Stage 3
    private var stage1PopScale: Float = 1f  // 0.72f -> 1.0f spring pop on Stage 1 entry
    private var activeStageLevel: Int = 0   // 0, 1, 2, 3
    private var hasStage3Configured: Boolean = false
    private var isConfirmArmed: Boolean = false
    private var activeAction: EdgeAction = EdgeAction.NONE

    // Pre-allocated SpringAnimation for fluid 120Hz release retraction
    private val retractHolder = FloatValueHolder(0f)
    private val retractSpring = SpringAnimation(retractHolder).apply {
        spring = SpringForce(0f).apply {
            stiffness = SpringForce.STIFFNESS_MEDIUM
            dampingRatio = 0.80f
        }
        minimumVisibleChange = 0.002f
        addUpdateListener { _, value, _ ->
            pullProgress = value.coerceAtLeast(0f)
            if (pullProgress <= IDLE_EPSILON) {
                finishIdleState()
            } else {
                invalidate()
            }
        }
        addEndListener { _, canceled, _, _ ->
            if (!canceled && pullProgress <= IDLE_EPSILON) {
                finishIdleState()
            }
        }
    }

    // Pre-allocated SpringAnimation for Stage 1 icon pop (0.72 -> 1.0)
    private val stage1PopHolder = FloatValueHolder(1f)
    private val stage1PopSpring = SpringAnimation(stage1PopHolder).apply {
        spring = SpringForce(1f).apply {
            stiffness = SpringForce.STIFFNESS_MEDIUM
            dampingRatio = 0.58f
        }
        minimumVisibleChange = 0.002f
        addUpdateListener { _, value, _ ->
            stage1PopScale = value.coerceIn(0.6f, 1.25f)
            if (visibility == VISIBLE) invalidate()
        }
    }

    // Pre-allocated SpringAnimation for 3-stage bounce (0f -> 1f -> 2f)
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

    fun applyConfig(config: EdgeConfig) {
        feedbackStyle = config.feedbackStyle
        visualTheme = config.visualTheme
        feedbackAlpha = config.feedbackAlpha
        liquidGlassEnabled = config.liquidGlassEnabled
        liquidGlassRefraction = config.liquidGlassRefraction
        dropletPeakWidthDp = config.dropletPeakWidthDp
        dropletBaseWidthDp = config.dropletBaseWidthDp
        edgeVisualStyle = config.edgeVisualStyle
        customMediaSizeDp = config.customMediaSizeDp
        customMediaAlpha = config.customMediaAlpha
        customMediaMirrorRight = config.customMediaMirrorRight
        palette.resolve(context, visualTheme)

        syncCustomMediaDrawable(config)

        if (!isVisualFeedbackEnabled()) {
            hideImmediately()
        }
    }

    private fun syncCustomMediaDrawable(config: EdgeConfig) {
        val wantsCustom = config.edgeVisualStyle != EdgeVisualStyle.DEFAULT_DROPLET &&
            config.customMediaPath.isNotBlank()

        if (!wantsCustom) {
            mediaLoadGeneration++
            releaseCustomDrawable()
            loadedMediaPath = ""
            loadedMediaVersion = -1L
            return
        }

        if (config.customMediaPath == loadedMediaPath &&
            config.customMediaVersion == loadedMediaVersion &&
            customDrawable != null
        ) {
            return
        }

        loadedMediaPath = config.customMediaPath
        loadedMediaVersion = config.customMediaVersion
        val token = ++mediaLoadGeneration

        CustomMediaLoader.decodeDrawableAsync(
            filePath = config.customMediaPath,
            maxLongEdgePx = CustomMediaLoader.MAX_DECODE_LONG_EDGE_PX
        ) { decoded ->
            if (token != mediaLoadGeneration) {
                if (decoded is AnimatedImageDrawable) {
                    runCatching { decoded.stop() }
                }
                return@decodeDrawableAsync
            }
            releaseCustomDrawable()
            customDrawable = decoded
            decoded?.callback = this
            if (visibility == VISIBLE) {
                startCustomAnimationIfNeeded()
                invalidate()
            } else {
                stopCustomAnimation()
            }
        }
    }

    private fun releaseCustomDrawable() {
        val current = customDrawable ?: return
        if (current is AnimatedImageDrawable) {
            runCatching { current.stop() }
        }
        current.callback = null
        customDrawable = null
    }

    fun refreshTheme() {
        palette.resolve(context, visualTheme)
    }

    fun onGestureUpdate(side: EdgeSide, update: GestureUpdate) {
        if (side == EdgeSide.BOTTOM) return
        if (!isVisualFeedbackEnabled()) {
            if (visibility != GONE) hideImmediately()
            return
        }

        val wasHidden = visibility != VISIBLE || pullProgress <= IDLE_EPSILON
        if (wasHidden) {
            val hasCachedBackdrop = backdropCapturer?.currentShader() != null
            activeSessionHasBackdrop = hasCachedBackdrop
            backdropReadyAtMs = 0L
            lastBackdropGen = backdropCapturer?.currentGeneration() ?: -1L
            smoothedBgLum = -1f
            lastLumUpdateUptimeMs = 0L
        }

        if (retractSpring.isRunning) {
            retractSpring.cancel()
        }

        activeSide = side
        anchorX = update.anchorX
        anchorY = update.anchorY

        // Organic Y-axis exponential smoothing (snapped on gesture start so it never jumps from prior edge Y)
        val rawShift = (update.currentY - update.anchorY).coerceIn(-72f * density, 72f * density)
        smoothedVerticalShiftPx = if (wasHidden) {
            rawShift
        } else {
            smoothedVerticalShiftPx + (rawShift - smoothedVerticalShiftPx) * Y_SMOOTH_LERP
        }

        pullProgress = update.pullProgress
        activeAction = update.activeAction
        hasStage3Configured = update.hasStage3Configured
        isConfirmArmed = update.isDoubleSwipeConfirmPending

        val newStage = update.stageLevel.coerceIn(0, 3)
        if (newStage != activeStageLevel) {
            // Stage 1 crisp icon pop
            if (newStage == 1 && activeStageLevel == 0) {
                stage1PopSpring.cancel()
                stage1PopScale = 0.72f
                stage1PopHolder.value = 0.72f
                stage1PopSpring.spring.finalPosition = 1f
                stage1PopSpring.setStartValue(0.72f)
                stage1PopSpring.start()
            }

            // Stage 2 / Stage 3 bounce transition
            val targetBoost = when (newStage) {
                3 -> 2f
                2 -> 1f
                else -> 0f
            }
            val currentTarget = stageSpring.spring.finalPosition
            if (targetBoost != currentTarget || stageBoost != targetBoost) {
                stageSpring.cancel()
                stageHolder.value = stageBoost
                stageSpring.spring.finalPosition = targetBoost
                stageSpring.setStartValue(stageBoost)
                stageSpring.start()
            }
            activeStageLevel = newStage
        }

        if (pullProgress > IDLE_EPSILON) {
            if (visibility != VISIBLE) {
                visibility = VISIBLE
            }
            startCustomAnimationIfNeeded()
            invalidate()
        } else if (visibility != GONE) {
            finishIdleState()
        }
    }

    fun onGestureRelease() {
        if (!isVisualFeedbackEnabled() || pullProgress <= IDLE_EPSILON) {
            hideImmediately()
            return
        }
        stageSpring.cancel()
        stageHolder.value = stageBoost
        stageSpring.spring.finalPosition = 0f
        stageSpring.setStartValue(stageBoost)
        stageSpring.start()

        retractSpring.cancel()
        retractHolder.value = pullProgress
        retractSpring.spring.finalPosition = 0f
        retractSpring.setStartValue(pullProgress)
        retractSpring.start()
    }

    fun hideImmediately() {
        retractSpring.cancel()
        stageSpring.cancel()
        stage1PopSpring.cancel()
        finishIdleState()
    }

    private fun finishIdleState() {
        val wasVisible = visibility == VISIBLE
        stopCustomAnimation()
        pullProgress = 0f
        stageBoost = 0f
        stage1PopScale = 1f
        activeStageLevel = 0
        smoothedVerticalShiftPx = 0f
        activeAction = EdgeAction.NONE
        activeSessionHasBackdrop = false
        backdropReadyAtMs = 0L
        smoothedBgLum = -1f
        lastLumUpdateUptimeMs = 0L
        alpha = 1f
        if (visibility != GONE) {
            visibility = GONE
        }
        if (wasVisible) {
            onIdleCallback()
        }
    }

    private fun isVisualFeedbackEnabled(): Boolean {
        if (feedbackStyle == FeedbackStyle.HIDDEN) return false
        val useCustomOnly = edgeVisualStyle == EdgeVisualStyle.CUSTOM_MEDIA_ONLY && customDrawable != null
        return if (useCustomOnly) {
            customMediaAlpha > IDLE_EPSILON
        } else {
            feedbackAlpha > IDLE_EPSILON || (edgeVisualStyle == EdgeVisualStyle.DROPLET_WITH_CUSTOM_MEDIA && customDrawable != null && customMediaAlpha > IDLE_EPSILON)
        }
    }

    private fun startCustomAnimationIfNeeded() {
        if (edgeVisualStyle == EdgeVisualStyle.DEFAULT_DROPLET) return
        val anim = customDrawable as? AnimatedImageDrawable ?: return
        if (!anim.isRunning) {
            runCatching { anim.start() }
        }
    }

    private fun stopCustomAnimation() {
        val anim = customDrawable as? AnimatedImageDrawable ?: return
        if (anim.isRunning) {
            runCatching { anim.stop() }
        }
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        super.verifyDrawable(who) || (who === customDrawable)

    override fun invalidateDrawable(drawable: Drawable) {
        if (drawable === customDrawable) {
            // Strict 0% idle CPU guard: never repaint when hidden
            if (visibility == VISIBLE && pullProgress > IDLE_EPSILON) {
                invalidate()
            } else {
                stopCustomAnimation()
            }
            return
        }
        super.invalidateDrawable(drawable)
    }

    override fun onDetachedFromWindow() {
        retractSpring.cancel()
        stageSpring.cancel()
        stage1PopSpring.cancel()
        stopCustomAnimation()
        pullProgress = 0f
        stageBoost = 0f
        stage1PopScale = 1f
        activeStageLevel = 0
        smoothedVerticalShiftPx = 0f
        activeAction = EdgeAction.NONE
        if (visibility != GONE) {
            visibility = GONE
        }
        super.onDetachedFromWindow()
    }

    fun release() {
        backdropCapturer?.removeBackdropReadyListener(backdropReadyListener)
        hideImmediately()
        releaseCustomDrawable()
    }

    override fun onDraw(canvas: Canvas) {
        if (!isVisualFeedbackEnabled() || pullProgress <= IDLE_EPSILON) {
            return
        }

        val clampedProgress = pullProgress.coerceIn(0f, 1.35f)
        val organicTension = organicEaseOut(clampedProgress)
        val fadeCurve = easeOutQuad(min(clampedProgress * 1.65f, 1f))

        val effectiveStyle = if (customDrawable != null) {
            edgeVisualStyle
        } else {
            EdgeVisualStyle.DEFAULT_DROPLET
        }

        when (effectiveStyle) {
            EdgeVisualStyle.CUSTOM_MEDIA_ONLY -> {
                drawCustomMediaOnly(canvas, organicTension, fadeCurve)
            }
            EdgeVisualStyle.DROPLET_WITH_CUSTOM_MEDIA -> {
                configureGlassPaints(fadeCurve)
                drawDropletWithCustomMedia(canvas, clampedProgress, organicTension, fadeCurve)
            }
            EdgeVisualStyle.DEFAULT_DROPLET -> {
                configureGlassPaints(fadeCurve)
                when (feedbackStyle) {
                    FeedbackStyle.BEZIER_BUBBLE -> drawBezierDroplet(canvas, clampedProgress, organicTension, fadeCurve)
                    FeedbackStyle.MINIMAL_ARROW -> drawMinimalPill(canvas, clampedProgress, organicTension, fadeCurve)
                    FeedbackStyle.HIDDEN -> return
                }
            }
        }
    }

    private fun configureGlassPaints(fadeCurve: Float) {
        val stageAlphaBoost = when {
            activeStageLevel >= 3 -> 0.22f
            activeStageLevel == 2 -> 0.14f
            activeStageLevel == 1 -> 0.07f
            else -> 0f
        }
        val fillAlpha = (feedbackAlpha * fadeCurve + stageAlphaBoost * fadeCurve).coerceIn(0f, 0.96f)

        val surfaceRgb = if (activeStageLevel >= 1) {
            palette.fillTriggeredRgb
        } else {
            palette.fillRgb
        }
        bubblePaint.color = GlassPalette.argb(surfaceRgb, fillAlpha)

        val rimRgb = when {
            activeStageLevel >= 3 -> palette.accentRgb
            activeStageLevel == 2 -> palette.accentRgb
            else -> palette.rimRgb
        }
        val rimAlpha = when {
            activeStageLevel >= 3 -> (palette.rimTriggeredAlpha + 0.34f).coerceAtMost(0.88f) * fadeCurve
            activeStageLevel == 2 -> (palette.rimTriggeredAlpha + 0.22f).coerceAtMost(0.76f) * fadeCurve
            activeStageLevel == 1 -> palette.rimTriggeredAlpha * fadeCurve
            else -> palette.rimBaseAlpha * fadeCurve
        }
        rimPaint.strokeWidth = if (activeStageLevel >= 3) 1.35f * density else 1.0f * density
        rimPaint.color = GlassPalette.argb(rimRgb, rimAlpha)

        val iconRgb = when {
            activeStageLevel >= 2 -> palette.accentRgb
            activeStageLevel == 1 -> palette.iconActiveRgb
            else -> palette.iconIdleRgb
        }
        val iconTargetAlpha = when {
            activeStageLevel >= 3 -> 1.0f
            activeStageLevel == 2 -> 0.98f
            activeStageLevel == 1 -> 0.94f
            else -> 0.80f
        }
        iconPaint.color = GlassPalette.argb(iconRgb, iconTargetAlpha * fadeCurve)
    }

    private fun computeDropletStageScale(): Float {
        // Refined native proportions: Stage 1: 1.0x, Stage 2: 1.10x, Stage 3: 1.18x
        val s2 = stageBoost.coerceIn(0f, 1f)
        val s3 = (stageBoost - 1f).coerceIn(0f, 1.35f)
        return 1f + 0.10f * s2 + 0.08f * s3
    }

    private fun computeCustomOnlyStageScale(): Float {
        // Stage 1: 1.0x, Stage 2: 1.12x, Stage 3: 1.24x
        val s2 = stageBoost.coerceIn(0f, 1f)
        val s3 = (stageBoost - 1f).coerceIn(0f, 1.35f)
        return 1f + 0.12f * s2 + 0.12f * s3
    }

    private fun drawBezierDroplet(
        canvas: Canvas,
        progress: Float,
        organicTension: Float,
        fadeCurve: Float
    ) {
        val stageScale = computeDropletStageScale()
        val basePeak = dropletPeakWidthDp * stageScale * density
        val peak = basePeak * organicTension
        val halfSpan = (dropletBaseWidthDp * 0.5f) * stageScale * density * (0.76f + 0.24f * min(progress, 1f))

        bubblePath.rewind()

        // Pin both roots at anchorY ± halfSpan and bend only the tip smoothly along Y
        val baseY = anchorY.coerceIn(halfSpan + 12f * density, height - halfSpan - 12f * density)
        val maxTipBend = min(halfSpan * 0.42f, MAX_TIP_BEND_DP * density)
        val tipOffsetY = (smoothedVerticalShiftPx * 0.56f).coerceIn(-maxTipBend, maxTipBend)
        val topY = baseY - halfSpan
        val bottomY = baseY + halfSpan
        val tipY = baseY + tipOffsetY

        val isRight = activeSide == EdgeSide.RIGHT
        val edgeX = if (isRight) width.toFloat() else 0f
        val iconCenterX = if (isRight) edgeX - peak * 0.48f else peak * 0.48f
        val iconCenterY = baseY + tipOffsetY * 0.56f

        val useLiquid = shouldUseLiquidGlass()
        val rawBgLum = if (useLiquid) {
            refreshWindowScreenOffset()
            backdropCapturer?.sampleLuminanceAt(
                windowScreenOffset[0] + iconCenterX,
                windowScreenOffset[1] + iconCenterY
            ) ?: -1f
        } else {
            -1f
        }
        val bgLum = if (useLiquid) updateSmoothedBgLum(rawBgLum) else -1f
        val effectiveAccentRgb = applyAdaptiveLiquidInk(useLiquid, bgLum, fadeCurve)

        if (useLiquid) {
            drawLiquidDropletSurface(
                canvas = canvas,
                edgeX = edgeX,
                isRightSide = isRight,
                peak = peak,
                baseY = baseY,
                tipOffsetY = tipOffsetY,
                halfSpan = halfSpan,
                organicTension = organicTension,
                fadeCurve = fadeCurve,
                localBgLum = bgLum
            )
        } else {
            val upperSpan = (tipY - topY).coerceAtLeast(8f)
            val lowerSpan = (bottomY - tipY).coerceAtLeast(8f)
            if (!isRight) {
                bubblePath.moveTo(0f, topY)
                bubblePath.cubicTo(
                    0f, topY + upperSpan * 0.42f,
                    peak, tipY - upperSpan * 0.42f,
                    peak, tipY
                )
                bubblePath.cubicTo(
                    peak, tipY + lowerSpan * 0.42f,
                    0f, bottomY - lowerSpan * 0.42f,
                    0f, bottomY
                )
                bubblePath.close()
            } else {
                bubblePath.moveTo(edgeX, topY)
                bubblePath.cubicTo(
                    edgeX, topY + upperSpan * 0.42f,
                    edgeX - peak, tipY - upperSpan * 0.42f,
                    edgeX - peak, tipY
                )
                bubblePath.cubicTo(
                    edgeX - peak, tipY + lowerSpan * 0.42f,
                    edgeX, bottomY - lowerSpan * 0.42f,
                    edgeX, bottomY
                )
                bubblePath.close()
            }
            canvas.drawPath(bubblePath, bubblePaint)
            canvas.drawPath(bubblePath, rimPaint)
        }

        drawStageAccentRings(canvas, iconCenterX, iconCenterY, fadeCurve, baseRadiusDp = 12.5f, accentRgb = effectiveAccentRgb)

        val iconRadius = 8.5f * stageScale * stage1PopScale * density * (0.78f + 0.22f * min(progress, 1f))
        val glyphType = if (activeStageLevel >= 1) activeAction.type else EdgeActionType.BACK
        ActionGlyphPathBuilder.build(iconPath, iconRect, glyphType, activeSide, iconCenterX, iconCenterY, iconRadius)
        canvas.drawPath(iconPath, iconPaint)

        // Subtle Stage indicator dots tucked cleanly under the glyph when Stage 3 is enabled
        if (hasStage3Configured && activeStageLevel >= 1 && peak >= 24f * density) {
            drawCompactStageDots(canvas, iconCenterX, iconCenterY + iconRadius + 6.5f * density, fadeCurve, effectiveAccentRgb, useLiquid, bgLum)
        }

        if (isConfirmArmed) {
            canvas.drawCircle(iconCenterX, iconCenterY - iconRadius * 1.35f, 2.1f * density, iconPaint)
        }
    }

    private fun drawMinimalPill(
        canvas: Canvas,
        progress: Float,
        organicTension: Float,
        fadeCurve: Float
    ) {
        val stageScale = computeDropletStageScale()
        val radius = 18.5f * stageScale * density
        val travel = (20f + 22f * organicTension) * density

        val cx = if (activeSide == EdgeSide.LEFT) travel else width - travel
        val cy = (anchorY + smoothedVerticalShiftPx * 0.42f).coerceIn(radius * 2f, height - radius * 2f)

        val useLiquid = shouldUseLiquidGlass()
        val rawBgLum = if (useLiquid) {
            refreshWindowScreenOffset()
            backdropCapturer?.sampleLuminanceAt(
                windowScreenOffset[0] + cx,
                windowScreenOffset[1] + cy
            ) ?: -1f
        } else {
            -1f
        }
        val bgLum = if (useLiquid) updateSmoothedBgLum(rawBgLum) else -1f
        val effectiveAccentRgb = applyAdaptiveLiquidInk(useLiquid, bgLum, fadeCurve)

        if (useLiquid) {
            drawLiquidSquircleSurface(
                canvas = canvas,
                cx = cx,
                cy = cy,
                halfW = radius * 1.05f,
                halfH = radius * 1.05f,
                fadeCurve = fadeCurve,
                localBgLum = bgLum
            )
        } else {
            canvas.drawCircle(cx, cy, radius, bubblePaint)
            canvas.drawCircle(cx, cy, radius, rimPaint)
        }

        drawStageAccentRings(canvas, cx, cy, fadeCurve, baseRadiusDp = 19.5f, accentRgb = effectiveAccentRgb)

        val glyphType = if (activeStageLevel >= 1) activeAction.type else EdgeActionType.BACK
        val iconRadius = 8.5f * stageScale * stage1PopScale * density
        ActionGlyphPathBuilder.build(iconPath, iconRect, glyphType, activeSide, cx, cy, iconRadius)
        canvas.drawPath(iconPath, iconPaint)

        if (hasStage3Configured && activeStageLevel >= 1) {
            drawCompactStageDots(canvas, cx, cy + radius + 6.5f * density, fadeCurve, effectiveAccentRgb, useLiquid, bgLum)
        }
    }

    private fun drawCustomMediaOnly(
        canvas: Canvas,
        organicTension: Float,
        fadeCurve: Float
    ) {
        val drawable = customDrawable ?: return
        val stageScale = computeCustomOnlyStageScale()
        val sizePx = customMediaSizeDp * density * stageScale * (0.86f + 0.14f * stage1PopScale)
        val halfSize = sizePx * 0.5f

        val maxTravel = (halfSize + 18f * density)
        val slideOffset = -halfSize * 0.55f + (maxTravel + halfSize * 0.55f) * organicTension

        val cx = if (activeSide == EdgeSide.LEFT) slideOffset else width - slideOffset
        val cy = (anchorY + smoothedVerticalShiftPx * 0.44f)
            .coerceIn(halfSize + 18f * density, height - halfSize - 32f * density)

        drawCustomDrawableFit(
            canvas = canvas,
            drawable = drawable,
            cx = cx,
            cy = cy,
            maxBoxPx = sizePx,
            alphaFraction = (customMediaAlpha * fadeCurve).coerceIn(0f, 1f)
        )

        // Discreet Stage 2 / Stage 3 compact action badge beneath custom media
        if (activeStageLevel >= 2 && !activeAction.isNone) {
            drawFloatingActionBadge(
                canvas = canvas,
                cx = cx,
                cy = cy + halfSize + 12f * density,
                fadeCurve = fadeCurve
            )
        } else if (isConfirmArmed) {
            stageDotPaint.color = GlassPalette.argb(palette.accentRgb, 0.92f * fadeCurve)
            canvas.drawCircle(cx, cy - halfSize - 6f * density, 2.8f * density, stageDotPaint)
        }
    }

    private fun drawDropletWithCustomMedia(
        canvas: Canvas,
        progress: Float,
        organicTension: Float,
        fadeCurve: Float
    ) {
        val drawable = customDrawable ?: return
        val stageScale = computeDropletStageScale()
        val mediaTargetPx = (customMediaSizeDp * 0.72f).coerceIn(26f, 64f) * density * stageScale
        val basePeak = max(dropletPeakWidthDp * density, mediaTargetPx + 14f * density) * stageScale
        val peak = basePeak * organicTension
        val halfSpan = max((dropletBaseWidthDp * 0.5f) * density, mediaTargetPx * 1.18f) * stageScale * (0.76f + 0.24f * min(progress, 1f))

        bubblePath.rewind()

        val baseY = anchorY.coerceIn(halfSpan + 12f * density, height - halfSpan - 16f * density)
        val maxTipBend = min(halfSpan * 0.42f, MAX_TIP_BEND_DP * density)
        val tipOffsetY = (smoothedVerticalShiftPx * 0.56f).coerceIn(-maxTipBend, maxTipBend)
        val topY = baseY - halfSpan
        val bottomY = baseY + halfSpan
        val tipY = baseY + tipOffsetY

        val isRight = activeSide == EdgeSide.RIGHT
        val edgeX = if (isRight) width.toFloat() else 0f
        val mediaCenterX = if (isRight) edgeX - peak * 0.48f else peak * 0.48f
        val mediaCenterY = baseY + tipOffsetY * 0.56f

        val useLiquid = shouldUseLiquidGlass()
        val rawBgLum = if (useLiquid) {
            refreshWindowScreenOffset()
            backdropCapturer?.sampleLuminanceAt(
                windowScreenOffset[0] + mediaCenterX,
                windowScreenOffset[1] + mediaCenterY
            ) ?: -1f
        } else {
            -1f
        }
        val bgLum = if (useLiquid) updateSmoothedBgLum(rawBgLum) else -1f

        if (useLiquid) {
            drawLiquidDropletSurface(
                canvas = canvas,
                edgeX = edgeX,
                isRightSide = isRight,
                peak = peak,
                baseY = baseY,
                tipOffsetY = tipOffsetY,
                halfSpan = halfSpan,
                organicTension = organicTension,
                fadeCurve = fadeCurve,
                localBgLum = bgLum
            )
        } else {
            if (!isRight) {
                bubblePath.moveTo(0f, topY)
                bubblePath.cubicTo(
                    0f, topY + halfSpan * 0.30f,
                    peak, tipY - halfSpan * 0.52f,
                    peak, tipY
                )
                bubblePath.cubicTo(
                    peak, tipY + halfSpan * 0.52f,
                    0f, bottomY - halfSpan * 0.30f,
                    0f, bottomY
                )
                bubblePath.close()
            } else {
                bubblePath.moveTo(edgeX, topY)
                bubblePath.cubicTo(
                    edgeX, topY + halfSpan * 0.30f,
                    edgeX - peak, tipY - halfSpan * 0.52f,
                    edgeX - peak, tipY
                )
                bubblePath.cubicTo(
                    edgeX - peak, tipY + halfSpan * 0.52f,
                    edgeX, bottomY - halfSpan * 0.30f,
                    edgeX, bottomY
                )
                bubblePath.close()
            }
            canvas.drawPath(bubblePath, bubblePaint)
            canvas.drawPath(bubblePath, rimPaint)
        }

        val fittedMediaPx = min(mediaTargetPx, peak * 0.78f).coerceAtLeast(16f * density)
        drawCustomDrawableFit(
            canvas = canvas,
            drawable = drawable,
            cx = mediaCenterX,
            cy = mediaCenterY,
            maxBoxPx = fittedMediaPx,
            alphaFraction = (customMediaAlpha * fadeCurve).coerceIn(0f, 1f)
        )

        if (activeStageLevel >= 2 && !activeAction.isNone) {
            drawFloatingActionBadge(
                canvas = canvas,
                cx = mediaCenterX,
                cy = mediaCenterY + fittedMediaPx * 0.5f + 13f * density,
                fadeCurve = fadeCurve
            )
        }
    }

    private fun drawCustomDrawableFit(
        canvas: Canvas,
        drawable: Drawable,
        cx: Float,
        cy: Float,
        maxBoxPx: Float,
        alphaFraction: Float
    ) {
        val intrW = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
        val intrH = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
        val aspect = intrW.toFloat() / intrH.toFloat()

        val drawW: Float
        val drawH: Float
        if (aspect >= 1f) {
            drawW = maxBoxPx
            drawH = maxBoxPx / aspect
        } else {
            drawH = maxBoxPx
            drawW = maxBoxPx * aspect
        }

        val left = (cx - drawW * 0.5f).roundToInt()
        val top = (cy - drawH * 0.5f).roundToInt()
        val right = (cx + drawW * 0.5f).roundToInt()
        val bottom = (cy + drawH * 0.5f).roundToInt()
        mediaBounds.set(left, top, right, bottom)
        drawable.bounds = mediaBounds
        drawable.alpha = (alphaFraction * 255f).roundToInt().coerceIn(0, 255)

        val shouldMirror = customMediaMirrorRight && activeSide == EdgeSide.RIGHT
        if (shouldMirror) {
            canvas.save()
            canvas.scale(-1f, 1f, cx, cy)
            drawable.draw(canvas)
            canvas.restore()
        } else {
            drawable.draw(canvas)
        }
    }

    private fun drawStageAccentRings(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        fadeCurve: Float,
        baseRadiusDp: Float,
        accentRgb: Int = palette.accentRgb
    ) {
        if (stageBoost <= 0.04f) return

        // Stage 2 luminous ring
        val s2Frac = min(stageBoost, 1f)
        ringPaint.strokeWidth = 1.4f * density
        ringPaint.color = GlassPalette.argb(accentRgb, (0.52f * s2Frac) * fadeCurve)
        val ringRadius = (baseRadiusDp + s2Frac * 4.2f) * density
        ringPath.rewind()
        ringPath.addCircle(cx, cy, ringRadius, Path.Direction.CW)
        canvas.drawPath(ringPath, ringPaint)

        // Stage 3 secondary outer accent ring
        val s3Frac = (stageBoost - 1f).coerceIn(0f, 1.2f)
        if (s3Frac > 0.04f) {
            ringPaint.strokeWidth = 1.6f * density
            ringPaint.color = GlassPalette.argb(accentRgb, (0.72f * min(s3Frac, 1f)) * fadeCurve)
            val outerRadius = (baseRadiusDp + 6.5f + s3Frac * 3.8f) * density
            ringPath.rewind()
            ringPath.addCircle(cx, cy, outerRadius, Path.Direction.CW)
            canvas.drawPath(ringPath, ringPaint)
        }
    }

    private fun drawFloatingActionBadge(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        fadeCurve: Float
    ) {
        val badgeH = 22f * density
        val badgeW = if (hasStage3Configured) 42f * density else 34f * density
        val safeCx = cx.coerceIn(badgeW * 0.5f + 6f * density, width - badgeW * 0.5f - 6f * density)
        val safeCy = cy.coerceIn(badgeH * 0.5f + 8f * density, height - badgeH * 0.5f - 8f * density)

        badgeRect.set(
            safeCx - badgeW * 0.5f,
            safeCy - badgeH * 0.5f,
            safeCx + badgeW * 0.5f,
            safeCy + badgeH * 0.5f
        )
        val radius = badgeH * 0.5f

        bubblePaint.color = GlassPalette.argb(palette.fillTriggeredRgb, 0.84f * fadeCurve)
        rimPaint.strokeWidth = 1.1f * density
        rimPaint.color = GlassPalette.argb(
            palette.accentRgb,
            (if (activeStageLevel >= 3) 0.88f else 0.65f) * fadeCurve
        )
        canvas.drawRoundRect(badgeRect, radius, radius, bubblePaint)
        canvas.drawRoundRect(badgeRect, radius, radius, rimPaint)

        val glyphCx = safeCx - (if (hasStage3Configured) 8f else 5f) * density
        badgeIconPaint.color = GlassPalette.argb(palette.accentRgb, 0.96f * fadeCurve)
        ActionGlyphPathBuilder.build(
            iconPath,
            iconRect,
            activeAction.type,
            activeSide,
            glyphCx,
            safeCy,
            5.6f * density
        )
        canvas.drawPath(iconPath, badgeIconPaint)

        val dotCount = if (hasStage3Configured) 3 else 2
        val dotStartX = safeCx + (if (hasStage3Configured) 3.5f else 6f) * density
        val dotSpacing = 4.6f * density
        for (i in 0 until dotCount) {
            val isLit = (i + 1) <= activeStageLevel
            stageDotPaint.color = GlassPalette.argb(
                if (isLit) palette.accentRgb else palette.iconIdleRgb,
                (if (isLit) 0.96f else 0.35f) * fadeCurve
            )
            canvas.drawCircle(dotStartX + i * dotSpacing, safeCy, 1.55f * density, stageDotPaint)
        }
    }

    private fun drawCompactStageDots(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        fadeCurve: Float,
        accentRgb: Int = palette.accentRgb,
        useLiquid: Boolean = false,
        bgLum: Float = -1f
    ) {
        val spacing = 5.0f * density
        val startX = cx - spacing
        val idleRgb = if (useLiquid && bgLum >= 0f) resolveAdaptiveInkRgb(bgLum) else palette.iconIdleRgb
        for (i in 0 until 3) {
            val isLit = (i + 1) <= activeStageLevel
            stageDotPaint.color = GlassPalette.argb(
                if (isLit) accentRgb else idleRgb,
                (if (isLit) 0.95f else 0.34f) * fadeCurve
            )
            canvas.drawCircle(startX + i * spacing, cy, 1.45f * density, stageDotPaint)
        }
    }

    private fun easeOutQuad(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return 1f - (1f - c) * (1f - c)
    }

    /**
     * Responsive non-linear organic tension curve (`1 - (1 - t)^2.4`) that grabs the eye
     * from the very first drag millimeters while gently easing into full reach.
     */
    private fun organicEaseOut(progress: Float): Float {
        val clamped = progress.coerceIn(0f, 1.25f)
        val unit = min(clamped, 1f)
        val base = 1f - (1f - unit).toDouble().pow(2.4).toFloat()
        val overshoot = (clamped - 1f).coerceAtLeast(0f) * 0.22f
        return base + overshoot
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

    private fun resolveAdaptiveInkRgb(bgLum: Float): Int {
        val darkWeight = GlassPalette.smoothstep(0.42f, 0.72f, bgLum)
        return GlassPalette.lerpRgb(LIGHT_INK_RGB, DARK_INK_RGB, darkWeight)
    }

    private fun resolveAdaptiveAccentRgb(bgLum: Float, defaultAccentRgb: Int): Int {
        val darkWeight = GlassPalette.smoothstep(0.42f, 0.72f, bgLum)
        return GlassPalette.lerpRgb(defaultAccentRgb, DARK_ACCENT_RGB, darkWeight)
    }

    private fun refreshWindowScreenOffset() {
        if (isAttachedToWindow) {
            getLocationOnScreen(windowScreenOffset)
        } else {
            windowScreenOffset[0] = 0
            windowScreenOffset[1] = 0
        }
    }

    private fun applyAdaptiveLiquidInk(useLiquid: Boolean, bgLum: Float, fadeCurve: Float): Int {
        if (!useLiquid) return palette.accentRgb
        val effectiveLum = if (bgLum >= 0f) bgLum else if (isLightGlassActive()) 0.72f else 0.25f
        val accentRgb = resolveAdaptiveAccentRgb(effectiveLum, palette.accentRgb)
        val iconRgb = if (activeStageLevel >= 2) {
            accentRgb
        } else {
            resolveAdaptiveInkRgb(effectiveLum)
        }
        val iconTargetAlpha = when {
            activeStageLevel >= 3 -> 1.0f
            activeStageLevel == 2 -> 0.98f
            activeStageLevel == 1 -> 0.96f
            else -> 0.84f
        }
        iconPaint.color = GlassPalette.argb(iconRgb, iconTargetAlpha * fadeCurve)
        return accentRgb
    }

    private fun drawLiquidDropletSurface(
        canvas: Canvas,
        edgeX: Float,
        isRightSide: Boolean,
        peak: Float,
        baseY: Float,
        tipOffsetY: Float,
        halfSpan: Float,
        organicTension: Float,
        fadeCurve: Float,
        localBgLum: Float
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val shader = liquidShader ?: return

        val bgShader = backdropCapturer?.currentShader()
        shader.bindBackdrop(bgShader)
        val backdropMix = resolveBackdropMix()
        val backdropScale = backdropCapturer?.currentBackdropScale() ?: 1f

        // Generous bounds covering both fixed root and bent tip + >=26dp shadow pad so shadow never clips
        val pad = SHADER_BOUNDS_PAD_DP * density
        val spanW = peak + pad
        val minY = min(baseY - halfSpan, baseY + tipOffsetY - halfSpan * 0.58f) - pad
        val maxY = max(baseY + halfSpan, baseY + tipOffsetY + halfSpan * 0.58f) + pad
        if (isRightSide) {
            shaderBounds.set(
                (edgeX - spanW).coerceAtLeast(0f),
                minY.coerceAtLeast(0f),
                edgeX,
                maxY.coerceAtMost(height.toFloat())
            )
        } else {
            shaderBounds.set(
                0f,
                minY.coerceAtLeast(0f),
                spanW.coerceAtMost(width.toFloat()),
                maxY.coerceAtMost(height.toFloat())
            )
        }

        val surfaceRgb = if (activeStageLevel >= 1) palette.fillTriggeredRgb else palette.fillRgb
        val opacity = ((0.76f + 0.24f * feedbackAlpha) * fadeCurve).coerceIn(0f, 0.98f)
        val isLight = isLightGlassActive()
        val resolvedLum = if (localBgLum >= 0f) localBgLum else if (isLight) 0.85f else 0.15f
        shader.drawDroplet(
            canvas = canvas,
            bounds = shaderBounds,
            edgeX = edgeX,
            centerY = baseY,
            peakPx = peak,
            halfSpanPx = halfSpan,
            fromLeft = !isRightSide,
            tipOffsetY = tipOffsetY,
            refraction = liquidGlassRefraction,
            stageBoost = stageBoost,
            backdropMix = backdropMix,
            isLightTheme = isLight,
            fillRgb = surfaceRgb,
            accentRgb = palette.accentRgb,
            opacity = opacity,
            screenOffsetX = windowScreenOffset[0].toFloat(),
            screenOffsetY = windowScreenOffset[1].toFloat(),
            backdropScale = backdropScale,
            shadowRangePx = SHADOW_RANGE_DP * density,
            localBgLum = resolvedLum
        )
    }

    private fun drawLiquidSquircleSurface(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        halfW: Float,
        halfH: Float,
        fadeCurve: Float,
        localBgLum: Float
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val shader = liquidShader ?: return

        val bgShader = backdropCapturer?.currentShader()
        shader.bindBackdrop(bgShader)
        val backdropMix = resolveBackdropMix()
        val backdropScale = backdropCapturer?.currentBackdropScale() ?: 1f

        val pad = SQUIRCLE_BOUNDS_PAD_DP * density
        shaderBounds.set(
            (cx - halfW - pad).coerceAtLeast(0f),
            (cy - halfH - pad).coerceAtLeast(0f),
            (cx + halfW + pad).coerceAtMost(width.toFloat()),
            (cy + halfH + pad).coerceAtMost(height.toFloat())
        )

        val surfaceRgb = if (activeStageLevel >= 1) palette.fillTriggeredRgb else palette.fillRgb
        val opacity = ((0.76f + 0.24f * feedbackAlpha) * fadeCurve).coerceIn(0f, 0.98f)
        val isLight = isLightGlassActive()
        val resolvedLum = if (localBgLum >= 0f) localBgLum else if (isLight) 0.85f else 0.15f
        shader.drawSquircle(
            canvas = canvas,
            bounds = shaderBounds,
            centerX = cx,
            centerY = cy,
            halfW = halfW,
            halfH = halfH,
            refraction = liquidGlassRefraction,
            stageBoost = stageBoost,
            backdropMix = backdropMix,
            isLightTheme = isLight,
            fillRgb = surfaceRgb,
            accentRgb = palette.accentRgb,
            opacity = opacity,
            screenOffsetX = windowScreenOffset[0].toFloat(),
            screenOffsetY = windowScreenOffset[1].toFloat(),
            backdropScale = backdropScale,
            shadowRangePx = SHADOW_RANGE_DP * density,
            localBgLum = resolvedLum
        )
    }

    companion object {
        private const val IDLE_EPSILON = 0.002f
        private const val Y_SMOOTH_LERP = 0.35f
        private const val MAX_TIP_BEND_DP = 32f
        private const val BACKDROP_FADE_MS = 60L
        private const val SHADOW_RANGE_DP = 14f
        private const val SHADER_BOUNDS_PAD_DP = 26f
        private const val SQUIRCLE_BOUNDS_PAD_DP = 24f
        private const val BRIGHT_BG_LUM_THRESHOLD = 0.56f
        private const val DARK_INK_RGB = 0x15171A
        private const val LIGHT_INK_RGB = 0xF8FAFC
        private const val DARK_ACCENT_RGB = 0x1D4ED8
        private const val LUM_SMOOTH_TAU_MS = 180.0
        private const val LUM_CONVERGENCE_EPS = 0.004f
    }
}
