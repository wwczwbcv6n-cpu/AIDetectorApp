package com.myapplication.common.data

import io.ktor.client.*
import io.ktor.client.call.*
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
}

/** Carries an [ApiError] through `Result.failure`. */
class ApiException(val apiError: ApiError) : Exception(apiError.userMessage)

class ApiClient(private val settings: AppSettings) {
    private val json = Json {
        prettyPrint = false
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout)
        // Do NOT throw on non-2xx by default; we inspect the status ourselves
        // so we can map it to a typed ApiError. (expectSuccess stays false.)
        expectSuccess = false
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

    private fun imagePart(imageData: ByteArray) = MultiPartFormDataContent(
        formData {
            append("image", imageData, Headers.build {
                append(HttpHeaders.ContentType, "image/jpeg")
                append(HttpHeaders.ContentDisposition, "filename=\"image.jpg\"")
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
     * Shared POST /analyze path. Captures the [HttpResponse] (rather than letting
     * `.body()` auto-throw) so we can map status -> [ApiError], and we deserialize
     * the body ONLY on a 2xx — surfacing parse failures distinctly from transport
     * failures. On any failure returns `Result.failure(ApiException(...))`.
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
            val response: HttpResponse = httpClient.post("$baseUrl/analyze") {
                applyAuth()
                timeout { requestTimeoutMillis = settings.apiTimeout }
                if (includeHeatmap != null) parameter("heatmap", includeHeatmap)
                setBody(imagePart(imageData))
            }

            when (response.status.value) {
                in 200..299 -> {
                    val parsed = try {
                        response.body<ApiAnalysisResult>()
                    } catch (e: Exception) {
                        return Result.failure(ApiException(ApiError.Parse(e.message)))
                    }
                    // 200 can still carry the server's graceful error schema
                    // (e.g. corrupt image -> ai_probability null + error set).
                    if (parsed.isServerError) {
                        Result.failure(
                            ApiException(ApiError.ServerError(response.status.value, parsed.error))
                        )
                    } else {
                        Result.success(parsed)
                    }
                }
                401, 403 -> Result.failure(ApiException(ApiError.Auth))
                in 400..499 -> Result.failure(
                    ApiException(
                        ApiError.ClientError(response.status.value, errorMessageOf(response))
                    )
                )
                else -> Result.failure(
                    ApiException(
                        ApiError.ServerError(response.status.value, errorMessageOf(response))
                    )
                )
            }
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

    /** Best-effort read of `{"error": ...}` from a non-2xx body. Never throws. */
    private suspend fun errorMessageOf(response: HttpResponse): String? = try {
        val text = response.bodyAsText()
        if (text.isBlank()) null
        else json.decodeFromString<ApiAnalysisResult>(text).error
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
}
