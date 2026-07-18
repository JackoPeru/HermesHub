package com.nemoclaw.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okio.BufferedSink
import android.util.Log
import android.util.Base64
import android.util.Base64OutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.net.ConnectException
import java.net.URI
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.coroutines.resume

private const val STREAM_ACCUM_MAX_CHARS = 2_000_000
private const val STREAM_UI_BATCH_MAX_CHARS = 2048
private const val STREAM_UI_BATCH_NS = 33_000_000L
private const val RUN_POLL_TIMEOUT_MS = 30 * 60 * 1000L
private const val RUN_POLL_MAX_CONSECUTIVE_FAILURES = 5
private const val INLINE_ATTACHMENT_MAX_BYTES = 8L * 1024 * 1024
private const val INLINE_ATTACHMENTS_TOTAL_MAX_BYTES = 12L * 1024 * 1024
private const val SSE_LINE_MAX_BYTES = 256L * 1024L
private val plugAndPlayStreamGatewayRoots = emptyList<String>()

private val streamHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.SECONDS)
    .apply { debugHttpLoggingInterceptor()?.let { addInterceptor(it) } }
    .build()

private fun debugHttpLoggingInterceptor(): okhttp3.Interceptor? {
    if (!com.nemoclaw.chat.BuildConfig.DEBUG) return null
    return try {
        val clazz = Class.forName("okhttp3.logging.HttpLoggingInterceptor")
        val instance = clazz.getDeclaredConstructor().newInstance()
        val levelClass = Class.forName("okhttp3.logging.HttpLoggingInterceptor\$Level")
        val headersLevel = levelClass.enumConstants?.firstOrNull { (it as Enum<*>).name == "HEADERS" }
        if (headersLevel != null) {
            clazz.getMethod("setLevel", levelClass).invoke(instance, headersLevel)
        }
        val redactMethod = clazz.getMethod("redactHeader", String::class.java)
        redactMethod.invoke(instance, "Authorization")
        redactMethod.invoke(instance, "Cookie")
        instance as okhttp3.Interceptor
    } catch (_: Throwable) {
        null
    }
}

private fun StringBuilder.appendBounded(text: String): Boolean {
    if (length >= STREAM_ACCUM_MAX_CHARS) return false
    val remaining = STREAM_ACCUM_MAX_CHARS - length
    if (text.length <= remaining) {
        append(text)
    } else {
        append(text, 0, remaining)
        append("\n\n[…troncato: limite ${STREAM_ACCUM_MAX_CHARS} caratteri raggiunto.]")
    }
    return true
}

private fun validateTokensPerSecond(value: Double?): Double? {
    val v = value ?: return null
    return if (v.isFinite() && v > 0.0 && v <= 70.0) v else null
}

@androidx.compose.runtime.Immutable
data class ChatStreamStats(
    val ttftMs: Double? = null,
    val totalMs: Double? = null,
    val tokensOut: Int? = null,
    val tokensPerSecond: Double? = null,
    val promptTokens: Int? = null,
    val contextTokens: Int? = null,
    val contextLength: Int? = null,
    val contextPercent: Int? = null
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
    val result: String? = null
)

@androidx.compose.runtime.Immutable
data class StreamingState(
    val text: String = "",
    val thinking: String = "",
    val hasThinking: Boolean = false,
    val thinkingFrozen: Boolean = false,
    val thinkingElapsedSec: Double = 0.0,
    val toolCalls: List<ToolCallState> = emptyList(),
    val visualBlocks: List<VisualBlock> = emptyList(),
    val visualBlocksVersion: Int? = null,
    val responseId: String? = null,
    val activeRunId: String? = null,
    val stats: ChatStreamStats? = null,
    val status: String = "Invio prompt a Hermes...",
    val promptProgressPercent: Int? = null,
    val promptProgressEstimated: Boolean = false,
    val promptProgressProcessedTokens: Int? = null,
    val promptProgressTotalTokens: Int? = null,
    val promptProgressCachedTokens: Int? = null,
    val promptProgressTimeMs: Double? = null,
    val activityLog: List<String> = listOf("0.0s  Invio prompt a Hermes..."),
    val error: String? = null,
    val source: String = "Hermes",
    val usedFallback: Boolean = false,
    val isDone: Boolean = false,
    val startedAtNs: Long = System.nanoTime()
) {
    fun applyEvent(event: ChatStreamEvent): StreamingState = when (event) {
        is ChatStreamEvent.TextDelta -> {
            val merged = mergeTextDelta(text, event.delta)
            val media = extractInlineMediaBlocks(merged)
            val cleanedText = stripInlineMediaMarkup(merged)
            val nextBlocks = mergeVisualBlocks(visualBlocks, media)
            copy(
                text = cleanedText,
                visualBlocks = nextBlocks,
                promptProgressPercent = null,
                promptProgressEstimated = false,
                promptProgressProcessedTokens = null,
                promptProgressTotalTokens = null,
                promptProgressCachedTokens = null,
                promptProgressTimeMs = null,
                status = if (toolCalls.any { inferToolPendingStatus(it) }) status else "Hermes sta scrivendo la risposta...",
                thinkingFrozen = thinkingFrozen || hasThinking,
                thinkingElapsedSec = if (!thinkingFrozen && hasThinking) {
                    (System.nanoTime() - startedAtNs) / 1_000_000_000.0
                } else thinkingElapsedSec
            ).withActivity(if (text.isEmpty()) "Primo testo ricevuto." else null)
        }
        is ChatStreamEvent.TextSnapshot -> {
            val merged = mergeTextSnapshot(text, event.text)
            val media = extractInlineMediaBlocks(merged)
            copy(
                text = stripInlineMediaMarkup(merged),
                visualBlocks = mergeVisualBlocks(visualBlocks, media),
                promptProgressPercent = null,
                promptProgressEstimated = false,
                promptProgressProcessedTokens = null,
                promptProgressTotalTokens = null,
                promptProgressCachedTokens = null,
                promptProgressTimeMs = null,
                status = "Risposta finale ricevuta.",
                thinkingFrozen = thinkingFrozen || hasThinking
            ).withActivity("Snapshot finale ricevuto.")
        }
        is ChatStreamEvent.ThinkingDelta -> copy(
            thinking = mergeTextDelta(thinking, event.delta),
            hasThinking = true,
            thinkingFrozen = false,
            promptProgressPercent = null,
            promptProgressEstimated = false,
            promptProgressProcessedTokens = null,
            promptProgressTotalTokens = null,
            promptProgressCachedTokens = null,
            promptProgressTimeMs = null,
            status = "Hermes sta ragionando..."
        ).withActivity(if (!hasThinking) "Reasoning ricevuto." else null)
        is ChatStreamEvent.ThinkingSnapshot -> copy(
            thinking = mergeTextSnapshot(thinking, event.text),
            hasThinking = true,
            thinkingFrozen = false,
            promptProgressPercent = null,
            promptProgressEstimated = false,
            promptProgressProcessedTokens = null,
            promptProgressTotalTokens = null,
            promptProgressCachedTokens = null,
            promptProgressTimeMs = null,
            status = "Hermes sta ragionando..."
        ).withActivity(if (!hasThinking) "Reasoning ricevuto." else null)
        is ChatStreamEvent.ToolCallStart -> copy(
            status = "Tool in esecuzione: ${event.name}",
            toolCalls = if (toolCalls.any { it.id == event.id }) toolCalls
                else toolCalls + ToolCallState(event.id, event.name)
        ).withActivity("Tool avviato: ${event.name}")
        is ChatStreamEvent.ToolCallArgs -> copy(
            status = "Preparazione tool...",
            toolCalls = toolCalls.map {
                if (it.id == event.id) it.copy(args = it.args + event.delta) else it
            }
        ).withActivity("Argomenti tool aggiornati.")
        is ChatStreamEvent.ToolCallEnd -> copy(
            status = "Tool completato.",
            toolCalls = toolCalls.map {
                if (it.id == event.id) it.copy(status = "completato") else it
            }
        ).withActivity("Tool completato.")
        is ChatStreamEvent.ToolResult -> copy(
            status = "Risultato tool ricevuto.",
            toolCalls = upsertToolResult(toolCalls, event)
        ).withActivity("Risultato tool ricevuto.")
        is ChatStreamEvent.ResponseId -> copy(responseId = event.id).withActivity("Response id: ${event.id}")
        is ChatStreamEvent.RunId -> copy(activeRunId = event.id).withActivity("Run id: ${event.id}")
        is ChatStreamEvent.VisualBlocks -> copy(
            visualBlocks = mergeVisualBlocks(visualBlocks, event.blocks),
            visualBlocksVersion = event.version
        ).withActivity("Visual blocks ricevuti: ${event.blocks.size}.")
        is ChatStreamEvent.Status -> copy(status = friendlyActivityStatus(event.message)).withActivity(event.message)
        is ChatStreamEvent.RawHermesEvent -> copy(status = "Evento Hermes: ${event.name}")
            .withActivity("Evento Hermes: ${event.name} ${event.json.take(300)}")
        is ChatStreamEvent.PromptProgress -> copy(
            promptProgressPercent = event.percent.coerceIn(0, 100),
            promptProgressEstimated = event.estimated,
            promptProgressProcessedTokens = event.processedTokens,
            promptProgressTotalTokens = event.totalTokens,
            promptProgressCachedTokens = event.cachedTokens,
            promptProgressTimeMs = event.timeMs,
            status = friendlyActivityStatus(event.label.ifBlank { "Elaborazione prompt" })
        ).withActivity("Elaborazione prompt ${event.percent}%")
        is ChatStreamEvent.Done -> copy(
            text = stripReasoningArtifacts(text),
            stats = event.stats,
            isDone = true,
            status = "Risposta completata.",
            promptProgressPercent = null,
            promptProgressEstimated = false,
            promptProgressProcessedTokens = null,
            promptProgressTotalTokens = null,
            promptProgressCachedTokens = null,
            promptProgressTimeMs = null,
            thinkingFrozen = true,
            thinkingElapsedSec = if (thinkingElapsedSec > 0) thinkingElapsedSec
                else (System.nanoTime() - startedAtNs) / 1_000_000_000.0
        ).withActivity("Risposta completata.")
        is ChatStreamEvent.Error -> copy(error = event.message, status = "Errore Hermes.", isDone = true).withActivity("Errore: ${event.message}")
        is ChatStreamEvent.Usage -> copy(
            status = "Usage ricevuta: prompt ${event.promptTokens ?: "-"}, output ${event.completionTokens ?: "-"}."
        ).withActivity("Usage ricevuta.")
        is ChatStreamEvent.ContextUsage -> copy(
            stats = (stats ?: ChatStreamStats()).copy(
                contextTokens = event.tokens ?: stats?.contextTokens,
                contextLength = event.length ?: stats?.contextLength,
                contextPercent = event.percent ?: stats?.contextPercent
            ),
            status = "Contesto Hermes: ${event.tokens ?: "-"} / ${event.length ?: "-"} (${event.percent ?: "-"}%)."
        ).withActivity("Contesto Hermes aggiornato.")
    }
}

