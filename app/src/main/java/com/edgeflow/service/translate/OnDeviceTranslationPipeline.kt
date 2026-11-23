package com.edgeflow.service.translate

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.LruCache
import com.edgeflow.data.MlKitSafeInit
import com.edgeflow.data.OcrScript
import com.edgeflow.data.TranslateLanguageCatalog
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class TranslatedBlock(
    val screenBounds: BlockBounds,
    val originalText: String,
    val translatedText: String,
    val lineCount: Int = 1,
    val averageLineHeightPx: Int = screenBounds.height,
    val normalizedText: String = StructuredNodeTextExtractor.normalizeKey(originalText)
)

enum class PipelineStatusNotice {
    DOWNLOADING_MODEL,
    WIFI_REQUIRED_FOR_MODEL,
    MODEL_DOWNLOAD_FAILED
}

data class PipelineResult(
    val blocks: List<TranslatedBlock>,
    val notice: PipelineStatusNotice? = null
)

/**
 * Hybrid On-Device ML Kit + Opt-In High-Quality Online Translation pipeline:
 * - **Default (`useOnlineEngine = false`)**: 100% On-Device ML Kit OCR v2 + Language ID +
 *   59-language Translation (ZERO network calls).
 * - **Opt-In Online (`useOnlineEngine = true`)**: Sends text-only batches via [OnlineTranslationClient]
 *   with automatic seamless fallback to On-Device ML Kit for any failed/offline/rate-limited blocks.
 *   Skips offline language pack download prompts whenever online translation succeeds for all blocks.
 * - **Mode-partitioned 1024-entry [LruCache]** (`"on|..."` vs `"off|..."`) so switching engines never
 *   mixes stale translations.
 * - **Overlay-overlap OCR exclusion (`overlapRatioOfSelf > 0.40f`)** so overlays never need to be hidden.
 *
 * ZERO Jetpack Compose imports.
 */
