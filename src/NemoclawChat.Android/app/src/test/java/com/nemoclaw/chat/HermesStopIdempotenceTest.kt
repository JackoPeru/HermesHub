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
 * Stop idempotente per runId: untrack + rimozione binding una sola volta,
 * secondo stop no-op (anti-race prima del POST).
 */
class HermesStopIdempotenceTest {

    @Before
    fun setUp() {
        resetStopClaimsForTest()
    }

    @Test
    fun secondStopForSameRunIsSkip() {
        val runId = "run_" + UUID.randomUUID()
        assertTrue(tryClaimStopRun(runId))
        assertFalse(tryClaimStopRun(runId))
    }

    @Test
    fun differentRunsAreIndependent() {
        val first = "run_" + UUID.randomUUID()
        val second = "run_" + UUID.randomUUID()
        assertTrue(tryClaimStopRun(first))
        assertTrue(tryClaimStopRun(second))
    }

    @Test
    fun blankRunNeverClaimed() {
        assertFalse(tryClaimStopRun(""))
        assertFalse(tryClaimStopRun("   "))
    }

    @Test
    fun concurrentStopsAllowOnlyOne() {
        val runId = "run_" + UUID.randomUUID()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        val wins = AtomicInteger(0)
        try {
            for (i in 0 until threads) {
                pool.execute {
                    ready.countDown()
                    try {
                        ready.await()
                    } catch (_: InterruptedException) {
                        // Chiusura test: niente da segnalare.
                    }
                    if (tryClaimStopRun(runId)) wins.incrementAndGet()
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
