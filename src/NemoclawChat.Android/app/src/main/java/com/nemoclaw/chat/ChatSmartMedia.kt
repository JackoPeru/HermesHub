package com.nemoclaw.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * Fast path media: invece del turn agentico completo sul 27B, quando ci sono
 * allegati si tenta POST manager /jobs/smart (triage -> prompt-only LLM ->
 * submit). Accettato -> poll + rendering risultato. Rifiutato/fallito ->
 * null e il chiamante usa il flusso chat normale invariato.
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
    // 1. Upload esplicito (stessa fn del flusso normale).
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
    val managerBase = gpuManagerBase(settings.gatewayUrl)
    val managerKey = loadGatewaySecret(context)
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

internal fun smartResultBlocks(jobId: String, kind: String, urls: List<String>): List<VisualBlock> =
    urls.take(12).mapIndexed { index, url ->
        val name = url.substringAfterLast("/").substringBefore("?").ifBlank { "hermes-$jobId-$index" }
        VisualBlock(
            id = "smart-$jobId-$index",
            type = "media_file",
            title = name,
            filename = name,
            mediaKind = kind,
            mimeType = smartMimeType(url),
            alt = name,
            caption = "",
            mediaUrl = url
        )
    }

/**
 * Poll del job smart + rendering. Ritorna true se ha prodotto messaggi
 * terminali (il chiamante salta il flusso normale). Lancia CancellationException
 * su stop (il chiamante ripristina gli allegati).
 */
internal suspend fun runSmartCompletion(
    context: android.content.Context,
    state: ChatStateHolder,
    settings: AppSettings,
    managerBase: String,
    managerKey: String?,
    activeStreamCid: String,
    displayText: String,
    smart: SmartAccepted,
    attachments: List<ChatInputAttachment>,
    onStatusAdded: (ChatMessage?) -> Unit
): Boolean {
    val label = if (smart.kind == "video") "video" else "immagine"
    var status = ChatMessage(
        "Hermes Hub", "Fast path: $label in corso (job ${smart.jobId})...",
        fromUser = false, isAction = true
    )
    state.messages.add(status)
    onStatusAdded(status)
    var consecutiveErrors = 0
    var lastShown = ""
    repeat(270) {
        coroutineContext.ensureActive()
        delay(10_000)
        val (code, body) = httpGetResponse("$managerBase/jobs/${smart.jobId}", managerKey)
        if (code !in 200..299) {
            if (++consecutiveErrors > 12) {
                return finishSmartFailed(
                    state, status, onStatusAdded, attachments,
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
            val next = status.copy(
                text = "Fast path: $label $jobStatus ($pct%)..."
            )
            state.messages.remove(status)
            status = next
            state.messages.add(status)
            onStatusAdded(status)
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
                    return finishSmartFailed(
                        state, status, onStatusAdded, attachments,
                        "Fast path completato ma senza file pubblicati."
                    )
                }
                val resultText = if (smart.kind == "video") {
                    "Video pronto (fast path, ${smart.preset})."
                } else {
                    "Immagine pronta (fast path, ${smart.preset})."
                }
                state.messages.remove(status)
                onStatusAdded(null)
                state.messages.add(
                    ChatMessage(
                        "Hermes", resultText, fromUser = false,
                        visualBlocks = smartResultBlocks(smart.jobId, smart.kind, urls)
                    )
                )
                renameSmartConversation(context, settings, activeStreamCid, displayText, resultText)
                return true
            }
            "failed", "cancelled" -> {
                val err = root.optString("error").take(160)
                return finishSmartFailed(
                    state, status, onStatusAdded, attachments,
                    "Fast path fallito (${err.ifBlank { jobStatus }}): premi invia per la via normale."
                )
            }
        }
    }
    return finishSmartFailed(
        state, status, onStatusAdded, attachments,
        "Fast path: tempo scaduto, il job continua sul server. Premi invia per la via normale."
    )
}

private fun finishSmartFailed(
    state: ChatStateHolder,
    status: ChatMessage,
    onStatusAdded: (ChatMessage?) -> Unit,
    attachments: List<ChatInputAttachment>,
    message: String
): Boolean {
    state.messages.remove(status)
    onStatusAdded(null)
    // Allegati ripristinati: i file sono ancora in cache, l'invio normale riusa.
    state.pendingAttachments.addAll(attachments)
    state.messages.add(ChatMessage("Hermes Hub", message, fromUser = false, isAction = true))
    return true
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
