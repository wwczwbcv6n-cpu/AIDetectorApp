package com.myapplication.common.secure

import com.myapplication.common.nowMillis

/**
 * Tayanch Secure Envelope v2 (TSE2) — client side of `secure/SPEC.md`.
 *
 * Wire layout (all sizes in bytes, byte-for-byte per the SPEC):
 *
 *   request  = "TYNSEC\x02\x00"(8) || kid(8) || ts(8, u64be) || rid(16) || enc(32) || ct
 *   info     = "tayanch/v2" || kid                               (HPKE SetupBaseS info)
 *   aad      = MAGIC_REQ || kid || ts || rid || route             (route = UTF-8 HTTP path)
 *   ct       = ctx.Seal(aad, plaintext)                           (first and only Seal, seq 0)
 *
 *   response = "TYNSEC\x02\x01"(8) || rid(16) || nonce(12) || AES-256-GCM(key_resp, nonce, aad_resp, json)
 *   key_resp = ctx.Export("tayanch/v2 response" || rid, 32)
 *   aad_resp = MAGIC_RESP || rid
 *
 * What this does and does not give you — stated plainly so nobody upgrades
 * the claim later without a measurement:
 *  - The upload is readable only by whoever holds the private half of `pkR`.
 *    Who that is depends on the TIER (see [TierState]); this class does not
 *    know and does not claim. On the standard tier the operator holds it.
 *  - The response is bound to this request (`rid` in the AAD and in the
 *    exporter context) and to the ephemeral sender context, so a replayed
 *    request yields ciphertext the replayer cannot open.
 *  - There is NO forward secrecy claim for v1/v2 as such: the sender side is
 *    ephemeral, but the server key's lifetime is a server property (an epoch
 *    on the attested tier, a file on the legacy tier). Do not write "forward
 *    secrecy" in UI copy.
 */
object Tse2 {
    val MAGIC_REQ: ByteArray = byteArrayOf('T'.code.toByte(), 'Y'.code.toByte(), 'N'.code.toByte(),
        'S'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte(), 0x02, 0x00)
    val MAGIC_RESP: ByteArray = byteArrayOf('T'.code.toByte(), 'Y'.code.toByte(), 'N'.code.toByte(),
        'S'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte(), 0x02, 0x01)

    const val KID_LEN = 8
    const val TS_LEN = 8
    const val RID_LEN = 16
    const val ENC_LEN = 32
    const val GCM_TAG_LEN = 16
    const val RESP_NONCE_LEN = 12
    const val RESP_KEY_LEN = 32

    /** 8+8+8+16+32 = 72 header bytes; minimum envelope = header + GCM tag = 88. */
    const val REQ_HEADER_LEN = 8 + KID_LEN + TS_LEN + RID_LEN + ENC_LEN
    const val MIN_REQ_LEN = REQ_HEADER_LEN + GCM_TAG_LEN
    const val RESP_HEADER_LEN = 8 + RID_LEN + RESP_NONCE_LEN
    const val MIN_RESP_LEN = RESP_HEADER_LEN + GCM_TAG_LEN

    private val INFO_PREFIX = Bytes.utf8("tayanch/v2")
    private val EXPORT_PREFIX = Bytes.utf8("tayanch/v2 response")

    /** `kid = SHA-256(pkR)[0:8]`. */
    fun kidOf(recipientPublicKey: ByteArray): ByteArray =
        sha256Bytes(recipientPublicKey).copyOfRange(0, KID_LEN)

    /** HPKE `info` for a given kid. */
    fun infoFor(kid: ByteArray): ByteArray = Bytes.concat(INFO_PREFIX, kid)

    /** Exporter context for the response key of a given request id. */
    fun exporterContextFor(rid: ByteArray): ByteArray = Bytes.concat(EXPORT_PREFIX, rid)

    /** Request AAD: MAGIC_REQ || kid || ts || rid || route. */
    fun requestAad(kid: ByteArray, ts: ByteArray, rid: ByteArray, route: String): ByteArray =
        Bytes.concat(MAGIC_REQ, kid, ts, rid, Bytes.utf8(route))

