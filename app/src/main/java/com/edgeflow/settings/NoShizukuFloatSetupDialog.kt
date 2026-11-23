package com.edgeflow.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import com.example.ui.icons.HinataIcons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.edgeflow.data.ConfigStore
import com.edgeflow.data.DeviceCompatibilityHelper
import com.edgeflow.data.EdgeAction
import com.edgeflow.data.EdgeActionType
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.EdgeConfigProvider
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import com.edgeflow.service.shizuku.ShizukuStatus
import com.example.R
import com.example.ui.theme.LocalEdgeFlowColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun isFloatingWindowAction(action: EdgeAction): Boolean =
    action.type == EdgeActionType.FLOAT_CURRENT_APP ||
        action.type == EdgeActionType.LAUNCH_APP_FLOATING

internal fun isShizukuDisconnected(context: Context): Boolean =
    ShizukuShellExecutor.checkStatus(context) != ShizukuStatus.CONNECTED

internal fun shouldShowNoShizukuFloatNotice(context: Context): Boolean =
    DeviceCompatibilityHelper.resolveCapabilities().supportsHyperOsNativeFloatingWindow &&
        isShizukuDisconnected(context) &&
        !SettingsUiPreferences.hasDismissedCcFloatNotice(context)

private fun openAccessibilitySettingsSafe(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

private fun formatCalibrationMethodLabel(context: Context, method: String): String = when (method) {
    EdgeConfig.CC_TILE_METHOD_OCR -> context.getString(R.string.cc_float_method_ocr)
    EdgeConfig.CC_TILE_METHOD_MANUAL_TAP -> context.getString(R.string.cc_float_method_manual)
    EdgeConfig.CC_TILE_METHOD_NODE -> context.getString(R.string.cc_float_method_node)
    else -> context.getString(R.string.cc_float_method_auto)
}

/**
 * Compact status pill indicating whether Control Center floating-window tile coordinates have
 * been memorized via NODE auto-scan, OCR, or 1-tap Manual calibration.
 */
@Composable
fun CcFloatTileStatusBadge(
    config: EdgeConfig,
    modifier: Modifier = Modifier
) {
    val extendedColors = LocalEdgeFlowColors.current
    val hasCoords = config.hasSavedCcFloatTileCoords()
    val labelRes = when {
        !hasCoords -> R.string.cc_float_status_unset
        config.ccFloatTileDetectionMethod == EdgeConfig.CC_TILE_METHOD_OCR -> R.string.cc_float_status_ocr
        config.ccFloatTileDetectionMethod == EdgeConfig.CC_TILE_METHOD_MANUAL_TAP -> R.string.cc_float_status_manual
        else -> R.string.cc_float_status_auto
    }
    val fgColor = when {
        !hasCoords -> MaterialTheme.colorScheme.onSurfaceVariant
        config.ccFloatTileDetectionMethod == EdgeConfig.CC_TILE_METHOD_NODE ||
            config.ccFloatTileDetectionMethod == EdgeConfig.CC_TILE_METHOD_AUTO -> extendedColors.statusActive
        else -> MaterialTheme.colorScheme.primary
    }
    val bgColor = when {
        !hasCoords -> MaterialTheme.colorScheme.surfaceVariant
        config.ccFloatTileDetectionMethod == EdgeConfig.CC_TILE_METHOD_NODE ||
            config.ccFloatTileDetectionMethod == EdgeConfig.CC_TILE_METHOD_AUTO -> extendedColors.statusActiveContainer
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
    }

    Surface(
        modifier = modifier.testTag("cc_float_tile_status_badge"),
        shape = RoundedCornerShape(999.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(fgColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = fgColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

private const val DEMO_LOOP_DURATION_SEC = 5.4f
private const val DEMO_PHASE1_END_SEC = 1.6f
private const val DEMO_PHASE2_END_SEC = 3.6f

private fun easeOutCubic(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    val inv = 1f - c
    return 1f - inv * inv * inv
}

private fun easeInOutCubic(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return if (c < 0.5f) 4f * c * c * c else 1f - (-2f * c + 2f).let { it * it * it } / 2f
}

/**
 * Lifecycle-bound 3-phase mini-phone animation demonstrating:
 * 1) Swiping down from the top-right corner to open Control Center
 * 2) Placing the "Floating windows" tile on Page 1 (with page dots & peeking page 2)
 * 3) Why moving tiles later requires tapping "Reload button position".
 *
 * Uses `repeatOnLifecycle(Lifecycle.State.STARTED)` + `withFrameNanos` for 0% background CPU.
 */
@Composable
fun ControlCenterFirstPageDemoAnimation(
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val extendedColors = LocalEdgeFlowColors.current
    var phaseTimeSec by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastFrameNs = 0L
            while (isActive) {
                withFrameNanos { nowNs ->
                    if (lastFrameNs != 0L) {
                        val dtSec = ((nowNs - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
                        phaseTimeSec = (phaseTimeSec + dtSec) % DEMO_LOOP_DURATION_SEC
                    }
                    lastFrameNs = nowNs
                }
            }
        }
    }

    val activePhase = when {
        phaseTimeSec < DEMO_PHASE1_END_SEC -> 0
        phaseTimeSec < DEMO_PHASE2_END_SEC -> 1
        else -> 2
    }
    val captionRes = when (activePhase) {
        0 -> R.string.cc_demo_phase1_caption
        1 -> R.string.cc_demo_phase2_caption
        else -> R.string.cc_demo_phase3_caption
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val warningColor = MaterialTheme.colorScheme.error
    val cardBorderColor = extendedColors.cardBorder

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("cc_float_demo_animation_card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Dynamic 1-line phase caption
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (activePhase == 2) warningColor else primaryColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(captionRes),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            // 178.dp live mini-phone visual demo canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(178.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(surfaceColor.copy(alpha = 0.72f))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val phoneW = (w * 0.52f).coerceIn(136.dp.toPx(), 196.dp.toPx())
                    val phoneH = h - 16.dp.toPx()
                    val phoneLeft = (w - phoneW) * 0.5f
                    val phoneTop = 8.dp.toPx()
                    val phoneRadius = 18.dp.toPx()

                    // Phone body frame
                    drawRoundRect(
                        color = surfaceVariantColor.copy(alpha = 0.55f),
                        topLeft = Offset(phoneLeft, phoneTop),
                        size = Size(phoneW, phoneH),
                        cornerRadius = CornerRadius(phoneRadius, phoneRadius)
                    )
                    drawRoundRect(
                        color = onSurfaceVariantColor.copy(alpha = 0.35f),
                        topLeft = Offset(phoneLeft, phoneTop),
                        size = Size(phoneW, phoneH),
                        cornerRadius = CornerRadius(phoneRadius, phoneRadius),
                        style = Stroke(width = 1.6.dp.toPx())
                    )

                    // Status bar indicators (clock left, signal/battery top-right)
                    val statusY = phoneTop + 10.dp.toPx()
                    drawRoundRect(
                        color = onSurfaceVariantColor.copy(alpha = 0.40f),
                        topLeft = Offset(phoneLeft + 12.dp.toPx(), statusY),
                        size = Size(18.dp.toPx(), 4.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                    drawRoundRect(
                        color = onSurfaceVariantColor.copy(alpha = 0.50f),
                        topLeft = Offset(phoneLeft + phoneW - 30.dp.toPx(), statusY),
                        size = Size(18.dp.toPx(), 4.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )

                    // Underlying dummy app content lines
                    val appContentTop = phoneTop + 28.dp.toPx()
                    for (line in 0 until 4) {
                        val lineW = phoneW * (if (line == 0) 0.55f else if (line == 3) 0.42f else 0.72f)
                        drawRoundRect(
                            color = onSurfaceVariantColor.copy(alpha = 0.14f),
                            topLeft = Offset(phoneLeft + 14.dp.toPx(), appContentTop + line * 18.dp.toPx()),
                            size = Size(lineW, 7.dp.toPx()),
                            cornerRadius = CornerRadius(3.5.dp.toPx(), 3.5.dp.toPx())
                        )
                    }

                    // Control Center drawer slide progress
                    val drawerProgress = when (activePhase) {
                        0 -> easeOutCubic((phaseTimeSec / 1.15f).coerceIn(0f, 1f))
                        else -> 1f
                    }
                    val drawerPadding = 4.dp.toPx()
                    val drawerLeft = phoneLeft + drawerPadding
                    val drawerW = phoneW - drawerPadding * 2f
                    val drawerFullH = phoneH - drawerPadding * 2f
                    val drawerH = drawerFullH * drawerProgress

                    if (drawerH > 6.dp.toPx()) {
                        // Frosted Control Center panel
                        drawRoundRect(
                            color = surfaceColor.copy(alpha = 0.94f),
                            topLeft = Offset(drawerLeft, phoneTop + drawerPadding),
                            size = Size(drawerW, drawerH),
                            cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx())
                        )
                        drawRoundRect(
                            color = cardBorderColor,
                            topLeft = Offset(drawerLeft, phoneTop + drawerPadding),
                            size = Size(drawerW, drawerH),
                            cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }

                    // Inside Control Center once mostly open
                    val contentAlpha = ((drawerProgress - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    if (contentAlpha > 0.02f) {
                        val innerPad = 10.dp.toPx()
                        val gridLeft = drawerLeft + innerPad
                        val gridTop = phoneTop + drawerPadding + 14.dp.toPx()
                        val gridW = drawerW - innerPad * 2f - 6.dp.toPx() // Leave right edge for peeking Page 2
                        val bigCardH = 22.dp.toPx()
                        val bigGap = 6.dp.toPx()
                        val bigCardW = (gridW - bigGap) * 0.5f

                        // 2 large top cards (Wi-Fi / Cellular)
                        drawRoundRect(
                            color = primaryColor.copy(alpha = 0.22f * contentAlpha),
                            topLeft = Offset(gridLeft, gridTop),
                            size = Size(bigCardW, bigCardH),
                            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                        )
                        drawRoundRect(
                            color = surfaceVariantColor.copy(alpha = 0.85f * contentAlpha),
                            topLeft = Offset(gridLeft + bigCardW + bigGap, gridTop),
                            size = Size(bigCardW, bigCardH),
                            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                        )

                        // Peeking Page 2 edge on the right side to clarify "Page 1 vs Page 2"
                        val peekLeft = drawerLeft + drawerW - 6.dp.toPx()
                        drawRoundRect(
                            color = onSurfaceVariantColor.copy(alpha = 0.16f * contentAlpha),
                            topLeft = Offset(peekLeft, gridTop + bigCardH + 10.dp.toPx()),
                            size = Size(4.dp.toPx(), 54.dp.toPx()),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                        )

                        // 4x2 Quick Toggle tile grid on Page 1
                        val cols = 4
                        val rows = 2
                        val tileGap = 6.dp.toPx()
                        val tilesTop = gridTop + bigCardH + 10.dp.toPx()
                        val tileSize = ((gridW - tileGap * (cols - 1)) / cols).coerceAtMost(26.dp.toPx())
                        val totalGridW = tileSize * cols + tileGap * (cols - 1)
                        val tilesStartX = gridLeft + (gridW - totalGridW) * 0.5f

                        // Phase 3 tile slide animation: tile moves from (col 1, row 0) -> (col 3, row 1)
                        val reorderT = if (activePhase == 2) {
                            easeInOutCubic(((phaseTimeSec - DEMO_PHASE2_END_SEC - 0.2f) / 1.0f).coerceIn(0f, 1f))
                        } else {
                            0f
                        }

                        fun tileCenter(col: Int, row: Int): Offset = Offset(
                            x = tilesStartX + col * (tileSize + tileGap) + tileSize * 0.5f,
                            y = tilesTop + row * (tileSize + tileGap) + tileSize * 0.5f
                        )

                        val slotA = tileCenter(1, 0)
                        val slotB = tileCenter(3, 1)

                        for (r in 0 until rows) {
                            for (c in 0 until cols) {
                                if (r == 0 && c == 1) continue // Floating Windows tile drawn on top
                                val center = tileCenter(c, r)
                                val radius = tileSize * 0.46f
                                drawCircle(
                                    color = surfaceVariantColor.copy(alpha = 0.90f * contentAlpha),
                                    radius = radius,
                                    center = center
                                )
                            }
                        }

                        // Highlighted "Floating Windows" tile
                        val floatCenter = Offset(
                            x = slotA.x + (slotB.x - slotA.x) * reorderT,
                            y = slotA.y + (slotB.y - slotA.y) * reorderT
                        )
                        val floatRadius = tileSize * 0.48f
                        val tileAccent = if (activePhase == 2 && reorderT > 0.2f) warningColor else primaryColor

                        // Ghost dashed outline at original slot during Phase 3 reorder
                        if (activePhase == 2 && reorderT > 0.05f) {
                            drawCircle(
                                color = warningColor.copy(alpha = 0.45f * contentAlpha),
                                radius = floatRadius,
                                center = slotA,
                                style = Stroke(width = 1.4.dp.toPx())
                            )
                        }

                        // Pulsing highlight halo in Phase 2 & Phase 3
                        if (activePhase >= 1) {
                            val pulse = 0.5f + 0.5f * sin(phaseTimeSec * 2f * PI.toFloat() * 1.15f)
                            drawCircle(
                                color = tileAccent.copy(alpha = (0.28f - 0.14f * pulse) * contentAlpha),
                                radius = floatRadius + (3.dp.toPx() + 4.dp.toPx() * pulse),
                                center = floatCenter
                            )
                        }

                        // Floating tile body + accent stroke
                        drawCircle(
                            color = if (activePhase == 2 && reorderT > 0.2f) {
                                warningColor.copy(alpha = 0.22f * contentAlpha)
                            } else {
                                primaryContainerColor.copy(alpha = contentAlpha)
                            },
                            radius = floatRadius,
                            center = floatCenter
                        )
                        drawCircle(
                            color = tileAccent.copy(alpha = contentAlpha),
                            radius = floatRadius,
                            center = floatCenter,
                            style = Stroke(width = 1.8.dp.toPx())
                        )

                        // Inner PiP (mini floating window) icon glyph
                        val pipW = floatRadius * 0.96f
                        val pipH = floatRadius * 0.72f
                        drawRoundRect(
                            color = tileAccent.copy(alpha = contentAlpha),
                            topLeft = Offset(floatCenter.x - pipW * 0.5f, floatCenter.y - pipH * 0.5f),
                            size = Size(pipW, pipH),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                            style = Stroke(width = 1.4.dp.toPx())
                        )
                        drawRoundRect(
                            color = tileAccent.copy(alpha = contentAlpha),
                            topLeft = Offset(floatCenter.x + pipW * 0.04f, floatCenter.y - pipH * 0.04f),
                            size = Size(pipW * 0.36f, pipH * 0.38f),
                            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
                        )

                        // Page indicator dots: ● ○ (Page 1 active)
                        val dotsY = tilesTop + rows * (tileSize + tileGap) + 10.dp.toPx()
                        val dotsCenterX = drawerLeft + drawerW * 0.5f
                        drawCircle(
                            color = primaryColor.copy(alpha = contentAlpha),
                            radius = 3.2.dp.toPx(),
                            center = Offset(dotsCenterX - 6.dp.toPx(), dotsY)
                        )
                        drawCircle(
                            color = onSurfaceVariantColor.copy(alpha = 0.35f * contentAlpha),
                            radius = 2.6.dp.toPx(),
                            center = Offset(dotsCenterX + 6.dp.toPx(), dotsY)
                        )
                    }

                    // Phase 1: Ghost finger swiping down from top-right corner
                    if (activePhase == 0) {
                        val swipeT = easeOutCubic((phaseTimeSec / 1.25f).coerceIn(0f, 1f))
                        val fingerAlpha = if (phaseTimeSec < 1.35f) 1f else ((1.6f - phaseTimeSec) / 0.25f).coerceIn(0f, 1f)
                        val startX = phoneLeft + phoneW - 18.dp.toPx()
                        val startY = phoneTop + 10.dp.toPx()
                        val endY = phoneTop + phoneH * 0.56f
                        val curY = startY + (endY - startY) * swipeT

                        // Swipe trail line
                        drawLine(
                            color = primaryColor.copy(alpha = 0.50f * fingerAlpha),
                            start = Offset(startX, startY),
                            end = Offset(startX, curY),
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        // Ghost finger ripple & core
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.22f * fingerAlpha),
                            radius = 12.dp.toPx(),
                            center = Offset(startX, curY)
                        )
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.92f * fingerAlpha),
                            radius = 5.5.dp.toPx(),
                            center = Offset(startX, curY)
                        )
                    }
                }

                // Subtle localized Page 1 / Warning pill badge overlay
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = if (activePhase == 2) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                    }
                ) {
                    Text(
                        text = if (activePhase == 2) {
                            stringResource(R.string.cc_demo_tile_floating_short) + " ⚠"
                        } else {
                            stringResource(R.string.cc_demo_page1_label)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (activePhase == 2) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        maxLines = 1
                    )
                }
            }

            // 3-step phase progress indicator dots
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (i in 0..2) {
                    val active = i == activePhase
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (active) 18.dp else 6.dp, height = 6.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    active && i == 2 -> warningColor
                                    active -> primaryColor
                                    else -> onSurfaceVariantColor.copy(alpha = 0.28f)
                                }
                            )
                    )
                }
            }
        }
    }
}