private fun StreamingState.withActivity(message: String?): StreamingState {
    if (message.isNullOrBlank()) return this
    val elapsed = (System.nanoTime() - startedAtNs) / 1_000_000_000.0
    val row = "${String.format(java.util.Locale.US, "%.1fs", elapsed)}  $message"
    return copy(activityLog = (activityLog + row).takeLast(80))
}

private fun upsertToolResult(tools: List<ToolCallState>, event: ChatStreamEvent.ToolResult): List<ToolCallState> {
    val id = event.id ?: event.name ?: "tool-result"
    if (tools.none { it.id == id }) {
        return tools + ToolCallState(id = id, name = event.name ?: id, status = "risultato pronto", result = event.output)
    }
    return tools.map {
        if ((event.id != null && it.id == event.id) || (event.id == null && event.name != null && it.name == event.name)) {
            it.copy(result = event.output, status = "risultato pronto")
        } else {
            it
        }
    }
}

private fun mergeVisualBlocks(current: List<VisualBlock>, incoming: List<VisualBlock>): List<VisualBlock> {
    if (incoming.isEmpty()) return current
    val seen = current.map { if (it.mediaUrl.isNotBlank()) "${it.type}:media:${it.mediaUrl}" else it.id }.toMutableSet()
    return current + incoming.filter {
        seen.add(if (it.mediaUrl.isNotBlank()) "${it.type}:media:${it.mediaUrl}" else it.id)
    }
}

internal fun mergeTextDelta(current: String, delta: String): String {
    if (delta.isEmpty()) return current
    if (current.isEmpty()) return delta
    return current + delta
}

internal fun mergeTextSnapshot(current: String, snapshot: String): String {
    if (snapshot.isEmpty()) return current
    if (current.isEmpty() || snapshot.startsWith(current)) return snapshot
    if (current.startsWith(snapshot) || current == snapshot) return current
    return snapshot
}

private fun inferToolPendingStatus(tool: ToolCallState): Boolean {
    val s = tool.status.lowercase()
    return !s.contains("completat") && !s.contains("risultato") && !s.contains("success") && !s.contains("done") && !s.contains("fail") && !s.contains("error")
}

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
    data class VisualBlocks(val blocks: List<VisualBlock>, val version: Int) : ChatStreamEvent()
    data class Status(val message: String) : ChatStreamEvent()
    data class RawHermesEvent(val name: String, val json: String) : ChatStreamEvent()
    data class Usage(val promptTokens: Int?, val completionTokens: Int?, val tokensPerSecond: Double? = null) : ChatStreamEvent()
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

private data class UploadedAttachmentRef(
    val filename: String,
    val mimeType: String,
    val path: String?,
    val mediaUrl: String?,
    val error: String?
)

