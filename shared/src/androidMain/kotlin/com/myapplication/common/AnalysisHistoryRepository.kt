package com.myapplication.common.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

actual class AnalysisHistoryRepository(private val context: Context) {
    // Lazy + IO-dispatched for the same reason as SettingsRepository: keystore
    // + disk work must not run on the main thread in Activity.onCreate.
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ai_detector_history",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val historyKey = "analysis_history"

    /** Non-suspending read for internal reuse; callers already on IO. */
    private fun readAll(): List<AnalysisHistoryEntry> {
        return try {
            val jsonString = prefs.getString(historyKey, null) ?: return emptyList()
            json.decodeFromString(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun writeAll(entries: List<AnalysisHistoryEntry>) {
        try {
            prefs.edit().putString(historyKey, json.encodeToString(entries)).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual suspend fun addEntry(entry: AnalysisHistoryEntry) = withContext(Dispatchers.IO) {
        val updated = listOf(entry) + readAll()
        val maxEntries = 100
        writeAll(if (updated.size > maxEntries) updated.take(maxEntries) else updated)
    }

    actual suspend fun getHistory(limit: Int): List<AnalysisHistoryEntry> =
        withContext(Dispatchers.IO) { readAll().take(limit) }

    actual suspend fun getEntry(id: String): AnalysisHistoryEntry? =
        withContext(Dispatchers.IO) { readAll().find { it.id == id } }

    actual suspend fun deleteEntry(id: String) = withContext(Dispatchers.IO) {
        writeAll(readAll().filter { it.id != id })
    }

    actual suspend fun clearHistory() = withContext(Dispatchers.IO) {
        try {
            prefs.edit().remove(historyKey).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual suspend fun searchHistory(query: String): List<AnalysisHistoryEntry> =
        withContext(Dispatchers.IO) {
            val lowerQuery = query.lowercase()
            readAll().filter { entry ->
                entry.fileName.lowercase().contains(lowerQuery) ||
                    entry.statusText.lowercase().contains(lowerQuery)
            }
        }
}
