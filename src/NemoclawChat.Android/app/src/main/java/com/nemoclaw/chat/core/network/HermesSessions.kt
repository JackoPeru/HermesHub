package com.nemoclaw.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sessions API nativa Hermes Agent (rif. v2026.9.14):
 * GET /api/sessions, POST /api/sessions, GET/PATCH/DELETE /api/sessions/{id},
 * GET /api/sessions/{id}/messages, POST /api/sessions/{id}/fork,
 * POST /api/sessions/{id}/chat, POST /api/sessions/{id}/chat/stream.
 * Sorgente autorevole server-side quando la capability esiste; cache locale solo per offline/rendering.
 */
data class HermesSession(
    val id: String,
    val title: String = "",
    val source: String = "",
    val parentId: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val raw: JSONObject? = null
)

data class HermesSessionMessage(
    val role: String,
    val content: String,
    val createdAt: String = "",
    val raw: JSONObject? = null
)

/**
 * Transcript server -> chat, piegato come la chat normale: NIENTE bubble
 * per i tool (policy: payload tool mai retained come testo, solo chip con
 * nome/stato), reasoning nel canvas "ragionamento" via timeline, tool e
 *Compattazioni scartati. Puro e testabile.
 */
internal fun foldTranscriptToChat(rows: List<HermesSessionMessage>): List<ChatMessage> {
    var currentText: String? = null
    var currentTimeline = mutableListOf<AssistantActivity>()
    val out = mutableListOf<ChatMessage>()
    fun flush() {
        if (currentText != null || currentTimeline.isNotEmpty()) {
            out.add(
                ChatMessage(
                    "Hermes",
                    currentText.orEmpty(),
                    fromUser = false,
                    activityTimeline = currentTimeline.toList()
                )
            )
        }
        currentText = null
        currentTimeline = mutableListOf()
    }
    fun toolCallEntries(row: HermesSessionMessage): List<Pair<String, String>> {
        val calls = row.raw?.optJSONArray("tool_calls") ?: return emptyList()
        return (0 until calls.length()).mapNotNull { i ->
            val obj = calls.optJSONObject(i) ?: return@mapNotNull null
            val name = obj.optString("name").takeIf { it.isNotBlank() }
                ?: obj.optJSONObject("function")?.optString("name")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val callId = obj.optString("tool_call_id").takeIf { it.isNotBlank() }
                ?: obj.optString("call_id").takeIf { it.isNotBlank() }
                ?: obj.optString("id").orEmpty()
            callId to name
        }
    }
    fun reasoningOf(row: HermesSessionMessage): String {
        return row.raw?.optString("reasoning_content").orEmpty()
            .ifBlank { row.raw?.optString("reasoning").orEmpty() }
    }
    fun markToolFailed(callId: String) {
        if (callId.isBlank()) return
        val index = currentTimeline.indexOfFirst {
            it.kind == AssistantActivity.Kind.Tool && it.tool?.id == callId
        }
        if (index >= 0) {
            val activity = currentTimeline[index]
            currentTimeline[index] = activity.copy(
                tool = activity.tool?.copy(status = "fallito")
            )
        }
    }
    rows.forEach { row ->
        val failed = row.raw?.optString("display_kind").orEmpty() == "failed_turn"
        when (row.role.lowercase()) {
            "user", "tu" -> {
                flush()
                if (row.content.isNotBlank()) out.add(ChatMessage("Tu", row.content, fromUser = true))
            }
            "assistant" -> {
                val reasoning = reasoningOf(row)
                val calls = toolCallEntries(row)
                if (row.content.isNotBlank()) {
                    flush()
                    currentText = (if (failed) "(fallito) " else "") + row.content
                    if (reasoning.isNotBlank()) {
                        currentTimeline.add(AssistantActivity(AssistantActivity.Kind.Reasoning, text = reasoning))
                    }
                    calls.forEach { (callId, name) ->
                        currentTimeline.add(
                            AssistantActivity(
                                AssistantActivity.Kind.Tool,
                                text = name,
                                tool = ToolCallState(id = callId, name = name, status = "completato")
                            )
                        )
                    }
                } else {
                    // Riga senza testo: contribuisce solo se ha reasoning o
                    // tool (altrimenti scartata, niente bubble vuote).
                    val hasContent = reasoning.isNotBlank() || calls.isNotEmpty()
                    if (hasContent) {
                        if (currentText == null && currentTimeline.isEmpty()) currentText = ""
                        if (reasoning.isNotBlank()) {
                            currentTimeline.add(AssistantActivity(AssistantActivity.Kind.Reasoning, text = reasoning))
                        }
                        calls.forEach { (callId, name) ->
                            currentTimeline.add(
                                AssistantActivity(
                                    AssistantActivity.Kind.Tool,
                                    text = name,
                                    tool = ToolCallState(id = callId, name = name, status = "completato")
                                )
                            )
                        }
                    }
                }
            }
            "tool" -> {
                // Risultato: MAI retained come testo (policy untrusted). Serve
                // solo a marcare fallito il chip corrispondente.
                val callId = row.raw?.optString("tool_call_id").orEmpty()
                if (failed) markToolFailed(callId)
            }
            else -> {
                // system/developer/compaction/summary/ignoti: scartati (niente
                // canvas, niente buchi visibili; la storia resta sul server).
            }
        }
    }
    flush()
    return out
}