fun streamChatRequest(
    settings: AppSettings,
    mode: String,
    prompt: String,
    history: List<ChatMessage>,
    conversationId: String?,
    previousResponseId: String?,
    attachments: List<ChatInputAttachment>,
    apiKey: String?
): Flow<ChatStreamEvent> = flow {
    val start = System.nanoTime()
    var sawActivity = false
    var sawAnswerText = false
    var sawVisualOutput = false
    var sawTerminal = false
    var ttftMs: Double? = null
    var promptTokens: Int? = null
    var completionTokens: Int? = null
    var serverTokensPerSecond: Double? = null
    var contextTokens: Int? = null
    var contextLength: Int? = null
    var contextPercent: Int? = null
    var emittedResponseId: String? = null
    var retriedWithoutPreviousResponseId = false
    val accumText = StringBuilder()
    val accumThink = StringBuilder()
    var lastError: String? = null
    val videoMode = isVideoRequest(prompt)
    val nativeMode = isHermesNative(settings)
    val serverConversationId = hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, conversationId)
    val attachmentPreparation = buildPromptWithAttachmentToolRefs(settings, prompt, attachments, apiKey)
    val promptForModel = attachmentPreparation.prompt
    val payloadAttachments = attachmentPreparation.inlineAttachments
    if (attachmentPreparation.uploadedCount > 0) {
        emit(ChatStreamEvent.Status("Allegati caricati sul gateway: ${attachmentPreparation.uploadedCount}."))
    }
    if (attachmentPreparation.uploadErrors.isNotEmpty()) {
        emit(ChatStreamEvent.Status("Upload parziale: ${attachmentPreparation.uploadErrors.joinToString("; ").take(320)}"))
    }
    val inlineBytes = payloadAttachments.sumOf { it.sizeBytes.coerceAtLeast(0) }
    val oversizedInline = payloadAttachments.firstOrNull { it.sizeBytes > INLINE_ATTACHMENT_MAX_BYTES }
    if (oversizedInline != null || inlineBytes > INLINE_ATTACHMENTS_TOTAL_MAX_BYTES) {
        val detail = oversizedInline?.filename ?: "allegati multipli"
        emit(ChatStreamEvent.Error("Upload gateway fallito per $detail e payload inline oltre il limite memoria sicuro."))
        return@flow
    }
    val textBatch = StringBuilder()
    val thinkingBatch = StringBuilder()
    var lastBatchFlushNs = System.nanoTime()
    val thinkExtractor = ThinkExtractor()

    suspend fun flushDeltaBatches() {
        if (textBatch.isNotEmpty()) {
            emit(ChatStreamEvent.TextDelta(textBatch.toString()))
            textBatch.clear()
        }
        if (thinkingBatch.isNotEmpty()) {
            emit(ChatStreamEvent.ThinkingDelta(thinkingBatch.toString()))
            thinkingBatch.clear()
        }
        lastBatchFlushNs = System.nanoTime()
    }

    suspend fun emitAndTrackInternal(ev: ChatStreamEvent): Boolean {
        when (ev) {
            is ChatStreamEvent.TextDelta -> {
                val tokenAt = System.nanoTime()
                if (!sawActivity) {
                    ttftMs = (tokenAt - start) / 1_000_000.0
                }
                sawActivity = true
                if (ev.delta.isNotEmpty()) sawAnswerText = true
                accumText.appendBounded(ev.delta)
                textBatch.append(ev.delta)
                val now = System.nanoTime()
                if (textBatch.length + thinkingBatch.length >= STREAM_UI_BATCH_MAX_CHARS ||
                    now - lastBatchFlushNs >= STREAM_UI_BATCH_NS
                ) {
                    flushDeltaBatches()
                }
                return false
            }
            is ChatStreamEvent.TextSnapshot -> {
                if (!sawActivity) {
                    ttftMs = (System.nanoTime() - start) / 1_000_000.0
                }
                sawActivity = true
                if (ev.text.isNotEmpty()) sawAnswerText = true
                flushDeltaBatches()
                val merged = mergeTextSnapshot(accumText.toString(), ev.text)
                accumText.clear()
                accumText.appendBounded(merged)
                emit(ev)
                return false
            }
            is ChatStreamEvent.ThinkingDelta -> {
                if (!sawActivity) {
                    ttftMs = (System.nanoTime() - start) / 1_000_000.0
                }
                sawActivity = true
                accumThink.appendBounded(ev.delta)
                thinkingBatch.append(ev.delta)
                val now = System.nanoTime()
                if (textBatch.length + thinkingBatch.length >= STREAM_UI_BATCH_MAX_CHARS ||
                    now - lastBatchFlushNs >= STREAM_UI_BATCH_NS
                ) {
                    flushDeltaBatches()
                }
                return false
            }
            is ChatStreamEvent.ThinkingSnapshot -> {
                if (!sawActivity) {
                    ttftMs = (System.nanoTime() - start) / 1_000_000.0
                }
                sawActivity = true
                flushDeltaBatches()
                val merged = mergeTextSnapshot(accumThink.toString(), ev.text)
                accumThink.clear()
                accumThink.appendBounded(merged)
                emit(ev)
                return false
            }
            is ChatStreamEvent.Usage -> {
                promptTokens = ev.promptTokens ?: promptTokens
                completionTokens = ev.completionTokens ?: completionTokens
                serverTokensPerSecond = validateTokensPerSecond(ev.tokensPerSecond) ?: serverTokensPerSecond
                return false
            }
            is ChatStreamEvent.ContextUsage -> {
                contextTokens = ev.tokens ?: contextTokens
                contextLength = ev.length ?: contextLength
                contextPercent = ev.percent ?: contextPercent
                return false
            }
            is ChatStreamEvent.ResponseId -> {
                emittedResponseId = ev.id
            }
            is ChatStreamEvent.Error -> {
                flushDeltaBatches()
                lastError = ev.message
                emit(ev)
                return true
            }
            else -> {
                if (ev is ChatStreamEvent.VisualBlocks || ev is ChatStreamEvent.ToolCallStart ||
                    ev is ChatStreamEvent.ToolCallArgs || ev is ChatStreamEvent.ToolCallEnd ||
                    ev is ChatStreamEvent.ToolResult
                ) {
                    sawActivity = true
                }
                if (ev is ChatStreamEvent.VisualBlocks && ev.blocks.isNotEmpty()) sawVisualOutput = true
                flushDeltaBatches()
            }
        }
        emit(ev)
        return false
    }

    suspend fun emitAndTrack(ev: ChatStreamEvent): Boolean {
        when (ev) {
            is ChatStreamEvent.TextDelta -> {
                var stop = false
                for (extracted in thinkExtractor.process(ev.delta)) {
                    if (emitAndTrackInternal(extracted)) stop = true
                }
                return stop
            }
            is ChatStreamEvent.TextSnapshot -> {
                var stop = false
                for (reconciled in thinkExtractor.processSnapshot(ev.text)) {
                    if (emitAndTrackInternal(reconciled)) stop = true
                }
                return stop
            }
            else -> return emitAndTrackInternal(ev)
        }
    }

    if (mode.equals("Agente", ignoreCase = true)) {
        runDetachedAgent(settings, promptForModel, history, conversationId, payloadAttachments, apiKey).collect { emit(it) }
        return@flow
    }

    if (shouldUseResponsesFirst(settings, mode)) {
        emit(ChatStreamEvent.Status(if (nativeMode) "Protocollo effettivo: Hermes Native via Responses. Context delegato a Hermes." else "Invio diretto a Hermes Responses API..."))
        emit(ChatStreamEvent.Status("Preparazione richiesta al modello..."))

        fun buildResponsePayload(candidatePreviousResponseId: String?): JSONObject {
            val payload = JSONObject()
                .put("model", settings.model)
                .put("input", buildMultimodalInput(promptForModel, payloadAttachments))
                .put("store", true)
                .put("stream", true)
                .put("return_progress", true)
                .put("timings_per_token", true)
                .put("conversation", serverConversationId ?: JSONObject.NULL)
                .put("previous_response_id", if (serverConversationId == null) candidatePreviousResponseId ?: JSONObject.NULL else JSONObject.NULL)
                .put("metadata", visualBlocksMetadataJson(settings, conversationId))
            payload.put(
                "instructions",
                (if (nativeMode) hermesNativeInstructions(mode) else
                    (if (mode.equals("Agente", ignoreCase = true))
                        hermesHubAgentInstructions()
                    else
                        hermesHubChatInstructions())) + projectContextInstructions(settings) + if (videoMode) "\n\n" + hermesHubVideoInstructions(settings) else ""
            )
            return payload
        }

        val responseUrl = "${settings.gatewayUrl.trimEnd('/')}/responses"
        val previousResponseCandidates = if (serverConversationId != null || previousResponseId.isNullOrBlank()) {
            listOf<String?>(null)
        } else {
            listOf(previousResponseId, null)
        }
        responseCandidateLoop@ for (candidatePreviousResponseId in previousResponseCandidates) {
            if (candidatePreviousResponseId == null && !previousResponseId.isNullOrBlank()) {
                retriedWithoutPreviousResponseId = true
                emit(ChatStreamEvent.Status("Contesto server rifiutato. Riprovo senza previous_response_id..."))
            }
            val responsePayload = buildResponsePayload(candidatePreviousResponseId)
            lastError = null
            val result = openSseStream(responseUrl, responsePayload, "Hermes Responses API", apiKey, true) { ev ->
                emitAndTrack(ev)
            }
            sawTerminal = result.terminal
            lastError = result.error ?: lastError
            if (result.accepted || result.error == null) {
                break
            }
            if (candidatePreviousResponseId != null && isRecoverablePreviousResponseError(result.error)) {
                continue@responseCandidateLoop
            }
            break
        }
        if (lastError != null && !sawActivity) {
            Log.w("ChatStream", "Responses API fallback: $lastError")
            if (nativeMode && isHermesAuthError(lastError)) {
                emit(ChatStreamEvent.Error("Hermes ha rifiutato l'API key salvata anche dopo il retry senza autenticazione e senza previous_response_id. Verifica la chiave in Impostazioni e HERMES_API_KEY sul gateway."))
                return@flow
            }
            if (nativeMode && settings.strictNativeMode) {
                emit(ChatStreamEvent.Error("Strict native mode: Hermes Native/Responses non disponibile. $lastError"))
                return@flow
            }
            emit(ChatStreamEvent.Status("Responses API non disponibile, fallback rapido a Chat Completions..."))
        }
    }

    if (!sawActivity) {
        if (nativeMode && settings.strictNativeMode) {
            emit(ChatStreamEvent.Error("Strict native mode: Hermes Native/Responses non disponibile. Stream vuoto."))
            return@flow
        }
        emit(ChatStreamEvent.Status(if (nativeMode) "Fallback compat: Chat Completions. Strict native mode disattivato." else "Invio diretto a Hermes Chat Completions..."))
        val compatHistory = if (nativeMode) emptyList() else history
        val payload = JSONObject()
            .put("model", settings.model)
            .put("stream", true)
            .put("return_progress", true)
            .put("timings_per_token", true)
            .put("session_id", serverConversationId ?: JSONObject.NULL)
            .put("metadata", visualBlocksMetadataJson(settings, conversationId))
            .put("messages", JSONArray().apply {
                if (!nativeMode) {
                    put(
                        JSONObject()
                                .put("role", "system")
                                .put("content", (if (mode.equals("Agente", ignoreCase = true)) hermesHubAgentInstructions() else hermesHubChatInstructions()) + projectContextInstructions(settings))
                    )
                }
                if (videoMode && !nativeMode) {
                    put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", hermesHubVideoInstructions(settings))
                    )
                }
                val compatMessages = compatHistory.filter { !it.isAction }
                var includedCurrentUser = false
                compatMessages.forEachIndexed { index, msg ->
                    val isLastUser = index == compatMessages.lastIndex && msg.fromUser
                    if (isLastUser) {
                        includedCurrentUser = true
                    }
                    put(
                        JSONObject()
                            .put("role", if (msg.fromUser) "user" else "assistant")
                            .put("content", if (isLastUser) buildChatCompletionsContent(promptForModel, payloadAttachments) else msg.text)
                    )
                }
                if (!includedCurrentUser) {
                    put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", buildChatCompletionsContent(promptForModel, payloadAttachments))
                    )
                }
        })
        val url = "${settings.gatewayUrl.trimEnd('/')}/chat/completions"
        lastError = null
        val result = openSseStream(url, payload, "Hermes Chat Completions", apiKey, true, serverConversationId) { ev ->
            emitAndTrack(ev)
        }
        sawTerminal = result.terminal
        lastError = result.error ?: lastError
    }

    val totalMs = (System.nanoTime() - start) / 1_000_000.0
    if (lastError != null) {
        flushDeltaBatches()
        if (sawActivity && !lastError.orEmpty().contains("parziale", ignoreCase = true)) {
            emit(ChatStreamEvent.Error("Stream Hermes interrotto dopo una risposta parziale. $lastError"))
        } else if (!sawActivity) {
            emit(ChatStreamEvent.Error(lastError ?: "Errore stream Hermes."))
        }
        return@flow
    }
    if (!sawTerminal) {
        flushDeltaBatches()
        emit(ChatStreamEvent.Error("Stream Hermes chiuso senza evento terminale; risposta non confermata."))
        return@flow
    }
    if (!sawActivity || (!sawAnswerText && !sawVisualOutput && accumText.isEmpty())) {
        flushDeltaBatches()
        emit(ChatStreamEvent.Error("Stream Hermes completato senza contenuto risposta."))
        return@flow
    }

    flushDeltaBatches()
    for (extracted in thinkExtractor.flush()) {
        emitAndTrackInternal(extracted)
    }
    flushDeltaBatches()
    val tokensOut = completionTokens ?: max(1, accumText.length / 4)
    val tps = serverTokensPerSecond
    if (retriedWithoutPreviousResponseId && emittedResponseId.isNullOrBlank()) {
        emit(ChatStreamEvent.ResponseId(""))
    }
    emit(ChatStreamEvent.Done(ChatStreamStats(ttftMs, totalMs, tokensOut, tps, promptTokens, contextTokens, contextLength, contextPercent)))
}.flowOn(Dispatchers.IO)

