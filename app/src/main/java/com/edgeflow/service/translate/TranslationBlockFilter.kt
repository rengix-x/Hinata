package com.edgeflow.service.translate

import com.edgeflow.data.TranslateLanguageCatalog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin screen-space integer bounding box (zero Android framework dependency
 * so [TranslationBlockFilter] is 100% pure-JVM testable).
 */
data class BlockBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    fun intersects(other: BlockBounds): Boolean {
        return left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }

    fun union(other: BlockBounds): BlockBounds {
        return BlockBounds(
            left = min(left, other.left),
            top = min(top, other.top),
            right = max(right, other.right),
            bottom = max(bottom, other.bottom)
        )
    }

    fun overlapRatioOfSelf(other: BlockBounds): Float {
        val selfArea = width.toLong() * height.toLong()
        if (selfArea <= 0L) return 0f
        val ix = (min(right, other.right) - max(left, other.left)).coerceAtLeast(0)
        val iy = (min(bottom, other.bottom) - max(top, other.top)).coerceAtLeast(0)
        val interArea = ix.toLong() * iy.toLong()
        return (interArea.toFloat() / selfArea.toFloat()).coerceIn(0f, 1f)
    }
}

data class RawOcrBlock(
    val bounds: BlockBounds,
    val text: String,
    val groupId: Int = -1
)

data class FilteredOcrBlock(
    val bounds: BlockBounds,
    val text: String,
    val lineCount: Int = 1,
    val averageLineHeightPx: Int = bounds.height
)

/**
 * Pure-Kotlin 2-stage block filter, inline link merger, paragraph merger, and target-language skipper.
 *
 * Pipeline:
 * 1. Region filtering (status bar, nav bar, control pill, screen bounds).
 * 2. **Phase 1 (`mergeInlineSameLineBlocks`)**: Horizontally merges fragmented inline nodes/links
 *    on the same line BEFORE final noise filtering so short words (`"a"`, `"I"`, `"in"`, `"radio"`)
 *    stay intact inside their sentences.
 * 3. **Phase 2 (`mergeParagraphLines`)**: Vertically clusters lines of the same paragraph with
 *    heading/bullet/sentence-end boundary guards and tracks `lineCount` & `averageLineHeightPx`.
 * 4. Final noise & target-language script filtering.
 */
object TranslationBlockFilter {

    const val LANGUAGE_ID_CONFIDENCE_THRESHOLD = 0.65f

    // Phase 1: Same-line horizontal inline merge thresholds
    private const val INLINE_MIN_V_OVERLAP_RATIO = 0.48f
    private const val INLINE_MAX_HEIGHT_RATIO = 1.70f
    private const val INLINE_MIN_GAP_X_MULTIPLIER = -0.45f
    private const val INLINE_MAX_GAP_X_MULTIPLIER = 1.10f
    private const val INLINE_CROSS_GROUP_MAX_GAP_X_MULT = 0.65f
    private const val INLINE_CROSS_GROUP_MAX_HEIGHT_RATIO = 1.25f

    // Phase 2: Vertical paragraph merge thresholds
    private const val PARAGRAPH_MAX_HEIGHT_RATIO = 1.38f
    private const val PARAGRAPH_MIN_GAP_Y_MULTIPLIER = -0.25f
    private const val PARAGRAPH_MAX_GAP_Y_MULTIPLIER = 1.15f
    private const val PARAGRAPH_CROSS_GROUP_MAX_GAP_Y_MULT = 0.70f
    private const val PARAGRAPH_CROSS_GROUP_ALIGN_TOL_MULT = 0.80f
    private const val PARAGRAPH_MIN_H_OVERLAP_RATIO = 0.35f
    private const val PARAGRAPH_LEFT_ALIGN_TOLERANCE_MULTIPLIER = 1.50f
    private const val PARAGRAPH_SHORT_PREV_LINE_RATIO = 0.62f
    private const val PARAGRAPH_BOUNDARY_GAP_MULTIPLIER = 0.45f

