package com.myapplication.common.data

import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit 2026-09-24 APP-07: nothing in the app capped the upload size. A
 * >40 MB file (a DNG shared as image/ *) was read, sealed (a second full
 * copy), uploaded over mobile data and only then refused with 413 by the
 * server's 40 MB cap (demo_api._IMAGE_MAX_BYTES). The cap is now checked
 * before anything is read in full, sealed or sent, with the server's number.
 * Run: ./gradlew :shared:desktopTest
 */
class UploadCapTest {

    private val mb = 1024L * 1024L

    @Test
    fun capNeverExceedsTheServers40Mb() {
        assertTrue(UPLOAD_MAX_BYTES <= 40 * mb)
        assertTrue(UPLOAD_MAX_BYTES >= 39 * mb)
    }

    @Test
    fun a41MbFileIsRefusedWithTheServerAlignedMessage() {
        val msg = assertNotNull(uploadTooLargeMessage(41 * mb))
        assertTrue("40 MB" in msg, msg)
        assertNull(uploadTooLargeMessage(10 * mb))
        assertNull(uploadTooLargeMessage(-1))       // unknown size: read, then re-check
    }

    @Test
    fun theClientRefusesBeforeSealingOrSending() = runBlocking {
        val client = ApiClient(AppSettings(apiBaseUrl = "https://api.example.test", apiKey = "k"),
            engine = MockEngine { error("nothing may be sent for an oversized file") })
        val r = client.analyzeImage(ByteArray((UPLOAD_MAX_BYTES + 1).toInt()))
        client.close()
        val e = (r.exceptionOrNull() as ApiException).apiError
        assertTrue(e is ApiError.ClientError, "got $e")
        assertEquals(413, (e as ApiError.ClientError).status)
        assertTrue("40 MB" in e.userMessage, e.userMessage)
    }
}
