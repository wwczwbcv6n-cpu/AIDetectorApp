package com.myapplication.common.secure

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android primitives. Mirrors `Primitives.desktop.kt` byte for byte — keep
 * the two in sync; the desktop unit tests exercise the identical code path.
 *
 * Why BouncyCastle's lightweight HPKE and not the platform: `KeyAgreement
 * "XDH"` exists only on API 33+ (research-verified) and this app's minSdk is
 * 24. `org.bouncycastle.crypto.hpke.HPKE` is pure Java, needs no provider
 * registration, and the jar's `org.bouncycastle.*` package does not clash
 * with Android's repackaged `com.android.org.bouncycastle`. Do NOT call
 * `Security.addProvider(BouncyCastleProvider())` here — Android's stripped
 * built-in provider is also named "BC".
 */
internal object JvmHpke : HpkePrimitives {
    private fun suite() = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)

    override fun sealOnce(
        recipientPublicKey: ByteArray,
        info: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
        exporterContext: ByteArray,
        exportLength: Int,
        deterministicEphemeralPrivateKey: ByteArray?,
    ): HpkeSealed {
        val hpke = suite()
        val pkR = hpke.deserializePublicKey(recipientPublicKey)
        val ctx = if (deterministicEphemeralPrivateKey == null) {
            hpke.setupBaseS(pkR, info)
        } else {
            // TEST VECTORS ONLY: fixed skE so `secure/vectors.json` is reproducible.
            val sk = X25519PrivateKeyParameters(deterministicEphemeralPrivateKey, 0)
            hpke.setupBaseS(pkR, info, AsymmetricCipherKeyPair(sk.generatePublicKey(), sk))
        }
        val ct = ctx.seal(aad, plaintext)            // seq 0 — the first and only Seal
        val key = ctx.export(exporterContext, exportLength)
        return HpkeSealed(ctx.encapsulation, ct, key)
    }

    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextWithTag)
    }
}

actual fun platformHpke(): HpkePrimitives = JvmHpke

actual fun sha256Bytes(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

private val secureRandom = SecureRandom()

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { secureRandom.nextBytes(it) }

actual fun rs256Verify(modulus: ByteArray, exponent: ByteArray, signingInput: ByteArray, signature: ByteArray): Boolean = try {
    val n = BigInteger(1, modulus)
    if (n.bitLength() < 2048) {
        false
    } else {
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, BigInteger(1, exponent)))
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initVerify(key)
        sig.update(signingInput)
        sig.verify(signature)
    }
} catch (e: Exception) {
    false
}
