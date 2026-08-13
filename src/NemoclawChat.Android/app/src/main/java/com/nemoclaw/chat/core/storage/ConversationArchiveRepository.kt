package com.nemoclaw.chat

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

internal val localArchiveLock = Any()
internal const val CURRENT_ARCHIVE_PREFS = "chatclaw_archive"
internal const val LEGACY_ARCHIVE_PREFS = "nemoclaw_archive"
internal const val STREAMING_CHECKPOINT_MAX_CHARS = 50_000
internal const val UNTITLED_CHAT_TITLE = "Nuova chat"
internal const val DELETED_CONVERSATION_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
internal const val VISUAL_BLOCKS_MAX_BLOCKS = 20
internal fun loadArchiveItems(context: Context): List<ArchiveItem> {
    return loadConversations(context).map {
        ArchiveItem(
            id = it.id,
            title = it.title,
            kind = it.kind,
            description = it.description.ifBlank { "Ultimo aggiornamento locale." },
            prompt = it.prompt
        )
    }
}

internal fun loadConversation(context: Context, id: String): LocalConversation? {
    return loadConversations(context).firstOrNull { it.id == id }
}

internal fun loadConversations(context: Context, includeDeleted: Boolean = false): List<LocalConversation> {
    synchronized(localArchiveLock) {
        val prefs = migratePrefs(context, CURRENT_ARCHIVE_PREFS, LEGACY_ARCHIVE_PREFS)
        val raw = prefs.getString("items", "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            val conversations = buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(
                        LocalConversation(
                            id = obj.optString("id"),
                            title = obj.optString("title", "Nuova chat"),
                            kind = obj.optString("kind", "Chat"),
                            description = obj.optString("description"),
                            prompt = obj.optString("prompt"),
                            updatedAt = obj.optLong("updatedAt"),
                            messages = readMessages(obj.optJSONArray("messages") ?: JSONArray()),
                            previousResponseId = obj.optString("previousResponseId").takeIf { it.isNotBlank() },
                            serverConversationId = obj.optString("serverConversationId").takeIf { it.isNotBlank() },
                            projectId = obj.optString("projectId", obj.optString("project_id")),
                            workspacePath = obj.optString("workspacePath", obj.optString("workspace_path")),
                            repositoryUrl = obj.optString("repositoryUrl", obj.optString("repository_url")),
                            projectInstructions = obj.optString("projectInstructions", obj.optString("project_instructions")),
                            projectMemory = obj.optString("projectMemory", obj.optString("project_memory")),
                            authorizedTools = obj.optJSONArray("authorizedTools")?.toStringList()
                                ?: obj.optJSONArray("authorized_tools")?.toStringList()
                                ?: emptyList(),
                            artifactType = obj.optString("artifactType", obj.optString("artifact_type")),
                            artifactUrl = obj.optString("artifactUrl", obj.optString("artifact_url")),
                            artifactFileName = obj.optString("artifactFileName", obj.optString("artifact_file_name")),
                            artifactMimeType = obj.optString("artifactMimeType", obj.optString("artifact_mime_type")),
                            sourceConversationId = obj.optString("sourceConversationId", obj.optString("source_conversation_id")),
                            sourceRunId = obj.optString("sourceRunId", obj.optString("source_run_id")),
                            version = obj.optInt("version", 0),
                            tags = obj.optJSONArray("tags")?.toStringList() ?: emptyList(),
                            folder = obj.optString("folder"),
                            summary = obj.optString("summary"),
                            parentConversationId = obj.optString("parentConversationId", obj.optString("parent_conversation_id")),
                            branchFromMessageId = obj.optString("branchFromMessageId", obj.optString("branch_from_message_id")),
                            linkedConversationIds = obj.optJSONArray("linkedConversationIds")?.toStringList()
                                ?: obj.optJSONArray("linked_conversation_ids")?.toStringList()
                                ?: emptyList(),
                            deletedAt = obj.optLong("deletedAt").takeIf { it > 0 }
                                ?: obj.optLong("deleted_at").takeIf { it > 0 }
                        )
                    )
                }
            }
            if (archiveContainsRawToolPayload(array)) {
                prefs.edit(commit = true) { putString("items", conversationsToJsonArray(conversations).toString()) }
            }
            conversations.filter { includeDeleted || it.deletedAt == null }.sortedByDescending { it.updatedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

private fun archiveContainsRawToolPayload(conversations: JSONArray): Boolean {
    for (conversationIndex in 0 until conversations.length()) {
        val messages = conversations.optJSONObject(conversationIndex)?.optJSONArray("messages") ?: continue
        for (messageIndex in 0 until messages.length()) {
            val message = messages.optJSONObject(messageIndex) ?: continue
            val timeline = message.optJSONArray("activityTimeline") ?: JSONArray()
            for (itemIndex in 0 until timeline.length()) {
                val item = timeline.optJSONObject(itemIndex) ?: continue
                val nestedTool = item.optJSONObject("tool") ?: item.optJSONObject("Tool")
                val arguments = nestedTool?.optString("args")
                    ?: item.optString("toolArguments", item.optString("ToolArguments"))
                val result = nestedTool?.optString("result")
                    ?: item.optString("toolResult", item.optString("ToolResult"))
                if (!arguments.isNullOrBlank() && arguments != safeToolPayloadSummary(arguments, result = false)) return true
                if (!result.isNullOrBlank() && result != safeToolPayloadSummary(result, result = true)) return true
            }
            val rawEvents = message.optJSONArray("rawEvents") ?: message.optJSONArray("RawEvents") ?: JSONArray()
            for (rawIndex in 0 until rawEvents.length()) {
                val rawEvent = rawEvents.optJSONObject(rawIndex) ?: continue
                if (rawEvent.optString("name") != SAFE_RAW_EVENT_NAME ||
                    rawEvent.optString("json") != SAFE_RAW_EVENT_JSON) return true
            }
        }
    }
    return false
}

internal fun saveConversations(context: Context, conversations: List<LocalConversation>, syncAfterSave: Boolean = true) {
    synchronized(localArchiveLock) {
        context.getSharedPreferences(CURRENT_ARCHIVE_PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putString("items", conversationsToJsonArray(conversations).toString())
        }
    }
    if (syncAfterSave) {
        ConversationArchiveAutoSync.scheduleUpload(context)
    }
}

internal fun conversationsToJsonArray(conversations: List<LocalConversation>): JSONArray {
    val array = JSONArray()
    val deletedCutoff = System.currentTimeMillis() - DELETED_CONVERSATION_RETENTION_MS
    conversations
        .sortedByDescending { it.updatedAt }
        .let { items ->
            items.filter { it.deletedAt == null } +
                items.filter { it.deletedAt != null && it.deletedAt >= deletedCutoff }
        }
        .forEach { conversation ->
            array.put(
                JSONObject()
                    .put("id", conversation.id)
                    .put("title", conversation.title)
                    .put("kind", conversation.kind)
                    .put("description", conversation.description)
                    .put("prompt", conversation.prompt)
                    .put("updatedAt", conversation.updatedAt)
                    .put("deletedAt", conversation.deletedAt ?: JSONObject.NULL)
                    .put("previousResponseId", conversation.previousResponseId ?: JSONObject.NULL)
                    .put("serverConversationId", conversation.serverConversationId ?: JSONObject.NULL)
                    .put("projectId", conversation.projectId.ifBlank { JSONObject.NULL })
                    .put("workspacePath", conversation.workspacePath.ifBlank { JSONObject.NULL })
                    .put("repositoryUrl", conversation.repositoryUrl.ifBlank { JSONObject.NULL })
                    .put("projectInstructions", conversation.projectInstructions.ifBlank { JSONObject.NULL })
                    .put("projectMemory", conversation.projectMemory.ifBlank { JSONObject.NULL })
                    .put("authorizedTools", JSONArray(conversation.authorizedTools))
                    .put("artifactType", conversation.artifactType.ifBlank { JSONObject.NULL })
                    .put("artifactUrl", conversation.artifactUrl.ifBlank { JSONObject.NULL })
                    .put("artifactFileName", conversation.artifactFileName.ifBlank { JSONObject.NULL })
                    .put("artifactMimeType", conversation.artifactMimeType.ifBlank { JSONObject.NULL })
                    .put("sourceConversationId", conversation.sourceConversationId.ifBlank { JSONObject.NULL })
                    .put("sourceRunId", conversation.sourceRunId.ifBlank { JSONObject.NULL })
                    .put("version", conversation.version)
                    .put("tags", JSONArray(conversation.tags))
                    .put("folder", conversation.folder.ifBlank { JSONObject.NULL })
                    .put("summary", conversation.summary.ifBlank { JSONObject.NULL })
                    .put("parentConversationId", conversation.parentConversationId.ifBlank { JSONObject.NULL })
                    .put("branchFromMessageId", conversation.branchFromMessageId.ifBlank { JSONObject.NULL })
                    .put("linkedConversationIds", JSONArray(conversation.linkedConversationIds))
                    .put("messages", writeMessages(conversation.messages))
            )
        }
    return array
}

internal fun readConversationsFromJsonArray(array: JSONArray): List<LocalConversation> {
    return buildList {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            add(
                LocalConversation(
                    id = id,
                    title = obj.optString("title", "Nuova chat"),
                    kind = obj.optString("kind", "Chat"),
                    description = obj.optString("description"),
                    prompt = obj.optString("prompt"),
                    updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                    messages = readMessages(obj.optJSONArray("messages") ?: JSONArray()),
                    previousResponseId = obj.optString("previousResponseId").takeIf { it.isNotBlank() },
                    serverConversationId = obj.optString("serverConversationId").takeIf { it.isNotBlank() },
                    projectId = obj.optString("projectId", obj.optString("project_id")),
                    workspacePath = obj.optString("workspacePath", obj.optString("workspace_path")),
                    repositoryUrl = obj.optString("repositoryUrl", obj.optString("repository_url")),
                    projectInstructions = obj.optString("projectInstructions", obj.optString("project_instructions")),
                    projectMemory = obj.optString("projectMemory", obj.optString("project_memory")),
                    authorizedTools = obj.optJSONArray("authorizedTools")?.toStringList()
                        ?: obj.optJSONArray("authorized_tools")?.toStringList()
                        ?: emptyList(),
                    artifactType = obj.optString("artifactType", obj.optString("artifact_type")),
                    artifactUrl = obj.optString("artifactUrl", obj.optString("artifact_url")),
                    artifactFileName = obj.optString("artifactFileName", obj.optString("artifact_file_name")),
                    artifactMimeType = obj.optString("artifactMimeType", obj.optString("artifact_mime_type")),
                    sourceConversationId = obj.optString("sourceConversationId", obj.optString("source_conversation_id")),
                    sourceRunId = obj.optString("sourceRunId", obj.optString("source_run_id")),
                    version = obj.optInt("version", 0),
                    tags = obj.optJSONArray("tags")?.toStringList() ?: emptyList(),
                    folder = obj.optString("folder"),
                    summary = obj.optString("summary"),
                    parentConversationId = obj.optString("parentConversationId", obj.optString("parent_conversation_id")),
                    branchFromMessageId = obj.optString("branchFromMessageId", obj.optString("branch_from_message_id")),
                    linkedConversationIds = obj.optJSONArray("linkedConversationIds")?.toStringList()
                        ?: obj.optJSONArray("linked_conversation_ids")?.toStringList()
                        ?: emptyList(),
                    deletedAt = obj.optLong("deletedAt").takeIf { it > 0 }
                        ?: obj.optLong("deleted_at").takeIf { it > 0 }
                )
            )
        }
    }.sortedByDescending { it.updatedAt }
}

