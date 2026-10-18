package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recovery turno Sessions dopo morte transport ( accepted, non terminale ):
 * riconciliazione via history. Puro e testabile, nessuna rete.
 */
class SessionTurnRecoveryTest {

    private fun msg(role: String, content: String) = HermesSessionMessage(role, content)

    @Test
    fun answerFollowsMatchedUserInput() {
        val history = listOf(
            msg("user", "ciao"),
            msg("assistant", "Ciao! Come posso aiutarti?"),
            msg("user", "quanto fa 2+2?"),
            msg("assistant", "Fa 4.")
        )
        assertEquals("Fa 4.", findSessionTurnAnswer(history, "quanto fa 2+2?"))
    }

    @Test
    fun lastPairWinsOnRepeatedInput() {
        val history = listOf(
            msg("user", "ripeti"),
            msg("assistant", "prima"),
            msg("user", "ripeti"),
            msg("assistant", "seconda")
        )
        assertEquals("seconda", findSessionTurnAnswer(history, "ripeti"))
    }

    @Test
    fun noAssistantYetMeansNotReady() {
        val history = listOf(
            msg("user", "ciao"),
            msg("assistant", "Ciao!"),
            msg("user", "dimmi tutto")
        )
        assertNull(findSessionTurnAnswer(history, "dimmi tutto"))
    }

    @Test
    fun blankAssistantRowsAreSkipped() {
        val history = listOf(
            msg("user", "vai"),
            msg("assistant", "   "),
            msg("assistant", "fatto")
        )
        assertEquals("fatto", findSessionTurnAnswer(history, "vai"))
    }

    @Test
    fun toolAndSystemRowsDoNotBreakPairing() {
        val history = listOf(
            msg("user", "cerca"),
            msg("tool", "{\"result\": 1}"),
            msg("system", "nota interna"),
            msg("assistant", "trovato")
        )
        assertEquals("trovato", findSessionTurnAnswer(history, "cerca"))
    }

    @Test
    fun inputOutsideWindowFallsBackToLastUser() {
        // History troncata (finestra 200): l'input non c'e, ultimo user e nostro.
        val history = listOf(
            msg("user", "vecchio turno"),
            msg("assistant", "vecchia risposta"),
            msg("user", "turno recente"),
            msg("assistant", "risposta recente")
        )
        assertEquals("risposta recente", findSessionTurnAnswer(history, "input non in finestra"))
    }

    @Test
    fun noUserMeansNull() {
        val history = listOf(msg("assistant", "orfana"))
        assertNull(findSessionTurnAnswer(history, "qualcosa"))
    }

    @Test
    fun blankInputOrEmptyHistoryMeansNull() {
        assertNull(findSessionTurnAnswer(listOf(msg("user", "x")), "   "))
        assertNull(findSessionTurnAnswer(emptyList(), "x"))
    }

    @Test
    fun rolesAreCaseInsensitive() {
        val history = listOf(
            msg("Tu", "domanda"),
            msg("HERMES", "risposta")
        )
        assertEquals("risposta", findSessionTurnAnswer(history, "domanda"))
    }

    @Test
    fun inputIsTrimmedBeforeMatching() {
        val history = listOf(
            msg("user", "spazi"),
            msg("assistant", "ok")
        )
        assertEquals("ok", findSessionTurnAnswer(history, "  spazi\n"))
    }

    @Test
    fun recoveryBoundsAreFiniteAndPositive() {
        assertTrue(SESSION_RUN_RECOVERY_ATTEMPTS > 0)
        assertTrue(SESSION_RUN_RECOVERY_DELAY_MS > 0)
        assertTrue(SESSION_HISTORY_RECOVERY_ATTEMPTS > 0)
        assertTrue(SESSION_HISTORY_RECOVERY_DELAY_MS > 0)
    }
}
