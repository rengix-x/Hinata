package com.edgeflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeConfigTranslateTest {

    @Test
    fun translateLanguageCatalog_hasExact59TargetAnd42SourceAndTop8Pinned() {
        assertEquals(59, TranslateLanguageCatalog.allLanguages.size)
        assertEquals(42, TranslateLanguageCatalog.sourceLanguages.size)
        assertEquals(
            listOf("ja", "en", "zh", "ko", "hi", "es", "fr", "de"),
            TranslateLanguageCatalog.pinnedLanguages.map { it.code }
        )

        // All codes must be unique
        assertEquals(
            TranslateLanguageCatalog.allLanguages.size,
            TranslateLanguageCatalog.allLanguages.map { it.code }.toSet().size
        )

        // Alias normalization
        assertEquals("zh", TranslateLanguageCatalog.normalizeCode("zh-Hans-CN"))
        assertEquals("he", TranslateLanguageCatalog.normalizeCode("iw"))
        assertEquals("id", TranslateLanguageCatalog.normalizeCode("in"))
        assertEquals("no", TranslateLanguageCatalog.normalizeCode("nb-NO"))
        assertEquals("tl", TranslateLanguageCatalog.normalizeCode("fil"))

        // Non-OCR target language is valid target, not OCR-readable source
        val arabic = TranslateLanguageCatalog.findByCode("ar")
        assertNotNull(arabic)
        assertNull(arabic?.ocrScript)
        assertEquals(TranslateLanguageCatalog.AUTO_CODE, TranslateLanguageCatalog.sanitizeSourceCode("ar"))
        assertEquals(TranslateLanguageCatalog.AUTO_CODE, TranslateLanguageCatalog.sanitizeSourceCode("auto"))
        assertEquals("ar", TranslateLanguageCatalog.sanitizeTargetCode("ar"))

        // Quick swap behavior
        assertEquals("ja" to "en", TranslateLanguageCatalog.computeQuickSwap("en", "ja"))
        assertEquals("auto" to "en", TranslateLanguageCatalog.computeQuickSwap("en", "ar"))
        assertEquals("ja" to "en", TranslateLanguageCatalog.computeQuickSwap("auto", "ja"))

        // Spec 70: AUTO source always starts with Latin OCR (preventing Japanese/Chinese mis-OCR of English text)
        assertEquals(OcrScript.LATIN, TranslateLanguageCatalog.ocrScriptForSource("auto", "ja"))
        assertEquals(OcrScript.LATIN, TranslateLanguageCatalog.ocrScriptForSource("auto", "zh"))
        assertEquals(OcrScript.LATIN, TranslateLanguageCatalog.ocrScriptForSource("auto", "en"))
        assertEquals(OcrScript.JAPANESE, TranslateLanguageCatalog.ocrScriptForSource("ja", "en"))
        assertEquals(OcrScript.CHINESE, TranslateLanguageCatalog.ocrScriptForSource("zh", "ja"))
        assertEquals(OcrScript.KOREAN, TranslateLanguageCatalog.ocrScriptForSource("ko", "ja"))
        assertEquals(OcrScript.DEVANAGARI, TranslateLanguageCatalog.ocrScriptForSource("hi", "en"))
    }

    @Test
    fun edgeConfig_translateFields_roundTripAndClamp() {
        val original = EdgeConfig(
            translateSourceLang = "en",
            translateTargetLang = "ja",
            translateTrackingMode = LiveTranslateTrackingMode.GAME_CONTINUOUS,
            translateRefreshSpeed = LiveTranslateRefreshSpeed.FAST,
            translateRenderStyle = LiveTranslateRenderStyle.FROSTED_CARD,
            translateOverlayAlpha = 0.82f,
            translateSkipTargetLanguage = false,
            translateWifiOnlyDownload = false,
            horizontalAction = EdgeAction(EdgeActionType.TOGGLE_SCREEN_TRANSLATE),
            horizontalHoldAction = EdgeAction(EdgeActionType.TRANSLATE_SCREEN_ONCE)
        )

        val encoded = original.serialize()
        val decoded = EdgeConfig.deserialize(encoded)

        assertEquals("en", decoded.translateSourceLang)
        assertEquals("ja", decoded.translateTargetLang)
        assertEquals(LiveTranslateTrackingMode.GAME_CONTINUOUS, decoded.translateTrackingMode)
        assertEquals(LiveTranslateRefreshSpeed.FAST, decoded.translateRefreshSpeed)
        assertEquals(LiveTranslateRenderStyle.FROSTED_CARD, decoded.translateRenderStyle)
        assertEquals(0.82f, decoded.translateOverlayAlpha, 0.001f)
        assertEquals(false, decoded.translateSkipTargetLanguage)
        assertEquals(false, decoded.translateWifiOnlyDownload)
        assertEquals(EdgeActionType.TOGGLE_SCREEN_TRANSLATE, decoded.horizontalAction.type)
        assertEquals(EdgeActionType.TRANSLATE_SCREEN_ONCE, decoded.horizontalHoldAction.type)

        // Clamping & invalid source language sanitization
        val clamped = EdgeConfig(
            translateSourceLang = "ar", // target-only -> sanitized to auto
            translateTargetLang = "invalid_xyz", // fallback to valid default
            translateOverlayAlpha = 2.5f
        ).clamped()
        assertEquals(TranslateLanguageCatalog.AUTO_CODE, clamped.translateSourceLang)
        assertTrue(TranslateLanguageCatalog.findByCode(clamped.translateTargetLang) != null)
        assertEquals(EdgeConfig.MAX_TRANSLATE_OVERLAY_ALPHA, clamped.translateOverlayAlpha, 0.001f)
    }

    @Test
    fun edgeConfig_controlCenterFloatTileFields_roundTripAndClamp() {
        // Default config has unset coordinates
        val defaults = EdgeConfig()
        assertEquals(EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD, defaults.ccFloatTileNormX, 0.0001f)
        assertEquals(EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD, defaults.ccFloatTileNormY, 0.0001f)
        assertEquals(EdgeConfig.CC_FLOAT_METHOD_AUTO, defaults.ccFloatTileDetectionMethod)
        assertEquals(false, defaults.hasSavedCcFloatTileCoords())

        // Round-trip valid saved OCR coordinates
        val saved = EdgeConfig(
            ccFloatTileNormX = 0.72f,
            ccFloatTileNormY = 0.34f,
            ccFloatTileDetectionMethod = EdgeConfig.CC_FLOAT_METHOD_OCR
        )
        assertTrue(saved.hasSavedCcFloatTileCoords())
        val decoded = EdgeConfig.deserialize(saved.serialize())
        assertEquals(0.72f, decoded.ccFloatTileNormX, 0.0001f)
        assertEquals(0.34f, decoded.ccFloatTileNormY, 0.0001f)
        assertEquals(EdgeConfig.CC_FLOAT_METHOD_OCR, decoded.ccFloatTileDetectionMethod)
        assertTrue(decoded.hasSavedCcFloatTileCoords())

        // Backward-compatibility when serialized string lacks new keys
        val legacyDecoded = EdgeConfig.deserialize("leftEnabled=true\nrightEnabled=true\n")
        assertEquals(EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD, legacyDecoded.ccFloatTileNormX, 0.0001f)
        assertEquals(EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD, legacyDecoded.ccFloatTileNormY, 0.0001f)
        assertEquals(EdgeConfig.CC_FLOAT_METHOD_AUTO, legacyDecoded.ccFloatTileDetectionMethod)
        assertEquals(false, legacyDecoded.hasSavedCcFloatTileCoords())

        // Clamping & sanitization: paired invalid coordinates reset cleanly to -1f
        val clamped = EdgeConfig(
            ccFloatTileNormX = 1.4f,
            ccFloatTileNormY = -0.25f,
            ccFloatTileDetectionMethod = "bogus_method"
        ).clamped()
        assertEquals(EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD, clamped.ccFloatTileNormX, 0.0001f)
        assertEquals(EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD, clamped.ccFloatTileNormY, 0.0001f)
        assertEquals(EdgeConfig.CC_TILE_METHOD_AUTO, clamped.ccFloatTileDetectionMethod)
        assertEquals(false, clamped.hasSavedCcFloatTileCoords())

        // Edge-border coordinates outside [0.02, 0.98] are rejected as a pair
        val edgeClamped = EdgeConfig(
            ccFloatTileNormX = 0.01f,
            ccFloatTileNormY = 0.50f,
            ccFloatTileDetectionMethod = "MANUAL"
        ).clamped()
        assertEquals(EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD, edgeClamped.ccFloatTileNormX, 0.0001f)
        assertEquals(EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD, edgeClamped.ccFloatTileNormY, 0.0001f)
        assertEquals(EdgeConfig.CC_TILE_METHOD_MANUAL_TAP, edgeClamped.ccFloatTileDetectionMethod)
        assertEquals(false, edgeClamped.hasSavedCcFloatTileCoords())

        // NODE method survives serialization round-trip
        val nodeSaved = EdgeConfig(
            ccFloatTileNormX = 0.25f,
            ccFloatTileNormY = 0.40f,
            ccFloatTileDetectionMethod = EdgeConfig.CC_TILE_METHOD_NODE
        )
        val nodeDecoded = EdgeConfig.deserialize(nodeSaved.serialize())
        assertEquals(EdgeConfig.CC_TILE_METHOD_NODE, nodeDecoded.ccFloatTileDetectionMethod)
        assertTrue(nodeDecoded.hasSavedCcFloatTileCoords())
    }

    @Test
    fun edgeConfig_translateEngineMode_enforcesPrivacyConsentClampAndRoundTrip() {
        // Default is strictly offline with consent = false
        val defaults = EdgeConfig()
        assertEquals(LiveTranslateEngineMode.OFFLINE_MLKIT, defaults.translateEngineMode)
        assertEquals(false, defaults.translateOnlineConsentAccepted)

        // Attempting ONLINE_HIGH_QUALITY without consent MUST clamp back to OFFLINE_MLKIT
        val unconsented = EdgeConfig(
            translateEngineMode = LiveTranslateEngineMode.ONLINE_HIGH_QUALITY,
            translateOnlineConsentAccepted = false
        ).clamped()
        assertEquals(LiveTranslateEngineMode.OFFLINE_MLKIT, unconsented.translateEngineMode)
        assertEquals(false, unconsented.translateOnlineConsentAccepted)

        // Tampered serialized string with online mode but consent=false MUST also clamp on deserialize
        val tampered = EdgeConfig.deserialize(
            "translateEngineMode=ONLINE_HIGH_QUALITY\ntranslateOnlineConsentAccepted=false\n"
        )
        assertEquals(LiveTranslateEngineMode.OFFLINE_MLKIT, tampered.translateEngineMode)
        assertEquals(false, tampered.translateOnlineConsentAccepted)

        // Consented ONLINE_HIGH_QUALITY survives serialize -> deserialize round-trip
        val consented = EdgeConfig(
            translateEngineMode = LiveTranslateEngineMode.ONLINE_HIGH_QUALITY,
            translateOnlineConsentAccepted = true
        )
        val roundTripped = EdgeConfig.deserialize(consented.serialize())
        assertEquals(LiveTranslateEngineMode.ONLINE_HIGH_QUALITY, roundTripped.translateEngineMode)
        assertEquals(true, roundTripped.translateOnlineConsentAccepted)

        // Legacy config missing both keys restores safely to OFFLINE_MLKIT & consent=false
        val legacy = EdgeConfig.deserialize("translateSourceLang=en\ntranslateTargetLang=ja\n")
        assertEquals(LiveTranslateEngineMode.OFFLINE_MLKIT, legacy.translateEngineMode)
        assertEquals(false, legacy.translateOnlineConsentAccepted)
    }

    @Test
    fun edgeConfig_oneTapTranslateAndImeBottomBar_defaultsRoundTripAndLegacyCompat() {
        // Verify new defaults
        val defaults = EdgeConfig()
        assertEquals(LiveTranslateTrackingMode.MANUAL_ONCE, defaults.translateTrackingMode)
        assertEquals(true, defaults.translateAutoClearOnScroll)
        assertEquals(true, defaults.showBottomBarDuringIme)

        // Round-trip non-default values
        val modified = EdgeConfig(
            translateTrackingMode = LiveTranslateTrackingMode.MANUAL_ONCE,
            translateAutoClearOnScroll = false,
            showBottomBarDuringIme = false
        )
        val decoded = EdgeConfig.deserialize(modified.serialize())
        assertEquals(LiveTranslateTrackingMode.MANUAL_ONCE, decoded.translateTrackingMode)
        assertEquals(false, decoded.translateAutoClearOnScroll)
        assertEquals(false, decoded.showBottomBarDuringIme)

        // Legacy serialized payload missing the new keys falls back cleanly to safe defaults
        val legacy = EdgeConfig.deserialize("leftEnabled=true\nbottomEnabled=true\n")
        assertEquals(LiveTranslateTrackingMode.MANUAL_ONCE, legacy.translateTrackingMode)
        assertEquals(true, legacy.translateAutoClearOnScroll)
        assertEquals(true, legacy.showBottomBarDuringIme)
    }
}


