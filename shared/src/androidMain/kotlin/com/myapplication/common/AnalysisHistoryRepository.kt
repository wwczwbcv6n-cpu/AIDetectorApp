package com.myapplication.common.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

actual class AnalysisHistoryRepository(private val context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ai_detector_history",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val historyKey = "analysis_history"

    actual suspend fun addEntry(entry: AnalysisHistoryEntry) {
        try {
            val current = getHistory(1000)
            val updated = listOf(entry) + current
            val maxEntries = 100
            val trimmed = if (updated.size > maxEntries) {
                updated.take(maxEntries)
            } else {
                updated
            }
            val jsonString = json.encodeToString(trimmed)
            prefs.edit().putString(historyKey, jsonString).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual suspend fun getHistory(limit: Int): List<AnalysisHistoryEntry> {
        return try {
            val jsonString = prefs.getString(historyKey, null) ?: return emptyList()
            val all: List<AnalysisHistoryEntry> = json.decodeFromString(jsonString)
            all.take(limit)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    actual suspend fun getEntry(id: String): AnalysisHistoryEntry? {
        return getHistory(1000).find { it.id == id }
    }

    actual suspend fun deleteEntry(id: String) {
        try {
            val current = getHistory(1000)
            val updated = current.filter { it.id != id }
            val jsonString = json.encodeToString(updated)
            prefs.edit().putString(historyKey, jsonString).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual suspend fun clearHistory() {
        try {
            prefs.edit().remove(historyKey).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual suspend fun searchHistory(query: String): List<AnalysisHistoryEntry> {
        val lowerQuery = query.lowercase()
        return getHistory(1000).filter { entry ->
            entry.fileName.lowercase().contains(lowerQuery) ||
            entry.statusText.lowercase().contains(lowerQuery)
        }
    }
}
