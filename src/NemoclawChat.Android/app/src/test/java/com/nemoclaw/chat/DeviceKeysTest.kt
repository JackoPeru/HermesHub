package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceKeysTest {
    @Test
    fun `canonical payload joins five lines uppercasing method`() {
        val payload = canonicalDevicePayload("post", "/v1/hub/server/maintenance", "1700000000", "nabc123", "deadbeef")
        assertEquals(
            "POST\n/v1/hub/server/maintenance\n1700000000\nnabc123\ndeadbeef",
            payload.toString(Charsets.UTF_8)
        )
    }

    @Test
    fun `canonical payload changes with any field`() {
        val base = canonicalDevicePayload("POST", "/v1/a", "1", "n1", "h1")
        assertTrue(!canonicalDevicePayload("POST", "/v1/b", "1", "n1", "h1").contentEquals(base))
        assertTrue(!canonicalDevicePayload("POST", "/v1/a", "2", "n1", "h1").contentEquals(base))
        assertTrue(!canonicalDevicePayload("POST", "/v1/a", "1", "n2", "h1").contentEquals(base))
        assertTrue(!canonicalDevicePayload("POST", "/v1/a", "1", "n1", "h2").contentEquals(base))
    }

    @Test
    fun `sha256 hex matches known vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun `device id matches server key format`() {
        repeat(20) {
            val id = newDeviceId()
            assertTrue(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,63}")))
        }
    }

    @Test
    fun `signature headers are the four agreed names`() {
        assertEquals(
            listOf("X-Hermes-Key-Id", "X-Hermes-Timestamp", "X-Hermes-Nonce", "X-Hermes-Signature"),
            deviceSignatureHeaders()
        )
    }
}
