package com.nemoclaw.chat

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal fun copyArchiveToClipboard(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("chatclaw-archive", exportArchiveText(context)))
}

internal fun importArchiveFromClipboard(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text = clipboard.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        ?.takeIf { it.isNotBlank() }
        ?: return "Appunti vuoti."
    val imported = parseArchiveExportText(text)
    if (imported.isEmpty()) {
        return "Nessuna chat riconosciuta negli appunti."
    }
    val current = loadConversations(context)
    val seenIds = current.map { it.id }.toMutableSet()
    val seen = current.map { "${it.title}\n${it.prompt}" }.toMutableSet()
    val merged = current.toMutableList()
    var added = 0
    imported.forEach { conversation ->
        // Dedup su id e su title+prompt: re-import dello stesso export non duplica.
        if (!seenIds.add(conversation.id)) return@forEach
        if (seen.add("${conversation.title}\n${conversation.prompt}")) {
            merged.add(conversation)
            added++
        }
    }
    if (added > 0) {
        saveConversations(context, merged)
    }
    return "Import da appunti: $added chat aggiunte."
}

internal fun exportArchiveText(context: Context): String {
    val conversations = loadConversations(context)
    if (conversations.isEmpty()) {
        return "Archivio Hermes Hub vuoto."
    }

    return conversations.joinToString("\n\n") { conversation ->
        val messages = conversation.messages.joinToString("\n") { message ->
            "${message.author}: ${message.text}"
        }
        "## ${conversation.title}\nTipo: ${conversation.kind}\nPrompt: ${conversation.prompt}\n$messages"
    }
}

// Kind ammessi in import da appunti: tutto il resto e' normalizzato a Chat (fail-closed).
internal val ARCHIVE_IMPORT_KINDS = setOf("Chat", "Task", "Progetto", "Artifact")

internal fun parseArchiveExportText(text: String): List<LocalConversation> {
    val blocks = Regex("(?m)^## ").split(text).drop(1)
    val now = System.currentTimeMillis()
    return blocks.mapIndexedNotNull { index, block ->
        val lines = block.lines()
        // Titolo obbligatorio e limitato: blocchi senza titolo sono scartati (niente conversazioni corrotte).
        val title = lines.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.take(180) ?: return@mapIndexedNotNull null
        var kind = "Chat"
        var prompt = ""
        var botIdentityKey = ""
        val messages = mutableListOf<ChatMessage>()
        var currentAuthor: String? = null
        val currentText = StringBuilder()

        fun flushMessage() {
            val author = currentAuthor?.trim()?.takeIf { it.isNotBlank() } ?: run {
                currentAuthor = null
                currentText.clear()
                return
            }
            messages.add(
                ChatMessage(
                    author = author.take(40),
                    text = currentText.toString().trimEnd().take(20_000),
                    fromUser = author.equals("Tu", ignoreCase = true) || author.equals("User", ignoreCase = true)
                )
            )
            currentAuthor = null
            currentText.clear()
        }

        lines.drop(1).forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                // Nota: niente spazio obbligatorio dopo ":": trimEnd puo averlo rimosso su righe vuote.
                line.startsWith("Tipo:") -> {
                    val raw = line.removePrefix("Tipo:").trim()
                    kind = raw.takeIf { it in ARCHIVE_IMPORT_KINDS } ?: "Chat"
                }
                line.startsWith("Prompt:") -> prompt = line.removePrefix("Prompt:").trim().take(20_000)
                // Campo opzionale non-breaking: se assente resta "" (compatibile con export attuale).
                line.startsWith("Bot:") -> botIdentityKey = line.removePrefix("Bot:").trim().take(256)
                else -> {
                    val match = Regex("^([^:]{1,40}):\\s?(.*)$").matchEntire(line)
                    if (match != null) {
                        flushMessage()
                        currentAuthor = match.groupValues[1].trim()
                        currentText.append(match.groupValues[2])
                    } else if (currentAuthor != null) {
                        currentText.append('\n').append(line)
                    }
                }
            }
        }
        flushMessage()
        // Blocco malformato (senza prompt e senza messaggi): scartato invece di creare record vuoti.
        if (prompt.isBlank() && messages.isEmpty()) return@mapIndexedNotNull null
        LocalConversation(
            id = "import_${now}_$index",
            title = title,
            kind = kind,
            description = "Importato da export testo.",
            prompt = prompt,
            updatedAt = now - index,
            messages = messages,
            botIdentityKey = botIdentityKey
        )
    }
}