private data class SseOpenResult(
    val accepted: Boolean,
    val terminal: Boolean,
    val error: String?
)

private data class AttachmentPreparation(
    val prompt: String,
    val inlineAttachments: List<ChatInputAttachment>,
    val uploadedCount: Int,
    val uploadErrors: List<String>
)

private sealed interface SseAttemptSignal {
    data object Accepted : SseAttemptSignal
    data class Event(val event: ChatStreamEvent) : SseAttemptSignal
    data class HttpFailure(val code: Int, val body: String) : SseAttemptSignal
    data class NetworkFailure(val message: String) : SseAttemptSignal
    data class Finished(val terminal: Boolean) : SseAttemptSignal
}

private suspend fun openSseStream(
    url: String,
    payload: JSONObject,
    label: String,
    apiKey: String?,
    allowCompatAuth: Boolean,
    sessionId: String? = null,
    onEvent: suspend (ChatStreamEvent) -> Unit
): SseOpenResult {
    val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
    val authCandidates = hermesAuthCandidates(apiKey, allowCompatAuth)
    var lastError: String? = null

    candidateLoop@ for (candidateUrl in plugAndPlayStreamUrlCandidates(url)) {
        for ((index, bearerToken) in authCandidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            onEvent(ChatStreamEvent.Status("$label: connessione stream..."))
            val builder = Request.Builder()
                .url(candidateUrl)
                .header("Accept", "text/event-stream, application/json")
                .header("User-Agent", "HermesHub-Android")
            bearerToken?.let { builder.header("Authorization", "Bearer $it") }
            sessionId?.takeIf { it.isNotBlank() }?.let { builder.header("X-Hermes-Session-Id", it) }
            val request = builder.post(body).build()
            var accepted = false
            var terminal = false
            var httpFailure: SseAttemptSignal.HttpFailure? = null
            var networkFailure: String? = null

            streamSseAttempt(request).collect { signal ->
                when (signal) {
                    SseAttemptSignal.Accepted -> {
                        accepted = true
                        onEvent(ChatStreamEvent.Status("$label connesso: $candidateUrl"))
                        onEvent(ChatStreamEvent.Status("Prompt inviato. Attendo primo token..."))
                    }
                    is SseAttemptSignal.Event -> onEvent(signal.event)
                    is SseAttemptSignal.HttpFailure -> httpFailure = signal
                    is SseAttemptSignal.NetworkFailure -> networkFailure = signal.message
                    is SseAttemptSignal.Finished -> terminal = signal.terminal
                }
            }

            if (accepted) {
                if (terminal) return SseOpenResult(true, true, null)
                val detail = networkFailure ?: "connessione chiusa prima dell'evento terminale"
                return SseOpenResult(true, false, "$label: stream parziale, $detail")
            }

            httpFailure?.let { failure ->
                val canRetryAuth = shouldRetrySseAuth(
                    accepted = false,
                    code = failure.code,
                    body = failure.body,
                    hasMoreAuthCandidates = index < authCandidates.lastIndex
                )
                if (canRetryAuth) {
                    onEvent(ChatStreamEvent.Status("API key Hermes non accettata. Riprovo automaticamente..."))
                    return@let
                }
                val message = if (failure.code == 401) {
                    "$label: API key rifiutata."
                } else {
                    "$label HTTP ${failure.code}: ${failure.body.take(240)}"
                }
                return SseOpenResult(false, false, message)
            }
            if (httpFailure != null) continue

            lastError = networkFailure ?: "$candidateUrl non raggiungibile"
            continue@candidateLoop
        }
    }
    return SseOpenResult(false, false, lastError ?: "$label: Hermes Gateway non raggiungibile.")
}

private fun streamSseAttempt(request: Request): Flow<SseAttemptSignal> = callbackFlow {
    val call = streamHttpClient.newCall(request)
    fun sendSignal(signal: SseAttemptSignal): Boolean = trySendBlocking(signal).isSuccess
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!call.isCanceled()) sendSignal(SseAttemptSignal.NetworkFailure(e.message ?: e.javaClass.simpleName))
            close()
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                response.use { current ->
                    val responseBody = current.body
                    if (!current.isSuccessful) {
                        sendSignal(SseAttemptSignal.HttpFailure(current.code, responseBody.byteStream().readUtf8Bounded().take(240)))
                        return@use
                    }
                    if (!sendSignal(SseAttemptSignal.Accepted)) return@use
                    val contentType = responseBody.contentType()?.toString().orEmpty()
                    if (!contentType.contains("event-stream", ignoreCase = true)) {
                        parseFullBody(responseBody.byteStream().readUtf8Bounded()).forEach { if (!sendSignal(SseAttemptSignal.Event(it))) return@use }
                        sendSignal(SseAttemptSignal.Finished(terminal = true))
                        return@use
                    }

                    val source = responseBody.source()
                    val dataBuffer = StringBuilder()
                    var eventName: String? = null
                    var terminalSeen = false

                    fun flushEvent(): Boolean {
                        if (dataBuffer.isEmpty()) return true
                        val data = dataBuffer.toString()
                        terminalSeen = terminalSeen || isTerminalSseEvent(eventName, data)
                        val parsed = parseSseData(eventName, data)
                        dataBuffer.clear()
                        eventName = null
                        return parsed.all { sendSignal(SseAttemptSignal.Event(it)) }
                    }

                    while (!call.isCanceled()) {
                        val line = source.readUtf8LineBounded(SSE_LINE_MAX_BYTES) ?: break
                        if (line.isEmpty()) {
                            if (!flushEvent()) return@use
                            continue
                        }
                        when {
                            line.startsWith(":") -> Unit
                            line.startsWith("event:", ignoreCase = true) -> eventName = line.substring(6).trim()
                            line.startsWith("data:", ignoreCase = true) -> {
                                val part = line.substring(5).let { if (it.startsWith(" ")) it.drop(1) else it }
                                val separatorLength = if (dataBuffer.isNotEmpty()) 1 else 0
                                if (dataBuffer.length + separatorLength + part.length > STREAM_ACCUM_MAX_CHARS) {
                                    throw PayloadTooLargeException("Evento SSE superiore al limite consentito")
                                }
                                if (separatorLength > 0) dataBuffer.append('\n')
                                dataBuffer.append(part)
                            }
                        }
                    }
                    if (!call.isCanceled() && !flushEvent()) return@use
                    if (!call.isCanceled()) sendSignal(SseAttemptSignal.Finished(terminalSeen))
                }
            } catch (ex: Exception) {
                if (!call.isCanceled()) sendSignal(SseAttemptSignal.NetworkFailure(ex.message ?: ex.javaClass.simpleName))
            } finally {
                close()
            }
        }
    })
    awaitClose { call.cancel() }
}

internal fun shouldRetrySseAuth(
    accepted: Boolean,
    code: Int,
    body: String,
    hasMoreAuthCandidates: Boolean
): Boolean = !accepted && hasMoreAuthCandidates && shouldRetryHermesWithBearerAuth(code, body)

internal fun isTerminalSseEvent(eventName: String?, data: String): Boolean {
    if (data.trim() == "[DONE]") return true
    val eventType = eventName.orEmpty().lowercase()
    if (eventType.contains("response.completed") || eventType.contains("response.done")) return true
    val root = runCatching { JSONObject(data) }.getOrNull() ?: return false
    val type = root.optString("type", "").lowercase()
    if (type.contains("response.completed") || type.contains("response.done")) return true
    val choices = root.optJSONArray("choices") ?: return false
    return (0 until choices.length()).any { index ->
        choices.optJSONObject(index)?.optString("finish_reason").orEmpty().isNotBlank()
    }
}

private fun buildMultimodalInput(prompt: String, attachments: List<ChatInputAttachment>): Any {
    if (attachments.isEmpty()) return prompt
    val content = JSONArray()
        .put(JSONObject().put("type", "input_text").put("text", prompt))
    attachments.forEach { attachment ->
        if (attachment.mimeType.startsWith("image/", ignoreCase = true)) {
            content.put(
                JSONObject()
                    .put("type", "input_image")
                    .put("image_url", attachment.inlineDataUrl())
                    .put("detail", "auto")
            )
        } else {
            content.put(
                JSONObject()
                    .put("type", "input_file")
                    .put("filename", attachment.filename)
                    .put("file_data", attachment.inlineDataUrl())
            )
        }
    }
    return JSONArray().put(JSONObject().put("role", "user").put("content", content))
}

