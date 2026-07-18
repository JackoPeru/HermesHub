package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceWakeWordTest {
    @Test
    fun stripsConfiguredWakePhraseAndPunctuation() {
        assertEquals("controlla il server", stripWakePhrase("Ehi Hermes, controlla il server", "Ehi Hermes"))
        assertEquals("", stripWakePhrase("Hermes", "Hermes"))
    }

    @Test
    fun acceptsCommonItalianHermesTranscription() {
        assertEquals("apri la chat", stripWakePhrase("Ermes: apri la chat", "Hermes"))
    }

    @Test
    fun acceptsNaturalPrefixBeforeConfiguredPhrase() {
        assertEquals("", stripWakePhrase("Ehi Hermes", "Hermes"))
        assertEquals("avvia la chiamata", stripWakePhrase("Ok Hermes, avvia la chiamata", "Hermes"))
    }

    @Test
    fun rejectsSpeechWithoutConfiguredWakePhrase() {
        assertNull(stripWakePhrase("Hermes controlla il server", "Ok Hermes"))
    }

    @Test
    fun normalizesBlankAndOversizedCustomPhrase() {
        assertEquals(DefaultWakePhrase, normalizeWakePhrase("   "))
        assertEquals(48, normalizeWakePhrase("wake ".repeat(20)).length)
    }
}
