package com.edgeflow.settings

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import com.example.ui.icons.HinataIcons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.edgeflow.data.DeviceCompatibilityHelper
import com.edgeflow.data.DeviceFamily
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.RuntimeStatus
import com.edgeflow.service.HinataRecoveryTileService
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import com.example.R
import com.example.ui.theme.LocalEdgeFlowColors
import com.example.ui.theme.NumericLabelStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HyperOSGuideScreen(
    config: EdgeConfig,
    onConfigChange: (EdgeConfig) -> Unit,
    snackbarHostState: SnackbarHostState,
    isAccessibilityEnabled: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    runtimeStatus: RuntimeStatus,
    onRefreshStatus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val extendedColors = LocalEdgeFlowColors.current
    val settingsPid = remember { Process.myPid() }
    val capabilities = remember { DeviceCompatibilityHelper.resolveCapabilities() }
    var statusExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("hyperos_guide_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(
                    text = stringResource(R.string.guide_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.guide_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Top Card #1: 4-Layer Safety, Auto-Recovery & Emergency Escape
        item {
            AutoRecoverySafetyCard(
                config = config,
                onConfigChange = onConfigChange,
                snackbarHostState = snackbarHostState,
                onRefreshStatus = onRefreshStatus
            )
        }

        // Top Card #2: Multi-OEM Shizuku Navigation & Back Gesture Control
        item {
            ShizukuNavBarCard(
                config = config,
                onConfigChange = onConfigChange,
                snackbarHostState = snackbarHostState
            )
        }

        // Hybrid Circle to Search (Native + CN ROM Shizuku Repair + Built-In Lens & Offline OCR)
        item {
            CircleToSearchSettingsCard(
                config = config,
                onConfigChange = onConfigChange,
                snackbarHostState = snackbarHostState
            )
        }

        // Reboot-Persistent Trick: Second Space 4-Step Guide (HyperOS / MIUI)
        if (capabilities.supportsHyperOsSecondSpaceTrick) {
            item {
                GuideInfoCard(
                    icon = HinataIcons.Layers,
                    title = stringResource(R.string.guide_second_space_title),
                    badgeText = stringResource(R.string.guide_second_space_badge),
                    accentTint = MaterialTheme.colorScheme.primary,
                    testTag = "guide_second_space_card"
                ) {
                    Text(
                        text = stringResource(R.string.guide_second_space_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    NumberedGuideStep(1, stringResource(R.string.guide_second_space_step1))
                    NumberedGuideStep(2, stringResource(R.string.guide_second_space_step2))
                    NumberedGuideStep(3, stringResource(R.string.guide_second_space_step3))
                    NumberedGuideStep(4, stringResource(R.string.guide_second_space_step4))
                }
            }
        }

        // Emergency Recovery #1: Restore 3-Button Navigation from System Settings
        item {
            GuideInfoCard(
                icon = HinataIcons.HealthAndSafety,
                title = stringResource(R.string.guide_emergency_settings_title),
                badgeText = stringResource(R.string.guide_emergency_badge),
                accentTint = extendedColors.statusInactive,
                testTag = "guide_emergency_settings_card"
            ) {
                Text(
                    text = stringResource(R.string.guide_emergency_settings_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.guide_emergency_settings_path),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }

        // Emergency Recovery #2: Android Safe Mode Forced Recovery
        item {
            GuideInfoCard(
                icon = HinataIcons.RestartAlt,
                title = stringResource(R.string.guide_emergency_safemode_title),
                badgeText = stringResource(R.string.guide_emergency_badge),
                accentTint = MaterialTheme.colorScheme.error,
                testTag = "guide_emergency_safemode_card"
            ) {
                Text(
                    text = stringResource(R.string.guide_emergency_safemode_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                NumberedGuideStep(1, stringResource(R.string.guide_emergency_safemode_step1))
                NumberedGuideStep(2, stringResource(R.string.guide_emergency_safemode_step2))
                NumberedGuideStep(3, stringResource(R.string.guide_emergency_safemode_step3))
            }
        }

        // Step 1: Accessibility Service
        item {
            SurvivalStepCard(
                icon = HinataIcons.AccessibilityNew,
                title = stringResource(R.string.guide_step1_title),
                description = stringResource(R.string.guide_step1_desc),
                actionLabel = stringResource(R.string.guide_step1_action),
                completed = isAccessibilityEnabled,
                testTag = "guide_step1_button",
                onClick = { openAccessibilitySettings(context) }
            )
        }

        // Step 2: Battery Saver Unrestricted
        item {
            SurvivalStepCard(
                icon = HinataIcons.BatteryChargingFull,
                title = stringResource(R.string.guide_step2_title),
                description = stringResource(R.string.guide_step2_desc),
                actionLabel = stringResource(R.string.guide_step2_action),
                completed = isIgnoringBatteryOptimizations,
                testTag = "guide_step2_button",
                onClick = { openBatteryUnrestrictedSettings(context) }
            )
        }

        // Step 3: HyperOS / MIUI Autostart (only shown on genuine HyperOS / MIUI devices)
        if (capabilities.supportsHyperOsAutoStartSettings) {
            item {
                SurvivalStepCard(
                    icon = HinataIcons.RocketLaunch,
                    title = stringResource(R.string.guide_step3_title),
                    description = stringResource(R.string.guide_step3_desc),
                    actionLabel = stringResource(R.string.guide_step3_action),
                    completed = null,
                    testTag = "guide_step3_button",
                    onClick = { openHyperOSAutoStartSettings(context) }
                )
            }
        }

        // Subtle collapsible System Status card
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, extendedColors.cardBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { statusExpanded = !statusExpanded }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = HinataIcons.Memory,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.guide_architecture_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            imageVector = if (statusExpanded) HinataIcons.ExpandLess else HinataIcons.ExpandMore,
                            contentDescription = stringResource(
                                if (statusExpanded) R.string.cd_collapse else R.string.cd_expand
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(visible = statusExpanded) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            HorizontalDivider(color = extendedColors.cardBorder)
                            Spacer(Modifier.height(10.dp))

                            TelemetryRow(
                                label = stringResource(R.string.diag_settings_process),
                                value = stringResource(R.string.diag_settings_process_value, settingsPid)
                            )
                            TelemetryRow(
                                label = stringResource(R.string.diag_resident_process),
                                value = if (runtimeStatus.residentProcessPid > 0) {
                                    stringResource(R.string.diag_resident_running, runtimeStatus.residentProcessPid)
                                } else {
                                    stringResource(R.string.diag_resident_standby)
                                }
                            )
                            TelemetryRow(
                                label = stringResource(R.string.diag_ime_state),
                                value = stringResource(
                                    if (runtimeStatus.imeVisible) R.string.diag_ime_visible
                                    else R.string.diag_ime_hidden
                                )
                            )
                            TelemetryRow(
                                label = stringResource(R.string.diag_orientation),
                                value = stringResource(
                                    if (runtimeStatus.landscapeActive) R.string.diag_orientation_landscape
                                    else R.string.diag_orientation_portrait
                                )
                            )

                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = onRefreshStatus,
                                    modifier = Modifier.testTag("refresh_telemetry_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(text = stringResource(R.string.cd_refresh_status))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoRecoverySafetyCard(
    config: EdgeConfig,
    onConfigChange: (EdgeConfig) -> Unit,
    snackbarHostState: SnackbarHostState,
    onRefreshStatus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val extendedColors = LocalEdgeFlowColors.current
    val scope = rememberCoroutineScope()
    var isRebinding by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        SettingsActivity.isWaitingForChildActivityResult = false
        // Foreground keep-alive service runs even if notification posting is denied;
        // granting permission surfaces the 1-tap Reload / Restore Nav actions in the shade.
    }

    fun showSnackbar(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("auto_recovery_safety_card"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extendedColors.cardBorder)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = HinataIcons.HealthAndSafety,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.recovery_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = stringResource(R.string.recovery_card_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            RecoveryToggleRow(
                title = stringResource(R.string.recovery_toggle_keep_alive_title),
                description = stringResource(R.string.recovery_toggle_keep_alive_desc),
                checked = config.keepAliveForegroundNotification,
                testTag = "recovery_keep_alive_switch",
                onCheckedChange = { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            SettingsActivity.isWaitingForChildActivityResult = true
                            runCatching {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }.onFailure {
                                SettingsActivity.isWaitingForChildActivityResult = false
                            }
                        }
                    }
                    onConfigChange(config.copy(keepAliveForegroundNotification = enabled))
                }
            )

            Spacer(Modifier.height(4.dp))

            RecoveryToggleRow(
                title = stringResource(R.string.recovery_toggle_auto_revive_title),
                description = stringResource(R.string.recovery_toggle_auto_revive_desc),
                checked = config.autoReviveWithShizuku,
                testTag = "recovery_auto_revive_switch",
                onCheckedChange = { enabled ->
                    onConfigChange(config.copy(autoReviveWithShizuku = enabled))
                }
            )

            Spacer(Modifier.height(12.dp))

            FilledTonalButton(
                onClick = {
                    if (isRebinding) return@FilledTonalButton
                    ShizukuShellExecutor.refreshNonProviderBinder(context)
                    if (ShizukuShellExecutor.isReady()) {
                        isRebinding = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                ShizukuShellExecutor.reviveAccessibilityServiceWithShizuku(
                                    context = context,
                                    forceToggleRebind = true
                                )
                            }
                            delay(450L)
                            onRefreshStatus()
                            isRebinding = false
                            if (result.success) {
                                showSnackbar(context.getString(R.string.recovery_toast_revive_success))
                            } else {
                                showSnackbar(
                                    context.getString(R.string.recovery_toast_revive_failed_open_settings)
                                )
                                openAccessibilitySettings(context)
                            }
                        }
                    } else {
                        openAccessibilitySettings(context)
                        showSnackbar(
                            context.getString(R.string.recovery_toast_open_accessibility_manual)
                        )
                    }
                },
                enabled = !isRebinding,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("recovery_rebind_now_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isRebinding) {
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
                    text = stringResource(R.string.recovery_button_rebind_now),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.recovery_qs_tile_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RecoveryToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
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
private fun GuideInfoCard(
    icon: ImageVector,
    title: String,
    badgeText: String,
    accentTint: androidx.compose.ui.graphics.Color,
    testTag: String,
    content: @Composable () -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extendedColors.cardBorder)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentTint,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = accentTint.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = accentTint,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun NumberedGuideStep(step: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
            modifier = Modifier.size(22.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = step.toString(),
                    style = NumericLabelStyle,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SurvivalStepCard(
    icon: ImageVector,
    title: String,
    description: String,
    actionLabel: String,
    completed: Boolean?,
    testTag: String,
    onClick: () -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extendedColors.cardBorder)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (completed != null) {
                    Spacer(Modifier.width(8.dp))
                    val badgeColor = if (completed) extendedColors.statusActive else extendedColors.statusInactive
                    val badgeContainer = if (completed) {
                        extendedColors.statusActiveContainer
                    } else {
                        extendedColors.statusInactiveContainer
                    }
                    Surface(
                        color = badgeContainer,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (completed) Icons.Rounded.CheckCircle else HinataIcons.WarningAmber,
                                contentDescription = null,
                                tint = badgeColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(
                                    if (completed) R.string.guide_step_done else R.string.guide_step_pending
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))

            if (completed == true) {
                OutlinedButton(
                    onClick = onClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(testTag),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = actionLabel,
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
            } else {
                Button(
                    onClick = onClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(testTag),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = actionLabel,
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
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
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

private fun prepareTaskForExternalSettings(context: Context, keepTaskAlive: Boolean) {
    if (keepTaskAlive) {
        SettingsActivity.keepAliveForExternalSettingsOnce = true
    } else {
        (context as? SettingsActivity)?.finishAndPurgeFromRecents()
    }
}

fun openAccessibilitySettings(context: Context, keepTaskAlive: Boolean = false) {
    prepareTaskForExternalSettings(context, keepTaskAlive)
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

fun openBatteryUnrestrictedSettings(context: Context, keepTaskAlive: Boolean = false) {
    prepareTaskForExternalSettings(context, keepTaskAlive)
    val miuiBatteryIntent = Intent().apply {
        component = ComponentName(
            "com.miui.powerkeeper",
            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
        )
        putExtra("package_name", context.packageName)
        putExtra("package_label", "Hinata")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(miuiBatteryIntent) }.isSuccess) return

    val ignoreListIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(ignoreListIntent) }.isSuccess) return

    startAppDetailsSettingsOnly(context)
}

fun openHyperOSAutoStartSettings(context: Context, keepTaskAlive: Boolean = false) {
    prepareTaskForExternalSettings(context, keepTaskAlive)
    val autoStartIntent = Intent().apply {
        component = ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(autoStartIntent) }.isSuccess) return

    startAppDetailsSettingsOnly(context)
}

fun openAppDetailsSettings(context: Context, keepTaskAlive: Boolean = false) {
    prepareTaskForExternalSettings(context, keepTaskAlive)
    startAppDetailsSettingsOnly(context)
}

private fun startAppDetailsSettingsOnly(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

fun openSystemNavigationSettingsDirect(context: Context, keepTaskAlive: Boolean = false) {
    prepareTaskForExternalSettings(context, keepTaskAlive)
    val gestureNavIntent = Intent("android.settings.GESTURE_NAVIGATION_SETTINGS").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(gestureNavIntent) }.isSuccess) return

    val fallbackSettings = Intent(Settings.ACTION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(fallbackSettings) }
}

/**
 * Invokes Android 13+ (API 33+) official [StatusBarManager.requestAddTileService] dialog for
 * Hinata's Quick Settings recovery tile. Returns `true` if the OS dialog request was dispatched.
 */
fun requestAddRecoveryQsTile(
    context: Context,
    onResult: (Int) -> Unit = {}
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val statusBarManager = context.getSystemService(StatusBarManager::class.java) ?: return false
    SettingsActivity.keepAliveForExternalSettingsOnce = true
    return runCatching {
        statusBarManager.requestAddTileService(
            ComponentName(context, HinataRecoveryTileService::class.java),
            context.getString(R.string.qs_tile_recovery_label),
            Icon.createWithResource(context, R.drawable.ic_launcher_monochrome),
            context.mainExecutor
        ) { resultCode ->
            onResult(resultCode)
        }
        true
    }.getOrDefault(false)
}
