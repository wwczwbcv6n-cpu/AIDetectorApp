package com.myapplication.common.data

import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit 2026-09-24 APP-09: the served API scales to zero (Modal
 * min_containers=0), so the first request after ~10 idle minutes meets a
 * cold start; the app's first call was GET /pubkey with a hard 10 s
 * timeout, there was no retry on 503 'server busy' (max_containers=1), and
 * the /analyze timeout covered the whole upload. The site waits up to 60 s
 * on /health first (site/demo.js) — the app now does the same, retries a
 * busy / bad-gateway answer once with a fresh envelope, and scales the
 * upload timeout with the file size. Run: ./gradlew :shared:desktopTest
 */
class ColdStartTest {

    private val img = ByteArray(4096) { (it * 7).toByte() }
    private val fast = ApiClient.Backoff(healthBudgetMs = 2_000, firstDelayMs = 1, retryDelayMs = 1)
    private val settings = AppSettings(apiBaseUrl = "https://api.example.test", apiKey = "k")

    @Test
    fun aColdServerIsWaitedForThenAnalyzes() = runBlocking {
        val s = FakeTayanchServer()
        var healthCalls = 0
        val engine = s.engine(other = { req ->
            if (req.url.encodedPath == "/health") {
                healthCalls++
                when (healthCalls) {
                    1 -> throw IOException("connection reset while the container boots")
                    2 -> with(s) { plain(HttpStatusCode.ServiceUnavailable, """{"error": "starting"}""") }
                    else -> with(s) { plain(HttpStatusCode.OK, """{"status": "ok"}""") }
                }
            } else with(s) { plain(HttpStatusCode.NotFound, "{}") }
        }) { req -> with(s) { sealed(req, HttpStatusCode.OK, FakeTayanchServer.REAL_BODY) } }
        val c = ApiClient(settings, hpke = s.hpke, engine = engine, backoff = fast)
        val r = c.analyzeImage(img)
        assertTrue(r.isSuccess, "got ${r.exceptionOrNull()}")
        assertEquals(3, healthCalls)
        assertEquals(listOf("/health", "/health", "/health", "/pubkey", "/analyze"), s.calls)
        // warm now: the next analysis does not wait on /health again
        s.calls.clear()
        assertTrue(c.analyzeImage(img).isSuccess)
        assertEquals(listOf("/pubkey", "/analyze"), s.calls)
    }

    @Test
    fun serverBusyIsRetriedOnceWithAFreshEnvelope() = runBlocking {
        val s = FakeTayanchServer()
        var analyzeCalls = 0
        val c = ApiClient(settings, hpke = s.hpke, backoff = fast, engine = s.engine(
            other = { with(s) { plain(HttpStatusCode.OK, """{"status": "ok"}""") } }) { req ->
            analyzeCalls++
            if (analyzeCalls == 1) with(s) { plain(HttpStatusCode.ServiceUnavailable, """{"error": "server busy — retry in a moment"}""") }
            else with(s) { sealed(req, HttpStatusCode.OK, FakeTayanchServer.REAL_BODY) }
        })
        val r = c.analyzeImage(img)
        assertTrue(r.isSuccess, "got ${r.exceptionOrNull()}")
        assertEquals(2, analyzeCalls)
        assertEquals(2, s.calls.count { it == "/pubkey" }, "a retry re-fetches the key and re-seals")
    }

    @Test
    fun dailyCapacityIsNotRetried() = runBlocking {
        val s = FakeTayanchServer()
        var analyzeCalls = 0
        val c = ApiClient(settings, hpke = s.hpke, backoff = fast, engine = s.engine(
            other = { with(s) { plain(HttpStatusCode.OK, """{"status": "ok"}""") } }) {
            analyzeCalls++
            with(s) { plain(HttpStatusCode.ServiceUnavailable, """{"error": "service is at its daily capacity — retry later"}""") }
        })
        val e = (c.analyzeImage(img).exceptionOrNull() as ApiException).apiError
        assertEquals(1, analyzeCalls)
        assertTrue("daily capacity" in e.userMessage, e.userMessage)
    }

    @Test
    fun uploadTimeoutGrowsWithTheFile() {
        val c = ApiClient(settings)
        val small = c.uploadTimeoutMs(100_000)
        val big = c.uploadTimeoutMs(20L * 1024 * 1024)
        c.close()
        assertTrue(small >= settings.effectiveTimeoutMs)
        // 20 MB at a 256 kbit/s uplink needs ~11 minutes on top of the analysis
        assertTrue(big >= settings.effectiveTimeoutMs + 600_000, "big=$big")
    }
}
