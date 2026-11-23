package com.edgeflow.service.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.edgeflow.data.DeviceFamily
import com.edgeflow.data.EdgeConfig
import com.example.BuildConfig
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class ShizukuStatus {
    NOT_INSTALLED,
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    CONNECTED
}

data class NavBarCommandResult(
    val success: Boolean,
    val status: ShizukuStatus,
    val detail: String = "",
    val blockedByAccessibilityGuard: Boolean = false
)

/**
 * Safe, zero-crash Shizuku wrapper for multi-OEM navigation control, Chinese-ROM Circle to Search
 * assistant repair, and ultra-fast shell-privileged (UID 2000) native scaled floating windows.
 *
 * Architectural guarantees:
 * - Pure Kotlin with ZERO Jetpack Compose imports (works in both resident default process & `:settings`).
 * - 30-second idle warm cache (`IDLE_UNBIND_MS = 30_000L`): keeps `INavBarControlService` warm during
 *   active usage for 0ms consecutive calls, then automatically unbinds with `remove = true` after
 *   30 seconds of idle, on Binder death, or on service teardown for 0% idle RAM.
 * - Mandatory Accessibility Service safety guard before suppressing OS navigation/back gestures so
 *   users can never lock themselves out.
 */
object ShizukuShellExecutor {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_CODE_SHIZUKU_PERMISSION = 9410

    // HyperOS / MIUI 3-button & gesture line
    const val CMD_FORCE_FSG_HIDE = "settings put global force_fsg_nav_bar 1"
    const val CMD_FORCE_FSG_RESTORE = "settings put global force_fsg_nav_bar 0"
    const val CMD_HIDE_GESTURE_LINE_ON = "settings put global hide_gesture_line 1"
    const val CMD_HIDE_GESTURE_LINE_OFF = "settings put global hide_gesture_line 0"
    const val CMD_OVERLAY_GESTURAL = "cmd overlay enable com.android.internal.systemui.navbar.gestural"
    const val CMD_OVERLAY_THREE_BUTTON = "cmd overlay enable com.android.internal.systemui.navbar.threebutton"
    const val CMD_GET_FREEFORM_SUPPORT = "settings get global enable_freeform_support"
    const val CMD_ENABLE_FREEFORM_SUPPORT = "settings put global enable_freeform_support 1"

    // Spec 63: All-OEM Back gesture suppression, NavHide, Samsung One UI hint, nav mode query
    const val CMD_BACK_INSET_LEFT_ZERO = "settings put secure back_gesture_inset_scale_left 0"
    const val CMD_BACK_INSET_LEFT_NEG = "settings put secure back_gesture_inset_scale_left -1"
    const val CMD_BACK_INSET_LEFT_DELETE = "settings delete secure back_gesture_inset_scale_left"
    const val CMD_BACK_INSET_LEFT_ONE = "settings put secure back_gesture_inset_scale_left 1"
    const val CMD_BACK_INSET_RIGHT_ZERO = "settings put secure back_gesture_inset_scale_right 0"
    const val CMD_BACK_INSET_RIGHT_NEG = "settings put secure back_gesture_inset_scale_right -1"
    const val CMD_BACK_INSET_RIGHT_DELETE = "settings delete secure back_gesture_inset_scale_right"
    const val CMD_BACK_INSET_RIGHT_ONE = "settings put secure back_gesture_inset_scale_right 1"
    const val CMD_NAVHIDE_DISABLE_FOR_SETUP_TRUE = "cmd statusbar disable-for-setup true"
    const val CMD_NAVHIDE_DISABLE_FOR_SETUP_FALSE = "cmd statusbar disable-for-setup false"
    const val CMD_NAVHIDE_DISABLE_HOME_RECENTS = "cmd statusbar send-disable-flag home recents"
    const val CMD_NAVHIDE_DISABLE_HOME_RECENTS_BACK = "cmd statusbar send-disable-flag home recents back"
    const val CMD_NAVHIDE_DISABLE_NONE = "cmd statusbar send-disable-flag none"
    const val CMD_SAMSUNG_GESTURE_HINT_OFF = "settings put global navigation_bar_gesture_hint 0"
    const val CMD_SAMSUNG_GESTURE_HINT_ON = "settings put global navigation_bar_gesture_hint 1"
    const val CMD_GET_NAVIGATION_MODE = "settings get secure navigation_mode"