internal suspend fun sendChatRequest(
    settings: AppSettings,
    mode: String,
    prompt: String,
    history: List<ChatMessage>,
    conversationId: String?,
    previousResponseId: String?,
    apiKey: String?
): GatewayChatResult = withContext(Dispatchers.IO) {
    var lastError = "errore sconosciuto"
    val serverConversationId = hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, conversationId)

    if (shouldUseResponsesFirst(settings, mode) && supportsResponsesApi(settings, apiKey)) {
        try {
            val payload = JSONObject()
                .put("model", settings.model)
                .put("input", prompt)
                .put("store", true)
                .put("conversation", serverConversationId ?: JSONObject.NULL)
                .put("previous_response_id", if (serverConversationId == null) previousResponseId ?: JSONObject.NULL else JSONObject.NULL)
                .put("metadata", visualBlocksMetadata(settings, conversationId))
            payload.put(
                "instructions",
                (if (isHermesNative(settings)) hermesNativeInstructions(mode) else if (mode.equals("Agente", ignoreCase = true)) {
                    hermesHubAgentInstructions()
                } else {
                    hermesHubChatInstructions()
                }) + projectContextInstructions(settings)
            )
            val response = postJson(resolveHermesUrl(settings, "/v1/responses"), payload, apiKey, allowCompatAuth = !(isHermesNative(settings) && settings.strictNativeMode))
            if (response.first in 200..299) {
                val text = extractAssistantText(response.second)
                if (text.isNotBlank()) {
                    return@withContext GatewayChatResult(
                        text = text,
                        source = "Hermes",
                        statusMessage = "Risposta ricevuta da Hermes Responses API.",
                        usedFallback = false,
                        responseId = extractResponseId(response.second),
                        visualBlocks = extractVisualBlocks(response.second),
                        visualBlocksVersion = VISUAL_BLOCKS_VERSION
                    )
                }
                lastError = "Hermes Responses API raggiunta ma senza contenuto utile"
            } else {
                lastError = "Responses API HTTP ${response.first}: ${extractHumanError(response.second)}"
            }
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
        }

        if (settings.strictNativeMode && isHermesNative(settings)) {
            return@withContext GatewayChatResult(
                text = "Hermes native non disponibile: $lastError.",
                source = "Errore Hermes Native",
                statusMessage = "Strict native mode: nessun fallback compat eseguito. $lastError",
                usedFallback = false
            )
        }
    }

    try {
        val payload = JSONObject()
            .put("model", settings.model)
            .put("stream", false)
            .put("session_id", serverConversationId ?: JSONObject.NULL)
            .put("metadata", visualBlocksMetadata(settings, conversationId))
            .put("messages", JSONArray().apply {
                if (!isHermesNative(settings)) {
                    put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", (if (mode.equals("Agente", ignoreCase = true)) hermesHubAgentInstructions() else hermesHubChatInstructions()) + projectContextInstructions(settings))
                    )
                }
                val compatHistory = if (isHermesNative(settings)) emptyList() else history
                compatHistory.filter { !it.isAction }.forEach { message ->
                    put(
                        JSONObject()
                            .put("role", if (message.fromUser) "user" else "assistant")
                            .put("content", message.text)
                    )
                }
            })
        val response = postJson(resolveHermesUrl(settings, "/v1/chat/completions"), payload, apiKey, allowCompatAuth = !(isHermesNative(settings) && settings.strictNativeMode), sessionId = serverConversationId)
        if (response.first in 200..299) {
            val text = extractAssistantText(response.second)
            if (text.isNotBlank()) {
                return@withContext GatewayChatResult(
                    text = text,
                    source = "Hermes",
                    statusMessage = "Risposta ricevuta da Hermes Chat Completions.",
                    usedFallback = false,
                    responseId = extractResponseId(response.second),
                    visualBlocks = extractVisualBlocks(response.second),
                    visualBlocksVersion = VISUAL_BLOCKS_VERSION
                )
            }
            lastError = "Hermes Chat Completions raggiunta ma senza contenuto utile"
        } else {
            lastError = "Chat Completions HTTP ${response.first}: ${extractHumanError(response.second)}"
        }
    } catch (ex: Exception) {
        lastError = ex.message ?: ex.javaClass.simpleName
    }

    if (settings.demoMode) {
        GatewayChatResult(
            text = buildFallbackReply(settings, mode, lastError),
            source = "Fallback locale",
            statusMessage = "Hermes non disponibile, uso fallback locale: $lastError.",
            usedFallback = true,
            visualBlocks = if (shouldAttachVisualBlocks(settings, prompt)) visualBlockFixtures() else emptyList(),
            visualBlocksVersion = VISUAL_BLOCKS_VERSION
        )
    } else {
        GatewayChatResult(
            text = "Hermes non raggiungibile: $lastError.",
            source = "Errore Hermes",
            statusMessage = "Invio fallito: $lastError.",
            usedFallback = false,
            visualBlocks = if (shouldAttachVisualBlocks(settings, prompt)) visualBlockFixtures() else emptyList(),
            visualBlocksVersion = VISUAL_BLOCKS_VERSION
        )
    }
}

