package com.myapplication.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.myapplication.common.data.AnalysisHistoryEntry
import com.myapplication.common.data.AnalysisHistoryRepository
import com.myapplication.common.data.ApiClient
import com.myapplication.common.data.ApiException
import com.myapplication.common.data.AppSettings
import com.myapplication.common.data.MixSummary
import com.myapplication.common.data.SettingsRepository
import com.myapplication.common.data.Verdict
import com.myapplication.common.nowMillis
import com.myapplication.common.secure.TierState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AnalysisUIState(
    val verdict: Verdict = Verdict.AUTHENTIC,
    // What the card shows (ui/ResultPresentation.kt, mirroring site/demo.js
    // showResult): the server's `label` as the headline, its `confidence`
    // (0..100) only on ai/real, the `mix` shares on uncertain. The raw p_ai
    // is never held here, so it cannot be displayed (audit 2026-09-24 APP-02).
    val label: String? = null,
    val confidencePct: Float? = null,
    val mix: MixSummary? = null,
    val processingTimeMs: Long = 0L,
    val processedImage: ImageBitmap? = null,
    val heatmapImage: ImageBitmap? = null,
    val detailedFeatures: Map<String, Float> = emptyMap(),
    // One-line qualifier shown under the verdict: a server abstain reason
    // ("image too degraded — share the original file"), a provenance match
    // ("AI generator signature: midjourney"), or a cache note.
    val detailNote: String? = null
) {
    /** Kept for callers that still need the binary collapse. */
    val isAI: Boolean get() = verdict == Verdict.AI
}

