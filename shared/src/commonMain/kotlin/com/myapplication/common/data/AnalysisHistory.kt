package com.myapplication.common.data

import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class AnalysisHistoryEntry(
    val id: String = Random.nextLong().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val fileName: String,
    val fileSize: Long, // bytes
    val isAI: Boolean,
    val confidence: Float,
    val analysisMode: String,
    val processingTimeMs: Long,
    val heatmapBase64: String? = null,
    val notes: String = ""
) {
    val formattedTime: String
        get() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(timestamp)

    val formattedSize: String
        get() = when {
            fileSize > 1024 * 1024 -> "%.2f MB".format(fileSize / (1024f * 1024f))
            fileSize > 1024 -> "%.2f KB".format(fileSize / 1024f)
            else -> "$fileSize B"
        }

    val statusText: String
        get() = if (isAI) "AI Generated" else "Real"
}

expect class AnalysisHistoryRepository {
    suspend fun addEntry(entry: AnalysisHistoryEntry)
    suspend fun getHistory(limit: Int = 100): List<AnalysisHistoryEntry>
    suspend fun getEntry(id: String): AnalysisHistoryEntry?
    suspend fun deleteEntry(id: String)
    suspend fun clearHistory()
    suspend fun searchHistory(query: String): List<AnalysisHistoryEntry>
}
