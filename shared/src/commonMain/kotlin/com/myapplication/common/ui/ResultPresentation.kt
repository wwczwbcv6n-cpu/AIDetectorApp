package com.myapplication.common.ui

import com.myapplication.common.data.AnalysisHistoryEntry
import com.myapplication.common.data.MixSummary
import com.myapplication.common.data.Verdict
import com.myapplication.common.toUiState
import kotlin.math.roundToInt

/**
 * Every string the result card shows, decided in one place so the live
 * screen, the history detail, the history list and the share sheet read the
 * same — and the same as tayanch.com (site/demo.js showResult + addMixRow):
 *  - [headline]: the server's `label`, else the band title;
 *  - [confidenceLine]: "N% confident" from the server's `confidence` (0..100),
 *    only on a verdict that is not uncertain;
 *  - [mixLine]: on uncertain with a `mix` block, the AI-like / real-like shares;
 *    otherwise an uncertain card carries no number.
 * The raw p_ai is never an input (audit 2026-09-24 APP-02).
 */
data class ResultPresentation(
    val verdict: Verdict,
    val headline: String,
    val confidenceLine: String?,
    val mixLine: String?,
    /** 0..1 width of the AI-like part of the mix bar; null = no bar. */
    val mixAiShare: Float?,
    val description: String,
    val signalLines: List<String>,
) {
    fun allText(): List<String> =
        listOfNotNull(headline, confidenceLine, mixLine, description) + signalLines
}

private const val MIX_DESCRIPTION =
    "The models disagree or sit between their cuts, so this is not an accusation."

fun presentResult(
    verdict: Verdict,
    label: String?,
    confidencePct: Double?,
    mix: MixSummary?,
): ResultPresentation {
    val v = verdict.visuals()
    val showsConfidence = verdict != Verdict.UNCERTAIN && verdict != Verdict.NOT_ANALYZED
    val confidenceLine = confidencePct
        ?.takeIf { showsConfidence && it.isFinite() }
        ?.let { "${it.coerceIn(0.0, 100.0).roundToInt()}% confident" }
    val m = mix?.takeIf { verdict == Verdict.UNCERTAIN }
    val mixLine = m?.let {
        val ai = (it.aiShare * 100).roundToInt().coerceIn(0, 100)
        "Mixed signals — $ai% reads AI-like, ${100 - ai}% reads real-like" +
            if (it.advisory) " (advisory)" else ""
    }
    return ResultPresentation(
        verdict = verdict,
        headline = label?.trim()?.takeIf { it.isNotEmpty() && verdict != Verdict.NOT_ANALYZED } ?: v.title,
        confidenceLine = confidenceLine,
        mixLine = mixLine,
        mixAiShare = m?.aiShare,
        description = if (m != null) MIX_DESCRIPTION else v.description,
        signalLines = m?.signals.orEmpty().map { (name, reads) -> "$name: reads $reads" },
    )
}

/** The card for a history row (list and reopen share it). */
fun AnalysisHistoryEntry.presentation(): ResultPresentation =
    toUiState().let { presentResult(it.verdict, it.label, it.confidencePct?.toDouble(), it.mix) }
