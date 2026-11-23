package com.edgeflow.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineTranslationClientTest {

    @Test
    fun parseGtxJsonResponse_concatenatesMultiSegmentAndUnescapesCharacters() {
        val raw = """
            [[["こんにちは、\"世界\"！\n","Hello, \"World\"!\n",null,null,10],["\u3042\u3044\u3046えお\\テスト\/完了","aiueo",null,null,10]],null,"en",null,null,null,1.0]
        """.trimIndent()

        val parsed = OnlineTranslationClient.parseGtxJsonResponse(raw)
        assertEquals("こんにちは、\"世界\"！\nあいうえお\\テスト/完了", parsed)
    }

    @Test
    fun parseGtxJsonResponse_handlesEnglishJapaneseBatchWithDoubleNewlines() {
        val raw = """
            [[["最初の段落です。\n\n","First paragraph.\n\n",null,null,10],["2番目の段落です。","Second paragraph.",null,null,10]],null,"en"]
        """.trimIndent()

        val parsed = OnlineTranslationClient.parseGtxJsonResponse(raw)
        assertEquals("最初の段落です。\n\n2番目の段落です。", parsed)
    }

    @Test
    fun parseGtxJsonResponse_returnsNullOnMalformedOrEmptyJson() {
        assertNull(OnlineTranslationClient.parseGtxJsonResponse(""))
        assertNull(OnlineTranslationClient.parseGtxJsonResponse("   "))
        assertNull(OnlineTranslationClient.parseGtxJsonResponse("[]"))
        assertNull(OnlineTranslationClient.parseGtxJsonResponse("[[]]"))
        assertNull(OnlineTranslationClient.parseGtxJsonResponse("not json"))
        assertNull(OnlineTranslationClient.parseGtxJsonResponse("[[[\"unterminated"))
        assertNull(OnlineTranslationClient.parseGtxJsonResponse("{\"error\":\"rate_limit\"}"))
    }

    @Test
    fun splitBatchTranslation_returnsOnlyExactCountMatchAndNullOnMismatch() {
        val exact = OnlineTranslationClient.splitBatchTranslation(
            translatedJoined = "段落1です。\n\n段落2です。\r\n \r\n段落3です。",
            expectedCount = 3
        )
        assertEquals(listOf("段落1です。", "段落2です。", "段落3です。"), exact)

        val single = OnlineTranslationClient.splitBatchTranslation(
            translatedJoined = "単独の訳文",
            expectedCount = 1
        )
        assertEquals(listOf("単独の訳文"), single)

        // Fewer segments than expected -> null (triggers safe per-item fallback)
        assertNull(
            OnlineTranslationClient.splitBatchTranslation(
                translatedJoined = "段落1です。\n\n段落2です。",
                expectedCount = 3
            )
        )

        // More segments than expected -> null (triggers safe per-item fallback)
        assertNull(
            OnlineTranslationClient.splitBatchTranslation(
                translatedJoined = "A\n\nB\n\nC",
                expectedCount = 2
            )
        )
    }

    @Test
    fun mapLanguageCode_normalizesSpecialCodesCorrectly() {
        assertEquals("auto", OnlineTranslationClient.mapLanguageCode("auto", allowAuto = true))
        assertEquals("zh-CN", OnlineTranslationClient.mapLanguageCode("zh", allowAuto = false))
        assertEquals("iw", OnlineTranslationClient.mapLanguageCode("he", allowAuto = false))
        assertEquals("ja", OnlineTranslationClient.mapLanguageCode("ja", allowAuto = false))
    }
}
