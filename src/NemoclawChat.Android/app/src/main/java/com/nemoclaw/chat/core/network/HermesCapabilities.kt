package com.nemoclaw.chat

import org.json.JSONObject

/**
 * Modello tipizzato per GET /v1/capabilities (Hermes Agent >= v2026.8.x, rif. v2026.9.14).
 * Fonte primaria capability-driven: mai usare confronti di versione quando esiste una capability.
 * Campi sconosciuti tollerati; capability assente = feature non supportata (fallback esplicito).
 */
data class HermesCapabilities(
    val raw: JSONObject,
    val platform: String = "hermes-agent",
    val model: String = "hermes-agent",
    val chatCompletions: Boolean = false,
    val responsesApi: Boolean = false,
    val runSubmission: Boolean = false,
    val runStatus: Boolean = false,
    val runEventsSse: Boolean = false,
    val runStop: Boolean = false,
    val runSteer: Boolean = false,
    val runApproval: Boolean = false,
    val sessionList: Boolean = false,
    val sessionCreate: Boolean = false,
    val sessionRead: Boolean = false,
    val sessionPatch: Boolean = false,
    val sessionDelete: Boolean = false,
    val sessionMessages: Boolean = false,
    val sessionFork: Boolean = false,
    val sessionChat: Boolean = false,
    val sessionChatStream: Boolean = false,
    val sessionModelLock: Boolean = false,
    val modelOptions: Boolean = false,
    val reasoningEfforts: List<String> = emptyList(),
    val sessionKeyHeader: String? = null,
    val sessionContinuityHeader: String? = null,
    val skillsApi: Boolean = false,
    val toolsetsApi: Boolean = false,
    val endpoints: Map<String, String> = emptyMap()
) {
    fun supportsSessions(): Boolean = sessionList || sessionCreate || sessionChat || sessionChatStream
    fun supportsModernSessions(): Boolean = sessionChatStream && sessionMessages && sessionFork
    fun supportsRuns(): Boolean = runSubmission
    fun supportsFullRunControl(): Boolean = runStatus && runEventsSse && runStop
    fun supportsSteer(): Boolean = runSteer
    fun supportsApproval(): Boolean = runApproval
    fun supportsModelOptions(): Boolean = modelOptions
    fun supportsReasoningEffort(effort: String): Boolean {
        if (reasoningEfforts.isEmpty()) return false
        return reasoningEfforts.any { it.equals(effort, ignoreCase = true) }
    }
}

internal fun parseHermesCapabilities(body: String): HermesCapabilities? {
    if (body.isBlank()) return null
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    return parseHermesCapabilities(root)
}

