package com.myapplication.common.data

/**
 * The largest image the app will read, seal and send. The served API refuses
 * any /analyze request whose body exceeds 40 MB (demo_api._IMAGE_MAX_BYTES,
 * IMAGE_MAX_UPLOAD_MB=40, checked on the whole multipart body), so the app
 * stops 64 KB short of it to leave room for the TSE2 envelope and multipart
 * framing (audit 2026-09-24 APP-07). tests/test_client_contract.py in the
 * parent repo keeps it within the server's cap.
 */
const val UPLOAD_MAX_BYTES: Long = 40L * 1024 * 1024 - 64L * 1024

/** The user-facing refusal for a file of [sizeBytes], or null when it fits (or the size is unknown, < 0). */
fun uploadTooLargeMessage(sizeBytes: Long): String? =
    if (sizeBytes > UPLOAD_MAX_BYTES) {
        "This file is ${(sizeBytes + (1L shl 19)) shr 20} MB; the Tayanch API accepts images up to 40 MB. " +
            "Nothing was sent. Share a smaller copy (for example a JPEG export)."
    } else {
        null
    }
