package com.nemoclaw.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
private val plugAndPlayStreamGatewayRoots = listOf(
    "http://100.105.46.6:8642",
    "http://hermes.local:8642",
    "http://hermes:8642",
    "http://hermes-hub:8642",
    "http://hermeshub:8642",
    "http://home-server:8642",
    "http://server:8642"
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
        is ChatStreamEvent.VisualBlocks -> copy(
            visualBlocks = mergeVisualBlocks(visualBlocks, event.blocks),
            visualBlocksVersion = event.version
        ).withActivity("Visual blocks ricevuti: ${event.blocks.size}.")
        is ChatStreamEvent.Status -> copy(status = event.message).withActivity(event.message)
        is ChatStreamEvent.RawHermesEvent -> copy(status = "Evento Hermes: ${event.name}")
            .withActivity("Evento Hermes: ${event.name} ${event.json.take(300)}")
        is ChatStreamEvent.PromptProgress -> copy(
            promptProgressPercent = event.percent.coerceIn(0, 100),
            status = event.label.ifBlank { "Processing prompt..." }
        ).withActivity("Processing prompt ${event.percent}%")
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
    data class VisualBlocks(val blocks: List<VisualBlock>, val version: Int) : ChatStreamEvent()
    data class Status(val message: String) : ChatStreamEvent()
    data class RawHermesEvent(val name: String, val json: String) : ChatStreamEvent()
    data class Usage(val promptTokens: Int?, val completionTokens: Int?) : ChatStreamEvent()
    data class ContextUsage(val tokens: Int?, val length: Int?, val percent: Int?) : ChatStreamEvent()
    data class PromptProgress(val percent: Int, val label: String = "Processing prompt...") : ChatStreamEvent()
    data class Done(val stats: ChatStreamStats) : ChatStreamEvent()
    data class Error(val message: String) : ChatStreamEvent()
}

