package com.edgeflow.data

/**
 * On-device ML Kit Text Recognition v2 script buckets.
 * Every language mapped to an [OcrScript] can be read cleanly offline via its dedicated
 * ML Kit Text Recognition v2 recognizer.
 */
enum class OcrScript {
    LATIN,
    JAPANESE,
    CHINESE,
    KOREAN,
    DEVANAGARI
}

data class TranslateLanguageEntry(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val ocrScript: OcrScript?,
    val pinned: Boolean = false
) {
    val isOcrReadable: Boolean get() = ocrScript != null
}

/**
 * Pure-Kotlin single source of truth for all 59 ML Kit on-device translation languages
 * (zero Android / Compose dependencies so it works seamlessly in resident service,
 * `:settings` process, and JVM unit tests).
 *
 * - **Source languages (`sourceLanguages`)**: 42 OCR-readable languages covered by ML Kit
 *   Text Recognition v2 (Latin, Japanese, Chinese, Korean, Devanagari), preventing users
 *   from picking an unreadable script as source.
 * - **Target languages (`allLanguages`)**: All 59 ML Kit Translate languages.
 * - **Pinned Top 8**: `ja`, `en`, `zh`, `ko`, `hi`, `es`, `fr`, `de`.
 */
object TranslateLanguageCatalog {

    const val AUTO_CODE = "auto"

    val allLanguages: List<TranslateLanguageEntry> = listOf(
        // Top 8 Pinned (all OCR-readable)
        TranslateLanguageEntry("ja", "日本語", "Japanese", OcrScript.JAPANESE, pinned = true),
        TranslateLanguageEntry("en", "English", "English", OcrScript.LATIN, pinned = true),
        TranslateLanguageEntry("zh", "中文", "Chinese", OcrScript.CHINESE, pinned = true),
        TranslateLanguageEntry("ko", "한국어", "Korean", OcrScript.KOREAN, pinned = true),
        TranslateLanguageEntry("hi", "हिन्दी", "Hindi", OcrScript.DEVANAGARI, pinned = true),
        TranslateLanguageEntry("es", "Español", "Spanish", OcrScript.LATIN, pinned = true),
        TranslateLanguageEntry("fr", "Français", "French", OcrScript.LATIN, pinned = true),
        TranslateLanguageEntry("de", "Deutsch", "German", OcrScript.LATIN, pinned = true),

        // Additional OCR-readable languages (Latin & Devanagari, alphabetical by English name)
        TranslateLanguageEntry("af", "Afrikaans", "Afrikaans", OcrScript.LATIN),
        TranslateLanguageEntry("sq", "Shqip", "Albanian", OcrScript.LATIN),
        TranslateLanguageEntry("ca", "Català", "Catalan", OcrScript.LATIN),
        TranslateLanguageEntry("hr", "Hrvatski", "Croatian", OcrScript.LATIN),
        TranslateLanguageEntry("cs", "Čeština", "Czech", OcrScript.LATIN),
        TranslateLanguageEntry("da", "Dansk", "Danish", OcrScript.LATIN),
        TranslateLanguageEntry("nl", "Nederlands", "Dutch", OcrScript.LATIN),
        TranslateLanguageEntry("eo", "Esperanto", "Esperanto", OcrScript.LATIN),
        TranslateLanguageEntry("et", "Eesti", "Estonian", OcrScript.LATIN),
        TranslateLanguageEntry("fi", "Suomi", "Finnish", OcrScript.LATIN),
        TranslateLanguageEntry("gl", "Galego", "Galician", OcrScript.LATIN),
        TranslateLanguageEntry("ht", "Kreyòl ayisyen", "Haitian Creole", OcrScript.LATIN),
        TranslateLanguageEntry("hu", "Magyar", "Hungarian", OcrScript.LATIN),
        TranslateLanguageEntry("is", "Íslenska", "Icelandic", OcrScript.LATIN),
        TranslateLanguageEntry("id", "Bahasa Indonesia", "Indonesian", OcrScript.LATIN),
        TranslateLanguageEntry("ga", "Gaeilge", "Irish", OcrScript.LATIN),
        TranslateLanguageEntry("it", "Italiano", "Italian", OcrScript.LATIN),
        TranslateLanguageEntry("lv", "Latviešu", "Latvian", OcrScript.LATIN),
        TranslateLanguageEntry("lt", "Lietuvių", "Lithuanian", OcrScript.LATIN),
        TranslateLanguageEntry("ms", "Bahasa Melayu", "Malay", OcrScript.LATIN),
        TranslateLanguageEntry("mt", "Malti", "Maltese", OcrScript.LATIN),
        TranslateLanguageEntry("mr", "मराठी", "Marathi", OcrScript.DEVANAGARI),
        TranslateLanguageEntry("no", "Norsk", "Norwegian", OcrScript.LATIN),
        TranslateLanguageEntry("pl", "Polski", "Polish", OcrScript.LATIN),
        TranslateLanguageEntry("pt", "Português", "Portuguese", OcrScript.LATIN),
        TranslateLanguageEntry("ro", "Română", "Romanian", OcrScript.LATIN),
        TranslateLanguageEntry("sk", "Slovenčina", "Slovak", OcrScript.LATIN),
        TranslateLanguageEntry("sl", "Slovenščina", "Slovenian", OcrScript.LATIN),
        TranslateLanguageEntry("sw", "Kiswahili", "Swahili", OcrScript.LATIN),
        TranslateLanguageEntry("sv", "Svenska", "Swedish", OcrScript.LATIN),
        TranslateLanguageEntry("tl", "Tagalog", "Tagalog", OcrScript.LATIN),
        TranslateLanguageEntry("tr", "Türkçe", "Turkish", OcrScript.LATIN),
        TranslateLanguageEntry("vi", "Tiếng Việt", "Vietnamese", OcrScript.LATIN),
        TranslateLanguageEntry("cy", "Cymraeg", "Welsh", OcrScript.LATIN),

        // Target-only ML Kit Translate languages (non-Latin/CJK/Devanagari scripts; 17 languages)
        TranslateLanguageEntry("ar", "العربية", "Arabic", null),
        TranslateLanguageEntry("be", "Беларуская", "Belarusian", null),
        TranslateLanguageEntry("bn", "বাংলা", "Bengali", null),
        TranslateLanguageEntry("bg", "Български", "Bulgarian", null),
        TranslateLanguageEntry("ka", "ქართული", "Georgian", null),
        TranslateLanguageEntry("el", "Ελληνικά", "Greek", null),
        TranslateLanguageEntry("gu", "ગુજરાતી", "Gujarati", null),
        TranslateLanguageEntry("he", "עברית", "Hebrew", null),
        TranslateLanguageEntry("kn", "ಕನ್ನಡ", "Kannada", null),
        TranslateLanguageEntry("mk", "Македонски", "Macedonian", null),
        TranslateLanguageEntry("fa", "فارسی", "Persian", null),
        TranslateLanguageEntry("ru", "Русский", "Russian", null),
        TranslateLanguageEntry("ta", "தமிழ்", "Tamil", null),
        TranslateLanguageEntry("te", "తెలుగు", "Telugu", null),
        TranslateLanguageEntry("th", "ไทย", "Thai", null),
        TranslateLanguageEntry("uk", "Українська", "Ukrainian", null),
        TranslateLanguageEntry("ur", "اردو", "Urdu", null)
    )

