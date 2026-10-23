package com.nemoclaw.chat

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume

internal fun parseSseData(eventName: String?, data: String): List<ChatStreamEvent> {
    if (data.isEmpty() || data.trim() == "[DONE]") return emptyList()
    val json = try { JSONObject(data) } catch (_: Exception) {
        return listOf(ChatStreamEvent.TextDelta(data))
    }
    val envelope = HermesHubProtocol.readEventEnvelope(json)
    val parsedEventName = envelope?.type ?: eventName
    val parsed = parseEventObject(parsedEventName, envelope?.payload ?: json)
    val result = mutableListOf<ChatStreamEvent>()
    if (envelope != null) {
        result += ChatStreamEvent.EnvelopeMetadata(
            protocolVersion = envelope.protocolVersion,
            eventId = envelope.eventId,
            sequence = envelope.sequence,
            requestId = envelope.requestId,
            correlationId = envelope.correlationId,
            type = envelope.type,
            sourceType = envelope.sourceType,
            runId = envelope.runId
        )
    }
    if (parsed.isEmpty()) {
        result += ChatStreamEvent.RawHermesEvent(
            name = parsedEventName ?: json.optString("type", "hermes.event"),
            json = json.toString(),
            protocolVersion = envelope?.protocolVersion,
            eventId = envelope?.eventId,
            sequence = envelope?.sequence,
            requestId = envelope?.requestId,
            correlationId = envelope?.correlationId,
            runId = envelope?.runId,
            sourceType = envelope?.sourceType
        )
    } else {
        result += parsed
    }
    return result
}

