package com.edgeflow.service.translate

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin snapshot of an accessibility node so Tier 1 structured text extraction,
 * viewport clipping, parent/child deduplication, and security/region filtering are 100%
 * pure-JVM unit testable.
 */
data class StructuredTextNode(
    val text: String,
    val bounds: BlockBounds,
    val isVisibleToUser: Boolean = true,
    val isPassword: Boolean = false,
    val isEditable: Boolean = false,
    val isFocused: Boolean = false,
    val stableHint: String? = null,
    val children: List<StructuredTextNode> = emptyList(),
    val isScrollable: Boolean = false,
    val className: String = "",
    val contentDescription: String = ""
)

/**
 * Lightweight browser/WebView detection + content viewport clip bounds for hybrid OCR vs Tier-1 routing.
 */
data class ViewportAndModeInfo(
    val isBrowserOrWebView: Boolean,
    val contentClipTopPx: Int,
    val contentClipBottomPx: Int,
    val targetPackageName: String = "",
    val targetWindowId: Int = -1
)

/**
 * Cloned accessibility anchor node held by the scroll tracker thread for 120fps WebView/RecyclerView
 * median-delta tracking without re-traversing the entire view hierarchy every frame.
 */
data class TrackedAnchorNode(
    val node: AccessibilityNodeInfo,
    var lastTopPx: Int,
    var lastBottomPx: Int
)

/**
 * Tier 1 structured extraction output including main scroll-container viewport clip bounds
 * (preventing address-bar bleed-through) and spatially distributed anchor nodes.
 */
data class StructuredExtractionResult(
    val blocks: List<RawOcrBlock>,
    val contentClipTopPx: Int,
    val contentClipBottomPx: Int,
    val anchorNodes: List<TrackedAnchorNode>,
    val targetPackageName: String = "",
    val targetWindowId: Int = -1
)

/**
 * Pure-JVM mirror of [StructuredExtractionResult] (without Android [AccessibilityNodeInfo]).
 */
data class PureTreeExtractionResult(
    val blocks: List<RawOcrBlock>,
    val contentClipTopPx: Int,
    val contentClipBottomPx: Int
)

/**
 * Resolved frontmost foreground application window root for non-flicker capture & node tracking.
 */
data class TargetAppWindowRoot(
    val root: AccessibilityNodeInfo,
    val packageName: String,
    val windowId: Int
)

/**
 * Structured text block extracted directly from the target app's `AccessibilityNodeInfo` tree.
 */
data class SourceTextBlock(
    val rawText: String,
    val normalizedText: String,
    val bounds: Rect,
    val stableHint: String?
)

/**
 * Tier 1 Hybrid Screen Translate text extractor with:
 * - Main scrollable container viewport clipping (eliminates Chrome address-bar overlap).
 * - Off-viewport subtree pruning so deep DOMs (`MAX_DEPTH = 32`, `MAX_NODES = 900`) never miss
 *   visible headings/captions/paragraphs.
 * - Spatially distributed `TrackedAnchorNode` cloning (`MAX_ANCHOR_NODES = 12`) for 120fps
 *   Chrome/WebView & inertial fling scroll tracking.
 *
 * Pure Kotlin / Android framework only — ZERO Jetpack Compose imports.
 */
object StructuredNodeTextExtractor {

    const val MAX_DEPTH = 32
    const val MAX_NODES = 900
    const val MAX_ANCHOR_NODES = 12
    const val MIN_RICH_BLOCK_COUNT = 2
    const val MIN_RICH_TOTAL_CHARS = 16

    private const val SCROLL_CONTAINER_MIN_WIDTH_RATIO = 0.60f
    private const val SCROLL_CONTAINER_MIN_HEIGHT_RATIO = 0.35f
    private const val ANCHOR_EDGE_MARGIN_PX = 20
    private const val ANCHOR_MIN_HEIGHT_PX = 16
    private const val ANCHOR_MAX_HEIGHT_PX = 600

    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Normalizes text into a canonical spatial-anchor / cache key: collapses whitespace & lowercases.
     */
    fun normalizeKey(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return WHITESPACE_REGEX.replace(trimmed, " ").lowercase()
    }

    /**
     * Returns `true` when Tier 1 extracted enough real text to skip ML Kit OCR completely.
     */
    fun isRichEnoughToSkipOcr(blocks: List<RawOcrBlock>): Boolean {
        if (blocks.size >= MIN_RICH_BLOCK_COUNT) return true
        var totalChars = 0
        for (i in blocks.indices) {
            totalChars += blocks[i].text.length
            if (totalChars >= MIN_RICH_TOTAL_CHARS) return true
        }
        return false
    }

    fun isRichEnoughSourceBlocks(blocks: List<SourceTextBlock>): Boolean {
        if (blocks.size >= MIN_RICH_BLOCK_COUNT) return true
        var totalChars = 0
        for (i in blocks.indices) {
            totalChars += blocks[i].rawText.length
            if (totalChars >= MIN_RICH_TOTAL_CHARS) return true
        }
        return false
    }

