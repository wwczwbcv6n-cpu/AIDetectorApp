package com.myapplication.common

import androidx.compose.ui.graphics.ImageBitmap
import com.myapplication.common.data.AnalysisHistoryEntry
import com.myapplication.common.data.ApiError
import com.myapplication.common.data.ApiException
import com.myapplication.common.data.MixSummary
import com.myapplication.common.data.Verdict

/**
 * What the analysis screen shows after a failed server call: an optional
 * result card ([state]) plus the error line.
 */
data class FailureOutcome(val state: AnalysisUIState?, val errorMessage: String)

/**
 * The server was not reached, so the image has no verdict. There is no
 * on-device fallback verdict any more: the heuristic behind it was never
 * measured, and an approximate port on data/api_bench called 14/556 AI
 * photos AI, every one by a metadata confession (audit 2026-09-24 APP-01).
 * A file that confesses its generation is still decided locally, before
 * the server is asked (MetadataAnalyzer), so nothing measured is lost.
 */
fun notAnalyzedState(reason: String, processingTimeMs: Long = 0L): AnalysisUIState =
    AnalysisUIState(
        verdict = Verdict.NOT_ANALYZED,
        processingTimeMs = processingTimeMs,
        detailNote = reason,
    )

/** Transport-level failures: the server never answered, so nothing was judged. */
fun ApiError.isUnreachable(): Boolean =
    this is ApiError.Network || this is ApiError.Timeout || this is ApiError.Configuration

/**
 * Map an analyze failure to what the user sees. Unreachable server ->
 * a NOT_ANALYZED card plus the reason; every other typed error (auth,
 * attestation, server error, ...) -> the error line alone.
 */
fun failureOutcome(e: Throwable): FailureOutcome {
    val apiError = (e as? ApiException)?.apiError
        ?: return FailureOutcome(null, "Analysis failed: ${e.message ?: "unknown error"}")
    if (apiError.isUnreachable()) {
        return FailureOutcome(
            notAnalyzedState("Not analyzed — Tayanch's server was not reached. Nothing was judged."),
            apiError.userMessage,
        )
    }
    return FailureOutcome(null, apiError.userMessage)
}

/** The ApiClient is not built yet (settings still loading, or never saved). */
fun serverNotReadyOutcome(): FailureOutcome =
    failureOutcome(ApiException(ApiError.Configuration(
        "The Tayanch server is not set up yet. Open Settings and set the API URL and key.")))

/** Reopen a history row. A LOCAL row shows NOT_ANALYZED with no number. */
fun AnalysisHistoryEntry.toUiState(heatmap: ImageBitmap? = null): AnalysisUIState {
    val band = verdictBand
    if (band == Verdict.NOT_ANALYZED) {
        return notAnalyzedState(
            "Saved while the server was not reached — no verdict was made. Analyze it again.",
            processingTimeMs,
        )
    }
    // `confidence` on rows written before audit APP-02 holds the raw p_ai,
    // so it is never shown; only `confidencePct` (the server's 0..100) is.
    return AnalysisUIState(
        verdict = band,
        label = label,
        confidencePct = confidencePct,
        mix = mixAiShare?.let { MixSummary(aiShare = it) },
        processingTimeMs = processingTimeMs,
        heatmapImage = heatmap,
    )
}

/** Headline of a verdict the phone decided itself (a metadata generation confession). */
const val LOCAL_PROVENANCE_LABEL: String =
    "AI-generated — the file's own metadata declares an AI generator"
