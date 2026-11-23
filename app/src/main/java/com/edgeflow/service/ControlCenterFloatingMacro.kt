package com.edgeflow.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import com.edgeflow.data.CcTileCalibrationState
import com.edgeflow.data.ConfigStore
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.EdgeConfigProvider
import com.edgeflow.data.MlKitSafeInit
import com.edgeflow.data.RuntimeStateRegistry
import com.example.R
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Zero-Shizuku, zero-Root HyperOS / Android floating-window macro using standard
 * `AccessibilityService` capabilities (`GLOBAL_ACTION_QUICK_SETTINGS` + internal node scan +
 * saved-coordinate gesture tap + on-device ML Kit OCR fallback + 1-tap manual calibration).
 *
 * Execution priority (Shizuku OFF):
 * 1. **Internal node scan (Primary)**: Opens Control Center (page 1), locates the
 *    "Floating windows" tile in the `AccessibilityNodeInfo` tree, auto-updates its normalized
 *    coordinates in [ConfigStore], and invokes `ACTION_CLICK` (falling back to a 50ms
 *    `dispatchGesture` tap if `ACTION_CLICK` is ignored by the ROM).
 * 2. **Saved-coordinate macro tap (Fallback)**: If the ROM hides tile labels in the node tree
 *    or coordinates were memorized via OCR/Manual calibration, executes a 50ms `dispatchGesture`
 *    tap at `(screenW * ccFloatTileNormX, screenH * ccFloatTileNormY)`.
 * 3. **Guided setup Toast**: If neither succeeds, closes Control Center (`GLOBAL_ACTION_BACK`)
 *    and guides the user to place the tile on page 1 and run Reload / OCR calibration.
 *
 * Pure Kotlin / Android framework only — ZERO Jetpack Compose imports.
 */