    /**
     * Seal [plaintext] for the HTTP path [route] to the server key ([pubkey], [kid]).
     *
     * [tsUnixSeconds], [rid] and [deterministicEphemeralPrivateKey] exist only so
     * the test suite can reproduce `secure/vectors.json`; production callers use
     * the defaults (wall clock, CSPRNG, CSPRNG).
     */
    fun sealRequest(
        hpke: HpkePrimitives,
        pubkey: ByteArray,
        kid: ByteArray,
        route: String,
        plaintext: ByteArray,
        tsUnixSeconds: Long = nowMillis() / 1000L,
        rid: ByteArray = secureRandomBytes(RID_LEN),
        deterministicEphemeralPrivateKey: ByteArray? = null,
    ): SealedRequest {
        require(pubkey.size == 32) { "pkR must be a raw 32-byte X25519 public key" }
        require(kid.size == KID_LEN) { "kid must be 8 bytes" }
        require(rid.size == RID_LEN) { "rid must be 16 bytes" }
        require(route.startsWith("/") && !route.contains('?') && !route.contains('#')) {
            "route must be an absolute HTTP path without query string"
        }
        // The kid the server advertised MUST be the hash of the key it advertised —
        // otherwise a substituted key could be smuggled in under a trusted kid.
        require(Bytes.constantTimeEquals(kid, kidOf(pubkey))) { "kid does not match SHA-256(pkR)[0:8]" }

        val ts = Bytes.u64be(tsUnixSeconds)
        val aad = requestAad(kid, ts, rid, route)
        val sealed = hpke.sealOnce(
            recipientPublicKey = pubkey,
            info = infoFor(kid),
            aad = aad,
            plaintext = plaintext,
            exporterContext = exporterContextFor(rid),
            exportLength = RESP_KEY_LEN,
            deterministicEphemeralPrivateKey = deterministicEphemeralPrivateKey,
        )
        check(sealed.enc.size == ENC_LEN) { "HPKE enc must be 32 bytes" }
        check(sealed.exportedKey.size == RESP_KEY_LEN) { "exported response key must be 32 bytes" }
        val envelope = Bytes.concat(MAGIC_REQ, kid, ts, rid, sealed.enc, sealed.ciphertext)
        return SealedRequest(envelope, Tse2State(rid.copyOf(), sealed.exportedKey))
    }

    /**
     * Open a `application/vnd.tayanch.secure+v2` response body. Throws
     * [Tse2Exception] on any framing or AEAD failure — callers must not fall
     * back to interpreting the bytes as plaintext.
     */
    fun openResponse(hpke: HpkePrimitives, state: Tse2State, bytes: ByteArray): ByteArray {
        if (bytes.size < MIN_RESP_LEN) throw Tse2Exception("response too short")
        val magic = bytes.copyOfRange(0, 8)
        if (!Bytes.constantTimeEquals(magic, MAGIC_RESP)) throw Tse2Exception("bad response magic")
        val rid = bytes.copyOfRange(8, 8 + RID_LEN)
        if (!Bytes.constantTimeEquals(rid, state.rid)) throw Tse2Exception("response rid does not match request")
        val nonce = bytes.copyOfRange(8 + RID_LEN, RESP_HEADER_LEN)
        val body = bytes.copyOfRange(RESP_HEADER_LEN, bytes.size)
        val aad = Bytes.concat(MAGIC_RESP, rid)
        return try {
            hpke.aesGcmOpen(state.responseKey, nonce, aad, body)
        } catch (e: Exception) {
            throw Tse2Exception("response authentication failed")
        }
    }

    /** Parse the request header (tests + diagnostics). */
    fun parseRequestHeader(envelope: ByteArray): RequestHeader {
        require(envelope.size >= MIN_REQ_LEN) { "envelope shorter than ${MIN_REQ_LEN} bytes" }
        require(Bytes.constantTimeEquals(envelope.copyOfRange(0, 8), MAGIC_REQ)) { "bad request magic" }
        var o = 8
        val kid = envelope.copyOfRange(o, o + KID_LEN); o += KID_LEN
        val ts = envelope.copyOfRange(o, o + TS_LEN); o += TS_LEN
        val rid = envelope.copyOfRange(o, o + RID_LEN); o += RID_LEN
        val enc = envelope.copyOfRange(o, o + ENC_LEN); o += ENC_LEN
        return RequestHeader(kid, ts, Bytes.readU64be(ts, 0), rid, enc, envelope.copyOfRange(o, envelope.size))
    }

    class RequestHeader(
        val kid: ByteArray,
        val tsBytes: ByteArray,
        val tsUnixSeconds: Long,
        val rid: ByteArray,
        val enc: ByteArray,
        val ciphertext: ByteArray,
    )
}

/** The envelope bytes to upload plus the state needed to open the reply. */
class SealedRequest(val envelope: ByteArray, val state: Tse2State)

/**
 * Per-request client state: the request id and the exporter-derived response
 * key. Equivalent to "keeping the sender context until the response is opened"
 * (the SPEC's wording) — the context has nothing else the client needs. Call
 * [wipe] once the response is opened or the request is abandoned.
 */
class Tse2State(val rid: ByteArray, val responseKey: ByteArray) {
    fun wipe() {
        Bytes.wipe(responseKey)
    }
}

class Tse2Exception(message: String) : Exception(message)
