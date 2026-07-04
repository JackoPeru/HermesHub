package com.nemoclaw.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.URI
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.math.max

private const val STREAM_ACCUM_MAX_CHARS = 2_000_000
private const val STREAM_UI_BATCH_MAX_CHARS = 2048
private const val STREAM_UI_BATCH_NS = 33_000_000L
private val plugAndPlayStreamGatewayRoots = listOf(
    "http://hermes:8642",
    "http://100.94.223.14:8642",
    "http://hermes.local:8642",
    "http://hermes-hub:8642",
    "http://hermeshub:8642",
    "http://home-server:8642",
    "http://server:8642",
    "http://100.105.46.6:8642"
)

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

private fun calculateStableTokensPerSecond(tokensOut: Int, firstTokenNs: Long?, lastTokenNs: Long?, totalMs: Double): Double? {
    if (tokensOut < 2) return null
    val durationMs = if (firstTokenNs != null && lastTokenNs != null) {
        ((lastTokenNs - firstTokenNs).coerceAtLeast(0L)) / 1_000_000.0
    } else {
        totalMs
    }
    if (durationMs < 200.0) return null
    return validateTokensPerSecond((tokensOut - 1).coerceAtLeast(1) / (durationMs / 1000.0))
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
                status = if (toolCalls.any { inferToolPendingStatus(it) }) status else "Hermes sta scrivendo la risposta...",
                thinkingFrozen = thinkingFrozen || hasThinking,
                thinkingElapsedSec = if (!thinkingFrozen && hasThinking) {
                    (System.nanoTime() - startedAtNs) / 1_000_000_000.0
                } else thinkingElapsedSec
            ).withActivity(if (text.isEmpty()) "Primo testo ricevuto." else null)
        }
        is ChatStreamEvent.ThinkingDelta -> copy(
            thinking = thinking + event.delta,
            hasThinking = true,
            thinkingFrozen = false,
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
        is ChatStreamEvent.Status -> copy(status = event.message).withActivity(event.message)
        is ChatStreamEvent.RawHermesEvent -> copy(status = "Evento Hermes: ${event.name}")
            .withActivity("Evento Hermes: ${event.name} ${event.json.take(300)}")
        is ChatStreamEvent.PromptProgress -> copy(
            promptProgressPercent = event.percent.coerceIn(0, 100),
            status = event.label.ifBlank { "llama.cpp: prefill prompt" }
        ).withActivity("Prefill prompt ${event.percent}%")
        is ChatStreamEvent.Done -> copy(
            stats = event.stats,
            isDone = true,
            status = "Risposta completata.",
            promptProgressPercent = 100,
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
    val seen = current.map { it.id }.toMutableSet()
    return current + incoming.filter { seen.add(it.id) }
}

private fun mergeTextDelta(current: String, delta: String): String {
    if (delta.isEmpty()) return current
    if (current.isEmpty()) return delta
    if (delta == current) return current
    if (delta.startsWith(current)) return delta
    if (current.endsWith(delta)) return current
    return current + delta
}

private fun inferToolPendingStatus(tool: ToolCallState): Boolean {
    val s = tool.status.lowercase()
    return !s.contains("completat") && !s.contains("risultato") && !s.contains("success") && !s.contains("done") && !s.contains("fail") && !s.contains("error")
}

sealed class ChatStreamEvent {
    data class TextDelta(val delta: String) : ChatStreamEvent()
    data class ThinkingDelta(val delta: String) : ChatStreamEvent()
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
    data class PromptProgress(val percent: Int, val label: String = "llama.cpp: prefill prompt") : ChatStreamEvent()
    data class Done(val stats: ChatStreamStats) : ChatStreamEvent()
    data class Error(val message: String) : ChatStreamEvent()
}

data class ChatInputAttachment(
    val filename: String,
    val mimeType: String,
    val dataUrl: String,
    val sizeBytes: Long
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
    var sawDelta = false
    var ttftMs: Double? = null
    var promptTokens: Int? = null
    var completionTokens: Int? = null
    var serverTokensPerSecond: Double? = null
    var firstOutputTokenNs: Long? = null
    var lastOutputTokenNs: Long? = null
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
    val promptForModelResult = buildPromptWithAttachmentToolRefs(settings, prompt, attachments, apiKey)
    val promptForModel = promptForModelResult.first
    val payloadAttachments = if (promptForModelResult.second > 0) {
        attachments.filterNot { it.mimeType.startsWith("image/", ignoreCase = true) }
    } else {
        attachments
    }
    if (promptForModelResult.second > 0) {
        emit(ChatStreamEvent.Status("Allegati caricati sul gateway per tool vision: ${promptForModelResult.second}."))
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
                if (firstOutputTokenNs == null) firstOutputTokenNs = tokenAt
                lastOutputTokenNs = tokenAt
                if (!sawDelta) {
                    ttftMs = (tokenAt - start) / 1_000_000.0
                    sawDelta = true
                }
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
            is ChatStreamEvent.ThinkingDelta -> {
                if (!sawDelta) {
                    ttftMs = (System.nanoTime() - start) / 1_000_000.0
                    sawDelta = true
                }
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
            is ChatStreamEvent.Usage -> {
                promptTokens = ev.promptTokens ?: promptTokens
                completionTokens = ev.completionTokens ?: completionTokens
                serverTokensPerSecond = if ((ev.completionTokens ?: 0) >= 8) {
                    validateTokensPerSecond(ev.tokensPerSecond) ?: serverTokensPerSecond
                } else {
                    serverTokensPerSecond
                }
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
                return true
            }
            else -> flushDeltaBatches()
        }
        emit(ev)
        return false
    }

    suspend fun emitAndTrack(ev: ChatStreamEvent): Boolean {
        if (ev is ChatStreamEvent.TextDelta) {
            var stop = false
            for (extracted in thinkExtractor.process(ev.delta)) {
                if (emitAndTrackInternal(extracted)) stop = true
            }
            return stop
        }
        return emitAndTrackInternal(ev)
    }

    if (mode.equals("Agente", ignoreCase = true)) {
        runDetachedAgent(settings, promptForModel, history, conversationId, payloadAttachments, apiKey).collect { emit(it) }
        return@flow
    }

    if (shouldUseResponsesFirst(settings, mode)) {
        emit(ChatStreamEvent.Status(if (nativeMode) "Protocollo effettivo: Hermes Native via Responses. Context delegato a Hermes." else "Invio diretto a Hermes Responses API..."))
        emit(ChatStreamEvent.Status("llama.cpp: prefill prompt..."))

        fun buildResponsePayload(candidatePreviousResponseId: String?): JSONObject {
            val payload = JSONObject()
                .put("model", settings.model)
                .put("input", buildMultimodalInput(promptForModel, payloadAttachments))
                .put("store", true)
                .put("stream", true)
                .put("conversation", serverConversationId ?: JSONObject.NULL)
                .put("previous_response_id", if (serverConversationId == null) candidatePreviousResponseId ?: JSONObject.NULL else JSONObject.NULL)
                .put("metadata", visualBlocksMetadataJson(settings, conversationId))
            if (!nativeMode) {
                payload.put(
                    "instructions",
                    (if (mode.equals("Agente", ignoreCase = true))
                        hermesHubAgentInstructions()
                    else
                        hermesHubChatInstructions()) + if (videoMode) "\n\n" + hermesHubVideoInstructions(settings) else ""
                )
            }
            return payload
        }

        val responseUrl = "${settings.gatewayUrl.trimEnd('/')}/responses"
        val previousResponseCandidates = if (previousResponseId.isNullOrBlank()) {
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
            var responsesAttempt = 0
            while (responsesAttempt < 2 && !sawDelta) {
                responsesAttempt++
                lastError = null
                val terminated = openSseStream(responseUrl, responsePayload, "Hermes Responses API", apiKey, true) { ev ->
                    emitAndTrack(ev)
                }
                if (!terminated || sawDelta) {
                    break
                }
                if (lastError != null && responsesAttempt < 2) {
                    kotlinx.coroutines.delay(500L)
                    emit(ChatStreamEvent.Status("Riconnessione Hermes (tentativo ${responsesAttempt + 1})..."))
                }
            }
            if (sawDelta || lastError == null) {
                break
            }
            if (candidatePreviousResponseId != null && isRecoverablePreviousResponseError(lastError)) {
                continue@responseCandidateLoop
            }
            break
        }
        if (lastError != null && !sawDelta) {
            Log.w("ChatStream", "Responses API fallback: $lastError")
            if (nativeMode && isHermesAuthError(lastError)) {
                emit(ChatStreamEvent.Error("Hermes ha rifiutato l'API key anche dopo retry con key salvata, hermes-hub, no-auth e senza previous_response_id. Ripristina API key in Impostazioni o allinea HERMES_API_KEY sul gateway."))
                return@flow
            }
            if (nativeMode && settings.strictNativeMode) {
                emit(ChatStreamEvent.Error("Strict native mode: Hermes Native/Responses non disponibile. $lastError"))
                return@flow
            }
            emit(ChatStreamEvent.Status("Responses API non disponibile, fallback rapido a Chat Completions..."))
        }
    }

    if (!sawDelta) {
        if (nativeMode && settings.strictNativeMode) {
            emit(ChatStreamEvent.Error("Strict native mode: Hermes Native/Responses non disponibile. Stream vuoto."))
            return@flow
        }
        emit(ChatStreamEvent.Status(if (nativeMode) "Fallback compat: Chat Completions. Strict native mode disattivato." else "Invio diretto a Hermes Chat Completions..."))
        val compatHistory = if (nativeMode) emptyList() else history
        val payload = JSONObject()
            .put("model", settings.model)
            .put("stream", true)
            .put("session_id", serverConversationId ?: JSONObject.NULL)
            .put("metadata", visualBlocksMetadataJson(settings, conversationId))
            .put("messages", JSONArray().apply {
                if (!nativeMode) {
                    put(
                        JSONObject()
                                .put("role", "system")
                                .put("content", if (mode.equals("Agente", ignoreCase = true)) hermesHubAgentInstructions() else hermesHubChatInstructions())
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
        openSseStream(url, payload, "Hermes Chat Completions", apiKey, true, serverConversationId) { ev ->
            emitAndTrack(ev)
        }
    }

    val totalMs = (System.nanoTime() - start) / 1_000_000.0
    if (!sawDelta) {
        flushDeltaBatches()
        emit(ChatStreamEvent.Error(lastError ?: "Stream Hermes vuoto."))
        return@flow
    }

    flushDeltaBatches()
    for (extracted in thinkExtractor.flush()) {
        emitAndTrackInternal(extracted)
    }
    flushDeltaBatches()
    val tokensOut = completionTokens ?: max(1, accumText.length / 4)
    val tps = serverTokensPerSecond ?: calculateStableTokensPerSecond(tokensOut, firstOutputTokenNs, lastOutputTokenNs, totalMs)
    if (retriedWithoutPreviousResponseId && emittedResponseId.isNullOrBlank()) {
        emit(ChatStreamEvent.ResponseId(""))
    }
    emit(ChatStreamEvent.Done(ChatStreamStats(ttftMs, totalMs, tokensOut, tps, promptTokens, contextTokens, contextLength, contextPercent)))
}.flowOn(Dispatchers.IO)

private suspend fun openSseStream(
    url: String,
    payload: JSONObject,
    label: String,
    apiKey: String?,
    allowCompatAuth: Boolean,
    sessionId: String? = null,
    onEvent: suspend (ChatStreamEvent) -> Boolean
): Boolean {
    var call: Call? = null
    val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
        if (cause is CancellationException) {
            call?.cancel()
        }
    }
    return try {
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val authCandidates = hermesAuthCandidates(apiKey, allowCompatAuth)
        var lastHttpError: Pair<Int, String>? = null
        var lastNetworkError: String? = null

        for (candidateUrl in plugAndPlayStreamUrlCandidates(url)) {
            for ((index, bearerToken) in authCandidates.withIndex()) {
                onEvent(ChatStreamEvent.Status("$label: connessione stream..."))
                val builder = Request.Builder()
                    .url(candidateUrl)
                    .header("Accept", "text/event-stream, application/json")
                    .header("User-Agent", "HermesHub-Android")
                bearerToken?.let { builder.header("Authorization", "Bearer $it") }
                sessionId?.takeIf { it.isNotBlank() }?.let { builder.header("X-Hermes-Session-Id", it) }
                val request = builder.post(body).build()
                val activeCall = streamHttpClient.newCall(request)
                call = activeCall
                var completedSuccessfully = false
                try {
                    activeCall.execute().use { response ->
                        val responseBody = response.body
                        if (!response.isSuccessful) {
                            val text = responseBody.string().take(240)
                            lastHttpError = response.code to text
                            val canRetry = index < authCandidates.lastIndex && shouldRetryHermesWithBearerAuth(response.code, text)
                            if (canRetry) {
                                onEvent(ChatStreamEvent.Status("API key Hermes non accettata. Riprovo automaticamente..."))
                                return@use
                            }
                            return@use
                        }
                        lastHttpError = null
                        onEvent(ChatStreamEvent.Status("$label connesso: $candidateUrl"))
                        onEvent(ChatStreamEvent.Status("Prompt inviato. Attendo primo token..."))
                        val ct = responseBody.contentType()?.toString().orEmpty()
                        if (!ct.contains("event-stream", ignoreCase = true)) {
                            val full = responseBody.string()
                            parseFullBody(full).forEach { onEvent(it) }
                            completedSuccessfully = true
                            return@use
                        }
                        val source = responseBody.source()
                        val dataBuf = StringBuilder()
                        var eventName: String? = null
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val line = source.readUtf8Line() ?: break
                            if (line.isEmpty()) {
                                if (dataBuf.isNotEmpty()) {
                                    parseSseData(eventName, dataBuf.toString()).forEach { onEvent(it) }
                                    dataBuf.clear()
                                    eventName = null
                                }
                                continue
                            }
                            when {
                                line.startsWith(":") -> Unit
                                line.startsWith("event:", ignoreCase = true) -> {
                                    eventName = line.substring(6).trim()
                                }
                                line.startsWith("data:", ignoreCase = true) -> {
                                    if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                                    val part = line.substring(5).let { if (it.startsWith(" ")) it.drop(1) else it }
                                    dataBuf.append(part)
                                }
                            }
                        }
                        if (dataBuf.isNotEmpty()) {
                            parseSseData(eventName, dataBuf.toString()).forEach { onEvent(it) }
                        }
                        completedSuccessfully = true
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    lastNetworkError = when (ex) {
                        is SocketTimeoutException -> "$candidateUrl timeout"
                        is ConnectException -> "$candidateUrl connessione fallita"
                        else -> "$candidateUrl ${ex.message ?: ex.javaClass.simpleName}"
                    }
                }
                if (completedSuccessfully && lastHttpError == null) {
                    return false
                }
            }
        }
        lastHttpError?.let {
            val message = if (it.first == 401) {
                "$label: API key rifiutata. Provati token salvato, hermes-hub e no-auth."
            } else {
                "$label HTTP ${it.first}: ${it.second}"
            }
            onEvent(ChatStreamEvent.Error(message))
        }
            ?: onEvent(ChatStreamEvent.Error(lastNetworkError ?: "$label: Hermes Gateway non raggiungibile."))
        true
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: Exception) {
        val message = when (ex) {
            is SocketTimeoutException -> "$label: timeout di rete verso Hermes Gateway."
            is ConnectException -> "$label: connessione a Hermes Gateway fallita."
            else -> "$label: ${ex.message ?: ex.javaClass.simpleName}"
        }
        onEvent(ChatStreamEvent.Error(message))
        true
    } finally {
        cancellationHook?.dispose()
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
                    .put("image_url", attachment.dataUrl)
                    .put("detail", "auto")
            )
        } else {
            content.put(
                JSONObject()
                    .put("type", "input_file")
                    .put("filename", attachment.filename)
                    .put("file_data", attachment.dataUrl)
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
                            .put("url", attachment.dataUrl)
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

private fun buildPromptWithAttachmentToolRefs(
    settings: AppSettings,
    prompt: String,
    attachments: List<ChatInputAttachment>,
    apiKey: String?
): Pair<String, Int> {
    val images = attachments.filter { it.mimeType.startsWith("image/", ignoreCase = true) }
    if (images.isEmpty()) return prompt to 0
    val refs = images.map { uploadAttachmentForTool(settings, it, apiKey) }
    val uploaded = refs.filter { it.error.isNullOrBlank() }
    if (uploaded.isEmpty()) return prompt to 0
    val text = buildString {
        append(prompt.trimEnd())
        append("\n\n")
        append("Allegati immagine disponibili per tool vision_analyze sul server Hermes:\n")
        uploaded.forEach { item ->
            append("- ")
            append(item.filename)
            item.path?.takeIf { it.isNotBlank() }?.let {
                append(" | image_url da usare nel tool vision_analyze: ")
                append(it)
            }
            item.mediaUrl?.takeIf { it.isNotBlank() }?.let {
                append(" | URL proxy fallback: ")
                append(it)
            }
            append('\n')
        }
        append("Se devi vedere/leggere l'immagine, chiama vision_analyze usando esattamente il path server indicato come image_url.\n")
        append("Non usare attachment:image/png, None, /tmp/... inventati o URL incompleti. Se vision_analyze fallisce, riporta l'errore tecnico; non concludere che il modello non supporta vision.")
    }
    return text to uploaded.size
}

private fun uploadAttachmentForTool(settings: AppSettings, attachment: ChatInputAttachment, apiKey: String?): UploadedAttachmentRef {
    val endpoint = "${settings.gatewayUrl.trimEnd('/')}/media/upload"
    val payload = JSONObject()
        .put("filename", attachment.filename)
        .put("mime_type", attachment.mimeType)
        .put("data_url", attachment.dataUrl)
    val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
    val authCandidates = hermesAuthCandidates(apiKey, true)
    for (candidateUrl in plugAndPlayStreamUrlCandidates(endpoint)) {
        for ((index, token) in authCandidates.withIndex()) {
            val builder = Request.Builder()
                .url(candidateUrl)
                .header("Accept", "application/json")
                .header("User-Agent", "HermesHub-Android")
                .post(body)
            token?.let { builder.header("Authorization", "Bearer $it") }
            try {
                streamHttpClient.newCall(builder.build()).execute().use { response ->
                    val responseBody = response.body.string()
                    if (!response.isSuccessful) {
                        if (index < authCandidates.lastIndex && shouldRetryHermesWithBearerAuth(response.code, responseBody)) {
                            return@use
                        }
                        return UploadedAttachmentRef(attachment.filename, attachment.mimeType, null, null, "HTTP ${response.code}")
                    }
                    val root = JSONObject(responseBody)
                    val media = root.optString("media_url", root.optString("url")).takeIf { it.isNotBlank() }?.let { value ->
                        if (value.startsWith("/")) "${gatewayOrigin(settings.gatewayUrl)}$value" else value
                    }
                    return UploadedAttachmentRef(
                        filename = root.optString("filename", attachment.filename),
                        mimeType = root.optString("mime_type", attachment.mimeType),
                        path = root.optString("path", root.optString("server_path")).takeIf { it.isNotBlank() },
                        mediaUrl = media,
                        error = null
                    )
                }
            } catch (ex: Exception) {
                return UploadedAttachmentRef(attachment.filename, attachment.mimeType, null, null, ex.message ?: ex.javaClass.simpleName)
            }
        }
    }
    return UploadedAttachmentRef(attachment.filename, attachment.mimeType, null, null, "auth failed")
}

private fun gatewayOrigin(gatewayUrl: String): String {
    val root = gatewayUrl.trimEnd('/')
    return if (root.endsWith("/v1", ignoreCase = true)) root.dropLast(3) else root
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
    while (true) {
        kotlinx.coroutines.delay(2_000L)
        val statusResponse = executeRunJsonRequest("${settings.gatewayUrl.trimEnd('/')}/runs/$runId", null, apiKey, "GET")
        if (statusResponse.first == 404) {
            val message = "Run $runId non piu' in cache gateway; se era lunga puo' ancora lavorare sul processo Hermes."
            if (message != lastStatus) {
                lastStatus = message
                emit(ChatStreamEvent.Status(message))
            }
            continue
        }
        if (statusResponse.first !in 200..299) {
            val message = "Run $runId polling HTTP ${statusResponse.first}"
            if (message != lastStatus) {
                lastStatus = message
                emit(ChatStreamEvent.Status(message))
            }
            continue
        }
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
            if (finalText.isNotBlank()) {
                emit(ChatStreamEvent.TextDelta(finalText))
            } else if (state != "completed") {
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

private fun executeRunJsonRequest(url: String, payload: JSONObject?, apiKey: String?, method: String): Pair<Int, String> {
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
                streamHttpClient.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    last = response.code to body
                    if (index < authCandidates.lastIndex && shouldRetryHermesWithBearerAuth(response.code, body)) {
                        return@use
                    }
                    return response.code to body
                }
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

private fun parseSseData(eventName: String?, data: String): List<ChatStreamEvent> {
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
            val percent = obj.optInt("percent", obj.optInt("progress", -1))
            if (percent in 0..100) {
                val rawLabel = obj.optString("label", "")
                val label = if (rawLabel.isBlank() || rawLabel.contains("processing prompt", ignoreCase = true)) {
                    "llama.cpp: prefill prompt"
                } else {
                    rawLabel
                }
                out += ChatStreamEvent.PromptProgress(percent, label)
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
            if (reasoning.isNotEmpty()) out += ChatStreamEvent.ThinkingDelta(reasoning)
            return out
        }
        t.contains("output_text") && t.contains("delta") -> {
            val delta = obj.optString("delta", obj.optString("text", ""))
            if (delta.isNotEmpty()) out += ChatStreamEvent.TextDelta(delta)
            return out
        }
        t.contains("output_text") && (t.contains("done") || t.contains("completed")) -> {
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
        t.contains("reasoning") && t.contains("delta") -> {
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
                if (itemType.contains("message", ignoreCase = true)) {
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
                } else {
                    val text = extractTextFromAnyJson(item)
                    if (text.isNotEmpty()) out += ChatStreamEvent.TextDelta(text)
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
                val blocks = extractVisualBlocksFromJson(resp.toString())
                if (blocks.isNotEmpty()) {
                    out += ChatStreamEvent.VisualBlocks(blocks, VISUAL_BLOCKS_VERSION)
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
                val reasoning = if (delta.has("reasoning")) delta.optString("reasoning", "") else delta.optString("reasoning_content", "")
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

    val direct = listOf("output_text", "text", "message", "reply", "result", "summary")
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

private fun parseFullBody(body: String): List<ChatStreamEvent> {
    if (body.isBlank()) return emptyList()
    val out = mutableListOf<ChatStreamEvent>()
    val text = extractAssistantText(body)
    val rid = extractResponseId(body)
    if (rid != null) out += ChatStreamEvent.ResponseId(rid)
    if (text.isNotEmpty()) out += ChatStreamEvent.TextDelta(text)
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
private val RAW_IMAGE_URL_REGEX = Regex("""(?i)\bhttps://[^\s<>)"']{6,1200}""")

private fun extractInlineMediaBlocks(text: String): List<VisualBlock> {
    val explicit = INLINE_MEDIA_REGEX.findAll(text).take(12).mapIndexed { index, match ->
        val label = (match.groups[1]?.value ?: match.groups[3]?.value ?: "Media Hermes").trim()
        val url = (match.groups[2]?.value ?: match.groups[4]?.value ?: "").trim()
        createInlineMediaBlock(index, label, url, explicit = true)
    }.toList()

    val seen = explicit.map { it.mediaUrl }.toMutableSet()
    val raw = RAW_IMAGE_URL_REGEX.findAll(text)
        .map { it.value.trimEnd('.', ',', ';', ':') }
        .filter { it !in seen && isLikelyRemoteImageUrl(it) }
        .take(12 - explicit.size)
        .mapIndexed { index, url ->
            seen += url
            createInlineMediaBlock(index + explicit.size, inferMediaFilename("immagine", url).ifBlank { "Immagine" }, url, explicit = false)
        }
        .toList()
    return explicit + raw
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
    return INLINE_MEDIA_REGEX.replace(text, "").replace(Regex("""\n{3,}"""), "\n\n").trim()
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
        value.contains(".pdf") -> "application/pdf"
        else -> ""
    }
}

private fun isLikelyRemoteImageUrl(url: String): Boolean {
    val value = url.lowercase()
    if (!value.startsWith("https://")) return false
    return listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp").any { value.substringBefore('?').substringBefore('#').endsWith(it) } ||
        listOf("picsum.photos", "images.unsplash.com", "unsplash.com", "pexels.com", "pixabay.com", "cloudinary.com", "image", "photo").any { value.contains(it) }
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
        .put("profile", "Matteo")
        .put("project_id", settings.activeProjectId)
        .put("project_name", settings.activeProjectName)
        .put("workspace", settings.activeProjectName.ifBlank { "default" })
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
                .put("video", "Feed personale video: Hermes conosce video_library_path/HERMES_VIDEO_LIBRARY_PATH; ogni video creato/scaricato per Matteo deve essere salvato o registrato li, desktop mostra file locali, app salva feedback e metadata.")
                .put("news", "Feed personale articoli: Hermes produce articoli con fonti; se crea HTML/giornale online salva in ${settings.newsLibraryPath} per /v1/news/library; app salva feedback.")
                .put("notifications", "Inbox notifiche: cron/agenti devono usare POST /v1/hub/notifications per avvisi importanti quando l'app non e' aperta.")
                .put("jobs", "Coda Hermes Jobs condivisa con CLI/server.")
                .put("runs", "Runs operative Hermes.")
        )
        .put(
            "notification_contract",
            JSONObject()
                .put("endpoint", "/v1/hub/notifications")
                .put("required_behavior", "When a cron, monitor or long-running agent finds something Matteo must know, create a notification with title, message, severity, source and conversation_prompt. Keep it concise and self-contained.")
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

private class ThinkExtractor {
    private val buffer = StringBuilder()
    private var inThink = false

    fun process(delta: String): List<ChatStreamEvent> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(delta)
        var text = buffer.toString()
        val events = mutableListOf<ChatStreamEvent>()

        while (true) {
            if (!inThink) {
                val start = text.indexOf("<think>")
                if (start >= 0) {
                    if (start > 0) {
                        events.add(ChatStreamEvent.TextDelta(text.substring(0, start)))
                    }
                    text = text.substring(start + 7)
                    buffer.clear()
                    buffer.append(text)
                    inThink = true
                } else {
                    val safeLen = maxOf(0, text.length - 6)
                    if (safeLen > 0) {
                        events.add(ChatStreamEvent.TextDelta(text.substring(0, safeLen)))
                        text = text.substring(safeLen)
                        buffer.clear()
                        buffer.append(text)
                    }
                    break
                }
            } else {
                val end = text.indexOf("</think>")
                if (end >= 0) {
                    if (end > 0) {
                        events.add(ChatStreamEvent.ThinkingDelta(text.substring(0, end)))
                    }
                    text = text.substring(end + 8)
                    buffer.clear()
                    buffer.append(text)
                    inThink = false
                } else {
                    val safeLen = maxOf(0, text.length - 7)
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
}
