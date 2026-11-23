package com.edgeflow.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import com.example.ui.icons.HinataIcons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edgeflow.data.EdgeAction
import com.edgeflow.data.EdgeActionType
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.FeedbackStyle
import com.edgeflow.data.QuickArcTriggerMethod
import com.edgeflow.data.SecondaryTriggerMode
import com.example.R
import com.example.ui.theme.LocalEdgeFlowColors
import com.example.ui.theme.NumericLabelStyle
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

enum class ABChoice { A, B }

data class BeginnerABFeatureSelection(
    val useQuickMenu: Boolean = true,
    val useRecentSwitcher: Boolean = true,
    val useBottomBar: Boolean = true,
    val useThreeStage: Boolean = false,
    val useLiquidGlass: Boolean = true
)

data class BeginnerABAnswers(
    val sideWidthChoice: ABChoice = ABChoice.A,
    val bottomComfortChoice: ABChoice = ABChoice.A,
    val visualSurfaceChoice: ABChoice = ABChoice.A,
    val visualShapeChoice: ABChoice = ABChoice.A,
    val stage2ModeChoice: ABChoice = ABChoice.A
)

data class BeginnerABSample(
    val thumbYRatioSamples: List<Float> = emptyList(),
    val swipeDistanceDpSamples: List<Float> = emptyList()
)

/**
 * Pure-Kotlin parameter generator for the Beginner A/B Auto-Tune Wizard (Beta).
 * Converts feature checklist toggles, A/B preference choices, and real thumb swipe telemetry
 * into a validated, clamped [EdgeConfig].
 */
object BeginnerABConfigGenerator {

