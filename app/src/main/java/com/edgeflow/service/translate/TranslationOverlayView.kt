package com.edgeflow.service.translate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 100% Touch-Through, Zero-GC, 120fps screen translation overlay driven by 2-stage
 * [ScanlineReconciler] updates:
 * - **Zero ghosting (`MISS_TOLERANCE = 0`)**: Unmatched blocks are removed immediately on stage 1.
 * - **Zero flicker + 120fps smooth snap**: Kept blocks stay locked at `alpha = 1.0f` and smoothly
 *   lerp (`0.42f` exponential convergence) across minor coordinate corrections (`<= 48px`).
 * - **Crisp `90ms` fade-in**: Newly translated blocks arrive in stage 2 with initial `driftY`
 *   already applied (zero warp) and fade in over `90ms` ease-out quad.
 * - **Smart unmeasured-scroll attenuation**: [setScrollMotionAttenuated] smoothly fades out (`80ms`)
 *   and back in (`90ms`) independently of long-press "Peek Original" (`setPeekOriginal`).
 * - **Viewport Clipping & Offscreen Pruning**: Clips rendering to
 *   `[contentClipTopPx, min(contentClipBottomPx, imeTopPx)]`.
 *
 * Pure Kotlin / Android framework only — ZERO Jetpack Compose imports.
 */
class TranslationOverlayView(context: Context) : View(context) {

    class LiveTrackedBlock(
        val id: Long,
        val originalText: String,
        val normalizedOriginal: String,
        var sourceBounds: BlockBounds,
        var rendered: RenderedTranslationBlock,
        var offsetX: Float = 0f,
        var offsetY: Float = 0f,
        var targetOffsetX: Float = offsetX,
        var targetOffsetY: Float = offsetY,
        var fadeStartUptimeMs: Long = 0L,
        var currentAlpha: Float = 1f
    ) {
        fun currentSourceBounds(): BlockBounds {
            val dx = targetOffsetX.roundToInt()
            val dy = targetOffsetY.roundToInt()
            return BlockBounds(
                left = sourceBounds.left + dx,
                top = sourceBounds.top + dy,
                right = sourceBounds.right + dx,
                bottom = sourceBounds.bottom + dy
            )
        }

        fun currentBgScreenBounds(): BlockBounds {
            return BlockBounds(
                left = (rendered.bgRect.left + targetOffsetX).roundToInt(),
                top = (rendered.bgRect.top + targetOffsetY).roundToInt(),
                right = (rendered.bgRect.right + targetOffsetX).roundToInt(),
                bottom = (rendered.bgRect.bottom + targetOffsetY).roundToInt()
            )
        }
    }

    private val density: Float = resources.displayMetrics.density
    private val offscreenPruneMarginPx: Float = OFFSCREEN_PRUNE_MARGIN_DP * density
    private var liveBlocks: MutableList<LiveTrackedBlock> = ArrayList(32)
    private var nextBlockId: Long = 1L

    // Pre-allocated paints & RectF — ZERO allocations inside onDraw()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }
    private val drawRect = RectF()

    private var contentClipTopPx: Int = 0
    private var contentClipBottomPx: Int = Int.MAX_VALUE
    private var imeVisible: Boolean = false
    private var imeTopPx: Int = 0
    private var isPeekingOriginal: Boolean = false