    private val URL_REGEX = Regex(
        "^((https?://|www\\.)\\S+|[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*\\.(com|net|org|jp|io|co|app|dev|cn|kr|in)(/\\S*)?)$",
        RegexOption.IGNORE_CASE
    )
    private val TIMESTAMP_OR_NUMERIC_REGEX = Regex(
        "^[+\\-]?[\\d,.:/%¥$€£₹₩°#()\\[\\]\\s]+$"
    )
    private val NUMBERED_BULLET_REGEX = Regex("^\\d+[.)]\\s+")
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun filterAndMerge(
        rawBlocks: List<RawOcrBlock>,
        screenWidthPx: Int,
        screenHeightPx: Int,
        statusBarGuardPx: Int,
        navBarGuardPx: Int,
        controlPillBounds: BlockBounds?,
        targetLang: String,
        skipTargetLanguage: Boolean
    ): List<FilteredOcrBlock> {
        if (rawBlocks.isEmpty()) return emptyList()

        val navBarTop = (screenHeightPx - navBarGuardPx).coerceAtLeast(0)
        val regionCandidates = ArrayList<RawOcrBlock>(rawBlocks.size)

        for (block in rawBlocks) {
            val b = block.bounds
            if (b.width <= 2 || b.height <= 2) continue
            if (statusBarGuardPx > 0 && (b.top < statusBarGuardPx && (b.top + b.bottom) / 2 < statusBarGuardPx)) {
                continue
            }
            if (navBarGuardPx > 0 && b.top >= navBarTop) continue
            if (controlPillBounds != null && b.intersects(controlPillBounds)) continue

            val cleaned = normalizeText(block.text)
            if (cleaned.isEmpty()) continue
            if (URL_REGEX.matches(cleaned)) continue

            val clamped = BlockBounds(
                left = b.left.coerceIn(0, screenWidthPx),
                top = b.top.coerceIn(0, screenHeightPx),
                right = b.right.coerceIn(0, screenWidthPx),
                bottom = b.bottom.coerceIn(0, screenHeightPx)
            )
            if (clamped.width <= 2 || clamped.height <= 2) continue
            regionCandidates.add(RawOcrBlock(clamped, cleaned, block.groupId))
        }

        if (regionCandidates.isEmpty()) return emptyList()

        // Phase 1: Merge same-line inline spans/links BEFORE final noise/target-language filtering
        val lineBlocks = mergeInlineSameLineBlocks(regionCandidates)
        if (lineBlocks.isEmpty()) return emptyList()

        // Phase 2: Merge consecutive lines belonging to the same paragraph
        val mergedParagraphs = mergeParagraphLines(lineBlocks)

        // Final filtering: drop standalone noise or already-target-language paragraphs
        val result = ArrayList<FilteredOcrBlock>(mergedParagraphs.size)
        for (paragraph in mergedParagraphs) {
            val text = paragraph.textBuilder.toString().trim()
            if (isNoiseText(text)) continue
            if (skipTargetLanguage && isAlreadyTargetLanguageByScript(text, targetLang)) continue
            val avgLineH = (paragraph.totalLineHeightPx / paragraph.lineCount.coerceAtLeast(1))
                .coerceAtLeast(1)
            result.add(
                FilteredOcrBlock(
                    bounds = paragraph.bounds,
                    text = text,
                    lineCount = paragraph.lineCount,
                    averageLineHeightPx = avgLineH
                )
            )
        }
        return result
    }