    fun generate(
        baseConfig: EdgeConfig,
        features: BeginnerABFeatureSelection,
        answers: BeginnerABAnswers,
        sample: BeginnerABSample = BeginnerABSample()
    ): EdgeConfig {
        val edgeWidthDp = if (answers.sideWidthChoice == ABChoice.B) 24f else 14f
        val edgeHeightPercent = 0.56f

        val edgeBottomOffsetPercent = if (sample.thumbYRatioSamples.isNotEmpty()) {
            val avgYFromTop = sample.thumbYRatioSamples
                .map { it.coerceIn(0.08f, 0.92f) }
                .average()
                .toFloat()
            val centerFromBottom = 1f - avgYFromTop
            (centerFromBottom - edgeHeightPercent * 0.5f)
                .coerceIn(0.04f, (1f - edgeHeightPercent).coerceAtLeast(0.04f))
        } else {
            0.14f
        }

        val swipeThresholdDp = if (sample.swipeDistanceDpSamples.isNotEmpty()) {
            val avgSwipeDp = sample.swipeDistanceDpSamples
                .map { it.coerceIn(24f, 180f) }
                .average()
                .toFloat()
            (avgSwipeDp * 0.36f).coerceIn(22f, 40f)
        } else {
            if (answers.sideWidthChoice == ABChoice.B) 26f else 30f
        }

        val bottomBarHeightDp = if (answers.bottomComfortChoice == ABChoice.B) 28f else 20f
        val bottomBarBottomOffsetDp = if (answers.bottomComfortChoice == ABChoice.B) 6f else 0f
        val bottomBarWidthPercent = if (answers.bottomComfortChoice == ABChoice.B) 0.48f else 0.38f

        val triggerMode = if (answers.stage2ModeChoice == ABChoice.B) {
            SecondaryTriggerMode.HOLD
        } else {
            SecondaryTriggerMode.DISTANCE
        }

        val liquidGlassEnabled = features.useLiquidGlass && (answers.visualSurfaceChoice == ABChoice.A)
        val feedbackStyle = if (answers.visualShapeChoice == ABChoice.B) {
            FeedbackStyle.MINIMAL_ARROW
        } else {
            FeedbackStyle.BEZIER_BUBBLE
        }

        val quickArcMethod = if (features.useQuickMenu) {
            QuickArcTriggerMethod.DEDICATED_ZONE
        } else {
            QuickArcTriggerMethod.GESTURE_ACTION
        }

        val sanitizedHorizontalHold = if (!features.useQuickMenu &&
            baseConfig.horizontalHoldAction.type == EdgeActionType.OPEN_QUICK_MENU
        ) {
            EdgeAction.PREVIOUS_APP
        } else {
            baseConfig.horizontalHoldAction
        }

        val sanitizedDiagUp = if (!features.useQuickMenu &&
            baseConfig.diagonalUpAction.type == EdgeActionType.OPEN_QUICK_MENU
        ) {
            EdgeAction.HOME
        } else {
            baseConfig.diagonalUpAction
        }

        val sanitizedDiagDown = if (!features.useQuickMenu &&
            baseConfig.diagonalDownAction.type == EdgeActionType.OPEN_QUICK_MENU
        ) {
            EdgeAction.NOTIFICATIONS
        } else {
            baseConfig.diagonalDownAction
        }

        val bottomHoldAction = if (features.useRecentSwitcher) {
            EdgeAction.OPEN_RECENT_SWITCHER
        } else {
            EdgeAction.RECENTS
        }

        val leftStage3 = if (features.useThreeStage) {
            val candidate = baseConfig.leftStage3Action
            if (candidate.isNone || (!features.useQuickMenu && candidate.type == EdgeActionType.OPEN_QUICK_MENU)) {
                EdgeAction.SCREENSHOT
            } else {
                candidate
            }
        } else {
            EdgeAction.NONE
        }

        val rightStage3 = if (features.useThreeStage) {
            val candidate = baseConfig.rightStage3Action
            if (candidate.isNone || (!features.useQuickMenu && candidate.type == EdgeActionType.OPEN_QUICK_MENU)) {
                EdgeAction.SCREENSHOT
            } else {
                candidate
            }
        } else {
            EdgeAction.NONE
        }

        val bottomStage3 = if (features.useThreeStage && features.useBottomBar) {
            val candidate = baseConfig.bottomStage3Action
            if (candidate.isNone || (!features.useQuickMenu && candidate.type == EdgeActionType.OPEN_QUICK_MENU)) {
                EdgeAction.LOCK_SCREEN
            } else {
                candidate
            }
        } else {
            EdgeAction.NONE
        }

        return baseConfig.copy(
            leftEnabled = true,
            rightEnabled = true,
            bottomEnabled = features.useBottomBar,
            edgeWidthDp = edgeWidthDp,
            edgeHeightPercent = edgeHeightPercent,
            edgeBottomOffsetPercent = edgeBottomOffsetPercent,
            swipeThresholdDp = swipeThresholdDp,
            bottomBarWidthPercent = bottomBarWidthPercent,
            bottomBarHeightDp = bottomBarHeightDp,
            bottomBarBottomOffsetDp = bottomBarBottomOffsetDp,
            sideTriggerMode = triggerMode,
            bottomTriggerMode = triggerMode,
            liquidGlassEnabled = liquidGlassEnabled,
            feedbackStyle = feedbackStyle,
            quickArcTriggerMethod = quickArcMethod,
            horizontalHoldAction = sanitizedHorizontalHold,
            diagonalUpAction = sanitizedDiagUp,
            diagonalDownAction = sanitizedDiagDown,
            bottomSwipeHoldAction = bottomHoldAction,
            leftStage3Action = leftStage3,
            rightStage3Action = rightStage3,
            bottomStage3Action = bottomStage3
        ).clamped()
    }
}

private enum class WizardStep {
    FEATURES,
    Q1_SIDE,
    Q2_BOTTOM,
    Q3_VISUAL,
    Q4_STAGE2,
    SUMMARY
}

