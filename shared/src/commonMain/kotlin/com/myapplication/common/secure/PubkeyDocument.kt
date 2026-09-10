package com.myapplication.common.secure

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `GET /pubkey?nonce=<hex>` document (SPEC §5). Also embedded verbatim in a
 * 409 `key_mismatch` body — which the client MUST re-verify exactly like a
 * fresh /pubkey before re-encrypting (an unauthenticated 409 body would be a
 * key-substitution channel for anyone in the TLS path).
 */
@Serializable
data class PubkeyDocument(
    val version: Int = 0,
    val kid: String = "",
    @SerialName("public_key") val publicKey: String = "",
    val suite: SuiteDoc? = null,
    @SerialName("minted_at") val mintedAt: Long? = null,
    @SerialName("not_after") val notAfter: Long? = null,
    val tier: String? = null,
    val attestation: AttestationDoc? = null,
)

@Serializable
data class SuiteDoc(val kem: Int = 0, val kdf: Int = 0, val aead: Int = 0)

@Serializable
data class AttestationDoc(
    val backend: String = "none",
    /** Backend-specific; for confidential_space a JWT string or `{"token": "<jwt>"}`. */
    val evidence: JsonElement? = null,
    val binding: BindingDoc? = null,
)

@Serializable
data class BindingDoc(
    val scheme: String? = null,
    @SerialName("client_nonce") val clientNonce: String? = null,
)

/**
 * The single client state every UI must show (SPEC §6). Exactly one of:
 *  - [Verified]   evidence present and valid against [AttestPolicy]; carries the measurement.
 *  - [Unattested] backend "none": the server (Tayanch and its host) can technically read
 *                 the media during analysis. Proceeds only if the user has not required attestation.
 *  - [Failed]     evidence present but invalid / expired / unverifiable, OR no usable key at all:
 *                 hard stop, nothing is sent.
 */
sealed class TierState {
    class Verified(val backend: String, val imageDigest: String, val hwModel: String) : TierState()
    object Unattested : TierState()
    class Failed(val reason: String) : TierState()

    /** The one line the UI shows. Copy is deliberately literal — no "not even us" outside Verified. */
    val userLine: String
        get() = when (this) {
            is Verified -> "Attested tier: measurement verified ($hwModel, image ${imageDigest.take(19)}…)."
            Unattested -> "Standard tier: Tayanch and its hosting provider can technically read this file during analysis."
            is Failed -> "Attestation failed: $reason. Nothing was sent."
        }

    val isVerified: Boolean get() = this is Verified
}
