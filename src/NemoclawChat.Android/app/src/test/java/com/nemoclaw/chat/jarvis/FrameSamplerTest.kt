package com.nemoclaw.chat.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSamplerTest {
    @Test
    fun `sampling is rate limited and eventually refreshes an unchanged scene`() {
        val sampler = FrameSampler(minimumIntervalMillis = 1_000, maximumIntervalMillis = 4_000)
        assertTrue(sampler.shouldAccept(1_000, 0L))
        assertFalse(sampler.shouldAccept(1_500, -1L))
        assertFalse(sampler.shouldAccept(2_100, 0L))
        assertTrue(sampler.shouldAccept(5_100, 0L))
    }

    @Test
    fun `changed scene is accepted after minimum interval`() {
        val sampler = FrameSampler(minimumIntervalMillis = 500, maximumIntervalMillis = 5_000)
        assertTrue(sampler.shouldAccept(1_000, 0L))
        assertTrue(sampler.shouldAccept(1_600, -1L))
    }

    @Test
    fun `rolling buffer drops oldest frame`() {
        val buffer = RollingFrameBuffer(2)
        buffer.add(SampledFrame(byteArrayOf(1), 1, 1))
        buffer.add(SampledFrame(byteArrayOf(2), 2, 2))
        buffer.add(SampledFrame(byteArrayOf(3), 3, 3))
        assertEquals(3L, buffer.latest()?.capturedAtMillis)
        buffer.clear()
        assertEquals(null, buffer.latest())
    }

    @Test
    fun `signature changes with content`() {
        assertNotEquals(perceptualSignature(ByteArray(128)), perceptualSignature(ByteArray(128) { it.toByte() }))
    }
}