    /**
     * Locates the frontmost foreground `TYPE_APPLICATION` window root, excluding Hinata itself,
     * SystemUI, and IME windows. Falls back to `service.rootInActiveWindow` only when `windows`
     * yields no valid target. Caller owns recycling [TargetAppWindowRoot.root].
     */
    fun findTargetAppRoot(service: AccessibilityService): TargetAppWindowRoot? {
        val selfPkg = service.packageName
        val windowList = runCatching { service.windows }.getOrNull()
        var bestRoot: AccessibilityNodeInfo? = null
        var bestPkg = ""
        var bestWindowId = -1
        var bestScore = Long.MIN_VALUE
        val winBounds = Rect()

        if (windowList != null) {
            for (i in 0 until windowList.size) {
                val win = windowList[i]
                if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = runCatching { win.root }.getOrNull() ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (isExcludedSystemOrSelfPackage(pkg, selfPkg)) {
                    @Suppress("DEPRECATION")
                    runCatching { root.recycle() }
                    continue
                }
                win.getBoundsInScreen(winBounds)
                val area = winBounds.width().coerceAtLeast(0).toLong() *
                    winBounds.height().coerceAtLeast(0).toLong()
                if (area <= 0L) {
                    @Suppress("DEPRECATION")
                    runCatching { root.recycle() }
                    continue
                }
                val activeBonus = if (win.isActive) 1_000_000L else 0L
                val score = activeBonus + win.layer.toLong() * 10_000L + (area / 10_000L)
                if (bestRoot == null || score > bestScore) {
                    @Suppress("DEPRECATION")
                    runCatching { bestRoot?.recycle() }
                    bestRoot = root
                    bestPkg = pkg
                    bestWindowId = win.id
                    bestScore = score
                } else {
                    @Suppress("DEPRECATION")
                    runCatching { root.recycle() }
                }
            }
        }
        if (bestRoot != null) {
            return TargetAppWindowRoot(
                root = bestRoot,
                packageName = bestPkg,
                windowId = bestWindowId
            )
        }

        val fallback = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        val pkg = fallback.packageName?.toString().orEmpty()
        if (isExcludedSystemOrSelfPackage(pkg, selfPkg)) {
            @Suppress("DEPRECATION")
            runCatching { fallback.recycle() }
            return null
        }
        return TargetAppWindowRoot(
            root = fallback,
            packageName = pkg,
            windowId = fallback.windowId
        )
    }

    /**
     * Lightweight pre-pass to determine whether the active foreground window is a browser /
     * WebView surface (preferring full-res clean OCR to avoid virtual-node coordinate drift)
     * and to locate its main scrollable viewport clip bounds.
     */
    fun detectContentViewportAndMode(
        service: AccessibilityService,
        screenWidthPx: Int,
        screenHeightPx: Int,
        statusBarGuardPx: Int,
        navBarGuardPx: Int
    ): ViewportAndModeInfo {
        val navBarTop = (screenHeightPx - navBarGuardPx).coerceAtLeast(statusBarGuardPx)
        val target = findTargetAppRoot(service) ?: return ViewportAndModeInfo(
            isBrowserOrWebView = false,
            contentClipTopPx = statusBarGuardPx,
            contentClipBottomPx = navBarTop
        )
        return try {
            var isBrowserOrWebView = isBrowserPackage(target.packageName)
            var contentClipTop = statusBarGuardPx
            var contentClipBottom = navBarTop
            var bestScrollArea = 0L

            val minScrollW = (screenWidthPx * SCROLL_CONTAINER_MIN_WIDTH_RATIO).toInt()
            val minScrollH = (screenHeightPx * SCROLL_CONTAINER_MIN_HEIGHT_RATIO).toInt()
            val minWebViewW = (screenWidthPx * 0.50f).toInt()
            val minWebViewH = (screenHeightPx * 0.35f).toInt()

            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>(48)
            stack.addLast(target.root to 0)
            var visited = 0
            val tempRect = Rect()

            while (stack.isNotEmpty() && visited < 220) {
                val (node, depth) = stack.removeLast()
                visited++
                val isRoot = (node === target.root)
                try {
                    if (!node.isVisibleToUser) continue
                    val cls = node.className?.toString().orEmpty()
                    node.getBoundsInScreen(tempRect)
                    val w = tempRect.width()
                    val h = tempRect.height()

                    if (!isBrowserOrWebView &&
                        cls.contains("WebView", ignoreCase = true) &&
                        w >= minWebViewW && h >= minWebViewH
                    ) {
                        isBrowserOrWebView = true
                    }

                    val isScroll = (node.isScrollable || isScrollableContainerClassName(cls)) &&
                        w >= minScrollW && h >= minScrollH
                    if (isScroll) {
                        val cL = tempRect.left.coerceIn(0, screenWidthPx)
                        val cT = tempRect.top.coerceIn(statusBarGuardPx, navBarTop)
                        val cR = tempRect.right.coerceIn(cL, screenWidthPx)
                        val cB = tempRect.bottom.coerceIn(cT, navBarTop)
                        val area = (cR - cL).toLong() * (cB - cT).toLong()
                        if (area > bestScrollArea) {
                            bestScrollArea = area
                            contentClipTop = cT
                            contentClipBottom = cB
                        }
                    }

                    val childCount = node.childCount
                    if (childCount > 0 && depth < 14 && visited < 220) {
                        for (i in childCount - 1 downTo 0) {
                            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                            stack.addLast(child to depth + 1)
                        }
                    }
                } finally {
                    if (!isRoot) {
                        @Suppress("DEPRECATION")
                        runCatching { node.recycle() }
                    }
                }
            }
            while (stack.isNotEmpty()) {
                val rem = stack.removeLast().first
                if (rem !== target.root) {
                    @Suppress("DEPRECATION")
                    runCatching { rem.recycle() }
                }
            }

            ViewportAndModeInfo(
                isBrowserOrWebView = isBrowserOrWebView,
                contentClipTopPx = contentClipTop,
                contentClipBottomPx = contentClipBottom,
                targetPackageName = target.packageName,
                targetWindowId = target.windowId
            )
        } catch (_: Throwable) {
            ViewportAndModeInfo(
                isBrowserOrWebView = isBrowserPackage(target.packageName),
                contentClipTopPx = statusBarGuardPx,
                contentClipBottomPx = navBarTop,
                targetPackageName = target.packageName,
                targetWindowId = target.windowId
            )
        } finally {
            @Suppress("DEPRECATION")
            runCatching { target.root.recycle() }
        }
    }

