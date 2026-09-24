package com.myapplication.common.data

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit 2026-09-24 APP-08: the server's reasons never reached the user.
 * Once it opened the envelope, secure_api seals EVERY JSON reply, errors
 * included (415 'unreadable image', 400 'image too large (...)'), but the app
 * opened sealed bodies only on 2xx and showed "The server rejected the
 * request (415)." 401/403 texts ('missing API key — get a free key at
 * https://tayanch.com/api', 'API key revoked') collapsed to one line. And any
 * non-2xx /pubkey (a website 404 because the user typed tayanch.com, a 5xx)
 * became a red "Attestation failed ... Nothing was sent." card.
 * Run: ./gradlew :shared:desktopTest
 */
class ServerErrorsTest {

    private val img = ByteArray(4096) { (it * 13).toByte() }

    private fun client(server: FakeTayanchServer, engine: io.ktor.client.engine.HttpClientEngine) =
        ApiClient(AppSettings(apiBaseUrl = "https://api.example.test", apiKey = "k"),
            hpke = server.hpke, engine = engine)

    private fun errorOf(r: Result<ApiAnalysisResult>): ApiError =
        (r.exceptionOrNull() as ApiException).apiError

    @Test
    fun sealedFourFifteenShowsTheServersReason() = runBlocking {
        val s = FakeTayanchServer()
        val c = client(s, s.engine { req -> with(s) { sealed(req, HttpStatusCode.UnsupportedMediaType, """{"error": "unreadable image"}""") } })
        val e = errorOf(c.analyzeImage(img))
        assertTrue(e is ApiError.ClientError, "got $e")
        assertEquals("unreadable image", e.userMessage)
    }

    @Test
    fun sealedFiveHundredShowsTheServersReason() = runBlocking {
        val s = FakeTayanchServer()
        val c = client(s, s.engine { req -> with(s) { sealed(req, HttpStatusCode.InternalServerError, """{"error": "analysis failed — please try another image"}""") } })
        val e = errorOf(c.analyzeImage(img))
        assertTrue(e is ApiError.ServerError, "got $e")
        assertEquals("analysis failed — please try another image", e.userMessage)
    }

    @Test
    fun gatewayAuthTextReachesTheUser() = runBlocking {
        val s = FakeTayanchServer()
        val msg = "missing API key — get a free key at https://tayanch.com/api (header: X-API-Key)"
        val c = client(s, s.engine { with(s) { plain(HttpStatusCode.Unauthorized, """{"error": "$msg"}""") } })
        val e = errorOf(c.analyzeImage(img))
        assertTrue(e is ApiError.Auth, "got $e")
        assertTrue(msg in e.userMessage, e.userMessage)
    }

    @Test
    fun pubkeyNotFoundIsAConfigurationErrorNotAttestation() = runBlocking {
        val s = FakeTayanchServer()
        var tier: com.myapplication.common.secure.TierState? = null
        val c = client(s, s.engine(pubkey = { with(s) { plain(HttpStatusCode.NotFound, "{}") } }) { error("must not upload") })
        c.onTierState = { tier = it }
        val e = errorOf(c.analyzeImage(img))
        assertTrue(e is ApiError.Configuration, "got $e")
        assertTrue("Tayanch API" in e.userMessage, e.userMessage)
        assertEquals(null, tier, "no attestation state for a server that never answered /pubkey")
    }

    @Test
    fun pubkeyServerErrorIsAServerError() = runBlocking {
        val s = FakeTayanchServer()
        val c = client(s, s.engine(pubkey = { with(s) { plain(HttpStatusCode.BadGateway, "{}") } }) { error("must not upload") })
        val e = errorOf(c.analyzeImage(img))
        assertTrue(e is ApiError.ServerError, "got $e")
    }

    @Test
    fun aSealedSuccessStillDecodes() = runBlocking {
        val s = FakeTayanchServer()
        val c = client(s, s.engine { req -> with(s) { sealed(req, HttpStatusCode.OK, FakeTayanchServer.REAL_BODY) } })
        val r = c.analyzeImage(img)
        assertEquals("real", r.getOrThrow().verdict)
    }
}
