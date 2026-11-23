package com.edgeflow.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Fresh, real-backdrop screenshot capturer powering Apple iOS 26 Liquid Glass real-time
 * optical refraction on Android 13+ (API 33+) via [AccessibilityService.takeScreenshot] and
 * Android 14+ (API 34+) [AccessibilityService.takeScreenshotOfWindow].
 *
 * Hardened guarantees (Missions 3 & 4):
 * - **Zero-Detach during active operation**: never drops `activeShader` / `activeBitmap` while
 *   `isOperationActive == true` (floating windows, clock ticks, or idle timer cannot detach glass).
 * - **Pre-warm capture**: after an app switch or screen transition (when idle), silently pre-warms
 *   the backdrop 280ms later so the very next gesture renders optical refraction from Frame 0.
 * - **Long idle retention**: retains cached backdrop for 25 seconds while idle (instantly freed
 *   on screen-off or foreground fullscreen app switch).
 * - **1-step downscale**: scales directly from the hardware bitmap to 1/2-res ARGB_8888 without
 *   allocating a full-resolution software copy.
 * - **Live scroll backdrop attenuation & re-capture**: when the underlying screen scrolls while a
 *   Liquid Glass overlay is open, smoothly attenuates `uBackdropMix` down to `0.15f` (preventing
 *   stale static refraction), then recaptures the underlying `TYPE_APPLICATION` window 360ms after
 *   scroll stops (API 34+) and smoothly restores `uBackdropMix` to `1.0f`.
 * - **Zero `FLAG_SECURE` pitch-black blocks**: inspects a 3x3 grid (20%, 50%, 80%); discards
 *   all-black/empty frames cleanly.
 */
