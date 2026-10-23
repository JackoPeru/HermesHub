package com.nemoclaw.chat

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nemoclaw.chat.features.bots.loadBotLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

internal fun diagnosticCheck(label: String, path: String, response: DiagnosticProbeResult): DiagnosticCheck {
    val ok = response.error == null && when (path) {
        "/v1/media/not-a-real-media-id" -> response.statusCode == 404 || response.body.contains("media_not_found")
        else -> response.statusCode?.let { it in 200..299 } == true
    }
    val attempts = if (response.attemptCount > 1) " (${response.attemptCount} tentativi)" else ""
    val message = when {
        response.error != null -> "Probe fallito$attempts: ${response.error}"
        ok -> response.body.limitText(180).ifBlank { "HTTP ${response.statusCode}$attempts" }
        response.statusCode == null -> "Trasporto fallito$attempts."
        else -> "HTTP ${response.statusCode}$attempts: ${extractHumanError(response.body)}"
    }
    return DiagnosticCheck(
        label = label,
        endpoint = response.effectiveUrl,
        ok = ok,
        message = message,
        action = diagnosticAction(label)
    )
}

internal fun diagnosticAction(label: String): String = when (label) {
    "Tailscale/API" -> "Avvia Tailscale e hermes-hub, verifica IP/porta 8642."
    "Memoria" -> "Aggiorna Hermes Gateway alla latest release e riavvia hermes-hub. Memoria = preferenze/profilo Hermes Agent lato server, non RAM telefono."
    "Hub state" -> "Aggiorna Hermes Gateway alla latest release e riavvia hermes-hub."
    "Hardware" -> "Aggiorna Hermes Gateway/patcher e installa psutil su Linux se mancano metriche live."
    "Video library" -> "Aggiorna Hermes Gateway alla latest release. Se il feed e' vuoto, imposta HERMES_VIDEO_LIBRARY_PATH sul server."
    else -> "Controlla API key, gateway URL e log del terminale hermes-hub."
}

