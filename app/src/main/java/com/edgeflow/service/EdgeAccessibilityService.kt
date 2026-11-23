package com.edgeflow.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.edgeflow.data.ConfigStore
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.RuntimeStateRegistry
import com.edgeflow.service.shizuku.NavBarAutoGuard
import com.edgeflow.service.shizuku.NavBarUserService
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import com.edgeflow.service.translate.LiveScreenTranslateController
import com.example.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Ultra-lightweight resident AccessibilityService for Hinata.
 *
 * Architectural & battery guarantees:
 * - Runs in the default process with ZERO Jetpack Compose imports.
 * - Complete Screen-Off sleep via [ACTION_SCREEN_OFF]/[ACTION_SCREEN_ON] + [PowerManager.isInteractive]:
 *   zero event processing, zero timers, and zero GPU backdrop cache while display is off.
 * - Throttles/debounces `windows` IPC inspection by 250ms via a single pre-allocated [Runnable]
 *   so scroll/window-change storms never spin the CPU.
 * - Filters out non-fullscreen floating/mini windows (<50% display area) on
 *   `TYPE_WINDOW_STATE_CHANGED` so floating clocks/widgets never reset Liquid Glass or Live Translate.
 * - Dynamically subscribes to `TYPE_VIEW_SCROLLED` whenever Live Screen Translate is active OR a
 *   Liquid Glass overlay operation is active (zero scroll overhead at idle).
 * - Syncs configuration live from the isolated :settings process via ContentObserver and handles
 *   cross-process Control Center tile scan triggers via [RuntimeStateRegistry].
 */
class EdgeAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var actionExecutor: ActionExecutor? = null
    private var hapticController: HapticController? = null
    private var iconCache: QuickMenuIconCache? = null
    private var recentAppsTracker: RecentAppsTracker? = null
    private var backdropCapturer: LiquidBackdropCapturer? = null
    private var overlayController: EdgeOverlayController? = null
    private var liveTranslateController: LiveScreenTranslateController? = null
    private var navBarAutoGuard: NavBarAutoGuard? = null
    private var configObserver: ContentObserver? = null
    private var accessibilitySettingObserver: ContentObserver? = null

    @Volatile
    private var currentConfig: EdgeConfig = EdgeConfig()
    @Volatile
    private var isScreenInteractive: Boolean = true
    private var dynamicTranslateEventsEnabled: Boolean = false
    private var liquidOperationActive: Boolean = false
    private var subscribedEventMask: Int = BASE_ACCESSIBILITY_EVENT_TYPES
    private var screenReceiverRegistered: Boolean = false
    private var lastEventPackage: String = ""
    private var lastMainAppPackage: String = ""
    private var lastScrollWindowId: Int = -1
    private var lastScrollX: Int = -1
    private var lastScrollY: Int = -1
    private var imeInspectScheduled: Boolean = false
    private var lastImeInspectUptimeMs: Long = 0L
    private var isForegroundKeepAliveActive: Boolean = false
    private val disconnectRecoveryTriggered = AtomicBoolean(false)

    // Pre-allocated Rects & Runnable — zero GC during accessibility event storms
    private val tempImeBounds = Rect()
    private val tempWindowBounds = Rect()

    private val imeInspectRunnable = Runnable {
        imeInspectScheduled = false
        if (!isScreenInteractive) return@Runnable
        lastImeInspectUptimeMs = SystemClock.uptimeMillis()
        inspectImeWindowStateNow()
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    imeInspectScheduled = false
                    mainHandler.removeCallbacks(imeInspectRunnable)
                    backdropCapturer?.setScreenInteractive(false)
                    overlayController?.onScreenOff()
                    liveTranslateController?.onScreenOff()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive = true
                    backdropCapturer?.setScreenInteractive(true)
                    liveTranslateController?.onScreenOn()
                    scheduleImeInspection(immediate = false)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        installCrashBreakwater()
        CircleToSearchTrigger.ensureBypass()
        ShizukuShellExecutor.initMultiProcess(this, isMainProcess = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Preserve resident service and edge overlays even when user clears Recents.
        // Schedule a lightweight check to auto-revive via Shizuku if the ROM drops the binding.
        val appCtx = applicationContext
        val cfg = currentConfig
        if (cfg.autoReviveWithShizuku) {
            mainHandler.postDelayed({
                if (activeInstance == null && ShizukuShellExecutor.isReady()) {
                    Thread({
                        ShizukuShellExecutor.reviveAccessibilityServiceWithShizuku(
                            context = appCtx,
                            forceToggleRebind = true
                        )
                    }, "hinata-task-removed-revive").apply {
                        isDaemon = true
                        start()
                    }
                }
            }, TASK_REMOVED_CHECK_DELAY_MS)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        disconnectRecoveryTriggered.set(false)
        cancelEmergencyOffNotification(applicationContext)
        CircleToSearchTrigger.ensureBypass()

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenInteractive = powerManager?.isInteractive ?: true
        lastEventPackage = ""
        lastMainAppPackage = ""

        registerScreenReceiverIfNeeded()

        val haptics = HapticController(this)
        val cache = QuickMenuIconCache(this)
        val tracker = RecentAppsTracker(this)
        val capturer = LiquidBackdropCapturer(
            service = this,
            onOperationActiveChanged = { active -> updateLiquidOperationScrollEvents(active) }
        ).apply {
            setScreenInteractive(isScreenInteractive)
        }
        val translateController = LiveScreenTranslateController(
            service = this,
            hapticController = haptics,
            onDynamicEventsChanged = { enabled -> updateDynamicTranslateEvents(enabled) }
        )
        val executor = ActionExecutor(
            service = this,
            recentAppsTracker = tracker,
            onBeforeScreenCapture = { overlayController?.hideAllVisualsForCapture() },
            onBeforeOverlayTransition = { overlayController?.hideActiveVisualsImmediately() },
            onScreenTransition = { capturer.markNeedsRefreshOnNextGesture() },
            onToggleScreenTranslate = { translateController.toggleLiveTranslate() },
            onTranslateScreenOnce = { translateController.translateScreenOnce() }
        )
        val overlay = EdgeOverlayController(
            context = this,
            actionExecutor = executor,
            hapticController = haptics,
            iconCache = cache,
            recentAppsTracker = tracker,
            backdropCapturer = capturer
        )

        actionExecutor = executor
        hapticController = haptics
        iconCache = cache
        recentAppsTracker = tracker
        backdropCapturer = capturer
        overlayController = overlay
        liveTranslateController = translateController

        RuntimeStateRegistry.registerCcFloatScanHandler { mode ->
            actionExecutor?.triggerControlCenterFloatScan(mode) ?: false
        }

        // Optional UsageStatsManager history backfill (runs on Dispatchers.IO; safe no-op if unpermitted)
        tracker.supplementFromUsageStatsIfPermitted()

        val initialConfig = ConfigStore.load(this)
        currentConfig = initialConfig
        capturer.setEnabled(initialConfig.liquidGlassEnabled)
        translateController.applyConfig(initialConfig)
        overlay.attach(initialConfig)
        overlay.onDisplayMetricsChanged(
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        )
        if (isScreenInteractive) {
            inspectImeWindowStateNow()
        }

        val autoGuard = NavBarAutoGuard(this)
        autoGuard.applyConfig(initialConfig)
        navBarAutoGuard = autoGuard

        updateKeepAliveNotification(initialConfig)
        registerAccessibilitySettingObserver()
        syncDeadManSwitchAsync(initialConfig)

        configObserver = ConfigStore.registerObserver(this) { updatedConfig ->
            currentConfig = updatedConfig
            backdropCapturer?.setEnabled(updatedConfig.liquidGlassEnabled)
            liveTranslateController?.applyConfig(updatedConfig)
            overlayController?.applyConfig(updatedConfig)
            navBarAutoGuard?.applyConfig(updatedConfig)
            updateKeepAliveNotification(updatedConfig)
            syncDeadManSwitchAsync(updatedConfig)
        }
    }

    /**
     * Cross-process entry point called by [EdgeConfigProvider] inside the resident process to
     * trigger Control Center Floating Window tile position calibration (`NODE` or `OCR`).
     */
    fun requestControlCenterTileCalibration(method: String): Boolean {
        val executor = actionExecutor ?: return false
        mainHandler.post {
            executor.triggerControlCenterFloatScan(method)
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isScreenInteractive) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString().orEmpty()
                val dominantPkg = resolveDominantMainAppPackage(pkg)
                if (dominantPkg != null && dominantPkg != lastMainAppPackage) {
                    lastMainAppPackage = dominantPkg
                    lastEventPackage = dominantPkg
                    recentAppsTracker?.onForegroundPackageChanged(dominantPkg)
                    lastScrollWindowId = -1
                    lastScrollX = -1
                    lastScrollY = -1
                    backdropCapturer?.onForegroundPackageChanged(dominantPkg)
                    liveTranslateController?.onWindowStateChanged(dominantPkg, isMainFullscreenApp = true)
                    if (shouldTrackForegroundForBlacklist(dominantPkg)) {
                        overlayController?.updateForegroundPackage(dominantPkg)
                    }
                } else if (pkg.isNotEmpty() && pkg != lastEventPackage && !isTransientSystemOverlayPackage(pkg)) {
                    // Floating/mini window (e.g. floating clock or PiP): do NOT tear down
                    // Liquid Glass backdrop cache or reset Live Translate's foreground anchor.
                    recentAppsTracker?.onForegroundPackageChanged(pkg)
                    liveTranslateController?.onWindowStateChanged(pkg, isMainFullscreenApp = false)
                }
                scheduleImeInspection(immediate = false)
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                scheduleImeInspection(immediate = false)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val sourcePkg = event.packageName?.toString().orEmpty()
                val targetPkg = liveTranslateController?.currentTargetPackage().orEmpty()
                if (sourcePkg.isNotEmpty() && !isTransientSystemOverlayPackage(sourcePkg)) {
                    if (targetPkg.isEmpty() || sourcePkg == targetPkg) {
                        val isRawDeltaPx = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                            (event.scrollDeltaX != 0 || event.scrollDeltaY != 0)
                        val deltaX = resolveScrollDeltaX(event)
                        val deltaY = resolveScrollDeltaY(event)
                        liveTranslateController?.onViewScrolled(
                            sourcePackage = sourcePkg,
                            scrollDeltaX = deltaX,
                            scrollDeltaY = deltaY,
                            isRawDeltaPx = isRawDeltaPx
                        )
                    }
                    if (backdropCapturer?.isOperationCurrentlyActive() == true) {
                        backdropCapturer?.onUnderlyingScrolled()
                    }
                }
            }
        }
    }

    /**
     * Resolves the dominant fullscreen `TYPE_APPLICATION` package covering >= 50% of screen area
     * (`winArea * 2L >= screenArea`), selecting the highest `layer` and strictly ignoring SystemUI,
     * IME, Hinata itself, and floating/mini windows. Recycles every accessed `AccessibilityNodeInfo`.
     */
    private fun resolveDominantMainAppPackage(fallbackEventPkg: String): String? {
        val windowList = runCatching { windows }.getOrNull()
        if (windowList.isNullOrEmpty()) {
            return if (fallbackEventPkg.isNotEmpty() &&
                fallbackEventPkg != packageName &&
                !isTransientSystemOverlayPackage(fallbackEventPkg)
            ) {
                fallbackEventPkg
            } else {
                null
            }
        }

        val dm = resources.displayMetrics
        val screenArea = dm.widthPixels.toLong().coerceAtLeast(320L) *
            dm.heightPixels.toLong().coerceAtLeast(480L)

        var bestPkg: String? = null
        var bestLayer = Int.MIN_VALUE
        for (i in 0 until windowList.size) {
            val win = windowList[i]
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            win.getBoundsInScreen(tempWindowBounds)
            val winArea = tempWindowBounds.width().toLong().coerceAtLeast(0L) *
                tempWindowBounds.height().toLong().coerceAtLeast(0L)
            if (winArea * 2L < screenArea) continue

            val root = runCatching { win.root }.getOrNull() ?: continue
            val winPkg = root.packageName?.toString().orEmpty()
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
            if (winPkg.isEmpty() || winPkg == packageName || isTransientSystemOverlayPackage(winPkg)) {
                continue
            }
            if (win.layer >= bestLayer) {
                bestLayer = win.layer
                bestPkg = winPkg
            }
        }
        return bestPkg
    }

    private fun resolveScrollDeltaX(event: AccessibilityEvent): Int {
        val rawDelta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            event.scrollDeltaX
        } else {
            0
        }
        val currScrollX = event.scrollX
        val winId = event.windowId
        if (rawDelta != 0) {
            if (currScrollX >= 0) {
                lastScrollWindowId = winId
                lastScrollX = currScrollX
            }
            return rawDelta
        }
        if (currScrollX >= 0) {
            val computed = if (winId == lastScrollWindowId && lastScrollX >= 0) {
                val diff = currScrollX - lastScrollX
                val maxPlausible = resources.displayMetrics.widthPixels.coerceAtLeast(720)
                if (abs(diff) <= maxPlausible) diff else 0
            } else {
                0
            }
            lastScrollWindowId = winId
            lastScrollX = currScrollX
            return computed
        }
        return 0
    }

    private fun resolveScrollDeltaY(event: AccessibilityEvent): Int {
        val rawDelta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            event.scrollDeltaY
        } else {
            0
        }
        val currScrollY = event.scrollY
        val winId = event.windowId
        if (rawDelta != 0) {
            if (currScrollY >= 0) {
                lastScrollWindowId = winId
                lastScrollY = currScrollY
            }
            return rawDelta
        }
        if (currScrollY >= 0) {
            val computed = if (winId == lastScrollWindowId && lastScrollY >= 0) {
                val diff = currScrollY - lastScrollY
                val maxPlausible = resources.displayMetrics.heightPixels.coerceAtLeast(1280)
                if (abs(diff) <= maxPlausible) diff else 0
            } else {
                0
            }
            lastScrollWindowId = winId
            lastScrollY = currScrollY
            return computed
        }
        return 0
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        lastScrollWindowId = -1
        lastScrollX = -1
        lastScrollY = -1
        backdropCapturer?.releaseBackdropNow()
        overlayController?.onDisplayMetricsChanged(landscape)
        liveTranslateController?.onConfigurationChanged()
        if (isScreenInteractive) {
            scheduleImeInspection(immediate = true)
        }
    }

    override fun onInterrupt() {
        // Idle by design when untouched.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown(triggerLockoutGuard = true)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown(triggerLockoutGuard = true)
        super.onDestroy()
    }

    private fun registerScreenReceiverIfNeeded() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            registerReceiver(screenStateReceiver, filter)
            screenReceiverRegistered = true
        }
    }

    private fun unregisterScreenReceiverIfNeeded() {
        if (!screenReceiverRegistered) return
        screenReceiverRegistered = false
        runCatching { unregisterReceiver(screenStateReceiver) }
    }

    private fun scheduleImeInspection(immediate: Boolean) {
        if (!isScreenInteractive) return
        if (immediate) {
            mainHandler.removeCallbacks(imeInspectRunnable)
            imeInspectScheduled = false
            lastImeInspectUptimeMs = SystemClock.uptimeMillis()
            inspectImeWindowStateNow()
            return
        }
        if (imeInspectScheduled) return
        val elapsed = SystemClock.uptimeMillis() - lastImeInspectUptimeMs
        val delayMs = (IME_INSPECT_THROTTLE_MS - elapsed).coerceIn(IME_INSPECT_MIN_DEBOUNCE_MS, IME_INSPECT_THROTTLE_MS)
        imeInspectScheduled = true
        mainHandler.postDelayed(imeInspectRunnable, delayMs)
    }

    private fun updateKeepAliveNotification(config: EdgeConfig) {
        if (!config.keepAliveForegroundNotification) {
            stopKeepAliveForeground()
            return
        }
        val localized = LocalizedContextHelper.forTag(this, config.appLanguageTag)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm != null) {
            val channel = NotificationChannel(
                KEEP_ALIVE_CHANNEL_ID,
                localized.getString(R.string.notification_keep_alive_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            runCatching { nm.createNotificationChannel(channel) }
        }

        val openSettingsIntent = Intent().apply {
            setClassName(packageName, SETTINGS_ACTIVITY_CLASS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            REQ_CODE_OPEN_SETTINGS,
            openSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val reloadIntent = Intent(this, HinataRecoveryActivity::class.java).apply {
            action = HinataRecoveryActivity.ACTION_REVIVE_SERVICE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val reloadPendingIntent = PendingIntent.getActivity(
            this,
            REQ_CODE_ACTION_RELOAD,
            reloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restoreNavIntent = Intent(this, HinataRecoveryActivity::class.java).apply {
            action = HinataRecoveryActivity.ACTION_RESTORE_SYSTEM_NAV
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val restoreNavPendingIntent = PendingIntent.getActivity(
            this,
            REQ_CODE_ACTION_RESTORE_NAV,
            restoreNavIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val reloadAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_launcher_monochrome),
            localized.getString(R.string.notification_action_reload),
            reloadPendingIntent
        ).build()

        val restoreNavAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_launcher_monochrome),
            localized.getString(R.string.notification_action_restore_nav),
            restoreNavPendingIntent
        ).build()

        val notification = Notification.Builder(this, KEEP_ALIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(localized.getString(R.string.notification_keep_alive_title))
            .setContentText(localized.getString(R.string.notification_keep_alive_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)
            .addAction(reloadAction)
            .addAction(restoreNavAction)
            .build()

        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    KEEP_ALIVE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(KEEP_ALIVE_NOTIFICATION_ID, notification)
            }
            true
        }.getOrDefault(false)

        if (started) {
            isForegroundKeepAliveActive = true
        }
    }

    private fun stopKeepAliveForeground() {
        if (!isForegroundKeepAliveActive) return
        isForegroundKeepAliveActive = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun teardown(triggerLockoutGuard: Boolean) {
        val configSnapshot = currentConfig
        val appCtx = applicationContext

        if (activeInstance === this) {
            activeInstance = null
        }
        imeInspectScheduled = false
        mainHandler.removeCallbacks(imeInspectRunnable)
        unregisterScreenReceiverIfNeeded()
        unregisterAccessibilitySettingObserver()
        stopKeepAliveForeground()
        RuntimeStateRegistry.registerCcFloatScanHandler(null)

        configObserver?.let { ConfigStore.unregisterObserver(this, it) }
        configObserver = null

        navBarAutoGuard?.release()
        navBarAutoGuard = null

        liveTranslateController?.stopAndRelease()
        liveTranslateController = null
        dynamicTranslateEventsEnabled = false
        liquidOperationActive = false
        refreshDynamicEventTypes()

        overlayController?.detach()
        overlayController = null

        backdropCapturer?.release()
        backdropCapturer = null

        actionExecutor?.release()
        actionExecutor = null

        iconCache?.release()
        iconCache = null

        recentAppsTracker?.release()
        recentAppsTracker = null

        hapticController = null
        lastEventPackage = ""

        RuntimeStateRegistry.update(
            serviceActive = false,
            imeVisible = false,
            blacklistedActive = false
        )

        if (triggerLockoutGuard && disconnectRecoveryTriggered.compareAndSet(false, true)) {
            runLockoutRecoveryAsync(
                appCtx = appCtx,
                configSnapshot = configSnapshot,
                forceToggleRebind = false,
                unbindWhenDone = true
            )
        } else if (!triggerLockoutGuard) {
            ShizukuShellExecutor.unbindCachedUserService()
        }
    }

    private fun syncDeadManSwitchAsync(config: EdgeConfig) {
        val appCtx = applicationContext
        Thread({
            runCatching {
                ShizukuShellExecutor.syncDeadManSwitch(appCtx, config)
            }
        }, "hinata-deadman-sync").apply {
            isDaemon = true
            start()
        }
    }

    private fun registerAccessibilitySettingObserver() {
        unregisterAccessibilitySettingObserver()
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                checkAccessibilityAutoOffSensor()
            }
        }
        val registered = runCatching {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer
            )
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false,
                observer
            )
            true
        }.getOrDefault(false)
        if (registered) {
            accessibilitySettingObserver = observer
        }
    }

    private fun unregisterAccessibilitySettingObserver() {
        val observer = accessibilitySettingObserver ?: return
        accessibilitySettingObserver = null
        runCatching { contentResolver.unregisterContentObserver(observer) }
    }

    private fun checkAccessibilityAutoOffSensor() {
        val enabledRaw = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull()
        val enabledFlag = runCatching {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.getOrDefault(1)

        if (NavBarUserService.isOwnAccessibilityEnabledInList(enabledRaw, enabledFlag, packageName)) {
            return
        }
        if (disconnectRecoveryTriggered.compareAndSet(false, true)) {
            runLockoutRecoveryAsync(
                appCtx = applicationContext,
                configSnapshot = currentConfig,
                forceToggleRebind = true,
                unbindWhenDone = false
            )
        }
    }

    /**
     * 0ms highest-priority lockout guard:
     * 1. If Shizuku is connected and system navigation / back gestures were modified, restores
     *    3-button & OS navigation IMMEDIATELY (0ms, zero sleep).
     * 2. Attempts Shizuku auto-revive if `autoReviveWithShizuku` is enabled.
     * 3. If Shizuku is unavailable or auto-revive fails, immediately posts the high-importance
     *    Emergency Escape Notification (ID 4199) so the user can never get stranded.
     */
    private fun runLockoutRecoveryAsync(
        appCtx: Context,
        configSnapshot: EdgeConfig,
        forceToggleRebind: Boolean,
        unbindWhenDone: Boolean
    ) {
        val needsNavRestore = configSnapshot.autoHideNavBarWithShizuku ||
            configSnapshot.shizukuSystemNavModified

        if (!ShizukuShellExecutor.isReady()) {
            postEmergencyOffNotification(appCtx, configSnapshot)
            if (unbindWhenDone) {
                ShizukuShellExecutor.unbindCachedUserService()
            }
            return
        }

        Thread({
            try {
                // Step 1 (0ms, zero sleep): restore 3-button & OS back navigation FIRST!
                if (needsNavRestore) {
                    ShizukuShellExecutor.restoreAllSystemNavigation(appCtx)
                }

                // Step 2: attempt Shizuku auto-revive if enabled
                var revived = false
                if (configSnapshot.autoReviveWithShizuku) {
                    val reviveResult = ShizukuShellExecutor.reviveAccessibilityServiceWithShizuku(
                        context = appCtx,
                        forceToggleRebind = forceToggleRebind
                    )
                    revived = reviveResult.success
                }

                if (!revived) {
                    postEmergencyOffNotification(appCtx, configSnapshot)
                }
            } finally {
                if (unbindWhenDone && activeInstance == null) {
                    ShizukuShellExecutor.unbindCachedUserService()
                }
            }
        }, "hinata-disconnect-guard").apply {
            isDaemon = true
            start()
        }
    }

    private fun updateDynamicTranslateEvents(enabled: Boolean) {
        if (dynamicTranslateEventsEnabled == enabled) return
        dynamicTranslateEventsEnabled = enabled
        refreshDynamicEventTypes()
    }

    private fun updateLiquidOperationScrollEvents(active: Boolean) {
        if (liquidOperationActive == active) return
        liquidOperationActive = active
        refreshDynamicEventTypes()
    }

    private fun refreshDynamicEventTypes() {
        val desiredMask = if (dynamicTranslateEventsEnabled || liquidOperationActive) {
            BASE_ACCESSIBILITY_EVENT_TYPES or AccessibilityEvent.TYPE_VIEW_SCROLLED
        } else {
            BASE_ACCESSIBILITY_EVENT_TYPES
        }
        if (subscribedEventMask == desiredMask) return
        subscribedEventMask = desiredMask
        runCatching {
            val info = serviceInfo ?: return
            info.eventTypes = desiredMask
            serviceInfo = info
        }
    }

    private fun inspectImeWindowStateNow() {
        val controller = overlayController ?: return
        val windowList = runCatching { windows }.getOrNull()
        if (windowList == null) {
            controller.updateImeState(false, 0)
            liveTranslateController?.updateImeState(false, 0)
            return
        }

        var foundIme = false
        var imeTop = 0
        for (i in 0 until windowList.size) {
            val win = windowList[i]
            if (win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                win.getBoundsInScreen(tempImeBounds)
                if (tempImeBounds.height() > 0) {
                    foundIme = true
                    imeTop = tempImeBounds.top
                    break
                }
            }
        }
        controller.updateImeState(foundIme, imeTop)
        liveTranslateController?.updateImeState(foundIme, imeTop)
    }

    private fun isTransientSystemOverlayPackage(pkg: String): Boolean {
        if (pkg == "com.miui.screenshot") return true
        if (pkg == "com.android.systemui") return true
        if (pkg == "android") return true
        if (pkg.contains("inputmethod", ignoreCase = true)) return true
        if (pkg.contains("keyboard", ignoreCase = true)) return true
        return false
    }

    private fun shouldTrackForegroundForBlacklist(pkg: String): Boolean {
        if (pkg == packageName) return false
        if (isTransientSystemOverlayPackage(pkg)) return false
        return true
    }

    companion object {
        @Volatile
        private var activeInstance: EdgeAccessibilityService? = null

        private val crashBreakwaterInstalled = AtomicBoolean(false)

        fun getActiveInstance(): EdgeAccessibilityService? = activeInstance

        /**
         * Wraps the default [Thread.UncaughtExceptionHandler] once per resident process so that
         * any unexpected overlay/rendering crash detaches edge windows and attempts emergency
         * Shizuku navigation restoration before delegating to the original handler.
         */
        private fun installCrashBreakwater() {
            if (!crashBreakwaterInstalled.compareAndSet(false, true)) return
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                val svc = activeInstance
                runCatching { svc?.overlayController?.detach() }
                val appCtx = svc?.applicationContext
                val cfg = svc?.currentConfig
                val needsNavRestore = cfg != null &&
                    (cfg.autoHideNavBarWithShizuku || cfg.shizukuSystemNavModified)
                if (appCtx != null && needsNavRestore && ShizukuShellExecutor.isReady()) {
                    val restoreThread = Thread({
                        runCatching { ShizukuShellExecutor.restoreAllSystemNavigation(appCtx) }
                    }, "hinata-crash-nav-restore").apply {
                        isDaemon = true
                        start()
                    }
                    runCatching { restoreThread.join(CRASH_RESTORE_JOIN_MS) }
                }
                previousHandler?.uncaughtException(thread, throwable)
            }
        }

        private fun postEmergencyOffNotification(appCtx: Context, config: EdgeConfig) {
            runCatching {
                val localized = LocalizedContextHelper.forTag(appCtx, config.appLanguageTag)
                val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return

                val channel = NotificationChannel(
                    EMERGENCY_OFF_CHANNEL_ID,
                    localized.getString(R.string.notification_emergency_off_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setShowBadge(true)
                }
                runCatching { nm.createNotificationChannel(channel) }

                val openA11yIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val openA11yPending = PendingIntent.getActivity(
                    appCtx,
                    REQ_CODE_EMERGENCY_OPEN_A11Y,
                    openA11yIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val restoreNavIntent = Intent(appCtx, HinataRecoveryActivity::class.java).apply {
                    action = HinataRecoveryActivity.ACTION_RESTORE_SYSTEM_NAV
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val restoreNavPending = PendingIntent.getActivity(
                    appCtx,
                    REQ_CODE_EMERGENCY_RESTORE_NAV,
                    restoreNavIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val openA11yAction = Notification.Action.Builder(
                    Icon.createWithResource(appCtx, R.drawable.ic_launcher_monochrome),
                    localized.getString(R.string.notification_emergency_action_open_accessibility),
                    openA11yPending
                ).build()

                val restoreNavAction = Notification.Action.Builder(
                    Icon.createWithResource(appCtx, R.drawable.ic_launcher_monochrome),
                    localized.getString(R.string.notification_emergency_action_restore_nav),
                    restoreNavPending
                ).build()

                val bodyText = localized.getString(R.string.notification_emergency_off_text)
                val notification = Notification.Builder(appCtx, EMERGENCY_OFF_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(localized.getString(R.string.notification_emergency_off_title))
                    .setContentText(bodyText)
                    .setStyle(Notification.BigTextStyle().bigText(bodyText))
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_ERROR)
                    .setContentIntent(openA11yPending)
                    .addAction(openA11yAction)
                    .addAction(restoreNavAction)
                    .build()

                nm.notify(EMERGENCY_OFF_NOTIFICATION_ID, notification)
            }
        }

        private fun cancelEmergencyOffNotification(appCtx: Context) {
            runCatching {
                val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(EMERGENCY_OFF_NOTIFICATION_ID)
            }
        }

        private const val BASE_ACCESSIBILITY_EVENT_TYPES =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        private const val IME_INSPECT_THROTTLE_MS = 250L
        private const val IME_INSPECT_MIN_DEBOUNCE_MS = 60L
        private const val TASK_REMOVED_CHECK_DELAY_MS = 450L
        private const val CRASH_RESTORE_JOIN_MS = 600L

        private const val KEEP_ALIVE_CHANNEL_ID = "hinata_keep_alive"
        private const val KEEP_ALIVE_NOTIFICATION_ID = 4109
        private const val EMERGENCY_OFF_CHANNEL_ID = "hinata_emergency_off"
        private const val EMERGENCY_OFF_NOTIFICATION_ID = 4199
        private const val REQ_CODE_OPEN_SETTINGS = 4110
        private const val REQ_CODE_ACTION_RELOAD = 4111
        private const val REQ_CODE_ACTION_RESTORE_NAV = 4112
        private const val REQ_CODE_EMERGENCY_OPEN_A11Y = 4198
        private const val REQ_CODE_EMERGENCY_RESTORE_NAV = 4197
        private const val SETTINGS_ACTIVITY_CLASS = "com.edgeflow.settings.SettingsActivity"
    }
}
