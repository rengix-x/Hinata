package com.edgeflow.settings

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import com.example.ui.icons.HinataIcons
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edgeflow.data.ConfigStore
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.RuntimeStatus
import com.edgeflow.service.ActionExecutor
import com.edgeflow.service.CustomMediaLoader
import com.edgeflow.service.EdgeAccessibilityService
import com.edgeflow.service.RecentAppsTracker
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import com.edgeflow.settings.translate.TranslateSettingsScreen
import com.example.R
import com.example.ui.theme.EdgeFlowTheme
import com.example.ui.theme.LocalEdgeFlowColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val config: EdgeConfig = EdgeConfig(),
    val isAccessibilityEnabled: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false,
    val hasUsageStatsPermission: Boolean = false,
    val runtimeStatus: RuntimeStatus = RuntimeStatus(),
    val appearancePrefs: SettingsAppearancePrefs = SettingsAppearancePrefs()
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private var configObserver: ContentObserver? = null

    init {
        refreshAll()
        configObserver = runCatching {
            ConfigStore.registerObserver(appContext) { updated ->
                _uiState.update { it.copy(config = updated) }
            }
        }.getOrNull()
    }

    override fun onCleared() {
        configObserver?.let { ConfigStore.unregisterObserver(appContext, it) }
        configObserver = null
        super.onCleared()
    }

    fun refreshAll() {
        val loaded = ConfigStore.load(appContext)
        val runtime = ConfigStore.queryRuntimeStatus(appContext)
        val accessibilityEnabled = isServiceEnabledInSystem(appContext) || runtime.serviceActive
        if (accessibilityEnabled) {
            SettingsUiPreferences.saveHasEverEnabledAccessibility(appContext, true)
        }
        val ignoringBattery = isBatteryUnrestricted(appContext)
        val usageStatsGranted = RecentAppsTracker.hasUsageStatsPermission(appContext)
        val appearance = SettingsUiPreferences.load(appContext)

        _uiState.update {
            it.copy(
                config = loaded,
                isAccessibilityEnabled = accessibilityEnabled,
                isIgnoringBatteryOptimizations = ignoringBattery,
                hasUsageStatsPermission = usageStatsGranted,
                runtimeStatus = runtime,
                appearancePrefs = appearance
            )
        }
    }

    fun updateConfig(newConfig: EdgeConfig) {
        val clamped = newConfig.clamped()
        _uiState.update { it.copy(config = clamped) }
        ConfigStore.save(appContext, clamped)
    }

    fun toggleBlacklistedPackage(packageName: String) {
        val current = _uiState.value.config.blacklistedPackages
        val updated = if (current.contains(packageName)) {
            current - packageName
        } else {
            current + packageName
        }
        updateConfig(_uiState.value.config.copy(blacklistedPackages = updated))
    }

    fun resetDefaults() {
        CustomMediaLoader.deleteCustomMedia(appContext, _uiState.value.config.customMediaPath)
        updateConfig(EdgeConfig(appLanguageTag = _uiState.value.config.appLanguageTag))
    }

    fun updateThemeMode(mode: AppThemeMode) {
        SettingsUiPreferences.saveThemeMode(appContext, mode)
        _uiState.update { it.copy(appearancePrefs = it.appearancePrefs.copy(themeMode = mode)) }
    }

    fun updateDynamicColor(enabled: Boolean) {
        SettingsUiPreferences.saveDynamicColor(appContext, enabled)
        _uiState.update { it.copy(appearancePrefs = it.appearancePrefs.copy(dynamicColorEnabled = enabled)) }
    }

    fun selectLanguage(activity: Activity, option: AppLanguageOption) {
        _uiState.update { it.copy(appearancePrefs = it.appearancePrefs.copy(language = option)) }
        // Sync selected language tag into ConfigStore so the resident AccessibilityService updates immediately
        updateConfig(_uiState.value.config.copy(appLanguageTag = option.tag))
        SettingsUiPreferences.applyLanguage(activity, option)
    }

    fun completeOnboarding() {
        SettingsUiPreferences.saveOnboardingCompleted(appContext, true)
        _uiState.update {
            it.copy(appearancePrefs = it.appearancePrefs.copy(hasCompletedOnboarding = true))
        }
    }

    private fun isServiceEnabledInSystem(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val expectedComponent = ComponentName(context, EdgeAccessibilityService::class.java)
        val flattened = expectedComponent.flattenToString()
        val shortFlattened = expectedComponent.flattenToShortString()
        return enabledServices.split(':').any { entry ->
            entry.equals(flattened, ignoreCase = true) ||
                entry.equals(shortFlattened, ignoreCase = true)
        }
    }

    private fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}

