package com.nemoclaw.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs API completa Hermes Agent (rif. v2026.9.14):
 * POST /v1/runs (202 + Idempotency-Key), GET /v1/runs/{id},
 * GET /v1/runs/{id}/events (SSE primario), POST .../stop, .../steer, .../approval.
 * Polling solo come fallback quando SSE non disponibile. Eventi sconosciuti tollerati.
 */
enum class HermesRunStatus(val wire: String) {
    QUEUED("queued"),
    RUNNING("running"),
    WAITING_FOR_APPROVAL("waiting_for_approval"),
    STOPPING("stopping"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): HermesRunStatus =
            values().firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: UNKNOWN
    }

    fun isTerminal(): Boolean = this == COMPLETED || this == FAILED || this == CANCELLED
}

data class HermesRunInfo(
    val runId: String,
    val status: HermesRunStatus,
    val sessionId: String? = null,
    val output: String? = null,
    val error: String? = null,
    val pendingSteer: String? = null,
    val usage: JSONObject? = null,
    val raw: String = ""
)

data class HermesRunApprovalRequest(
    val approvalId: String = "",
    val requestId: String = "",
    val tool: String = "",
    val command: String = "",
    val description: String = "",
    /** Scelte offerte dal server per questa richiesta (mai inventate, mai estese). */
    val choices: List<String> = emptyList(),
    val raw: JSONObject? = null
)

/** Scelte canoniche approval Hermes (api_server_runs: allowed set + alias). */
internal val HERMES_APPROVAL_CHOICES = listOf("once", "session", "always", "deny")
private val HERMES_APPROVAL_ALIASES = mapOf("approve" to "once", "approved" to "once", "allow" to "once")

internal fun normalizeHermesApprovalChoice(raw: String): String? {
    val clean = raw.trim().lowercase()
    if (clean.isEmpty()) return null
    if (clean in HERMES_APPROVAL_CHOICES) return clean
    return HERMES_APPROVAL_ALIASES[clean]
}

/**
 * Scelta di auto-approvazione data modalita' utente e scelte offerte dal server.
 * No-downgrade: MAI ripiegare su un livello inferiore a quello configurato.
 * Se il server offre solo [once, deny] e mode=always/session, NON approvare
 * (ritorna null, il chiamante mostra la richiesta all'utente). Mai "deny" automatico.
 * Ritorna null se (e solo se) nessuna offerta corrisponde al livello configurato.
 */
internal fun pickAutoApprovalChoice(offered: List<String>, mode: String): String? {
    val clean = offered.map { it.trim().lowercase() }.filter { it in HERMES_APPROVAL_CHOICES }
    if (clean.isEmpty()) return null
    val normalizedMode = mode.trim().lowercase()
    if (normalizedMode != "always" && normalizedMode != "session") return null
    // Livello richiesto esatto: always->always, session->session. Niente fallback a once.
    val required = normalizedMode
    if (required in clean) return required
    runCatching {
        android.util.Log.w(
            "HermesRuns",
            "no-downgrade: mode=$normalizedMode offered=$clean -> nessuna auto-approvazione"
        )
    }
    return null
}

/**
 * Regola server per le choices offerte (_approval_event_choices su main):
 * smart_denied o sessione non consentita -> [once, deny];
 * permanent non consentito -> [once, session, deny]; altrimenti set completo.
 */
internal fun hermesApprovalFallbackChoices(
    smartDenied: Boolean,
    allowSession: Boolean,
    allowPermanent: Boolean
): List<String> = if (smartDenied || !allowSession) {
    listOf("once", "deny")
} else if (allowPermanent) {
    listOf("once", "session", "always", "deny")
} else {
    listOf("once", "session", "deny")
}

internal fun parseHermesRunInfo(body: String): HermesRunInfo? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val runId = root.optString("run_id", root.optString("runId", root.optString("id", ""))).trim()
    if (runId.isEmpty()) return null
    return HermesRunInfo(
        runId = runId,
        status = HermesRunStatus.fromWire(root.optString("status", "")),
        sessionId = root.optString("session_id", "").takeIf { it.isNotBlank() },
        output = root.optString("output", "").takeIf { it.isNotEmpty() },
        error = root.optString("error", "").takeIf { it.isNotEmpty() },
        pendingSteer = root.optString("pending_steer", "").takeIf { it.isNotEmpty() },
        usage = root.optJSONObject("usage"),
        raw = body
    )
}

