package com.myapplication.common.data

import com.myapplication.common.secure.AttestationVerifier
import com.myapplication.common.secure.Bytes
import com.myapplication.common.secure.Classification
import com.myapplication.common.secure.HpkePrimitives
import com.myapplication.common.secure.PubkeyDocument
import com.myapplication.common.secure.TierState
import com.myapplication.common.secure.Tse2
import com.myapplication.common.secure.Tse2Exception
import com.myapplication.common.secure.platformHpke
import com.myapplication.common.secure.secureRandomBytes
import io.ktor.client.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.errors.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire DTO for the server's `POST /analyze` JSON response.
 *
 * IMPORTANT: the field names here MUST match `server.py::_run_analysis` /
 * `_run_analysis_v2`. The previous version of this class used invented keys
 * (`isAI`, `confidence`, `heatmap`, `detailedFeatures`) that the server never
 * emits, so kotlinx.serialization threw `MissingFieldException` on EVERY
 * response — which `analyzeImage` swallowed into `Result.failure`, silently
 * dropping the app onto the local heuristic. The server never got used.
 *
 * Server schema (see server.py docstring on `/analyze` + the result dicts):
 *   ai_probability   Double? [0-1]   — calibrated P(AI); null on a bad-image error path
 *   conclusion       String?         — "AI-Generated" | "REAL" | null
 *   verdict          String?         — "ai" | "real" | "uncertain" | "tampered" | "error"
 *   confidence       Double?         — additive v2 field; may be absent on legacy backend
 *   heatmap_base64   String?         — base64 PNG, or null
 *   inference_ms     Double?
 *   model_type       String?
 *   detector_version String?
 *   error            String?         — present only on the graceful error schema
 *   error_code       String?
 *
 * Every field is nullable with a default so a partial or legacy-backend
 * payload still deserializes instead of throwing. We then project to the
 * UI-friendly [isAI] / [uiConfidence] in code, where we control the fallbacks.
 *
 * Since TSE2 this JSON arrives INSIDE the sealed response envelope
 * (`application/vnd.tayanch.secure+v2`); the fields are unchanged.
 */
@Serializable
data class ApiAnalysisResult(
    @SerialName("ai_probability") val aiProbability: Double? = null,
    @SerialName("conclusion") val conclusion: String? = null,
    @SerialName("verdict") val verdict: String? = null,
    @SerialName("confidence") val confidence: Double? = null,
    @SerialName("heatmap_base64") val heatmapBase64: String? = null,
    @SerialName("inference_ms") val inferenceMs: Double? = null,
    @SerialName("model_type") val modelType: String? = null,
    @SerialName("detector_version") val detectorVersion: String? = null,
    @SerialName("verdict_band") val verdictBand: String? = null,
    // Human-readable reason when the server abstains or qualifies the call
    // (e.g. "image looks heavily processed — share the original file").
    @SerialName("detail") val detail: String? = null,
    // "content" (model verdict) | "provenance" (signed C2PA/metadata match).
    @SerialName("method") val method: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
) {
    /**
     * The calibrated three-band verdict for the UI. Trusts the server's own
     * 3-way `verdict` ("ai"/"real"/"uncertain"/"tampered"); falls back to
     * `conclusion` / `ai_probability` (with an uncertain band around
     * [threshold]) only for a legacy backend that doesn't emit `verdict`.
     * This is what keeps an "uncertain" server call from becoming a false
     * accusation in the app.
     */
    fun toVerdict(threshold: Float = 0.5f): Verdict =
        Verdict.fromServer(verdict, conclusion, aiProbability, threshold)
    /**
     * Binary AI/real call for the UI. Trusts the server's own collapse:
     * `conclusion == "AI-Generated"` (which the v2 backend only emits when
     * verdict=="ai", so an "uncertain" never becomes a false accusation).
     * Falls back to `verdict` when `conclusion` is absent.
     */
    val isAI: Boolean
        get() = when {
            conclusion != null -> conclusion.equals("AI-Generated", ignoreCase = true)
            verdict != null -> verdict.equals("ai", ignoreCase = true)
            else -> false
        }

    /**
     * 0..1 score for the confidence bar. The server's `ai_probability` is the
     * meaningful number; the additive `confidence` field is informational and
     * not always present, so we drive the bar from `ai_probability`.
     */
    val uiConfidence: Float
        get() = (aiProbability ?: confidence ?: 0.0).toFloat().coerceIn(0f, 1f)

    /** True when the server returned its graceful error schema (bad/corrupt image, etc.). */
    val isServerError: Boolean
        get() = error != null || verdict.equals("error", ignoreCase = true)
}

