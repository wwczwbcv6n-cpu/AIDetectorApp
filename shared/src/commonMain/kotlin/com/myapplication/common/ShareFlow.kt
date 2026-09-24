package com.myapplication.common

import com.myapplication.common.data.AnalysisHistoryEntry
import com.myapplication.common.data.ApiAnalysisResult

/**
 * Starts each shared item's analysis once (audit 2026-09-24 APP-16). Held by
 * a retained ViewModel, so an Activity recreated by rotation finds the item
 * already started instead of uploading — and billing — it a second time.
 */
class ShareRequestGuard {
    private val started = mutableSetOf<String>()

    /** True the first time [key] (the shared uri) is seen, false after. */
    fun shouldStart(key: String): Boolean = started.add(key)
}

/**
 * The history row for a server verdict — one builder for the main screen and
 * the share sheet, so both record the label, the server's confidence, the mix
 * share and the model the verdict cache keys on.
 */
fun serverHistoryEntry(
    result: ApiAnalysisResult,
    fileName: String,
    fileSize: Long,
    processingTimeMs: Long,
    sha256: String?,
): AnalysisHistoryEntry {
    val verdict = result.toVerdict()
    return AnalysisHistoryEntry(
        fileName = fileName,
        fileSize = fileSize,
        isAI = result.isAI,
        confidence = result.uiConfidence,
        analysisMode = AnalysisHistoryEntry.MODE_SERVER,
        processingTimeMs = processingTimeMs,
        verdict = verdict.name,
        sha256 = sha256,
        label = result.label,
        confidencePct = result.confidence?.toFloat(),
        mixAiShare = result.mix?.toSummary()?.aiShare,
        model = result.model,
    )
}

/** The history row for a file that confessed its own AI generation in metadata (decided on the phone). */
fun provenanceHistoryEntry(fileName: String, fileSize: Long, processingTimeMs: Long, sha256: String?) =
    AnalysisHistoryEntry(
        fileName = fileName,
        fileSize = fileSize,
        isAI = true,
        confidence = 0.95f,
        analysisMode = AnalysisHistoryEntry.MODE_PROVENANCE,
        processingTimeMs = processingTimeMs,
        verdict = com.myapplication.common.data.Verdict.AI.name,
        sha256 = sha256,
        label = LOCAL_PROVENANCE_LABEL,
    )
