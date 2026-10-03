package com.nemoclaw.chat

import android.content.Context
import org.json.JSONObject

/**
 * Binding persistente tra conversazione e run server attiva.
 *
 * Il lavoro dell'agente vive sul gateway: se il client muore (app killata,
 * stream troncato) la run continua grazie a `continue_on_disconnect`. Questo
 * store tiene l'indirizzo per ritrovarla alla riapertura: conversationId ->
 * runId (+ sessioni e obiettivo). Scrittura atomica via apply(); mai dati
 * parziali. Solo il terminale reale (o lo stop utente confermato) cancella.
 */
data class ActiveWorkBinding(
    val conversationId: String,
    val runId: String,
    val sessionId: String? = null,
    val serverSessionId: String? = null,
    val goal: String = "",
    val startedAtMs: Long = 0L
)

internal const val ACTIVE_WORK_PREFS = "chatclaw_active_work"
private const val ACTIVE_WORK_KEY = "bindings"
private val activeWorkLock = Any()

internal fun encodeActiveWorkBinding(binding: ActiveWorkBinding): JSONObject = JSONObject()
    .put("conversationId", binding.conversationId)
    .put("runId", binding.runId)
    .put("sessionId", binding.sessionId ?: JSONObject.NULL)
    .put("serverSessionId", binding.serverSessionId ?: JSONObject.NULL)
    .put("goal", binding.goal)
    .put("startedAtMs", binding.startedAtMs)

internal fun decodeActiveWorkBinding(obj: JSONObject?): ActiveWorkBinding? {
    if (obj == null) return null
    val conversationId = obj.optString("conversationId").trim()
    val runId = obj.optString("runId").trim()
    if (conversationId.isEmpty() || runId.isEmpty()) return null
    return ActiveWorkBinding(
        conversationId = conversationId,
        runId = runId,
        sessionId = obj.optString("sessionId").takeIf { it.isNotBlank() },
        serverSessionId = obj.optString("serverSessionId").takeIf { it.isNotBlank() },
        goal = obj.optString("goal"),
        startedAtMs = obj.optLong("startedAtMs")
    )
}

internal fun saveActiveWorkBinding(context: Context, binding: ActiveWorkBinding) {
    synchronized(activeWorkLock) {
        val prefs = context.applicationContext
            .getSharedPreferences(ACTIVE_WORK_PREFS, Context.MODE_PRIVATE)
        val all = readActiveWorkMap(prefs.getString(ACTIVE_WORK_KEY, "{}").orEmpty()).toMutableMap()
        all[binding.conversationId] = binding
        prefs.edit().putString(ACTIVE_WORK_KEY, JSONObject(all.mapValues { encodeActiveWorkBinding(it.value) }).toString()).apply()
    }
}

internal fun loadActiveWorkBindings(context: Context): Map<String, ActiveWorkBinding> {
    synchronized(activeWorkLock) {
        val prefs = context.applicationContext
            .getSharedPreferences(ACTIVE_WORK_PREFS, Context.MODE_PRIVATE)
        return readActiveWorkMap(prefs.getString(ACTIVE_WORK_KEY, "{}").orEmpty())
    }
}

internal fun loadActiveWorkBinding(context: Context, conversationId: String): ActiveWorkBinding? =
    loadActiveWorkBindings(context)[conversationId]

internal fun clearActiveWorkBinding(context: Context, conversationId: String) {
    synchronized(activeWorkLock) {
        val prefs = context.applicationContext
            .getSharedPreferences(ACTIVE_WORK_PREFS, Context.MODE_PRIVATE)
        val all = readActiveWorkMap(prefs.getString(ACTIVE_WORK_KEY, "{}").orEmpty()).toMutableMap()
        if (all.remove(conversationId) != null) {
            prefs.edit().putString(ACTIVE_WORK_KEY, JSONObject(all.mapValues { encodeActiveWorkBinding(it.value) }).toString()).apply()
        }
    }
}

private fun readActiveWorkMap(raw: String): Map<String, ActiveWorkBinding> {
    val out = mutableMapOf<String, ActiveWorkBinding>()
    if (raw.isBlank()) return out
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return out
    val keys = root.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        decodeActiveWorkBinding(root.optJSONObject(key))?.let { out[key] = it }
    }
    return out
}

/** Stato lavoro-background derivato dallo status run server. Puro, testabile. */
internal enum class BackgroundWorkState {
    ACTIVE,
    WAITING_FOR_APPROVAL,
    DONE_COMPLETED,
    DONE_FAILED,
    DONE_CANCELLED,
    GONE,
    UNKNOWN
}

internal fun backgroundWorkStateFromRun(info: HermesRunInfo?, hasApproval: Boolean): BackgroundWorkState {
    if (info == null) return BackgroundWorkState.UNKNOWN
    // waiting_for_approval puo' arrivare sia come status wire sia dedotto dal payload.
    if (hasApproval) return BackgroundWorkState.WAITING_FOR_APPROVAL
    return when (info.status) {
        HermesRunStatus.QUEUED, HermesRunStatus.RUNNING -> BackgroundWorkState.ACTIVE
        HermesRunStatus.WAITING_FOR_APPROVAL -> BackgroundWorkState.WAITING_FOR_APPROVAL
        HermesRunStatus.STOPPING -> BackgroundWorkState.ACTIVE
        HermesRunStatus.COMPLETED -> BackgroundWorkState.DONE_COMPLETED
        HermesRunStatus.FAILED -> BackgroundWorkState.DONE_FAILED
        HermesRunStatus.CANCELLED -> BackgroundWorkState.DONE_CANCELLED
        HermesRunStatus.UNKNOWN -> BackgroundWorkState.UNKNOWN
    }
}

/**
 * Estrae una eventuale approval dal body di GET /v1/runs/{id} (campo "approval"
 * registrato dal server quando il run attende decisione). Tollerante alla forma.
 */
internal fun parseRunApprovalPayload(body: String, runId: String = ""): HermesRunApprovalRequest? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val node = root.optJSONObject("approval")
        ?: root.optJSONObject("payload")?.optJSONObject("approval")
        ?: return null
    if (node.optString("approval_id", node.optString("request_id", node.optString("id", ""))).isBlank()) {
        return null
    }
    val parsed = parseHermesApprovalRequest(node)
    if (parsed.approvalId.isBlank() && parsed.requestId.isBlank()) return null
    return parsed
}

internal fun backgroundWorkSummary(binding: ActiveWorkBinding): String {
    val goal = binding.goal.trim().take(90)
    return if (goal.isBlank()) "Hermes al lavoro in background…" else "Hermes al lavoro: $goal"
}
