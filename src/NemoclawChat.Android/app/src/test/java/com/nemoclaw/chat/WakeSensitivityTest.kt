package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeSensitivityTest {
    @Test
    fun `default gain keeps legacy threshold`() {
        assertEquals(31.0, wakeVoiceThreshold(10.0, 1.0f), 0.001)
    }

    @Test
    fun `permissive lowers strict raises`() {
        val normal = wakeVoiceThreshold(10.0, 1.0f)
        assertEquals(true, wakeVoiceThreshold(10.0, 0.5f) < normal)
        assertEquals(true, wakeVoiceThreshold(10.0, 2.0f) > normal)
    }

    @Test
    fun `clamps hold`() {
        assertEquals(18.0, wakeVoiceThreshold(0.0, 1.0f), 0.001)
        assertEquals(1800.0, wakeVoiceThreshold(100000.0, 2.0f), 0.001)
        assertEquals(wakeVoiceThreshold(10.0, 2.0f), wakeVoiceThreshold(10.0, 99.0f), 0.001)
        assertEquals(wakeVoiceThreshold(10.0, 0.5f), wakeVoiceThreshold(10.0, -3.0f), 0.001)
    }

    @Test
    fun `stats line formats minutes and hits`() {
        assertEquals("Ascolto oggi: 2 min · attivazioni: 3.", wakeStatsLine(150L to 3L))
    }
}
