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
import com.myapplication.common.data.HeatmapUtils
import com.myapplication.common.data.Verdict
import com.myapplication.common.nowMillis
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

    private val heuristicDetector = HeuristicAIDetector()
    // Compose's Snapshot system rejects mutableStateOf writes from background
    // dispatchers when the state was created on Main. We launch on Main and
    // shift any CPU-heavy block (heuristic analysis, repo IO) into
    // withContext(Default).
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var apiClient: ApiClient? = null

    init {
        coroutineScope.launch {
            try {
                settings = settingsRepository.getSettings()
                apiClient = ApiClient(settings)
                loadHistory()
            } catch (e: Exception) {
                Logger.error("AppViewModel initialization failed", e)
                errorMessage = "Initialization failed: ${e.message}"
            }
        }
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
                //    the stored verdict instantly, for free.
                val cached = historyRepository.getHistory(500)
                    .firstOrNull { it.sha256 == hash }
                if (cached != null) {
                    analysisResult = AnalysisUIState(
                        verdict = cached.verdictBand,
                        confidence = cached.confidence,
                        processingTimeMs = nowMillis() - startTime,
                        heatmapImage = cached.heatmapBase64
                            ?.let { HeatmapUtils.decodeHeatmapImage(it) },
                        detailNote = "Cached result — identical image analyzed ${cached.formattedTime}."
                    )
                    return@launch
                }

                // 1. Provenance/metadata — free byte scan; a generator
                //    signature (SD/Midjourney EXIF, IPTC trainedAlgorithmicMedia)
                //    is near-perfect precision, so it decides without a
                //    network round-trip. Absence proves nothing and falls
                //    through to the model.
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

                // Try the server first.
                val apiResult = if (settings.enableHeatmap) {
                    client.analyzeImageWithHeatmap(imageData)
                } else {
                    client.analyzeImage(imageData)
                }

                apiResult.fold(
                    onSuccess = { result ->
                        val processingTime = nowMillis() - startTime
                        val verdict = result.toVerdict(settings.confidenceThreshold)

                        analysisResult = AnalysisUIState(
                            verdict = verdict,
                            confidence = result.uiConfidence,
                            processingTimeMs = processingTime,
                            heatmapImage = result.heatmapBase64
                                ?.let { HeatmapUtils.decodeHeatmapImage(it) },
                            // Surface the server's qualifier (degradation
                            // abstain reason / provenance note) honestly.
                            detailNote = result.detail
                        )

                        val entry = AnalysisHistoryEntry(
                            fileName = fileName,
                            fileSize = fileSize,
                            isAI = result.isAI,
                            confidence = result.uiConfidence,
                            analysisMode = settings.analysisMode.name,
                            processingTimeMs = processingTime,
                            heatmapBase64 = result.heatmapBase64,
                            verdict = verdict.name,
                            sha256 = hash
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
     */
    private suspend fun handleAnalyzeFailure(
        e: Throwable,
        imageData: ByteArray,
        fileName: String,
        fileSize: Long,
        sha256: String? = null,
    ) {
        val apiError = (e as? ApiException)?.apiError
        Logger.warn("analyze API failed: ${apiError ?: e.message}")
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
                // Auth / ClientError / ServerError / Parse / Unknown — show it.
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
            apiClient = ApiClient(newSettings)
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
            loadHistory()
        }
    }

    fun clearHistory() {
        coroutineScope.launch {
            historyRepository.clearHistory()
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
            heatmapImage = entry.heatmapBase64?.let { HeatmapUtils.decodeHeatmapImage(it) }
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
