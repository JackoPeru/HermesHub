package com.nemoclaw.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-flight condiviso processo per le approval (contratto ActiveWorkStore).
 */
class HermesApprovalClaimTest {

    @Before
    fun setUp() {
        resetApprovalClaimsForTest()
    }

    @Test
    fun claimOnceSecondIsSkip() {
        val id = "ap_" + UUID.randomUUID()
        assertTrue(tryClaimApproval(id))
        assertFalse(tryClaimApproval(id))
    }

    @Test
    fun differentIdsAreIndependent() {
        val first = "ap_" + UUID.randomUUID()
        val second = "ap_" + UUID.randomUUID()
        assertTrue(tryClaimApproval(first))
        assertTrue(tryClaimApproval(second))
    }

    @Test
    fun blankIdsNeverClaimed() {
        assertFalse(tryClaimApproval(""))
        assertFalse(tryClaimApproval("   "))
    }

    @Test
    fun trimmedIdsAreSameClaim() {
        val base = "ap_" + UUID.randomUUID()
        assertTrue(tryClaimApproval("  $base  "))
        assertFalse(tryClaimApproval(base))
    }

    @Test
    fun concurrentClaimsAllowOnlyOne() {
        val id = "ap_" + UUID.randomUUID()
        val threads = 10
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        val wins = AtomicInteger(0)
        try {
            for (i in 0 until threads) {
                pool.execute {
                    ready.countDown()
                    ready.await()
                    if (tryClaimApproval(id)) wins.incrementAndGet()
                    done.countDown()
                }
            }
            done.await()
        } finally {
            pool.shutdownNow()
        }
        assertTrue(wins.get() == 1)
    }
}
