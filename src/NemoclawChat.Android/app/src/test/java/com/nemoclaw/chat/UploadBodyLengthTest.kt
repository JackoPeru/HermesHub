package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun smartMimeTypeByExtension() {        assertEquals("image/jpeg", smartMimeType("/v1/media/abc.jpg"))
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

    @Test
    fun resolveSmartPromptModes() {
        // Disattivato -> null (flusso normale).
        assertNull(resolveSmartPrompt("fisso", false, "ciao"))
        // Vuoto -> auto (stringa vuota = decide il manager via LLM).
        assertEquals("", resolveSmartPrompt("", true, "ciao"))
        assertEquals("", resolveSmartPrompt("   ", true, "ciao"))
        // Template con placeholder.
        assertEquals(
            "Animate this: ciao forte",
            resolveSmartPrompt("Animate this: {testo}", true, "ciao forte")
        )
        // Fisso senza placeholder: testo ignorato, zero LLM.
        assertEquals("sempre lo stesso", resolveSmartPrompt("sempre lo stesso", true, "ignora questo"))
    }

    @Test
    fun smartCaseConfigMapping() {
        val base = AppSettings()
        assertEquals(true to "", smartCaseConfig(base, "create_image"))
        assertEquals(true to "", smartCaseConfig(base, "edit_image"))
        assertEquals(true to "", smartCaseConfig(base, "journey_video_preview"))
        assertNull(smartCaseConfig(base, "sconosciuto"))
        val custom = base.copy(smartEdit = false, smartEditPrompt = "fisso")
        assertEquals(false to "fisso", smartCaseConfig(custom, "edit_image"))
    }

    @Test
    fun blankPhotoForcedPreset() {
        val video = AppSettings(smartBlankPhoto = "video")
        val chat = AppSettings(smartBlankPhoto = "chat")
        assertEquals("journey_video_preview", blankPhotoForcedPreset(video, "", true))
        assertEquals("journey_video_preview", blankPhotoForcedPreset(video, "   ", true))
        assertNull(blankPhotoForcedPreset(chat, "", true))
        assertNull(blankPhotoForcedPreset(video, "fai qualcosa", true))
        assertNull(blankPhotoForcedPreset(video, "", false))
        val off = AppSettings(smartBlankPhoto = "video", smartVideo = false)
        assertNull(blankPhotoForcedPreset(off, "", true))
    }
}
