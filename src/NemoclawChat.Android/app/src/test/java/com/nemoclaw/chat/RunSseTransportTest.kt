package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trasporto run-SSE con timeout finiti + watchdog 90s (stessi valori streaming chat).
 * JVM puro: verifica costanti, client dedicato e predicati.
 */
class RunSseTransportTest {

    @Test
    fun runSseClientUsesFiniteTimeouts() {
        assertEquals(15_000, runSseHttpClient.connectTimeoutMillis)
        assertEquals(60_000, runSseHttpClient.readTimeoutMillis)
        assertEquals(30_000, runSseHttpClient.writeTimeoutMillis)
        assertEquals(30 * 60 * 1000, runSseHttpClient.callTimeoutMillis)
    }

    @Test
    fun runSseTimeoutsMatchChatStreaming() {
        assertEquals(SSE_CONNECT_TIMEOUT_SEC, RUN_SSE_CONNECT_TIMEOUT_SEC)
        assertEquals(SSE_READ_TIMEOUT_SEC, RUN_SSE_READ_TIMEOUT_SEC)
        assertEquals(SSE_WRITE_TIMEOUT_SEC, RUN_SSE_WRITE_TIMEOUT_SEC)
        assertEquals(SSE_CALL_TIMEOUT_MIN, RUN_SSE_CALL_TIMEOUT_MIN)
    }

    @Test
    fun runSseClientIsDedicatedInstance() {
        // Il client a timeout infiniti resta per le chiamate brevi; le run usano il dedicato.
        assertNotSame(streamHttpClient, runSseHttpClient)
    }

    @Test
    fun runInactivityConstantsMatchSpec() {
        assertEquals(90_000L, RUN_SSE_INACTIVITY_TIMEOUT_MS)
        assertTrue(RUN_SSE_INACTIVITY_CHECK_MS > 0L)
        assertTrue(RUN_SSE_INACTIVITY_CHECK_MS < RUN_SSE_INACTIVITY_TIMEOUT_MS)
        assertTrue(RUN_SSE_INACTIVITY_ERROR_MESSAGE.contains("nessun dato per 90s", ignoreCase = true))
    }

    @Test
    fun runWatchdogFiresOnlyAfterNinetySeconds() {
        val startNs = 1_000_000_000L
        val ms = 1_000_000L
        assertFalse(isRunSseInactivityExpired(startNs, startNs))
        assertFalse(isRunSseInactivityExpired(startNs, startNs + 89_999 * ms))
        assertTrue(isRunSseInactivityExpired(startNs, startNs + 90_000 * ms))
        assertTrue(isRunSseInactivityExpired(startNs, startNs + 300_000 * ms))
    }

    @Test
    fun runWatchdogCanBeDisabled() {
        assertFalse(isRunSseInactivityExpired(0L, Long.MAX_VALUE, timeoutMs = 0L))
        assertFalse(isRunSseInactivityExpired(0L, Long.MAX_VALUE, timeoutMs = -1L))
    }

    @Test
    fun runInactivityMessageIsExplicit() {
        assertTrue(isRunSseInactivityMessage(RUN_SSE_INACTIVITY_ERROR_MESSAGE))
        assertTrue(isRunSseInactivityMessage("Run Hermes interrotto: NESSUN DATO PER 90S."))
        assertFalse(isRunSseInactivityMessage("connessione chiusa"))
        assertFalse(isRunSseInactivityMessage(null))
    }
}