private fun readMessages(array: JSONArray): List<ChatMessage> {
    return buildList {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val storedId = obj.optString("id").takeIf { it.isNotBlank() }
            add(
                ChatMessage(
                    author = obj.optString("author"),
                    text = obj.optString("text"),
                    fromUser = obj.optBoolean("fromUser"),
                    isAction = obj.optBoolean("isAction", false),
                    thinking = obj.optString("thinking"),
                    activityTimeline = readAssistantActivityTimeline(obj.optJSONArray("activityTimeline") ?: JSONArray()),
                    visualBlocksVersion = obj.optNullableInt("visualBlocksVersion"),
                    visualBlocks = readVisualBlocks(obj.optJSONArray("visualBlocks") ?: JSONArray()),
                    stats = readChatStats(obj.optJSONObject("stats")),
                    rawEvents = readRawEvents(obj.optJSONArray("rawEvents") ?: obj.optJSONArray("RawEvents") ?: JSONArray()),
                    id = storedId ?: java.util.UUID.randomUUID().toString(),
                    isBookmarked = obj.optBoolean("bookmarked", obj.optBoolean("isBookmarked", false))
                )
            )
        }
    }
}

private fun writeMessages(messages: List<ChatMessage>): JSONArray {
    val array = JSONArray()
    messages.forEach { message ->
        array.put(
            JSONObject()
                .put("id", message.id)
                .put("author", message.author)
                .put("text", message.text)
                .put("fromUser", message.fromUser)
                .put("isAction", message.isAction)
                .put("thinking", message.thinking)
                .put("activityTimeline", writeAssistantActivityTimeline(message.activityTimeline))
                .put("visualBlocksVersion", message.visualBlocksVersion ?: JSONObject.NULL)
                .put("visualBlocks", writeVisualBlocks(message.visualBlocks))
                .put("stats", writeChatStats(message.stats) ?: JSONObject.NULL)
                .put("rawEvents", writeRawEvents(message.rawEvents))
                .put("bookmarked", message.isBookmarked)
        )
    }
    return array
}