    // Spec 64: ColorOS 16 / OriginOS 6 Google Assistant CTS repair & restore
    const val CMD_CTS_SET_ASSISTANT =
        "settings put secure assistant com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService"
    const val CMD_CTS_SET_VOICE_INTERACTION =
        "settings put secure voice_interaction_service com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService"
    const val CMD_CTS_SET_VOICE_RECOGNITION =
        "settings put secure voice_recognition_service com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
    const val CMD_CTS_ADD_ASSISTANT_ROLE =
        "cmd role add-role-holder android.app.role.ASSISTANT com.google.android.googlequicksearchbox"
    const val CMD_CTS_DELETE_ASSISTANT = "settings delete secure assistant"
    const val CMD_CTS_DELETE_VOICE_INTERACTION = "settings delete secure voice_interaction_service"
    const val CMD_CTS_DELETE_VOICE_RECOGNITION = "settings delete secure voice_recognition_service"

    private const val BIND_TIMEOUT_MS = 3_000L
    private const val IDLE_UNBIND_MS = 30_000L

    private val executionLock = Any()
    private val idleHandler = Handler(Looper.getMainLooper())
    private val processAliveToken = Binder()

    @Volatile
    private var keepUserServiceWarmForDeadManSwitch: Boolean = false

    @Volatile
    private var cachedService: INavBarControlService? = null
    @Volatile
    private var cachedConnection: ServiceConnection? = null
    @Volatile
    private var cachedServiceArgs: Shizuku.UserServiceArgs? = null

    private val idleUnbindRunnable = Runnable {
        if (!keepUserServiceWarmForDeadManSwitch) {
            unbindCachedUserService()
        }
    }

    fun initMultiProcess(context: Context, isMainProcess: Boolean) {
        runCatching {
            ShizukuProvider.enableMultiProcessSupport(isMainProcess)
            if (!isMainProcess) {
                ShizukuProvider.requestBinderForNonProviderProcess(context.applicationContext)
            }
        }
    }

