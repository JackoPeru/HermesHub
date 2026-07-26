package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HealthSyncTest {
    @Test
    fun wellbeingUrlUsesConfiguredV1Root() {
        assertEquals(
            "http://hermes-node:8642/v1/hub/wellbeing",
            HealthSync.wellbeingCollectionUrl(AppSettings(gatewayUrl = "http://hermes-node:8642/v1"))
        )
    }

    @Test
    fun wellbeingUrlRepairsFullApiEndpointAndKeepsReverseProxyPrefix() {
        assertEquals(
            "https://example.test/hermes/v1/hub/wellbeing",
            HealthSync.wellbeingCollectionUrl(
                AppSettings(gatewayUrl = "https://example.test/hermes/v1/responses?ignored=true")
            )
        )
        assertEquals(
            "https://example.test/base/v1/hub/wellbeing",
            HealthSync.wellbeingCollectionUrl(AppSettings(gatewayUrl = "https://example.test/base/V1/chat/completions"))
        )
        assertEquals(
            "https://example.test/v10/v1/hub/wellbeing",
            HealthSync.wellbeingCollectionUrl(AppSettings(gatewayUrl = "https://example.test/v10"))
        )
    }

    @Test
    fun wellbeingUrlRejectsNonHttpAndRelativeEndpoints() {
        assertThrows(IllegalArgumentException::class.java) {
            HealthSync.wellbeingCollectionUrl(AppSettings(gatewayUrl = "ftp://example.test/v1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HealthSync.wellbeingCollectionUrl(AppSettings(gatewayUrl = "/v1"))
        }
    }

    @Test
    fun healthConnectRateLimitIsRecognizedThroughWrappedErrors() {
        val wrapped = IllegalStateException(
            "Health read failed",
            java.io.IOException("Request rejected. Rate limited request quota has been exceeded.")
        )
        assertEquals(true, HealthSync.isHealthConnectRateLimit(wrapped))
        assertEquals(false, HealthSync.isHealthConnectRateLimit(java.io.IOException("connection reset")))
    }
}
