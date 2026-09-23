package com.myapplication.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.myapplication.common.data.AnalysisHistoryEntry
import com.myapplication.common.data.AnalysisHistoryRepository
import com.myapplication.common.data.ApiClient
import com.myapplication.common.data.ApiError
import com.myapplication.common.data.ApiException
import com.myapplication.common.data.AppSettings
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
    val confidence: Float = 0f,
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

    private val heuristicDetector = HeuristicAIDetector()
    // Compose's Snapshot system rejects mutableStateOf writes from background
    // dispatchers when the state was created on Main. We launch on Main and
    // shift any CPU-heavy block (heuristic analysis, repo IO) into
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
                    analysisResult = AnalysisUIState(
                        verdict = cached.verdictBand,
                        confidence = cached.confidence,
                        processingTimeMs = nowMillis() - startTime,
                        heatmapImage = sessionHeatmaps[cached.id],
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
                        confidence = 0.95f,
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
                            sha256 = hash
                        )
                    )
                    loadHistory()
                    return@launch
                }

                val client = apiClient
                if (client == null) {
                    // Settings not yet loaded / client not built — fall back to the
                    // on-device estimate but say so, don't pretend it's the server.
                    analyzeWithLocalModel(imageData, fileName, fileSize, offline = true,
                                          sha256 = hash)
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
                            sha256 = hash
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
                            confidence = result.uiConfidence,
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
                        handleAnalyzeFailure(e, imageData, fileName, fileSize, hash)
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
     * Map a typed [ApiError] to a visible UI state (#2). Transport-level
     * failures (offline / timeout / not-configured) optionally fall back to the
     * on-device heuristic — but ALWAYS clearly labelled as an offline estimate,
     * never silently substituted for the server's verdict. Auth / parse / server
     * errors do NOT fall back: hiding them behind the heuristic is what made the
     * server look like it "worked" while never being used.
     *
     * Attestation / envelope errors ([ApiError.Attestation],
     * [ApiError.AttestationRequired], [ApiError.Protocol], ...) are shown as
     * they are: nothing was sent (or nothing was decoded), and no fallback
     * runs — the user asked for a server verdict under a stated tier and did
     * not get one.
     */
    private suspend fun handleAnalyzeFailure(
        e: Throwable,
        imageData: ByteArray,
        fileName: String,
        fileSize: Long,
        sha256: String? = null,
    ) {
        val apiError = (e as? ApiException)?.apiError
        // Type only — never the message, which can echo server strings.
        Logger.warn("analyze API failed: ${apiError?.let { it::class.simpleName } ?: e::class.simpleName}")
        when (apiError) {
            is ApiError.Network,
            is ApiError.Timeout,
            is ApiError.Configuration -> {
                // Offline-style failure → on-device estimate, clearly marked.
                errorMessage = apiError.userMessage + " Showing an on-device estimate."
                analyzeWithLocalModel(imageData, fileName, fileSize, offline = true,
                                      sha256 = sha256)
            }
            null -> {
                // Non-ApiException (shouldn't happen) — surface generically.
                errorMessage = "Analysis failed: ${e.message ?: "unknown error"}"
            }
            else -> {
                // Auth / ClientError / ServerError / Parse / Unknown /
                // Attestation* / StaleTimestamp / KeyRotation / Protocol / Envelope — show it.
                errorMessage = apiError.userMessage
            }
        }
    }

    /**
     * Run the on-device heuristic detector. [offline] = true means this is an
     * explicit fallback for a transport failure (an informational [errorMessage]
     * has already been set by the caller and must be preserved); false means a
     * directly-requested local analysis.
     */
    private suspend fun analyzeWithLocalModel(
        imageData: ByteArray,
        fileName: String,
        fileSize: Long,
        offline: Boolean = false,
        sha256: String? = null,
    ) {
        try {
            val startTime = nowMillis()
            val result = withContext(Dispatchers.Default) {
                heuristicDetector.analyze(imageData)
            }
            val processingTime = nowMillis() - startTime
            val verdict = Verdict.fromBoolean(result.isAI)

            analysisResult = AnalysisUIState(
                verdict = verdict,
                confidence = result.confidence,
                processingTimeMs = processingTime,
                processedImage = result.processedImage,
                detailedFeatures = result.features,
                detailNote = result.explanation.takeIf { it.isNotBlank() }
            )

            // NOTE: deliberately NO sha256 on LOCAL entries — the heuristic is
            // a weak offline estimate, and caching it under the byte hash
            // would shadow a proper server verdict for the same image later.
            val entry = AnalysisHistoryEntry(
                fileName = fileName,
                fileSize = fileSize,
                isAI = result.isAI,
                confidence = result.confidence,
                analysisMode = "LOCAL",
                processingTimeMs = processingTime,
                verdict = verdict.name
            )
            historyRepository.addEntry(entry)
            loadHistory()
        } catch (e: Exception) {
            Logger.error("Local analysis failed", e)
            // Don't clobber a more-informative server error when this was a fallback.
            if (!offline || errorMessage == null) {
                errorMessage = "Local analysis failed: ${e.message ?: "unknown error"}"
            }
        }
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
        analysisResult = AnalysisUIState(
            verdict = entry.verdictBand,
            confidence = entry.confidence,
            processingTimeMs = entry.processingTimeMs,
            heatmapImage = sessionHeatmaps[entry.id]
        )
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
