package com.edgeflow.service.translate

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import com.edgeflow.data.ConfigStore
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.LiveTranslateEngineMode
import com.edgeflow.data.TranslateLanguageCatalog
import com.edgeflow.service.HapticController
import com.edgeflow.service.LocalizedContextHelper
import com.edgeflow.settings.SettingsActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Orchestrates the **One-Tap Screen Translate** session inside the resident `AccessibilityService`
 * process with **ZERO Jetpack Compose**, **ZERO background auto-tracking loops**, and **0.0% idle
 * CPU/RAM when closed**.
 *
 * - **`toggleLiveTranslate()`**: Shows the edge-snapping one-tap translate button in standby (does
 *   NOT cover the screen until tapped) or closes and fully releases everything if already active.
 * - **`translateScreenOnce()`**: Shows the button if not visible and immediately performs one
 *   high-precision screen translation pass.
 * - **Button 1-Tap (`onPrimaryTranslateToggle`)**: Clears the translation back to the original screen
 *   if translation blocks are visible (keeping the button docked), or translates the current screen
 *   once when cleared.
 * - **Clean Auto-Clear**: Optionally clears displayed translation blocks immediately on scroll
 *   (`config.translateAutoClearOnScroll`, subscribing ONLY to `TYPE_VIEW_SCROLLED`) and always
 *   clears previous-app blocks when switching to a different main fullscreen app.
 */
