package com.edgeflow.settings

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edgeflow.data.EdgeAction
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.EdgeSide
import com.edgeflow.data.FeedbackStyle
import com.edgeflow.data.QuickArcTriggerMethod
import com.edgeflow.data.QuickArcZoneSide
import com.edgeflow.data.SecondaryTriggerMode
import com.edgeflow.data.VisualTheme
import com.example.R
import com.example.ui.theme.LocalEdgeFlowColors
import com.example.ui.theme.NumericSmallStyle
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

enum class GuidelineFocus {
    NONE,
    SIDE_STAGE2,
    SIDE_STAGE3,
    BOTTOM_STAGE2,
    BOTTOM_STAGE3
}

/**
 * Compact sticky interactive smartphone preview designed to remain pinned at the top of the
 * Zones screen (~28% viewport height) while sliders scroll smoothly underneath.
 *
 * Features:
 * - Real-time left/right/bottom trigger zones, guide opacity, and dedicated Quick Arc strips
 * - Stage 2 dashed guidelines (primary hue, up to 75%) and Stage 3 dashed guidelines (accent hue,
 *   up to 85%, automatically shown ONLY when the corresponding Stage 3 action is assigned)
 * - Live interactive drag sandbox inside the phone frame: 3-stage side droplet AND restrained
 *   rubber-band bottom pill lift (no droplet on bottom!)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZonePreviewCanvas(
    config: EdgeConfig,
    actionLabelResolver: (EdgeAction) -> String,
    highlightGuideline: GuidelineFocus = GuidelineFocus.NONE,
    modifier: Modifier = Modifier
) {
    var activeSandboxSide by remember { mutableStateOf<EdgeSide?>(null) }
    var isDedicatedArcDrag by remember { mutableStateOf(false) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragCurrent by remember { mutableStateOf(Offset.Zero) }
    var dragPull by remember { mutableFloatStateOf(0f) }
    var previewStageLevel by remember { mutableIntStateOf(0) }
    var previewAction by remember { mutableStateOf<EdgeAction?>(null) }
    var lastTriggeredLabel by remember { mutableStateOf<String?>(null) }

    val animatedPull by animateFloatAsState(
        targetValue = dragPull,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "preview_pull"
    )

    val extendedColors = LocalEdgeFlowColors.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val stage3Color = extendedColors.dedicatedZoneAccent
    val glassFillBase = when (config.visualTheme) {
        VisualTheme.LIGHT_GLASS -> Color(0xFFF4F6FA)
        else -> Color(0xFF14161A)
    }
    val glassRimColor = when (config.visualTheme) {
        VisualTheme.LIGHT_GLASS -> Color(0xFF15171A)
        VisualTheme.MATERIAL_YOU -> primaryColor
        else -> Color.White
    }
    val glyphColor = when (config.visualTheme) {
        VisualTheme.LIGHT_GLASS -> Color(0xFF15171A)
        else -> Color.White
    }

    val surfaceHigh = MaterialTheme.colorScheme.surfaceVariant
    val bgColor = MaterialTheme.colorScheme.background
    val outlineColor = MaterialTheme.colorScheme.outline
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("zone_preview_card"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Compact interactive phone mockup (pinned ~186dp height)
        Box(
            modifier = Modifier
                .width(102.dp)
                .aspectRatio(9f / 16.4f)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(surfaceHigh.copy(alpha = 0.65f), bgColor)
                    )
                )
                .border(
                    width = 1.4.dp,
                    color = outlineColor.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(20.dp)
                )
                .pointerInput(config) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val dedicatedActive = config.quickArcTriggerMethod == QuickArcTriggerMethod.DEDICATED_ZONE
                            val arcTop = (h - h * config.quickArcZoneBottomOffsetPercent - h * config.quickArcZoneHeightPercent)
                                .coerceAtLeast(h * 0.05f)
                            val arcBottom = arcTop + h * config.quickArcZoneHeightPercent
                            val inArcY = startOffset.y in arcTop..arcBottom
                            val leftDedicated = dedicatedActive &&
                                config.quickArcZoneSide != QuickArcZoneSide.RIGHT_ONLY &&
                                startOffset.x < w * 0.30f && inArcY
                            val rightDedicated = dedicatedActive &&
                                config.quickArcZoneSide != QuickArcZoneSide.LEFT_ONLY &&
                                startOffset.x > w * 0.70f && inArcY

                            val bottomHitW = w * config.bottomBarWidthPercent
                            val bottomHitH = ((config.bottomBarHeightDp / 48f) * (h * 0.13f))
                                .coerceIn(h * 0.04f, h * 0.14f)
                            val bottomMarginPx = ((config.bottomBarBottomOffsetDp / 32f) * (h * 0.09f))
                                .coerceIn(0f, h * 0.11f)
                            val maxHalfShift = ((w - bottomHitW) * 0.5f).coerceAtLeast(0f)
                            val bottomShift = (w * config.bottomBarHorizontalOffsetPercent)
                                .coerceIn(-maxHalfShift, maxHalfShift)
                            val bottomLeftX = (w - bottomHitW) * 0.5f + bottomShift
                            val bottomTopY = (h - bottomMarginPx - bottomHitH).coerceAtLeast(h * 0.65f)
                            val inBottomZone = config.bottomEnabled &&
                                startOffset.y >= (bottomTopY - h * 0.06f) &&
                                startOffset.x in (bottomLeftX - w * 0.06f)..(bottomLeftX + bottomHitW + w * 0.06f)

                            val side = when {
                                leftDedicated -> EdgeSide.LEFT
                                rightDedicated -> EdgeSide.RIGHT
                                config.leftEnabled && startOffset.x < w * 0.30f -> EdgeSide.LEFT
                                config.rightEnabled && startOffset.x > w * 0.70f -> EdgeSide.RIGHT
                                inBottomZone -> EdgeSide.BOTTOM
                                else -> null
                            }
                            activeSandboxSide = side
                            isDedicatedArcDrag = leftDedicated || rightDedicated
                            dragStart = startOffset
                            dragCurrent = startOffset
                            dragPull = 0f
                            previewStageLevel = 0
                            previewAction = null
                        },
                        onDragEnd = {
                            previewAction?.let { act ->
                                if (!act.isNone) {
                                    lastTriggeredLabel = actionLabelResolver(act)
                                }
                            }
                            dragPull = 0f
                            isDedicatedArcDrag = false
                            previewStageLevel = 0
                            previewAction = null
                        },
                        onDragCancel = {
                            dragPull = 0f
                            isDedicatedArcDrag = false
                            previewStageLevel = 0
                            previewAction = null
                        },
                        onDrag = { change, _ ->
                            val side = activeSandboxSide ?: return@detectDragGestures
                            change.consume()
                            dragCurrent = change.position
                            val dx = dragCurrent.x - dragStart.x
                            val dy = dragCurrent.y - dragStart.y
                            val thresholdPx = size.width * 0.12f

                            if (isDedicatedArcDrag) {
                                val inward = if (side == EdgeSide.LEFT) dx else -dx
                                dragPull = (inward.coerceAtLeast(0f) / (size.width * 0.24f)).coerceIn(0f, 1.25f)
                                if (inward >= size.width * 0.06f) {
                                    previewStageLevel = 2
                                    previewAction = EdgeAction.OPEN_QUICK_MENU
                                } else {
                                    previewStageLevel = 0
                                    previewAction = null
                                }
                            } else if (side == EdgeSide.BOTTOM) {
                                val up = (-dy).coerceAtLeast(0f)
                                val usesDistance = config.bottomTriggerMode != SecondaryTriggerMode.HOLD
                                val stage2Px = size.height * config.bottomStage2DistanceRatio
                                val stage3Px = size.height * config.bottomStage3DistanceRatio
                                dragPull = (up / (size.height * 0.25f)).coerceIn(0f, 1.25f)
                                when {
                                    usesDistance && !config.bottomStage3Action.isNone && up >= stage3Px -> {
                                        previewStageLevel = 3
                                        previewAction = config.bottomStage3Action
                                    }
                                    usesDistance && !config.bottomSwipeHoldAction.isNone && up >= stage2Px -> {
                                        previewStageLevel = 2
                                        previewAction = config.bottomSwipeHoldAction
                                    }
                                    up >= thresholdPx -> {
                                        previewStageLevel = 1
                                        previewAction = config.bottomSwipeUpAction
                                    }
                                    else -> {
                                        previewStageLevel = 0
                                        previewAction = null
                                    }
                                }
                            } else {
                                val inward = if (side == EdgeSide.LEFT) dx else -dx
                                val usesDistance = config.sideTriggerMode != SecondaryTriggerMode.HOLD
                                val stage2Px = size.width * config.sideStage2DistanceRatio
                                val stage3Px = size.width * config.sideStage3DistanceRatio
                                val stage3Action = config.stage3ActionFor(side)
                                dragPull = (inward.coerceAtLeast(0f) / (size.width * 0.34f)).coerceIn(0f, 1.25f)
                                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                                if (dist >= thresholdPx && inward >= thresholdPx * 0.5f) {
                                    val deg = Math.toDegrees(
                                        atan2((-dy).toDouble(), inward.coerceAtLeast(1f).toDouble())
                                    ).toFloat()
                                    when {
                                        deg >= config.diagonalAngleDeg -> {
                                            previewStageLevel = 1
                                            previewAction = config.diagonalUpAction
                                        }
                                        deg <= -config.diagonalAngleDeg -> {
                                            previewStageLevel = 1
                                            previewAction = config.diagonalDownAction
                                        }
                                        usesDistance && !stage3Action.isNone && inward >= stage3Px -> {
                                            previewStageLevel = 3
                                            previewAction = stage3Action
                                        }
                                        usesDistance && !config.horizontalHoldAction.isNone && inward >= stage2Px -> {
                                            previewStageLevel = 2
                                            previewAction = config.horizontalHoldAction
                                        }
                                        else -> {
                                            previewStageLevel = 1
                                            previewAction = config.horizontalAction
                                        }
                                    }
                                } else {
                                    previewStageLevel = 0
                                    previewAction = null
                                }
                            }
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Minimalist camera dot
                drawCircle(
                    color = outlineColor.copy(alpha = 0.50f),
                    radius = w * 0.026f,
                    center = Offset(w * 0.5f, h * 0.044f)
                )

                val visualStripWidth = ((config.edgeWidthDp / 36f) * (w * 0.14f)).coerceIn(w * 0.04f, w * 0.15f)
                val zoneHeight = h * config.edgeHeightPercent
                val zoneBottomMargin = h * config.edgeBottomOffsetPercent
                val zoneTop = (h - zoneBottomMargin - zoneHeight).coerceAtLeast(h * 0.06f)
                val zoneBottom = (zoneTop + zoneHeight).coerceAtMost(h * 0.94f)
                val zoneBorderStroke = Stroke(width = 1.dp.toPx())

                // Left Edge Area + live edge handle
                if (config.leftEnabled) {
                    drawRoundRect(
                        color = primaryColor.copy(alpha = 0.22f),
                        topLeft = Offset(0f, zoneTop),
                        size = Size(visualStripWidth, zoneHeight),
                        cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                    )
                    drawRoundRect(
                        color = primaryColor.copy(alpha = 0.45f),
                        topLeft = Offset(0f, zoneTop),
                        size = Size(visualStripWidth, zoneHeight),
                        cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                        style = zoneBorderStroke
                    )
                    if (config.edgeGuideAlpha > 0.005f) {
                        val barW = 2.dp.toPx()
                        drawRoundRect(
                            color = glassRimColor.copy(alpha = config.edgeGuideAlpha),
                            topLeft = Offset(1.5.dp.toPx(), zoneTop + 5.dp.toPx()),
                            size = Size(barW, (zoneHeight - 10.dp.toPx()).coerceAtLeast(6f)),
                            cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                        )
                    }
                }

                // Right Edge Area + live edge handle
                if (config.rightEnabled) {
                    val leftX = w - visualStripWidth
                    drawRoundRect(
                        color = primaryColor.copy(alpha = 0.22f),
                        topLeft = Offset(leftX, zoneTop),
                        size = Size(visualStripWidth, zoneHeight),
                        cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                    )
                    drawRoundRect(
                        color = primaryColor.copy(alpha = 0.45f),
                        topLeft = Offset(leftX, zoneTop),
                        size = Size(visualStripWidth, zoneHeight),
                        cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                        style = zoneBorderStroke
                    )
                    if (config.edgeGuideAlpha > 0.005f) {
                        val barW = 2.dp.toPx()
                        drawRoundRect(
                            color = glassRimColor.copy(alpha = config.edgeGuideAlpha),
                            topLeft = Offset(w - barW - 1.5.dp.toPx(), zoneTop + 5.dp.toPx()),
                            size = Size(barW, (zoneHeight - 10.dp.toPx()).coerceAtLeast(6f)),
                            cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                        )
                    }
                }

                // Dedicated Quick Menu Edge Areas
                if (config.quickArcTriggerMethod == QuickArcTriggerMethod.DEDICATED_ZONE) {
                    val arcHeight = h * config.quickArcZoneHeightPercent
                    val arcBottomMargin = h * config.quickArcZoneBottomOffsetPercent
                    val arcTop = (h - arcBottomMargin - arcHeight).coerceAtLeast(h * 0.06f)

                    if (config.quickArcZoneSide != QuickArcZoneSide.RIGHT_ONLY) {
                        drawRoundRect(
                            color = stage3Color.copy(alpha = 0.26f),
                            topLeft = Offset(0f, arcTop),
                            size = Size(visualStripWidth, arcHeight),
                            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                        )
                        drawRoundRect(
                            color = stage3Color.copy(alpha = 0.65f),
                            topLeft = Offset(0f, arcTop),
                            size = Size(visualStripWidth, arcHeight),
                            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                            style = zoneBorderStroke
                        )
                    }

                    if (config.quickArcZoneSide != QuickArcZoneSide.LEFT_ONLY) {
                        val leftX = w - visualStripWidth
                        drawRoundRect(
                            color = stage3Color.copy(alpha = 0.26f),
                            topLeft = Offset(leftX, arcTop),
                            size = Size(visualStripWidth, arcHeight),
                            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                        )
                        drawRoundRect(
                            color = stage3Color.copy(alpha = 0.65f),
                            topLeft = Offset(leftX, arcTop),
                            size = Size(visualStripWidth, arcHeight),
                            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                            style = zoneBorderStroke
                        )
                    }
                }

                // Side Stage 2 & Stage 3 dashed guidelines (up to 85%)
                val dashS2 = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)
                val dashS3 = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                if (config.sideTriggerMode != SecondaryTriggerMode.HOLD) {
                    val s2Focus = highlightGuideline == GuidelineFocus.SIDE_STAGE2 ||
                        (previewStageLevel == 2 && (activeSandboxSide == EdgeSide.LEFT || activeSandboxSide == EdgeSide.RIGHT))
                    val s3Focus = highlightGuideline == GuidelineFocus.SIDE_STAGE3 ||
                        (previewStageLevel == 3 && (activeSandboxSide == EdgeSide.LEFT || activeSandboxSide == EdgeSide.RIGHT))

                    val s2Color = primaryColor.copy(alpha = if (s2Focus) 0.92f else 0.34f)
                    val s2Width = if (s2Focus) 1.5.dp.toPx() else 0.9.dp.toPx()
                    val s3Color = stage3Color.copy(alpha = if (s3Focus) 0.95f else 0.42f)
                    val s3Width = if (s3Focus) 1.6.dp.toPx() else 0.9.dp.toPx()

                    if (config.leftEnabled) {
                        val xLeft2 = (w * config.sideStage2DistanceRatio).coerceIn(visualStripWidth + 3f, w * 0.90f)
                        drawLine(
                            color = s2Color,
                            start = Offset(xLeft2, zoneTop),
                            end = Offset(xLeft2, zoneBottom),
                            strokeWidth = s2Width,
                            pathEffect = dashS2
                        )
                        if (!config.leftStage3Action.isNone || highlightGuideline == GuidelineFocus.SIDE_STAGE3) {
                            val xLeft3 = (w * config.sideStage3DistanceRatio).coerceIn(xLeft2 + 3f, w * 0.93f)
                            drawLine(
                                color = s3Color,
                                start = Offset(xLeft3, zoneTop),
                                end = Offset(xLeft3, zoneBottom),
                                strokeWidth = s3Width,
                                pathEffect = dashS3
                            )
                        }
                    }
                    if (config.rightEnabled) {
                        val xRight2 = (w * (1f - config.sideStage2DistanceRatio)).coerceIn(w * 0.10f, w - visualStripWidth - 3f)
                        drawLine(
                            color = s2Color,
                            start = Offset(xRight2, zoneTop),
                            end = Offset(xRight2, zoneBottom),
                            strokeWidth = s2Width,
                            pathEffect = dashS2
                        )
                        if (!config.rightStage3Action.isNone || highlightGuideline == GuidelineFocus.SIDE_STAGE3) {
                            val xRight3 = (w * (1f - config.sideStage3DistanceRatio)).coerceIn(w * 0.07f, xRight2 - 3f)
                            drawLine(
                                color = s3Color,
                                start = Offset(xRight3, zoneTop),
                                end = Offset(xRight3, zoneBottom),
                                strokeWidth = s3Width,
                                pathEffect = dashS3
                            )
                        }
                    }
                }

                // Bottom Stage 2 & Stage 3 dashed guidelines (up to 85%)
                if (config.bottomEnabled && config.bottomTriggerMode != SecondaryTriggerMode.HOLD) {
                    val b2Focus = highlightGuideline == GuidelineFocus.BOTTOM_STAGE2 ||
                        (previewStageLevel == 2 && activeSandboxSide == EdgeSide.BOTTOM)
                    val b3Focus = highlightGuideline == GuidelineFocus.BOTTOM_STAGE3 ||
                        (previewStageLevel == 3 && activeSandboxSide == EdgeSide.BOTTOM)

                    val yBottom2 = (h * (1f - config.bottomStage2DistanceRatio)).coerceIn(h * 0.14f, h * 0.90f)
                    drawLine(
                        color = primaryColor.copy(alpha = if (b2Focus) 0.92f else 0.34f),
                        start = Offset(w * 0.12f, yBottom2),
                        end = Offset(w * 0.88f, yBottom2),
                        strokeWidth = if (b2Focus) 1.5.dp.toPx() else 0.9.dp.toPx(),
                        pathEffect = dashS2
                    )

                    if (!config.bottomStage3Action.isNone || highlightGuideline == GuidelineFocus.BOTTOM_STAGE3) {
                        val yBottom3 = (h * (1f - config.bottomStage3DistanceRatio)).coerceIn(h * 0.10f, yBottom2 - 3f)
                        drawLine(
                            color = stage3Color.copy(alpha = if (b3Focus) 0.95f else 0.42f),
                            start = Offset(w * 0.12f, yBottom3),
                            end = Offset(w * 0.88f, yBottom3),
                            strokeWidth = if (b3Focus) 1.6.dp.toPx() else 0.9.dp.toPx(),
                            pathEffect = dashS3
                        )
                    }
                }

                // Bottom Hit Zone & High-Contrast Charcoal Pill (honors width, height, bottom offset, horizontal offset)
                if (config.bottomEnabled) {
                    val neutralBarIdle = Color(0xFF4A505A)
                    val neutralBarLift = Color(0xFF3A3F47)
                    val neutralBarActive = Color(0xFF2D3138)
                    val neutralBarStage3 = Color(0xFF1F2329)
                    val neutralBarRim = Color(0xFFD8DDE6)

                    val isDraggingBottom = activeSandboxSide == EdgeSide.BOTTOM && animatedPull > 0.01f
                    val upwardDrag = if (isDraggingBottom) (-(dragCurrent.y - dragStart.y)).coerceAtLeast(0f) else 0f
                    val dxDrag = if (isDraggingBottom) (dragCurrent.x - dragStart.x) else 0f
                    val maxLift = 11.dp.toPx()
                    val lift = if (isDraggingBottom) {
                        maxLift * (1f - exp((-upwardDrag / (h * 0.18f)).toDouble()).toFloat())
                    } else {
                        0f
                    }
                    val horizFollow = if (isDraggingBottom) {
                        sign(dxDrag) * 5.dp.toPx() * (1f - exp((-abs(dxDrag) / (w * 0.25f)).toDouble()).toFloat())
                    } else {
                        0f
                    }

                    val hitW = w * config.bottomBarWidthPercent
                    val hitH = ((config.bottomBarHeightDp / 48f) * (h * 0.13f))
                        .coerceIn(5.dp.toPx(), h * 0.14f)
                    val bottomMarginPx = ((config.bottomBarBottomOffsetDp / 32f) * (h * 0.09f))
                        .coerceIn(0f, h * 0.11f)
                    val maxHalfShift = ((w - hitW) * 0.5f).coerceAtLeast(0f)
                    val baseShift = (w * config.bottomBarHorizontalOffsetPercent)
                        .coerceIn(-maxHalfShift, maxHalfShift)
                    val hitX = (w - hitW) * 0.5f + baseShift
                    val hitY = (h - bottomMarginPx - hitH).coerceAtLeast(h * 0.65f)

                    // Subtle translucent bottom touch-zone outline so width/height/offset/shift are live-visible
                    drawRoundRect(
                        color = neutralBarIdle.copy(alpha = 0.16f),
                        topLeft = Offset(hitX, hitY),
                        size = Size(hitW, hitH),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                    drawRoundRect(
                        color = neutralBarIdle.copy(alpha = 0.44f),
                        topLeft = Offset(hitX, hitY),
                        size = Size(hitW, hitH),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        style = zoneBorderStroke
                    )

                    val stretch = if (isDraggingBottom) 1f + 0.12f * min(animatedPull, 1f) else 1f
                    val pillW = (hitW * stretch).coerceAtMost(w * 0.94f)
                    val pillH = 3.8.dp.toPx() * (if (isDraggingBottom) 1.15f else 1f)
                    val pillX = (hitX + hitW * 0.5f - pillW * 0.5f + horizFollow)
                        .coerceIn(2.dp.toPx(), (w - pillW - 2.dp.toPx()).coerceAtLeast(2.dp.toPx()))
                    val pillY = (h - bottomMarginPx - pillH - 4.dp.toPx() - lift)
                        .coerceIn(h * 0.2f, h - pillH - 2.dp.toPx())

                    // High-contrast neutral charcoal gray across all themes with crisp light rim
                    val pillColor = when {
                        isDraggingBottom && previewStageLevel >= 3 -> neutralBarStage3
                        isDraggingBottom && previewStageLevel >= 2 -> neutralBarActive
                        isDraggingBottom -> neutralBarLift
                        else -> neutralBarIdle
                    }
                    val visibleAlpha = if (isDraggingBottom) {
                        0.96f
                    } else {
                        config.bottomPillAlpha.coerceAtLeast(0.24f)
                    }
                    val pillRimAlpha = if (isDraggingBottom) {
                        0.55f
                    } else {
                        (visibleAlpha * 0.45f).coerceAtMost(0.45f)
                    }
                    drawRoundRect(
                        color = pillColor.copy(alpha = visibleAlpha),
                        topLeft = Offset(pillX, pillY),
                        size = Size(pillW, pillH),
                        cornerRadius = CornerRadius(pillH / 2f, pillH / 2f)
                    )
                    drawRoundRect(
                        color = neutralBarRim.copy(alpha = pillRimAlpha),
                        topLeft = Offset(pillX, pillY),
                        size = Size(pillW, pillH),
                        cornerRadius = CornerRadius(pillH / 2f, pillH / 2f),
                        style = Stroke(width = 0.85.dp.toPx())
                    )

                    // Tiny floating stage indicator dots pill above lifted bottom pill
                    if (isDraggingBottom && previewStageLevel >= 1) {
                        val badgeW = 26.dp.toPx()
                        val badgeH = 11.dp.toPx()
                        val badgeX = (pillX + pillW * 0.5f - badgeW * 0.5f)
                            .coerceIn(4.dp.toPx(), w - badgeW - 4.dp.toPx())
                        val badgeY = pillY - badgeH - 5.dp.toPx()
                        drawRoundRect(
                            color = glassFillBase.copy(alpha = 0.85f),
                            topLeft = Offset(badgeX, badgeY),
                            size = Size(badgeW, badgeH),
                            cornerRadius = CornerRadius(badgeH / 2f, badgeH / 2f)
                        )
                        drawRoundRect(
                            color = neutralBarActive.copy(alpha = 0.65f),
                            topLeft = Offset(badgeX, badgeY),
                            size = Size(badgeW, badgeH),
                            cornerRadius = CornerRadius(badgeH / 2f, badgeH / 2f),
                            style = Stroke(width = 0.8.dp.toPx())
                        )
                        val dotCount = if (!config.bottomStage3Action.isNone) 3 else 2
                        val spacing = 5.dp.toPx()
                        val startX = badgeX + badgeW * 0.5f - (dotCount - 1) * spacing * 0.5f
                        for (i in 0 until dotCount) {
                            val lit = (i + 1) <= previewStageLevel
                            drawCircle(
                                color = if (lit) neutralBarStage3 else glyphColor.copy(alpha = 0.35f),
                                radius = if (lit) 1.7.dp.toPx() else 1.2.dp.toPx(),
                                center = Offset(startX + i * spacing, badgeY + badgeH * 0.5f)
                            )
                        }
                    }
                }

                // Side-edge interactive droplet preview (LEFT & RIGHT only)
                val side = activeSandboxSide
                if (side != null &&
                    side != EdgeSide.BOTTOM &&
                    animatedPull > 0.01f &&
                    config.feedbackStyle != FeedbackStyle.HIDDEN &&
                    config.feedbackAlpha > 0.005f
                ) {
                    val stageScale = when (previewStageLevel) {
                        3 -> 1.18f
                        2 -> 1.10f
                        else -> 1.0f
                    }
                    val peakScale = (config.dropletPeakWidthDp / EdgeConfig.DEFAULT_DROPLET_PEAK_WIDTH_DP)
                        .coerceIn(0.60f, 1.85f)
                    val baseScale = (config.dropletBaseWidthDp / EdgeConfig.DEFAULT_DROPLET_BASE_WIDTH_DP)
                        .coerceIn(0.55f, 1.48f)
                    val fade = min(animatedPull * 1.4f, 1f)
                    val peak = (w * 0.21f * peakScale * stageScale * min(animatedPull, 1.1f))
                        .coerceAtMost(w * 0.42f)
                    val halfSpan = (h * 0.135f * baseScale * stageScale)
                        .coerceIn(h * 0.06f, h * 0.30f)
                    val shiftY = (dragCurrent.y - dragStart.y).coerceIn(-h * 0.12f, h * 0.12f)
                    val dropletPath = Path()

                    if (side == EdgeSide.LEFT) {
                        val cy = dragStart.y.coerceIn(halfSpan, h - halfSpan)
                        val tipY = cy + shiftY * 0.2f
                        dropletPath.moveTo(0f, cy - halfSpan)
                        dropletPath.cubicTo(0f, cy - halfSpan * 0.48f, peak, tipY - halfSpan * 0.26f, peak, tipY)
                        dropletPath.cubicTo(peak, tipY + halfSpan * 0.26f, 0f, cy + halfSpan * 0.48f, 0f, cy + halfSpan)
                        dropletPath.close()
                    } else {
                        val cy = dragStart.y.coerceIn(halfSpan, h - halfSpan)
                        val tipY = cy + shiftY * 0.2f
                        dropletPath.moveTo(w, cy - halfSpan)
                        dropletPath.cubicTo(w, cy - halfSpan * 0.48f, w - peak, tipY - halfSpan * 0.26f, w - peak, tipY)
                        dropletPath.cubicTo(w - peak, tipY + halfSpan * 0.26f, w, cy + halfSpan * 0.48f, w, cy + halfSpan)
                        dropletPath.close()
                    }

                    val fillColor = glassFillBase.copy(alpha = (config.feedbackAlpha * fade).coerceIn(0f, 0.96f))
                    val rimAccent = when {
                        previewStageLevel >= 3 -> stage3Color
                        previewStageLevel == 2 -> primaryColor
                        else -> glassRimColor
                    }
                    val rimStrokeColor = rimAccent.copy(alpha = (if (previewStageLevel >= 2) 0.78f else 0.30f) * fade)
                    if (config.feedbackStyle == FeedbackStyle.BEZIER_BUBBLE) {
                        drawPath(dropletPath, color = fillColor)
                        drawPath(dropletPath, color = rimStrokeColor, style = Stroke(width = 1.dp.toPx()))
                    } else {
                        val cx = if (side == EdgeSide.LEFT) peak * 0.7f else w - peak * 0.7f
                        val cy = dragStart.y.coerceIn(h * 0.15f, h * 0.85f)
                        drawCircle(color = fillColor, radius = w * 0.09f * stageScale, center = Offset(cx, cy))
                        drawCircle(
                            color = rimStrokeColor,
                            radius = w * 0.09f * stageScale,
                            center = Offset(cx, cy),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }

                    val strokeW = 1.8.dp.toPx()
                    val iconColor = (if (previewStageLevel >= 2) rimAccent else glyphColor).copy(alpha = 0.94f * fade)
                    val cy = dragStart.y.coerceIn(halfSpan, h - halfSpan) + shiftY * 0.2f
                    if (side == EdgeSide.LEFT) {
                        val cx = peak * 0.46f
                        drawLine(iconColor, Offset(cx - 4f, cy - 6f), Offset(cx + 4f, cy), strokeW, StrokeCap.Round)
                        drawLine(iconColor, Offset(cx + 4f, cy), Offset(cx - 4f, cy + 6f), strokeW, StrokeCap.Round)
                    } else {
                        val cx = w - peak * 0.46f
                        drawLine(iconColor, Offset(cx + 4f, cy - 6f), Offset(cx - 4f, cy), strokeW, StrokeCap.Round)
                        drawLine(iconColor, Offset(cx - 4f, cy), Offset(cx + 4f, cy + 6f), strokeW, StrokeCap.Round)
                    }
                }
            }
        }

        // Right column: Live focus pill + compact metrics + concise sandbox hint
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val focusBadge = when (highlightGuideline) {
                GuidelineFocus.SIDE_STAGE2 -> stringResource(
                    R.string.preview_side_stage2_focus,
                    (config.sideStage2DistanceRatio * 100).roundToInt()
                )
                GuidelineFocus.SIDE_STAGE3 -> stringResource(
                    R.string.preview_side_stage3_focus,
                    (config.sideStage3DistanceRatio * 100).roundToInt()
                )
                GuidelineFocus.BOTTOM_STAGE2 -> stringResource(
                    R.string.preview_bottom_stage2_focus,
                    (config.bottomStage2DistanceRatio * 100).roundToInt()
                )
                GuidelineFocus.BOTTOM_STAGE3 -> stringResource(
                    R.string.preview_bottom_stage3_focus,
                    (config.bottomStage3DistanceRatio * 100).roundToInt()
                )
                GuidelineFocus.NONE -> null
            }

            val activeLabel = focusBadge
                ?: previewAction?.let { act ->
                    val base = actionLabelResolver(act)
                    when (previewStageLevel) {
                        3 -> stringResource(R.string.preview_stage3_action_prefix, base)
                        2 -> stringResource(R.string.preview_deep_action_prefix, base)
                        else -> base
                    }
                }
                ?: lastTriggeredLabel
                ?: stringResource(R.string.preview_live_title)

            val isStage3Highlight = highlightGuideline == GuidelineFocus.SIDE_STAGE3 ||
                highlightGuideline == GuidelineFocus.BOTTOM_STAGE3 ||
                previewStageLevel >= 3 ||
                isDedicatedArcDrag
            val badgeAccent = if (isStage3Highlight) stage3Color else primaryColor

            Surface(
                color = badgeAccent.copy(alpha = 0.14f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = activeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = badgeAccent,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PreviewMetricChip(
                    text = stringResource(R.string.metric_width_short, config.edgeWidthDp.roundToInt())
                )
                if (config.sideTriggerMode != SecondaryTriggerMode.HOLD) {
                    PreviewMetricChip(
                        text = stringResource(
                            R.string.metric_side_s2_short,
                            (config.sideStage2DistanceRatio * 100).roundToInt()
                        )
                    )
                    if (config.hasAnySideStage3()) {
                        PreviewMetricChip(
                            text = stringResource(
                                R.string.metric_side_s3_short,
                                (config.sideStage3DistanceRatio * 100).roundToInt()
                            ),
                            accent = stage3Color
                        )
                    }
                } else {
                    PreviewMetricChip(
                        text = stringResource(R.string.metric_side_hold_short, config.sideStage2HoldMs)
                    )
                }

                if (config.bottomEnabled) {
                    if (config.bottomTriggerMode != SecondaryTriggerMode.HOLD) {
                        PreviewMetricChip(
                            text = stringResource(
                                R.string.metric_bottom_s2_short,
                                (config.bottomStage2DistanceRatio * 100).roundToInt()
                            )
                        )
                        if (!config.bottomStage3Action.isNone) {
                            PreviewMetricChip(
                                text = stringResource(
                                    R.string.metric_bottom_s3_short,
                                    (config.bottomStage3DistanceRatio * 100).roundToInt()
                                ),
                                accent = stage3Color
                            )
                        }
                    } else {
                        PreviewMetricChip(
                            text = stringResource(R.string.metric_bottom_hold_short, config.bottomStage2HoldMs)
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.preview_sticky_hint),
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PreviewMetricChip(
    text: String,
    accent: Color? = null
) {
    val extendedColors = LocalEdgeFlowColors.current
    Surface(
        color = accent?.copy(alpha = 0.12f) ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.35f) ?: extendedColors.cardBorder)
    ) {
        Text(
            text = text,
            style = NumericSmallStyle,
            color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}
