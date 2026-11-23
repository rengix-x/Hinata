package com.edgeflow.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.widget.Toast
import com.edgeflow.data.EdgeAction
import com.edgeflow.data.EdgeActionType
import com.edgeflow.data.EdgeConfig
import com.example.R

/**
 * Unified action executor for all [EdgeAction]s:
 * - Conflict-free GLOBAL_ACTION_RECENTS (hides overlays first + 20ms flush + HyperOS HOME->RECENTS compat mode)
 * - Blazing-fast PREVIOUS_APP direct launch from [RecentAppsTracker] (POCO Launcher independent Alt+Tab)
 * - Non-root Circle to Search with 1-frame clean screen capture delay
 * - Safe CameraManager torch toggle & direct app launch
 */
class ActionExecutor(
    private val service: AccessibilityService,
    private val recentAppsTracker: RecentAppsTracker,
    private val onBeforeScreenCapture: () -> Unit = {},
    private val onBeforeOverlayTransition: () -> Unit = {},
    private val onScreenTransition: () -> Unit = {},
    private val onToggleScreenTranslate: () -> Boolean = { false },
    private val onTranslateScreenOnce: () -> Boolean = { false }
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val floatingWindowLauncher = FloatingWindowLauncher(service)
    private val cameraManager: CameraManager? =
        service.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    @Volatile
    private var config: EdgeConfig = EdgeConfig()

    @Volatile
    private var torchCameraId: String? = null

    @Volatile
    private var isTorchEnabled: Boolean = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (torchCameraId == null || cameraId == torchCameraId) {
                torchCameraId = cameraId
                isTorchEnabled = enabled
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == torchCameraId) {
                isTorchEnabled = false
            }
        }
    }

    private val directRecentsRunnable = Runnable {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    private val hyperOsCompatRecentsRunnable = Runnable {
        val homeSent = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        if (homeSent) {
            mainHandler.removeCallbacks(directRecentsRunnable)
            mainHandler.postDelayed(
                directRecentsRunnable,
                config.hyperOsRecentsDelayMs.toLong()
            )
        } else {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        }
    }

    private val delayedCircleToSearchRunnable = Runnable {
        val cfg = config
        CircleToSearchTrigger.trigger(
            context = service,
            languageTag = cfg.appLanguageTag,
            autoRepairWithShizuku = cfg.ctsAutoRepairAssistantWithShizuku
        )
    }

    private val circleToSearchFrameCallback = Choreographer.FrameCallback {
        mainHandler.removeCallbacks(delayedCircleToSearchRunnable)
        mainHandler.postDelayed(delayedCircleToSearchRunnable, CAPTURE_FLUSH_DELAY_MS)
    }

    init {
        torchCameraId = resolveTorchCameraId()
        runCatching {
            cameraManager?.registerTorchCallback(torchCallback, mainHandler)
        }
    }

    fun updateConfig(newConfig: EdgeConfig) {
        config = newConfig
        floatingWindowLauncher.updateConfig(newConfig)
    }

    fun triggerControlCenterFloatScan(mode: String): Boolean {
        onBeforeOverlayTransition()
        return floatingWindowLauncher.triggerControlCenterFloatScan(mode)
    }

    fun execute(action: EdgeAction): Boolean {
        return when (action.type) {
            EdgeActionType.BACK -> {
                onScreenTransition()
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }

            EdgeActionType.HOME -> {
                onScreenTransition()
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }

            EdgeActionType.RECENTS -> {
                onScreenTransition()
                executeRecentsConflictFree()
            }

            EdgeActionType.PREVIOUS_APP -> {
                onScreenTransition()
                executeFastPreviousApp()
            }

            EdgeActionType.NOTIFICATIONS ->
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

            EdgeActionType.QUICK_SETTINGS ->
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)

            EdgeActionType.LOCK_SCREEN -> {
                onScreenTransition()
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            }

            EdgeActionType.SCREENSHOT -> {
                onBeforeScreenCapture()
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            }

            EdgeActionType.SPLIT_SCREEN -> {
                onScreenTransition()
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            }

            EdgeActionType.FLOAT_CURRENT_APP ->
                floatingWindowLauncher.floatCurrentApp(recentAppsTracker, onBeforeOverlayTransition)

            EdgeActionType.CIRCLE_TO_SEARCH -> {
                onBeforeScreenCapture()
                Choreographer.getInstance().removeFrameCallback(circleToSearchFrameCallback)
                Choreographer.getInstance().postFrameCallback(circleToSearchFrameCallback)
                true
            }

            EdgeActionType.LAUNCH_APP -> {
                onScreenTransition()
                launchApplication(action.packageName)
            }

            EdgeActionType.LAUNCH_APP_FLOATING -> {
                onScreenTransition()
                onBeforeOverlayTransition()
                floatingWindowLauncher.launchInFloatingWindow(action.packageName)
            }

            EdgeActionType.LAUNCH_SHORTCUT -> {
                onScreenTransition()
                launchShortcut(action)
            }

            EdgeActionType.OPEN_URL -> {
                onScreenTransition()
                openUrl(action.targetData)
            }

            EdgeActionType.TOGGLE_TORCH -> toggleTorch()

            EdgeActionType.TOGGLE_SCREEN_TRANSLATE -> {
                onBeforeOverlayTransition()
                onToggleScreenTranslate()
            }

            EdgeActionType.TRANSLATE_SCREEN_ONCE -> {
                onBeforeOverlayTransition()
                onTranslateScreenOnce()
            }

            EdgeActionType.OPEN_QUICK_MENU,
            EdgeActionType.OPEN_RECENT_SWITCHER,
            EdgeActionType.NONE -> false
        }
    }

    /**
     * Immediately hides our overlays and waits 20ms before invoking GLOBAL_ACTION_RECENTS
     * to avoid HyperOS window-transition contention. When HyperOS compat mode is active and
     * a regular app is in foreground, sends HOME -> delay -> RECENTS automatically.
     */
    private fun executeRecentsConflictFree(): Boolean {
        runCatching {
            service.sendBroadcast(
                Intent(ACTION_PURGE_SETTINGS_TASK_FROM_RECENTS).setPackage(service.packageName)
            )
        }
        onBeforeOverlayTransition()
        mainHandler.removeCallbacks(directRecentsRunnable)
        mainHandler.removeCallbacks(hyperOsCompatRecentsRunnable)

        val useHyperOsCompat =
            com.edgeflow.data.DeviceCompatibilityHelper.resolveCapabilities().supportsHyperOsRecentsCompat &&
                config.hyperOsRecentsCompatMode &&
                !recentAppsTracker.isOnHomeScreen()
        if (useHyperOsCompat) {
            mainHandler.postDelayed(hyperOsCompatRecentsRunnable, RECENTS_OVERLAY_SETTLE_MS)
        } else {
            mainHandler.postDelayed(directRecentsRunnable, RECENTS_OVERLAY_SETTLE_MS)
        }
        return true
    }

    /**
     * Blazing-fast Alt+Tab using RecentAppsTracker (independent of POCO Launcher Recents).
     * Falls back cleanly to Recents only if no prior app is recorded yet.
     */
    private fun executeFastPreviousApp(): Boolean {
        val previousPkg = recentAppsTracker.getPreviousApp()
        if (!previousPkg.isNullOrEmpty() && launchApplication(previousPkg)) {
            return true
        }
        return executeRecentsConflictFree()
    }

    fun launchApplication(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return runCatching {
            val launchIntent = service.packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            service.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    /**
     * Opens any URL/deep-link via standard Android ACTION_VIEW (requires ZERO INTERNET permission).
     */
    fun openUrl(rawUrl: String): Boolean {
        val normalized = normalizeAndValidateUrl(rawUrl)
        if (normalized == null) {
            showToast(R.string.toast_url_open_failed)
            return false
        }
        val launched = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
            true
        }.getOrDefault(false)
        if (!launched) {
            showToast(R.string.toast_url_open_failed)
        }
        return launched
    }

    /**
     * Launches a saved Intent URI shortcut. Gracefully falls back to launching the host app
     * if the target activity rejects direct start (e.g., SecurityException on non-exported target).
     */
    fun launchShortcut(action: EdgeAction): Boolean {
        val uriString = action.targetData.trim()
        if (uriString.isNotEmpty()) {
            val directOk = runCatching {
                val intent = Intent.parseUri(uriString, Intent.URI_INTENT_SCHEME).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(intent)
                true
            }.getOrDefault(false)
            if (directOk) return true
        }

        if (action.packageName.isNotBlank() && launchApplication(action.packageName)) {
            return true
        }
        showToast(R.string.toast_shortcut_launch_failed)
        return false
    }

    private fun showToast(resId: Int) {
        val langTag = config.appLanguageTag
        mainHandler.post {
            runCatching {
                val localized = LocalizedContextHelper.forTag(service.applicationContext, langTag)
                Toast.makeText(service.applicationContext, localized.getString(resId), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleTorch(): Boolean {
        val manager = cameraManager ?: return false
        val camId = torchCameraId ?: resolveTorchCameraId()?.also { torchCameraId = it } ?: return false
        return runCatching {
            val nextState = !isTorchEnabled
            manager.setTorchMode(camId, nextState)
            isTorchEnabled = nextState
            true
        }.getOrDefault(false)
    }

    private fun resolveTorchCameraId(): String? {
        val manager = cameraManager ?: return null
        return runCatching {
            var fallbackId: String? = null
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (!hasFlash) continue
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id
                }
                if (fallbackId == null) fallbackId = id
            }
            fallbackId
        }.getOrNull()
    }

    fun release() {
        mainHandler.removeCallbacks(directRecentsRunnable)
        mainHandler.removeCallbacks(hyperOsCompatRecentsRunnable)
        mainHandler.removeCallbacks(delayedCircleToSearchRunnable)
        runCatching {
            Choreographer.getInstance().removeFrameCallback(circleToSearchFrameCallback)
        }
        runCatching {
            cameraManager?.unregisterTorchCallback(torchCallback)
        }
        floatingWindowLauncher.release()
    }

    companion object {
        const val ACTION_PURGE_SETTINGS_TASK_FROM_RECENTS =
            "io.github.rengixx.hinata.action.PURGE_SETTINGS_TASK_FROM_RECENTS"

        private const val RECENTS_OVERLAY_SETTLE_MS = 20L
        private const val CAPTURE_FLUSH_DELAY_MS = 18L

        private val EXPLICIT_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.+")

        /**
         * Validates and normalizes user-entered URLs or deep links:
         * - Rejects empty strings or strings containing whitespace.
         * - Preserves valid explicit schemes (`https://...`, `intent://...`, `myapp://...`).
         * - Prepends `https://` to bare domain names containing a dot (e.g. `google.com`).
         * - Rejects bare words without scheme and dot (e.g. `test`, `localhost_without_scheme`).
         */
        fun normalizeAndValidateUrl(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.any { it.isWhitespace() }) return null

            if (trimmed.contains("://")) {
                if (!EXPLICIT_SCHEME_REGEX.matches(trimmed)) return null
                val afterScheme = trimmed.substringAfter("://")
                if (afterScheme.isEmpty() || afterScheme.startsWith("/")) {
                    // Allow deep-link schemes with paths (e.g. file-like or custom schemes) only if non-empty host/path
                    if (afterScheme.length <= 1) return null
                }
                return trimmed
            }

            // Without an explicit scheme, require a dot separating non-empty domain labels
            val hostPart = trimmed.substringBefore('/').substringBefore('?').substringBefore('#')
            val dotIdx = hostPart.indexOf('.')
            if (dotIdx <= 0 || dotIdx >= hostPart.length - 1) return null
            if (hostPart.contains("..")) return null
            return "https://$trimmed"
        }
    }
}
