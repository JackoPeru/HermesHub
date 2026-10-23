package com.nemoclaw.chat

import kotlin.math.roundToInt

@androidx.compose.runtime.Immutable
data class ChatStreamStats(
    val ttftMs: Double? = null,
    val totalMs: Double? = null,
    val tokensOut: Int? = null,
    val tokensPerSecond: Double? = null,
    val promptTokens: Int? = null,
    val contextTokens: Int? = null,
    val contextLength: Int? = null,
    val contextPercent: Int? = null,
    val acceptanceRate: Double? = null,
    val acceptanceLabel: String? = null,
    val modelPromptTokens: Int? = null
)

internal fun ChatStreamStats.contextTokens(): Int {
    contextTokens?.takeIf { it > 0 }?.let { return it }
    val prompt = promptTokens?.takeIf { it > 0 } ?: 0
    val output = tokensOut?.takeIf { it > 0 } ?: 0
    return (prompt + output).coerceAtLeast(prompt).coerceAtLeast(output)
}

@androidx.compose.runtime.Immutable
data class ToolCallState(
    val id: String,
    val name: String,
    val args: String = "",
    val status: String = "in esecuzione…",
    val result: String? = null,
    val argsPreview: String = "",
    val resultPreview: String = "",
    val startedAtMs: Long = 0L,
    val endedAtMs: Long = 0L
)

/** Durata reale del tool (0 se sconosciuta o ancora in corso). Puro. */
internal fun toolDurationMs(tool: ToolCallState): Long {
    if (tool.startedAtMs <= 0L || tool.endedAtMs <= tool.startedAtMs) return 0L
    return tool.endedAtMs - tool.startedAtMs
}

/** "608ms" / "6,2s" / "25s" come il desktop ufficiale. Puro e testabile. */
internal fun formatToolDuration(ms: Long): String {
    if (ms <= 0) return ""
    if (ms < 1000) return "${ms}ms"
    val s = ms / 1000.0
    return if (s < 10) String.format(java.util.Locale.US, "%.1f", s).replace('.', ',') + "s"
    else "${s.roundToInt()}s"
}

internal fun toolDurationLabel(tool: ToolCallState): String = formatToolDuration(toolDurationMs(tool))

/** Titolo gruppo tool compatti ("4 attività"). Puro. */
internal fun toolsGroupTitle(count: Int): String = "$count attività"

internal const val SAFE_TOOL_ARGUMENTS_SUMMARY = "Argomenti ricevuti; contenuto omesso."
internal const val SAFE_TOOL_RESULT_SUMMARY = "Risultato ricevuto; contenuto omesso."
internal const val SAFE_RAW_EVENT_JSON = "{\"redacted\":true,\"summary\":\"Evento Hermes omesso per riservatezza.\"}"

/** Tool payload is untrusted and never retained client-side as raw text. */
internal fun safeToolPayloadSummary(payload: String, result: Boolean): String? {
    if (payload.isEmpty()) return null
    return if (result) SAFE_TOOL_RESULT_SUMMARY else SAFE_TOOL_ARGUMENTS_SUMMARY
}

internal fun safeToolCall(tool: ToolCallState): ToolCallState = tool.copy(
    args = safeToolPayloadSummary(tool.args, result = false) ?: "",
    result = tool.result?.let { safeToolPayloadSummary(it, result = true) }
)

internal fun redactToolRawEvent(name: String, json: String): String {
    // Raw gateway events are untrusted. Never retain a payload merely because it lacks a known marker.
    return SAFE_RAW_EVENT_JSON
}


/** Explicit server activity. Client-generated status never enters this timeline. */
@androidx.compose.runtime.Immutable
data class AssistantActivity(
    val kind: Kind,
    val text: String = "",
    val tool: ToolCallState? = null
) {
    enum class Kind { Reasoning, PromptProgress, Tool }
}

/**
 * Blocco del transcript agente in ordine cronologico (stile "agent feed"):
 * testo dell'agente interleavato con pensieri e raffiche di tool. Il testo
 * finale resta in [StreamingState.text]; i segmenti AgentText servono solo a
 * mostrare il flusso durante lo streaming.
 */
@androidx.compose.runtime.Immutable
sealed interface TranscriptBlock {
    @androidx.compose.runtime.Immutable
    data class AgentText(val text: String) : TranscriptBlock

    @androidx.compose.runtime.Immutable
    data class Thought(val text: String, val elapsedSec: Double = 0.0) : TranscriptBlock

    @androidx.compose.runtime.Immutable
    data class Tools(val tools: List<ToolCallState>) : TranscriptBlock

    @androidx.compose.runtime.Immutable
    data class Prefill(val text: String) : TranscriptBlock
}

internal const val MAX_ACTIVITY_TIMELINE_ENTRIES = 256
internal const val MAX_ACTIVITY_ENTRY_CHARS = 16_384
internal const val MAX_ACTIVITY_TIMELINE_CHARS = 96_000
internal const val MAX_TRANSCRIPT_BLOCKS = 128
internal const val MAX_TRANSCRIPT_CHARS = 96_000

/** Approvazione server-side di un run (Hunter: approval.request). Distinta dal Visual Block locale "approval". */
@androidx.compose.runtime.Immutable
data class HermesServerApproval(
    val approvalId: String = "",
    val requestId: String = "",
    val runId: String = "",
    val tool: String = "",
    val command: String = "",
    val description: String = "",
    /** Scelte offerte dal server per questa richiesta: renderizzare esattamente queste. */
    val choices: List<String> = emptyList(),
    val status: String = "pending"
)

