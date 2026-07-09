package com.myapplication.common

/**
 * SHA-256 of the exact upload bytes, hex-encoded.
 *
 * Used as the PRIMARY key for the verdict cache (mid-2026 research §10):
 * a perceptual hash must never be the authoritative cache key — pHash is
 * designed to collide for visually similar images, so an adversary could
 * force a cache hit that returns a stale verdict for a *different* image.
 * Exact byte hashing has no such collision surface.
 */
expect fun sha256Hex(bytes: ByteArray): String