private fun readRawEvents(array: JSONArray): List<HermesRawEvent> = buildList {
    for (i in 0 until minOf(array.length(), 80)) {
        val obj = array.optJSONObject(i) ?: continue
        add(
            safeRawHermesEvent(obj.optLong("timestamp", obj.optLong("Timestamp", System.currentTimeMillis())))
        )
    }
}

private fun writeRawEvents(events: List<HermesRawEvent>): JSONArray {
    return JSONArray(events.take(80).map { event ->
        val safeEvent = safeRawHermesEvent(event.timestamp)
        JSONObject()
            .put("name", safeEvent.name)
            .put("json", safeEvent.json)
            .put("timestamp", safeEvent.timestamp)
    })
}

private fun readChatStats(obj: JSONObject?): ChatStreamStats? {
    if (obj == null) return null
    return ChatStreamStats(
        ttftMs = obj.optNullableDouble("ttftMs"),
        totalMs = obj.optNullableDouble("totalMs"),
        tokensOut = obj.optNullableInt("tokensOut"),
        tokensPerSecond = obj.optNullableDouble("tokensPerSecond"),
        promptTokens = obj.optNullableInt("promptTokens"),
        contextTokens = obj.optNullableInt("contextTokens"),
        contextLength = obj.optNullableInt("contextLength"),
        contextPercent = obj.optNullableInt("contextPercent")
    ).takeIf {
        it.ttftMs != null || it.totalMs != null || it.tokensOut != null ||
            it.tokensPerSecond != null || it.promptTokens != null ||
            it.contextTokens != null || it.contextLength != null || it.contextPercent != null
    }
}