private enum class SettingsTab(
    val titleRes: Int,
    val icon: ImageVector,
    val testTag: String
) {
    ZONES(R.string.tab_zones, HinataIcons.Gesture, "nav_tab_zones"),
    ARC(R.string.tab_arc, HinataIcons.Radar, "nav_tab_arc"),
    ACTIONS(R.string.tab_actions, HinataIcons.TouchApp, "nav_tab_actions"),
    BLACKLIST(R.string.tab_blacklist, HinataIcons.Apps, "nav_tab_blacklist"),
    GUIDE(R.string.tab_guide, HinataIcons.Shield, "nav_tab_guide")
}

/**
 * Material 3 Settings host Activity running strictly inside the isolated `:settings` process
 * (`android:process=":settings"` in AndroidManifest.xml). Closing this Activity allows Android
 * to reclaim all Compose & UI memory while the resident gesture service remains lightweight.
 */
class SettingsActivity : ComponentActivity() {

    private var openTranslateRequestToken by mutableStateOf(0)
    private var openSystemTabRequestToken by mutableStateOf(0)
    private var autoReviveRefreshToken by mutableStateOf(0)
    private var lastAutoReviveAttemptElapsedMs: Long = Long.MIN_VALUE
    private var purgeReceiverRegistered: Boolean = false