    /**
     * Primary Task-1 extraction entry point: extracts visible text blocks with main scroll-container
     * viewport clipping (excluding text hidden behind browser address bars) and collects up to
     * [MAX_ANCHOR_NODES] spatially distributed cloned [TrackedAnchorNode]s for 120fps scroll tracking.
     *
     * Caller owns recycling all returned [TrackedAnchorNode.node] instances via [recycleAnchors].
     */
    fun extractWithViewportAndAnchors(
        service: AccessibilityService,
        screenWidthPx: Int,
        screenHeightPx: Int,
        statusBarGuardPx: Int,
        navBarGuardPx: Int,
        controlPillBounds: BlockBounds?
    ): StructuredExtractionResult {
        val defaultClipBottom = (screenHeightPx - navBarGuardPx).coerceAtLeast(statusBarGuardPx)
        val target = findTargetAppRoot(service) ?: return StructuredExtractionResult(
            blocks = emptyList(),
            contentClipTopPx = statusBarGuardPx,
            contentClipBottomPx = defaultClipBottom,
            anchorNodes = emptyList()
        )
        return try {
            extractRootWithViewportAndAnchors(
                root = target.root,
                targetPackageName = target.packageName,
                targetWindowId = target.windowId,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                statusBarGuardPx = statusBarGuardPx,
                navBarGuardPx = navBarGuardPx,
                controlPillBounds = controlPillBounds
            )
        } catch (_: Throwable) {
            StructuredExtractionResult(
                blocks = emptyList(),
                contentClipTopPx = statusBarGuardPx,
                contentClipBottomPx = defaultClipBottom,
                anchorNodes = emptyList(),
                targetPackageName = target.packageName,
                targetWindowId = target.windowId
            )
        } finally {
            @Suppress("DEPRECATION")
            runCatching { target.root.recycle() }
        }
    }

