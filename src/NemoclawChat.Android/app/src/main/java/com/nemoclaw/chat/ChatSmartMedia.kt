package com.nemoclaw.chat

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * Fast path media: invece del turn agentico completo sul 27B, quando ci sono
 * allegati si tenta POST manager /jobs/smart (triage -> prompt-only LLM ->
 * submit). Accettato -> poll + rendering risultato. Rifiutato/fallito ->
 * null e il chiamante usa il flusso chat normale invariato.
 *
 * Tutte le scritture UI sono vincolate alla conversazione che ha generato
 * l'invio (come collectFlow): se l'utente cambia chat a meta poll, niente
 * finisce nella chat sbagliata, ma snapshot e rename usano sempre l'id
 * originale. Nessuna chiamata parte senza chiave (fail-closed).
 */

internal data class SmartAccepted(
    val jobId: String,
    val preset: String,
    val prompt: String,
    val kind: String
)

internal suspend fun trySmartMediaSend(
    context: android.content.Context,
    settings: AppSettings,
    botApiKey: String?,
    botProfile: String?,
    botMultiplexEnabled: Boolean,
    botAllowCompatAuth: Boolean,
    text: String,
    attachments: List<ChatInputAttachment>
): SmartAccepted? {
    if (attachments.isEmpty()) return null
    // Chiave assente -> niente chiamate anonime al manager.
    val managerKey = loadGatewaySecret(context)?.takeIf { it.isNotBlank() } ?: return null
    val managerBase = gpuManagerBase(settings.gatewayUrl)
    // Fase 0: verdetto senza file. Se non e media, il flusso normale carica
    // una volta sola dentro streamChatRequest: niente doppio upload.
    val verdictPayload = JSONObject()
        .put("text", text)
        .put("has_image", true)
        .put("triage_only", true)
    val (verdictCode, verdictBody) = postJson(
        "$managerBase/jobs/smart", verdictPayload, managerKey, allowCompatAuth = false
    )
    if (verdictCode !in 200..299) return null
    val verdict = runCatching { JSONObject(verdictBody) }.getOrNull() ?: return null
    if (!verdict.optBoolean("media", false)) return null
    // 1. Upload esplicito (stessa fn del flusso normale). Al primo errore si
    // abortisce e il flusso normale riprova da zero: niente subset silenziosi.
    // (I file gia caricati restano orfani sul gateway: retention lato server.)
    val serverPaths = mutableListOf<String>()
    for (attachment in attachments) {
        val ref = uploadAttachmentForTool(
            settings, attachment, botApiKey, botProfile,
            botMultiplexEnabled, botAllowCompatAuth
        )
        if (!ref.error.isNullOrBlank()) return null
        val path = ref.path?.takeIf { it.isNotBlank() } ?: return null
        serverPaths.add(path)
    }
    // 2. Triage + submit manager.
    val payload = JSONObject()
        .put("text", text)
        .put("input_images", org.json.JSONArray(serverPaths))
    val (code, body) = postJson(
        "$managerBase/jobs/smart", payload, managerKey, allowCompatAuth = false
    )
    if (code !in 200..299) return null
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    if (!root.optBoolean("media", false)) return null
    val jobId = root.optString("job_id").takeIf { it.isNotBlank() } ?: return null
    val preset = root.optString("preset")
    val prompt = root.optString("prompt")
    val kind = if (preset.startsWith("journey_video")) "video" else "image"
    return SmartAccepted(jobId, preset, prompt, kind)
}

/** Cancella un job smart lato server (fire-and-forget, mai fatale). */
internal fun cancelSmartJob(managerBase: String, managerKey: String?, jobId: String) {
    HermesStreamRuntime.scope.launch {
        runCatching {
            postJson(
                "$managerBase/jobs/${URLEncoder.encode(jobId, "UTF-8")}/cancel",
                JSONObject(), managerKey, allowCompatAuth = false
            )
        }
    }
}

internal fun smartMimeType(url: String): String {
    val lower = url.substringBefore("?").lowercase()
    return when {
        lower.endsWith(".mp4") -> "video/mp4"
        lower.endsWith(".webm") -> "video/webm"
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        else -> ""
    }
}

/** kind dall'estensione reale, preset solo come fallback: mai incoerenti. */
internal fun smartKindForUrl(url: String, presetKind: String): String {
    val lower = url.substringBefore("?").lowercase()
    return when {
        lower.endsWith(".mp4") || lower.endsWith(".webm") ||
            lower.endsWith(".mov") || lower.endsWith(".m3u8") -> "video"
        lower.endsWith(".png") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".webp") ||
            lower.endsWith(".gif") -> "image"
        else -> presetKind.ifBlank { "image" }
    }
}

internal fun smartResultBlocks(jobId: String, kind: String, urls: List<String>): List<VisualBlock> =
    urls.take(12).mapIndexed { index, url ->
        val name = url.substringAfterLast("/").substringBefore("?").ifBlank { "hermes-$jobId-$index" }
        val resolvedKind = smartKindForUrl(url, kind)
        VisualBlock(
            id = "smart-$jobId-$index",
            type = "media_file",
            title = name,
            filename = name,
            mediaKind = resolvedKind,
            mimeType = smartMimeType(url),
            alt = name,
            caption = "",
            mediaUrl = url
        )
    }

/**
 * Poll del job smart + rendering. Ritorna true se ha prodotto messaggi
 * terminali (il chiamante salta il flusso normale). Lancia CancellationException
 * su stop (il chiamante ripristina gli allegati e cancella lato server).
 */