@Composable
fun BeginnerABWizardScreen(
    currentConfig: EdgeConfig,
    onApplyConfig: (EdgeConfig) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extendedColors = LocalEdgeFlowColors.current
    val supportsLiquidGlass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    var features by remember {
        mutableStateOf(
            BeginnerABFeatureSelection(
                useQuickMenu = currentConfig.quickArcTriggerMethod == QuickArcTriggerMethod.DEDICATED_ZONE,
                useRecentSwitcher = currentConfig.bottomSwipeHoldAction.type == EdgeActionType.OPEN_RECENT_SWITCHER,
                useBottomBar = currentConfig.bottomEnabled,
                useThreeStage = currentConfig.hasAnySideStage3() || !currentConfig.bottomStage3Action.isNone,
                useLiquidGlass = supportsLiquidGlass && currentConfig.liquidGlassEnabled
            )
        )
    }
    var answers by remember { mutableStateOf(BeginnerABAnswers()) }
    val thumbYSamples = remember { mutableStateListOf<Float>() }
    val swipeDistSamples = remember { mutableStateListOf<Float>() }

    val steps = remember(features.useBottomBar) {
        buildList {
            add(WizardStep.FEATURES)
            add(WizardStep.Q1_SIDE)
            if (features.useBottomBar) add(WizardStep.Q2_BOTTOM)
            add(WizardStep.Q3_VISUAL)
            add(WizardStep.Q4_STAGE2)
            add(WizardStep.SUMMARY)
        }
    }
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val safeIndex = stepIndex.coerceIn(0, steps.lastIndex)
    val currentStep = steps[safeIndex]

    val generatedConfig = remember(currentConfig, features, answers, thumbYSamples.size, swipeDistSamples.size) {
        BeginnerABConfigGenerator.generate(
            baseConfig = currentConfig,
            features = features,
            answers = answers,
            sample = BeginnerABSample(
                thumbYRatioSamples = thumbYSamples.toList(),
                swipeDistanceDpSamples = swipeDistSamples.toList()
            )
        )
    }

    BackHandler {
        if (safeIndex > 0) {
            stepIndex = safeIndex - 1
        } else {
            onCancel()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("ab_wizard_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 560.dp)
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    // Header with Back, Title + Beta badge, Step Counter, Close
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (safeIndex > 0) stepIndex = safeIndex - 1 else onCancel()
                            },
                            modifier = Modifier.testTag("ab_wizard_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.ab_wizard_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
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
                        Text(
                            text = "${safeIndex + 1} / ${steps.size}",
                            style = NumericLabelStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.testTag("ab_wizard_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.button_close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { (safeIndex + 1).toFloat() / steps.size.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        AnimatedContent(
                            targetState = currentStep,
                            transitionSpec = {
                                val forward = steps.indexOf(targetState) >= steps.indexOf(initialState)
                                val slideIn = slideInHorizontally(
                                    animationSpec = tween(220),
                                    initialOffsetX = { w -> if (forward) w / 4 else -w / 4 }
                                ) + fadeIn(tween(200))
                                val slideOut = slideOutHorizontally(
                                    animationSpec = tween(180),
                                    targetOffsetX = { w -> if (forward) -w / 4 else w / 4 }
                                ) + fadeOut(tween(160))
                                slideIn togetherWith slideOut
                            },
                            label = "ab_wizard_step"
                        ) { step ->
                            when (step) {
                                WizardStep.FEATURES -> FeatureChecklistStep(
                                    features = features,
                                    supportsLiquidGlass = supportsLiquidGlass,
                                    onChange = { features = it }
                                )

                                WizardStep.Q1_SIDE -> SideWidthAndThumbStep(
                                    selected = answers.sideWidthChoice,
                                    sampleCount = thumbYSamples.size,
                                    estimatedOffsetPercent = (generatedConfig.edgeBottomOffsetPercent * 100f).roundToInt(),
                                    onSelect = { answers = answers.copy(sideWidthChoice = it) },
                                    onRecordSwipe = { yRatio, distDp ->
                                        thumbYSamples.add(yRatio)
                                        swipeDistSamples.add(distDp)
                                    }
                                )

                                WizardStep.Q2_BOTTOM -> BottomComfortStep(
                                    selected = answers.bottomComfortChoice,
                                    onSelect = { answers = answers.copy(bottomComfortChoice = it) }
                                )

                                WizardStep.Q3_VISUAL -> VisualStyleStep(
                                    surfaceChoice = answers.visualSurfaceChoice,
                                    shapeChoice = answers.visualShapeChoice,
                                    liquidGlassAllowed = features.useLiquidGlass && supportsLiquidGlass,
                                    onSelectSurface = { answers = answers.copy(visualSurfaceChoice = it) },
                                    onSelectShape = { answers = answers.copy(visualShapeChoice = it) }
                                )

                                WizardStep.Q4_STAGE2 -> Stage2MethodStep(
                                    selected = answers.stage2ModeChoice,
                                    onSelect = { answers = answers.copy(stage2ModeChoice = it) }
                                )

                                WizardStep.SUMMARY -> SummaryStep(
                                    generatedConfig = generatedConfig,
                                    features = features,
                                    sampleCount = thumbYSamples.size
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    if (currentStep == WizardStep.SUMMARY) {
                        Button(
                            onClick = { onApplyConfig(generatedConfig) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("ab_wizard_apply_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.ab_wizard_apply_button),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("ab_wizard_cancel_button"),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, extendedColors.cardBorder)
                        ) {
                            Text(
                                text = stringResource(R.string.button_cancel),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    } else {
                        Button(
                            onClick = { stepIndex = (safeIndex + 1).coerceAtMost(steps.lastIndex) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("ab_wizard_next_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.onboarding_button_next),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
}

@Composable
private fun FeatureChecklistStep(
    features: BeginnerABFeatureSelection,
    supportsLiquidGlass: Boolean,
    onChange: (BeginnerABFeatureSelection) -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.ab_step_features_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.ab_step_features_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))

        SectionCard {
            WizardFeatureToggleRow(
                title = stringResource(R.string.ab_feat_quick_menu_title),
                desc = stringResource(R.string.ab_feat_quick_menu_desc),
                checked = features.useQuickMenu,
                testTag = "ab_feat_quick_menu",
                onCheckedChange = { onChange(features.copy(useQuickMenu = it)) }
            )
            HorizontalDivider(color = extendedColors.cardBorder)
            WizardFeatureToggleRow(
                title = stringResource(R.string.ab_feat_recent_switcher_title),
                desc = stringResource(R.string.ab_feat_recent_switcher_desc),
                checked = features.useRecentSwitcher,
                testTag = "ab_feat_recent_switcher",
                onCheckedChange = { onChange(features.copy(useRecentSwitcher = it)) }
            )
            HorizontalDivider(color = extendedColors.cardBorder)
            WizardFeatureToggleRow(
                title = stringResource(R.string.ab_feat_bottom_bar_title),
                desc = stringResource(R.string.ab_feat_bottom_bar_desc),
                checked = features.useBottomBar,
                testTag = "ab_feat_bottom_bar",
                onCheckedChange = { onChange(features.copy(useBottomBar = it)) }
            )
            HorizontalDivider(color = extendedColors.cardBorder)
            WizardFeatureToggleRow(
                title = stringResource(R.string.ab_feat_three_stage_title),
                desc = stringResource(R.string.ab_feat_three_stage_desc),
                checked = features.useThreeStage,
                testTag = "ab_feat_three_stage",
                onCheckedChange = { onChange(features.copy(useThreeStage = it)) }
            )
            if (supportsLiquidGlass) {
                HorizontalDivider(color = extendedColors.cardBorder)
                WizardFeatureToggleRow(
                    title = stringResource(R.string.ab_feat_liquid_glass_title),
                    desc = stringResource(R.string.ab_feat_liquid_glass_desc),
                    checked = features.useLiquidGlass,
                    testTag = "ab_feat_liquid_glass",
                    onCheckedChange = { onChange(features.copy(useLiquidGlass = it)) }
                )
            }
        }
    }
}

@Composable
private fun WizardFeatureToggleRow(
    title: String,
    desc: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun SideWidthAndThumbStep(
    selected: ABChoice,
    sampleCount: Int,
    estimatedOffsetPercent: Int,
    onSelect: (ABChoice) -> Unit,
    onRecordSwipe: (yRatio: Float, distDp: Float) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val bgColor = MaterialTheme.colorScheme.background
    val extendedColors = LocalEdgeFlowColors.current

    var dragActive by remember { mutableStateOf(false) }
    var dragFromLeft by remember { mutableStateOf(true) }
    var anchorY by remember { mutableFloatStateOf(0.55f) }
    var tipOffsetY by remember { mutableFloatStateOf(0f) }
    var pullPx by remember { mutableFloatStateOf(0f) }

    val zoneWidthDp = if (selected == ABChoice.B) 24.dp else 14.dp

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState, enabled = !dragActive),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.ab_q1_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.ab_q1_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ABOptionPair(
            selected = selected,
            titleA = stringResource(R.string.ab_q1_opt_a_title),
            descA = stringResource(R.string.ab_q1_opt_a_desc),
            titleB = stringResource(R.string.ab_q1_opt_b_title),
            descB = stringResource(R.string.ab_q1_opt_b_desc),
            testTagPrefix = "ab_q1",
            onSelect = onSelect
        )

        // Interactive swipe test pad that records live thumb height & swipe distance
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(surfaceVariant.copy(alpha = 0.55f), bgColor)))
                .border(1.dp, extendedColors.cardBorder, RoundedCornerShape(20.dp))
                .pointerInput(selected) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val startPos = down.position
                        val startedOnLeft = startPos.x <= size.width * 0.5f
                        var maxInwardPx = 0f
                        dragFromLeft = startedOnLeft
                        anchorY = (startPos.y / size.height.coerceAtLeast(1)).coerceIn(0.12f, 0.88f)
                        tipOffsetY = 0f
                        pullPx = 0f
                        dragActive = true
                        var completedNormally = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                change.consume()
                                if (!change.pressed) {
                                    completedNormally = true
                                    break
                                }
                                val pos = change.position
                                val inward = if (startedOnLeft) {
                                    (pos.x - startPos.x).coerceAtLeast(0f)
                                } else {
                                    (startPos.x - pos.x).coerceAtLeast(0f)
                                }
                                pullPx = inward
                                if (inward > maxInwardPx) maxInwardPx = inward
                                tipOffsetY = (pos.y - startPos.y).coerceIn(-72f, 72f)
                            }
                        } finally {
                            dragActive = false
                            if (completedNormally) {
                                val distDp = with(density) { maxInwardPx.toDp().value }
                                if (distDp >= 18f) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onRecordSwipe(anchorY, distDp)
                                }
                            }
                            pullPx = 0f
                            tipOffsetY = 0f
                        }
                    }
                }
                .testTag("ab_q1_test_pad")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val stripW = zoneWidthDp.toPx()

                // Left & right touch-zone highlight strips reflecting A (14dp) vs B (24dp)
                drawRoundRect(
                    color = primary.copy(alpha = 0.24f),
                    topLeft = Offset(0f, h * 0.14f),
                    size = Size(stripW, h * 0.72f),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )
                drawRoundRect(
                    color = primary.copy(alpha = 0.24f),
                    topLeft = Offset(w - stripW, h * 0.14f),
                    size = Size(stripW, h * 0.72f),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )

                // Root-fixed + tip-bending live droplet feedback
                if (dragActive && pullPx > 4f) {
                    val baseY = h * anchorY
                    val dir = if (dragFromLeft) 1f else -1f
                    val edgeX = if (dragFromLeft) 0f else w
                    val peak = (pullPx * 0.42f).coerceIn(12.dp.toPx(), 42.dp.toPx())
                    val halfSpan = 56.dp.toPx()
                    val tipY = baseY + tipOffsetY * 0.45f
                    val path = Path().apply {
                        moveTo(edgeX, baseY - halfSpan)
                        cubicTo(
                            edgeX, baseY - halfSpan * 0.50f,
                            edgeX + dir * peak, tipY - halfSpan * 0.34f,
                            edgeX + dir * peak, tipY
                        )
                        cubicTo(
                            edgeX + dir * peak, tipY + halfSpan * 0.34f,
                            edgeX, baseY + halfSpan * 0.50f,
                            edgeX, baseY + halfSpan
                        )
                        close()
                    }
                    drawPath(path = path, color = primary.copy(alpha = 0.32f))
                    drawPath(path = path, color = primary, style = Stroke(width = 1.6.dp.toPx()))
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.ab_q1_pad_hint),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (sampleCount > 0) {
                        extendedColors.statusActiveContainer
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                    }
                ) {
                    Text(
                        text = if (sampleCount > 0) {
                            stringResource(R.string.ab_q1_calibrated_status, sampleCount, estimatedOffsetPercent)
                        } else {
                            stringResource(R.string.ab_q1_uncalibrated_status)
                        },
                        style = NumericLabelStyle,
                        color = if (sampleCount > 0) {
                            extendedColors.statusActive
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomComfortStep(
    selected: ABChoice,
    onSelect: (ABChoice) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val bgColor = MaterialTheme.colorScheme.background
    val extendedColors = LocalEdgeFlowColors.current

    var pullUpPx by remember { mutableFloatStateOf(0f) }
    var isPadDragging by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState, enabled = !isPadDragging),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.ab_q2_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.ab_q2_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ABOptionPair(
            selected = selected,
            titleA = stringResource(R.string.ab_q2_opt_a_title),
            descA = stringResource(R.string.ab_q2_opt_a_desc),
            titleB = stringResource(R.string.ab_q2_opt_b_title),
            descB = stringResource(R.string.ab_q2_opt_b_desc),
            testTagPrefix = "ab_q2",
            onSelect = onSelect
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(surfaceVariant.copy(alpha = 0.55f), bgColor)))
                .border(1.dp, extendedColors.cardBorder, RoundedCornerShape(20.dp))
                .pointerInput(selected) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val startY = down.position.y
                        isPadDragging = true
                        var maxPullUp = 0f
                        var completedNormally = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                change.consume()
                                if (!change.pressed) {
                                    completedNormally = true
                                    break
                                }
                                val up = (startY - change.position.y).coerceAtLeast(0f)
                                pullUpPx = up
                                if (up > maxPullUp) maxPullUp = up
                            }
                        } finally {
                            isPadDragging = false
                            if (completedNormally && maxPullUp > 32f) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            pullUpPx = 0f
                        }
                    }
                }
                .testTag("ab_q2_test_pad")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val barW = w * (if (selected == ABChoice.B) 0.50f else 0.38f)
                val touchZoneH = (if (selected == ABChoice.B) 28.dp else 20.dp).toPx()
                val bottomOff = (if (selected == ABChoice.B) 8.dp else 2.dp).toPx()
                val lift = 12.dp.toPx() * (1f - exp((-pullUpPx / 140f).toDouble()).toFloat())

                drawRoundRect(
                    color = primary.copy(alpha = 0.16f),
                    topLeft = Offset((w - barW) * 0.5f, h - touchZoneH - bottomOff),
                    size = Size(barW, touchZoneH),
                    cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
                )

                val pillH = 5.dp.toPx()
                val pillY = h - bottomOff - 7.dp.toPx() - pillH - lift
                drawRoundRect(
                    color = if (pullUpPx > 28f) primary else Color(0xFF4A505A),
                    topLeft = Offset((w - barW) * 0.5f, pillY),
                    size = Size(barW, pillH),
                    cornerRadius = CornerRadius(pillH * 0.5f, pillH * 0.5f)
                )
            }
            Text(
                text = stringResource(R.string.ab_q2_pad_hint),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun VisualStyleStep(
    surfaceChoice: ABChoice,
    shapeChoice: ABChoice,
    liquidGlassAllowed: Boolean,
    onSelectSurface: (ABChoice) -> Unit,
    onSelectShape: (ABChoice) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val bgColor = MaterialTheme.colorScheme.background
    val extendedColors = LocalEdgeFlowColors.current

    var pullPx by remember { mutableFloatStateOf(46f) }
    var isPadDragging by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState, enabled = !isPadDragging),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.ab_q3_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.ab_q3_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (liquidGlassAllowed) {
            ABOptionPair(
                selected = surfaceChoice,
                titleA = stringResource(R.string.ab_q3_surface_a_title),
                descA = stringResource(R.string.ab_q3_surface_a_desc),
                titleB = stringResource(R.string.ab_q3_surface_b_title),
                descB = stringResource(R.string.ab_q3_surface_b_desc),
                testTagPrefix = "ab_q3_surface",
                onSelect = onSelectSurface
            )
        }

        ABOptionPair(
            selected = shapeChoice,
            titleA = stringResource(R.string.ab_q3_shape_a_title),
            descA = stringResource(R.string.ab_q3_shape_a_desc),
            titleB = stringResource(R.string.ab_q3_shape_b_title),
            descB = stringResource(R.string.ab_q3_shape_b_desc),
            testTagPrefix = "ab_q3_shape",
            onSelect = onSelectShape
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(144.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.horizontalGradient(listOf(primary.copy(alpha = 0.14f), surfaceVariant.copy(alpha = 0.5f), bgColor)))
                .border(1.dp, extendedColors.cardBorder, RoundedCornerShape(20.dp))
                .pointerInput(surfaceChoice, shapeChoice) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isPadDragging = true
                        try {
                            pullPx = down.position.x.coerceIn(24f, size.width * 0.45f)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                change.consume()
                                if (!change.pressed) break
                                pullPx = change.position.x.coerceIn(24f, size.width * 0.45f)
                            }
                        } finally {
                            isPadDragging = false
                            pullPx = 46f
                        }
                    }
                }
                .testTag("ab_q3_preview_pad")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val h = size.height
                val cy = h * 0.5f
                val peak = pullPx.coerceIn(24.dp.toPx(), 54.dp.toPx())
                val isLiquid = liquidGlassAllowed && surfaceChoice == ABChoice.A

                if (shapeChoice == ABChoice.A) {
                    val halfSpan = 54.dp.toPx()
                    val path = Path().apply {
                        moveTo(0f, cy - halfSpan)
                        cubicTo(0f, cy - halfSpan * 0.5f, peak, cy - halfSpan * 0.34f, peak, cy)
                        cubicTo(peak, cy + halfSpan * 0.34f, 0f, cy + halfSpan * 0.5f, 0f, cy + halfSpan)
                        close()
                    }
                    drawPath(
                        path = path,
                        color = if (isLiquid) Color.White.copy(alpha = 0.32f) else Color(0xFF1D222D).copy(alpha = 0.80f)
                    )
                    drawPath(
                        path = path,
                        color = if (isLiquid) Color.White.copy(alpha = 0.90f) else primary,
                        style = Stroke(width = if (isLiquid) 2.dp.toPx() else 1.4.dp.toPx())
                    )
                } else {
                    val cx = peak * 0.72f
                    val r = 18.dp.toPx()
                    drawCircle(
                        color = if (isLiquid) Color.White.copy(alpha = 0.35f) else Color(0xFF1D222D).copy(alpha = 0.82f),
                        radius = r,
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = if (isLiquid) Color.White.copy(alpha = 0.9f) else primary,
                        radius = r,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.6.dp.toPx())
                    )
                }
            }
            Text(
                text = stringResource(R.string.ab_q3_pad_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 18.dp)
            )
        }
    }
}

