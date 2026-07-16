package com.nemoclaw.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticProbeTest {
    @Test
    fun attemptPlanIsBoundedInsteadOfHostAuthCartesianProduct() {
        val plan = diagnosticProbePlan("http://configured-gateway:8642/health", "configured-secret")

        assertEquals(
            listOf("http://configured-gateway:8642/health"),
            plan.routes
        )
        assertEquals(listOf("configured-secret", null), plan.authCandidates)
        assertEquals(2, plan.maxAttempts)
    }

    @Test
    fun transportFailureMovesHostWithoutTryingMoreAuthOnDeadRoute() = runBlocking {
        val attempts = mutableListOf<DiagnosticProbeAttempt>()

        val result = probeDiagnosticEndpoint("http://configured-gateway:8642/health", "configured-secret") { attempt ->
            attempts += attempt
            DiagnosticAttemptResult.transportFailure("timeout")
        }

        assertEquals(1, attempts.size)
        assertEquals("configured-secret", attempts[0].bearerToken)
        assertEquals("http://configured-gateway:8642/health", result.effectiveUrl)
        assertNull(result.statusCode)
    }

    @Test
    fun authRetryStaysOnFirstRouteThatAnsweredHttp() = runBlocking {
        val attempts = mutableListOf<DiagnosticProbeAttempt>()

        val result = probeDiagnosticEndpoint("http://configured-gateway:8642/health", "configured-secret") { attempt ->
            attempts += attempt
            if (attempt.bearerToken == "configured-secret") {
                DiagnosticAttemptResult.http(401, "unauthorized")
            } else {
                DiagnosticAttemptResult.http(200, "ok")
            }
        }

        assertEquals(2, attempts.size)
        assertEquals(listOf("http://configured-gateway:8642/health", "http://configured-gateway:8642/health"), attempts.map { it.url })
        assertEquals(listOf("configured-secret", null), attempts.map { it.bearerToken })
        assertEquals("http://configured-gateway:8642/health", result.effectiveUrl)
        assertNull(result.bearerToken)
    }

    @Test
    fun exhaustedAuthDoesNotRestartSameAuthPlanOnOtherHosts() = runBlocking {
        val attempts = mutableListOf<DiagnosticProbeAttempt>()

        val result = probeDiagnosticEndpoint("http://configured-gateway:8642/health", "configured-secret") { attempt ->
            attempts += attempt
            DiagnosticAttemptResult.http(401, "unauthorized")
        }

        assertEquals(2, attempts.size)
        assertEquals(listOf("http://configured-gateway:8642/health"), attempts.map { it.url }.distinct())
        assertEquals(401, result.statusCode)
        assertNull(result.bearerToken)
    }

    @Test
    fun effectiveRouteKeepsRequestedPathAndRawQuery() {
        val effective = diagnosticEffectiveUrl(
            requestedUrl = "http://configured-gateway:8642/v1/hub/state?include=full%20state",
            effectiveRouteUrl = "http://gateway.tailnet-example.ts.net:8642/health"
        )

        assertEquals(
            "http://gateway.tailnet-example.ts.net:8642/v1/hub/state?include=full%20state",
            effective
        )
    }
}