internal suspend fun runSmartCompletion(
    context: android.content.Context,
    state: ChatStateHolder,
    settings: AppSettings,
    managerBase: String,
    managerKey: String,
    streamCid: String,
    historyBase: List<ChatMessage>,
    mode: String,
    displayText: String,
    prevId: String?,
    smart: SmartAccepted,
    attachments: List<ChatInputAttachment>,
    onStatusAdded: (ChatMessage?) -> Unit
): Boolean {
    val done = mutableListOf<ChatMessage>()
    var shown: ChatMessage? = null
    fun show(message: ChatMessage) {
        shown?.let { state.messages.remove(it) }
        shown = message
        // Solo nella conversazione giusta, come collectFlow.
        if (state.activeConversationId == streamCid) state.messages.add(message)
        onStatusAdded(message)
    }
    fun unshow() {
        shown?.let { state.messages.remove(it) }
        shown = null
        onStatusAdded(null)
    }
    val label = if (smart.kind == "video") "video" else "immagine"
    show(
        ChatMessage(
            "Hermes Hub", "Fast path: $label in corso (job ${smart.jobId})...",
            fromUser = false, isAction = true
        )
    )
    suspend fun finishFailed(message: String): Boolean {        unshow()
        // Allegati ripristinati senza duplicati: i file sono ancora in cache.
        val fresh = attachments.filter { it !in state.pendingAttachments }
        state.pendingAttachments.addAll(fresh)
        val error = ChatMessage("Hermes Hub", message, fromUser = false, isAction = true)
        done.add(error)
        if (state.activeConversationId == streamCid) state.messages.add(error)
        persistSmartSnapshot(context, settings, streamCid, mode, displayText, prevId, historyBase + done)
        return true
    }
    var consecutiveErrors = 0
    var lastShown = ""
    // Cap 70min: oltre il job_timeout_video di Comfy (60min).
    repeat(420) {
        coroutineContext.ensureActive()
        delay(10_000)
        val encodedId = runCatching { URLEncoder.encode(smart.jobId, "UTF-8") }.getOrNull() ?: smart.jobId
        val (code, body) = httpGetResponse("$managerBase/jobs/$encodedId", managerKey)
        if (code !in 200..299) {
            if (++consecutiveErrors > 12) {
                return finishFailed(
                    "Fast path: manager non risponde, premi invia per la via normale."
                )
            }
            return@repeat
        }
        consecutiveErrors = 0
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return@repeat
        val jobStatus = root.optString("status")
        val progress = root.optDouble("progress", 0.0)
        val bucket = "${jobStatus}_${(progress * 10).toInt()}"
        if (bucket != lastShown) {
            lastShown = bucket
            val pct = (progress * 100).toInt().coerceIn(0, 100)
            show(
                (shown ?: ChatMessage("Hermes Hub", "", fromUser = false, isAction = true)).copy(
                    text = "Fast path: $label $jobStatus ($pct%)..."
                )
            )
        }
        when (jobStatus) {
            "done" -> {
                val params = root.optJSONObject("parameters")
                val urls = mutableListOf<String>()
                params?.optJSONArray("media_urls")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let { urls.add(it) }
                    }
                }
                if (urls.isEmpty()) {
                    return finishFailed("Fast path completato ma senza file pubblicati.")
                }
                var resultText = if (smart.kind == "video") {
                    "Video pronto (fast path, ${smart.preset})."
                } else {
                    "Immagine pronta (fast path, ${smart.preset})."
                }
                if (urls.size > 12) resultText += " (+${urls.size - 12} altri file non mostrati)"
                unshow()
                val result = ChatMessage(
                    "Hermes", resultText, fromUser = false,
                    visualBlocks = smartResultBlocks(smart.jobId, smart.kind, urls)
                )
                done.add(result)
                if (state.activeConversationId == streamCid) state.messages.add(result)
                persistSmartSnapshot(context, settings, streamCid, mode, displayText, prevId, historyBase + done)
                renameSmartConversation(context, settings, streamCid, displayText, resultText)
                return true
            }
            "failed", "cancelled" -> {
                val err = root.optString("error").take(160)
                return finishFailed(
                    "Fast path fallito (${err.ifBlank { jobStatus }}): premi invia per la via normale."
                )
            }
        }
    }
    return finishFailed(
        "Fast path: tempo scaduto, il job continua sul server. Premi invia per la via normale."
    )
}

private suspend fun persistSmartSnapshot(
    context: android.content.Context,
    settings: AppSettings,
    streamCid: String,
    mode: String,
    displayText: String,
    prevId: String?,
    messages: List<ChatMessage>
) {
    withContext(Dispatchers.IO) {
        saveConversationSnapshot(
            context = context,
            conversationId = streamCid,
            mode = mode,
            prompt = displayText,
            messages = messages,
            source = "Hermes fast path",
            responseId = prevId,
            projectId = settings.activeProjectId,
            syncAfterSave = false
        )
    }
}

private suspend fun renameSmartConversation(
    context: android.content.Context,
    settings: AppSettings,
    activeStreamCid: String,
    displayText: String,
    resultText: String
) {
    // Stesso rename del flusso normale (best effort, mai fatale).
    val title = runCatching {
        generateConversationTitle(
            settings = settings,
            firstPrompt = displayText,
            firstAnswer = resultText,
            apiKey = loadGatewaySecret(context)
        )
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
    withContext(Dispatchers.IO) {
        runCatching { renameConversation(context, activeStreamCid, title) }
    }
}
