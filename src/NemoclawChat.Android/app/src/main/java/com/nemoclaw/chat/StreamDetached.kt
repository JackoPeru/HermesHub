package com.nemoclaw.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

internal fun runDetachedAgent(
    settings: AppSettings,
    prompt: String,
    history: List<ChatMessage>,
    conversationId: String?,
    attachments: List<ChatInputAttachment>,
    apiKey: String?,
    botProfile: String?,
    botSessionId: String?,
    botMultiplexEnabled: Boolean,
    allowCompatAuth: Boolean
): Flow<ChatStreamEvent> = flow {
    val startedAt = System.nanoTime()
    val payload = JSONObject()
        .put("model", settings.model)
        .put("input", buildMultimodalInput(prompt, attachments))
        .put("instructions", hermesHubAgentInstructions() + projectContextInstructions(settings))
        .put(
            "session_id",
            botSessionId?.trim()?.takeIf { it.isNotBlank() }
                ?: hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, conversationId)
                ?: JSONObject.NULL
        )
        .put("metadata", visualBlocksMetadataJson(settings, conversationId, botProfile, botSessionId))
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
    val runUrl = if (botProfile.isNullOrBlank()) {
        resolveHermesUrl(settings, "/v1/runs")
    } else {
        resolveHermesProfileUrl(settings, botProfile, "/v1/runs", botMultiplexEnabled)
    }
    val startResponse = executeRunJsonRequest(runUrl, payload, apiKey, "POST", allowCompatAuth)
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
            emit(ChatStreamEvent.Error("Run $runId ancora attiva dopo 60 minuti: polling locale terminato, controlla lo stato sul gateway."))
            return@flow
        }
        kotlinx.coroutines.delay((2_000L * (consecutiveFailures + 1)).coerceAtMost(10_000L))
        val statusUrl = if (botProfile.isNullOrBlank()) resolveHermesUrl(settings, "/v1/runs/${java.net.URLEncoder.encode(runId, "UTF-8")}") else resolveHermesProfileUrl(settings, botProfile, "/v1/runs/${java.net.URLEncoder.encode(runId, "UTF-8")}", botMultiplexEnabled)
        val statusResponse = executeRunJsonRequest(statusUrl, null, apiKey, "GET", allowCompatAuth)
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
            // 401/403 permanenti (chiave): inutile riprovare, chiudi subito.
            if (statusResponse.first == 401 || statusResponse.first == 403) {
                emit(ChatStreamEvent.Error("Run $runId: chiave rifiutata (HTTP ${statusResponse.first}). Verifica la chiave in Impostazioni."))
                return@flow
            }
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

internal suspend fun executeRunJsonRequest(
    url: String,
    payload: JSONObject?,
    apiKey: String?,
    method: String,
    allowCompatAuth: Boolean = true
): Pair<Int, String> {
    var last: Pair<Int, String>? = null
    var attempt = 0
    val authCandidates = hermesAuthCandidates(apiKey, allowCompatAuth)
    val requestContext = HermesHubProtocol.newCorrelationContext()
    for (candidateUrl in plugAndPlayStreamUrlCandidates(url)) {
        for ((index, token) in authCandidates.withIndex()) {
            // Fail-closed profilo: mai fallback anonimo su /p/<profile>/.
            if (candidateUrl.contains("/p/", ignoreCase = true) && token.isNullOrBlank()) continue
            val builder = Request.Builder()
                .url(candidateUrl)
                .header("Accept", "application/json")
                .header("User-Agent", "HermesHub-Android")
            token?.let { builder.header("Authorization", "Bearer $it") }
            HermesHubProtocol.addCorrelationHeaders(builder, requestContext)
            val request = when (method.uppercase()) {
                "GET" -> builder.get().build()
                else -> builder.post((payload ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            }
            try {
                val response = executeCancellableRequest(request)
                last = response
                if (index < authCandidates.lastIndex && shouldRetryHermesWithBearerAuth(response.first, response.second, token)) {
                    continue
                }
                return response
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                last = 0 to (ex.message ?: ex.javaClass.simpleName)
                hermesRetryBackoffDelay(attempt++)
            }
        }
    }
    return last ?: (0 to "")
}

internal suspend fun stopHermesRun(
    settings: AppSettings,
    runId: String,
    apiKey: String?,
    botProfile: String? = null,
    botMultiplexEnabled: Boolean = false,
    allowCompatAuth: Boolean = true
): Pair<Int, String> = withContext(Dispatchers.IO) {
    if (runId.isBlank()) {
        return@withContext 0 to "run_id assente"
    }
    // Percorso canonico Runs API (v2026.9.x): POST /v1/runs/{id}/stop. Mai solo cancel locale.
    return@withContext try {
        val client = HermesRunClient(settings, apiKey, botProfile, botMultiplexEnabled, capabilities = null)
        client.stop(runId)
    } catch (ex: Exception) {
        0 to (ex.message ?: ex.javaClass.simpleName)
    }
}

internal suspend fun steerHermesRun(
    settings: AppSettings,
    runId: String,
    text: String,
    apiKey: String?,
    botProfile: String? = null,
    botMultiplexEnabled: Boolean = false
): Pair<Int, String> = withContext(Dispatchers.IO) {
    if (runId.isBlank()) return@withContext 0 to "run_id assente"
    if (text.isBlank()) return@withContext 400 to "Testo steer obbligatorio."
    return@withContext try {
        // Singolo tentativo: niente retry automatico su mutazione con esito incerto.
        HermesRunClient(settings, apiKey, botProfile, botMultiplexEnabled, null).steer(runId, text)
    } catch (ex: Exception) {
        0 to (ex.message ?: ex.javaClass.simpleName)
    }
}

internal suspend fun resolveHermesRunApproval(
    settings: AppSettings,
    runId: String,
    choice: String,
    apiKey: String?,
    requestId: String? = null,
    botProfile: String? = null,
    botMultiplexEnabled: Boolean = false
): Pair<Int, String> = withContext(Dispatchers.IO) {
    if (runId.isBlank()) return@withContext 0 to "run_id assente"
    return@withContext try {
        HermesRunClient(settings, apiKey, botProfile, botMultiplexEnabled, null).approval(runId, choice, requestId)
    } catch (ex: Exception) {
        0 to (ex.message ?: ex.javaClass.simpleName)
    }
}

