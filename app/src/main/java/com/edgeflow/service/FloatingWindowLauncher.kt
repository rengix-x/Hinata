package com.edgeflow.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.annotation.Keep
import com.edgeflow.data.EdgeConfig
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import com.example.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Zero-crash Floating Window ("小窓化") launcher supporting both Shizuku-ON (instant UID 2000
 * HyperOS native scaled freeform) and Shizuku-OFF (transparent [FloatingProxyActivity] +
 * [ControlCenterFloatingMacro] tile auto-tap).
 *
 * Pure Kotlin / Android framework only — ZERO Jetpack Compose imports.
 */
@Keep
class FloatingWindowLauncher(private val service: AccessibilityService) {

    private val appContext: Context = service.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EdgeFlow-FloatWin").apply { isDaemon = true }
    }

    @Volatile
    private var currentConfig: EdgeConfig = EdgeConfig()

    @Volatile
    private var appLanguageTag: String = ""

    @Volatile
    private var noShizukuControlCenterFloat: Boolean = true

    private val floatCurrentSeq = AtomicInteger(0)

    private val controlCenterMacro = ControlCenterFloatingMacro(
        service = service,
        configProvider = { currentConfig }
    )

    fun updateConfig(config: EdgeConfig) {
        currentConfig = config
        appLanguageTag = config.appLanguageTag
        noShizukuControlCenterFloat = config.noShizukuControlCenterFloat
    }

    fun updateLanguageTag(tag: String) {
        appLanguageTag = tag
        currentConfig = currentConfig.copy(appLanguageTag = tag)
    }

    fun triggerControlCenterFloatScan(mode: String): Boolean {
        controlCenterMacro.calibrateTilePosition(mode)
        return true
    }

    fun launchInFloatingWindow(packageName: String): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return false

        val launchIntent = resolveLaunchIntent(pkg) ?: return false

        executor.execute {
            val intentCopy = Intent(launchIntent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            MiuiFreeformOptions.applyMiuiFreeformFlags(intentCopy)

            val isHyperOs = com.edgeflow.data.DeviceCompatibilityHelper.resolveCapabilities()
                .supportsHyperOsNativeFloatingWindow
            val shizukuReady = ShizukuShellExecutor.isReady()

            if (!isHyperOs) {
                // Non-HyperOS OS: strictly standard Android freeform via Shizuku only
                if (shizukuReady) {
                    val componentFlat = launchIntent.component?.flattenToShortString().orEmpty()
                    if (componentFlat.isNotEmpty()) {
                        val shellResult = runCatching {
                            ShizukuShellExecutor.launchInFloatingWindow(
                                context = appContext,
                                componentFlat = componentFlat,
                                flagsHex = null
                            )
                        }.getOrNull()
                        if (shellResult != null && shellResult.success) {
                            recordLocalTrace(appContext, "launch($pkg): non-HyperOS Shizuku am freeform OK")
                            return@execute
                        }
                    }
                }
                showToast(R.string.floating_window_hyperos_or_shizuku_only)
                return@execute
            }

            // Route A (Shizuku ON — Primary, zero detour): Warm-cached UID 2000 HyperOS native scaled freeform
            if (shizukuReady) {
                val nativeOk = ShizukuShellExecutor.launchNativeHyperOsFreeform(
                    context = appContext,
                    packageName = pkg,
                    launchIntent = intentCopy
                )
                if (nativeOk) {
                    recordLocalTrace(appContext, "launch($pkg): Shizuku native OK")
                    return@execute
                }

                // Shizuku fallback: `am start-activity --windowingMode 5`
                val componentFlat = launchIntent.component?.flattenToShortString().orEmpty()
                if (componentFlat.isNotEmpty()) {
                    val shellResult = runCatching {
                        ShizukuShellExecutor.launchInFloatingWindow(
                            context = appContext,
                            componentFlat = componentFlat,
                            flagsHex = null
                        )
                    }.getOrNull()
                    if (shellResult != null && shellResult.success) {
                        recordLocalTrace(appContext, "launch($pkg): Shizuku am freeform OK")
                        return@execute
                    }
                }
            }

            // Route B (Shizuku OFF): Transparent FloatingProxyActivity with Activity context.
            // Once FloatingProxyActivity.launch(...) returns true, treat launch as succeeded and
            // never invoke ControlCenterFloatingMacro (prevents accidental Control Center popups).
            val anchor = computeCenterAnchor()
            val proxyLaunched = FloatingProxyActivity.launch(
                context = service,
                targetIntent = intentCopy,
                packageName = pkg,
                anchorX = anchor.first,
                anchorY = anchor.second
            )
            recordLocalTrace(
                appContext,
                "launch($pkg): shizuku=$shizukuReady proxyLaunched=$proxyLaunched"
            )

            if (proxyLaunched) {
                return@execute
            }

            showToast(R.string.floating_window_need_shizuku)
        }
        return true
    }

    fun floatCurrentApp(
        recentAppsTracker: RecentAppsTracker,
        onBeforeTransition: () -> Unit
    ): Boolean {
        // Immediately hide Hinata overlays so window transitions stay glitch-free
        onBeforeTransition()

        executor.execute {
            val windowPkg = resolveCurrentForegroundPackageFromWindows(recentAppsTracker)
            val trackerPkg = recentAppsTracker.getCurrentForegroundPackage().trim()
            val validTrackerPkg = trackerPkg.takeIf {
                it.isNotEmpty() &&
                    it != appContext.packageName &&
                    !recentAppsTracker.isSystemOrLauncherPackage(it) &&
                    !recentAppsTracker.isOnHomeScreen()
            }.orEmpty()
            val resolvedForegroundPkg = windowPkg.ifEmpty { validTrackerPkg }

            val isHyperOs = com.edgeflow.data.DeviceCompatibilityHelper.resolveCapabilities()
                .supportsHyperOsNativeFloatingWindow
            val shizukuReady = ShizukuShellExecutor.isReady()
            val resolvedLaunchIntent = resolvedForegroundPkg.takeIf { it.isNotEmpty() }?.let {
                resolveLaunchIntent(it)
            }

            if (!isHyperOs) {
                if (shizukuReady) {
                    val shizukuPkg = ShizukuShellExecutor.getForegroundPackage(appContext).trim()
                    val currentPkg = shizukuPkg.ifEmpty { resolvedForegroundPkg }
                    if (currentPkg.isNotEmpty() &&
                        currentPkg != appContext.packageName &&
                        !recentAppsTracker.isSystemOrLauncherPackage(currentPkg)
                    ) {
                        val launchIntent = resolveLaunchIntent(currentPkg)
                        val componentFlat = launchIntent?.component?.flattenToShortString().orEmpty()
                        if (componentFlat.isNotEmpty()) {
                            val shellResult = runCatching {
                                ShizukuShellExecutor.launchInFloatingWindow(
                                    context = appContext,
                                    componentFlat = componentFlat,
                                    flagsHex = FLOAT_CURRENT_FLAGS_HEX
                                )
                            }.getOrNull()
                            if (shellResult != null && shellResult.success) {
                                recordLocalTrace(appContext, "floatCurrent($currentPkg): non-HyperOS Shizuku am freeform OK")
                                return@execute
                            }
                        }
                    }
                    showToast(R.string.floating_window_cannot_float_current)
                    return@execute
                }
                showToast(R.string.floating_window_hyperos_or_shizuku_only)
                return@execute
            }

            // Route A (Shizuku ON — Primary, zero detour): Warm-cached UID 2000 HyperOS native foreground small-freeform
            if (shizukuReady) {
                val nativeFloatOk = ShizukuShellExecutor.floatForegroundNativeHyperOs(
                    context = appContext,
                    fallbackPackageName = resolvedForegroundPkg,
                    fallbackLaunchIntent = resolvedLaunchIntent
                )
                if (nativeFloatOk) {
                    recordLocalTrace(appContext, "floatCurrent: Shizuku native OK")
                    return@execute
                }

                // Shizuku fallback: resolve foreground package & `am start-activity --windowingMode 5`
                val shizukuPkg = ShizukuShellExecutor.getForegroundPackage(appContext).trim()
                val currentPkg = shizukuPkg.ifEmpty { resolvedForegroundPkg }.ifEmpty { trackerPkg }
                if (currentPkg.isNotEmpty() &&
                    currentPkg != appContext.packageName &&
                    !recentAppsTracker.isSystemOrLauncherPackage(currentPkg)
                ) {
                    val launchIntent = resolveLaunchIntent(currentPkg)
                    val componentFlat = launchIntent?.component?.flattenToShortString().orEmpty()
                    if (componentFlat.isNotEmpty()) {
                        val shellResult = runCatching {
                            ShizukuShellExecutor.launchInFloatingWindow(
                                context = appContext,
                                componentFlat = componentFlat,
                                flagsHex = FLOAT_CURRENT_FLAGS_HEX
                            )
                        }.getOrNull()
                        if (shellResult != null && shellResult.success) {
                            recordLocalTrace(appContext, "floatCurrent($currentPkg): Shizuku am freeform OK")
                            return@execute
                        }
                    }
                }
            }

            // Guard against attempting to float Home launcher or Hinata itself
            if (recentAppsTracker.isOnHomeScreen() && resolvedForegroundPkg.isEmpty()) {
                showToast(R.string.floating_window_cannot_float_current)
                return@execute
            }

            // Route B (HyperOS + Shizuku OFF — 1st Priority): Transparent FloatingProxyActivity
            // using Activity context + MiuiFreeformOptions (proven path from Quick Menu, no Control Center detour)
            val targetPkg = resolvedForegroundPkg.ifEmpty {
                trackerPkg.takeIf {
                    it.isNotEmpty() &&
                        it != appContext.packageName &&
                        !recentAppsTracker.isSystemOrLauncherPackage(it)
                }.orEmpty()
            }

            if (targetPkg.isNotEmpty()) {
                val baseLaunchIntent = if (targetPkg == resolvedForegroundPkg) {
                    resolvedLaunchIntent
                } else {
                    resolveLaunchIntent(targetPkg)
                }
                if (baseLaunchIntent != null) {
                    // Briefly send foreground task to background via GLOBAL_ACTION_HOME (same launch
                    // precondition as Quick Menu), then 160ms later recall it via FloatingProxyActivity
                    // without FLAG_ACTIVITY_RESET_TASK_IF_NEEDED to preserve the current screen state.
                    val token = floatCurrentSeq.incrementAndGet()
                    val launchIntent = Intent(baseLaunchIntent).apply {
                        flags = flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED.inv()
                    }
                    MiuiFreeformOptions.applyMiuiFreeformFlags(launchIntent)
                    val anchor = computeCenterAnchor()
                    val shouldFallbackToCc = !shizukuReady && noShizukuControlCenterFloat

                    mainHandler.post {
                        if (token != floatCurrentSeq.get()) return@post
                        runCatching {
                            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        }
                        mainHandler.postDelayed({
                            if (token != floatCurrentSeq.get()) return@postDelayed
                            val proxyLaunched = FloatingProxyActivity.launch(
                                context = service,
                                targetIntent = launchIntent,
                                packageName = targetPkg,
                                anchorX = anchor.first,
                                anchorY = anchor.second,
                                extraFlags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            )
                            recordLocalTrace(
                                appContext,
                                "floatCurrent($targetPkg): shizuku=$shizukuReady home->160ms->proxyLaunched=$proxyLaunched ccMacro=$shouldFallbackToCc"
                            )
                            if (proxyLaunched) {
                                // Only if still full-screen 680ms after FloatingProxyActivity launch,
                                // fall back to Control Center tile macro when enabled.
                                if (shouldFallbackToCc) {
                                    controlCenterMacro.scheduleFloatAfterAppLaunch(
                                        targetPackage = targetPkg,
                                        delayMs = FLOAT_CURRENT_FALLBACK_DELAY_MS,
                                        onBeforeTransition = onBeforeTransition
                                    )
                                }
                            } else {
                                showToast(R.string.floating_window_need_shizuku)
                            }
                        }, FLOAT_CURRENT_HOME_DELAY_MS)
                    }
                    return@execute
                }
            }

            // Fallback when targetPkg or launchIntent could not be resolved (e.g., special non-launcher screen)
            if (!shizukuReady && noShizukuControlCenterFloat) {
                recordLocalTrace(
                    appContext,
                    "floatCurrent($targetPkg): fallback -> ControlCenterFloatingMacro"
                )
                controlCenterMacro.triggerFloatCurrentApp(onBeforeTransition)
                return@execute
            }

            showToast(
                if (targetPkg.isEmpty()) {
                    R.string.floating_window_cannot_float_current
                } else {
                    R.string.floating_window_need_shizuku
                }
            )
        }
        return true
    }

    private fun resolveCurrentForegroundPackageFromWindows(
        recentAppsTracker: RecentAppsTracker
    ): String {
        val windowList = runCatching { service.windows }.getOrNull() ?: return ""
        if (windowList.isEmpty()) return ""
        val dm = appContext.resources.displayMetrics
        val screenArea = dm.widthPixels.toLong().coerceAtLeast(1L) *
            dm.heightPixels.toLong().coerceAtLeast(1L)
        val rect = Rect()
        var bestPkg = ""
        var bestLayer = Int.MIN_VALUE

        for (i in 0 until windowList.size) {
            val win = windowList[i]
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            win.getBoundsInScreen(rect)
            val width = rect.width().toLong()
            val height = rect.height().toLong()
            if (width <= 0L || height <= 0L) continue
            // Must occupy at least 50% of screen area (ignores floating mini-windows and tiny overlays)
            if (width * height * 2L < screenArea) continue

            val root = runCatching { win.root }.getOrNull() ?: continue
            val pkg = try {
                root.packageName?.toString()?.trim().orEmpty()
            } finally {
                @Suppress("DEPRECATION")
                runCatching { root.recycle() }
            }
            if (pkg.isEmpty()) continue
            if (pkg == appContext.packageName) continue
            if (pkg == "com.android.systemui" || pkg == "android") continue
            if (pkg.contains("inputmethod", ignoreCase = true) ||
                pkg.contains("keyboard", ignoreCase = true)
            ) continue
            if (recentAppsTracker.isSystemOrLauncherPackage(pkg)) continue

            val layer = win.layer
            if (layer >= bestLayer) {
                bestLayer = layer
                bestPkg = pkg
            }
        }
        return bestPkg
    }

    private fun computeCenterAnchor(): Pair<Int, Int> {
        val dm = appContext.resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(720)
        val screenH = dm.heightPixels.coerceAtLeast(1280)
        return Pair(screenW / 2, screenH / 2)
    }

    private fun resolveLaunchIntent(packageName: String): Intent? {
        return runCatching {
            appContext.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull()
    }

    private fun showToast(stringResId: Int) {
        val langTag = appLanguageTag
        mainHandler.post {
            runCatching {
                val localized = LocalizedContextHelper.forTag(appContext, langTag)
                Toast.makeText(
                    appContext,
                    localized.getString(stringResId),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun release() {
        floatCurrentSeq.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        controlCenterMacro.release()
        runCatching { executor.shutdownNow() }
    }

    companion object {
        private const val LOCAL_TRACE_FILE = "local_freeform_trace.txt"
        private const val LOG_TAG = "EdgeFlow-FloatWin"
        // FLAG_ACTIVITY_NEW_TASK (0x10000000) | FLAG_ACTIVITY_REORDER_TO_FRONT (0x00020000)
        private const val FLOAT_CURRENT_FLAGS_HEX = "0x10020000"
        private const val FLOAT_CURRENT_HOME_DELAY_MS = 160L
        private const val FLOAT_CURRENT_FALLBACK_DELAY_MS = 680L

        @Volatile
        var lastLocalFreeformTrace: String = ""
            private set

        internal fun recordLocalTrace(context: Context, trace: String) {
            lastLocalFreeformTrace = trace
            runCatching { android.util.Log.d(LOG_TAG, trace) }
            runCatching {
                java.io.File(context.applicationContext.cacheDir, LOCAL_TRACE_FILE)
                    .writeText(trace)
            }
        }

        fun readLocalTrace(context: Context): String {
            val mem = lastLocalFreeformTrace
            if (mem.isNotBlank()) return mem
            return runCatching {
                val f = java.io.File(context.applicationContext.cacheDir, LOCAL_TRACE_FILE)
                if (f.exists()) f.readText().trim() else ""
            }.getOrDefault("")
        }
    }
}