    val sourceLanguages: List<TranslateLanguageEntry> = allLanguages.filter { it.isOcrReadable }

    val pinnedLanguages: List<TranslateLanguageEntry> = allLanguages.filter { it.pinned }

    private val byCode: Map<String, TranslateLanguageEntry> = allLanguages.associateBy { it.code }

    fun findByCode(code: String): TranslateLanguageEntry? {
        val normalized = normalizeCode(code)
        return byCode[normalized]
    }

    fun normalizeCode(raw: String): String {
        val cleaned = raw.trim().lowercase().substringBefore('-').substringBefore('_')
        return when (cleaned) {
            "iw" -> "he"
            "in" -> "id"
            "fil" -> "tl"
            "nb", "nn" -> "no"
            else -> cleaned
        }
    }

    fun sanitizeSourceCode(raw: String): String {
        val cleaned = raw.replace("\n", "").trim().lowercase()
        if (cleaned.isEmpty() || cleaned == AUTO_CODE) return AUTO_CODE
        val entry = findByCode(cleaned)
        return if (entry != null && entry.isOcrReadable) entry.code else AUTO_CODE
    }

    fun sanitizeTargetCode(raw: String): String {
        val cleaned = raw.replace("\n", "").trim().lowercase()
        val entry = findByCode(cleaned)
        return entry?.code ?: EdgeConfig.DEFAULT_TRANSLATE_TARGET_LANG
    }

    fun ocrScriptForSource(sourceCode: String, targetCode: String): OcrScript {
        if (sourceCode != AUTO_CODE) {
            return findByCode(sourceCode)?.ocrScript ?: OcrScript.LATIN
        }
        // In AUTO mode, default to fast high-accuracy Latin OCR so English/European pages never
        // suffer CJK misreads; Asian OCR is explicitly selected by sourceCode or automatic fallback.
        return OcrScript.LATIN
    }

    fun matchesQuery(entry: TranslateLanguageEntry, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return entry.code.lowercase().contains(q) ||
            entry.nativeName.lowercase().contains(q) ||
            entry.englishName.lowercase().contains(q)
    }

    /**
     * Computes a clean 1-tap swap pair for the floating control pill & settings screen.
     * If source is `"auto"`, swaps to `(target -> fallback)` if target is OCR-readable,
     * else keeps `"auto"` -> `"en"` / `"ja"`.
     */
    fun computeQuickSwap(sourceCode: String, targetCode: String): Pair<String, String> {
        val src = sanitizeSourceCode(sourceCode)
        val tgt = sanitizeTargetCode(targetCode)
        if (src == AUTO_CODE) {
            val targetEntry = findByCode(tgt)
            val newTarget = if (tgt == "en") "ja" else "en"
            val newSource = if (targetEntry != null && targetEntry.isOcrReadable) tgt else AUTO_CODE
            return newSource to newTarget
        }
        val targetEntry = findByCode(tgt)
        val newSource = if (targetEntry != null && targetEntry.isOcrReadable) tgt else AUTO_CODE
        return newSource to src
    }
}
