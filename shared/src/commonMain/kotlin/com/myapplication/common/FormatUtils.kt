package com.myapplication.common

import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Multiplatform number / time formatting helpers.
 *
 * commonMain code can't call `String.format("%.2f", x)` or
 * `java.text.SimpleDateFormat(...)` because those are JVM-only. Each
 * helper here is either pure Kotlin (works on Kotlin/Native too) or an
 * `expect` function whose `actual` implementations live next to the
 * platform's native date/time API.
 */

/**
 * Format a [Double] with exactly [decimals] digits after the dot.
 * Pure Kotlin, no `String.format` — safe in commonMain.
 *
 *   3.14159.formatTo(2) == "3.14"
 *   3.0.formatTo(2)     == "3.00"
 *   -0.005.formatTo(2)  == "-0.01"
 */
fun Double.formatTo(decimals: Int): String {
    require(decimals >= 0) { "decimals must be >= 0, got $decimals" }
    if (this.isNaN()) return "NaN"
    if (this.isInfinite()) return if (this > 0) "Infinity" else "-Infinity"

    val factor = 10.0.pow(decimals)
    val rounded = (this * factor).roundToLong() / factor
    val neg = rounded < 0
    val absStr = rounded.absoluteValue.toString()
    val dot = absStr.indexOf('.')
    val padded = if (decimals == 0) {
        // .roundToLong() / factor for integer decimals still yields "1.0"-ish
        // representations through Double.toString(); strip the trailing ".0".
        if (dot >= 0) absStr.substring(0, dot) else absStr
    } else if (dot < 0) {
        "$absStr." + "0".repeat(decimals)
    } else {
        val tail = absStr.length - dot - 1
        when {
            tail == decimals -> absStr
            tail < decimals  -> absStr + "0".repeat(decimals - tail)
            else             -> absStr.substring(0, dot + 1 + decimals)
        }
    }
    return if (neg) "-$padded" else padded
}

/** Same as [Double.formatTo] but for [Float] arguments. */
fun Float.formatTo(decimals: Int): String = this.toDouble().formatTo(decimals)

/**
 * Format an epoch-millis timestamp as a human-readable string.
 *
 * Implemented per-platform so we can use `SimpleDateFormat` on the JVM
 * (Android, desktop) and `NSDateFormatter` on iOS without dragging in
 * `kotlinx-datetime` as a new dependency. Output format follows the
 * pattern "yyyy-MM-dd HH:mm:ss" (24-hour, UTC offset = device local).
 */
expect fun formatTimestamp(epochMillis: Long): String