class LiquidBackdropCapturer(
    private val service: AccessibilityService,
    private val onOperationActiveChanged: (Boolean) -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val tempWinBounds = Rect()

    private var workerExecutor: ExecutorService? = null

    @Volatile
    private var enabled: Boolean = true

    @Volatile
    private var screenInteractive: Boolean = true

    @Volatile
    private var captureInFlight: Boolean = false

    @Volatile
    private var activeBitmap: Bitmap? = null

    @Volatile
    private var activeShader: BitmapShader? = null

    @Volatile
    private var activeBackdropScale: Float = 1f

    @Volatile
    private var activeCaptureTimeMs: Long = 0L

    @Volatile
    private var lastAttemptTimeMs: Long = 0L

    @Volatile
    private var generation: Long = 0L

    @Volatile
    private var lastForegroundPackage: String = ""

    @Volatile
    private var released: Boolean = false

    @Volatile
    private var isOperationActive: Boolean = false

    @Volatile
    private var idleReleaseScheduled: Boolean = false

    @Volatile
    private var retryCount: Int = 0

    @Volatile
    private var needsRefreshOnNextGesture: Boolean = false

    @Volatile
    private var pendingPrewarmAfterOp: Boolean = false

    // Mission 4: Scroll backdrop attenuation & live re-capture state
    @Volatile
    private var isBackdropScrolling: Boolean = false

    @Volatile
    private var scrollMixFrom: Float = 1f

    @Volatile
    private var scrollMixTarget: Float = 1f

    @Volatile
    private var scrollMixAnimStartMs: Long = 0L

    @Volatile
    private var scrollRecaptureRetryCount: Int = 0

    private val idleReleaseRunnable = Runnable {
        idleReleaseScheduled = false
        // Mission 3: Never release backdrop while an active gesture/menu is on screen
        if (isOperationActive) return@Runnable
        releaseBackdropNow()
    }

    private val prewarmCaptureRunnable = Runnable {
        if (released || !enabled || !screenInteractive || isOperationActive || !isSupported()) {
            return@Runnable
        }
        if (captureInFlight) return@Runnable
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastAttemptTimeMs
        val minInterval = minCaptureIntervalMs()
        if (elapsed < minInterval) {
            val waitMs = (minInterval - elapsed).coerceIn(40L, minInterval)
            mainHandler.removeCallbacks(prewarmRetryRunnable)
            mainHandler.postDelayed(prewarmRetryRunnable, waitMs)
            return@Runnable
        }
        needsRefreshOnNextGesture = false
        startDisplayScreenshotRequestNow(forScrollRecapture = false)
    }

    private val prewarmRetryRunnable = Runnable {
        if (released || !enabled || !screenInteractive || isOperationActive || captureInFlight || !isSupported()) {
            return@Runnable
        }
        if (SystemClock.uptimeMillis() - lastAttemptTimeMs < minCaptureIntervalMs()) {
            return@Runnable
        }
        needsRefreshOnNextGesture = false
        startDisplayScreenshotRequestNow(forScrollRecapture = false)
    }

    private val scrollStopRecaptureRunnable = Runnable {
        if (released || !enabled || !screenInteractive || !isOperationActive) {
            finishBackdropScrollAttenuation(animate = false)
            return@Runnable
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            finishBackdropScrollAttenuation(animate = true)
            return@Runnable
        }
        if (captureInFlight) {
            if (scrollRecaptureRetryCount < MAX_SCROLL_RECAPTURE_RETRIES) {
                scrollRecaptureRetryCount += 1
                mainHandler.postDelayed(this.scrollStopRecaptureRunnableRef, SCROLL_RECAPTURE_BUSY_RETRY_MS)
            } else {
                finishBackdropScrollAttenuation(animate = true)
            }
            return@Runnable
        }
        val elapsed = SystemClock.uptimeMillis() - lastAttemptTimeMs
        val minInterval = minCaptureIntervalMs()
        if (elapsed < minInterval && scrollRecaptureRetryCount < MAX_SCROLL_RECAPTURE_RETRIES) {
            scrollRecaptureRetryCount += 1
            val waitMs = (minInterval - elapsed).coerceIn(40L, minInterval)
            mainHandler.postDelayed(this.scrollStopRecaptureRunnableRef, waitMs)
            return@Runnable
        }
        val targetWindowId = findTargetApplicationWindowId()
        if (targetWindowId < 0) {
            finishBackdropScrollAttenuation(animate = true)
            return@Runnable
        }
        startWindowScreenshotRequestApi34(targetWindowId)
    }

    // Self-reference wrapper to avoid forward-reference inside Runnable lambda
    private val scrollStopRecaptureRunnableRef: Runnable
        get() = scrollStopRecaptureRunnable

    private val captureWatchdogRunnable = Runnable {
        if (captureInFlight) {
            captureInFlight = false
            if (isBackdropScrolling) {
                finishBackdropScrollAttenuation(animate = true)
            }
        }
    }

    private val retryCaptureRunnable = Runnable {
        if (released || !enabled || !screenInteractive || !isOperationActive || idleReleaseScheduled) {
            return@Runnable
        }
        if (captureInFlight || activeShader != null || !isSupported()) {
            return@Runnable
        }
        startDisplayScreenshotRequestNow(forScrollRecapture = false)
    }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            mainHandler.removeCallbacks(prewarmCaptureRunnable)
            mainHandler.removeCallbacks(prewarmRetryRunnable)
            mainHandler.removeCallbacks(scrollStopRecaptureRunnable)
            releaseBackdropNow()
        }
    }

    fun setScreenInteractive(interactive: Boolean) {
        screenInteractive = interactive
        if (!interactive) {
            setOperationActive(false)
            mainHandler.removeCallbacks(idleReleaseRunnable)
            mainHandler.removeCallbacks(prewarmCaptureRunnable)
            mainHandler.removeCallbacks(prewarmRetryRunnable)
            mainHandler.removeCallbacks(scrollStopRecaptureRunnable)
            releaseBackdropNow()
        }
    }

    /**
     * Handles a confirmed fullscreen foreground app change:
     * - While [isOperationActive] is `true`, NEVER tears down the active Liquid Glass backdrop;
     *   marks [needsRefreshOnNextGesture] so the next gesture refreshes smoothly.
     * - While idle, immediately drops the stale app's texture and silently pre-warms the new app
     *   after [PREWARM_CAPTURE_DELAY_MS] (280ms).
     */
    fun onForegroundPackageChanged(pkg: String) {
        if (pkg.isEmpty() || pkg == lastForegroundPackage) return
        lastForegroundPackage = pkg
        if (isOperationActive) {
            pendingPrewarmAfterOp = true
            needsRefreshOnNextGesture = true
            return
        }
        pendingPrewarmAfterOp = false
        needsRefreshOnNextGesture = false
        releaseBackdropNow()
        schedulePrewarmCapture()
    }

    /**
     * Marks the cached backdrop as needing refresh on the next gesture and schedules a silent
     * 280ms pre-warm when idle so the very next swipe opens with an already-fresh backdrop.
     */
    fun markNeedsRefreshOnNextGesture() {
        needsRefreshOnNextGesture = true
        if (!isOperationActive) {
            schedulePrewarmCapture()
        }
    }

    /**
     * Mission 4: Called when `TYPE_VIEW_SCROLLED` occurs on the underlying app while a Liquid
     * Glass overlay is active. Smoothly attenuates `uBackdropMix` to `0.15f` during scrolling and
     * recaptures the underlying `TYPE_APPLICATION` window 360ms after scrolling stops.
     */
    fun onUnderlyingScreenScrolled() {
        if (released || !enabled || !screenInteractive || !isOperationActive) return
        if (activeShader == null) return

        scrollRecaptureRetryCount = 0
        if (!isBackdropScrolling) {
            isBackdropScrolling = true
            animateScrollMixTo(SCROLL_ATTENUATED_MIX)
        }
        mainHandler.removeCallbacks(scrollStopRecaptureRunnable)
        mainHandler.postDelayed(scrollStopRecaptureRunnable, SCROLL_STOP_RECAPTURE_MS)
    }

    fun isOperationCurrentlyActive(): Boolean = isOperationActive

    fun onUnderlyingScrolled() = onUnderlyingScreenScrolled()

    fun currentScrollBackdropWeight(): Float = currentScrollMixMultiplier()

    /**
     * Returns the current smooth scroll attenuation multiplier (`0.15f..1.0f`) to multiply with
     * each Liquid Glass view's `uBackdropMix`. Zero allocations.
     */
    fun currentScrollMixMultiplier(): Float {
        val startMs = scrollMixAnimStartMs
        val target = scrollMixTarget
        if (startMs == 0L) return target
        val elapsed = (SystemClock.uptimeMillis() - startMs).coerceAtLeast(0L)
        val raw = (elapsed.toFloat() / SCROLL_MIX_ANIM_MS.toFloat()).coerceIn(0f, 1f)
        if (raw >= 1f) {
            scrollMixFrom = target
            scrollMixAnimStartMs = 0L
            return target
        }
        val inv = 1f - raw
        val eased = 1f - inv * inv * inv
        return scrollMixFrom + (target - scrollMixFrom) * eased
    }

    /**
     * Returns `true` while the scroll-attenuation `uBackdropMix` ease animation is in progress so
     * visible Liquid Glass views can call `postInvalidateOnAnimation()`.
     */
    fun isScrollMixAnimating(): Boolean {
        val startMs = scrollMixAnimStartMs
        if (startMs == 0L) return false
        return (SystemClock.uptimeMillis() - startMs) < SCROLL_MIX_ANIM_MS
    }

    fun addBackdropReadyListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeBackdropReadyListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun currentShader(): BitmapShader? = activeShader

    fun currentBackdropScale(): Float = activeBackdropScale

    fun currentGeneration(): Long = generation

    /**
     * Zero-GC 3x3 (9-point) wide-area trimmed-mean relative luminance probe (0.0 = pure black,
     * 1.0 = pure white) directly on the real captured 1/2-res backdrop at screen coordinates
     * ([screenX], [screenY]). Discards the single brightest and single darkest outlier samples
     * (`(sum - min - max) / 7f`) to prevent single-pixel text/icon flicker.
     */
    fun sampleLuminanceAt(screenX: Float, screenY: Float): Float? {
        val bmp = activeBitmap ?: return null
        if (bmp.isRecycled) return null
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null

        return try {
            val scale = activeBackdropScale
            val cx = (screenX * scale).toInt().coerceIn(0, w - 1)
            val cy = (screenY * scale).toInt().coerceIn(0, h - 1)
            val step = (SAMPLE_OFFSET_PX * scale).toInt().coerceAtLeast(3)

            val x0 = (cx - step).coerceIn(0, w - 1)
            val x1 = cx
            val x2 = (cx + step).coerceIn(0, w - 1)
            val y0 = (cy - step).coerceIn(0, h - 1)
            val y1 = cy
            val y2 = (cy + step).coerceIn(0, h - 1)

            var lumSum = 0f
            var minLum = Float.POSITIVE_INFINITY
            var maxLum = Float.NEGATIVE_INFINITY

            var sample = pixelLuminance(bmp.getPixel(x0, y0))
            lumSum += sample; if (sample < minLum) minLum = sample; if (sample > maxLum) maxLum = sample

            sample = pixelLuminance(bmp.getPixel(x1, y0))
            lumSum += sample; if (sample < minLum) minLum = sample; if (sample > maxLum) maxLum = sample

            sample = pixelLuminance(bmp.getPixel(x2, y0))
            lumSum += sample; if (sample < minLum) minLum = sample; if (sample > maxLum) maxLum = sample

            sample = pixelLuminance(bmp.getPixel(x0, y1))
            lumSum += sample; if (sample < minLum) minLum = sample; if (sample > maxLum) maxLum = sample

            sample = pixelLuminance(bmp.getPixel(x1, y1))
            lumSum += sample; if (sample < minLum) minLum = sample; if (sample > maxLum) maxLum = sample

            sample = pixelLuminance(bmp.getPixel(x2, y1))
            lumSum += sample; if (sample < minLum) minLum = sample; if (sample > maxLum) maxLum = sample

            sample = pixelLuminance(bmp.getPixel(x0, y2))
            lumSum += sample; if (sample < minLum) minLum = sample; if (sample > maxLum) maxLum = sample

            sample = pixelLuminance(bmp.getPixel(x1, y2))
            lumSum += sample; if (sample < minLum) minLum = sample; if (sample > maxLum) maxLum = sample

            sample = pixelLuminance(bmp.getPixel(x2, y2))
            lumSum += sample; if (sample < minLum) minLum = sample; if (sample > maxLum) maxLum = sample

            ((lumSum - minLum - maxLum) / 7f).coerceIn(0f, 1f)
        } catch (_: Throwable) {
            null
        }
    }

    fun cancelIdleRelease() {
        idleReleaseScheduled = false
        setOperationActive(true)
        mainHandler.removeCallbacks(idleReleaseRunnable)
        mainHandler.removeCallbacks(prewarmCaptureRunnable)
        mainHandler.removeCallbacks(prewarmRetryRunnable)
    }

    fun scheduleIdleRelease() {
        setOperationActive(false)
        retryCount = 0
        finishBackdropScrollAttenuation(animate = false)
        mainHandler.removeCallbacks(scrollStopRecaptureRunnable)
        mainHandler.removeCallbacks(retryCaptureRunnable)
        mainHandler.removeCallbacks(idleReleaseRunnable)
        if (activeShader != null) {
            idleReleaseScheduled = true
            mainHandler.postDelayed(idleReleaseRunnable, IDLE_RELEASE_MS)
        } else {
            idleReleaseScheduled = false
        }
        if (pendingPrewarmAfterOp || needsRefreshOnNextGesture) {
            pendingPrewarmAfterOp = false
            schedulePrewarmCapture()
        }
    }

    /**
     * Starts or reuses a real-time backdrop capture for a new gesture (`ACTION_DOWN`).
     * Preserves any pre-warmed / cached shader while refreshing asynchronously so the first frame
     * never flashes empty.
     */
    fun captureForNewGesture() {
        retryCount = 0
        setOperationActive(true)
        idleReleaseScheduled = false
        finishBackdropScrollAttenuation(animate = false)
        mainHandler.removeCallbacks(scrollStopRecaptureRunnable)
        mainHandler.removeCallbacks(prewarmCaptureRunnable)
        mainHandler.removeCallbacks(prewarmRetryRunnable)
        mainHandler.removeCallbacks(retryCaptureRunnable)
        val shouldRefresh = needsRefreshOnNextGesture
        needsRefreshOnNextGesture = false
        captureIfNeeded(forceFresh = shouldRefresh)
    }

    /**
     * Used during mid-gesture hand-offs (e.g. hold / Stage 2 / Stage 3 -> Quick Menu or Recent
     * Switcher). Preserves and reuses the active backdrop shader immediately without discarding it.
     */
    fun captureIfMissing() {
        if (released || !enabled || !screenInteractive || !isSupported()) return
        setOperationActive(true)
        idleReleaseScheduled = false
        mainHandler.removeCallbacks(idleReleaseRunnable)
        mainHandler.removeCallbacks(prewarmCaptureRunnable)
        mainHandler.removeCallbacks(prewarmRetryRunnable)
        if (activeShader != null) return
        captureIfNeeded(forceFresh = false)
    }

    /**
     * Captures a real-time backdrop when Liquid Glass is enabled, respecting Android's
     * `takeScreenshot` rate limit (`360ms` on Android 14+ / API 34+, `1050ms` on Android 13).
     */
    fun captureIfNeeded(forceFresh: Boolean = false) {
        if (released || !enabled || !screenInteractive || !isSupported()) return
        setOperationActive(true)
        idleReleaseScheduled = false
        mainHandler.removeCallbacks(idleReleaseRunnable)
        mainHandler.removeCallbacks(prewarmCaptureRunnable)
        mainHandler.removeCallbacks(prewarmRetryRunnable)

        val now = SystemClock.uptimeMillis()
        if (captureInFlight) return

        val minIntervalMs = minCaptureIntervalMs()
        val sinceLastAttempt = now - lastAttemptTimeMs
        if (sinceLastAttempt < minIntervalMs) {
            if (!forceFresh && activeShader != null) {
                return
            }
            // Keep existing shader visible during rate-limit cooldown rather than flashing blank,
            // and retry automatically once the rate-limit window opens.
            if (activeShader == null && retryCount < MAX_RETRY_COUNT) {
                val waitMs = (minIntervalMs - sinceLastAttempt).coerceIn(RETRY_DELAY_FIRST_MS, RETRY_DELAY_SECOND_MS)
                retryCount += 1
                mainHandler.removeCallbacks(retryCaptureRunnable)
                mainHandler.postDelayed(retryCaptureRunnable, waitMs)
            }
            return
        }

        startDisplayScreenshotRequestNow(forScrollRecapture = false)
    }

    private fun schedulePrewarmCapture() {
        if (released || !enabled || !screenInteractive || isOperationActive || !isSupported()) return
        mainHandler.removeCallbacks(prewarmCaptureRunnable)
        mainHandler.removeCallbacks(prewarmRetryRunnable)
        mainHandler.postDelayed(prewarmCaptureRunnable, PREWARM_CAPTURE_DELAY_MS)
    }

    private fun setOperationActive(active: Boolean) {
        val changed = isOperationActive != active
        isOperationActive = active
        if (changed) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onOperationActiveChanged(active)
            } else {
                mainHandler.post { onOperationActiveChanged(isOperationActive) }
            }
        }
    }

    private fun animateScrollMixTo(target: Float) {
        val current = currentScrollMixMultiplier()
        if (abs(current - target) < 0.005f) {
            scrollMixFrom = target
            scrollMixTarget = target
            scrollMixAnimStartMs = 0L
            notifyListeners()
            return
        }
        scrollMixFrom = current
        scrollMixTarget = target
        scrollMixAnimStartMs = SystemClock.uptimeMillis()
        notifyListeners()
    }

    private fun finishBackdropScrollAttenuation(animate: Boolean) {
        isBackdropScrolling = false
        scrollRecaptureRetryCount = 0
        if (animate) {
            animateScrollMixTo(1f)
        } else {
            scrollMixFrom = 1f
            scrollMixTarget = 1f
            scrollMixAnimStartMs = 0L
        }
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            listener()
        }
    }

    private fun minCaptureIntervalMs(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            MIN_CAPTURE_INTERVAL_API34_MS
        } else {
            MIN_CAPTURE_INTERVAL_API33_MS
        }
    }

    private fun startDisplayScreenshotRequestNow(forScrollRecapture: Boolean) {
        lastAttemptTimeMs = SystemClock.uptimeMillis()
        captureInFlight = true
        mainHandler.removeCallbacks(captureWatchdogRunnable)
        mainHandler.postDelayed(captureWatchdogRunnable, CAPTURE_WATCHDOG_MS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestScreenshotApi33(forScrollRecapture)
        } else {
            mainHandler.removeCallbacks(captureWatchdogRunnable)
            captureInFlight = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun startWindowScreenshotRequestApi34(windowId: Int) {
        lastAttemptTimeMs = SystemClock.uptimeMillis()
        captureInFlight = true
        mainHandler.removeCallbacks(captureWatchdogRunnable)
        mainHandler.postDelayed(captureWatchdogRunnable, CAPTURE_WATCHDOG_MS)

        val executor = ensureExecutor()
        try {
            service.takeScreenshotOfWindow(
                windowId,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        processScreenshotResult(screenshot, forScrollRecapture = true)
                    }

                    override fun onFailure(errorCode: Int) {
                        mainHandler.post {
                            mainHandler.removeCallbacks(captureWatchdogRunnable)
                            captureInFlight = false
                            finishBackdropScrollAttenuation(animate = true)
                        }
                    }
                }
            )
        } catch (_: Throwable) {
            mainHandler.removeCallbacks(captureWatchdogRunnable)
            captureInFlight = false
            finishBackdropScrollAttenuation(animate = true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestScreenshotApi33(forScrollRecapture: Boolean) {
        val executor = ensureExecutor()
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        processScreenshotResult(screenshot, forScrollRecapture = forScrollRecapture)
                    }

                    override fun onFailure(errorCode: Int) {
                        mainHandler.post {
                            mainHandler.removeCallbacks(captureWatchdogRunnable)
                            captureInFlight = false
                            if (forScrollRecapture) {
                                finishBackdropScrollAttenuation(animate = true)
                            } else if (!released && enabled && screenInteractive &&
                                isOperationActive && !idleReleaseScheduled &&
                                retryCount < MAX_RETRY_COUNT
                            ) {
                                val delayMs = if (retryCount == 0) {
                                    RETRY_DELAY_FIRST_MS
                                } else {
                                    RETRY_DELAY_SECOND_MS
                                }
                                retryCount += 1
                                mainHandler.removeCallbacks(retryCaptureRunnable)
                                mainHandler.postDelayed(retryCaptureRunnable, delayMs)
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {
            mainHandler.removeCallbacks(captureWatchdogRunnable)
            captureInFlight = false
            if (forScrollRecapture) {
                finishBackdropScrollAttenuation(animate = true)
            }
        }
    }

    /**
     * Worker-thread single-step downscale from hardware buffer -> 1/2-resolution `ARGB_8888`
     * software bitmap (Mission 3 optimization: eliminates full-res intermediate software copy).
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun processScreenshotResult(
        screenshot: AccessibilityService.ScreenshotResult,
        forScrollRecapture: Boolean
    ) {
        var hardwareBmp: Bitmap? = null
        var downscaledBmp: Bitmap? = null
        try {
            val buffer = screenshot.hardwareBuffer
            val colorSpace = screenshot.colorSpace
            try {
                hardwareBmp = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
            } finally {
                runCatching { buffer.close() }
            }

            if (hardwareBmp == null || released || !enabled || !screenInteractive) {
                mainHandler.post {
                    mainHandler.removeCallbacks(captureWatchdogRunnable)
                    captureInFlight = false
                    if (forScrollRecapture) finishBackdropScrollAttenuation(animate = true)
                }
                return
            }

            val srcW = hardwareBmp.width
            val srcH = hardwareBmp.height
            if (srcW <= 0 || srcH <= 0) {
                mainHandler.post {
                    mainHandler.removeCallbacks(captureWatchdogRunnable)
                    captureInFlight = false
                    if (forScrollRecapture) finishBackdropScrollAttenuation(animate = true)
                }
                return
            }

            // Mission 3: 1-step downscale directly from hardware bitmap -> 1/2-res ARGB_8888
            val dstW = (srcW / 2).coerceAtLeast(1)
            val dstH = (srcH / 2).coerceAtLeast(1)
            val scale = dstW.toFloat() / srcW.toFloat()

            var scaledTemp: Bitmap? = null
            var softwareFallback: Bitmap? = null
            try {
                scaledTemp = try {
                    Bitmap.createScaledBitmap(hardwareBmp, dstW, dstH, true)
                } catch (_: Throwable) {
                    softwareFallback = hardwareBmp.copy(Bitmap.Config.ARGB_8888, false)
                    softwareFallback?.let { Bitmap.createScaledBitmap(it, dstW, dstH, true) }
                }
                val resolvedTemp = scaledTemp
                downscaledBmp = if (resolvedTemp == null) {
                    null
                } else if (resolvedTemp.config == Bitmap.Config.ARGB_8888 &&
                    resolvedTemp !== hardwareBmp &&
                    resolvedTemp !== softwareFallback
                ) {
                    scaledTemp = null
                    resolvedTemp
                } else {
                    resolvedTemp.copy(Bitmap.Config.ARGB_8888, false)
                }
            } finally {
                if (scaledTemp != null && scaledTemp !== hardwareBmp &&
                    scaledTemp !== softwareFallback && scaledTemp !== downscaledBmp
                ) {
                    runCatching { scaledTemp.recycle() }
                }
                if (softwareFallback != null && softwareFallback !== downscaledBmp) {
                    runCatching { softwareFallback.recycle() }
                }
            }

            val candidate = downscaledBmp
            if (candidate == null || released || !enabled || !screenInteractive) {
                runCatching { candidate?.recycle() }
                downscaledBmp = null
                mainHandler.post {
                    mainHandler.removeCallbacks(captureWatchdogRunnable)
                    captureInFlight = false
                    if (forScrollRecapture) finishBackdropScrollAttenuation(animate = true)
                }
                return
            }

            // FLAG_SECURE guard: discard all-black/empty frames cleanly
            if (isSecureBlackOrEmptyFrame(candidate)) {
                runCatching { candidate.recycle() }
                downscaledBmp = null
                mainHandler.post {
                    mainHandler.removeCallbacks(captureWatchdogRunnable)
                    captureInFlight = false
                    retryCount = 0
                    if (forScrollRecapture) {
                        finishBackdropScrollAttenuation(animate = true)
                    } else {
                        releaseBackdropNow()
                    }
                }
                return
            }

            val newShader = BitmapShader(
                candidate,
                Shader.TileMode.CLAMP,
                Shader.TileMode.CLAMP
            )
            val capturedBmp = candidate
            downscaledBmp = null

            mainHandler.post {
                mainHandler.removeCallbacks(captureWatchdogRunnable)
                captureInFlight = false
                retryCount = 0
                if (released || !enabled || !screenInteractive) {
                    runCatching { capturedBmp.recycle() }
                    return@post
                }
                val oldBmp = activeBitmap
                activeBitmap = capturedBmp
                activeShader = newShader
                activeBackdropScale = scale
                activeCaptureTimeMs = SystemClock.uptimeMillis()
                generation += 1L
                if (oldBmp != null && oldBmp !== capturedBmp) {
                    runCatching { oldBmp.recycle() }
                }
                if (forScrollRecapture || isBackdropScrolling) {
                    isBackdropScrolling = false
                    scrollRecaptureRetryCount = 0
                    animateScrollMixTo(1f)
                } else {
                    notifyListeners()
                }
            }
        } catch (_: Throwable) {
            runCatching { downscaledBmp?.recycle() }
            mainHandler.post {
                mainHandler.removeCallbacks(captureWatchdogRunnable)
                captureInFlight = false
                if (forScrollRecapture) {
                    finishBackdropScrollAttenuation(animate = true)
                }
            }
        } finally {
            runCatching { hardwareBmp?.recycle() }
        }
    }

    /**
     * Locates the frontmost fullscreen `TYPE_APPLICATION` window underneath Hinata's overlays
     * (area >= 50% of display area, excluding SystemUI and IME) for clean API 34+ window capture.
     */
    private fun findTargetApplicationWindowId(): Int {
        val windowList = runCatching { service.windows }.getOrNull() ?: return -1
        val dm = service.resources.displayMetrics
        val minArea = dm.widthPixels.toLong().coerceAtLeast(320L) *
            dm.heightPixels.toLong().coerceAtLeast(480L) * FULLSCREEN_AREA_RATIO

        var bestWindowId = -1
        var bestLayer = Int.MIN_VALUE
        for (i in 0 until windowList.size) {
            val win = windowList[i]
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            win.getBoundsInScreen(tempWinBounds)
            val area = tempWinBounds.width().toLong().coerceAtLeast(0L) *
                tempWinBounds.height().toLong().coerceAtLeast(0L)
            if (area < minArea) continue

            val root = runCatching { win.root }.getOrNull()
            val pkg = root?.packageName?.toString().orEmpty()
            if (root != null) {
                @Suppress("DEPRECATION")
                runCatching { root.recycle() }
            }
            if (pkg == "com.android.systemui" ||
                pkg.contains("inputmethod", ignoreCase = true) ||
                pkg.contains("keyboard", ignoreCase = true)
            ) {
                continue
            }
            if (win.layer >= bestLayer) {
                bestLayer = win.layer
                bestWindowId = win.id
            }
        }
        return bestWindowId
    }

    /**
     * Checks 9 grid points (20%, 50%, 80% on X & Y). Returns `true` when EVERY sample is
     * pitch-black (`r <= 3 && g <= 3 && b <= 3`) or fully transparent (`alpha == 0`),
     * indicating an Android `FLAG_SECURE` protected window (banking, DRM, password managers).
     */
    private fun isSecureBlackOrEmptyFrame(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w < 4 || h < 4) return true
        val xs0 = (w * 0.20f).toInt().coerceIn(0, w - 1)
        val xs1 = (w * 0.50f).toInt().coerceIn(0, w - 1)
        val xs2 = (w * 0.80f).toInt().coerceIn(0, w - 1)
        val ys0 = (h * 0.20f).toInt().coerceIn(0, h - 1)
        val ys1 = (h * 0.50f).toInt().coerceIn(0, h - 1)
        val ys2 = (h * 0.80f).toInt().coerceIn(0, h - 1)

        if (!isBlackOrTransparent(bmp.getPixel(xs0, ys0))) return false
        if (!isBlackOrTransparent(bmp.getPixel(xs1, ys0))) return false
        if (!isBlackOrTransparent(bmp.getPixel(xs2, ys0))) return false
        if (!isBlackOrTransparent(bmp.getPixel(xs0, ys1))) return false
        if (!isBlackOrTransparent(bmp.getPixel(xs1, ys1))) return false
        if (!isBlackOrTransparent(bmp.getPixel(xs2, ys1))) return false
        if (!isBlackOrTransparent(bmp.getPixel(xs0, ys2))) return false
        if (!isBlackOrTransparent(bmp.getPixel(xs1, ys2))) return false
        if (!isBlackOrTransparent(bmp.getPixel(xs2, ys2))) return false
        return true
    }

    private fun isBlackOrTransparent(pixel: Int): Boolean {
        val a = Color.alpha(pixel)
        if (a == 0) return true
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return r <= 3 && g <= 3 && b <= 3
    }

    private fun pixelLuminance(pixel: Int): Float {
        val r = Color.red(pixel) / 255f
        val g = Color.green(pixel) / 255f
        val b = Color.blue(pixel) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    fun releaseBackdropNow() {
        idleReleaseScheduled = false
        retryCount = 0
        finishBackdropScrollAttenuation(animate = false)
        mainHandler.removeCallbacks(idleReleaseRunnable)
        mainHandler.removeCallbacks(retryCaptureRunnable)
        mainHandler.removeCallbacks(scrollStopRecaptureRunnable)
        val oldBmp = activeBitmap
        activeBitmap = null
        activeShader = null
        activeCaptureTimeMs = 0L
        if (oldBmp != null) {
            runCatching { oldBmp.recycle() }
        }
    }

    fun release() {
        released = true
        setOperationActive(false)
        idleReleaseScheduled = false
        mainHandler.removeCallbacks(captureWatchdogRunnable)
        mainHandler.removeCallbacks(retryCaptureRunnable)
        mainHandler.removeCallbacks(idleReleaseRunnable)
        mainHandler.removeCallbacks(prewarmCaptureRunnable)
        mainHandler.removeCallbacks(prewarmRetryRunnable)
        mainHandler.removeCallbacks(scrollStopRecaptureRunnable)
        captureInFlight = false
        listeners.clear()
        releaseBackdropNow()
        workerExecutor?.shutdownNow()
        workerExecutor = null
    }

    @Synchronized
    private fun ensureExecutor(): ExecutorService {
        val existing = workerExecutor
        if (existing != null && !existing.isShutdown) return existing
        return Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "edgeflow-liquid-backdrop").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }.also { workerExecutor = it }
    }

    companion object {
        private const val MIN_CAPTURE_INTERVAL_API34_MS = 360L
        private const val MIN_CAPTURE_INTERVAL_API33_MS = 1050L
        private const val IDLE_RELEASE_MS = 25_000L
        private const val PREWARM_CAPTURE_DELAY_MS = 280L
        private const val SCROLL_STOP_RECAPTURE_MS = 360L
        private const val SCROLL_RECAPTURE_BUSY_RETRY_MS = 120L
        private const val MAX_SCROLL_RECAPTURE_RETRIES = 2
        private const val SCROLL_ATTENUATED_MIX = 0.15f
        private const val SCROLL_MIX_ANIM_MS = 180L
        private const val FULLSCREEN_AREA_RATIO = 0.50
        private const val CAPTURE_WATCHDOG_MS = 850L
        private const val RETRY_DELAY_FIRST_MS = 140L
        private const val RETRY_DELAY_SECOND_MS = 280L
        private const val MAX_RETRY_COUNT = 2
        private const val SAMPLE_OFFSET_PX = 42f
    }
}
