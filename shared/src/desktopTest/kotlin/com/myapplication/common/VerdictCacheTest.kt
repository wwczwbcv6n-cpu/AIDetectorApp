package com.myapplication.common

import com.myapplication.common.data.AnalysisHistoryEntry
import com.myapplication.common.data.ApiClient
import com.myapplication.common.data.AppSettings
import com.myapplication.common.data.Verdict
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Audit 2026-09-24 APP-12: the verdict cache keyed on the byte hash alone and
 * never expired, so after Tayanch fixed a false accusation server-side (a new
 * head, a recalibrated threshold) the app replayed the old verdict as "Cached
 * result" for the same photo. A row now replays only when the SAME server
 * model decided it, within [CACHE_MAX_AGE_MS]. Run: ./gradlew :shared:desktopTest
 */
class VerdictCacheTest {

    private val hash = "cd".repeat(32)
    private val now = 1_800_000_000_000L
    private fun row(model: String?, ageMs: Long = 60_000L) = AnalysisHistoryEntry(
        fileName = "a.jpg", fileSize = 1L, isAI = false, confidence = 0.02f,
        analysisMode = AnalysisHistoryEntry.MODE_SERVER, processingTimeMs = 5L,
        verdict = Verdict.AUTHENTIC.name, sha256 = hash, model = model, timestamp = now - ageMs)

    @Test
    fun sameModelFreshRowReplays() {
        val r = row("tayanch_union_vitl14_v5")
        assertEquals(r, cachedVerdict(listOf(r), hash, "tayanch_union_vitl14_v5", now))
    }

    @Test
    fun aDifferentServerModelIsNotReplayed() {
        assertNull(cachedVerdict(listOf(row("tayanch_union_vitl14_v4")), hash, "tayanch_union_vitl14_v5", now))
    }

    @Test
    fun anUnknownCurrentModelIsNotReplayed() {
        assertNull(cachedVerdict(listOf(row("tayanch_union_vitl14_v5")), hash, null, now))
    }

    @Test
    fun rowsFromBeforeTheModelWasStoredAreNotReplayed() {
        assertNull(cachedVerdict(listOf(row(null)), hash, "tayanch_union_vitl14_v5", now))
    }

    @Test
    fun anOldRowIsNotReplayed() {
        assertNull(cachedVerdict(listOf(row("m", ageMs = CACHE_MAX_AGE_MS + 1)), hash, "m", now))
    }

    @Test
    fun theClientLearnsTheServedModelFromHealth() = runBlocking {
        val c = ApiClient(AppSettings(apiBaseUrl = "https://api.example.test"), engine = MockEngine {
            respond("""{"status": "ok", "model": "tayanch_union_vitl14_v5"}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertEquals("tayanch_union_vitl14_v5", c.currentModel())
        c.close()
    }
}
