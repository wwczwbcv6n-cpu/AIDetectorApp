package com.myapplication.common.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The served `POST /analyze` bodies decode into [ApiAnalysisResult] with the
 * same Json configuration `ApiClient` uses. The fixtures carry the exact key
 * sets of the parent repo's `contract/api_v2.json` variants
 * ("200 content+edit_screen", "200 provenance", "400 no_file") plus the
 * optional keys the snapshot cannot show (`union_candidate`), so a key the
 * server adds must never break decoding. Run: ./gradlew :shared:desktopTest
 */
class ApiAnalysisResultTest {

    private val json = Json {
        prettyPrint = false
        isLenient = true
        ignoreUnknownKeys = true
    }

    @Test
    fun contentBodyWithEditScreenDecodes() {
        val body = """
            {"verdict": "uncertain",
             "label": "Uncertain — possible local edit",
             "confidence": 0,
             "p_ai": 0.1234,
             "ai_probability": 0.1234,
             "conclusion": "UNCERTAIN",
             "degradation": {"level": "moderate", "reasons": ["EXIF stripped"], "jpeg_quality": 78},
             "method": "content",
             "detail": "Reads as a camera photo overall. Its noise pattern is uneven.",
             "preview": "data:image/jpeg;base64,/9j/4AAQ",
             "model": "tayanch_union_vitl14_v5",
             "elapsed_ms": 812,
             "localized_edit": {"score": 0.71, "thr": 0.6, "fires": true, "localized": true,
                                "edited_score": 0.55, "mask_area": 0.03, "bbox": [10, 20, 30, 40],
                                "clean_fpr": 0.02},
             "heatmap_token": "0123456789abcdef0123456789abcdef",
             "union_candidate": "clip_laundered"}
        """.trimIndent()
        val r = json.decodeFromString(ApiAnalysisResult.serializer(), body)
        assertEquals("uncertain", r.verdict)
        assertEquals(Verdict.UNCERTAIN, r.toVerdict())
        assertFalse(r.isAI)
        assertEquals(0.1234f, r.uiConfidence, 1e-6f)
        assertEquals(0.0, r.confidence)
        assertEquals("Uncertain — possible local edit", r.label)
        assertEquals("content", r.method)
        assertEquals("tayanch_union_vitl14_v5", r.model)
        assertEquals(812L, r.elapsedMs)
        assertEquals("0123456789abcdef0123456789abcdef", r.heatmapToken)
        assertEquals("moderate", r.degradation?.level)
        assertEquals(listOf("EXIF stripped"), r.degradation?.reasons)
        assertEquals(true, r.localizedEdit?.fires)
        assertTrue(r.editLocalized)
        assertNull(r.provenanceKind)
        assertFalse(r.isServerError)
    }

    @Test
    fun provenanceBodyDecodes() {
        val body = """
            {"verdict": "ai",
             "label": "AI-generated — Content Credentials (C2PA) manifest declares AI generation",
             "provenance_kind": "c2pa_manifest",
             "provenance_verified": false,
             "confidence": 100,
             "p_ai": 1.0,
             "ai_probability": 1.0,
             "conclusion": "AI-Generated",
             "method": "provenance",
             "detail": "C2PA manifest: digitalSourceType trainedAlgorithmicMedia",
             "preview": "data:image/jpeg;base64,/9j/4AAQ",
             "model": "tayanch_union_vitl14_v5",
             "elapsed_ms": 41,
             "heatmap_token": "fedcba9876543210fedcba9876543210"}
        """.trimIndent()
        val r = json.decodeFromString(ApiAnalysisResult.serializer(), body)
        assertEquals(Verdict.AI, r.toVerdict())
        assertTrue(r.isAI)
        assertEquals(1.0f, r.uiConfidence, 1e-6f)
        assertEquals("c2pa_manifest", r.provenanceKind)
        assertEquals(false, r.provenanceVerified)
        assertNull(r.degradation)
        assertNull(r.localizedEdit)
        assertFalse(r.editLocalized)
        assertFalse(r.isServerError)
    }

    @Test
    fun realVerdictNeverReadsConfidenceAsAiProbability() {
        // `confidence` is a 0..100 percent (confidence in REAL here); the bar must
        // come from ai_probability / p_ai, never from a clamped `confidence`.
        val body = """{"verdict": "real", "label": "Likely authentic", "confidence": 96,
                       "p_ai": 0.02, "ai_probability": 0.02, "conclusion": "REAL",
                       "degradation": {"level": "none", "reasons": []}, "method": "content",
                       "detail": null, "model": "tayanch_union_vitl14_v5", "elapsed_ms": 650,
                       "heatmap_token": "00000000000000000000000000000000"}"""
        val r = json.decodeFromString(ApiAnalysisResult.serializer(), body)
        assertEquals(Verdict.AUTHENTIC, r.toVerdict())
        assertFalse(r.isAI)
        assertEquals(0.02f, r.uiConfidence, 1e-6f)
        assertNull(r.detail)
    }

    @Test
    fun gracefulErrorBodyIsAServerError() {
        val r = json.decodeFromString(ApiAnalysisResult.serializer(), """{"error": "no_file"}""")
        assertTrue(r.isServerError)
        assertEquals("no_file", r.error)
        assertEquals(Verdict.UNCERTAIN, r.toVerdict())
    }
}
