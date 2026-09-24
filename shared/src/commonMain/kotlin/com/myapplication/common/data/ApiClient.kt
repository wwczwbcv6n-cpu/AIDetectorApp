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
import com.myapplication.common.nowMillis
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `POST /analyze` -> `degradation`: how "laundered" the upload looked
 * (screenshot geometry, messenger-grade recompression, stripped EXIF).
 * Content path only; absent on the provenance path.
 */
@Serializable
data class ApiDegradation(
    @SerialName("level") val level: String? = null,          // "none" | "moderate" | "heavy"
    @SerialName("reasons") val reasons: List<String>? = null,
)

/**
 * `POST /analyze` -> `localized_edit`: the additive edit screen — a FLAG for
 * review, never an accusation (it demotes real -> uncertain only). Present only
 * when the screen ran; `localized` only when it fired. Extra server keys
 * (`edited_score`, `mask_area`, `bbox`, `clean_fpr`) are ignored.
 */
@Serializable
data class ApiLocalizedEdit(
    @SerialName("fires") val fires: Boolean? = null,
    @SerialName("localized") val localized: Boolean? = null,   // true: POST /heatmap has a region to show
    @SerialName("score") val score: Double? = null,
    @SerialName("thr") val thr: Double? = null,
)

/** One model's read inside [ApiMix] (`reads`: ai / real / unclear / flag / clear / ...). */
@Serializable
data class ApiMixSignal(
    @SerialName("name") val name: String? = null,
    @SerialName("reads") val reads: String? = null,
    @SerialName("in_share") val inShare: Boolean? = null,
)

/**
 * `POST /analyze` -> `mix` (2026-09-20, demo_api._mix_block): present on an
 * `uncertain` verdict that measured something, with label "Mixed signals".
 * `ai_share` / `real_share` place each model's read on ITS OWN calibrated
 * band; they are not a probability. Other keys (`parts`, `kind`, per-signal
 * `p_ai` / `band` / `pos`) are ignored by the app.
 */
@Serializable
data class ApiMix(
    @SerialName("ai_share") val aiShare: Double? = null,
    @SerialName("real_share") val realShare: Double? = null,
    @SerialName("advisory") val advisory: Boolean? = null,
    @SerialName("basis") val basis: String? = null,
    @SerialName("signals") val signals: List<ApiMixSignal>? = null,
) {
    /** The display summary, or null when the block carries no share. */
    fun toSummary(): MixSummary? {
        val share = aiShare?.takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f, 1f) ?: return null
        return MixSummary(
            aiShare = share,
            advisory = advisory == true,
            basis = basis,
            signals = signals.orEmpty().mapNotNull { sg ->
                val n = sg.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                n to (sg.reads ?: "unclear")
            },
        )
    }
}

/** What the result card shows of a `mix` block (also rebuilt from history). */
data class MixSummary(
    val aiShare: Float,
    val advisory: Boolean = false,
    val basis: String? = null,
    val signals: List<Pair<String, String>> = emptyList(),
)

