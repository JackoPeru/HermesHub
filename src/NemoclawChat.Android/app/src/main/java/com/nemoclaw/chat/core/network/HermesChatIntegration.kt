package com.nemoclaw.chat

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val SESSION_BINDINGS_PREFS = "hermes_session_bindings"
private const val CAPABILITIES_CACHE_MS = 60_000L

private var cachedCapabilities: HermesCapabilities? = null
private var cachedCapabilitiesAt: Long = 0L
private var cachedCapabilitiesKey: String = ""

/** Cache breve capabilities per decidere il percorso (Sessions vs legacy) senza probe a ogni turno. */
suspend fun loadHermesCapabilitiesCached(
    settings: AppSettings,
    apiKey: String?,
    forceRefresh: Boolean = false
): HermesCapabilities? = withContext(Dispatchers.IO) {
    val key = settings.gatewayUrl.trimEnd('/')
    val now = System.currentTimeMillis()
    if (!forceRefresh && cachedCapabilities != null && cachedCapabilitiesKey == key &&
        now - cachedCapabilitiesAt < CAPABILITIES_CACHE_MS
    ) return@withContext cachedCapabilities
    val body = runCatching {
        httpGet("${key.trimEnd('/')}/v1/capabilities", apiKey)
    }.getOrNull() ?: return@withContext cachedCapabilities?.takeIf { cachedCapabilitiesKey == key }
    val parsed = parseHermesCapabilities(body) ?: return@withContext null
    cachedCapabilities = parsed
    cachedCapabilitiesAt = now
    cachedCapabilitiesKey = key
    parsed
}

internal fun sessionBindingKey(localId: String, profile: String?): String {
    val p = profile?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "default"
    return "$p::${localId.trim()}"
}

internal fun loadSessionBinding(context: Context, localId: String, profile: String?): String? {
    val prefs = context.getSharedPreferences(SESSION_BINDINGS_PREFS, Context.MODE_PRIVATE)
    return prefs.getString(sessionBindingKey(localId, profile), null)?.takeIf { it.isNotBlank() }
}

internal fun saveSessionBinding(context: Context, localId: String, profile: String?, serverSessionId: String) {
    context.getSharedPreferences(SESSION_BINDINGS_PREFS, Context.MODE_PRIVATE).edit {
        putString(sessionBindingKey(localId, profile), serverSessionId.trim())
    }
}

internal fun clearSessionBinding(context: Context, localId: String, profile: String?) {
    context.getSharedPreferences(SESSION_BINDINGS_PREFS, Context.MODE_PRIVATE).edit {
        remove(sessionBindingKey(localId, profile))
    }
}

/**
 * Assicura una vera sessione Hermes per la chat locale (Percorso primario quando capability presente).
 * Ritorna serverSessionId oppure null se Sessions non disponibile (fallback legacy esplicito).
 */
suspend fun ensureHermesChatSession(
    context: Context,
    settings: AppSettings,
    apiKey: String?,
    localConversationId: String,
    profile: String? = null,
    multiplexEnabled: Boolean = false,
    capabilities: HermesCapabilities? = null,
    title: String? = null
): String? = withContext(Dispatchers.IO) {
    val caps = capabilities ?: loadHermesCapabilitiesCached(settings, apiKey)
    if (caps == null || !caps.supportsModernSessions()) return@withContext null
    val existing = loadSessionBinding(context, localConversationId, profile)
    val client = HermesSessionClient(settings, apiKey, profile, multiplexEnabled, caps)
    if (!existing.isNullOrBlank()) {
        // Valida che la sessione esista ancora; se 404, ricrea. Nessun fallback invisibile su 401.
        val (code, _) = client.get(existing)
        if (code in 200..299) return@withContext existing
        if (code == 401 || code == 403) throw SecurityException("Hermes ha rifiutato la chiave per il profilo (HTTP $code).")
        // 404 o errore trasporto: prova a ricreare sotto.
    }
    val (_, created) = client.create(title = title ?: "Hermes Hub Android", source = "hermes-hub-android")
    if (created == null) return@withContext null
    saveSessionBinding(context, localConversationId, profile, created.id)
    created.id
}

/** Cronologia autorevole dal server quando Sessions disponibile; null = usa cache locale. */
suspend fun loadHermesServerHistory(
    settings: AppSettings,
    apiKey: String?,
    serverSessionId: String,
    profile: String? = null,
    multiplexEnabled: Boolean = false,
    capabilities: HermesCapabilities? = null
): List<HermesSessionMessage>? = withContext(Dispatchers.IO) {
    val caps = capabilities ?: loadHermesCapabilitiesCached(settings, apiKey)
    if (caps == null || !caps.sessionMessages) return@withContext null
    val client = HermesSessionClient(settings, apiKey, profile, multiplexEnabled, caps)
    val (code, messages) = client.messages(serverSessionId)
    if (code !in 200..299) return@withContext null
    messages
}

