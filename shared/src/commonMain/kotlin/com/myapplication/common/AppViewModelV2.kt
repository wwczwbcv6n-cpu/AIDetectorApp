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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

data class AnalysisUIState(
    val isAI: Boolean = false,
    val confidence: Float = 0f,
    val processingTimeMs: Long = 0L,
    val processedImage: ImageBitmap? = null,
    val heatmapImage: ImageBitmap? = null,
    val detailedFeatures: Map<String, Float> = emptyMap()
)

/**
 * Production-grade AppViewModel with:
 * - Proper lifecycle management
 * - Rate limiting
 * - Error recovery
 * - Thread-safe operations
 * - Comprehensive logging
 */
class AppViewModelV2(
    private val pyTorchModel: PyTorchModel,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: AnalysisHistoryRepository
) {
    // Lifecycle management
    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + job)

    // Rate limiting
    private val analysisRateLimiter = AnalysisRateLimiter()
    private val apiRateLimiter = RateLimiter(maxRequests = 10, windowMs = 1000L)

    // State management
    var settings by mutableStateOf(AppSettings())
    var detectedImage by mutableStateOf<ImageBitmap?>(null)
    var analysisResult by mutableStateOf<AnalysisUIState?>(null)
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var analysisHistory by mutableStateOf<List<AnalysisHistoryEntry>>(emptyList())
    var selectedHistoryEntry by mutableStateOf<AnalysisHistoryEntry?>(null)

    private val aiDetector = AIDetector(pyTorchModel)
    private val heuristicDetector = HeuristicAIDetector()
    private var apiClient: ApiClient? = null

    init {
        coroutineScope.launch {
            try {
                Logger.debug("AppViewModelV2 initializing...")
                settings = settingsRepository.getSettings()
                apiClient = ApiClient(settings)
                loadHistory()
                Logger.info("AppViewModelV2 initialized successfully")
            } catch (e: Exception) {
                Logger.error("AppViewModelV2 initialization failed", e)
                errorMessage = "Initialization failed: ${e.message}"
            }
        }
    }

    fun analyzeImage(imageData: ByteArray, fileName: String, fileSize: Long = 0L) {
        coroutineScope.launch {
            // Validate inputs
            if (!validateImageInput(imageData, fileName, fileSize)) return@launch

            // Check rate limiting
            if (!analysisRateLimiter.canStartAnalysis()) {
                Logger.warn("Analysis rate limited - already analyzing")
                errorMessage = "Analysis already in progress. Please wait."
                return@launch
            }

            isLoading = true
            errorMessage = null
            analysisResult = null
            detectedImage = null
            selectedHistoryEntry = null

            val startTime = System.currentTimeMillis()
            try {
                Logger.info("Starting analysis for: $fileName (${fileSize} bytes)")

                // Try API first if enabled
                val apiResult = if (shouldUseAPI()) {
                    if (apiRateLimiter.allowRequest()) {
                        Logger.debug("Using API for analysis")
                        if (settings.enableHeatmap) {
                            apiClient?.analyzeImageWithHeatmap(imageData)
                        } else {
                            apiClient?.analyzeImage(imageData)
                        }
                    } else {
                        Logger.warn("API rate limited")
                        null
                    }
                } else {
                    null
                }

                if (apiResult?.isSuccess == true) {
                    val result = apiResult.getOrNull()!!
                    val processingTime = System.currentTimeMillis() - startTime

                    Logger.info("API analysis successful: AI=${result.isAI}, confidence=${result.confidence}")

                    analysisResult = AnalysisUIState(
                        isAI = result.isAI,
                        confidence = result.confidence,
                        processingTimeMs = processingTime,
                        detailedFeatures = result.detailedFeatures,
                        heatmapImage = result.heatmap?.let { HeatmapUtils.decodeHeatmapImage(it) }
                    )

                    // Save to history
                    saveToHistory(fileName, fileSize, result, processingTime)
                } else {
                    Logger.warn("API analysis failed or unavailable: ${apiResult?.exceptionOrNull()}")
                    analyzeWithLocalModel(imageData, fileName, fileSize, startTime)
                }
            } catch (e: Exception) {
                Logger.error("Analysis failed", e)
                errorMessage = when {
                    e.message?.contains("timeout", ignoreCase = true) == true ->
                        "Analysis timed out. Check API connection."
                    e.message?.contains("memory", ignoreCase = true) == true ->
                        "Image too large for processing"
                    else -> "Analysis failed: ${e.message}"
                }
                try {
                    analyzeWithLocalModel(imageData, fileName, fileSize, startTime)
                } catch (fallbackError: Exception) {
                    Logger.error("Fallback analysis also failed", fallbackError)
                }
            } finally {
                isLoading = false
                analysisRateLimiter.finishAnalysis()
            }
        }
    }

    private fun validateImageInput(imageData: ByteArray, fileName: String, fileSize: Long): Boolean {
        // Validate file name
        if (fileName.isBlank()) {
            errorMessage = "Invalid file name"
            Logger.warn("Invalid file name")
            return false
        }

        // Validate image size (max 50MB)
        val maxSizeBytes = 50 * 1024 * 1024
        if (imageData.size > maxSizeBytes) {
            errorMessage = "Image too large (max 50MB). Got ${imageData.size / (1024 * 1024)}MB"
            Logger.warn("Image too large: ${imageData.size} bytes")
            return false
        }

        // Validate minimum size (at least 10 bytes)
        if (imageData.size < 10) {
            errorMessage = "Image too small or invalid"
            Logger.warn("Image too small: ${imageData.size} bytes")
            return false
        }

        return true
    }

    private suspend fun saveToHistory(
        fileName: String,
        fileSize: Long,
        result: ApiAnalysisResult,
        processingTime: Long
    ) {
        try {
            val entry = AnalysisHistoryEntry(
                fileName = fileName.take(100),  // Limit filename length
                fileSize = fileSize,
                isAI = result.isAI,
                confidence = abs(result.confidence).coerceIn(0f, 1f),  // Ensure valid range
                analysisMode = settings.analysisMode.name,
                processingTimeMs = processingTime,
                heatmapBase64 = result.heatmap?.take(1_000_000)  // Limit heatmap size
            )
            historyRepository.addEntry(entry)
            loadHistory()
            Logger.debug("Result saved to history")
        } catch (e: Exception) {
            Logger.error("Failed to save to history", e)
            // Don't fail the analysis if history save fails
        }
    }

    private suspend fun analyzeWithLocalModel(
        imageData: ByteArray,
        fileName: String,
        fileSize: Long,
        startTime: Long
    ) {
        try {
            Logger.debug("Running on-device heuristic analysis")
            val result = heuristicDetector.analyze(imageData)
            val processingTime = System.currentTimeMillis() - startTime

            analysisResult = AnalysisUIState(
                isAI = result.isAI,
                confidence = result.confidence,
                processingTimeMs = processingTime,
                processedImage = result.processedImage,
                detailedFeatures = result.features
            )

            val entry = AnalysisHistoryEntry(
                fileName = fileName.take(100),
                fileSize = fileSize,
                isAI = result.isAI,
                confidence = abs(result.confidence).coerceIn(0f, 1f),
                analysisMode = "LOCAL",
                processingTimeMs = processingTime
            )
            historyRepository.addEntry(entry)
            loadHistory()
            Logger.info("Heuristic analysis successful: confidence=${result.confidence}")
        } catch (e: Exception) {
            Logger.error("Heuristic analysis failed", e)
            errorMessage = "Local analysis failed: ${e.message}"
        }
    }

    private fun shouldUseAPI(): Boolean {
        return settings.apiBaseUrl.isNotBlank() &&
               settings.apiBaseUrl.startsWith("http")
    }

    fun updateSettings(newSettings: AppSettings) {
        // Validate settings
        if (!validateSettings(newSettings)) return

        coroutineScope.launch {
            try {
                Logger.debug("Updating settings")
                settingsRepository.saveSettings(newSettings)
                settings = newSettings
                apiClient = ApiClient(newSettings)
                Logger.info("Settings updated successfully")
                errorMessage = "✓ Settings saved"
            } catch (e: Exception) {
                Logger.error("Failed to save settings", e)
                errorMessage = "Failed to save settings: ${e.message}"
            }
        }
    }

    private fun validateSettings(settings: AppSettings): Boolean {
        // Validate timeout
        if (settings.apiTimeout < 1000 || settings.apiTimeout > 300000) {
            errorMessage = "Invalid timeout (must be 1-300 seconds)"
            Logger.warn("Invalid timeout: ${settings.apiTimeout}")
            return false
        }

        // Validate confidence threshold
        if (settings.confidenceThreshold < 0f || settings.confidenceThreshold > 1f) {
            errorMessage = "Invalid confidence threshold (must be 0-1)"
            Logger.warn("Invalid confidence: ${settings.confidenceThreshold}")
            return false
        }

        // Validate cache count
        if (settings.cacheResultsCount < 1 || settings.cacheResultsCount > 1000) {
            errorMessage = "Invalid cache size (must be 1-1000)"
            Logger.warn("Invalid cache size: ${settings.cacheResultsCount}")
            return false
        }

        return true
    }

    fun loadHistory() {
        coroutineScope.launch {
            try {
                Logger.debug("Loading history")
                analysisHistory = historyRepository.getHistory(settings.cacheResultsCount)
                Logger.debug("Loaded ${analysisHistory.size} history entries")
            } catch (e: Exception) {
                Logger.error("Failed to load history", e)
                errorMessage = "Failed to load history"
            }
        }
    }

    fun deleteHistoryEntry(id: String) {
        coroutineScope.launch {
            try {
                Logger.debug("Deleting history entry: $id")
                historyRepository.deleteEntry(id)
                loadHistory()
            } catch (e: Exception) {
                Logger.error("Failed to delete history entry", e)
                errorMessage = "Failed to delete entry"
            }
        }
    }

    fun clearHistory() {
        coroutineScope.launch {
            try {
                Logger.info("Clearing all history")
                historyRepository.clearHistory()
                loadHistory()
                errorMessage = "✓ History cleared"
            } catch (e: Exception) {
                Logger.error("Failed to clear history", e)
                errorMessage = "Failed to clear history"
            }
        }
    }

    fun searchHistory(query: String) {
        if (query.isBlank()) {
            loadHistory()
            return
        }

        coroutineScope.launch {
            try {
                Logger.debug("Searching history for: $query")
                analysisHistory = historyRepository.searchHistory(query)
            } catch (e: Exception) {
                Logger.error("Search failed", e)
                errorMessage = "Search failed"
            }
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
        Logger.debug("Selected history entry: ${entry.id}")
    }

    fun testApiConnection() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                Logger.debug("Testing API connection to ${settings.apiBaseUrl}")
                val result = apiClient?.healthCheck()
                if (result?.isSuccess == true) {
                    Logger.info("API connection successful")
                    errorMessage = "✓ API connection successful"
                } else {
                    Logger.warn("API connection failed: ${result?.exceptionOrNull()}")
                    errorMessage = "✗ API connection failed"
                }
            } catch (e: Exception) {
                Logger.error("API health check failed", e)
                errorMessage = "✗ API connection failed: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun clearError() {
        errorMessage = null
    }

    /**
     * Must be called when Activity/Screen is destroyed to prevent memory leaks
     */
    fun dispose() {
        Logger.debug("AppViewModelV2 disposing...")
        try {
            apiClient?.close()
        } catch (e: Exception) {
            Logger.warn("ApiClient.close() failed: ${e.message}")
        }
        apiClient = null
        job.cancel()
        Logger.info("AppViewModelV2 disposed")
    }
}
