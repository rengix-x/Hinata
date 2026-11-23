package com.edgeflow.service.shizuku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.edgeflow.data.ConfigStore
import com.edgeflow.data.DeviceCompatibilityHelper
import com.edgeflow.data.DeviceFamily
import com.edgeflow.data.EdgeConfig
import com.edgeflow.service.LocalizedContextHelper
import com.example.R
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pure-Kotlin infinite-loop protection policy for event-driven NavBarAutoGuard.
 *
 * Guarantees:
 * - Minimum cooldown (`2000ms`) between consecutive re-apply attempts.
 * - Rolling failure window (`10_000ms`): reaching `3` failures within 10 seconds signals
 *   an immediate automatic shutoff to prevent loops on restricted ROMs.
 */
class AutoGuardRetryPolicy(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val failureWindowMs: Long = DEFAULT_FAILURE_WINDOW_MS,
    private val maxFailuresInWindow: Int = DEFAULT_MAX_FAILURES_IN_WINDOW
) {
    private var lastAttemptMs: Long = Long.MIN_VALUE
    private val failureTimestamps = ArrayDeque<Long>(maxFailuresInWindow + 1)

    @Synchronized
    fun tryAcquireAttempt(nowMs: Long): Boolean {
        if (lastAttemptMs != Long.MIN_VALUE && (nowMs - lastAttemptMs) < cooldownMs) {
            return false
        }
        lastAttemptMs = nowMs
        return true
    }

    @Synchronized
    fun recordSuccess() {
        failureTimestamps.clear()
    }

    /**
     * Records a failed command attempt and returns `true` when the auto-disable threshold
     * (3 failures within 10 seconds) has been reached.
     */
    @Synchronized
    fun recordFailure(nowMs: Long): Boolean {
        val cutoff = nowMs - failureWindowMs
        while (failureTimestamps.isNotEmpty() && failureTimestamps.peekFirst()!! < cutoff) {
            failureTimestamps.pollFirst()
        }
        failureTimestamps.addLast(nowMs)
        return failureTimestamps.size >= maxFailuresInWindow
    }

    @Synchronized
    fun reset() {
        lastAttemptMs = Long.MIN_VALUE
        failureTimestamps.clear()
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 2_000L
        const val DEFAULT_FAILURE_WINDOW_MS = 10_000L
        const val DEFAULT_MAX_FAILURES_IN_WINDOW = 3
    }
}

/**
 * Zero-battery-drain, purely event-driven multi-OEM navigation bar auto-guard.
 *
 * Architectural guarantees:
 * - NO timers, NO polling loops, NO foreground wakelocks.
 * - On Xiaomi/HyperOS: registers a ContentObserver on `Settings.Global/force_fsg_nav_bar`
 *   plus `Intent.ACTION_USER_PRESENT` broadcast receiver.
 * - On ColorOS/realme UI, OriginOS, One UI, and AOSP/Pixel: registers `Intent.ACTION_USER_PRESENT`
 *   to silently re-apply `hideSystemNavBarForDevice` on unlock with 2000ms cooldown.
 * - Silently skips with ZERO Toast whenever Shizuku is stopped or unpermitted.
 * - Immediately unregisters all listeners when disabled or when the service tears down.
 */
