package com.myapplication.common

import com.myapplication.common.data.AnalysisHistoryEntry

/**
 * The verdict-cache hit for [hash] (exact byte SHA-256), or null.
 *
 * PROVENANCE rows never count: the metadata scan that wrote them is a free,
 * on-device byte scan, so re-running it costs nothing, and the rows written
 * before audit 2026-09-23 PROV-7 came from a substring scan that called real
 * photos AI (Artist 'Leonardo Rossi', an Imagenomic retouch, a Magic Eraser
 * composite — 8 of 13 metadata-intact real copies). History survives an app
 * update, and the cache is consulted BEFORE the scan, so without this those
 * accusations would replay as "Cached result" for the same bytes forever.
 * A file that still confesses its generation is re-decided by the current
 * [MetadataAnalyzer] a moment later, so nothing true is lost.
 *
 * LOCAL rows (the retired on-device fallback) never count either: they are
 * not a verdict, and reopen as NOT_ANALYZED (audit 2026-09-24 APP-01).
 *
 * And a row replays only when the SAME server model decided it, within
 * [CACHE_MAX_AGE_MS] (audit 2026-09-24 APP-12): after a server fix (a new
 * head, a recalibrated threshold) the old verdict must not come back as
 * "Cached result". [currentModel] is what /health serves now (null = not
 * known -> no replay); rows written before the model was stored never replay.
 */
fun cachedVerdict(
    history: List<AnalysisHistoryEntry>,
    hash: String,
    currentModel: String?,
    nowMs: Long = nowMillis(),
): AnalysisHistoryEntry? {
    if (currentModel.isNullOrBlank()) return null
    return history.firstOrNull {
        it.sha256 == hash && it.analysisMode != "PROVENANCE" &&
            it.analysisMode != AnalysisHistoryEntry.MODE_LOCAL &&
            it.model == currentModel && nowMs - it.timestamp in 0..CACHE_MAX_AGE_MS
    }
}

/** How long a server verdict may be replayed for identical bytes. */
const val CACHE_MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000