/**
 * Plain-JSON error body the server sends BEFORE it has opened the request
 * envelope (SPEC §3/§4: bad_envelope, key_mismatch, stale_timestamp, replay,
 * auth/quota). By contract it never contains user data. `pubkey` is present
 * on 409 key_mismatch and is re-verified exactly like GET /pubkey.
 */
@Serializable
data class PreOpenError(
    val error: String? = null,
    @SerialName("server_time") val serverTime: Long? = null,
    val pubkey: PubkeyDocument? = null,
)

/**
 * Typed analyze failures so the UI can tell the user what actually went wrong
 * instead of the old behaviour: mask EVERY failure behind the local heuristic.
 */
sealed class ApiError(val userMessage: String) {
    /** No connectivity / DNS / TLS / connection refused. */
    object Network : ApiError("Couldn't reach the server. Check your connection and API URL.")
    /** Request exceeded the configured timeout. */
    object Timeout : ApiError("The server took too long to respond. Try again.")
    /** 401/403 — bad or missing API key. */
    object Auth : ApiError("Authentication failed. Check your API key in Settings.")
    /** 4xx other than auth (bad request, unsupported media, etc.). */
    class ClientError(val status: Int, val serverMessage: String?) :
        ApiError(serverMessage ?: "The server rejected the request ($status).")
    /** 5xx, or the server's graceful error schema. */
    class ServerError(val status: Int, val serverMessage: String?) :
        ApiError(serverMessage ?: "The server failed to analyze the image ($status).")
    /** Response body didn't match the expected schema. */
    class Parse(val detail: String?) :
        ApiError("Couldn't read the server's response (unexpected format).")
    /** URL not configured / cleartext-on-public-host etc. */
    class Configuration(val detail: String) : ApiError(detail)
    /** Anything we didn't anticipate. */
    class Unknown(val detail: String?) : ApiError("Analysis failed unexpectedly.")

    // ---- TSE2 / attestation (SPEC §6): none of these ever fall back to plaintext ----

    /** Tier classification FAILED (evidence invalid/expired/unverifiable, or no TSE2 key). Nothing was sent. */
    class Attestation(val state: TierState.Failed) : ApiError(state.userLine)
    /** Server offers only the standard tier and Settings → "Require the attested tier" is on. Nothing was sent. */
    object AttestationRequired : ApiError(
        "This server offers only the standard tier (no attestation) and Settings requires the attested tier. Nothing was sent."
    )
    /** 400 stale_timestamp — the device clock is more than 5 minutes off the server's. */
    class StaleTimestamp(val serverTime: Long?) : ApiError(
        "The server rejected the request time stamp — this device's clock is more than 5 minutes off. Fix the clock and try again."
    )
    /** Two 409 key_mismatch answers in a row: a rolling deploy or key-epoch race. */
    object KeyRotation : ApiError("The server rotated its key during the upload. Try again in a moment.")
    /** The server answered a sealed request outside the protocol (e.g. plaintext body). Nothing was decoded. */
    class Protocol(val detail: String) : ApiError("The server broke the secure-envelope protocol ($detail). Nothing was decoded.")
    /** This device could not build the envelope (missing iOS bridge, malformed key, ...). Nothing was sent. */
    class Envelope(val detail: String) : ApiError("Could not build the secure envelope on this device ($detail). Nothing was sent.")
}

/** Carries an [ApiError] through `Result.failure`. */
class ApiException(val apiError: ApiError) : Exception(apiError.userMessage)

