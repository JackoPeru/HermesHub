package com.nemoclaw.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

internal sealed interface SseAttemptSignal {
    data object Accepted : SseAttemptSignal
    data class Event(val event: ChatStreamEvent) : SseAttemptSignal
    data class HttpFailure(val code: Int, val body: String) : SseAttemptSignal
    data class NetworkFailure(val message: String) : SseAttemptSignal
    data class Finished(val terminal: Boolean) : SseAttemptSignal
}

internal suspend fun openSseStream(
    url: String,
    payload: JSONObject,
    label: String,
    apiKey: String?,
    allowCompatAuth: Boolean,
    sessionId: String? = null,
    sessionKey: String? = null,
    botProfile: String? = null,
    requestContext: HermesRequestContext = HermesHubProtocol.newCorrelationContext(),
    onEvent: suspend (ChatStreamEvent) -> Unit
): SseOpenResult {
    val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
    // Isolamento profili: su /p/<profile>/ mai fallback null/compat (fail-closed, breaking change lug-2026).
    val authCandidates = if (!botProfile.isNullOrBlank()) hermesProfileAuthCandidates(apiKey, botProfile)
        else hermesAuthCandidates(apiKey, allowCompatAuth)
    var lastError: String? = null

    candidateLoop@ for (candidateUrl in plugAndPlayStreamUrlCandidates(url)) {
        for ((index, bearerToken) in authCandidates.withIndex()) {
            // Fail-closed profilo: mai fallback anonimo su /p/<profile>/.
            if (candidateUrl.contains("/p/", ignoreCase = true) && bearerToken.isNullOrBlank()) continue
            currentCoroutineContext().ensureActive()
            onEvent(ChatStreamEvent.Status("$label: connessione stream..."))
            val builder = Request.Builder()
                .url(candidateUrl)
                .header("Accept", "text/event-stream, application/json")
                .header("User-Agent", "HermesHub-Android")
            HermesHubProtocol.addCorrelationHeaders(builder, requestContext)
            bearerToken?.let { builder.header("Authorization", "Bearer $it") }
            sessionId?.takeIf { it.isNotBlank() }?.let { builder.header("X-Hermes-Session-Id", it) }
            sessionKey?.takeIf { isValidHermesSessionKey(it) }?.let { builder.header("X-Hermes-Session-Key", it.trim()) }
            val request = builder.post(body).build()
            var accepted = false
            var terminal = false
            var httpFailure: SseAttemptSignal.HttpFailure? = null
            var networkFailure: String? = null

            streamSseAttempt(request).collect { signal ->
                when (signal) {
                    SseAttemptSignal.Accepted -> {
                        accepted = true
                        onEvent(ChatStreamEvent.Status("$label connesso."))
                        onEvent(ChatStreamEvent.Status("Prompt inviato. Attendo primo token..."))
                    }
                    is SseAttemptSignal.Event -> onEvent(signal.event)
                    is SseAttemptSignal.HttpFailure -> httpFailure = signal
                    is SseAttemptSignal.NetworkFailure -> networkFailure = signal.message
                    is SseAttemptSignal.Finished -> terminal = signal.terminal
                }
            }

            if (accepted) {
                if (terminal) return SseOpenResult(true, true, null)
                val detail = networkFailure ?: "connessione chiusa prima dell'evento terminale"
                // Watchdog inattivita: messaggio esplicito SENZA la parola "parziale",
                // cosi' streamChatRequest emette Error esplicito invece di sopprimerlo.
                return if (isSseInactivityMessage(detail)) SseOpenResult(true, false, "$label: $detail")
                else SseOpenResult(true, false, "$label: stream parziale, $detail")
            }

            httpFailure?.let { failure ->
                val canRetryAuth = shouldRetrySseAuth(
                    accepted = false,
                    code = failure.code,
                    body = failure.body,
                    hasMoreAuthCandidates = index < authCandidates.lastIndex,
                    token = bearerToken,
                    tokenProvided = true
                )
                if (canRetryAuth) {
                    onEvent(ChatStreamEvent.Status("API key Hermes non accettata. Riprovo automaticamente..."))
                    return@let
                }
                val message = if (failure.code == 401) {
                    "$label: API key rifiutata."
                } else {
                    "$label HTTP ${failure.code}: ${failure.body.take(240)}"
                }
                return SseOpenResult(false, false, message)
            }
            if (httpFailure != null) continue

            lastError = networkFailure ?: "$candidateUrl non raggiungibile"
            continue@candidateLoop
        }
    }
    return SseOpenResult(false, false, lastError ?: "$label: Hermes Gateway non raggiungibile.")
}

