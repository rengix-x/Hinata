package com.edgeflow.settings

import android.app.StatusBarManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import com.example.ui.icons.HinataIcons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import com.edgeflow.service.shizuku.ShizukuStatus
import com.example.R
import com.example.ui.theme.LocalEdgeFlowColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val TOTAL_ONBOARDING_STEPS = 6
private const val QS_TILE_COUNTDOWN_SECONDS = 5

private const val URL_SHIZUKU_PLAY = "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"
private const val URL_SHIZUKU_GITHUB = "https://github.com/RikkaApps/Shizuku/releases"
private const val URL_SHIZUKU_FDROID = "https://apt.izzysoft.de/fdroid/index/apk/moe.shizuku.privileged.api"
private const val URL_SHIZUKU_APKMIRROR = "https://www.apkmirror.com/apk/rikka-apps/shizuku/"

private enum class OnboardingSandboxTab {
    SIDE,
    MENU,
    BOTTOM
}

/**
 * 6-step interactive onboarding experience strictly hosted in the `:settings` process.
 *
 * Step 1: Welcome, 4-language quick switch, privacy assurance, strong lockout/self-responsibility
 *         warning & 2 mandatory independent checkboxes.
 * Step 2: Interactive in-app gesture sandbox (Side droplet, Quick Menu arc, Bottom bar lift/hold).
 * Step 3: Mandatory live auto-detecting Accessibility Service setup (no skip; Shizuku 1-tap ON if connected).
 * Step 4: Strongly recommended Quick Panel "Hinata Recovery" Tile step with 5-second countdown lock.
 * Step 5: Beginner-friendly 3-button bar hiding & Shizuku guide with external links.
 * Step 6: Unrestricted Battery recommendation + Finish / Launch Beginner A/B Auto-Tune (Beta).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    currentLanguage: AppLanguageOption,
    isAccessibilityEnabled: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    isReplayMode: Boolean,
    onSelectLanguage: (AppLanguageOption) -> Unit,
    onRefreshStatus: () -> Unit = {},
    onComplete: () -> Unit,
    onCompleteToABWizard: () -> Unit = onComplete,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val extendedColors = LocalEdgeFlowColors.current
    val scope = rememberCoroutineScope()
    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    var acceptedRiskAndSelfResponsibility by rememberSaveable { mutableStateOf(isReplayMode) }
    var acceptedAccessibilityMandatory by rememberSaveable { mutableStateOf(isReplayMode) }
    var qsTileCountdownRemaining by rememberSaveable {
        mutableIntStateOf(if (isReplayMode) 0 else QS_TILE_COUNTDOWN_SECONDS)
    }
    var qsTileAddedOrRequested by rememberSaveable { mutableStateOf(isReplayMode) }
    var isEnablingA11yWithShizuku by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }

    // Live Shizuku status for Steps 3 & 5, refreshed automatically on resume
    var shizukuStatus by remember { mutableStateOf(ShizukuShellExecutor.checkStatus(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ShizukuShellExecutor.refreshNonProviderBinder(context)
                shizukuStatus = ShizukuShellExecutor.checkStatus(context)
                onRefreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 5-second mandatory reading countdown on Step 4 (index 3: Quick Settings Recovery Tile)
    LaunchedEffect(currentStep, qsTileCountdownRemaining) {
        if (currentStep == 3 && qsTileCountdownRemaining > 0) {
            delay(1_000L)
            qsTileCountdownRemaining = (qsTileCountdownRemaining - 1).coerceAtLeast(0)
        }
    }

    BackHandler(enabled = currentStep > 0 || isReplayMode) {
        if (currentStep > 0) {
            currentStep -= 1
        } else if (isReplayMode) {
            onComplete()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding_screen"),
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
                    .widthIn(max = 540.dp)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                // Top bar: back button + instant language switcher chip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (currentStep > 0) {
                        IconButton(
                            onClick = { currentStep -= 1 },
                            modifier = Modifier.testTag("onboarding_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    } else if (isReplayMode) {
                        IconButton(
                            onClick = onComplete,
                            modifier = Modifier.testTag("onboarding_close_replay_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }

                    val languageChipLabel = if (currentLanguage == AppLanguageOption.SYSTEM) {
                        stringResource(R.string.onboarding_language_chip)
                    } else {
                        currentLanguage.nativeLabel
                    }
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { showLanguageSheet = true }
                            .testTag("onboarding_language_chip"),
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, extendedColors.cardBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = HinataIcons.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = languageChipLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Animated step content
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    AnimatedContent(
                        targetState = currentStep,
                        transitionSpec = {
                            val forward = targetState > initialState
                            val slideIn = slideInHorizontally(
                                animationSpec = tween(260),
                                initialOffsetX = { width -> if (forward) width / 4 else -width / 4 }
                            ) + fadeIn(animationSpec = tween(220))
                            val slideOut = slideOutHorizontally(
                                animationSpec = tween(220),
                                targetOffsetX = { width -> if (forward) -width / 4 else width / 4 }
                            ) + fadeOut(animationSpec = tween(180))
                            slideIn togetherWith slideOut
                        },
                        label = "onboarding_step_transition"
                    ) { step ->
                        when (step) {
                            0 -> OnboardingStepWelcomeDisclaimer(
                                currentLanguage = currentLanguage,
                                acceptedRiskAndSelfResponsibility = acceptedRiskAndSelfResponsibility,
                                acceptedAccessibilityMandatory = acceptedAccessibilityMandatory,
                                onToggleRiskAndSelfResponsibility = { acceptedRiskAndSelfResponsibility = it },
                                onToggleAccessibilityMandatory = { acceptedAccessibilityMandatory = it },
                                onSelectLanguage = onSelectLanguage
                            )
                            1 -> OnboardingStepInteractiveSandbox()
                            2 -> OnboardingStepAccessibility(
                                isAccessibilityEnabled = isAccessibilityEnabled
                            )
                            3 -> OnboardingStepQuickTileSafety(
                                tileAddedOrRequested = qsTileAddedOrRequested,
                                onRequestAddTile = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        val dispatched = requestAddRecoveryQsTile(context) { resultCode ->
                                            qsTileAddedOrRequested = true
                                            qsTileCountdownRemaining = 0
                                            val msgRes = when (resultCode) {
                                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                                                    R.string.onboarding_qs_tile_added_toast
                                                else ->
                                                    R.string.onboarding_qs_tile_manual_hint_toast
                                            }
                                            Toast.makeText(context, msgRes, Toast.LENGTH_LONG).show()
                                        }
                                        if (dispatched) {
                                            qsTileCountdownRemaining = 0
                                        } else {
                                            Toast.makeText(
                                                context,
                                                R.string.onboarding_qs_tile_manual_hint_toast,
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    } else {
                                        Toast.makeText(
                                            context,
                                            R.string.onboarding_qs_tile_manual_hint_toast,
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                onOpenQuickPanelHint = {
                                    expandQuickSettingsPanelBestEffort(context)
                                    Toast.makeText(
                                        context,
                                        R.string.onboarding_qs_tile_manual_hint_toast,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            )
                            4 -> OnboardingStepShizukuGuide(
                                shizukuStatus = shizukuStatus,
                                onOpenExternalUrl = { url -> openExternalUrl(context, url) }
                            )
                            else -> OnboardingStepReady(
                                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                                onOpenBatterySettings = {
                                    openBatteryUnrestrictedSettings(context, keepTaskAlive = true)
                                }
                            )
                        }
                    }
                }

                // Dot progress indicator (6 steps)
                OnboardingStepDots(
                    totalSteps = TOTAL_ONBOARDING_STEPS,
                    currentStep = currentStep,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 12.dp)
                )

                // Step-specific bottom actions
                when (currentStep) {
                    0 -> {
                        val canProceed = (acceptedRiskAndSelfResponsibility &&
                            acceptedAccessibilityMandatory) || isReplayMode
                        Button(
                            onClick = { currentStep = 1 },
                            enabled = canProceed,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp)
                                .testTag("onboarding_start_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.onboarding_button_agree_start),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    1 -> {
                        Button(
                            onClick = { currentStep = 2 },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp)
                                .testTag("onboarding_sandbox_next_button"),
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
                        Spacer(Modifier.height(10.dp))
                    }

                    2 -> {
                        if (isAccessibilityEnabled) {
                            Button(
                                onClick = { currentStep = 3 },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 54.dp)
                                    .testTag("onboarding_step2_next_button"),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = extendedColors.statusActive,
                                    contentColor = Color.White
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
                            Spacer(Modifier.height(10.dp))
                        } else {
                            Button(
                                onClick = {
                                    openAccessibilitySettings(context, keepTaskAlive = true)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 54.dp)
                                    .testTag("onboarding_open_accessibility_button"),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text(
                                    text = stringResource(R.string.onboarding_step2_open_settings),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = HinataIcons.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (shizukuStatus == ShizukuStatus.CONNECTED) {
                                Spacer(Modifier.height(8.dp))
                                FilledTonalButton(
                                    onClick = {
                                        if (isEnablingA11yWithShizuku) return@FilledTonalButton
                                        isEnablingA11yWithShizuku = true
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                ShizukuShellExecutor.reviveAccessibilityServiceWithShizuku(
                                                    context = context,
                                                    forceToggleRebind = true
                                                )
                                            }
                                            delay(450L)
                                            onRefreshStatus()
                                            isEnablingA11yWithShizuku = false
                                            if (!result.success) {
                                                Toast.makeText(
                                                    context,
                                                    R.string.recovery_toast_revive_failed_open_settings,
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    enabled = !isEnablingA11yWithShizuku,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 50.dp)
                                        .testTag("onboarding_shizuku_auto_a11y_button"),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    if (isEnablingA11yWithShizuku) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = HinataIcons.RestartAlt,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.a11y_gate_shizuku_auto_enable_button),
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    3 -> {
                        val unlocked = qsTileCountdownRemaining <= 0
                        Button(
                            onClick = { currentStep = 4 },
                            enabled = unlocked,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp)
                                .testTag("onboarding_qs_tile_next_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (qsTileAddedOrRequested) {
                                    extendedColors.statusActive
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                contentColor = if (qsTileAddedOrRequested) {
                                    Color.White
                                } else {
                                    MaterialTheme.colorScheme.onPrimary
                                }
                            )
                        ) {
                            Text(
                                text = if (unlocked) {
                                    stringResource(R.string.onboarding_qs_tile_next_button)
                                } else {
                                    stringResource(
                                        R.string.onboarding_qs_tile_countdown_button,
                                        qsTileCountdownRemaining
                                    )
                                },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (unlocked) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    4 -> {
                        val isShizukuConnected = shizukuStatus == ShizukuStatus.CONNECTED
                        Button(
                            onClick = { currentStep = 5 },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp)
                                .testTag("onboarding_shizuku_next_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isShizukuConnected) extendedColors.statusActive else MaterialTheme.colorScheme.primary,
                                contentColor = if (isShizukuConnected) Color.White else MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(
                                text = stringResource(
                                    if (isShizukuConnected) R.string.onboarding_button_next
                                    else R.string.onboarding_button_skip
                                ),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    else -> {
                        Button(
                            onClick = onCompleteToABWizard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp)
                                .testTag("onboarding_launch_ab_wizard_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = HinataIcons.AutoFixHigh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.onboarding_button_try_ab_wizard),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onComplete,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                                .testTag("onboarding_finish_button"),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, extendedColors.cardBorder)
                        ) {
                            Text(
                                text = stringResource(R.string.onboarding_button_start_now),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    if (showLanguageSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showLanguageSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
                    .testTag("onboarding_language_sheet")
            ) {
                Text(
                    text = stringResource(R.string.settings_language_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(14.dp))
                AppLanguageOption.entries.forEach { option ->
                    val selected = currentLanguage == option
                    val label = if (option == AppLanguageOption.SYSTEM) {
                        stringResource(R.string.lang_system_default)
                    } else {
                        option.nativeLabel
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                else Color.Transparent
                            )
                            .clickable {
                                showLanguageSheet = false
                                onSelectLanguage(option)
                            }
                            .padding(horizontal = 14.dp, vertical = 14.dp)
                            .testTag("onboarding_lang_${option.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        if (selected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.cd_selected),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingStepWelcomeDisclaimer(
    currentLanguage: AppLanguageOption,
    acceptedRiskAndSelfResponsibility: Boolean,
    acceptedAccessibilityMandatory: Boolean,
    onToggleRiskAndSelfResponsibility: (Boolean) -> Unit,
    onToggleAccessibilityMandatory: (Boolean) -> Unit,
    onSelectLanguage: (AppLanguageOption) -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    val cardColor = MaterialTheme.colorScheme.surface
    val cardBorder = BorderStroke(1.dp, extendedColors.cardBorder)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        HinataBrandMark(modifier = Modifier.size(76.dp))

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = stringResource(R.string.onboarding_step1_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(14.dp))

        // Quick 4-language switcher chips
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf(
                AppLanguageOption.JAPANESE,
                AppLanguageOption.ENGLISH,
                AppLanguageOption.CHINESE_SIMPLIFIED,
                AppLanguageOption.HINDI
            ).forEach { option ->
                val selected = currentLanguage == option
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onSelectLanguage(option) }
                        .testTag("onboarding_quick_lang_${option.name}"),
                    shape = RoundedCornerShape(999.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else cardColor,
                    border = if (selected) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        cardBorder
                    }
                ) {
                    Text(
                        text = option.nativeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Privacy assurance card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = cardColor,
            border = cardBorder
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(extendedColors.statusActiveContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = HinataIcons.Shield,
                        contentDescription = null,
                        tint = extendedColors.statusActive,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.onboarding_privacy_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.onboarding_privacy_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Prominent critical warning card + 2 mandatory independent consent checkboxes
        val bothAccepted = acceptedRiskAndSelfResponsibility && acceptedAccessibilityMandatory
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_disclaimer_card"),
            shape = RoundedCornerShape(18.dp),
            color = if (bothAccepted) {
                cardColor
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.24f)
            },
            border = BorderStroke(
                width = 1.5.dp,
                color = if (bothAccepted) {
                    extendedColors.statusActive.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.65f)
                }
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = HinataIcons.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.onboarding_disclaimer_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_disclaimer_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            onToggleRiskAndSelfResponsibility(!acceptedRiskAndSelfResponsibility)
                        }
                        .padding(vertical = 4.dp)
                        .testTag("onboarding_disclaimer_checkbox_row"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = acceptedRiskAndSelfResponsibility,
                        onCheckedChange = onToggleRiskAndSelfResponsibility,
                        modifier = Modifier.testTag("onboarding_disclaimer_checkbox")
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.onboarding_disclaimer_agree),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            onToggleAccessibilityMandatory(!acceptedAccessibilityMandatory)
                        }
                        .padding(vertical = 4.dp)
                        .testTag("onboarding_a11y_mandatory_checkbox_row"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = acceptedAccessibilityMandatory,
                        onCheckedChange = onToggleAccessibilityMandatory,
                        modifier = Modifier.testTag("onboarding_a11y_mandatory_checkbox")
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.onboarding_a11y_mandatory_agree),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingStepInteractiveSandbox() {
    val extendedColors = LocalEdgeFlowColors.current
    val haptic = LocalHapticFeedback.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.tertiary

    var activeTab by rememberSaveable { mutableStateOf(OnboardingSandboxTab.SIDE) }
    var triedSide by rememberSaveable { mutableStateOf(false) }
    var triedMenu by rememberSaveable { mutableStateOf(false) }
    var triedBottom by rememberSaveable { mutableStateOf(false) }

    // Interactive sandbox canvas state (no Accessibility permission required)
    var isDragging by remember { mutableStateOf(false) }
    var fromRight by remember { mutableStateOf(true) }
    var anchorX by remember { mutableFloatStateOf(0f) }
    var anchorY by remember { mutableFloatStateOf(0f) }
    var fingerX by remember { mutableFloatStateOf(0f) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var sideStage by remember { mutableIntStateOf(0) }
    var menuSlotIndex by remember { mutableIntStateOf(-1) }
    var bottomLiftFraction by remember { mutableFloatStateOf(0f) }
    var bottomHoldActive by remember { mutableStateOf(false) }
    var bottomHoldProgress by remember { mutableFloatStateOf(0f) }
    var bottomTouchId by remember { mutableIntStateOf(0) }
    var bottomIsHoldingCandidate by remember { mutableStateOf(false) }
    var lastBottomOutcomeRes by remember { mutableStateOf<Int?>(null) }

    val menuSlotLabels = listOf(
        stringResource(R.string.action_back),
        stringResource(R.string.action_home),
        stringResource(R.string.action_recents),
        stringResource(R.string.action_screenshot),
        stringResource(R.string.action_toggle_torch)
    )

    // Hold-to-shimmer timer for Bottom Bar mode
    LaunchedEffect(activeTab, bottomTouchId, bottomIsHoldingCandidate) {
        if (activeTab == OnboardingSandboxTab.BOTTOM && bottomIsHoldingCandidate) {
            bottomHoldProgress = 0f
            val totalSteps = 14
            for (step in 1..totalSteps) {
                delay(30L)
                if (!bottomIsHoldingCandidate) return@LaunchedEffect
                bottomHoldProgress = step / totalSteps.toFloat()
            }
            if (bottomIsHoldingCandidate) {
                bottomHoldActive = true
                lastBottomOutcomeRes = R.string.onboarding_sandbox_bottom_cts
                triedBottom = true
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        } else {
            bottomHoldProgress = 0f
        }
    }

    val statusText = when (activeTab) {
        OnboardingSandboxTab.SIDE -> when (sideStage) {
            1 -> stringResource(R.string.onboarding_sandbox_stage1)
            2 -> stringResource(R.string.onboarding_sandbox_stage2)
            3 -> stringResource(R.string.onboarding_sandbox_stage3)
            else -> stringResource(R.string.onboarding_sandbox_side_hint)
        }
        OnboardingSandboxTab.MENU -> if (menuSlotIndex in menuSlotLabels.indices) {
            stringResource(R.string.onboarding_sandbox_menu_selected, menuSlotLabels[menuSlotIndex])
        } else {
            stringResource(R.string.onboarding_sandbox_menu_hint)
        }
        OnboardingSandboxTab.BOTTOM -> when {
            bottomHoldActive -> stringResource(R.string.onboarding_sandbox_bottom_cts)
            bottomLiftFraction > 0.62f -> stringResource(R.string.onboarding_sandbox_bottom_recents)
            bottomLiftFraction > 0.16f -> stringResource(R.string.onboarding_sandbox_bottom_home)
            lastBottomOutcomeRes != null -> stringResource(lastBottomOutcomeRes!!)
            else -> stringResource(R.string.onboarding_sandbox_bottom_hint)
        }
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState, enabled = !isDragging),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = HinataIcons.TouchApp,
            contentDescription = null,
            tint = primaryColor,
            modifier = Modifier.size(34.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_sandbox_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.onboarding_sandbox_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(14.dp))

        // 3 tryout mode chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                Triple(OnboardingSandboxTab.SIDE, R.string.onboarding_sandbox_tab_side, triedSide),
                Triple(OnboardingSandboxTab.MENU, R.string.onboarding_sandbox_tab_menu, triedMenu),
                Triple(OnboardingSandboxTab.BOTTOM, R.string.onboarding_sandbox_tab_bottom, triedBottom)
            ).forEach { (tab, labelRes, tried) ->
                val selected = activeTab == tab
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            activeTab = tab
                            isDragging = false
                            sideStage = 0
                            menuSlotIndex = -1
                            bottomLiftFraction = 0f
                            bottomHoldActive = false
                            bottomHoldProgress = 0f
                            bottomIsHoldingCandidate = false
                            lastBottomOutcomeRes = null
                        }
                        .testTag("onboarding_sandbox_tab_${tab.name}"),
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border = if (selected) {
                        BorderStroke(1.2.dp, primaryColor)
                    } else {
                        BorderStroke(1.dp, extendedColors.cardBorder)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (tried) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = extendedColors.statusActive,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) primaryColor else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Live interactive mini-phone sandbox canvas
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .testTag("onboarding_sandbox_canvas"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.5.dp, extendedColors.cardBorder)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(activeTab) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val start = down.position
                                isDragging = true
                                fromRight = start.x >= w * 0.5f
                                anchorX = if (activeTab == OnboardingSandboxTab.BOTTOM) w * 0.5f else if (fromRight) w else 0f
                                anchorY = start.y.coerceIn(h * 0.18f, h * 0.82f)
                                fingerX = start.x
                                fingerY = start.y
                                sideStage = 0
                                menuSlotIndex = -1
                                bottomLiftFraction = 0f
                                bottomHoldActive = false
                                bottomHoldProgress = 0f
                                lastBottomOutcomeRes = null
                                bottomTouchId += 1
                                bottomIsHoldingCandidate = activeTab == OnboardingSandboxTab.BOTTOM && start.y >= h * 0.65f

                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        change.consume()
                                        if (!change.pressed) break
                                        val pos = change.position
                                        fingerX = pos.x
                                        fingerY = pos.y

                                        when (activeTab) {
                                            OnboardingSandboxTab.SIDE -> {
                                                val inward = if (fromRight) (w - pos.x).coerceAtLeast(0f) else pos.x.coerceAtLeast(0f)
                                                val frac = inward / (w * 0.58f)
                                                val newStage = when {
                                                    frac >= 0.85f -> 3
                                                    frac >= 0.48f -> 2
                                                    frac >= 0.14f -> 1
                                                    else -> 0
                                                }
                                                if (newStage != sideStage) {
                                                    sideStage = newStage
                                                    if (newStage > 0) {
                                                        triedSide = true
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                }
                                            }
                                            OnboardingSandboxTab.MENU -> {
                                                val dx = if (fromRight) (anchorX - pos.x) else (pos.x - anchorX)
                                                val dy = pos.y - anchorY
                                                val dist = hypot(dx, dy)
                                                if (dist > w * 0.12f) {
                                                    val angleDeg = (atan2(dy.toDouble(), dx.coerceAtLeast(1f).toDouble()) * 180.0 / PI).toFloat()
                                                    val normalized = ((angleDeg + 68f) / 136f).coerceIn(0f, 0.999f)
                                                    val newSlot = (normalized * 5f).toInt().coerceIn(0, 4)
                                                    if (newSlot != menuSlotIndex) {
                                                        menuSlotIndex = newSlot
                                                        triedMenu = true
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                } else {
                                                    menuSlotIndex = -1
                                                }
                                            }
                                            OnboardingSandboxTab.BOTTOM -> {
                                                val upDist = (start.y - pos.y).coerceAtLeast(0f)
                                                if (upDist > 16f || abs(pos.x - start.x) > 22f) {
                                                    bottomIsHoldingCandidate = false
                                                    bottomHoldActive = false
                                                }
                                                val newLift = (upDist / (h * 0.42f)).coerceIn(0f, 1f)
                                                val prevStage = if (bottomLiftFraction > 0.62f) 2 else if (bottomLiftFraction > 0.16f) 1 else 0
                                                val nextStage = if (newLift > 0.62f) 2 else if (newLift > 0.16f) 1 else 0
                                                bottomLiftFraction = newLift
                                                if (nextStage == 2) {
                                                    lastBottomOutcomeRes = R.string.onboarding_sandbox_bottom_recents
                                                } else if (nextStage == 1) {
                                                    lastBottomOutcomeRes = R.string.onboarding_sandbox_bottom_home
                                                }
                                                if (nextStage != prevStage && nextStage > 0) {
                                                    triedBottom = true
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                        }
                                    }
                                } finally {
                                    isDragging = false
                                    bottomIsHoldingCandidate = false
                                    // Cleanly reset bottom pill lift/glow back to rest while keeping lastBottomOutcomeRes visible
                                    bottomLiftFraction = 0f
                                    bottomHoldActive = false
                                    bottomHoldProgress = 0f
                                }
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height

                    // Subtle side edge trigger hints when in SIDE or MENU mode
                    if (activeTab == OnboardingSandboxTab.SIDE || activeTab == OnboardingSandboxTab.MENU) {
                        val barColor = primaryColor.copy(alpha = 0.22f)
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(0f, h * 0.22f),
                            size = Size(8.dp.toPx(), h * 0.56f),
                            cornerRadius = CornerRadius(4.dp.toPx())
                        )
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(w - 8.dp.toPx(), h * 0.22f),
                            size = Size(8.dp.toPx(), h * 0.56f),
                            cornerRadius = CornerRadius(4.dp.toPx())
                        )
                    }

                    // Bottom pill hint in BOTTOM mode
                    if (activeTab == OnboardingSandboxTab.BOTTOM) {
                        val liftPx = (bottomLiftFraction * 20.dp.toPx())
                        val pillW = w * 0.36f
                        val pillH = 7.dp.toPx()
                        val pillTop = h - 18.dp.toPx() - liftPx

                        // Center-out shimmer glow when holding for Circle to Search
                        if (bottomHoldProgress > 0.02f || bottomHoldActive) {
                            val glowProgress = if (bottomHoldActive) 1f else bottomHoldProgress
                            val glowRadius = pillW * (0.25f + 0.65f * glowProgress)
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        primaryColor.copy(alpha = 0.55f * glowProgress),
                                        secondaryColor.copy(alpha = 0.25f * glowProgress),
                                        Color.Transparent
                                    ),
                                    center = Offset(w * 0.5f, pillTop + pillH * 0.5f),
                                    radius = glowRadius
                                ),
                                radius = glowRadius,
                                center = Offset(w * 0.5f, pillTop + pillH * 0.5f)
                            )
                        }

                        drawRoundRect(
                            color = if (bottomHoldActive || bottomLiftFraction > 0.16f) primaryColor else primaryColor.copy(alpha = 0.55f),
                            topLeft = Offset((w - pillW) * 0.5f, pillTop),
                            size = Size(pillW, pillH),
                            cornerRadius = CornerRadius(pillH * 0.5f)
                        )
                    }

                    // SIDE mode: root-fixed + tip-bending Liquid Glass droplet
                    if (activeTab == OnboardingSandboxTab.SIDE && isDragging) {
                        val inward = if (fromRight) (w - fingerX).coerceAtLeast(0f) else fingerX.coerceAtLeast(0f)
                        val pull = inward.coerceIn(0f, w * 0.28f)
                        val rootX = if (fromRight) w else 0f
                        val dir = if (fromRight) -1f else 1f
                        val baseHalfH = 54.dp.toPx()
                        val tipOffsetY = ((fingerY - anchorY) * 0.42f).coerceIn(-36.dp.toPx(), 36.dp.toPx())
                        val tipX = rootX + dir * pull
                        val tipY = anchorY + tipOffsetY

                        val droplet = Path().apply {
                            moveTo(rootX, anchorY - baseHalfH)
                            cubicTo(
                                rootX, anchorY - baseHalfH * 0.42f,
                                tipX, tipY - baseHalfH * 0.36f,
                                tipX, tipY
                            )
                            cubicTo(
                                tipX, tipY + baseHalfH * 0.36f,
                                rootX, anchorY + baseHalfH * 0.42f,
                                rootX, anchorY + baseHalfH
                            )
                            close()
                        }
                        val tint = when (sideStage) {
                            3 -> secondaryColor
                            2 -> primaryColor
                            else -> primaryColor.copy(alpha = 0.78f)
                        }
                        drawPath(
                            path = droplet,
                            color = tint.copy(alpha = 0.28f)
                        )
                        drawPath(
                            path = droplet,
                            color = tint.copy(alpha = 0.85f),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    // MENU mode: fan-shaped Liquid Glass radial menu
                    if (activeTab == OnboardingSandboxTab.MENU && isDragging) {
                        val radius = minOf(w, h) * 0.42f
                        val originX = if (fromRight) w - 6.dp.toPx() else 6.dp.toPx()
                        val originY = anchorY
                        val dir = if (fromRight) -1f else 1f

                        // Translucent fan halo
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    primaryColor.copy(alpha = 0.16f),
                                    primaryColor.copy(alpha = 0.04f),
                                    Color.Transparent
                                ),
                                center = Offset(originX, originY),
                                radius = radius * 1.25f
                            ),
                            radius = radius * 1.25f,
                            center = Offset(originX, originY)
                        )

                        for (slot in 0 until 5) {
                            val angleDeg = -60f + slot * 30f
                            val rad = angleDeg * PI / 180.0
                            val cx = originX + dir * (cos(rad) * radius).toFloat()
                            val cy = originY + (sin(rad) * radius).toFloat()
                            val selected = slot == menuSlotIndex
                            val nodeRadius = if (selected) 21.dp.toPx() else 16.dp.toPx()
                            drawCircle(
                                color = if (selected) primaryColor else primaryColor.copy(alpha = 0.22f),
                                radius = nodeRadius,
                                center = Offset(cx, cy)
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = if (selected) 0.9f else 0.45f),
                                radius = nodeRadius,
                                center = Offset(cx, cy),
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }
                    }
                }

                // Live status pill centered in sandbox
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 16.dp, end = 16.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingStepAccessibility(
    isAccessibilityEnabled: Boolean
) {
    val context = LocalContext.current
    val extendedColors = LocalEdgeFlowColors.current
    val emblemBg by animateColorAsState(
        targetValue = if (isAccessibilityEnabled) {
            extendedColors.statusActiveContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        },
        animationSpec = tween(280),
        label = "a11y_emblem_bg"
    )
    val emblemTint by animateColorAsState(
        targetValue = if (isAccessibilityEnabled) {
            extendedColors.statusActive
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(280),
        label = "a11y_emblem_tint"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(emblemBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isAccessibilityEnabled) {
                    Icons.Rounded.CheckCircle
                } else {
                    HinataIcons.AccessibilityNew
                },
                contentDescription = null,
                tint = emblemTint,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onboarding_step2_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.onboarding_step2_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))

        Surface(
            modifier = Modifier.testTag("onboarding_a11y_status_badge"),
            shape = RoundedCornerShape(999.dp),
            color = if (isAccessibilityEnabled) {
                extendedColors.statusActiveContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isAccessibilityEnabled) extendedColors.statusActive
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (isAccessibilityEnabled) R.string.onboarding_step2_status_enabled
                        else R.string.onboarding_step2_status_waiting
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isAccessibilityEnabled) {
                        extendedColors.statusActive
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        if (!isAccessibilityEnabled) {
            Spacer(Modifier.height(20.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.onboarding_a11y_restricted_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.onboarding_a11y_restricted_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { openAppDetailsSettings(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.onboarding_a11y_open_app_info))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.onboarding_a11y_nav_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingStepQuickTileSafety(
    tileAddedOrRequested: Boolean,
    onRequestAddTile: () -> Unit,
    onOpenQuickPanelHint: () -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    val cardColor = MaterialTheme.colorScheme.surface
    val cardBorder = BorderStroke(1.dp, extendedColors.cardBorder)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("onboarding_qs_tile_step"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(
                    if (tileAddedOrRequested) extendedColors.statusActiveContainer
                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (tileAddedOrRequested) {
                    Icons.Rounded.CheckCircle
                } else {
                    HinataIcons.HealthAndSafety
                },
                contentDescription = null,
                tint = if (tileAddedOrRequested) {
                    extendedColors.statusActive
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = stringResource(R.string.onboarding_qs_tile_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.onboarding_qs_tile_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 6.dp)
        )

        Spacer(Modifier.height(18.dp))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Button(
                onClick = onRequestAddTile,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("onboarding_qs_tile_request_add_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = HinataIcons.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_qs_tile_add_now_button),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = cardColor,
            border = cardBorder
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_qs_tile_manual_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.onboarding_qs_tile_manual_steps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onOpenQuickPanelHint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("onboarding_qs_tile_manual_panel_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_qs_tile_open_panel_button),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingStepShizukuGuide(
    shizukuStatus: ShizukuStatus,
    onOpenExternalUrl: (String) -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    val cardColor = MaterialTheme.colorScheme.surface
    val cardBorder = BorderStroke(1.dp, extendedColors.cardBorder)
    val isConnected = shizukuStatus == ShizukuStatus.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(74.dp)
                .clip(CircleShape)
                .background(
                    if (isConnected) extendedColors.statusActiveContainer
                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Rounded.CheckCircle else HinataIcons.LayersClear,
                contentDescription = null,
                tint = if (isConnected) extendedColors.statusActive else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_shizuku_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_shizuku_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(14.dp))

        // Live Shizuku connection pill
        Surface(
            modifier = Modifier.testTag("onboarding_shizuku_status_badge"),
            shape = RoundedCornerShape(999.dp),
            color = if (isConnected) extendedColors.statusActiveContainer else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isConnected) extendedColors.statusActive
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (isConnected) R.string.onboarding_shizuku_connected
                        else R.string.onboarding_shizuku_not_connected
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isConnected) extendedColors.statusActive else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 4 External Download Links (ACTION_VIEW only — zero INTERNET permission)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ShizukuExternalLinkRow(
                label = stringResource(R.string.onboarding_shizuku_link_play),
                cardColor = cardColor,
                cardBorder = cardBorder,
                testTag = "onboarding_shizuku_link_play",
                onClick = { onOpenExternalUrl(URL_SHIZUKU_PLAY) }
            )
            ShizukuExternalLinkRow(
                label = stringResource(R.string.onboarding_shizuku_link_github),
                cardColor = cardColor,
                cardBorder = cardBorder,
                testTag = "onboarding_shizuku_link_github",
                onClick = { onOpenExternalUrl(URL_SHIZUKU_GITHUB) }
            )
            ShizukuExternalLinkRow(
                label = stringResource(R.string.onboarding_shizuku_link_fdroid),
                cardColor = cardColor,
                cardBorder = cardBorder,
                testTag = "onboarding_shizuku_link_fdroid",
                onClick = { onOpenExternalUrl(URL_SHIZUKU_FDROID) }
            )
            ShizukuExternalLinkRow(
                label = stringResource(R.string.onboarding_shizuku_link_apkmirror),
                cardColor = cardColor,
                cardBorder = cardBorder,
                testTag = "onboarding_shizuku_link_apkmirror",
                onClick = { onOpenExternalUrl(URL_SHIZUKU_APKMIRROR) }
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.onboarding_shizuku_return_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ShizukuExternalLinkRow(
    label: String,
    cardColor: Color,
    cardBorder: BorderStroke,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(14.dp),
        color = cardColor,
        border = cardBorder
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = HinataIcons.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun OnboardingStepReady(
    isIgnoringBatteryOptimizations: Boolean,
    onOpenBatterySettings: () -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    val cardColor = MaterialTheme.colorScheme.surface

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(extendedColors.statusActiveContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = HinataIcons.Gesture,
                contentDescription = null,
                tint = extendedColors.statusActive,
                modifier = Modifier.size(42.dp)
            )
        }

        Spacer(Modifier.height(22.dp))

        Text(
            text = stringResource(R.string.onboarding_step3_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.onboarding_step3_try_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))

        // One-tap optional HyperOS battery unrestricted card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onOpenBatterySettings)
                .testTag("onboarding_battery_card"),
            shape = RoundedCornerShape(20.dp),
            color = cardColor,
            border = BorderStroke(
                width = 1.dp,
                color = if (isIgnoringBatteryOptimizations) {
                    extendedColors.statusActive.copy(alpha = 0.45f)
                } else {
                    extendedColors.cardBorder
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (isIgnoringBatteryOptimizations) {
                                extendedColors.statusActiveContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isIgnoringBatteryOptimizations) {
                            Icons.Rounded.CheckCircle
                        } else {
                            HinataIcons.BatteryChargingFull
                        },
                        contentDescription = null,
                        tint = if (isIgnoringBatteryOptimizations) {
                            extendedColors.statusActive
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.onboarding_step3_battery_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = stringResource(
                            if (isIgnoringBatteryOptimizations) R.string.onboarding_step3_battery_done
                            else R.string.onboarding_step3_battery_desc
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isIgnoringBatteryOptimizations) {
                            extendedColors.statusActive
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = HinataIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun openExternalUrl(context: Context, url: String) {
    SettingsActivity.keepAliveForExternalSettingsOnce = true
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

@Suppress("PrivateApi")
private fun expandQuickSettingsPanelBestEffort(context: Context) {
    runCatching {
        val statusBarService = context.getSystemService("statusbar") ?: return
        val statusBarManager = Class.forName("android.app.StatusBarManager")
        val expandMethod = runCatching { statusBarManager.getMethod("expandSettingsPanel") }
            .getOrElse { statusBarManager.getMethod("expandNotificationsPanel") }
        expandMethod.invoke(statusBarService)
    }
}

@Composable
private fun OnboardingStepDots(
    totalSteps: Int,
    currentStep: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (index in 0 until totalSteps) {
            val active = index == currentStep
            val width by animateDpAsState(
                targetValue = if (active) 24.dp else 8.dp,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "dot_width_$index"
            )
            val color by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                animationSpec = tween(220),
                label = "dot_color_$index"
            )
            Box(
                modifier = Modifier
                    .width(width)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/**
 * Vector-exact flat rendering of Hinata's pop-red chick app icon (zero gradients) for crisp display at any DPI.
 */