internal fun parseEventObject(eventName: String?, obj: JSONObject): List<ChatStreamEvent> {
    val out = mutableListOf<ChatStreamEvent>()
    obj.promptProgressEventOrNull()?.let { out += it }
    obj.tokensPerSecondOrNull()?.let { out += ChatStreamEvent.Usage(null, null, it) }
    obj.draftAcceptanceOrNull()?.let { out += ChatStreamEvent.DraftAcceptance(it.rate, it.label) }
    val type = eventName ?: obj.optString("type", "")
    val t = type.lowercase()

    // Percorso moderno Sessions/Runs (v2026.9.x): assistant.delta, tool.started/completed,
    // run.* lifecycle, approval.*, hermes.tool.progress. Tollerante agli sconosciuti.
    val modern = parseModernSessionRunEvents(eventName ?: obj.optString("type", "").takeIf { it.isNotBlank() }, obj)
    if (modern.isNotEmpty()) {
        out += modern
        return out
    }

    when {
        t.contains("hermes.context.usage") || t.contains("context.usage") -> {
            out += ChatStreamEvent.ContextUsage(
                obj.optIntOrNull("context_tokens") ?: obj.optIntOrNull("tokens"),
                obj.optIntOrNull("context_length") ?: obj.optIntOrNull("max_tokens"),
                obj.optIntOrNull("context_percent") ?: obj.optIntOrNull("percent")
            )
            return out
        }
        t.contains("prompt.progress") || t.contains("processing.progress") -> {
            if (out.any { it is ChatStreamEvent.PromptProgress }) {
                return out
            }
            val estimated = obj.optBoolean("estimated", false)
            val percent = obj.optProgressPercent()
            val progress = obj.optJSONObject("prompt_progress")
            val processed = progress?.optIntOrNull("processed")
            val total = progress?.optIntOrNull("total")?.takeIf { it > 0 }
            val cache = progress?.optIntOrNull("cache")
            val timeMs = progress?.optDoubleOrNull("time_ms")
            // Anche stimato si mostra (marcato ~): niente prefill se zero dati.
            if (percent == null && processed == null && total == null) {
                return out
            }
            out += ChatStreamEvent.PromptProgress(
                percent = percent ?: 0,
                label = "Elaborazione prompt",
                estimated = estimated,
                processedTokens = processed,
                totalTokens = total,
                cachedTokens = cache,
                timeMs = timeMs
            )
            return out
        }
        t.contains("hermes.visual_blocks") || t.contains("visual_blocks") -> {
            val blocks = extractVisualBlocksFromJson(obj.toString())
            if (blocks.isNotEmpty()) out += ChatStreamEvent.VisualBlocks(blocks, VISUAL_BLOCKS_VERSION)
            return out
        }
        isToolEnvelopeType(t) -> {
            val id = obj.optString("toolCallId", obj.optString("call_id", obj.optString("id", "tool")))
            val name = obj.optString("tool", obj.optString("name", "tool"))
            val status = obj.optString("status", "").lowercase()
            val label = obj.optString("label", "")
            out += ChatStreamEvent.ToolCallStart(id, name)
            if (label.isNotBlank() && label != name) out += ChatStreamEvent.ToolCallArgs(id, label)
            val result = extractToolOutput(obj)
            if (result.isNotBlank()) out += ChatStreamEvent.ToolResult(id, name, result)
            if (t.contains("completed") || t.contains("done") || t.contains("failed") || t.contains("error") ||
                status.contains("complete") || status.contains("done") || status.contains("success") || status.contains("failed") || status.contains("error")) {
                out += ChatStreamEvent.ToolCallEnd(id)
            }
            return out
        }
        t.contains("reasoning.available") -> {
            val reasoning = obj.optString("reasoning", obj.optString("summary", obj.optString("text", obj.optString("preview", ""))))
            if (reasoning.isNotEmpty()) out += ChatStreamEvent.ThinkingSnapshot(reasoning)
            return out
        }
        t.contains("output_text") && t.contains("delta") -> {
            val delta = obj.optString("delta", obj.optString("text", ""))
            if (delta.isNotEmpty()) out += ChatStreamEvent.TextDelta(delta)
            return out
        }
        t.contains("output_text") &&
            !t.contains("output_item") &&
            (t.contains("done") || t.contains("completed")) -> {
            extractTextFromAnyJson(obj).takeIf { it.isNotEmpty() }?.let {
                out += ChatStreamEvent.TextSnapshot(it)
            }
            val usage = obj.optJSONObject("usage")
            if (usage != null) {
                out += ChatStreamEvent.Usage(
                    usage.optIntOrNull("input_tokens") ?: usage.optIntOrNull("prompt_tokens"),
                    usage.optIntOrNull("output_tokens") ?: usage.optIntOrNull("completion_tokens"),
                    usage.tokensPerSecondOrNull() ?: obj.optJSONObject("timings")?.tokensPerSecondOrNull()
                )
            }
            val blocks = extractVisualBlocksFromJson(obj.toString())
            if (blocks.isNotEmpty()) {
                out += ChatStreamEvent.VisualBlocks(blocks, VISUAL_BLOCKS_VERSION)
            }
            return out
        }
        (t.contains("reasoning") || t.contains("analysis")) && t.contains("delta") -> {
            val delta = obj.optString("delta", obj.optString("text", ""))
            if (delta.isNotEmpty()) out += ChatStreamEvent.ThinkingDelta(delta)
            return out
        }
        t.contains("function_call") && t.contains("arguments") && t.contains("delta") -> {
            val id = obj.optString("item_id", obj.optString("id", "tool"))
            val delta = obj.optString("delta", "")
            if (delta.isNotEmpty()) out += ChatStreamEvent.ToolCallArgs(id, delta)
            return out
        }
        t.contains("output_item.added") -> {
            val item = obj.optJSONObject("item")
            if (item != null) {
                val itype = item.optString("type", "")
                if (itype.contains("function", ignoreCase = true) || itype.contains("tool", ignoreCase = true)) {
                    val id = item.optString("call_id", item.optString("id", "tool"))
                    val name = item.optString("name", "tool")
                    out += ChatStreamEvent.ToolCallStart(id, name)
                    val args = item.optString("arguments", "")
                    if (args.isNotEmpty()) out += ChatStreamEvent.ToolCallArgs(id, args)
                }
            }
            return out
        }
        t.contains("output_item.done") || t.contains("function_call.completed") -> {
            val item = obj.optJSONObject("item")
            if (item != null) {
                val itemType = item.optString("type", "")
                if (itemType.contains("reasoning", ignoreCase = true) || itemType.contains("analysis", ignoreCase = true)) {
                    val reasoning = extractReasoningText(item)
                    if (reasoning.isNotEmpty()) out += ChatStreamEvent.ThinkingSnapshot(reasoning)
                } else if (itemType.contains("message", ignoreCase = true)) {
                    extractTextFromAnyJson(item).takeIf { it.isNotEmpty() }?.let {
                        out += ChatStreamEvent.TextSnapshot(it)
                    }
                    val blocks = extractVisualBlocksFromJson(item.toString())
                    if (blocks.isNotEmpty()) {
                        out += ChatStreamEvent.VisualBlocks(blocks, VISUAL_BLOCKS_VERSION)
                    }
                } else if (itemType.contains("output", ignoreCase = true) || itemType.contains("result", ignoreCase = true)) {
                    val id = item.optString("call_id", item.optString("id", "tool"))
                    val name = item.optString("name", "")
                    val output = extractToolOutput(item)
                    if (output.isNotBlank()) out += ChatStreamEvent.ToolResult(id, name.ifBlank { null }, output)
                } else if (itemType.contains("function", ignoreCase = true) || itemType.contains("tool", ignoreCase = true)) {
                    val id = item.optString("call_id", item.optString("id", "tool"))
                    val name = item.optString("name", "tool")
                    if (name.isNotBlank()) out += ChatStreamEvent.ToolCallStart(id, name)
                    val args = item.optString("arguments", "")
                    if (args.isNotEmpty()) out += ChatStreamEvent.ToolCallArgs(id, args)
                    out += ChatStreamEvent.ToolCallEnd(id)
                }
            }
            return out
        }
        t.contains("response.created") || t.contains("response.started") -> {
            val resp = obj.optJSONObject("response")
            if (resp != null) {
                val rid = resp.optString("id")
                if (rid.isNotBlank()) out += ChatStreamEvent.ResponseId(rid)
            }
            return out
        }
        t.contains("response.completed") || t.contains("response.done") -> {
            val resp = obj.optJSONObject("response")
            if (resp != null) {
                val rid = resp.optString("id")
                if (rid.isNotBlank()) out += ChatStreamEvent.ResponseId(rid)
                val usage = resp.optJSONObject("usage")
                if (usage != null) {
                    out += ChatStreamEvent.Usage(
                        usage.optIntOrNull("input_tokens") ?: usage.optIntOrNull("prompt_tokens"),
                        usage.optIntOrNull("output_tokens") ?: usage.optIntOrNull("completion_tokens"),
                        usage.tokensPerSecondOrNull() ?: resp.optJSONObject("timings")?.tokensPerSecondOrNull()
                    )
                }
                out += extractToolEventsFromAnyJson(resp)
                extractTextFromAnyJson(resp).takeIf { it.isNotEmpty() }?.let {
                    out += ChatStreamEvent.TextSnapshot(it)
                }
                val blocks = extractVisualBlocksFromJson(resp.toString())
                if (blocks.isNotEmpty()) {
                    out += ChatStreamEvent.VisualBlocks(blocks, VISUAL_BLOCKS_VERSION)
                }
                val reasoning = extractReasoningText(resp)
                if (reasoning.isNotEmpty()) {
                    out += ChatStreamEvent.ThinkingSnapshot(reasoning)
                }
            }
            return out
        }
    }

    // Chat Completions delta
    val choices = obj.optJSONArray("choices")
    if (choices != null) {
        for (i in 0 until choices.length()) {
            val choice = choices.optJSONObject(i) ?: continue
            val delta = choice.optJSONObject("delta")
            if (delta != null) {
                val content = delta.optString("content", "")
                if (content.isNotEmpty()) out += ChatStreamEvent.TextDelta(content)
                val reasoning = when {
                    delta.has("reasoning") -> delta.optString("reasoning", "")
                    delta.has("reasoning_content") -> delta.optString("reasoning_content", "")
                    delta.has("analysis") -> delta.optString("analysis", "")
                    else -> delta.optString("analysis_content", "")
                }
                if (reasoning.isNotEmpty()) out += ChatStreamEvent.ThinkingDelta(reasoning)
                val toolCalls = delta.optJSONArray("tool_calls")
                if (toolCalls != null) {
                    for (j in 0 until toolCalls.length()) {
                        val call = toolCalls.optJSONObject(j) ?: continue
                        val id = call.optString("id", "tool")
                        val fn = call.optJSONObject("function")
                        if (fn != null) {
                            val name = fn.optString("name", "")
                            if (name.isNotBlank()) out += ChatStreamEvent.ToolCallStart(id, name)
                            val args = fn.optString("arguments", "")
                            if (args.isNotEmpty()) out += ChatStreamEvent.ToolCallArgs(id, args)
                        }
                    }
                }
            }
            val finishReason = choice.optString("finish_reason", "")
            if (delta != null && out.isEmpty() && delta.has("role") && finishReason.isBlank()) {
                return out
            }
            val message = choice.optJSONObject("message")
            if (message != null) {
                extractTextFromAnyJson(message).takeIf { it.isNotEmpty() }?.let {
                    out += ChatStreamEvent.TextSnapshot(it)
                }
                extractReasoningText(message).takeIf { it.isNotEmpty() }?.let {
                    out += ChatStreamEvent.ThinkingSnapshot(it)
                }
                out += extractToolEventsFromAnyJson(message)
            }
        }
        val usage = obj.optJSONObject("usage")
        if (usage != null) {
            out += ChatStreamEvent.Usage(
                usage.optIntOrNull("input_tokens") ?: usage.optIntOrNull("prompt_tokens"),
                usage.optIntOrNull("output_tokens") ?: usage.optIntOrNull("completion_tokens"),
                usage.tokensPerSecondOrNull() ?: obj.optJSONObject("timings")?.tokensPerSecondOrNull()
            )
        }
        val blocks = extractVisualBlocksFromJson(obj.toString())
        if (blocks.isNotEmpty()) {
            out += ChatStreamEvent.VisualBlocks(blocks, VISUAL_BLOCKS_VERSION)
        }
    } else {
        if (!isToolPayload(obj, t)) {
            val text = extractTextFromAnyJson(obj)
            if (text.isNotEmpty()) {
                out += ChatStreamEvent.TextDelta(text)
            }
        }
    }

    return out
}

