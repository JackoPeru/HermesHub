package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class HermesWorkServiceTest {

    @Test
    fun foregroundSummarySingleRunShowsGoal() {
        assertEquals("Hermes al lavoro", foregroundSummary(1, ""))
        assertEquals("Hermes al lavoro", foregroundSummary(0, ""))
        assertEquals("Usiamo questo al posto di whisper?", foregroundSummary(1, "Usiamo questo al posto di whisper?"))
    }

    @Test
    fun foregroundSummaryMultiRunCounts() {
        assertEquals("Hermes al lavoro (2)", foregroundSummary(2, "qualcosa"))
        assertEquals("Hermes al lavoro (4)", foregroundSummary(4, ""))
    }
}
