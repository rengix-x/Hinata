package com.edgeflow.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.edgeflow.data.ConfigStore
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import com.example.R

/**
 * Quick Settings / Control Center 1-tap emergency recovery Tile running in the default resident
 * process (ZERO Jetpack Compose imports).
 *
 * Guarantees 1-tap escape from any screen:
 * - When Shizuku is connected: force-rebinds Hinata's AccessibilityService, waits 450ms to verify
 *   live recovery, and automatically restores OS 3-button / back navigation if still dead or if
 *   3-button nav-hide escape is needed.
 * - When Shizuku is not connected: opens Accessibility settings directly if stopped, or Hinata's
 *   System/Recovery settings tab if already running (API 34+ PendingIntent safe).
 */
class HinataRecoveryTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        val appCtx = applicationContext
        ShizukuShellExecutor.initMultiProcess(appCtx, isMainProcess = true)

        val config = ConfigStore.load(appCtx)
        val langTag = config.appLanguageTag
        val wasActive = EdgeAccessibilityService.getActiveInstance() != null

        if (ShizukuShellExecutor.isReady()) {
            Thread({
                val reviveResult = ShizukuShellExecutor.reviveAccessibilityServiceWithShizuku(
                    context = appCtx,
                    forceToggleRebind = true
                )
                runCatching { Thread.sleep(REVIVE_VERIFY_DELAY_MS) }

                val activeAfterRevive = EdgeAccessibilityService.getActiveInstance() != null
                val needsEmergencyNavRestore = !activeAfterRevive ||
                    (wasActive && (config.autoHideNavBarWithShizuku || config.shizukuSystemNavModified))

                val toastMsgRes = if (needsEmergencyNavRestore) {
                    val restoreResult = ShizukuShellExecutor.restoreAllSystemNavigation(appCtx)
                    if (restoreResult.success &&
                        (config.autoHideNavBarWithShizuku || config.shizukuSystemNavModified)
                    ) {
                        ConfigStore.save(
                            appCtx,
                            config.copy(
                                autoHideNavBarWithShizuku = false,
                                shizukuSystemNavModified = false
                            )
                        )
                    }
                    when {
                        restoreResult.success -> R.string.recovery_toast_nav_restored
                        reviveResult.success && activeAfterRevive -> R.string.recovery_toast_revive_success
                        else -> R.string.recovery_toast_nav_restore_failed_open_settings
                    }
                } else {
                    if (reviveResult.success || activeAfterRevive) {
                        R.string.recovery_toast_revive_success
                    } else {
                        R.string.recovery_toast_revive_failed_open_settings
                    }
                }

                mainHandler.post {
                    refreshTileState()
                    showLocalizedToast(appCtx, langTag, toastMsgRes)
                }
            }, "hinata-qs-recovery").apply {
                isDaemon = true
                start()
            }
        } else {
            if (!wasActive) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchActivityAndCollapseCompat(intent, REQUEST_CODE_ACCESSIBILITY)
                showLocalizedToast(appCtx, langTag, R.string.recovery_toast_open_accessibility_manual)
            } else {
                val intent = Intent().apply {
                    setClassName(packageName, SETTINGS_ACTIVITY_CLASS)
                    putExtra(EXTRA_OPEN_SYSTEM_TAB, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                launchActivityAndCollapseCompat(intent, REQUEST_CODE_SETTINGS)
            }
        }
    }

    private fun refreshTileState() {
        val tile = qsTile ?: return
        val isActive = EdgeAccessibilityService.getActiveInstance() != null
        val config = ConfigStore.load(applicationContext)
        val localized = LocalizedContextHelper.forTag(this, config.appLanguageTag)

        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = localized.getString(R.string.qs_tile_recovery_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = localized.getString(
                if (isActive) {
                    R.string.qs_tile_recovery_subtitle_active
                } else {
                    R.string.qs_tile_recovery_subtitle_inactive
                }
            )
        }
        runCatching { tile.updateTile() }
    }

    private fun launchActivityAndCollapseCompat(intent: Intent, requestCode: Int) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure {
            runCatching { startActivity(intent) }
        }
    }

    private fun showLocalizedToast(appCtx: Context, langTag: String, msgRes: Int) {
        runCatching {
            val localized = LocalizedContextHelper.forTag(appCtx, langTag)
            Toast.makeText(appCtx, localized.getString(msgRes), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REVIVE_VERIFY_DELAY_MS = 450L
        private const val REQUEST_CODE_ACCESSIBILITY = 4201
        private const val REQUEST_CODE_SETTINGS = 4202
        private const val SETTINGS_ACTIVITY_CLASS = "com.edgeflow.settings.SettingsActivity"
        const val EXTRA_OPEN_SYSTEM_TAB = "open_system_tab"
    }
}