internal fun isToolEnvelopeType(type: String): Boolean {
    return type.contains("hermes.tool.") ||
        type.contains("tool.progress") ||
        type.contains("tool.started") ||
        type.contains("tool.completed") ||
        type.contains("tool.failed") ||
        type.contains("tool.error") ||
        type.contains("tool_result") ||
        type.contains("tool.output") ||
        type.contains("function_call_output")
}

internal fun isToolPayload(obj: JSONObject, type: String): Boolean {
    if (isToolEnvelopeType(type)) return true
    val objectType = obj.optString("type", "").lowercase()
    if (isToolEnvelopeType(objectType)) return true
    if (obj.has("tool") || obj.has("tool_call_id") || obj.has("toolCallId") || obj.has("call_id")) return true
    return obj.has("is_error") && (obj.has("result") || obj.has("output"))
}


internal fun extractToolEventsFromAnyJson(obj: JSONObject): List<ChatStreamEvent> {
    val out = mutableListOf<ChatStreamEvent>()
    val toolCalls = obj.optJSONArray("tool_calls")
    if (toolCalls != null) {
        for (i in 0 until toolCalls.length()) {
            val call = toolCalls.optJSONObject(i) ?: continue
            val id = call.optString("id", call.optString("call_id", "tool-$i"))
            val fn = call.optJSONObject("function")
            val name = fn?.optString("name") ?: call.optString("name", "tool")
            out += ChatStreamEvent.ToolCallStart(id, name)
            val args = fn?.optString("arguments") ?: call.optString("arguments", "")
            if (args.isNotEmpty()) out += ChatStreamEvent.ToolCallArgs(id, args)
        }
    }

    val output = obj.optJSONArray("output")
    if (output != null) {
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val type = item.optString("type", "")
            when {
                type.contains("function_call_output", ignoreCase = true) ||
                    type.contains("tool_result", ignoreCase = true) ||
                    type.contains("tool_output", ignoreCase = true) -> {
                    val id = item.optString("call_id", item.optString("id", "tool-$i"))
                    val name = item.optString("name", "")
                    val result = extractToolOutput(item)
                    if (result.isNotBlank()) out += ChatStreamEvent.ToolResult(id, name.ifBlank { null }, result)
                }
                type.contains("function_call", ignoreCase = true) ||
                    type.contains("tool_call", ignoreCase = true) -> {
                    val id = item.optString("call_id", item.optString("id", "tool-$i"))
                    val name = item.optString("name", "tool")
                    out += ChatStreamEvent.ToolCallStart(id, name)
                    val args = item.optString("arguments", "")
                    if (args.isNotEmpty()) out += ChatStreamEvent.ToolCallArgs(id, args)
                    if (item.optString("status", "").contains("completed", ignoreCase = true)) {
                        out += ChatStreamEvent.ToolCallEnd(id)
                    }
                }
            }
        }
    }
    return out
}

