package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TtsTextValidationTest {

    @Test
    fun `testo corto valido resta intatto ma trimmato`() {
        assertEquals("Ciao Hermes", validateTtsText("  Ciao Hermes\n"))
    }

    @Test
    fun `testo al limite 4000 char passa`() {
        assertEquals(MAX_TTS_TEXT_CHARS, validateTtsText("a".repeat(4000)).length)
    }

    @Test
    fun `testo oltre 4000 char rifiutato onesto`() {
        try {
            validateTtsText("a".repeat(4001))
            fail("Atteso IllegalArgumentException oltre 4000 char")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message.orEmpty().contains("seleziona una parte"))
        }
    }

    @Test
    fun `testo vuoto rifiutato`() {
        try {
            validateTtsText("   ")
            fail("Atteso IllegalArgumentException su testo vuoto")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message.orEmpty().isNotBlank())
        }
    }
}