/** Compat: singolo messaggio (primo), null se niente da mostrare. */
internal fun HermesSessionMessage.toBotChatMessage(): ChatMessage? =
    foldTranscriptToChat(listOf(this)).firstOrNull()

/**
 * Stato live di una riga transcript per il banner "sta lavorando":
 * solo etichette brevi, mai contenuto (niente muri di testo). Puro.
 */
internal fun botLiveStatusFor(row: HermesSessionMessage): String {
    val toolName = row.raw?.optString("tool_name").orEmpty().ifBlank {
        row.raw?.optJSONArray("tool_calls")?.optJSONObject(0)?.optString("name").orEmpty().ifBlank {
            row.raw?.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")?.optString("name").orEmpty()
        }
    }
    return when (row.role.lowercase()) {
        "tool" -> if (toolName.isNotBlank()) "Sta usando $toolName…" else "Sta usando uno strumento…"
        "assistant" -> {
            val reasoning = row.raw?.optString("reasoning_content").orEmpty()
                .ifBlank { row.raw?.optString("reasoning").orEmpty() }
            if (toolName.isNotBlank()) "Sta usando $toolName…"
            else if (reasoning.isNotBlank()) "Sta ragionando…"
            else "Sta scrivendo…"
        }
        "user", "tu" -> "Nuovo messaggio…"
        else -> "Sta lavorando…"
    }
}

internal fun transcriptTimestampOf(row: HermesSessionMessage): Double =
    row.raw?.optDouble("timestamp", 0.0) ?: 0.0

internal fun transcriptCreatedAtOf(row: HermesSessionMessage): String =
    row.raw?.optString("created_at").orEmpty().ifBlank { row.createdAt }

internal fun transcriptRowIdOf(row: HermesSessionMessage): String =
    row.raw?.optString("id").orEmpty()
        .ifBlank { row.raw?.optString("message_id").orEmpty() }
        .ifBlank { row.raw?.optString("seq").orEmpty() }
        .ifBlank { row.raw?.optString("created_at").orEmpty() + "|" + row.createdAt }

/**
 * Ordinamento stabile transcript: compareBy(timestamp).thenBy(createdAt).thenBy(id),
 * fallback asReversed solo se tutti timestamp == 0 (ordine API). Puro e testabile.
 */
internal fun sortTranscriptRows(rows: List<HermesSessionMessage>): List<HermesSessionMessage> {
    if (rows.isEmpty()) return rows
    val allZero = rows.all { transcriptTimestampOf(it) == 0.0 }
    if (allZero) return rows.asReversed()
    return rows.sortedWith(
        compareBy<HermesSessionMessage> { transcriptTimestampOf(it) }
            .thenBy { transcriptCreatedAtOf(it) }
            .thenBy { transcriptRowIdOf(it) }
    )
}

/**
 * Transcript canonico di una sessione bot (cronologia autorevole condivisa
 * col desktop). latest-first dal server, reso cronologico (ordina per
 * timestamp quando presenti, altrimenti ordine API). Null in caso di
 * errore/offline: il chiamante usa la cache locale.
 */