internal fun extractToolOutput(obj: JSONObject): String {
    val direct = listOf("output", "result", "content", "text")
        .firstNotNullOfOrNull { key ->
            when (val value = obj.opt(key)) {
                is String -> value.takeIf { it.isNotBlank() }
                is JSONObject, is JSONArray -> value.toString()
                else -> null
            }
        }
    return direct.orEmpty()
}

internal const val JSON_EXTRACT_MAX_DEPTH = 10

internal fun extractTextFromAnyJson(obj: JSONObject, depth: Int = 0): String {
    if (depth >= JSON_EXTRACT_MAX_DEPTH) return ""
    if (isNonAssistantTextPayload(obj)) return ""

    val direct = listOf("output_text", "text", "message", "reply")
        .firstNotNullOfOrNull { key ->
            val value = obj.opt(key)
            if (value is String && value.isNotEmpty()) value else null
        }
    if (!direct.isNullOrEmpty()) return direct

    val content = obj.optJSONArray("content")
    if (content != null) {
        val builder = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            if (isNonAssistantTextPayload(part)) continue
            val partText = part.optString("text", part.optString("output_text", ""))
            if (partText.isNotEmpty()) builder.append(partText)
        }
        if (builder.isNotEmpty()) return builder.toString()
    }

    val output = obj.optJSONArray("output")
    if (output != null) {
        val builder = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val nested = extractTextFromAnyJson(item, depth + 1)
            if (nested.isNotEmpty()) builder.append(nested)
        }
        if (builder.isNotEmpty()) return builder.toString()
    }

    val response = obj.optJSONObject("response")
    if (response != null) {
        val nested = extractTextFromAnyJson(response, depth + 1)
        if (nested.isNotEmpty()) return nested
    }

    val item = obj.optJSONObject("item")
    if (item != null) {
        val nested = extractTextFromAnyJson(item, depth + 1)
        if (nested.isNotEmpty()) return nested
    }

    return ""
}

