package com.myapplication.common.data

import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

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
            e.printStackTrace()
            AppSettings()
        }
    }

    actual suspend fun saveSettings(settings: AppSettings) {
        try {
            val jsonString = json.encodeToString(AppSettings.serializer(), settings)
            userDefaults.setObject(jsonString, "ai_detector_settings")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual suspend fun resetToDefaults() {
        saveSettings(AppSettings())
    }
}
