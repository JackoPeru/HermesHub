package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadBodyLengthTest {

    @Test
    fun base64LengthIsFourPerThreeBytes() {
        // 4 byte ogni 3 di input (padding incluso), senza wrap.
        val cases = mapOf(
            0L to 0L, 1L to 4L, 2L to 4L, 3L to 4L, 4L to 8L,
            5L to 8L, 6L to 8L, 7L to 12L, 1024L to 1368L,
            570500L to 760668L
        )
        for ((raw, expected) in cases) {
            assertEquals("raw=$raw", expected, base64EncodedLength(raw))
        }
    }

    @Test
    fun smartMimeTypeByExtension() {
        assertEquals("image/jpeg", smartMimeType("/v1/media/abc.jpg"))
        assertEquals("image/png", smartMimeType("/v1/media/abc.PNG?x=1"))
        assertEquals("video/mp4", smartMimeType("/v1/media/vid.mp4"))
        assertEquals("video/webm", smartMimeType("/v1/media/vid.webm"))
        assertEquals("", smartMimeType("/v1/media/blob"))
    }

    @Test
    fun smartResultBlocksCarryMediaUrls() {
        val blocks = smartResultBlocks(
            "abc123", "image",
            listOf("/v1/media/abc123_out.png", "/v1/media/abc123_b.png")
        )
        assertEquals(2, blocks.size)
        assertEquals("media_file", blocks[0].type)
        assertEquals("image", blocks[0].mediaKind)
        assertEquals("/v1/media/abc123_out.png", blocks[0].mediaUrl)
        assertEquals("image/png", blocks[0].mimeType)
        val capped = smartResultBlocks("x", "video", (1..20).map { "/v1/media/$it.mp4" })
        assertEquals(12, capped.size)
    }
}