internal fun parseHermesApprovalRequest(eventJson: JSONObject): HermesRunApprovalRequest {
    val payload = eventJson.optJSONObject("payload") ?: eventJson
    val offered = mutableListOf<String>()
    payload.optJSONArray("choices")?.let { arr ->
        for (i in 0 until arr.length()) {
            val raw = arr.optString(i, "").trim().lowercase()
            // Scelte sconosciute ignorate: mai mostrare opzioni che il server rifiuterebbe.
            if (raw in HERMES_APPROVAL_CHOICES && raw !in offered) offered += raw
        }
    }
    val choices = if (offered.isNotEmpty()) {
        offered
    } else {
        // Nessuna choices esplicita: stessa regola del server dai flag presenti.
        hermesApprovalFallbackChoices(
            smartDenied = payload.optBoolean("smart_denied", false),
            allowSession = payload.optBoolean("allow_session", true),
            allowPermanent = payload.optBoolean("allow_permanent", true)
        )
    }
    // Lettura separata senza shadowing: prima non-blank tra approval_id/request_id/id.
    // optString con fallback annidato nasconde gli altri id quando il primo esiste ma è blank.
    val approvalIdRaw = payload.optString("approval_id").trim()
    val requestIdRaw = payload.optString("request_id").trim()
    val idRaw = payload.optString("id").trim()
    val firstId = listOf(approvalIdRaw, requestIdRaw, idRaw).firstOrNull { it.isNotBlank() }.orEmpty()
    val approvalId = approvalIdRaw.ifBlank { firstId }
    val requestId = requestIdRaw.ifBlank { firstId }
    return HermesRunApprovalRequest(
        approvalId = approvalId,
        requestId = requestId,
        tool = payload.optString("tool", ""),
        command = payload.optString("command", ""),
        description = payload.optString("description", payload.optString("prompt", "")),
        choices = choices,
        raw = eventJson
    )
}

