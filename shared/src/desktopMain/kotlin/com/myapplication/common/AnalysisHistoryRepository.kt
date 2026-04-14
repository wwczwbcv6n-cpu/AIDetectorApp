package com.myapplication.common.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

actual class AnalysisHistoryRepository {
    private val historyFile = File(
        System.getProperty("user.home"),
        ".config/ai_detector/history.json"
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    init {
        historyFile.parentFile?.mkdirs()
    }

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
            historyFile.writeText(json.encodeToString(trimmed))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual suspend fun getHistory(limit: Int): List<AnalysisHistoryEntry> {
        return try {
            if (!historyFile.exists()) return emptyList()
            val content = historyFile.readText()
            val all: List<AnalysisHistoryEntry> = json.decodeFromString(content)
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
            historyFile.writeText(json.encodeToString(updated))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual suspend fun clearHistory() {
        try {
            historyFile.delete()
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
