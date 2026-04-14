package com.myapplication.common.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import com.myapplication.common.Logger

/**
 * Thread-safe, production-grade AnalysisHistoryRepository implementation
 * for common code. Platform-specific subclasses should override storage methods.
 */
abstract class SafeAnalysisHistoryRepository : AnalysisHistoryRepository {
    protected val mutex = Mutex()
    protected val json = Json { ignoreUnknownKeys = true }

    override suspend fun addEntry(entry: AnalysisHistoryEntry) = mutex.withLock {
        try {
            // Validate entry
            if (entry.fileName.isBlank()) {
                Logger.warn("Invalid entry: blank filename")
                return@withLock
            }

            if (entry.confidence < 0f || entry.confidence > 1f) {
                Logger.warn("Invalid entry: confidence ${entry.confidence} out of range")
                return@withLock
            }

            Logger.debug("Adding entry: ${entry.fileName}")
            val current = getHistorySafe(1000)
            val updated = listOf(entry) + current
            val maxEntries = 100
            val trimmed = if (updated.size > maxEntries) {
                updated.take(maxEntries)
            } else {
                updated
            }

            saveHistorySafe(trimmed)
            Logger.debug("Entry added successfully, total: ${trimmed.size}")
        } catch (e: Exception) {
            Logger.error("Failed to add entry", e)
        }
    }

    override suspend fun getHistory(limit: Int): List<AnalysisHistoryEntry> = mutex.withLock {
        return getHistorySafe(limit)
    }

    override suspend fun getEntry(id: String): AnalysisHistoryEntry? = mutex.withLock {
        return try {
            getHistorySafe(1000).find { it.id == id }
        } catch (e: Exception) {
            Logger.error("Failed to get entry", e)
            null
        }
    }

    override suspend fun deleteEntry(id: String) = mutex.withLock {
        try {
            Logger.debug("Deleting entry: $id")
            val current = getHistorySafe(1000)
            val updated = current.filter { it.id != id }
            saveHistorySafe(updated)
            Logger.debug("Entry deleted")
        } catch (e: Exception) {
            Logger.error("Failed to delete entry", e)
        }
    }

    override suspend fun clearHistory() = mutex.withLock {
        try {
            Logger.info("Clearing all history")
            clearHistorySafe()
        } catch (e: Exception) {
            Logger.error("Failed to clear history", e)
        }
    }

    override suspend fun searchHistory(query: String): List<AnalysisHistoryEntry> = mutex.withLock {
        return try {
            val lowerQuery = query.lowercase().trim()
            if (lowerQuery.isBlank()) {
                return getHistorySafe(1000)
            }

            getHistorySafe(1000).filter { entry ->
                entry.fileName.lowercase().contains(lowerQuery) ||
                entry.statusText.lowercase().contains(lowerQuery)
            }
        } catch (e: Exception) {
            Logger.error("Search failed", e)
            emptyList()
        }
    }

    // Abstract methods for platform-specific storage
    protected abstract suspend fun getHistorySafe(limit: Int): List<AnalysisHistoryEntry>
    protected abstract suspend fun saveHistorySafe(entries: List<AnalysisHistoryEntry>)
    protected abstract suspend fun clearHistorySafe()
}
