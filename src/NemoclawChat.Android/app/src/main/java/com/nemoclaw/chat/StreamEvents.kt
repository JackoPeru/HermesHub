package com.nemoclaw.chat


sealed class ChatStreamEvent {
    data class TextDelta(val delta: String) : ChatStreamEvent()
    data class TextSnapshot(val text: String) : ChatStreamEvent()
    data class ThinkingDelta(val delta: String) : ChatStreamEvent()
    data class ThinkingSnapshot(val text: String) : ChatStreamEvent()
    data class ToolCallStart(val id: String, val name: String) : ChatStreamEvent()
    data class ToolCallArgs(val id: String, val delta: String) : ChatStreamEvent()
    data class ToolCallEnd(val id: String) : ChatStreamEvent()
    data class ToolResult(val id: String?, val name: String?, val output: String) : ChatStreamEvent()
    data class ResponseId(val id: String) : ChatStreamEvent()
    data class RunId(val id: String) : ChatStreamEvent()
    data class RunStatusChanged(val runId: String, val status: String, val pendingSteer: String? = null) : ChatStreamEvent()
    data class ApprovalRequest(
        val runId: String,
        val approvalId: String,
        val requestId: String,
        val tool: String,
        val command: String,
        val description: String,
        val choices: List<String>
    ) : ChatStreamEvent()
    data class ApprovalResolved(val runId: String, val approvalId: String, val choice: String) : ChatStreamEvent()
    data class VisualBlocks(val blocks: List<VisualBlock>, val version: Int) : ChatStreamEvent()
    data class Status(val message: String) : ChatStreamEvent()
    /** Wrapper senza contenuto (es. message.completed senza testo): nessun
     *  cambio visibile, serve solo a non cadere nel fallback Raw. */
    data object KeepAlive : ChatStreamEvent()
    data class RawHermesEvent(
        val name: String,
        val json: String,
        val protocolVersion: Int? = null,
        val eventId: String? = null,
        val sequence: Long? = null,
        val requestId: String? = null,
        val correlationId: String? = null,
        val runId: String? = null,
        val sourceType: String? = null
    ) : ChatStreamEvent()
    data class EnvelopeMetadata(
        val protocolVersion: Int,
        val eventId: String,
        val sequence: Long,
        val requestId: String,
        val correlationId: String,
        val type: String,
        val sourceType: String,
        val runId: String?
    ) : ChatStreamEvent()
    data class Usage(val promptTokens: Int?, val completionTokens: Int?, val tokensPerSecond: Double? = null) : ChatStreamEvent()
    data class DraftAcceptance(val rate: Double, val label: String? = null) : ChatStreamEvent()
    data class ContextUsage(val tokens: Int?, val length: Int?, val percent: Int?) : ChatStreamEvent()
    data class PromptProgress(
        val percent: Int,
        val label: String = "Elaborazione prompt",
        val estimated: Boolean = false,
        val processedTokens: Int? = null,
        val totalTokens: Int? = null,
        val cachedTokens: Int? = null,
        val timeMs: Double? = null
    ) : ChatStreamEvent()
    data class Done(val stats: ChatStreamStats) : ChatStreamEvent()
    data class Error(val message: String) : ChatStreamEvent()
}

data class ChatInputAttachment(
    val filename: String,
    val mimeType: String,
    val dataUrl: String = "",
    val sizeBytes: Long,
    val localFilePath: String? = null
)

internal data class UploadedAttachmentRef(
    val filename: String,
    val mimeType: String,
    val path: String?,
    val mediaUrl: String?,
    val error: String?
)

