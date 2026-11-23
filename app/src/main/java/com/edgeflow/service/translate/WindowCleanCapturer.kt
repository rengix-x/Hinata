package com.edgeflow.service.translate

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Full-resolution, clean screen capturer for Live Interactive Screen Translate.
 *
 * Key architectural guarantees:
 * - **Full-res OCR (`MAX_OCR_LONG_EDGE_PX = 2800`)**: Preserves crisp fine print, Wikipedia
 *   infoboxes, and dense table cells.
 * - **API 34+ primary (`takeScreenshotOfWindow`)**: Captures ONLY the underlying target
 *   `TYPE_APPLICATION` window (`containsOverlay = false`) with ZERO overlay hiding.
 * - **API 30–33 display fallback**: Temporarily hides ONLY `TranslationOverlayView` (NEVER the
 *   floating control pill) for 1 `Choreographer` frame when [setOverlayOnlyHiddenForFallback] is
 *   supplied, restoring immediately on callback so OCR sees 100% clean underlying content.
 * - **350ms rate-limit guard**, single-step downscale from `HardwareBuffer`, immediate
 *   `HardwareBuffer.close()` & hardware bitmap recycle, 9-point all-black `FLAG_SECURE` check.
 * - Retains thread-safe [lastCapture] for background & ink color extraction.
 *
 * Pure Kotlin / Android framework only — ZERO Jetpack Compose imports.
 */
