package com.myapplication.common

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.getTimeMillis

/**
 * Production-grade rate limiter for preventing spam and overload
 */
class RateLimiter(
    private val maxRequests: Int = 5,
    private val windowMs: Long = 1000L  // 5 requests per second
) {
    private val mutex = Mutex()
    private val timestamps = mutableListOf<Long>()

    suspend fun allowRequest(): Boolean = mutex.withLock {
        val now = getTimeMillis()

        // Remove old timestamps outside the window
        timestamps.removeAll { it < now - windowMs }

        return if (timestamps.size < maxRequests) {
            timestamps.add(now)
            true
        } else {
            false
        }
    }

    suspend fun reset() = mutex.withLock {
        timestamps.clear()
    }

    fun getState(): Pair<Int, Int> = Pair(timestamps.size, maxRequests)
}

/**
 * Rate limiter for analysis requests
 * - Max 1 concurrent analysis per device
 * - Prevents UI button spam
 */
class AnalysisRateLimiter {
    private val mutex = Mutex()
    private var isAnalyzing = false
    private var lastAnalysisTime = 0L

    suspend fun canStartAnalysis(): Boolean = mutex.withLock {
        if (isAnalyzing) return false
        isAnalyzing = true
        lastAnalysisTime = getTimeMillis()
        return true
    }

    suspend fun finishAnalysis() = mutex.withLock {
        isAnalyzing = false
    }

    suspend fun isCurrentlyAnalyzing(): Boolean = mutex.withLock {
        return isAnalyzing
    }
}
