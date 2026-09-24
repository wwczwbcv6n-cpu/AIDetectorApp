package com.myapplication.common.data

import kotlinx.serialization.Serializable

/**
 * App configuration settings persisted to device storage.
 *
 * `apiBaseUrl` no longer ships with a developer's LAN IP — the user
 * configures their own server in the Settings screen on first run.
 * Empty default forces them to do so before /analyze can fire.
 *
 * `apiKey` carries the X-API-Key header value. The production API
 * (api_gateway.py, GATEWAY_ENABLED=1) REQUIRES it on every /analyze call:
 * a keyless request is a 401. Keys are issued at https://tayanch.com/api.
 * Without a working server the app shows "Not analyzed" — there is no
 * on-device verdict (audit 2026-09-24 APP-01; see failureOutcome).
 *
 * `requireAttestation` (default false): when true the upload proceeds only
 * if the server's `/pubkey` evidence VERIFIES against the pinned policy;
 * an UNATTESTED (standard-tier) server is a hard stop. When false the
 * standard tier is allowed and the UI shows the sentence "Standard tier:
 * Tayanch and its hosting provider can technically read this file during
 * analysis." A FAILED classification stops the upload in either mode.
 */
@Serializable
data class AppSettings(
    val apiBaseUrl: String = "",
    val apiKey: String = "",
    val apiTimeout: Long = 60000L, // milliseconds (union head can take ~25s/image on CPU)
    val confidenceThreshold: Float = 0.5f,
    val enableHeatmap: Boolean = true,
    val enableLogging: Boolean = false,
    val analysisMode: AnalysisMode = AnalysisMode.FAST,
    val cacheResultsCount: Int = 100,
    val requireAttestation: Boolean = false,
) {
    enum class AnalysisMode {
        FAST,      // Quick analysis
        BALANCED,  // Medium quality
        DETAILED   // Full heatmap and detailed features
    }

    /** True when the URL is set AND uses a safe scheme.
     *  Plain `http://` is allowed only against localhost, RFC1918 LAN, or a
     *  Tailscale address (100.64.0.0/10 — a private, encrypted device-to-device
     *  mesh, so cleartext over it is not exposed to hostile networks); for
     *  everything else require https. */
    fun isApiUrlAcceptable(): Boolean {
        val u = apiBaseUrl.trim()
        if (u.isBlank()) return false
        if (u.startsWith("https://", ignoreCase = true)) return true
        if (!u.startsWith("http://", ignoreCase = true)) return false
        // Allow http only on localhost / RFC1918 LAN / Tailscale — else https.
        val hostPart = u.removePrefix("http://").substringBefore('/').substringBefore(':')
        if (hostPart.equals("localhost", ignoreCase = true)) return true
        if (hostPart == "10.0.2.2") return true // Android emulator host alias
        // Crude private-range check: 10.x, 172.16-31.x, 192.168.x.x,
        // plus Tailscale CGNAT 100.64.0.0/10 (100.64.x .. 100.127.x).
        val parts = hostPart.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return when {
            parts[0] == 10 -> true
            parts[0] == 172 && parts[1] in 16..31 -> true
            parts[0] == 192 && parts[1] == 168 -> true
            parts[0] == 100 && parts[1] in 64..127 -> true // Tailscale
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
