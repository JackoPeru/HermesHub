package com.nemoclaw.chat

import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject


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