internal fun sessionProfileUrl(settings: AppSettings, profile: String?, path: String, multiplexEnabled: Boolean): String {
    val root = hermesApiRoot(settings).trimEnd('/')
    val normalized = if (path.startsWith("/")) path else "/$path"
    if (profile.isNullOrBlank()) return "$root$normalized"
    check(multiplexEnabled) { "Il multiplexing dei profili Hermes non è pronto." }
    return "$root/p/${normalizeHermesProfileName(profile)}$normalized"
}

/**
 * Streaming primario Sessions API: POST /api/sessions/{id}/chat/stream (SSE).
 * Eventi: assistant.delta, tool.started/completed, run.completed/failed/cancelled, approval.*.
 */
fun streamHermesSessionChat(
    settings: AppSettings,
    serverSessionId: String,
    input: String,
    apiKey: String?,
    profile: String? = null,
    multiplexEnabled: Boolean = false,
    model: String? = null,
    provider: String? = null,
    modelOptions: JSONObject? = null,
    sessionKey: String? = null,
    allowCompatAuth: Boolean = true
): Flow<ChatStreamEvent> = flow {
    val payload = JSONObject().put("input", input)
    val effModel = model?.takeIf { it.isNotBlank() } ?: settings.model.takeIf { it.isNotBlank() }
    if (effModel != null) payload.put("model", effModel)
    val effProvider = provider?.takeIf { it.isNotBlank() }
        ?: settings.provider.takeIf { it.isNotBlank() && !it.equals("hermes-agent", ignoreCase = true) }
    if (effProvider != null) payload.put("provider", effProvider)
    val effOptions = modelOptions ?: buildHermesModelOptions(settings.reasoningEffort, settings.serviceTier, null)
    if (effOptions != null) payload.put("model_options", effOptions)
    val url = sessionProfileUrl(settings, profile, "/api/sessions/$serverSessionId/chat/stream", multiplexEnabled)
    val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
    val authCandidates = if (!profile.isNullOrBlank()) hermesProfileAuthCandidates(apiKey, profile)
    else hermesAuthCandidates(apiKey, allowCompatAuth)
    var accepted = false
    var terminal = false
    var lastError: String? = null
    val requestContext = HermesHubProtocol.newCorrelationContext()
    candidateLoop@ for (candidateUrl in plugAndPlayUrlCandidates(url)) {
        for ((index, token) in authCandidates.withIndex()) {
            if (!profile.isNullOrBlank() && token.isNullOrBlank()) continue // fail-closed profilo
            emit(ChatStreamEvent.Status("Sessione Hermes $serverSessionId: connessione stream..."))
            val builder = Request.Builder()
                .url(candidateUrl)
                .header("Accept", "text/event-stream, application/json")
                .header("User-Agent", "HermesHub-Android")
            HermesHubProtocol.addCorrelationHeaders(builder, requestContext)
            token?.let { builder.header("Authorization", "Bearer $it") }
            if (isValidHermesSessionKey(sessionKey)) builder.header("X-Hermes-Session-Key", sessionKey!!.trim())
            val request = builder.post(body).build()
            // Trasporto condiviso cancellabile (stesso di Chat Completions/Responses):
            // keepalive ignorato, terminale rilevato, stop utente abbatte la connessione.
            var httpFailure: SseAttemptSignal.HttpFailure? = null
            var networkFailure: String? = null
            var sawTerminal = false
            try {
                streamSseAttempt(request).collect { signal ->
                    when (signal) {
                        SseAttemptSignal.Accepted -> {
                            accepted = true
                            emit(ChatStreamEvent.Status("Sessione Hermes connessa."))
                        }
                        is SseAttemptSignal.Event -> emit(signal.event)
                        is SseAttemptSignal.HttpFailure -> httpFailure = signal
                        is SseAttemptSignal.NetworkFailure -> networkFailure = signal.message
                        is SseAttemptSignal.Finished -> sawTerminal = signal.terminal
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                networkFailure = ex.message ?: ex.javaClass.simpleName
            }
            if (accepted) {
                terminal = sawTerminal
                if (!terminal) {
                    emit(ChatStreamEvent.Error("Stream sessione chiuso senza evento terminale; risposta non confermata."))
                }
                return@flow
            }
            httpFailure?.let { failure ->
                val code = failure.code
                val hbody = failure.body
                if (index < authCandidates.lastIndex && shouldRetryHermesWithBearerAuth(code, hbody)) continue
                if (code == 401 || code == 403) {
                    emit(ChatStreamEvent.Error("Hermes ha rifiutato la chiave per questa sessione/profilo (HTTP $code)."))
                    return@flow
                }
                if (code == 404) {
                    emit(ChatStreamEvent.Error("Sessione Hermes non trovata (404): verrà ricreata al prossimo turno."))
                    return@flow
                }
                lastError = "Sessione Hermes HTTP $code: ${hbody.take(200)}"
                return@flow
            }
            lastError = networkFailure ?: "Sessione non raggiungibile"
        }
    }
    emit(ChatStreamEvent.Error(lastError ?: "Sessione Hermes non raggiungibile."))
}
