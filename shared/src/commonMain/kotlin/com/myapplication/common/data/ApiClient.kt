package com.myapplication.common.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

@Serializable
data class ApiAnalysisResult(
    val isAI: Boolean,
    val confidence: Float,
    val heatmap: String? = null, // Base64 encoded heatmap image
    val detailedFeatures: Map<String, Float> = emptyMap()
)

class ApiClient(private val settings: AppSettings) {
    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = false
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
        install(HttpTimeout)
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

    suspend fun analyzeImage(imageData: ByteArray): Result<ApiAnalysisResult> {
        return try {
            ensureUrl()
            val response: ApiAnalysisResult = httpClient.post("$baseUrl/analyze") {
                applyAuth()
                timeout { requestTimeoutMillis = settings.apiTimeout }
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("image", imageData, Headers.build {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"image.jpg\"")
                            })
                        }
                    )
                )
            }.body()
            Result.success(response)
        } catch (e: Exception) {
            // Log via plugin in production — never leak to user UI.
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun analyzeImageWithHeatmap(
        imageData: ByteArray,
        includeHeatmap: Boolean = true
    ): Result<ApiAnalysisResult> {
        return try {
            ensureUrl()
            val response: ApiAnalysisResult = httpClient.post("$baseUrl/analyze") {
                applyAuth()
                timeout { requestTimeoutMillis = settings.apiTimeout }
                parameter("heatmap", includeHeatmap)
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("image", imageData, Headers.build {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"image.jpg\"")
                            })
                        }
                    )
                )
            }.body()
            Result.success(response)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun healthCheck(): Result<Unit> {
        return try {
            ensureUrl()
            httpClient.get("$baseUrl/health") {
                applyAuth()
                timeout { requestTimeoutMillis = 5000 }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        httpClient.close()
    }
}
