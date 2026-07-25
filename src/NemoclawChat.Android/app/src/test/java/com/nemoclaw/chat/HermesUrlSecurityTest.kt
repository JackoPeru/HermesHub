package com.nemoclaw.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesUrlSecurityTest {
    private val settings = AppSettings(gatewayUrl = "http://hermes-node:8642/v1")

    @Test
    fun `configured endpoint requires an absolute http origin`() {
        assertFalse(hasConfiguredHermesEndpoint(AppSettings(gatewayUrl = "")))
        assertFalse(hasConfiguredHermesEndpoint(AppSettings(gatewayUrl = "/v1")))
        assertFalse(hasConfiguredHermesEndpoint(AppSettings(gatewayUrl = "ftp://gateway.example")))
        assertTrue(hasConfiguredHermesEndpoint(AppSettings(gatewayUrl = "https://gateway.example/v1")))
    }

    @Test
    fun `auth is limited to configured scheme host port and api path`() {
        assertTrue(shouldAuthenticateHermesUrl(settings, "http://hermes-node:8642/v1/media/image.png"))
        assertFalse(shouldAuthenticateHermesUrl(settings, "https://hermes-node:8642/v1/media/image.png"))
        assertFalse(shouldAuthenticateHermesUrl(settings, "http://hermes-node:9443/v1/media/image.png"))
        assertFalse(shouldAuthenticateHermesUrl(settings, "http://other-node:8642/v1/media/image.png"))
        assertFalse(shouldAuthenticateHermesUrl(settings, "https://example.test/v1/media/image.png"))
        assertFalse(shouldAuthenticateHermesUrl(settings, "http://hermes-node:8642/public/image.png"))
    }

    @Test
    fun `default ports normalize without widening origin`() {
        val https = AppSettings(gatewayUrl = "https://hermes.example/v1")
        assertTrue(shouldAuthenticateHermesUrl(https, "https://hermes.example:443/v1/capabilities"))
        assertFalse(shouldAuthenticateHermesUrl(https, "https://hermes.example:444/v1/capabilities"))
    }
}
