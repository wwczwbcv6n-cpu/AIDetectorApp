package com.myapplication.common.data

import kotlinx.serialization.Serializable

/**
 * App configuration settings persisted to device storage
 */
@Serializable
data class AppSettings(
    val apiBaseUrl: String = "http://192.168.1.100:8080",
    val apiTimeout: Long = 30000L, // milliseconds
    val confidenceThreshold: Float = 0.5f,
    val enableHeatmap: Boolean = true,
    val enableLogging: Boolean = false,
    val analysisMode: AnalysisMode = AnalysisMode.FAST,
    val cacheResultsCount: Int = 100
) {
    enum class AnalysisMode {
        FAST,      // Quick analysis
        BALANCED,  // Medium quality
        DETAILED   // Full heatmap and detailed features
    }
}

/**
 * Settings repository for persistence
 */
expect class SettingsRepository {
    suspend fun getSettings(): AppSettings
    suspend fun saveSettings(settings: AppSettings)
    suspend fun resetToDefaults()
}
