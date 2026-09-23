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
 */
fun cachedVerdict(history: List<AnalysisHistoryEntry>, hash: String): AnalysisHistoryEntry? =
    history.firstOrNull { it.sha256 == hash && it.analysisMode != "PROVENANCE" }