class NavBarAutoGuard(
    private val context: Context,
    private val retryPolicy: AutoGuardRetryPolicy = AutoGuardRetryPolicy()
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deviceFamily: DeviceFamily = DeviceCompatibilityHelper.detectDeviceFamily()

    @Volatile
    private var currentConfig: EdgeConfig = EdgeConfig()

    @Volatile
    private var isRegistered: Boolean = false

    private var navBarObserver: ContentObserver? = null
    private var unlockReceiver: BroadcastReceiver? = null
    private var workerExecutor: ExecutorService? = null

    fun applyConfig(config: EdgeConfig) {
        currentConfig = config
        if (config.autoHideNavBarWithShizuku) {
            if (!isRegistered) {
                retryPolicy.reset()
                registerListeners()
            }
        } else {
            unregisterListeners()
        }
    }

    fun release() {
        unregisterListeners()
        workerExecutor?.shutdownNow()
        workerExecutor = null
    }

    private fun registerListeners() {
        if (isRegistered) return

        if (deviceFamily == DeviceFamily.XIAOMI_HYPEROS) {
            val observer = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    onPotentialNavBarReset()
                }
            }
            val uri = Settings.Global.getUriFor(SETTING_FORCE_FSG_NAV_BAR)
            runCatching {
                appContext.contentResolver.registerContentObserver(uri, false, observer)
            }
            navBarObserver = observer
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    onPotentialNavBarReset()
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(Intent.ACTION_USER_PRESENT),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        unlockReceiver = receiver
        isRegistered = true
    }

    private fun unregisterListeners() {
        if (!isRegistered) return
        navBarObserver?.let { observer ->
            runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
        }
        navBarObserver = null

        unlockReceiver?.let { receiver ->
            runCatching { appContext.unregisterReceiver(receiver) }
        }
        unlockReceiver = null
        isRegistered = false
    }

    private fun onPotentialNavBarReset() {
        val configSnapshot = currentConfig
        if (!configSnapshot.autoHideNavBarWithShizuku) return

        if (deviceFamily == DeviceFamily.XIAOMI_HYPEROS) {
            val currentValue = runCatching {
                Settings.Global.getInt(appContext.contentResolver, SETTING_FORCE_FSG_NAV_BAR, -1)
            }.getOrDefault(-1)

            // Only re-apply when HyperOS explicitly reset force_fsg_nav_bar back to 0
            if (currentValue != 0) return
        }

        // Silently skip (zero Toast!) when Shizuku is not active or not permitted
        if (!ShizukuShellExecutor.isReady()) return

        val now = SystemClock.elapsedRealtime()
        if (!retryPolicy.tryAcquireAttempt(now)) return

        val executor = ensureExecutor()
        executor.execute {
            runReapplyOnWorker(configSnapshot.hideGestureLine)
        }
    }

    private fun runReapplyOnWorker(hideGestureLine: Boolean) {
        if (!currentConfig.autoHideNavBarWithShizuku) return
        if (!ShizukuShellExecutor.isReady()) return

        val result = ShizukuShellExecutor.hideSystemNavBarForDevice(
            context = appContext,
            deviceFamily = deviceFamily,
            hideGestureLine = hideGestureLine,
            requireAccessibilityGuard = false
        )

        if (result.success) {
            retryPolicy.recordSuccess()
        } else {
            val shouldDisable = retryPolicy.recordFailure(SystemClock.elapsedRealtime())
            if (shouldDisable) {
                autoDisableDueToRepeatedFailures()
            }
        }
    }

    private fun autoDisableDueToRepeatedFailures() {
        val latest = ConfigStore.load(appContext)
        if (latest.autoHideNavBarWithShizuku) {
            ConfigStore.save(appContext, latest.copy(autoHideNavBarWithShizuku = false))
        }
        currentConfig = latest.copy(autoHideNavBarWithShizuku = false)
        val langTag = latest.appLanguageTag
        mainHandler.post {
            unregisterListeners()
            runCatching {
                val localized = LocalizedContextHelper.forTag(appContext, langTag)
                Toast.makeText(
                    appContext,
                    localized.getString(R.string.shizuku_auto_hide_disabled_toast),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Synchronized
    private fun ensureExecutor(): ExecutorService {
        val existing = workerExecutor
        if (existing != null && !existing.isShutdown) return existing
        return Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "edgeflow-navbar-guard").apply {
                isDaemon = true
            }
        }.also { workerExecutor = it }
    }

    companion object {
        const val SETTING_FORCE_FSG_NAV_BAR = "force_fsg_nav_bar"
    }
}
