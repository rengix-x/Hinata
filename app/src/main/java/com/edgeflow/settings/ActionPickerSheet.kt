package com.edgeflow.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Search
import com.example.ui.icons.HinataIcons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.edgeflow.data.ConfigStore
import com.edgeflow.data.DeviceCompatibilityHelper
import com.edgeflow.data.EdgeAction
import com.edgeflow.data.EdgeActionType
import com.edgeflow.data.EdgeConfig
import com.edgeflow.service.ActionExecutor
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import com.edgeflow.service.shizuku.ShizukuStatus
import com.example.R
import com.example.ui.theme.LocalEdgeFlowColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

fun resolveActionLabel(
    context: Context,
    action: EdgeAction,
    appLabelLookup: Map<String, String> = emptyMap()
): String = when (action.type) {
    EdgeActionType.BACK -> context.getString(R.string.action_back)
    EdgeActionType.HOME -> context.getString(R.string.action_home)
    EdgeActionType.RECENTS -> context.getString(R.string.action_recents)
    EdgeActionType.PREVIOUS_APP -> context.getString(R.string.action_previous_app)
    EdgeActionType.NOTIFICATIONS -> context.getString(R.string.action_notifications)
    EdgeActionType.QUICK_SETTINGS -> context.getString(R.string.action_quick_settings)
    EdgeActionType.LOCK_SCREEN -> context.getString(R.string.action_lock_screen)
    EdgeActionType.SCREENSHOT -> context.getString(R.string.action_screenshot)
    EdgeActionType.SPLIT_SCREEN -> context.getString(R.string.action_split_screen)
    EdgeActionType.FLOAT_CURRENT_APP -> context.getString(R.string.action_float_current_app)
    EdgeActionType.CIRCLE_TO_SEARCH -> context.getString(R.string.action_circle_to_search)
    EdgeActionType.OPEN_QUICK_MENU -> context.getString(R.string.action_open_quick_menu)
    EdgeActionType.OPEN_RECENT_SWITCHER -> context.getString(R.string.action_open_recent_switcher)
    EdgeActionType.TOGGLE_TORCH -> context.getString(R.string.action_toggle_torch)
    EdgeActionType.TOGGLE_SCREEN_TRANSLATE -> context.getString(R.string.action_toggle_screen_translate)
    EdgeActionType.TRANSLATE_SCREEN_ONCE -> context.getString(R.string.action_translate_screen_once)
    EdgeActionType.LAUNCH_APP -> {
        val label = appLabelLookup[action.packageName]
            ?: runCatching {
                val info = context.packageManager.getApplicationInfo(action.packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrNull()
            ?: action.packageName
        label.ifBlank { context.getString(R.string.action_launch_app) }
    }
    EdgeActionType.LAUNCH_APP_FLOATING -> {
        val label = appLabelLookup[action.packageName]
            ?: runCatching {
                val info = context.packageManager.getApplicationInfo(action.packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrNull()
            ?: action.packageName
        if (label.isBlank()) {
            context.getString(R.string.action_launch_app_floating)
        } else {
            context.getString(R.string.action_launch_app_floating_format, label)
        }
    }
    EdgeActionType.LAUNCH_SHORTCUT -> {
        val appLabel = appLabelLookup[action.packageName]
            ?: if (action.packageName.isNotBlank()) {
                runCatching {
                    val info = context.packageManager.getApplicationInfo(action.packageName, 0)
                    context.packageManager.getApplicationLabel(info).toString()
                }.getOrNull() ?: action.packageName
            } else {
                ""
            }
        appLabel.ifBlank { context.getString(R.string.action_launch_shortcut) }
    }
    EdgeActionType.OPEN_URL -> {
        val raw = action.targetData.trim()
        if (raw.isEmpty()) {
            context.getString(R.string.action_open_url)
        } else {
            val host = runCatching {
                val normalized = if (raw.contains("://")) raw else "https://$raw"
                Uri.parse(normalized).host?.removePrefix("www.")
            }.getOrNull()
            host?.takeIf { it.isNotBlank() } ?: raw
        }
    }
    EdgeActionType.NONE -> context.getString(R.string.action_none)
}

fun actionIcon(type: EdgeActionType): ImageVector = when (type) {
    EdgeActionType.BACK -> Icons.AutoMirrored.Rounded.ArrowBack
    EdgeActionType.HOME -> Icons.Rounded.Home
    EdgeActionType.RECENTS -> HinataIcons.ViewCarousel
    EdgeActionType.PREVIOUS_APP -> HinataIcons.SwapHoriz
    EdgeActionType.NOTIFICATIONS -> Icons.Rounded.Notifications
    EdgeActionType.QUICK_SETTINGS -> HinataIcons.Tune
    EdgeActionType.LOCK_SCREEN -> Icons.Rounded.Lock
    EdgeActionType.SCREENSHOT -> HinataIcons.ContentCut
    EdgeActionType.SPLIT_SCREEN -> HinataIcons.Splitscreen
    EdgeActionType.FLOAT_CURRENT_APP -> HinataIcons.PictureInPictureAlt
    EdgeActionType.CIRCLE_TO_SEARCH -> HinataIcons.AutoAwesome
    EdgeActionType.OPEN_QUICK_MENU -> HinataIcons.Radar
    EdgeActionType.OPEN_RECENT_SWITCHER -> HinataIcons.Layers
    EdgeActionType.TOGGLE_TORCH -> HinataIcons.FlashlightOn
    EdgeActionType.TOGGLE_SCREEN_TRANSLATE -> HinataIcons.Translate
    EdgeActionType.TRANSLATE_SCREEN_ONCE -> HinataIcons.Translate
    EdgeActionType.LAUNCH_APP -> HinataIcons.Apps
    EdgeActionType.LAUNCH_APP_FLOATING -> HinataIcons.PictureInPictureAlt
    EdgeActionType.LAUNCH_SHORTCUT -> HinataIcons.Bolt
    EdgeActionType.OPEN_URL -> HinataIcons.Link
    EdgeActionType.NONE -> HinataIcons.Block
}

private data class StaticShortcutItem(
    val id: String,
    val packageName: String,
    val appLabel: String,
    val label: String,
    val intentUri: String,
    val icon: ImageBitmap?
)

private data class ShortcutCreatorItem(
    val id: String,
    val packageName: String,
    val activityName: String,
    val label: String,
    val appLabel: String,
    val icon: ImageBitmap?
)

private data class DiscoveredShortcuts(
    val staticShortcuts: List<StaticShortcutItem>,
    val creators: List<ShortcutCreatorItem>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedActionPickerSheet(
    currentAction: EdgeAction,
    allowNone: Boolean = true,
    allowOpenQuickMenu: Boolean = true,
    config: EdgeConfig? = null,
    onSelect: (EdgeAction, String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val extendedColors = LocalEdgeFlowColors.current
    val capabilities = remember { DeviceCompatibilityHelper.resolveCapabilities() }
    val isShizukuConnected = remember {
        ShizukuShellExecutor.checkStatus(context) == ShizukuStatus.CONNECTED
    }
    val canUseFloat = capabilities.supportsHyperOsNativeFloatingWindow || isShizukuConnected
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember {
        mutableIntStateOf(
            when {
                currentAction.isUrlLaunch -> 3
                currentAction.isShortcutLaunch -> 2
                currentAction.isAppLaunch -> 1
                else -> 0
            }
        )
    }
    var openInFloatingWindow by remember {
        mutableStateOf(currentAction.type == EdgeActionType.LAUNCH_APP_FLOATING && canUseFloat)
    }
    var showNoShizukuFloatDialog by remember { mutableStateOf(false) }
    var appSearchQuery by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<LaunchableAppItem>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(false) }

    // Shortcuts discovery state (non-root 2-tier: static XML + ACTION_CREATE_SHORTCUT creators)
    var shortcutSearchQuery by remember { mutableStateOf("") }
    var discoveredShortcuts by remember { mutableStateOf<DiscoveredShortcuts?>(null) }
    var loadingShortcuts by remember { mutableStateOf(false) }
    var pendingCreatorPackage by remember { mutableStateOf("") }

    // URL link editor state
    var urlInput by remember {
        mutableStateOf(if (currentAction.isUrlLaunch) currentAction.targetData else "")
    }
    var customLinkName by remember { mutableStateOf("") }

    val shortcutCreatorLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        SettingsActivity.isWaitingForChildActivityResult = false
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            @Suppress("DEPRECATION")
            val shortcutIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data?.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
            } else {
                data?.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
            }
            @Suppress("DEPRECATION")
            val shortcutName = data?.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)?.trim().orEmpty()
            if (shortcutIntent != null) {
                val targetPkg = shortcutIntent.`package`
                    ?: shortcutIntent.component?.packageName
                    ?: pendingCreatorPackage
                val uri = shortcutIntent.toUri(Intent.URI_INTENT_SCHEME)
                onSelect(EdgeAction.launchShortcut(targetPkg, uri), shortcutName)
            } else {
                Toast.makeText(context, R.string.shortcut_create_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && apps.isEmpty()) {
            loadingApps = true
            apps = withContext(Dispatchers.IO) {
                loadLaunchableApps(context.packageManager, context.packageName)
            }
            loadingApps = false
        } else if (selectedTab == 2 && discoveredShortcuts == null) {
            loadingShortcuts = true
            discoveredShortcuts = withContext(Dispatchers.IO) {
                discoverNonRootShortcuts(context.packageManager, context.packageName)
            }
            loadingShortcuts = false
        }
    }

    val systemActions = remember(allowNone, allowOpenQuickMenu) {
        EdgeActionType.entries.filter { type ->
            type != EdgeActionType.LAUNCH_APP &&
                type != EdgeActionType.LAUNCH_APP_FLOATING &&
                type != EdgeActionType.LAUNCH_SHORTCUT &&
                type != EdgeActionType.OPEN_URL &&
                (allowNone || type != EdgeActionType.NONE) &&
                (allowOpenQuickMenu || type != EdgeActionType.OPEN_QUICK_MENU)
        }
    }

    val filteredApps = remember(apps, appSearchQuery) {
        val q = appSearchQuery.trim().lowercase()
        if (q.isEmpty()) apps else apps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    val filteredStaticShortcuts = remember(discoveredShortcuts, shortcutSearchQuery) {
        val list = discoveredShortcuts?.staticShortcuts.orEmpty()
        val q = shortcutSearchQuery.trim().lowercase()
        if (q.isEmpty()) list else list.filter {
            it.label.lowercase().contains(q) ||
                it.appLabel.lowercase().contains(q) ||
                it.packageName.lowercase().contains(q)
        }
    }

    val filteredCreators = remember(discoveredShortcuts, shortcutSearchQuery) {
        val list = discoveredShortcuts?.creators.orEmpty()
        val q = shortcutSearchQuery.trim().lowercase()
        if (q.isEmpty()) list else list.filter {
            it.label.lowercase().contains(q) ||
                it.appLabel.lowercase().contains(q) ||
                it.packageName.lowercase().contains(q)
        }
    }

    val fieldUnfocusedBorder = extendedColors.cardBorder
    val fieldContainerColor = Color.Transparent

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = stringResource(R.string.dialog_pick_action_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(12.dp))

            SecondaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.testTag("picker_tab_system"),
                    text = {
                        Text(
                            text = stringResource(R.string.picker_tab_system),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.testTag("picker_tab_apps"),
                    text = {
                        Text(
                            text = stringResource(R.string.picker_tab_apps),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    modifier = Modifier.testTag("picker_tab_shortcuts"),
                    text = {
                        Text(
                            text = stringResource(R.string.tab_shortcuts),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    modifier = Modifier.testTag("picker_tab_link"),
                    text = {
                        Text(
                            text = stringResource(R.string.tab_link),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            when (selectedTab) {
                0 -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(systemActions, key = { it.name }) { candidateType ->
                            val candidate = EdgeAction(candidateType)
                            val selected = currentAction.type == candidateType
                            val floatRestricted = candidateType == EdgeActionType.FLOAT_CURRENT_APP && !canUseFloat
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        if (floatRestricted) {
                                            Toast.makeText(
                                                context,
                                                R.string.floating_window_hyperos_or_shizuku_only,
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            onSelect(candidate, "")
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp)
                                    .testTag("pick_action_${candidateType.name}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = actionIcon(candidateType),
                                    contentDescription = null,
                                    tint = when {
                                        floatRestricted -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                        selected -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = resolveActionLabel(context, candidate),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = when {
                                        floatRestricted -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f)
                                        selected -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                if (floatRestricted) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = stringResource(R.string.floating_window_requires_hyperos_or_shizuku_badge),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                } else if (selected) {
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

                1 -> {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                if (!canUseFloat) {
                                    Toast.makeText(
                                        context,
                                        R.string.floating_window_hyperos_or_shizuku_only,
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    val next = !openInFloatingWindow
                                    openInFloatingWindow = next
                                    if (next && shouldShowNoShizukuFloatNotice(context)) {
                                        showNoShizukuFloatDialog = true
                                    }
                                }
                            }
                            .testTag("picker_floating_window_row"),
                        shape = RoundedCornerShape(14.dp),
                        color = if (openInFloatingWindow && canUseFloat) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
                        },
                        border = if (openInFloatingWindow && canUseFloat) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.50f))
                        } else {
                            null
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = HinataIcons.PictureInPictureAlt,
                                contentDescription = null,
                                tint = when {
                                    !canUseFloat -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    openInFloatingWindow -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.floating_window_toggle_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (canUseFloat) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                    }
                                )
                                Text(
                                    text = stringResource(
                                        if (canUseFloat) R.string.floating_window_toggle_desc
                                        else R.string.floating_window_toggle_restricted_desc
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = openInFloatingWindow && canUseFloat,
                                enabled = canUseFloat,
                                onCheckedChange = { next ->
                                    openInFloatingWindow = next
                                    if (next && shouldShowNoShizukuFloatNotice(context)) {
                                        showNoShizukuFloatDialog = true
                                    }
                                },
                                modifier = Modifier.testTag("picker_floating_window_switch")
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = appSearchQuery,
                        onValueChange = { appSearchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("picker_app_search"),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.blacklist_search_placeholder),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            if (appSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { appSearchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Clear,
                                        contentDescription = stringResource(R.string.cd_clear_search)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = fieldUnfocusedBorder,
                            focusedContainerColor = fieldContainerColor,
                            unfocusedContainerColor = fieldContainerColor
                        )
                    )

                    Spacer(Modifier.height(10.dp))

                    if (loadingApps) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filteredApps, key = { it.packageName }) { app ->
                                val selected = currentAction.isAppLaunch &&
                                    currentAction.packageName == app.packageName
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            val action = if (openInFloatingWindow) {
                                                EdgeAction.launchAppFloating(app.packageName)
                                            } else {
                                                EdgeAction.launchApp(app.packageName)
                                            }
                                            onSelect(action, app.label)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (app.icon != null) {
                                        Image(
                                            bitmap = app.icon,
                                            contentDescription = app.label,
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(9.dp))
                                        )
                                    } else {
                                        Icon(
                                            imageVector = HinataIcons.Apps,
                                            contentDescription = app.label,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.label,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = app.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
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

                2 -> {
                    OutlinedTextField(
                        value = shortcutSearchQuery,
                        onValueChange = { shortcutSearchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("picker_shortcut_search"),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search_shortcuts_hint),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            if (shortcutSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { shortcutSearchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Clear,
                                        contentDescription = stringResource(R.string.cd_clear_search)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = fieldUnfocusedBorder,
                            focusedContainerColor = fieldContainerColor,
                            unfocusedContainerColor = fieldContainerColor
                        )
                    )

                    Spacer(Modifier.height(10.dp))

                    if (loadingShortcuts) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else if (filteredStaticShortcuts.isEmpty() && filteredCreators.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.empty_shortcuts_found),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (filteredStaticShortcuts.isNotEmpty()) {
                                item(key = "header_static_shortcuts") {
                                    Text(
                                        text = stringResource(R.string.section_app_shortcuts),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                                    )
                                }
                                items(filteredStaticShortcuts, key = { it.id }) { item ->
                                    val selected = currentAction.isShortcutLaunch &&
                                        currentAction.targetData == item.intentUri
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                                else Color.Transparent
                                            )
                                            .clickable {
                                                onSelect(
                                                    EdgeAction.launchShortcut(item.packageName, item.intentUri),
                                                    item.label
                                                )
                                            }
                                            .padding(horizontal = 10.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (item.icon != null) {
                                            Image(
                                                bitmap = item.icon,
                                                contentDescription = item.label,
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(RoundedCornerShape(9.dp))
                                            )
                                        } else {
                                            Icon(
                                                imageVector = HinataIcons.Bolt,
                                                contentDescription = item.label,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.label,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = item.appLabel,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
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

                            if (filteredCreators.isNotEmpty()) {
                                item(key = "header_shortcut_creators") {
                                    Text(
                                        text = stringResource(R.string.section_create_shortcut_apps),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 4.dp)
                                    )
                                }
                                items(filteredCreators, key = { it.id }) { creator ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                pendingCreatorPackage = creator.packageName
                                                val intent = Intent(Intent.ACTION_CREATE_SHORTCUT).apply {
                                                    component = ComponentName(creator.packageName, creator.activityName)
                                                }
                                                SettingsActivity.isWaitingForChildActivityResult = true
                                                runCatching {
                                                    shortcutCreatorLauncher.launch(intent)
                                                }.onFailure {
                                                    SettingsActivity.isWaitingForChildActivityResult = false
                                                    Toast.makeText(
                                                        context,
                                                        R.string.shortcut_create_failed,
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (creator.icon != null) {
                                            Image(
                                                bitmap = creator.icon,
                                                contentDescription = creator.label,
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(RoundedCornerShape(9.dp))
                                            )
                                        } else {
                                            Icon(
                                                imageVector = HinataIcons.Bolt,
                                                contentDescription = creator.label,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = creator.label,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = creator.appLabel,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Icon(
                                            imageVector = HinataIcons.OpenInNew,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    // Tab 3: URL Link Launch (unified validation with ActionExecutor.normalizeAndValidateUrl)
                    val normalizedUrl = remember(urlInput) {
                        ActionExecutor.normalizeAndValidateUrl(urlInput)
                    }
                    val isUrlValid = normalizedUrl != null
                    val showUrlError = urlInput.isNotBlank() && !isUrlValid

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("picker_url_input"),
                            label = { Text(stringResource(R.string.label_url_input)) },
                            placeholder = { Text(stringResource(R.string.hint_url_input)) },
                            supportingText = {
                                Text(
                                    text = stringResource(R.string.hint_url_validation_guide),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (showUrlError) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            },
                            isError = showUrlError,
                            leadingIcon = {
                                Icon(
                                    imageVector = HinataIcons.Link,
                                    contentDescription = null
                                )
                            },
                            trailingIcon = {
                                if (urlInput.isNotEmpty()) {
                                    IconButton(onClick = { urlInput = "" }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Clear,
                                            contentDescription = stringResource(R.string.cd_clear_search)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = fieldUnfocusedBorder,
                                focusedContainerColor = fieldContainerColor,
                                unfocusedContainerColor = fieldContainerColor
                            )
                        )

                        OutlinedTextField(
                            value = customLinkName,
                            onValueChange = { customLinkName = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("picker_url_label_input"),
                            label = { Text(stringResource(R.string.label_custom_name_optional)) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = fieldUnfocusedBorder,
                                focusedContainerColor = fieldContainerColor,
                                unfocusedContainerColor = fieldContainerColor
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val validUrl = normalizedUrl ?: return@OutlinedButton
                                    runCatching {
                                        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(validUrl)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(viewIntent)
                                    }.onFailure {
                                        Toast.makeText(
                                            context,
                                            R.string.toast_url_open_failed,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                enabled = isUrlValid,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("picker_url_test_button"),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = stringResource(R.string.button_test_link),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Button(
                                onClick = {
                                    val validUrl = normalizedUrl ?: return@Button
                                    onSelect(EdgeAction.openUrl(validUrl), customLinkName.trim())
                                },
                                enabled = isUrlValid,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("picker_url_save_button"),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text(
                                    text = stringResource(R.string.button_save_link),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNoShizukuFloatDialog) {
        NoShizukuFloatSetupDialog(
            config = config ?: remember(context) { ConfigStore.load(context) },
            onDismiss = { showNoShizukuFloatDialog = false }
        )
    }
}

/**
 * 2-tier non-root, non-default-launcher shortcut discovery:
 * - Tier A: Parses static `android.app.shortcuts` XML metadata from installed launcher activities.
 * - Tier B: Queries activities handling `Intent.ACTION_CREATE_SHORTCUT`.
 */
private fun discoverNonRootShortcuts(
    pm: PackageManager,
    ownPackage: String
): DiscoveredShortcuts {
    val staticResults = ArrayList<StaticShortcutItem>()
    val seenStaticKeys = HashSet<String>()
    val iconCache = HashMap<String, ImageBitmap?>()

    fun appIconFor(pkg: String): ImageBitmap? = iconCache.getOrPut(pkg) {
        runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }

    // Tier A: Static shortcuts declared via <meta-data android:name="android.app.shortcuts" />
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val launcherActivities = runCatching {
        pm.queryIntentActivities(launcherIntent, PackageManager.GET_META_DATA)
    }.getOrDefault(emptyList())

    for (resolve in launcherActivities) {
        val activityInfo = resolve.activityInfo ?: continue
        val pkg = activityInfo.packageName ?: continue
        if (pkg == ownPackage) continue
        val resId = activityInfo.metaData?.getInt("android.app.shortcuts", 0) ?: 0
        if (resId == 0) continue

        val appInfo = activityInfo.applicationInfo ?: continue
        val appLabel = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
        val appRes = runCatching { pm.getResourcesForApplication(appInfo) }.getOrNull() ?: continue
        val xml = runCatching { pm.getXml(pkg, resId, appInfo) }.getOrNull() ?: continue

        try {
            var currentShortcutId = ""
            var currentEnabled = true
            var currentLabel = ""
            var insideShortcut = false
            var eventType = xml.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (xml.name) {
                        "shortcut" -> {
                            insideShortcut = true
                            currentShortcutId = xml.getAttributeValue(ANDROID_NS, "shortcutId").orEmpty()
                            val enabledRaw = xml.getAttributeValue(ANDROID_NS, "enabled")
                            currentEnabled = enabledRaw == null || enabledRaw.equals("true", ignoreCase = true)
                            currentLabel = resolveXmlString(xml, appRes, "shortcutShortLabel")
                                .ifBlank { resolveXmlString(xml, appRes, "shortcutLongLabel") }
                        }

                        "intent" -> {
                            if (insideShortcut && currentEnabled) {
                                val action = xml.getAttributeValue(ANDROID_NS, "action").orEmpty()
                                val targetPkg = xml.getAttributeValue(ANDROID_NS, "targetPackage").orEmpty().ifBlank { pkg }
                                val targetClass = xml.getAttributeValue(ANDROID_NS, "targetClass").orEmpty()
                                val data = xml.getAttributeValue(ANDROID_NS, "data").orEmpty()
                                if (action.isNotBlank() || targetClass.isNotBlank()) {
                                    val shortcutIntent = Intent().apply {
                                        if (action.isNotBlank()) setAction(action)
                                        if (targetClass.isNotBlank()) {
                                            val resolvedClass = if (targetClass.startsWith(".")) {
                                                targetPkg + targetClass
                                            } else {
                                                targetClass
                                            }
                                            component = ComponentName(targetPkg, resolvedClass)
                                        } else {
                                            setPackage(targetPkg)
                                        }
                                        if (data.isNotBlank()) {
                                            setData(Uri.parse(data))
                                        }
                                    }
                                    val uri = shortcutIntent.toUri(Intent.URI_INTENT_SCHEME)
                                    val dedupKey = "$pkg|$uri"
                                    val finalLabel = currentLabel.ifBlank { currentShortcutId }.ifBlank { appLabel }
                                    if (seenStaticKeys.add(dedupKey)) {
                                        staticResults.add(
                                            StaticShortcutItem(
                                                id = "static_${staticResults.size}_$pkg",
                                                packageName = pkg,
                                                appLabel = appLabel,
                                                label = finalLabel,
                                                intentUri = uri,
                                                icon = appIconFor(pkg)
                                            )
                                        )
                                    }
                                    // Avoid adding multiple nested intents for the same shortcut tag
                                    insideShortcut = false
                                }
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG && xml.name == "shortcut") {
                    insideShortcut = false
                }
                eventType = xml.next()
            }
        } catch (_: Throwable) {
            // Skip malformed third-party shortcut XML cleanly
        } finally {
            runCatching { xml.close() }
        }
    }

    // Tier B: Apps supporting Intent.ACTION_CREATE_SHORTCUT
    val creatorResults = ArrayList<ShortcutCreatorItem>()
    val seenCreators = HashSet<String>()
    val createIntent = Intent(Intent.ACTION_CREATE_SHORTCUT)
    val creatorActivities = runCatching {
        pm.queryIntentActivities(createIntent, 0)
    }.getOrDefault(emptyList())

    for (resolve in creatorActivities) {
        val activityInfo = resolve.activityInfo ?: continue
        val pkg = activityInfo.packageName ?: continue
        val actName = activityInfo.name ?: continue
        if (pkg == ownPackage) continue
        val key = "$pkg/$actName"
        if (!seenCreators.add(key)) continue
        val label = runCatching { resolve.loadLabel(pm).toString() }.getOrDefault(pkg)
        val appLabel = runCatching {
            val info = activityInfo.applicationInfo
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
        val icon = runCatching {
            resolve.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
        }.getOrNull() ?: appIconFor(pkg)
        creatorResults.add(
            ShortcutCreatorItem(
                id = "creator_$key",
                packageName = pkg,
                activityName = actName,
                label = label,
                appLabel = appLabel,
                icon = icon
            )
        )
    }

    staticResults.sortWith(compareBy({ it.appLabel.lowercase() }, { it.label.lowercase() }))
    creatorResults.sortWith(compareBy({ it.appLabel.lowercase() }, { it.label.lowercase() }))
    return DiscoveredShortcuts(staticShortcuts = staticResults, creators = creatorResults)
}

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

private fun resolveXmlString(
    xml: android.content.res.XmlResourceParser,
    res: android.content.res.Resources,
    attrName: String
): String {
    val resId = xml.getAttributeResourceValue(ANDROID_NS, attrName, 0)
    if (resId != 0) {
        return runCatching { res.getString(resId) }.getOrDefault("")
    }
    return xml.getAttributeValue(ANDROID_NS, attrName).orEmpty()
}
