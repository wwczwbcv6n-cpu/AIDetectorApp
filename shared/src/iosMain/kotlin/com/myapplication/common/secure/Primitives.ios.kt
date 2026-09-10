package com.myapplication.common.secure

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * iOS primitives: HPKE / AES-GCM / RS256 go through the Swift-implemented
 * [HpkeBridge] (CryptoKit + Security.framework); SHA-256 and randomness use
 * CommonCrypto / SecRandomCopyBytes directly from Kotlin/Native.
 *
 * NOT COMPILED IN THIS ENVIRONMENT (Linux build host, no iOS toolchain).
 */
private object IosHpke : HpkePrimitives {
    private fun bridge(): HpkeBridge = HpkeBridgeRegistry.bridge
        ?: throw IllegalStateException(
            "HpkeBridge not installed — Swift must call HpkeBridgeRegistry.install at launch (iosApp/HpkeBridge.swift)"
        )

    override fun sealOnce(
        recipientPublicKey: ByteArray,
        info: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
        exporterContext: ByteArray,
        exportLength: Int,
        deterministicEphemeralPrivateKey: ByteArray?,
    ): HpkeSealed {
        // CryptoKit has no hook for a fixed ephemeral key; the SPEC's fixed-skE
        // vectors run on the JVM. Refuse rather than silently ignore.
        require(deterministicEphemeralPrivateKey == null) {
            "deterministic ephemeral keys are unsupported on iOS (test vectors only)"
        }
        val sealed = bridge().seal(
            recipientPublicKey.toNSData(), info.toNSData(), aad.toNSData(),
            plaintext.toNSData(), exporterContext.toNSData(), exportLength,
        ) ?: throw IllegalStateException("CryptoKit HPKE seal failed (requires iOS 17 or newer)")
        return HpkeSealed(sealed.enc.toByteArray(), sealed.ciphertext.toByteArray(), sealed.exportedKey.toByteArray())
    }

    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextWithTag: ByteArray): ByteArray =
        bridge().aesGcmOpen(key.toNSData(), nonce.toNSData(), aad.toNSData(), ciphertextWithTag.toNSData())
            ?.toByteArray()
            ?: throw IllegalStateException("AES-GCM authentication failed")
}

actual fun platformHpke(): HpkePrimitives = IosHpke

@OptIn(ExperimentalForeignApi::class)
actual fun sha256Bytes(data: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    if (data.isNotEmpty()) {
        data.usePinned { pinned ->
            digest.usePinned { out ->
                CC_SHA256(pinned.addressOf(0), data.size.toUInt(), out.addressOf(0))
            }
        }
    } else {
        digest.usePinned { out -> CC_SHA256(null, 0u, out.addressOf(0)) }
    }
    return digest.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(count: Int): ByteArray {
    if (count <= 0) return ByteArray(0)
    val out = ByteArray(count)
    val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.toULong(), it.addressOf(0)) }
    check(status == 0) { "SecRandomCopyBytes failed" }
    return out
}

actual fun rs256Verify(modulus: ByteArray, exponent: ByteArray, signingInput: ByteArray, signature: ByteArray): Boolean =
    HpkeBridgeRegistry.bridge
        ?.rs256Verify(modulus.toNSData(), exponent.toNSData(), signingInput.toNSData(), signature.toNSData())
        ?: false