internal fun streamSseAttempt(request: Request): Flow<SseAttemptSignal> = callbackFlow {
    // Trasporto dedicato con timeout finiti (read 60s, call 30min): niente blocco infinito.
    val call = sseStreamHttpClient.newCall(request)
    val lastProgressNs = AtomicLong(System.nanoTime())
    fun markProgress() {
        lastProgressNs.set(System.nanoTime())
    }
    fun sendSignal(signal: SseAttemptSignal): Boolean {
        markProgress()
        return trySendBlocking(signal).isSuccess
    }
    // Watchdog inattivita: nessun byte/evento per 90s -> errore esplicito e chiusura.
    // Nessun meccanismo simile esisteva: readTimeout copre solo stalli senza byte,
    // non keepalive senza eventi. Il segnale parte PRIMA della cancel (onFailure
    // post-cancel viene ignorato per via di isCanceled).
    val watchdog = launch {
        try {
            while (true) {
                delay(SSE_INACTIVITY_CHECK_MS)
                if (isSseInactivityExpired(lastProgressNs.get(), System.nanoTime())) {
                    trySendBlocking(SseAttemptSignal.NetworkFailure(SSE_INACTIVITY_ERROR_MESSAGE))
                    call.cancel()
                    close()
                    break
                }
            }
        } catch (_: CancellationException) {
            // Chiusura normale o cancel utente: niente da segnalare.
        }
    }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!call.isCanceled()) sendSignal(SseAttemptSignal.NetworkFailure(e.message ?: e.javaClass.simpleName))
            close()
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                response.use { current ->
                    val responseBody = current.body
                    if (!current.isSuccessful) {
                        sendSignal(SseAttemptSignal.HttpFailure(current.code, responseBody.byteStream().readUtf8Bounded().take(240)))
                        return@use
                    }
                    if (!sendSignal(SseAttemptSignal.Accepted)) return@use
                    val contentType = responseBody.contentType()?.toString().orEmpty()
                    if (!contentType.contains("event-stream", ignoreCase = true)) {
                        parseFullBody(responseBody.byteStream().readUtf8Bounded()).forEach { if (!sendSignal(SseAttemptSignal.Event(it))) return@use }
                        sendSignal(SseAttemptSignal.Finished(terminal = true))
                        return@use
                    }

                    val source = responseBody.source()
                    val dataBuffer = StringBuilder()
                    var eventName: String? = null
                    var terminalSeen = false
                    // Oltre il limite l'evento si tronca (una sola segnalazione):
                    // il turno completa con il testo parziale invece di buttare
                    // tutto con NetworkFailure generico.
                    var accumTruncated = false

                    fun flushEvent(): Boolean {
                        if (dataBuffer.isEmpty()) return true
                        val data = dataBuffer.toString()
                        terminalSeen = terminalSeen || isTerminalSseEvent(eventName, data)
                        val parsed = parseSseData(eventName, data)
                        dataBuffer.clear()
                        eventName = null
                        return parsed.all { sendSignal(SseAttemptSignal.Event(it)) }
                    }

                    while (!call.isCanceled()) {
                        val line = source.readUtf8LineBounded(MAX_SSE_LINE_BYTES) ?: break
                        markProgress()
                        if (line.isEmpty()) {
                            if (!flushEvent()) return@use
                            continue
                        }
                        when {
                            line.startsWith(":") -> Unit
                            line.startsWith("event:", ignoreCase = true) -> eventName = line.substring(6).trim()
                            line.startsWith("data:", ignoreCase = true) -> {
                                val part = line.substring(5).let { if (it.startsWith(" ")) it.drop(1) else it }
                                val separatorLength = if (dataBuffer.isNotEmpty()) 1 else 0
                                if (dataBuffer.length + separatorLength + part.length > STREAM_ACCUM_MAX_CHARS) {
                                    if (!accumTruncated) {
                                        accumTruncated = true
                                        sendSignal(SseAttemptSignal.Event(ChatStreamEvent.Status("Risposta troncata: oltre 2M caratteri in un evento SSE.")))
                                    }
                                } else {
                                    if (separatorLength > 0) dataBuffer.append('\n')
                                    dataBuffer.append(part)
                                }
                            }
                        }
                    }
                    if (!call.isCanceled() && !flushEvent()) return@use
                    if (!call.isCanceled()) sendSignal(SseAttemptSignal.Finished(terminalSeen))
                }
            } catch (ex: Exception) {
                if (!call.isCanceled()) sendSignal(SseAttemptSignal.NetworkFailure(ex.message ?: ex.javaClass.simpleName))
            } finally {
                close()
            }
        }
    })
    awaitClose {
        watchdog.cancel()
        call.cancel()
    }
}