/**
 * HTTP client for the detector API.
 *
 * Every upload is a TSE2 envelope (`secure/SPEC.md`): the client fetches a
 * fresh `/pubkey` document bound to a random nonce immediately before each
 * upload, classifies it into exactly one [TierState], seals the image to the
 * advertised key with route "/analyze" in the AAD, and opens the sealed
 * reply. There is NO plaintext upload path any more and NO plaintext reply
 * path: a 2xx that is not `application/vnd.tayanch.secure+v2` is a protocol
 * error, not data.
 *
 * What the envelope guarantees depends on the tier (the UI shows the line):
 *  - UNATTESTED (backend "none"): the operator and its host can technically
 *    read the file during analysis. Proceeds unless the user required attestation.
 *  - VERIFIED: evidence matched the pinned policy. Only then may stronger copy be shown.
 *  - FAILED: hard stop, nothing is sent.
 *
 * [engine] and [verifier] are injectable for tests (Ktor MockEngine, fake JWKS).
 */
class ApiClient(
    private val settings: AppSettings,
    private val hpke: HpkePrimitives = platformHpke(),
    verifier: AttestationVerifier? = null,
    engine: HttpClientEngine? = null,
) {
    /** Called with every classification, on the caller's dispatcher; the UI shows exactly one state. */
    var onTierState: ((TierState) -> Unit)? = null

    private val json = Json {
        prettyPrint = false
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val httpClient = if (engine != null) {
        HttpClient(engine) { configure() }
    } else {
        HttpClient { configure() }
    }

    private fun HttpClientConfig<*>.configure() {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout)
        // Do NOT throw on non-2xx by default; we inspect the status ourselves
        // so we can map it to a typed ApiError. (expectSuccess stays false.)
        expectSuccess = false
    }

    private val attestation: AttestationVerifier =
        verifier ?: AttestationVerifier(fetchJwks = { url -> fetchText(url) })

    /** Plain GET of a pinned-URL text document (the JWKS). Never carries the API key. */
    private suspend fun fetchText(url: String): String {
        val response = httpClient.get(url) { timeout { requestTimeoutMillis = JWKS_TIMEOUT_MS } }
        if (response.status.value !in 200..299) throw IllegalStateException("HTTP ${response.status.value}")
        return response.bodyAsText()
    }

    /** Trim trailing slash so "${baseUrl}/analyze" doesn't become "host//analyze". */
    private val baseUrl: String get() = settings.apiBaseUrl.trim().trimEnd('/')

    /** Reject early if the user hasn't configured a valid URL. */
    private fun ensureUrl() {
        check(settings.isApiUrlAcceptable()) {
            "API base URL is not configured or uses cleartext on a public host. " +
            "Open Settings → API URL and set an https:// endpoint."
        }
    }

    /** Add the X-API-Key header when an api key has been configured. */
    private fun HttpRequestBuilder.applyAuth() {
        val key = settings.apiKey.trim()
        if (key.isNotEmpty()) header("X-API-Key", key)
    }

    /**
     * The multipart field stays `image` (server contract); the part carries the
     * TSE2 envelope, not pixels, so it is labelled as such. The server keys off
     * the `TYNSEC\x02\x00` magic, not the filename.
     */
    private fun envelopePart(envelope: ByteArray) = MultiPartFormDataContent(
        formData {
            append("image", envelope, Headers.build {
                append(HttpHeaders.ContentType, SECURE_V2)
                append(HttpHeaders.ContentDisposition, "filename=\"image.tse2\"")
            })
        }
    )

    suspend fun analyzeImage(imageData: ByteArray): Result<ApiAnalysisResult> =
        runAnalyze(imageData, includeHeatmap = null)

    suspend fun analyzeImageWithHeatmap(
        imageData: ByteArray,
        includeHeatmap: Boolean = true
    ): Result<ApiAnalysisResult> =
        runAnalyze(imageData, includeHeatmap = includeHeatmap)

    /**
     * Shared sealed POST /analyze path. Captures the [HttpResponse] (rather than
     * letting `.body()` auto-throw) so we can map status -> [ApiError]; the body
     * is opened ONLY on a 2xx with the sealed content type. On any failure
     * returns `Result.failure(ApiException(...))`.
     */
    private suspend fun runAnalyze(
        imageData: ByteArray,
        includeHeatmap: Boolean?,
    ): Result<ApiAnalysisResult> {
        // Configuration errors are deterministic and worth surfacing precisely.
        try {
            ensureUrl()
        } catch (e: IllegalStateException) {
            return Result.failure(
                ApiException(ApiError.Configuration(e.message ?: "API URL not configured."))
            )
        }

        return try {
            analyzeSealed(imageData, includeHeatmap)
        } catch (e: HttpRequestTimeoutException) {
            Result.failure(ApiException(ApiError.Timeout))
        } catch (e: IOException) {
            Result.failure(ApiException(ApiError.Network))
        } catch (e: ApiException) {
            Result.failure(e)
        } catch (e: Exception) {
            // Unknown — do NOT leak the raw message to the UI; keep it generic.
            Result.failure(ApiException(ApiError.Unknown(e.message)))
        }
    }

    private suspend fun analyzeSealed(imageData: ByteArray, includeHeatmap: Boolean?): Result<ApiAnalysisResult> {
        // 1. Fresh key + evidence bound to a fresh 32-byte nonce, fetched immediately
        //    before the upload (SPEC §5). The same nonce goes in X-Tayanch-Nonce so a
        //    409 body can carry evidence bound to it.
        val nonceHex = Bytes.toHex(secureRandomBytes(32))
        var classification = classify(fetchPubkeyDocument(nonceHex), nonceHex)
        gate(classification)?.let { return Result.failure(ApiException(it)) }

        var retriedAfterKeyMismatch = false
        while (true) {
            val key = classification.key
                ?: return Result.failure(ApiException(ApiError.Attestation(TierState.Failed("no usable server key"))))

            // 2. Seal. Route is the HTTP path only (no query string), per SPEC §2.
            val sealed = try {
                Tse2.sealRequest(hpke, key.pubkey, key.kid, ROUTE_ANALYZE, imageData)
            } catch (e: Exception) {
                return Result.failure(ApiException(ApiError.Envelope(e.message ?: "seal failed")))
            }

            try {
                val response: HttpResponse = httpClient.post("$baseUrl$ROUTE_ANALYZE") {
                    applyAuth()
                    header(HEADER_NONCE, nonceHex)
                    accept(ContentType.parse(SECURE_V2))
                    accept(ContentType.Application.Json)
                    timeout { requestTimeoutMillis = settings.apiTimeout }
                    if (includeHeatmap != null) parameter("heatmap", includeHeatmap)
                    setBody(envelopePart(sealed.envelope))
                }
                val status = response.status.value

                if (status in 200..299) {
                    // 3. The reply to a sealed request MUST be sealed (SPEC §3). A plaintext
                    //    2xx is the exact downgrade the design review flagged — refuse it.
                    if (!isSecureV2(response.contentType())) {
                        return Result.failure(ApiException(ApiError.Protocol("plaintext reply to a sealed request")))
                    }
                    val plain = try {
                        Tse2.openResponse(hpke, sealed.state, response.readBytes())
                    } catch (e: Tse2Exception) {
                        return Result.failure(ApiException(ApiError.Protocol(e.message ?: "response could not be opened")))
                    }
                    val parsed = try {
                        json.decodeFromString<ApiAnalysisResult>(plain.decodeToString())
                    } catch (e: Exception) {
                        return Result.failure(ApiException(ApiError.Parse(null)))
                    }
                    // A sealed 200 can still carry the server's graceful error schema
                    // (decode failure, model error) — those are sealed like a success.
                    return if (parsed.isServerError) {
                        Result.failure(ApiException(ApiError.ServerError(status, parsed.error)))
                    } else {
                        Result.success(parsed)
                    }
                }

                // 4. Pre-open errors: plain JSON, no user data (SPEC §3).
                val err = preOpenErrorOf(response)
                when {
                    status == 409 && err?.error == "key_mismatch" -> {
                        val doc = err.pubkey
                        if (retriedAfterKeyMismatch || doc == null) {
                            return Result.failure(ApiException(ApiError.KeyRotation))
                        }
                        retriedAfterKeyMismatch = true
                        // The 409 body is NOT trusted on its own: re-run the identical
                        // verifier against the same nonce, then re-seal and retry ONCE.
                        classification = classify(doc, nonceHex)
                        gate(classification)?.let { return Result.failure(ApiException(it)) }
                        continue
                    }
                    status == 409 && err?.error == "replay" ->
                        return Result.failure(ApiException(ApiError.ClientError(status, "The server already saw this envelope (replay). Try again.")))
                    status == 400 && err?.error == "stale_timestamp" ->
                        return Result.failure(ApiException(ApiError.StaleTimestamp(err.serverTime)))
                    status == 401 || status == 403 ->
                        return Result.failure(ApiException(ApiError.Auth))
                    status in 400..499 ->
                        return Result.failure(ApiException(ApiError.ClientError(status, err?.error)))
                    else ->
                        return Result.failure(ApiException(ApiError.ServerError(status, err?.error)))
                }
            } finally {
                sealed.state.wipe()
            }
        }
    }

    /** GET /pubkey?nonce=…; null when the server does not offer a v2 key document. */
    private suspend fun fetchPubkeyDocument(nonceHex: String): PubkeyDocument? {
        val response = httpClient.get("$baseUrl$ROUTE_PUBKEY") {
            applyAuth()
            parameter("nonce", nonceHex)
            timeout { requestTimeoutMillis = PUBKEY_TIMEOUT_MS }
        }
        if (response.status.value !in 200..299) return null
        return try {
            json.decodeFromString<PubkeyDocument>(response.bodyAsText())
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun classify(doc: PubkeyDocument?, nonceHex: String): Classification {
        val c = if (doc == null) {
            Classification(TierState.Failed("server did not offer a TSE2 key document"), null)
        } else {
            attestation.classify(doc, nonceHex)
        }
        onTierState?.invoke(c.state)
        return c
    }

    /** SPEC §6 gate: FAILED always stops; UNATTESTED stops only when the user required attestation. */
    private fun gate(c: Classification): ApiError? = when (val s = c.state) {
        is TierState.Failed -> ApiError.Attestation(s)
        TierState.Unattested -> if (settings.requireAttestation) ApiError.AttestationRequired else null
        is TierState.Verified -> null
    }

    private fun isSecureV2(ct: ContentType?): Boolean =
        ct != null && ct.contentType.equals("application", ignoreCase = true) &&
            ct.contentSubtype.equals("vnd.tayanch.secure+v2", ignoreCase = true)

    /** Best-effort read of the pre-open JSON error from a non-2xx body. Never throws. */
    private suspend fun preOpenErrorOf(response: HttpResponse): PreOpenError? = try {
        val text = response.bodyAsText()
        if (text.isBlank()) null else json.decodeFromString<PreOpenError>(text)
    } catch (e: Exception) {
        null
    }

    suspend fun healthCheck(): Result<Unit> {
        try {
            ensureUrl()
        } catch (e: IllegalStateException) {
            return Result.failure(
                ApiException(ApiError.Configuration(e.message ?: "API URL not configured."))
            )
        }
        return try {
            val response = httpClient.get("$baseUrl/health") {
                applyAuth()
                timeout { requestTimeoutMillis = 5000 }
            }
            when (response.status.value) {
                in 200..299 -> Result.success(Unit)
                401, 403 -> Result.failure(ApiException(ApiError.Auth))
                in 400..499 -> Result.failure(
                    ApiException(ApiError.ClientError(response.status.value, null))
                )
                else -> Result.failure(
                    ApiException(ApiError.ServerError(response.status.value, null))
                )
            }
        } catch (e: HttpRequestTimeoutException) {
            Result.failure(ApiException(ApiError.Timeout))
        } catch (e: IOException) {
            Result.failure(ApiException(ApiError.Network))
        } catch (e: Exception) {
            Result.failure(ApiException(ApiError.Unknown(e.message)))
        }
    }

    fun close() {
        httpClient.close()
    }

    companion object {
        const val ROUTE_ANALYZE = "/analyze"
        const val ROUTE_PUBKEY = "/pubkey"
        const val HEADER_NONCE = "X-Tayanch-Nonce"
        const val SECURE_V2 = "application/vnd.tayanch.secure+v2"
        private const val PUBKEY_TIMEOUT_MS = 10_000L
        private const val JWKS_TIMEOUT_MS = 10_000L
    }
}
