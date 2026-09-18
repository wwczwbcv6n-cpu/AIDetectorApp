package com.myapplication.common.secure

/**
 * The PINNED attestation policy compiled into this client.
 *
 * This is the app's trust anchor for the "attested" tier: a `/pubkey`
 * document is VERIFIED only if its evidence is signed by the pinned issuer's
 * keys AND every claim below matches. The policy ships inside the signed app
 * binary (the operator cannot swap it per user the way a web page can), which
 * is the one property that lets a native client make a stronger statement
 * than the web demo. It is still only as good as the pinned digests: an EMPTY
 * [imageDigests] list means NOTHING can be verified yet, and the verifier
 * says so ("no pinned image digest") instead of passing.
 *
 * Values below marked "server-chosen" MUST match what the attested-tier
 * deployment requests from the Confidential Space launcher; change them here
 * and ship a new app build — never make them runtime-configurable.
 */
object AttestPolicy {
    /** Confidential Space OIDC issuer (research-verified 2026-09-10). */
    const val ISSUER = "https://confidentialcomputing.googleapis.com"

    /**
     * JWKS of the Confidential Space signer (research-verified: the OIDC
     * discovery document's `jwks_uri`; keys rotate, two RS256 keys live).
     * Fetched over TLS at verification time; if unreachable the state is
     * FAILED, never a silent downgrade.
     */
    const val JWKS_URL =
        "https://www.googleapis.com/service_accounts/v1/metadata/jwk/signer@confidentialspace-sign.iam.gserviceaccount.com"

    /**
     * Server-chosen custom audience the workload requests from the launcher
     * (`{"audience": ...}` on /run/container_launcher/teeserver.sock). Must not
     * be the default `https://sts.googleapis.com` (research: default WIF aud).
     */
    const val AUDIENCE = "https://tayanch.com/attest"

    /** Only RS256 is advertised by the issuer; anything else is rejected. */
    const val ALG = "RS256"

    /** Hardware the client accepts. Plain Shielded VM (no memory encryption) is NOT accepted. */
    val allowedHwModels: Set<String> = setOf("GCP_INTEL_TDX", "GCP_AMD_SEV", "GCP_AMD_SEV_ES")

    const val SWNAME = "CONFIDENTIAL_SPACE"

    /** The debug image allows SSH and still attests; production must be "disabled-since-boot". */
    const val DBGSTAT = "disabled-since-boot"

    /** `submods.confidential_space.support_attributes` must contain this. */
    const val SUPPORT_ATTRIBUTE = "STABLE"

    /**
     * Allowed `submods.container.image_digest` values ("sha256:<hex>").
     * EMPTY ON PURPOSE: no attested image has been published and measured yet.
     * While empty every confidential_space document classifies as FAILED with
     * reason "no pinned image digest". Fill this from the release's published
     * digest (and record it outside this repo — a transparency log — before
     * calling anything "verifiable"; the critique is explicit on that).
     */
    val imageDigests: List<String> = emptyList()

    /** Clock skew tolerated on exp/nbf/iat (seconds). */
    const val CLOCK_SKEW_SECONDS = 120L

    /**
     * Maximum accepted age of the evidence (now - iat). The launcher token is
     * minted when the workload asks for it, not per request; the server is
     * expected to re-mint it with every key epoch (design: hourly). A token
     * older than this is treated as stale. Generous on purpose until the
     * server's rotation cadence is measured.
     */
    const val MAX_EVIDENCE_AGE_SECONDS = 24L * 3600L

    /**
     * How the key is bound into the evidence. The workload passes
     * base64url(SHA-256("tayanch/v2 pubkey" || kid || pubkey)) as the launcher
     * nonce, echoed back as `eat_nonce`. Note what this does NOT give: the
     * client's own request nonce is not in the token (Confidential Space mints
     * the token at workload start, so per-request freshness is impossible on
     * this backend; freshness comes from `exp`/`iat` only). Stated plainly,
     * per the design review.
     */
    const val EAT_NONCE_PREFIX = "tayanch/v2 pubkey"

    fun expectedEatNonce(kid: ByteArray, pubkey: ByteArray): String =
        Bytes.base64Encode(sha256Bytes(Bytes.concat(Bytes.utf8(EAT_NONCE_PREFIX), kid, pubkey)), urlSafe = true, pad = false)

    /** Backends this client knows how to verify. Everything else is FAILED, never UNATTESTED. */
    val verifiableBackends: Set<String> = setOf("confidential_space")

    /** A `/pubkey` document must have at least this much life left to be used (SPEC §5). */
    const val MIN_KEY_LIFETIME_LEFT_SECONDS = 60L
}