private fun writeChatStats(stats: ChatStreamStats?): JSONObject? {
    if (stats == null) return null
    return JSONObject()
        .put("ttftMs", stats.ttftMs ?: JSONObject.NULL)
        .put("totalMs", stats.totalMs ?: JSONObject.NULL)
        .put("tokensOut", stats.tokensOut ?: JSONObject.NULL)
        .put("tokensPerSecond", stats.tokensPerSecond ?: JSONObject.NULL)
        .put("promptTokens", stats.promptTokens ?: JSONObject.NULL)
        .put("contextTokens", stats.contextTokens ?: JSONObject.NULL)
        .put("contextLength", stats.contextLength ?: JSONObject.NULL)
        .put("contextPercent", stats.contextPercent ?: JSONObject.NULL)
}

internal fun String.streamingCheckpointPreview(): String {
    if (length <= STREAMING_CHECKPOINT_MAX_CHARS) {
        return this
    }

    return take(STREAMING_CHECKPOINT_MAX_CHARS) +
        "\n\n[checkpoint parziale limitato; risposta completa salvata a fine stream]"
}

private fun JSONObject.optNullableDouble(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return optDouble(name).takeIf { it.isFinite() }
}

private fun JSONObject.optNullableInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name).takeIf { it > 0 }
}

