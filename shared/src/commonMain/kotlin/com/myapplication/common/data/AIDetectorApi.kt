package com.myapplication.common.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Thin wrapper around the /analyze endpoint. The constructor used to
 * hardcode `http://192.168.1.142:8080` which (a) shipped a single
 * developer's LAN IP to every user's phone, and (b) sent image data
 * over cleartext HTTP that any public-WiFi attacker could intercept.
 *
 * Now: caller MUST provide a base URL — typically `settings.apiBaseUrl`
 * from [AppSettings]. Mutating endpoints additionally accept an
 * `apiKey` so the request carries `X-API-Key` (server requires it for
 * /rag/* and /finetune; analyze accepts it for rate-limit identity).
 *
 * Prefer [ApiClient] for new code — it ties the URL + timeout +
 * settings together. This class is kept for the simpler "just analyze
 * one image" path used in a couple of UI flows.
 */
class AIDetectorApi(
    private val baseUrl: String,
    private val apiKey: String? = null,
) {

    init {
        require(baseUrl.isNotBlank()) {
            "AIDetectorApi requires a non-empty baseUrl — read it from " +
            "AppSettings.apiBaseUrl, do not hardcode."
        }
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true // Useful for API changes
            })
        }
    }

    suspend fun analyzeImage(imageData: ByteArray): Result<AnalysisResult> {
        return try {
            val response: AnalysisResult = httpClient.post(
                "${baseUrl.trimEnd('/')}/analyze"
            ) {
                apiKey?.takeIf { it.isNotBlank() }?.let {
                    header("X-API-Key", it)
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
            // In a real app, log this exception via the Logging plugin —
            // never let a stack trace surface to the user (it can leak
            // local IPs, file paths, or auth headers).
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /** Release the underlying HttpClient. Call from the owner's teardown
     *  (e.g. Activity.onDestroy) so the connection pool / engine threads
     *  don't outlive the screen that created them. */
    fun close() {
        httpClient.close()
    }
}
