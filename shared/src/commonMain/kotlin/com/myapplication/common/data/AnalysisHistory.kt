package com.myapplication.common.data

import com.myapplication.common.formatTimestamp
import com.myapplication.common.formatTo
import com.myapplication.common.nowMillis
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class AnalysisHistoryEntry(
    val id: String = Random.nextLong().toString(),
    val timestamp: Long = nowMillis(),
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
        get() = formatTimestamp(timestamp)

    val formattedSize: String
        get() = when {
            fileSize > 1024 * 1024 -> "${(fileSize / (1024f * 1024f)).formatTo(2)} MB"
            fileSize > 1024 -> "${(fileSize / 1024f).formatTo(2)} KB"
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
