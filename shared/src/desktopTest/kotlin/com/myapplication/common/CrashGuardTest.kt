package com.myapplication.common

import com.myapplication.common.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit 2026-09-24 APP-06: a saved "Max History Entries" of -1 made
 * readAll().take(-1) throw inside a launch on the ViewModel scope, which had
 * no CoroutineExceptionHandler, so the app died at every start-up before the
 * user could reach Settings. A timeout of 0 or less was accepted too and Ktor
 * rejected it as "Analysis failed unexpectedly." Run: ./gradlew :shared:desktopTest
 */
class CrashGuardTest {

    @Test
    fun anExceptionInAViewModelLaunchIsReportedNotFatal() = runBlocking {
        val seen = mutableListOf<Throwable>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + viewModelExceptionHandler { seen += it })
        scope.launch { listOf(1, 2, 3).take(-1) }.join()
        assertEquals(1, seen.size)
        assertTrue(seen[0] is IllegalArgumentException)
        // the scope survives: a later launch still runs
        var ran = false
        scope.launch { ran = true }.join()
        assertTrue(ran)
    }

    @Test
    fun historyLimitIsNeverNegative() {
        assertEquals(0, safeHistoryLimit(-1))
        assertEquals(100, safeHistoryLimit(100))
        assertEquals(HISTORY_LIMIT, safeHistoryLimit(1_000_000))
    }

    @Test
    fun nonPositiveTimeoutFallsBackToASaneValue() {
        assertEquals(AppSettings().apiTimeout, AppSettings(apiTimeout = 0).effectiveTimeoutMs)
        assertEquals(AppSettings().apiTimeout, AppSettings(apiTimeout = -5).effectiveTimeoutMs)
        assertEquals(5_000L, AppSettings(apiTimeout = 10).effectiveTimeoutMs)
        assertEquals(90_000L, AppSettings(apiTimeout = 90_000).effectiveTimeoutMs)
    }
}