    /**
     * Phase 1: Groups vertically overlapping spans into visual lines, sorts each line left-to-right,
     * and joins horizontally adjacent fragments (such as Chrome/Wikipedia inline links and single
     * articles like `"A"` / `"I"`) into single clean [LineBlock]s.
     */
    private fun mergeInlineSameLineBlocks(candidates: List<RawOcrBlock>): List<LineBlock> {
        if (candidates.isEmpty()) return emptyList()

        // Group into visual rows first (tolerating minor vertical baseline jitter between inline links)
        val byCenterY = candidates.sortedWith(
            compareBy<RawOcrBlock> { (it.bounds.top + it.bounds.bottom) / 2 }.thenBy { it.bounds.left }
        )

        class VisualRow(
            var topSum: Int,
            var bottomSum: Int,
            var count: Int,
            val items: MutableList<RawOcrBlock>
        ) {
            val avgTop: Int get() = topSum / count
            val avgBottom: Int get() = bottomSum / count
            val avgHeight: Int get() = (avgBottom - avgTop).coerceAtLeast(1)

            fun add(item: RawOcrBlock) {
                topSum += item.bounds.top
                bottomSum += item.bounds.bottom
                count++
                items.add(item)
            }
        }

        val visualRows = ArrayList<VisualRow>(byCenterY.size)
        for (item in byCenterY) {
            var matchedRow: VisualRow? = null
            for (i in visualRows.size - 1 downTo 0) {
                val row = visualRows[i]
                val rowTop = row.avgTop
                val rowBottom = row.avgBottom
                val rowH = row.avgHeight
                val itemH = item.bounds.height.coerceAtLeast(1)
                val minH = min(rowH, itemH)
                val maxH = max(rowH, itemH)
                if (maxH.toFloat() > minH.toFloat() * INLINE_MAX_HEIGHT_RATIO) continue

                val vOverlap = min(rowBottom, item.bounds.bottom) - max(rowTop, item.bounds.top)
                if (vOverlap >= minH * INLINE_MIN_V_OVERLAP_RATIO) {
                    matchedRow = row
                    break
                }
            }
            if (matchedRow != null) {
                matchedRow.add(item)
            } else {
                visualRows.add(
                    VisualRow(
                        topSum = item.bounds.top,
                        bottomSum = item.bounds.bottom,
                        count = 1,
                        items = ArrayList<RawOcrBlock>(4).apply { add(item) }
                    )
                )
            }
        }

        val outputLines = ArrayList<LineBlock>(visualRows.size)

        for (row in visualRows) {
            row.items.sortBy { it.bounds.left }
            var currentBounds = row.items[0].bounds
            val currentText = StringBuilder(row.items[0].text)
            var currentGroupId = row.items[0].groupId
            var weightedHeightSum = row.items[0].bounds.height.toLong() * row.items[0].bounds.width.coerceAtLeast(1)
            var weightSum = row.items[0].bounds.width.coerceAtLeast(1).toLong()
            var lastSegmentBounds = row.items[0].bounds

            for (i in 1 until row.items.size) {
                val next = row.items[i]
                val prevH = lastSegmentBounds.height.coerceAtLeast(1)
                val nextH = next.bounds.height.coerceAtLeast(1)
                val minH = min(prevH, nextH)
                val maxH = max(prevH, nextH)
                val vOverlap = min(lastSegmentBounds.bottom, next.bounds.bottom) -
                    max(lastSegmentBounds.top, next.bounds.top)
                val gapX = next.bounds.left - lastSegmentBounds.right

                val crossGroup = currentGroupId >= 0 && next.groupId >= 0 && currentGroupId != next.groupId
                val maxAllowedGapX = if (crossGroup) {
                    minH * INLINE_CROSS_GROUP_MAX_GAP_X_MULT
                } else {
                    minH * INLINE_MAX_GAP_X_MULTIPLIER
                }
                val maxAllowedHeightRatio = if (crossGroup) {
                    INLINE_CROSS_GROUP_MAX_HEIGHT_RATIO
                } else {
                    INLINE_MAX_HEIGHT_RATIO
                }

                val canInlineMerge = vOverlap >= minH * INLINE_MIN_V_OVERLAP_RATIO &&
                    maxH.toFloat() <= minH.toFloat() * maxAllowedHeightRatio &&
                    gapX >= minH * INLINE_MIN_GAP_X_MULTIPLIER &&
                    gapX <= maxAllowedGapX

                if (canInlineMerge) {
                    appendInlineText(currentText, next.text)
                    currentBounds = currentBounds.union(next.bounds)
                    if (currentGroupId < 0 && next.groupId >= 0) {
                        currentGroupId = next.groupId
                    }
                    val w = next.bounds.width.coerceAtLeast(1).toLong()
                    weightedHeightSum += next.bounds.height.toLong() * w
                    weightSum += w
                    lastSegmentBounds = next.bounds
                } else {
                    val lineH = (weightedHeightSum / weightSum.coerceAtLeast(1L)).toInt()
                        .coerceAtLeast(1)
                    outputLines.add(LineBlock(currentBounds, currentText.toString().trim(), lineH, currentGroupId))

                    currentBounds = next.bounds
                    currentText.setLength(0)
                    currentText.append(next.text)
                    currentGroupId = next.groupId
                    val w = next.bounds.width.coerceAtLeast(1).toLong()
                    weightedHeightSum = next.bounds.height.toLong() * w
                    weightSum = w
                    lastSegmentBounds = next.bounds
                }
            }

            val lineH = (weightedHeightSum / weightSum.coerceAtLeast(1L)).toInt().coerceAtLeast(1)
            outputLines.add(LineBlock(currentBounds, currentText.toString().trim(), lineH, currentGroupId))
        }

        return outputLines
    }

