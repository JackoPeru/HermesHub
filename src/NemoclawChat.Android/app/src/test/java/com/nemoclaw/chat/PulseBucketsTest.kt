package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class PulseBucketsTest {
    @Test
    fun `seven buckets oldest to today`() {
        val nowMs = 1_700_000_000_000L
        val day = 86_400_000L
        val created = listOf(
            (nowMs - 6 * day) / 1000L to "done",
            (nowMs - 6 * day) / 1000L to "failed",
            nowMs / 1000L to "done",
            nowMs / 1000L to "done",
            (nowMs - 30 * day) / 1000L to "done"
        )
        val buckets = bucketPulseDays(created, nowMs)
        assertEquals(7, buckets.size)
        assertEquals(1, buckets[0].done)
        assertEquals(1, buckets[0].failed)
        assertEquals(2, buckets[6].done)
        assertEquals(0, buckets[6].failed)
        assertEquals(0, buckets[3].done + buckets[3].failed)
    }

    @Test
    fun `unknown statuses count as failed`() {
        val nowMs = 1_700_000_000_000L
        val buckets = bucketPulseDays(listOf(nowMs / 1000L to "running"), nowMs)
        assertEquals(0, buckets[6].done)
        assertEquals(1, buckets[6].failed)
    }

    @Test
    fun `empty input gives empty week`() {
        val buckets = bucketPulseDays(emptyList(), 1_700_000_000_000L)
        assertEquals(7, buckets.size)
        assertTrue(buckets.all { it.done == 0 && it.failed == 0 })
    }

    private fun assertTrue(value: Boolean) {
        assertEquals(true, value)
    }
}
