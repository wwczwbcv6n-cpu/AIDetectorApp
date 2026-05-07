package com.myapplication.common.data

import kotlinx.serialization.Serializable

/**
 * App configuration settings persisted to device storage.
 *
 * `apiBaseUrl` no longer ships with a developer's LAN IP — the user
 * configures their own server in the Settings screen on first run.
 * Empty default forces them to do so before /analyze can fire.
 *
 * `apiKey` carries the X-API-Key header value when set. The server
 * requires it for mutating endpoints (/rag/add, /rag/feedback,
 * /finetune); for /analyze it's optional but recommended so the
 * server's rate limiter identifies your traffic instead of bucketing
 * you with everyone else on the same egress IP.
 */
@Serializable
data class AppSettings(
    val apiBaseUrl: String = "",
    val apiKey: String = "",
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

    /** True when the URL is set AND uses a safe scheme.
     *  Plain `http://` is allowed only against localhost / private LAN
     *  ranges (the network_security_config still blocks it on hostile
     *  networks); for everything else require https. */
    fun isApiUrlAcceptable(): Boolean {
        val u = apiBaseUrl.trim()
        if (u.isBlank()) return false
        if (u.startsWith("https://", ignoreCase = true)) return true
        if (!u.startsWith("http://", ignoreCase = true)) return false
        // Allow http only on localhost or RFC1918 LAN — production must use https.
        val hostPart = u.removePrefix("http://").substringBefore('/').substringBefore(':')
        if (hostPart.equals("localhost", ignoreCase = true)) return true
        if (hostPart == "10.0.2.2") return true // Android emulator host alias
        // Crude RFC1918 check: 10.x, 172.16-31.x, 192.168.x.x
        val parts = hostPart.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return when {
            parts[0] == 10 -> true
            parts[0] == 172 && parts[1] in 16..31 -> true
            parts[0] == 192 && parts[1] == 168 -> true
            else -> false
        }
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