    fun refreshNonProviderBinder(context: Context) {
        runCatching {
            if (!isBinderAlive()) {
                ShizukuProvider.requestBinderForNonProviderProcess(context.applicationContext)
            }
        }
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    fun isBinderAlive(): Boolean {
        return runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    }

    fun hasShizukuPermission(): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun isReady(): Boolean = isBinderAlive() && hasShizukuPermission()

    fun checkStatus(context: Context): ShizukuStatus {
        if (!isShizukuInstalled(context)) return ShizukuStatus.NOT_INSTALLED
        if (!isBinderAlive()) return ShizukuStatus.NOT_RUNNING
        if (!hasShizukuPermission()) return ShizukuStatus.PERMISSION_REQUIRED
        return ShizukuStatus.CONNECTED
    }

    fun requestPermission(requestCode: Int = REQUEST_CODE_SHIZUKU_PERMISSION): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                return false
            }
            Shizuku.requestPermission(requestCode)
            true
        }.getOrDefault(false)
    }

    fun openShizukuApp(context: Context): Boolean {
        return runCatching {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                ?: return false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    /**
     * 3-way check for whether Hinata's AccessibilityService is enabled:
     * 1. Resident process live singleton (`EdgeAccessibilityService.getActiveInstance() != null`).
     * 2. `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)`.
     * 3. `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` (handles both full and short component formats).
     */
    fun isEdgeFlowAccessibilityEnabled(context: Context): Boolean {
        if (com.edgeflow.service.EdgeAccessibilityService.getActiveInstance() != null) {
            return true
        }
        val viaManager = runCatching {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? android.view.accessibility.AccessibilityManager ?: return@runCatching false
            val enabledList = am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            enabledList.any { info ->
                val svcInfo = info.resolveInfo?.serviceInfo
                val pkgMatch = svcInfo?.packageName == context.packageName
                val clsMatch = svcInfo?.name?.contains("EdgeAccessibilityService", ignoreCase = true) == true
                val idMatch = info.id?.contains(context.packageName, ignoreCase = true) == true &&
                    info.id?.contains("EdgeAccessibilityService", ignoreCase = true) == true
                (pkgMatch && clsMatch) || idMatch
            }
        }.getOrDefault(false)
        if (viaManager) return true

        return runCatching {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val expectedFull = "${context.packageName}/com.edgeflow.service.EdgeAccessibilityService"
            val expectedShort = "${context.packageName}/.service.EdgeAccessibilityService"
            enabled.split(':').any { entry ->
                entry.equals(expectedFull, ignoreCase = true) ||
                    entry.equals(expectedShort, ignoreCase = true) ||
                    (entry.contains("EdgeAccessibilityService", ignoreCase = true) &&
                        entry.contains(context.packageName, ignoreCase = true))
            }
        }.getOrDefault(false)
    }

    /**
     * Immediately unbinds and terminates any warm-cached Shizuku UserService so idle memory
     * returns to 0%. Safe to call from any thread (including `onDestroy()` and idle timer).
     */
    fun unbindCachedUserService() {
        keepUserServiceWarmForDeadManSwitch = false
        idleHandler.removeCallbacks(idleUnbindRunnable)
        val argsToUnbind: Shizuku.UserServiceArgs?
        val connToUnbind: ServiceConnection?
        synchronized(executionLock) {
            cachedService?.let { svc ->
                runCatching { svc.armDeadManSwitch(null, false, false) }
            }
            argsToUnbind = cachedServiceArgs
            connToUnbind = cachedConnection
            cachedService = null
            cachedConnection = null
            cachedServiceArgs = null
        }
        if (argsToUnbind != null && connToUnbind != null) {
            runCatching {
                Shizuku.unbindUserService(argsToUnbind, connToUnbind, true)
            }
        }
    }

    /**
     * Synchronizes the UID 2000 Dead-Man's Switch ([IBinder.linkToDeath]) and direct
     * `ENABLED_ACCESSIBILITY_SERVICES` ContentObserver with the current [EdgeConfig].
     *
     * While system navigation or OS back gestures are hidden (`needsNavRestore == true`),
     * keeps `NavBarUserService` warm (bypassing the 30s idle unbind timer) so SIGKILL / OOM
     * process death triggers 0ms immediate 3-button recovery.
     */
    fun syncDeadManSwitch(context: Context, config: EdgeConfig) {
        val needsNavRestore = config.autoHideNavBarWithShizuku || config.shizukuSystemNavModified
        val needsWatch = needsNavRestore || config.autoReviveWithShizuku
        if (!needsWatch) {
            keepUserServiceWarmForDeadManSwitch = false
            synchronized(executionLock) {
                val existing = cachedService
                if (existing != null) {
                    runCatching { existing.armDeadManSwitch(null, false, false) }
                    scheduleIdleUnbind()
                }
            }
            return
        }
        if (!isReady()) {
            keepUserServiceWarmForDeadManSwitch = false
            return
        }
        keepUserServiceWarmForDeadManSwitch = needsNavRestore
        withBoundUserServiceResult(
            context = context,
            onError = { _, _ -> Unit }
        ) { service ->
            runCatching {
                service.armDeadManSwitch(
                    processAliveToken,
                    needsNavRestore,
                    config.autoReviveWithShizuku
                )
            }
        }
    }

    private fun scheduleIdleUnbind() {
        idleHandler.removeCallbacks(idleUnbindRunnable)
        if (keepUserServiceWarmForDeadManSwitch) return
        idleHandler.postDelayed(idleUnbindRunnable, IDLE_UNBIND_MS)
    }

    // ---------------------------------------------------------------------------------------------
    // Multi-OEM Navigation Bar & System Back Gesture Controls (Spec 63)
    // ---------------------------------------------------------------------------------------------

    fun hideNavBar(
        context: Context,
        hideGestureLine: Boolean = true,
        enableGesturalOverlay: Boolean = true,
        requireAccessibilityGuard: Boolean = true
    ): NavBarCommandResult {
        if (requireAccessibilityGuard && !isEdgeFlowAccessibilityEnabled(context)) {
            return NavBarCommandResult(
                success = false,
                status = checkStatus(context),
                detail = "Accessibility service disabled",
                blockedByAccessibilityGuard = true
            )
        }
        val status = checkStatus(context)
        if (status != ShizukuStatus.CONNECTED) {
            return NavBarCommandResult(success = false, status = status)
        }

        return withBoundUserService(context) { service ->
            val requiredExit = runCatching {
                service.runCommand(CMD_FORCE_FSG_HIDE)
            }.getOrElse { t ->
                return@withBoundUserService NavBarCommandResult(
                    success = false,
                    status = ShizukuStatus.CONNECTED,
                    detail = t.message ?: t.javaClass.simpleName
                )
            }

            if (requiredExit != 0) {
                return@withBoundUserService NavBarCommandResult(
                    success = false,
                    status = ShizukuStatus.CONNECTED,
                    detail = "exit=$requiredExit"
                )
            }

            if (hideGestureLine) {
                runCatching { service.runCommand(CMD_HIDE_GESTURE_LINE_ON) }
            }
            if (enableGesturalOverlay) {
                runCatching { service.runCommand(CMD_OVERLAY_GESTURAL) }
            }

            NavBarCommandResult(success = true, status = ShizukuStatus.CONNECTED)
        }
    }

    fun restoreNavBar(context: Context): NavBarCommandResult {
        return restoreAllSystemNavigation(context)
    }

    /**
     * Device-family-aware navigation bar suppression (Spec 63).
     * Guards against lockout by checking [isEdgeFlowAccessibilityEnabled] when [requireAccessibilityGuard] is true.
     */
    fun hideSystemNavBarForDevice(
        context: Context,
        deviceFamily: DeviceFamily,
        hideGestureLine: Boolean = true,
        requireAccessibilityGuard: Boolean = true
    ): NavBarCommandResult {
        if (requireAccessibilityGuard && !isEdgeFlowAccessibilityEnabled(context)) {
            return NavBarCommandResult(
                success = false,
                status = checkStatus(context),
                detail = "Accessibility service disabled",
                blockedByAccessibilityGuard = true
            )
        }
        if (deviceFamily == DeviceFamily.XIAOMI_HYPEROS) {
            return hideNavBar(
                context = context,
                hideGestureLine = hideGestureLine,
                enableGesturalOverlay = true,
                requireAccessibilityGuard = false
            )
        }
        val status = checkStatus(context)
        if (status != ShizukuStatus.CONNECTED) {
            return NavBarCommandResult(success = false, status = status)
        }
        return withBoundUserService(context) { service ->
            var anySuccess = false
            when (deviceFamily) {
                DeviceFamily.REALME_OPPO_ONEPLUS -> {
                    // ColorOS / realme UI trick: enable gestural overlay while 3-button is selected
                    val overlayExit = runCatching { service.runCommand(CMD_OVERLAY_GESTURAL) }.getOrDefault(-1)
                    if (overlayExit == 0) anySuccess = true
                    val nav1 = runCatching { service.runCommand(CMD_NAVHIDE_DISABLE_FOR_SETUP_TRUE) }.getOrDefault(-1)
                    val nav2 = runCatching { service.runCommand(CMD_NAVHIDE_DISABLE_HOME_RECENTS) }.getOrDefault(-1)
                    if (nav1 == 0 || nav2 == 0) anySuccess = true
                }
                DeviceFamily.SAMSUNG_ONEUI -> {
                    val hintExit = runCatching { service.runCommand(CMD_SAMSUNG_GESTURE_HINT_OFF) }.getOrDefault(-1)
                    if (hintExit == 0) anySuccess = true
                    val nav1 = runCatching { service.runCommand(CMD_NAVHIDE_DISABLE_FOR_SETUP_TRUE) }.getOrDefault(-1)
                    val nav2 = runCatching { service.runCommand(CMD_NAVHIDE_DISABLE_HOME_RECENTS) }.getOrDefault(-1)
                    if (nav1 == 0 || nav2 == 0) anySuccess = true
                }
                DeviceFamily.VIVO_ORIGIN_OS, DeviceFamily.STANDARD_ANDROID -> {
                    val nav1 = runCatching { service.runCommand(CMD_NAVHIDE_DISABLE_FOR_SETUP_TRUE) }.getOrDefault(-1)
                    val nav2 = runCatching { service.runCommand(CMD_NAVHIDE_DISABLE_HOME_RECENTS) }.getOrDefault(-1)
                    if (nav1 == 0 || nav2 == 0) anySuccess = true
                }
                DeviceFamily.XIAOMI_HYPEROS -> Unit
            }
            NavBarCommandResult(
                success = anySuccess,
                status = ShizukuStatus.CONNECTED,
                detail = if (anySuccess) "" else "No nav-hide command accepted"
            )
        }
    }

    /**
     * Suppresses OS left and/or right back gestures via `back_gesture_inset_scale_*` (Pixel / AOSP /
     * Xperia / Nothing / Motorola / Samsung / HyperOS gestural mode).
     */
    fun suppressSystemBackGestures(
        context: Context,
        disableLeft: Boolean,
        disableRight: Boolean,
        useNegativeFallback: Boolean = false,
        requireAccessibilityGuard: Boolean = true
    ): NavBarCommandResult {
        if ((disableLeft || disableRight) &&
            requireAccessibilityGuard &&
            !isEdgeFlowAccessibilityEnabled(context)
        ) {
            return NavBarCommandResult(
                success = false,
                status = checkStatus(context),
                detail = "Accessibility service disabled",
                blockedByAccessibilityGuard = true
            )
        }
        val status = checkStatus(context)
        if (status != ShizukuStatus.CONNECTED) {
            return NavBarCommandResult(success = false, status = status)
        }
        return withBoundUserService(context) { service ->
            val leftCmd = if (disableLeft) {
                if (useNegativeFallback) CMD_BACK_INSET_LEFT_NEG else CMD_BACK_INSET_LEFT_ZERO
            } else {
                CMD_BACK_INSET_LEFT_DELETE
            }
            val rightCmd = if (disableRight) {
                if (useNegativeFallback) CMD_BACK_INSET_RIGHT_NEG else CMD_BACK_INSET_RIGHT_ZERO
            } else {
                CMD_BACK_INSET_RIGHT_DELETE
            }
            val leftExit = runCatching { service.runCommand(leftCmd) }.getOrDefault(-1)
            val rightExit = runCatching { service.runCommand(rightCmd) }.getOrDefault(-1)
            if (!disableLeft && leftExit != 0) {
                runCatching { service.runCommand(CMD_BACK_INSET_LEFT_ONE) }
            }
            if (!disableRight && rightExit != 0) {
                runCatching { service.runCommand(CMD_BACK_INSET_RIGHT_ONE) }
            }
            val ok = leftExit == 0 || rightExit == 0
            NavBarCommandResult(
                success = ok,
                status = ShizukuStatus.CONNECTED,
                detail = if (ok) "" else "left=$leftExit right=$rightExit"
            )
        }
    }

    fun restoreSystemBackGestures(context: Context): NavBarCommandResult {
        val status = checkStatus(context)
        if (status != ShizukuStatus.CONNECTED) {
            return NavBarCommandResult(success = false, status = status)
        }
        return withBoundUserService(context) { service ->
            val l1 = runCatching { service.runCommand(CMD_BACK_INSET_LEFT_DELETE) }.getOrDefault(-1)
            if (l1 != 0) runCatching { service.runCommand(CMD_BACK_INSET_LEFT_ONE) }
            val r1 = runCatching { service.runCommand(CMD_BACK_INSET_RIGHT_DELETE) }.getOrDefault(-1)
            if (r1 != 0) runCatching { service.runCommand(CMD_BACK_INSET_RIGHT_ONE) }
            NavBarCommandResult(success = true, status = ShizukuStatus.CONNECTED)
        }
    }

    /**
     * Emergency universal restore: reverts HyperOS `force_fsg_nav_bar`, gesture line, 3-button
     * overlay, NavHide statusbar flags, OS back gesture inset scales, and Samsung gesture hint
     * in a single 0ms UID 2000 IPC round-trip.
     */
    fun restoreAllSystemNavigation(context: Context): NavBarCommandResult {
        val status = checkStatus(context)
        if (status != ShizukuStatus.CONNECTED) {
            return NavBarCommandResult(success = false, status = status)
        }
        return withBoundUserService(context) { service ->
            val ok = runCatching {
                service.restoreSystemNavigationImmediate()
            }.getOrElse { t ->
                return@withBoundUserService NavBarCommandResult(
                    success = false,
                    status = ShizukuStatus.CONNECTED,
                    detail = t.message ?: t.javaClass.simpleName
                )
            }
            NavBarCommandResult(
                success = ok,
                status = ShizukuStatus.CONNECTED,
                detail = if (ok) "" else "restore commands failed"
            )
        }
    }

    /**
     * Re-enables or force-rebinds Hinata's own AccessibilityService via Shizuku UID 2000.
     * Target component is locked strictly to Hinata inside [NavBarUserService].
     */
    fun reviveAccessibilityServiceWithShizuku(
        context: Context,
        forceToggleRebind: Boolean = false
    ): NavBarCommandResult {
        val status = checkStatus(context)
        if (status != ShizukuStatus.CONNECTED) {
            return NavBarCommandResult(success = false, status = status)
        }
        return withBoundUserService(context) { service ->
            val ok = runCatching {
                service.reviveOwnAccessibilityService(forceToggleRebind)
            }.getOrElse { t ->
                return@withBoundUserService NavBarCommandResult(
                    success = false,
                    status = ShizukuStatus.CONNECTED,
                    detail = t.message ?: t.javaClass.simpleName
                )
            }
            NavBarCommandResult(
                success = ok,
                status = ShizukuStatus.CONNECTED,
                detail = if (ok) "" else "reviveOwnAccessibilityService returned false"
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Spec 64: ColorOS 16 / OriginOS 6 Google Assistant CTS Repair & Reset
    // ---------------------------------------------------------------------------------------------

    fun enableGoogleAssistantForCts(context: Context): NavBarCommandResult {
        val status = checkStatus(context)
        if (status != ShizukuStatus.CONNECTED) {
            return NavBarCommandResult(success = false, status = status)
        }
        return withBoundUserService(context) { service ->
            val e1 = runCatching { service.runCommand(CMD_CTS_SET_ASSISTANT) }.getOrDefault(-1)
            val e2 = runCatching { service.runCommand(CMD_CTS_SET_VOICE_INTERACTION) }.getOrDefault(-1)
            val e3 = runCatching { service.runCommand(CMD_CTS_SET_VOICE_RECOGNITION) }.getOrDefault(-1)
            // Optional role command (may fail on some ROMs — ignore failure and continue)
            runCatching { service.runCommand(CMD_CTS_ADD_ASSISTANT_ROLE) }
            val ok = e1 == 0 && e2 == 0
            NavBarCommandResult(
                success = ok,
                status = ShizukuStatus.CONNECTED,
                detail = if (ok) "" else "e1=$e1 e2=$e2 e3=$e3"
            )
        }
    }

    fun resetAssistantSettings(context: Context): NavBarCommandResult {
        val status = checkStatus(context)
        if (status != ShizukuStatus.CONNECTED) {
            return NavBarCommandResult(success = false, status = status)
        }
        return withBoundUserService(context) { service ->
            val e1 = runCatching { service.runCommand(CMD_CTS_DELETE_ASSISTANT) }.getOrDefault(-1)
            val e2 = runCatching { service.runCommand(CMD_CTS_DELETE_VOICE_INTERACTION) }.getOrDefault(-1)
            val e3 = runCatching { service.runCommand(CMD_CTS_DELETE_VOICE_RECOGNITION) }.getOrDefault(-1)
            NavBarCommandResult(
                success = e1 == 0 || e2 == 0 || e3 == 0,
                status = ShizukuStatus.CONNECTED
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Floating Window & Diagnostics APIs (Warm-Cached 0ms Execution)
    // ---------------------------------------------------------------------------------------------

    fun startActivityWithOptions(
        context: Context,
        intent: Intent,
        opts: Bundle?,
        userId: Int = 0
    ): Boolean {
        if (!isReady()) return false
        return withBoundUserServiceResult(
            context = context,
            onError = { _, _ -> false }
        ) { service ->
            runCatching { service.startWithOptions(intent, opts, userId) }.getOrDefault(false)
        }
    }

    fun getForegroundTaskId(context: Context): Int {
        if (!isReady()) return -1
        return withBoundUserServiceResult(
            context = context,
            onError = { _, _ -> -1 }
        ) { service ->
            runCatching { service.foregroundTaskId }.getOrDefault(-1)
        }
    }

    fun getForegroundPackage(context: Context): String {
        if (!isReady()) return ""
        return withBoundUserServiceResult(
            context = context,
            onError = { _, _ -> "" }
        ) { service ->
            runCatching { service.foregroundPackage.orEmpty() }.getOrDefault("")
        }
    }

    fun moveTaskToFreeform(context: Context, taskId: Int): Boolean {
        if (taskId < 0 || !isReady()) return false
        return withBoundUserServiceResult(
            context = context,
            onError = { _, _ -> false }
        ) { service ->
            runCatching { service.moveTaskToFreeform(taskId) }.getOrDefault(false)
        }
    }

    fun dumpFreeformApi(context: Context): String? {
        val localTrace = com.edgeflow.service.FloatingWindowLauncher.readLocalTrace(context)
        val shizukuDump = if (isReady()) {
            withBoundUserServiceResult(
                context = context,
                onError = { _, _ -> null }
            ) { service ->
                runCatching { service.dumpFreeformApi() }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        } else {
            null
        }
        return when {
            shizukuDump != null && localTrace.isNotBlank() ->
                "[App-side (No-Shizuku) Trace]\n$localTrace\n\n$shizukuDump"
            shizukuDump != null -> shizukuDump
            localTrace.isNotBlank() -> "[App-side (No-Shizuku) Trace]\n$localTrace"
            else -> null
        }
    }

    fun launchNativeHyperOsFreeform(
        context: Context,
        packageName: String,
        launchIntent: Intent
    ): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty() || !isReady()) return false
        return withBoundUserServiceResult(
            context = context,
            onError = { _, _ -> false }
        ) { service ->
            runCatching {
                service.launchNativeHyperOsFreeform(pkg, launchIntent)
            }.getOrDefault(false)
        }
    }

    fun floatForegroundNativeHyperOs(
        context: Context,
        fallbackPackageName: String,
        fallbackLaunchIntent: Intent?
    ): Boolean {
        if (!isReady()) return false
        return withBoundUserServiceResult(
            context = context,
            onError = { _, _ -> false }
        ) { service ->
            runCatching {
                service.floatForegroundNativeHyperOs(fallbackPackageName.trim(), fallbackLaunchIntent)
            }.getOrDefault(false)
        }
    }

    fun launchInFloatingWindow(
        context: Context,
        componentFlat: String,
        flagsHex: String? = null
    ): NavBarCommandResult {
        val status = checkStatus(context)
        if (status != ShizukuStatus.CONNECTED) {
            return NavBarCommandResult(success = false, status = status)
        }
        val safeComponent = componentFlat.trim()
        if (safeComponent.isEmpty() || !safeComponent.contains('/')) {
            return NavBarCommandResult(
                success = false,
                status = ShizukuStatus.CONNECTED,
                detail = "Invalid component"
            )
        }

        val flagsSegment = if (!flagsHex.isNullOrBlank()) " -f ${flagsHex.trim()}" else ""
        val primaryCmd = "am start-activity --windowingMode 5$flagsSegment -n $safeComponent"
        val fallbackCmd = "am start --windowingMode 5$flagsSegment -n $safeComponent"

        return withBoundUserService(context) { service ->
            runCatching {
                val rawOut = service.runCommandWithOutput(CMD_GET_FREEFORM_SUPPORT).orEmpty()
                val value = rawOut.substringAfter('\n', "").trim()
                if (value == "0") {
                    service.runCommand(CMD_ENABLE_FREEFORM_SUPPORT)
                }
            }

            val primaryExit = runCatching {
                service.runCommand(primaryCmd)
            }.getOrDefault(-1)
            if (primaryExit == 0) {
                return@withBoundUserService NavBarCommandResult(
                    success = true,
                    status = ShizukuStatus.CONNECTED
                )
            }

            val fallbackExit = runCatching {
                service.runCommand(fallbackCmd)
            }.getOrElse { t ->
                return@withBoundUserService NavBarCommandResult(
                    success = false,
                    status = ShizukuStatus.CONNECTED,
                    detail = t.message ?: t.javaClass.simpleName
                )
            }

            NavBarCommandResult(
                success = fallbackExit == 0,
                status = ShizukuStatus.CONNECTED,
                detail = if (fallbackExit == 0) "" else "exit=$fallbackExit"
            )
        }
    }

    private fun withBoundUserService(
        context: Context,
        block: (INavBarControlService) -> NavBarCommandResult
    ): NavBarCommandResult {
        return withBoundUserServiceResult(
            context = context,
            onError = { status, detail ->
                NavBarCommandResult(success = false, status = status, detail = detail)
            },
            block = block
        )
    }

    /**
     * Executes [block] against a warm-cached [INavBarControlService] (0ms when already alive)
     * or binds cleanly on demand. Resets the 30-second idle auto-unbind timer after every call
     * so standby RAM drops back to 0% automatically.
     */
    private fun <T> withBoundUserServiceResult(
        context: Context,
        onError: (ShizukuStatus, String) -> T,
        block: (INavBarControlService) -> T
    ): T = synchronized(executionLock) {
        val appContext = context.applicationContext
        idleHandler.removeCallbacks(idleUnbindRunnable)

        // Fast path: reuse live cached UserService (0ms overhead!)
        val existing = cachedService
        if (existing != null && runCatching { existing.asBinder().isBinderAlive && existing.asBinder().pingBinder() }.getOrDefault(false)) {
            return@synchronized try {
                val res = block(existing)
                scheduleIdleUnbind()
                res
            } catch (t: Throwable) {
                // Binder died mid-call -> clear cache cleanly
                clearCacheLockedAndUnbind()
                onError(checkStatus(appContext), t.message ?: t.javaClass.simpleName)
            }
        }

        // Stale or dead cached service -> unbind before re-binding
        if (cachedConnection != null) {
            clearCacheLockedAndUnbind()
        }

        val serviceArgs = Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, NavBarUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("navbar_ctrl")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

        val latch = CountDownLatch(1)
        val serviceRef = AtomicReference<INavBarControlService?>(null)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    serviceRef.set(INavBarControlService.Stub.asInterface(binder))
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceRef.set(null)
                // Drop reference if this connection was the cached one
                if (cachedConnection === this) {
                    cachedService = null
                    cachedConnection = null
                    cachedServiceArgs = null
                    idleHandler.removeCallbacks(idleUnbindRunnable)
                }
            }
        }

        val bindAttempt = runCatching {
            Shizuku.bindUserService(serviceArgs, connection)
        }
        if (bindAttempt.isFailure) {
            return@synchronized onError(
                checkStatus(appContext),
                bindAttempt.exceptionOrNull()?.message.orEmpty()
            )
        }

        val connected = runCatching {
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)

        val service = serviceRef.get()
        if (!connected || service == null) {
            runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
            return@synchronized onError(checkStatus(appContext), "UserService bind timed out")
        }

        cachedService = service
        cachedConnection = connection
        cachedServiceArgs = serviceArgs

        return@synchronized try {
            val res = block(service)
            scheduleIdleUnbind()
            res
        } catch (t: Throwable) {
            clearCacheLockedAndUnbind()
            onError(checkStatus(appContext), t.message ?: t.javaClass.simpleName)
        }
    }

    private fun clearCacheLockedAndUnbind() {
        idleHandler.removeCallbacks(idleUnbindRunnable)
        val args = cachedServiceArgs
        val conn = cachedConnection
        cachedService = null
        cachedConnection = null
        cachedServiceArgs = null
        if (args != null && conn != null) {
            runCatching { Shizuku.unbindUserService(args, conn, true) }
        }
    }
}
