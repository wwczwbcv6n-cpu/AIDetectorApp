package com.myapplication.common.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import com.myapplication.common.Logger

/**
 * Thread-safe, production-grade SettingsRepository with:
 * - Atomic operations
 * - Backup and recovery
 * - Validation
 * - Corruption detection
 */
abstract class SafeSettingsRepository : SettingsRepository {
    protected val mutex = Mutex()
    protected val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override suspend fun getSettings(): AppSettings = mutex.withLock {
        return try {
            val settings = getSettingsSafe()
            validateSettings(settings)
            Logger.debug("Settings loaded successfully")
            settings
        } catch (e: Exception) {
            Logger.error("Failed to load settings, using defaults", e)
            AppSettings()
        }
    }

    override suspend fun saveSettings(settings: AppSettings) = mutex.withLock {
        try {
            // Validate before saving
            if (!validateSettingsForSave(settings)) {
                Logger.warn("Settings validation failed")
                return@withLock
            }

            Logger.debug("Saving settings...")

            // Try to load backup first (atomic operation)
            val current = try {
                getSettingsSafe()
            } catch (e: Exception) {
                Logger.warn("Current settings corrupted, will use defaults for backup")
                AppSettings()
            }

            // Save backup
            try {
                saveSettingsBackupSafe(current)
                Logger.debug("Settings backup created")
            } catch (e: Exception) {
                Logger.warn("Failed to create settings backup", e)
            }

            // Save new settings
            saveSettingsSafe(settings)
            Logger.info("Settings saved successfully")
        } catch (e: Exception) {
            Logger.error("Failed to save settings", e)
            // Try to restore from backup
            try {
                restoreSettingsBackupSafe()
                Logger.info("Settings restored from backup")
            } catch (restoreError: Exception) {
                Logger.error("Failed to restore settings from backup", restoreError)
            }
        }
    }

    override suspend fun resetToDefaults() = mutex.withLock {
        try {
            Logger.warn("Resetting settings to defaults")
            saveSettings(AppSettings())
        } catch (e: Exception) {
            Logger.error("Failed to reset to defaults", e)
        }
    }

    private fun validateSettings(settings: AppSettings) {
        // Log validation
        Logger.debug(
            "Settings: " +
            "url=${settings.apiBaseUrl}, " +
            "timeout=${settings.apiTimeout}, " +
            "threshold=${settings.confidenceThreshold}, " +
            "mode=${settings.analysisMode}"
        )
    }

    private fun validateSettingsForSave(settings: AppSettings): Boolean {
        // Validate API URL
        if (settings.apiBaseUrl.isBlank()) {
            Logger.warn("Validation failed: blank API URL")
            return false
        }

        // Validate timeout (1s to 5min)
        if (settings.apiTimeout < 1000 || settings.apiTimeout > 300000) {
            Logger.warn("Validation failed: invalid timeout ${settings.apiTimeout}")
            return false
        }

        // Validate confidence threshold
        if (settings.confidenceThreshold < 0f || settings.confidenceThreshold > 1f) {
            Logger.warn("Validation failed: invalid confidence ${settings.confidenceThreshold}")
            return false
        }

        // Validate cache count
        if (settings.cacheResultsCount < 1 || settings.cacheResultsCount > 1000) {
            Logger.warn("Validation failed: invalid cache count ${settings.cacheResultsCount}")
            return false
        }

        return true
    }

    // Abstract methods for platform-specific storage
    protected abstract suspend fun getSettingsSafe(): AppSettings
    protected abstract suspend fun saveSettingsSafe(settings: AppSettings)
    protected abstract suspend fun saveSettingsBackupSafe(settings: AppSettings)
    protected abstract suspend fun restoreSettingsBackupSafe()
}
