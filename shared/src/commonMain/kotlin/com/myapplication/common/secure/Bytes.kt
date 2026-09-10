package com.myapplication.common.secure

/**
 * Small, dependency-free byte helpers shared by the TSE2 envelope and the
 * attestation verifier. Kept in commonMain so every platform frames the
 * envelope with the SAME code — only the primitives (HPKE, AES-GCM, RSA,
 * SHA-256, randomness) are platform `actual`s.
 *
 * Base64 is hand-rolled on purpose: JWTs and JWKs use UNPADDED base64url,
 * and the Kotlin 1.9 stdlib `kotlin.io.encoding.Base64` rejects missing
 * padding (the padding options only arrived in Kotlin 2.0).
 */
internal object Bytes {
    private const val HEX = "0123456789abcdef"

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray {
        val s = hex.trim()
        require(s.length % 2 == 0) { "hex string has odd length" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = hexVal(s[2 * i])
            val lo = hexVal(s[2 * i + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not a hex digit")
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        var n = 0
        for (p in parts) n += p.size
        val out = ByteArray(n)
        var off = 0
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }

    /** uint64 big-endian, as the SPEC requires for `ts`. */
    fun u64be(value: Long): ByteArray {
        val out = ByteArray(8)
        var v = value
        for (i in 7 downTo 0) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return out
    }

    fun readU64be(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return v
    }

    /** Constant-time equality; both inputs are attacker-visible lengths anyway. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun wipe(bytes: ByteArray) {
        for (i in bytes.indices) bytes[i] = 0
    }

    // ---- base64 ---------------------------------------------------------

    private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun base64Encode(bytes: ByteArray, urlSafe: Boolean = false, pad: Boolean = true): String {
        val table = if (urlSafe) URL else STD
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(table[(n ushr 18) and 63]).append(table[(n ushr 12) and 63])
                .append(table[(n ushr 6) and 63]).append(table[n and 63])
            i += 3
        }
        val rem = bytes.size - i
        if (rem == 1) {
            val n = (bytes[i].toInt() and 0xFF) shl 16
            sb.append(table[(n ushr 18) and 63]).append(table[(n ushr 12) and 63])
            if (pad) sb.append("==")
        } else if (rem == 2) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            sb.append(table[(n ushr 18) and 63]).append(table[(n ushr 12) and 63])
                .append(table[(n ushr 6) and 63])
            if (pad) sb.append('=')
        }
        return sb.toString()
    }

    /** Accepts standard OR url-safe alphabets, with or without padding. */
    fun base64Decode(text: String): ByteArray {
        val s = text.trim().trimEnd('=')
        val out = ArrayList<Byte>(s.length * 3 / 4 + 3)
        var acc = 0
        var bits = 0
        for (c in s) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                '\n', '\r', ' ' -> continue
                else -> throw IllegalArgumentException("invalid base64 character")
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((acc ushr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    fun utf8(s: String): ByteArray = s.encodeToByteArray()
}