class HermesRunClient(
    private val settings: AppSettings,
    private val apiKey: String?,
    private val profile: String? = null,
    private val multiplexEnabled: Boolean = false,
    private val capabilities: HermesCapabilities? = null
) {
    private fun runUrl(path: String): String {
        val root = hermesApiRoot(settings).trimEnd('/')
        val normalized = if (path.startsWith("/")) path else "/$path"
        if (!profile.isNullOrBlank()) {
            check(multiplexEnabled) { "Il multiplexing dei profili Hermes non è pronto." }
            val name = normalizeHermesProfileName(profile)
            // Routing profilo: /p/<profile>/v1/... (fail-closed su default key riusata).
            return "$root/p/$name$normalized"
        }
        return "$root$normalized"
    }

    private fun requireCapability(ok: Boolean, name: String) {
        if (capabilities != null && !ok) throw UnsupportedOperationException("Funzione non supportata dal server: $name")
    }

    suspend fun create(
        input: String,
        sessionId: String? = null,
        sessionKey: String? = null,
        model: String? = null,
        provider: String? = null,
        modelOptions: JSONObject? = null,
        instructions: String? = null,
        idempotencyKey: String? = null
    ): Pair<Int, HermesRunInfo?> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.runSubmission ?: true, "run_submission")
        val payload = JSONObject().put("input", input)
        if (!sessionId.isNullOrBlank()) payload.put("session_id", sessionId)
        if (!model.isNullOrBlank()) payload.put("model", model)
        if (!provider.isNullOrBlank()) payload.put("provider", provider)
        if (modelOptions != null) payload.put("model_options", modelOptions)
        if (!instructions.isNullOrBlank()) payload.put("instructions", instructions)
        val key = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: "hub-${UUID.randomUUID()}"
        var last: Pair<Int, String> = 0 to "Rete non disponibile"
        for (candidateUrl in plugAndPlayUrlCandidates(runUrl("/v1/runs"))) {
            for (token in hermesAuthCandidates(apiKey)) {
                val res = runCatching {
                    val builder = okhttp3.Request.Builder()
                        .url(candidateUrl)
                        .header("Accept", "application/json")
                        .header("User-Agent", "HermesHub-Android")
                        .header("Idempotency-Key", key)
                    HermesHubProtocol.addCorrelationHeaders(builder, HermesHubProtocol.newCorrelationContext())
                    token?.let { builder.header("Authorization", "Bearer $it") }
                    if (!sessionId.isNullOrBlank()) builder.header("X-Hermes-Session-Id", sessionId)
                    if (!sessionKey.isNullOrBlank()) builder.header("X-Hermes-Session-Key", sessionKey.trim())
                    val request = builder.post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                    apiHttpClient.newCall(request).execute().use { resp ->
                        val replayed = resp.header("Idempotency-Replayed") == "true"
                        val code = resp.code
                        val body = resp.body.byteStream().readUtf8Bounded()
                        // 202 = run creato/riprodotto; 409 = conflitto idempotency (payload diverso).
                        if (code == 409) code to body else code to body
                    }
                }.getOrElse {
                    if (it is CancellationException) throw it
                    0 to (it.message ?: it.javaClass.simpleName)
                }
                last = res
                if (!shouldRetryHermesWithBearerAuth(res.first, res.second)) {
                    if (res.first != 0) {
                        if (res.first !in 200..299 && res.first != 202) return@withContext res.first to null
                        return@withContext res.first to parseHermesRunInfo(res.second)
                    }
                }
            }
        }
        last.first to null
    }

    suspend fun status(runId: String): Pair<Int, HermesRunInfo?> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.runStatus ?: true, "run_status")
        val res = httpGetResponse(runUrl("/v1/runs/$runId"), apiKey)
        if (res.first !in 200..299) return@withContext res.first to null
        res.first to parseHermesRunInfo(res.second)
    }

    suspend fun stop(runId: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.runStop ?: true, "run_stop")
        postJson(runUrl("/v1/runs/$runId/stop"), JSONObject(), apiKey)
    }

    /**
     * STEER: inietta guida nel run attivo. Accettato solo se running (409 altrimenti).
     * 200/run.steered = accodato, non consegnato. pending_steer va riproposto come turno successivo.
     */
    suspend fun steer(runId: String, text: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.runSteer ?: true, "run_steer")
        val clean = text.trim()
        require(clean.isNotEmpty()) { "Testo steer obbligatorio." }
        postJson(runUrl("/v1/runs/$runId/steer"), JSONObject().put("text", clean), apiKey)
    }

    /**
     * APPROVAL: risolve approvazione pendente. Solo scelte canoniche Hermes
     * (once/session/always/deny, con alias approve/approved/allow -> once);
     * request_id obbligatorio per grant room-scoped. Singolo invio, niente retry.
     */
    suspend fun approval(runId: String, choice: String, requestId: String? = null): Pair<Int, String> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.runApproval ?: true, "run_approval")
        val normalized = normalizeHermesApprovalChoice(choice)
            ?: throw IllegalArgumentException("Scelta approval non valida.")
        val payload = JSONObject().put("choice", normalized)
        if (!requestId.isNullOrBlank()) payload.put("request_id", requestId.trim())
        postJson(runUrl("/v1/runs/$runId/approval"), payload, apiKey)
    }

    /**
     * Eventi SSE nativi come fonte primaria. Il chiamante usa parseSseData per ogni evento.
     * Ritorna Flow di coppie (eventName, data). Keepalive ": keepalive" gia' filtrato dal trasporto.
     */
    fun events(runId: String): Flow<Pair<String?, String>> = flow {
        requireCapability(capabilities?.runEventsSse ?: true, "run_events_sse")
        val url = runUrl("/v1/runs/$runId/events")
        // Riutilizza il trasporto SSE di ChatStream tramite callback pubblica.
        collectRunSseEvents(url, apiKey) { name, data -> emit(name to data) }
    }
}

/** Trasporto SSE condiviso per run events (keepalive ignorato, eventi sconosciuti preservati). */
/** Timeout finiti run-SSE: stessi valori dello streaming chat (connect 15s, read 60s, call 30min). */
internal const val RUN_SSE_CONNECT_TIMEOUT_SEC = 15L
internal const val RUN_SSE_READ_TIMEOUT_SEC = 60L
internal const val RUN_SSE_WRITE_TIMEOUT_SEC = 30L
internal const val RUN_SSE_CALL_TIMEOUT_MIN = 30L
/** Watchdog inattivita run-SSE: nessun byte/evento per 90s -> errore esplicito e chiusura. */
internal const val RUN_SSE_INACTIVITY_TIMEOUT_MS = 90_000L
internal const val RUN_SSE_INACTIVITY_CHECK_MS = 10_000L
internal const val RUN_SSE_INACTIVITY_ERROR_MESSAGE =
    "Run Hermes interrotto: nessun dato per 90s (timeout inattivita). Risposta non confermata."

