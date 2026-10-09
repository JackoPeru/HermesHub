package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalMediaAuthBoundaryTest {
    @Test
    fun `fetch esterno non allega bearer o token alla URL`() {
        val url = "https://cdn.example/files/report.pdf?download=1"

        val plan = externalMediaRequestPlan(
            AppSettings(gatewayUrl = "https://gateway.example/v1"),
            url,
            "test-gateway-token"
        )

        assertEquals(url, plan.url)
        assertNull(plan.bearerToken)
    }

    @Test
    fun `fetch Hermes usa header e lascia invariata la URL`() {
        val url = "https://gateway.example/v1/media/report.pdf"

        val plan = externalMediaRequestPlan(
            AppSettings(gatewayUrl = "https://gateway.example/v1"),
            url,
            "test-gateway-token"
        )

        assertEquals(url, plan.url)
        assertEquals("test-gateway-token", plan.bearerToken)
    }
}
