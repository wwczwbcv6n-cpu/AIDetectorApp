package com.myapplication.common.data

import com.myapplication.common.Logger
import com.myapplication.common.secure.toByteArray
import com.myapplication.common.secure.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionComplete
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.numberWithBool
import platform.Foundation.writeToURL

/**
 * iOS history store.
 *
 * Was: `NSUserDefaults.standardUserDefaults` — a plaintext plist that is
 * included in iCloud/iTunes backups and (until the purge) carried a base64
 * heatmap rendering of the user's photo. Flagged by the 2026-09 privacy
 * review.
 *
 * Now: `<Application Support>/ai_detector/history.json`, written with
 * `NSDataWritingFileProtectionComplete` (Data Protection class A: encrypted
 * with a key that is evicted while the device is locked) and marked
 * `NSURLIsExcludedFromBackupKey` (never in iCloud/iTunes backups). The
 * directory itself is created with `NSFileProtectionComplete`. On first use
 * a legacy NSUserDefaults blob is migrated into the file (dropping the
 * heatmap field via `ignoreUnknownKeys`) and then removed from the defaults.
 *
 * Limits, stated plainly: class A means the file is unreadable while the
 * device is locked, which also means history cannot be written by any
 * background continuation after lock — acceptable, all writes here happen in
 * the foreground. This is at-rest hygiene for the user's own device; it says
 * nothing about the server tier.
 *
 * NOT COMPILED IN THIS ENVIRONMENT (no Xcode / Kotlin/Native iOS toolchain on
 * the Linux build host). API names follow the Kotlin/Native Foundation
 * bindings; verify on the first macOS build.
 */
@OptIn(ExperimentalForeignApi::class)
actual class AnalysisHistoryRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val fileManager = NSFileManager.defaultManager
    private val legacyDefaultsKey = "ai_detector_history"

    private val fileUrl: NSURL? by lazy { prepareStore() }

    private fun prepareStore(): NSURL? = memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        val base = fileManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = err.ptr,
        )
        if (base == null) {
            Logger.warn("history store: no Application Support directory")
            return null
        }
        val dir = base.URLByAppendingPathComponent("ai_detector", true) ?: return null
        val created = fileManager.createDirectoryAtURL(
            url = dir,
            withIntermediateDirectories = true,
            attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionComplete),
            error = err.ptr,
        )
        if (!created) {
            Logger.warn("history store: could not create directory")
            return null
        }
        excludeFromBackup(dir)
        dir.URLByAppendingPathComponent("history.json", false)
    }

    private fun excludeFromBackup(url: NSURL) = memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        val ok = url.setResourceValue(NSNumber.numberWithBool(true), NSURLIsExcludedFromBackupKey, err.ptr)
        if (!ok) Logger.warn("history store: could not exclude from backup")
    }

    private fun readAll(): List<AnalysisHistoryEntry> {
        val url = fileUrl ?: return emptyList()
        return try {
            val data = NSData.dataWithContentsOfURL(url)
            if (data == null) {
                migrateLegacyDefaults()
            } else {
                val text = data.toByteArray().decodeToString()
                val entries: List<AnalysisHistoryEntry> = json.decodeFromString(text)
                // Purge pre-migration rows that still carry the heatmap rendering.
                if (text.contains(LEGACY_HEATMAP_FIELD)) writeAll(entries)
                entries
            }
        } catch (e: Exception) {
            // Type only — kotlinx decode errors quote the stored JSON.
            Logger.warn("history read failed: ${e::class.simpleName}")
            emptyList()
        }
    }

    /** One-time move of the old NSUserDefaults blob into the protected file. */
    private fun migrateLegacyDefaults(): List<AnalysisHistoryEntry> {
        val defaults = NSUserDefaults.standardUserDefaults
        val legacy = defaults.stringForKey(legacyDefaultsKey) ?: return emptyList()
        val entries: List<AnalysisHistoryEntry> = try {
            json.decodeFromString(legacy)
        } catch (e: Exception) {
            emptyList()
        }
        writeAll(entries)
        defaults.removeObjectForKey(legacyDefaultsKey)
        return entries
    }

    private fun writeAll(entries: List<AnalysisHistoryEntry>): Boolean {
        val url = fileUrl ?: return false
        return try {
            val data = json.encodeToString(entries).encodeToByteArray().toNSData()
            val ok = memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                data.writeToURL(
                    url = url,
                    options = NSDataWritingAtomic or NSDataWritingFileProtectionComplete,
                    error = err.ptr,
                )
            }
            if (ok) excludeFromBackup(url) else Logger.warn("history write failed")
            ok
        } catch (e: Exception) {
            Logger.warn("history write failed: ${e::class.simpleName}")
            false
        }
    }

    actual suspend fun addEntry(entry: AnalysisHistoryEntry) {
        val updated = listOf(entry) + readAll()
        val maxEntries = 100
        writeAll(if (updated.size > maxEntries) updated.take(maxEntries) else updated)
    }

    actual suspend fun getHistory(limit: Int): List<AnalysisHistoryEntry> = readAll().take(com.myapplication.common.safeHistoryLimit(limit))

    actual suspend fun getEntry(id: String): AnalysisHistoryEntry? = readAll().find { it.id == id }

    actual suspend fun deleteEntry(id: String) {
        writeAll(readAll().filter { it.id != id })
    }

    actual suspend fun clearHistory() {
        val url = fileUrl ?: return
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            if (!fileManager.removeItemAtURL(url, err.ptr)) {
                // Missing file is fine; anything else is worth a (type-only) note.
                Logger.debug("history clear: nothing removed")
            }
        }
        // Belt and braces: the legacy blob must not survive a clear either.
        NSUserDefaults.standardUserDefaults.removeObjectForKey(legacyDefaultsKey)
    }

    actual suspend fun searchHistory(query: String): List<AnalysisHistoryEntry> {
        val lowerQuery = query.lowercase()
        return readAll().filter { entry ->
            entry.fileName.lowercase().contains(lowerQuery) ||
            entry.statusText.lowercase().contains(lowerQuery)
        }
    }
}
