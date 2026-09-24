package com.myapplication.common.data

import com.myapplication.common.Logger
import com.myapplication.common.applySettingsSideEffects
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every setting the user can change must change something (audit 2026-09-24
 * APP-05). The confidence-threshold slider only fed a legacy fallback the
 * served API never reaches (it always sends `verdict`); the heatmap toggle and
 * the FAST/BALANCED/DETAILED mode were unwired; "Enable Debug Logging" was
 * never read (Logger stayed on in release); "Max History Entries" only trimmed
 * the list the repository already capped at 100 — and a negative value crashed
 * the app at start-up (APP-06). Run: ./gradlew :shared:desktopTest
 */
class AppSettingsTest {

    @AfterTest
    fun resetLogger() {
        Logger.enabled = false
    }

    @Test
    fun everyPersistedSettingIsOneTheAppReads() {
        val d = AppSettings.serializer().descriptor
        val fields = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
        // apiBaseUrl/apiKey/apiTimeout/requireAttestation: ApiClient;
        // enableLogging: applySettingsSideEffects -> Logger.enabled.
        assertEquals(setOf("apiBaseUrl", "apiKey", "apiTimeout", "enableLogging", "requireAttestation"), fields)
    }

    @Test
    fun settingsSavedByTheOldAppStillLoad() {
        val old = """{"apiBaseUrl": "https://api.example.test", "apiKey": "k", "apiTimeout": 60000,
            "confidenceThreshold": 0.7, "enableHeatmap": true, "enableLogging": true,
            "analysisMode": "DETAILED", "cacheResultsCount": -1, "requireAttestation": true}"""
        val s = Json { ignoreUnknownKeys = true }.decodeFromString(AppSettings.serializer(), old)
        assertEquals("https://api.example.test", s.apiBaseUrl)
        assertTrue(s.enableLogging)
        assertTrue(s.requireAttestation)
    }

    @Test
    fun debugLoggingSettingDrivesTheLogger() {
        applySettingsSideEffects(AppSettings(enableLogging = true))
        assertTrue(Logger.enabled)
        applySettingsSideEffects(AppSettings(enableLogging = false))
        assertFalse(Logger.enabled)
    }

    @Test
    fun loggingIsOffByDefault() {
        assertFalse(AppSettings().enableLogging)
    }
}