private fun buildChatCompletionsContent(prompt: String, attachments: List<ChatInputAttachment>): Any {
    if (attachments.isEmpty()) return prompt
    val content = JSONArray()
        .put(JSONObject().put("type", "text").put("text", prompt))
    attachments.forEach { attachment ->
        if (attachment.mimeType.startsWith("image/", ignoreCase = true)) {
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject()
                            .put("url", attachment.inlineDataUrl())
                            .put("detail", "auto")
                    )
            )
        } else {
            content.put(
                JSONObject()
                    .put("type", "text")
                    .put("text", "Allegato file: ${attachment.filename} (${attachment.mimeType}, ${attachment.sizeBytes} bytes). Se serve il contenuto binario, usa Responses API/input_file.")
            )
        }
    }
    return content
}

private fun ChatInputAttachment.inlineDataUrl(): String {
    if (dataUrl.isNotBlank()) return dataUrl
    val path = localFilePath?.takeIf { it.isNotBlank() }
        ?: throw IOException("Payload locale assente per $filename")
    val file = File(path)
    if (!file.isFile || file.length() <= 0L || file.length() > INLINE_ATTACHMENT_MAX_BYTES) {
        throw IOException("Payload locale non valido o troppo grande per $filename")
    }
    val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    return "data:$mimeType;base64,$encoded"
}

private suspend fun buildPromptWithAttachmentToolRefs(
    settings: AppSettings,
    prompt: String,
    attachments: List<ChatInputAttachment>,
    apiKey: String?
): AttachmentPreparation {
    if (attachments.isEmpty()) return AttachmentPreparation(prompt, emptyList(), 0, emptyList())
    val refs = attachments.map { uploadAttachmentForTool(settings, it, apiKey) }
    val uploadedIndexes = refs.indices.filter { refs[it].error.isNullOrBlank() }.toSet()
    val uploaded = refs.filterIndexed { index, _ -> index in uploadedIndexes }
    val remaining = attachments.filterIndexed { index, _ -> index !in uploadedIndexes }
    val errors = refs.mapNotNull { ref -> ref.error?.let { "${ref.filename}: $it" } }
    if (uploaded.isEmpty()) return AttachmentPreparation(prompt, attachments, 0, errors)
    val text = buildString {
        append(prompt.trimEnd())
        append("\n\n")
        append("Allegati caricati e disponibili sul server Hermes:\n")
        uploaded.forEach { item ->
            append("- ")
            append(item.filename)
            item.path?.takeIf { it.isNotBlank() }?.let {
                append(" | path server: ")
                append(it)
            }
            item.mediaUrl?.takeIf { it.isNotBlank() }?.let {
                append(" | URL proxy fallback: ")
                append(it)
            }
            append('\n')
        }
        append("Per le immagini usa vision_analyze con esattamente il path server indicato. Per gli altri file usa il path o URL proxy riportato.\n")
        append("Non inventare path o URL. Se un tool fallisce, riporta l'errore tecnico.")
    }
    return AttachmentPreparation(text, remaining, uploaded.size, errors)
}

private suspend fun uploadAttachmentForTool(settings: AppSettings, attachment: ChatInputAttachment, apiKey: String?): UploadedAttachmentRef {
    val endpoint = "${settings.gatewayUrl.trimEnd('/')}/media/upload"
    val body = attachment.streamingJsonUploadBody()
    val authCandidates = hermesAuthCandidates(apiKey, true)
    var lastError = "gateway non raggiungibile"
    candidateLoop@ for (candidateUrl in plugAndPlayStreamUrlCandidates(endpoint)) {
        for ((index, token) in authCandidates.withIndex()) {
            val builder = Request.Builder()
                .url(candidateUrl)
                .header("Accept", "application/json")
                .header("User-Agent", "HermesHub-Android")
                .post(body)
            token?.let { builder.header("Authorization", "Bearer $it") }
            val response = executeCancellableRequest(builder.build())
            if (response.first == 0) {
                lastError = response.second
                continue@candidateLoop
            }
            if (response.first !in 200..299) {
                if (index < authCandidates.lastIndex && shouldRetryHermesWithBearerAuth(response.first, response.second)) {
                    continue
                }
                lastError = "HTTP ${response.first}: ${response.second.take(120)}"
                continue@candidateLoop
            }
            val root = runCatching { JSONObject(response.second) }.getOrElse {
                return UploadedAttachmentRef(attachment.filename, attachment.mimeType, null, null, "risposta upload non valida")
            }
            val media = root.optString("media_url", root.optString("url")).takeIf { it.isNotBlank() }?.let { value ->
                if (value.startsWith("/")) "${gatewayOrigin(candidateUrl)}$value" else value
            }
            return UploadedAttachmentRef(
                filename = root.optString("filename", attachment.filename),
                mimeType = root.optString("mime_type", attachment.mimeType),
                path = root.optString("path", root.optString("server_path")).takeIf { it.isNotBlank() },
                mediaUrl = media,
                error = null
            )
        }
    }
    return UploadedAttachmentRef(attachment.filename, attachment.mimeType, null, null, lastError)
}

private fun ChatInputAttachment.streamingJsonUploadBody(): RequestBody = object : RequestBody() {
    override fun contentType() = "application/json; charset=utf-8".toMediaType()

    override fun writeTo(sink: BufferedSink) {
        val file = localFilePath?.let(::File)?.takeIf { it.isFile }
        if (file == null) {
            val payload = JSONObject()
                .put("filename", filename)
                .put("mime_type", mimeType)
                .put("data_url", dataUrl)
            sink.writeUtf8(payload.toString())
            return
        }
        sink.writeUtf8("{\"filename\":${JSONObject.quote(filename)},\"mime_type\":${JSONObject.quote(mimeType)},\"data_url\":\"data:${mimeType};base64,")
        sink.flush()
        val encoder = Base64OutputStream(sink.outputStream(), Base64.NO_WRAP or Base64.NO_CLOSE)
        file.inputStream().use { input -> input.copyTo(encoder) }
        encoder.close()
        sink.writeUtf8("\"}")
    }
}

internal fun gatewayOrigin(gatewayUrl: String): String {
    return runCatching {
        val uri = URI(gatewayUrl)
        "${uri.scheme}://${uri.rawAuthority}"
    }.getOrElse {
        val root = gatewayUrl.trimEnd('/')
        if (root.endsWith("/v1", ignoreCase = true)) root.dropLast(3) else root
    }
}

