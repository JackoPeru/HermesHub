package com.nemoclaw.chat

import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

internal fun loadWorkspaceRequests(context: Context, kind: String): List<WorkspaceRequest> {
    val raw = context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE).getString("items", "[]") ?: "[]"
    return try {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(
                    WorkspaceRequest(
                        id = obj.optString("id"),
                        kind = obj.optString("kind"),
                        title = obj.optString("title", "Nuova richiesta"),
                        prompt = obj.optString("prompt"),
                        result = obj.optString("result"),
                        source = obj.optString("source"),
                        status = obj.optString("status"),
                        remoteId = obj.optString("remoteId").ifBlank { null },
                        streamUrl = obj.optString("streamUrl"),
                        downloadUrl = obj.optString("downloadUrl"),
                        feedback = obj.optString("feedback"),
                        updatedAt = obj.optLong("updatedAt")
                    )
                )
            }
        }
            .filter { it.kind.equals(kind, ignoreCase = true) }
            .sortedByDescending { it.updatedAt }
            .take(80)
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun saveWorkspaceRequest(
    context: Context,
    kind: String,
    prompt: String,
    result: String,
    source: String,
    status: String,
    remoteId: String? = null,
    title: String = makeTitle(prompt),
    streamUrl: String = "",
    downloadUrl: String = "",
    feedback: String = ""
) {
    val prefs = context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE)
    val existingRaw = prefs.getString("items", "[]") ?: "[]"
    val existing = try {
        JSONArray(existingRaw)
    } catch (_: Exception) {
        JSONArray()
    }
    val now = System.currentTimeMillis()
    val all = mutableListOf<JSONObject>()
    all.add(
        JSONObject()
            .put("id", "workspace_$now")
            .put("kind", kind)
            .put("title", title)
            .put("prompt", prompt)
            .put("result", result)
            .put("source", source)
            .put("status", status)
            .put("remoteId", remoteId ?: JSONObject.NULL)
            .put("streamUrl", streamUrl)
            .put("downloadUrl", downloadUrl)
            .put("feedback", feedback)
            .put("updatedAt", now)
    )
    for (i in 0 until existing.length()) {
        existing.optJSONObject(i)?.let { all.add(it) }
    }
    val array = JSONArray()
    all.sortedByDescending { it.optLong("updatedAt") }.take(200).forEach { array.put(it) }
    prefs.edit { putString("items", array.toString()) }
}

