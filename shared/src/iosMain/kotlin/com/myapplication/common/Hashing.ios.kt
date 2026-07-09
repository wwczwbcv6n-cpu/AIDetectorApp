package com.myapplication.common

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
actual fun sha256Hex(bytes: ByteArray): String {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned ->
            digest.usePinned { out ->
                CC_SHA256(pinned.addressOf(0), bytes.size.toUInt(), out.addressOf(0))
            }
        }
    } else {
        digest.usePinned { out -> CC_SHA256(null, 0u, out.addressOf(0)) }
    }
    return digest.joinToString("") { it.toString(16).padStart(2, '0') }
}
