package com.edgeflow.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import com.example.ui.icons.HinataIcons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edgeflow.data.DeviceCompatibilityHelper
import com.edgeflow.data.EdgeAction
import com.edgeflow.data.EdgeActionType
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.EdgeSide
import com.edgeflow.data.QuickArcTriggerMethod
import com.edgeflow.data.QuickArcZoneSide
import com.edgeflow.data.QuickSlotConfig
import com.edgeflow.data.SecondaryTriggerMode
import com.edgeflow.data.VisualTheme
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import com.edgeflow.service.shizuku.ShizukuStatus
import com.example.R
import com.example.ui.theme.LocalEdgeFlowColors
import com.example.ui.theme.NumericSmallStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickArcSettingsScreen(
    config: EdgeConfig,
    onConfigChange: (EdgeConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val extendedColors = LocalEdgeFlowColors.current
    val capabilities = remember { DeviceCompatibilityHelper.resolveCapabilities() }
    val isShizukuConnected = remember {
        ShizukuShellExecutor.checkStatus(context) == ShizukuStatus.CONNECTED
    }
    val canUseLaunchAppFloating =
        capabilities.supportsHyperOsNativeFloatingWindow || isShizukuConnected
    var editingSlotIndex by remember { mutableStateOf<Int?>(null) }
    var addingNewSlot by remember { mutableStateOf(false) }
    var showNoShizukuFloatSetup by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("quick_arc_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(
                    text = stringResource(R.string.arc_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.arc_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 4 selectable Quick Menu trigger methods
        item {
            QuickArcTriggerMethodSection(
                config = config,
                onConfigChange = onConfigChange
            )
        }

        // Optional 1-tap banner when a non-gesture method is active and horizontal Deep Swipe is still OPEN_QUICK_MENU
        if (config.quickArcTriggerMethod != QuickArcTriggerMethod.GESTURE_ACTION &&
            config.horizontalHoldAction.type == EdgeActionType.OPEN_QUICK_MENU
        ) {
            item {
                FreeHorizontalStage2Banner(
                    onSwitchToPreviousApp = {
                        onConfigChange(config.copy(horizontalHoldAction = EdgeAction.PREVIOUS_APP))
                    }
                )
            }
        }

        // Interactive live radial menu preview
        item {
            SectionCard {
                QuickArcPreviewCanvas(
                    config = config,
                    slotLabelResolver = { slot ->
                        slot.customLabel.ifBlank { resolveActionLabel(context, slot.action) }
                    }
                )
            }
        }

        // Menu layout & dim controls
        item {
            SectionCard(title = stringResource(R.string.arc_section_geometry)) {
                MetricSlider(
                    label = stringResource(R.string.arc_label_radius),
                    valueText = "${config.quickMenuRadiusDp.roundToInt()} dp",
                    value = config.quickMenuRadiusDp,
                    range = 84f..156f,
                    testTag = "slider_arc_radius",
                    onValueChange = { onConfigChange(config.copy(quickMenuRadiusDp = it)) }
                )

                MetricSlider(
                    label = stringResource(R.string.arc_label_dim_alpha),
                    valueText = "${(config.quickMenuDimAlpha * 100).roundToInt()}%",
                    value = config.quickMenuDimAlpha,
                    range = 0.0f..0.40f,
                    testTag = "slider_arc_dim",
                    onValueChange = { onConfigChange(config.copy(quickMenuDimAlpha = it)) }
                )
            }
        }

        // Slot list (3..16 slots) with Add, Delete, Reorder & Action/App/Shortcut/Link picker
        item {
            SectionCard(title = stringResource(R.string.arc_section_slots)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.label_slot_count_ratio,
                                config.quickMenuSlots.size,
                                EdgeConfig.MAX_QUICK_MENU_SLOTS
                            ),
                            style = NumericSmallStyle,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    OutlinedButton(
                        onClick = { addingNewSlot = true },
                        enabled = config.quickMenuSlots.size < EdgeConfig.MAX_QUICK_MENU_SLOTS,
                        modifier = Modifier.testTag("arc_add_slot_button"),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        border = BorderStroke(
                            1.dp,
                            if (config.quickMenuSlots.size < EdgeConfig.MAX_QUICK_MENU_SLOTS) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            } else {
                                extendedColors.cardBorder
                            }
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.button_add_slot),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                if (config.quickMenuSlots.size >= 7) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = HinataIcons.Autorenew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.hint_quick_menu_rotary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                config.quickMenuSlots.forEachIndexed { index, slot ->
                    if (index > 0) {
                        HorizontalDivider(color = extendedColors.cardBorder)
                    }
                    val displayLabel = slot.customLabel.ifBlank {
                        resolveActionLabel(context, slot.action)
                    }
                    val isAppSlot = slot.action.isAppLaunch
                    val isFloatingSlot = slot.action.type == EdgeActionType.LAUNCH_APP_FLOATING
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editingSlotIndex = index }
                                .padding(vertical = 8.dp)
                                .testTag("arc_slot_row_$index"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = actionIcon(slot.action.type),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.arc_slot_number, index + 1),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (isFloatingSlot) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.floating_window_badge_short),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = displayLabel,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (index > 0) {
                                        val mutable = config.quickMenuSlots.toMutableList()
                                        val item = mutable.removeAt(index)
                                        mutable.add(index - 1, item)
                                        onConfigChange(config.copy(quickMenuSlots = mutable))
                                    }
                                },
                                enabled = index > 0
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.cd_move_up)
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (index < config.quickMenuSlots.lastIndex) {
                                        val mutable = config.quickMenuSlots.toMutableList()
                                        val item = mutable.removeAt(index)
                                        mutable.add(index + 1, item)
                                        onConfigChange(config.copy(quickMenuSlots = mutable))
                                    }
                                },
                                enabled = index < config.quickMenuSlots.lastIndex
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.cd_move_down)
                                )
                            }
                            if (config.quickMenuSlots.size > EdgeConfig.MIN_QUICK_MENU_SLOTS) {
                                IconButton(
                                    onClick = {
                                        val mutable = config.quickMenuSlots.toMutableList()
                                        if (index in mutable.indices && mutable.size > EdgeConfig.MIN_QUICK_MENU_SLOTS) {
                                            mutable.removeAt(index)
                                            onConfigChange(config.copy(quickMenuSlots = mutable))
                                        }
                                    },
                                    modifier = Modifier.testTag("arc_slot_delete_$index")
                                ) {
                                    Icon(
                                        imageVector = HinataIcons.DeleteOutline,
                                        contentDescription = stringResource(R.string.cd_delete_slot),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = HinataIcons.ChevronRight,
                                contentDescription = stringResource(R.string.cd_change),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isAppSlot) {
                            val canToggleFloat = canUseLaunchAppFloating || isFloatingSlot
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 50.dp, bottom = 8.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                                    .clickable {
                                        val enablingFloat = !isFloatingSlot
                                        if (enablingFloat && !canUseLaunchAppFloating) {
                                            Toast.makeText(
                                                context,
                                                R.string.floating_window_hyperos_or_shizuku_only,
                                                Toast.LENGTH_LONG
                                            ).show()
                                            return@clickable
                                        }
                                        val toggledAction = if (isFloatingSlot) {
                                            EdgeAction.launchApp(slot.action.packageName)
                                        } else {
                                            EdgeAction.launchAppFloating(slot.action.packageName)
                                        }
                                        val mutable = config.quickMenuSlots.toMutableList()
                                        mutable[index] = slot.copy(action = toggledAction)
                                        onConfigChange(config.copy(quickMenuSlots = mutable))
                                        if (enablingFloat && shouldShowNoShizukuFloatNotice(context)) {
                                            showNoShizukuFloatSetup = true
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .testTag("arc_slot_floating_row_$index"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = HinataIcons.PictureInPictureAlt,
                                        contentDescription = null,
                                        tint = when {
                                            !canUseLaunchAppFloating && !isFloatingSlot ->
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                            isFloatingSlot -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.floating_window_toggle_title),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (canToggleFloat) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!canUseLaunchAppFloating) {
                                            Text(
                                                text = stringResource(R.string.floating_window_toggle_restricted_desc),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = isFloatingSlot,
                                    enabled = canToggleFloat,
                                    onCheckedChange = { enabled ->
                                        if (enabled && !canUseLaunchAppFloating) {
                                            Toast.makeText(
                                                context,
                                                R.string.floating_window_hyperos_or_shizuku_only,
                                                Toast.LENGTH_LONG
                                            ).show()
                                            return@Switch
                                        }
                                        val toggledAction = if (enabled) {
                                            EdgeAction.launchAppFloating(slot.action.packageName)
                                        } else {
                                            EdgeAction.launchApp(slot.action.packageName)
                                        }
                                        val mutable = config.quickMenuSlots.toMutableList()
                                        mutable[index] = slot.copy(action = toggledAction)
                                        onConfigChange(config.copy(quickMenuSlots = mutable))
                                        if (enabled && shouldShowNoShizukuFloatNotice(context)) {
                                            showNoShizukuFloatSetup = true
                                        }
                                    },
                                    modifier = Modifier.testTag("arc_slot_floating_switch_$index")
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val activeEdit = editingSlotIndex
    if (activeEdit != null && activeEdit in config.quickMenuSlots.indices) {
        UnifiedActionPickerSheet(
            currentAction = config.quickMenuSlots[activeEdit].action,
            allowNone = false,
            allowOpenQuickMenu = false,
            config = config,
            onSelect = { action, label ->
                val mutable = config.quickMenuSlots.toMutableList()
                mutable[activeEdit] = QuickSlotConfig(action = action, customLabel = label)
                onConfigChange(config.copy(quickMenuSlots = mutable))
                editingSlotIndex = null
                if (shouldShowNoShizukuFloatNotice(context) &&
                    (action.type == EdgeActionType.FLOAT_CURRENT_APP ||
                        (action.type == EdgeActionType.LAUNCH_APP_FLOATING && !config.hasSavedCcFloatTileCoords()))
                ) {
                    showNoShizukuFloatSetup = true
                }
            },
            onDismiss = { editingSlotIndex = null }
        )
    } else if (addingNewSlot && config.quickMenuSlots.size < EdgeConfig.MAX_QUICK_MENU_SLOTS) {
        UnifiedActionPickerSheet(
            currentAction = EdgeAction.NONE,
            allowNone = false,
            allowOpenQuickMenu = false,
            config = config,
            onSelect = { action, label ->
                val mutable = config.quickMenuSlots.toMutableList()
                if (mutable.size < EdgeConfig.MAX_QUICK_MENU_SLOTS) {
                    mutable.add(QuickSlotConfig(action = action, customLabel = label))
                    onConfigChange(config.copy(quickMenuSlots = mutable))
                }
                addingNewSlot = false
                if (shouldShowNoShizukuFloatNotice(context) &&
                    (action.type == EdgeActionType.FLOAT_CURRENT_APP ||
                        (action.type == EdgeActionType.LAUNCH_APP_FLOATING && !config.hasSavedCcFloatTileCoords()))
                ) {
                    showNoShizukuFloatSetup = true
                }
            },
            onDismiss = { addingNewSlot = false }
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

@Composable
private fun QuickArcTriggerMethodSection(
    config: EdgeConfig,
    onConfigChange: (EdgeConfig) -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    var demoMethod by remember { mutableStateOf<QuickArcTriggerMethod?>(null) }

    SectionCard(title = stringResource(R.string.arc_section_trigger_method)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TriggerMethodOptionCard(
                selected = config.quickArcTriggerMethod == QuickArcTriggerMethod.DEDICATED_ZONE,
                icon = HinataIcons.ViewSidebar,
                title = stringResource(R.string.arc_method_dedicated_title),
                badge = stringResource(R.string.arc_method_dedicated_badge),
                description = stringResource(R.string.arc_method_dedicated_desc),
                accentDedicated = true,
                testTag = "arc_method_DEDICATED_ZONE",
                demoButtonTestTag = "arc_demo_DEDICATED_ZONE",
                onClick = {
                    onConfigChange(config.copy(quickArcTriggerMethod = QuickArcTriggerMethod.DEDICATED_ZONE))
                },
                onWatchDemo = { demoMethod = QuickArcTriggerMethod.DEDICATED_ZONE }
            )
            TriggerMethodOptionCard(
                selected = config.quickArcTriggerMethod == QuickArcTriggerMethod.EDGE_STATIONARY_HOLD,
                icon = HinataIcons.TouchApp,
                title = stringResource(R.string.arc_method_edge_hold_title),
                badge = stringResource(R.string.arc_method_edge_hold_badge),
                description = stringResource(R.string.arc_method_edge_hold_desc),
                accentDedicated = false,
                testTag = "arc_method_EDGE_STATIONARY_HOLD",
                demoButtonTestTag = "arc_demo_EDGE_STATIONARY_HOLD",
                onClick = {
                    onConfigChange(config.copy(quickArcTriggerMethod = QuickArcTriggerMethod.EDGE_STATIONARY_HOLD))
                },
                onWatchDemo = { demoMethod = QuickArcTriggerMethod.EDGE_STATIONARY_HOLD }
            )
            TriggerMethodOptionCard(
                selected = config.quickArcTriggerMethod == QuickArcTriggerMethod.PULL_AND_RETURN,
                icon = HinataIcons.UTurnLeft,
                title = stringResource(R.string.arc_method_pull_return_title),
                badge = stringResource(R.string.arc_method_pull_return_badge),
                description = stringResource(R.string.arc_method_pull_return_desc),
                accentDedicated = false,
                testTag = "arc_method_PULL_AND_RETURN",
                demoButtonTestTag = "arc_demo_PULL_AND_RETURN",
                onClick = {
                    onConfigChange(config.copy(quickArcTriggerMethod = QuickArcTriggerMethod.PULL_AND_RETURN))
                },
                onWatchDemo = { demoMethod = QuickArcTriggerMethod.PULL_AND_RETURN }
            )
            TriggerMethodOptionCard(
                selected = config.quickArcTriggerMethod == QuickArcTriggerMethod.GESTURE_ACTION,
                icon = HinataIcons.Gesture,
                title = stringResource(R.string.arc_method_gesture_title),
                badge = stringResource(R.string.arc_method_gesture_badge),
                description = stringResource(R.string.arc_method_gesture_desc),
                accentDedicated = false,
                testTag = "arc_method_GESTURE_ACTION",
                demoButtonTestTag = "arc_demo_GESTURE_ACTION",
                onClick = {
                    onConfigChange(config.copy(quickArcTriggerMethod = QuickArcTriggerMethod.GESTURE_ACTION))
                },
                onWatchDemo = { demoMethod = QuickArcTriggerMethod.GESTURE_ACTION }
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = extendedColors.cardBorder)
        Spacer(Modifier.height(14.dp))

        when (config.quickArcTriggerMethod) {
            QuickArcTriggerMethod.DEDICATED_ZONE -> {
                DedicatedZoneControls(config = config, onConfigChange = onConfigChange)
            }

            QuickArcTriggerMethod.EDGE_STATIONARY_HOLD -> {
                MetricSlider(
                    label = stringResource(R.string.arc_edge_hold_ms_label),
                    valueText = "${config.quickArcEdgeHoldMs} ms",
                    value = config.quickArcEdgeHoldMs.toFloat(),
                    range = 200f..650f,
                    testTag = "slider_arc_edge_hold_ms",
                    onValueChange = { raw ->
                        val snapped = ((raw / 25f).roundToInt() * 25).coerceIn(200, 650)
                        onConfigChange(config.copy(quickArcEdgeHoldMs = snapped))
                    }
                )
            }

            QuickArcTriggerMethod.PULL_AND_RETURN -> {
                PullAndReturnGuideRow()
            }

            QuickArcTriggerMethod.GESTURE_ACTION -> {
                QuickArcTriggerStatusBanner(
                    config = config,
                    onAssignDefault = {
                        onConfigChange(
                            config.copy(
                                horizontalHoldAction = EdgeAction.OPEN_QUICK_MENU,
                                sideTriggerMode = if (config.sideTriggerMode == SecondaryTriggerMode.HOLD) {
                                    SecondaryTriggerMode.BOTH
                                } else {
                                    config.sideTriggerMode
                                }
                            )
                        )
                    }
                )
            }
        }
    }

    val activeDemo = demoMethod
    if (activeDemo != null) {
        QuickArcTriggerDemoSheet(
            method = activeDemo,
            config = config,
            onDismiss = { demoMethod = null }
        )
    }
}

@Composable
private fun TriggerMethodOptionCard(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    badge: String,
    description: String,
    accentDedicated: Boolean,
    testTag: String,
    demoButtonTestTag: String,
    onClick: () -> Unit,
    onWatchDemo: () -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    val activeAccent = if (accentDedicated) extendedColors.dedicatedZoneAccent else MaterialTheme.colorScheme.primary
    val activeContainer = if (accentDedicated) {
        extendedColors.dedicatedZoneContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) activeContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = if (selected) {
            BorderStroke(1.dp, activeAccent.copy(alpha = 0.60f))
        } else {
            BorderStroke(1.dp, extendedColors.cardBorder)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) activeAccent.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surface
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) activeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (selected) activeAccent.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) activeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onWatchDemo)
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                        .testTag(demoButtonTestTag),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = HinataIcons.PlayCircleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.button_watch_animation),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (selected) HinataIcons.RadioButtonChecked else HinataIcons.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) activeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DedicatedZoneControls(
    config: EdgeConfig,
    onConfigChange: (EdgeConfig) -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.arc_zone_side_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        val sideOptions = listOf(
            QuickArcZoneSide.BOTH to R.string.arc_zone_side_both,
            QuickArcZoneSide.LEFT_ONLY to R.string.arc_zone_side_left,
            QuickArcZoneSide.RIGHT_ONLY to R.string.arc_zone_side_right
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            sideOptions.forEachIndexed { idx, (side, labelRes) ->
                SegmentedButton(
                    selected = config.quickArcZoneSide == side,
                    onClick = { onConfigChange(config.copy(quickArcZoneSide = side)) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = idx,
                        count = sideOptions.size
                    ),
                    icon = {},
                    modifier = Modifier.testTag("arc_zone_side_${side.name}")
                ) {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Compact edge placement mini-diagram comparing Main Edge vs Dedicated Quick Menu Area
        DedicatedZonePlacementDiagram(config = config)

        MetricSlider(
            label = stringResource(R.string.arc_zone_height_label),
            valueText = "${(config.quickArcZoneHeightPercent * 100).roundToInt()}%",
            value = config.quickArcZoneHeightPercent,
            range = 0.08f..0.40f,
            testTag = "slider_arc_zone_height",
            onValueChange = { h ->
                val maxOffset = (1f - h).coerceAtLeast(0f)
                onConfigChange(
                    config.copy(
                        quickArcZoneHeightPercent = h,
                        quickArcZoneBottomOffsetPercent = config.quickArcZoneBottomOffsetPercent.coerceAtMost(maxOffset)
                    )
                )
            }
        )

        val maxOffset = (1f - config.quickArcZoneHeightPercent).coerceAtLeast(0.05f)
        MetricSlider(
            label = stringResource(R.string.arc_zone_offset_label),
            valueText = "${(config.quickArcZoneBottomOffsetPercent * 100).roundToInt()}%",
            value = config.quickArcZoneBottomOffsetPercent.coerceIn(0f, maxOffset),
            range = 0f..maxOffset,
            testTag = "slider_arc_zone_offset",
            onValueChange = { offset ->
                onConfigChange(config.copy(quickArcZoneBottomOffsetPercent = offset))
            }
        )

        val mainStart = config.edgeBottomOffsetPercent
        val mainEnd = mainStart + config.edgeHeightPercent
        val arcStart = config.quickArcZoneBottomOffsetPercent
        val arcEnd = arcStart + config.quickArcZoneHeightPercent
        val overlaps = arcStart < mainEnd - 0.005f && mainStart < arcEnd - 0.005f
        if (overlaps) {
            Text(
                text = stringResource(R.string.arc_zone_overlap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = extendedColors.statusInactive
            )
        }
    }
}

@Composable
private fun DedicatedZonePlacementDiagram(config: EdgeConfig) {
    val extendedColors = LocalEdgeFlowColors.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val dedicatedAccent = extendedColors.dedicatedZoneAccent
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = extendedColors.cardBorder

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendDot(color = primaryColor, label = stringResource(R.string.arc_zone_legend_main))
                LegendDot(color = dedicatedAccent, label = stringResource(R.string.arc_zone_legend_arc))
            }
            Text(
                text = stringResource(
                    R.string.menu_zone_range_format,
                    (config.quickArcZoneBottomOffsetPercent * 100).roundToInt(),
                    ((config.quickArcZoneBottomOffsetPercent + config.quickArcZoneHeightPercent) * 100).roundToInt()
                ),
                style = NumericSmallStyle,
                color = dedicatedAccent,
                maxLines = 1,
                softWrap = false
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clip(RoundedCornerShape(10.dp))
        ) {
            val w = size.width
            val h = size.height
            drawRoundRect(
                color = trackColor,
                size = size,
                cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
            )
            drawRoundRect(
                color = borderColor,
                size = size,
                cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )
            val mainX = w * config.edgeBottomOffsetPercent
            val mainW = (w * config.edgeHeightPercent).coerceAtLeast(6f)
            drawRoundRect(
                color = primaryColor.copy(alpha = 0.42f),
                topLeft = Offset(mainX, 3.dp.toPx()),
                size = Size(mainW.coerceAtMost(w - mainX), h - 6.dp.toPx()),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )
            val arcX = w * config.quickArcZoneBottomOffsetPercent
            val arcW = (w * config.quickArcZoneHeightPercent).coerceAtLeast(6f)
            drawRoundRect(
                color = dedicatedAccent.copy(alpha = 0.80f),
                topLeft = Offset(arcX, 2.dp.toPx()),
                size = Size(arcW.coerceAtMost(w - arcX), h - 4.dp.toPx()),
                cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.menu_zone_bottom_label),
                style = NumericSmallStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.menu_zone_top_label),
                style = NumericSmallStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun PullAndReturnGuideRow() {
    val extendedColors = LocalEdgeFlowColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val steps = listOf(
            R.string.arc_pull_return_step1,
            R.string.arc_pull_return_step2,
            R.string.arc_pull_return_step3
        )
        steps.forEach { stepRes ->
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, extendedColors.cardBorder)
            ) {
                Text(
                    text = stringResource(stepRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun FreeHorizontalStage2Banner(
    onSwitchToPreviousApp: () -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("arc_free_stage2_banner"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, extendedColors.cardBorder)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HinataIcons.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.arc_free_long_swipe_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.arc_free_long_swipe_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onSwitchToPreviousApp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("arc_free_stage2_button"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Text(
                    text = stringResource(R.string.arc_free_long_swipe_button),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun QuickArcTriggerStatusBanner(
    config: EdgeConfig,
    onAssignDefault: () -> Unit
) {
    val context = LocalContext.current
    val extendedColors = LocalEdgeFlowColors.current

    val assignedGestures = remember(config, context.resources.configuration) {
        buildList {
            if (config.horizontalAction.type == EdgeActionType.OPEN_QUICK_MENU) {
                add(context.getString(R.string.arc_assigned_side_short))
            }
            if (config.horizontalHoldAction.type == EdgeActionType.OPEN_QUICK_MENU) {
                val pct = (config.sideStage2DistanceRatio * 100).roundToInt()
                val label = when (config.sideTriggerMode) {
                    SecondaryTriggerMode.DISTANCE -> context.getString(R.string.arc_assigned_side_deep_distance, pct)
                    SecondaryTriggerMode.HOLD -> context.getString(R.string.arc_assigned_side_deep_hold, config.sideStage2HoldMs)
                    SecondaryTriggerMode.BOTH -> context.getString(R.string.arc_assigned_side_deep_both, pct, config.sideStage2HoldMs)
                }
                add(label)
            }
            if (config.leftStage3Action.type == EdgeActionType.OPEN_QUICK_MENU) {
                add(context.getString(R.string.gesture_left_stage3))
            }
            if (config.rightStage3Action.type == EdgeActionType.OPEN_QUICK_MENU) {
                add(context.getString(R.string.gesture_right_stage3))
            }
            if (config.diagonalUpAction.type == EdgeActionType.OPEN_QUICK_MENU) {
                add(context.getString(R.string.arc_assigned_diag_up))
            }
            if (config.diagonalDownAction.type == EdgeActionType.OPEN_QUICK_MENU) {
                add(context.getString(R.string.arc_assigned_diag_down))
            }
            if (config.bottomSwipeUpAction.type == EdgeActionType.OPEN_QUICK_MENU) {
                add(context.getString(R.string.arc_assigned_bottom_up))
            }
            if (config.bottomSwipeHoldAction.type == EdgeActionType.OPEN_QUICK_MENU) {
                add(context.getString(R.string.arc_assigned_bottom_deep))
            }
            if (config.bottomStage3Action.type == EdgeActionType.OPEN_QUICK_MENU) {
                add(context.getString(R.string.gesture_bottom_stage3))
            }
            if (config.bottomLongPressEnabled && config.bottomLongPressAction.type == EdgeActionType.OPEN_QUICK_MENU) {
                add(context.getString(R.string.arc_assigned_bottom_hold))
            }
        }
    }

    val isAssigned = assignedGestures.isNotEmpty()
    val statusColor = if (isAssigned) extendedColors.statusActive else extendedColors.statusInactive
    val statusContainer = if (isAssigned) {
        extendedColors.statusActiveContainer
    } else {
        extendedColors.statusInactiveContainer
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("arc_trigger_status_banner"),
        shape = RoundedCornerShape(14.dp),
        color = statusContainer
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isAssigned) Icons.Rounded.CheckCircle else HinataIcons.WarningAmber,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (isAssigned) R.string.arc_trigger_active_title
                        else R.string.arc_trigger_unassigned_title
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = statusColor
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isAssigned) {
                    assignedGestures.joinToString(" · ")
                } else {
                    stringResource(R.string.arc_trigger_unassigned_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!isAssigned) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onAssignDefault,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("arc_quick_assign_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.arc_trigger_assign_button),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickArcPreviewCanvas(
    config: EdgeConfig,
    slotLabelResolver: (QuickSlotConfig) -> String
) {
    val extendedColors = LocalEdgeFlowColors.current
    var previewSide by remember { mutableStateOf(EdgeSide.LEFT) }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var scrollIndex by remember { mutableFloatStateOf(0f) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceBg = MaterialTheme.colorScheme.background
    val glassFillBase = when (config.visualTheme) {
        VisualTheme.LIGHT_GLASS -> Color(0xFFF4F6FA)
        else -> Color(0xFF14161A)
    }
    val rimColor = when (config.visualTheme) {
        VisualTheme.LIGHT_GLASS -> Color(0xFF15171A)
        VisualTheme.MATERIAL_YOU -> primaryColor
        else -> Color.White
    }
    val slots = config.quickMenuSlots
    val visibleWindow = 6
    val maxScroll = (slots.size - visibleWindow).coerceAtLeast(0).toFloat()
    val clampedScroll = scrollIndex.coerceIn(0f, maxScroll)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(216.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(surfaceBg)
                .pointerInput(slots, config.quickMenuRadiusDp) {
                    detectDragGestures(
                        onDragStart = { start ->
                            previewSide = if (start.x > size.width * 0.5f) EdgeSide.RIGHT else EdgeSide.LEFT
                        },
                        onDragEnd = { focusedIndex = -1 },
                        onDragCancel = { focusedIndex = -1 },
                        onDrag = { change, _ ->
                            change.consume()
                            val originX = if (previewSide == EdgeSide.LEFT) 0f else size.width.toFloat()
                            val origin = Offset(originX, size.height * 0.52f)
                            val dx = change.position.x - origin.x
                            val dy = change.position.y - origin.y
                            val inwardDx = if (previewSide == EdgeSide.LEFT) dx else -dx
                            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                            if (dist < size.width * 0.13f || inwardDx < 12f || slots.isEmpty()) {
                                focusedIndex = -1
                            } else {
                                val angleRad = atan2(dy.toDouble(), inwardDx.toDouble()).toFloat()
                                val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
                                val isRotary = slots.size > visibleWindow
                                if (isRotary) {
                                    val rotaryStep = 0.18f * config.quickMenuRotarySpeed.coerceIn(0.5f, 2.0f)
                                    if (angleDeg < -52f) {
                                        scrollIndex = (scrollIndex - rotaryStep).coerceIn(0f, maxScroll)
                                    } else if (angleDeg > 52f) {
                                        scrollIndex = (scrollIndex + rotaryStep).coerceIn(0f, maxScroll)
                                    }
                                    val sweepDeg = 136f
                                    val startDeg = -sweepDeg / 2f
                                    val stepDeg = sweepDeg / (visibleWindow - 1)
                                    val exactIdx = ((angleDeg - startDeg) / stepDeg + scrollIndex)
                                        .roundToInt()
                                        .coerceIn(0, slots.lastIndex)
                                    focusedIndex = exactIdx
                                } else {
                                    val sweepDeg = when {
                                        slots.size <= 3 -> 104f
                                        slots.size == 4 -> 118f
                                        slots.size == 5 -> 128f
                                        else -> 136f
                                    }
                                    val startDeg = -sweepDeg / 2f
                                    val stepDeg = if (slots.size > 1) sweepDeg / (slots.size - 1) else 0f
                                    var best = -1
                                    var bestDiff = Float.MAX_VALUE
                                    for (i in slots.indices) {
                                        val rad = ((startDeg + stepDeg * i) * PI / 180.0).toFloat()
                                        val diff = abs(angleRad - rad)
                                        if (diff < bestDiff) {
                                            bestDiff = diff
                                            best = i
                                        }
                                    }
                                    focusedIndex = best
                                }
                            }
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                drawRect(color = Color.Black.copy(alpha = config.quickMenuDimAlpha))

                val dirSign = if (previewSide == EdgeSide.LEFT) 1f else -1f
                val origin = Offset(if (previewSide == EdgeSide.LEFT) 0f else w, h * 0.52f)
                val previewRadius = ((config.quickMenuRadiusDp / 156f) * (h * 0.40f))
                    .coerceIn(h * 0.24f, h * 0.42f)
                val deadZoneScale = (config.quickMenuDeadZoneDp / 36f).coerceIn(0.56f, 1.78f)
                val deadZoneR = (w * 0.12f * deadZoneScale).coerceIn(w * 0.065f, previewRadius * 0.72f)
                drawCircle(
                    color = rimColor.copy(alpha = if (focusedIndex < 0) 0.32f else 0.15f),
                    radius = deadZoneR,
                    center = origin,
                    style = Stroke(width = 1.dp.toPx())
                )

                val isRotary = slots.size > visibleWindow
                val sweepDeg = when {
                    isRotary -> 136f
                    slots.size <= 3 -> 104f
                    slots.size == 4 -> 118f
                    slots.size == 5 -> 128f
                    else -> 136f
                }
                val startDeg = -sweepDeg / 2f
                val stepDeg = if (isRotary) {
                    sweepDeg / (visibleWindow - 1)
                } else if (slots.size > 1) {
                    sweepDeg / (slots.size - 1)
                } else {
                    0f
                }
                val baseSlotR = 17.dp.toPx()

                // Outer rotary scrollbar track & thumb when >= 7 slots
                if (isRotary) {
                    val trackR = previewRadius + 24.dp.toPx()
                    val trackTopLeft = Offset(origin.x - trackR, origin.y - trackR)
                    val trackSize = Size(trackR * 2f, trackR * 2f)
                    val arcStart = if (previewSide == EdgeSide.LEFT) -62f else 118f
                    val arcSweep = 124f
                    drawArc(
                        color = rimColor.copy(alpha = 0.22f),
                        startAngle = arcStart,
                        sweepAngle = arcSweep,
                        useCenter = false,
                        topLeft = trackTopLeft,
                        size = trackSize,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                    val thumbRatio = visibleWindow.toFloat() / slots.size.toFloat()
                    val thumbSweep = arcSweep * thumbRatio
                    val scrollFrac = if (maxScroll > 0.001f) clampedScroll / maxScroll else 0f
                    val thumbStart = if (previewSide == EdgeSide.LEFT) {
                        arcStart + (arcSweep - thumbSweep) * scrollFrac
                    } else {
                        (arcStart + arcSweep - thumbSweep) - (arcSweep - thumbSweep) * scrollFrac
                    }
                    drawArc(
                        color = primaryColor.copy(alpha = 0.85f),
                        startAngle = thumbStart,
                        sweepAngle = thumbSweep,
                        useCenter = false,
                        topLeft = trackTopLeft,
                        size = trackSize,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                for (i in slots.indices) {
                    val deg = if (isRotary) {
                        startDeg + (i - clampedScroll) * stepDeg
                    } else {
                        startDeg + stepDeg * i
                    }
                    if (isRotary && (deg < -82f || deg > 82f)) continue
                    val edgeFade = if (isRotary && abs(deg) > 64f) {
                        (1f - (abs(deg) - 64f) / 18f).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    if (edgeFade <= 0.02f) continue

                    val rad = (deg * PI / 180.0).toFloat()
                    val cx = origin.x + dirSign * cos(rad) * previewRadius
                    val cy = origin.y + sin(rad) * previewRadius
                    val isFocused = i == focusedIndex
                    val r = (if (isFocused) baseSlotR * 1.24f else baseSlotR) * (0.72f + 0.28f * edgeFade)
                    val fillAlpha = ((config.feedbackAlpha.coerceAtLeast(0.24f) +
                        if (isFocused) 0.18f else 0f) * edgeFade).coerceIn(0f, 0.94f)

                    if (isFocused) {
                        drawLine(
                            color = primaryColor.copy(alpha = 0.40f * edgeFade),
                            start = origin,
                            end = Offset(cx, cy),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    drawCircle(
                        color = glassFillBase.copy(alpha = fillAlpha),
                        radius = r,
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = rimColor.copy(alpha = (if (isFocused) 0.40f else 0.20f) * edgeFade),
                        radius = r,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.dp.toPx())
                    )
                    if (isFocused) {
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.72f * edgeFade),
                            radius = r + 3.dp.toPx(),
                            center = Offset(cx, cy),
                            style = Stroke(width = 1.6.dp.toPx())
                        )
                    }
                    drawCircle(
                        color = (if (isFocused) primaryColor else Color.White.copy(alpha = 0.85f))
                            .copy(alpha = edgeFade),
                        radius = r * 0.26f,
                        center = Offset(cx, cy)
                    )
                }
            }

            // Clean readout pill on the opposite side
            Surface(
                modifier = Modifier
                    .align(if (previewSide == EdgeSide.LEFT) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 14.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                border = if (focusedIndex >= 0) {
                    BorderStroke(1.dp, primaryColor.copy(alpha = 0.55f))
                } else {
                    BorderStroke(1.dp, extendedColors.cardBorder)
                }
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = if (previewSide == EdgeSide.LEFT) Alignment.End else Alignment.Start
                ) {
                    Text(
                        text = if (focusedIndex in slots.indices) {
                            slotLabelResolver(slots[focusedIndex])
                        } else {
                            stringResource(R.string.arc_deadzone_cancel)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (focusedIndex >= 0) primaryColor
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.menu_preview_metrics,
                            config.quickMenuRadiusDp.roundToInt(),
                            (config.quickMenuDimAlpha * 100).roundToInt()
                        ),
                        style = NumericSmallStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.arc_preview_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
