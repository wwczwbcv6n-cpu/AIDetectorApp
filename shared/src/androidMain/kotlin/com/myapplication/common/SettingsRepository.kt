package com.myapplication.common.data

import android.content.Context
import kotlinx.serialization.json.Json
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

actual class SettingsRepository(private val context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ai_detector_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val json = Json { ignoreUnknownKeys = true }

    actual suspend fun getSettings(): AppSettings {
        return try {
            val json = prefs.getString("settings", null)
            if (json != null) {
                this.json.decodeFromString<AppSettings>(json)
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
            val json = json.encodeToString(AppSettings.serializer(), settings)
            prefs.edit().putString("settings", json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual suspend fun resetToDefaults() {
        saveSettings(AppSettings())
    }
}