class AppViewModel(
    private val pyTorchModel: PyTorchModel,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: AnalysisHistoryRepository
) {
    var settings by mutableStateOf(AppSettings())
    var detectedImage by mutableStateOf<ImageBitmap?>(null)
    var analysisResult by mutableStateOf<AnalysisUIState?>(null)
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var analysisHistory by mutableStateOf<List<AnalysisHistoryEntry>>(emptyList())
    var selectedHistoryEntry by mutableStateOf<AnalysisHistoryEntry?>(null)

    /**
     * The tier the LAST upload was classified into (SPEC §6) — exactly one of
     * verified / unattested / failed, or null before the first upload. The
     * analysis screen renders [TierState.userLine] verbatim.
     */
    var tierState by mutableStateOf<TierState?>(null)

    // Compose's Snapshot system rejects mutableStateOf writes from background
    // dispatchers when the state was created on Main. We launch on Main and
    // shift any CPU-heavy block (hashing, metadata scan, decode) into
    // withContext(Default).
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var apiClient: ApiClient? = null

    /**
     * Server heatmaps for THIS SESSION ONLY, keyed by history entry id. They
     * are a rendering of the user's photo and are deliberately never
     * persisted (see [AnalysisHistoryEntry]); reopening the app shows the
     * verdict without the heatmap.
     */
    private val sessionHeatmaps = HashMap<String, ImageBitmap>()

    init {
        coroutineScope.launch {
            try {
                settings = settingsRepository.getSettings()
                apiClient = buildClient(settings)
                loadHistory()
            } catch (e: Exception) {
                Logger.error("AppViewModel initialization failed", e)
                errorMessage = "Initialization failed: ${e.message}"
            }
        }
    }

    /** One ApiClient per settings snapshot; its tier callback feeds [tierState]. */
    private fun buildClient(forSettings: AppSettings): ApiClient =
        ApiClient(forSettings).also { client ->
            client.onTierState = { state -> tierState = state }
        }

    /**
     * Must be called when the host Activity/Composable is destroyed to release
     * the API HttpClient and cancel in-flight coroutines.
     */
    fun dispose() {
        try {
            apiClient?.close()
        } catch (e: Exception) {
            Logger.warn("ApiClient.close() failed: ${e.message}")
        }
        apiClient = null
        sessionHeatmaps.clear()
        coroutineScope.cancel()
    }

    fun analyzeImage(imageData: ByteArray, fileName: String, fileSize: Long = 0L) {
        // In-flight guard (#5): ignore taps while a request is already running so
        // a double-tap can't fire two overlapping analyses / two history writes.
        if (isLoading) {
            Logger.debug("analyzeImage ignored — a request is already in flight")
            return
        }
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            analysisResult = null
            selectedHistoryEntry = null

            // Show the picked image while (and after) the analysis runs — this
            // was reset to null and never populated on the server path, so the
            // "Selected Image" card never appeared. Decode a bounded preview
            // off the main thread; a decode failure just skips the preview.
            detectedImage = withContext(Dispatchers.Default) {
                try {
                    decodeImage(imageData, maxSide = 512).composeImage
                } catch (e: Exception) {
                    Logger.warn("Preview decode failed: ${e.message}")
                    null
                }
            }

            val startTime = nowMillis()
            try {
                // Pipeline order per the mid-2026 research doc (§7): cheap and
                // decisive checks first, the network/model last.
                val hash = withContext(Dispatchers.Default) { sha256Hex(imageData) }

                // 0. Verdict cache — exact byte hash only (§10). Feeds repeat
                //    the same media constantly; an identical byte stream gets
                //    the stored verdict instantly, for free. The hash stays on
                //    this device (it is never logged or sent). PROVENANCE rows
                //    are skipped: the scan below is free and current, and rows
                //    the pre-PROV-7 substring scan wrote accused real photos.
                val cached = cachedVerdict(historyRepository.getHistory(500), hash)
                if (cached != null) {
                    analysisResult = cached.toUiState(sessionHeatmaps[cached.id]).copy(
                        processingTimeMs = nowMillis() - startTime,
                        detailNote = "Cached result — identical image analyzed ${cached.formattedTime}."
                    )
                    return@launch
                }

                // 1. Provenance/metadata — free byte scan. Only a file that
                //    confesses its own generation (an A1111 / ComfyUI settings
                //    chunk, the IPTC trainedAlgorithmicMedia URL) decides here
                //    without a network round-trip; a name in a caption or an
                //    Artist field never does (MetadataAnalyzer, audit PROV-7).
                //    Absence proves nothing and falls through to the server.
                val meta = withContext(Dispatchers.Default) {
                    try {
                        MetadataAnalyzer.analyze(imageData)
                    } catch (e: Exception) {
                        Logger.warn("Metadata scan failed: ${e.message}")
                        null
                    }
                }
                if (meta?.generatorMatch != null) {
                    val processingTime = nowMillis() - startTime
                    analysisResult = AnalysisUIState(
                        verdict = Verdict.AI,
                        label = LOCAL_PROVENANCE_LABEL,
                        processingTimeMs = processingTime,
                        detailNote = "AI generator signature in metadata: ${meta.generatorMatch}"
                    )
                    historyRepository.addEntry(
                        AnalysisHistoryEntry(
                            fileName = fileName,
                            fileSize = fileSize,
                            isAI = true,
                            confidence = 0.95f,
                            analysisMode = "PROVENANCE",
                            processingTimeMs = processingTime,
                            verdict = Verdict.AI.name,
                            sha256 = hash,
                            label = LOCAL_PROVENANCE_LABEL,
                        )
                    )
                    loadHistory()
                    return@launch
                }

                val client = apiClient
                if (client == null) {
                    // Settings not yet loaded / client not built: no verdict
                    // at all (the old on-device estimate read "Likely
                    // authentic" on AI photos — audit 2026-09-24 APP-01).
                    showFailure(serverNotReadyOutcome())
                    return@launch
                }

                // 2. The server, through the sealed TSE2 envelope. The client
                //    classifies the tier first (tierState updates through the
                //    callback) and refuses to send on FAILED, or on UNATTESTED
                //    when the user required attestation.
                //    (`settings.enableHeatmap` no longer changes this request: the
                //    served API ignored `?heatmap=`. It is reserved for the second
                //    sealed POST /heatmap call — see below.)
                val apiResult = client.analyzeImage(imageData)

                apiResult.fold(
                    onSuccess = { result ->
                        val processingTime = nowMillis() - startTime
                        val verdict = result.toVerdict(settings.confidenceThreshold)

                        val entry = AnalysisHistoryEntry(
                            fileName = fileName,
                            fileSize = fileSize,
                            isAI = result.isAI,
                            confidence = result.uiConfidence,
                            analysisMode = settings.analysisMode.name,
                            processingTimeMs = processingTime,
                            verdict = verdict.name,
                            sha256 = hash,
                            label = result.label,
                            confidencePct = result.confidence?.toFloat(),
                            mixAiShare = result.mix?.toSummary()?.aiShare,
                        )
                        // Heat map: /analyze never inlines one (the old inline
                        // base64 field was never served). It is a
                        // second sealed call — POST /heatmap with
                        // `token` = result.heatmapToken, exactly as
                        // site/demo.js addHeatmapRow() does — to be gated by
                        // settings.enableHeatmap and kept session-only in
                        // [sessionHeatmaps] (never persisted).
                        // TODO(app): wire that call; until then no map is shown.
                        analysisResult = AnalysisUIState(
                            verdict = verdict,
                            label = result.label,
                            confidencePct = result.confidence?.toFloat(),
                            mix = result.mix?.toSummary(),
                            processingTimeMs = processingTime,
                            heatmapImage = null,
                            // Surface the server's qualifier (degradation
                            // abstain reason / edit note / provenance note)
                            // honestly; with none, the server's own timing —
                            // the same fallback site/demo.js uses.
                            detailNote = result.detail
                                ?: result.elapsedMs?.let { ms -> "Server analysis took $ms ms." }
                        )

                        historyRepository.addEntry(entry)
                        loadHistory()
                    },
                    onFailure = { e ->
                        // Type only — never the message, which can echo server strings.
                        Logger.warn("analyze API failed: " +
                            ((e as? ApiException)?.apiError?.let { it::class.simpleName } ?: e::class.simpleName))
                        showFailure(failureOutcome(e))
                    }
                )
            } catch (e: Exception) {
                // Defensive: runAnalyze already wraps failures in Result, so we
                // shouldn't normally land here — surface it rather than hide it.
                Logger.error("analyzeImage unexpected failure", e)
                errorMessage = "Analysis failed: ${e.message ?: "unknown error"}"
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Show a failed analysis (#2). An unreachable server (offline / timeout /
     * not configured) gets a NOT_ANALYZED card and NO history row; every other
     * typed error (auth, attestation, server error, ...) is shown as it is.
     * Nothing ever falls back to an on-device verdict: the heuristic that used
     * to was never measured and passed AI photos as "Likely authentic"
     * (audit 2026-09-24 APP-01). See [failureOutcome].
     */
    private fun showFailure(outcome: FailureOutcome) {
        analysisResult = outcome.state
        errorMessage = outcome.errorMessage
    }

    fun updateSettings(newSettings: AppSettings) {
        coroutineScope.launch {
            settingsRepository.saveSettings(newSettings)
            settings = newSettings
            // Swap the client, then release the old one — each ApiClient owns
            // an HttpClient engine; replacing without closing leaked its
            // connection pool/threads on every settings save.
            val old = apiClient
            apiClient = buildClient(newSettings)
            try {
                old?.close()
            } catch (e: Exception) {
                Logger.warn("Old ApiClient.close() failed: ${e.message}")
            }
        }
    }

    fun loadHistory() {
        coroutineScope.launch {
            analysisHistory = historyRepository.getHistory(settings.cacheResultsCount)
        }
    }

    fun deleteHistoryEntry(id: String) {
        coroutineScope.launch {
            historyRepository.deleteEntry(id)
            sessionHeatmaps.remove(id)
            loadHistory()
        }
    }

    fun clearHistory() {
        coroutineScope.launch {
            historyRepository.clearHistory()
            sessionHeatmaps.clear()
            loadHistory()
        }
    }

    fun searchHistory(query: String) {
        coroutineScope.launch {
            analysisHistory = historyRepository.searchHistory(query)
        }
    }

    fun selectHistoryEntry(entry: AnalysisHistoryEntry) {
        selectedHistoryEntry = entry
        analysisResult = entry.toUiState(sessionHeatmaps[entry.id])
    }

    fun testApiConnection() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val result = apiClient?.healthCheck()
                if (result?.isSuccess == true) {
                    errorMessage = "✓ API connection successful"
                } else {
                    errorMessage = "✗ API connection failed"
                }
            } catch (e: Exception) {
                errorMessage = "✗ API connection failed: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }
}