    private fun extractRootWithViewportAndAnchors(
        root: AccessibilityNodeInfo,
        targetPackageName: String,
        targetWindowId: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        statusBarGuardPx: Int,
        navBarGuardPx: Int,
        controlPillBounds: BlockBounds?
    ): StructuredExtractionResult {
        val navBarTop = (screenHeightPx - navBarGuardPx).coerceAtLeast(statusBarGuardPx)
        val minScrollW = (screenWidthPx * SCROLL_CONTAINER_MIN_WIDTH_RATIO).toInt()
        val minScrollH = (screenHeightPx * SCROLL_CONTAINER_MIN_HEIGHT_RATIO).toInt()

        class StackItem(
            val node: AccessibilityNodeInfo,
            val depth: Int,
            val parentIdx: Int,
            val clipLeft: Int,
            val clipTop: Int,
            val clipRight: Int,
            val clipBottom: Int,
            val insideScrollContainer: Boolean
        )

        class RawEntry(
            val parentIdx: Int,
            val rawText: String,
            val bounds: BlockBounds,
            val clipLeft: Int,
            val clipTop: Int,
            val clipRight: Int,
            val clipBottom: Int,
            val insideScrollContainer: Boolean,
            val childTexts: StringBuilder = StringBuilder()
        )

        val entries = ArrayList<RawEntry>(96)
        val stack = ArrayDeque<StackItem>(64)
        stack.addLast(
            StackItem(
                node = root,
                depth = 0,
                parentIdx = -1,
                clipLeft = 0,
                clipTop = statusBarGuardPx,
                clipRight = screenWidthPx,
                clipBottom = navBarTop,
                insideScrollContainer = false
            )
        )

        var bestScrollArea = 0L
        var contentClipTop = statusBarGuardPx
        var contentClipBottom = navBarTop
        val anchorBuckets = arrayOfNulls<TrackedAnchorNode>(MAX_ANCHOR_NODES)

        var visited = 0
        val tempRect = Rect()

        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val item = stack.removeLast()
            val node = item.node
            visited++
            val isRoot = (node === root)

            try {
                if (!node.isVisibleToUser || node.isPassword || node.isEditable) {
                    continue
                }

                node.getBoundsInScreen(tempRect)
                val b = BlockBounds(tempRect.left, tempRect.top, tempRect.right, tempRect.bottom)
                val childCount = node.childCount
                val className = node.className?.toString().orEmpty()
                val isScrollContainer = (node.isScrollable || isScrollableContainerClassName(className)) &&
                    b.width >= minScrollW && b.height >= minScrollH

                // Prune non-scrollable off-viewport subtrees early to preserve node budget
                if (item.insideScrollContainer && !isScrollContainer && childCount <= 4) {
                    if (b.bottom <= item.clipTop || b.top >= item.clipBottom ||
                        b.right <= item.clipLeft || b.left >= item.clipRight
                    ) {
                        continue
                    }
                }

                var childClipL = item.clipLeft
                var childClipT = item.clipTop
                var childClipR = item.clipRight
                var childClipB = item.clipBottom
                var childInsideScroll = item.insideScrollContainer

                if (isScrollContainer) {
                    val containerClipL = b.left.coerceIn(0, screenWidthPx)
                    val containerClipT = b.top.coerceIn(statusBarGuardPx, navBarTop)
                    val containerClipR = b.right.coerceIn(containerClipL, screenWidthPx)
                    val containerClipB = b.bottom.coerceIn(containerClipT, navBarTop)

                    childClipL = max(childClipL, containerClipL)
                    childClipT = max(childClipT, containerClipT)
                    childClipR = min(childClipR, containerClipR)
                    childClipB = min(childClipB, containerClipB)
                    childInsideScroll = true

                    val area = (containerClipR - containerClipL).toLong() *
                        (containerClipB - containerClipT).toLong()
                    if (area > bestScrollArea) {
                        bestScrollArea = area
                        contentClipTop = containerClipT
                        contentClipBottom = containerClipB
                    }
                }

                val rawNodeText = node.text?.toString().orEmpty().trim()
                val resolvedText = rawNodeText.ifEmpty {
                    if (childCount == 0 && !isNonTextImageOrWebViewClass(className)) {
                        node.contentDescription?.toString().orEmpty().trim()
                    } else {
                        ""
                    }
                }
                val cleaned = TranslationBlockFilter.normalizeText(resolvedText)

                // Collect spatially distributed anchor nodes inside the main scrollable container
                if (item.insideScrollContainer &&
                    cleaned.isNotEmpty() &&
                    b.top >= item.clipTop + ANCHOR_EDGE_MARGIN_PX &&
                    b.bottom <= item.clipBottom - ANCHOR_EDGE_MARGIN_PX &&
                    b.height in ANCHOR_MIN_HEIGHT_PX..ANCHOR_MAX_HEIGHT_PX
                ) {
                    val viewportH = (item.clipBottom - item.clipTop).coerceAtLeast(1)
                    val centerY = (b.top + b.bottom) / 2
                    val bucketIdx = (((centerY - item.clipTop).toLong() * MAX_ANCHOR_NODES) / viewportH)
                        .toInt()
                        .coerceIn(0, MAX_ANCHOR_NODES - 1)
                    if (anchorBuckets[bucketIdx] == null) {
                        val cloned = cloneNodeSafely(node)
                        if (cloned != null) {
                            anchorBuckets[bucketIdx] = TrackedAnchorNode(
                                node = cloned,
                                lastTopPx = b.top,
                                lastBottomPx = b.bottom
                            )
                        }
                    }
                }

                val entryIdx = entries.size
                entries.add(
                    RawEntry(
                        parentIdx = item.parentIdx,
                        rawText = cleaned,
                        bounds = b,
                        clipLeft = item.clipLeft,
                        clipTop = item.clipTop,
                        clipRight = item.clipRight,
                        clipBottom = item.clipBottom,
                        insideScrollContainer = item.insideScrollContainer
                    )
                )

                if (childCount > 0 && item.depth < MAX_DEPTH && visited < MAX_NODES) {
                    for (i in childCount - 1 downTo 0) {
                        val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                        stack.addLast(
                            StackItem(
                                node = child,
                                depth = item.depth + 1,
                                parentIdx = entryIdx,
                                clipLeft = childClipL,
                                clipTop = childClipT,
                                clipRight = childClipR,
                                clipBottom = childClipB,
                                insideScrollContainer = childInsideScroll
                            )
                        )
                    }
                }
            } finally {
                if (!isRoot) {
                    @Suppress("DEPRECATION")
                    runCatching { node.recycle() }
                }
            }
        }

