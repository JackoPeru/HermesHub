package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPollerTest {

    @Test
    fun backoffStartsAtBaseForZeroFailures() {
        assertEquals(8_000L, nextPollDelay(0, 8_000L, 60_000L))
        assertEquals(6_000L, nextPollDelay(0, 6_000L, 60_000L))
        assertEquals(5_000L, nextPollDelay(0, 5_000L, 60_000L))
        assertEquals(10_000L, nextPollDelay(0, 10_000L, 60_000L))
    }

    @Test
    fun backoffDoublesOnConsecutiveFailures() {
        val base = 5_000L
        val max = 60_000L
        assertEquals(5_000L, nextPollDelay(0, base, max))
        assertEquals(10_000L, nextPollDelay(1, base, max))
        assertEquals(20_000L, nextPollDelay(2, base, max))
        assertEquals(40_000L, nextPollDelay(3, base, max))
        assertEquals(60_000L, nextPollDelay(4, base, max))
        assertEquals(60_000L, nextPollDelay(5, base, max))
        assertEquals(60_000L, nextPollDelay(10, base, max))
    }

    @Test
    fun backoffCapsAtMaxForAllKnownBases() {
        assertEquals(60_000L, nextPollDelay(10, 8_000L, 60_000L))
        assertEquals(60_000L, nextPollDelay(10, 6_000L, 60_000L))
        assertEquals(60_000L, nextPollDelay(10, 5_000L, 60_000L))
        assertEquals(60_000L, nextPollDelay(10, 10_000L, 60_000L))
        // Max minore di base: mai superare max.
        assertEquals(1_000L, nextPollDelay(0, 5_000L, 1_000L))
        assertEquals(1_000L, nextPollDelay(3, 5_000L, 1_000L))
    }

    @Test
    fun backoffResetsToBaseAfterSuccess() {
        val base = 5_000L
        val max = 60_000L
        var failures = 0
        // 3 failure consecutive: delay cresce.
        failures += 1
        val afterOne = nextPollDelay(failures, base, max)
        failures += 1
        val afterTwo = nextPollDelay(failures, base, max)
        assertTrue(afterTwo > afterOne)
        // Successo -> reset a 0 -> di nuovo base.
        failures = 0
        assertEquals(base, nextPollDelay(failures, base, max))
    }

    @Test
    fun negativeFailuresTreatedAsSuccess() {
        assertEquals(5_000L, nextPollDelay(-1, 5_000L, 60_000L))
    }

    @Test
    fun keysUnchangedDoNotRestart() {
        assertFalse(pollKeysChanged(arrayOf<Any?>("gw", 480), arrayOf<Any?>("gw", 480)))
        assertFalse(pollKeysChanged(arrayOf(null), arrayOf(null)))
        assertFalse(pollKeysChanged(emptyArray<Any?>(), emptyArray<Any?>()))
    }

    @Test
    fun keysChangedRestartPoller() {
        assertTrue(pollKeysChanged(arrayOf("gw1"), arrayOf("gw2")))
        assertTrue(pollKeysChanged(arrayOf<Any?>("gw", 480), arrayOf<Any?>("gw", 640)))
        assertTrue(pollKeysChanged(arrayOf(true), arrayOf(false)))
        assertTrue(pollKeysChanged(arrayOf("a", "b"), arrayOf("a")))
        assertTrue(pollKeysChanged(arrayOf(null), arrayOf("x")))
    }
}
