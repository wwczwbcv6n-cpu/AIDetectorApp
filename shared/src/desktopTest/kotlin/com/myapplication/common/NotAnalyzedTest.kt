package com.myapplication.common

import com.myapplication.common.data.AnalysisHistoryEntry
import com.myapplication.common.data.ApiClient
import com.myapplication.common.data.ApiError
import com.myapplication.common.data.ApiException
import com.myapplication.common.data.AppSettings
import com.myapplication.common.data.Verdict
import io.ktor.client.engine.mock.MockEngine
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When Tayanch's server is not reached (offline, timeout, no server URL set,
 * client not built yet) the app shows NOT_ANALYZED — never a verdict band and
 * never a probability. The old fallback rendered the on-device heuristic as
 * "Likely authentic", which on data/api_bench (approximate port, audit
 * 2026-09-24 APP-01) cleared ~97% of AI photos, and it was the out-of-box
 * experience because apiBaseUrl defaults to "". Run: ./gradlew :shared:desktopTest
 */
class NotAnalyzedTest {

    // Bytes of an AI image with no metadata confession: the server would be
    // the only thing that could call it, so offline it must stay unanswered.
    private val aiFixture = ByteArray(2048) { (it * 31 + 7).toByte() }.also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte()
    }

    private fun assertNotAnalyzed(outcome: FailureOutcome) {
        val state = assertNotNull(outcome.state, "an unreachable server must still show a result card")
        assertEquals(Verdict.NOT_ANALYZED, state.verdict)
        assertNotEquals(Verdict.AUTHENTIC, state.verdict)
        assertNull(state.confidencePct)
        assertNull(state.mix)
        assertTrue(outcome.errorMessage.isNotBlank())
        assertTrue("estimate" !in outcome.errorMessage.lowercase(),
            "no on-device estimate is offered any more: ${outcome.errorMessage}")
    }

    @Test
    fun networkFailureIsNotAnalyzed() = runBlocking {
        val client = ApiClient(
            AppSettings(apiBaseUrl = "https://api.example.test"),
            engine = MockEngine { throw IOException("offline") },
        )
        val result = client.analyzeImage(aiFixture)
        client.close()
        val e = result.exceptionOrNull()
        assertEquals(ApiError.Network, (e as ApiException).apiError)
        assertNotAnalyzed(failureOutcome(e))
    }

    @Test
    fun unconfiguredServerIsNotAnalyzed() = runBlocking {
        val client = ApiClient(AppSettings(apiBaseUrl = ""),
            engine = MockEngine { error("must not be called") })
        val e = client.analyzeImage(aiFixture).exceptionOrNull()
        client.close()
        assertTrue((e as ApiException).apiError is ApiError.Configuration)
        assertNotAnalyzed(failureOutcome(e))
    }

    @Test
    fun timeoutIsNotAnalyzed() {
        assertNotAnalyzed(failureOutcome(ApiException(ApiError.Timeout)))
    }

    @Test
    fun clientNotBuiltIsNotAnalyzed() {
        assertNotAnalyzed(serverNotReadyOutcome())
    }

    @Test
    fun authFailureShowsTheErrorOnly() {
        val outcome = failureOutcome(ApiException(ApiError.Auth))
        assertNull(outcome.state)
        assertEquals(ApiError.Auth.userMessage, outcome.errorMessage)
    }

    // ── LOCAL rows the old fallback already wrote to history ─────────────

    private val localRow = AnalysisHistoryEntry(
        fileName = "selected_image.jpg", fileSize = 2048L, isAI = false,
        confidence = 0.21f, analysisMode = "LOCAL", processingTimeMs = 40L,
        verdict = Verdict.AUTHENTIC.name, sha256 = "ab".repeat(32))

    @Test
    fun oldLocalRowReopensAsNotAnalyzed() {
        assertEquals(Verdict.NOT_ANALYZED, localRow.verdictBand)
        assertEquals("Not analyzed", localRow.statusText)
        val state = localRow.toUiState()
        assertEquals(Verdict.NOT_ANALYZED, state.verdict)
        assertNull(state.confidencePct)
    }

    @Test
    fun oldLocalRowIsNeverACacheHit() {
        assertNull(cachedVerdict(listOf(localRow), localRow.sha256!!))
    }

    @Test
    fun serverRowStillReopensWithItsVerdict() {
        val server = localRow.copy(analysisMode = "FAST")
        assertEquals(Verdict.AUTHENTIC, server.verdictBand)
        assertEquals(Verdict.AUTHENTIC, server.toUiState().verdict)
    }
}