internal fun parseHermesCapabilities(root: JSONObject): HermesCapabilities {
    val features = root.optJSONObject("features") ?: JSONObject()
    // Alcune versioni espongono flag a top-level oltre che dentro "features".
    fun flag(vararg names: String): Boolean {
        names.forEach { n ->
            if (features.optBoolean(n, false)) return true
            if (root.optBoolean(n, false)) return true
        }
        return false
    }
    // Tabella endpoints: upstream reale usa oggetti {method,path}, docs vecchie stringhe.
    val endpointsObj = root.optJSONObject("endpoints") ?: JSONObject()
    val endpoints = mutableMapOf<String, String>()
    val keys = endpointsObj.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val v = endpointsObj.opt(k)
        endpoints[k] = when (v) {
            is JSONObject -> v.optString("path", v.toString())
            is String -> v
            else -> v?.toString().orEmpty()
        }
    }
    fun hasEndpoint(vararg names: String): Boolean =
        names.any { n -> endpoints.keys.any { it.equals(n, ignoreCase = true) } }
    // Session flags: supporta naming docs (session_*) E nomi reali upstream (session_resources,
    // session_chat_streaming, session_update, ...) E detection via tabella endpoints.
    fun sessionFlag(docsName: String, vararg realNames: String, endpointKey: String): Boolean {
        if (flag(docsName, "session_$docsName")) return true
        realNames.forEach { if (flag(it)) return true }
        return hasEndpoint(endpointKey, "session_$docsName")
    }
    val reasoningEfforts = mutableListOf<String>()
    val effArray = features.optJSONArray("reasoning_efforts")
        ?: features.optJSONArray("reasoningEfforts")
        ?: root.optJSONArray("reasoning_efforts")
    if (effArray != null) {
        for (i in 0 until effArray.length()) {
            val v = effArray.optString(i, "").trim()
            if (v.isNotEmpty()) reasoningEfforts += v.lowercase()
        }
    }
    // Fallback: se model_options presente ma ladder assente -> server legacy (fail-closed su max/ultra).
    val sessionKeyHeader = (features.optString("session_key_header", "").ifBlank {
        root.optString("session_key_header", "")
    }).takeIf { it.isNotBlank() }
    val sessionContinuityHeader = (features.optString("session_continuity_header", "").ifBlank {
        root.optString("session_continuity_header", "")
    }).takeIf { it.isNotBlank() }
    return HermesCapabilities(
        raw = root,
        platform = root.optString("platform", "hermes-agent").ifBlank { "hermes-agent" },
        model = root.optString("model", "hermes-agent").ifBlank { "hermes-agent" },
        chatCompletions = flag("chat_completions", "chatCompletions") || hasEndpoint("chat_completions"),
        responsesApi = flag("responses_api", "responsesApi") || hasEndpoint("responses"),
        runSubmission = flag("run_submission", "runSubmission") || hasEndpoint("runs"),
        runStatus = flag("run_status", "runStatus") || hasEndpoint("run_status"),
        runEventsSse = flag("run_events_sse", "runEventsSse") || hasEndpoint("run_events"),
        runStop = flag("run_stop", "runStop") || hasEndpoint("run_stop"),
        runSteer = flag("run_steer", "runSteer") || hasEndpoint("run_steer"),
        runApproval = flag("run_approval", "runApproval", "run_approval_response", "approval_events") || hasEndpoint("run_approval"),
        sessionList = sessionFlag("list", "session_resources", endpointKey = "sessions"),
        sessionCreate = sessionFlag("create", "session_resources", endpointKey = "session_create"),
        sessionRead = sessionFlag("read", "session_resources", endpointKey = "session") || flag("session_get"),
        sessionPatch = sessionFlag("patch", "session_resources", endpointKey = "session_update") || flag("session_update"),
        sessionDelete = sessionFlag("delete", "session_resources", endpointKey = "session_delete"),
        sessionMessages = sessionFlag("messages", "session_resources", endpointKey = "session_messages"),
        sessionFork = sessionFlag("fork", "session_resources", endpointKey = "session_fork") || flag("session_fork"),
        sessionChat = sessionFlag("chat", "session_resources", endpointKey = "session_chat") || flag("session_chat"),
        sessionChatStream = sessionFlag("chat_stream", "session_resources", "session_chat_streaming", endpointKey = "session_chat_stream") || flag("session_stream"),
        sessionModelLock = flag("session_model_lock", "sessionModelLock") || hasEndpoint("session_model_lock"),
        modelOptions = flag("model_options", "modelOptions") || hasEndpoint("model_options"),
        reasoningEfforts = reasoningEfforts.distinct(),
        sessionKeyHeader = sessionKeyHeader,
        sessionContinuityHeader = sessionContinuityHeader,
        skillsApi = flag("skills_api", "skillsApi") || hasEndpoint("skills"),
        toolsetsApi = flag("toolsets_api", "toolsetsApi") || hasEndpoint("toolsets"),
        endpoints = endpoints
    )
}

/** Ladder canonica accettata da Hermes Agent attuale (v2026.9.14, _REASONING_EFFORTS). Mai hardcodare nel picker: usare capabilities. */
internal val HERMES_REASONING_EFFORT_LADDER = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

/**
 * Risolve l'effort da inviare: se il server pubblicizza la ladder, invia solo valori pubblicizzati.
 * Ritorna null se non supportato (il chiamante deve nascondere/disabilitare la UI, non simulare).
 */
internal fun resolveReasoningEffortForServer(capabilities: HermesCapabilities?, requested: String?): String? {
    val effort = requested?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    if (effort == "none") return "none"
    if (capabilities == null) return null // Senza capabilities: fail-closed, non inventare.
    if (!capabilities.supportsModelOptions()) return null
    if (capabilities.reasoningEfforts.isEmpty()) {
        // Server legacy: accetta solo ladder storica a 6 livelli; max/ultra degradati esplicitamente dal chiamante.
        return if (effort in listOf("minimal", "low", "medium", "high", "xhigh")) effort else null
    }
    return if (capabilities.supportsReasoningEffort(effort)) effort else null
}
