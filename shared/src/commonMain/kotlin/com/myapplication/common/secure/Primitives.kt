package com.myapplication.common.secure

/**
 * The platform crypto primitives the TSE2 envelope needs. Everything above
 * this line (framing, AAD, kid, response opening, attestation policy) is
 * common code; everything below it is a per-platform `actual`:
 *
 *  - Android + desktop (JVM): BouncyCastle lightweight API
 *    (`org.bouncycastle.crypto.hpke.HPKE`, NO provider registration — Android
 *    ships its own stripped provider that is also named "BC").
 *  - iOS: CryptoKit via [HpkeBridge], a Kotlin interface implemented in
 *    Swift and injected at app start (Kotlin/Native cannot import a pure
 *    Swift module such as CryptoKit).
 */

/** Result of a single-shot HPKE base-mode seal. */
class HpkeSealed(
    /** HPKE encapsulated key (32 bytes for DHKEM(X25519)). */
    val enc: ByteArray,
    /** AEAD output of the first (and only) Seal, GCM tag included. */
    val ciphertext: ByteArray,
    /** `ctx.Export(exporterContext, exportLength)` from the SAME sender context. */
    val exportedKey: ByteArray,
)

interface HpkePrimitives {
    /**
     * HPKE base mode, DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM
     * (RFC 9180): SetupBaseS(pkR, info) -> Seal(aad, plaintext) with seq 0 ->
     * Export(exporterContext, exportLength). The sender context is discarded
     * afterwards; the exported key is all the client needs to open the reply.
     *
     * [deterministicEphemeralPrivateKey] is for TEST VECTORS ONLY (the SPEC's
     * fixed `skE`). Production callers MUST leave it null so the ephemeral key
     * comes from the platform CSPRNG. Implementations that cannot honour it
     * (CryptoKit has no such hook) must throw rather than ignore it.
     */
    fun sealOnce(
        recipientPublicKey: ByteArray,
        info: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
        exporterContext: ByteArray,
        exportLength: Int,
        deterministicEphemeralPrivateKey: ByteArray? = null,
    ): HpkeSealed

    /** AES-256-GCM decrypt; [ciphertextWithTag] = ct || 16-byte tag. Throws on failure. */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextWithTag: ByteArray): ByteArray
}

/** The platform's [HpkePrimitives]. */
expect fun platformHpke(): HpkePrimitives

/** SHA-256 digest (32 bytes). */
expect fun sha256Bytes(data: ByteArray): ByteArray

/** Cryptographically secure random bytes from the platform CSPRNG. */
expect fun secureRandomBytes(count: Int): ByteArray

/**
 * RSASSA-PKCS1-v1_5 with SHA-256 ("RS256") verification for a JWK RSA public
 * key given as unsigned big-endian [modulus] / [exponent]. Returns false on
 * any failure; never throws for malformed input.
 */
expect fun rs256Verify(modulus: ByteArray, exponent: ByteArray, signingInput: ByteArray, signature: ByteArray): Boolean
