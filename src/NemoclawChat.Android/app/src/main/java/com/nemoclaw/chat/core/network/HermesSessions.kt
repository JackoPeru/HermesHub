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
 * Riga transcript server -> messaggi chat. Puro e testabile.
 * user->Tu, assistant->Hermes (thinking da reasoning, tool_calls anche
 * come riga azione), tool->azione compatta, turni falliti marcati,
 * system/developer scartati, resto ignoto solo con testo.
 */
internal fun HermesSessionMessage.toBotChatMessages(): List<ChatMessage> {
    val toolCalls = raw?.optJSONArray("tool_calls")
    val toolName = raw?.optString("tool_name").orEmpty().ifBlank {
        toolCalls?.optJSONObject(0)?.optString("name").orEmpty().ifBlank {
            toolCalls?.optJSONObject(0)?.optJSONObject("function")?.optString("name").orEmpty()
        }
    }
    val reasoning = raw?.optString("reasoning_content").orEmpty()
        .ifBlank { raw?.optString("reasoning").orEmpty() }
    val failed = raw?.optString("display_kind").orEmpty() == "failed_turn"
    val failedPrefix = if (failed) "(fallito) " else ""
    fun toolAction(): ChatMessage? {
        if (toolName.isBlank() && (toolCalls == null || toolCalls.length() == 0)) return null
        val names = (0 until (toolCalls?.length() ?: 0)).mapNotNull { i ->
            toolCalls?.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                ?: toolCalls?.optJSONObject(i)?.optJSONObject("function")?.optString("name")?.takeIf { it.isNotBlank() }
        }.distinct().take(3)
        val label = names.ifEmpty { listOf(toolName.ifBlank { "strumento" }) }.joinToString(", ")
        return ChatMessage("Hermes", "🛠 $failedPrefix$label", fromUser = false, isAction = true)
    }
    return when (role.lowercase()) {
        "user", "tu" -> listOf(ChatMessage("Tu", content, fromUser = true))
        "assistant" -> buildList {
            if (content.isNotBlank()) add(ChatMessage("Hermes", "$failedPrefix$content", fromUser = false, thinking = reasoning))
            toolAction()?.let { add(it) }
        }
        "tool" -> {
            val text = content.ifBlank { "(nessun output)" }
            val short = if (text.length > 300) text.take(300) + "… (+${text.length - 300})" else text
            listOf(ChatMessage("Strumento", "$failedPrefix${toolName.ifBlank { "tool" }}: $short", fromUser = false, isAction = true))
        }
        "system", "developer" -> emptyList()
        "compaction", "summary" -> if (content.isNotBlank()) {
            listOf(ChatMessage("Hermes", "(contesto compattato) ${content.take(300)}", fromUser = false, isAction = true))
        } else emptyList()
        else -> if (content.isNotBlank()) listOf(ChatMessage("Hermes", content, fromUser = false)) else emptyList()
    }
}

/** Compat: singolo messaggio (primo), null se niente da mostrare. */
internal fun HermesSessionMessage.toBotChatMessage(): ChatMessage? =
    toBotChatMessages().firstOrNull()

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
        val (code, rows) = client.messages(id = sessionId, limit = limit, order = "latest", includeCompacted = true)
        if (code !in 200..299 || rows.isEmpty()) return@runCatching null
        val chronological = if (rows.all { (it.raw?.optDouble("timestamp", 0.0) ?: 0.0) > 0 }) {
            rows.sortedBy { it.raw?.optDouble("timestamp", 0.0) }
        } else {
            rows.asReversed()
        }
        chronological.flatMap { it.toBotChatMessages() }.takeIf { it.isNotEmpty() }
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
        val res = httpGetResponse(sessionUrl("/api/sessions/$id"), apiKey)
        if (res.first !in 200..299) return@withContext res.first to null
        res.first to parseHermesSession(res.second)
    }

    suspend fun patch(id: String, title: String? = null, endReason: String? = null): Pair<Int, HermesSession?> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.sessionPatch ?: true, "session_patch")
        val payload = JSONObject()
        if (title != null) payload.put("title", title)
        if (endReason != null) payload.put("end_reason", endReason)
        val res = postJson(sessionUrl("/api/sessions/$id"), payload, apiKey, method = "PATCH")
        if (res.first !in 200..299) return@withContext res.first to null
        res.first to parseHermesSession(res.second)
    }

    suspend fun delete(id: String): Int = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.sessionDelete ?: true, "session_delete")
        postJson(sessionUrl("/api/sessions/$id"), JSONObject(), apiKey, method = "DELETE").first
    }

    suspend fun messages(id: String, limit: Int = 200, order: String? = null, includeCompacted: Boolean = false): Pair<Int, List<HermesSessionMessage>> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.sessionMessages ?: true, "session_messages")
        var url = sessionUrl("/api/sessions/$id/messages?limit=$limit")
        if (!order.isNullOrBlank()) url += "&order=$order"
        if (includeCompacted) url += "&include_compacted=true"
        val res = httpGetResponse(url, apiKey)
        if (res.first !in 200..299) return@withContext res.first to emptyList()
        res.first to parseHermesSessionMessages(res.second)
    }

    suspend fun fork(id: String, title: String? = null): Pair<Int, HermesSession?> = withContext(Dispatchers.IO) {
        requireCapability(capabilities?.sessionFork ?: true, "session_fork")
        val payload = JSONObject()
        if (!title.isNullOrBlank()) payload.put("title", title)
        val res = postJson(sessionUrl("/api/sessions/$id/fork"), payload, apiKey)
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
        postJson(sessionUrl("/api/sessions/$id/model"), payload, apiKey)
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
        postJsonWithSessionKey(sessionUrl("/api/sessions/$id/chat"), payload, sessionKey)
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
                    builder.header("X-Hermes-Session-Key", sessionKey.trim())
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
