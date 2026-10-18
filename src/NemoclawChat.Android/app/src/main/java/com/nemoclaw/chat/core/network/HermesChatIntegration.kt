package com.nemoclaw.chat

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    val (code, body) = runCatching {
        httpGetResponse("${key.trimEnd('/')}/v1/capabilities", apiKey)
    }.getOrElse { return@withContext cachedCapabilities?.takeIf { cachedCapabilitiesKey == key } }
    // 401/403: chiave cambiata/rifiutata — MAI decidere su cache stale
    // (routing Sessions-vs-legacy su dati pre-rotazione).
    if (code == 401 || code == 403) {
        if (cachedCapabilitiesKey == key) {
            cachedCapabilities = null
        }
        return@withContext null
    }
    // Trasporto: tieni la cache come prima.
    if (code !in 200..299) return@withContext cachedCapabilities?.takeIf { cachedCapabilitiesKey == key }
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
    allowCompatAuth: Boolean = true,
    continueOnDisconnect: Boolean = true
): Flow<ChatStreamEvent> = flow {
    val payload = JSONObject().put("input", input)
    // Il turno deve sopravvivere alla morte del client (app killata): il gateway
    // continua il lavoro e il risultato resta leggibile via GET /v1/runs/{id}.
    if (continueOnDisconnect) payload.put("continue_on_disconnect", true)
    val effModel = model?.takeIf { it.isNotBlank() } ?: settings.model.takeIf { it.isNotBlank() }
    if (effModel != null) payload.put("model", effModel)
    val effProvider = provider?.takeIf { it.isNotBlank() }
        ?: settings.provider.takeIf { it.isNotBlank() && !it.equals("hermes-agent", ignoreCase = true) }
    if (effProvider != null) payload.put("provider", effProvider)
    val effOptions = modelOptions ?: buildHermesModelOptions(settings.reasoningEffort, settings.serviceTier, null)
    if (effOptions != null) payload.put("model_options", effOptions)
    val url = sessionProfileUrl(settings, profile, "/api/sessions/${java.net.URLEncoder.encode(serverSessionId, "UTF-8")}/chat/stream", multiplexEnabled)
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
            if (isValidHermesSessionKey(sessionKey)) builder.header("X-Hermes-Session-Key", sessionKey?.trim().orEmpty())
            val request = builder.post(body).build()
            // Trasporto condiviso cancellabile (stesso di Chat Completions/Responses):
            // keepalive ignorato, terminale rilevato, stop utente abbatte la connessione.
            var httpFailure: SseAttemptSignal.HttpFailure? = null
            var networkFailure: String? = null
            var sawTerminal = false
            // Run osservato nel flusso: indirizzo per il recupero se il transport
            // muore prima del terminale (stesso ruolo del response_id nel percorso
            // Chat Completions). Senza, resta la riconciliazione via history.
            var observedRunId: String? = null
            try {
                streamSseAttempt(request).collect { signal ->
                    when (signal) {
                        SseAttemptSignal.Accepted -> {
                            accepted = true
                            emit(ChatStreamEvent.Status("Sessione Hermes connessa."))
                        }
                        is SseAttemptSignal.Event -> {
                            val ev = signal.event
                            if (ev is ChatStreamEvent.RunId && ev.id.isNotBlank()) observedRunId = ev.id
                            emit(ev)
                        }
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
                    // Transport morto a turno vivo: il server continua per
                    // continue_on_disconnect. Prima di dichiarare il turno morto,
                    // recupera (poll run osservato, poi riconciliazione history) —
                    // stesso contratto del percorso Chat Completions (0.6.258).
                    if (continueOnDisconnect) {
                        emit(ChatStreamEvent.Status("Connessione persa a turno vivo: recupero la risposta dal server..."))
                        val recovered = recoverSessionTurnAfterDisconnect(
                            emit = { emit(it) },
                            settings = settings,
                            apiKey = apiKey,
                            serverSessionId = serverSessionId,
                            input = input,
                            profile = profile,
                            multiplexEnabled = multiplexEnabled,
                            observedRunId = observedRunId
                        )
                        if (recovered) return@flow
                    }
                    emit(ChatStreamEvent.Error("Stream sessione chiuso senza evento terminale; risposta non confermata."))
                }
                return@flow
            }
            httpFailure?.let { failure ->
                val code = failure.code
                val hbody = failure.body
                if (index < authCandidates.lastIndex && shouldRetryHermesWithBearerAuth(code, hbody, token)) continue
                if (code == 401 || code == 403) {
                    emit(ChatStreamEvent.Error("Hermes ha rifiutato la chiave per questa sessione/profilo (HTTP $code)."))
                    return@flow
                }
                if (code == 404) {
                    emit(ChatStreamEvent.Error("Sessione Hermes non trovata (404): verrà ricreata al prossimo turno."))
                    return@flow
                }
                lastError = "Sessione Hermes HTTP $code: ${hbody.take(200)}"
                emit(ChatStreamEvent.Error(lastError))
                return@flow
            }
            lastError = networkFailure ?: "Sessione non raggiungibile"
        }
    }
    emit(ChatStreamEvent.Error(lastError ?: "Sessione Hermes non raggiungibile."))
}

