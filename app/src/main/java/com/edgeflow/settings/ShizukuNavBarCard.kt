package com.edgeflow.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import com.example.ui.icons.HinataIcons
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.edgeflow.data.DeviceCompatibilityHelper
import com.edgeflow.data.DeviceFamily
import com.edgeflow.data.EdgeConfig
import com.edgeflow.service.shizuku.NavBarCommandResult
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import com.edgeflow.service.shizuku.ShizukuStatus
import com.example.R
import com.example.ui.theme.LocalEdgeFlowColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Multi-OEM Shizuku navigation & back-gesture suppression card (Spec 63).
 *
 * Automatically adapts to:
 * - Xiaomi / HyperOS / MIUI
 * - realme / OPPO / OnePlus (ColorOS / OxygenOS / realme UI)
 * - vivo / iQOO (OriginOS / Funtouch OS)
 * - Samsung (One UI)
 * - Pixel / Xperia / Nothing / Motorola / Standard Android
 *
 * Enforces an Accessibility anti-lockout safety guard before suppressing OS navigation,
 * and always exposes a prominent universal "Restore All Defaults" emergency recovery button.
 */
@Composable
fun ShizukuNavBarCard(
    config: EdgeConfig,
    onConfigChange: (EdgeConfig) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val extendedColors = LocalEdgeFlowColors.current
    val scope = rememberCoroutineScope()
    val capabilities = remember { DeviceCompatibilityHelper.resolveCapabilities() }
    val deviceFamily = capabilities.deviceFamily

    var shizukuStatus by remember { mutableStateOf(ShizukuShellExecutor.checkStatus(context)) }
    var isExecuting by remember { mutableStateOf(false) }
    var showGuideDialog by rememberSaveable { mutableStateOf(false) }

    fun refreshShizukuStatus() {
        ShizukuShellExecutor.refreshNonProviderBinder(context)
        shizukuStatus = ShizukuShellExecutor.checkStatus(context)
    }

    fun showMessage(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshShizukuStatus()
            }
        }
        val binderReceivedListener = Shizuku.OnBinderReceivedListener {
            shizukuStatus = ShizukuShellExecutor.checkStatus(context)
        }
        val binderDeadListener = Shizuku.OnBinderDeadListener {
            shizukuStatus = ShizukuShellExecutor.checkStatus(context)
        }
        val permissionResultListener =
            Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == ShizukuShellExecutor.REQUEST_CODE_SHIZUKU_PERMISSION) {
                    shizukuStatus = ShizukuShellExecutor.checkStatus(context)
                    val msg = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        context.getString(R.string.shizuku_snackbar_permission_granted)
                    } else {
                        context.getString(R.string.shizuku_snackbar_permission_denied)
                    }
                    showMessage(msg)
                }
            }

        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener) }
        runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
        runCatching { Shizuku.addRequestPermissionResultListener(permissionResultListener) }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
            runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
            runCatching { Shizuku.removeRequestPermissionResultListener(permissionResultListener) }
        }
    }

    fun handleStatusAction() {
        when (shizukuStatus) {
            ShizukuStatus.NOT_INSTALLED -> showGuideDialog = true
            ShizukuStatus.NOT_RUNNING -> {
                if (!ShizukuShellExecutor.openShizukuApp(context)) {
                    showMessage(context.getString(R.string.shizuku_snackbar_open_failed))
                }
            }
            ShizukuStatus.PERMISSION_REQUIRED -> {
                if (!ShizukuShellExecutor.requestPermission()) {
                    refreshShizukuStatus()
                    showMessage(context.getString(R.string.shizuku_snackbar_not_ready))
                }
            }
            ShizukuStatus.CONNECTED -> refreshShizukuStatus()
        }
    }

    fun runShizukuAction(
        successMsgRes: Int,
        onSuccess: (() -> Unit)? = null,
        action: () -> NavBarCommandResult
    ) {
        if (isExecuting) return
        refreshShizukuStatus()
        when (shizukuStatus) {
            ShizukuStatus.NOT_INSTALLED -> {
                showGuideDialog = true
                return
            }
            ShizukuStatus.NOT_RUNNING -> {
                showMessage(context.getString(R.string.shizuku_snackbar_not_ready))
                return
            }
            ShizukuStatus.PERMISSION_REQUIRED -> {
                if (!ShizukuShellExecutor.requestPermission()) {
                    showMessage(context.getString(R.string.shizuku_snackbar_not_ready))
                }
                return
            }
            ShizukuStatus.CONNECTED -> Unit
        }

        isExecuting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            shizukuStatus = result.status
            isExecuting = false
            if (result.success) {
                onSuccess?.invoke()
            }
            val message = when {
                result.blockedByAccessibilityGuard ->
                    context.getString(R.string.shizuku_snackbar_accessibility_required)
                result.success -> context.getString(successMsgRes)
                result.status != ShizukuStatus.CONNECTED ->
                    context.getString(R.string.shizuku_snackbar_not_ready)
                result.detail.isNotBlank() ->
                    context.getString(R.string.shizuku_snackbar_failed_detail, result.detail)
                else -> context.getString(R.string.shizuku_snackbar_failed)
            }
            showMessage(message)
        }
    }

    val isConnected = shizukuStatus == ShizukuStatus.CONNECTED
    val badgeColor = when (shizukuStatus) {
        ShizukuStatus.CONNECTED -> extendedColors.statusActive
        ShizukuStatus.PERMISSION_REQUIRED, ShizukuStatus.NOT_RUNNING -> extendedColors.statusInactive
        ShizukuStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val badgeContainer = when (shizukuStatus) {
        ShizukuStatus.CONNECTED -> extendedColors.statusActiveContainer
        ShizukuStatus.PERMISSION_REQUIRED, ShizukuStatus.NOT_RUNNING -> extendedColors.statusInactiveContainer
        ShizukuStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.surfaceVariant
    }
    val badgeLabelRes = when (shizukuStatus) {
        ShizukuStatus.CONNECTED -> R.string.shizuku_status_connected
        ShizukuStatus.PERMISSION_REQUIRED -> R.string.shizuku_status_permission_required
        ShizukuStatus.NOT_RUNNING -> R.string.shizuku_status_stopped
        ShizukuStatus.NOT_INSTALLED -> R.string.shizuku_status_not_installed
    }

    val cardTitleRes = when (deviceFamily) {
        DeviceFamily.XIAOMI_HYPEROS -> R.string.shizuku_card_title
        DeviceFamily.REALME_OPPO_ONEPLUS -> R.string.shizuku_card_title_coloros
        DeviceFamily.VIVO_ORIGIN_OS -> R.string.shizuku_card_title_originos
        DeviceFamily.SAMSUNG_ONEUI -> R.string.shizuku_card_title_oneui
        DeviceFamily.STANDARD_ANDROID -> R.string.shizuku_card_title_aosp
    }
    val cardSubtitleRes = when (deviceFamily) {
        DeviceFamily.XIAOMI_HYPEROS -> R.string.shizuku_card_subtitle
        DeviceFamily.REALME_OPPO_ONEPLUS -> R.string.shizuku_card_subtitle_coloros
        DeviceFamily.VIVO_ORIGIN_OS -> R.string.shizuku_card_subtitle_originos
        DeviceFamily.SAMSUNG_ONEUI -> R.string.shizuku_card_subtitle_oneui
        DeviceFamily.STANDARD_ANDROID -> R.string.shizuku_card_subtitle_aosp
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("shizuku_navbar_card"),
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
                        imageVector = HinataIcons.Fullscreen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(cardTitleRes),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.width(8.dp))

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(onClick = ::handleStatusAction)
                        .testTag("shizuku_status_badge"),
                    color = badgeContainer,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(badgeLabelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(cardSubtitleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Anti-lockout safety banner
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = HinataIcons.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.shizuku_safety_guard_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Contextual Shizuku readiness action when not yet connected
            if (!isConnected) {
                Spacer(Modifier.height(14.dp))
                when (shizukuStatus) {
                    ShizukuStatus.NOT_INSTALLED -> {
                        OutlinedButton(
                            onClick = { showGuideDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("shizuku_setup_guide_button"),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.shizuku_action_setup_guide),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    ShizukuStatus.NOT_RUNNING -> {
                        OutlinedButton(
                            onClick = ::handleStatusAction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("shizuku_open_app_button"),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = stringResource(R.string.shizuku_action_open_app),
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

                    ShizukuStatus.PERMISSION_REQUIRED -> {
                        Button(
                            onClick = ::handleStatusAction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("shizuku_grant_permission_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = HinataIcons.Key,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.shizuku_action_grant_permission),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    ShizukuStatus.CONNECTED -> Unit
                }
            }

            Spacer(Modifier.height(14.dp))

            // Primary OEM-tailored suppression action(s)
            if (deviceFamily == DeviceFamily.STANDARD_ANDROID) {
                ShizukuPrimaryActionButton(
                    isConnected = isConnected,
                    isExecuting = isExecuting,
                    icon = HinataIcons.DoNotDisturbOn,
                    label = stringResource(R.string.shizuku_button_suppress_back),
                    testTag = "shizuku_suppress_back_button",
                    onClick = {
                        runShizukuAction(
                            successMsgRes = R.string.shizuku_snackbar_back_suppressed,
                            onSuccess = {
                                if (!config.shizukuSystemNavModified) {
                                    onConfigChange(config.copy(shizukuSystemNavModified = true))
                                }
                            }
                        ) {
                            ShizukuShellExecutor.suppressSystemBackGestures(
                                context = context,
                                disableLeft = config.leftEnabled,
                                disableRight = config.rightEnabled,
                                requireAccessibilityGuard = true
                            )
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        runShizukuAction(
                            successMsgRes = R.string.shizuku_snackbar_hide_success,
                            onSuccess = {
                                if (!config.shizukuSystemNavModified) {
                                    onConfigChange(config.copy(shizukuSystemNavModified = true))
                                }
                            }
                        ) {
                            ShizukuShellExecutor.hideSystemNavBarForDevice(
                                context = context,
                                deviceFamily = deviceFamily,
                                hideGestureLine = config.hideGestureLine,
                                requireAccessibilityGuard = true
                            )
                        }
                    },
                    enabled = !isExecuting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("shizuku_hide_navbar_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = HinataIcons.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.shizuku_button_hide_navbar_generic),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                ShizukuPrimaryActionButton(
                    isConnected = isConnected,
                    isExecuting = isExecuting,
                    icon = HinataIcons.VisibilityOff,
                    label = stringResource(
                        if (deviceFamily == DeviceFamily.XIAOMI_HYPEROS) {
                            R.string.shizuku_button_hide_navbar
                        } else {
                            R.string.shizuku_button_hide_navbar_generic
                        }
                    ),
                    testTag = "shizuku_hide_navbar_button",
                    onClick = {
                        runShizukuAction(
                            successMsgRes = R.string.shizuku_snackbar_hide_success,
                            onSuccess = {
                                if (!config.shizukuSystemNavModified) {
                                    onConfigChange(config.copy(shizukuSystemNavModified = true))
                                }
                            }
                        ) {
                            ShizukuShellExecutor.hideSystemNavBarForDevice(
                                context = context,
                                deviceFamily = deviceFamily,
                                hideGestureLine = config.hideGestureLine,
                                requireAccessibilityGuard = true
                            )
                        }
                    }
                )
                if (deviceFamily != DeviceFamily.XIAOMI_HYPEROS) {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            runShizukuAction(
                                successMsgRes = R.string.shizuku_snackbar_back_suppressed,
                                onSuccess = {
                                    if (!config.shizukuSystemNavModified) {
                                        onConfigChange(config.copy(shizukuSystemNavModified = true))
                                    }
                                }
                            ) {
                                ShizukuShellExecutor.suppressSystemBackGestures(
                                    context = context,
                                    disableLeft = config.leftEnabled,
                                    disableRight = config.rightEnabled,
                                    requireAccessibilityGuard = true
                                )
                            }
                        },
                        enabled = !isExecuting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("shizuku_suppress_back_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = HinataIcons.DoNotDisturbOn,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.shizuku_button_suppress_back),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Prominent universal safe recovery button (reverts NavHide + Back Inset + HyperOS + Samsung)
            OutlinedButton(
                onClick = {
                    runShizukuAction(
                        successMsgRes = R.string.shizuku_snackbar_restore_success,
                        onSuccess = {
                            if (config.shizukuSystemNavModified || config.autoHideNavBarWithShizuku) {
                                onConfigChange(
                                    config.copy(
                                        shizukuSystemNavModified = false,
                                        autoHideNavBarWithShizuku = false
                                    )
                                )
                            }
                        }
                    ) {
                        ShizukuShellExecutor.restoreAllSystemNavigation(context)
                    }
                },
                enabled = !isExecuting,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("shizuku_restore_navbar_button"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
            ) {
                Icon(
                    imageVector = HinataIcons.Restore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.shizuku_button_restore_all_defaults),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = extendedColors.cardBorder)
            Spacer(Modifier.height(8.dp))

            // Option 1: Event-driven auto-hide on reset / unlock
            ShizukuToggleRow(
                title = stringResource(R.string.shizuku_toggle_auto_hide_title),
                description = stringResource(R.string.shizuku_toggle_auto_hide_desc),
                checked = config.autoHideNavBarWithShizuku,
                testTag = "shizuku_auto_hide_switch",
                onCheckedChange = { enabled ->
                    onConfigChange(config.copy(autoHideNavBarWithShizuku = enabled))
                }
            )

            if (capabilities.supportsHyperOsGestureLineToggle) {
                Spacer(Modifier.height(4.dp))
                ShizukuToggleRow(
                    title = stringResource(R.string.shizuku_toggle_hide_gesture_line_title),
                    description = stringResource(R.string.shizuku_toggle_hide_gesture_line_desc),
                    checked = config.hideGestureLine,
                    testTag = "shizuku_hide_gesture_line_switch",
                    onCheckedChange = { enabled ->
                        onConfigChange(config.copy(hideGestureLine = enabled))
                    }
                )
            }

            if (capabilities.supportsHyperOsNativeFloatingWindow) {
                Spacer(Modifier.height(4.dp))
                ShizukuToggleRow(
                    title = stringResource(R.string.shizuku_toggle_no_shizuku_cc_float_title),
                    description = stringResource(R.string.shizuku_toggle_no_shizuku_cc_float_desc),
                    checked = config.noShizukuControlCenterFloat,
                    testTag = "shizuku_no_shizuku_cc_float_switch",
                    onCheckedChange = { enabled ->
                        onConfigChange(config.copy(noShizukuControlCenterFloat = enabled))
                    }
                )

                Spacer(Modifier.height(10.dp))
                CcFloatTileCalibrationSection(
                    config = config,
                    snackbarHostState = snackbarHostState,
                    onConfigChange = onConfigChange
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.shizuku_reboot_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = HinataIcons.PictureInPictureAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (capabilities.supportsHyperOsNativeFloatingWindow) {
                            R.string.shizuku_floating_window_note
                        } else {
                            R.string.shizuku_floating_window_non_hyperos_note
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showGuideDialog) {
        AlertDialog(
            onDismissRequest = { showGuideDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = stringResource(R.string.shizuku_guide_dialog_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.shizuku_guide_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showGuideDialog = false },
                    modifier = Modifier.testTag("shizuku_guide_close_button")
                ) {
                    Text(text = stringResource(R.string.button_close))
                }
            }
        )
    }
}

@Composable
private fun ShizukuPrimaryActionButton(
    isConnected: Boolean,
    isExecuting: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit
) {
    if (isConnected) {
        Button(
            onClick = onClick,
            enabled = !isExecuting,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(testTag),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            if (isExecuting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        FilledTonalButton(
            onClick = onClick,
            enabled = !isExecuting,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(testTag),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ShizukuToggleRow(
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