/**
 * Wire DTO for the served `POST /analyze` JSON response (`demo_api.py`, the
 * Tayanch API). The key set is pinned by the parent repo's
 * `contract/api_v2.json`; `tests/test_client_contract.py` there asserts that
 * every key this class reads is one the server actually sends.
 *
 * History: the previous version read six keys of the legacy `server.py`
 * schema (an inline base64 heat map, an inference-time field, a model-type /
 * detector-version pair, a verdict band and an error code) that the served
 * API never emits (audit 2026-09-18, site_app.md §2 bug 1). With
 * `ignoreUnknownKeys` they decoded silently to null, so the model name, the
 * timing and the heat map were always empty in the app.
 *
 * Served schema (variants "200 content", "200 content+edit_screen",
 * "200 provenance"; a 400 carries only `error`):
 *   verdict              String   "ai" | "real" | "uncertain" — the 3-band source of truth
 *   label                String   "Likely AI-generated" / "Uncertain — possible local edit" / ...
 *   confidence           Number   0..100 PERCENT on the site's scale (NOT a probability;
 *                                 for a "real" verdict it is confidence in REAL); 0 on uncertain
 *   p_ai                 Number   0..1 calibrated P(AI)
 *   ai_probability       Number   alias of p_ai, emitted for this DTO
 *   conclusion           String   "AI-Generated" | "REAL" | "UNCERTAIN"
 *   detail               String?  abstain reason / edit note / provenance note, or null
 *   method               String   "content" | "provenance"
 *   model                String   name of the served head
 *   elapsed_ms           Integer  server-side wall time in ms
 *   heatmap_token        String   opaque, short-lived; presented back to POST /heatmap
 *   preview              String?  data-URL thumbnail (dropped when the client sends preview=0)
 *   degradation          Object?  [ApiDegradation] — content path only
 *   localized_edit       Object?  [ApiLocalizedEdit] — only when the edit screen ran
 *   provenance_kind      String?  "c2pa_manifest" | "metadata_tag" — provenance path only
 *   provenance_verified  Boolean? false: the marker was detected, no signature was validated
 *   mix                  Object?  [ApiMix] — uncertain verdicts that measured something ("Mixed signals")
 *   error                String?  the graceful error body ("no_file")
 *
 * Every field is nullable with a default so an optional-key or partial body
 * still decodes, and the client's Json keeps `ignoreUnknownKeys = true` so
 * keys the server adds later (`union_candidate`, `feature_mode`, `degraded`,
 * ...) never crash the app. [isAI] / [uiConfidence] are projected in code.
 *
 * Since TSE2 this JSON arrives INSIDE the sealed response envelope
 * (`application/vnd.tayanch.secure+v2`); the fields are unchanged.
 *
 * HEAT MAP: /analyze never inlines one. It is a SECOND sealed call,
 * `POST /heatmap` — multipart with the image envelope plus the form fields
 * `token` = [heatmapToken], `gate` (0..1 overlay strength) and optionally
 * `verdict` / `p_ai` — answering `{heatmap: data-URL | null, zoom?, method,
 * gate?, reason?, error?}` (410 `token_expired` when the token lapsed). That is
 * what site/demo.js `addHeatmapRow()` does; the app does not wire it yet.
 */