        while (stack.isNotEmpty()) {
            val rem = stack.removeLast().node
            if (rem !== root) {
                @Suppress("DEPRECATION")
                runCatching { rem.recycle() }
            }
        }

        val anchors = ArrayList<TrackedAnchorNode>(MAX_ANCHOR_NODES)
        for (bucket in anchorBuckets) {
            if (bucket != null) anchors.add(bucket)
        }

        if (entries.isEmpty()) {
            return StructuredExtractionResult(
                blocks = emptyList(),
                contentClipTopPx = contentClipTop,
                contentClipBottomPx = contentClipBottom,
                anchorNodes = anchors,
                targetPackageName = targetPackageName,
                targetWindowId = targetWindowId
            )
        }

        val accepted = ArrayList<RawOcrBlock>(entries.size.coerceAtMost(96))
        for (i in entries.size - 1 downTo 0) {
            val entry = entries[i]
            val childCombined = entry.childTexts.toString()
            // Preserve short inline words ("a", "I") so Phase 1 inline merge in TranslationBlockFilter can join them
            val textValid = entry.rawText.isNotEmpty()
            val redundantParent = textValid &&
                childCombined.isNotEmpty() &&
                isRedundantContainerText(entry.rawText, childCombined)

            val contrib = when {
                textValid && !redundantParent -> {
                    if (childCombined.isEmpty()) entry.rawText else "${entry.rawText} $childCombined"
                }
                else -> childCombined
            }
            if (contrib.isNotEmpty() && entry.parentIdx >= 0) {
                val pSb = entries[entry.parentIdx].childTexts
                if (pSb.isNotEmpty()) pSb.append(' ')
                pSb.append(contrib)
            }

            if (!textValid || redundantParent) continue

            val clipped = clipAndValidateBounds(
                b = entry.bounds,
                clipLeft = entry.clipLeft,
                clipTop = entry.clipTop,
                clipRight = entry.clipRight,
                clipBottom = entry.clipBottom,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                statusBarGuardPx = statusBarGuardPx,
                navBarGuardPx = navBarGuardPx,
                navBarTop = navBarTop,
                controlPillBounds = controlPillBounds
            ) ?: continue

            accepted.add(RawOcrBlock(bounds = clipped, text = entry.rawText))
        }