internal suspend fun loadHubMemory(settings: AppSettings, apiKey: String?): Pair<HubMemoryState, String> = withContext(Dispatchers.IO) {
    try {
        val body = httpGet(resolveHermesUrl(settings, "/v1/hub/memory"), apiKey, allowCompatAuth = false)
        val root = JSONObject(body)
        if (root.has("error")) return@withContext HubMemoryState() to "Memoria gateway non esposta: ${extractHumanError(body)}"
        val categories = root.optJSONObject("categories") ?: JSONObject()
        HubMemoryState(
            videoPreferences = categories.optString("video_preferences"),
            newsPreferences = categories.optString("news_preferences"),
            responseStyle = categories.optString("response_style"),
            projectRules = categories.optString("project_rules"),
            generalNotes = categories.optString("general_notes")
        ) to "Memoria caricata da gateway."
    } catch (ex: Exception) {
        HubMemoryState() to "Memoria gateway non esposta: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun saveHubMemory(settings: AppSettings, memory: HubMemoryState, apiKey: String?): String = withContext(Dispatchers.IO) {
    try {
        val payload = JSONObject().put(
            "categories",
            JSONObject()
                .put("video_preferences", memory.videoPreferences)
                .put("news_preferences", memory.newsPreferences)
                .put("response_style", memory.responseStyle)
                .put("project_rules", memory.projectRules)
                .put("general_notes", memory.generalNotes)
        )
        val response = postJson(resolveHermesUrl(settings, "/v1/hub/memory"), payload, apiKey, "PATCH", allowCompatAuth = false)
        if (response.first in 200..299) "Memoria salvata sul gateway." else "Memoria gateway non esposta: HTTP ${response.first} ${extractHumanError(response.second)}"
    } catch (ex: Exception) {
        "Memoria gateway non esposta: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun postHubState(settings: AppSettings, kind: String, entityId: String, payload: JSONObject, apiKey: String?): String = withContext(Dispatchers.IO) {
    try {
        val body = JSONObject()
            .put("kind", kind)
            .put("entity_id", entityId)
            .put("project_id", if (settings.activeProjectId.isBlank()) JSONObject.NULL else settings.activeProjectId)
            .put("project_name", if (settings.activeProjectName.isBlank()) JSONObject.NULL else settings.activeProjectName)
            .put("payload", payload)
        val response = postJson(resolveHermesUrl(settings, "/v1/hub/state"), body, apiKey, allowCompatAuth = false)
        if (response.first in 200..299) "Sincronizzato con Hub State." else "Hub State non disponibile: HTTP ${response.first}"
    } catch (ex: Exception) {
        "Hub State non disponibile: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun syncConversationsToHub(context: Context, settings: AppSettings, apiKey: String?): String = withContext(Dispatchers.IO) {
    try {
        val items = conversationsToJsonArray(loadConversations(context, includeDeleted = true))
        val payload = JSONObject().put("items", items)
        val response = postJson(resolveHermesUrl(settings, "/v1/hub/conversations/import"), payload, apiKey, allowCompatAuth = false)
        if (response.first !in 200..299) {
            return@withContext "Archivio server non disponibile: HTTP ${response.first} ${extractHumanError(response.second)}"
        }
        val merged = runCatching { JSONObject(response.second).optInt("merged", items.length()) }.getOrDefault(items.length())
        "Archivio caricato sul gateway: $merged elementi aggiornati."
    } catch (ex: Exception) {
        "Archivio server non disponibile: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun restoreConversationsFromHub(
    context: Context,
    settings: AppSettings,
    apiKey: String?,
    syncAfterSave: Boolean = true
): String = withContext(Dispatchers.IO) {
    try {
        val response = httpGetResponse(resolveHermesUrl(settings, "/v1/hub/conversations"), apiKey, allowCompatAuth = false)
        if (response.first !in 200..299) {
            return@withContext "Archivio server non disponibile: HTTP ${response.first} ${extractHumanError(response.second)}"
        }
        val remote = readConversationsFromJsonArray(JSONObject(response.second).optJSONArray("items") ?: JSONArray())
        if (remote.isEmpty()) {
            return@withContext "Archivio server vuoto."
        }
        synchronized(localArchiveLock) {
            val byId = loadConversations(context, includeDeleted = true).associateBy { it.id }.toMutableMap()
            // Chat collegate desktop: unisci i messaggi invece di sostituire
            // l'entita intera (telefono+desktop scrivono lo stesso id).
            val linkedIds = loadBotLinks(context).values.toSet()
            remote.forEach { incoming ->
                val existing = byId[incoming.id]
                if (existing == null) {
                    byId[incoming.id] = incoming
                } else if (incoming.id in linkedIds) {
                    byId[incoming.id] = incoming.copy(
                        messages = unionChatMessages(existing.messages, incoming.messages),
                        updatedAt = maxOf(existing.updatedAt, incoming.updatedAt)
                    )
                } else if (incoming.updatedAt >= existing.updatedAt) {
                    byId[incoming.id] = incoming
                }
            }
            saveConversations(context, byId.values.toList(), syncAfterSave)
        }
        "Archivio scaricato dal gateway: ${remote.size} chat disponibili."
    } catch (ex: Exception) {
        "Archivio server non disponibile: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun syncRemoteTasks(settings: AppSettings, apiKey: String?): Pair<List<AgentTask>, String> = withContext(Dispatchers.IO) {
    try {
        val body = httpGet(resolveHermesUrl(settings, "/api/jobs"), apiKey)
        val array = findWorkspaceJobsArray(body)
        val tasks = buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.extractString("id") ?: obj.extractString("job_id") ?: "job_${i}_${System.currentTimeMillis()}"
                add(
                    AgentTask(
                        id = "remote_$id",
                        remoteId = id,
                        title = obj.optString("title", "Hermes job $id"),
                        mode = "Job",
                        status = obj.optString("status", "sincronizzato"),
                        detail = obj.optString("instructions", obj.optString("detail", obj.toString().limitText(600))),
                        requiresApproval = obj.optBoolean("requiresApproval", obj.optBoolean("approvalRequired", false)),
                        source = "Hermes Jobs",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }.sortedByDescending { it.updatedAt }
        tasks to "Sincronizzati ${tasks.size} job da Hermes."
    } catch (ex: Exception) {
        emptyList<AgentTask>() to "Sync Jobs fallita: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun supportsResponsesApi(settings: AppSettings, apiKey: String?): Boolean = withContext(Dispatchers.IO) {
    try {
        val body = httpGet(resolveHermesUrl(settings, "/v1/capabilities"), apiKey)
        body.isBlank() || body.contains("responses", ignoreCase = true)
    } catch (_: Exception) {
        false
    }
}

internal fun hermesRoot(settings: AppSettings): String {
    val api = settings.gatewayUrl.trimEnd('/')
    return if (api.endsWith("/v1", ignoreCase = true)) api.removeSuffix("/v1") else api
}

internal fun hasConfiguredHermesEndpoint(settings: AppSettings): Boolean {
    return runCatching {
        val uri = URI(settings.gatewayUrl.trim())
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

internal val OPERATOR_PRESETS = listOf(
    OperatorPreset("Dashboard", "Health", "GET /health", ""),
    OperatorPreset("Dashboard", "Health detailed", "GET /health/detailed", ""),
    OperatorPreset("Dashboard", "Capabilities", "GET /v1/capabilities", ""),
    OperatorPreset("Modelli", "Lista modelli", "GET /v1/models", ""),
    OperatorPreset("Tecnico", "Crea run", "POST /v1/runs", "{\"model\":\"hermes-agent\",\"input\":\"Controlla stato operativo e riassumi.\"}"),
    OperatorPreset("Cron", "Lista cron", "GET /api/jobs", ""),
    OperatorPreset("Cron", "Crea cron", "POST /api/jobs", "{\"name\":\"Controllo operativo\",\"schedule\":\"0 8 * * *\",\"prompt\":\"Controlla stato Hermes e segnala problemi.\"}")
)

internal fun String.limitText(maxLength: Int): String {
    return if (length <= maxLength) this else take(maxLength) + "..."
}

internal fun String.jsonEscaped(): String {
    return replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
}

