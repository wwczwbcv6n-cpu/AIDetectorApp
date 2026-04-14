package com.myapplication.common.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import platform.Foundation.NSUserDefaults

actual class AnalysisHistoryRepository {
    private val userDefaults = NSUserDefaults.standardUserDefaults()
    private val json = Json { ignoreUnknownKeys = true }
    private val historyKey = "ai_detector_history"

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
            userDefaults.setObject(jsonString, historyKey)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual suspend fun getHistory(limit: Int): List<AnalysisHistoryEntry> {
        return try {
            val jsonString = userDefaults.stringForKey(historyKey) ?: return emptyList()
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
            userDefaults.setObject(jsonString, historyKey)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual suspend fun clearHistory() {
        try {
            userDefaults.removeObjectForKey(historyKey)
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