    private val recentsPurgeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ActionExecutor.ACTION_PURGE_SETTINGS_TASK_FROM_RECENTS) {
                finishAndPurgeFromRecents()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(SettingsUiPreferences.wrapContextForLegacyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        excludeFromRecentsNow()
        registerRecentsPurgeReceiver()
        ShizukuShellExecutor.initMultiProcess(this, isMainProcess = false)
        consumeDeepLinkIntent(intent)

        // Apply initial contrast-safe edge-to-edge system bar style before first frame
        val initialPrefs = SettingsUiPreferences.load(this)
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val initialDark = when (initialPrefs.themeMode) {
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.SYSTEM -> systemDark
        }
        applySystemBarContrast(initialDark)

        setContent {
            val viewModel: SettingsViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val isSystemDark = isSystemInDarkTheme()
            val resolvedDarkTheme = when (uiState.appearancePrefs.themeMode) {
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
                AppThemeMode.SYSTEM -> isSystemDark
            }

            LaunchedEffect(resolvedDarkTheme) {
                applySystemBarContrast(resolvedDarkTheme)
            }

            EdgeFlowTheme(
                darkTheme = resolvedDarkTheme,
                dynamicColor = uiState.appearancePrefs.dynamicColorEnabled
            ) {
                EdgeFlowSettingsRoot(
                    viewModel = viewModel,
                    uiState = uiState,
                    openTranslateRequestToken = openTranslateRequestToken,
                    openSystemTabRequestToken = openSystemTabRequestToken,
                    autoReviveRefreshToken = autoReviveRefreshToken,
                    onSelectLanguage = { option ->
                        viewModel.selectLanguage(this, option)
                    },
                    onFinishAndRemoveTask = {
                        finishAndPurgeFromRecents()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isWaitingForChildActivityResult = false
        keepAliveForExternalSettingsOnce = false
        excludeFromRecentsNow()
        ShizukuShellExecutor.refreshNonProviderBinder(this)
        maybeAutoReviveAccessibilityService()
    }

    override fun onStop() {
        super.onStop()
        excludeFromRecentsNow()
        val keepOnce = keepAliveForExternalSettingsOnce
        if (keepOnce) {
            keepAliveForExternalSettingsOnce = false
        }
        if (!isChangingConfigurations && !isWaitingForChildActivityResult && !keepOnce) {
            finishAndPurgeFromRecents()
        }
    }

    override fun onDestroy() {
        unregisterRecentsPurgeReceiver()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDeepLinkIntent(intent)
    }

    private fun registerRecentsPurgeReceiver() {
        if (purgeReceiverRegistered) return
        val filter = IntentFilter(ActionExecutor.ACTION_PURGE_SETTINGS_TASK_FROM_RECENTS)
        runCatching {
            ContextCompat.registerReceiver(
                this,
                recentsPurgeReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            purgeReceiverRegistered = true
        }
    }

    private fun unregisterRecentsPurgeReceiver() {
        if (!purgeReceiverRegistered) return
        purgeReceiverRegistered = false
        runCatching { unregisterReceiver(recentsPurgeReceiver) }
    }

    /**
     * Dynamically excludes every AppTask belonging to this process from Recents so OEM
     * "Clear All" swipe actions can never collateral-kill Hinata's resident AccessibilityService.
     */
    private fun excludeFromRecentsNow() {
        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            am.appTasks?.forEach { task ->
                runCatching { task.setExcludeFromRecents(true) }
            }
        }
    }

    /**
     * Completely purges this Settings task from the OS Recents list immediately.
     */
    internal fun finishAndPurgeFromRecents() {
        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.appTasks?.forEach { task ->
                runCatching { task.setExcludeFromRecents(true) }
                runCatching { task.finishAndRemoveTask() }
            }
        }
        runCatching { finishAndRemoveTask() }
    }

    /**
     * Seamlessly auto-revives Hinata's resident AccessibilityService on resume if the user had
     * previously enabled it, opted into `autoReviveWithShizuku`, Shizuku is connected, and the
     * resident process reports `serviceActive == false` (e.g., right after an OEM background kill).
     */
    private fun maybeAutoReviveAccessibilityService() {
        val now = SystemClock.elapsedRealtime()
        if (lastAutoReviveAttemptElapsedMs != Long.MIN_VALUE &&
            (now - lastAutoReviveAttemptElapsedMs) < AUTO_REVIVE_COOLDOWN_MS
        ) {
            return
        }
        val appCtx = applicationContext
        if (!SettingsUiPreferences.hasEverEnabledAccessibility(appCtx)) return

        Thread({
            val config = ConfigStore.load(appCtx)
            if (!config.autoReviveWithShizuku) return@Thread
            val runtime = ConfigStore.queryRuntimeStatus(appCtx)
            if (runtime.serviceActive) return@Thread
            if (!ShizukuShellExecutor.isReady()) return@Thread

            lastAutoReviveAttemptElapsedMs = SystemClock.elapsedRealtime()
            val result = ShizukuShellExecutor.reviveAccessibilityServiceWithShizuku(
                context = appCtx,
                forceToggleRebind = true
            )
            if (result.success) {
                runCatching { Thread.sleep(AUTO_REVIVE_SETTLE_MS) }
                runOnUiThread {
                    if (!isDestroyed) {
                        autoReviveRefreshToken++
                        runCatching {
                            Toast.makeText(
                                this@SettingsActivity,
                                getString(R.string.recovery_toast_revive_success),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }, "hinata-settings-auto-revive").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Safely inspects incoming Intent extras. Only non-privileged UI navigation flags
     * ([EXTRA_OPEN_TRANSLATE_SETTINGS] and [EXTRA_OPEN_SYSTEM_TAB]) are read (wrapped against
     * malformed/untrusted Parcelable extras); external Intents can never trigger ConfigStore
     * writes, permission changes, or Shizuku command execution.
     */
    private fun consumeDeepLinkIntent(intent: Intent?) {
        val openTranslate = runCatching {
            intent?.getBooleanExtra(EXTRA_OPEN_TRANSLATE_SETTINGS, false) == true
        }.getOrDefault(false)
        if (openTranslate) {
            runCatching { intent?.removeExtra(EXTRA_OPEN_TRANSLATE_SETTINGS) }
            openTranslateRequestToken++
        }
        val openSystemTab = runCatching {
            intent?.getBooleanExtra(EXTRA_OPEN_SYSTEM_TAB, false) == true
        }.getOrDefault(false)
        if (openSystemTab) {
            runCatching { intent?.removeExtra(EXTRA_OPEN_SYSTEM_TAB) }
            openSystemTabRequestToken++
        }
    }

    private fun applySystemBarContrast(isDarkTheme: Boolean) {
        val barStyle = if (isDarkTheme) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        enableEdgeToEdge(
            statusBarStyle = barStyle,
            navigationBarStyle = barStyle
        )
    }

    companion object {
        const val EXTRA_OPEN_TRANSLATE_SETTINGS = "open_translate_settings"
        const val EXTRA_OPEN_SYSTEM_TAB = "open_system_tab"
        private const val AUTO_REVIVE_COOLDOWN_MS = 15_000L
        private const val AUTO_REVIVE_SETTLE_MS = 450L

        @Volatile
        var isWaitingForChildActivityResult: Boolean = false

        @Volatile
        var keepAliveForExternalSettingsOnce: Boolean = false
    }
}

@Composable
private fun EdgeFlowSettingsRoot(
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    openTranslateRequestToken: Int,
    openSystemTabRequestToken: Int,
    autoReviveRefreshToken: Int,
    onSelectLanguage: (AppLanguageOption) -> Unit,
    onFinishAndRemoveTask: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val extendedColors = LocalEdgeFlowColors.current
    var currentTab by rememberSaveable { mutableStateOf(SettingsTab.ZONES) }
    var showGeneralSettings by rememberSaveable { mutableStateOf(false) }
    var showTranslateSettings by rememberSaveable { mutableStateOf(false) }
    var replayingOnboarding by rememberSaveable { mutableStateOf(false) }
    var showABWizard by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(openTranslateRequestToken) {
        if (openTranslateRequestToken > 0) {
            showGeneralSettings = false
            replayingOnboarding = false
            showABWizard = false
            showTranslateSettings = true
        }
    }

    LaunchedEffect(openSystemTabRequestToken) {
        if (openSystemTabRequestToken > 0) {
            showGeneralSettings = false
            showTranslateSettings = false
            replayingOnboarding = false
            showABWizard = false
            currentTab = SettingsTab.GUIDE
        }
    }

    LaunchedEffect(autoReviveRefreshToken) {
        if (autoReviveRefreshToken > 0) {
            viewModel.refreshAll()
        }
    }

    // Refresh service & battery status whenever returning from system settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val showOnboarding = !uiState.appearancePrefs.hasCompletedOnboarding || replayingOnboarding
    if (showOnboarding) {
        OnboardingScreen(
            currentLanguage = uiState.appearancePrefs.language,
            isAccessibilityEnabled = uiState.isAccessibilityEnabled,
            isIgnoringBatteryOptimizations = uiState.isIgnoringBatteryOptimizations,
            isReplayMode = replayingOnboarding && uiState.appearancePrefs.hasCompletedOnboarding,
            onSelectLanguage = onSelectLanguage,
            onRefreshStatus = viewModel::refreshAll,
            onComplete = {
                replayingOnboarding = false
                viewModel.completeOnboarding()
            },
            onCompleteToABWizard = {
                replayingOnboarding = false
                viewModel.completeOnboarding()
                showABWizard = true
            }
        )
        return
    }

    if (!uiState.isAccessibilityEnabled) {
        AccessibilityRequiredGateScreen(
            config = uiState.config,
            onConfigChange = viewModel::updateConfig,
            onRefreshStatus = viewModel::refreshAll,
            onFinishAndRemoveTask = onFinishAndRemoveTask
        )
        return
    }

    if (showABWizard) {
        BeginnerABWizardScreen(
            currentConfig = uiState.config,
            onApplyConfig = { generated ->
                viewModel.updateConfig(generated)
                showABWizard = false
            },
            onCancel = { showABWizard = false }
        )
        return
    }

    val isSubScreenOpen = showGeneralSettings || showTranslateSettings
    BackHandler(enabled = !isSubScreenOpen && currentTab != SettingsTab.ZONES) {
        currentTab = SettingsTab.ZONES
    }
    BackHandler(enabled = !isSubScreenOpen && currentTab == SettingsTab.ZONES) {
        onFinishAndRemoveTask()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavigationRail && !isSubScreenOpen) {
                    Column {
                        HorizontalDivider(color = extendedColors.cardBorder)
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp
                        ) {
                            SettingsTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentTab == tab,
                                    onClick = { currentTab = tab },
                                    modifier = Modifier.testTag(tab.testTag),
                                    icon = {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = stringResource(tab.titleRes)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(tab.titleRes),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail && !isSubScreenOpen) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        SettingsTab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = currentTab == tab,
                                onClick = { currentTab = tab },
                                modifier = Modifier.testTag(tab.testTag),
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = stringResource(tab.titleRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(tab.titleRes),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 680.dp)
                    ) {
                        if (showTranslateSettings) {
                            TranslateSettingsScreen(
                                config = uiState.config,
                                onConfigChange = viewModel::updateConfig,
                                onBack = { showTranslateSettings = false }
                            )
                        } else if (showGeneralSettings) {
                            GeneralSettingsScreen(
                                appearancePrefs = uiState.appearancePrefs,
                                isAccessibilityEnabled = uiState.isAccessibilityEnabled,
                                hasUsageStatsPermission = uiState.hasUsageStatsPermission,
                                runtimeStatus = uiState.runtimeStatus,
                                onSelectLanguage = onSelectLanguage,
                                onSelectThemeMode = viewModel::updateThemeMode,
                                onToggleDynamicColor = viewModel::updateDynamicColor,
                                onOpenABWizard = {
                                    showGeneralSettings = false
                                    showABWizard = true
                                },
                                onOpenTranslateSettings = {
                                    showGeneralSettings = false
                                    showTranslateSettings = true
                                },
                                onRefreshStatus = viewModel::refreshAll,
                                onReplayOnboarding = {
                                    showGeneralSettings = false
                                    replayingOnboarding = true
                                },
                                onBack = { showGeneralSettings = false }
                            )
                        } else {
                            EdgeFlowHeaderBar(
                                isServiceActive = uiState.isAccessibilityEnabled,
                                onStatusClick = {
                                    if (uiState.isAccessibilityEnabled) {
                                        currentTab = SettingsTab.GUIDE
                                    } else {
                                        openAccessibilitySettings(context)
                                    }
                                },
                                onOpenSettings = { showGeneralSettings = true }
                            )

                            Box(modifier = Modifier.weight(1f)) {
                                when (currentTab) {
                                    SettingsTab.ZONES -> ZonesTuningScreen(
                                        config = uiState.config,
                                        onConfigChange = viewModel::updateConfig,
                                        onResetDefaults = viewModel::resetDefaults,
                                        snackbarHostState = snackbarHostState,
                                        onOpenABWizard = { showABWizard = true }
                                    )

                                    SettingsTab.ARC -> QuickArcSettingsScreen(
                                        config = uiState.config,
                                        onConfigChange = viewModel::updateConfig
                                    )

                                    SettingsTab.ACTIONS -> ActionsAndAvoidanceScreen(
                                        config = uiState.config,
                                        hasUsageStatsPermission = uiState.hasUsageStatsPermission,
                                        onConfigChange = viewModel::updateConfig,
                                        onOpenTranslateSettings = { showTranslateSettings = true }
                                    )

                                    SettingsTab.BLACKLIST -> AppBlacklistScreen(
                                        blacklistedPackages = uiState.config.blacklistedPackages,
                                        onTogglePackage = viewModel::toggleBlacklistedPackage
                                    )

                                    SettingsTab.GUIDE -> HyperOSGuideScreen(
                                        config = uiState.config,
                                        onConfigChange = viewModel::updateConfig,
                                        snackbarHostState = snackbarHostState,
                                        isAccessibilityEnabled = uiState.isAccessibilityEnabled,
                                        isIgnoringBatteryOptimizations = uiState.isIgnoringBatteryOptimizations,
                                        runtimeStatus = uiState.runtimeStatus,
                                        onRefreshStatus = viewModel::refreshAll
                                    )
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
private fun EdgeFlowHeaderBar(
    isServiceActive: Boolean,
    onStatusClick: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    val statusColor = if (isServiceActive) extendedColors.statusActive else extendedColors.statusInactive
    val statusContainer = if (isServiceActive) {
        extendedColors.statusActiveContainer
    } else {
        extendedColors.statusInactiveContainer
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onStatusClick)
                    .testTag("service_status_badge"),
                color = statusContainer,
                shape = RoundedCornerShape(999.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = stringResource(
                            if (isServiceActive) R.string.status_service_active
                            else R.string.status_service_inactive
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("open_general_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.cd_open_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
