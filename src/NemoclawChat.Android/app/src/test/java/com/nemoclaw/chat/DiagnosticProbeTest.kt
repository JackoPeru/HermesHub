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
        assertEquals(listOf("configured-secret"), plan.authCandidates)
        assertEquals(1, plan.maxAttempts)
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
    fun generic401DoesNotDowngradeConfiguredCredential() = runBlocking {
        val attempts = mutableListOf<DiagnosticProbeAttempt>()

        val result = probeDiagnosticEndpoint("http://configured-gateway:8642/health", "configured-secret") { attempt ->
            attempts += attempt
            DiagnosticAttemptResult.http(401, "token not accepted")
        }

        assertEquals(1, attempts.size)
        assertEquals(listOf("http://configured-gateway:8642/health"), attempts.map { it.url })
        assertEquals(listOf("configured-secret"), attempts.map { it.bearerToken })
        assertEquals("http://configured-gateway:8642/health", result.effectiveUrl)
        assertEquals("configured-secret", result.bearerToken)
        assertEquals(401, result.statusCode)
        assertEquals("token not accepted", result.body)
    }

    @Test
    fun rejectedCredentialPreservesOriginal401Response() = runBlocking {
        val attempts = mutableListOf<DiagnosticProbeAttempt>()

        val result = probeDiagnosticEndpoint("http://configured-gateway:8642/health", "configured-secret") { attempt ->
            attempts += attempt
            DiagnosticAttemptResult.http(401, "original error body")
        }

        assertEquals(1, attempts.size)
        assertEquals(listOf("http://configured-gateway:8642/health"), attempts.map { it.url }.distinct())
        assertEquals(401, result.statusCode)
        assertEquals("original error body", result.body)
        assertEquals("configured-secret", result.bearerToken)
        assertEquals(1, result.attemptCount)
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
