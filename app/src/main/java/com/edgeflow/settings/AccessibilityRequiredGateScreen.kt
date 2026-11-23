package com.edgeflow.settings

import android.app.StatusBarManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.edgeflow.data.EdgeConfig
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import com.edgeflow.service.shizuku.ShizukuStatus
import com.example.R
import com.example.ui.icons.HinataIcons
import com.example.ui.theme.LocalEdgeFlowColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen mandatory Accessibility Service hard gate displayed whenever onboarding is finished
 * but Hinata's AccessibilityService is currently disabled.
 *
 * Always exposes:
 * 1. Direct button to open Android Accessibility Settings.
 * 2. Shizuku 1-tap auto-enable button when Shizuku is connected.
 * 3. Always-visible Emergency 3-Button / System Navigation Restore button so users who hid their
 *    system nav bar before Accessibility stopped are never stranded.
 * 4. Secondary button to add the "Hinata Recovery" Quick Settings tile (API 33+).
 */
@Composable
fun AccessibilityRequiredGateScreen(
    config: EdgeConfig,
    onConfigChange: (EdgeConfig) -> Unit,
    onRefreshStatus: () -> Unit,
    onFinishAndRemoveTask: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val extendedColors = LocalEdgeFlowColors.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var shizukuStatus by remember { mutableStateOf(ShizukuShellExecutor.checkStatus(context)) }
    var isRevivingWithShizuku by remember { mutableStateOf(false) }
    var isRestoringNav by remember { mutableStateOf(false) }

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

    BackHandler {
        onFinishAndRemoveTask()
    }

    fun showMessage(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    val isShizukuConnected = shizukuStatus == ShizukuStatus.CONNECTED

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("accessibility_required_gate_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = HinataIcons.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.a11y_gate_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = stringResource(R.string.a11y_gate_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                Spacer(Modifier.height(22.dp))

                // Main Button 1: Open Accessibility Settings
                Button(
                    onClick = { openAccessibilitySettings(context, keepTaskAlive = true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .testTag("gate_open_accessibility_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = HinataIcons.AccessibilityNew,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.a11y_gate_open_accessibility_button),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Main Button 2 (Shizuku Connected only): Instant Auto-Enable via Shizuku
                if (isShizukuConnected) {
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(
                        onClick = {
                            if (isRevivingWithShizuku) return@FilledTonalButton
                            isRevivingWithShizuku = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    ShizukuShellExecutor.reviveAccessibilityServiceWithShizuku(
                                        context = context,
                                        forceToggleRebind = true
                                    )
                                }
                                delay(450L)
                                onRefreshStatus()
                                isRevivingWithShizuku = false
                                if (result.success) {
                                    showMessage(context.getString(R.string.recovery_toast_revive_success))
                                } else {
                                    showMessage(
                                        context.getString(R.string.recovery_toast_revive_failed_open_settings)
                                    )
                                }
                            }
                        },
                        enabled = !isRevivingWithShizuku,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("gate_shizuku_auto_enable_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (isRevivingWithShizuku) {
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

                Spacer(Modifier.height(14.dp))

                // Emergency Escape Button (always visible): Immediately restore 3-button / OS nav
                OutlinedButton(
                    onClick = {
                        if (isRestoringNav) return@OutlinedButton
                        ShizukuShellExecutor.refreshNonProviderBinder(context)
                        if (ShizukuShellExecutor.isReady()) {
                            isRestoringNav = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    ShizukuShellExecutor.restoreAllSystemNavigation(context)
                                }
                                isRestoringNav = false
                                if (result.success) {
                                    if (config.autoHideNavBarWithShizuku || config.shizukuSystemNavModified) {
                                        onConfigChange(
                                            config.copy(
                                                autoHideNavBarWithShizuku = false,
                                                shizukuSystemNavModified = false
                                            )
                                        )
                                    }
                                    showMessage(context.getString(R.string.recovery_toast_nav_restored))
                                } else {
                                    showMessage(
                                        context.getString(R.string.recovery_toast_nav_restore_failed_open_settings)
                                    )
                                    openSystemNavigationSettingsDirect(context, keepTaskAlive = true)
                                }
                            }
                        } else {
                            openSystemNavigationSettingsDirect(context, keepTaskAlive = true)
                        }
                    },
                    enabled = !isRestoringNav,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("gate_emergency_restore_nav_button"),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    if (isRestoringNav) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Icon(
                            imageVector = HinataIcons.HealthAndSafety,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.a11y_gate_emergency_restore_nav_button),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Secondary Button: Add Quick Settings Recovery Tile (API 33+)
                TextButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val dispatched = requestAddRecoveryQsTile(context) { resultCode ->
                                val msg = when (resultCode) {
                                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                                        context.getString(R.string.onboarding_qs_tile_added_toast)
                                    else ->
                                        context.getString(R.string.onboarding_qs_tile_manual_hint_toast)
                                }
                                showMessage(msg)
                            }
                            if (!dispatched) {
                                showMessage(context.getString(R.string.onboarding_qs_tile_manual_hint_toast))
                            }
                        } else {
                            showMessage(context.getString(R.string.onboarding_qs_tile_manual_hint_toast))
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("gate_add_qs_tile_button")
                ) {
                    Icon(
                        imageVector = HinataIcons.Shield,
                        contentDescription = null,
                        tint = extendedColors.statusActive,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.a11y_gate_add_qs_tile_button),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
