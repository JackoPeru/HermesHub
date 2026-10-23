package com.nemoclaw.chat

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal const val STREAM_ACCUM_MAX_CHARS = 2_000_000
internal const val STREAM_UI_BATCH_MAX_CHARS = 2048
internal const val STREAM_UI_BATCH_NS = 33_000_000L
internal const val RUN_POLL_TIMEOUT_MS = 60 * 60 * 1000L
internal const val RUN_POLL_MAX_CONSECUTIVE_FAILURES = 5
internal const val INLINE_ATTACHMENT_MAX_BYTES = 8L * 1024 * 1024
internal const val INLINE_ATTACHMENTS_TOTAL_MAX_BYTES = 12L * 1024 * 1024
internal const val SSE_LINE_MAX_BYTES = MAX_SSE_LINE_BYTES
internal val plugAndPlayStreamGatewayRoots = emptyList<String>()

internal val streamHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .apply { debugHttpLoggingInterceptor()?.let { addInterceptor(it) } }
    .build()

/** Timeout finiti del trasporto SSE (run lunghe fino a 60min). */
internal const val SSE_CONNECT_TIMEOUT_SEC = 15L
internal const val SSE_READ_TIMEOUT_SEC = 60L
internal const val SSE_WRITE_TIMEOUT_SEC = 30L
internal const val SSE_CALL_TIMEOUT_MIN = 60L
/** Watchdog inattivita: nessun byte/evento per 90s -> errore esplicito e chiusura. */
internal const val SSE_INACTIVITY_TIMEOUT_MS = 90_000L
internal const val SSE_INACTIVITY_CHECK_MS = 10_000L
internal const val SSE_INACTIVITY_ERROR_MESSAGE =
    "Stream Hermes interrotto: nessun dato per 90s (timeout inattivita). Risposta non confermata."

/**
 * Client dedicato allo streaming SSE con timeout finiti.
 * streamHttpClient resta invariato per le chiamate brevi che lo condividono
 * (upload allegati, poll run JSON, run events): non cambiarlo.
 */
internal val sseStreamHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(SSE_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
    .readTimeout(SSE_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
    .writeTimeout(SSE_WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
    .callTimeout(SSE_CALL_TIMEOUT_MIN, TimeUnit.MINUTES)
    .apply { debugHttpLoggingInterceptor()?.let { addInterceptor(it) } }
    .build()

internal fun isSseInactivityExpired(lastProgressNs: Long, nowNs: Long, timeoutMs: Long = SSE_INACTIVITY_TIMEOUT_MS): Boolean {
    if (timeoutMs <= 0L) return false
    return nowNs - lastProgressNs >= timeoutMs * 1_000_000L
}

/** Transport morto a turno vivo con response_id: il reattach via GET ha
 *  senso anche con contenuto parziale gia mostrato (il merge degli snapshot
 *  rimuove i duplicati: lo snapshot completo vince sul prefisso). */
internal fun shouldReattachStoredResponse(
    lastError: String?,
    responseId: String?,
    sawTerminal: Boolean
): Boolean = !sawTerminal && !responseId.isNullOrBlank() && isTransportFailureForReattach(lastError)

/**
 * Errori di puro trasporto (il server potrebbe aver continuato grazie al
 * detach): assenza rete, inattivita, chiusura senza terminale. Errori
 * HTTP/auth/applicativi -> mai reattach (ritentare e inutile o dannoso).
 */
internal fun isTransportFailureForReattach(lastError: String?): Boolean {
    if (lastError == null) return true
    if (isSseInactivityMessage(lastError)) return true
    val lower = lastError.lowercase()
    return lower.contains("non raggiungibile") ||
        lower.contains("connessione chiusa prima dell'evento terminale") ||
        lower.contains("stream parziale")
}

internal fun isSseInactivityMessage(message: String?): Boolean =
    message?.contains("nessun dato per 90s", ignoreCase = true) == true

/**
 * URL status di una response (resume dopo kill/rientro). Puro e testabile:
 * stessa normalizzazione /v1 del resto (profileUrl centrale).
 */
internal fun responseStatusUrl(
    settings: AppSettings,
    botProfile: String?,
    botMultiplexEnabled: Boolean,
    responseId: String
): String {
    val base = if (botProfile.isNullOrBlank()) resolveHermesUrl(settings, "/v1/responses")
    else resolveHermesProfileUrl(settings, botProfile, "/v1/responses", botMultiplexEnabled)
    return "$base/${java.net.URLEncoder.encode(responseId, "UTF-8")}"
}

/** Retention run lato hub (~1h): oltre non ha senso riprovare il resume. */
internal const val RESPONSE_RESUME_MAX_AGE_MS = 55L * 60L * 1000L

/** Binding troppo vecchio per il resume (server ha buttato la response). Puro. */
internal fun isResponseResumeExpired(startedAtMs: Long, nowMs: Long): Boolean =
    startedAtMs <= 0L || nowMs - startedAtMs > RESPONSE_RESUME_MAX_AGE_MS

