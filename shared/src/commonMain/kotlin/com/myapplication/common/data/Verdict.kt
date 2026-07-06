package com.myapplication.common.data

/**
 * The calibrated verdict band the UI renders — the honest, three-band
 * (plus a tamper band) call, never a bare binary "FAKE" stamp.
 *
 * The server (`server.py::_run_analysis_v2`) already emits a 3-way `verdict`
 * field — "ai" | "real" | "uncertain" (+ "tampered" / "error"). The app used
 * to collapse this to a boolean (`isAI`), silently turning an "uncertain"
 * server call into a confident real/AI accusation. That is exactly the failure
 * mode our product guidance forbids: even at ~0.98 AUC an individual verdict is
 * probabilistic, so a low-signal image must be allowed to land in an explicit
 * abstention band instead of being force-labelled.
 */
enum class Verdict {
    AUTHENTIC,   // server "real"      — likely a genuine photo
    AI,          // server "ai"        — likely AI-generated
    UNCERTAIN,   // server "uncertain" — not enough signal; abstain
    TAMPERED;    // server "tampered"  — localized edit / manipulation

    /** True only for a confident AI/tamper call — never for [UNCERTAIN]. */
    val isAccusation: Boolean get() = this == AI || this == TAMPERED

    companion object {
        /**
         * Map a server response to a [Verdict]. Prefers the explicit 3-way
         * `verdict` string (the server has already applied its calibrated
         * operating point there); only falls back to `conclusion` /
         * `aiProbability` with a symmetric uncertain band around [threshold]
         * for a legacy backend that returns just the binary fields.
         */
        fun fromServer(
            verdict: String?,
            conclusion: String?,
            aiProbability: Double?,
            threshold: Float = 0.5f,
            uncertainHalfWidth: Float = 0.15f,
        ): Verdict {
            when (verdict?.trim()?.lowercase()) {
                "ai", "ai-generated", "generated" -> return AI
                "real", "authentic" -> return AUTHENTIC
                "uncertain", "unknown" -> return UNCERTAIN
                "tampered", "edited" -> return TAMPERED
            }
            // Legacy backend: no verdict field — derive a band from probability.
            val p = aiProbability?.toFloat()
            if (p != null) {
                val lo = threshold - uncertainHalfWidth
                val hi = threshold + uncertainHalfWidth
                return when {
                    p >= hi -> AI
                    p <= lo -> AUTHENTIC
                    else -> UNCERTAIN
                }
            }
            // Last resort: the collapsed conclusion string.
            return when {
                conclusion.equals("AI-Generated", ignoreCase = true) -> AI
                conclusion.equals("REAL", ignoreCase = true) -> AUTHENTIC
                else -> UNCERTAIN
            }
        }

        /** Heuristic / legacy-history results that only carry a boolean. */
        fun fromBoolean(isAI: Boolean): Verdict = if (isAI) AI else AUTHENTIC

        /** Re-hydrate a persisted [Verdict.name]; null when absent/unknown. */
        fun fromName(name: String?): Verdict? =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