internal suspend fun loadCanonicalBotTranscript(
    settings: AppSettings,
    apiKey: String?,
    profile: String,
    sessionId: String,
    multiplexEnabled: Boolean,
    limit: Int = 500
): List<ChatMessage>? = withContext(Dispatchers.IO) {
    runCatching {
        val client = HermesSessionClient(settings, apiKey, profile, multiplexEnabled, null)
        // Il limit del chiamante vale davvero: pagine da max 500, stop al
        // raggiungimento (niente fetch da 2500 righe quando ne bastano 5).
        val pageSize = limit.coerceAtLeast(1).coerceAtMost(500)
        val allRows = mutableListOf<HermesSessionMessage>()
        var offset = 0
        var lastCode = 200
        for (page in 0 until 5) {
            val (code, rows) = client.messages(id = sessionId, limit = pageSize, offset = offset, order = "latest", includeCompacted = true)
            lastCode = code
            if (code !in 200..299) {
                if (allRows.isEmpty()) return@runCatching null
                break
            }
            if (rows.isEmpty()) break
            allRows.addAll(rows)
            if (rows.size < pageSize || allRows.size >= limit) break
            offset += pageSize
        }
        if (lastCode !in 200..299 || allRows.isEmpty()) return@runCatching null
        val chronological = sortTranscriptRows(allRows)
        foldTranscriptToChat(chronological).takeIf { it.isNotEmpty() }
    }.getOrNull()
}

internal fun hermesApiRoot(settings: AppSettings): String {
    // hermesRoot e' definito in HubOperations; qui replica leggera per evitare dipendenze circolari.
    val base = settings.gatewayUrl.trim().trimEnd('/')
    if (base.isEmpty()) return ""
    // Se gatewayUrl termina gia' con /v1, risali di un livello per /api/*.
    return if (base.endsWith("/v1", ignoreCase = true)) base.dropLast(3).trimEnd('/') else base
}

internal fun parseHermesSessionList(body: String): List<HermesSession> {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
    val arr = root.optJSONArray("sessions") ?: root.optJSONArray("items") ?: root.optJSONArray("data") ?: JSONArray()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id", o.optString("session_id", "")).trim()
        if (id.isEmpty()) return@mapNotNull null
        HermesSession(
            id = id,
            title = o.optString("title", ""),
            source = o.optString("source", ""),
            parentId = o.optString("parent_session_id", o.optString("parent_id", "")).takeIf { it.isNotBlank() },
            createdAt = o.optString("created_at", ""),
            updatedAt = o.optString("updated_at", ""),
            raw = o
        )
    }
}

internal fun parseHermesSession(body: String): HermesSession? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val o = root.optJSONObject("session") ?: root
    val id = o.optString("id", o.optString("session_id", "")).trim()
    if (id.isEmpty()) return null
    return HermesSession(
        id = id,
        title = o.optString("title", ""),
        source = o.optString("source", ""),
        parentId = o.optString("parent_session_id", o.optString("parent_id", "")).takeIf { it.isNotBlank() },
        createdAt = o.optString("created_at", ""),
        updatedAt = o.optString("updated_at", ""),
        raw = o
    )
}

internal fun parseHermesSessionMessages(body: String): List<HermesSessionMessage> {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
    val arr = root.optJSONArray("messages") ?: root.optJSONArray("items") ?: root.optJSONArray("data") ?: JSONArray()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val role = o.optString("role", "unknown")
        val content = when (val c = o.opt("content")) {
            is String -> c
            is JSONArray, is JSONObject -> c.toString()
            else -> o.optString("text", o.optString("message", ""))
        }
        HermesSessionMessage(role = role, content = content, createdAt = o.optString("created_at", ""), raw = o)
    }
}

/**
 * Messaggio da mostrare quando POST /api/sessions/{id}/model fallisce.
 * null = lock salvato, nessun warning. Il turno usa comunque model/provider/model_options.
 */
internal fun hermesModelLockWarning(code: Int, model: String): String? {
    val label = model.trim().takeIf { it.isNotEmpty() } ?: "selezionato"
    return when {
        code in 200..299 -> null
        code == 401 || code == 403 ->
            "Modello $label usato per questo turno, ma il lock persistente NON è salvato: chiave rifiutata (fail-closed, HTTP $code)."
        code == 404 ->
            "Modello $label usato per questo turno, ma il lock persistente NON è salvato: sessione non trovata (404)."
        else ->
            "Modello $label usato per questo turno, ma il lock persistente NON è salvato (HTTP $code): verrà riprovato alla prossima selezione."
    }
}

