package com.myapplication.common.data

import com.myapplication.common.secure.Bytes
import com.myapplication.common.secure.HpkePrimitives
import com.myapplication.common.secure.HpkeSealed
import com.myapplication.common.secure.Tse2
import com.myapplication.common.secure.platformHpke
import com.myapplication.common.secure.sha256Bytes
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A MockEngine stand-in for the served API's TSE2 path (standard tier). The
 * client is given [hpke], whose "seal" is a pass-through with a FIXED
 * exported response key, so this fake can seal replies exactly as
 * secure_api.py does (MAGIC_RESP || rid || nonce || AES-256-GCM(key, nonce,
 * MAGIC_RESP || rid, json)) without an X25519 private key. The response
 * opening, framing and AEAD on the client side are the real ones.
 */
class FakeTayanchServer {
    val responseKey = ByteArray(32) { 7 }
    private val pk = ByteArray(32) { (it + 1).toByte() }

    val hpke: HpkePrimitives = object : HpkePrimitives {
        override fun sealOnce(
            recipientPublicKey: ByteArray, info: ByteArray, aad: ByteArray, plaintext: ByteArray,
            exporterContext: ByteArray, exportLength: Int, deterministicEphemeralPrivateKey: ByteArray?,
        ) = HpkeSealed(ByteArray(32), plaintext + ByteArray(16), responseKey.copyOf())

        override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextWithTag: ByteArray) =
            platformHpke().aesGcmOpen(key, nonce, aad, ciphertextWithTag)
    }

    val calls = mutableListOf<String>()

    fun pubkeyJson(): String {
        val kid = Bytes.toHex(sha256Bytes(pk).copyOfRange(0, 8))
        val notAfter = System.currentTimeMillis() / 1000 + 86_400
        return """{"version": 2, "kid": "$kid", "public_key": "${java.util.Base64.getEncoder().encodeToString(pk)}",
            "suite": {"kem": 32, "kdf": 1, "aead": 2}, "minted_at": 0, "not_after": $notAfter,
            "tier": "standard", "attestation": {"backend": "none"}}"""
    }

    /** rid of the envelope inside a multipart /analyze body. */
    suspend fun ridOf(req: HttpRequestData): ByteArray {
        val body = req.body.toByteArray()
        val at = indexOf(body, Tse2.MAGIC_REQ)
        require(at >= 0) { "no TSE2 envelope in the request" }
        val o = at + 8 + Tse2.KID_LEN + Tse2.TS_LEN
        return body.copyOfRange(o, o + Tse2.RID_LEN)
    }

    fun seal(rid: ByteArray, json: String): ByteArray {
        val nonce = ByteArray(12) { 3 }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(responseKey, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(Tse2.MAGIC_RESP + rid)
        return Tse2.MAGIC_RESP + rid + nonce + c.doFinal(json.encodeToByteArray())
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val secureHeaders = headersOf(HttpHeaders.ContentType, ApiClient.SECURE_V2)

    fun MockRequestHandleScope.plain(status: HttpStatusCode, json: String): HttpResponseData =
        respond(json, status, jsonHeaders)

    suspend fun MockRequestHandleScope.sealed(req: HttpRequestData, status: HttpStatusCode, json: String): HttpResponseData =
        respond(seal(ridOf(req), json), status, secureHeaders)

    /**
     * An engine where /pubkey answers [pubkey] (default: a valid standard-tier
     * document) and /analyze answers [analyze].
     */
    fun engine(
        pubkey: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { plain(HttpStatusCode.OK, pubkeyJson()) },
        other: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { plain(HttpStatusCode.NotFound, """{"error":"not found"}""") },
        analyze: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = MockEngine { req ->
        calls += req.url.encodedPath
        when (req.url.encodedPath) {
            "/pubkey" -> pubkey(req)
            "/analyze" -> analyze(req)
            else -> other(req)
        }
    }

    companion object {
        fun indexOf(hay: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..hay.size - needle.size) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }

        const val REAL_BODY = """{"verdict": "real", "label": "Likely authentic", "confidence": 96,
            "p_ai": 0.02, "ai_probability": 0.02, "conclusion": "REAL", "method": "content",
            "detail": null, "model": "tayanch_union_vitl14_v5", "elapsed_ms": 650,
            "heatmap_token": "00000000000000000000000000000000"}"""
    }
}
