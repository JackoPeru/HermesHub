package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feedback recupero turno staccato: ogni esito ha un messaggio guidato.
 * Puro, nessuna rete.
 */
class DetachedResumeTest {
    @Test
    fun `live states drive banner subtitle`() {
        assertTrue(detachedResumeSubtitle(DetachedResumeOutcome.STILL_RUNNING)!!.contains("Ancora"))
        assertTrue(detachedResumeSubtitle(DetachedResumeOutcome.NET_ERROR)!!.contains("Rete"))
        assertNull(detachedResumeSubtitle(DetachedResumeOutcome.SKIPPED))
        assertNull(detachedResumeSubtitle(DetachedResumeOutcome.RECOVERED))
        assertNull(detachedResumeSubtitle(DetachedResumeOutcome.AUTH_GONE))
    }

    @Test
    fun `terminal empty outcomes drive one shot note`() {
        assertTrue(detachedResumeNote(DetachedResumeOutcome.AUTH_GONE)!!.contains("chiave"))
        assertTrue(detachedResumeNote(DetachedResumeOutcome.NOT_FOUND_GONE)!!.contains("niente da recuperare", ignoreCase = true))
        assertTrue(detachedResumeNote(DetachedResumeOutcome.EXPIRED_GONE)!!.contains("niente da recuperare", ignoreCase = true))
        assertTrue(detachedResumeNote(DetachedResumeOutcome.WORK_FAILED_GONE)!!.isNotBlank())
        assertNull(detachedResumeNote(DetachedResumeOutcome.STILL_RUNNING))
        assertNull(detachedResumeNote(DetachedResumeOutcome.NET_ERROR))
        assertNull(detachedResumeNote(DetachedResumeOutcome.RECOVERED))
        assertNull(detachedResumeNote(DetachedResumeOutcome.SKIPPED))
    }

    @Test
    fun `every outcome maps somewhere visible`() {
        for (outcome in DetachedResumeOutcome.entries) {
            val visible = detachedResumeSubtitle(outcome) != null ||
                detachedResumeNote(outcome) != null ||
                outcome == DetachedResumeOutcome.SKIPPED ||
                outcome == DetachedResumeOutcome.RECOVERED
            assertTrue("esito muto: $outcome", visible)
            assertEquals(outcome, DetachedResumeOutcome.valueOf(outcome.name))
        }
    }
}