class HermesSessionClient(
    private val settings: AppSettings,
    private val apiKey: String?,
    private val profile: String? = null,
    private val multiplexEnabled: Boolean = false,
    private val capabilities: HermesCapabilities? = null
) {
    private fun sessionUrl(path: String): String {
        val root = hermesApiRoot(settings).trimEnd('/')
        val normalized = if (path.startsWith("/")) path else "/$path"
        if (!profile.isNullOrBlank()) {
            // Fail-closed: profilo nominato richiede multiplex esplicito; mai riusare default key su prefisso /p/.
            check(multiplexEnabled) { "Il multiplexing dei profili Hermes non è pronto." }
            val name = normalizeHermesProfileName(profile)
            return "$root/p/$name$normalized"
        }
        return "$root$normalized"
    }

    private fun requireCapability(ok: Boolean, name: String) {
        // Nessun fallback silenzioso: capability assente -> errore esplicito e deterministico.
        if (capabilities != null && !ok) throw UnsupportedOperationException("Funzione non supportata dal server: $name")
    }

    suspend fun list(limit: Int = 50, offset: Int = 0, source: String? = null, title: String? = null, includeHidden: Boolean = false): Pair<Int, List<HermesSession>> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.sessionList ?: true, "session_list")
        var url = sessionUrl("/api/sessions?limit=$limit&offset=$offset")
        if (!source.isNullOrBlank()) url += "&source=${java.net.URLEncoder.encode(source, "UTF-8")}"
        // Filtro titolo esatto indicizzato (registro canonical bot: title="Bot Chat").
        if (!title.isNullOrBlank()) url += "&title=${java.net.URLEncoder.encode(title, "UTF-8")}"
        // Le Bot Chat canoniche sono sempre hidden: senza questo la scan
        // torna vuota e si finisce a mintare duplicati (HTTP 400).
        if (includeHidden) url += "&include_hidden=true"
        val res = httpGetResponse(url, apiKey)
        if (res.first !in 200..299) return@withContext res.first to emptyList()
        res.first to parseHermesSessionList(res.second)
    }

    suspend fun create(title: String? = null, source: String = "hermes-hub-android"): Pair<Int, HermesSession?> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.sessionCreate ?: true, "session_create")
        val payload = JSONObject().put("source", source)
        if (!title.isNullOrBlank()) payload.put("title", title)
        val res = postJson(sessionUrl("/api/sessions"), payload, apiKey)
        if (res.first !in 200..299) return@withContext res.first to null
        res.first to parseHermesSession(res.second)
    }

    suspend fun get(id: String): Pair<Int, HermesSession?> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.sessionRead ?: true, "session_read")
        val res = httpGetResponse(sessionUrl("/api/sessions/${java.net.URLEncoder.encode(id, "UTF-8")}"), apiKey)
        if (res.first !in 200..299) return@withContext res.first to null
        res.first to parseHermesSession(res.second)
    }

    suspend fun patch(id: String, title: String? = null, endReason: String? = null): Pair<Int, HermesSession?> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.sessionPatch ?: true, "session_patch")
        val payload = JSONObject()
        if (title != null) payload.put("title", title)
        if (endReason != null) payload.put("end_reason", endReason)
        val res = postJson(sessionUrl("/api/sessions/${java.net.URLEncoder.encode(id, "UTF-8")}"), payload, apiKey, method = "PATCH")
        if (res.first !in 200..299) return@withContext res.first to null
        res.first to parseHermesSession(res.second)
    }

    suspend fun delete(id: String): Int = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.sessionDelete ?: true, "session_delete")
        postJson(sessionUrl("/api/sessions/${java.net.URLEncoder.encode(id, "UTF-8")}"), JSONObject(), apiKey, method = "DELETE").first
    }

    suspend fun messages(id: String, limit: Int = 200, offset: Int = 0, order: String? = null, includeCompacted: Boolean = false): Pair<Int, List<HermesSessionMessage>> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.sessionMessages ?: true, "session_messages")
        var url = sessionUrl("/api/sessions/${java.net.URLEncoder.encode(id, "UTF-8")}/messages?limit=$limit&offset=$offset")
        if (!order.isNullOrBlank()) url += "&order=${java.net.URLEncoder.encode(order, "UTF-8")}"
        if (includeCompacted) url += "&include_compacted=true"
        val res = httpGetResponse(url, apiKey)
        if (res.first !in 200..299) return@withContext res.first to emptyList()
        res.first to parseHermesSessionMessages(res.second)
    }

    suspend fun fork(id: String, title: String? = null): Pair<Int, HermesSession?> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.sessionFork ?: true, "session_fork")
        val payload = JSONObject()
        if (!title.isNullOrBlank()) payload.put("title", title)
        val res = postJson(sessionUrl("/api/sessions/${java.net.URLEncoder.encode(id, "UTF-8")}/fork"), payload, apiKey)
        if (res.first !in 200..299) return@withContext res.first to null
        res.first to parseHermesSession(res.second)
    }

    /**
     * Model lock per-sessione (POST /api/sessions/{id}/model): pin persistente server-side,
     * precedence #1 sui turni della sessione. Usato quando la chat ha override modello/provider.
     */
    suspend fun lockModel(id: String, model: String? = null, provider: String? = null): Pair<Int, String> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.sessionModelLock ?: true, "session_model_lock")
        val payload = JSONObject()
        if (!model.isNullOrBlank()) payload.put("model", model)
        if (!provider.isNullOrBlank()) payload.put("provider", provider)
        postJson(sessionUrl("/api/sessions/${java.net.URLEncoder.encode(id, "UTF-8")}/model"), payload, apiKey)
    }

    suspend fun chat(
        id: String,
        input: String,
        model: String? = null,
        provider: String? = null,
        modelOptions: JSONObject? = null,
        sessionKey: String? = null
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.sessionChat ?: true, "session_chat")
        val payload = JSONObject().put("input", input)
        if (!model.isNullOrBlank()) payload.put("model", model)
        if (!provider.isNullOrBlank()) payload.put("provider", provider)
        if (modelOptions != null) payload.put("model_options", modelOptions)
        postJsonWithSessionKey(sessionUrl("/api/sessions/${java.net.URLEncoder.encode(id, "UTF-8")}/chat"), payload, sessionKey)
    }

    private suspend fun postJsonWithSessionKey(url: String, payload: JSONObject, sessionKey: String?): Pair<Int, String> {
        // X-Hermes-Session-Key e' thread-safe via header dedicato; mai riusare Session-Id come memory scope.
        if (sessionKey.isNullOrBlank()) return postJson(url, payload, apiKey)
        var last: Pair<Int, String> = 0 to "Rete non disponibile"
        for (candidateUrl in plugAndPlayUrlCandidates(url)) {
            for (token in hermesAuthCandidates(apiKey)) {
                // Fail-closed profilo: mai fallback anonimo su /p/<profile>/.
                if (!profile.isNullOrBlank() && token.isNullOrBlank()) continue
                val res = runCatching {
                    val builder = okhttp3.Request.Builder()
                        .url(candidateUrl)
                        .header("Accept", "application/json")
                        .header("User-Agent", "HermesHub-Android")
                    HermesHubProtocol.addCorrelationHeaders(builder, HermesHubProtocol.newCorrelationContext())
                    token?.let { builder.header("Authorization", "Bearer $it") }
                    // Chiave sessione validata come altrove: header con \r\n
                    // farebbe lanciare OkHttp invece di un drop pulito.
                    sessionKey.takeIf { isValidHermesSessionKey(it) }?.let {
                        builder.header("X-Hermes-Session-Key", it.trim())
                    }
                    val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = builder.post(body).build()
                    apiHttpClient.newCall(request).execute().use { resp ->
                        resp.code to resp.body.byteStream().readUtf8Bounded()
                    }
                }.getOrElse {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    0 to (it.message ?: it.javaClass.simpleName)
                }
                last = res
                if (!shouldRetryHermesWithBearerAuth(res.first, res.second)) {
                    if (res.first != 0) return res
                }
            }
        }
        return last
    }
}
