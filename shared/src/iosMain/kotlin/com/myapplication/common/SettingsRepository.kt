package com.myapplication.common.data

import com.myapplication.common.Logger
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/**
 * iOS settings persistence.
 *
 * SECURITY (#3): NSUserDefaults is plaintext (stored in the app container's
 * Library/Preferences plist) and is the WRONG place for the X-API-Key, which is
 * a credential. The correct store is the iOS Keychain (kSecClassGenericPassword
 * via the Security framework). That requires a Security-framework cinterop
 * binding which this module does not yet configure, so migrating is tracked as
 * follow-up rather than done blind here.
 *
 * Mitigations applied now:
 *   - The key is NEVER written to the log (we log messages, not values).
 *   - The whole AppSettings blob (incl. the key) stays in the per-app sandbox;
 *     on a non-jailbroken device other apps cannot read it.
 *
 * TODO(security): move `apiKey` specifically into the Keychain (and keep the
 * non-secret prefs in NSUserDefaults) once the Security cinterop is wired up.
 */
actual class SettingsRepository {
    private val userDefaults = NSUserDefaults.standardUserDefaults()
    private val json = Json { ignoreUnknownKeys = true }

    actual suspend fun getSettings(): AppSettings {
        return try {
            val jsonString = userDefaults.stringForKey("ai_detector_settings")
            if (jsonString != null) {
                json.decodeFromString<AppSettings>(jsonString)
            } else {
                AppSettings()
            }
        } catch (e: Exception) {
            // Do not log the raw value — it contains the API key.
            Logger.error("Failed to read settings: ${e.message}")
            AppSettings()
        }
    }

    actual suspend fun saveSettings(settings: AppSettings) {
        try {
            val jsonString = json.encodeToString(AppSettings.serializer(), settings)
            userDefaults.setObject(jsonString, "ai_detector_settings")
        } catch (e: Exception) {
            Logger.error("Failed to save settings: ${e.message}")
        }
    }

    actual suspend fun resetToDefaults() {
        saveSettings(AppSettings())
    }
}
