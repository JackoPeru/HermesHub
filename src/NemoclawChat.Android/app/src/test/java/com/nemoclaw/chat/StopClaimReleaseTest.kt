package com.nemoclaw.chat

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Stop retry: a esaurimento senza conferma il claim viene rilasciato
 * cosi un nuovo stop resta possibile (niente claim orfano per sempre).
 */
class StopClaimReleaseTest {

    @Before
    fun setUp() {
        resetStopClaimsForTest()
    }

    @After
    fun tearDown() {
        resetStopClaimsForTest()
    }

    @Test
    fun exhaustedRetriesReleaseClaimForNewStop() {
        val runId = "run_" + UUID.randomUUID()
        // Primo stop: claim preso, secondo e skip (single-flight).
        assertTrue(tryClaimStopRun(runId))
        assertFalse(tryClaimStopRun(runId))
        // Il service a esaurimento chiama releaseStopClaim (con log): simula qui.
        assertTrue(shouldReleaseStopClaimOnExhaustion(confirmed = false))
        releaseStopClaim(runId)
        // Un nuovo stop resta possibile.
        assertTrue(tryClaimStopRun(runId))
    }

    @Test
    fun confirmedStopKeepsClaim() {
        // Stop confermato (200/404): il run e sparito, il claim resta per idempotenza.
        assertFalse(shouldReleaseStopClaimOnExhaustion(confirmed = true))
        val runId = "run_" + UUID.randomUUID()
        assertTrue(tryClaimStopRun(runId))
        // Nessun rilascio: il duplicato resta skip.
        assertFalse(tryClaimStopRun(runId))
    }

    @Test
    fun releaseIsTrimmedAndSafeOnBlank() {
        val runId = "run_" + UUID.randomUUID()
        assertTrue(tryClaimStopRun(runId))
        releaseStopClaim("  $runId  ")
        assertTrue(tryClaimStopRun(runId))
        // Blank: no-op, mai eccezioni.
        releaseStopClaim("")
        releaseStopClaim("   ")
        assertTrue(tryClaimStopRun("run_" + UUID.randomUUID()))
    }
}
