package com.myapplication.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.myapplication.common.data.AnalysisHistoryEntry
import com.myapplication.common.data.AnalysisHistoryRepository
import com.myapplication.common.data.ApiAnalysisResult
import com.myapplication.common.data.ApiClient
import com.myapplication.common.data.AppSettings
import com.myapplication.common.data.SettingsRepository
import com.myapplication.common.data.HeatmapUtils
import com.myapplication.common.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AnalysisUIState(
    val isAI: Boolean = false,
    val confidence: Float = 0f,
    val processingTimeMs: Long = 0L,
    val processedImage: ImageBitmap? = null,
    val heatmapImage: ImageBitmap? = null,
    val detailedFeatures: Map<String, Float> = emptyMap()
)

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
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            analysisResult = null
            detectedImage = null
            selectedHistoryEntry = null

            val startTime = nowMillis()
            try {
                // Try API first if configured
                val apiResult = if (settings.enableHeatmap) {
                    apiClient?.analyzeImageWithHeatmap(imageData)
                } else {
                    apiClient?.analyzeImage(imageData)
                }

                if (apiResult?.isSuccess == true) {
                    val result = apiResult.getOrNull()!!
                    val processingTime = nowMillis() - startTime

                    // Build UI state
                    analysisResult = AnalysisUIState(
                        isAI = result.isAI,
                        confidence = result.confidence,
                        processingTimeMs = processingTime,
                        detailedFeatures = result.detailedFeatures,
                        heatmapImage = result.heatmap?.let { HeatmapUtils.decodeHeatmapImage(it) }
                    )

                    // Save to history
                    val entry = AnalysisHistoryEntry(
                        fileName = fileName,
                        fileSize = fileSize,
                        isAI = result.isAI,
                        confidence = result.confidence,
                        analysisMode = settings.analysisMode.name,
                        processingTimeMs = processingTime,
                        heatmapBase64 = result.heatmap
                    )
                    historyRepository.addEntry(entry)
                    loadHistory()
                } else {
                    // Fallback to local model
                    analyzeWithLocalModel(imageData, fileName, fileSize)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = "Analysis failed: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    private suspend fun analyzeWithLocalModel(
        imageData: ByteArray,
        fileName: String,
        fileSize: Long
    ) {
        try {
            val startTime = nowMillis()
            val result = withContext(Dispatchers.Default) {
                heuristicDetector.analyze(imageData)
            }
            val processingTime = nowMillis() - startTime

            analysisResult = AnalysisUIState(
                isAI = result.isAI,
                confidence = result.confidence,
                processingTimeMs = processingTime,
                processedImage = result.processedImage,
                detailedFeatures = result.features
            )

            val entry = AnalysisHistoryEntry(
                fileName = fileName,
                fileSize = fileSize,
                isAI = result.isAI,
                confidence = result.confidence,
                analysisMode = "LOCAL",
                processingTimeMs = processingTime
            )
            historyRepository.addEntry(entry)
            loadHistory()
        } catch (e: Exception) {
            e.printStackTrace()
            errorMessage = "Local analysis failed: ${e.message}"
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        coroutineScope.launch {
            settingsRepository.saveSettings(newSettings)
            settings = newSettings
            apiClient = ApiClient(newSettings)
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
            isAI = entry.isAI,
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
