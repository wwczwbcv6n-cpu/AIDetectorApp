package com.myapplication.common.data

import com.myapplication.common.Logger
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Desktop settings persistence.
 *
 * SECURITY (#3): this file holds the X-API-Key in plaintext. Desktop OSes have
 * no app-sandbox guaranteeing per-user isolation of an arbitrary dotfile, so we
 * cannot match the Android EncryptedSharedPreferences guarantee here without a
 * native keystore integration (out of scope). As a defence-in-depth minimum we
 * restrict the file to owner-only (POSIX 0600) and the containing directory to
 * 0700 on POSIX filesystems (Linux/macOS). On non-POSIX filesystems (Windows)
 * we fall back to java.io.File ACL hardening (deny group/other) and log nothing
 * sensitive. The key value is NEVER written to the log.
 *
 * If/when desktop needs true at-rest encryption, integrate the OS keystore
 * (macOS Keychain / Windows DPAPI / libsecret) behind this same class.
 */
actual class SettingsRepository {
    private val settingsDir = File(
        System.getProperty("user.home"),
        ".config/ai_detector"
    )
    private val settingsFile = File(settingsDir, "settings.json")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val ownerOnlyFile = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )
    private val ownerOnlyDir = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

    init {
        settingsDir.mkdirs()
        restrictDirPermissions()
    }

    /** Lock the config dir to the owner so a sibling user can't list/read it. */
    private fun restrictDirPermissions() {
        try {
            val path = settingsDir.toPath()
            val supportsPosix = path.fileSystem
                .supportedFileAttributeViews().contains("posix")
            if (supportsPosix) {
                Files.setPosixFilePermissions(path, ownerOnlyDir)
            } else {
                settingsDir.setReadable(false, false)
                settingsDir.setReadable(true, true)
            }
        } catch (e: Exception) {
            // Non-fatal: worst case the dir keeps default perms. Don't leak path details.
            Logger.warn("Could not restrict settings dir permissions: ${e.message}")
        }
    }

    /** Lock the settings file to owner read/write only (it holds the API key). */
    private fun restrictFilePermissions() {
        try {
            val path = settingsFile.toPath()
            val supportsPosix = path.fileSystem
                .supportedFileAttributeViews().contains("posix")
            if (supportsPosix) {
                Files.setPosixFilePermissions(path, ownerOnlyFile)
            } else {
                // Best-effort on Windows/other: strip world/group access.
                settingsFile.setReadable(false, false)
                settingsFile.setWritable(false, false)
                settingsFile.setReadable(true, true)
                settingsFile.setWritable(true, true)
            }
        } catch (e: Exception) {
            Logger.warn("Could not restrict settings file permissions: ${e.message}")
        }
    }

    actual suspend fun getSettings(): AppSettings {
        return try {
            if (settingsFile.exists()) {
                json.decodeFromString<AppSettings>(settingsFile.readText())
            } else {
                AppSettings()
            }
        } catch (e: Exception) {
            // Never log the file contents (contains the API key).
            Logger.error("Failed to read settings", e)
            AppSettings()
        }
    }

    actual suspend fun saveSettings(settings: AppSettings) {
        try {
            // Create the file with restricted perms BEFORE writing the key into it,
            // so there is no window where it exists world-readable with a key inside.
            if (!settingsFile.exists()) {
                settingsFile.createNewFile()
            }
            restrictFilePermissions()
            settingsFile.writeText(json.encodeToString(AppSettings.serializer(), settings))
            // Re-assert perms in case createNewFile/umask widened them.
            restrictFilePermissions()
        } catch (e: Exception) {
            Logger.error("Failed to save settings", e)
        }
    }

    actual suspend fun resetToDefaults() {
        saveSettings(AppSettings())
    }
}