fun streamChatRequest(
    settings: AppSettings,
    mode: String,
    prompt: String,
    history: List<ChatMessage>,
    conversationId: String?,
    previousResponseId: String?,
    apiKey: String?
): Flow<ChatStreamEvent> = flow {
    val start = System.nanoTime()
    var sawDelta = false
    var ttftMs: Double? = null
    var promptTokens: Int? = null
    var completionTokens: Int? = null
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

    suspend fun emitAndTrack(ev: ChatStreamEvent): Boolean {
        when (ev) {
            is ChatStreamEvent.TextDelta -> {
                if (!sawDelta) {
                    ttftMs = (System.nanoTime() - start) / 1_000_000.0
                    sawDelta = true
                }
                accumText.appendBounded(ev.delta)
            }
            is ChatStreamEvent.ThinkingDelta -> {
                if (!sawDelta) {
                    ttftMs = (System.nanoTime() - start) / 1_000_000.0
                    sawDelta = true
                }
                accumThink.appendBounded(ev.delta)
            }
            is ChatStreamEvent.Usage -> {
                promptTokens = ev.promptTokens ?: promptTokens
                completionTokens = ev.completionTokens ?: completionTokens
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
                lastError = ev.message
                return true
            }
            else -> Unit
        }
        emit(ev)
        return false
    }

    if (shouldUseResponsesFirst(settings, mode)) {
        emit(ChatStreamEvent.Status(if (nativeMode) "Protocollo effettivo: Hermes Native via Responses. Context delegato a Hermes." else "Invio diretto a Hermes Responses API..."))

        fun buildResponsePayload(candidatePreviousResponseId: String?): JSONObject {
            val payload = JSONObject()
                .put("model", settings.model)
                .put("input", prompt)
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
                compatHistory.filter { !it.isAction }.forEach { msg ->
                    put(
                        JSONObject()
                            .put("role", if (msg.fromUser) "user" else "assistant")
                            .put("content", msg.text)
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
        emit(ChatStreamEvent.Error(lastError ?: "Stream Hermes vuoto."))
        return@flow
    }

    val tokensOut = completionTokens ?: max(1, accumText.length / 4)
    val ttftSnapshot = ttftMs
    val sinceFirst = if (ttftSnapshot != null) max(1.0, totalMs - ttftSnapshot) else totalMs
    val tps = if (tokensOut > 0 && sinceFirst > 0) tokensOut / (sinceFirst / 1000.0) else null
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
                                    dataBuf.append(line.substring(5).trimStart())
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
    if (data.isBlank() || data.trim() == "[DONE]") return emptyList()
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
                out += ChatStreamEvent.PromptProgress(percent, obj.optString("label", "Processing prompt..."))
            }
            return out
        }
        t.contains("hermes.visual_blocks") || t.contains("visual_blocks") -> {
            val blocks = extractVisualBlocksFromJson(obj.toString())
            if (blocks.isNotEmpty()) out += ChatStreamEvent.VisualBlocks(blocks, VISUAL_BLOCKS_VERSION)
            return out
        }
        t.contains("hermes.tool.progress") || t.contains("tool.progress") -> {
            val id = obj.optString("toolCallId", obj.optString("call_id", obj.optString("id", "tool")))
            val name = obj.optString("tool", obj.optString("name", "tool"))
            val status = obj.optString("status", "").lowercase()
            val label = obj.optString("label", "")
            out += ChatStreamEvent.ToolCallStart(id, name)
            if (label.isNotBlank() && label != name) out += ChatStreamEvent.ToolCallArgs(id, label)
            val result = extractToolOutput(obj)
            if (result.isNotBlank()) out += ChatStreamEvent.ToolResult(id, name, result)
            if (status.contains("complete") || status.contains("done") || status.contains("success") || status.contains("failed") || status.contains("error")) {
                out += ChatStreamEvent.ToolCallEnd(id)
            }
            return out
        }
        t.contains("reasoning.available") -> {
            val reasoning = obj.optString("reasoning", obj.optString("summary", obj.optString("text", obj.optString("preview", ""))))
            if (reasoning.isNotBlank()) out += ChatStreamEvent.ThinkingDelta(reasoning)
            return out
        }
        t.contains("output_text") && t.contains("delta") -> {
            val delta = obj.optString("delta", obj.optString("text", ""))
            if (delta.isNotEmpty()) out += ChatStreamEvent.TextDelta(delta)
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
                    if (args.isNotBlank()) out += ChatStreamEvent.ToolCallArgs(id, args)
                }
            }
            return out
        }
        t.contains("output_item.done") || t.contains("function_call.completed") -> {
            val item = obj.optJSONObject("item")
            if (item != null) {
                val itemType = item.optString("type", "")
                if (itemType.contains("output", ignoreCase = true) || itemType.contains("result", ignoreCase = true)) {
                    val id = item.optString("call_id", item.optString("id", "tool"))
                    val name = item.optString("name", "")
                    val output = extractToolOutput(item)
                    if (output.isNotBlank()) out += ChatStreamEvent.ToolResult(id, name.ifBlank { null }, output)
                } else if (itemType.contains("function", ignoreCase = true) || itemType.contains("tool", ignoreCase = true)) {
                    val id = item.optString("call_id", item.optString("id", "tool"))
                    val name = item.optString("name", "tool")
                    if (name.isNotBlank()) out += ChatStreamEvent.ToolCallStart(id, name)
                    val args = item.optString("arguments", "")
                    if (args.isNotBlank()) out += ChatStreamEvent.ToolCallArgs(id, args)
                    out += ChatStreamEvent.ToolCallEnd(id)
                } else {
                    val text = extractTextFromAnyJson(item)
                    if (text.isNotBlank()) out += ChatStreamEvent.TextDelta(text)
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
                        usage.optIntOrNull("output_tokens") ?: usage.optIntOrNull("completion_tokens")
                    )
                }
                val text = extractTextFromAnyJson(resp)
                if (text.isNotBlank()) out += ChatStreamEvent.TextDelta(text)
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
                usage.optIntOrNull("output_tokens") ?: usage.optIntOrNull("completion_tokens")
            )
        }
        val blocks = extractVisualBlocksFromJson(obj.toString())
        if (blocks.isNotEmpty()) {
            out += ChatStreamEvent.VisualBlocks(blocks, VISUAL_BLOCKS_VERSION)
        }
    } else {
        val text = extractTextFromAnyJson(obj)
        if (text.isNotBlank()) {
            out += ChatStreamEvent.TextDelta(text)
        }
    }

    return out
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
        - Ogni file .mp4/.m4v/.mov/.mkv/.webm/.avi messo in quella cartella apparira' automaticamente nella sezione Video tramite /v1/video/library.
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
            if (args.isNotBlank()) out += ChatStreamEvent.ToolCallArgs(id, args)
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
                    if (args.isNotBlank()) out += ChatStreamEvent.ToolCallArgs(id, args)
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
            if (value is String && value.isNotBlank()) value else null
        }
    if (!direct.isNullOrBlank()) return direct

    val content = obj.optJSONArray("content")
    if (content != null) {
        val builder = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            val partText = part.optString("text", part.optString("output_text", ""))
            if (partText.isNotBlank()) builder.append(partText)
        }
        if (builder.isNotEmpty()) return builder.toString()
    }

    val output = obj.optJSONArray("output")
    if (output != null) {
        val builder = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val nested = extractTextFromAnyJson(item, depth + 1)
            if (nested.isNotBlank()) builder.append(nested)
        }
        if (builder.isNotEmpty()) return builder.toString()
    }

    val response = obj.optJSONObject("response")
    if (response != null) {
        val nested = extractTextFromAnyJson(response, depth + 1)
        if (nested.isNotBlank()) return nested
    }

    val item = obj.optJSONObject("item")
    if (item != null) {
        val nested = extractTextFromAnyJson(item, depth + 1)
        if (nested.isNotBlank()) return nested
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
        listOf(".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi").any { value.contains(it) } -> "video"
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
                .put("news", "Feed personale articoli: Hermes produce articoli con fonti, app salva feedback.")
                .put("jobs", "Coda Hermes Jobs condivisa con CLI/server.")
                .put("runs", "Runs operative Hermes.")
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