@Serializable
data class ApiAnalysisResult(
    @SerialName("verdict") val verdict: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("confidence") val confidence: Double? = null,
    @SerialName("p_ai") val pAi: Double? = null,
    @SerialName("ai_probability") val aiProbability: Double? = null,
    @SerialName("conclusion") val conclusion: String? = null,
    // Human-readable reason when the server abstains or qualifies the call
    // (e.g. "image looks heavily processed — share the original file").
    @SerialName("detail") val detail: String? = null,
    // "content" (model verdict) | "provenance" (signed C2PA/metadata match).
    @SerialName("method") val method: String? = null,
    @SerialName("model") val model: String? = null,
    @SerialName("elapsed_ms") val elapsedMs: Long? = null,
    @SerialName("heatmap_token") val heatmapToken: String? = null,
    @SerialName("preview") val preview: String? = null,
    @SerialName("degradation") val degradation: ApiDegradation? = null,
    @SerialName("localized_edit") val localizedEdit: ApiLocalizedEdit? = null,
    @SerialName("provenance_kind") val provenanceKind: String? = null,
    @SerialName("provenance_verified") val provenanceVerified: Boolean? = null,
    @SerialName("mix") val mix: ApiMix? = null,
    @SerialName("error") val error: String? = null,
) {
    /**
     * The calibrated three-band verdict for the UI. Trusts the server's own
     * 3-way `verdict` ("ai"/"real"/"uncertain"); falls back to
     * `conclusion` / `ai_probability` (with an uncertain band around
     * [threshold]) only for a backend that doesn't emit `verdict`.
     * This is what keeps an "uncertain" server call from becoming a false
     * accusation in the app.
     */
    fun toVerdict(threshold: Float = 0.5f): Verdict =
        Verdict.fromServer(verdict, conclusion, aiProbability ?: pAi, threshold)

    /**
     * Binary AI/real call for the UI. Trusts the server's own collapse:
     * `conclusion == "AI-Generated"` (which the backend only emits when
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
     * 0..1 P(AI): `ai_probability` (alias of `p_ai`). NEVER DISPLAYED: the
     * served head's uncertain band is 0.20 <= p_ai < 0.99, so showing it read
     * as an accusation on Uncertain cards (audit 2026-09-24 APP-02). The card
     * shows [label], `confidence` on ai/real and the [mix] shares instead
     * (ui/ResultPresentation.kt). Kept for the legacy history field only.
     * `confidence` is deliberately NOT a fallback — it is a 0..100 percent on
     * the site's scale and, for a "real" verdict, confidence in REAL; clamping
     * it into 0..1 would paint every real photo as 100% AI.
     */
    val uiConfidence: Float
        get() = (aiProbability ?: pAi ?: 0.0).toFloat().coerceIn(0f, 1f)

    /**
     * The edit screen flagged a possible local edit AND the localizer found a
     * region, i.e. POST /heatmap has an "area to check" to show. Mirrors
     * site/demo.js: `localized_edit.fires && localized_edit.localized === true`.
     */
    val editLocalized: Boolean
        get() = localizedEdit?.fires == true && localizedEdit?.localized == true

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
    /**
     * 401/403 — bad, missing, revoked or expired API key. [serverMessage] is
     * the gateway's own text ("missing API key — get a free key at
     * https://tayanch.com/api", "API key revoked", ...), shown as is (APP-08).
     */
    class Auth(val serverMessage: String? = null) : ApiError(
        serverMessage?.takeIf { it.isNotBlank() }?.let { "The server refused the API key: $it" }
            ?: "Authentication failed. Check your API key in Settings."
    )
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
    private val backoff: Backoff = Backoff(),
) {
    /**
     * Cold-start / busy tolerance (audit 2026-09-24 APP-09). The served API
     * scales to zero (deploy/modal_app.py min_containers=0) and runs one
     * container (max_containers=1). Before the first upload after
     * [warmForMs] of silence the client waits on GET /health for up to
     * [healthBudgetMs] (the site's budget, site/demo.js), backing off from
     * [firstDelayMs]; a busy / bad-gateway /analyze answer is retried ONCE
     * after [retryDelayMs] with a fresh key and envelope.
     * The 60 s budget mirrors the site; the Modal cold-start time to the
     * first /pubkey answer has not been measured yet (verifier note).
     */
    class Backoff(
        val healthBudgetMs: Long = 60_000L,
        val firstDelayMs: Long = 1_000L,
        val retryDelayMs: Long = 2_000L,
        val warmForMs: Long = 5 * 60_000L,
    )

    /** Until when the server counts as warm (nowMillis); 0 = never checked. */
    private var warmUntil = 0L

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

    /**
     * Sealed POST /analyze. There is no `?heatmap=` variant any more: the served
     * API ignored that query parameter and never inlines a heat map — the map
     * is a second sealed call to POST /heatmap with the returned
     * [ApiAnalysisResult.heatmapToken] (see the DTO doc; not wired yet).
     */
    suspend fun analyzeImage(imageData: ByteArray): Result<ApiAnalysisResult> =
        runAnalyze(imageData)

    /**
     * Shared sealed POST /analyze path. Captures the [HttpResponse] (rather than
     * letting `.body()` auto-throw) so we can map status -> [ApiError]; the body
     * is opened ONLY on a 2xx with the sealed content type. On any failure
     * returns `Result.failure(ApiException(...))`.
     */
    private suspend fun runAnalyze(imageData: ByteArray): Result<ApiAnalysisResult> {
        // Configuration errors are deterministic and worth surfacing precisely.
        try {
            ensureUrl()
        } catch (e: IllegalStateException) {
            return Result.failure(
                ApiException(ApiError.Configuration(e.message ?: "API URL not configured."))
            )
        }

        // Refuse an oversized file before sealing (a second full copy) or
        // sending it: the server would answer 413 after the upload (APP-07).
        uploadTooLargeMessage(imageData.size.toLong())?.let { msg ->
            return Result.failure(ApiException(ApiError.ClientError(413, msg)))
        }

        var retried = false
        while (true) {
            val result = try {
                warmUp()
                analyzeSealed(imageData)
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
            val err = (result.exceptionOrNull() as? ApiException)?.apiError
            if (!retried && err is ApiError.ServerError && isRetryable(err)) {
                // Rejected before any work was done (gateway slot full, or the
                // proxy lost the container): one more try, re-keyed and re-sealed.
                retried = true
                warmUntil = 0L
                delay(backoff.retryDelayMs)
                continue
            }
            return result
        }
    }

    /**
     * 503 'server busy — retry in a moment' (the gateway's concurrency slot;
     * nothing was billed) and 502 (the proxy lost the container). NOT 503
     * 'daily capacity' (retrying cannot help) and NOT 504 (the server may
     * still finish — and bill — the first request).
     */
    private fun isRetryable(e: ApiError.ServerError): Boolean =
        e.status == 502 || (e.status == 503 && e.serverMessage?.contains("busy", ignoreCase = true) == true)

    /**
     * Wait for a scaled-to-zero server (APP-09): GET /health until it answers
     * 2xx, backing off on 502/503/504 or a timed-out attempt for up to
     * [Backoff.healthBudgetMs]. Connection errors get two quick retries only
     * (an offline phone should hear "Not analyzed" in seconds, not a minute).
     * Any other answer ends the wait; the /pubkey step then reports it.
     */
    private suspend fun warmUp() {
        if (nowMillis() < warmUntil) return
        val start = nowMillis()
        var wait = backoff.firstDelayMs
        var ioFailures = 0
        while (true) {
            val again: Boolean = try {
                val r = httpClient.get("$baseUrl/health") {
                    timeout { requestTimeoutMillis = HEALTH_ATTEMPT_MS }
                }
                when (r.status.value) {
                    in 200..299 -> {
                        warmUntil = nowMillis() + backoff.warmForMs
                        return
                    }
                    502, 503, 504 -> true
                    else -> return
                }
            } catch (e: HttpRequestTimeoutException) {
                true
            } catch (e: IOException) {
                ++ioFailures <= 2
            }
            if (!again || nowMillis() - start + wait > backoff.healthBudgetMs) return
            delay(wait)
            wait = (wait * 2).coerceAtMost(8_000L)
        }
    }

    /**
     * /analyze timeout: the analysis budget plus the upload at a slow
     * 256 kbit/s uplink (32 bytes/ms). A fixed total used to cover both, so
     * a 10 MB original on a ~1 Mbit/s uplink could never finish (APP-09).
     */
    fun uploadTimeoutMs(bytes: Long): Long = settings.effectiveTimeoutMs + bytes.coerceAtLeast(0L) / 32L

    private suspend fun analyzeSealed(imageData: ByteArray): Result<ApiAnalysisResult> {
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
                    timeout { requestTimeoutMillis = uploadTimeoutMs(sealed.envelope.size.toLong()) }
                    // No query string: the route in the envelope AAD is the bare
                    // path (SPEC §2), and the server reads no /analyze query params.
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

                // 4a. Post-open errors: once the server opened the envelope it
                //     seals EVERY JSON reply (secure_api.py), errors included —
                //     415 'unreadable image', 400 'image too large (...)', 500.
                //     Open them with this request's state so the user sees the
                //     server's reason, not "rejected the request (415)" (APP-08).
                if (isSecureV2(response.contentType())) {
                    val plain = try {
                        Tse2.openResponse(hpke, sealed.state, response.readBytes())
                    } catch (e: Tse2Exception) {
                        return Result.failure(ApiException(ApiError.Protocol(e.message ?: "error reply could not be opened")))
                    }
                    val reason = try {
                        json.decodeFromString<PreOpenError>(plain.decodeToString()).error
                    } catch (e: Exception) {
                        null
                    }
                    return Result.failure(ApiException(errorForStatus(status, reason)))
                }

                // 4b. Pre-open errors: plain JSON, no user data (SPEC §3).
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
                    else ->
                        return Result.failure(ApiException(errorForStatus(status, err?.error)))
                }
            } finally {
                sealed.state.wipe()
            }
        }
    }

    /** A non-2xx status + the server's `error` text -> the typed error the user sees. */
    private fun errorForStatus(status: Int, reason: String?): ApiError = when (status) {
        401, 403 -> ApiError.Auth(reason)
        in 400..499 -> ApiError.ClientError(status, reason)
        else -> ApiError.ServerError(status, reason)
    }

    /**
     * GET /pubkey?nonce=…; null when the server answered a JSON document that
     * is not a usable v2 key (-> TierState.Failed, nothing is sent).
     *
     * A server that did not answer /pubkey at all is NOT an attestation
     * failure (audit 2026-09-24 APP-08): an HTTP error or a non-JSON body
     * (e.g. the website's 404 page because the user typed tayanch.com) throws
     * a Configuration / Auth / ServerError instead, so the user sees "wrong
     * URL" or "server unavailable" rather than "Attestation failed". Nothing
     * is sent in either case.
     */
    private suspend fun fetchPubkeyDocument(nonceHex: String): PubkeyDocument? {
        val response = httpClient.get("$baseUrl$ROUTE_PUBKEY") {
            applyAuth()
            parameter("nonce", nonceHex)
            timeout { requestTimeoutMillis = PUBKEY_TIMEOUT_MS }
        }
        val status = response.status.value
        val text = response.bodyAsText()
        val obj = try {
            json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
        } catch (e: Exception) {
            null
        }
        if (status !in 200..299) {
            val reason = (obj?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
            throw ApiException(when {
                status == 401 || status == 403 -> ApiError.Auth(reason)
                status >= 500 -> ApiError.ServerError(status,
                    "The Tayanch server is unavailable right now (HTTP $status). Try again in a moment.")
                else -> notTayanch("HTTP $status")
            })
        }
        if (obj == null) throw ApiException(notTayanch("not a key document"))
        return try {
            json.decodeFromJsonElement(PubkeyDocument.serializer(), obj)
        } catch (e: Exception) {
            null
        }
    }

    private fun notTayanch(what: String) = ApiError.Configuration(
        "No Tayanch API answered at this URL (/pubkey: $what). " +
            "Check the API URL in Settings — it is the API host, not the website.")

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

    /**
     * Settings -> "Test API Connection" (audit 2026-09-24 APP-04). Two probes,
     * both free and neither billable:
     *  1. GET /health must answer 2xx JSON with a `status` — otherwise this URL
     *     is not a Tayanch API (e.g. the marketing site's 404 page);
     *  2. GET [ROUTE_KEY_PROBE] with the key: that route is outside the
     *     gateway's open paths and not billable, so the gateway authenticates
     *     the key BEFORE the route answers — 401/403 = the key is refused;
     *     the route's own 404 (or any 2xx) = the key passed. /health alone is
     *     an open path and passed with no key or a revoked one.
     * Success carries the line to show; failures are typed [ApiError]s.
     */
    suspend fun testConnection(): Result<String> {
        try {
            ensureUrl()
        } catch (e: IllegalStateException) {
            return Result.failure(ApiException(ApiError.Configuration(e.message ?: "API URL not configured.")))
        }
        return try {
            val health = httpClient.get("$baseUrl/health") {
                timeout { requestTimeoutMillis = PROBE_TIMEOUT_MS }
            }
            val healthOk = health.status.value in 200..299 && try {
                json.parseToJsonElement(health.bodyAsText()).let {
                    it is kotlinx.serialization.json.JsonObject && "status" in it
                }
            } catch (e: Exception) {
                false
            }
            if (!healthOk) {
                return Result.failure(ApiException(ApiError.Configuration(
                    "No Tayanch API answered at this URL (HTTP ${health.status.value}). " +
                        "Check the API URL — it is the API host, not the website.")))
            }
            if (settings.apiKey.isBlank()) {
                return Result.failure(ApiException(ApiError.Configuration(
                    "The server answered, but no API key is set — get a free key at https://tayanch.com/api.")))
            }
            val probe = httpClient.get("$baseUrl$ROUTE_KEY_PROBE") {
                applyAuth()
                timeout { requestTimeoutMillis = PROBE_TIMEOUT_MS }
            }
            when (val st = probe.status.value) {
                401, 403 -> Result.failure(ApiException(ApiError.Auth(preOpenErrorOf(probe)?.error)))
                429 -> Result.failure(ApiException(ApiError.ClientError(st, preOpenErrorOf(probe)?.error)))
                in 200..299, 404, 405 -> Result.success("✓ Connected — the server accepted this API key.")
                else -> Result.failure(ApiException(ApiError.ServerError(st, preOpenErrorOf(probe)?.error)))
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
        private const val PUBKEY_TIMEOUT_MS = 20_000L
        private const val HEALTH_ATTEMPT_MS = 20_000L
        private const val PROBE_TIMEOUT_MS = 15_000L
        /**
         * Key probe for [testConnection]: a served route that is NOT in the
         * gateway's open_paths and NOT billable (demo_api.py create_app), so the
         * gateway checks X-API-Key first and the route itself (admin-token
         * gated) then answers 404. No quota is spent.
         */
        const val ROUTE_KEY_PROBE = "/stats"
        private const val JWKS_TIMEOUT_MS = 10_000L
    }
}