        accepted.reverse()
        return StructuredExtractionResult(
            blocks = deduplicateBlocks(accepted),
            contentClipTopPx = contentClipTop,
            contentClipBottomPx = contentClipBottom,
            anchorNodes = anchors,
            targetPackageName = targetPackageName,
            targetWindowId = targetWindowId
        )
    }

    /**
     * Safely recycles a list of cloned [TrackedAnchorNode]s.
     */
    fun recycleAnchors(anchors: List<TrackedAnchorNode>) {
        for (i in anchors.indices) {
            @Suppress("DEPRECATION")
            runCatching { anchors[i].node.recycle() }
        }
    }

    private fun cloneNodeSafely(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                AccessibilityNodeInfo(node)
            } else {
                @Suppress("DEPRECATION")
                AccessibilityNodeInfo.obtain(node)
            }
        }.getOrNull()
    }

    /**
     * Full non-recursive extraction from [root]. Kept for backwards compatibility.
     */
    fun extractFull(
        root: AccessibilityNodeInfo,
        screenWidthPx: Int,
        screenHeightPx: Int,
        statusBarGuardPx: Int,
        navBarGuardPx: Int,
        controlPillBounds: BlockBounds?
    ): List<SourceTextBlock> {
        val result = extractRootWithViewportAndAnchors(
            root = root,
            targetPackageName = "",
            targetWindowId = -1,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            statusBarGuardPx = statusBarGuardPx,
            navBarGuardPx = navBarGuardPx,
            controlPillBounds = controlPillBounds
        )
        recycleAnchors(result.anchorNodes)
        return result.blocks.mapNotNull { raw ->
            if (TranslationBlockFilter.isNoiseText(raw.text)) return@mapNotNull null
            SourceTextBlock(
                rawText = raw.text,
                normalizedText = normalizeKey(raw.text),
                bounds = Rect(raw.bounds.left, raw.bounds.top, raw.bounds.right, raw.bounds.bottom),
                stableHint = null
            )
        }
    }

    /**
     * Legacy fast scroll-tracking bounds refresh pass.
     */
    fun refreshBounds(
        root: AccessibilityNodeInfo,
        wantedNormalizedKeys: Set<String>
    ): Map<String, MutableList<Rect>> {
        if (wantedNormalizedKeys.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<Rect>>(wantedNormalizedKeys.size * 2)
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>(64)
        stack.addLast(root to 0)
        var visited = 0
        val tempRect = Rect()

        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            visited++
            val isRoot = (node === root)
            try {
                if (!node.isVisibleToUser || node.isPassword || node.isEditable) {
                    continue
                }
                val rawNodeText = node.text?.toString()
                val childCount = node.childCount
                val clsName = node.className?.toString().orEmpty()
                val candidate = if (!rawNodeText.isNullOrBlank()) {
                    rawNodeText
                } else if (childCount == 0 && !isNonTextImageOrWebViewClass(clsName)) {
                    node.contentDescription?.toString()
                } else {
                    null
                }
                if (!candidate.isNullOrBlank()) {
                    val key = normalizeKey(TranslationBlockFilter.normalizeText(candidate))
                    if (key.isNotEmpty() && wantedNormalizedKeys.contains(key)) {
                        node.getBoundsInScreen(tempRect)
                        if (tempRect.width() > 0 && tempRect.height() > 0) {
                            val list = out.getOrPut(key) { ArrayList(2) }
                            list.add(Rect(tempRect))
                        }
                    }
                }
                if (childCount > 0 && depth < MAX_DEPTH && visited < MAX_NODES) {
                    for (i in childCount - 1 downTo 0) {
                        val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                        stack.addLast(child to depth + 1)
                    }
                }
            } finally {
                if (!isRoot) {
                    @Suppress("DEPRECATION")
                    runCatching { node.recycle() }
                }
            }
        }

        while (stack.isNotEmpty()) {
            val rem = stack.removeLast().first
            if (rem !== root) {
                @Suppress("DEPRECATION")
                runCatching { rem.recycle() }
            }
        }
        return out
    }

    /**
     * Convenience wrapper extracting [RawOcrBlock]s directly from [service].
     */
    fun extractFromService(
        service: AccessibilityService,
        screenWidthPx: Int,
        screenHeightPx: Int,
        statusBarGuardPx: Int,
        navBarGuardPx: Int,
        controlPillBounds: BlockBounds?
    ): List<RawOcrBlock> {
        val result = extractWithViewportAndAnchors(
            service = service,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            statusBarGuardPx = statusBarGuardPx,
            navBarGuardPx = navBarGuardPx,
            controlPillBounds = controlPillBounds
        )
        recycleAnchors(result.anchorNodes)
        return result.blocks
    }

    /**
     * Pure-JVM entry point for deterministic testing and extraction from a [StructuredTextNode] tree
     * (backwards-compatible wrapper returning blocks only).
     */
    fun extractFromTree(
        root: StructuredTextNode,
        screenWidthPx: Int,
        screenHeightPx: Int,
        statusBarGuardPx: Int,
        navBarGuardPx: Int,
        controlPillBounds: BlockBounds?
    ): List<RawOcrBlock> {
        return extractFromTreeWithViewport(
            root = root,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            statusBarGuardPx = statusBarGuardPx,
            navBarGuardPx = navBarGuardPx,
            controlPillBounds = controlPillBounds
        ).blocks
    }

    /**
     * Pure-JVM entry point that also exposes main scroll-container viewport clip bounds so
     * address-bar occlusion and off-viewport pruning are 100% unit testable.
     */
    fun extractFromTreeWithViewport(
        root: StructuredTextNode,
        screenWidthPx: Int,
        screenHeightPx: Int,
        statusBarGuardPx: Int,
        navBarGuardPx: Int,
        controlPillBounds: BlockBounds?
    ): PureTreeExtractionResult {
        val navBarTop = (screenHeightPx - navBarGuardPx).coerceAtLeast(statusBarGuardPx)
        val minScrollW = (screenWidthPx * SCROLL_CONTAINER_MIN_WIDTH_RATIO).toInt()
        val minScrollH = (screenHeightPx * SCROLL_CONTAINER_MIN_HEIGHT_RATIO).toInt()
        val out = ArrayList<RawOcrBlock>(48)
        val visited = IntArray(1)
        val clipState = intArrayOf(statusBarGuardPx, navBarTop)
        val bestScrollArea = LongArray(1)

        collectPureNode(
            node = root,
            depth = 0,
            visited = visited,
            clipLeft = 0,
            clipTop = statusBarGuardPx,
            clipRight = screenWidthPx,
            clipBottom = navBarTop,
            insideScrollContainer = false,
            minScrollW = minScrollW,
            minScrollH = minScrollH,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            statusBarGuardPx = statusBarGuardPx,
            navBarGuardPx = navBarGuardPx,
            navBarTop = navBarTop,
            controlPillBounds = controlPillBounds,
            clipState = clipState,
            bestScrollArea = bestScrollArea,
            out = out
        )

        return PureTreeExtractionResult(
            blocks = deduplicateBlocks(out),
            contentClipTopPx = clipState[0],
            contentClipBottomPx = clipState[1]
        )
    }

    private fun collectPureNode(
        node: StructuredTextNode,
        depth: Int,
        visited: IntArray,
        clipLeft: Int,
        clipTop: Int,
        clipRight: Int,
        clipBottom: Int,
        insideScrollContainer: Boolean,
        minScrollW: Int,
        minScrollH: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        statusBarGuardPx: Int,
        navBarGuardPx: Int,
        navBarTop: Int,
        controlPillBounds: BlockBounds?,
        clipState: IntArray,
        bestScrollArea: LongArray,
        out: MutableList<RawOcrBlock>
    ): String {
        if (depth > MAX_DEPTH || visited[0] >= MAX_NODES) return ""
        visited[0]++

        if (!node.isVisibleToUser || node.isPassword || node.isEditable) return ""

        val b = node.bounds
        val isScrollContainer = (node.isScrollable || isScrollableContainerClassName(node.className)) &&
            b.width >= minScrollW && b.height >= minScrollH

        if (insideScrollContainer && !isScrollContainer && node.children.size <= 4) {
            if (b.bottom <= clipTop || b.top >= clipBottom ||
                b.right <= clipLeft || b.left >= clipRight
            ) {
                return ""
            }
        }

        var childClipL = clipLeft
        var childClipT = clipTop
        var childClipR = clipRight
        var childClipB = clipBottom
        var childInsideScroll = insideScrollContainer

        if (isScrollContainer) {
            val containerClipL = b.left.coerceIn(0, screenWidthPx)
            val containerClipT = b.top.coerceIn(statusBarGuardPx, navBarTop)
            val containerClipR = b.right.coerceIn(containerClipL, screenWidthPx)
            val containerClipB = b.bottom.coerceIn(containerClipT, navBarTop)

            childClipL = max(childClipL, containerClipL)
            childClipT = max(childClipT, containerClipT)
            childClipR = min(childClipR, containerClipR)
            childClipB = min(childClipB, containerClipB)
            childInsideScroll = true

            val area = (containerClipR - containerClipL).toLong() *
                (containerClipB - containerClipT).toLong()
            if (area > bestScrollArea[0]) {
                bestScrollArea[0] = area
                clipState[0] = containerClipT
                clipState[1] = containerClipB
            }
        }

        val childTexts = if (node.children.isNotEmpty() && depth < MAX_DEPTH) {
            val sb = StringBuilder()
            for (child in node.children) {
                if (visited[0] >= MAX_NODES) break
                val sub = collectPureNode(
                    node = child,
                    depth = depth + 1,
                    visited = visited,
                    clipLeft = childClipL,
                    clipTop = childClipT,
                    clipRight = childClipR,
                    clipBottom = childClipB,
                    insideScrollContainer = childInsideScroll,
                    minScrollW = minScrollW,
                    minScrollH = minScrollH,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    statusBarGuardPx = statusBarGuardPx,
                    navBarGuardPx = navBarGuardPx,
                    navBarTop = navBarTop,
                    controlPillBounds = controlPillBounds,
                    clipState = clipState,
                    bestScrollArea = bestScrollArea,
                    out = out
                )
                if (sub.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(sub)
                }
            }
            sb.toString()
        } else {
            ""
        }

        val resolvedPureText = node.text.trim().ifEmpty {
            if (node.children.isEmpty() && !isNonTextImageOrWebViewClass(node.className)) {
                node.contentDescription.trim()
            } else {
                ""
            }
        }
        val cleaned = TranslationBlockFilter.normalizeText(resolvedPureText)
        if (cleaned.isEmpty() || TranslationBlockFilter.isNoiseText(cleaned)) {
            return childTexts
        }

        if (childTexts.isNotEmpty() && isRedundantContainerText(cleaned, childTexts)) {
            return childTexts
        }

        val clipped = clipAndValidateBounds(
            b = b,
            clipLeft = clipLeft,
            clipTop = clipTop,
            clipRight = clipRight,
            clipBottom = clipBottom,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            statusBarGuardPx = statusBarGuardPx,
            navBarGuardPx = navBarGuardPx,
            navBarTop = navBarTop,
            controlPillBounds = controlPillBounds
        ) ?: return childTexts

        out.add(RawOcrBlock(bounds = clipped, text = cleaned))
        return if (childTexts.isEmpty()) cleaned else "$cleaned $childTexts"
    }

    private fun clipAndValidateBounds(
        b: BlockBounds,
        clipLeft: Int,
        clipTop: Int,
        clipRight: Int,
        clipBottom: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        statusBarGuardPx: Int,
        navBarGuardPx: Int,
        navBarTop: Int,
        controlPillBounds: BlockBounds?
    ): BlockBounds? {
        if (b.width <= 4 || b.height <= 4) return null
        if (b.right <= clipLeft || b.left >= clipRight) return null
        if (b.bottom <= clipTop || b.top >= clipBottom) return null

        // Drop full-screen root wrappers
        if (b.width >= (screenWidthPx * 0.98f).toInt() &&
            b.height >= (screenHeightPx * 0.85f).toInt()
        ) {
            return null
        }

        // Drop nodes whose vertical midpoint is tucked behind the address bar / clip top
        val centerY = (b.top + b.bottom) / 2
        if (centerY < clipTop) return null
        if (statusBarGuardPx > 0 && b.top < statusBarGuardPx && centerY < statusBarGuardPx) {
            return null
        }
        if (navBarGuardPx > 0 && b.top >= navBarTop) return null

        val clipped = BlockBounds(
            left = b.left.coerceIn(clipLeft, clipRight),
            top = b.top.coerceIn(clipTop, clipBottom),
            right = b.right.coerceIn(clipLeft, clipRight),
            bottom = b.bottom.coerceIn(clipTop, clipBottom)
        )
        if (clipped.width <= 4 || clipped.height <= 4) return null
        if (controlPillBounds != null && clipped.intersects(controlPillBounds)) return null
        return clipped
    }

    private fun isScrollableContainerClassName(className: String): Boolean {
        if (className.isEmpty()) return false
        return className.contains("WebView", ignoreCase = true) ||
            className.contains("RecyclerView", ignoreCase = true) ||
            className.contains("NestedScrollView", ignoreCase = true) ||
            className.contains("ScrollView", ignoreCase = true) ||
            className.contains("ListView", ignoreCase = true)
    }

    private fun isRedundantContainerText(parentText: String, combinedChildrenText: String): Boolean {
        if (parentText.equals(combinedChildrenText, ignoreCase = true)) return true
        val normParent = stripSpacesLower(parentText)
        val normChildren = stripSpacesLower(combinedChildrenText)
        if (normParent.isEmpty() || normChildren.isEmpty()) return false
        if (normParent == normChildren) return true
        if (normChildren.contains(normParent)) return true
        if (normParent.contains(normChildren) && normParent.length <= normChildren.length + 6) {
            return true
        }
        return false
    }

    private fun stripSpacesLower(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (!ch.isWhitespace()) sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    private fun deduplicateBlocks(raw: List<RawOcrBlock>): List<RawOcrBlock> {
        if (raw.size <= 1) return raw
        val result = ArrayList<RawOcrBlock>(raw.size)
        for (candidate in raw) {
            var isDup = false
            for (existing in result) {
                if (existing.text.equals(candidate.text, ignoreCase = true) &&
                    abs(existing.bounds.left - candidate.bounds.left) <= 12 &&
                    abs(existing.bounds.top - candidate.bounds.top) <= 12 &&
                    abs(existing.bounds.right - candidate.bounds.right) <= 12 &&
                    abs(existing.bounds.bottom - candidate.bounds.bottom) <= 12
                ) {
                    isDup = true
                    break
                }
            }
            if (!isDup) {
                result.add(candidate)
            }
        }
        return result
    }

    /**
     * Returns `true` for image / WebView classes whose `contentDescription` (HTML `alt` text or
     * decorative icon accessibility label) must NEVER be painted over images as translated text.
     */
    fun isNonTextImageOrWebViewClass(className: String): Boolean {
        if (className.isEmpty()) return false
        return className.contains("ImageView", ignoreCase = true) ||
            className.contains("ImageButton", ignoreCase = true) ||
            className.contains("WebView", ignoreCase = true) ||
            className.equals("Image", ignoreCase = true) ||
            className.endsWith(".Image", ignoreCase = true)
    }

    /**
     * Returns `true` for known mobile web browsers whose accessibility trees exhibit virtual DOM
     * coordinate lag or missing sub-text nodes, making clean full-res OCR the superior path.
     */
    fun isBrowserPackage(packageName: String): Boolean {
        val pkg = packageName.trim().lowercase()
        if (pkg.isEmpty()) return false
        return pkg == "com.android.chrome" ||
            pkg.startsWith("com.chrome.") ||
            pkg == "org.mozilla.firefox" ||
            pkg.startsWith("org.mozilla.firefox_") ||
            pkg == "org.mozilla.focus" ||
            pkg == "com.microsoft.emmx" ||
            pkg.startsWith("com.microsoft.emmx.") ||
            pkg == "com.brave.browser" ||
            pkg.startsWith("com.brave.browser_") ||
            pkg == "com.opera.browser" ||
            pkg == "com.opera.mini.native" ||
            pkg == "com.sec.android.app.sbrowser" ||
            pkg == "com.kiwibrowser.browser" ||
            pkg == "com.vivaldi.browser" ||
            pkg == "com.android.browser" ||
            pkg == "com.mi.globalbrowser" ||
            pkg == "com.duckduckgo.mobile.android" ||
            pkg == "company.thebrowser.arc"
    }

    internal fun isExcludedSystemOrSelfPackage(pkg: String, selfPkg: String): Boolean {
        if (pkg.isEmpty()) return false
        if (pkg == selfPkg) return true
        if (pkg == "com.android.systemui" || pkg == "com.miui.systemui") return true
        if (pkg.contains("inputmethod", ignoreCase = true) || pkg.contains("keyboard", ignoreCase = true)) {
            return true
        }
        return false
    }
}
