package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRootsTest {

    @Test
    fun localValidFirstThenConfigured() {
        val settings = AppSettings(
            gatewayUrl = "https://relay.example/v1",
            localGatewayUrl = "http://192.168.1.10:8642/v1"
        )
        assertEquals(
            listOf("http://192.168.1.10:8642/v1", "https://relay.example/v1"),
            localRootCandidates(settings)
        )
    }

    @Test
    fun blankLocalYieldsOnlyConfigured() {
        val settings = AppSettings(
            gatewayUrl = "https://relay.example/v1",
            localGatewayUrl = ""
        )
        assertEquals(listOf("https://relay.example/v1"), localRootCandidates(settings))
    }

    @Test
    fun invalidLocalIsDropped() {
        listOf(
            "ftp://home.local:8642/v1",
            "not-a-url",
            "http://",
            "/v1",
            "192.168.1.10:8642"
        ).forEach { bad ->
            val settings = AppSettings(
                gatewayUrl = "https://relay.example/v1",
                localGatewayUrl = bad
            )
            assertEquals(listOf("https://relay.example/v1"), localRootCandidates(settings))
        }
    }

    @Test
    fun distinctCaseInsensitiveAndTrailingSlash() {
        val settings = AppSettings(
            gatewayUrl = "http://192.168.1.10:8642/v1/",
            localGatewayUrl = "HTTP://192.168.1.10:8642/v1"
        )
        assertEquals(1, localRootCandidates(settings).size)
    }

    @Test
    fun trimsWhitespace() {
        val settings = AppSettings(
            gatewayUrl = "  https://relay.example/v1/  ",
            localGatewayUrl = "  http://192.168.1.10:8642/v1/  "
        )
        assertEquals(
            listOf("http://192.168.1.10:8642/v1", "https://relay.example/v1"),
            localRootCandidates(settings)
        )
    }

    @Test
    fun emptyWhenBothBlank() {
        val settings = AppSettings(gatewayUrl = "", localGatewayUrl = "")
        assertEquals(emptyList<String>(), localRootCandidates(settings))
    }

    @Test
    fun wifiRacesBothRoots() {
        val settings = AppSettings(
            gatewayUrl = "https://relay.example/v1",
            localGatewayUrl = "http://192.168.1.10:8642/v1"
        )
        assertEquals(
            listOf("http://192.168.1.10:8642/v1", "https://relay.example/v1"),
            raceRootsForTransport(true, settings)
        )
    }

    @Test
    fun cellularUsesConfiguredOnly() {
        val settings = AppSettings(
            gatewayUrl = "https://relay.example/v1",
            localGatewayUrl = "http://192.168.1.10:8642/v1"
        )
        assertEquals(listOf("https://relay.example/v1"), raceRootsForTransport(false, settings))
    }

    @Test
    fun cellularWithBlankConfiguredIsEmpty() {
        val settings = AppSettings(gatewayUrl = "", localGatewayUrl = "http://192.168.1.10:8642/v1")
        assertEquals(emptyList<String>(), raceRootsForTransport(false, settings))
    }

    @Test
    fun fastestCacheKeyDistinguishesTransport() {
        val settings = AppSettings(
            gatewayUrl = "https://relay.example/v1",
            localGatewayUrl = "http://192.168.1.10:8642/v1"
        )
        assertTrue(fastestCacheKeyFor(settings, true).contains("true"))
        assertTrue(fastestCacheKeyFor(settings, false).contains("false"))
    }
}