class WindowCleanCapturer(
    private val service: AccessibilityService,
    private val setOverlayOnlyHiddenForFallback: ((Boolean) -> Unit)? = null
) {
    sealed class CaptureOutcome {
        data class Success(
            val bitmap: Bitmap,
            val offsetX: Int,
            val offsetY: Int,
            val captureScale: Float,
            val containsOverlay: Boolean,
            val isDisplayFallback: Boolean = containsOverlay
        ) : CaptureOutcome()

        data object SecureBlocked : CaptureOutcome()
        data object RateLimitedOrBusy : CaptureOutcome()
        data class Failed(val errorCode: Int) : CaptureOutcome()
    }

    data class LastCaptureSnapshot(
        val bitmap: Bitmap,
        val offsetX: Int,
        val offsetY: Int,
        val captureScale: Float
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerExecutor: ExecutorService? = null
    private val lastCaptureLock = Any()

    @Volatile
    private var captureInFlight: Boolean = false

    @Volatile
    private var lastCaptureAttemptMs: Long = 0L

    @Volatile
    private var released: Boolean = false

    @Volatile
    private var overlayHiddenForFallback: Boolean = false

    @Volatile
    var lastCapture: LastCaptureSnapshot? = null
        private set

    private val tempWinBounds = Rect()
    private var activeCallback: ((CaptureOutcome) -> Unit)? = null

    private val watchdogRunnable = Runnable {
        if (captureInFlight) {
            restoreOverlayIfHidden()
            captureInFlight = false
            val cb = activeCallback
            activeCallback = null
            cb?.invoke(CaptureOutcome.Failed(-1))
        }
    }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasLastCapture(): Boolean = synchronized(lastCaptureLock) {
        val snap = lastCapture
        snap != null && !snap.bitmap.isRecycled
    }

    fun clearLastCapture() {
        synchronized(lastCaptureLock) {
            val old = lastCapture
            lastCapture = null
            if (old != null) {
                runCatching { old.bitmap.recycle() }
            }
        }
    }

    /**
     * Estimates the real underlying background color (RGB, alpha = 0xFF) around [rect] using
     * [lastCapture], returning `null` if unavailable or visually too complex.
     */
    fun estimateBackground(rect: BlockBounds): Int? = synchronized(lastCaptureLock) {
        val snap = lastCapture ?: return@synchronized null
        val bmp = snap.bitmap
        if (bmp.isRecycled) return@synchronized null
        estimateBackgroundFromBitmap(
            bitmap = bmp,
            screenBounds = rect,
            offsetX = snap.offsetX,
            offsetY = snap.offsetY,
            captureScale = snap.captureScale
        )
    }

    /**
     * Extracts authentic foreground ink color (e.g., Wikipedia blue links) inside [rect] when it
     * meets WCAG AA contrast (`>= 4.2f`) against [bgRgb].
     */
    fun estimateInkColor(rect: BlockBounds, bgRgb: Int): Int? = synchronized(lastCaptureLock) {
        val snap = lastCapture ?: return@synchronized null
        val bmp = snap.bitmap
        if (bmp.isRecycled) return@synchronized null
        estimateInkColorFromBitmap(
            bitmap = bmp,
            screenBounds = rect,
            offsetX = snap.offsetX,
            offsetY = snap.offsetY,
            captureScale = snap.captureScale,
            bgRgb = bgRgb
        )
    }

    fun captureCleanFrame(
        preferredWindowId: Int = -1,
        forceIgnoreRateLimit: Boolean = false,
        onResult: (CaptureOutcome) -> Unit
    ) {
        if (released || !isSupported()) {
            onResult(CaptureOutcome.Failed(-2))
            return
        }
        if (captureInFlight) {
            onResult(CaptureOutcome.RateLimitedOrBusy)
            return
        }

        val now = SystemClock.uptimeMillis()
        if (!forceIgnoreRateLimit && (now - lastCaptureAttemptMs) < MIN_CAPTURE_INTERVAL_MS) {
            onResult(CaptureOutcome.RateLimitedOrBusy)
            return
        }

        lastCaptureAttemptMs = now
        captureInFlight = true
        activeCallback = onResult
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val target = findFrontmostAppWindow(preferredWindowId)
            if (target != null) {
                requestWindowScreenshotApi34(target.windowId, target.offsetX, target.offsetY)
                return
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestDisplayScreenshotApi30()
        } else {
            finishWithOutcome(CaptureOutcome.Failed(-2))
        }
    }

    private data class TargetAppWindow(
        val windowId: Int,
        val offsetX: Int,
        val offsetY: Int
    )

    private fun findFrontmostAppWindow(preferredWindowId: Int): TargetAppWindow? {
        val windowList = runCatching { service.windows }.getOrNull() ?: return null
        val selfPkg = service.packageName
        var bestWin: TargetAppWindow? = null
        var bestScore = Long.MIN_VALUE

        for (i in 0 until windowList.size) {
            val win = windowList[i]
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = runCatching { win.root }.getOrNull()
            val rootPkg = root?.packageName?.toString().orEmpty()
            @Suppress("DEPRECATION")
            runCatching { root?.recycle() }
            if (StructuredNodeTextExtractor.isExcludedSystemOrSelfPackage(rootPkg, selfPkg)) {
                continue
            }
            win.getBoundsInScreen(tempWinBounds)
            if (tempWinBounds.width() < MIN_WINDOW_SIZE_PX || tempWinBounds.height() < MIN_WINDOW_SIZE_PX) {
                continue
            }
            if (preferredWindowId >= 0 && win.id == preferredWindowId) {
                return TargetAppWindow(
                    windowId = win.id,
                    offsetX = tempWinBounds.left,
                    offsetY = tempWinBounds.top
                )
            }
            val area = tempWinBounds.width().toLong() * tempWinBounds.height().toLong()
            val activeBonus = if (win.isActive) 1_000_000L else 0L
            val score = activeBonus + win.layer.toLong() * 10_000L + (area / 10_000L)
            if (bestWin == null || score > bestScore) {
                bestScore = score
                bestWin = TargetAppWindow(
                    windowId = win.id,
                    offsetX = tempWinBounds.left,
                    offsetY = tempWinBounds.top
                )
            }
        }
        return bestWin
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun requestWindowScreenshotApi34(windowId: Int, offsetX: Int, offsetY: Int) {
        val executor = ensureExecutor()
        try {
            service.takeScreenshotOfWindow(
                windowId,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        processScreenshotResult(
                            screenshot = screenshot,
                            offsetX = offsetX,
                            offsetY = offsetY,
                            containsOverlay = false,
                            isDisplayFallback = false
                        )
                    }

                    override fun onFailure(errorCode: Int) {
                        if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                            mainHandler.post { finishWithOutcome(CaptureOutcome.SecureBlocked) }
                            return
                        }
                        mainHandler.post {
                            if (released || !captureInFlight) return@post
                            requestDisplayScreenshotApi30()
                        }
                    }
                }
            )
        } catch (_: Throwable) {
            requestDisplayScreenshotApi30()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestDisplayScreenshotApi30() {
        val hideCallback = setOverlayOnlyHiddenForFallback
        if (hideCallback != null) {
            mainHandler.post {
                if (released || !captureInFlight) return@post
                overlayHiddenForFallback = true
                runCatching { hideCallback(true) }
                // Wait 1 Choreographer frame so SurfaceFlinger commits the hidden TranslationOverlayView
                Choreographer.getInstance().postFrameCallback {
                    if (released || !captureInFlight) {
                        restoreOverlayIfHidden()
                        return@postFrameCallback
                    }
                    executeDisplayScreenshotApi30(containsOverlay = false)
                }
            }
        } else {
            executeDisplayScreenshotApi30(containsOverlay = true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun executeDisplayScreenshotApi30(containsOverlay: Boolean) {
        val executor = ensureExecutor()
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        mainHandler.post { restoreOverlayIfHidden() }
                        processScreenshotResult(
                            screenshot = screenshot,
                            offsetX = 0,
                            offsetY = 0,
                            containsOverlay = containsOverlay,
                            isDisplayFallback = true
                        )
                    }

                    override fun onFailure(errorCode: Int) {
                        mainHandler.post {
                            restoreOverlayIfHidden()
                            if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                                finishWithOutcome(CaptureOutcome.SecureBlocked)
                            } else {
                                finishWithOutcome(CaptureOutcome.Failed(errorCode))
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {
            mainHandler.post {
                restoreOverlayIfHidden()
                finishWithOutcome(CaptureOutcome.Failed(-3))
            }
        }
    }

    private fun restoreOverlayIfHidden() {
        if (overlayHiddenForFallback) {
            overlayHiddenForFallback = false
            runCatching { setOverlayOnlyHiddenForFallback?.invoke(false) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun processScreenshotResult(
        screenshot: AccessibilityService.ScreenshotResult,
        offsetX: Int,
        offsetY: Int,
        containsOverlay: Boolean,
        isDisplayFallback: Boolean
    ) {
        var hardwareBmp: Bitmap? = null
        var scaledBmp: Bitmap? = null
        try {
            val buffer = screenshot.hardwareBuffer
            val colorSpace = screenshot.colorSpace
            try {
                hardwareBmp = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
            } finally {
                runCatching { buffer.close() }
            }

            if (hardwareBmp == null || released) {
                mainHandler.post { finishWithOutcome(CaptureOutcome.Failed(-4)) }
                return
            }

            val srcW = hardwareBmp.width
            val srcH = hardwareBmp.height
            if (srcW <= 0 || srcH <= 0) {
                mainHandler.post { finishWithOutcome(CaptureOutcome.Failed(-5)) }
                return
            }

            val longEdge = max(srcW, srcH)
            val scale = if (longEdge > MAX_OCR_LONG_EDGE_PX) {
                MAX_OCR_LONG_EDGE_PX.toFloat() / longEdge.toFloat()
            } else {
                1f
            }
            val dstW = (srcW * scale).toInt().coerceAtLeast(1)
            val dstH = (srcH * scale).toInt().coerceAtLeast(1)

            scaledBmp = if (dstW == srcW && dstH == srcH) {
                hardwareBmp.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                val scaledHw = Bitmap.createScaledBitmap(hardwareBmp, dstW, dstH, true)
                try {
                    scaledHw.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    if (scaledHw !== hardwareBmp) {
                        runCatching { scaledHw.recycle() }
                    }
                }
            }

            if (scaledBmp == null) {
                mainHandler.post { finishWithOutcome(CaptureOutcome.Failed(-6)) }
                return
            }

            if (isSecureBlackFrame(scaledBmp)) {
                runCatching { scaledBmp.recycle() }
                scaledBmp = null
                mainHandler.post { finishWithOutcome(CaptureOutcome.SecureBlocked) }
                return
            }

            // Retain a clean copy in lastCapture for background & ink color sampling
            if (!containsOverlay || !hasLastCapture()) {
                val snapCopy = runCatching { scaledBmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                if (snapCopy != null) {
                    synchronized(lastCaptureLock) {
                        if (released) {
                            runCatching { snapCopy.recycle() }
                        } else {
                            val old = lastCapture
                            lastCapture = LastCaptureSnapshot(
                                bitmap = snapCopy,
                                offsetX = offsetX,
                                offsetY = offsetY,
                                captureScale = scale
                            )
                            if (old != null) {
                                runCatching { old.bitmap.recycle() }
                            }
                        }
                    }
                }
            }

            val readyBmp = scaledBmp
            scaledBmp = null
            mainHandler.post {
                if (released) {
                    runCatching { readyBmp.recycle() }
                    finishWithOutcome(CaptureOutcome.Failed(-7))
                    return@post
                }
                finishWithOutcome(
                    CaptureOutcome.Success(
                        bitmap = readyBmp,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        captureScale = scale,
                        containsOverlay = containsOverlay,
                        isDisplayFallback = isDisplayFallback
                    )
                )
            }
        } catch (_: Throwable) {
            runCatching { scaledBmp?.recycle() }
            mainHandler.post { finishWithOutcome(CaptureOutcome.Failed(-8)) }
        } finally {
            runCatching { hardwareBmp?.recycle() }
        }
    }

    private fun finishWithOutcome(outcome: CaptureOutcome) {
        mainHandler.removeCallbacks(watchdogRunnable)
        restoreOverlayIfHidden()
        captureInFlight = false
        val cb = activeCallback
        activeCallback = null
        cb?.invoke(outcome)
    }

    private fun isSecureBlackFrame(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w < 8 || h < 8) return true
        val xs0 = (w * 0.20f).toInt().coerceIn(0, w - 1)
        val xs1 = (w * 0.50f).toInt().coerceIn(0, w - 1)
        val xs2 = (w * 0.80f).toInt().coerceIn(0, w - 1)
        val ys0 = (h * 0.20f).toInt().coerceIn(0, h - 1)
        val ys1 = (h * 0.50f).toInt().coerceIn(0, h - 1)
        val ys2 = (h * 0.80f).toInt().coerceIn(0, h - 1)

        val coords = intArrayOf(
            xs0, ys0, xs1, ys0, xs2, ys0,
            xs0, ys1, xs1, ys1, xs2, ys1,
            xs0, ys2, xs1, ys2, xs2, ys2
        )
        var i = 0
        while (i < coords.size) {
            val px = bmp.getPixel(coords[i], coords[i + 1])
            val a = Color.alpha(px)
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            if (a != 0 && (r > 3 || g > 3 || b > 3)) {
                return false
            }
            i += 2
        }
        return true
    }

    fun release() {
        released = true
        mainHandler.removeCallbacks(watchdogRunnable)
        restoreOverlayIfHidden()
        captureInFlight = false
        activeCallback = null
        clearLastCapture()
        workerExecutor?.shutdownNow()
        workerExecutor = null
    }

    @Synchronized
    private fun ensureExecutor(): ExecutorService {
        val existing = workerExecutor
        if (existing != null && !existing.isShutdown) return existing
        return Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "edgeflow-translate-capture").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }.also { workerExecutor = it }
    }

    companion object {
        private const val MIN_CAPTURE_INTERVAL_MS = 280L
        private const val WATCHDOG_TIMEOUT_MS = 1200L
        const val MAX_OCR_LONG_EDGE_PX = 1920
        private const val MIN_WINDOW_SIZE_PX = 120
        private const val COMPLEX_BG_VARIANCE_THRESHOLD = 48f
        private const val LIGHT_CLUSTER_LUM_CUTOFF = 138
        private const val MIN_WCAG_INK_CONTRAST = 4.2f

        /**
         * Samples 24 perimeter points (4 corners + 5 points along each of the 4 edges) around
         * [screenBounds] and extracts the dominant clean bright/dark background RGB.
         * Returns `null` when background variance is too high (e.g. photo / game art).
         */
        fun estimateBackgroundFromBitmap(
            bitmap: Bitmap,
            screenBounds: BlockBounds,
            offsetX: Int,
            offsetY: Int,
            captureScale: Float
        ): Int? {
            if (bitmap.isRecycled) return null
            val bw = bitmap.width
            val bh = bitmap.height
            if (bw < 4 || bh < 4) return null

            val l = ((screenBounds.left - offsetX) * captureScale).roundToInt()
            val t = ((screenBounds.top - offsetY) * captureScale).roundToInt()
            val r = ((screenBounds.right - offsetX) * captureScale).roundToInt()
            val b = ((screenBounds.bottom - offsetY) * captureScale).roundToInt()
            val pad = (5f * captureScale).roundToInt().coerceIn(3, 8)

            val outerL = (l - pad).coerceIn(0, bw - 1)
            val outerR = (r + pad).coerceIn(0, bw - 1)
            val outerT = (t - pad).coerceIn(0, bh - 1)
            val outerB = (b + pad).coerceIn(0, bh - 1)

            val sampleR = IntArray(24)
            val sampleG = IntArray(24)
            val sampleB = IntArray(24)
            val sampleLum = IntArray(24)
            val sortedIdx = IntArray(24)
            var count = 0

            fun record(x: Int, y: Int) {
                if (count >= sampleR.size) return
                val px = bitmap.getPixel(x, y)
                val pr = Color.red(px)
                val pg = Color.green(px)
                val pb = Color.blue(px)
                sampleR[count] = pr
                sampleG[count] = pg
                sampleB[count] = pb
                sampleLum[count] = (pr * 77 + pg * 150 + pb * 29) shr 8
                count++
            }

            // 4 outer corners
            record(outerL, outerT)
            record(outerR, outerT)
            record(outerL, outerB)
            record(outerR, outerB)

            // 5 points along each of the 4 perimeter edges (20 points -> 24 total)
            for (i in 0 until 5) {
                val frac = (i + 0.5f) / 5f
                val sx = (l + (r - l) * frac).roundToInt().coerceIn(0, bw - 1)
                val sy = (t + (b - t) * frac).roundToInt().coerceIn(0, bh - 1)
                record(sx, outerT)
                record(sx, outerB)
                record(outerL, sy)
                record(outerR, sy)
            }

            if (count < 4) return null

            for (i in 0 until count) sortedIdx[i] = i
            for (i in 1 until count) {
                val key = sortedIdx[i]
                val keyLum = sampleLum[key]
                var j = i - 1
                while (j >= 0 && sampleLum[sortedIdx[j]] > keyLum) {
                    sortedIdx[j + 1] = sortedIdx[j]
                    j--
                }
                sortedIdx[j + 1] = key
            }

            val q75Lum = sampleLum[sortedIdx[(count * 3) / 4]]
            val startIdx: Int
            val endIdx: Int
            if (q75Lum >= LIGHT_CLUSTER_LUM_CUTOFF) {
                startIdx = (count * 45) / 100
                endIdx = ((count * 92) / 100).coerceAtLeast(startIdx + 1)
            } else {
                startIdx = (count * 12) / 100
                endIdx = ((count * 62) / 100).coerceAtLeast(startIdx + 1)
            }

            val clusterLen = (endIdx - startIdx).coerceAtLeast(1)
            val repIdx = sortedIdx[startIdx + clusterLen / 2]
            val repR = sampleR[repIdx]
            val repG = sampleG[repIdx]
            val repB = sampleB[repIdx]

            var devSum = 0
            for (k in startIdx until endIdx) {
                val idx = sortedIdx[k]
                devSum += abs(sampleR[idx] - repR) + abs(sampleG[idx] - repG) + abs(sampleB[idx] - repB)
            }
            val avgChannelDev = devSum.toFloat() / (clusterLen * 3f)
            if (avgChannelDev > COMPLEX_BG_VARIANCE_THRESHOLD) {
                return null
            }
            return Color.rgb(repR, repG, repB)
        }

        /**
         * Samples a 3×5 inner grid (15 points) inside [screenBounds], takes the top 30% pixels
         * farthest in RGB distance from [bgRgb], averages their color, and returns it only when
         * WCAG contrast ratio against [bgRgb] is `>= 4.2f`.
         */
        fun estimateInkColorFromBitmap(
            bitmap: Bitmap,
            screenBounds: BlockBounds,
            offsetX: Int,
            offsetY: Int,
            captureScale: Float,
            bgRgb: Int
        ): Int? {
            if (bitmap.isRecycled) return null
            val bw = bitmap.width
            val bh = bitmap.height
            if (bw < 4 || bh < 4) return null

            val l = ((screenBounds.left - offsetX) * captureScale).roundToInt().coerceIn(0, bw - 1)
            val t = ((screenBounds.top - offsetY) * captureScale).roundToInt().coerceIn(0, bh - 1)
            val r = ((screenBounds.right - offsetX) * captureScale).roundToInt().coerceIn(l, bw - 1)
            val b = ((screenBounds.bottom - offsetY) * captureScale).roundToInt().coerceIn(t, bh - 1)
            if (r - l < 2 || b - t < 2) return null

            val bgR = Color.red(bgRgb)
            val bgG = Color.green(bgRgb)
            val bgB = Color.blue(bgRgb)

            val rs = IntArray(15)
            val gs = IntArray(15)
            val bs = IntArray(15)
            val distSq = IntArray(15)
            val order = IntArray(15)
            var count = 0

            for (row in 0 until 3) {
                val yFrac = (row + 1) / 4f
                val sy = (t + (b - t) * yFrac).roundToInt().coerceIn(0, bh - 1)
                for (col in 0 until 5) {
                    val xFrac = (col + 1) / 6f
                    val sx = (l + (r - l) * xFrac).roundToInt().coerceIn(0, bw - 1)
                    val px = bitmap.getPixel(sx, sy)
                    val pr = Color.red(px)
                    val pg = Color.green(px)
                    val pb = Color.blue(px)
                    val dr = pr - bgR
                    val dg = pg - bgG
                    val db = pb - bgB
                    rs[count] = pr
                    gs[count] = pg
                    bs[count] = pb
                    distSq[count] = dr * dr + dg * dg + db * db
                    order[count] = count
                    count++
                }
            }

            if (count == 0) return null

            // Sort indices descending by RGB distance squared from background
            for (i in 1 until count) {
                val key = order[i]
                val keyDist = distSq[key]
                var j = i - 1
                while (j >= 0 && distSq[order[j]] < keyDist) {
                    order[j + 1] = order[j]
                    j--
                }
                order[j + 1] = key
            }

            // Top 30% farthest pixels (4 out of 15)
            val topK = (count * 0.30f).roundToInt().coerceIn(1, count)
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (k in 0 until topK) {
                val idx = order[k]
                sumR += rs[idx]
                sumG += gs[idx]
                sumB += bs[idx]
            }
            val inkR = (sumR / topK).coerceIn(0, 255)
            val inkG = (sumG / topK).coerceIn(0, 255)
            val inkB = (sumB / topK).coerceIn(0, 255)

            val ratio = wcagContrastRatio(inkR, inkG, inkB, bgR, bgG, bgB)
            return if (ratio >= MIN_WCAG_INK_CONTRAST) {
                Color.rgb(inkR, inkG, inkB)
            } else {
                null
            }
        }

        internal fun wcagContrastRatio(
            r1: Int, g1: Int, b1: Int,
            r2: Int, g2: Int, b2: Int
        ): Float {
            val l1 = relativeLuminance(r1, g1, b1)
            val l2 = relativeLuminance(r2, g2, b2)
            val hi = max(l1, l2)
            val lo = min(l1, l2)
            return (hi + 0.05f) / (lo + 0.05f)
        }

        private fun relativeLuminance(r: Int, g: Int, b: Int): Float {
            fun linearize(c: Int): Float {
                val s = c / 255f
                return if (s <= 0.03928f) s / 12.92f else ((s + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
            }
            return 0.2126f * linearize(r) + 0.7152f * linearize(g) + 0.0722f * linearize(b)
        }
    }
}
