package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upload parziale: MAI fallback silenzioso al subset. L'invio va bloccato con
 * ChatStreamEvent.Error esplicito (impossibile non vederlo: imposta error+isDone
 * e lo stato viene reso assertive), non con Status (solo riga di log).
 */
class PartialUploadBlockTest {

    @Test
    fun emptyAttachmentsNeverBlock() {
        assertFalse(isPartialUploadBlocked(0, 0, emptyList()))
    }

    @Test
    fun fullSuccessDoesNotBlock() {
        assertFalse(isPartialUploadBlocked(2, 2, emptyList()))
    }

    @Test
    fun anyUploadErrorBlocks() {
        assertTrue(isPartialUploadBlocked(3, 2, listOf("b.pdf: HTTP 500")))
        assertTrue(isPartialUploadBlocked(3, 3, listOf("b.pdf: HTTP 500")))
    }

    @Test
    fun shortCountWithoutErrorsBlocks() {
        assertTrue(isPartialUploadBlocked(3, 2, emptyList()))
        assertTrue(isPartialUploadBlocked(1, 0, emptyList()))
    }

    @Test
    fun blockMessageIsExplicitAndActionable() {
        val message = partialUploadBlockMessage(3, 2, listOf("b.pdf: HTTP 500: gateway"))
        assertTrue(message.contains("2/3"))
        assertTrue(message.contains("b.pdf: HTTP 500: gateway"))
        assertTrue(message.contains("bloccato", ignoreCase = true))
        assertTrue(message.contains("parziale", ignoreCase = true))
    }

    @Test
    fun blockMessageWithoutErrorDetailsStillCounts() {
        val message = partialUploadBlockMessage(2, 1, emptyList())
        assertTrue(message.contains("1/2"))
        assertTrue(message.contains("bloccato", ignoreCase = true))
    }

    @Test
    fun errorEventIsUnmissableWhileStatusIsNot() {
        val blocked = StreamingState().applyEvent(
            ChatStreamEvent.Error(partialUploadBlockMessage(2, 1, listOf("a.jpg: KO")))
        )
        assertTrue(blocked.isDone)
        assertTrue(blocked.error.orEmpty().contains("bloccato", ignoreCase = true))
        assertEquals("Errore Hermes.", blocked.status)

        val silent = StreamingState().applyEvent(ChatStreamEvent.Status("Upload parziale: a.jpg: KO"))
        assertFalse(silent.isDone)
        assertNull(silent.error)
    }
}