internal fun saveWorkspaceFeedback(context: Context, id: String, feedback: String, status: String) {
    val prefs = context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE)
    val existing = try {
        JSONArray(prefs.getString("items", "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }
    val array = JSONArray()
    for (i in 0 until existing.length()) {
        val obj = existing.optJSONObject(i) ?: continue
        if (obj.optString("id") == id) {
            obj.put("feedback", feedback)
            obj.put("status", status)
            obj.put("updatedAt", System.currentTimeMillis())
        }
        array.put(obj)
    }
    prefs.edit { putString("items", array.toString()) }
}

internal suspend fun syncWorkspaceJobs(context: Context, settings: AppSettings, kind: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    try {
        val body = httpGet("${hermesRoot(settings)}/api/jobs", apiKey)
        val array = findWorkspaceJobsArray(body)
        var imported = 0
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val text = obj.toString()
            if (!text.contains(kind, ignoreCase = true) && !text.contains("\"workspace\":\"${kind.lowercase()}\"", ignoreCase = true)) continue
            val artifact = parseWorkspaceArtifact(kind, text)
            saveWorkspaceRequest(
                context = context,
                kind = kind,
                prompt = obj.optString("instructions", obj.optString("detail", artifact.result)),
                result = artifact.result.ifBlank { obj.optString("summary", text.limitText(900)) },
                source = "Hermes Jobs",
                status = artifact.status.ifBlank { obj.optString("status", "Sincronizzato da Hermes.") },
                remoteId = extractTaskId(text),
                title = artifact.title.ifBlank { obj.optString("title", "$kind Hermes") },
                streamUrl = artifact.streamUrl,
                downloadUrl = artifact.downloadUrl
            )
            imported++
        }
        "Sincronizzazione completata: $imported elementi $kind importati/aggiornati."
    } catch (ex: Exception) {
        "Sincronizzazione fallita: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun sendWorkspaceFeedback(settings: AppSettings, item: WorkspaceRequest, feedback: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    val instructions = """
        Feedback utente per ${item.kind} '${item.title}':
        $feedback

        Aggiorna il contenuto rispettando preferenze utente. Se e' Video, migliora editing/contenuto e mantieni output sul PC con stream_url/download_url. Se e' News, aggiorna articolo e fonti.
        Se il feedback contiene una preferenza stabile, salvala o incorporala nella memoria agente condivisa Hermes/CLI/app, non solo in questo item.
        Job originale: ${item.remoteId ?: "non disponibile"}
    """.trimIndent()
    try {
        postHubState(
            settings,
            "${item.kind.lowercase()}_feedback",
            item.id,
            JSONObject()
                .put("title", item.title)
                .put("feedback", feedback)
                .put("read", true)
                .put("status", item.status),
            apiKey
        )
        if (item.remoteId != null) {
            val response = postJson(
                "${hermesRoot(settings)}/api/jobs/${URLEncoder.encode(item.remoteId, "UTF-8")}",
                JSONObject().put("feedback", feedback).put("instructions", instructions),
                apiKey,
                "PATCH"
            )
            if (response.first in 200..299) {
                return@withContext "Feedback inviato a Hermes Jobs."
            }
        }
        val result = sendWorkspaceRunRequest(settings, item.kind, instructions, apiKey)
        "Feedback inviato a Hermes: ${result.status}"
    } catch (ex: Exception) {
        "Feedback salvato localmente; invio Hermes fallito: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal fun loadVideoFeedback(context: Context, id: String): String {
    val prefs = context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString("video_feedback", "{}") ?: "{}"
    return try {
        JSONObject(raw).optJSONObject(id)?.optString("feedback").orEmpty()
    } catch (_: Exception) {
        ""
    }
}

internal fun loadVideoReaction(context: Context, id: String): String {
    val prefs = context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString("video_feedback", "{}") ?: "{}"
    return try {
        JSONObject(raw).optJSONObject(id)?.optString("reaction").orEmpty()
    } catch (_: Exception) {
        ""
    }
}

internal fun saveVideoFeedback(context: Context, id: String, feedback: String, reaction: String, status: String) {
    val prefs = context.getSharedPreferences(CURRENT_WORKSPACE_PREFS, Context.MODE_PRIVATE)
    val root = try {
        JSONObject(prefs.getString("video_feedback", "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }
    root.put(
        id,
        JSONObject()
            .put("feedback", feedback)
            .put("reaction", reaction)
            .put("status", status)
            .put("updatedAt", System.currentTimeMillis())
    )
    prefs.edit { putString("video_feedback", root.toString()) }
}

internal suspend fun sendVideoLibraryFeedback(settings: AppSettings, item: VideoLibraryItem, feedback: String, reaction: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    val reactionLabel = when (reaction) {
        "like" -> "like / mi piace"
        "dislike" -> "dislike / non mi piace"
        else -> "nessuna reazione rapida"
    }
    val instructions = """
        Feedback editoriale su video Hermes Hub.
        Video: ${item.title}
        File: ${item.filename}
        Path server: ${item.path}
        Reazione rapida: $reactionLabel

        Commento utente:
        ${feedback.ifBlank { "nessun commento scritto" }}

        Interpreta like/dislike come feedback primario rapido. Il commento scritto e' un rinforzo qualitativo opzionale.
        Usa questo feedback come memoria editoriale condivisa Hermes/CLI/app quando e' stabile.
        Nei video futuri migliora ritmo, hook, chiarezza, montaggio, durata, tono, musica, voce e struttura in base a queste note.
        Non creare un nuovo video ora a meno che l'utente lo chieda esplicitamente.
    """.trimIndent()
    val payload = JSONObject()
        .put("model", settings.model)
        .put("input", instructions)
        .put(
            "metadata",
            JSONObject()
                .put("client", "hermes-hub")
                .put("client_surface", "android-app")
                .put("workspace", "video")
                .put("source", "video-feedback")
                .put("memory_scope", "shared-hermes-agent-memory")
                .put("share_with_cli", true)
        )
    if (settings.provider.isNotBlank() && !settings.provider.equals("hermes-agent", ignoreCase = true)) {
        payload.put("provider", settings.provider)
    }
    buildHermesModelOptions(settings.reasoningEffort, settings.serviceTier, capabilities = null)?.let {
        payload.put("model_options", it)
    }
    try {
        postHubState(
            settings,
            "video_feedback",
            item.id,
            JSONObject()
                .put("title", item.title)
                .put("filename", item.filename)
                .put("feedback", feedback)
                .put("reaction", reaction)
                .put("path", item.path),
            apiKey
        )
        val run = postJson(resolveHermesUrl(settings, "/v1/runs"), payload, apiKey)
        if (run.first in 200..299) {
            return@withContext "Feedback inviato a Hermes."
        }
        val chatPayload = JSONObject()
            .put("model", settings.model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", hermesHubAgentInstructions() + projectContextInstructions(settings)))
                    .put(JSONObject().put("role", "user").put("content", instructions))
            )
            .put("stream", false)
            .put("metadata", payload.getJSONObject("metadata"))
        val chat = postJson(resolveHermesUrl(settings, "/v1/chat/completions"), chatPayload, apiKey)
        if (chat.first in 200..299) "Feedback inviato a Hermes." else "Feedback salvato localmente; Hermes HTTP ${chat.first}: ${extractHumanError(chat.second)}"
    } catch (ex: Exception) {
        "Feedback salvato localmente; invio Hermes fallito: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun runWorkspaceJobAction(settings: AppSettings, item: WorkspaceRequest, action: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    val id = item.remoteId ?: return@withContext "Nessun job Hermes collegato."
    return@withContext try {
        val response = postJson("${hermesRoot(settings)}/api/jobs/${URLEncoder.encode(id, "UTF-8")}/${URLEncoder.encode(action, "UTF-8")}", JSONObject(), apiKey)
        if (response.first in 200..299) "Job Hermes aggiornato." else "Hermes HTTP ${response.first}: ${extractHumanError(response.second)}"
    } catch (ex: Exception) {
        "Azione job fallita: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

