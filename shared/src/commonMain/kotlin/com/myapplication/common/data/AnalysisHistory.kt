package com.myapplication.common.data

import com.myapplication.common.formatTimestamp
import com.myapplication.common.formatTo
import com.myapplication.common.nowMillis
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * One persisted history row. Deliberately holds NO image derivative: the
 * server heatmap (a rendering of the user's photo) used to be stored here as
 * `heatmapBase64`, which turned "local history" into a stored copy of the
 * media (flagged by the 2026-09 privacy review). It now lives only in the
 * ViewModel for the current session. Old rows that still carry the field are
 * dropped on read (`ignoreUnknownKeys`) and rewritten without it by every
 * repository — see `LEGACY_HEATMAP_FIELD`.
 */
@Serializable
data class AnalysisHistoryEntry(
    val id: String = Random.nextLong().toString(),
    val timestamp: Long = nowMillis(),
    val fileName: String,
    val fileSize: Long, // bytes
    val isAI: Boolean,
    // LEGACY: the raw p_ai (0..1). Never displayed — on an Uncertain row it
    // read "Confidence: 97%" (audit 2026-09-24 APP-02). See [confidencePct].
    val confidence: Float,
    val analysisMode: String,
    val processingTimeMs: Long,
    val notes: String = "",
    // Persisted three-band verdict name ([Verdict.name]). Nullable + defaulted
    // so entries written before the three-band migration still deserialize;
    // when absent we fall back to the [isAI] boolean.
    val verdict: String? = null,
    // SHA-256 of the exact analyzed bytes — the verdict-cache key (research
    // §10: byte hash primary, never a perceptual hash). Null on old entries.
    val sha256: String? = null,
    // The server's `label` (the card headline), its `confidence` 0..100 (shown
    // only on ai/real) and the `mix` ai_share of an uncertain verdict. Null on
    // rows written before audit APP-02: those show the band title, no number.
    val label: String? = null,
    val confidencePct: Float? = null,
    val mixAiShare: Float? = null,
) {
    /**
     * The three-band [Verdict], preferring the persisted name over [isAI].
     * A LOCAL row was written by the retired on-device fallback, which never
     * measured anything: it reopens as [Verdict.NOT_ANALYZED], whatever band
     * it stored (audit 2026-09-24 APP-01).
     */
    val verdictBand: Verdict
        get() = if (analysisMode == MODE_LOCAL) Verdict.NOT_ANALYZED
            else Verdict.fromName(verdict) ?: Verdict.fromBoolean(isAI)

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
            Verdict.NOT_ANALYZED -> "Not analyzed"
        }

    companion object {
        /** analysisMode of rows the retired on-device fallback wrote. */
        const val MODE_LOCAL: String = "LOCAL"
    }
}

/**
 * Marker every repository looks for in the raw stored JSON: if present, the
 * store predates the heatmap purge and is rewritten once without the field.
 */
const val LEGACY_HEATMAP_FIELD: String = "\"heatmapBase64\""

expect class AnalysisHistoryRepository {
    suspend fun addEntry(entry: AnalysisHistoryEntry)
    suspend fun getHistory(limit: Int = 100): List<AnalysisHistoryEntry>
    suspend fun getEntry(id: String): AnalysisHistoryEntry?
    suspend fun deleteEntry(id: String)
    suspend fun clearHistory()
    suspend fun searchHistory(query: String): List<AnalysisHistoryEntry>
}
