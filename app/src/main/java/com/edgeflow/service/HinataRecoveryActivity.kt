package com.edgeflow.service

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.edgeflow.data.ConfigStore
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import com.example.R

/**
 * Ultra-lightweight transparent emergency recovery Activity running in the default resident
 * process (ZERO Jetpack Compose imports).
 *
 * Launched by:
 * - Home-screen Launcher App Shortcuts (`res/xml/shortcuts.xml`)
 * - Keep-alive Foreground Notification 1-tap action buttons
 *
 * Security hardening:
 * - Strictly ignores all arbitrary Intent extras and unknown actions.
 * - Accepts ONLY the two fixed recovery actions [ACTION_REVIVE_SERVICE] and
 *   [ACTION_RESTORE_SYSTEM_NAV].
 * - Always excludes itself from Recents and exits cleanly via [finishAndRemoveTask].
 */
class HinataRecoveryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        excludeFromRecentsNow()

        val appCtx = applicationContext
        ShizukuShellExecutor.initMultiProcess(appCtx, isMainProcess = true)

        when (intent?.action) {
            ACTION_REVIVE_SERVICE -> handleReviveService(appCtx)
            ACTION_RESTORE_SYSTEM_NAV -> handleRestoreSystemNav(appCtx)
            else -> {
                // Ignore unknown actions or untrusted external invocations cleanly
            }
        }

        finishAndRemoveTask()
    }

    private fun handleReviveService(appCtx: Context) {
        val config = ConfigStore.load(appCtx)
        val langTag = config.appLanguageTag

        if (ShizukuShellExecutor.isReady()) {
            Thread({
                val result = ShizukuShellExecutor.reviveAccessibilityServiceWithShizuku(
                    context = appCtx,
                    forceToggleRebind = true
                )
                MAIN_HANDLER.post {
                    if (result.success) {
                        showLocalizedToast(appCtx, langTag, R.string.recovery_toast_revive_success)
                    } else {
                        showLocalizedToast(
                            appCtx,
                            langTag,
                            R.string.recovery_toast_revive_failed_open_settings
                        )
                        openAccessibilitySettings(appCtx)
                    }
                }
            }, "hinata-recovery-revive").apply {
                isDaemon = true
                start()
            }
        } else {
            openAccessibilitySettings(appCtx)
            showLocalizedToast(appCtx, langTag, R.string.recovery_toast_open_accessibility_manual)
        }
    }

    private fun handleRestoreSystemNav(appCtx: Context) {
        val config = ConfigStore.load(appCtx)
        val langTag = config.appLanguageTag

        if (ShizukuShellExecutor.isReady()) {
            Thread({
                val result = ShizukuShellExecutor.restoreAllSystemNavigation(appCtx)
                if (result.success && (config.autoHideNavBarWithShizuku || config.shizukuSystemNavModified)) {
                    ConfigStore.save(
                        appCtx,
                        config.copy(
                            autoHideNavBarWithShizuku = false,
                            shizukuSystemNavModified = false
                        )
                    )
                }
                MAIN_HANDLER.post {
                    if (result.success) {
                        showLocalizedToast(appCtx, langTag, R.string.recovery_toast_nav_restored)
                    } else {
                        showLocalizedToast(
                            appCtx,
                            langTag,
                            R.string.recovery_toast_nav_restore_failed_open_settings
                        )
                        openSystemNavigationSettings(appCtx)
                    }
                }
            }, "hinata-recovery-restore-nav").apply {
                isDaemon = true
                start()
            }
        } else {
            openSystemNavigationSettings(appCtx)
            showLocalizedToast(appCtx, langTag, R.string.recovery_toast_open_nav_settings_manual)
        }
    }

    private fun excludeFromRecentsNow() {
        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            am.appTasks?.forEach { task ->
                runCatching { task.setExcludeFromRecents(true) }
            }
        }
    }

    private fun openAccessibilitySettings(appCtx: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appCtx.startActivity(intent) }
    }

    private fun openSystemNavigationSettings(appCtx: Context) {
        val gestureNavIntent = Intent(ACTION_GESTURE_NAVIGATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { appCtx.startActivity(gestureNavIntent) }.isSuccess) {
            return
        }
        val fallbackSettings = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appCtx.startActivity(fallbackSettings) }
    }

    private fun showLocalizedToast(appCtx: Context, langTag: String, msgRes: Int) {
        runCatching {
            val localized = LocalizedContextHelper.forTag(appCtx, langTag)
            Toast.makeText(appCtx, localized.getString(msgRes), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val ACTION_REVIVE_SERVICE = "io.github.rengixx.hinata.action.REVIVE_SERVICE"
        const val ACTION_RESTORE_SYSTEM_NAV = "io.github.rengixx.hinata.action.RESTORE_SYSTEM_NAV"
        private const val ACTION_GESTURE_NAVIGATION_SETTINGS =
            "android.settings.GESTURE_NAVIGATION_SETTINGS"

        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
    }
}
