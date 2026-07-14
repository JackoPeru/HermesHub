package com.nemoclaw.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticProbeTest {
    @Test
    fun attemptPlanIsBoundedInsteadOfHostAuthCartesianProduct() {
        val plan = diagnosticProbePlan("http://hermes:8642/health", "configured-secret")

        assertEquals(
            listOf(
                "http://hermes:8642/health",
                "http://100.94.223.14:8642/health",
                "http://hermes.local:8642/health"
            ),
            plan.routes
        )
        assertEquals(listOf("configured-secret", HERMES_FALLBACK_API_KEY, null), plan.authCandidates)
        assertEquals(5, plan.maxAttempts)
    }

    @Test
    fun transportFailureMovesHostWithoutTryingMoreAuthOnDeadRoute() = runBlocking {
        val attempts = mutableListOf<DiagnosticProbeAttempt>()

        val result = probeDiagnosticEndpoint("http://hermes:8642/health", "configured-secret") { attempt ->
            attempts += attempt
            if (attempt.url.startsWith("http://hermes:8642")) {
                DiagnosticAttemptResult.transportFailure("timeout")
            } else {
                DiagnosticAttemptResult.http(200, "ok")
            }
        }

        assertEquals(2, attempts.size)
        assertEquals("configured-secret", attempts[0].bearerToken)
        assertEquals("configured-secret", attempts[1].bearerToken)
        assertEquals("http://100.94.223.14:8642/health", result.effectiveUrl)
        assertEquals(200, result.statusCode)
    }

    @Test
    fun authRetryStaysOnFirstRouteThatAnsweredHttp() = runBlocking {
        val attempts = mutableListOf<DiagnosticProbeAttempt>()

        val result = probeDiagnosticEndpoint("http://hermes:8642/health", "configured-secret") { attempt ->
            attempts += attempt
            if (attempt.bearerToken == "configured-secret") {
                DiagnosticAttemptResult.http(401, "unauthorized")
            } else {
                DiagnosticAttemptResult.http(200, "ok")
            }
        }

        assertEquals(2, attempts.size)
        assertEquals(listOf("http://hermes:8642/health", "http://hermes:8642/health"), attempts.map { it.url })
        assertEquals(listOf("configured-secret", HERMES_FALLBACK_API_KEY), attempts.map { it.bearerToken })
        assertEquals("http://hermes:8642/health", result.effectiveUrl)
        assertEquals(HERMES_FALLBACK_API_KEY, result.bearerToken)
    }

    @Test
    fun exhaustedAuthDoesNotRestartSameAuthPlanOnOtherHosts() = runBlocking {
        val attempts = mutableListOf<DiagnosticProbeAttempt>()

        val result = probeDiagnosticEndpoint("http://hermes:8642/health", "configured-secret") { attempt ->
            attempts += attempt
            DiagnosticAttemptResult.http(401, "unauthorized")
        }

        assertEquals(3, attempts.size)
        assertEquals(listOf("http://hermes:8642/health"), attempts.map { it.url }.distinct())
        assertEquals(401, result.statusCode)
        assertNull(result.bearerToken)
    }

    @Test
    fun effectiveRouteKeepsRequestedPathAndRawQuery() {
        val effective = diagnosticEffectiveUrl(
            requestedUrl = "http://hermes:8642/v1/hub/state?include=full%20state",
            effectiveRouteUrl = "http://100.94.223.14:8642/health"
        )

        assertEquals(
            "http://100.94.223.14:8642/v1/hub/state?include=full%20state",
            effective
        )
    }
}
