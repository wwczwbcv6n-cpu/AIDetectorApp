package com.myapplication.common.secure

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * iOS crypto bridge.
 *
 * Kotlin/Native cannot import a pure Swift module, and CryptoKit (which owns
 * the only HPKE implementation on iOS, 17+) is pure Swift. So the primitives
 * are declared here as a Kotlin interface — exported into the `shared`
 * framework as an Objective-C protocol — and IMPLEMENTED IN SWIFT
 * (`iosApp/iosApp/HpkeBridge.swift`, class `CryptoKitHpkeBridge`), then
 * injected at launch through [HpkeBridgeRegistry.install]. Until that call
 * happens every sealed upload fails closed ("HpkeBridge not installed").
 *
 * Byte buffers cross as `NSData` (Swift `Data`) rather than `KotlinByteArray`
 * so a multi-megabyte image is a single memcpy, not millions of ObjC calls.
 * Methods return null on failure instead of throwing: a Swift class cannot
 * throw into a Kotlin interface method without an NSError** convention.
 */
class HpkeBridgeSealed(val enc: NSData, val ciphertext: NSData, val exportedKey: NSData)

interface HpkeBridge {
    /**
     * HPKE base mode DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM:
     * Sender(recipientKey, info) -> seal(plaintext, aad) once ->
     * exportSecret(exporterContext, exportLength). Null on failure (including
     * iOS < 17, where CryptoKit has no HPKE).
     */
    fun seal(
        recipientPublicKey: NSData,
        info: NSData,
        aad: NSData,
        plaintext: NSData,
        exporterContext: NSData,
        exportLength: Int,
    ): HpkeBridgeSealed?

    /** AES-256-GCM open; [ciphertextWithTag] = ct || 16-byte tag. Null on authentication failure. */
    fun aesGcmOpen(key: NSData, nonce: NSData, aad: NSData, ciphertextWithTag: NSData): NSData?

    /** RSASSA-PKCS1-v1_5 / SHA-256 over [message] with the JWK (n, e) public key, via SecKeyVerifySignature. */
    fun rs256Verify(modulus: NSData, exponent: NSData, message: NSData, signature: NSData): Boolean
}

object HpkeBridgeRegistry {
    var bridge: HpkeBridge? = null
        private set

    /** Called once from Swift at app launch. */
    fun install(bridge: HpkeBridge) {
        this.bridge = bridge
    }

    val isInstalled: Boolean get() = bridge != null
}

@OptIn(ExperimentalForeignApi::class)
fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    return ByteArray(n).also { out ->
        out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
}