internal suspend fun executeCancellableRequest(request: Request): Pair<Int, String> =
    suspendCancellableCoroutine { continuation ->
        val call = streamHttpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resume(0 to (e.message ?: e.javaClass.simpleName))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use { it.code to it.body.byteStream().readUtf8Bounded() }
                }.getOrElse { 0 to (it.message ?: it.javaClass.simpleName) }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }

internal fun isNonAssistantTextPayload(obj: JSONObject): Boolean {
    val type = obj.optString("type", "").lowercase()
    if (type.contains("reasoning") ||
        type.contains("analysis") ||
        type.contains("function_call") ||
        type.contains("tool")
    ) {
        return true
    }
    return obj.has("tool_call_id") ||
        obj.has("toolCallId") ||
        obj.has("call_id") ||
        obj.has("is_error")
}

internal fun extractReasoningText(value: Any?, insideReasoning: Boolean = false): String {
    return when (value) {
        null, JSONObject.NULL -> ""
        is String -> if (insideReasoning) value else ""
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                append(extractReasoningText(value.opt(i), insideReasoning))
            }
        }
        is JSONObject -> {
            val type = value.optString("type", "")
            val nowInsideReasoning = insideReasoning ||
                type.contains("reasoning", ignoreCase = true) ||
                type.contains("analysis", ignoreCase = true)
            buildString {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "type" || key == "id" || key == "status" || key == "role") continue
                    val childInsideReasoning = nowInsideReasoning || key.contains("reasoning", ignoreCase = true)
                    if ((!childInsideReasoning && isNonAssistantTextPayload(value)).not() && (childInsideReasoning ||
                        key == "output" ||
                        key == "item" ||
                        key == "response" ||
                        key == "content" ||
                        key == "summary"
                    )) {
                        append(extractReasoningText(value.opt(key), childInsideReasoning))
                    }
                }
            }
        }
        else -> ""
    }
}

internal fun parseFullBody(body: String): List<ChatStreamEvent> {
    if (body.isBlank()) return emptyList()
    val out = mutableListOf<ChatStreamEvent>()
    val text = extractAssistantText(body)
    val rid = extractResponseId(body)
    if (rid != null) out += ChatStreamEvent.ResponseId(rid)
    if (text.isNotEmpty()) out += ChatStreamEvent.TextDelta(text)
    try {
        val reasoning = extractReasoningText(JSONObject(body))
        if (reasoning.isNotEmpty()) out += ChatStreamEvent.ThinkingSnapshot(reasoning)
    } catch (_: Exception) {
        // Body may be plain text.
    }
    try {
        out += extractToolEventsFromAnyJson(JSONObject(body))
    } catch (_: Exception) {
        // Body may be plain text.
    }
    val blocks = try { extractVisualBlocks(body) } catch (_: Exception) { emptyList() }
    if (blocks.isNotEmpty()) out += ChatStreamEvent.VisualBlocks(blocks, VISUAL_BLOCKS_VERSION)
    return out
}

