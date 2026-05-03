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

    suspend fun analyzeImage(imageData: ByteArray): Result<ApiAnalysisResult> {
        return try {
            val response: ApiAnalysisResult = httpClient.post(
                "${settings.apiBaseUrl}/analyze"
            ) {
                timeout {
                    requestTimeoutMillis = settings.apiTimeout
                }
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

    suspend fun analyzeImageWithHeatmap(
        imageData: ByteArray,
        includeHeatmap: Boolean = true
    ): Result<ApiAnalysisResult> {
        return try {
            val response: ApiAnalysisResult = httpClient.post(
                "${settings.apiBaseUrl}/analyze"
            ) {
                timeout {
                    requestTimeoutMillis = settings.apiTimeout
                }
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
            httpClient.get("${settings.apiBaseUrl}/health") {
                timeout {
                    requestTimeoutMillis = 5000
                }
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
