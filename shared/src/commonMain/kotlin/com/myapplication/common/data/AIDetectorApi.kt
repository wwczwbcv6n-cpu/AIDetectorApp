package com.myapplication.common.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class AIDetectorApi {

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
            val response: AnalysisResult = httpClient.post("http://192.168.1.142:8080/analyze") { // Update to machine's local IP
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("image", imageData, Headers.build {
                                append(HttpHeaders.ContentType, "image/jpeg") // Assuming JPEG, adjust if needed
                                append(HttpHeaders.ContentDisposition, "filename=\"image.jpg\"")
                            })
                        }
                    )
                )
            }.body()
            Result.success(response)
        } catch (e: Exception) {
            // In a real app, log this exception
            e.printStackTrace()
            Result.failure(e)
        }
    }
}
