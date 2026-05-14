package com.myapplication.common

/**
 * Wall-clock time in milliseconds since the Unix epoch.
 *
 * commonMain cannot call `System.currentTimeMillis()` directly — that
 * would compile fine for the JVM (Android, desktop) but Kotlin/Native
 * (iOS) has no `java.lang.System`. Each platform implements this in an
 * `actual` declaration that resolves to the native equivalent.
 */
expect fun nowMillis(): Long
