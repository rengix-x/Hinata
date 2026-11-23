package com.edgeflow.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.ui.icons.HinataIcons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.EdgeSide
import com.edgeflow.data.QuickArcTriggerMethod
import com.edgeflow.data.QuickArcZoneSide
import com.edgeflow.data.SecondaryTriggerMode
import com.edgeflow.data.VisualTheme
import com.example.R
import com.example.ui.theme.LocalEdgeFlowColors
import com.example.ui.theme.NumericSmallStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Lifecycle-aware frame clock in milliseconds.
 * Automatically pauses (0% CPU) when the composable leaves composition or the app enters background.
 */
@Composable
private fun rememberLifecycleDemoClockMs(enabled: Boolean = true): Long {
    val lifecycleOwner = LocalLifecycleOwner.current
    var elapsedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(lifecycleOwner, enabled) {
        if (!enabled) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastNanos = 0L
            while (true) {
                withFrameNanos { frameNanos ->
                    if (lastNanos != 0L) {
                        val deltaMs = ((frameNanos - lastNanos) / 1_000_000L).coerceIn(0L, 64L)
                        elapsedMs += deltaMs
                    }
                    lastNanos = frameNanos
                }
            }
        }
    }
    return elapsedMs
}

private fun easeOutCubic(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    val inv = 1f - c
    return 1f - inv * inv * inv
}

