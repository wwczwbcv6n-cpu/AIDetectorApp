package com.myapplication.common.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings -> "Test API Connection" (audit 2026-09-24 APP-04). It used to
 * test the SAVED client (not the fields being edited), probe only /health (an
 * open path, so it passed with no key or a revoked one) and write its result
 * where only the Analysis screen could show it. Now it builds a throwaway
 * client from the edited values, checks that a Tayanch API answers /health,
 * then proves the key on a gateway-authenticated, non-billable route.
 * Run: ./gradlew :shared:desktopTest
 */
class ConnectionTestTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val health = """{"status": "ok", "model": "tayanch_union_vitl14_v5", "checks": {}}"""

    private fun settings(key: String = "tk_live_edited") =
        AppSettings(apiBaseUrl = "https://api.example.test/", apiKey = key)

    @Test
    fun rejectedKeyFailsWithAuth() = runBlocking {
        val seenKeys = mutableListOf<String?>()
        val engine = MockEngine { req ->
            seenKeys += req.headers["X-API-Key"]
            when (req.url.encodedPath) {
                "/health" -> respond(health, HttpStatusCode.OK, jsonHeaders)
                else -> respond("""{"error": "invalid API key"}""", HttpStatusCode.Forbidden, jsonHeaders)
            }
        }
        val r = ApiClient(settings(), engine = engine).testConnection()
        val e = (r.exceptionOrNull() as ApiException).apiError
        assertTrue(e is ApiError.Auth, "got $e")
        // the probe carried the EDITED key, not a saved one
        assertEquals("tk_live_edited", seenKeys.last())
        assertTrue(seenKeys.size == 2, "health, then the keyed probe: $seenKeys")
    }

    @Test
    fun acceptedKeySucceeds() = runBlocking {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/health" -> respond(health, HttpStatusCode.OK, jsonHeaders)
                // the key passed the gateway; the route itself answers 404
                else -> respond("""{"error": "not found"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        }
        val r = ApiClient(settings(), engine = engine).testConnection()
        assertTrue(r.isSuccess, "got ${r.exceptionOrNull()}")
        assertTrue("accepted" in r.getOrThrow(), r.getOrThrow())
    }

    @Test
    fun noKeyIsNotReportedAsReady() = runBlocking {
        val engine = MockEngine { respond(health, HttpStatusCode.OK, jsonHeaders) }
        val r = ApiClient(settings(key = ""), engine = engine).testConnection()
        val msg = r.getOrNull() ?: (r.exceptionOrNull() as ApiException).apiError.userMessage
        assertTrue("no API key" in msg, msg)
    }

    @Test
    fun aWebsiteThatIsNotTheApiIsAConfigurationError() = runBlocking {
        // e.g. the user typed https://tayanch.com: Vercel answers a 404 page.
        val engine = MockEngine {
            respond("<html>404</html>", HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, "text/html"))
        }
        val r = ApiClient(settings(), engine = engine).testConnection()
        val e = (r.exceptionOrNull() as ApiException).apiError
        assertTrue(e is ApiError.Configuration, "got $e")
        assertTrue("Tayanch API" in e.userMessage, e.userMessage)
    }
}