/** Client dedicato run-SSE con timeout finiti (niente blocco infinito). */
internal val runSseHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(RUN_SSE_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
    .readTimeout(RUN_SSE_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
    .writeTimeout(RUN_SSE_WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
    .callTimeout(RUN_SSE_CALL_TIMEOUT_MIN, TimeUnit.MINUTES)
    .build()

internal fun isRunSseInactivityExpired(
    lastProgressNs: Long,
    nowNs: Long,
    timeoutMs: Long = RUN_SSE_INACTIVITY_TIMEOUT_MS
): Boolean {
    if (timeoutMs <= 0L) return false
    return nowNs - lastProgressNs >= timeoutMs * 1_000_000L
}

internal fun isRunSseInactivityMessage(message: String?): Boolean =
    message?.contains("nessun dato per 90s", ignoreCase = true) == true

internal suspend fun collectRunSseEvents(
    url: String,
    apiKey: String?,
    onEvent: suspend (String?, String) -> Unit
) {
    for (candidateUrl in plugAndPlayUrlCandidates(url)) {
        for (token in hermesAuthCandidates(apiKey)) {
            // Stesso pattern cancellabile di streamSseAttempt: stop abbatte la connessione.
            val builder = okhttp3.Request.Builder()
                .url(candidateUrl)
                .header("Accept", "text/event-stream")
                .header("User-Agent", "HermesHub-Android")
            HermesHubProtocol.addCorrelationHeaders(builder, HermesHubProtocol.newCorrelationContext())
            token?.let { builder.header("Authorization", "Bearer $it") }
            val request = builder.get().build()
            val delivered = kotlinx.coroutines.flow.callbackFlow {
                // Trasporto dedicato con timeout finiti (connect 15s, read 60s, call 30min).
                val call = runSseHttpClient.newCall(request)
                val lastProgressNs = AtomicLong(System.nanoTime())
                fun markProgress() {
                    lastProgressNs.set(System.nanoTime())
                }
                fun send(pair: Pair<String?, String>): Boolean = try {
                    markProgress()
                    trySendBlocking(pair).isSuccess
                } catch (_: Exception) {
                    false
                }
                // Watchdog inattivita 90s: nessun byte/evento -> errore esplicito e chiusura.
                // Stesso pattern di streamSseAttempt: il segnale parte PRIMA della cancel.
                val watchdog = launch {
                    try {
                        while (true) {
                            delay(RUN_SSE_INACTIVITY_CHECK_MS)
                            if (isRunSseInactivityExpired(lastProgressNs.get(), System.nanoTime())) {
                                trySendBlocking("error" to RUN_SSE_INACTIVITY_ERROR_MESSAGE)
                                call.cancel()
                                close()
                                break
                            }
                        }
                    } catch (_: CancellationException) {
                        // Chiusura normale o cancel utente: niente da segnalare.
                    }
                }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        close()
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        try {
                            response.use { current ->
                                if (!current.isSuccessful) {
                                    close()
                                    return@use
                                }
                                if (!trySendBlocking("connected" to "").isSuccess) return@use
                                markProgress()
                                val source = current.body.source()
                                val dataBuffer = StringBuilder()
                                var eventName: String? = null
                                fun flush(): Boolean {
                                    if (dataBuffer.isEmpty()) return true
                                    val data = dataBuffer.toString()
                                    dataBuffer.clear()
                                    val name = eventName
                                    eventName = null
                                    return send(name to data)
                                }
                                while (!call.isCanceled()) {
                                    val line = try {
                                        source.readUtf8LineStrict(256L * 1024L)
                                    } catch (_: Exception) {
                                        break
                                    }
                                    // Ogni byte ricevuto resetta il watchdog (keepalive incluso).
                                    markProgress()
                                    if (line.isEmpty()) {
                                        if (!flush()) return@use
                                        continue
                                    }
                                    if (line.startsWith(":")) continue // keepalive Hermes ": keepalive"
                                    when {
                                        line.startsWith("event:", ignoreCase = true) ->
                                            eventName = line.substring(6).trim()
                                        line.startsWith("data:", ignoreCase = true) -> {
                                            val part = line.substring(5).let {
                                                if (it.startsWith(" ")) it.drop(1) else it
                                            }
                                            if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                                            dataBuffer.append(part)
                                        }
                                    }
                                }
                                if (!call.isCanceled()) flush()
                            }
                        } catch (_: Exception) {
                            // Chiusura trasporto: il consumer vede solo eventi completi.
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
            var accepted = false
            var failed = false
            try {
                delivered.collect { (name, data) ->
                    if (name == "connected") {
                        accepted = true
                        return@collect
                    }
                    accepted = true
                    onEvent(name, data)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                failed = true
            }
            if (accepted && !failed) return
        }
    }
}