/** Poll run osservato: tentativi x intervallo prima della riconciliazione history. */
internal const val SESSION_RUN_RECOVERY_ATTEMPTS = 30
internal const val SESSION_RUN_RECOVERY_DELAY_MS = 10_000L
/** Riconciliazione history senza runId noto: breve, poi errore vero. */
internal const val SESSION_HISTORY_RECOVERY_ATTEMPTS = 6
internal const val SESSION_HISTORY_RECOVERY_DELAY_MS = 10_000L

private val SESSION_USER_ROLES = setOf("user", "tu", "human", "me")
private val SESSION_ASSISTANT_ROLES = setOf("assistant", "hermes", "bot", "ai")

/**
 * Risposta del turno nella history sessione: ultimo messaggio user che
 * corrisponde al nostro input + primo assistant non-blank dopo di lui.
 * null = turno non ancora persistito (server ancora al lavoro o mai partito).
 * Puro e testabile.
 */
internal fun findSessionTurnAnswer(
    messages: List<HermesSessionMessage>,
    userInput: String
): String? {
    val want = userInput.trim()
    if (want.isEmpty() || messages.isEmpty()) return null
    var anchor = -1
    for (i in messages.indices) {
        val m = messages[i]
        if (m.role.trim().lowercase() !in SESSION_USER_ROLES) continue
        if (m.content.trim() == want) anchor = i
    }
    if (anchor < 0) {
        // Input non trovato nella finestra (history lunga): ultimo user come
        // fallback, la chat Bot e single-user e il turno occupa la coda.
        for (i in messages.indices.reversed()) {
            if (messages[i].role.trim().lowercase() in SESSION_USER_ROLES) {
                anchor = i
                break
            }
        }
        if (anchor < 0) return null
    }
    for (i in anchor + 1 until messages.size) {
        val m = messages[i]
        if (m.role.trim().lowercase() !in SESSION_ASSISTANT_ROLES) continue
        if (m.content.isNotBlank()) return m.content
    }
    return null
}

/**
 * Recupero turno Sessions dopo morte del transport (accepted, non terminale).
 * 1) runId osservato -> poll GET /v1/runs/{id} fino a terminale;
 * 2) altrimenti -> riconciliazione via history sessione (il server continua
 *    per continue_on_disconnect e persiste la risposta).
 * true = turno chiuso con eventi di recupero (niente errore); false = recovery
 * fallita, il chiamante emette l'errore originale. Mai su stop utente: la
 * cancellazione della coroutine interrompe i poll (ensureActive + delay).
 */
private suspend fun recoverSessionTurnAfterDisconnect(
    emit: suspend (ChatStreamEvent) -> Unit,
    settings: AppSettings,
    apiKey: String?,
    serverSessionId: String,
    input: String,
    profile: String?,
    multiplexEnabled: Boolean,
    observedRunId: String?
): Boolean {
    val runId = observedRunId?.takeIf { it.isNotBlank() }
    if (runId != null) {
        val client = HermesRunClient(settings, apiKey, profile, multiplexEnabled)
        repeat(SESSION_RUN_RECOVERY_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            if (it > 0) delay(SESSION_RUN_RECOVERY_DELAY_MS)
            val (code, info) = runCatching { client.status(runId) }
                .getOrElse { return@repeat }
            // 401/403/404 permanenti: niente senso a insistere qui.
            if (code == 401 || code == 403 || code == 404) return false
            if (code !in 200..299 || info == null) return@repeat
            when (info.status) {
                HermesRunStatus.COMPLETED -> {
                    val output = info.output.orEmpty()
                    // Snapshot: il merge non duplica i delta gia mostrati.
                    if (output.isNotBlank()) emit(ChatStreamEvent.TextSnapshot(output))
                    emit(ChatStreamEvent.RunStatusChanged(runId, "completed", info.pendingSteer))
                    return true
                }
                HermesRunStatus.FAILED -> {
                    emit(ChatStreamEvent.RunStatusChanged(runId, "failed", info.pendingSteer))
                    emit(ChatStreamEvent.Error("Il turno recuperato risulta fallito sul server."))
                    return true
                }
                HermesRunStatus.CANCELLED -> {
                    emit(ChatStreamEvent.RunStatusChanged(runId, "cancelled", info.pendingSteer))
                    return true
                }
                HermesRunStatus.WAITING_FOR_APPROVAL -> {
                    // La UI approvazioni aggancia activeRunId dal vivo.
                    emit(ChatStreamEvent.RunStatusChanged(runId, "waiting_for_approval", info.pendingSteer))
                    return true
                }
                else -> return@repeat
            }
        }
    }
    // Senza history server-side non c'e nulla da riconciliare: errore subito.
    val caps = loadHermesCapabilitiesCached(settings, apiKey)
    if (caps != null && !caps.sessionMessages) return false
    repeat(SESSION_HISTORY_RECOVERY_ATTEMPTS) { attempt ->
        currentCoroutineContext().ensureActive()
        if (attempt > 0) delay(SESSION_HISTORY_RECOVERY_DELAY_MS)
        val history = loadHermesServerHistory(settings, apiKey, serverSessionId, profile, multiplexEnabled)
            ?: return@repeat
        val answer = findSessionTurnAnswer(history, input) ?: return@repeat
        if (answer.isBlank()) return@repeat
        emit(ChatStreamEvent.TextSnapshot(answer))
        emit(ChatStreamEvent.RunStatusChanged(runId.orEmpty(), "completed"))
        return true
    }
    return false
}