private fun runDetachedAgent(
    settings: AppSettings,
    prompt: String,
    history: List<ChatMessage>,
    conversationId: String?,
    attachments: List<ChatInputAttachment>,
    apiKey: String?
): Flow<ChatStreamEvent> = flow {
    val startedAt = System.nanoTime()
    val payload = JSONObject()
        .put("model", settings.model)
        .put("input", buildMultimodalInput(prompt, attachments))
        .put("instructions", hermesHubAgentInstructions() + projectContextInstructions(settings))
        .put("session_id", hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, conversationId) ?: JSONObject.NULL)
        .put("metadata", visualBlocksMetadataJson(settings, conversationId))
        .put("conversation_history", JSONArray(history.takeLast(30).mapNotNull { msg ->
            if (msg.isAction || (msg.fromUser && msg.text == prompt)) {
                null
            } else {
                JSONObject()
                    .put("role", if (msg.fromUser) "user" else "assistant")
                    .put("content", msg.text)
            }
        }))
    emit(ChatStreamEvent.Status("Modalita Agente: avvio run server-side persistente..."))
    val startResponse = executeRunJsonRequest("${settings.gatewayUrl.trimEnd('/')}/runs", payload, apiKey, "POST")
    if (startResponse.first !in 200..299) {
        emit(ChatStreamEvent.Error("Hermes Runs HTTP ${startResponse.first}: ${startResponse.second.take(240)}"))
        return@flow
    }
    val runId = runCatching { JSONObject(startResponse.second).optString("run_id") }.getOrDefault("")
    if (runId.isBlank()) {
        emit(ChatStreamEvent.Error("Hermes Runs: run_id assente nella risposta."))
        return@flow
    }
    emit(ChatStreamEvent.RunId(runId))
    emit(ChatStreamEvent.Status("Run server-side avviata: $runId. Se chiudi l'app, Hermes continua sul server."))

    var lastStatus = ""
    var finalText = ""
    var consecutiveFailures = 0
    val pollDeadline = System.currentTimeMillis() + RUN_POLL_TIMEOUT_MS
    while (true) {
        if (System.currentTimeMillis() >= pollDeadline) {
            emit(ChatStreamEvent.Error("Run $runId ancora attiva dopo 30 minuti: polling locale terminato, controlla lo stato sul gateway."))
            return@flow
        }
        kotlinx.coroutines.delay((2_000L * (consecutiveFailures + 1)).coerceAtMost(10_000L))
        val statusResponse = executeRunJsonRequest("${settings.gatewayUrl.trimEnd('/')}/runs/$runId", null, apiKey, "GET")
        if (statusResponse.first == 404) {
            consecutiveFailures++
            val message = "Run $runId non piu' in cache gateway; se era lunga puo' ancora lavorare sul processo Hermes."
            if (message != lastStatus) {
                lastStatus = message
                emit(ChatStreamEvent.Status(message))
            }
            if (consecutiveFailures >= RUN_POLL_MAX_CONSECUTIVE_FAILURES) {
                emit(ChatStreamEvent.Error("Run $runId non disponibile sul gateway dopo $consecutiveFailures tentativi."))
                return@flow
            }
            continue
        }
        if (statusResponse.first !in 200..299) {
            consecutiveFailures++
            val message = "Run $runId polling HTTP ${statusResponse.first}"
            if (message != lastStatus) {
                lastStatus = message
                emit(ChatStreamEvent.Status(message))
            }
            if (consecutiveFailures >= RUN_POLL_MAX_CONSECUTIVE_FAILURES) {
                emit(ChatStreamEvent.Error("Run $runId: polling fallito dopo $consecutiveFailures tentativi (${statusResponse.first})."))
                return@flow
            }
            continue
        }
        consecutiveFailures = 0
        val root = JSONObject(statusResponse.second)
        val state = root.optString("status", "running")
        val lastEvent = root.optString("last_event")
        val message = if (lastEvent.isBlank()) "Run $runId: $state" else "Run $runId: $state ($lastEvent)"
        if (message != lastStatus) {
            lastStatus = message
            emit(ChatStreamEvent.Status(message))
        }
        finalText = root.optString("output", finalText)
        if (state == "completed" || state == "failed" || state == "cancelled") {
            extractVisualBlocks(statusResponse.second).takeIf { it.isNotEmpty() }?.let {
                emit(ChatStreamEvent.VisualBlocks(it, VISUAL_BLOCKS_VERSION))
            }
            if (finalText.isNotBlank()) {
                emit(ChatStreamEvent.TextSnapshot(finalText))
            }
            if (state != "completed") {
                emit(ChatStreamEvent.Error(root.optString("error", "Run $runId: $state")))
                return@flow
            }
            val totalMs = (System.nanoTime() - startedAt) / 1_000_000.0
            val usage = root.optJSONObject("usage")
            val completionTokens = usage?.optInt("output_tokens")?.takeIf { it > 0 } ?: max(1, finalText.length / 4)
            val promptTokens = usage?.optInt("input_tokens")?.takeIf { it > 0 }
            emit(ChatStreamEvent.Done(ChatStreamStats(null, totalMs, completionTokens, null, promptTokens, null, null, null)))
            return@flow
        }
    }
}.flowOn(Dispatchers.IO)

