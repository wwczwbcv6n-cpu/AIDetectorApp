package com.myapplication.common.ui

import com.myapplication.common.data.AnalysisHistoryEntry
import com.myapplication.common.data.ApiAnalysisResult
import com.myapplication.common.data.Verdict
import com.myapplication.common.toUiState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The result card mirrors site/demo.js showResult: the headline is the
 * server's `label`, "N% confident" comes from `confidence` (0..100) and only
 * for an ai/real verdict, and an uncertain verdict shows the `mix` shares
 * when present, otherwise no number. The raw p_ai is never shown: the served
 * head's uncertain band is 0.20 <= p_ai < 0.99, so the old "AI-generated
 * probability: 97%" on an Uncertain card read as an accusation, while
 * tayanch.com showed the same file as "Mixed signals" with no number
 * (audit 2026-09-24 APP-02). Run: ./gradlew :shared:desktopTest
 */
class ResultPresentationTest {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private fun decode(body: String) = json.decodeFromString(ApiAnalysisResult.serializer(), body)

    private val uncertainWithMix = """
        {"verdict": "uncertain", "label": "Mixed signals", "confidence": 0,
         "p_ai": 0.97, "ai_probability": 0.97, "conclusion": "UNCERTAIN",
         "method": "content", "detail": null, "model": "tayanch_union_vitl14_v5",
         "elapsed_ms": 700, "heatmap_token": "00000000000000000000000000000000",
         "mix": {"kind": "image", "ai_share": 0.62, "real_share": 0.38, "n_measured": 2,
                 "signals": [{"name": "union head", "reads": "unclear", "p_ai": 0.97,
                              "band": [0.2, 0.99], "pos": 0.97, "in_share": true},
                             {"name": "edit screen", "reads": "clear", "in_share": false}],
                 "parts": {}, "advisory": false, "basis": "model reads on their own bands"}}
    """.trimIndent()

    @Test
    fun uncertainWithMixShowsMixedSignalsAndNoRawProbability() {
        val r = decode(uncertainWithMix)
        val text = presentResult(r.toVerdict(), r.label, r.confidence, r.mix?.toSummary()).allText()
        assertTrue(text.any { "Mixed signals" in it }, text.toString())
        assertTrue(text.any { "62% reads AI-like" in it && "38% reads real-like" in it }, text.toString())
        assertFalse(text.any { "97%" in it }, text.toString())
        assertFalse(text.any { "AI-generated probability" in it }, text.toString())
        assertFalse(text.any { "confident" in it }, text.toString())
    }

    @Test
    fun uncertainSignalsLeanNeutrallyAndNeverSayReadsAi() {
        // clip_conflict: the union is past its cut (reads "ai"), the sidecar reads
        // real, the verdict is uncertain. The site says "leans AI" in a neutral
        // chip (audit 2026-09-23 DISP-3); the app printed "reads ai" and "reads
        // flag" and drew the AI-like share in accusation red.
        val r = decode("""{"verdict": "uncertain", "label": "Mixed signals", "confidence": 0,
            "p_ai": 0.9999,
            "mix": {"ai_share": 0.55, "real_share": 0.45,
                    "signals": [{"name": "sensor + content model", "reads": "ai", "in_share": true},
                                {"name": "edit-region check", "reads": "flag", "in_share": false},
                                {"name": "content model", "reads": "real", "in_share": true},
                                {"name": "camera check", "reads": "corroborating"},
                                {"name": "sidecar", "reads": "quiet"}]}}""")
        val p = presentResult(r.toVerdict(), r.label, r.confidence, r.mix?.toSummary())
        val lines = p.signalLines
        assertEquals("sensor + content model: leans AI", lines[0])
        assertEquals("edit-region check: leans AI · shown, not averaged", lines[1])
        assertEquals("content model: reads real", lines[2])
        assertEquals("camera check: leans AI (with the sensor check)", lines[3])
        assertEquals("sidecar: quiet", lines[4])
        assertFalse(p.allText().any { "reads ai" in it || "reads flag" in it }, p.allText().toString())
        assertEquals(0xFFB8804A, MIX_BAR_AI_LIKE_ARGB)
        assertTrue(MIX_BAR_AI_LIKE_ARGB != 0xFFC62828, "the mix bar must not use the accusation red")
    }

    @Test
    fun uncertainWithoutMixShowsNoNumber() {
        val r = decode("""{"verdict": "uncertain", "label": "Uncertain — possible local edit",
                           "confidence": 0, "p_ai": 0.85, "ai_probability": 0.85}""")
        val p = presentResult(r.toVerdict(), r.label, r.confidence, r.mix?.toSummary())
        assertEquals("Uncertain — possible local edit", p.headline)
        assertNull(p.confidenceLine)
        assertNull(p.mixLine)
        assertFalse(p.allText().any { it.contains(Regex("\\d+%")) }, p.allText().toString())
    }

    @Test
    fun realVerdictShowsServerConfidenceNotPAi() {
        val r = decode("""{"verdict": "real", "label": "Likely authentic", "confidence": 96,
                           "p_ai": 0.03, "ai_probability": 0.03}""")
        val p = presentResult(r.toVerdict(), r.label, r.confidence, r.mix?.toSummary())
        assertEquals("Likely authentic", p.headline)
        assertEquals("96% confident", p.confidenceLine)
        assertFalse(p.allText().any { "3%" in it })
    }

    @Test
    fun headlineIsTheServerLabel() {
        val r = decode("""{"verdict": "ai",
            "label": "AI-generated — the file's own metadata declares an AI generator",
            "confidence": 100, "p_ai": 1.0}""")
        val p = presentResult(r.toVerdict(), r.label, r.confidence, null)
        assertEquals("AI-generated — the file's own metadata declares an AI generator", p.headline)
        assertEquals("100% confident", p.confidenceLine)
    }

    @Test
    fun missingLabelFallsBackToTheBandTitle() {
        val p = presentResult(Verdict.AI, null, null, null)
        assertEquals("Likely AI-generated", p.headline)
        assertNull(p.confidenceLine)
    }

    // ── history rows ────────────────────────────────────────────────────

    @Test
    fun oldHistoryRowNeverShowsItsStoredPAiAsConfidence() {
        // Rows written before this change stored p_ai in `confidence`: a REAL
        // row read "Confidence: 3%". They are relabelled: band title, no number.
        val old = AnalysisHistoryEntry(fileName = "a.jpg", fileSize = 1L, isAI = false,
            confidence = 0.03f, analysisMode = "FAST", processingTimeMs = 5L,
            verdict = Verdict.AUTHENTIC.name)
        val p = old.presentation()
        assertEquals("Likely authentic", p.headline)
        assertNull(p.confidenceLine)
        assertFalse(p.allText().any { "3%" in it })
    }

    @Test
    fun newHistoryRowReopensWithLabelConfidenceAndMix() {
        val row = AnalysisHistoryEntry(fileName = "a.jpg", fileSize = 1L, isAI = false,
            confidence = 0.97f, analysisMode = "SERVER", processingTimeMs = 5L,
            verdict = Verdict.UNCERTAIN.name, label = "Mixed signals",
            confidencePct = 0f, mixAiShare = 0.62f)
        val state = row.toUiState()
        val text = presentResult(state.verdict, state.label, state.confidencePct?.toDouble(),
            state.mix).allText()
        assertTrue(text.any { "Mixed signals" in it })
        assertTrue(text.any { "62% reads AI-like" in it })
        assertFalse(text.any { "97%" in it })
        assertEquals(text, row.presentation().allText())
    }
}