    /**
     * Phase 2: Clusters vertically consecutive [LineBlock]s into natural paragraphs while respecting
     * heading font-size changes, bullet lists, paragraph-ending short lines, and UI button columns.
     */
    private fun mergeParagraphLines(lines: List<LineBlock>): List<MutableParagraphCluster> {
        val sorted = lines.sortedWith(compareBy<LineBlock> { it.bounds.top }.thenBy { it.bounds.left })
        val clusters = ArrayList<MutableParagraphCluster>(sorted.size)

        for (line in sorted) {
            if (line.text.isEmpty()) continue
            var targetCluster: MutableParagraphCluster? = null
            for (i in clusters.size - 1 downTo 0) {
                val cluster = clusters[i]
                if (canMergeIntoParagraph(cluster, line)) {
                    targetCluster = cluster
                    break
                }
            }
            if (targetCluster != null) {
                targetCluster.append(line)
            } else {
                clusters.add(
                    MutableParagraphCluster(
                        bounds = line.bounds,
                        lastLineBounds = line.bounds,
                        lastLineHeightPx = line.lineHeightPx,
                        lastLineText = line.text,
                        maxLineWidthPx = line.bounds.width,
                        lineCount = 1,
                        totalLineHeightPx = line.lineHeightPx,
                        groupId = line.groupId,
                        textBuilder = StringBuilder(line.text)
                    )
                )
            }
        }
        return clusters
    }

    private fun canMergeIntoParagraph(cluster: MutableParagraphCluster, curr: LineBlock): Boolean {
        val prevBounds = cluster.lastLineBounds
        val currBounds = curr.bounds

        val prevLineH = cluster.lastLineHeightPx.coerceAtLeast(1)
        val currLineH = curr.lineHeightPx.coerceAtLeast(1)
        val minH = min(prevLineH, currLineH)
        val maxH = max(prevLineH, currLineH)
        if (maxH.toFloat() > minH.toFloat() * PARAGRAPH_MAX_HEIGHT_RATIO) return false

        val verticalGap = currBounds.top - prevBounds.bottom
        if (verticalGap < minH * PARAGRAPH_MIN_GAP_Y_MULTIPLIER ||
            verticalGap > minH * PARAGRAPH_MAX_GAP_Y_MULTIPLIER
        ) {
            return false
        }

        // ML Kit textBlock group separation: distinct OCR groups stay separate unless tightly aligned
        val crossGroup = cluster.groupId >= 0 && curr.groupId >= 0 && cluster.groupId != curr.groupId
        if (crossGroup) {
            val leftDiff = abs(cluster.bounds.left - currBounds.left)
            val widthDiff = abs(cluster.bounds.width - currBounds.width)
            if (leftDiff > minH * PARAGRAPH_CROSS_GROUP_ALIGN_TOL_MULT ||
                widthDiff > minH * PARAGRAPH_CROSS_GROUP_ALIGN_TOL_MULT ||
                verticalGap > minH * PARAGRAPH_CROSS_GROUP_MAX_GAP_Y_MULT
            ) {
                return false
            }
        }

        // Bullet / numbered list boundary guard
        if (isBulletStart(curr.text)) return false

        // Paragraph-end boundary guard: short previous line ending in sentence/clause terminator
        if (cluster.maxLineWidthPx > 0 &&
            prevBounds.width.toFloat() < cluster.maxLineWidthPx.toFloat() * PARAGRAPH_SHORT_PREV_LINE_RATIO &&
            endsWithParagraphTerminator(cluster.lastLineText) &&
            verticalGap > minH * PARAGRAPH_BOUNDARY_GAP_MULTIPLIER
        ) {
            return false
        }

        // Horizontal compatibility: >= 35% overlap OR left-aligned with > 0 overlap
        val overlapLeft = max(cluster.bounds.left, currBounds.left)
        val overlapRight = min(cluster.bounds.right, currBounds.right)
        val overlapW = (overlapRight - overlapLeft).coerceAtLeast(0)
        val minW = min(cluster.bounds.width, currBounds.width).coerceAtLeast(1)
        val overlapRatio = overlapW.toFloat() / minW.toFloat()

        val leftAligned = abs(cluster.bounds.left - currBounds.left) <=
            minH * PARAGRAPH_LEFT_ALIGN_TOLERANCE_MULTIPLIER
        val horizontallyCompatible = overlapRatio >= PARAGRAPH_MIN_H_OVERLAP_RATIO ||
            (leftAligned && overlapW > 0)
        if (!horizontallyCompatible) return false

        // UI button column guard: keep very short labels separated when vertical gap is noticeable
        val bothVeryShort = cluster.textBuilder.length <= 10 &&
            curr.text.length <= 10 &&
            verticalGap > minH * PARAGRAPH_BOUNDARY_GAP_MULTIPLIER
        if (bothVeryShort) return false

        return true
    }

