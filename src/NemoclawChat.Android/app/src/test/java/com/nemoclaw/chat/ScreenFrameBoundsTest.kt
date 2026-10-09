package com.nemoclaw.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertThrows

class ScreenFrameBoundsTest {
    @Test
    fun `screen frame HTTP oltre il limite viene rifiutato`() {
        val body = ByteArray((MAX_SCREEN_FRAME_BYTES + 1L).toInt()) { 7 }
        LocalHttpTestServer(body, contentType = "image/png").use { server ->
            assertThrows(PayloadTooLargeException::class.java) {
                runBlocking { fetchScreenFrameBytesFromUrl(server.url("/display/frame.png"), null) }
            }
            assertEquals("GET", server.awaitRequest().method)
        }
    }
}