class OnDeviceTranslationPipeline(
    private val context: Context,
    private val onStatusNotice: (PipelineStatusNotice) -> Unit = {}
) {
    private val recognizers = ConcurrentHashMap<OcrScript, TextRecognizer>()
    private val translators = ConcurrentHashMap<String, Translator>()
    private val modelDownloadMutex = Mutex()
    private val readyModelCodes = ConcurrentHashMap.newKeySet<String>()
    private val onlineClient = OnlineTranslationClient()

    @Volatile
    private var languageIdentifier: LanguageIdentifier? = null

    private val translationCache = object : LruCache<String, String>(LRU_CACHE_SIZE) {}

    /**
     * Tier 1 (Zero-OCR) entry point: merges inline links and paragraph lines on structured
     * [RawOcrBlock]s extracted from the target app's accessibility tree and translates them.
     */
    suspend fun translateExtractedBlocks(
        rawBlocks: List<RawOcrBlock>,
        screenWidthPx: Int,
        screenHeightPx: Int,
        statusBarGuardPx: Int,
        navBarGuardPx: Int,
        controlPillBounds: BlockBounds?,
        sourceLang: String,
        targetLang: String,
        skipTargetLanguage: Boolean,
        wifiOnlyDownload: Boolean,
        useOnlineEngine: Boolean = false
    ): PipelineResult = withContext(Dispatchers.Default) {
        if (rawBlocks.isEmpty()) {
            return@withContext PipelineResult(emptyList())
        }
        if (!useOnlineEngine && !MlKitSafeInit.ensureInitialized(context)) {
            return@withContext PipelineResult(emptyList())
        }
        val safeTarget = TranslateLanguageCatalog.sanitizeTargetCode(targetLang)
        val safeSource = TranslateLanguageCatalog.sanitizeSourceCode(sourceLang)
        translateRawBlocks(
            rawBlocks = rawBlocks,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            statusBarGuardPx = statusBarGuardPx,
            navBarGuardPx = navBarGuardPx,
            controlPillBounds = controlPillBounds,
            safeSource = safeSource,
            safeTarget = safeTarget,
            skipTargetLanguage = skipTargetLanguage,
            wifiOnlyDownload = wifiOnlyDownload,
            useOnlineEngine = useOnlineEngine
        )
    }

    /**
     * Tier 2 (OCR Fallback) entry point for games, manga, images, and opaque WebViews.
     * Whenever [occupiedRects] is non-empty, OCR boxes overlapping an existing overlay rect by
     * more than `40%` (`overlapRatioOfSelf > 0.40f`) are discarded to prevent self-OCR feedback
     * loops without ever setting `View.INVISIBLE`.
     */
    suspend fun processFrame(
        bitmap: Bitmap,
        offsetX: Int,
        offsetY: Int,
        captureScale: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
        statusBarGuardPx: Int,
        navBarGuardPx: Int,
        controlPillBounds: BlockBounds?,
        sourceLang: String,
        targetLang: String,
        skipTargetLanguage: Boolean,
        wifiOnlyDownload: Boolean,
        occupiedRects: List<BlockBounds> = emptyList(),
        containsOverlay: Boolean = false,
        useOnlineEngine: Boolean = false
    ): PipelineResult = withContext(Dispatchers.Default) {
        if (!MlKitSafeInit.ensureInitialized(context)) {
            return@withContext PipelineResult(emptyList())
        }
        val safeTarget = TranslateLanguageCatalog.sanitizeTargetCode(targetLang)
        val safeSource = TranslateLanguageCatalog.sanitizeSourceCode(sourceLang)

        val primaryScript = TranslateLanguageCatalog.ocrScriptForSource(safeSource, safeTarget)
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        var rawBlocks = runOcrScript(primaryScript, inputImage, offsetX, offsetY, captureScale)

        if (safeSource == TranslateLanguageCatalog.AUTO_CODE &&
            primaryScript == OcrScript.LATIN &&
            countLetters(rawBlocks) < AUTO_ASIAN_FALLBACK_MIN_LETTERS
        ) {
            val fallbackScript = when (safeTarget) {
                "ja" -> OcrScript.CHINESE
                "zh" -> OcrScript.JAPANESE
                else -> OcrScript.JAPANESE
            }
            val fallbackBlocks = runOcrScript(fallbackScript, inputImage, offsetX, offsetY, captureScale)
            if (countLetters(fallbackBlocks) > countLetters(rawBlocks)) {
                rawBlocks = fallbackBlocks
            }
        }

        if (occupiedRects.isNotEmpty()) {
            rawBlocks = rawBlocks.filter { block ->
                occupiedRects.none { occupied ->
                    block.bounds.overlapRatioOfSelf(occupied) > OVERLAY_OVERLAP_EXCLUSION_THRESHOLD
                }
            }
        }

        translateRawBlocks(
            rawBlocks = rawBlocks,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            statusBarGuardPx = statusBarGuardPx,
            navBarGuardPx = navBarGuardPx,
            controlPillBounds = controlPillBounds,
            safeSource = safeSource,
            safeTarget = safeTarget,
            skipTargetLanguage = skipTargetLanguage,
            wifiOnlyDownload = wifiOnlyDownload,
            useOnlineEngine = useOnlineEngine
        )
    }

    /**
     * Runs ML Kit OCR on [bitmap], tags every line with its parent ML Kit `textBlock` index as
     * `groupId`, and returns filtered & merged [FilteredOcrBlock]s ready for [ScanlineReconciler].
     */
    suspend fun extractOcrFilteredBlocks(
        bitmap: Bitmap,
        offsetX: Int,
        offsetY: Int,
        captureScale: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
        statusBarGuardPx: Int,
        navBarGuardPx: Int,
        controlPillBounds: BlockBounds?,
        sourceLang: String,
        targetLang: String,
        skipTargetLanguage: Boolean,
        occupiedRects: List<BlockBounds> = emptyList()
    ): List<FilteredOcrBlock> = extractOcrFilteredBlocksInBand(
        bitmap = bitmap,
        offsetX = offsetX,
        offsetY = offsetY,
        captureScale = captureScale,
        screenWidthPx = screenWidthPx,
        screenHeightPx = screenHeightPx,
        statusBarGuardPx = statusBarGuardPx,
        navBarGuardPx = navBarGuardPx,
        controlPillBounds = controlPillBounds,
        sourceLang = sourceLang,
        targetLang = targetLang,
        skipTargetLanguage = skipTargetLanguage,
        occupiedRects = occupiedRects,
        cropBandTopBmpPx = 0,
        cropBandBottomBmpPx = bitmap.height
    )

    /**
     * Ultra-fast partial-band crop OCR: when `[cropBandTopBmpPx, cropBandBottomBmpPx]` covers less
     * than 72% of `bitmap.height` (and >= 48px), crops ONLY the newly exposed scroll band, runs
     * ML Kit OCR on the small slice (~40-60ms), restores full-screen Y coordinates by adding
     * `safeTop`, and recycles the temporary cropped bitmap in a `finally` block.
     */
    suspend fun extractOcrFilteredBlocksInBand(
        bitmap: Bitmap,
        offsetX: Int,
        offsetY: Int,
        captureScale: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
        statusBarGuardPx: Int,
        navBarGuardPx: Int,
        controlPillBounds: BlockBounds?,
        sourceLang: String,
        targetLang: String,
        skipTargetLanguage: Boolean,
        occupiedRects: List<BlockBounds> = emptyList(),
        cropBandTopBmpPx: Int = 0,
        cropBandBottomBmpPx: Int = bitmap.height
    ): List<FilteredOcrBlock> = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return@withContext emptyList()
        }
        if (!MlKitSafeInit.ensureInitialized(context)) {
            return@withContext emptyList()
        }
        val safeTarget = TranslateLanguageCatalog.sanitizeTargetCode(targetLang)
        val safeSource = TranslateLanguageCatalog.sanitizeSourceCode(sourceLang)
        val primaryScript = TranslateLanguageCatalog.ocrScriptForSource(safeSource, safeTarget)

        val safeTop = cropBandTopBmpPx.coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
        val safeBottom = cropBandBottomBmpPx.coerceIn(safeTop + 1, bitmap.height)
        val safeH = safeBottom - safeTop
        val useBandCrop = safeH >= MIN_BAND_CROP_HEIGHT_PX &&
            safeH < (bitmap.height * MAX_BAND_CROP_HEIGHT_RATIO).toInt()

        var croppedBmp: Bitmap? = null
        var rawBlocks: List<RawOcrBlock>
        try {
            val targetBmp = if (useBandCrop) {
                runCatching {
                    Bitmap.createBitmap(bitmap, 0, safeTop, bitmap.width, safeH)
                }.getOrNull()?.also { croppedBmp = it } ?: bitmap
            } else {
                bitmap
            }
            val bandOffsetY = if (croppedBmp != null) safeTop else 0
            val inputImage = InputImage.fromBitmap(targetBmp, 0)
            rawBlocks = runOcrScript(
                script = primaryScript,
                inputImage = inputImage,
                offsetX = offsetX,
                offsetY = offsetY,
                captureScale = captureScale,
                bandOffsetBmpY = bandOffsetY
            )

            if (safeSource == TranslateLanguageCatalog.AUTO_CODE &&
                primaryScript == OcrScript.LATIN &&
                countLetters(rawBlocks) < AUTO_ASIAN_FALLBACK_MIN_LETTERS
            ) {
                val fallbackScript = when (safeTarget) {
                    "ja" -> OcrScript.CHINESE
                    "zh" -> OcrScript.JAPANESE
                    else -> OcrScript.JAPANESE
                }
                val fallbackBlocks = runOcrScript(
                    script = fallbackScript,
                    inputImage = inputImage,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    captureScale = captureScale,
                    bandOffsetBmpY = bandOffsetY
                )
                if (countLetters(fallbackBlocks) > countLetters(rawBlocks)) {
                    rawBlocks = fallbackBlocks
                }
            }
        } finally {
            runCatching { croppedBmp?.recycle() }
        }

        if (occupiedRects.isNotEmpty()) {
            rawBlocks = rawBlocks.filter { block ->
                occupiedRects.none { occupied ->
                    block.bounds.overlapRatioOfSelf(occupied) > OVERLAY_OVERLAP_EXCLUSION_THRESHOLD
                }
            }
        }

        TranslationBlockFilter.filterAndMerge(
            rawBlocks = rawBlocks,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            statusBarGuardPx = statusBarGuardPx,
            navBarGuardPx = navBarGuardPx,
            controlPillBounds = controlPillBounds,
            targetLang = safeTarget,
            skipTargetLanguage = skipTargetLanguage
        )
    }

    /**
     * Translates an already-filtered list of [FilteredOcrBlock]s (e.g., strictly the `toTranslate`
     * delta emitted by [ScanlineReconciler]).
     */
    suspend fun translateFilteredBlocks(
        filtered: List<FilteredOcrBlock>,
        sourceLang: String,
        targetLang: String,
        skipTargetLanguage: Boolean,
        wifiOnlyDownload: Boolean,
        useOnlineEngine: Boolean = false
    ): PipelineResult = withContext(Dispatchers.Default) {
        if (filtered.isEmpty()) {
            return@withContext PipelineResult(emptyList())
        }
        if (!useOnlineEngine && !MlKitSafeInit.ensureInitialized(context)) {
            return@withContext PipelineResult(emptyList())
        }
        val safeTarget = TranslateLanguageCatalog.sanitizeTargetCode(targetLang)
        val safeSource = TranslateLanguageCatalog.sanitizeSourceCode(sourceLang)
        if (useOnlineEngine) {
            translateHybridOnlineFirst(
                filtered = filtered,
                safeSource = safeSource,
                safeTarget = safeTarget,
                skipTargetLanguage = skipTargetLanguage,
                wifiOnlyDownload = wifiOnlyDownload
            )
        } else {
            translateOfflineOnly(
                filtered = filtered,
                safeSource = safeSource,
                safeTarget = safeTarget,
                skipTargetLanguage = skipTargetLanguage,
                wifiOnlyDownload = wifiOnlyDownload
            )
        }
    }

    private suspend fun runOcrScript(
        script: OcrScript,
        inputImage: InputImage,
        offsetX: Int,
        offsetY: Int,
        captureScale: Float,
        bandOffsetBmpY: Int = 0
    ): List<RawOcrBlock> {
        val recognizer = getOrCreateRecognizer(script)
        val visionText = runCatching { recognizer.process(inputImage).await() }.getOrNull()
            ?: return emptyList()

        val invScale = if (captureScale > 0.01f) 1f / captureScale else 1f
        val rawBlocks = ArrayList<RawOcrBlock>(visionText.textBlocks.size * 2)

        for (blockIdx in visionText.textBlocks.indices) {
            val textBlock = visionText.textBlocks[blockIdx]
            val lines = textBlock.lines
            if (lines.isEmpty()) {
                val box = textBlock.boundingBox ?: continue
                rawBlocks.add(
                    RawOcrBlock(
                        bounds = BlockBounds(
                            left = offsetX + (box.left * invScale).roundToInt(),
                            top = offsetY + ((box.top + bandOffsetBmpY) * invScale).roundToInt(),
                            right = offsetX + (box.right * invScale).roundToInt(),
                            bottom = offsetY + ((box.bottom + bandOffsetBmpY) * invScale).roundToInt()
                        ),
                        text = textBlock.text,
                        groupId = blockIdx
                    )
                )
            } else {
                for (line in lines) {
                    val box = line.boundingBox ?: continue
                    rawBlocks.add(
                        RawOcrBlock(
                            bounds = BlockBounds(
                                left = offsetX + (box.left * invScale).roundToInt(),
                                top = offsetY + ((box.top + bandOffsetBmpY) * invScale).roundToInt(),
                                right = offsetX + (box.right * invScale).roundToInt(),
                                bottom = offsetY + ((box.bottom + bandOffsetBmpY) * invScale).roundToInt()
                            ),
                            text = line.text,
                            groupId = blockIdx
                        )
                    )
                }
            }
        }
        return rawBlocks
    }

    private fun countLetters(blocks: List<RawOcrBlock>): Int {
        var total = 0
        for (b in blocks) {
            for (ch in b.text) {
                if (Character.isLetter(ch)) total++
            }
        }
        return total
    }

    private suspend fun translateRawBlocks(
        rawBlocks: List<RawOcrBlock>,
        screenWidthPx: Int,
        screenHeightPx: Int,
        statusBarGuardPx: Int,
        navBarGuardPx: Int,
        controlPillBounds: BlockBounds?,
        safeSource: String,
        safeTarget: String,
        skipTargetLanguage: Boolean,
        wifiOnlyDownload: Boolean,
        useOnlineEngine: Boolean
    ): PipelineResult {
        val filtered = TranslationBlockFilter.filterAndMerge(
            rawBlocks = rawBlocks,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            statusBarGuardPx = statusBarGuardPx,
            navBarGuardPx = navBarGuardPx,
            controlPillBounds = controlPillBounds,
            targetLang = safeTarget,
            skipTargetLanguage = skipTargetLanguage
        )
        if (filtered.isEmpty()) {
            return PipelineResult(emptyList())
        }

        if (useOnlineEngine) {
            return translateHybridOnlineFirst(
                filtered = filtered,
                safeSource = safeSource,
                safeTarget = safeTarget,
                skipTargetLanguage = skipTargetLanguage,
                wifiOnlyDownload = wifiOnlyDownload
            )
        }

        return translateOfflineOnly(
            filtered = filtered,
            safeSource = safeSource,
            safeTarget = safeTarget,
            skipTargetLanguage = skipTargetLanguage,
            wifiOnlyDownload = wifiOnlyDownload
        )
    }

    /**
     * Opt-in high-quality online translation path with automatic on-device ML Kit fallback
     * strictly for blocks that failed online translation (e.g. offline, timeout, HTTP 429).
     */
    private suspend fun translateHybridOnlineFirst(
        filtered: List<FilteredOcrBlock>,
        safeSource: String,
        safeTarget: String,
        skipTargetLanguage: Boolean,
        wifiOnlyDownload: Boolean
    ): PipelineResult {
        val skipFlag = if (skipTargetLanguage) "1" else "0"
        val resolvedTranslations = arrayOfNulls<String>(filtered.size)
        val uncachedIndices = ArrayList<Int>(filtered.size)
        val uncachedTexts = ArrayList<String>(filtered.size)
        val uncachedCacheKeys = ArrayList<String>(filtered.size)
        var langId: LanguageIdentifier? = null
        val mlKitAvailable = MlKitSafeInit.ensureInitialized(context)

        for (i in filtered.indices) {
            val block = filtered[i]
            val onlineKey = "on|$safeSource->$safeTarget|$skipFlag|${block.text}"
            val cached = synchronized(translationCache) { translationCache.get(onlineKey) }
            if (cached != null) {
                if (cached != CACHE_SENTINEL_SKIP) {
                    resolvedTranslations[i] = cached
                }
                continue
            }

            if (skipTargetLanguage && mlKitAvailable) {
                if (langId == null) {
                    langId = runCatching { getOrCreateLanguageIdentifier() }.getOrNull()
                }
                val id = langId
                if (id != null) {
                    val (code, confidence) = identifyTopLanguage(id, block.text)
                    if (TranslationBlockFilter.shouldSkipByDetectedLanguage(code, confidence, safeTarget)) {
                        synchronized(translationCache) { translationCache.put(onlineKey, CACHE_SENTINEL_SKIP) }
                        continue
                    }
                }
            }

            uncachedIndices.add(i)
            uncachedTexts.add(block.text)
            uncachedCacheKeys.add(onlineKey)
        }

        val unresolvedForOffline = ArrayList<Pair<Int, FilteredOcrBlock>>()

        if (uncachedIndices.isNotEmpty()) {
            val batchSource = if (safeSource == TranslateLanguageCatalog.AUTO_CODE) {
                TranslateLanguageCatalog.AUTO_CODE
            } else {
                safeSource
            }
            val onlineMap = onlineClient.translateBatch(
                texts = uncachedTexts,
                sourceLang = batchSource,
                targetLang = safeTarget
            )
            for (k in uncachedIndices.indices) {
                val origIdx = uncachedIndices[k]
                val tr = onlineMap?.get(k)?.trim()
                if (!tr.isNullOrEmpty()) {
                    resolvedTranslations[origIdx] = tr
                    synchronized(translationCache) {
                        translationCache.put(uncachedCacheKeys[k], tr)
                    }
                } else {
                    unresolvedForOffline.add(origIdx to filtered[origIdx])
                }
            }
        }

        var fallbackNotice: PipelineStatusNotice? = null
        if (unresolvedForOffline.isNotEmpty() && mlKitAvailable) {
            val offlineResult = translateOfflineOnly(
                filtered = unresolvedForOffline.map { it.second },
                safeSource = safeSource,
                safeTarget = safeTarget,
                skipTargetLanguage = skipTargetLanguage,
                wifiOnlyDownload = wifiOnlyDownload
            )
            fallbackNotice = offlineResult.notice
            val fallbackByText = HashMap<String, String>(offlineResult.blocks.size * 2)
            for (fb in offlineResult.blocks) {
                fallbackByText[fb.originalText] = fb.translatedText
            }
            for ((origIdx, block) in unresolvedForOffline) {
                val fb = fallbackByText[block.text]
                if (!fb.isNullOrEmpty()) {
                    resolvedTranslations[origIdx] = fb
                }
            }
        }

        val output = ArrayList<TranslatedBlock>(filtered.size)
        for (i in filtered.indices) {
            val tr = resolvedTranslations[i] ?: continue
            val block = filtered[i]
            output.add(
                TranslatedBlock(
                    screenBounds = block.bounds,
                    originalText = block.text,
                    translatedText = tr,
                    lineCount = block.lineCount,
                    averageLineHeightPx = block.averageLineHeightPx,
                    normalizedText = StructuredNodeTextExtractor.normalizeKey(block.text)
                )
            )
        }
        return PipelineResult(output, fallbackNotice)
    }

    /**
     * 100% On-Device ML Kit translation path (ZERO network calls).
     */
    private suspend fun translateOfflineOnly(
        filtered: List<FilteredOcrBlock>,
        safeSource: String,
        safeTarget: String,
        skipTargetLanguage: Boolean,
        wifiOnlyDownload: Boolean
    ): PipelineResult {
        if (!MlKitSafeInit.ensureInitialized(context)) {
            return PipelineResult(emptyList())
        }
        val mlTarget = toMlKitLang(safeTarget) ?: return PipelineResult(emptyList())

        data class PreparedBlock(
            val block: FilteredOcrBlock,
            val normKey: String,
            val sourceCode: String,
            val fastCacheKey: String,
            val resolvedCacheKey: String,
            val cachedTranslation: String?
        )

        val prepared = ArrayList<PreparedBlock>(filtered.size)
        var langId: LanguageIdentifier? = null
        val skipFlag = if (skipTargetLanguage) "1" else "0"

        for (block in filtered) {
            val normKey = StructuredNodeTextExtractor.normalizeKey(block.text)
            if (normKey.isEmpty()) continue

            val fastKey = "off|$safeSource->$safeTarget|$skipFlag|${block.text}"
            val fastHit = synchronized(translationCache) { translationCache.get(fastKey) }
            if (fastHit != null) {
                if (fastHit == CACHE_SENTINEL_SKIP) continue
                prepared.add(
                    PreparedBlock(
                        block = block,
                        normKey = normKey,
                        sourceCode = safeSource,
                        fastCacheKey = fastKey,
                        resolvedCacheKey = fastKey,
                        cachedTranslation = fastHit
                    )
                )
                continue
            }

            if (langId == null && (safeSource == TranslateLanguageCatalog.AUTO_CODE || skipTargetLanguage)) {
                langId = getOrCreateLanguageIdentifier()
            }

            var resolvedSource = if (safeSource == TranslateLanguageCatalog.AUTO_CODE) {
                detectBlockSourceLang(langId, block.text, safeTarget, skipTargetLanguage)
            } else {
                if (skipTargetLanguage && langId != null) {
                    val top = identifyTopLanguage(langId, block.text)
                    if (TranslationBlockFilter.shouldSkipByDetectedLanguage(top.first, top.second, safeTarget)) {
                        null
                    } else {
                        safeSource
                    }
                } else {
                    safeSource
                }
            }

            if (resolvedSource == null) {
                synchronized(translationCache) { translationCache.put(fastKey, CACHE_SENTINEL_SKIP) }
                continue
            }

            resolvedSource = TranslateLanguageCatalog.normalizeCode(resolvedSource)
            if (resolvedSource == safeTarget) {
                synchronized(translationCache) { translationCache.put(fastKey, CACHE_SENTINEL_SKIP) }
                continue
            }
            if (toMlKitLang(resolvedSource) == null) continue

            val resolvedKey = "off|$resolvedSource->$safeTarget|${block.text}"
            val cached = synchronized(translationCache) { translationCache.get(resolvedKey) }
            if (cached != null) {
                synchronized(translationCache) { translationCache.put(fastKey, cached) }
            }
            prepared.add(
                PreparedBlock(
                    block = block,
                    normKey = normKey,
                    sourceCode = resolvedSource,
                    fastCacheKey = fastKey,
                    resolvedCacheKey = resolvedKey,
                    cachedTranslation = cached
                )
            )
        }

        if (prepared.isEmpty()) {
            return PipelineResult(emptyList())
        }

        val neededSources = LinkedHashSet<String>()
        for (item in prepared) {
            if (item.cachedTranslation == null) {
                neededSources.add(item.sourceCode)
            }
        }

        var notice: PipelineStatusNotice? = null
        if (neededSources.isNotEmpty()) {
            val missingNotice = ensureModelsReady(
                sourceCodes = neededSources,
                targetCode = safeTarget,
                wifiOnly = wifiOnlyDownload
            )
            if (missingNotice != null) {
                notice = missingNotice
                val cachedOnly = prepared.mapNotNull { item ->
                    item.cachedTranslation?.let { tr ->
                        TranslatedBlock(
                            screenBounds = item.block.bounds,
                            originalText = item.block.text,
                            translatedText = tr,
                            lineCount = item.block.lineCount,
                            averageLineHeightPx = item.block.averageLineHeightPx,
                            normalizedText = item.normKey
                        )
                    }
                }
                return PipelineResult(cachedOnly, notice)
            }
        }

        val translatedList = coroutineScope {
            prepared.map { item ->
                async {
                    val text = item.cachedTranslation ?: run {
                        val srcMl = toMlKitLang(item.sourceCode) ?: return@async null
                        val translator = getOrCreateTranslator(srcMl, mlTarget)
                        val out = runCatching { translator.translate(item.block.text).await() }
                            .getOrNull()
                            ?.trim()
                            .orEmpty()
                        if (out.isEmpty()) return@async null
                        synchronized(translationCache) {
                            translationCache.put(item.resolvedCacheKey, out)
                            translationCache.put(item.fastCacheKey, out)
                        }
                        out
                    }
                    TranslatedBlock(
                        screenBounds = item.block.bounds,
                        originalText = item.block.text,
                        translatedText = text,
                        lineCount = item.block.lineCount,
                        averageLineHeightPx = item.block.averageLineHeightPx,
                        normalizedText = item.normKey
                    )
                }
            }.awaitAll().filterNotNull()
        }

        return PipelineResult(translatedList, notice)
    }

    private suspend fun detectBlockSourceLang(
        langId: LanguageIdentifier?,
        text: String,
        targetLang: String,
        skipTargetLanguage: Boolean
    ): String? {
        if (langId == null) return "en"
        val (code, confidence) = identifyTopLanguage(langId, text)
        if (skipTargetLanguage &&
            TranslationBlockFilter.shouldSkipByDetectedLanguage(code, confidence, targetLang)
        ) {
            return null
        }
        if (code != null && code != "und" && confidence >= 0.45f) {
            val norm = TranslateLanguageCatalog.normalizeCode(code)
            if (toMlKitLang(norm) != null) return norm
        }
        return guessScriptLanguage(text, targetLang)
    }

    private suspend fun identifyTopLanguage(
        langId: LanguageIdentifier,
        text: String
    ): Pair<String?, Float> {
        val list = runCatching { langId.identifyPossibleLanguages(text).await() }.getOrNull()
        val first = list?.firstOrNull() ?: return null to 0f
        return first.languageTag to first.confidence
    }

    private fun guessScriptLanguage(text: String, targetLang: String): String {
        var kana = 0
        var han = 0
        var hangul = 0
        var devanagari = 0
        for (ch in text) {
            val c = ch.code
            when {
                c in 0x3040..0x30FF -> kana++
                c in 0x4E00..0x9FFF -> han++
                c in 0xAC00..0xD7AF -> hangul++
                c in 0x0900..0x097F -> devanagari++
            }
        }
        return when {
            kana > 0 -> "ja"
            hangul > 0 -> "ko"
            devanagari > 0 -> "hi"
            han > 0 -> if (targetLang == "ja") "zh" else "ja"
            else -> "en"
        }
    }

    private suspend fun ensureModelsReady(
        sourceCodes: Set<String>,
        targetCode: String,
        wifiOnly: Boolean
    ): PipelineStatusNotice? = modelDownloadMutex.withLock {
        if (!MlKitSafeInit.ensureInitialized(context)) {
            return PipelineStatusNotice.MODEL_DOWNLOAD_FAILED
        }
        val manager = runCatching { RemoteModelManager.getInstance() }.getOrNull()
            ?: return PipelineStatusNotice.MODEL_DOWNLOAD_FAILED
        val downloadedCodes = runCatching {
            manager.getDownloadedModels(TranslateRemoteModel::class.java)
                .await()
                .mapTo(HashSet()) { it.language }
        }.getOrDefault(emptySet())
        readyModelCodes.addAll(downloadedCodes)

        val requiredMlCodes = LinkedHashSet<String>()
        toMlKitLang(targetCode)?.let { requiredMlCodes.add(it) }
        for (src in sourceCodes) {
            toMlKitLang(src)?.let { requiredMlCodes.add(it) }
        }

        val missing = requiredMlCodes.filter { !readyModelCodes.contains(it) }
        if (missing.isEmpty()) return null

        if (wifiOnly && !isOnWifi()) {
            onStatusNotice(PipelineStatusNotice.WIFI_REQUIRED_FOR_MODEL)
            return PipelineStatusNotice.WIFI_REQUIRED_FOR_MODEL
        }

        onStatusNotice(PipelineStatusNotice.DOWNLOADING_MODEL)
        val conditionsBuilder = DownloadConditions.Builder()
        if (wifiOnly) {
            conditionsBuilder.requireWifi()
        }
        val conditions = conditionsBuilder.build()

        for (mlCode in missing) {
            val model = TranslateRemoteModel.Builder(mlCode).build()
            val ok = runCatching {
                manager.download(model, conditions).await()
                true
            }.getOrDefault(false)
            if (ok) {
                readyModelCodes.add(mlCode)
            } else {
                val notice = if (wifiOnly && !isOnWifi()) {
                    PipelineStatusNotice.WIFI_REQUIRED_FOR_MODEL
                } else {
                    PipelineStatusNotice.MODEL_DOWNLOAD_FAILED
                }
                onStatusNotice(notice)
                return notice
            }
        }
        return null
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun getOrCreateRecognizer(script: OcrScript): TextRecognizer {
        return recognizers.getOrPut(script) {
            when (script) {
                OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            }
        }
    }

    private fun getOrCreateLanguageIdentifier(): LanguageIdentifier {
        val existing = languageIdentifier
        if (existing != null) return existing
        return LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.45f)
                .build()
        ).also { languageIdentifier = it }
    }

    private fun getOrCreateTranslator(srcMl: String, tgtMl: String): Translator {
        val key = "$srcMl->$tgtMl"
        return translators.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(srcMl)
                .setTargetLanguage(tgtMl)
                .build()
            Translation.getClient(options)
        }
    }

    private fun toMlKitLang(code: String): String? {
        val norm = TranslateLanguageCatalog.normalizeCode(code)
        return TranslateLanguage.fromLanguageTag(norm)
    }

    fun clearCache() {
        synchronized(translationCache) {
            translationCache.evictAll()
        }
    }

    fun release() {
        for (recognizer in recognizers.values) {
            runCatching { recognizer.close() }
        }
        recognizers.clear()

        for (translator in translators.values) {
            runCatching { translator.close() }
        }
        translators.clear()

        runCatching { languageIdentifier?.close() }
        languageIdentifier = null
        readyModelCodes.clear()
        clearCache()
    }

    companion object {
        private const val LRU_CACHE_SIZE = 1024
        private const val AUTO_ASIAN_FALLBACK_MIN_LETTERS = 6
        private const val OVERLAY_OVERLAP_EXCLUSION_THRESHOLD = 0.40f
        private const val MIN_BAND_CROP_HEIGHT_PX = 48
        private const val MAX_BAND_CROP_HEIGHT_RATIO = 0.72f
        private const val CACHE_SENTINEL_SKIP = "\u0000__SKIP__"
    }
}