class LiveScreenTranslateController(
    private val service: AccessibilityService,
    private val hapticController: HapticController,
    private val onDynamicEventsChanged: (enabled: Boolean) -> Unit
) {
    private val windowManager: WindowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var config: EdgeConfig = EdgeConfig()
    private var activeSourceLang: String = config.translateSourceLang
    private var activeTargetLang: String = config.translateTargetLang

    @Volatile
    private var isActive: Boolean = false

    @Volatile
    private var isScreenInteractive: Boolean = true

    @Volatile
    private var isProcessing: Boolean = false

    @Volatile
    private var dynamicScrollSubscribed: Boolean = false

    private var imeVisible: Boolean = false
    private var imeTopPx: Int = 0

    @Volatile
    private var currentForegroundPkg: String = ""
    private var secureToastShownForPkg: String? = null
    private var downloadingToastShown: Boolean = false
    private var wifiToastShown: Boolean = false

    @Volatile
    private var sceneEpoch: Long = 0L

    // Session-scoped components (strictly null when OFF -> 0.0% idle memory)
    private var sessionScope: CoroutineScope? = null
    private var inFlightJob: Job? = null
    private var overlayView: TranslationOverlayView? = null
    private var controlView: TranslateFloatingControlView? = null
    private var capturer: WindowCleanCapturer? = null
    private var pipeline: OnDeviceTranslationPipeline? = null
    private var layoutEngine: BlockColorAndLayoutEngine? = null

    fun isSessionActive(): Boolean = isActive

    fun currentTargetPackage(): String = currentForegroundPkg

    private fun isOnlineEngineEnabled(): Boolean =
        config.translateOnlineConsentAccepted &&
            config.translateEngineMode == LiveTranslateEngineMode.ONLINE_HIGH_QUALITY

    private fun syncDynamicScrollSubscription() {
        val shouldSubscribe = isActive && isScreenInteractive && config.translateAutoClearOnScroll
        if (dynamicScrollSubscribed != shouldSubscribe) {
            dynamicScrollSubscribed = shouldSubscribe
            onDynamicEventsChanged(shouldSubscribe)
        }
    }

    fun applyConfig(newConfig: EdgeConfig) {
        val oldSource = config.translateSourceLang
        val oldTarget = config.translateTargetLang
        val oldStyle = config.translateRenderStyle
        val oldAlpha = config.translateOverlayAlpha
        val oldSkip = config.translateSkipTargetLanguage
        val oldEngine = config.translateEngineMode
        val oldConsent = config.translateOnlineConsentAccepted
        val oldAutoClear = config.translateAutoClearOnScroll
        config = newConfig.clamped()

        if (!isActive) {
            activeSourceLang = config.translateSourceLang
            activeTargetLang = config.translateTargetLang
            return
        }

        controlView?.setOnlineEngineActive(isOnlineEngineEnabled())

        if (config.translateAutoClearOnScroll != oldAutoClear) {
            syncDynamicScrollSubscription()
        }

        var needsRefreshIfVisible = false
        if (config.translateSourceLang != oldSource || config.translateTargetLang != oldTarget) {
            activeSourceLang = config.translateSourceLang
            activeTargetLang = config.translateTargetLang
            controlView?.setLanguageBadge(activeSourceLang, activeTargetLang)
            needsRefreshIfVisible = true
        }
        if (config.translateRenderStyle != oldStyle ||
            config.translateOverlayAlpha != oldAlpha ||
            config.translateSkipTargetLanguage != oldSkip ||
            config.translateEngineMode != oldEngine ||
            config.translateOnlineConsentAccepted != oldConsent
        ) {
            needsRefreshIfVisible = true
        }

        if (needsRefreshIfVisible && isScreenInteractive && overlayView?.hasBlocks() == true) {
            triggerSingleShotTranslate(bypassCache = false)
        }
    }

    /**
     * Gesture Action: "Toggle Translate Button".
     * - When OFF: attaches the overlay and docked one-tap button in standby (without covering screen).
     * - When ON: closes everything and releases all ML Kit / overlay resources.
     */
    fun toggleLiveTranslate(): Boolean {
        if (isActive) {
            stopAndRelease()
            return true
        }
        return startSession(translateImmediately = false)
    }

    /**
     * Gesture Action: "Translate Screen Now".
     * - When OFF: attaches the docked button AND immediately translates the current screen once.
     * - When ON: immediately retranslates the current screen once.
     */
    fun translateScreenOnce(): Boolean {
        if (isActive) {
            triggerSingleShotTranslate(bypassCache = false)
            return true
        }
        return startSession(translateImmediately = true)
    }

    private fun handlePrimaryTranslateToggle() {
        if (!isActive || !isScreenInteractive) return
        if (isProcessing) return
        if (overlayView?.hasBlocks() == true) {
            clearVisibleTranslation()
        } else {
            triggerSingleShotTranslate(bypassCache = false)
        }
    }

    private fun clearVisibleTranslation() {
        sceneEpoch++
        inFlightJob?.cancel()
        inFlightJob = null
        isProcessing = false
        overlayView?.clearBlocks()
        controlView?.setProcessing(false)
        controlView?.setHasVisibleTranslation(false)
    }

    private fun handleEngineToggleFromToolbar() {
        if (!config.translateOnlineConsentAccepted) {
            showToast(R.string.translate_toast_online_consent_required)
            runCatching {
                val intent = Intent(service, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(SettingsActivity.EXTRA_OPEN_TRANSLATE_SETTINGS, true)
                }
                service.startActivity(intent)
            }
            return
        }

        val nextMode = if (config.translateEngineMode == LiveTranslateEngineMode.ONLINE_HIGH_QUALITY) {
            LiveTranslateEngineMode.OFFLINE_MLKIT
        } else {
            LiveTranslateEngineMode.ONLINE_HIGH_QUALITY
        }
        config = config.copy(translateEngineMode = nextMode).clamped()
        ConfigStore.save(service, config)
        controlView?.setOnlineEngineActive(isOnlineEngineEnabled())

        showToast(
            if (isOnlineEngineEnabled()) R.string.translate_toast_switched_online
            else R.string.translate_toast_switched_offline
        )

        pipeline?.clearCache()
        if (overlayView?.hasBlocks() == true) {
            triggerSingleShotTranslate(bypassCache = true)
        }
    }

    private fun startSession(translateImmediately: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showToast(R.string.translate_toast_unsupported_android)
            return false
        }

        stopAndRelease()

        activeSourceLang = config.translateSourceLang
        activeTargetLang = config.translateTargetLang
        secureToastShownForPkg = null
        downloadingToastShown = false
        wifiToastShown = false
        isProcessing = false
        sceneEpoch++

        val (screenW, screenH) = currentDisplaySize()
        val dm = service.resources.displayMetrics
        val density = dm.density
        val scaledDensity = service.resources.configuration.fontScale * density

        val overlay = TranslationOverlayView(service).apply {
            updateImeState(imeVisible, imeTopPx)
            setContentClipBounds(resolveStatusBarGuardPx(), screenH - resolveNavBarGuardPx())
        }
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val dockWidthPx = (TranslateFloatingControlView.COLLAPSED_WIDTH_DP * density).roundToInt()
        val dockHeightPx = (TranslateFloatingControlView.COLLAPSED_HEIGHT_DP * density).roundToInt()
        val controlParams = WindowManager.LayoutParams(
            dockWidthPx,
            dockHeightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - dockWidthPx - (8f * density).roundToInt()).coerceAtLeast(0)
            y = (screenH * 0.34f).roundToInt()
        }

        val dock = TranslateFloatingControlView(
            context = service,
            windowManager = windowManager,
            layoutParams = controlParams,
            hapticController = hapticController,
            onPrimaryTranslateToggle = { handlePrimaryTranslateToggle() },
            onRetranslate = {
                overlayView?.clearBlocks()
                controlView?.setHasVisibleTranslation(false)
                triggerSingleShotTranslate(bypassCache = true)
            },
            onPeekChanged = { peeking ->
                overlayView?.setPeekOriginal(peeking)
            },
            onQuickSwapLanguages = {
                val (newSrc, newTgt) = TranslateLanguageCatalog.computeQuickSwap(
                    activeSourceLang,
                    activeTargetLang
                )
                activeSourceLang = newSrc
                activeTargetLang = newTgt
                controlView?.setLanguageBadge(activeSourceLang, activeTargetLang)
                overlayView?.clearBlocks()
                controlView?.setHasVisibleTranslation(false)
                triggerSingleShotTranslate(bypassCache = false)
            },
            onToggleEngine = {
                handleEngineToggleFromToolbar()
            },
            onClose = {
                stopAndRelease()
            }
        ).apply {
            updateDisplayBounds(screenW, screenH)
            setLanguageBadge(activeSourceLang, activeTargetLang)
            setOnlineEngineActive(isOnlineEngineEnabled())
            setHasVisibleTranslation(false)
            setProcessing(false)
        }

        val addedOverlay = runCatching {
            windowManager.addView(overlay, overlayParams)
            true
        }.getOrDefault(false)
        if (!addedOverlay) return false

        val addedDock = runCatching {
            windowManager.addView(dock, controlParams)
            true
        }.getOrDefault(false)
        if (!addedDock) {
            runCatching { windowManager.removeViewImmediate(overlay) }
            return false
        }

        overlayView = overlay
        controlView = dock
        layoutEngine = BlockColorAndLayoutEngine(density, scaledDensity)
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        capturer = WindowCleanCapturer(
            service = service,
            setOverlayOnlyHiddenForFallback = { hidden ->
                overlayView?.setFallbackCaptureHidden(hidden)
            }
        )
        pipeline = OnDeviceTranslationPipeline(service.applicationContext) { notice ->
            mainHandler.post { handlePipelineNotice(notice) }
        }

        isActive = true
        syncDynamicScrollSubscription()

        if (translateImmediately) {
            mainHandler.postDelayed({
                if (isActive && isScreenInteractive) {
                    triggerSingleShotTranslate(bypassCache = false)
                }
            }, INITIAL_CAPTURE_DELAY_MS)
        }
        return true
    }

    fun onViewScrolled(
        sourcePackage: String,
        scrollDeltaX: Int,
        scrollDeltaY: Int,
        isRawDeltaPx: Boolean = false
    ) {
        if (!isActive || !isScreenInteractive || !config.translateAutoClearOnScroll) return
        if (sourcePackage.isEmpty() || sourcePackage == service.packageName) return
        if (overlayView?.hasBlocks() == true) {
            // Immediately clear misaligned translation when user scrolls; no auto-retranslate
            clearVisibleTranslation()
        }
    }

    fun onWindowStateChanged(packageName: String, isMainFullscreenApp: Boolean = true) {
        if (!isMainFullscreenApp) return
        if (packageName.isEmpty() || packageName == service.packageName) return
        if (packageName != currentForegroundPkg) {
            currentForegroundPkg = packageName
            secureToastShownForPkg = null
            if (isActive) {
                // Clear previous app's translation while keeping the floating button ready
                clearVisibleTranslation()
            }
        }
    }

    fun updateImeState(visible: Boolean, imeTopPx: Int) {
        this.imeVisible = visible
        this.imeTopPx = imeTopPx
        overlayView?.updateImeState(visible, imeTopPx)
    }

    fun onConfigurationChanged() {
        if (!isActive) return
        val (screenW, screenH) = currentDisplaySize()
        val dm = service.resources.displayMetrics
        val density = dm.density
        val scaledDensity = service.resources.configuration.fontScale * density
        layoutEngine = BlockColorAndLayoutEngine(density, scaledDensity)
        controlView?.updateDisplayBounds(screenW, screenH)
        overlayView?.setContentClipBounds(resolveStatusBarGuardPx(), screenH - resolveNavBarGuardPx())
        clearVisibleTranslation()
    }

    fun onScreenOff() {
        isScreenInteractive = false
        if (isActive) {
            inFlightJob?.cancel()
            inFlightJob = null
            isProcessing = false
            controlView?.setProcessing(false)
            syncDynamicScrollSubscription()
        }
    }

    fun onScreenOn() {
        isScreenInteractive = true
        if (isActive) {
            syncDynamicScrollSubscription()
        }
    }

    /**
     * Single-shot high-precision translation pass:
     * 1. Detects content viewport clip bounds (address bar / bottom chrome guard).
     * 2. Uses Tier 1 Accessibility Node extraction when non-browser native content is rich enough.
     * 3. Otherwise captures a single clean `1920px` window frame -> ML Kit OCR -> Online/Offline
     *    translation -> 2D non-overlapping adaptive rendering -> commits once cleanly.
     */
    private fun triggerSingleShotTranslate(bypassCache: Boolean) {
        if (!isActive || !isScreenInteractive) return
        val activeScope = sessionScope ?: return
        val activeCapturer = capturer ?: return
        val activePipeline = pipeline ?: return
        val activeEngine = layoutEngine ?: return

        inFlightJob?.cancel()
        inFlightJob = null

        val startEpoch = ++sceneEpoch
        if (bypassCache) {
            activePipeline.clearCache()
            activeCapturer.clearLastCapture()
        }

        val (screenW, screenH) = currentDisplaySize()
        val statusGuardPx = resolveStatusBarGuardPx()
        val navGuardPx = resolveNavBarGuardPx()
        val pillBounds = controlView?.getCurrentScreenBounds()?.let {
            BlockBounds(it.left, it.top, it.right, it.bottom)
        }

        val srcLang = activeSourceLang
        val tgtLang = activeTargetLang
        val skipTarget = config.translateSkipTargetLanguage
        val wifiOnly = config.translateWifiOnlyDownload
        val renderStyle = config.translateRenderStyle
        val overlayAlpha = config.translateOverlayAlpha
        val useOnline = isOnlineEngineEnabled()

        val viewportInfo = StructuredNodeTextExtractor.detectContentViewportAndMode(
            service = service,
            screenWidthPx = screenW,
            screenHeightPx = screenH,
            statusBarGuardPx = statusGuardPx,
            navBarGuardPx = navGuardPx
        )
        overlayView?.setContentClipBounds(
            viewportInfo.contentClipTopPx,
            viewportInfo.contentClipBottomPx
        )
        if (viewportInfo.targetPackageName.isNotEmpty() && currentForegroundPkg.isEmpty()) {
            currentForegroundPkg = viewportInfo.targetPackageName
        }

        // Path A: Non-browser native apps with rich Tier 1 Accessibility text nodes
        if (!viewportInfo.isBrowserOrWebView) {
            val extracted = StructuredNodeTextExtractor.extractWithViewportAndAnchors(
                service = service,
                screenWidthPx = screenW,
                screenHeightPx = screenH,
                statusBarGuardPx = statusGuardPx,
                navBarGuardPx = navGuardPx,
                controlPillBounds = pillBounds
            )
            // Anchor polling is retired — immediately recycle any returned anchor nodes
            StructuredNodeTextExtractor.recycleAnchors(extracted.anchorNodes)

            if (extracted.targetPackageName.isNotEmpty() && currentForegroundPkg.isEmpty()) {
                currentForegroundPkg = extracted.targetPackageName
            }

            if (StructuredNodeTextExtractor.isRichEnoughToSkipOcr(extracted.blocks)) {
                overlayView?.setContentClipBounds(
                    extracted.contentClipTopPx,
                    extracted.contentClipBottomPx
                )

                // Lazily refresh 1 background bitmap for accurate bg/ink sampling if missing
                if (!activeCapturer.hasLastCapture()) {
                    activeCapturer.captureCleanFrame(
                        preferredWindowId = extracted.targetWindowId,
                        forceIgnoreRateLimit = false
                    ) { outcome ->
                        if (outcome is WindowCleanCapturer.CaptureOutcome.Success) {
                            runCatching { outcome.bitmap.recycle() }
                        }
                    }
                }

                isProcessing = true
                controlView?.setProcessing(true)
                inFlightJob = activeScope.launch {
                    try {
                        val freshFiltered = withContext(Dispatchers.Default) {
                            TranslationBlockFilter.filterAndMerge(
                                rawBlocks = extracted.blocks,
                                screenWidthPx = screenW,
                                screenHeightPx = screenH,
                                statusBarGuardPx = statusGuardPx,
                                navBarGuardPx = navGuardPx,
                                controlPillBounds = pillBounds,
                                targetLang = tgtLang,
                                skipTargetLanguage = skipTarget
                            )
                        }
                        if (!isActive || sceneEpoch != startEpoch) return@launch

                        if (freshFiltered.isEmpty()) {
                            overlayView?.clearBlocks()
                            controlView?.setHasVisibleTranslation(false)
                            showToast(R.string.translate_toast_no_text_found)
                            return@launch
                        }

                        val translatedBlocks = activePipeline.translateFilteredBlocks(
                            filtered = freshFiltered,
                            sourceLang = srcLang,
                            targetLang = tgtLang,
                            skipTargetLanguage = skipTarget,
                            wifiOnlyDownload = wifiOnly,
                            useOnlineEngine = useOnline
                        ).blocks

                        val compiled = withContext(Dispatchers.Default) {
                            activeEngine.compileBlocks(
                                blocks = translatedBlocks,
                                captureBitmap = null,
                                offsetX = 0,
                                offsetY = 0,
                                captureScale = 1f,
                                screenWidthPx = screenW,
                                screenHeightPx = screenH,
                                renderStyle = renderStyle,
                                overlayAlpha = overlayAlpha,
                                backgroundColorSampler = { rect -> activeCapturer.estimateBackground(rect) },
                                inkColorSampler = { rect, bgRgb -> activeCapturer.estimateInkColor(rect, bgRgb) }
                            )
                        }

                        if (!isActive || sceneEpoch != startEpoch) return@launch
                        commitSingleShotResult(freshFiltered, compiled)
                    } catch (_: Throwable) {
                        if (isActive && sceneEpoch == startEpoch) {
                            controlView?.setHasVisibleTranslation(overlayView?.hasBlocks() == true)
                        }
                    } finally {
                        if (sceneEpoch == startEpoch) {
                            isProcessing = false
                            controlView?.setProcessing(false)
                        }
                    }
                }
                return
            }
        }

        // Path B: Clean 1920px Window Capture -> ML Kit OCR -> Online/Offline Translate -> Render
        val clipTopPx = viewportInfo.contentClipTopPx
        val clipBottomPx = viewportInfo.contentClipBottomPx
        isProcessing = true
        controlView?.setProcessing(true)
        activeCapturer.captureCleanFrame(
            preferredWindowId = viewportInfo.targetWindowId,
            forceIgnoreRateLimit = true
        ) { outcome ->
            if (!isActive || sceneEpoch != startEpoch) {
                if (outcome is WindowCleanCapturer.CaptureOutcome.Success) {
                    runCatching { outcome.bitmap.recycle() }
                }
                if (sceneEpoch == startEpoch) {
                    isProcessing = false
                    controlView?.setProcessing(false)
                }
                return@captureCleanFrame
            }

            when (outcome) {
                is WindowCleanCapturer.CaptureOutcome.SecureBlocked -> {
                    isProcessing = false
                    controlView?.setProcessing(false)
                    overlayView?.clearBlocks()
                    controlView?.setHasVisibleTranslation(false)
                    val pkgKey = currentForegroundPkg.ifEmpty { "__secure__" }
                    if (secureToastShownForPkg != pkgKey) {
                        secureToastShownForPkg = pkgKey
                        showToast(R.string.translate_toast_secure_screen)
                    }
                }

                is WindowCleanCapturer.CaptureOutcome.RateLimitedOrBusy,
                is WindowCleanCapturer.CaptureOutcome.Failed -> {
                    isProcessing = false
                    controlView?.setProcessing(false)
                    controlView?.setHasVisibleTranslation(overlayView?.hasBlocks() == true)
                }

                is WindowCleanCapturer.CaptureOutcome.Success -> {
                    val bmp = outcome.bitmap
                    val occupiedSnapshot = if (outcome.containsOverlay) {
                        overlayView?.getOccupiedScreenBounds().orEmpty()
                    } else {
                        emptyList()
                    }
                    inFlightJob = activeScope.launch {
                        try {
                            val freshFiltered = activePipeline.extractOcrFilteredBlocks(
                                bitmap = bmp,
                                offsetX = outcome.offsetX,
                                offsetY = outcome.offsetY,
                                captureScale = outcome.captureScale,
                                screenWidthPx = screenW,
                                screenHeightPx = screenH,
                                statusBarGuardPx = clipTopPx.coerceAtLeast(statusGuardPx),
                                navBarGuardPx = (screenH - clipBottomPx).coerceAtLeast(navGuardPx),
                                controlPillBounds = pillBounds,
                                sourceLang = srcLang,
                                targetLang = tgtLang,
                                skipTargetLanguage = skipTarget,
                                occupiedRects = occupiedSnapshot
                            )
                            if (!isActive || sceneEpoch != startEpoch) return@launch

                            if (freshFiltered.isEmpty()) {
                                overlayView?.clearBlocks()
                                controlView?.setHasVisibleTranslation(false)
                                showToast(R.string.translate_toast_no_text_found)
                                return@launch
                            }

                            val translatedBlocks = activePipeline.translateFilteredBlocks(
                                filtered = freshFiltered,
                                sourceLang = srcLang,
                                targetLang = tgtLang,
                                skipTargetLanguage = skipTarget,
                                wifiOnlyDownload = wifiOnly,
                                useOnlineEngine = useOnline
                            ).blocks

                            val compiled = withContext(Dispatchers.Default) {
                                activeEngine.compileBlocks(
                                    blocks = translatedBlocks,
                                    captureBitmap = bmp,
                                    offsetX = outcome.offsetX,
                                    offsetY = outcome.offsetY,
                                    captureScale = outcome.captureScale,
                                    screenWidthPx = screenW,
                                    screenHeightPx = screenH,
                                    renderStyle = renderStyle,
                                    overlayAlpha = overlayAlpha
                                )
                            }

                            if (!isActive || sceneEpoch != startEpoch) return@launch
                            commitSingleShotResult(freshFiltered, compiled)
                        } catch (_: Throwable) {
                            if (isActive && sceneEpoch == startEpoch) {
                                controlView?.setHasVisibleTranslation(overlayView?.hasBlocks() == true)
                            }
                        } finally {
                            runCatching { bmp.recycle() }
                            if (sceneEpoch == startEpoch) {
                                isProcessing = false
                                controlView?.setProcessing(false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun commitSingleShotResult(
        filtered: List<FilteredOcrBlock>,
        compiled: List<RenderedTranslationBlock>
    ) {
        if (compiled.isEmpty()) {
            overlayView?.clearBlocks()
            controlView?.setHasVisibleTranslation(false)
            showToast(R.string.translate_toast_no_text_found)
            return
        }
        val paired = pairFilteredWithRendered(filtered, compiled)
        overlayView?.clearBlocks()
        overlayView?.appendNewlyTranslatedBlocks(
            newlyAdded = paired,
            initialDriftY = 0f,
            animateNewBlocks = true
        )
        controlView?.setHasVisibleTranslation(true)
    }

    private fun pairFilteredWithRendered(
        filtered: List<FilteredOcrBlock>,
        rendered: List<RenderedTranslationBlock>
    ): List<Pair<FilteredOcrBlock, RenderedTranslationBlock>> {
        if (filtered.isEmpty() || rendered.isEmpty()) return emptyList()
        val byBounds = HashMap<BlockBounds, FilteredOcrBlock>(filtered.size * 2)
        for (f in filtered) byBounds[f.bounds] = f
        val out = ArrayList<Pair<FilteredOcrBlock, RenderedTranslationBlock>>(rendered.size)
        for (r in rendered) {
            val match = byBounds[r.sourceBounds] ?: FilteredOcrBlock(
                bounds = r.sourceBounds,
                text = r.blockKey,
                lineCount = 1,
                averageLineHeightPx = r.averageLineHeightPx
            )
            out.add(match to r)
        }
        return out
    }

    private fun handlePipelineNotice(notice: PipelineStatusNotice) {
        if (!isActive) return
        when (notice) {
            PipelineStatusNotice.DOWNLOADING_MODEL -> {
                if (!downloadingToastShown) {
                    downloadingToastShown = true
                    showToast(R.string.translate_toast_downloading_model)
                }
            }
            PipelineStatusNotice.WIFI_REQUIRED_FOR_MODEL -> {
                if (!wifiToastShown) {
                    wifiToastShown = true
                    showToast(R.string.translate_toast_wifi_required)
                }
            }
            PipelineStatusNotice.MODEL_DOWNLOAD_FAILED -> {
                showToast(R.string.translate_toast_model_download_failed)
            }
        }
    }

    private fun currentDisplaySize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width().coerceAtLeast(320) to bounds.height().coerceAtLeast(480)
        } else {
            val dm = service.resources.displayMetrics
            dm.widthPixels.coerceAtLeast(320) to dm.heightPixels.coerceAtLeast(480)
        }
    }

    private fun resolveStatusBarGuardPx(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        val fromRes = if (id > 0) service.resources.getDimensionPixelSize(id) else 0
        return fromRes.coerceAtLeast((24f * service.resources.displayMetrics.density).roundToInt())
    }

    private fun resolveNavBarGuardPx(): Int {
        val id = service.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val fromRes = if (id > 0) service.resources.getDimensionPixelSize(id) else 0
        return fromRes.coerceAtMost((56f * service.resources.displayMetrics.density).roundToInt())
    }

    private fun showToast(resId: Int) {
        val langTag = config.appLanguageTag
        mainHandler.post {
            runCatching {
                val localized = LocalizedContextHelper.forTag(service.applicationContext, langTag)
                Toast.makeText(
                    service.applicationContext,
                    localized.getString(resId),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Immediately stops One-Tap Screen Translate, removes all overlays via `removeViewImmediate`,
     * cancels all coroutines, closes all ML Kit clients, and unsubscribes dynamic scroll events
     * -> **0.0% idle CPU & RAM**.
     */
    fun stopAndRelease() {
        isActive = false
        isProcessing = false
        sceneEpoch++

        inFlightJob?.cancel()
        inFlightJob = null
        sessionScope?.cancel()
        sessionScope = null

        controlView?.let { dock ->
            dock.cleanup()
            runCatching { windowManager.removeViewImmediate(dock) }
        }
        controlView = null

        overlayView?.let { overlay ->
            overlay.clearBlocks()
            runCatching { windowManager.removeViewImmediate(overlay) }
        }
        overlayView = null

        capturer?.release()
        capturer = null

        pipeline?.release()
        pipeline = null

        layoutEngine = null

        if (dynamicScrollSubscribed) {
            dynamicScrollSubscribed = false
            onDynamicEventsChanged(false)
        }
    }

    enum class DriftResolution {
        APPLY_EXACT,
        APPLY_WITH_DRIFT,
        DROP_AND_RESCAN
    }

    companion object {
        private const val INITIAL_CAPTURE_DELAY_MS = 140L

        const val STALE_DRIFT_DROP_PX = 140f
        const val BAND_OCR_MARGIN_BMP_PX = 140
        const val BAND_OCR_MIN_DELTA_BMP_PX = 24
        const val BAND_OCR_MAX_RATIO = 0.68f
        const val BAND_OCR_MIN_CONFIDENCE = 0.55f
        const val BAND_OCR_MAX_BITMAP_FRACTION = 0.72f
        const val BAND_OCR_MIN_HEIGHT_PX = 48

        /**
         * Classifies scroll displacement accumulated during an async OCR/translation pass.
         */
        fun classifyScrollDrift(
            startScrollY: Float,
            currentScrollY: Float
        ): Pair<DriftResolution, Float> {
            val driftY = currentScrollY - startScrollY
            val absDrift = abs(driftY)
            return when {
                absDrift > STALE_DRIFT_DROP_PX -> DriftResolution.DROP_AND_RESCAN to driftY
                absDrift >= 1f -> DriftResolution.APPLY_WITH_DRIFT to driftY
                else -> DriftResolution.APPLY_EXACT to 0f
            }
        }

        /**
         * Computes the newly exposed vertical bitmap band `[topBmpPx, bottomBmpPx]` for partial-band
         * crop math, or returns `null` when a full-viewport pass should be performed instead.
         */
        fun computeExposedCropBandBmp(
            deltaYPx: Int,
            confidence: Float,
            bmpClipTop: Int,
            bmpClipBottom: Int,
            bmpHeight: Int,
            marginPx: Int = BAND_OCR_MARGIN_BMP_PX
        ): Pair<Int, Int>? {
            if (confidence < BAND_OCR_MIN_CONFIDENCE) return null
            if (bmpHeight <= BAND_OCR_MIN_HEIGHT_PX) return null
            val safeClipTop = bmpClipTop.coerceIn(0, bmpHeight - 1)
            val safeClipBottom = bmpClipBottom.coerceIn(safeClipTop + 1, bmpHeight)
            val clipSpan = safeClipBottom - safeClipTop
            if (clipSpan <= BAND_OCR_MIN_HEIGHT_PX) return null

            val absDelta = abs(deltaYPx)
            val maxBandDelta = (clipSpan * BAND_OCR_MAX_RATIO).roundToInt()
            if (absDelta < BAND_OCR_MIN_DELTA_BMP_PX || absDelta > maxBandDelta) {
                return null
            }

            val bandTop: Int
            val bandBottom: Int
            if (deltaYPx < 0) {
                bandTop = (safeClipBottom - absDelta - marginPx).coerceIn(safeClipTop, safeClipBottom - 1)
                bandBottom = safeClipBottom
            } else {
                bandTop = safeClipTop
                bandBottom = (safeClipTop + absDelta + marginPx).coerceIn(safeClipTop + 1, safeClipBottom)
            }

            val bandHeight = bandBottom - bandTop
            if (bandHeight < BAND_OCR_MIN_HEIGHT_PX) return null
            if (bandHeight >= (bmpHeight * BAND_OCR_MAX_BITMAP_FRACTION).toInt()) return null
            return bandTop to bandBottom
        }
    }
}
