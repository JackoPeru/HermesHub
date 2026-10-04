package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timeout finiti del trasporto SSE + watchdog inattivita (90s).
 * Nessuna dipendenza di rete: verifica costanti, client dedicato e predicati puri.
 */
class SseStreamingTimeoutsTest {

    @Test
    fun sseClientUsesFiniteTimeouts() {
        assertEquals(15_000, sseStreamHttpClient.connectTimeoutMillis)
        assertEquals(60_000, sseStreamHttpClient.readTimeoutMillis)
        assertEquals(30_000, sseStreamHttpClient.writeTimeoutMillis)
        assertEquals(30 * 60 * 1000, sseStreamHttpClient.callTimeoutMillis)
    }

    @Test
    fun sseClientIsDedicatedInstance() {
        // Il client delle chiamate brevi resta invariato; lo streaming usa il suo.
        assertNotSame(streamHttpClient, sseStreamHttpClient)
    }

    @Test
    fun inactivityConstantsMatchSpec() {
        assertEquals(90_000L, SSE_INACTIVITY_TIMEOUT_MS)
        assertTrue(SSE_INACTIVITY_CHECK_MS > 0L)
        assertTrue(SSE_INACTIVITY_CHECK_MS < SSE_INACTIVITY_TIMEOUT_MS)
        assertTrue(SSE_INACTIVITY_ERROR_MESSAGE.contains("nessun dato per 90s", ignoreCase = true))
    }

    @Test
    fun watchdogFiresOnlyAfterNinetySecondsOfSilence() {
        val startNs = 1_000_000_000L
        val ms = 1_000_000L
        assertFalse(isSseInactivityExpired(startNs, startNs))
        assertFalse(isSseInactivityExpired(startNs, startNs + 89_999 * ms))
        assertTrue(isSseInactivityExpired(startNs, startNs + 90_000 * ms))
        assertTrue(isSseInactivityExpired(startNs, startNs + 300_000 * ms))
    }

    @Test
    fun watchdogCanBeDisabledWithNonPositiveTimeout() {
        assertFalse(isSseInactivityExpired(0L, Long.MAX_VALUE, timeoutMs = 0L))
        assertFalse(isSseInactivityExpired(0L, Long.MAX_VALUE, timeoutMs = -1L))
    }

    @Test
    fun inactivityMessageIsRecognizedForExplicitErrorRouting() {
        assertTrue(isSseInactivityMessage(SSE_INACTIVITY_ERROR_MESSAGE))
        assertTrue(isSseInactivityMessage("Stream Hermes interrotto: NESSUN DATO PER 90S."))
        assertFalse(isSseInactivityMessage("connessione chiusa prima dell'evento terminale"))
        assertFalse(isSseInactivityMessage(null))
    }
}
