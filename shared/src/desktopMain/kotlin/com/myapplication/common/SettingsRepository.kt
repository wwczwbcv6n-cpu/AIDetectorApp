package com.myapplication.common.data

import kotlinx.serialization.json.Json
import java.io.File

actual class SettingsRepository {
    private val settingsFile = File(
        System.getProperty("user.home"),
        ".config/ai_detector/settings.json"
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    init {
        settingsFile.parentFile?.mkdirs()
    }

    actual suspend fun getSettings(): AppSettings {
        return try {
            if (settingsFile.exists()) {
                json.decodeFromString<AppSettings>(settingsFile.readText())
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
            settingsFile.writeText(json.encodeToString(AppSettings.serializer(), settings))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual suspend fun resetToDefaults() {
        saveSettings(AppSettings())
    }
}
