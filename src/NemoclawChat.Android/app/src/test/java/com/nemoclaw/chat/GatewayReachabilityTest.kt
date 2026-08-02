package com.nemoclaw.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayReachabilityTest {
    @Test
    fun rejectsRelativeOrMissingGatewayUrlWithoutThrowing() {
        assertFalse(isValidGatewayProbeUrl("/v1/capabilities"))
        assertFalse(isValidGatewayProbeUrl(""))
        assertFalse(isValidGatewayProbeUrl("not a url"))
        assertTrue(isValidGatewayProbeUrl("http://100.64.0.1:8642/v1/capabilities"))
        assertTrue(isValidGatewayProbeUrl("https://hermes.example/v1/capabilities"))
    }

    @Test
    fun acceptsOnlySuccessfulAuthenticatedGatewayResponse() {
        assertTrue(isSuccessfulGatewayProbe(200))
        assertTrue(isSuccessfulGatewayProbe(204))
        assertFalse(isSuccessfulGatewayProbe(0))
        assertFalse(isSuccessfulGatewayProbe(401))
        assertFalse(isSuccessfulGatewayProbe(500))
    }
}