@Composable
private fun Stage2MethodStep(
    selected: ABChoice,
    onSelect: (ABChoice) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val bgColor = MaterialTheme.colorScheme.background
    val extendedColors = LocalEdgeFlowColors.current

    var dragXRatio by remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    var stageLevel by remember { mutableIntStateOf(0) }
    var isPadDragging by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    LaunchedEffect(holding, selected) {
        if (holding && selected == ABChoice.B) {
            delay(320L)
            if (holding && stageLevel == 1) {
                stageLevel = 2
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState, enabled = !isPadDragging),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.ab_q4_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.ab_q4_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ABOptionPair(
            selected = selected,
            titleA = stringResource(R.string.ab_q4_opt_a_title),
            descA = stringResource(R.string.ab_q4_opt_a_desc),
            titleB = stringResource(R.string.ab_q4_opt_b_title),
            descB = stringResource(R.string.ab_q4_opt_b_desc),
            testTagPrefix = "ab_q4",
            onSelect = onSelect
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.horizontalGradient(listOf(surfaceVariant.copy(alpha = 0.55f), bgColor)))
                .border(1.dp, extendedColors.cardBorder, RoundedCornerShape(20.dp))
                .pointerInput(selected) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val startX = down.position.x
                        isPadDragging = true
                        dragXRatio = 0f
                        stageLevel = 0
                        holding = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                change.consume()
                                if (!change.pressed) break
                                val ratio = ((change.position.x - startX) / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                dragXRatio = ratio
                                if (selected == ABChoice.A) {
                                    val nextStage = when {
                                        ratio >= 0.34f -> 2
                                        ratio >= 0.10f -> 1
                                        else -> 0
                                    }
                                    if (nextStage != stageLevel) {
                                        stageLevel = nextStage
                                        if (nextStage > 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                } else {
                                    if (ratio >= 0.12f) {
                                        if (stageLevel == 0) {
                                            stageLevel = 1
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        holding = true
                                    } else {
                                        holding = false
                                        stageLevel = 0
                                    }
                                }
                            }
                        } finally {
                            isPadDragging = false
                            holding = false
                            dragXRatio = 0f
                            stageLevel = 0
                        }
                    }
                }
                .testTag("ab_q4_test_pad")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                if (selected == ABChoice.A) {
                    val thresholdX = w * 0.34f
                    drawLine(
                        color = primary.copy(alpha = if (stageLevel >= 2) 0.9f else 0.35f),
                        start = Offset(thresholdX, 20.dp.toPx()),
                        end = Offset(thresholdX, h - 20.dp.toPx()),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                if (dragXRatio > 0.02f) {
                    val cx = (w * dragXRatio).coerceIn(24.dp.toPx(), w - 24.dp.toPx())
                    drawCircle(
                        color = if (stageLevel >= 2) primary else primary.copy(alpha = 0.45f),
                        radius = if (stageLevel >= 2) 22.dp.toPx() else 16.dp.toPx(),
                        center = Offset(cx, h * 0.56f)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when (stageLevel) {
                        2 -> stringResource(R.string.ab_q4_stage2_lit)
                        1 -> stringResource(R.string.ab_q4_stage1_lit)
                        else -> stringResource(R.string.ab_q4_pad_hint)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (stageLevel >= 2) primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SummaryStep(
    generatedConfig: EdgeConfig,
    features: BeginnerABFeatureSelection,
    sampleCount: Int
) {
    val extendedColors = LocalEdgeFlowColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HinataIcons.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.ab_summary_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.ab_summary_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionCard {
            SummaryMetricRow(
                label = stringResource(R.string.label_edge_width),
                value = "${generatedConfig.edgeWidthDp.roundToInt()} dp"
            )
            HorizontalDivider(color = extendedColors.cardBorder)
            SummaryMetricRow(
                label = stringResource(R.string.label_edge_offset),
                value = if (sampleCount > 0) {
                    "${(generatedConfig.edgeBottomOffsetPercent * 100f).roundToInt()}% (${stringResource(R.string.ab_summary_calibrated_tag)})"
                } else {
                    "${(generatedConfig.edgeBottomOffsetPercent * 100f).roundToInt()}%"
                }
            )
            HorizontalDivider(color = extendedColors.cardBorder)
            SummaryMetricRow(
                label = stringResource(R.string.label_swipe_threshold),
                value = "${generatedConfig.swipeThresholdDp.roundToInt()} dp"
            )
            HorizontalDivider(color = extendedColors.cardBorder)
            SummaryMetricRow(
                label = stringResource(R.string.label_side_trigger_mode),
                value = stringResource(
                    if (generatedConfig.sideTriggerMode == SecondaryTriggerMode.HOLD) {
                        R.string.mode_hold
                    } else {
                        R.string.mode_distance
                    }
                )
            )
            HorizontalDivider(color = extendedColors.cardBorder)
            SummaryMetricRow(
                label = stringResource(R.string.label_bottom_bar),
                value = if (generatedConfig.bottomEnabled) {
                    "${generatedConfig.bottomBarHeightDp.roundToInt()} dp / +${generatedConfig.bottomBarBottomOffsetDp.roundToInt()} dp"
                } else {
                    stringResource(R.string.ab_summary_off)
                }
            )
            HorizontalDivider(color = extendedColors.cardBorder)
            SummaryMetricRow(
                label = stringResource(R.string.label_liquid_glass),
                value = stringResource(
                    if (generatedConfig.liquidGlassEnabled) R.string.ab_summary_on else R.string.ab_summary_off
                )
            )
            HorizontalDivider(color = extendedColors.cardBorder)
            SummaryMetricRow(
                label = stringResource(R.string.ab_feat_three_stage_title),
                value = stringResource(
                    if (features.useThreeStage) R.string.ab_summary_on else R.string.ab_summary_off
                )
            )
        }
    }
}

@Composable
private fun SummaryMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = NumericLabelStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ABOptionPair(
    selected: ABChoice,
    titleA: String,
    descA: String,
    titleB: String,
    descB: String,
    testTagPrefix: String,
    onSelect: (ABChoice) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ABOptionCard(
            badge = "A",
            title = titleA,
            desc = descA,
            selected = selected == ABChoice.A,
            onClick = { onSelect(ABChoice.A) },
            modifier = Modifier
                .weight(1f)
                .testTag("${testTagPrefix}_A")
        )
        ABOptionCard(
            badge = "B",
            title = titleB,
            desc = descB,
            selected = selected == ABChoice.B,
            onClick = { onSelect(ABChoice.B) },
            modifier = Modifier
                .weight(1f)
                .testTag("${testTagPrefix}_B")
        )
    }
}

@Composable
private fun ABOptionCard(
    badge: String,
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extendedColors = LocalEdgeFlowColors.current
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val border = if (selected) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, extendedColors.cardBorder)
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = border
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = badge,
                            style = NumericLabelStyle,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
