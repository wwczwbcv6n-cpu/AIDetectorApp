package com.myapplication.common.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

actual class SettingsRepository(private val context: Context) {
    // Lazy + accessed only from Dispatchers.IO: building the MasterKey and
    // opening EncryptedSharedPreferences hits the Android Keystore and disk.
    // It used to run in the constructor — i.e. on the main thread during
    // Activity.onCreate — a jank/ANR risk on slower devices.
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ai_detector_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    actual suspend fun getSettings(): AppSettings = withContext(Dispatchers.IO) {
        try {
            val stored = prefs.getString("settings", null)
            if (stored != null) {
                json.decodeFromString<AppSettings>(stored)
            } else {
                AppSettings()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            AppSettings()
        }
    }

    actual suspend fun saveSettings(settings: AppSettings) = withContext(Dispatchers.IO) {
        try {
            val encoded = json.encodeToString(AppSettings.serializer(), settings)
            prefs.edit().putString("settings", encoded).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual suspend fun resetToDefaults() {
        saveSettings(AppSettings())
    }
}