private suspend fun executeRunJsonRequest(url: String, payload: JSONObject?, apiKey: String?, method: String): Pair<Int, String> {
    var last: Pair<Int, String>? = null
    val authCandidates = hermesAuthCandidates(apiKey, true)
    for (candidateUrl in plugAndPlayStreamUrlCandidates(url)) {
        for ((index, token) in authCandidates.withIndex()) {
            val builder = Request.Builder()
                .url(candidateUrl)
                .header("Accept", "application/json")
                .header("User-Agent", "HermesHub-Android")
            token?.let { builder.header("Authorization", "Bearer $it") }
            val request = when (method.uppercase()) {
                "GET" -> builder.get().build()
                else -> builder.post((payload ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            }
            try {
                val response = executeCancellableRequest(request)
                last = response
                if (index < authCandidates.lastIndex && shouldRetryHermesWithBearerAuth(response.first, response.second)) {
                    continue
                }
                return response
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                last = 0 to (ex.message ?: ex.javaClass.simpleName)
            }
        }
    }
    return last ?: (0 to "")
}

internal suspend fun stopHermesRun(settings: AppSettings, runId: String, apiKey: String?): Pair<Int, String> = withContext(Dispatchers.IO) {
    if (runId.isBlank()) {
        return@withContext 0 to "run_id assente"
    }
    executeRunJsonRequest("${settings.gatewayUrl.trimEnd('/')}/runs/$runId/stop", JSONObject().put("reason", "user_cancelled"), apiKey, "POST")
}

private fun plugAndPlayStreamUrlCandidates(url: String): List<String> {
    return try {
        val uri = URI(url)
        if (uri.port != 8642) return listOf(url)
        val suffix = buildString {
            append(uri.rawPath.orEmpty())
            if (!uri.rawQuery.isNullOrBlank()) append("?").append(uri.rawQuery)
        }
        val currentRoot = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}".trimEnd('/')
        (listOf(currentRoot) + plugAndPlayStreamGatewayRoots)
            .distinctBy { it.lowercase() }
            .map { it.trimEnd('/') + suffix }
    } catch (_: Exception) {
        listOf(url)
    }
}

internal fun parseSseData(eventName: String?, data: String): List<ChatStreamEvent> {
    if (data.isEmpty() || data.trim() == "[DONE]") return emptyList()
    val json = try { JSONObject(data) } catch (_: Exception) {
        return listOf(ChatStreamEvent.TextDelta(data))
    }
    val parsed = parseEventObject(eventName, json)
    return if (parsed.isEmpty()) {
        listOf(ChatStreamEvent.RawHermesEvent(eventName ?: json.optString("type", "hermes.event"), json.toString()))
    } else {
        parsed
    }
}

private fun parseEventObject(eventName: String?, obj: JSONObject): List<ChatStreamEvent> {
    val out = mutableListOf<ChatStreamEvent>()
    obj.promptProgressEventOrNull()?.let { out += it }
    obj.tokensPerSecondOrNull()?.let { out += ChatStreamEvent.Usage(null, null, it) }
    val type = eventName ?: obj.optString("type", "")
    val t = type.lowercase()

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
            if (obj.optBoolean("estimated", false)) {
                return out
            }
            val percent = obj.optProgressPercent()
            if (percent != null) {
                out += ChatStreamEvent.PromptProgress(percent, "Elaborazione prompt")
            }
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
        t.contains("output_text") && (t.contains("done") || t.contains("completed")) -> {
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

private fun isToolEnvelopeType(type: String): Boolean {
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

private fun isToolPayload(obj: JSONObject, type: String): Boolean {
    if (isToolEnvelopeType(type)) return true
    val objectType = obj.optString("type", "").lowercase()
    if (isToolEnvelopeType(objectType)) return true
    if (obj.has("tool") || obj.has("tool_call_id") || obj.has("toolCallId") || obj.has("call_id")) return true
    return obj.has("is_error") && (obj.has("result") || obj.has("output"))
}

private fun streamHermesNativeInstructions(mode: String): String {
    return ""
}

private fun isVideoRequest(prompt: String): Boolean {
    val text = prompt.lowercase()
    return listOf("video", "filmato", "clip", "montaggio", "remotion", "youtube", "mp4", "render").any { text.contains(it) }
}

private fun hermesHubVideoInstructions(settings: AppSettings): String {
    val path = settings.videoLibraryPath.ifBlank { "HERMES_VIDEO_LIBRARY_PATH" }
    return """
        System prompt video Hermes Hub:
        - La sezione Video e' un watched-folder feed, non dipende dalla chat.
        - La cartella ufficiale sul PC Hermes e': $path
        - Quando l'utente chiede creazione, download, montaggio, rendering o preparazione di un video, salva sempre il file video finale in quella cartella.
        - Ogni file video comune (.mp4/.m4v/.mov/.mkv/.webm/.avi/.wmv/.flv/.mpg/.mpeg/.ts/.m2ts/.3gp/.ogv) messo in quella cartella apparira' automaticamente nella sezione Video tramite /v1/video/library.
        - Per massima compatibilita crea preferibilmente MP4 H.264 + AAC + yuv420p + faststart; il gateway puo' anche servire playback compat con ?format=mp4.
        - Se mostri anche il video in chat, pubblicalo tramite proxy /v1/media/... come visual_blocks media_file; non scrivere file:// o path locali come risposta finale.
        - Se non riesci a generare/renderizzare il video, spiega il blocco concreto e non fingere di averlo mandato.
    """.trimIndent()
}

private fun extractToolEventsFromAnyJson(obj: JSONObject): List<ChatStreamEvent> {
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

private fun extractToolOutput(obj: JSONObject): String {
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

private const val JSON_EXTRACT_MAX_DEPTH = 10

private fun extractTextFromAnyJson(obj: JSONObject, depth: Int = 0): String {
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

private suspend fun executeCancellableRequest(request: Request): Pair<Int, String> =
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

private fun isNonAssistantTextPayload(obj: JSONObject): Boolean {
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

private fun extractReasoningText(value: Any?, insideReasoning: Boolean = false): String {
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

private fun parseFullBody(body: String): List<ChatStreamEvent> {
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

private val INLINE_MEDIA_REGEX = Regex("""(?is)MEDIA\s*:\s*\[([^\]]{1,500})]\(([^)\s]{1,1200})\)|!\[([^\]]{1,500})]\(([^)\s]{1,1200})\)""")
private val MEDIA_PROXY_LINE_REGEX = Regex("""(?im)^\s*(?:Media\s+proxy\s+URL|Media\s+URL|Download\s+URL|File\s+URL|URL\s+media|Link\s+download)\s*:\s*([^\s<>"'`]{1,1200})\s*$""")
private val RAW_MEDIA_PROXY_URL_REGEX = Regex("""(?i)(?:https?://[^\s<>)"']+/v1/media/[^\s<>)"']{1,1200}|/v1/media/[^\s<>)"']{1,1200})""")
private val RAW_IMAGE_URL_REGEX = Regex("""(?i)\bhttps://[^\s<>)"']{6,1200}""")

private fun extractInlineMediaBlocks(text: String): List<VisualBlock> {
    val explicit = INLINE_MEDIA_REGEX.findAll(text).take(12).mapIndexed { index, match ->
        val label = (match.groups[1]?.value ?: match.groups[3]?.value ?: "Media Hermes").trim()
        val url = (match.groups[2]?.value ?: match.groups[4]?.value ?: "").trim()
        createInlineMediaBlock(index, label, url, explicit = true)
    }.toList()

    val seen = explicit.map { it.mediaUrl }.toMutableSet()
    val proxy = MEDIA_PROXY_LINE_REGEX.findAll(text)
        .map { it.groups[1]?.value.orEmpty().trimEnd('.', ',', ';', ':') }
        .plus(RAW_MEDIA_PROXY_URL_REGEX.findAll(text).map { it.value.trimEnd('.', ',', ';', ':') })
        .filter { it.isNotBlank() && it !in seen && isSafeInlineMediaUrl(it) }
        .distinct()
        .take(12 - explicit.size)
        .mapIndexed { index, url ->
            seen += url
            createInlineMediaBlock(
                index + explicit.size,
                inferMediaFilename("file-hermes", url).ifBlank { "File Hermes" },
                url,
                explicit = true
            )
        }
        .toList()
    val raw = RAW_IMAGE_URL_REGEX.findAll(text)
        .map { it.value.trimEnd('.', ',', ';', ':') }
        .filter { it !in seen && isLikelyRemoteImageUrl(it) }
        .take(12 - explicit.size - proxy.size)
        .mapIndexed { index, url ->
            seen += url
            createInlineMediaBlock(index + explicit.size + proxy.size, inferMediaFilename("immagine", url).ifBlank { "Immagine" }, url, explicit = false)
        }
        .toList()
    return explicit + proxy + raw
}

private fun createInlineMediaBlock(index: Int, label: String, url: String, explicit: Boolean): VisualBlock {
        val filename = inferMediaFilename(label, url)
        val kind = inferMediaKind(filename, url)
        return VisualBlock(
            id = "inline-media-${stableInlineId(url, index)}",
            type = "media_file",
            title = filename.ifBlank { "File multimediale" },
            mediaUrl = url,
            mediaKind = kind,
            mimeType = inferMimeType(filename, url),
            filename = filename,
            thumbnailUrl = "",
            alt = label.ifBlank { filename.ifBlank { "Media Hermes" } },
            caption = if (url.startsWith("file://", ignoreCase = true) || url.contains(":\\") || label.contains(":\\")) {
                "Hermes ha restituito un path locale. Per mostrarlo su Android deve pubblicarlo tramite proxy /v1/media/..."
            } else if (!explicit) {
                "Immagine rilevata da link HTTPS nella risposta Hermes."
            } else {
                "Media rilevato dal testo Hermes."
            }
        )
}

private fun stripInlineMediaMarkup(text: String): String {
    return INLINE_MEDIA_REGEX
        .replace(text, "")
        .let { MEDIA_PROXY_LINE_REGEX.replace(it, "") }
        .let { RAW_MEDIA_PROXY_URL_REGEX.replace(it, "") }
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

private fun stableInlineId(value: String, index: Int): String {
    val hash = value.fold(17) { acc, ch -> acc * 31 + ch.code }
    return "${index}-${hash.toUInt().toString(16)}"
}

private fun inferMediaFilename(label: String, url: String): String {
    val candidate = label.takeIf { it.contains('.') } ?: url.substringAfterLast('/').substringAfterLast('\\')
    return candidate.substringBefore('?').substringBefore('#').take(160)
}

private fun inferMediaKind(filename: String, url: String): String {
    val value = "$filename $url".lowercase()
    return when {
        listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp").any { value.contains(it) } -> "image"
        isLikelyRemoteImageUrl(url) -> "image"
        listOf(".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi", ".wmv", ".flv", ".mpg", ".mpeg", ".ts", ".m2ts", ".3gp", ".ogv").any { value.contains(it) } -> "video"
        listOf(".mp3", ".wav", ".m4a", ".flac", ".ogg").any { value.contains(it) } -> "audio"
        else -> "document"
    }
}

private fun inferMimeType(filename: String, url: String): String {
    val value = "$filename $url".lowercase()
    return when {
        value.contains(".png") -> "image/png"
        value.contains(".jpg") || value.contains(".jpeg") -> "image/jpeg"
        value.contains(".webp") -> "image/webp"
        value.contains(".gif") -> "image/gif"
        isLikelyRemoteImageUrl(url) -> "image/*"
        value.contains(".mp4") || value.contains(".m4v") -> "video/mp4"
        value.contains(".mov") -> "video/quicktime"
        value.contains(".webm") -> "video/webm"
        value.contains(".mkv") -> "video/x-matroska"
        value.contains(".avi") -> "video/x-msvideo"
        value.contains(".wmv") -> "video/x-ms-wmv"
        value.contains(".flv") -> "video/x-flv"
        value.contains(".mpg") || value.contains(".mpeg") -> "video/mpeg"
        value.contains(".ts") || value.contains(".m2ts") -> "video/mp2t"
        value.contains(".3gp") -> "video/3gpp"
        value.contains(".ogv") -> "video/ogg"
        value.contains(".mp3") -> "audio/mpeg"
        value.contains(".wav") -> "audio/wav"
        value.contains(".m4a") -> "audio/mp4"
        value.contains(".flac") -> "audio/flac"
        value.contains(".ogg") -> "audio/ogg"
        value.contains(".pdf") -> "application/pdf"
        value.contains(".md") || value.contains(".markdown") -> "text/markdown"
        value.contains(".txt") -> "text/plain"
        value.contains(".csv") -> "text/csv"
        value.contains(".json") -> "application/json"
        value.contains(".html") || value.contains(".htm") -> "text/html"
        value.contains(".zip") -> "application/zip"
        else -> ""
    }
}

private fun isLikelyRemoteImageUrl(url: String): Boolean {
    val value = url.lowercase()
    if (!value.startsWith("https://")) return false
    return listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp").any { value.substringBefore('?').substringBefore('#').endsWith(it) } ||
        listOf("picsum.photos", "images.unsplash.com", "unsplash.com", "pexels.com", "pixabay.com", "cloudinary.com", "image", "photo").any { value.contains(it) }
}

private fun isSafeInlineMediaUrl(value: String): Boolean {
    if (value.isBlank()) return false
    if (value.startsWith("/v1/media/", ignoreCase = true)) return true
    return try {
        val uri = URI(value)
        (uri.scheme == "http" || uri.scheme == "https") && uri.path.startsWith("/v1/media/")
    } catch (_: Exception) {
        false
    }
}

private fun extractVisualBlocksFromJson(json: String): List<VisualBlock> {
    return try { extractVisualBlocks(json) } catch (_: Exception) { emptyList() }
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return try { getInt(key) } catch (_: Exception) { null }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return try { getDouble(key) } catch (_: Exception) { null }
}

private fun JSONObject.optProgressPercent(): Int? {
    optIntOrNull("percent")?.let { return it.coerceIn(0, 100) }
    val progress = optDoubleOrNull("progress") ?: return null
    val value = if (progress <= 1.0) progress * 100.0 else progress
    return value.roundToInt().coerceIn(0, 100)
}

private fun JSONObject.promptProgressEventOrNull(): ChatStreamEvent.PromptProgress? {
    if (optBoolean("estimated", false)) return null
    val progress = optJSONObject("prompt_progress") ?: return null
    val total = progress.optIntOrNull("total")?.takeIf { it > 0 } ?: return null
    val processed = progress.optIntOrNull("processed") ?: return null
    val cache = progress.optIntOrNull("cache")
    val timeMs = progress.optDoubleOrNull("time_ms")
    val percent = ((processed.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    return ChatStreamEvent.PromptProgress(
        percent = percent,
        label = "Elaborazione prompt",
        estimated = false,
        processedTokens = processed,
        totalTokens = total,
        cachedTokens = cache,
        timeMs = timeMs
    )
}

private fun JSONObject.tokensPerSecondOrNull(): Double? {
    val direct = optDoubleOrNull("tokens_per_second")
        ?: optDoubleOrNull("predicted_per_second")
        ?: optDoubleOrNull("generation_tokens_per_second")
    if (direct != null) return validateTokensPerSecond(direct)
    val timings = optJSONObject("timings") ?: return null
    return validateTokensPerSecond(
        timings.optDoubleOrNull("predicted_per_second")
            ?: timings.optDoubleOrNull("tokens_per_second")
    )
}

private fun visualBlocksMetadataJson(settings: AppSettings, conversationId: String?): JSONObject {
    val serverConversationId = hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, conversationId)
    return JSONObject()
        .put("client", "hermes-hub")
        .put("client_surface", HERMES_HUB_ANDROID_SURFACE)
        .put("hub_client", true)
        .put("requested_protocol", settings.preferredApi)
        .put("strict_native_mode", settings.strictNativeMode)
        .put("profile", "user")
        .put("project_id", settings.activeProjectId)
        .put("project_name", settings.activeProjectName)
        .put("workspace", settings.activeProjectName.ifBlank { "default" })
        .put(
            "project_context",
            if (settings.activeProjectId.isBlank()) JSONObject.NULL else JSONObject()
                .put("id", settings.activeProjectId)
                .put("name", settings.activeProjectName)
                .put("workspace_path", settings.activeProjectWorkspacePath)
                .put("repository_url", settings.activeProjectRepositoryUrl)
                .put("instructions", settings.activeProjectInstructions)
                .put("memory", settings.activeProjectMemory)
                .put("authorized_tools", JSONArray(settings.activeProjectTools.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()))
        )
        .put(
            "hub_conversation",
            JSONObject()
                .put("id", serverConversationId ?: JSONObject.NULL)
                .put("local_id", conversationId ?: JSONObject.NULL)
                .put("surface", HERMES_HUB_ANDROID_SURFACE)
                .put("scope", "per-chat-per-surface")
                .put("isolation_required", true)
                .put("do_not_merge_with_other_conversations", true)
                .put("do_not_merge_with_other_surfaces", true)
                .put("shared_memory_policy", "Only stable user preferences may be shared; transient chat context must stay in this conversation id.")
        )
        .put(
            "memory_policy",
            JSONObject()
                .put("scope", "shared-hermes-agent-memory")
                .put("share_with_cli", true)
                .put("use_server_memory_tools", true)
                .put("do_not_create_app_only_memory", true)
                .put("runtime_context_scope", "isolated_conversation")
                .put("do_not_use_other_active_chats_as_context", true)
                .put("context_owner", if (isHermesNative(settings)) "hermes-agent" else "client-compat")
        )
        .put(
            "native_context",
            JSONObject()
                .put("delegated", isHermesNative(settings))
                .put("conversation_id_required", true)
                .put("client_history_is_snapshot_only", isHermesNative(settings))
                .put("client_context_meter", if (isHermesNative(settings)) "server-authoritative" else "local-estimate")
        )
        .put(
            "hub_sections",
            JSONObject()
                .put("chat", "Conversazione principale Hermes Hub.")
                .put("video", "Feed personale video: Hermes conosce video_library_path/HERMES_VIDEO_LIBRARY_PATH; ogni video creato/scaricato per l'utente deve essere salvato o registrato li, desktop mostra file locali, app salva feedback e metadata.")
                .put("news", "Feed personale articoli: Hermes produce articoli con fonti; se crea HTML/giornale online salva in ${settings.newsLibraryPath} per /v1/news/library; app salva feedback.")
                .put("notifications", "Inbox notifiche: cron/agenti devono usare POST /v1/hub/notifications per avvisi importanti quando l'app non e' aperta.")
                .put("jobs", "Coda Hermes Jobs condivisa con CLI/server.")
                .put("runs", "Runs operative Hermes.")
        )
        .put(
            "notification_contract",
            JSONObject()
                .put("endpoint", "/v1/hub/notifications")
                .put("required_behavior", "When a cron, monitor or long-running agent finds something the user must know, create a notification with title, message, severity, source and conversation_prompt. Keep it concise and self-contained.")
        )
        .put("news_library_path", settings.newsLibraryPath)
        .put(
            "news_contract",
            JSONObject()
                .put("mode", "watched-folder")
                .put("library_path", settings.newsLibraryPath)
                .put("required_behavior", "When the user asks for news, articles, briefings, online newspapers or HTML pages, store the final HTML file in news_library_path/HERMES_NEWS_LIBRARY_PATH, let /v1/news/library expose it, and use media proxy if referenced in chat.")
        )
        .put(
            "activity_stream",
            JSONObject()
                .put("requested", true)
                .put("include_reasoning", true)
                .put("include_tool_calls", true)
                .put("include_tool_results", true)
                .put("include_intermediate_model_calls", true)
                .put("client_requires_realtime_visibility", true)
        )
        .put("video_library_path", settings.videoLibraryPath)
        .put(
            "video_contract",
            JSONObject()
                .put("mode", "watched-folder")
                .put("library_path", settings.videoLibraryPath)
                .put("required_behavior", "When the user asks for video creation/download/editing, store the final video file in video_library_path/HERMES_VIDEO_LIBRARY_PATH and expose it through media proxy if referenced in chat.")
        )
        .put(
            "visual_blocks",
            JSONObject()
                .put("min_supported_version", VISUAL_BLOCKS_VERSION)
                .put("max_supported_version", VISUAL_BLOCKS_VERSION)
                .put("mode", settings.visualBlocksMode)
                .put("image_gallery", "supported via /v1/media proxy URLs only")
                .put("media_file", "supported for image/video/audio/document via safe proxy URLs; include media_kind, mime_type, filename, size_bytes, duration_ms, thumbnail_url when known")
        )
}

private fun stripReasoningArtifacts(text: String): String {
    if (text.isEmpty()) return ""
    return text
        .replace(Regex("<\\s*think\\s*>.*?<\\s*/\\s*think\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        .replace(Regex("<\\s*think\\s*>.*\\z", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        .replace(Regex("<\\s*/?\\s*think\\s*>", RegexOption.IGNORE_CASE), "")
        .trimStart('\r', '\n')
}

internal class ThinkExtractor {
    private companion object {
        const val MAX_THINK_TAG_FRAGMENT_CHARS = 24
        val OPEN_THINK_TAG = Regex("<\\s*think\\s*>", RegexOption.IGNORE_CASE)
        val CLOSE_THINK_TAG = Regex("<\\s*/\\s*think\\s*>", RegexOption.IGNORE_CASE)
    }

    private val buffer = StringBuilder()
    private var inThink = false

    fun process(delta: String): List<ChatStreamEvent> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(delta)
        var text = buffer.toString()
        val events = mutableListOf<ChatStreamEvent>()

        while (true) {
            if (!inThink) {
                val start = OPEN_THINK_TAG.find(text)
                if (start != null) {
                    if (start.range.first > 0) {
                        events.add(ChatStreamEvent.TextDelta(text.substring(0, start.range.first)))
                    }
                    text = text.substring(start.range.last + 1)
                    buffer.clear()
                    buffer.append(text)
                    inThink = true
                } else {
                    val safeLen = maxOf(0, text.length - MAX_THINK_TAG_FRAGMENT_CHARS)
                    if (safeLen > 0) {
                        events.add(ChatStreamEvent.TextDelta(text.substring(0, safeLen)))
                        text = text.substring(safeLen)
                        buffer.clear()
                        buffer.append(text)
                    }
                    break
                }
            } else {
                val end = CLOSE_THINK_TAG.find(text)
                if (end != null) {
                    if (end.range.first > 0) {
                        events.add(ChatStreamEvent.ThinkingDelta(text.substring(0, end.range.first)))
                    }
                    text = text.substring(end.range.last + 1)
                    buffer.clear()
                    buffer.append(text)
                    inThink = false
                } else {
                    val safeLen = maxOf(0, text.length - MAX_THINK_TAG_FRAGMENT_CHARS)
                    if (safeLen > 0) {
                        events.add(ChatStreamEvent.ThinkingDelta(text.substring(0, safeLen)))
                        text = text.substring(safeLen)
                        buffer.clear()
                        buffer.append(text)
                    }
                    break
                }
            }
        }
        return events
    }

    fun flush(): List<ChatStreamEvent> {
        val text = buffer.toString()
        val events = mutableListOf<ChatStreamEvent>()
        if (text.isNotEmpty()) {
            if (inThink) events.add(ChatStreamEvent.ThinkingDelta(text))
            else events.add(ChatStreamEvent.TextDelta(text))
        }
        buffer.clear()
        return events
    }

    fun processSnapshot(snapshot: String): List<ChatStreamEvent> {
        val pending = flush()
        val snapshotExtractor = ThinkExtractor()
        val parts = snapshotExtractor.process(snapshot) + snapshotExtractor.flush()
        val answer = buildString {
            parts.filterIsInstance<ChatStreamEvent.TextDelta>().forEach { append(it.delta) }
        }
        val reasoning = buildString {
            parts.filterIsInstance<ChatStreamEvent.ThinkingDelta>().forEach { append(it.delta) }
        }
        return buildList {
            addAll(pending)
            if (reasoning.isNotEmpty()) add(ChatStreamEvent.ThinkingSnapshot(reasoning))
            if (answer.isNotEmpty() || reasoning.isEmpty()) add(ChatStreamEvent.TextSnapshot(answer))
        }
    }
}