@Composable
private fun HinataBrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.clip(RoundedCornerShape(22.dp))) {
        val w = size.width
        val h = size.height
        // Map adaptive safe zone (14..94 of 108x108) cleanly onto the rounded square icon
        val scale = w / 80f
        fun mx(v: Float): Float = (v - 14f) * scale
        fun my(v: Float): Float = (v - 14f) * scale

        // 1. Flat pop-red background (#EE0909)
        drawRoundRect(
            color = Color(0xFFEE0909),
            size = size,
            cornerRadius = CornerRadius(w * 0.24f, h * 0.24f)
        )

        val chickYellow = Color(0xFFFFF000)
        val handOutline = Color(0xFFF98800)
        val eyeBlack = Color(0xFF141414)
        val beakOrange = Color(0xFFFF8800)
        val cheekPink = Color(0xFFFF6B6B)

        // 2. Plump yellow chick peeking from right of the edge bar + 3 top tufts + 2 tail feathers
        val chickBody = Path().apply {
            moveTo(mx(35.5f), my(42f))
            cubicTo(mx(41f), my(36.8f), mx(51f), my(36.2f), mx(58.5f), my(39f))
            cubicTo(mx(66.5f), my(42.2f), mx(68.8f), my(51.5f), mx(66.5f), my(60.5f))
            cubicTo(mx(64.2f), my(69.5f), mx(55.5f), my(73.2f), mx(45.5f), my(72.2f))
            cubicTo(mx(40.2f), my(71.6f), mx(37f), my(69.8f), mx(35.5f), my(67.2f))
            close()
        }
        drawPath(path = chickBody, color = chickYellow)

        // 3 fluffy top tuft feathers
        val tuft1 = Path().apply {
            moveTo(mx(48.5f), my(39f))
            cubicTo(mx(47.2f), my(32.5f), mx(52.2f), my(29.2f), mx(54.4f), my(33.2f))
            cubicTo(mx(55.4f), my(35.2f), mx(54.2f), my(38.2f), mx(52f), my(39.2f))
            close()
        }
        val tuft2 = Path().apply {
            moveTo(mx(52.8f), my(39.2f))
            cubicTo(mx(54.4f), my(31.2f), mx(60.8f), my(29.8f), mx(61.2f), my(34.6f))
            cubicTo(mx(61.5f), my(37.4f), mx(58.8f), my(39.8f), mx(56f), my(40.4f))
            close()
        }
        val tuft3 = Path().apply {
            moveTo(mx(57.6f), my(40.8f))
            cubicTo(mx(60.8f), my(35.5f), mx(65.8f), my(36.2f), mx(65.2f), my(39.8f))
            cubicTo(mx(64.8f), my(42.2f), mx(61.8f), my(43.2f), mx(59.2f), my(42.6f))
            close()
        }
        drawPath(path = tuft1, color = chickYellow)
        drawPath(path = tuft2, color = chickYellow)
        drawPath(path = tuft3, color = chickYellow)

        // 2 small lower-right wing/tail feathers
        val tail1 = Path().apply {
            moveTo(mx(64f), my(58.5f))
            cubicTo(mx(69.2f), my(57.5f), mx(72.2f), my(60.6f), mx(69.6f), my(63.6f))
            cubicTo(mx(67.8f), my(65.4f), mx(64.5f), my(64.4f), mx(63f), my(62f))
            close()
        }
        val tail2 = Path().apply {
            moveTo(mx(62f), my(63.4f))
            cubicTo(mx(66.6f), my(64f), mx(68.6f), my(67.2f), mx(65.8f), my(69.2f))
            cubicTo(mx(64f), my(70.4f), mx(61f), my(69f), mx(60f), my(66.5f))
            close()
        }
        drawPath(path = tail1, color = chickYellow)
        drawPath(path = tail2, color = chickYellow)

        // 3. Left white vertical edge bar (x=27..36.5, y=26..82, r=4.75)
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(mx(27f), my(26f)),
            size = Size(9.5f * scale, 56f * scale),
            cornerRadius = CornerRadius(4.75f * scale, 4.75f * scale)
        )

        // 4. Two round yellow hands with orange outline gripping the right edge of the white bar
        val handRadius = 3.4f * scale
        val handStroke = Stroke(width = 1.7f * scale)
        val upperHand = Offset(mx(36.2f), my(46.8f))
        val lowerHand = Offset(mx(36.2f), my(62.2f))
        drawCircle(color = chickYellow, radius = handRadius, center = upperHand)
        drawCircle(color = handOutline, radius = handRadius, center = upperHand, style = handStroke)
        drawCircle(color = chickYellow, radius = handRadius, center = lowerHand)
        drawCircle(color = handOutline, radius = handRadius, center = lowerHand, style = handStroke)

        // 5. Cute Japanese face (2 pink cheeks, 2 tilted dot eyes, rounded-diamond orange beak)
        drawCircle(color = cheekPink, radius = 2.7f * scale, center = Offset(mx(43.8f), my(55.2f)))
        drawCircle(color = cheekPink, radius = 2.7f * scale, center = Offset(mx(61.0f), my(57.2f)))
        drawCircle(color = eyeBlack, radius = 2.2f * scale, center = Offset(mx(47.2f), my(50.8f)))
        drawCircle(color = eyeBlack, radius = 2.2f * scale, center = Offset(mx(58.2f), my(52.4f)))

        val beak = Path().apply {
            moveTo(mx(52.6f), my(52.6f))
            quadraticTo(mx(55.6f), my(54.8f), mx(55.4f), my(55.5f))
            quadraticTo(mx(55.2f), my(56.2f), mx(52.1f), my(58.0f))
            quadraticTo(mx(49.1f), my(56.0f), mx(49.3f), my(55.2f))
            quadraticTo(mx(49.5f), my(54.4f), mx(52.6f), my(52.6f))
            close()
        }
        drawPath(path = beak, color = beakOrange)

        // 6. Right white gesture crescent arc (M71,39 C80.5,48.5 79.5,64.5 64.5,75.5)
        val arc = Path().apply {
            moveTo(mx(71f), my(39f))
            cubicTo(mx(80.5f), my(48.5f), mx(79.5f), my(64.5f), mx(64.5f), my(75.5f))
        }
        drawPath(
            path = arc,
            color = Color.White,
            style = Stroke(
                width = 5.2f * scale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
