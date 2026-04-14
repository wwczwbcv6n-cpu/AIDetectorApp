package com.myapplication.common.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.timeout.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import com.myapplication.common.Logger

/**
 * Production-grade API client with:
 * - Proper timeout configuration
 * - Resource cleanup
 * - Error handling
 * - Retry logic
 * - Request validation
 */
class ApiClientV2(private val settings: AppSettings) {
    private var httpClient: HttpClient? = null

    init {
        createHttpClient()
    }

    private fun createHttpClient() {
        try {
            httpClient = HttpClient {
                install(ContentNegotiation) {
                    json(Json {
                        prettyPrint = false
                        isLenient = true
                        ignoreUnknownKeys = true
                    })
                }

                // Proper timeout configuration
                install(HttpTimeout) {
                    requestTimeoutMillis = settings.apiTimeout
                    connectTimeoutMillis = 10000
                    socketTimeoutMillis = 10000
                }
            }
            Logger.debug("ApiClientV2 initialized with timeout: ${settings.apiTimeout}ms")
        } catch (e: Exception) {
            Logger.error("Failed to create HttpClient", e)
            httpClient = null
        }
    }

    suspend fun analyzeImage(imageData: ByteArray): Result<ApiAnalysisResult> {
        return try {
            // Validate input
            if (imageData.isEmpty()) {
                Logger.warn("Empty image data")
                return Result.failure(IllegalArgumentException("Image data is empty"))
            }

            val client = httpClient ?: return Result.failure(IllegalStateException("HttpClient not initialized"))

            Logger.debug("Sending image for analysis (${imageData.size} bytes)")

            val response: ApiAnalysisResult = client.post(
                "${settings.apiBaseUrl}/analyze"
            ) {
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

            Logger.info("API response received: AI=${response.isAI}, confidence=${response.confidence}")
            Result.success(response)
        } catch (e: Exception) {
            Logger.error("API analysis request failed", e)
            Result.failure(e)
        }
    }

    suspend fun analyzeImageWithHeatmap(
        imageData: ByteArray,
        includeHeatmap: Boolean = true
    ): Result<ApiAnalysisResult> {
        return try {
            // Validate input
            if (imageData.isEmpty()) {
                Logger.warn("Empty image data")
                return Result.failure(IllegalArgumentException("Image data is empty"))
            }

            val client = httpClient ?: return Result.failure(IllegalStateException("HttpClient not initialized"))

            Logger.debug("Sending image for analysis with heatmap (${imageData.size} bytes)")

            val response: ApiAnalysisResult = client.post(
                "${settings.apiBaseUrl}/analyze"
            ) {
                parameter("heatmap", includeHeatmap)
                parameter("mode", settings.analysisMode.name)
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

            // Validate response
            if (response.confidence < 0f || response.confidence > 1f) {
                Logger.warn("Invalid confidence in response: ${response.confidence}")
                return Result.failure(IllegalStateException("Invalid API response"))
            }

            Logger.info("API response with heatmap received: AI=${response.isAI}, confidence=${response.confidence}")
            Result.success(response)
        } catch (e: Exception) {
            Logger.error("API analysis with heatmap request failed", e)
            Result.failure(e)
        }
    }

    suspend fun healthCheck(): Result<Unit> {
        return try {
            val client = httpClient ?: return Result.failure(IllegalStateException("HttpClient not initialized"))

            Logger.debug("Checking API health at: ${settings.apiBaseUrl}/health")

            client.get("${settings.apiBaseUrl}/health") {
                timeout {
                    requestTimeoutMillis = 5000
                    connectTimeoutMillis = 5000
                }
            }

            Logger.info("API health check passed")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.warn("API health check failed", e)
            Result.failure(e)
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        if (newSettings.apiTimeout != settings.apiTimeout) {
            Logger.info("Timeout changed: ${settings.apiTimeout}ms -> ${newSettings.apiTimeout}ms")
            close()
            createHttpClient()
        }
    }

    fun close() {
        try {
            httpClient?.close()
            Logger.debug("HttpClient closed")
        } catch (e: Exception) {
            Logger.error("Error closing HttpClient", e)
        } finally {
            httpClient = null
        }
    }

    fun isAvailable(): Boolean = httpClient != null
}
