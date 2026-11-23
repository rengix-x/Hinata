package com.edgeflow.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.ui.icons.HinataIcons
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edgeflow.data.DeviceCompatibilityHelper
import com.edgeflow.data.EdgeConfig
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import com.edgeflow.service.shizuku.ShizukuStatus
import com.example.R
import com.example.ui.theme.LocalEdgeFlowColors
import com.example.ui.theme.NumericLabelStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hybrid Circle to Search settings card (Spec 64) for CN ROMs (OriginOS 6, ColorOS 16,
 * realme UI, HyperOS CN) and Global ROMs:
 * - 3-mode selector (Auto Native First / Native Google Only / Built-In Lasso + Lens)
 * - Auto-repair Google Assistant via Shizuku toggle
 * - Force-repair & Reset Assistant Shizuku actions
 * - 4-step CN ROM checklist + 1-tap "Open Google App Info" shortcut
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircleToSearchSettingsCard(
    config: EdgeConfig,
    onConfigChange: (EdgeConfig) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val extendedColors = LocalEdgeFlowColors.current
    val scope = rememberCoroutineScope()
    val capabilities = remember { DeviceCompatibilityHelper.resolveCapabilities() }
    var isBusy by remember { mutableStateOf(false) }

    fun showMessage(msg: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    fun runRepairAssistant() {
        if (isBusy) return
        val status = ShizukuShellExecutor.checkStatus(context)
        if (status != ShizukuStatus.CONNECTED) {
            if (status == ShizukuStatus.PERMISSION_REQUIRED) {
                ShizukuShellExecutor.requestPermission()
            } else {
                showMessage(context.getString(R.string.shizuku_snackbar_not_ready))
            }
            return
        }
        isBusy = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                ShizukuShellExecutor.enableGoogleAssistantForCts(context)
            }
            isBusy = false
            showMessage(
                if (res.success) context.getString(R.string.cts_shizuku_repair_success)
                else context.getString(R.string.shizuku_snackbar_failed)
            )
        }
    }

    fun runResetAssistant() {
        if (isBusy) return
        val status = ShizukuShellExecutor.checkStatus(context)
        if (status != ShizukuStatus.CONNECTED) {
            showMessage(context.getString(R.string.shizuku_snackbar_not_ready))
            return
        }
        isBusy = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                ShizukuShellExecutor.resetAssistantSettings(context)
            }
            isBusy = false
            showMessage(
                if (res.success) context.getString(R.string.cts_shizuku_reset_success)
                else context.getString(R.string.shizuku_snackbar_failed)
            )
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("cts_settings_card"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extendedColors.cardBorder)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HinataIcons.ImageSearch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.cts_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.cts_card_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = extendedColors.cardBorder)
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        onConfigChange(
                            config.copy(
                                ctsAutoRepairAssistantWithShizuku = !config.ctsAutoRepairAssistantWithShizuku
                            )
                        )
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.cts_toggle_auto_repair_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.cts_toggle_auto_repair_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = config.ctsAutoRepairAssistantWithShizuku,
                    onCheckedChange = {
                        onConfigChange(config.copy(ctsAutoRepairAssistantWithShizuku = it))
                    },
                    modifier = Modifier.testTag("cts_auto_repair_switch")
                )
            }

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = ::runRepairAssistant,
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("cts_shizuku_repair_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = HinataIcons.AutoFixHigh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.cts_shizuku_repair_button),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = ::runResetAssistant,
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("cts_shizuku_reset_button"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, extendedColors.cardBorder)
            ) {
                Icon(
                    imageVector = HinataIcons.Restore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.cts_shizuku_reset_button),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (capabilities.isChineseRomCtsRepairRelevant) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = extendedColors.cardBorder)
                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.cts_cn_rom_checklist_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                CtsChecklistRow(1, stringResource(R.string.cts_cn_rom_step1))
                CtsChecklistRow(2, stringResource(R.string.cts_cn_rom_step2))
                CtsChecklistRow(3, stringResource(R.string.cts_cn_rom_step3))
                CtsChecklistRow(4, stringResource(R.string.cts_cn_rom_step4))

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", GOOGLE_APP_PACKAGE, null)
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (runCatching { context.startActivity(intent) }.isFailure) {
                            showMessage(context.getString(R.string.cts_google_app_not_found))
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("cts_open_google_app_info_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, extendedColors.cardBorder)
                ) {
                    Text(
                        text = stringResource(R.string.cts_open_google_app_info_button),
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
private fun CtsChecklistRow(step: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
            modifier = Modifier.size(20.dp)
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
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

private const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