private fun readVisualBlocks(array: JSONArray): List<VisualBlock> = buildList {
    for (i in 0 until minOf(array.length(), VISUAL_BLOCKS_MAX_BLOCKS)) {
        val block = readVisualBlock(array.optJSONObject(i) ?: continue)
        if (block.isValidVisualBlock()) {
            add(block)
        }
    }
}

private fun writeVisualBlocks(blocks: List<VisualBlock>): JSONArray {
    val array = JSONArray()
    blocks.take(VISUAL_BLOCKS_MAX_BLOCKS).filter { it.isValidVisualBlock() }.forEach { block ->
        val obj = JSONObject()
            .put("id", block.id)
            .put("type", block.type)
            .put("title", block.title)
            .put("caption", block.caption)
        when (block.type) {
            "markdown" -> obj.put("text", block.text)
            "code" -> obj.put("language", block.language).put("filename", block.filename).put("code", block.code).put("highlight_lines", JSONArray(block.highlightLines))
            "table" -> obj.put("columns", JSONArray(block.columns.map { JSONObject().put("key", it.key).put("label", it.label).put("align", it.align).put("format", it.format).put("sortable", it.sortable) })).put("rows", JSONArray(block.rows.map { row -> JSONObject(row) }))
            "chart" -> obj.put("chart_type", block.chartType).put("x_label", block.xLabel).put("y_label", block.yLabel).put("unit", block.unit).put("summary", block.summary).put("series", JSONArray(block.series.map { series -> JSONObject().put("name", series.name).put("points", JSONArray(series.points.map { point -> JSONObject().put("x", point.x).put("y", point.y) })) }))
            "diagram" -> obj.put("source_format", block.sourceFormat).put("source", block.source).put("rendered_media_url", block.renderedMediaUrl).put("alt", block.alt)
            "image_gallery" -> obj.put("layout", block.layout).put("images", JSONArray(block.images.map { image -> JSONObject().put("media_url", image.mediaUrl).put("alt", image.alt).put("caption", image.caption) }))
            "media_file" -> obj.put("media_url", block.mediaUrl).put("media_kind", block.mediaKind).put("mime_type", block.mimeType).put("filename", block.filename).put("size_bytes", block.sizeBytes ?: JSONObject.NULL).put("duration_ms", block.durationMs ?: JSONObject.NULL).put("thumbnail_url", block.thumbnailUrl).put("local_data_url", block.localDataUrl).put("alt", block.alt)
            "callout" -> obj.put("variant", block.variant).put("text", block.text)
            "unknown_block" -> obj.put("raw_json", block.rawJson)
        }
        array.put(obj)
    }
    return array
}

internal val MULTI_WHITESPACE_REGEX = Regex("\\s+")
internal val BIDI_CONTROL_CHARS = setOf(
    '\u200E', '\u200F',
    '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
    '\u2066', '\u2067', '\u2068', '\u2069'
)

internal fun makeTitle(prompt: String): String {
    val cleaned = prompt
        .replace('\u00A0', ' ')
        .filterNot { it in BIDI_CONTROL_CHARS }
        .lines()
        .joinToString(" ") { it.trim() }
        .filter { ch -> ch.isLetterOrDigit() || ch.isWhitespace() || ch in "_-.,:;?!()[]\"'/\\@#%&*+=" }
        .replace(MULTI_WHITESPACE_REGEX, " ")
        .trim()
    if (cleaned.isEmpty()) return "Nuova richiesta"
    return if (cleaned.length <= 46) cleaned else cleaned.take(46).trimEnd() + "..."
}
