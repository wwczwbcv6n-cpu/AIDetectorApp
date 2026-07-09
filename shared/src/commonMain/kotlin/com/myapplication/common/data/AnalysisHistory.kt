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
    val notes: String = "",
    // Persisted three-band verdict name ([Verdict.name]). Nullable + defaulted
    // so entries written before the three-band migration still deserialize;
    // when absent we fall back to the [isAI] boolean.
    val verdict: String? = null,
    // SHA-256 of the exact analyzed bytes — the verdict-cache key (research
    // §10: byte hash primary, never a perceptual hash). Null on old entries.
    val sha256: String? = null
) {
    /** The three-band [Verdict], preferring the persisted name over [isAI]. */
    val verdictBand: Verdict
        get() = Verdict.fromName(verdict) ?: Verdict.fromBoolean(isAI)

    val formattedTime: String
        get() = formatTimestamp(timestamp)

    val formattedSize: String
        get() = when {
            fileSize > 1024 * 1024 -> "${(fileSize / (1024f * 1024f)).formatTo(2)} MB"
            fileSize > 1024 -> "${(fileSize / 1024f).formatTo(2)} KB"
            else -> "$fileSize B"
        }

    val statusText: String
        get() = when (verdictBand) {
            Verdict.AUTHENTIC -> "Likely authentic"
            Verdict.AI -> "Likely AI-generated"
            Verdict.UNCERTAIN -> "Uncertain"
            Verdict.TAMPERED -> "Possibly edited"
        }
}

expect class AnalysisHistoryRepository {
    suspend fun addEntry(entry: AnalysisHistoryEntry)
    suspend fun getHistory(limit: Int = 100): List<AnalysisHistoryEntry>
    suspend fun getEntry(id: String): AnalysisHistoryEntry?
    suspend fun deleteEntry(id: String)
    suspend fun clearHistory()
    suspend fun searchHistory(query: String): List<AnalysisHistoryEntry>
}
