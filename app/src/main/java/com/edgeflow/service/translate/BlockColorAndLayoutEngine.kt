package com.edgeflow.service.translate

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.edgeflow.data.LiveTranslateRenderStyle
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Immutable, pre-compiled visual translation block ready for zero-GC 120fps `onDraw(Canvas)`.
 * Carries [blockKey] and [sourceBounds] for Spatial Anchor Matching & single-cycle reconciliation.
 */
data class RenderedTranslationBlock(
    val blockKey: String,
    val sourceBounds: BlockBounds,
    val bgRect: RectF,
    val bgColor: Int,
    val borderColor: Int,
    val hasBorder: Boolean,
    val cornerRadiusPx: Float,
    val layout: StaticLayout,
    val textOffsetX: Float,
    val textOffsetY: Float,
    val averageLineHeightPx: Int = sourceBounds.height
)

/**
 * Background-thread (`Dispatchers.Default`) color extractor (24-pt background median + 15-pt WCAG
 * ink extractor) & 2D non-overlapping [StaticLayout] pre-compiler for authentic in-place screen
 * translation rendering.
 */
class BlockColorAndLayoutEngine(
    private val density: Float,
    private val scaledDensity: Float
) {
    fun compileBlocks(
        blocks: List<TranslatedBlock>,
        captureBitmap: Bitmap?,
        offsetX: Int,
        offsetY: Int,
        captureScale: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
        renderStyle: LiveTranslateRenderStyle,
        overlayAlpha: Float,
        backgroundColorSampler: ((BlockBounds) -> Int?)? = null,
        inkColorSampler: ((BlockBounds, Int) -> Int?)? = null
    ): List<RenderedTranslationBlock> {
        if (blocks.isEmpty()) return emptyList()

        val minWidthPx = (MIN_BLOCK_WIDTH_DP * density).roundToInt()
        val screenMarginPx = SCREEN_MARGIN_DP * density
        val cornerRadiusPx = CORNER_RADIUS_DP * density
        val clampedAlpha = overlayAlpha.coerceIn(0.55f, 1.0f)
        val safeScaledDensity = scaledDensity.coerceAtLeast(0.5f)

        val pageMedianLineHeightPx = computeMedianLineHeight(blocks)
        val compiled = ArrayList<RenderedTranslationBlock>(blocks.size)

        for (block in blocks) {
            val sb = block.screenBounds
            val avgLineH = block.averageLineHeightPx.coerceAtLeast(1)
            val padHPx = (avgLineH * 0.16f).coerceIn(2f * density, 5f * density)
            val padVPx = (avgLineH * 0.12f).coerceIn(1.5f * density, 4f * density)

            val origW = sb.width.toFloat().coerceAtLeast(minWidthPx.toFloat())
            val origH = sb.height.toFloat().coerceAtLeast(12f * density)

            val maxBoxW = (screenWidthPx - screenMarginPx * 2f).coerceAtLeast(minWidthPx.toFloat())
            // Strict width fidelity: hug original bounds + proportional padding
            val desiredW = (origW + padHPx * 2f).coerceIn(minWidthPx.toFloat(), maxBoxW)

            val rawLeft = (sb.left - padHPx)
                .coerceIn(screenMarginPx, (screenWidthPx - screenMarginPx - desiredW).coerceAtLeast(screenMarginPx))
            val innerTextWidth = (desiredW - padHPx * 2f).roundToInt().coerceAtLeast((16f * density).roundToInt())

            val targetFitHeight = max(origH * TARGET_HEIGHT_RATIO, origH + 4f * density)
            val hardMaxHeight = max(origH * HARD_MAX_HEIGHT_RATIO, origH + 6f * density)

            val estimatedSp = ((avgLineH * 0.76f) / safeScaledDensity).coerceIn(7.0f, 22.0f)
            val maxSpForBlock = if (block.lineCount >= 2) {
                estimatedSp.coerceAtMost(17.0f)
            } else {
                estimatedSp.coerceAtMost(22.0f)
            }
            val isHeading = block.lineCount == 1 &&
                pageMedianLineHeightPx > 0 &&
                avgLineH >= pageMedianLineHeightPx * HEADING_HEIGHT_RATIO

            val bgStyle = resolveBackgroundStyle(
                captureBitmap = captureBitmap,
                backgroundColorSampler = backgroundColorSampler,
                inkColorSampler = inkColorSampler,
                screenBounds = sb,
                offsetX = offsetX,
                offsetY = offsetY,
                captureScale = captureScale,
                renderStyle = renderStyle,
                overlayAlpha = clampedAlpha
            )

            val textPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                color = bgStyle.textColor
                typeface = if (isHeading) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }

            val layout = buildBestFitLayout(
                text = block.translatedText,
                paint = textPaint,
                widthPx = innerTextWidth,
                targetHeightPx = targetFitHeight,
                hardMaxHeightPx = hardMaxHeight,
                maxFontSp = maxSpForBlock
            )

            val minCoverLeft = (sb.left - padHPx).coerceAtLeast(screenMarginPx)
            val minCoverRight = (sb.right + padHPx).coerceAtMost(screenWidthPx - screenMarginPx)
            val minCoverTop = (sb.top - padVPx).coerceAtLeast(screenMarginPx)
            val minCoverBottom = (sb.bottom + padVPx).coerceAtMost(screenHeightPx - screenMarginPx)

            val finalLeft = min(rawLeft, minCoverLeft).coerceAtLeast(screenMarginPx)
            val finalRight = max(finalLeft + desiredW, minCoverRight)
                .coerceAtMost(screenWidthPx - screenMarginPx)

            val cappedBoxH = min(
                max(origH + padVPx * 2f, layout.height + padVPx * 2f),
                hardMaxHeight + padVPx * 2f
            )
            val finalTop = minCoverTop
                .coerceIn(screenMarginPx, (screenHeightPx - screenMarginPx - cappedBoxH).coerceAtLeast(screenMarginPx))
            val finalBottom = max(finalTop + cappedBoxH, minCoverBottom)
                .coerceAtMost(screenHeightPx - screenMarginPx)

            val bgRect = RectF(finalLeft, finalTop, finalRight, finalBottom)
            val textOffsetX = padHPx
            val textOffsetY = ((bgRect.height() - layout.height) * 0.5f).coerceAtLeast(padVPx * 0.4f)

            compiled.add(
                RenderedTranslationBlock(
                    blockKey = TranslationBlockFilter.normalizeText(block.originalText),
                    sourceBounds = sb,
                    bgRect = bgRect,
                    bgColor = bgStyle.bgColor,
                    borderColor = bgStyle.borderColor,
                    hasBorder = bgStyle.hasBorder,
                    cornerRadiusPx = cornerRadiusPx,
                    layout = layout,
                    textOffsetX = textOffsetX,
                    textOffsetY = textOffsetY,
                    averageLineHeightPx = avgLineH
                )
            )
        }

        return enforceNonOverlappingRects(compiled)
    }

    private fun computeMedianLineHeight(blocks: List<TranslatedBlock>): Int {
        if (blocks.isEmpty()) return 0
        val heights = IntArray(blocks.size) { blocks[it].averageLineHeightPx.coerceAtLeast(1) }
        heights.sort()
        return heights[heights.size / 2]
    }

    /**
     * 2D overlap resolver: clamps colliding block pairs strictly at their midpoint (`midY` for
     * vertical overlaps, `midX` for horizontal overlaps) with a `1dp` gap so NO two rendered
     * rectangles overlap on screen (`0.0px` overlap).
     */
    private fun enforceNonOverlappingRects(
        input: MutableList<RenderedTranslationBlock>
    ): List<RenderedTranslationBlock> {
        if (input.size <= 1) return input
        input.sortWith(compareBy<RenderedTranslationBlock> { it.bgRect.top }.thenBy { it.bgRect.left })
        val halfGapPx = (OVERLAP_GAP_DP * density) * 0.5f

        for (pass in 0 until 2) {
            var anyAdjusted = false
            for (i in 0 until input.size) {
                for (j in i + 1 until input.size) {
                    val a = input[i]
                    val b = input[j]
                    val ra = a.bgRect
                    val rb = b.bgRect

                    val overlapX = min(ra.right, rb.right) - max(ra.left, rb.left)
                    val overlapY = min(ra.bottom, rb.bottom) - max(ra.top, rb.top)
                    if (overlapX <= 0f || overlapY <= 0f) continue

                    anyAdjusted = true
                    if (overlapY <= overlapX) {
                        // Resolve vertically at midpoint
                        val topFirst = ra.centerY() <= rb.centerY()
                        val upper = if (topFirst) a else b
                        val lower = if (topFirst) b else a
                        val midY = (upper.bgRect.bottom + lower.bgRect.top) * 0.5f
                        val newUpperBottom = max(upper.bgRect.top + MIN_CLAMPED_DIM_PX, midY - halfGapPx)
                        val newLowerTop = min(lower.bgRect.bottom - MIN_CLAMPED_DIM_PX, midY + halfGapPx)

                        val upperRect = RectF(upper.bgRect.left, upper.bgRect.top, upper.bgRect.right, newUpperBottom)
                        val lowerRect = RectF(lower.bgRect.left, newLowerTop, lower.bgRect.right, lower.bgRect.bottom)
                        val upperIdx = if (topFirst) i else j
                        val lowerIdx = if (topFirst) j else i
                        input[upperIdx] = upper.copy(
                            bgRect = upperRect,
                            textOffsetY = ((upperRect.height() - upper.layout.height) * 0.5f).coerceAtLeast(1f)
                        )
                        input[lowerIdx] = lower.copy(
                            bgRect = lowerRect,
                            textOffsetY = ((lowerRect.height() - lower.layout.height) * 0.5f).coerceAtLeast(1f)
                        )
                    } else {
                        // Resolve horizontally at midpoint
                        val leftFirst = ra.centerX() <= rb.centerX()
                        val leftBlk = if (leftFirst) a else b
                        val rightBlk = if (leftFirst) b else a
                        val midX = (leftBlk.bgRect.right + rightBlk.bgRect.left) * 0.5f
                        val newLeftRight = max(leftBlk.bgRect.left + MIN_CLAMPED_DIM_PX, midX - halfGapPx)
                        val newRightLeft = min(rightBlk.bgRect.right - MIN_CLAMPED_DIM_PX, midX + halfGapPx)

                        val leftRect = RectF(leftBlk.bgRect.left, leftBlk.bgRect.top, newLeftRight, leftBlk.bgRect.bottom)
                        val rightRect = RectF(newRightLeft, rightBlk.bgRect.top, rightBlk.bgRect.right, rightBlk.bgRect.bottom)
                        val leftIdx = if (leftFirst) i else j
                        val rightIdx = if (leftFirst) j else i
                        input[leftIdx] = leftBlk.copy(
                            bgRect = leftRect,
                            textOffsetX = min(leftBlk.textOffsetX, (leftRect.width() * 0.1f).coerceAtLeast(1f))
                        )
                        input[rightIdx] = rightBlk.copy(
                            bgRect = rightRect,
                            textOffsetX = min(rightBlk.textOffsetX, (rightRect.width() * 0.1f).coerceAtLeast(1f))
                        )
                    }
                }
            }
            if (!anyAdjusted) break
        }
        return input
    }

    private fun buildBestFitLayout(
        text: String,
        paint: TextPaint,
        widthPx: Int,
        targetHeightPx: Float,
        hardMaxHeightPx: Float,
        maxFontSp: Float
    ): StaticLayout {
        var lowSp = MIN_FONT_SP
        var highSp = maxFontSp.coerceAtLeast(MIN_FONT_SP)
        var bestLayout: StaticLayout? = null

        for (step in 0 until BINARY_SEARCH_STEPS) {
            val midSp = (lowSp + highSp) * 0.5f
            paint.textSize = midSp * scaledDensity
            val candidate = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.06f)
                .build()

            if (candidate.height <= targetHeightPx) {
                bestLayout = candidate
                lowSp = midSp
            } else {
                highSp = midSp
            }
        }

        if (bestLayout != null) return bestLayout

        paint.textSize = MIN_FONT_SP * scaledDensity
        val probe = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.04f)
            .build()

        if (probe.height <= hardMaxHeightPx) return probe

        val singleLineH = max(probe.height.toFloat() / probe.lineCount.coerceAtLeast(1), 1f)
        val allowedLines = floor(hardMaxHeightPx / singleLineH).toInt().coerceAtLeast(1)
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.04f)
            .setMaxLines(allowedLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private data class ResolvedBlockStyle(
        val bgColor: Int,
        val borderColor: Int,
        val hasBorder: Boolean,
        val textColor: Int
    )

    private fun resolveBackgroundStyle(
        captureBitmap: Bitmap?,
        backgroundColorSampler: ((BlockBounds) -> Int?)?,
        inkColorSampler: ((BlockBounds, Int) -> Int?)?,
        screenBounds: BlockBounds,
        offsetX: Int,
        offsetY: Int,
        captureScale: Float,
        renderStyle: LiveTranslateRenderStyle,
        overlayAlpha: Float
    ): ResolvedBlockStyle {
        if (renderStyle == LiveTranslateRenderStyle.FROSTED_CARD) {
            return frostedSmokeStyle(overlayAlpha)
        }

        val sampledRgb = when {
            captureBitmap != null && !captureBitmap.isRecycled ->
                WindowCleanCapturer.estimateBackgroundFromBitmap(
                    bitmap = captureBitmap,
                    screenBounds = screenBounds,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    captureScale = captureScale
                )
            backgroundColorSampler != null -> backgroundColorSampler(screenBounds)
            else -> null
        } ?: return frostedSmokeStyle(overlayAlpha)

        val repR = Color.red(sampledRgb)
        val repG = Color.green(sampledRgb)
        val repB = Color.blue(sampledRgb)

        val alphaInt = (OPAQUE_MASK_FACTOR * overlayAlpha * 255f)
            .roundToInt()
            .coerceIn(MIN_INPLACE_BG_ALPHA, 255)
        val bgColor = Color.argb(alphaInt, repR, repG, repB)

        val sampledInk = when {
            captureBitmap != null && !captureBitmap.isRecycled ->
                WindowCleanCapturer.estimateInkColorFromBitmap(
                    bitmap = captureBitmap,
                    screenBounds = screenBounds,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    captureScale = captureScale,
                    bgRgb = sampledRgb
                )
            inkColorSampler != null -> inkColorSampler(screenBounds, sampledRgb)
            else -> null
        }

        val textColor = sampledInk ?: run {
            val relLum = (0.2126f * repR + 0.7152f * repG + 0.0722f * repB) / 255f
            if (relLum > LIGHT_BG_LUM_THRESHOLD) COLOR_TEXT_ON_LIGHT else COLOR_TEXT_ON_DARK
        }

        return ResolvedBlockStyle(
            bgColor = bgColor,
            borderColor = Color.TRANSPARENT,
            hasBorder = false,
            textColor = textColor
        )
    }

    private fun frostedSmokeStyle(overlayAlpha: Float): ResolvedBlockStyle {
        val alphaInt = (OPAQUE_MASK_FACTOR * overlayAlpha * 244f)
            .roundToInt()
            .coerceIn(MIN_FROSTED_BG_ALPHA, 250)
        return ResolvedBlockStyle(
            bgColor = Color.argb(alphaInt, 0x12, 0x16, 0x20),
            borderColor = COLOR_FROSTED_RIM,
            hasBorder = true,
            textColor = COLOR_TEXT_ON_DARK
        )
    }

    companion object {
        private const val MIN_BLOCK_WIDTH_DP = 20f
        private const val SCREEN_MARGIN_DP = 4f
        private const val CORNER_RADIUS_DP = 5f
        private const val OVERLAP_GAP_DP = 1f
        private const val MIN_CLAMPED_DIM_PX = 8f
        private const val MIN_FONT_SP = 6.0f
        private const val BINARY_SEARCH_STEPS = 7
        private const val TARGET_HEIGHT_RATIO = 1.08f
        private const val HARD_MAX_HEIGHT_RATIO = 1.18f
        private const val HEADING_HEIGHT_RATIO = 1.25f
        private const val OPAQUE_MASK_FACTOR = 0.99f
        private const val MIN_INPLACE_BG_ALPHA = 248
        private const val MIN_FROSTED_BG_ALPHA = 224
        private const val LIGHT_BG_LUM_THRESHOLD = 0.54f
        private const val COLOR_TEXT_ON_LIGHT = 0xFF15171A.toInt()
        private const val COLOR_TEXT_ON_DARK = 0xFFF8FAFC.toInt()
        private const val COLOR_FROSTED_RIM = 0x38FFFFFF
    }
}