    private fun isBulletStart(text: String): Boolean {
        val t = text.trimStart()
        if (t.isEmpty()) return false
        val first = t[0]
        if (first == '•' || first == '・' || first == '▪' || first == '◦' ||
            first == '●' || first == '○'
        ) {
            return true
        }
        if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("— ")) {
            return true
        }
        return NUMBERED_BULLET_REGEX.containsMatchIn(t)
    }

    private fun endsWithParagraphTerminator(text: String): Boolean {
        val t = text.trimEnd()
        if (t.isEmpty()) return false
        val last = t[t.length - 1]
        return last == '.' || last == '!' || last == '?' ||
            last == '。' || last == ':' || last == '：'
    }

    private fun appendInlineText(sb: StringBuilder, nextText: String) {
        if (nextText.isEmpty()) return
        if (sb.isNotEmpty()) {
            val prevLast = sb[sb.length - 1]
            val nextFirst = nextText[0]
            val needsSpace = !isCjkChar(prevLast) &&
                !isCjkChar(nextFirst) &&
                !isNoLeadingSpacePunctuation(nextFirst) &&
                prevLast != '(' &&
                prevLast != '['
            if (needsSpace) {
                sb.append(' ')
            }
        }
        sb.append(nextText)
    }

    private fun isNoLeadingSpacePunctuation(ch: Char): Boolean {
        return ch == ',' || ch == '.' || ch == ';' || ch == ':' ||
            ch == '!' || ch == '?' || ch == ')' || ch == ']' || ch == '%'
    }

    fun normalizeText(raw: String): String {
        return raw.replace('\n', ' ').replace(WHITESPACE_REGEX, " ").trim()
    }

    fun isNoiseText(text: String): Boolean {
        if (text.isEmpty()) return true
        if (URL_REGEX.matches(text)) return true
        if (TIMESTAMP_OR_NUMERIC_REGEX.matches(text)) return true

        var letterCount = 0
        var cjkOrIndicLetterCount = 0
        for (ch in text) {
            if (Character.isLetter(ch)) {
                letterCount++
                if (isIdeographicOrSyllabic(ch)) {
                    cjkOrIndicLetterCount++
                }
            }
        }
        if (letterCount == 0) return true
        if (text.length == 1 && cjkOrIndicLetterCount == 0) return true
        return false
    }

    fun shouldSkipByDetectedLanguage(
        detectedLangCode: String?,
        confidence: Float,
        targetLang: String
    ): Boolean {
        if (detectedLangCode.isNullOrBlank() || detectedLangCode == "und") return false
        if (confidence < LANGUAGE_ID_CONFIDENCE_THRESHOLD) return false
        return TranslateLanguageCatalog.normalizeCode(detectedLangCode) ==
            TranslateLanguageCatalog.normalizeCode(targetLang)
    }

    fun isAlreadyTargetLanguageByScript(text: String, targetLang: String): Boolean {
        val target = TranslateLanguageCatalog.normalizeCode(targetLang)
        var totalLetters = 0
        var hiraganaKatakana = 0
        var han = 0
        var hangul = 0
        var devanagari = 0
        var arabic = 0
        var cyrillic = 0
        var thai = 0
        var greek = 0
        var hebrew = 0
        var latin = 0

        for (ch in text) {
            if (!Character.isLetter(ch)) continue
            totalLetters++
            val code = ch.code
            when {
                code in 0x3040..0x309F || code in 0x30A0..0x30FF || code in 0xFF66..0xFF9F -> hiraganaKatakana++
                code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF -> han++
                code in 0xAC00..0xD7AF || code in 0x1100..0x11FF || code in 0x3130..0x318F -> hangul++
                code in 0x0900..0x097F -> devanagari++
                code in 0x0600..0x06FF -> arabic++
                code in 0x0400..0x04FF -> cyrillic++
                code in 0x0E00..0x0E7F -> thai++
                code in 0x0370..0x03FF -> greek++
                code in 0x0590..0x05FF -> hebrew++
                code in 0x0041..0x024F -> latin++
            }
        }
        if (totalLetters == 0) return false

        return when (target) {
            "ja" -> {
                val jpChars = hiraganaKatakana + han
                hiraganaKatakana >= 1 &&
                    (jpChars.toFloat() / totalLetters) >= 0.45f &&
                    hiraganaKatakana.toFloat() / max(1, hiraganaKatakana + latin) >= 0.25f
            }
            "zh" -> hiraganaKatakana == 0 && hangul == 0 && (han.toFloat() / totalLetters) >= 0.55f
            "ko" -> (hangul.toFloat() / totalLetters) >= 0.45f
            "hi", "mr" -> (devanagari.toFloat() / totalLetters) >= 0.45f
            "ar", "fa", "ur" -> (arabic.toFloat() / totalLetters) >= 0.45f
            "ru", "uk", "be", "bg", "mk" -> (cyrillic.toFloat() / totalLetters) >= 0.45f
            "th" -> (thai.toFloat() / totalLetters) >= 0.45f
            "el" -> (greek.toFloat() / totalLetters) >= 0.45f
            "he" -> (hebrew.toFloat() / totalLetters) >= 0.45f
            else -> false
        }
    }

    private fun isIdeographicOrSyllabic(ch: Char): Boolean {
        val c = ch.code
        return c in 0x3040..0x30FF ||
            c in 0x4E00..0x9FFF ||
            c in 0x3400..0x4DBF ||
            c in 0xAC00..0xD7AF ||
            c in 0x0900..0x097F
    }

    private fun isCjkChar(ch: Char): Boolean {
        val c = ch.code
        return c in 0x3040..0x30FF || c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF
    }

    private data class LineBlock(
        val bounds: BlockBounds,
        val text: String,
        val lineHeightPx: Int,
        val groupId: Int = -1
    )

    private class MutableParagraphCluster(
        var bounds: BlockBounds,
        var lastLineBounds: BlockBounds,
        var lastLineHeightPx: Int,
        var lastLineText: String,
        var maxLineWidthPx: Int,
        var lineCount: Int,
        var totalLineHeightPx: Int,
        var groupId: Int,
        val textBuilder: StringBuilder
    ) {
        fun append(line: LineBlock) {
            appendInlineText(textBuilder, line.text)
            bounds = bounds.union(line.bounds)
            lastLineBounds = line.bounds
            lastLineHeightPx = line.lineHeightPx
            lastLineText = line.text
            if (line.bounds.width > maxLineWidthPx) {
                maxLineWidthPx = line.bounds.width
            }
            if (groupId < 0 && line.groupId >= 0) {
                groupId = line.groupId
            }
            lineCount++
            totalLineHeightPx += line.lineHeightPx
        }
    }
}
