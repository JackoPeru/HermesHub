package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingShareValidationTest {

    @Test
    fun `prompt condiviso oltre 2000 char e troncato`() {
        val out = truncateIncomingPrompt("  " + "p".repeat(5000) + "  ")
        assertEquals(MAX_INCOMING_PROMPT_CHARS, out.length)
    }

    @Test
    fun `prompt corto resta intatto ma trimmato`() {
        assertEquals("ciao Hermes", truncateIncomingPrompt("  ciao Hermes\n"))
    }

    @Test
    fun `allowlist MIME accetta solo image audio video text e pdf`() {
        assertTrue(isAllowedIncomingMime("image/jpeg"))
        assertTrue(isAllowedIncomingMime("audio/mp4"))
        assertTrue(isAllowedIncomingMime("video/mp4"))
        assertTrue(isAllowedIncomingMime("text/plain"))
        assertTrue(isAllowedIncomingMime("application/pdf"))
        assertFalse(isAllowedIncomingMime("application/zip"))
        assertFalse(isAllowedIncomingMime("application/octet-stream"))
        assertFalse(isAllowedIncomingMime(null))
        assertFalse(isAllowedIncomingMime("  "))
    }

    @Test
    fun `allegato oltre 25MB rifiutato, ignoto passa al controllo in copia`() {
        assertTrue(validateIncomingAttachmentSize(MAX_INCOMING_ATTACHMENT_BYTES))
        assertFalse(validateIncomingAttachmentSize(MAX_INCOMING_ATTACHMENT_BYTES + 1))
        assertFalse(validateIncomingAttachmentSize(0L))
        assertTrue(validateIncomingAttachmentSize(-1L))
    }

    @Test
    fun `deeplink con prompt oltre 2000 char e troncato`() {
        val parsed = parseHermesDeepLink("hermes-hub://chat?prompt=" + "p".repeat(9000))
        assertNotNull(parsed)
        assertEquals(MAX_HERMES_DEEPLINK_PROMPT_CHARS, parsed!!.prompt.length)
        assertTrue(parsed.promptRequiresConfirmation)
    }
}