/**
 * 3-point beginner explanation answering:
 * 1) What is Control Center?
 * 2) What does "Page 1" mean & how to move the tile there?
 * 3) Why rearranging tiles later requires reloading button position.
 */
@Composable
fun CcBeginnerGuidePoints(
    modifier: Modifier = Modifier
) {
    val extendedColors = LocalEdgeFlowColors.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("cc_float_beginner_guide_points"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, extendedColors.cardBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CcBeginnerGuideRow(
                icon = HinataIcons.SwipeDown,
                tint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.cc_guide_what_is_cc_title),
                desc = stringResource(R.string.cc_guide_what_is_cc_desc)
            )
            CcBeginnerGuideRow(
                icon = HinataIcons.HelpOutline,
                tint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.cc_guide_what_is_page1_title),
                desc = stringResource(R.string.cc_guide_what_is_page1_desc)
            )
            CcBeginnerGuideRow(
                icon = HinataIcons.WarningAmber,
                tint = MaterialTheme.colorScheme.error,
                title = stringResource(R.string.cc_guide_reorder_caution_title),
                desc = stringResource(R.string.cc_guide_reorder_caution_desc)
            )
        }
    }
}

@Composable
private fun CcBeginnerGuideRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    desc: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Setup & macro-caution dialog shown whenever the user assigns a Floating Window action or toggles
 * "Open in floating window" ON while Shizuku is disconnected (unless permanently dismissed).
 */
