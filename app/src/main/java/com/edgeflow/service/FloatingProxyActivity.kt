package com.edgeflow.service

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import androidx.annotation.Keep
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Ultra-lightweight, completely transparent proxy Activity running in the default process
 * (ZERO Jetpack Compose) for Shizuku-OFF floating window launches.
 *
 * Starting an activity with `MiuiFreeformOptions` or `setLaunchWindowingMode(5)` directly from
 * a background `AccessibilityService` context is often blocked or downgraded to full-screen on
 * Android 14–16 / HyperOS due to missing Activity window tokens. Launching from an Activity's
 * own `Context` bypasses service-context restrictions cleanly and exits immediately with zero
 * transition animation.
 */
@Keep
class FloatingProxyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty().trim()
        val anchorX = intent?.getIntExtra(EXTRA_ANCHOR_X, 0) ?: 0
        val anchorY = intent?.getIntExtra(EXTRA_ANCHOR_Y, 0) ?: 0
        val extraFlags = intent?.getIntExtra(EXTRA_EXTRA_FLAGS, 0) ?: 0
        val rawTarget = extractTargetIntent(intent)

        val reorderToFront = (extraFlags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0
        val targetIntent = (rawTarget ?: pkg.takeIf { it.isNotEmpty() }?.let {
            runCatching { packageManager.getLaunchIntentForPackage(it) }.getOrNull()
        })?.let { base ->
            Intent(base).apply {
                if (reorderToFront) {
                    flags = flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED.inv()
                }
                val baseFlags = if (reorderToFront) {
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                } else {
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }
                addFlags(baseFlags or extraFlags)
            }
        }

        if (targetIntent != null) {
            launchUsingActivityContext(targetIntent, pkg, anchorX, anchorY, reorderToFront)
        }

        finish()
        disableTransitions()
    }

    private fun launchUsingActivityContext(
        targetIntent: Intent,
        pkg: String,
        anchorX: Int,
        anchorY: Int,
        reorderToFront: Boolean
    ) {
        MiuiFreeformOptions.ensureHiddenApiExemptions()
        MiuiFreeformOptions.applyMiuiFreeformFlags(targetIntent)
        if (reorderToFront) {
            targetIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }

        val effectiveAnchorX = if (anchorX > 0) anchorX else (resources.displayMetrics.widthPixels / 2)
        val effectiveAnchorY = if (anchorY > 0) anchorY else (resources.displayMetrics.heightPixels / 2)

        // 1. Native HyperOS / MIUI 4-arg MiuiFreeFormManager -> MiuiMultiWindowUtils using Activity context
        if (pkg.isNotEmpty()) {
            val miuiBundle = MiuiFreeformOptions.build(
                context = this,
                packageName = pkg,
                anchorX = effectiveAnchorX,
                anchorY = effectiveAnchorY
            )
            if (miuiBundle != null && !miuiBundle.isEmpty) {
                val started = runCatching {
                    startActivity(targetIntent, miuiBundle)
                    true
                }.getOrDefault(false)
                if (started) return
            }
        }

        // 2. Standard Android Freeform ActivityOptions (`setLaunchWindowingMode(5)` + centered bounds)
        val aospStarted = runCatching {
            val options = ActivityOptions.makeBasic()
            HiddenApiBypass.invoke(
                ActivityOptions::class.java,
                options,
                "setLaunchWindowingMode",
                WINDOWING_MODE_FREEFORM
            )
            runCatching { options.setLaunchBounds(computeCenteredBounds()) }
            val freeformIntent = Intent(targetIntent).apply {
                if (!reorderToFront) {
                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
            }
            startActivity(freeformIntent, options.toBundle())
            true
        }.getOrDefault(false)
        if (aospStarted) return

        // 3. Final fallback: launch target app normally so Control Center macro can float it
        runCatching { startActivity(targetIntent) }
    }

    private fun computeCenteredBounds(): Rect {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(720)
        val screenH = dm.heightPixels.coerceAtLeast(1280)
        val winW = (screenW * 0.82f).toInt().coerceAtLeast(480)
        val winH = (screenH * 0.62f).toInt().coerceAtLeast(680)
        val left = ((screenW - winW) / 2).coerceAtLeast(24)
        val top = ((screenH - winH) / 2 - (screenH * 0.04f).toInt()).coerceAtLeast(72)
        return Rect(left, top, left + winW, top + winH)
    }

    private fun extractTargetIntent(src: Intent?): Intent? {
        if (src == null) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                src.getParcelableExtra(EXTRA_TARGET_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                src.getParcelableExtra(EXTRA_TARGET_INTENT)
            }
        }.getOrNull()
    }

    private fun disableTransitions() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }

    companion object {
        private const val EXTRA_TARGET_INTENT = "edgeflow.extra.TARGET_INTENT"
        private const val EXTRA_PACKAGE_NAME = "edgeflow.extra.PACKAGE_NAME"
        private const val EXTRA_ANCHOR_X = "edgeflow.extra.ANCHOR_X"
        private const val EXTRA_ANCHOR_Y = "edgeflow.extra.ANCHOR_Y"
        private const val EXTRA_EXTRA_FLAGS = "edgeflow.extra.EXTRA_FLAGS"
        private const val WINDOWING_MODE_FREEFORM = 5

        fun launch(
            context: Context,
            targetIntent: Intent,
            packageName: String,
            anchorX: Int,
            anchorY: Int,
            extraFlags: Int = 0
        ): Boolean {
            return runCatching {
                val proxy = Intent(context, FloatingProxyActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    putExtra(EXTRA_TARGET_INTENT, Intent(targetIntent))
                    putExtra(EXTRA_PACKAGE_NAME, packageName)
                    putExtra(EXTRA_ANCHOR_X, anchorX)
                    putExtra(EXTRA_ANCHOR_Y, anchorY)
                    putExtra(EXTRA_EXTRA_FLAGS, extraFlags)
                }
                context.startActivity(proxy)
                true
            }.getOrDefault(false)
        }
    }
}
