package com.nemoclaw.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun runtimeBadgeUsesVerifiedAgentVersionAndRollbackState() {
        assertTrue(gatewayRuntimeLabel(true, GatewayRuntimeStatus("0.20.0", "healthy", "")).contains("0.20.0"))
        assertTrue(gatewayRuntimeLabel(true, GatewayRuntimeStatus("0.19.1", "rolled_back", "patch preflight failed")).contains("rollback"))
        assertTrue(gatewayRuntimeLabel(true, GatewayRuntimeStatus("0.19.1", "rollback_failed", "")).contains("ripristino fallito"))
        assertTrue(gatewayRuntimeLabel(true, GatewayRuntimeStatus("0.19.1", "blocked", "")).contains("aggiornamento bloccato"))
        assertTrue(gatewayRuntimeLabel(true, GatewayRuntimeStatus("0.19.1", "unhealthy", "")).contains("stato da verificare"))
        assertTrue(gatewayRuntimeLabel(true, GatewayRuntimeStatus("0.19.1", "updating", "")).contains("aggiornamento in corso"))
        assertFalse(gatewayRuntimeLabel(true, GatewayRuntimeStatus("0.19.1", "healthy", "rollback text")).contains("rollback"))
        assertFalse(gatewayRuntimeLabel(false, GatewayRuntimeStatus("0.20.0", "healthy", "")).contains("0.20.0"))
        assertEquals("Rete non disponibile", gatewayRuntimeLabel(false, null))
    }
}
