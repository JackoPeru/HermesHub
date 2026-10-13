package com.nemoclaw.chat

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rehydrate con profilo: il binding porta il routing /p/<profilo>/...,
 * il 404 a profilo ignoto non cancella mai (tiene e riprova).
 */
class WorkBindingProfileTest {

    @Test
    fun roundtripPreservaProfiloMultiplex() {
        val binding = ActiveWorkBinding(
            conversationId = "c1",
            runId = "r1",
            goal = "fai X",
            startedAtMs = 1700000000000L,
            profile = "coder",
            multiplex = true
        )
        val decoded = decodeActiveWorkBinding(encodeActiveWorkBinding(binding))
        assertEquals(binding, decoded)
        assertEquals("coder", decoded!!.profile)
        assertTrue(decoded.multiplex)
    }

    @Test
    fun legacySenzaProfiloRestaValido() {
        val legacy = JSONObject().put("conversationId", "c").put("runId", "r")
        val decoded = decodeActiveWorkBinding(legacy)
        assertEquals("c", decoded!!.conversationId)
        assertNull(decoded.profile)
        assertFalse(decoded.multiplex)
    }

    @Test
    fun profiloBindingVinceSulCorrente() {
        assertEquals("coder", resolveWorkProfile("coder", "altro"))
        assertEquals("corrente", resolveWorkProfile(null, "corrente"))
        assertEquals("corrente", resolveWorkProfile("  ", "corrente"))
        assertNull(resolveWorkProfile(null, null))
        assertNull(resolveWorkProfile("", "  "))
    }

    @Test
    fun multiplexSegueIlProfiloBinding() {
        assertTrue(resolveWorkMultiplex("coder", true, false))
        assertFalse(resolveWorkMultiplex("coder", false, true))
        assertTrue(resolveWorkMultiplex(null, false, true))
        assertFalse(resolveWorkMultiplex(" ", true, false))
    }

    @Test
    fun cancellaSu404SoloAProfiloNoto() {
        assertTrue(shouldClearBindingOn404("coder"))
        assertFalse(shouldClearBindingOn404(null))
        assertFalse(shouldClearBindingOn404(""))
        assertFalse(shouldClearBindingOn404("   "))
    }

    @Test
    fun singleFlightSkipSeOccupato() = runBlocking {
        // Stesso pattern di recheckActiveWork: tryLock, se occupato skip.
        val mutex = Mutex()
        assertTrue(mutex.tryLock())
        try {
            assertFalse(mutex.tryLock())
        } finally {
            mutex.unlock()
        }
        assertTrue(mutex.tryLock())
        mutex.unlock()
    }
}