internal fun readAssistantActivityTimeline(array: JSONArray): List<AssistantActivity> = buildList {
    for (i in 0 until minOf(array.length(), 256)) {
        val item = array.optJSONObject(i) ?: continue
        val kind = when (item.optString("kind", item.optString("Kind")).lowercase()) {
            "reasoning" -> AssistantActivity.Kind.Reasoning
            "progress", "promptprogress" -> AssistantActivity.Kind.PromptProgress
            "tool" -> AssistantActivity.Kind.Tool
            else -> null
        } ?: continue
        // Accept the previous Android nested form and the canonical flat wire form.
        val toolObject = item.optJSONObject("tool") ?: item.optJSONObject("Tool")
        val tool = (toolObject ?: item).let {
            val id = it.optString("id", it.optString("toolId", it.optString("ToolId")))
            if (id.isBlank()) null else ToolCallState(
                id = id,
                name = it.optString("name", it.optString("toolName", it.optString("ToolName", id))),
                args = it.optString("args", it.optString("toolArguments", it.optString("ToolArguments"))),
                status = it.optString("status", it.optString("toolStatus", it.optString("ToolStatus", "in esecuzione…"))),
                result = it.optString("result", it.optString("toolResult", it.optString("ToolResult"))).takeIf(String::isNotEmpty),
                argsPreview = it.optString("argsPreview", it.optString("toolArgsPreview", "")),
                resultPreview = it.optString("resultPreview", it.optString("toolResultPreview", ""))
            )
        }
        if (kind != AssistantActivity.Kind.Tool || tool != null) {
            add(AssistantActivity(kind, item.optString("text", item.optString("Text")), tool?.let(::safeToolCall)))
        }
    }
}

internal fun writeAssistantActivityTimeline(items: List<AssistantActivity>): JSONArray = JSONArray().also { array ->
    items.takeLast(256).forEach { item ->
        val tool = item.tool?.let(::safeToolCall)
        array.put(JSONObject()
            .put("kind", activityKindWireName(item.kind))
            .put("text", item.text)
            .put("toolId", tool?.id ?: JSONObject.NULL)
            .put("toolName", tool?.name ?: JSONObject.NULL)
            .put("toolArguments", tool?.args ?: JSONObject.NULL)
            .put("toolResult", tool?.result ?: JSONObject.NULL)
            .put("toolArgsPreview", tool?.argsPreview.orEmpty())
            .put("toolResultPreview", tool?.resultPreview.orEmpty())
            .put("toolStatus", tool?.status ?: JSONObject.NULL))
    }
}

internal fun activityKindWireName(kind: AssistantActivity.Kind): String = when (kind) {
    AssistantActivity.Kind.Reasoning -> "reasoning"
    AssistantActivity.Kind.PromptProgress -> "progress"
    AssistantActivity.Kind.Tool -> "tool"
}

internal fun normalizeGeneratedConversationTitle(value: String?, fallback: String): String {
    var title = value.orEmpty()
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
        .replace(Regex("^\\s*(?:titolo|title)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        .trim(' ', '\t', '"', '\'', '`', '#', '*', '.', ':', ';', '-', '–', '—')
    if (title.isBlank()) title = fallback
    title = title.replace(MULTI_WHITESPACE_REGEX, " ").trim()
    return if (title.length <= 70) title else title.take(70).trimEnd() + "…"
}

internal fun formatDateTime(millis: Long): String {
    return java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))
}

