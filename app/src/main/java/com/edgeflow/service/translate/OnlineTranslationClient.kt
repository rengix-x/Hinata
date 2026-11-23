package com.edgeflow.service.translate

import android.os.SystemClock
import com.edgeflow.data.TranslateLanguageCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

/**
 * Opt-in high-quality text-only online batch translation client using Android's standard
 * [HttpsURLConnection] and a pure-Kotlin JSON parser (zero external dependencies, zero
 * `org.json.*` so pure-JVM unit tests run without mocking, zero Jetpack Compose).
 *
 * Privacy & Resilience Guarantees:
 * - **Text-only transmission**: Never transmits screenshots, images, or screen metadata.
 * - **Chunked 1-request batching**: Up to [MAX_CHUNK_BLOCKS] blocks / [MAX_CHUNK_CHARS] chars
 *   joined with `\n\n` per HTTPS POST request.
 * - **Mismatch-safe fallback**: If the translated paragraph count differs from the chunk input
 *   count, falls back to per-item requests guarded by a `Semaphore(4)` to prevent misaligned text.
 * - **30s rate-limit cooldown**: On HTTP 429/403 or 2 consecutive network errors, enters a 30s
 *   cooldown returning `null` immediately (0ms fallback to on-device ML Kit).
 */
class OnlineTranslationClient(
    private val uptimeClockMs: () -> Long = { SystemClock.uptimeMillis() }
) {
    @Volatile
    private var cooldownUntilUptimeMs: Long = 0L

    @Volatile
    private var consecutiveFailures: Int = 0

    fun isInCooldown(): Boolean = uptimeClockMs() < cooldownUntilUptimeMs

    suspend fun translateBatch(
        texts: List<String>,
        sourceLang: String,
        targetLang: String
    ): Map<Int, String>? = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyMap()
        if (isInCooldown()) return@withContext null

        val mappedSrc = mapLanguageCode(sourceLang, allowAuto = true)
        val mappedTgt = mapLanguageCode(targetLang, allowAuto = false)
        if (mappedTgt.isEmpty()) return@withContext null

        // Normalize every input text to single-line (replace newlines with spaces)
        val normalized = ArrayList<String>(texts.size)
        for (i in texts.indices) {
            normalized.add(normalizeSingleLine(texts[i]))
        }

        // Partition into chunks of <= MAX_CHUNK_BLOCKS and <= MAX_CHUNK_CHARS
        val chunks = ArrayList<List<Int>>(4)
        var currentChunk = ArrayList<Int>(MAX_CHUNK_BLOCKS)
        var currentChars = 0
        for (i in normalized.indices) {
            val itemLen = normalized[i].length
            val addedLen = if (currentChunk.isEmpty()) itemLen else itemLen + 2
            if (currentChunk.isNotEmpty() &&
                (currentChunk.size >= MAX_CHUNK_BLOCKS || currentChars + addedLen > MAX_CHUNK_CHARS)
            ) {
                chunks.add(currentChunk)
                currentChunk = ArrayList(MAX_CHUNK_BLOCKS)
                currentChars = 0
            }
            currentChunk.add(i)
            currentChars += if (currentChunk.size == 1) itemLen else itemLen + 2
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk)
        }

        val results = LinkedHashMap<Int, String>(texts.size)
        val singleFallbackSemaphore = Semaphore(SINGLE_FALLBACK_CONCURRENCY)

        for (chunkIndices in chunks) {
            if (isInCooldown()) break

            val chunkTexts = chunkIndices.map { normalized[it] }
            val joined = chunkTexts.joinToString(separator = "\n\n")
            val outcome = executeGtxPost(joined, mappedSrc, mappedTgt)

            when (outcome) {
                is HttpOutcome.RateLimited -> {
                    triggerCooldown()
                    break
                }
                is HttpOutcome.NetworkError -> {
                    recordNetworkFailure()
                    if (isInCooldown()) break
                }
                is HttpOutcome.Success -> {
                    consecutiveFailures = 0
                    val parsed = parseGtxJsonResponse(outcome.body)
                    val split = if (parsed != null) {
                        splitBatchTranslation(parsed, chunkTexts.size)
                    } else {
                        null
                    }

                    if (split != null) {
                        for (j in chunkIndices.indices) {
                            results[chunkIndices[j]] = split[j]
                        }
                    } else if (chunkIndices.size > 1) {
                        // Paragraph count mismatch -> safe per-item fallback under Semaphore(4)
                        val singleResults = coroutineScope {
                            chunkIndices.map { idx ->
                                async {
                                    singleFallbackSemaphore.withPermit {
                                        if (isInCooldown()) return@withPermit idx to null
                                        val singleOutcome = executeGtxPost(
                                            text = normalized[idx],
                                            sourceCode = mappedSrc,
                                            targetCode = mappedTgt
                                        )
                                        when (singleOutcome) {
                                            is HttpOutcome.RateLimited -> {
                                                triggerCooldown()
                                                idx to null
                                            }
                                            is HttpOutcome.NetworkError -> {
                                                recordNetworkFailure()
                                                idx to null
                                            }
                                            is HttpOutcome.Success -> {
                                                consecutiveFailures = 0
                                                val translated = parseGtxJsonResponse(singleOutcome.body)
                                                    ?.trim()
                                                    ?.takeIf { it.isNotEmpty() }
                                                idx to translated
                                            }
                                        }
                                    }
                                }
                            }.awaitAll()
                        }
                        for ((idx, tr) in singleResults) {
                            if (tr != null) {
                                results[idx] = tr
                            }
                        }
                    }
                }
            }
        }

        if (results.isEmpty()) null else results
    }

    private sealed class HttpOutcome {
        data class Success(val body: String) : HttpOutcome()
        data object RateLimited : HttpOutcome()
        data object NetworkError : HttpOutcome()
    }

    private fun executeGtxPost(
        text: String,
        sourceCode: String,
        targetCode: String
    ): HttpOutcome {
        var conn: HttpsURLConnection? = null
        return try {
            val encodedSl = URLEncoder.encode(sourceCode, "UTF-8")
            val encodedTl = URLEncoder.encode(targetCode, "UTF-8")
            val url = URL("$GTX_ENDPOINT?client=gtx&dt=t&sl=$encodedSl&tl=$encodedTl")
            conn = (url.openConnection() as? HttpsURLConnection) ?: return HttpOutcome.NetworkError
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.doInput = true
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")

            val payload = "q=${URLEncoder.encode(text, "UTF-8")}".toByteArray(StandardCharsets.UTF_8)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { out ->
                out.write(payload)
                out.flush()
            }

            val code = conn.responseCode
            when {
                code == 429 || code == 403 -> HttpOutcome.RateLimited
                code in 200..299 -> {
                    val body = conn.inputStream.use { stream ->
                        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).readText()
                    }
                    HttpOutcome.Success(body)
                }
                else -> HttpOutcome.NetworkError
            }
        } catch (_: Throwable) {
            HttpOutcome.NetworkError
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun triggerCooldown() {
        consecutiveFailures = MAX_CONSECUTIVE_FAILURES
        cooldownUntilUptimeMs = uptimeClockMs() + COOLDOWN_DURATION_MS
    }

    private fun recordNetworkFailure() {
        val count = consecutiveFailures + 1
        consecutiveFailures = count
        if (count >= MAX_CONSECUTIVE_FAILURES) {
            cooldownUntilUptimeMs = uptimeClockMs() + COOLDOWN_DURATION_MS
        }
    }

    companion object {
        private const val GTX_ENDPOINT = "https://translate.googleapis.com/translate_a/single"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15)"
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val READ_TIMEOUT_MS = 4000
        private const val COOLDOWN_DURATION_MS = 30_000L
        private const val MAX_CONSECUTIVE_FAILURES = 2
        private const val MAX_CHUNK_CHARS = 2200
        private const val MAX_CHUNK_BLOCKS = 18
        private const val SINGLE_FALLBACK_CONCURRENCY = 4

        private val SINGLE_LINE_WS_REGEX = Regex("\\s+")
        private val PARAGRAPH_SPLIT_REGEX = Regex("\\r?\\n\\s*\\r?\\n")

        internal fun mapLanguageCode(rawCode: String, allowAuto: Boolean): String {
            val trimmed = rawCode.trim().lowercase()
            if (allowAuto && (trimmed.isEmpty() || trimmed == TranslateLanguageCatalog.AUTO_CODE)) {
                return "auto"
            }
            val norm = TranslateLanguageCatalog.normalizeCode(trimmed)
            return when (norm) {
                "auto" -> if (allowAuto) "auto" else "en"
                "zh" -> "zh-CN"
                "he" -> "iw"
                else -> norm
            }
        }

        private fun normalizeSingleLine(text: String): String {
            return text.replace('\r', ' ').replace('\n', ' ').replace(SINGLE_LINE_WS_REGEX, " ").trim()
        }

        /**
         * Pure-Kotlin JSON state-machine parser for Google GTX responses of shape:
         * `[[["translated1","orig1",null,null,10],["translated2","orig2",...]],null,"en",...]`
         *
         * Walks element 0 of the root JSON array, unescapes element 0 (JSON string literal) of
         * each sub-array (`\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r`, `\t`, `\uXXXX`), and
         * concatenates them. Returns `null` on malformed/empty JSON.
         */
        internal fun parseGtxJsonResponse(rawJson: String): String? {
            if (rawJson.isBlank()) return null
            val cursor = JsonCursor(rawJson)
            cursor.skipWhitespace()
            if (!cursor.consumeChar('[')) return null
            cursor.skipWhitespace()
            if (!cursor.consumeChar('[')) return null

            val result = StringBuilder()
            cursor.skipWhitespace()
            if (cursor.peek() == ']') {
                return null
            }

            while (!cursor.eof()) {
                cursor.skipWhitespace()
                if (cursor.consumeChar(']')) break

                if (cursor.peek() == '[') {
                    cursor.consumeChar('[')
                    cursor.skipWhitespace()
                    if (cursor.peek() == '"') {
                        val segment = cursor.parseString() ?: return null
                        result.append(segment)
                    } else if (cursor.peek() != ']') {
                        if (!cursor.skipValue()) return null
                    }
                    // Skip remaining elements inside this segment sub-array until its closing ']'
                    if (!cursor.skipRestOfArray()) return null
                } else {
                    if (!cursor.skipValue()) return null
                }

                cursor.skipWhitespace()
                if (cursor.consumeChar(',')) {
                    continue
                } else if (cursor.peek() == ']') {
                    cursor.consumeChar(']')
                    break
                } else {
                    return null
                }
            }

            val out = result.toString()
            return if (out.isBlank()) null else out
        }

        /**
         * Splits a batch-translated string joined with `\n\n` back into individual segments.
         * Returns non-null ONLY when the split segment count exactly matches [expectedCount]
         * and every segment is non-blank (preventing cross-paragraph translation misalignment).
         */
        internal fun splitBatchTranslation(
            translatedJoined: String,
            expectedCount: Int
        ): List<String>? {
            if (expectedCount <= 0) return emptyList()
            val trimmed = translatedJoined.trim()
            if (trimmed.isEmpty()) return null
            if (expectedCount == 1) {
                val single = normalizeSingleLine(trimmed)
                return if (single.isEmpty()) null else listOf(single)
            }

            val rawParts = PARAGRAPH_SPLIT_REGEX.split(trimmed)
            if (rawParts.size != expectedCount) return null

            val cleaned = ArrayList<String>(expectedCount)
            for (part in rawParts) {
                val item = normalizeSingleLine(part)
                if (item.isEmpty()) return null
                cleaned.add(item)
            }
            return cleaned
        }

        private class JsonCursor(private val src: String) {
            private var pos = 0
            private val len = src.length

            fun eof(): Boolean = pos >= len

            fun peek(): Char = if (pos < len) src[pos] else '\u0000'

            fun skipWhitespace() {
                while (pos < len) {
                    val ch = src[pos]
                    if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                        pos++
                    } else {
                        break
                    }
                }
            }

            fun consumeChar(expected: Char): Boolean {
                if (pos < len && src[pos] == expected) {
                    pos++
                    return true
                }
                return false
            }

            fun parseString(): String? {
                if (!consumeChar('"')) return null
                val sb = StringBuilder()
                while (pos < len) {
                    val ch = src[pos++]
                    if (ch == '"') {
                        return sb.toString()
                    }
                    if (ch == '\\') {
                        if (pos >= len) return null
                        val esc = src[pos++]
                        when (esc) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > len) return null
                                var codePoint = 0
                                for (k in 0 until 4) {
                                    val hex = src[pos++]
                                    val digit = when (hex) {
                                        in '0'..'9' -> hex - '0'
                                        in 'a'..'f' -> hex - 'a' + 10
                                        in 'A'..'F' -> hex - 'A' + 10
                                        else -> return null
                                    }
                                    codePoint = (codePoint shl 4) or digit
                                }
                                sb.append(codePoint.toChar())
                            }
                            else -> return null
                        }
                    } else {
                        sb.append(ch)
                    }
                }
                return null
            }

            fun skipRestOfArray(): Boolean {
                while (!eof()) {
                    skipWhitespace()
                    if (consumeChar(']')) return true
                    if (consumeChar(',')) {
                        skipWhitespace()
                        if (!skipValue()) return false
                    } else {
                        return false
                    }
                }
                return false
            }

            fun skipValue(): Boolean {
                skipWhitespace()
                if (eof()) return false
                return when (peek()) {
                    '"' -> parseString() != null
                    '[' -> skipBracketed('[', ']')
                    '{' -> skipObject()
                    't' -> consumeLiteral("true")
                    'f' -> consumeLiteral("false")
                    'n' -> consumeLiteral("null")
                    '-', in '0'..'9' -> skipNumber()
                    else -> false
                }
            }

            private fun skipBracketed(open: Char, close: Char): Boolean {
                if (!consumeChar(open)) return false
                skipWhitespace()
                if (consumeChar(close)) return true
                while (!eof()) {
                    if (!skipValue()) return false
                    skipWhitespace()
                    if (consumeChar(',')) continue
                    if (consumeChar(close)) return true
                    return false
                }
                return false
            }

            private fun skipObject(): Boolean {
                if (!consumeChar('{')) return false
                skipWhitespace()
                if (consumeChar('}')) return true
                while (!eof()) {
                    skipWhitespace()
                    if (parseString() == null) return false
                    skipWhitespace()
                    if (!consumeChar(':')) return false
                    if (!skipValue()) return false
                    skipWhitespace()
                    if (consumeChar(',')) continue
                    if (consumeChar('}')) return true
                    return false
                }
                return false
            }

            private fun consumeLiteral(lit: String): Boolean {
                if (pos + lit.length > len) return false
                for (i in lit.indices) {
                    if (src[pos + i] != lit[i]) return false
                }
                pos += lit.length
                return true
            }

            private fun skipNumber(): Boolean {
                val start = pos
                if (pos < len && src[pos] == '-') pos++
                var hasDigit = false
                while (pos < len && src[pos] in '0'..'9') {
                    pos++
                    hasDigit = true
                }
                if (!hasDigit) return false
                if (pos < len && src[pos] == '.') {
                    pos++
                    var fracDigit = false
                    while (pos < len && src[pos] in '0'..'9') {
                        pos++
                        fracDigit = true
                    }
                    if (!fracDigit) return false
                }
                if (pos < len && (src[pos] == 'e' || src[pos] == 'E')) {
                    pos++
                    if (pos < len && (src[pos] == '+' || src[pos] == '-')) pos++
                    var expDigit = false
                    while (pos < len && src[pos] in '0'..'9') {
                        pos++
                        expDigit = true
                    }
                    if (!expDigit) return false
                }
                return pos > start
            }
        }
    }
}
