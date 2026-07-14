package com.nemoclaw.chat

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val DIAGNOSTIC_RESPONSE_MAX_BYTES = 512L * 1024L

private val diagnosticHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
}

internal data class DiagnosticProbePlan(
    val routes: List<String>,
    val authCandidates: List<String?>
) {
    val maxAttempts: Int
        get() = if (routes.isEmpty() || authCandidates.isEmpty()) {
            0
        } else {
            routes.size + authCandidates.size - 1
        }
}

internal data class DiagnosticProbeAttempt(
    val url: String,
    val bearerToken: String?
)

internal data class DiagnosticAttemptResult(
    val statusCode: Int?,
    val body: String = "",
    val error: String? = null
) {
    companion object {
        fun http(statusCode: Int, body: String = "", error: String? = null) =
            DiagnosticAttemptResult(statusCode, body, error)

        fun transportFailure(error: String) = DiagnosticAttemptResult(null, error = error)
    }
}

internal data class DiagnosticProbeResult(
    val statusCode: Int?,
    val body: String,
    val error: String?,
    val effectiveUrl: String,
    val bearerToken: String?,
    val attemptCount: Int
)

internal fun diagnosticProbePlan(url: String, apiKey: String?): DiagnosticProbePlan =
    DiagnosticProbePlan(
        routes = plugAndPlayUrlCandidates(url),
        authCandidates = hermesAuthCandidates(apiKey)
    )

internal fun diagnosticEffectiveUrl(requestedUrl: String, effectiveRouteUrl: String): String {
    return runCatching {
        val requested = URI(requestedUrl)
        val effective = URI(effectiveRouteUrl)
        require(!effective.scheme.isNullOrBlank() && !effective.rawAuthority.isNullOrBlank())
        buildString {
            append(effective.scheme)
            append("://")
            append(effective.rawAuthority)
            append(requested.rawPath.orEmpty())
            if (!requested.rawQuery.isNullOrBlank()) append('?').append(requested.rawQuery)
        }
    }.getOrDefault(requestedUrl)
}

internal suspend fun probeDiagnosticEndpoint(
    url: String,
    apiKey: String?,
    execute: suspend (DiagnosticProbeAttempt) -> DiagnosticAttemptResult = ::executeDiagnosticAttempt
): DiagnosticProbeResult {
    val plan = diagnosticProbePlan(url, apiKey)
    if (plan.routes.isEmpty() || plan.authCandidates.isEmpty()) {
        return DiagnosticProbeResult(null, "", "Nessuna route diagnostica disponibile.", url, null, 0)
    }

    var attempts = 0
    var last = DiagnosticProbeResult(null, "", "Gateway non raggiungibile.", url, plan.authCandidates.first(), 0)
    for (route in plan.routes) {
        val primaryAttempt = DiagnosticProbeAttempt(route, plan.authCandidates.first())
        val primary = executeSafely(primaryAttempt, execute)
        attempts++
        last = primary.toProbeResult(primaryAttempt, attempts)

        // Transport failure says nothing about credentials. Move host immediately;
        // do not multiply every auth candidate across an unreachable route.
        if (primary.statusCode == null) continue

        if (!shouldRetryHermesWithBearerAuth(primary.statusCode, primary.body)) return last

        // This route answered HTTP, so it is the effective route. Retry only auth
        // on this host and never restart the host/auth Cartesian product.
        for (token in plan.authCandidates.drop(1)) {
            val authAttempt = DiagnosticProbeAttempt(route, token)
            val authResult = executeSafely(authAttempt, execute)
            attempts++
            last = authResult.toProbeResult(authAttempt, attempts)
            if (authResult.statusCode == null) return last
            if (!shouldRetryHermesWithBearerAuth(authResult.statusCode, authResult.body)) return last
        }
        return last
    }
    return last
}

internal suspend fun probePinnedDiagnosticEndpoint(
    url: String,
    bearerToken: String?,
    execute: suspend (DiagnosticProbeAttempt) -> DiagnosticAttemptResult = ::executeDiagnosticAttempt
): DiagnosticProbeResult {
    val attempt = DiagnosticProbeAttempt(url, bearerToken)
    return executeSafely(attempt, execute).toProbeResult(attempt, 1)
}

private suspend fun executeSafely(
    attempt: DiagnosticProbeAttempt,
    execute: suspend (DiagnosticProbeAttempt) -> DiagnosticAttemptResult
): DiagnosticAttemptResult {
    return try {
        execute(attempt)
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: Exception) {
        DiagnosticAttemptResult.transportFailure(ex.message ?: ex.javaClass.simpleName)
    }
}

private fun DiagnosticAttemptResult.toProbeResult(
    attempt: DiagnosticProbeAttempt,
    attempts: Int
) = DiagnosticProbeResult(
    statusCode = statusCode,
    body = body,
    error = error,
    effectiveUrl = attempt.url,
    bearerToken = attempt.bearerToken,
    attemptCount = attempts
)

private suspend fun executeDiagnosticAttempt(attempt: DiagnosticProbeAttempt): DiagnosticAttemptResult {
    val request = Request.Builder()
        .url(attempt.url)
        .header("Accept", "application/json")
        .header("User-Agent", "HermesHub-Android-Diagnostics")
        .apply { attempt.bearerToken?.let { header("Authorization", "Bearer $it") } }
        .get()
        .build()

    return suspendCancellableCoroutine { continuation ->
        val call = diagnosticHttpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resume(
                        DiagnosticAttemptResult.transportFailure(e.message ?: e.javaClass.simpleName)
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    runCatching {
                        DiagnosticAttemptResult.http(
                            statusCode = it.code,
                            body = it.body.byteStream().readUtf8Bounded(DIAGNOSTIC_RESPONSE_MAX_BYTES)
                        )
                    }.getOrElse { error ->
                        DiagnosticAttemptResult.http(
                            statusCode = it.code,
                            error = error.message ?: error.javaClass.simpleName
                        )
                    }
                }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }
}