private fun easeInOutCubic(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return if (c < 0.5f) 4f * c * c * c else 1f - ((-2f * c + 2f).let { it * it * it }) / 2f
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

/**
 * Draws the shared ghost finger indicator: tapered gradient trail + translucent finger dot +
 * subtle press compression + optional circular hold progress ring + tap ripple.
 */
private fun DrawScope.drawGhostFinger(
    center: Offset,
    pressed: Boolean,
    alpha: Float,
    accentColor: Color,
    trailPoints: List<Offset>,
    holdProgress: Float = 0f,
    rippleProgress: Float = 0f
) {
    if (alpha <= 0.01f) return

    // Tapered luminous trail
    if (trailPoints.size >= 2) {
        val lastIdx = trailPoints.lastIndex
        for (i in 0 until lastIdx) {
            val frac = (i + 1).toFloat() / lastIdx.toFloat()
            val segAlpha = (frac * frac * 0.42f * alpha).coerceIn(0f, 0.55f)
            val strokeW = (2.dp.toPx() + 6.5.dp.toPx() * frac)
            drawLine(
                color = accentColor.copy(alpha = segAlpha),
                start = trailPoints[i],
                end = trailPoints[i + 1],
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
        }
    }

    // Expanding ripple on press or stage transition
    if (rippleProgress in 0.01f..0.99f) {
        val rippleR = lerp(10.dp.toPx(), 26.dp.toPx(), easeOutCubic(rippleProgress))
        val rippleAlpha = ((1f - rippleProgress) * 0.48f * alpha).coerceIn(0f, 0.55f)
        drawCircle(
            color = accentColor.copy(alpha = rippleAlpha),
            radius = rippleR,
            center = center,
            style = Stroke(width = 1.6.dp.toPx())
        )
    }

    // Hold progress ring around finger
    if (holdProgress > 0.01f) {
        val ringR = 16.dp.toPx()
        val ringTopLeft = Offset(center.x - ringR, center.y - ringR)
        val ringSize = Size(ringR * 2f, ringR * 2f)
        drawArc(
            color = accentColor.copy(alpha = 0.22f * alpha),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = ringTopLeft,
            size = ringSize,
            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
        )
        drawArc(
            color = accentColor.copy(alpha = 0.94f * alpha),
            startAngle = -90f,
            sweepAngle = 360f * holdProgress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = ringTopLeft,
            size = ringSize,
            style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round)
        )
    }

    val scale = if (pressed) 0.88f else 1.0f
    val outerR = 12.dp.toPx() * scale
    val innerR = 5.4.dp.toPx() * scale

    // Outer translucent halo
    drawCircle(
        color = accentColor.copy(alpha = 0.22f * alpha),
        radius = outerR,
        center = center
    )
    drawCircle(
        color = accentColor.copy(alpha = 0.72f * alpha),
        radius = outerR,
        center = center,
        style = Stroke(width = 1.5.dp.toPx())
    )
    // Inner fingertip dot
    drawCircle(
        color = Color.White.copy(alpha = 0.95f * alpha),
        radius = innerR,
        center = center
    )
    drawCircle(
        color = accentColor.copy(alpha = 0.85f * alpha),
        radius = innerR * 0.55f,
        center = center
    )
}

/**
 * Draws a sleek side-edge Bezier droplet that bounces smoothly as stage level increases.
 */
private fun DrawScope.drawDemoDroplet(
    fromLeft: Boolean,
    anchorY: Float,
    pullFraction: Float,
    stageLevel: Int,
    primaryColor: Color,
    stage3Color: Color,
    glassFill: Color,
    glassRim: Color,
    peakScale: Float = 1f,
    baseScale: Float = 1f
) {
    if (pullFraction <= 0.01f) return
    val w = size.width
    val h = size.height
    val stageBoost = when {
        stageLevel >= 3 -> 1.18f
        stageLevel == 2 -> 1.10f
        else -> 1.0f
    }
    val peak = (w * 0.13f * peakScale.coerceIn(0.6f, 1.85f) * min(pullFraction, 1.15f) * stageBoost)
        .coerceAtMost(w * 0.32f)
    val halfSpan = (h * 0.30f * baseScale.coerceIn(0.55f, 1.48f) * stageBoost)
        .coerceIn(h * 0.14f, h * 0.48f)
    val cy = anchorY.coerceIn(halfSpan * 0.85f, h - halfSpan * 0.85f)
    val fade = min(pullFraction * 1.6f, 1f)

    val path = Path().apply {
        if (fromLeft) {
            moveTo(0f, cy - halfSpan)
            cubicTo(0f, cy - halfSpan * 0.48f, peak, cy - halfSpan * 0.26f, peak, cy)
            cubicTo(peak, cy + halfSpan * 0.26f, 0f, cy + halfSpan * 0.48f, 0f, cy + halfSpan)
            close()
        } else {
            moveTo(w, cy - halfSpan)
            cubicTo(w, cy - halfSpan * 0.48f, w - peak, cy - halfSpan * 0.26f, w - peak, cy)
            cubicTo(w - peak, cy + halfSpan * 0.26f, w, cy + halfSpan * 0.48f, w, cy + halfSpan)
            close()
        }
    }

    val accent = when {
        stageLevel >= 3 -> stage3Color
        stageLevel == 2 -> primaryColor
        else -> glassRim
    }
    drawPath(path = path, color = glassFill.copy(alpha = 0.84f * fade))
    drawPath(
        path = path,
        color = accent.copy(alpha = (if (stageLevel >= 2) 0.88f else 0.42f) * fade),
        style = Stroke(width = (if (stageLevel >= 2) 1.6 else 1.1).dp.toPx())
    )

    // Minimal directional chevron inside droplet
    val cx = if (fromLeft) peak * 0.48f else w - peak * 0.48f
    val chev = 4.2.dp.toPx()
    val chevColor = (if (stageLevel >= 2) accent else Color.White).copy(alpha = 0.94f * fade)
    val dir = if (fromLeft) 1f else -1f
    drawLine(
        color = chevColor,
        start = Offset(cx - dir * chev * 0.6f, cy - chev),
        end = Offset(cx + dir * chev * 0.6f, cy),
        strokeWidth = 1.8.dp.toPx(),
        cap = StrokeCap.Round
    )
    drawLine(
        color = chevColor,
        start = Offset(cx + dir * chev * 0.6f, cy),
        end = Offset(cx - dir * chev * 0.6f, cy + chev),
        strokeWidth = 1.8.dp.toPx(),
        cap = StrokeCap.Round
    )
}

/**
 * Draws the radial fan menu (supporting both standard <=6 slots and 7+ rotary wheel scroll).
 */
private fun DrawScope.drawDemoRadialMenu(
    origin: Offset,
    fromLeft: Boolean,
    radius: Float,
    openProgress: Float,
    slotCount: Int,
    focusedIndex: Int,
    rotaryScrollIndex: Float,
    primaryColor: Color,
    glassFill: Color,
    glassRim: Color
) {
    if (openProgress <= 0.01f) return
    val ease = easeOutCubic(openProgress)
    val dirSign = if (fromLeft) 1f else -1f
    val visibleWindow = 6
    val isRotary = slotCount > visibleWindow
    val maxScroll = (slotCount - visibleWindow).coerceAtLeast(0).toFloat()
    val clampedScroll = rotaryScrollIndex.coerceIn(0f, maxScroll)

    val sweepDeg = when {
        isRotary -> 132f
        slotCount <= 3 -> 100f
        slotCount == 4 -> 114f
        slotCount == 5 -> 124f
        else -> 132f
    }
    val startDeg = -sweepDeg / 2f
    val stepDeg = if (isRotary) {
        sweepDeg / (visibleWindow - 1)
    } else if (slotCount > 1) {
        sweepDeg / (slotCount - 1)
    } else {
        0f
    }

    val activeRadius = radius * (0.35f + 0.65f * ease)
    val baseSlotR = (radius * 0.145f).coerceIn(10.dp.toPx(), 16.dp.toPx())

    // Subtle rotary track & thumb for 7+ slots
    if (isRotary) {
        val trackR = activeRadius + baseSlotR + 7.dp.toPx()
        val arcStart = if (fromLeft) -60f else 120f
        val arcSweep = 120f
        val topLeft = Offset(origin.x - trackR, origin.y - trackR)
        val arcSize = Size(trackR * 2f, trackR * 2f)
        drawArc(
            color = glassRim.copy(alpha = 0.22f * ease),
            startAngle = arcStart,
            sweepAngle = arcSweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
        val thumbSweep = arcSweep * (visibleWindow.toFloat() / slotCount.toFloat())
        val frac = if (maxScroll > 0.001f) clampedScroll / maxScroll else 0f
        val thumbStart = if (fromLeft) {
            arcStart + (arcSweep - thumbSweep) * frac
        } else {
            (arcStart + arcSweep - thumbSweep) - (arcSweep - thumbSweep) * frac
        }
        drawArc(
            color = primaryColor.copy(alpha = 0.85f * ease),
            startAngle = thumbStart,
            sweepAngle = thumbSweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }

    for (i in 0 until slotCount) {
        val deg = if (isRotary) startDeg + (i - clampedScroll) * stepDeg else startDeg + stepDeg * i
        if (isRotary && (deg < -78f || deg > 78f)) continue
        val edgeFade = if (isRotary && abs(deg) > 60f) {
            (1f - (abs(deg) - 60f) / 18f).coerceIn(0f, 1f)
        } else {
            1f
        }
        if (edgeFade <= 0.02f) continue

        val rad = (deg * PI / 180.0).toFloat()
        val cx = origin.x + dirSign * cos(rad) * activeRadius
        val cy = origin.y + sin(rad) * activeRadius
        val isFocused = i == focusedIndex
        val slotR = baseSlotR * (if (isFocused) 1.24f else 1.0f) * (0.75f + 0.25f * edgeFade) * ease
        val alpha = ease * edgeFade

        if (isFocused) {
            drawLine(
                color = primaryColor.copy(alpha = 0.42f * alpha),
                start = origin,
                end = Offset(cx, cy),
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        drawCircle(
            color = glassFill.copy(alpha = (if (isFocused) 0.90f else 0.72f) * alpha),
            radius = slotR,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = (if (isFocused) primaryColor else glassRim).copy(
                alpha = (if (isFocused) 0.92f else 0.30f) * alpha
            ),
            radius = slotR,
            center = Offset(cx, cy),
            style = Stroke(width = (if (isFocused) 1.8 else 1.0).dp.toPx())
        )
        drawCircle(
            color = (if (isFocused) primaryColor else Color.White.copy(alpha = 0.85f)).copy(alpha = alpha),
            radius = slotR * 0.28f,
            center = Offset(cx, cy)
        )
    }
}

// =================================================================================================
// 3.1 Quick Menu Trigger Method Modal Bottom Sheet (4 Methods)
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickArcTriggerDemoSheet(
    method: QuickArcTriggerMethod,
    config: EdgeConfig,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val extendedColors = LocalEdgeFlowColors.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val stage3Color = extendedColors.dedicatedZoneAccent
    val surfaceHigh = MaterialTheme.colorScheme.surfaceVariant
    val bgColor = MaterialTheme.colorScheme.background
    val outlineColor = MaterialTheme.colorScheme.outline

    val titleRes = when (method) {
        QuickArcTriggerMethod.DEDICATED_ZONE -> R.string.arc_method_dedicated_title
        QuickArcTriggerMethod.EDGE_STATIONARY_HOLD -> R.string.arc_method_edge_hold_title
        QuickArcTriggerMethod.PULL_AND_RETURN -> R.string.arc_method_pull_return_title
        QuickArcTriggerMethod.GESTURE_ACTION -> R.string.arc_method_gesture_title
    }
    val guideRes = when (method) {
        QuickArcTriggerMethod.DEDICATED_ZONE -> R.string.demo_sheet_guide_dedicated
        QuickArcTriggerMethod.EDGE_STATIONARY_HOLD -> R.string.demo_sheet_guide_edge_hold
        QuickArcTriggerMethod.PULL_AND_RETURN -> R.string.demo_sheet_guide_pull_return
        QuickArcTriggerMethod.GESTURE_ACTION -> R.string.demo_sheet_guide_gesture
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.testTag("quick_arc_demo_sheet")
    ) {
        val elapsedMs = rememberLifecycleDemoClockMs(enabled = true)
        val fromLeft = config.quickArcZoneSide == QuickArcZoneSide.LEFT_ONLY
        val glassFill = if (config.visualTheme == VisualTheme.LIGHT_GLASS) {
            Color(0xFFF3F6FA)
        } else {
            Color(0xFF151820)
        }
        val glassRim = if (config.visualTheme == VisualTheme.LIGHT_GLASS) {
            Color(0xFF1A1D24)
        } else {
            Color.White
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = HinataIcons.PlayCircleOutline,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(14.dp))

            // Clean phone mockup stage with live animation
            val stepLabel = when (method) {
                QuickArcTriggerMethod.DEDICATED_ZONE -> stringResource(R.string.demo_badge_instant_0ms)
                QuickArcTriggerMethod.EDGE_STATIONARY_HOLD -> stringResource(
                    R.string.demo_badge_hold_ms,
                    config.quickArcEdgeHoldMs
                )
                QuickArcTriggerMethod.PULL_AND_RETURN -> stringResource(R.string.demo_badge_uturn)
                QuickArcTriggerMethod.GESTURE_ACTION -> if (config.sideTriggerMode == SecondaryTriggerMode.HOLD) {
                    stringResource(R.string.demo_badge_hold_ms, config.sideStage2HoldMs.toInt())
                } else {
                    stringResource(
                        R.string.demo_badge_distance_pct,
                        (config.sideStage2DistanceRatio * 100f).roundToInt()
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(232.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(surfaceHigh.copy(alpha = 0.65f), bgColor)
                        )
                    )
                    .border(
                        width = 1.4.dp,
                        color = outlineColor.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(22.dp)
                    )
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val dirSign = if (fromLeft) 1f else -1f
                    val edgeX = if (fromLeft) 0f else w
                    val stripW = 14.dp.toPx()

                    // Subtle phone frame top speaker pill
                    drawRoundRect(
                        color = outlineColor.copy(alpha = 0.35f),
                        topLeft = Offset(w * 0.5f - 18.dp.toPx(), 8.dp.toPx()),
                        size = Size(36.dp.toPx(), 3.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )

                    val slotCount = config.quickMenuSlots.size.coerceIn(3, 6)
                    val menuR = (h * 0.36f).coerceIn(62.dp.toPx(), 86.dp.toPx())

                    when (method) {
                        QuickArcTriggerMethod.DEDICATED_ZONE -> {
                            val zoneH = (h * config.quickArcZoneHeightPercent).coerceIn(h * 0.14f, h * 0.44f)
                            val bottomMargin = h * config.quickArcZoneBottomOffsetPercent
                            val zoneTop = (h - bottomMargin - zoneH).coerceIn(h * 0.14f, h - zoneH - h * 0.10f)
                            val anchorY = (zoneTop + zoneH * 0.5f).coerceIn(h * 0.32f, h * 0.72f)
                            val origin = Offset(edgeX, anchorY)

                            // Dedicated edge highlight strip
                            val stripLeft = if (fromLeft) 0f else w - stripW
                            drawRoundRect(
                                color = stage3Color.copy(alpha = 0.25f),
                                topLeft = Offset(stripLeft, zoneTop),
                                size = Size(stripW, zoneH),
                                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                            )
                            drawRoundRect(
                                color = stage3Color.copy(alpha = 0.75f),
                                topLeft = Offset(stripLeft, zoneTop),
                                size = Size(stripW, zoneH),
                                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                                style = Stroke(width = 1.3.dp.toPx())
                            )

                            val cycleMs = 3000L
                            val t = (elapsedMs % cycleMs).toFloat() / cycleMs.toFloat()

                            fun posAt(p: Float): Offset {
                                val targetRad = (-24f * PI / 180.0).toFloat()
                                val targetPt = Offset(
                                    origin.x + dirSign * cos(targetRad) * menuR,
                                    origin.y + sin(targetRad) * menuR
                                )
                                val midPt = Offset(origin.x + dirSign * menuR * 0.62f, origin.y)
                                return when {
                                    p < 0.12f -> Offset(origin.x + dirSign * 6.dp.toPx(), origin.y)
                                    p < 0.36f -> {
                                        val k = easeOutCubic((p - 0.12f) / 0.24f)
                                        Offset(lerp(origin.x + dirSign * 6.dp.toPx(), midPt.x, k), origin.y)
                                    }
                                    p < 0.68f -> {
                                        val k = easeInOutCubic((p - 0.36f) / 0.32f)
                                        Offset(lerp(midPt.x, targetPt.x, k), lerp(midPt.y, targetPt.y, k))
                                    }
                                    else -> targetPt
                                }
                            }

                            val openProgress = when {
                                t < 0.14f -> 0f
                                t < 0.26f -> (t - 0.14f) / 0.12f
                                t < 0.80f -> 1f
                                t < 0.92f -> 1f - (t - 0.80f) / 0.12f
                                else -> 0f
                            }
                            val focusedSlot = when {
                                t in 0.44f..0.80f -> 1
                                t in 0.26f..0.44f -> 2
                                else -> -1
                            }
                            drawDemoRadialMenu(
                                origin = origin,
                                fromLeft = fromLeft,
                                radius = menuR,
                                openProgress = openProgress,
                                slotCount = slotCount,
                                focusedIndex = focusedSlot,
                                rotaryScrollIndex = 0f,
                                primaryColor = stage3Color,
                                glassFill = glassFill,
                                glassRim = glassRim
                            )

                            val fingerAlpha = when {
                                t < 0.08f -> t / 0.08f
                                t < 0.78f -> 1f
                                t < 0.88f -> 1f - (t - 0.78f) / 0.10f
                                else -> 0f
                            }
                            val trail = if (t in 0.12f..0.76f) {
                                List(14) { i ->
                                    val sampleT = (t - 0.14f * (1f - i / 13f)).coerceAtLeast(0.12f)
                                    posAt(sampleT)
                                }
                            } else emptyList()
                            val ripple = if (t in 0.12f..0.30f) (t - 0.12f) / 0.18f else 0f
                            drawGhostFinger(
                                center = posAt(t),
                                pressed = t in 0.06f..0.78f,
                                alpha = fingerAlpha,
                                accentColor = stage3Color,
                                trailPoints = trail,
                                rippleProgress = ripple
                            )
                        }

                        QuickArcTriggerMethod.EDGE_STATIONARY_HOLD -> {
                            val anchorY = h * 0.54f
                            val origin = Offset(edgeX, anchorY)
                            val holdRatio = (config.quickArcEdgeHoldMs / 650f).coerceIn(0.32f, 1.0f)
                            val holdFrac = lerp(0.22f, 0.44f, (holdRatio - 0.32f) / 0.68f)
                            val holdStart = 0.08f
                            val holdEnd = holdStart + holdFrac
                            val cycleMs = 3400L
                            val t = (elapsedMs % cycleMs).toFloat() / cycleMs.toFloat()

                            // Edge strip hint
                            val stripLeft = if (fromLeft) 0f else w - stripW * 0.7f
                            drawRoundRect(
                                color = primaryColor.copy(alpha = 0.16f),
                                topLeft = Offset(stripLeft, h * 0.22f),
                                size = Size(stripW * 0.7f, h * 0.58f),
                                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                            )

                            val startPt = Offset(origin.x + dirSign * 9.dp.toPx(), origin.y)
                            val targetRad = (22f * PI / 180.0).toFloat()
                            val targetPt = Offset(
                                origin.x + dirSign * cos(targetRad) * menuR,
                                origin.y + sin(targetRad) * menuR
                            )

                            fun posAt(p: Float): Offset = when {
                                p <= holdEnd -> startPt
                                p <= holdEnd + 0.26f -> {
                                    val k = easeInOutCubic((p - holdEnd) / 0.26f)
                                    Offset(lerp(startPt.x, targetPt.x, k), lerp(startPt.y, targetPt.y, k))
                                }
                                else -> targetPt
                            }

                            val holdProgress = if (t in holdStart..holdEnd) {
                                ((t - holdStart) / holdFrac).coerceIn(0f, 1f)
                            } else 0f

                            val openProgress = when {
                                t < holdEnd -> 0f
                                t < holdEnd + 0.10f -> (t - holdEnd) / 0.10f
                                t < 0.84f -> 1f
                                t < 0.94f -> 1f - (t - 0.84f) / 0.10f
                                else -> 0f
                            }
                            val focusedSlot = if (t in (holdEnd + 0.12f)..0.84f) (slotCount - 2).coerceAtLeast(0) else -1

                            drawDemoRadialMenu(
                                origin = origin,
                                fromLeft = fromLeft,
                                radius = menuR,
                                openProgress = openProgress,
                                slotCount = slotCount,
                                focusedIndex = focusedSlot,
                                rotaryScrollIndex = 0f,
                                primaryColor = primaryColor,
                                glassFill = glassFill,
                                glassRim = glassRim
                            )

                            val fingerAlpha = when {
                                t < 0.06f -> t / 0.06f
                                t < 0.82f -> 1f
                                t < 0.92f -> 1f - (t - 0.82f) / 0.10f
                                else -> 0f
                            }
                            val trail = if (t in holdEnd..0.80f) {
                                List(14) { i ->
                                    val s = (t - 0.14f * (1f - i / 13f)).coerceAtLeast(holdEnd)
                                    posAt(s)
                                }
                            } else emptyList()
                            val ripple = if (t in holdEnd..(holdEnd + 0.16f)) {
                                (t - holdEnd) / 0.16f
                            } else 0f
                            drawGhostFinger(
                                center = posAt(t),
                                pressed = t in 0.06f..0.82f,
                                alpha = fingerAlpha,
                                accentColor = primaryColor,
                                trailPoints = trail,
                                holdProgress = holdProgress,
                                rippleProgress = ripple
                            )
                        }

                        QuickArcTriggerMethod.PULL_AND_RETURN -> {
                            val anchorY = h * 0.54f
                            val origin = Offset(edgeX, anchorY)
                            val cycleMs = 3600L
                            val t = (elapsedMs % cycleMs).toFloat() / cycleMs.toFloat()

                            val startPt = Offset(origin.x + dirSign * 6.dp.toPx(), origin.y)
                            val pullPeakX = origin.x + dirSign * (w * 0.34f)
                            val returnPtX = origin.x + dirSign * (w * 0.13f)
                            val targetRad = (-22f * PI / 180.0).toFloat()
                            val targetPt = Offset(
                                origin.x + dirSign * cos(targetRad) * menuR,
                                origin.y + sin(targetRad) * menuR
                            )

                            fun posAt(p: Float): Offset = when {
                                p < 0.08f -> startPt
                                p < 0.30f -> {
                                    val k = easeOutCubic((p - 0.08f) / 0.22f)
                                    Offset(lerp(startPt.x, pullPeakX, k), origin.y)
                                }
                                p < 0.48f -> {
                                    // U-turn back toward edge
                                    val k = easeInOutCubic((p - 0.30f) / 0.18f)
                                    Offset(lerp(pullPeakX, returnPtX, k), origin.y + sin(k * PI).toFloat() * 6.dp.toPx())
                                }
                                p < 0.72f -> {
                                    val k = easeInOutCubic((p - 0.48f) / 0.24f)
                                    Offset(lerp(returnPtX, targetPt.x, k), lerp(origin.y, targetPt.y, k))
                                }
                                else -> targetPt
                            }

                            // Droplet shown during initial inward pull before U-turn completes
                            if (t in 0.08f..0.46f) {
                                val pullFrac = when {
                                    t < 0.30f -> (t - 0.08f) / 0.22f
                                    else -> 1f - ((t - 0.30f) / 0.16f) * 0.55f
                                }
                                drawDemoDroplet(
                                    fromLeft = fromLeft,
                                    anchorY = anchorY,
                                    pullFraction = pullFrac,
                                    stageLevel = 1,
                                    primaryColor = primaryColor,
                                    stage3Color = stage3Color,
                                    glassFill = glassFill,
                                    glassRim = glassRim
                                )
                            }

                            val openProgress = when {
                                t < 0.45f -> 0f
                                t < 0.55f -> (t - 0.45f) / 0.10f
                                t < 0.84f -> 1f
                                t < 0.94f -> 1f - (t - 0.84f) / 0.10f
                                else -> 0f
                            }
                            val focusedSlot = if (t in 0.58f..0.84f) 1 else -1
                            drawDemoRadialMenu(
                                origin = origin,
                                fromLeft = fromLeft,
                                radius = menuR,
                                openProgress = openProgress,
                                slotCount = slotCount,
                                focusedIndex = focusedSlot,
                                rotaryScrollIndex = 0f,
                                primaryColor = primaryColor,
                                glassFill = glassFill,
                                glassRim = glassRim
                            )

                            val fingerAlpha = when {
                                t < 0.06f -> t / 0.06f
                                t < 0.82f -> 1f
                                t < 0.92f -> 1f - (t - 0.82f) / 0.10f
                                else -> 0f
                            }
                            val trail = if (t in 0.08f..0.80f) {
                                List(16) { i ->
                                    val s = (t - 0.16f * (1f - i / 15f)).coerceAtLeast(0.08f)
                                    posAt(s)
                                }
                            } else emptyList()
                            val ripple = if (t in 0.45f..0.60f) (t - 0.45f) / 0.15f else 0f
                            drawGhostFinger(
                                center = posAt(t),
                                pressed = t in 0.06f..0.82f,
                                alpha = fingerAlpha,
                                accentColor = primaryColor,
                                trailPoints = trail,
                                rippleProgress = ripple
                            )
                        }

                        QuickArcTriggerMethod.GESTURE_ACTION -> {
                            val anchorY = h * 0.54f
                            val origin = Offset(edgeX, anchorY)
                            val usesHoldOnly = config.sideTriggerMode == SecondaryTriggerMode.HOLD
                            val stage2X = if (fromLeft) {
                                (w * config.sideStage2DistanceRatio).coerceIn(w * 0.18f, w * 0.62f)
                            } else {
                                (w * (1f - config.sideStage2DistanceRatio)).coerceIn(w * 0.38f, w * 0.82f)
                            }
                            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                            if (!usesHoldOnly) {
                                drawLine(
                                    color = primaryColor.copy(alpha = 0.55f),
                                    start = Offset(stage2X, h * 0.18f),
                                    end = Offset(stage2X, h * 0.86f),
                                    strokeWidth = 1.5.dp.toPx(),
                                    pathEffect = dash
                                )
                            }

                            val cycleMs = 3400L
                            val t = (elapsedMs % cycleMs).toFloat() / cycleMs.toFloat()
                            val startPt = Offset(origin.x + dirSign * 6.dp.toPx(), origin.y)
                            val triggerX = if (usesHoldOnly) {
                                origin.x + dirSign * (w * 0.24f)
                            } else {
                                stage2X + dirSign * 14.dp.toPx()
                            }
                            val targetRad = (24f * PI / 180.0).toFloat()
                            val targetPt = Offset(
                                origin.x + dirSign * cos(targetRad) * menuR,
                                origin.y + sin(targetRad) * menuR
                            )
                            val openStart = if (usesHoldOnly) 0.48f else 0.40f

                            fun posAt(p: Float): Offset = when {
                                p < 0.08f -> startPt
                                p < 0.36f -> {
                                    val k = easeInOutCubic((p - 0.08f) / 0.28f)
                                    Offset(lerp(startPt.x, triggerX, k), origin.y)
                                }
                                p < openStart -> Offset(triggerX, origin.y)
                                p < openStart + 0.24f -> {
                                    val k = easeInOutCubic((p - openStart) / 0.24f)
                                    Offset(lerp(triggerX, targetPt.x, k), lerp(origin.y, targetPt.y, k))
                                }
                                else -> targetPt
                            }

                            if (t in 0.08f..openStart) {
                                val pullFrac = ((t - 0.08f) / 0.28f).coerceIn(0f, 1.05f)
                                drawDemoDroplet(
                                    fromLeft = fromLeft,
                                    anchorY = anchorY,
                                    pullFraction = pullFrac,
                                    stageLevel = if (t >= openStart - 0.04f) 2 else 1,
                                    primaryColor = primaryColor,
                                    stage3Color = stage3Color,
                                    glassFill = glassFill,
                                    glassRim = glassRim
                                )
                            }

                            val openProgress = when {
                                t < openStart -> 0f
                                t < openStart + 0.10f -> (t - openStart) / 0.10f
                                t < 0.84f -> 1f
                                t < 0.94f -> 1f - (t - 0.84f) / 0.10f
                                else -> 0f
                            }
                            val focusedSlot = if (t in (openStart + 0.12f)..0.84f) (slotCount - 2).coerceAtLeast(0) else -1
                            drawDemoRadialMenu(
                                origin = origin,
                                fromLeft = fromLeft,
                                radius = menuR,
                                openProgress = openProgress,
                                slotCount = slotCount,
                                focusedIndex = focusedSlot,
                                rotaryScrollIndex = 0f,
                                primaryColor = primaryColor,
                                glassFill = glassFill,
                                glassRim = glassRim
                            )

                            val holdProg = if (usesHoldOnly && t in 0.34f..openStart) {
                                ((t - 0.34f) / (openStart - 0.34f)).coerceIn(0f, 1f)
                            } else 0f
                            val fingerAlpha = when {
                                t < 0.06f -> t / 0.06f
                                t < 0.82f -> 1f
                                t < 0.92f -> 1f - (t - 0.82f) / 0.10f
                                else -> 0f
                            }
                            val trail = if (t in 0.08f..0.80f) {
                                List(15) { i ->
                                    val s = (t - 0.14f * (1f - i / 14f)).coerceAtLeast(0.08f)
                                    posAt(s)
                                }
                            } else emptyList()
                            val ripple = if (t in openStart..(openStart + 0.15f)) (t - openStart) / 0.15f else 0f
                            drawGhostFinger(
                                center = posAt(t),
                                pressed = t in 0.06f..0.82f,
                                alpha = fingerAlpha,
                                accentColor = primaryColor,
                                trailPoints = trail,
                                holdProgress = holdProg,
                                rippleProgress = ripple
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(guideRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quick_arc_demo_close_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryColor,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = stringResource(R.string.button_close),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

// =================================================================================================
// 3.2 Side Edge Trigger Real-Time Animation Preview (~132dp, DISTANCE / HOLD / BOTH)
// =================================================================================================

@Composable
fun SideTriggerLivePreview(
    config: EdgeConfig,
    modifier: Modifier = Modifier
) {
    val extendedColors = LocalEdgeFlowColors.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val stage3Color = extendedColors.dedicatedZoneAccent
    val surfaceHigh = MaterialTheme.colorScheme.surfaceVariant
    val bgColor = MaterialTheme.colorScheme.background
    val outlineColor = MaterialTheme.colorScheme.outline

    val glassFill = if (config.visualTheme == VisualTheme.LIGHT_GLASS) {
        Color(0xFFF3F6FA)
    } else {
        Color(0xFF151820)
    }
    val glassRim = if (config.visualTheme == VisualTheme.LIGHT_GLASS) {
        Color(0xFF1A1D24)
    } else {
        Color.White
    }

    val elapsedMs = rememberLifecycleDemoClockMs(enabled = true)
    val hasStage3 = config.hasAnySideStage3()

    // Determine active demo cycle mode (for BOTH, alternate Distance loop then Hold loop)
    // Extended cycle ensures Stage 3 stays clearly lit for ~900ms before smoothly fading out.
    val cycleMs = 4600L
    val loopIndex = (elapsedMs / cycleMs).toInt()
    val t = (elapsedMs % cycleMs).toFloat() / cycleMs.toFloat()
    val playingHoldLoop = when (config.sideTriggerMode) {
        SecondaryTriggerMode.DISTANCE -> false
        SecondaryTriggerMode.HOLD -> true
        SecondaryTriggerMode.BOTH -> (loopIndex % 2) == 1
    }

    // Smooth fade-out at end of cycle so Stage 3 never snaps off abruptly
    val endFadeAlpha = when {
        t < 0.80f -> 1f
        t < 0.93f -> (1f - (t - 0.80f) / 0.13f).coerceIn(0f, 1f)
        else -> 0f
    }

    // Compute normalized stage & hold progress cleanly for both top chips and canvas
    val stage2HoldFrac = (config.sideStage2HoldMs / 1900f).coerceIn(0.11f, 0.21f)
    val stage3HoldFrac = if (hasStage3) {
        ((config.sideStage3HoldMs - config.sideStage2HoldMs).coerceAtLeast(150L) / 1900f)
            .coerceIn(0.11f, 0.20f)
    } else 0f

    val holdStart = 0.18f
    val holdS2At = holdStart + stage2HoldFrac
    val holdS3At = (holdS2At + stage3HoldFrac).coerceAtMost(0.60f)

    val s2XFrac = config.sideStage2DistanceRatio.coerceIn(0.14f, 0.74f)
    val s3XFrac = config.sideStage3DistanceRatio.coerceIn(s2XFrac + 0.08f, 0.86f)
    val maxPullFrac = if (hasStage3) (s3XFrac + 0.07f).coerceAtMost(0.92f) else (s2XFrac + 0.10f).coerceAtMost(0.86f)

    val currentPullXFrac = if (!playingHoldLoop) {
        when {
            t < 0.06f -> 0.02f
            t < 0.58f -> lerp(0.02f, maxPullFrac, easeInOutCubic((t - 0.06f) / 0.52f))
            t < 0.93f -> maxPullFrac
            else -> 0.02f
        }
    } else {
        val shallowX = (s2XFrac * 0.58f).coerceIn(0.14f, 0.26f)
        when {
            t < 0.06f -> 0.02f
            t < holdStart -> lerp(0.02f, shallowX, easeOutCubic((t - 0.06f) / (holdStart - 0.06f)))
            t < 0.93f -> shallowX
            else -> 0.02f
        }
    }

    val activeStage = if (!playingHoldLoop) {
        when {
            t >= 0.93f || t < 0.10f -> 0
            hasStage3 && currentPullXFrac >= s3XFrac -> 3
            currentPullXFrac >= s2XFrac -> 2
            currentPullXFrac >= 0.10f -> 1
            else -> 0
        }
    } else {
        when {
            t >= 0.93f || t < 0.10f -> 0
            hasStage3 && t >= holdS3At -> 3
            t >= holdS2At -> 2
            t >= 0.12f -> 1
            else -> 0
        }
    }

    val holdRingProgress = if (playingHoldLoop && t in holdStart..0.80f) {
        when {
            t < holdS2At -> ((t - holdStart) / stage2HoldFrac).coerceIn(0f, 1f)
            hasStage3 && t < holdS3At -> ((t - holdS2At) / (holdS3At - holdS2At).coerceAtLeast(0.05f)).coerceIn(0f, 1f)
            else -> 0f
        }
    } else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(surfaceHigh.copy(alpha = 0.65f), bgColor)
                )
            )
            .border(
                width = 1.dp,
                color = outlineColor.copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("side_trigger_live_preview")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val anchorY = h * 0.58f
            val dashS2 = PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f)
            val dashS3 = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)

            // Left edge zone strip
            drawRoundRect(
                color = primaryColor.copy(alpha = 0.18f),
                topLeft = Offset(0f, h * 0.16f),
                size = Size(8.dp.toPx(), h * 0.72f),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )

            // Stage 2 & Stage 3 live distance dashed lines (shown when Distance or Both)
            if (config.sideTriggerMode != SecondaryTriggerMode.HOLD) {
                val x2 = w * s2XFrac
                drawLine(
                    color = primaryColor.copy(alpha = if (activeStage == 2) 0.92f else 0.36f),
                    start = Offset(x2, h * 0.24f),
                    end = Offset(x2, h * 0.92f),
                    strokeWidth = (if (activeStage == 2) 1.8 else 1.1).dp.toPx(),
                    pathEffect = dashS2
                )
                if (hasStage3) {
                    val x3 = w * s3XFrac
                    drawLine(
                        color = stage3Color.copy(alpha = if (activeStage == 3) 0.95f else 0.42f),
                        start = Offset(x3, h * 0.24f),
                        end = Offset(x3, h * 0.92f),
                        strokeWidth = (if (activeStage == 3) 1.9 else 1.1).dp.toPx(),
                        pathEffect = dashS3
                    )
                }
            }

            // Elastic Bezier droplet (smoothly eases out at end of Stage 3 dwell)
            val dropletPull = if (activeStage > 0) {
                (currentPullXFrac / 0.28f).coerceIn(0.35f, 1.15f) * endFadeAlpha
            } else {
                0f
            }
            drawDemoDroplet(
                fromLeft = true,
                anchorY = anchorY,
                pullFraction = dropletPull,
                stageLevel = activeStage,
                primaryColor = primaryColor,
                stage3Color = stage3Color,
                glassFill = glassFill,
                glassRim = glassRim,
                peakScale = config.dropletPeakWidthDp / EdgeConfig.DEFAULT_DROPLET_PEAK_WIDTH_DP,
                baseScale = config.dropletBaseWidthDp / EdgeConfig.DEFAULT_DROPLET_BASE_WIDTH_DP
            )

            // Ghost finger + smooth trail
            val fingerAlpha = when {
                t < 0.05f -> t / 0.05f
                t < 0.80f -> 1f
                t < 0.93f -> 1f - (t - 0.80f) / 0.13f
                else -> 0f
            }
            val fingerPos = Offset(w * currentPullXFrac, anchorY)
            val trail = if (!playingHoldLoop && t in 0.06f..0.80f) {
                List(14) { i ->
                    val s = (t - 0.12f * (1f - i / 13f)).coerceAtLeast(0.06f)
                    val sx = if (s < 0.58f) lerp(0.02f, maxPullFrac, easeInOutCubic((s - 0.06f) / 0.52f)) else maxPullFrac
                    Offset(w * sx, anchorY)
                }
            } else if (playingHoldLoop && t in 0.06f..holdStart) {
                val shallowX = (s2XFrac * 0.58f).coerceIn(0.14f, 0.26f)
                List(10) { i ->
                    val s = (t - 0.06f * (1f - i / 9f)).coerceAtLeast(0.06f)
                    val sx = lerp(0.02f, shallowX, easeOutCubic((s - 0.06f) / (holdStart - 0.06f)))
                    Offset(w * sx, anchorY)
                }
            } else emptyList()

            val accent = if (activeStage >= 3) stage3Color else primaryColor
            drawGhostFinger(
                center = fingerPos,
                pressed = t in 0.05f..0.80f,
                alpha = fingerAlpha,
                accentColor = accent,
                trailPoints = trail,
                holdProgress = holdRingProgress
            )
        }

        // Top overlay row: Stage 1 -> 2 -> (3) chips (smoothly fades at end of cycle, zero timer text)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val maxStage = if (hasStage3) 3 else 2
                for (stage in 1..maxStage) {
                    val isLit = activeStage >= stage && endFadeAlpha > 0.05f
                    val isCurrent = activeStage == stage && endFadeAlpha > 0.05f
                    val chipAccent = if (stage == 3) stage3Color else primaryColor
                    val chipFade = if (isLit) endFadeAlpha else 1f
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = when {
                            isCurrent -> chipAccent.copy(alpha = chipFade)
                            isLit -> chipAccent.copy(alpha = 0.20f * chipFade)
                            else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                        },
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isLit) chipAccent.copy(alpha = 0.65f * chipFade) else extendedColors.cardBorder
                        )
                    ) {
                        Text(
                            text = stage.toString(),
                            style = NumericSmallStyle,
                            color = when {
                                isCurrent -> MaterialTheme.colorScheme.onPrimary.copy(alpha = chipFade)
                                isLit -> chipAccent.copy(alpha = chipFade)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

// =================================================================================================
// 3.4 Bottom Edge Trigger Real-Time Animation Preview (~136dp, Restrained Pill + Long-Press)
// =================================================================================================

@Composable
fun BottomTriggerLivePreview(
    config: EdgeConfig,
    modifier: Modifier = Modifier
) {
    val extendedColors = LocalEdgeFlowColors.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val stage3Color = extendedColors.dedicatedZoneAccent
    val surfaceHigh = MaterialTheme.colorScheme.surfaceVariant
    val bgColor = MaterialTheme.colorScheme.background
    val outlineColor = MaterialTheme.colorScheme.outline

    val elapsedMs = rememberLifecycleDemoClockMs(enabled = true)
    val hasStage3 = !config.bottomStage3Action.isNone

    // Build dynamic sequence of demo phases based on bottomTriggerMode + bottomLongPressEnabled
    // 0 = Distance, 1 = Hold, 2 = Stationary Long-Press on bar
    val phases = remember(config.bottomTriggerMode, config.bottomLongPressEnabled) {
        buildList {
            when (config.bottomTriggerMode) {
                SecondaryTriggerMode.DISTANCE -> add(0)
                SecondaryTriggerMode.HOLD -> add(1)
                SecondaryTriggerMode.BOTH -> {
                    add(0)
                    add(1)
                }
            }
            if (config.bottomLongPressEnabled) {
                add(2)
            }
        }
    }

    val cycleMs = 4400L
    val phaseKind = phases[((elapsedMs / cycleMs).toInt()) % phases.size]
    val t = (elapsedMs % cycleMs).toFloat() / cycleMs.toFloat()

    val endFadeAlpha = when {
        t < 0.80f -> 1f
        t < 0.93f -> (1f - (t - 0.80f) / 0.13f).coerceIn(0f, 1f)
        else -> 0f
    }

    val s2YFrac = config.bottomStage2DistanceRatio.coerceIn(0.14f, 0.72f)
    val s3YFrac = config.bottomStage3DistanceRatio.coerceIn(s2YFrac + 0.08f, 0.84f)
    val maxUpFrac = if (hasStage3) (s3YFrac + 0.07f).coerceAtMost(0.90f) else (s2YFrac + 0.10f).coerceAtMost(0.84f)

    val holdS2Dur = (config.bottomStage2HoldMs / 1900f).coerceIn(0.11f, 0.21f)
    val holdS3Dur = if (hasStage3) {
        ((config.bottomStage3HoldMs - config.bottomStage2HoldMs).coerceAtLeast(150L) / 1900f)
            .coerceIn(0.11f, 0.20f)
    } else 0f
    val holdStart = 0.18f
    val holdS2At = holdStart + holdS2Dur
    val holdS3At = (holdS2At + holdS3Dur).coerceAtMost(0.60f)

    val lpDurFrac = (config.bottomLongPressDurationMs / 1100f).coerceIn(0.24f, 0.46f)
    val lpStart = 0.12f
    val lpEnd = (lpStart + lpDurFrac).coerceAtMost(0.62f)

    val upwardFrac = when (phaseKind) {
        0 -> when {
            t < 0.06f -> 0f
            t < 0.58f -> lerp(0f, maxUpFrac, easeInOutCubic((t - 0.06f) / 0.52f))
            t < 0.93f -> maxUpFrac
            else -> 0f
        }
        1 -> {
            val shallowUp = (s2YFrac * 0.56f).coerceIn(0.13f, 0.24f)
            when {
                t < 0.06f -> 0f
                t < holdStart -> lerp(0f, shallowUp, easeOutCubic((t - 0.06f) / (holdStart - 0.06f)))
                t < 0.93f -> shallowUp
                else -> 0f
            }
        }
        else -> 0f // Stationary long-press on the bottom bar
    }

    val activeStage = when (phaseKind) {
        0 -> when {
            t >= 0.93f || t < 0.10f -> 0
            hasStage3 && upwardFrac >= s3YFrac -> 3
            upwardFrac >= s2YFrac -> 2
            upwardFrac >= 0.09f -> 1
            else -> 0
        }
        1 -> when {
            t >= 0.93f || t < 0.10f -> 0
            hasStage3 && t >= holdS3At -> 3
            t >= holdS2At -> 2
            t >= 0.12f -> 1
            else -> 0
        }
        else -> 0
    }

    val longPressProgress = if (phaseKind == 2 && t in lpStart..0.93f) {
        ((t - lpStart) / (lpEnd - lpStart)).coerceIn(0f, 1f) * endFadeAlpha
    } else 0f

    val holdRingProgress = if (phaseKind == 1 && t in holdStart..0.80f) {
        when {
            t < holdS2At -> ((t - holdStart) / holdS2Dur).coerceIn(0f, 1f)
            hasStage3 && t < holdS3At -> ((t - holdS2At) / (holdS3At - holdS2At).coerceAtLeast(0.05f)).coerceIn(0f, 1f)
            else -> 0f
        }
    } else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(136.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(surfaceHigh.copy(alpha = 0.62f), bgColor)
                )
            )
            .border(
                width = 1.dp,
                color = outlineColor.copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("bottom_trigger_live_preview")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val dashS2 = PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f)
            val dashS3 = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)

            val barBaseW = (w * config.bottomBarWidthPercent * 0.72f).coerceIn(48.dp.toPx(), w * 0.74f)
            val maxShift = ((w - barBaseW) * 0.45f).coerceAtLeast(0f)
            val barCx = (w * 0.5f + w * config.bottomBarHorizontalOffsetPercent * 0.6f)
                .coerceIn(w * 0.5f - maxShift, w * 0.5f + maxShift)
            val bottomOffset = ((config.bottomBarBottomOffsetDp / 32f) * 14.dp.toPx()).coerceIn(0f, 14.dp.toPx())
            val idleBottomY = h - 10.dp.toPx() - bottomOffset
            val usableH = (idleBottomY - 26.dp.toPx()).coerceAtLeast(36.dp.toPx())

            // Stage 2 & Stage 3 horizontal dashed lines
            if (config.bottomTriggerMode != SecondaryTriggerMode.HOLD && phaseKind != 2) {
                val y2 = idleBottomY - usableH * s2YFrac
                drawLine(
                    color = primaryColor.copy(alpha = if (activeStage == 2) 0.92f else 0.36f),
                    start = Offset(w * 0.14f, y2),
                    end = Offset(w * 0.86f, y2),
                    strokeWidth = (if (activeStage == 2) 1.8 else 1.1).dp.toPx(),
                    pathEffect = dashS2
                )
                if (hasStage3) {
                    val y3 = idleBottomY - usableH * s3YFrac
                    drawLine(
                        color = stage3Color.copy(alpha = if (activeStage == 3) 0.95f else 0.42f),
                        start = Offset(w * 0.14f, y3),
                        end = Offset(w * 0.86f, y3),
                        strokeWidth = (if (activeStage == 3) 1.9 else 1.1).dp.toPx(),
                        pathEffect = dashS3
                    )
                }
            }

            // Native-style restrained rubber-band vertical lift (strictly small, never over-lifted)
            val maxVisualLift = 13.dp.toPx()
            val rubberLift = if (upwardFrac > 0.005f) {
                maxVisualLift * (1f - exp((-upwardFrac / 0.34f).toDouble()).toFloat())
            } else 0f

            val lpStretch = if (phaseKind == 2 && longPressProgress > 0f) {
                1f + 0.15f * easeOutCubic(longPressProgress)
            } else 1f
            val swipeStretch = 1f + 0.09f * (rubberLift / maxVisualLift) +
                (if (activeStage >= 2) 0.04f else 0f) + (if (activeStage >= 3) 0.03f else 0f)

            val pillW = (barBaseW * (if (phaseKind == 2) lpStretch else swipeStretch)).coerceAtMost(w * 0.86f)
            val pillH = 4.5.dp.toPx() * (1f + 0.16f * (rubberLift / maxVisualLift) + 0.22f * longPressProgress)
            val pillBottomY = idleBottomY - rubberLift
            val pillTopY = pillBottomY - pillH
            val pillLeft = barCx - pillW * 0.5f

            // High-contrast dark neutral charcoal bottom pill + light outer rim
            val neutralIdle = Color(0xFF4A505A)
            val neutralLift = Color(0xFF3A3F47)
            val neutralActive = Color(0xFF2D3138)
            val neutralStage3 = Color(0xFF1F2329)
            val neutralRim = Color(0xFFD8DDE6)

            val pillColor = when {
                activeStage >= 3 -> neutralStage3
                activeStage == 2 || longPressProgress > 0.05f -> neutralActive
                activeStage == 1 -> neutralLift
                else -> neutralIdle
            }
            val pillAlpha = (config.bottomPillAlpha.coerceAtLeast(0.62f) +
                0.28f * (rubberLift / maxVisualLift) + 0.22f * longPressProgress).coerceIn(0.60f, 1.0f)

            // Subtle neutral luminous halo during Stage 2/3 or stationary long-press
            if (activeStage >= 2 || longPressProgress > 0.05f) {
                val glowAlpha = if (phaseKind == 2) 0.30f * easeOutCubic(longPressProgress) else 0.18f
                drawRoundRect(
                    color = neutralRim.copy(alpha = glowAlpha),
                    topLeft = Offset(pillLeft - 4.dp.toPx(), pillTopY - 2.5.dp.toPx()),
                    size = Size(pillW + 8.dp.toPx(), pillH + 5.dp.toPx()),
                    cornerRadius = CornerRadius((pillH + 5.dp.toPx()) * 0.5f, (pillH + 5.dp.toPx()) * 0.5f)
                )
            }

            drawRoundRect(
                color = pillColor.copy(alpha = pillAlpha),
                topLeft = Offset(pillLeft, pillTopY),
                size = Size(pillW, pillH),
                cornerRadius = CornerRadius(pillH * 0.5f, pillH * 0.5f)
            )
            drawRoundRect(
                color = neutralRim.copy(alpha = (pillAlpha * 0.50f).coerceAtMost(0.55f)),
                topLeft = Offset(pillLeft, pillTopY),
                size = Size(pillW, pillH),
                cornerRadius = CornerRadius(pillH * 0.5f, pillH * 0.5f),
                style = Stroke(width = 1.dp.toPx())
            )

            // Center-out luminous shimmer on stationary long-press (Circle to Search etc.)
            if (phaseKind == 2 && longPressProgress > 0.02f) {
                val shimmerW = pillW * easeOutCubic(longPressProgress)
                drawRoundRect(
                    color = neutralRim.copy(alpha = (0.45f + 0.45f * longPressProgress).coerceAtMost(0.90f)),
                    topLeft = Offset(barCx - shimmerW * 0.5f, pillTopY),
                    size = Size(shimmerW, pillH),
                    cornerRadius = CornerRadius(pillH * 0.5f, pillH * 0.5f)
                )
            }

            // Discreet floating stage dots pill directly above the lifted bottom bar
            if (activeStage >= 1) {
                val dotCount = if (hasStage3) 3 else 2
                val badgeW = (if (hasStage3) 38 else 30).dp.toPx()
                val badgeH = 14.dp.toPx()
                val badgeTop = pillTopY - badgeH - 7.dp.toPx()
                drawRoundRect(
                    color = Color(0xFF171A21).copy(alpha = 0.86f),
                    topLeft = Offset(barCx - badgeW * 0.5f, badgeTop),
                    size = Size(badgeW, badgeH),
                    cornerRadius = CornerRadius(badgeH * 0.5f, badgeH * 0.5f)
                )
                drawRoundRect(
                    color = (if (activeStage >= 3) stage3Color else primaryColor).copy(alpha = 0.72f),
                    topLeft = Offset(barCx - badgeW * 0.5f, badgeTop),
                    size = Size(badgeW, badgeH),
                    cornerRadius = CornerRadius(badgeH * 0.5f, badgeH * 0.5f),
                    style = Stroke(width = 1.dp.toPx())
                )
                val spacing = 7.dp.toPx()
                val startX = barCx - (dotCount - 1) * spacing * 0.5f
                val dotY = badgeTop + badgeH * 0.5f
                for (i in 0 until dotCount) {
                    val lit = (i + 1) <= activeStage
                    val dotColor = if (lit) {
                        if (i == 2) stage3Color else primaryColor
                    } else {
                        Color.White.copy(alpha = 0.34f)
                    }
                    drawCircle(
                        color = dotColor,
                        radius = if (lit) 2.2.dp.toPx() else 1.6.dp.toPx(),
                        center = Offset(startX + i * spacing, dotY)
                    )
                }
            }

            // Ghost finger + trail
            val fingerY = idleBottomY - usableH * upwardFrac
            val fingerPos = Offset(barCx, fingerY)
            val fingerAlpha = when {
                t < 0.05f -> t / 0.05f
                t < 0.80f -> 1f
                t < 0.93f -> 1f - (t - 0.80f) / 0.13f
                else -> 0f
            }
            val trail = if (phaseKind == 0 && t in 0.06f..0.80f) {
                List(14) { i ->
                    val s = (t - 0.12f * (1f - i / 13f)).coerceAtLeast(0.06f)
                    val su = if (s < 0.58f) lerp(0f, maxUpFrac, easeInOutCubic((s - 0.06f) / 0.52f)) else maxUpFrac
                    Offset(barCx, idleBottomY - usableH * su)
                }
            } else if (phaseKind == 1 && t in 0.06f..holdStart) {
                val shallowUp = (s2YFrac * 0.56f).coerceIn(0.13f, 0.24f)
                List(10) { i ->
                    val s = (t - 0.06f * (1f - i / 9f)).coerceAtLeast(0.06f)
                    val su = lerp(0f, shallowUp, easeOutCubic((s - 0.06f) / (holdStart - 0.06f)))
                    Offset(barCx, idleBottomY - usableH * su)
                }
            } else emptyList()

            val ripple = if (phaseKind == 2 && t in lpEnd..(lpEnd + 0.16f)) {
                (t - lpEnd) / 0.16f
            } else 0f

            drawGhostFinger(
                center = fingerPos,
                pressed = t in 0.05f..0.80f,
                alpha = fingerAlpha,
                accentColor = if (activeStage >= 3) stage3Color else primaryColor,
                trailPoints = trail,
                holdProgress = if (phaseKind == 1) holdRingProgress else 0f,
                rippleProgress = ripple
            )
        }

        // Top overlay row: Stage chips (or Long-Press chip), zero timer/metric text
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            if (phaseKind == 2) {
                val fired = t >= lpEnd && endFadeAlpha > 0.05f
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (fired) primaryColor.copy(alpha = endFadeAlpha) else MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.55f * endFadeAlpha.coerceAtLeast(0.4f)))
                ) {
                    Text(
                        text = stringResource(R.string.demo_badge_bottom_long_press),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fired) MaterialTheme.colorScheme.onPrimary.copy(alpha = endFadeAlpha) else primaryColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp)
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val maxStage = if (hasStage3) 3 else 2
                    for (stage in 1..maxStage) {
                        val isLit = activeStage >= stage && endFadeAlpha > 0.05f
                        val isCurrent = activeStage == stage && endFadeAlpha > 0.05f
                        val chipAccent = if (stage == 3) stage3Color else primaryColor
                        val chipFade = if (isLit) endFadeAlpha else 1f
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = when {
                                isCurrent -> chipAccent.copy(alpha = chipFade)
                                isLit -> chipAccent.copy(alpha = 0.20f * chipFade)
                                else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                            },
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isLit) chipAccent.copy(alpha = 0.65f * chipFade) else extendedColors.cardBorder
                            )
                        ) {
                            Text(
                                text = stage.toString(),
                                style = NumericSmallStyle,
                                color = when {
                                    isCurrent -> MaterialTheme.colorScheme.onPrimary.copy(alpha = chipFade)
                                    isLit -> chipAccent.copy(alpha = chipFade)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