@Keep
class ControlCenterFloatingMacro(
    private val service: AccessibilityService,
    private val configProvider: () -> EdgeConfig
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerExecutor: ExecutorService? = null
    private var activeRunToken: Int = 0
    private var manualCaptureOverlay: View? = null

    private val manualOverlayTimeoutRunnable = Runnable {
        removeManualCaptureOverlay()
        publishCalibrationState(
            inProgress = false,
            statusCode = EdgeConfigProvider.STATUS_NOT_FOUND,
            methodUsed = EdgeConfig.CC_TILE_METHOD_OCR
        )
        closeControlCenterQuietly()
    }

    fun calibrateTilePosition(method: String) {
        if (method.equals(EdgeConfig.CC_TILE_METHOD_OCR, ignoreCase = true) ||
            method.equals(EdgeConfigProvider.SCAN_MODE_OCR, ignoreCase = true)
        ) {
            reloadTileByOcrScan()
        } else {
            reloadTileByInternalScan()
        }
    }

    fun cancelPending() {
        activeRunToken++
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post { removeManualCaptureOverlay() }
    }

    fun release() {
        cancelPending()
        workerExecutor?.shutdownNow()
        workerExecutor = null
    }

    /**
     * Triggers the Control Center floating-window tile tap immediately on the current foreground app.
     */
    fun triggerFloatCurrentApp(onBeforeTransition: () -> Unit = {}) {
        if (!com.edgeflow.data.DeviceCompatibilityHelper.resolveCapabilities()
                .supportsHyperOsNativeFloatingWindow
        ) {
            return
        }
        mainHandler.post {
            activeRunToken++
            val token = activeRunToken
            mainHandler.removeCallbacksAndMessages(null)
            removeManualCaptureOverlay()

            onBeforeTransition()
            val opened = runCatching {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            }.getOrDefault(false)
            if (!opened) {
                showToast(R.string.floating_window_cc_tile_not_found_setup)
                return@post
            }

            schedulePollAttempt(
                token = token,
                attemptIndex = 0,
                delayMs = INITIAL_POLL_DELAY_MS,
                clickFoundTile = true
            )
        }
    }

    /**
     * Schedules a post-launch check after launching [targetPackage] with Shizuku OFF:
     * if the target app did NOT already open in a floating/freeform window, converts it into a
     * genuine HyperOS scaled floating window via the Control Center tile macro.
     */
    fun scheduleFloatAfterAppLaunch(
        targetPackage: String,
        delayMs: Long = POST_LAUNCH_DELAY_MS,
        onBeforeTransition: () -> Unit = {}
    ) {
        if (!com.edgeflow.data.DeviceCompatibilityHelper.resolveCapabilities()
                .supportsHyperOsNativeFloatingWindow
        ) {
            return
        }
        mainHandler.post {
            activeRunToken++
            val token = activeRunToken
            mainHandler.removeCallbacksAndMessages(null)
            removeManualCaptureOverlay()

            mainHandler.postDelayed({
                if (token != activeRunToken) return@postDelayed
                if (isPackageAlreadyInFloatingWindow(targetPackage)) {
                    return@postDelayed
                }
                onBeforeTransition()
                val opened = runCatching {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                }.getOrDefault(false)
                if (!opened) return@postDelayed
                schedulePollAttempt(
                    token = token,
                    attemptIndex = 0,
                    delayMs = INITIAL_POLL_DELAY_MS,
                    clickFoundTile = true
                )
            }, delayMs)
        }
    }

    /**
     * Method A (Standard): Resets old memorized coordinates, opens Control Center (page 1),
     * scans the internal `AccessibilityNodeInfo` tree to memorize the new tile position WITHOUT
     * triggering the tile, then closes Control Center and reports the result.
     */
    fun reloadTileByInternalScan() {
        mainHandler.post {
            activeRunToken++
            val token = activeRunToken
            mainHandler.removeCallbacksAndMessages(null)
            removeManualCaptureOverlay()

            publishCalibrationState(
                inProgress = true,
                statusCode = EdgeConfigProvider.STATUS_RUNNING,
                methodUsed = EdgeConfig.CC_TILE_METHOD_NODE
            )

            // Reset old memorized coordinates first so stale positions cannot linger
            persistTileCoordinates(
                normX = EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD,
                normY = EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD,
                method = EdgeConfig.CC_TILE_METHOD_AUTO
            )

            val opened = runCatching {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            }.getOrDefault(false)
            if (!opened) {
                publishCalibrationState(
                    inProgress = false,
                    statusCode = EdgeConfigProvider.STATUS_NOT_FOUND,
                    methodUsed = EdgeConfig.CC_TILE_METHOD_NODE
                )
                showToast(R.string.cc_float_scan_internal_failed)
                return@post
            }

            schedulePollAttempt(
                token = token,
                attemptIndex = 0,
                delayMs = CALIBRATION_INITIAL_POLL_DELAY_MS,
                clickFoundTile = false
            )
        }
    }

    /**
     * Method B (Fallback): Opens Control Center (page 1), waits 480ms, captures 1 frame via
     * `takeScreenshot` and runs on-device ML Kit OCR (Japanese / Latin / Chinese / Devanagari)
     * to detect the "Floating windows" label and memorize its tile center.
     *
     * If OCR detects no text (e.g., HyperOS icon-only Control Center tiles with labels hidden),
     * automatically opens a 1-tap manual calibration overlay over the still-open Control Center.
     */
    fun reloadTileByOcrScan() {
        mainHandler.post {
            activeRunToken++
            val token = activeRunToken
            mainHandler.removeCallbacksAndMessages(null)
            removeManualCaptureOverlay()

            publishCalibrationState(
                inProgress = true,
                statusCode = EdgeConfigProvider.STATUS_RUNNING,
                methodUsed = EdgeConfig.CC_TILE_METHOD_OCR
            )

            val opened = runCatching {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            }.getOrDefault(false)
            if (!opened) {
                publishCalibrationState(
                    inProgress = false,
                    statusCode = EdgeConfigProvider.STATUS_NOT_FOUND,
                    methodUsed = EdgeConfig.CC_TILE_METHOD_OCR
                )
                showToast(R.string.cc_float_scan_ocr_failed)
                return@post
            }

            mainHandler.postDelayed({
                if (token != activeRunToken) return@postDelayed
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    captureAndRunOcrApi30(token)
                } else {
                    // API 29 fallback: direct 1-tap calibration over the open Control Center
                    showManualTapCalibrationOverlay(token)
                }
            }, OCR_CAPTURE_DELAY_MS)
        }
    }

    private fun schedulePollAttempt(
        token: Int,
        attemptIndex: Int,
        delayMs: Long,
        clickFoundTile: Boolean
    ) {
        mainHandler.postDelayed({
            if (token != activeRunToken) return@postDelayed

            val found = findFloatingTileInTree(clickNode = clickFoundTile)
            if (found != null) {
                val savedCoords = saveNormalizedPoint(
                    screenX = found.centerX,
                    screenY = found.centerY,
                    method = EdgeConfig.CC_TILE_METHOD_NODE
                )
                if (clickFoundTile) {
                    if (found.clickedByAction) {
                        // HyperOS may return true from ACTION_CLICK without activating the QS tile.
                        // Verify 260ms later whether Control Center actually dismissed; if still open,
                        // fire a genuine 50ms gesture tap at the tile center and re-verify.
                        verifyControlCenterClosedAndFallback(
                            token = token,
                            tapX = found.centerX,
                            tapY = found.centerY,
                            attemptGesture = true,
                            delayMs = ACTION_CLICK_VERIFY_DELAY_MS
                        )
                    } else {
                        // ACTION_CLICK rejected: immediately dispatch a genuine 50ms gesture tap
                        val dispatched = dispatchGestureTap(found.centerX, found.centerY)
                        if (dispatched) {
                            verifyControlCenterClosedAndFallback(
                                token = token,
                                tapX = found.centerX,
                                tapY = found.centerY,
                                attemptGesture = false,
                                delayMs = GESTURE_TAP_VERIFY_DELAY_MS
                            )
                        } else {
                            closeControlCenterQuietly()
                            showToast(R.string.floating_window_cc_tile_not_found_setup)
                        }
                    }
                } else {
                    publishCalibrationState(
                        inProgress = false,
                        statusCode = EdgeConfigProvider.STATUS_SUCCESS,
                        methodUsed = EdgeConfig.CC_TILE_METHOD_NODE,
                        normX = savedCoords?.first ?: EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD,
                        normY = savedCoords?.second ?: EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD
                    )
                    closeControlCenterQuietly()
                    showToast(R.string.cc_float_scan_internal_success)
                }
                return@postDelayed
            }

            val nextAttempt = attemptIndex + 1
            if (nextAttempt < MAX_POLL_ATTEMPTS) {
                schedulePollAttempt(
                    token = token,
                    attemptIndex = nextAttempt,
                    delayMs = POLL_INTERVAL_MS,
                    clickFoundTile = clickFoundTile
                )
            } else {
                if (clickFoundTile) {
                    val cfg = configProvider()
                    if (cfg.hasSavedCcFloatTileCoords()) {
                        val dm = service.resources.displayMetrics
                        val tapX = dm.widthPixels * cfg.ccFloatTileNormX
                        val tapY = dm.heightPixels * cfg.ccFloatTileNormY
                        val dispatched = dispatchGestureTap(tapX, tapY)
                        if (dispatched) {
                            verifyControlCenterClosedAndFallback(
                                token = token,
                                tapX = tapX,
                                tapY = tapY,
                                attemptGesture = false,
                                delayMs = GESTURE_TAP_VERIFY_DELAY_MS
                            )
                        } else {
                            closeControlCenterQuietly()
                            showToast(R.string.floating_window_cc_tile_not_found_setup)
                        }
                    } else {
                        closeControlCenterQuietly()
                        showToast(R.string.floating_window_cc_tile_not_found_setup)
                    }
                } else {
                    publishCalibrationState(
                        inProgress = false,
                        statusCode = EdgeConfigProvider.STATUS_NOT_FOUND,
                        methodUsed = EdgeConfig.CC_TILE_METHOD_NODE
                    )
                    closeControlCenterQuietly()
                    showToast(R.string.cc_float_scan_internal_failed)
                }
            }
        }, delayMs)
    }

    /**
     * Verifies whether Control Center actually closed after clicking/tapping the Floating Windows
     * tile. If HyperOS ignored `ACTION_CLICK` (false success), fires a real 50ms `dispatchGesture`
     * tap at `(tapX, tapY)` and re-checks 380ms later. If Control Center STILL remains open,
     * guarantees closing it with `GLOBAL_ACTION_BACK` and shows the setup guidance Toast so the
     * user is never left stranded with Control Center stuck open.
     */
    private fun verifyControlCenterClosedAndFallback(
        token: Int,
        tapX: Float,
        tapY: Float,
        attemptGesture: Boolean,
        delayMs: Long
    ) {
        mainHandler.postDelayed({
            if (token != activeRunToken) return@postDelayed
            if (!isControlCenterCurrentlyOpen()) {
                return@postDelayed
            }
            if (attemptGesture) {
                val dispatched = dispatchGestureTap(tapX, tapY)
                if (dispatched) {
                    verifyControlCenterClosedAndFallback(
                        token = token,
                        tapX = tapX,
                        tapY = tapY,
                        attemptGesture = false,
                        delayMs = GESTURE_TAP_VERIFY_DELAY_MS
                    )
                    return@postDelayed
                }
            }
            closeControlCenterQuietly()
            showToast(R.string.floating_window_cc_tile_not_found_setup)
        }, delayMs)
    }

    /**
     * Returns `true` when SystemUI / MIUI Control Center currently occupies >= 50% of the screen
     * or remains the active accessibility window. Recycles every accessed `AccessibilityNodeInfo`.
     */
    private fun isControlCenterCurrentlyOpen(): Boolean {
        val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
        if (activeRoot != null) {
            val activePkg = activeRoot.packageName?.toString().orEmpty()
            @Suppress("DEPRECATION")
            runCatching { activeRoot.recycle() }
            if (isSystemUiPackage(activePkg)) {
                return true
            }
        }

        val dm = service.resources.displayMetrics
        val screenArea = dm.widthPixels.toLong().coerceAtLeast(320L) *
            dm.heightPixels.toLong().coerceAtLeast(480L)
        val bounds = Rect()
        val windowList = runCatching { service.windows }.getOrNull() ?: return false
        for (i in 0 until windowList.size) {
            val win = windowList[i]
            win.getBoundsInScreen(bounds)
            val area = bounds.width().toLong().coerceAtLeast(0L) *
                bounds.height().toLong().coerceAtLeast(0L)
            if (area * 2L < screenArea) continue
            val root = runCatching { win.root }.getOrNull() ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
            if (isSystemUiPackage(pkg)) {
                return true
            }
        }
        return false
    }

    private fun isSystemUiPackage(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        return pkg == "com.android.systemui" || pkg == "com.miui.systemui"
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureAndRunOcrApi30(token: Int) {
        val executor = ensureExecutor()
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        var hardwareBmp: Bitmap? = null
                        var softwareBmp: Bitmap? = null
                        try {
                            val buffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            try {
                                hardwareBmp = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                            } finally {
                                runCatching { buffer.close() }
                            }
                            if (hardwareBmp == null) {
                                postOcrFallbackToManual(token)
                                return
                            }
                            softwareBmp = hardwareBmp.copy(Bitmap.Config.ARGB_8888, false)
                            if (softwareBmp == null) {
                                postOcrFallbackToManual(token)
                                return
                            }

                            val bmpW = softwareBmp.width.coerceAtLeast(1)
                            val bmpH = softwareBmp.height.coerceAtLeast(1)
                            val matchedBox = runOcrTileSearch(softwareBmp)
                            if (matchedBox != null) {
                                val tapX = matchedBox.centerX().toFloat()
                                val tapY = (matchedBox.top - matchedBox.height() * 0.25f)
                                    .coerceAtLeast(matchedBox.top.toFloat())
                                val normX = (tapX / bmpW.toFloat()).coerceIn(
                                    EdgeConfig.CC_FLOAT_MIN_VALID_COORD,
                                    EdgeConfig.CC_FLOAT_MAX_VALID_COORD
                                )
                                val normY = (tapY / bmpH.toFloat()).coerceIn(
                                    EdgeConfig.CC_FLOAT_MIN_VALID_COORD,
                                    EdgeConfig.CC_FLOAT_MAX_VALID_COORD
                                )
                                mainHandler.post {
                                    if (token != activeRunToken) return@post
                                    persistTileCoordinates(normX, normY, EdgeConfig.CC_TILE_METHOD_OCR)
                                    publishCalibrationState(
                                        inProgress = false,
                                        statusCode = EdgeConfigProvider.STATUS_SUCCESS,
                                        methodUsed = EdgeConfig.CC_TILE_METHOD_OCR,
                                        normX = normX,
                                        normY = normY
                                    )
                                    closeControlCenterQuietly()
                                    showToast(R.string.cc_float_scan_ocr_success)
                                }
                            } else {
                                // Icon-only tiles (no labels visible): offer instant 1-tap calibration
                                postOcrFallbackToManual(token)
                            }
                        } catch (_: Throwable) {
                            postOcrFallbackToManual(token)
                        } finally {
                            runCatching { softwareBmp?.recycle() }
                            runCatching { hardwareBmp?.recycle() }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        postOcrFallbackToManual(token)
                    }
                }
            )
        } catch (_: Throwable) {
            postOcrFallbackToManual(token)
        }
    }

    private fun postOcrFallbackToManual(token: Int) {
        mainHandler.post {
            if (token != activeRunToken) return@post
            showManualTapCalibrationOverlay(token)
        }
    }

    /**
     * Runs on-device ML Kit OCR (Japanese+Latin -> Chinese+Latin -> Devanagari) on the Control
     * Center screenshot and returns the bounding box of the matching floating-window label.
     */
    private fun runOcrTileSearch(bitmap: Bitmap): Rect? {
        MlKitSafeInit.ensureInitialized(service.applicationContext)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        val recognizerFactories: List<() -> TextRecognizer> = listOf(
            { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) },
            { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) },
            { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }
        )

        for (factory in recognizerFactories) {
            var recognizer: TextRecognizer? = null
            try {
                recognizer = factory()
                val result = Tasks.await(recognizer.process(inputImage), OCR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        if (matchesOcrTileKeyword(line.text)) {
                            val box = line.boundingBox
                            if (box != null && box.width() > 4 && box.height() > 4) {
                                return Rect(box)
                            }
                        }
                    }
                    if (matchesOcrTileKeyword(block.text)) {
                        val box = block.boundingBox
                        if (box != null && box.width() > 4 && box.height() > 4) {
                            return Rect(box)
                        }
                    }
                }
            } catch (_: Throwable) {
                // Continue to next script recognizer
            } finally {
                runCatching { recognizer?.close() }
            }
        }
        return null
    }

    /**
     * Shows a lightweight pure-Android-View 1-tap calibration overlay over the open Control Center
     * when OCR could not find visible text labels (e.g. icon-only HyperOS tiles).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showManualTapCalibrationOverlay(token: Int) {
        removeManualCaptureOverlay()
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
            publishCalibrationState(
                inProgress = false,
                statusCode = EdgeConfigProvider.STATUS_NOT_FOUND,
                methodUsed = EdgeConfig.CC_TILE_METHOD_MANUAL_TAP
            )
            closeControlCenterQuietly()
            showToast(R.string.cc_float_scan_ocr_failed)
            return
        }
        publishCalibrationState(
            inProgress = true,
            statusCode = EdgeConfigProvider.STATUS_WAITING_TAP,
            methodUsed = EdgeConfig.CC_TILE_METHOD_MANUAL_TAP
        )

        val localized = LocalizedContextHelper.forTag(
            service.applicationContext,
            configProvider().appLanguageTag
        )
        val density = service.resources.displayMetrics.density
        val dp = { value: Float -> (value * density).toInt() }

        val root = FrameLayout(service).apply {
            setBackgroundColor(Color.argb(64, 0, 0, 0))
            isClickable = true
            isFocusable = false
        }

        val banner = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(12f), dp(12f), dp(12f))
            background = GradientDrawable().apply {
                setColor(Color.argb(236, 20, 24, 33))
                cornerRadius = 18f * density
                setStroke(dp(1f), Color.argb(110, 148, 163, 184))
            }
        }

        val promptView = TextView(service).apply {
            text = localized.getString(R.string.cc_float_manual_tap_prompt)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val cancelButton = TextView(service).apply {
            text = localized.getString(R.string.button_cancel)
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(dp(12f), dp(8f), dp(8f), dp(8f))
            minWidth = dp(48f)
            minHeight = dp(48f)
            gravity = Gravity.CENTER
            setOnClickListener {
                removeManualCaptureOverlay()
                publishCalibrationState(
                    inProgress = false,
                    statusCode = EdgeConfigProvider.STATUS_NOT_FOUND,
                    methodUsed = EdgeConfig.CC_TILE_METHOD_MANUAL_TAP
                )
                closeControlCenterQuietly()
            }
        }

        banner.addView(promptView)
        banner.addView(cancelButton)

        val bannerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(48f)
            leftMargin = dp(16f)
            rightMargin = dp(16f)
        }
        root.addView(banner, bannerParams)

        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                if (token == activeRunToken) {
                    val dm = service.resources.displayMetrics
                    val screenW = dm.widthPixels.coerceAtLeast(1).toFloat()
                    val screenH = dm.heightPixels.coerceAtLeast(1).toFloat()
                    val normX = (event.rawX / screenW).coerceIn(
                        EdgeConfig.CC_FLOAT_MIN_VALID_COORD,
                        EdgeConfig.CC_FLOAT_MAX_VALID_COORD
                    )
                    val normY = (event.rawY / screenH).coerceIn(
                        EdgeConfig.CC_FLOAT_MIN_VALID_COORD,
                        EdgeConfig.CC_FLOAT_MAX_VALID_COORD
                    )
                    removeManualCaptureOverlay()
                    persistTileCoordinates(normX, normY, EdgeConfig.CC_TILE_METHOD_MANUAL_TAP)
                    publishCalibrationState(
                        inProgress = false,
                        statusCode = EdgeConfigProvider.STATUS_SUCCESS,
                        methodUsed = EdgeConfig.CC_TILE_METHOD_MANUAL_TAP,
                        normX = normX,
                        normY = normY
                    )
                    closeControlCenterQuietly()
                    showToast(R.string.cc_float_scan_manual_success)
                } else {
                    removeManualCaptureOverlay()
                }
                true
            } else {
                true
            }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        val added = runCatching {
            wm.addView(root, lp)
            manualCaptureOverlay = root
            true
        }.getOrDefault(false)

        if (added) {
            mainHandler.removeCallbacks(manualOverlayTimeoutRunnable)
            mainHandler.postDelayed(manualOverlayTimeoutRunnable, MANUAL_CAPTURE_TIMEOUT_MS)
        } else {
            publishCalibrationState(
                inProgress = false,
                statusCode = EdgeConfigProvider.STATUS_NOT_FOUND,
                methodUsed = EdgeConfig.CC_TILE_METHOD_MANUAL_TAP
            )
            closeControlCenterQuietly()
            showToast(R.string.cc_float_scan_ocr_failed)
        }
    }

    private fun removeManualCaptureOverlay() {
        mainHandler.removeCallbacks(manualOverlayTimeoutRunnable)
        val view = manualCaptureOverlay ?: return
        manualCaptureOverlay = null
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeViewImmediate(view) }
    }

    private data class FoundTileResult(
        val centerX: Float,
        val centerY: Float,
        val clickedByAction: Boolean
    )

    private fun findFloatingTileInTree(clickNode: Boolean): FoundTileResult? {
        val roots = collectCandidateRoots()
        if (roots.isEmpty()) return null

        var found: FoundTileResult? = null
        for (root in roots) {
            if (found == null) {
                found = searchTileInRoot(root, clickNode)
            }
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
        return found
    }

    private fun collectCandidateRoots(): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(4)
        val systemUiRoots = ArrayList<AccessibilityNodeInfo>(2)
        val otherRoots = ArrayList<AccessibilityNodeInfo>(2)

        val windowList = runCatching { service.windows }.getOrNull()
        if (windowList != null) {
            for (i in 0 until windowList.size) {
                val win = windowList[i]
                val root = runCatching { win.root }.getOrNull() ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg == service.packageName) {
                    @Suppress("DEPRECATION")
                    runCatching { root.recycle() }
                    continue
                }
                val isSystemUi = pkg == "com.android.systemui" ||
                    pkg == "com.miui.systemui" ||
                    win.type == AccessibilityWindowInfo.TYPE_SYSTEM
                if (isSystemUi) {
                    systemUiRoots.add(root)
                } else {
                    otherRoots.add(root)
                }
            }
        }

        result.addAll(systemUiRoots)
        val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
        if (activeRoot != null && activeRoot.packageName?.toString() != service.packageName) {
            result.add(activeRoot)
        } else if (activeRoot != null) {
            @Suppress("DEPRECATION")
            runCatching { activeRoot.recycle() }
        }
        result.addAll(otherRoots)
        return result
    }

    private fun searchTileInRoot(root: AccessibilityNodeInfo, clickNode: Boolean): FoundTileResult? {
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>(32)
        stack.addLast(root to 0)
        var visited = 0
        val tempBounds = Rect()

        while (stack.isNotEmpty() && visited < MAX_NODES_PER_ROOT) {
            val (node, depth) = stack.removeLast()
            visited++

            val isRoot = (node === root)
            val matched = node.isVisibleToUser &&
                matchesFloatingWindowTileLabel(node.text, node.contentDescription)

            if (matched) {
                node.getBoundsInScreen(tempBounds)
                val validBounds = tempBounds.width() > 8 && tempBounds.height() > 8
                val centerX = tempBounds.exactCenterX()
                val centerY = tempBounds.exactCenterY()
                val clicked = if (clickNode) performClickOnNodeOrParent(node) else false

                if (!isRoot) {
                    @Suppress("DEPRECATION")
                    runCatching { node.recycle() }
                }
                while (stack.isNotEmpty()) {
                    val rem = stack.removeLast().first
                    if (rem !== root) {
                        @Suppress("DEPRECATION")
                        runCatching { rem.recycle() }
                    }
                }
                if (validBounds || clicked) {
                    return FoundTileResult(
                        centerX = centerX,
                        centerY = centerY,
                        clickedByAction = clicked
                    )
                }
                continue
            }

            if (depth < MAX_TREE_DEPTH) {
                val childCount = node.childCount
                for (i in childCount - 1 downTo 0) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    stack.addLast(child to depth + 1)
                }
            }

            if (!isRoot) {
                @Suppress("DEPRECATION")
                runCatching { node.recycle() }
            }
        }

        while (stack.isNotEmpty()) {
            val rem = stack.removeLast().first
            if (rem !== root) {
                @Suppress("DEPRECATION")
                runCatching { rem.recycle() }
            }
        }
        return null
    }

    private fun performClickOnNodeOrParent(matchedNode: AccessibilityNodeInfo): Boolean {
        if (matchedNode.isClickable && matchedNode.isEnabled) {
            if (runCatching { matchedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                return true
            }
        }
        var currentParent: AccessibilityNodeInfo? = runCatching { matchedNode.parent }.getOrNull()
        var hops = 0
        while (currentParent != null && hops < MAX_PARENT_CLICK_HOPS) {
            val clickable = currentParent.isClickable && currentParent.isEnabled
            if (clickable) {
                val ok = runCatching {
                    currentParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                @Suppress("DEPRECATION")
                runCatching { currentParent.recycle() }
                return ok
            }
            val nextParent = runCatching { currentParent.parent }.getOrNull()
            @Suppress("DEPRECATION")
            runCatching { currentParent.recycle() }
            currentParent = nextParent
            hops++
        }
        if (currentParent != null) {
            @Suppress("DEPRECATION")
            runCatching { currentParent.recycle() }
        }
        return runCatching {
            matchedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
    }

    private fun dispatchGestureTap(screenX: Float, screenY: Float): Boolean {
        val dm = service.resources.displayMetrics
        val safeX = screenX.coerceIn(4f, (dm.widthPixels - 4).coerceAtLeast(4).toFloat())
        val safeY = screenY.coerceIn(4f, (dm.heightPixels - 4).coerceAtLeast(4).toFloat())
        val path = Path().apply {
            moveTo(safeX, safeY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, GESTURE_TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching {
            service.dispatchGesture(gesture, null, null)
        }.getOrDefault(false)
    }

    private fun saveNormalizedPoint(screenX: Float, screenY: Float, method: String): Pair<Float, Float>? {
        val dm = service.resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1).toFloat()
        val screenH = dm.heightPixels.coerceAtLeast(1).toFloat()
        val normX = (screenX / screenW).coerceIn(
            EdgeConfig.CC_FLOAT_MIN_VALID_COORD,
            EdgeConfig.CC_FLOAT_MAX_VALID_COORD
        )
        val normY = (screenY / screenH).coerceIn(
            EdgeConfig.CC_FLOAT_MIN_VALID_COORD,
            EdgeConfig.CC_FLOAT_MAX_VALID_COORD
        )
        persistTileCoordinates(normX, normY, method)
        return normX to normY
    }

    private fun persistTileCoordinates(normX: Float, normY: Float, method: String) {
        val current = configProvider()
        val updated = current.copy(
            ccFloatTileNormX = normX,
            ccFloatTileNormY = normY,
            ccFloatTileDetectionMethod = method
        ).clamped()
        runCatching {
            ConfigStore.save(service.applicationContext, updated)
        }
    }

    private fun publishCalibrationState(
        inProgress: Boolean,
        statusCode: String,
        methodUsed: String,
        normX: Float = EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD,
        normY: Float = EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD
    ) {
        RuntimeStateRegistry.updateCcCalibrationState(
            CcTileCalibrationState(
                inProgress = inProgress,
                statusCode = statusCode,
                methodUsed = methodUsed,
                normX = normX,
                normY = normY,
                timestampMs = System.currentTimeMillis()
            )
        )
    }

    private fun closeControlCenterQuietly() {
        runCatching {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    private fun isPackageAlreadyInFloatingWindow(targetPackage: String): Boolean {
        val dm = service.resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(320)
        val screenH = dm.heightPixels.coerceAtLeast(480)
        val bounds = Rect()
        val windowList = runCatching { service.windows }.getOrNull() ?: return false
        for (i in 0 until windowList.size) {
            val win = windowList[i]
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = runCatching { win.root }.getOrNull() ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
            if (targetPackage.isNotEmpty() && pkg != targetPackage) continue
            win.getBoundsInScreen(bounds)
            if (isFloatingWindowBounds(
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    screenW = screenW,
                    screenH = screenH
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun showToast(stringResId: Int) {
        val langTag = configProvider().appLanguageTag
        mainHandler.post {
            runCatching {
                val appCtx = service.applicationContext
                val localized = LocalizedContextHelper.forTag(appCtx, langTag)
                Toast.makeText(
                    appCtx,
                    localized.getString(stringResId),
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
            Thread(runnable, "EdgeFlow-CcOcr").apply { isDaemon = true }
        }.also { workerExecutor = it }
    }

    companion object {
        private const val INITIAL_POLL_DELAY_MS = 340L
        private const val CALIBRATION_INITIAL_POLL_DELAY_MS = 340L
        private const val POLL_INTERVAL_MS = 120L
        private const val MAX_POLL_ATTEMPTS = 4
        private const val POST_LAUNCH_DELAY_MS = 320L
        private const val ACTION_CLICK_VERIFY_DELAY_MS = 260L
        private const val GESTURE_TAP_VERIFY_DELAY_MS = 380L
        private const val OCR_CAPTURE_DELAY_MS = 480L
        private const val OCR_TIMEOUT_MS = 4000L
        private const val GESTURE_TAP_DURATION_MS = 50L
        private const val MANUAL_CAPTURE_TIMEOUT_MS = 12_000L
        private const val MAX_NODES_PER_ROOT = 260
        private const val MAX_TREE_DEPTH = 20
        private const val MAX_PARENT_CLICK_HOPS = 4

        private val TILE_KEYWORDS = arrayOf(
            "フローティングウィンドウ",
            "フローティング ウィンドウ",
            "floating windows",
            "floating window",
            "小窗",
            "悬浮窗",
            "फ़्लोटिंग विंडो"
        )

        private val OCR_KEYWORDS = arrayOf(
            "フローティング",
            "ウィンドウ",
            "小窓",
            "floating",
            "小窗",
            "悬浮窗",
            "悬浮",
            "फ़्लोटिंग"
        )

        internal fun matchesFloatingWindowTileLabel(
            text: CharSequence?,
            contentDesc: CharSequence?
        ): Boolean {
            if (matchesKeyword(text, TILE_KEYWORDS)) return true
            if (matchesKeyword(contentDesc, TILE_KEYWORDS)) return true
            return false
        }

        internal fun matchesOcrTileKeyword(text: CharSequence?): Boolean =
            matchesKeyword(text, OCR_KEYWORDS)

        internal fun matchesOcrFloatingKeyword(text: CharSequence?): Boolean =
            matchesOcrTileKeyword(text) || matchesFloatingWindowTileLabel(text, null)

        internal fun isFloatingWindowBounds(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            screenW: Int,
            screenH: Int
        ): Boolean {
            val width = right - left
            val height = bottom - top
            if (width <= 120 || height <= 120) return false
            val isSmallerThanFullscreen = width < (screenW * 0.92f).toInt() ||
                height < (screenH * 0.88f).toInt()
            val hasEdgeMargin = left > 16 ||
                top > 140 ||
                right < screenW - 16 ||
                bottom < screenH - 120
            return isSmallerThanFullscreen || hasEdgeMargin
        }

        private fun matchesKeyword(raw: CharSequence?, keywords: Array<String>): Boolean {
            if (raw.isNullOrBlank()) return false
            val lower = raw.toString().trim().lowercase()
            for (kw in keywords) {
                if (lower.contains(kw)) return true
            }
            return false
        }
    }
}