    // Independent unmeasured-scroll motion attenuation (80ms fade out / 90ms fade in, Zero-GC)
    private var scrollMotionAttenuated: Boolean = false
    private var scrollAttenCurrentAlpha: Float = 1f
    private var scrollAttenStartAlpha: Float = 1f
    private var scrollAttenTargetAlpha: Float = 1f
    private var scrollAttenStartMs: Long = 0L
    private var scrollAttenDurationMs: Long = 0L

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 1f
    }

    fun hasBlocks(): Boolean = liveBlocks.isNotEmpty()

    /**
     * Temporarily hides ONLY this overlay view during API 30-33 display fallback screenshot capture.
     * Never called on API 34+ (`takeScreenshotOfWindow`).
     */
    fun setFallbackCaptureHidden(hidden: Boolean) {
        val targetVis = if (hidden) INVISIBLE else VISIBLE
        if (visibility != targetVis) {
            visibility = targetVis
        }
    }

    /**
     * Smoothly fades out (`80ms`) during unmeasured zero-delta scrolls (e.g. opaque browser WebViews
     * where OS reports `deltaX == 0 && deltaY == 0`) so stale boxes never obscure scrolling text,
     * and smoothly restores (`90ms`) once freshly reconciled blocks arrive.
     * Managed independently of [setPeekOriginal].
     */
    fun setScrollMotionAttenuated(attenuated: Boolean) {
        if (scrollMotionAttenuated == attenuated) return
        scrollMotionAttenuated = attenuated
        val nowMs = SystemClock.uptimeMillis()
        scrollAttenCurrentAlpha = evaluateScrollAttenuationAlpha(nowMs)
        scrollAttenStartAlpha = scrollAttenCurrentAlpha
        scrollAttenTargetAlpha = if (attenuated) 0f else 1f
        scrollAttenStartMs = nowMs
        scrollAttenDurationMs = if (attenuated) ATTENUATE_OUT_DURATION_MS else ATTENUATE_IN_DURATION_MS
        postInvalidateOnAnimation()
    }

    /**
     * Updates the vertical content viewport clip bounds (e.g. below browser address bar and above
     * navigation bar) so scrolled translated text cleanly clips inside the scrollable content area.
     */
    fun setContentClipBounds(topPx: Int, bottomPx: Int) {
        val safeTop = topPx.coerceAtLeast(0)
        val safeBottom = if (bottomPx > safeTop) bottomPx else Int.MAX_VALUE
        if (contentClipTopPx == safeTop && contentClipBottomPx == safeBottom) return
        contentClipTopPx = safeTop
        contentClipBottomPx = safeBottom
        invalidate()
    }

    /**
     * Instant 120fps vertical scroll shift with out-of-viewport pruning.
     */
    fun applyScrollShift(dyPx: Float) {
        shiftAll(0f, dyPx)
    }

    /**
     * Instant 120fps scroll shift applied to all currently rendered blocks with zero opacity loss,
     * pruning blocks that have scrolled completely beyond viewport bounds.
     */
    fun shiftAll(dx: Float, dy: Float) {
        if (liveBlocks.isEmpty() || (dx == 0f && dy == 0f)) return
        val viewH = if (height > 0) height else resources.displayMetrics.heightPixels
        val clipTopF = contentClipTopPx.coerceIn(0, viewH).toFloat()
        val rawBottom = if (contentClipBottomPx == Int.MAX_VALUE) viewH else contentClipBottomPx
        val clipBottomF = rawBottom.coerceIn(contentClipTopPx.coerceIn(0, viewH), viewH).toFloat()

        var i = liveBlocks.size - 1
        while (i >= 0) {
            val block = liveBlocks[i]
            block.offsetX += dx
            block.offsetY += dy
            block.targetOffsetX += dx
            block.targetOffsetY += dy
            val top = block.rendered.bgRect.top + block.targetOffsetY
            val bottom = block.rendered.bgRect.bottom + block.targetOffsetY
            if (bottom < clipTopF - offscreenPruneMarginPx || top > clipBottomF + offscreenPruneMarginPx) {
                liveBlocks.removeAt(i)
            }
            i--
        }
        invalidate()
    }

    /**
     * Exports a snapshot of all currently active blocks (including live scroll offsets) for
     * single-cycle 1:1 matching in [ScanlineReconciler].
     */
    fun exportActiveTrackedBlocks(): List<ActiveTrackedBlock> {
        if (liveBlocks.isEmpty()) return emptyList()
        val out = ArrayList<ActiveTrackedBlock>(liveBlocks.size)
        for (i in 0 until liveBlocks.size) {
            val b = liveBlocks[i]
            out.add(
                ActiveTrackedBlock(
                    id = b.id,
                    originalText = b.originalText,
                    normalizedOriginal = b.normalizedOriginal,
                    currentBounds = b.currentSourceBounds()
                )
            )
        }
        return out
    }

    /**
     * Returns current screen-space bounding boxes (including active scroll `offsetX/Y`) of all
     * visible live blocks.
     */
    fun getOccupiedScreenBounds(): List<BlockBounds> {
        if (liveBlocks.isEmpty()) return emptyList()
        val out = ArrayList<BlockBounds>(liveBlocks.size)
        for (i in 0 until liveBlocks.size) {
            out.add(liveBlocks[i].currentBgScreenBounds())
        }
        return out
    }

    /**
     * **Stage 1 (0ms instant commit before network/ML Kit translation completes)**:
     * - Immediately drops every block in [removedIds] (`MISS_TOLERANCE = 0`).
     * - Smoothly snaps every block in [kept] to its fresh position + [driftY] (`<= 48px` uses
     *   120fps exponential convergence `lerp 0.42f`; larger deltas snap immediately).
     * - Protects any existing live block NOT in [removedIds] and NOT in [kept] (used by partial-band
     *   crop OCR so blocks outside the newly exposed scroll band remain completely untouched).
     */
    fun applyReconciledKeepAndRemove(
        kept: List<KeptBlockMatch>,
        removedIds: Set<Long>,
        driftY: Float = 0f,
        keptRecompiled: Map<Long, RenderedTranslationBlock> = emptyMap()
    ) {
        val keptById = HashMap<Long, KeptBlockMatch>(kept.size * 2)
        for (i in kept.indices) {
            keptById[kept[i].id] = kept[i]
        }

        val nextList = ArrayList<LiveTrackedBlock>(liveBlocks.size)
        for (i in 0 until liveBlocks.size) {
            val block = liveBlocks[i]
            if (removedIds.contains(block.id)) continue
            val match = keptById[block.id]
            if (match != null) {
                val recompiled = keptRecompiled[block.id]
                if (recompiled != null) {
                    block.rendered = recompiled
                    block.sourceBounds = match.freshBlock.bounds
                    block.offsetX = 0f
                    block.offsetY = driftY
                    block.targetOffsetX = 0f
                    block.targetOffsetY = driftY
                } else {
                    val desiredX = (match.freshBlock.bounds.left - block.sourceBounds.left).toFloat()
                    val desiredY = (match.freshBlock.bounds.top - block.sourceBounds.top).toFloat() + driftY
                    val diffX = abs(desiredX - block.offsetX)
                    val diffY = abs(desiredY - block.offsetY)
                    block.targetOffsetX = desiredX
                    block.targetOffsetY = desiredY
                    if (diffX > SMOOTH_SNAP_THRESHOLD_PX || diffY > SMOOTH_SNAP_THRESHOLD_PX) {
                        block.offsetX = desiredX
                        block.offsetY = desiredY
                    }
                }
                block.fadeStartUptimeMs = 0L
                block.currentAlpha = 1f
            }
            nextList.add(block)
        }

        liveBlocks = nextList
        postInvalidateOnAnimation()
    }

    /**
     * **Stage 2 (arrival of newly translated blocks)**:
     * Appends [newlyAdded] blocks with [initialDriftY] already compensated (zero post-scroll warp)
     * and fades them in cleanly over `90ms` ease-out quad when [animateNewBlocks] is true.
     */
    fun appendNewlyTranslatedBlocks(
        newlyAdded: List<Pair<FilteredOcrBlock, RenderedTranslationBlock>>,
        initialDriftY: Float = 0f,
        animateNewBlocks: Boolean = true
    ) {
        if (newlyAdded.isEmpty()) return
        val nowMs = SystemClock.uptimeMillis()
        for (i in newlyAdded.indices) {
            val (filtered, rendered) = newlyAdded[i]
            val id = nextBlockId++
            liveBlocks.add(
                LiveTrackedBlock(
                    id = id,
                    originalText = filtered.text,
                    normalizedOriginal = StructuredNodeTextExtractor.normalizeKey(filtered.text),
                    sourceBounds = filtered.bounds,
                    rendered = rendered,
                    offsetX = 0f,
                    offsetY = initialDriftY,
                    targetOffsetX = 0f,
                    targetOffsetY = initialDriftY,
                    fadeStartUptimeMs = if (animateNewBlocks) nowMs else 0L,
                    currentAlpha = if (animateNewBlocks) 0f else 1f
                )
            )
        }
        postInvalidateOnAnimation()
    }

    /**
     * Single-cycle 1:1 reconciliation commit combining Stage 1 + Stage 2 (retained for compatibility).
     */
    fun applyReconciledUpdate(
        kept: List<KeptBlockMatch>,
        newlyAdded: List<Pair<FilteredOcrBlock, RenderedTranslationBlock>>,
        removedIds: Set<Long>,
        animateNewBlocks: Boolean = true,
        keptRecompiled: Map<Long, RenderedTranslationBlock> = emptyMap(),
        initialDriftY: Float = 0f
    ) {
        applyReconciledKeepAndRemove(
            kept = kept,
            removedIds = removedIds,
            driftY = initialDriftY,
            keptRecompiled = keptRecompiled
        )
        appendNewlyTranslatedBlocks(
            newlyAdded = newlyAdded,
            initialDriftY = initialDriftY,
            animateNewBlocks = animateNewBlocks
        )
    }

    /**
     * Backwards-compatible bulk replace/merge convenience helper.
     */
    fun mergeBlocks(
        incoming: List<RenderedTranslationBlock>,
        removeUnseen: Boolean,
        animateNewBlocks: Boolean = true
    ) {
        val freshBlocks = incoming.map { r ->
            FilteredOcrBlock(
                bounds = r.sourceBounds,
                text = r.blockKey,
                lineCount = 1,
                averageLineHeightPx = r.averageLineHeightPx
            )
        }
        val active = exportActiveTrackedBlocks()
        val rec = ScanlineReconciler.reconcile(active, freshBlocks)
        val renderedByBounds = HashMap<BlockBounds, RenderedTranslationBlock>(incoming.size * 2)
        for (r in incoming) renderedByBounds[r.sourceBounds] = r
        val addedPairs = rec.toTranslate.mapNotNull { fb ->
            renderedByBounds[fb.bounds]?.let { fb to it }
        }
        val removed = if (removeUnseen) rec.removedIds else emptySet()
        applyReconciledUpdate(
            kept = rec.kept,
            newlyAdded = addedPairs,
            removedIds = removed,
            animateNewBlocks = animateNewBlocks
        )
    }

    fun clearBlocks() {
        scrollMotionAttenuated = false
        scrollAttenCurrentAlpha = 1f
        scrollAttenStartAlpha = 1f
        scrollAttenTargetAlpha = 1f
        scrollAttenStartMs = 0L
        scrollAttenDurationMs = 0L
        if (liveBlocks.isEmpty()) return
        liveBlocks.clear()
        invalidate()
    }

    fun updateImeState(visible: Boolean, imeTop: Int) {
        if (imeVisible == visible && imeTopPx == imeTop) return
        imeVisible = visible
        imeTopPx = imeTop
        invalidate()
    }

    fun setPeekOriginal(peeking: Boolean) {
        if (isPeekingOriginal == peeking) return
        isPeekingOriginal = peeking
        alpha = if (isPeekingOriginal) 0f else 1f
    }

    private fun evaluateScrollAttenuationAlpha(nowMs: Long): Float {
        if (scrollAttenDurationMs <= 0L || scrollAttenStartMs <= 0L) {
            return scrollAttenTargetAlpha
        }
        val elapsed = (nowMs - scrollAttenStartMs).coerceAtLeast(0L)
        if (elapsed >= scrollAttenDurationMs) {
            scrollAttenDurationMs = 0L
            scrollAttenStartMs = 0L
            return scrollAttenTargetAlpha
        }
        val t = (elapsed.toFloat() / scrollAttenDurationMs.toFloat()).coerceIn(0f, 1f)
        val inv = 1f - t
        val eased = 1f - inv * inv
        return (scrollAttenStartAlpha + (scrollAttenTargetAlpha - scrollAttenStartAlpha) * eased)
            .coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        if (liveBlocks.isEmpty()) return
        if (isPeekingOriginal || alpha <= 0.004f) return

        val viewW = width
        val viewH = height
        if (viewW <= 0 || viewH <= 0) return

        val nowMs = SystemClock.uptimeMillis()
        var hasAnimatingBlocks = false

        val motionAlpha = evaluateScrollAttenuationAlpha(nowMs)
        scrollAttenCurrentAlpha = motionAlpha
        if (scrollAttenDurationMs > 0L) {
            hasAnimatingBlocks = true
        }
        if (motionAlpha <= 0.004f) {
            if (hasAnimatingBlocks) postInvalidateOnAnimation()
            return
        }

        val clipTop = contentClipTopPx.coerceIn(0, viewH)
        val rawBottom = if (contentClipBottomPx == Int.MAX_VALUE) viewH else contentClipBottomPx
        val imeLimit = if (imeVisible && imeTopPx > 0) imeTopPx else viewH
        val clipBottom = min(rawBottom.coerceIn(clipTop, viewH), imeLimit).coerceAtLeast(clipTop)
        if (clipBottom <= clipTop) return

        val savedRoot = canvas.save()
        canvas.clipRect(0, clipTop, viewW, clipBottom)

        val clipTopF = clipTop.toFloat()
        val clipBottomF = clipBottom.toFloat()
        val viewWF = viewW.toFloat()

        for (i in 0 until liveBlocks.size) {
            val live = liveBlocks[i]

            // 120fps smooth exponential convergence for minor coordinate snap corrections
            val dxRem = live.targetOffsetX - live.offsetX
            val dyRem = live.targetOffsetY - live.offsetY
            if (abs(dxRem) > SNAP_EPSILON_PX || abs(dyRem) > SNAP_EPSILON_PX) {
                live.offsetX += dxRem * SMOOTH_SNAP_LERP_FACTOR
                live.offsetY += dyRem * SMOOTH_SNAP_LERP_FACTOR
                hasAnimatingBlocks = true
            } else {
                live.offsetX = live.targetOffsetX
                live.offsetY = live.targetOffsetY
            }

            val block = live.rendered
            val left = block.bgRect.left + live.offsetX
            val top = block.bgRect.top + live.offsetY
            val right = block.bgRect.right + live.offsetX
            val bottom = block.bgRect.bottom + live.offsetY

            if (bottom <= clipTopF || top >= clipBottomF || right <= 0f || left >= viewWF) {
                continue
            }

            val blockFadeAlpha = if (live.fadeStartUptimeMs > 0L) {
                val computed = computeEaseOutFadeAlpha(live.fadeStartUptimeMs, nowMs)
                live.currentAlpha = computed
                if (computed >= 0.999f) {
                    live.fadeStartUptimeMs = 0L
                    live.currentAlpha = 1f
                    1f
                } else {
                    hasAnimatingBlocks = true
                    computed
                }
            } else {
                live.currentAlpha = 1f
                1f
            }

            val effectiveAlpha = blockFadeAlpha * motionAlpha
            if (effectiveAlpha <= 0.004f) continue

            drawRect.set(left, top, right, bottom)

            bgPaint.color = scaleColorAlpha(block.bgColor, effectiveAlpha)
            canvas.drawRoundRect(
                drawRect,
                block.cornerRadiusPx,
                block.cornerRadiusPx,
                bgPaint
            )

            if (block.hasBorder) {
                borderPaint.color = scaleColorAlpha(block.borderColor, effectiveAlpha)
                canvas.drawRoundRect(
                    drawRect,
                    block.cornerRadiusPx,
                    block.cornerRadiusPx,
                    borderPaint
                )
            }

            canvas.save()
            canvas.clipRect(drawRect)
            canvas.translate(left + block.textOffsetX, top + block.textOffsetY)
            if (effectiveAlpha < 0.995f) {
                val layoutPaint = block.layout.paint
                val prevAlpha = layoutPaint.alpha
                layoutPaint.alpha = (prevAlpha * effectiveAlpha).toInt().coerceIn(0, 255)
                block.layout.draw(canvas)
                layoutPaint.alpha = prevAlpha
            } else {
                block.layout.draw(canvas)
            }
            canvas.restore()
        }

        canvas.restoreToCount(savedRoot)

        if (hasAnimatingBlocks) {
            postInvalidateOnAnimation()
        }
    }

    private fun computeEaseOutFadeAlpha(startMs: Long, nowMs: Long): Float {
        if (startMs <= 0L) return 1f
        val elapsed = (nowMs - startMs).coerceAtLeast(0L)
        if (elapsed >= FADE_IN_DURATION_MS) return 1f
        val t = elapsed.toFloat() / FADE_IN_DURATION_MS.toFloat()
        val inv = 1f - t
        return (1f - inv * inv).coerceIn(0f, 1f)
    }

    private fun scaleColorAlpha(color: Int, factor: Float): Int {
        if (factor >= 0.995f) return color
        val baseA = Color.alpha(color)
        val scaledA = (baseA * factor).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (scaledA shl 24)
    }

    companion object {
        private const val FADE_IN_DURATION_MS = 90L
        private const val ATTENUATE_OUT_DURATION_MS = 80L
        private const val ATTENUATE_IN_DURATION_MS = 90L
        private const val SMOOTH_SNAP_THRESHOLD_PX = 48f
        private const val SMOOTH_SNAP_LERP_FACTOR = 0.42f
        private const val SNAP_EPSILON_PX = 0.35f
        private const val OFFSCREEN_PRUNE_MARGIN_DP = 64f
    }
}
