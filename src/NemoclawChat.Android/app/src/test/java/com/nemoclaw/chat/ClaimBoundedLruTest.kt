package com.nemoclaw.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Claim bounded LRU (cap 500): stessa semantica tryClaim/release,
 * eviction del meno recente quando pieno.
 */
class ClaimBoundedLruTest {

    @Before
    fun setUp() {
        resetApprovalClaimsForTest()
        resetStopClaimsForTest()
    }

    @After
    fun tearDown() {
        resetApprovalClaimsForTest()
        resetStopClaimsForTest()
    }

    @Test
    fun approvalClaimsAreBounded() {
        for (i in 0 until CLAIM_LRU_CAP) {
            assertTrue(tryClaimApproval("ap_ev_$i"))
        }
        assertEquals(CLAIM_LRU_CAP, approvalClaimSizeForTest())
        // Uno in piu: nessun sforamento, il piu vecchio viene sfrattato.
        assertTrue(tryClaimApproval("ap_ev_extra"))
        assertEquals(CLAIM_LRU_CAP, approvalClaimSizeForTest())
        // Il primo e stato sfrattato: puo essere reclamato di nuovo.
        assertTrue(tryClaimApproval("ap_ev_0"))
        assertEquals(CLAIM_LRU_CAP, approvalClaimSizeForTest())
    }

    @Test
    fun stopClaimsAreBounded() {
        for (i in 0 until CLAIM_LRU_CAP) {
            assertTrue(tryClaimStopRun("run_ev_$i"))
        }
        assertEquals(CLAIM_LRU_CAP, stopClaimSizeForTest())
        assertTrue(tryClaimStopRun("run_ev_extra"))
        assertEquals(CLAIM_LRU_CAP, stopClaimSizeForTest())
        assertTrue(tryClaimStopRun("run_ev_0"))
        assertEquals(CLAIM_LRU_CAP, stopClaimSizeForTest())
    }

    @Test
    fun duplicateClaimDoesNotGrowButStaysSkip() {
        assertTrue(tryClaimApproval("ap_dup"))
        val sizeAfterFirst = approvalClaimSizeForTest()
        assertFalse(tryClaimApproval("ap_dup"))
        assertEquals(sizeAfterFirst, approvalClaimSizeForTest())
        assertTrue(tryClaimStopRun("run_dup"))
        val stopSize = stopClaimSizeForTest()
        assertFalse(tryClaimStopRun("run_dup"))
        assertEquals(stopSize, stopClaimSizeForTest())
    }

    @Test
    fun releaseFreesSlot() {
        assertTrue(tryClaimApproval("ap_rel"))
        releaseApprovalClaim("ap_rel")
        assertTrue(tryClaimApproval("ap_rel"))
        assertTrue(tryClaimStopRun("run_rel"))
        releaseStopClaim("run_rel")
        assertTrue(tryClaimStopRun("run_rel"))
    }

    @Test
    fun blankIdsNeverClaimed() {
        assertFalse(tryClaimApproval(""))
        assertFalse(tryClaimStopRun("   "))
        assertEquals(0, approvalClaimSizeForTest())
        assertEquals(0, stopClaimSizeForTest())
    }
}
