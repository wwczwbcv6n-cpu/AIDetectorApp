package com.myapplication.common.secure

import com.myapplication.common.nowMillis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The server key extracted from a classified `/pubkey` document. */
class ServerKey(val pubkey: ByteArray, val kid: ByteArray, val notAfter: Long?)

/** Classification result: [key] is non-null exactly when [state] is not [TierState.Failed]. */
class Classification(val state: TierState, val key: ServerKey?)

/**
 * A snapshot of [AttestPolicy] so tests can exercise the "digest pinned" path
 * without mutating the pinned object. Production code uses [pinned].
 */
data class PolicyView(
    val issuer: String,
    val jwksUrl: String,
    val audience: String,
    val alg: String,
    val allowedHwModels: Set<String>,
    val swname: String,
    val dbgstat: String,
    val supportAttribute: String,
    val imageDigests: List<String>,
    val clockSkewSeconds: Long,
    val maxEvidenceAgeSeconds: Long,
    val verifiableBackends: Set<String>,
    val minKeyLifetimeLeftSeconds: Long,
) {
    companion object {
        fun pinned(): PolicyView = PolicyView(
            issuer = AttestPolicy.ISSUER,
            jwksUrl = AttestPolicy.JWKS_URL,
            audience = AttestPolicy.AUDIENCE,
            alg = AttestPolicy.ALG,
            allowedHwModels = AttestPolicy.allowedHwModels,
            swname = AttestPolicy.SWNAME,
            dbgstat = AttestPolicy.DBGSTAT,
            supportAttribute = AttestPolicy.SUPPORT_ATTRIBUTE,
            imageDigests = AttestPolicy.imageDigests,
            clockSkewSeconds = AttestPolicy.CLOCK_SKEW_SECONDS,
            maxEvidenceAgeSeconds = AttestPolicy.MAX_EVIDENCE_AGE_SECONDS,
            verifiableBackends = AttestPolicy.verifiableBackends,
            minKeyLifetimeLeftSeconds = AttestPolicy.MIN_KEY_LIFETIME_LEFT_SECONDS,
        )
    }
}

/**
 * Classifies a `/pubkey` document into exactly one [TierState] (SPEC §6) and,
 * for the confidential_space backend, verifies the OIDC evidence against the
 * pinned [PolicyView]:
 *
 *   RS256 signature (JWKS from the pinned URL) -> iss -> aud -> exp/nbf/iat ->
 *   eat_nonce == base64url(SHA-256("tayanch/v2 pubkey" || kid || pubkey)) ->
 *   hwmodel -> swname -> dbgstat -> secboot -> support_attributes ->
 *   submods.container.image_digest ∈ pinned digests.
 *
 * Any deviation is FAILED with a reason; there is no path from "evidence
 * present but bad" to UNATTESTED. Backends this client has no verifier for
 * are FAILED ("this client cannot verify <backend>"), never silently accepted.
 *
 * [fetchJwks] is injected so the verifier stays transport-agnostic (Ktor in
 * the app, a fake in tests). It must return the JWKS JSON text or throw.
 */
