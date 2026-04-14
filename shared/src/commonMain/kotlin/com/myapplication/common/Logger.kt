package com.myapplication.common

import kotlin.system.measureTimeMillis

/**
 * Production-grade logging infrastructure
 */
object Logger {
    var enabled = false
    private const val TAG = "AIDetector"

    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun log(level: Level, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        val tag = "[$TAG]"
        val levelStr = "[${level.name}]"
        val msg = "$tag $levelStr $message"

        when {
            throwable != null -> println("$msg\n${throwable.stackTraceToString()}")
            else -> println(msg)
        }
    }

    fun debug(message: String) = log(Level.DEBUG, message)
    fun info(message: String) = log(Level.INFO, message)
    fun warn(message: String) = log(Level.WARN, message)
    fun error(message: String, throwable: Throwable? = null) = log(Level.ERROR, message, throwable)

    inline fun <T> debugTime(message: String, block: () -> T): T {
        var result: T
        val ms = measureTimeMillis { result = block() }
        debug("$message completed in ${ms}ms")
        return result
    }
}