internal fun shouldRetrySseAuth(
    accepted: Boolean,
    code: Int,
    body: String,
    hasMoreAuthCandidates: Boolean,
    token: String? = null,
    @Suppress("UNUSED_PARAMETER") tokenProvided: Boolean = false
): Boolean {
    if (accepted || !hasMoreAuthCandidates) return false
    // Il ciclo candidati e' la fonte di verita': un 401 consent solo
    // l'avanzamento verso i candidati rimasti (quando una chiave e' salvata
    // hermesAuthCandidates non aggiunge mai il fallback anonimo); niente
    // retry su 401 con chiave espressamente rifiutata.
    if (code != 401) return false
    val lower = body.lowercase()
    return !(lower.contains("invalid_api_key") ||
        lower.contains("invalid api key") ||
        lower.contains("invalidapikey"))
}

internal fun isTerminalSseEvent(eventName: String?, data: String): Boolean {
    if (data.trim() == "[DONE]") return true
    val eventType = eventName.orEmpty().lowercase()
    if (eventType.contains("response.completed") || eventType.contains("response.done")) return true
    if (eventType.contains("run.completed") || eventType.contains("run.failed") || eventType.contains("run.cancelled")) return true
    if (eventType.contains("assistant.completed")) return true
    val root = runCatching { JSONObject(data) }.getOrNull() ?: return isTerminalSseEventLenient(data)
    val type = root.optString("type", "").lowercase()
    if (type.contains("response.completed") || type.contains("response.done")) return true
    if (type.contains("run.completed") || type.contains("run.failed") || type.contains("run.cancelled")) return true
    if (type.contains("assistant.completed")) return true
    // Payload annidato run/status per stream sessioni/run (v2026.9.x).
    val nestedStatus = root.optJSONObject("run")?.optString("status", "")
        ?: root.optJSONObject("status")?.optString("status", "")
    if (nestedStatus is String && (nestedStatus.equals("completed", true) || nestedStatus.equals("failed", true) || nestedStatus.equals("cancelled", true))) {
        // Solo se l'evento e' esplicitamente terminale, non per status intermedi.
        if (type.contains("completed") || type.contains("failed") || type.contains("cancelled")) return true
    }
    val choices = root.optJSONArray("choices") ?: return false
    return (0 until choices.length()).any { index ->
        choices.optJSONObject(index)?.optString("finish_reason").orEmpty().isNotBlank()
    }
}

internal val TERMINAL_FINISH_REASON_REGEX = Regex(""""finish_reason"\s*:\s*"((?:[^"\\]|\\.)+)"?""")
internal val TERMINAL_TYPE_MARKER_REGEX = Regex(""""type"\s*:\s*"[^"]*(completed|failed|cancelled)[^"]*"""", RegexOption.IGNORE_CASE)

/**
 * Fallback senza JSON parse: frame troncato ma con marcatori terminali
 * leggibili (es. finish_reason valorizzato). Solo segnali espliciti, mai
 * su spazzatura generica (che resta al watchdog).
 */
internal fun isTerminalSseEventLenient(data: String): Boolean {
    if (data.length > 16_384) return false
    val finish = TERMINAL_FINISH_REASON_REGEX.find(data)?.groupValues?.getOrNull(1).orEmpty()
    if (finish.isNotBlank() && !finish.equals("null", ignoreCase = true)) return true
    return TERMINAL_TYPE_MARKER_REGEX.containsMatchIn(data)
}


internal fun plugAndPlayStreamUrlCandidates(url: String): List<String> {
    return try {
        val uri = URI(url)
        if (uri.port != 8642) return listOf(url)
        val suffix = buildString {
            append(uri.rawPath.orEmpty())
            if (!uri.rawQuery.isNullOrBlank()) append("?").append(uri.rawQuery)
        }
        val currentRoot = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}".trimEnd('/')
        (listOf(currentRoot) + plugAndPlayStreamGatewayRoots)
            .distinctBy { it.lowercase() }
            .map { it.trimEnd('/') + suffix }
    } catch (_: Exception) {
        listOf(url)
    }
}