class AttestationVerifier(
    private val fetchJwks: suspend (url: String) -> String,
    private val nowSeconds: () -> Long = { nowMillis() / 1000L },
    private val policy: PolicyView = PolicyView.pinned(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun classify(doc: PubkeyDocument, clientNonceHex: String): Classification {
        if (doc.version != 2) return failed("unsupported /pubkey version ${doc.version}")
        val suite = doc.suite ?: return failed("missing suite")
        if (suite.kem != 0x20 || suite.kdf != 1 || suite.aead != 2) {
            return failed("unsupported suite kem=${suite.kem} kdf=${suite.kdf} aead=${suite.aead}")
        }
        val pubkey = try { Bytes.base64Decode(doc.publicKey) } catch (e: Exception) { return failed("malformed public key") }
        if (pubkey.size != 32) return failed("public key is not 32 bytes")
        val kid = try { Bytes.fromHex(doc.kid) } catch (e: Exception) { return failed("malformed kid") }
        if (kid.size != Tse2.KID_LEN) return failed("kid is not 8 bytes")
        if (!Bytes.constantTimeEquals(kid, Tse2.kidOf(pubkey))) return failed("kid does not match the advertised key")
        val now = nowSeconds()
        val notAfter = doc.notAfter ?: return failed("key has no expiry (not_after)")
        if (notAfter < now + policy.minKeyLifetimeLeftSeconds) return failed("server key expired or about to expire")

        val key = ServerKey(pubkey, kid, notAfter)
        val att = doc.attestation ?: return failed("missing attestation block")
        val backend = att.backend.trim().lowercase()
        return when {
            backend == "none" -> Classification(TierState.Unattested, key)
            backend !in policy.verifiableBackends -> failed("this client cannot verify ${att.backend}")
            backend == "confidential_space" -> {
                val state = verifyConfidentialSpace(att.evidence, kid, pubkey, now)
                Classification(state, if (state is TierState.Failed) null else key)
            }
            else -> failed("this client cannot verify ${att.backend}")
        }
    }

    private fun failed(reason: String) = Classification(TierState.Failed(reason), null)

    // ---- Confidential Space (GCP) OIDC token ----------------------------

    private suspend fun verifyConfidentialSpace(evidence: JsonElement?, kid: ByteArray, pubkey: ByteArray, now: Long): TierState {
        val token = extractToken(evidence) ?: return TierState.Failed("no evidence token")
        val parts = token.split('.')
        if (parts.size != 3) return TierState.Failed("malformed evidence token")

        val header = parseJsonObject(parts[0]) ?: return TierState.Failed("malformed evidence header")
        val payload = parseJsonObject(parts[1]) ?: return TierState.Failed("malformed evidence claims")
        val signature = try { Bytes.base64Decode(parts[2]) } catch (e: Exception) { return TierState.Failed("malformed evidence signature") }

        if (header.str("alg") != policy.alg) return TierState.Failed("unsupported evidence algorithm")
        val signingKid = header.str("kid") ?: return TierState.Failed("evidence has no signing key id")

        // 1. Signature first — nothing below is trusted until this passes.
        val jwksText = try { fetchJwks(policy.jwksUrl) } catch (e: Exception) {
            return TierState.Failed("could not fetch the attestation signing keys")
        }
        val jwk = findRsaKey(jwksText, signingKid) ?: return TierState.Failed("attestation signing key not found")
        val signingInput = Bytes.utf8(parts[0] + "." + parts[1])
        if (!rs256Verify(jwk.first, jwk.second, signingInput, signature)) return TierState.Failed("evidence signature invalid")

        // 2. Issuer / audience.
        if (payload.str("iss") != policy.issuer) return TierState.Failed("evidence issuer not pinned")
        if (!payload.strOrList("aud").contains(policy.audience)) return TierState.Failed("evidence audience mismatch")

        // 3. Time window.
        val exp = payload.long("exp") ?: return TierState.Failed("evidence has no expiry")
        if (exp + policy.clockSkewSeconds < now) return TierState.Failed("evidence expired")
        payload.long("nbf")?.let { if (it > now + policy.clockSkewSeconds) return TierState.Failed("evidence not yet valid") }
        val iat = payload.long("iat") ?: return TierState.Failed("evidence has no issue time")
        if (iat > now + policy.clockSkewSeconds) return TierState.Failed("evidence issued in the future")
        if (now - iat > policy.maxEvidenceAgeSeconds) return TierState.Failed("evidence too old")

        // 4. Key binding: eat_nonce must be the hash of the advertised key. Padding-insensitive
        //    because the launcher echoes whatever string the workload passed.
        val expected = AttestPolicy.expectedEatNonce(kid, pubkey).trimEnd('=')
        val nonces = payload.strOrList("eat_nonce").map { it.trimEnd('=') }
        if (!nonces.contains(expected)) return TierState.Failed("evidence is not bound to the advertised key")

        // 5. Platform claims.
        val hw = payload.str("hwmodel") ?: return TierState.Failed("evidence has no hardware model")
        if (hw !in policy.allowedHwModels) return TierState.Failed("hardware model not accepted ($hw)")
        if (payload.str("swname") != policy.swname) return TierState.Failed("not a Confidential Space image")
        if (payload.str("dbgstat") != policy.dbgstat) return TierState.Failed("debug-enabled image")
        if (payload.bool("secboot") != true) return TierState.Failed("secure boot not attested")
        val submods = payload.obj("submods") ?: return TierState.Failed("evidence has no submods")
        val support = submods.obj("confidential_space")?.strOrList("support_attributes") ?: emptyList()
        if (!support.contains(policy.supportAttribute)) return TierState.Failed("image support level not ${policy.supportAttribute}")

        // 6. The measurement itself.
        val digest = submods.obj("container")?.str("image_digest") ?: return TierState.Failed("evidence has no image digest")
        if (policy.imageDigests.isEmpty()) return TierState.Failed("no pinned image digest")
        if (digest !in policy.imageDigests) return TierState.Failed("image digest not pinned (${digest.take(19)}…)")

        return TierState.Verified(backend = "confidential_space", imageDigest = digest, hwModel = hw)
    }

    private fun extractToken(evidence: JsonElement?): String? = when (evidence) {
        null -> null
        is JsonPrimitive -> evidence.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonObject -> evidence.str("token") ?: evidence.str("jwt") ?: evidence.str("oidc")
        else -> null
    }

    private fun parseJsonObject(b64url: String): JsonObject? = try {
        json.parseToJsonElement(Bytes.base64Decode(b64url).decodeToString()).jsonObject
    } catch (e: Exception) {
        null
    }

    /** Returns (modulus, exponent) of the RSA JWK with the given kid, or null. */
    private fun findRsaKey(jwksText: String, kid: String): Pair<ByteArray, ByteArray>? = try {
        val keys = json.parseToJsonElement(jwksText).jsonObject["keys"]?.jsonArray ?: JsonArray(emptyList())
        keys.asSequence().map { it.jsonObject }
            .firstOrNull { it.str("kid") == kid && it.str("kty") == "RSA" }
            ?.let { k ->
                val n = k.str("n") ?: return null
                val e = k.str("e") ?: return null
                Pair(Bytes.base64Decode(n), Bytes.base64Decode(e))
            }
    } catch (e: Exception) {
        null
    }

    // ---- tiny JSON accessors ---------------------------------------------

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.strOrList(key: String): List<String> = when (val v = this[key]) {
        is JsonPrimitive -> listOfNotNull(v.contentOrNull)
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }
}
