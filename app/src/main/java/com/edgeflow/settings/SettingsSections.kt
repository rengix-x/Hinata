package com.edgeflow.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.Choreographer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.ui.icons.HinataIcons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.edgeflow.data.DeviceCompatibilityHelper
import com.edgeflow.data.EdgeAction
import com.edgeflow.data.EdgeActionType
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.FeedbackStyle
import com.edgeflow.data.HapticStrength
import com.edgeflow.data.ImeAvoidanceMode
import com.edgeflow.data.LandscapeMode
import com.edgeflow.data.SecondaryTriggerMode
import com.edgeflow.data.VisualTheme
import com.edgeflow.data.TranslateLanguageCatalog
import com.edgeflow.service.CircleToSearchTrigger
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import com.edgeflow.service.shizuku.ShizukuStatus
import com.example.R
import com.example.ui.theme.LocalEdgeFlowColors
import com.example.ui.theme.NumericLabelStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ZonesTuningScreen(
    config: EdgeConfig,
    onConfigChange: (EdgeConfig) -> Unit,
    onResetDefaults: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onOpenABWizard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val extendedColors = LocalEdgeFlowColors.current
    val scope = rememberCoroutineScope()
    var pickingBottomLongPress by remember { mutableStateOf(false) }
    var showNoShizukuFloatSetup by remember { mutableStateOf(false) }
    var highlightGuideline by remember { mutableStateOf(GuidelineFocus.NONE) }
    var shizukuStatus by remember { mutableStateOf(ShizukuShellExecutor.checkStatus(context)) }
    var isApplyingBackMitigation by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ShizukuShellExecutor.refreshNonProviderBinder(context)
                shizukuStatus = ShizukuShellExecutor.checkStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("zones_tuning_screen")
    ) {
        // Sticky Live Smartphone Preview (always visible at ~28% viewport height while sliders scroll)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, extendedColors.cardBorder)
        ) {
            ZonePreviewCanvas(
                config = config,
                actionLabelResolver = { resolveActionLabel(context, it) },
                highlightGuideline = highlightGuideline,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }

        HorizontalDivider(
            color = extendedColors.cardBorder,
            modifier = Modifier.padding(top = 4.dp)
        )

        // Independently scrollable tuning controls below the sticky preview
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)
        ) {
            // 0. Beginner A/B Auto-Tune Wizard (Beta) Banner Card
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenABWizard)
                        .testTag("zones_open_ab_wizard_card"),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = HinataIcons.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.ab_wizard_entry_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Text(
                                        text = stringResource(R.string.badge_beta),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = stringResource(R.string.ab_wizard_entry_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = HinataIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 1. Side Edges (Left & Right) — Independent Trigger Mode + 3-Stage Thresholds
            item {
                SectionCard(title = stringResource(R.string.section_side_edge_tuning)) {
                    ToggleRow(
                        label = stringResource(R.string.label_left_edge),
                        checked = config.leftEnabled,
                        testTag = "toggle_left_edge",
                        onCheckedChange = { onConfigChange(config.copy(leftEnabled = it)) }
                    )
                    HorizontalDivider(color = extendedColors.cardBorder)
                    ToggleRow(
                        label = stringResource(R.string.label_right_edge),
                        checked = config.rightEnabled,
                        testTag = "toggle_right_edge",
                        onCheckedChange = { onConfigChange(config.copy(rightEnabled = it)) }
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.label_side_trigger_mode),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    TriggerModeSegmentedRow(
                        selected = config.sideTriggerMode,
                        testTagPrefix = "side_trigger_mode",
                        onSelect = { onConfigChange(config.copy(sideTriggerMode = it)) }
                    )

                    Spacer(Modifier.height(12.dp))
                    SideTriggerLivePreview(config = config)

                    if (config.sideTriggerMode != SecondaryTriggerMode.HOLD) {
                        Spacer(Modifier.height(12.dp))
                        MetricSlider(
                            label = stringResource(R.string.label_side_stage2_distance),
                            valueText = "${(config.sideStage2DistanceRatio * 100).roundToInt()}%",
                            value = config.sideStage2DistanceRatio,
                            range = EdgeConfig.MIN_SIDE_STAGE2_RATIO..EdgeConfig.MAX_STAGE2_DISTANCE_RATIO,
                            testTag = "slider_side_stage2_distance",
                            onValueChange = { nextS2 ->
                                highlightGuideline = GuidelineFocus.SIDE_STAGE2
                                val adjustedS3 = max(
                                    config.sideStage3DistanceRatio,
                                    (nextS2 + EdgeConfig.MIN_STAGE_DISTANCE_GAP)
                                        .coerceAtMost(EdgeConfig.MAX_STAGE3_DISTANCE_RATIO)
                                )
                                onConfigChange(
                                    config.copy(
                                        sideStage2DistanceRatio = nextS2,
                                        sideStage3DistanceRatio = adjustedS3
                                    )
                                )
                            },
                            onValueChangeFinished = { highlightGuideline = GuidelineFocus.NONE }
                        )
                        MetricSlider(
                            label = stringResource(R.string.label_side_stage3_distance),
                            valueText = "${(config.sideStage3DistanceRatio * 100).roundToInt()}%",
                            value = config.sideStage3DistanceRatio,
                            range = EdgeConfig.MIN_SIDE_STAGE3_RATIO..EdgeConfig.MAX_STAGE3_DISTANCE_RATIO,
                            testTag = "slider_side_stage3_distance",
                            onValueChange = { rawS3 ->
                                highlightGuideline = GuidelineFocus.SIDE_STAGE3
                                val minS3 = (config.sideStage2DistanceRatio + EdgeConfig.MIN_STAGE_DISTANCE_GAP)
                                    .coerceAtMost(EdgeConfig.MAX_STAGE3_DISTANCE_RATIO)
                                onConfigChange(config.copy(sideStage3DistanceRatio = max(rawS3, minS3)))
                            },
                            onValueChangeFinished = { highlightGuideline = GuidelineFocus.NONE }
                        )
                    }

                    if (config.sideTriggerMode != SecondaryTriggerMode.DISTANCE) {
                        Spacer(Modifier.height(8.dp))
                        MetricSlider(
                            label = stringResource(R.string.label_side_stage2_hold),
                            valueText = "${config.sideStage2HoldMs} ms",
                            value = config.sideStage2HoldMs.toFloat(),
                            range = EdgeConfig.MIN_STAGE2_HOLD_MS.toFloat()..EdgeConfig.MAX_STAGE2_HOLD_MS.toFloat(),
                            testTag = "slider_side_stage2_hold",
                            onValueChange = { raw ->
                                val snapped = ((raw / 25f).roundToInt() * 25L)
                                    .coerceIn(EdgeConfig.MIN_STAGE2_HOLD_MS, EdgeConfig.MAX_STAGE2_HOLD_MS)
                                val adjustedS3 = max(
                                    config.sideStage3HoldMs,
                                    (snapped + EdgeConfig.MIN_STAGE_HOLD_GAP_MS)
                                        .coerceAtMost(EdgeConfig.MAX_STAGE3_HOLD_MS)
                                )
                                onConfigChange(
                                    config.copy(
                                        sideStage2HoldMs = snapped,
                                        sideStage3HoldMs = adjustedS3
                                    )
                                )
                            }
                        )
                        MetricSlider(
                            label = stringResource(R.string.label_side_stage3_hold),
                            valueText = "${config.sideStage3HoldMs} ms",
                            value = config.sideStage3HoldMs.toFloat(),
                            range = EdgeConfig.MIN_STAGE3_HOLD_MS.toFloat()..EdgeConfig.MAX_STAGE3_HOLD_MS.toFloat(),
                            testTag = "slider_side_stage3_hold",
                            onValueChange = { raw ->
                                val snapped = ((raw / 25f).roundToInt() * 25L)
                                    .coerceIn(EdgeConfig.MIN_STAGE3_HOLD_MS, EdgeConfig.MAX_STAGE3_HOLD_MS)
                                val minS3 = (config.sideStage2HoldMs + EdgeConfig.MIN_STAGE_HOLD_GAP_MS)
                                    .coerceAtMost(EdgeConfig.MAX_STAGE3_HOLD_MS)
                                onConfigChange(config.copy(sideStage3HoldMs = max(snapped, minS3)))
                            }
                        )
                    }

                    if (!config.hasAnySideStage3()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.hint_stage3_unassigned),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 2. Bottom Edge — Independent Trigger Mode + 3-Stage + Circle to Search Long-Press
            item {
                SectionCard(title = stringResource(R.string.section_bottom_cts)) {
                    ToggleRow(
                        label = stringResource(R.string.label_bottom_bar),
                        checked = config.bottomEnabled,
                        testTag = "toggle_bottom_bar",
                        onCheckedChange = { onConfigChange(config.copy(bottomEnabled = it)) }
                    )

                    if (config.bottomEnabled) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.label_bottom_trigger_mode),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        TriggerModeSegmentedRow(
                            selected = config.bottomTriggerMode,
                            testTagPrefix = "bottom_trigger_mode",
                            onSelect = { onConfigChange(config.copy(bottomTriggerMode = it)) }
                        )

                        Spacer(Modifier.height(12.dp))
                        BottomTriggerLivePreview(config = config)

                        if (config.bottomTriggerMode != SecondaryTriggerMode.HOLD) {
                            Spacer(Modifier.height(12.dp))
                            MetricSlider(
                                label = stringResource(R.string.label_bottom_stage2_distance),
                                valueText = "${(config.bottomStage2DistanceRatio * 100).roundToInt()}%",
                                value = config.bottomStage2DistanceRatio,
                                range = EdgeConfig.MIN_BOTTOM_STAGE2_RATIO..EdgeConfig.MAX_STAGE2_DISTANCE_RATIO,
                                testTag = "slider_bottom_stage2_distance",
                                onValueChange = { nextS2 ->
                                    highlightGuideline = GuidelineFocus.BOTTOM_STAGE2
                                    val adjustedS3 = max(
                                        config.bottomStage3DistanceRatio,
                                        (nextS2 + EdgeConfig.MIN_STAGE_DISTANCE_GAP)
                                            .coerceAtMost(EdgeConfig.MAX_STAGE3_DISTANCE_RATIO)
                                    )
                                    onConfigChange(
                                        config.copy(
                                            bottomStage2DistanceRatio = nextS2,
                                            bottomStage3DistanceRatio = adjustedS3
                                        )
                                    )
                                },
                                onValueChangeFinished = { highlightGuideline = GuidelineFocus.NONE }
                            )
                            MetricSlider(
                                label = stringResource(R.string.label_bottom_stage3_distance),
                                valueText = "${(config.bottomStage3DistanceRatio * 100).roundToInt()}%",
                                value = config.bottomStage3DistanceRatio,
                                range = EdgeConfig.MIN_BOTTOM_STAGE3_RATIO..EdgeConfig.MAX_STAGE3_DISTANCE_RATIO,
                                testTag = "slider_bottom_stage3_distance",
                                onValueChange = { rawS3 ->
                                    highlightGuideline = GuidelineFocus.BOTTOM_STAGE3
                                    val minS3 = (config.bottomStage2DistanceRatio + EdgeConfig.MIN_STAGE_DISTANCE_GAP)
                                        .coerceAtMost(EdgeConfig.MAX_STAGE3_DISTANCE_RATIO)
                                    onConfigChange(config.copy(bottomStage3DistanceRatio = max(rawS3, minS3)))
                                },
                                onValueChangeFinished = { highlightGuideline = GuidelineFocus.NONE }
                            )
                        }

                        if (config.bottomTriggerMode != SecondaryTriggerMode.DISTANCE) {
                            Spacer(Modifier.height(8.dp))
                            MetricSlider(
                                label = stringResource(R.string.label_bottom_stage2_hold),
                                valueText = "${config.bottomStage2HoldMs} ms",
                                value = config.bottomStage2HoldMs.toFloat(),
                                range = EdgeConfig.MIN_STAGE2_HOLD_MS.toFloat()..EdgeConfig.MAX_STAGE2_HOLD_MS.toFloat(),
                                testTag = "slider_bottom_stage2_hold",
                                onValueChange = { raw ->
                                    val snapped = ((raw / 25f).roundToInt() * 25L)
                                        .coerceIn(EdgeConfig.MIN_STAGE2_HOLD_MS, EdgeConfig.MAX_STAGE2_HOLD_MS)
                                    val adjustedS3 = max(
                                        config.bottomStage3HoldMs,
                                        (snapped + EdgeConfig.MIN_STAGE_HOLD_GAP_MS)
                                            .coerceAtMost(EdgeConfig.MAX_STAGE3_HOLD_MS)
                                    )
                                    onConfigChange(
                                        config.copy(
                                            bottomStage2HoldMs = snapped,
                                            bottomStage3HoldMs = adjustedS3
                                        )
                                    )
                                }
                            )
                            MetricSlider(
                                label = stringResource(R.string.label_bottom_stage3_hold),
                                valueText = "${config.bottomStage3HoldMs} ms",
                                value = config.bottomStage3HoldMs.toFloat(),
                                range = EdgeConfig.MIN_STAGE3_HOLD_MS.toFloat()..EdgeConfig.MAX_STAGE3_HOLD_MS.toFloat(),
                                testTag = "slider_bottom_stage3_hold",
                                onValueChange = { raw ->
                                    val snapped = ((raw / 25f).roundToInt() * 25L)
                                        .coerceIn(EdgeConfig.MIN_STAGE3_HOLD_MS, EdgeConfig.MAX_STAGE3_HOLD_MS)
                                    val minS3 = (config.bottomStage2HoldMs + EdgeConfig.MIN_STAGE_HOLD_GAP_MS)
                                        .coerceAtMost(EdgeConfig.MAX_STAGE3_HOLD_MS)
                                    onConfigChange(config.copy(bottomStage3HoldMs = max(snapped, minS3)))
                                }
                            )
                        }

                        if (config.bottomStage3Action.isNone) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.hint_stage3_unassigned),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = extendedColors.cardBorder)
                    ToggleRow(
                        label = stringResource(R.string.label_bottom_long_press),
                        checked = config.bottomLongPressEnabled,
                        testTag = "toggle_bottom_long_press",
                        onCheckedChange = { onConfigChange(config.copy(bottomLongPressEnabled = it)) }
                    )
                    if (config.bottomLongPressEnabled) {
                        MetricSlider(
                            label = stringResource(R.string.label_bottom_long_press_duration),
                            valueText = "${config.bottomLongPressDurationMs} ms",
                            value = config.bottomLongPressDurationMs.toFloat(),
                            range = 250f..800f,
                            testTag = "slider_bottom_long_press_ms",
                            onValueChange = {
                                val snapped = (it / 25f).roundToInt() * 25
                                onConfigChange(config.copy(bottomLongPressDurationMs = snapped))
                            }
                        )
                        ActionAssignmentRow(
                            gestureLabel = stringResource(R.string.label_bottom_long_press_action),
                            action = config.bottomLongPressAction,
                            actionLabel = resolveActionLabel(context, config.bottomLongPressAction),
                            testTag = "row_bottom_long_press_action",
                            onClick = { pickingBottomLongPress = true }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            Choreographer.getInstance().postFrameCallback {
                                CircleToSearchTrigger.trigger(context)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("test_circle_to_search_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = HinataIcons.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.button_test_cts),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { openDigitalAssistantSettings(context) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_assistant_settings_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = stringResource(R.string.button_assistant_settings),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = HinataIcons.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 3. Custom Transparent GIF / Animated WebP / PNG Side-Edge Visual Feedback
            item {
                CustomMediaSectionCard(
                    config = config,
                    onConfigChange = onConfigChange,
                    snackbarHostState = snackbarHostState
                )
            }

            // 4. Appearance & Haptics
            item {
                SectionCard(title = stringResource(R.string.section_appearance)) {
                    MetricSlider(
                        label = stringResource(R.string.label_feedback_alpha),
                        valueText = "${(config.feedbackAlpha * 100).roundToInt()}%",
                        value = config.feedbackAlpha,
                        range = 0.0f..1.0f,
                        testTag = "slider_feedback_alpha",
                        onValueChange = { onConfigChange(config.copy(feedbackAlpha = it)) }
                    )
                    MetricSlider(
                        label = stringResource(R.string.label_edge_guide_alpha),
                        valueText = "${(config.edgeGuideAlpha * 100).roundToInt()}%",
                        value = config.edgeGuideAlpha,
                        range = 0.0f..0.80f,
                        testTag = "slider_edge_guide_alpha",
                        onValueChange = { onConfigChange(config.copy(edgeGuideAlpha = it)) }
                    )
                    MetricSlider(
                        label = stringResource(R.string.label_bottom_pill_alpha),
                        valueText = "${(config.bottomPillAlpha * 100).roundToInt()}%",
                        value = config.bottomPillAlpha,
                        range = 0.0f..1.0f,
                        testTag = "slider_bottom_pill_alpha",
                        onValueChange = { onConfigChange(config.copy(bottomPillAlpha = it)) }
                    )

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = extendedColors.cardBorder)
                    val supportsLiquidGlass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.label_liquid_glass),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(
                                    if (supportsLiquidGlass) R.string.desc_liquid_glass
                                    else R.string.desc_liquid_glass_unsupported
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = supportsLiquidGlass && config.liquidGlassEnabled,
                            enabled = supportsLiquidGlass,
                            onCheckedChange = { enabled ->
                                onConfigChange(config.copy(liquidGlassEnabled = enabled))
                            },
                            modifier = Modifier.testTag("toggle_liquid_glass")
                        )
                    }
                    if (supportsLiquidGlass && config.liquidGlassEnabled) {
                        MetricSlider(
                            label = stringResource(R.string.label_liquid_glass_refraction),
                            valueText = "${(config.liquidGlassRefraction * 100).roundToInt()}%",
                            value = config.liquidGlassRefraction,
                            range = EdgeConfig.MIN_LIQUID_GLASS_REFRACTION..EdgeConfig.MAX_LIQUID_GLASS_REFRACTION,
                            testTag = "slider_liquid_glass_refraction",
                            onValueChange = {
                                onConfigChange(config.copy(liquidGlassRefraction = it).clamped())
                            }
                        )
                    }
                    HorizontalDivider(color = extendedColors.cardBorder)

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.label_visual_theme),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        VisualTheme.entries.forEach { theme ->
                            val label = when (theme) {
                                VisualTheme.AUTO -> stringResource(R.string.theme_auto)
                                VisualTheme.DARK_GLASS -> stringResource(R.string.theme_dark_glass)
                                VisualTheme.LIGHT_GLASS -> stringResource(R.string.theme_light_glass)
                                VisualTheme.MATERIAL_YOU -> stringResource(R.string.theme_material_you)
                            }
                            CleanFilterChip(
                                selected = config.visualTheme == theme,
                                label = label,
                                testTag = "theme_chip_${theme.name}",
                                onClick = { onConfigChange(config.copy(visualTheme = theme)) }
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.label_feedback_style),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FeedbackStyle.entries.forEach { style ->
                            val label = when (style) {
                                FeedbackStyle.BEZIER_BUBBLE -> stringResource(R.string.style_bezier)
                                FeedbackStyle.MINIMAL_ARROW -> stringResource(R.string.style_minimal)
                                FeedbackStyle.HIDDEN -> stringResource(R.string.style_hidden)
                            }
                            CleanFilterChip(
                                selected = config.feedbackStyle == style,
                                label = label,
                                testTag = "style_chip_${style.name}",
                                onClick = { onConfigChange(config.copy(feedbackStyle = style)) }
                            )
                        }
                    }

                    if (config.feedbackStyle == FeedbackStyle.BEZIER_BUBBLE) {
                        Spacer(Modifier.height(10.dp))
                        MetricSlider(
                            label = stringResource(R.string.label_droplet_peak_width),
                            valueText = "${config.dropletPeakWidthDp.roundToInt()} dp",
                            value = config.dropletPeakWidthDp,
                            range = EdgeConfig.MIN_DROPLET_PEAK_WIDTH_DP..EdgeConfig.MAX_DROPLET_PEAK_WIDTH_DP,
                            testTag = "slider_droplet_peak_width",
                            onValueChange = {
                                onConfigChange(config.copy(dropletPeakWidthDp = it).clamped())
                            }
                        )
                        MetricSlider(
                            label = stringResource(R.string.label_droplet_base_width),
                            valueText = "${config.dropletBaseWidthDp.roundToInt()} dp",
                            value = config.dropletBaseWidthDp,
                            range = EdgeConfig.MIN_DROPLET_BASE_WIDTH_DP..EdgeConfig.MAX_DROPLET_BASE_WIDTH_DP,
                            testTag = "slider_droplet_base_width",
                            onValueChange = {
                                onConfigChange(config.copy(dropletBaseWidthDp = it).clamped())
                            }
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.label_haptic_strength),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        HapticStrength.entries.forEach { strength ->
                            val label = when (strength) {
                                HapticStrength.OFF -> stringResource(R.string.haptic_off)
                                HapticStrength.LIGHT -> stringResource(R.string.haptic_light)
                                HapticStrength.MEDIUM -> stringResource(R.string.haptic_medium)
                                HapticStrength.STRONG -> stringResource(R.string.haptic_strong)
                            }
                            CleanFilterChip(
                                selected = config.hapticStrength == strength,
                                label = label,
                                testTag = "haptic_chip_${strength.name}",
                                onClick = { onConfigChange(config.copy(hapticStrength = strength)) }
                            )
                        }
                    }
                }
            }

            // 5. Edge Area Dimensions & Geometry
            item {
                SectionCard(title = stringResource(R.string.section_trigger_zones)) {
                    MetricSlider(
                        label = stringResource(R.string.label_edge_width),
                        valueText = "${config.edgeWidthDp.roundToInt()} dp",
                        value = config.edgeWidthDp,
                        range = 8f..36f,
                        testTag = "slider_edge_width",
                        onValueChange = { onConfigChange(config.copy(edgeWidthDp = it)) }
                    )
                    MetricSlider(
                        label = stringResource(R.string.label_edge_height),
                        valueText = "${(config.edgeHeightPercent * 100).roundToInt()}%",
                        value = config.edgeHeightPercent,
                        range = 0.12f..0.90f,
                        testTag = "slider_edge_height",
                        onValueChange = { onConfigChange(config.copy(edgeHeightPercent = it).clamped()) }
                    )
                    MetricSlider(
                        label = stringResource(R.string.label_edge_offset),
                        valueText = "${(config.edgeBottomOffsetPercent * 100).roundToInt()}%",
                        value = config.edgeBottomOffsetPercent,
                        range = 0.0f..(1f - config.edgeHeightPercent).coerceAtLeast(0.05f),
                        testTag = "slider_edge_offset",
                        onValueChange = { onConfigChange(config.copy(edgeBottomOffsetPercent = it).clamped()) }
                    )

                    Spacer(Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("zones_os_back_mitigation_card"),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, extendedColors.cardBorder)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = HinataIcons.Shield,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.zones_os_back_mitigation_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.zones_os_back_mitigation_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (shizukuStatus == ShizukuStatus.CONNECTED) {
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        if (isApplyingBackMitigation) return@Button
                                        isApplyingBackMitigation = true
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                val disableLeft = config.leftEnabled || !config.rightEnabled
                                                val disableRight = config.rightEnabled || !config.leftEnabled
                                                ShizukuShellExecutor.suppressSystemBackGestures(
                                                    context = context,
                                                    disableLeft = disableLeft,
                                                    disableRight = disableRight,
                                                    requireAccessibilityGuard = true
                                                )
                                            }
                                            isApplyingBackMitigation = false
                                            if (result.success && !config.shizukuSystemNavModified) {
                                                onConfigChange(config.copy(shizukuSystemNavModified = true))
                                            }
                                            val msg = when {
                                                result.blockedByAccessibilityGuard ->
                                                    context.getString(R.string.shizuku_snackbar_accessibility_required)
                                                result.success ->
                                                    context.getString(R.string.shizuku_snackbar_back_suppressed)
                                                else ->
                                                    context.getString(R.string.shizuku_snackbar_failed)
                                            }
                                            snackbarHostState.currentSnackbarData?.dismiss()
                                            snackbarHostState.showSnackbar(msg)
                                        }
                                    },
                                    enabled = !isApplyingBackMitigation,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("zones_os_back_disable_shizuku_button"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = HinataIcons.DoNotDisturbOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.zones_os_back_disable_shizuku_button),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        if (isApplyingBackMitigation) return@OutlinedButton
                                        isApplyingBackMitigation = true
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                ShizukuShellExecutor.restoreSystemBackGestures(context)
                                            }
                                            isApplyingBackMitigation = false
                                            if (result.success && config.shizukuSystemNavModified) {
                                                onConfigChange(config.copy(shizukuSystemNavModified = false))
                                            }
                                            val msg = if (result.success) {
                                                context.getString(R.string.shizuku_snackbar_restore_success)
                                            } else {
                                                context.getString(R.string.shizuku_snackbar_failed)
                                            }
                                            snackbarHostState.currentSnackbarData?.dismiss()
                                            snackbarHostState.showSnackbar(msg)
                                        }
                                    },
                                    enabled = !isApplyingBackMitigation,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("zones_os_back_restore_button"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = HinataIcons.Restore,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.zones_os_back_restore_button),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    val opened = runCatching {
                                        context.startActivity(
                                            Intent("android.settings.GESTURE_NAVIGATION_SETTINGS").apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                        true
                                    }.getOrDefault(false)
                                    if (!opened) {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_SETTINGS).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("zones_open_nav_settings_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = HinataIcons.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.zones_open_nav_settings_button),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))

                    if (config.bottomEnabled) {
                        MetricSlider(
                            label = stringResource(R.string.label_bottom_width),
                            valueText = "${(config.bottomBarWidthPercent * 100).roundToInt()}%",
                            value = config.bottomBarWidthPercent,
                            range = EdgeConfig.MIN_BOTTOM_WIDTH_PERCENT..EdgeConfig.MAX_BOTTOM_WIDTH_PERCENT,
                            testTag = "slider_bottom_width",
                            onValueChange = { onConfigChange(config.copy(bottomBarWidthPercent = it).clamped()) }
                        )
                        MetricSlider(
                            label = stringResource(R.string.label_bottom_height),
                            valueText = "${config.bottomBarHeightDp.roundToInt()} dp",
                            value = config.bottomBarHeightDp,
                            range = EdgeConfig.MIN_BOTTOM_HEIGHT_DP..EdgeConfig.MAX_BOTTOM_HEIGHT_DP,
                            testTag = "slider_bottom_height",
                            onValueChange = { onConfigChange(config.copy(bottomBarHeightDp = it).clamped()) }
                        )
                        MetricSlider(
                            label = stringResource(R.string.label_bottom_offset),
                            valueText = "${config.bottomBarBottomOffsetDp.roundToInt()} dp",
                            value = config.bottomBarBottomOffsetDp,
                            range = 0f..EdgeConfig.MAX_BOTTOM_OFFSET_DP,
                            testTag = "slider_bottom_offset",
                            onValueChange = { onConfigChange(config.copy(bottomBarBottomOffsetDp = it).clamped()) }
                        )
                        val hPct = (config.bottomBarHorizontalOffsetPercent * 100f).roundToInt()
                        val hText = if (hPct > 0) "+$hPct%" else "$hPct%"
                        MetricSlider(
                            label = stringResource(R.string.label_bottom_horizontal_offset),
                            valueText = hText,
                            value = config.bottomBarHorizontalOffsetPercent,
                            range = EdgeConfig.MIN_BOTTOM_HORIZONTAL_OFFSET_PERCENT..EdgeConfig.MAX_BOTTOM_HORIZONTAL_OFFSET_PERCENT,
                            testTag = "slider_bottom_horizontal_offset",
                            onValueChange = { raw ->
                                val snapped = if (abs(raw) < 0.015f) 0f else raw
                                onConfigChange(config.copy(bottomBarHorizontalOffsetPercent = snapped).clamped())
                            }
                        )
                        if (abs(config.bottomBarHorizontalOffsetPercent) >= 0.005f) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                CleanFilterChip(
                                    selected = false,
                                    label = stringResource(R.string.button_center_reset),
                                    testTag = "button_bottom_center_reset",
                                    onClick = {
                                        onConfigChange(config.copy(bottomBarHorizontalOffsetPercent = 0f))
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = extendedColors.cardBorder)
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.label_show_bottom_bar_during_ime),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.desc_show_bottom_bar_during_ime),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = config.showBottomBarDuringIme,
                                onCheckedChange = {
                                    onConfigChange(config.copy(showBottomBarDuringIme = it))
                                },
                                modifier = Modifier.testTag("toggle_zones_show_bottom_during_ime")
                            )
                        }
                    }

                    MetricSlider(
                        label = stringResource(R.string.label_swipe_threshold),
                        valueText = "${config.swipeThresholdDp.roundToInt()} dp",
                        value = config.swipeThresholdDp,
                        range = 16f..56f,
                        testTag = "slider_swipe_threshold",
                        onValueChange = { onConfigChange(config.copy(swipeThresholdDp = it)) }
                    )
                    MetricSlider(
                        label = stringResource(R.string.label_diagonal_angle),
                        valueText = "${config.diagonalAngleDeg.roundToInt()}°",
                        value = config.diagonalAngleDeg,
                        range = 20f..45f,
                        testTag = "slider_diagonal_angle",
                        onValueChange = { onConfigChange(config.copy(diagonalAngleDeg = it)) }
                    )
                }
            }

            item {
                OutlinedButton(
                    onClick = onResetDefaults,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reset_defaults_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = HinataIcons.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = stringResource(R.string.label_reset_defaults))
                }
            }
        }
    }

    if (pickingBottomLongPress) {
        UnifiedActionPickerSheet(
            currentAction = config.bottomLongPressAction,
            allowNone = true,
            allowOpenQuickMenu = true,
            config = config,
            onSelect = { action, _ ->
                onConfigChange(config.copy(bottomLongPressAction = action))
                pickingBottomLongPress = false
                if (shouldShowNoShizukuFloatNotice(context) &&
                    (action.type == EdgeActionType.FLOAT_CURRENT_APP ||
                        (action.type == EdgeActionType.LAUNCH_APP_FLOATING && !config.hasSavedCcFloatTileCoords()))
                ) {
                    showNoShizukuFloatSetup = true
                }
            },
            onDismiss = { pickingBottomLongPress = false }
        )
    }

    if (showNoShizukuFloatSetup) {
        NoShizukuFloatSetupDialog(
            config = config,
            onConfigChange = onConfigChange,
            onDismiss = { showNoShizukuFloatSetup = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerModeSegmentedRow(
    selected: SecondaryTriggerMode,
    testTagPrefix: String,
    onSelect: (SecondaryTriggerMode) -> Unit
) {
    val modes = SecondaryTriggerMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            val label = when (mode) {
                SecondaryTriggerMode.DISTANCE -> stringResource(R.string.mode_distance)
                SecondaryTriggerMode.HOLD -> stringResource(R.string.mode_hold)
                SecondaryTriggerMode.BOTH -> stringResource(R.string.mode_both)
            }
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                icon = {},
                modifier = Modifier.testTag("${testTagPrefix}_${mode.name}")
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private enum class ActionSlot {
    HORIZONTAL,
    HORIZONTAL_HOLD,
    LEFT_STAGE3,
    RIGHT_STAGE3,
    DIAGONAL_UP,
    DIAGONAL_DOWN,
    BOTTOM_UP,
    BOTTOM_HOLD,
    BOTTOM_STAGE3,
    BOTTOM_LONG_PRESS
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionsAndAvoidanceScreen(
    config: EdgeConfig,
    hasUsageStatsPermission: Boolean,
    onConfigChange: (EdgeConfig) -> Unit,
    onOpenTranslateSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val extendedColors = LocalEdgeFlowColors.current
    val capabilities = remember { DeviceCompatibilityHelper.resolveCapabilities() }
    var editingSlot by remember { mutableStateOf<ActionSlot?>(null) }
    var showNoShizukuFloatSetup by remember { mutableStateOf(false) }
    var showRecentHistorySheet by remember { mutableStateOf(false) }

    val sourceLangLabel = if (config.translateSourceLang == EdgeConfig.DEFAULT_TRANSLATE_SOURCE_LANG) {
        stringResource(R.string.translate_source_auto_short)
    } else {
        TranslateLanguageCatalog.findByCode(config.translateSourceLang)?.nativeName
            ?: config.translateSourceLang.uppercase()
    }
    val targetLangLabel = TranslateLanguageCatalog.findByCode(config.translateTargetLang)?.nativeName
        ?: config.translateTargetLang.uppercase()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("actions_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
    ) {
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenTranslateSettings)
                    .testTag("actions_open_translate_settings_card"),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = HinataIcons.Translate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.translate_settings_entry_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "$sourceLangLabel → $targetLangLabel · ${stringResource(R.string.translate_settings_entry_desc)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = HinataIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.section_side_actions)) {
                ActionAssignmentRow(
                    gestureLabel = stringResource(R.string.gesture_horizontal),
                    action = config.horizontalAction,
                    actionLabel = resolveActionLabel(context, config.horizontalAction),
                    testTag = "action_row_horizontal",
                    onClick = { editingSlot = ActionSlot.HORIZONTAL }
                )
                HorizontalDivider(color = extendedColors.cardBorder)
                ActionAssignmentRow(
                    gestureLabel = stringResource(R.string.gesture_horizontal_hold),
                    action = config.horizontalHoldAction,
                    actionLabel = resolveActionLabel(context, config.horizontalHoldAction),
                    testTag = "action_row_horizontal_hold",
                    onClick = { editingSlot = ActionSlot.HORIZONTAL_HOLD }
                )
                HorizontalDivider(color = extendedColors.cardBorder)
                ActionAssignmentRow(
                    gestureLabel = stringResource(R.string.gesture_left_stage3),
                    action = config.leftStage3Action,
                    actionLabel = resolveActionLabel(context, config.leftStage3Action),
                    testTag = "action_row_left_stage3",
                    onClick = { editingSlot = ActionSlot.LEFT_STAGE3 }
                )
                HorizontalDivider(color = extendedColors.cardBorder)
                ActionAssignmentRow(
                    gestureLabel = stringResource(R.string.gesture_right_stage3),
                    action = config.rightStage3Action,
                    actionLabel = resolveActionLabel(context, config.rightStage3Action),
                    testTag = "action_row_right_stage3",
                    onClick = { editingSlot = ActionSlot.RIGHT_STAGE3 }
                )
                HorizontalDivider(color = extendedColors.cardBorder)
                ActionAssignmentRow(
                    gestureLabel = stringResource(R.string.gesture_diagonal_up),
                    action = config.diagonalUpAction,
                    actionLabel = resolveActionLabel(context, config.diagonalUpAction),
                    testTag = "action_row_diagonal_up",
                    onClick = { editingSlot = ActionSlot.DIAGONAL_UP }
                )
                HorizontalDivider(color = extendedColors.cardBorder)
                ActionAssignmentRow(
                    gestureLabel = stringResource(R.string.gesture_diagonal_down),
                    action = config.diagonalDownAction,
                    actionLabel = resolveActionLabel(context, config.diagonalDownAction),
                    testTag = "action_row_diagonal_down",
                    onClick = { editingSlot = ActionSlot.DIAGONAL_DOWN }
                )
            }
        }

        item {
            SectionCard(title = stringResource(R.string.section_bottom_actions)) {
                ActionAssignmentRow(
                    gestureLabel = stringResource(R.string.gesture_bottom_up),
                    action = config.bottomSwipeUpAction,
                    actionLabel = resolveActionLabel(context, config.bottomSwipeUpAction),
                    testTag = "action_row_bottom_up",
                    onClick = { editingSlot = ActionSlot.BOTTOM_UP }
                )
                HorizontalDivider(color = extendedColors.cardBorder)
                ActionAssignmentRow(
                    gestureLabel = stringResource(R.string.gesture_bottom_hold),
                    action = config.bottomSwipeHoldAction,
                    actionLabel = resolveActionLabel(context, config.bottomSwipeHoldAction),
                    testTag = "action_row_bottom_hold",
                    onClick = { editingSlot = ActionSlot.BOTTOM_HOLD }
                )
                HorizontalDivider(color = extendedColors.cardBorder)
                ActionAssignmentRow(
                    gestureLabel = stringResource(R.string.gesture_bottom_stage3),
                    action = config.bottomStage3Action,
                    actionLabel = resolveActionLabel(context, config.bottomStage3Action),
                    testTag = "action_row_bottom_stage3",
                    onClick = { editingSlot = ActionSlot.BOTTOM_STAGE3 }
                )
                HorizontalDivider(color = extendedColors.cardBorder)
                ActionAssignmentRow(
                    gestureLabel = stringResource(R.string.gesture_bottom_long_press),
                    action = config.bottomLongPressAction,
                    actionLabel = resolveActionLabel(context, config.bottomLongPressAction),
                    testTag = "action_row_bottom_long_press",
                    onClick = { editingSlot = ActionSlot.BOTTOM_LONG_PRESS }
                )
            }
        }

        // App Switching & HyperOS Recents Fix
        item {
            SectionCard(title = stringResource(R.string.section_task_switching)) {
                if (capabilities.supportsHyperOsRecentsCompat) {
                    ToggleRow(
                        label = stringResource(R.string.label_hyperos_recents_compat),
                        checked = config.hyperOsRecentsCompatMode,
                        testTag = "toggle_hyperos_recents_compat",
                        onCheckedChange = { onConfigChange(config.copy(hyperOsRecentsCompatMode = it)) }
                    )
                    Text(
                        text = stringResource(R.string.desc_hyperos_recents_compat),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (config.hyperOsRecentsCompatMode) {
                        Spacer(Modifier.height(10.dp))
                        MetricSlider(
                            label = stringResource(R.string.label_hyperos_recents_delay),
                            valueText = "${config.hyperOsRecentsDelayMs} ms",
                            value = config.hyperOsRecentsDelayMs.toFloat(),
                            range = 50f..250f,
                            testTag = "slider_hyperos_recents_delay",
                            onValueChange = {
                                val snapped = (it / 10f).roundToInt() * 10
                                onConfigChange(config.copy(hyperOsRecentsDelayMs = snapped))
                            }
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = extendedColors.cardBorder)
                    Spacer(Modifier.height(14.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.label_usage_stats_permission),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    val chipColor = if (hasUsageStatsPermission) {
                        extendedColors.statusActive
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val chipContainer = if (hasUsageStatsPermission) {
                        extendedColors.statusActiveContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                    Surface(
                        color = chipContainer,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = stringResource(
                                if (hasUsageStatsPermission) R.string.status_usage_granted
                                else R.string.status_usage_optional
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = chipColor,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.desc_usage_stats_permission),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { openUsageAccessSettings(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_usage_access_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = stringResource(R.string.button_open_usage_access),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = HinataIcons.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { showRecentHistorySheet = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("test_recent_history_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = HinataIcons.History,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.button_test_recent_history),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Smart Behavior
        item {
            SectionCard(title = stringResource(R.string.section_smart_avoidance)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = HinataIcons.Keyboard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.label_ime_avoidance),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ImeAvoidanceMode.entries.forEach { mode ->
                        val label = when (mode) {
                            ImeAvoidanceMode.SHIFT_UP -> stringResource(R.string.ime_shift_up)
                            ImeAvoidanceMode.DISABLE_TOUCH -> stringResource(R.string.ime_disable)
                            ImeAvoidanceMode.NONE -> stringResource(R.string.ime_none)
                        }
                        CleanFilterChip(
                            selected = config.imeAvoidanceMode == mode,
                            label = label,
                            testTag = "ime_chip_${mode.name}",
                            onClick = { onConfigChange(config.copy(imeAvoidanceMode = mode)) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.label_show_bottom_bar_during_ime),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.desc_show_bottom_bar_during_ime),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.showBottomBarDuringIme,
                        onCheckedChange = {
                            onConfigChange(config.copy(showBottomBarDuringIme = it))
                        },
                        modifier = Modifier.testTag("toggle_show_bottom_during_ime")
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = HinataIcons.ScreenRotation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.label_landscape_mode),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LandscapeMode.entries.forEach { mode ->
                        val label = when (mode) {
                            LandscapeMode.DOUBLE_SWIPE_CONFIRM -> stringResource(R.string.landscape_double_swipe)
                            LandscapeMode.DISABLE -> stringResource(R.string.landscape_disable)
                            LandscapeMode.KEEP_ENABLED -> stringResource(R.string.landscape_keep)
                        }
                        CleanFilterChip(
                            selected = config.landscapeMode == mode,
                            label = label,
                            testTag = "landscape_chip_${mode.name}",
                            onClick = { onConfigChange(config.copy(landscapeMode = mode)) }
                        )
                    }
                }
            }
        }
    }

    val currentSlot = editingSlot
    if (currentSlot != null) {
        val currentAction = when (currentSlot) {
            ActionSlot.HORIZONTAL -> config.horizontalAction
            ActionSlot.HORIZONTAL_HOLD -> config.horizontalHoldAction
            ActionSlot.LEFT_STAGE3 -> config.leftStage3Action
            ActionSlot.RIGHT_STAGE3 -> config.rightStage3Action
            ActionSlot.DIAGONAL_UP -> config.diagonalUpAction
            ActionSlot.DIAGONAL_DOWN -> config.diagonalDownAction
            ActionSlot.BOTTOM_UP -> config.bottomSwipeUpAction
            ActionSlot.BOTTOM_HOLD -> config.bottomSwipeHoldAction
            ActionSlot.BOTTOM_STAGE3 -> config.bottomStage3Action
            ActionSlot.BOTTOM_LONG_PRESS -> config.bottomLongPressAction
        }
        UnifiedActionPickerSheet(
            currentAction = currentAction,
            allowNone = true,
            allowOpenQuickMenu = true,
            config = config,
            onSelect = { candidate, _ ->
                val updated = when (currentSlot) {
                    ActionSlot.HORIZONTAL -> config.copy(horizontalAction = candidate)
                    ActionSlot.HORIZONTAL_HOLD -> config.copy(horizontalHoldAction = candidate)
                    ActionSlot.LEFT_STAGE3 -> config.copy(leftStage3Action = candidate)
                    ActionSlot.RIGHT_STAGE3 -> config.copy(rightStage3Action = candidate)
                    ActionSlot.DIAGONAL_UP -> config.copy(diagonalUpAction = candidate)
                    ActionSlot.DIAGONAL_DOWN -> config.copy(diagonalDownAction = candidate)
                    ActionSlot.BOTTOM_UP -> config.copy(bottomSwipeUpAction = candidate)
                    ActionSlot.BOTTOM_HOLD -> config.copy(bottomSwipeHoldAction = candidate)
                    ActionSlot.BOTTOM_STAGE3 -> config.copy(bottomStage3Action = candidate)
                    ActionSlot.BOTTOM_LONG_PRESS -> config.copy(bottomLongPressAction = candidate)
                }
                onConfigChange(updated)
                editingSlot = null
                if (shouldShowNoShizukuFloatNotice(context) &&
                    (candidate.type == EdgeActionType.FLOAT_CURRENT_APP ||
                        (candidate.type == EdgeActionType.LAUNCH_APP_FLOATING && !config.hasSavedCcFloatTileCoords()))
                ) {
                    showNoShizukuFloatSetup = true
                }
            },
            onDismiss = { editingSlot = null }
        )
    }

    if (showNoShizukuFloatSetup) {
        NoShizukuFloatSetupDialog(
            config = config,
            onConfigChange = onConfigChange,
            onDismiss = { showNoShizukuFloatSetup = false }
        )
    }

    if (showRecentHistorySheet) {
        RecentHistoryTestSheet(
            onDismiss = { showRecentHistorySheet = false }
        )
    }
}

fun openUsageAccessSettings(context: Context) {
    val usageIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(usageIntent) }.isSuccess) return

    runCatching {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun openDigitalAssistantSettings(context: Context) {
    val voiceIntent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(voiceIntent) }.isSuccess) return

    val defaultAppsIntent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(defaultAppsIntent) }.isSuccess) return

    runCatching {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun SectionCard(
    title: String? = null,
    content: @Composable () -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extendedColors.cardBorder)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(14.dp))
            }
            content()
        }
    }
}

@Composable
internal fun CleanFilterChip(
    selected: Boolean,
    label: String,
    testTag: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = Modifier.testTag(testTag),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
internal fun MetricSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    testTag: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = valueText,
                style = NumericLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            modifier = Modifier.testTag(testTag),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun ActionAssignmentRow(
    gestureLabel: String,
    action: EdgeAction,
    actionLabel: String,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = actionIcon(action.type),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = gestureLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = HinataIcons.ChevronRight,
            contentDescription = stringResource(R.string.cd_change),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
