package com.nemoclaw.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.coroutines.coroutineContext

fun streamChatRequest(
    settings: AppSettings,
    mode: String,
    prompt: String,
    history: List<ChatMessage>,
    conversationId: String?,
    previousResponseId: String?,
    attachments: List<ChatInputAttachment>,
    apiKey: String?,
    botProfile: String? = null,
    botSessionId: String? = null,
    botMultiplexEnabled: Boolean = false,
    allowCompatAuth: Boolean = true
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
    var draftRate: Double? = null
    var draftLabel: String? = null
    var contextTokens: Int? = null
    var contextLength: Int? = null
    var contextPercent: Int? = null
    var emittedResponseId: String? = null
    var retriedWithoutPreviousResponseId = false
    val accumText = StringBuilder()
    val accumThink = StringBuilder()
    var lastError: String? = null
    val requestContext = HermesHubProtocol.newCorrelationContext()
    val videoMode = isVideoRequest(prompt)
    val nativeMode = isHermesNative(settings)
    val serverConversationId = botSessionId?.trim()?.takeIf { it.isNotBlank() }
        ?: hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, conversationId)
    fun profileUrl(path: String): String {
        // Il gateway serve tutto sotto /v1 (o /api, /health): un path nudo
        // fa 404. Normalizza qui cosi nessun chiamante puo sbagliare.
        val normalized = (if (path.startsWith("/")) path else "/$path").let {
            if (it.startsWith("/v1", ignoreCase = true) || it.startsWith("/api/", ignoreCase = true) ||
                it.startsWith("/health", ignoreCase = true)
            ) it else "/v1$it"
        }
        return if (botProfile.isNullOrBlank()) {
            resolveHermesUrl(settings, normalized)
        } else {
            resolveHermesProfileUrl(settings, botProfile, normalized, botMultiplexEnabled)
        }
    }
    val attachmentPreparation = buildPromptWithAttachmentToolRefs(settings, prompt, attachments, apiKey, botProfile, botMultiplexEnabled, allowCompatAuth)
    val promptForModel = attachmentPreparation.prompt
    val payloadAttachments = attachmentPreparation.inlineAttachments
    // Upload parziale: MAI fallback silenzioso a subset. Error blocca l'invio:
    // ChatStreamEvent.Error imposta error+isDone ed e' impossibile non vederlo
    // (StreamingBubbleView lo rende assertive; Status resterebbe una riga di log).
    if (isPartialUploadBlocked(attachments.size, attachmentPreparation.uploadedCount, attachmentPreparation.uploadErrors)) {
        emit(ChatStreamEvent.Error(partialUploadBlockMessage(attachments.size, attachmentPreparation.uploadedCount, attachmentPreparation.uploadErrors)))
        return@flow
    }
    if (attachmentPreparation.uploadedCount > 0) {
        emit(ChatStreamEvent.Status("Allegati caricati sul gateway: ${attachmentPreparation.uploadedCount}."))
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
            is ChatStreamEvent.DraftAcceptance -> {
                draftRate = ev.rate
                if (!ev.label.isNullOrBlank()) draftLabel = ev.label
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
        runDetachedAgent(settings, promptForModel, history, conversationId, payloadAttachments, apiKey, botProfile, botSessionId, botMultiplexEnabled, allowCompatAuth).collect { emit(it) }
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
                // Il turno deve sopravvivere alla morte del client: vedi session chat.
                .put("continue_on_disconnect", true)
                .put("return_progress", true)
                .put("timings_per_token", true)
                .put("conversation", serverConversationId ?: JSONObject.NULL)
                .put("previous_response_id", if (serverConversationId == null) candidatePreviousResponseId ?: JSONObject.NULL else JSONObject.NULL)
                .put("metadata", visualBlocksMetadataJson(settings, conversationId, botProfile, botSessionId))
            applyHermesModelOverrides(payload, settings, capabilities = null)
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

        val responseUrl = profileUrl("/responses")
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
            val result = openSseStream(
                responseUrl,
                responsePayload,
                "Hermes Responses API",
                apiKey,
                allowCompatAuth,
                sessionId = serverConversationId,
                sessionKey = settings.hermesSessionKey.takeIf { isValidHermesSessionKey(it) },
                botProfile = botProfile,
                requestContext = requestContext
            ) { ev ->
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
            if (com.nemoclaw.chat.BuildConfig.DEBUG) {
                Log.w("ChatStream", "Responses API fallback: $lastError")
            }
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
            .put("continue_on_disconnect", true)
            .put("return_progress", true)
            .put("timings_per_token", true)
            .put("session_id", serverConversationId ?: JSONObject.NULL)
            .put("metadata", visualBlocksMetadataJson(settings, conversationId, botProfile, botSessionId))
        applyHermesModelOverrides(payload, settings, capabilities = null)
        payload.put("messages", JSONArray().apply {
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
        val url = profileUrl("/chat/completions")
        lastError = null
        val result = openSseStream(
            url,
            payload,
            "Hermes Chat Completions",
            apiKey,
            allowCompatAuth,
            sessionId = serverConversationId,
            sessionKey = settings.hermesSessionKey.takeIf { isValidHermesSessionKey(it) },
            botProfile = botProfile,
            requestContext = requestContext
        ) { ev ->
            emitAndTrack(ev)
        }
        sawTerminal = result.terminal
        lastError = result.error ?: lastError
    }

    val totalMs = (System.nanoTime() - start) / 1_000_000.0
    suspend fun reattachStoredResponse(): Boolean {
        val responseId = emittedResponseId?.takeIf { it.isNotBlank() } ?: return false
        val url = responseStatusUrl(settings, botProfile, botMultiplexEnabled, responseId)
        // Cap 55min: oltre scade anche la retention run lato hub (1h).
        repeat(330) {
            coroutineContext.ensureActive()
            delay(10_000)
            val (code, body) = httpGetResponse(url, apiKey)
            // 401/403/404 sono permanenti (chiave, profilo, mai creata o scaduta):
            // non ha senso tener vivo il collector per un'ora.
            if (code == 401 || code == 403 || code == 404) return false
            if (code !in 200..299) return@repeat
            val storedStatus = runCatching { JSONObject(body).optString("status") }.getOrNull() ?: return@repeat
            if (storedStatus != "completed" && storedStatus != "failed") return@repeat
            for (ev in parseFullBody(body)) emitAndTrack(ev)
            if (storedStatus == "failed") {
                emit(ChatStreamEvent.Error("Il turno recuperato risulta fallito sul server."))
                return false
            }
            flushDeltaBatches()
            for (extracted in thinkExtractor.flush()) {
                emitAndTrackInternal(extracted)
            }
            flushDeltaBatches()
            val totalMs = (System.nanoTime() - start) / 1_000_000.0
            val tokensOut = completionTokens ?: max(1, accumText.length / 4)
            emit(
                ChatStreamEvent.Done(
                    ChatStreamStats(
                        ttftMs, totalMs, tokensOut, serverTokensPerSecond, promptTokens,
                        contextTokens, contextLength, contextPercent, draftRate, draftLabel
                    )
                )
            )
            return true
        }
        return false
    }

    if (lastError != null) {
        flushDeltaBatches()
        // Transport morto a turno vivo (watchdog 90s incluso): prima di
        // dichiarare il turno morto, recupera via GET come nel ramo pulito.
        // Il server continua per continue_on_disconnect: il collector resta
        // in ascolto invece di chiudere la chat.
        if (shouldReattachStoredResponse(lastError, emittedResponseId, sawTerminal)) {
            emit(ChatStreamEvent.Status("Connessione persa a turno vivo: recupero la risposta dal server..."))
            if (reattachStoredResponse()) return@flow
        }
        if (sawActivity && !lastError.orEmpty().contains("parziale", ignoreCase = true)) {
            emit(ChatStreamEvent.Error("Stream Hermes interrotto dopo una risposta parziale. $lastError"))
        } else if (!sawActivity) {
            emit(ChatStreamEvent.Error(lastError ?: "Errore stream Hermes."))
        }
        return@flow
    }

    if (!sawTerminal) {
        // Transport morto a turno vivo (caso video lunghi): il detach
        // server-side continua il lavoro. Prima di dichiarare il turno
        // morto, recupera la risposta archiviata via GET — anche con
        // contenuto parziale gia mostrato (il merge non duplica).
        if (shouldReattachStoredResponse(lastError, emittedResponseId, sawTerminal)) {
            emit(ChatStreamEvent.Status("Connessione persa a turno vivo: recupero la risposta dal server..."))
            if (reattachStoredResponse()) return@flow
        }
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
    emit(ChatStreamEvent.Done(ChatStreamStats(ttftMs, totalMs, tokensOut, tps, promptTokens, contextTokens, contextLength, contextPercent, draftRate, draftLabel)))
}.flowOn(Dispatchers.IO)

