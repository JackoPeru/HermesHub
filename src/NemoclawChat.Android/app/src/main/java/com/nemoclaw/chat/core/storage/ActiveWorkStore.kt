package com.nemoclaw.chat

import android.content.Context
import androidx.core.content.edit
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

private val approvalClaimLock = Any()
private val claimedApprovalIds = mutableSetOf<String>()

private val stopClaimLock = Any()
private val claimedStopRuns = mutableSetOf<String>()

/**
 * Single-flight condiviso processo per i POST di approvazione.
 * Ritorna true solo la prima volta per id, false per i duplicati (skip).
 * Thread-safe via lock dedicato. Id vuoti mai reclamati.
 */
internal fun tryClaimApproval(approvalId: String): Boolean {
    val clean = approvalId.trim()
    if (clean.isEmpty()) return false
    synchronized(approvalClaimLock) {
        if (claimedApprovalIds.contains(clean)) return false
        claimedApprovalIds.add(clean)
        return true
    }
}

internal fun resetApprovalClaimsForTest() {
    synchronized(approvalClaimLock) { claimedApprovalIds.clear() }
}

/** Rilascia un claim dopo POST fallito: il retry resta possibile. */
internal fun releaseApprovalClaim(approvalId: String) {
    val clean = approvalId.trim()
    if (clean.isEmpty()) return
    synchronized(approvalClaimLock) { claimedApprovalIds.remove(clean) }
}

/**
 * Single-flight per lo stop: secondo stop per stesso runId è no-op.
 * Sincrono e idempotente, thread-safe.
 */
internal fun tryClaimStopRun(runId: String): Boolean {
    val clean = runId.trim()
    if (clean.isEmpty()) return false
    synchronized(stopClaimLock) {
        if (claimedStopRuns.contains(clean)) return false
        claimedStopRuns.add(clean)
        return true
    }
}

internal fun resetStopClaimsForTest() {
    synchronized(stopClaimLock) { claimedStopRuns.clear() }
}

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
        prefs.edit { putString(ACTIVE_WORK_KEY, JSONObject(all.mapValues { encodeActiveWorkBinding(it.value) }).toString()) }
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
            prefs.edit { putString(ACTIVE_WORK_KEY, JSONObject(all.mapValues { encodeActiveWorkBinding(it.value) }).toString()) }
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
    // Lettura separata senza shadowing: optString con fallback annidato nasconde
    // request_id/id quando approval_id esiste ma è blank. Prendi la prima non-blank.
    val approvalIdRaw = node.optString("approval_id").trim()
    val requestIdRaw = node.optString("request_id").trim()
    val idRaw = node.optString("id").trim()
    val firstId = listOf(approvalIdRaw, requestIdRaw, idRaw).firstOrNull { it.isNotBlank() }.orEmpty()
    if (firstId.isBlank()) {
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

private const val BOT_AUTO_APPROVE_PREFS = "chatclaw_bot_auto_approve"
private val botAutoApproveLock = Any()

/** Auto-approve per bot (chiave: nome profilo). Null = nessuna override, vale il globale. */
internal fun loadBotAutoApproveMap(context: Context): Map<String, String> {
    synchronized(botAutoApproveLock) {
        val prefs = context.applicationContext
            .getSharedPreferences(BOT_AUTO_APPROVE_PREFS, Context.MODE_PRIVATE)
        val out = mutableMapOf<String, String>()
        val raw = prefs.getString("modes", "{}").orEmpty()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return out
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val mode = root.optString(key).trim().lowercase()
            if (mode in setOf("off", "session", "always")) out[key] = mode
        }
        return out
    }
}

internal fun saveBotAutoApprove(context: Context, botProfile: String, mode: String) {
    val key = botProfile.trim()
    if (key.isEmpty()) return
    val clean = mode.trim().lowercase().takeIf { it in setOf("off", "session", "always") } ?: return
    synchronized(botAutoApproveLock) {
        val prefs = context.applicationContext
            .getSharedPreferences(BOT_AUTO_APPROVE_PREFS, Context.MODE_PRIVATE)
        val all = loadBotAutoApproveMap(context).toMutableMap()
        if (clean == "off") all.remove(key) else all[key] = clean
        val encoded = JSONObject()
        for ((k, v) in all) encoded.put(k, v)
        prefs.edit { putString("modes", encoded.toString()) }
    }
}

/** Risoluzione effettiva: per-bot > globale. Pura, testabile a meno del context. */
internal fun resolveAutoApproveMode(botProfile: String?, botModes: Map<String, String>, globalMode: String): String {
    val cleanGlobal = globalMode.trim().lowercase().takeIf { it in setOf("off", "session", "always") } ?: "off"
    val key = botProfile?.trim().orEmpty()
    if (key.isEmpty()) return cleanGlobal
    return botModes[key] ?: botModes.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value ?: cleanGlobal
}