@Composable
fun NoShizukuFloatSetupDialog(
    config: EdgeConfig,
    onConfigChange: ((EdgeConfig) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val extendedColors = LocalEdgeFlowColors.current
    val scope = rememberCoroutineScope()
    var calibrating by remember { mutableStateOf(false) }

    fun triggerCalibration(method: String) {
        val (ok, statusCode) = ConfigStore.requestControlCenterTileCalibration(context, method)
        if (!ok) {
            val msgRes = if (statusCode == EdgeConfigProvider.STATUS_SERVICE_DISABLED) {
                R.string.cc_float_error_service_disabled
            } else {
                R.string.cc_float_error_service_not_bound
            }
            Toast.makeText(context, msgRes, Toast.LENGTH_LONG).show()
            if (statusCode == EdgeConfigProvider.STATUS_SERVICE_DISABLED) {
                openAccessibilitySettingsSafe(context)
            }
            return
        }
        calibrating = true
        scope.launch {
            var ticks = 0
            var maxTicks = 15
            while (ticks < maxTicks) {
                delay(400L)
                ticks++
                val state = ConfigStore.queryControlCenterCalibrationStatus(context)
                if (state.statusCode == EdgeConfigProvider.STATUS_WAITING_TAP) {
                    maxTicks = 35
                }
                if (!state.inProgress && state.statusCode != EdgeConfigProvider.STATUS_RUNNING &&
                    state.statusCode != EdgeConfigProvider.STATUS_WAITING_TAP
                ) {
                    break
                }
            }
            calibrating = false
            onConfigChange?.invoke(ConfigStore.load(context))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Icon(
                imageVector = HinataIcons.PictureInPictureAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.cc_float_setup_dialog_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.cc_float_calibration_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    CcFloatTileStatusBadge(config = config)
                }

                // Live 3-phase Control Center visual demo + 3-step beginner guide
                ControlCenterFirstPageDemoAnimation()
                CcBeginnerGuidePoints()

                // Shizuku ON vs OFF comparison card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, extendedColors.cardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = extendedColors.statusActive,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.cc_float_setup_shizuku_on_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.cc_float_setup_shizuku_on_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = HinataIcons.PictureInPictureAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.cc_float_setup_shizuku_off_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.cc_float_setup_shizuku_off_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Prominent amber/red macro button-swap caution box
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cc_float_macro_warning_box"),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                    border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = HinataIcons.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.cc_float_macro_warning_full),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.cc_float_setup_step1),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Button(
                    onClick = { triggerCalibration(EdgeConfig.CC_TILE_METHOD_NODE) },
                    enabled = !calibrating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("dialog_cc_float_internal_scan_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    if (calibrating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.cc_float_btn_read_internal),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = { triggerCalibration(EdgeConfig.CC_TILE_METHOD_OCR) },
                    enabled = !calibrating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("dialog_cc_float_ocr_scan_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                ) {
                    Icon(
                        imageVector = HinataIcons.CenterFocusWeak,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.cc_float_btn_read_ocr),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    SettingsUiPreferences.saveCcFloatNoticeDismissed(context, true)
                    onDismiss()
                },
                modifier = Modifier.testTag("dialog_cc_float_dont_show_again_button")
            ) {
                Text(text = stringResource(R.string.cc_float_btn_dont_show_again))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_cc_float_close_button")
            ) {
                Text(text = stringResource(R.string.button_close))
            }
        }
    )
}

/**
 * Permanent calibration section embedded in the System tab (`ShizukuNavBarCard`) so users who
 * rearrange their Control Center tiles later can immediately reload or OCR-scan the new position.
 */
@Composable
fun CcFloatTileCalibrationSection(
    config: EdgeConfig,
    snackbarHostState: SnackbarHostState,
    onConfigChange: (EdgeConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val extendedColors = LocalEdgeFlowColors.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var calibrating by remember { mutableStateOf(false) }
    var showVisualGuide by rememberSaveable { mutableStateOf(false) }

    // Reload persisted coordinates whenever user returns to Settings (e.g., after Control Center scan)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val latest = ConfigStore.load(context)
                if (latest.ccFloatTileNormX != config.ccFloatTileNormX ||
                    latest.ccFloatTileNormY != config.ccFloatTileNormY ||
                    latest.ccFloatTileDetectionMethod != config.ccFloatTileDetectionMethod
                ) {
                    onConfigChange(latest)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun triggerCalibration(method: String) {
        val (ok, statusCode) = ConfigStore.requestControlCenterTileCalibration(context, method)
        if (!ok) {
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                if (statusCode == EdgeConfigProvider.STATUS_SERVICE_DISABLED) {
                    val action = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.cc_float_error_service_disabled),
                        actionLabel = context.getString(R.string.cc_float_action_open_accessibility),
                        duration = SnackbarDuration.Long
                    )
                    if (action == SnackbarResult.ActionPerformed) {
                        openAccessibilitySettingsSafe(context)
                    }
                } else {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.cc_float_error_service_not_bound),
                        duration = SnackbarDuration.Long
                    )
                }
            }
            return
        }

        calibrating = true
        scope.launch {
            var ticks = 0
            var maxTicks = 15
            while (ticks < maxTicks) {
                delay(400L)
                ticks++
                val state = ConfigStore.queryControlCenterCalibrationStatus(context)
                if (state.statusCode == EdgeConfigProvider.STATUS_WAITING_TAP) {
                    maxTicks = 35
                }
                if (!state.inProgress && state.statusCode != EdgeConfigProvider.STATUS_RUNNING &&
                    state.statusCode != EdgeConfigProvider.STATUS_WAITING_TAP
                ) {
                    break
                }
            }
            calibrating = false
            onConfigChange(ConfigStore.load(context))
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("system_cc_float_calibration_section"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
        border = BorderStroke(1.dp, extendedColors.cardBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.cc_float_calibration_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                CcFloatTileStatusBadge(config = config)
            }

            // Shizuku ON vs OFF explanation summary
            Text(
                text = stringResource(R.string.cc_float_calibration_shizuku_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Expandable beginner visual demo animation & 3-point guide
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showVisualGuide = !showVisualGuide }
                    .testTag("system_cc_float_visual_guide_toggle"),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = HinataIcons.PlayCircleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (showVisualGuide) R.string.cc_demo_toggle_hide_guide
                                else R.string.cc_demo_toggle_show_guide
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = if (showVisualGuide) HinataIcons.ExpandLess else HinataIcons.ExpandMore,
                        contentDescription = stringResource(
                            if (showVisualGuide) R.string.cd_collapse else R.string.cd_expand
                        ),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            AnimatedVisibility(visible = showVisualGuide) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ControlCenterFirstPageDemoAnimation()
                    CcBeginnerGuidePoints()
                }
            }

            // Saved coordinates display + Reset button
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("system_cc_float_coords_row"),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, extendedColors.cardBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val coordText = if (config.hasSavedCcFloatTileCoords()) {
                        val xPct = (config.ccFloatTileNormX * 100f).roundToInt()
                        val yPct = (config.ccFloatTileNormY * 100f).roundToInt()
                        val methodLabel = formatCalibrationMethodLabel(context, config.ccFloatTileDetectionMethod)
                        stringResource(R.string.cc_float_saved_coords_format, xPct, yPct, methodLabel)
                    } else {
                        stringResource(R.string.cc_float_saved_coords_unset)
                    }
                    Text(
                        text = coordText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (config.hasSavedCcFloatTileCoords()) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (config.hasSavedCcFloatTileCoords()) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                onConfigChange(
                                    config.copy(
                                        ccFloatTileNormX = EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD,
                                        ccFloatTileNormY = EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD,
                                        ccFloatTileDetectionMethod = EdgeConfig.CC_TILE_METHOD_AUTO
                                    )
                                )
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("system_cc_float_reset_coords_button")
                        ) {
                            Icon(
                                imageVector = HinataIcons.DeleteOutline,
                                contentDescription = stringResource(R.string.cc_float_btn_reset_coords),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.cc_float_btn_reset_coords),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Always-visible macro tile-swap warning
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = HinataIcons.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.cc_float_macro_warning_short),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            FilledTonalButton(
                onClick = { triggerCalibration(EdgeConfig.CC_TILE_METHOD_NODE) },
                enabled = !calibrating,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("system_cc_float_reload_internal_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (calibrating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.cc_float_btn_reload_internal),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = { triggerCalibration(EdgeConfig.CC_TILE_METHOD_OCR) },
                enabled = !calibrating,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("system_cc_float_reload_ocr_button"),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
            ) {
                Icon(
                    imageVector = HinataIcons.CenterFocusWeak,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.cc_float_btn_reload_ocr),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
