package com.myapplication.common

import com.myapplication.common.data.AnalysisHistoryEntry
import com.myapplication.common.data.ApiAnalysisResult
import com.myapplication.common.data.Verdict
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audit 2026-09-24 APP-16, the share-sheet flow (the consumer's main entry):
 * rotating during "Analyzing…" recreated ShareActivity and LaunchedEffect(uri)
 * uploaded the photo AGAIN (a second billed call on the key), and share
 * results never reached history. The request is now started once per shared
 * item by a guard that lives in a retained ViewModel, and the share result is
 * written with the same history row builder the main screen uses.
 * Run: ./gradlew :shared:desktopTest
 */
class ShareFlowTest {

    @Test
    fun aSharedItemIsSentOnceAcrossRecreation() {
        val guard = ShareRequestGuard()
        assertTrue(guard.shouldStart("content://media/external/images/42"))
        // the Activity is recreated on rotation; the retained guard says no
        assertFalse(guard.shouldStart("content://media/external/images/42"))
        assertTrue(guard.shouldStart("content://media/external/images/43"))
    }

    @Test
    fun aServerResultBecomesTheSameHistoryRowAsTheMainScreen() {
        val r = Json { ignoreUnknownKeys = true }.decodeFromString(ApiAnalysisResult.serializer(),
            """{"verdict": "uncertain", "label": "Mixed signals", "confidence": 0, "p_ai": 0.9,
                "model": "tayanch_union_vitl14_v5",
                "mix": {"ai_share": 0.4, "real_share": 0.6, "signals": []}}""")
        val row = serverHistoryEntry(r, "IMG_1.jpg", 2048L, 700L, "ab".repeat(32))
        assertEquals(Verdict.UNCERTAIN, row.verdictBand)
        assertEquals("Mixed signals", row.label)
        assertEquals(0f, row.confidencePct)
        assertEquals(0.4f, row.mixAiShare)
        assertEquals("tayanch_union_vitl14_v5", row.model)
        assertEquals(AnalysisHistoryEntry.MODE_SERVER, row.analysisMode)
        assertEquals("IMG_1.jpg", row.fileName)
    }
}
