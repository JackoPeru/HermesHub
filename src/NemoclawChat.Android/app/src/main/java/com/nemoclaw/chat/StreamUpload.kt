package com.nemoclaw.chat

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import android.util.Base64
import android.util.Base64OutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.net.URI

internal data class SseOpenResult(
    val accepted: Boolean,
    val terminal: Boolean,
    val error: String?
)

internal data class AttachmentPreparation(
    val prompt: String,
    val inlineAttachments: List<ChatInputAttachment>,
    val uploadedCount: Int,
    val uploadErrors: List<String>
)

/**
 * Vera quando l'upload e' parziale o fallito: uploadErrors non vuoto oppure
 * uploadedCount inferiore agli allegati richiesti. In quel caso l'invio va
 * bloccato con Error esplicito, senza fallback al subset riuscito.
 */
internal fun isPartialUploadBlocked(totalAttachments: Int, uploadedCount: Int, uploadErrors: List<String>): Boolean {
    if (totalAttachments <= 0) return false
    if (uploadErrors.isNotEmpty()) return true
    return uploadedCount < totalAttachments
}

internal fun partialUploadBlockMessage(totalAttachments: Int, uploadedCount: Int, uploadErrors: List<String>): String {
    val detail = uploadErrors.joinToString("; ").take(320).ifBlank {
        "$uploadedCount/$totalAttachments allegati caricati"
    }
    return "Upload allegati incompleto ($uploadedCount/$totalAttachments riusciti): $detail. " +
        "Invio bloccato, nessun allegato inviato in forma parziale: riprova."
}


internal fun buildMultimodalInput(prompt: String, attachments: List<ChatInputAttachment>): Any {
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

internal fun buildChatCompletionsContent(prompt: String, attachments: List<ChatInputAttachment>): Any {
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

internal fun ChatInputAttachment.inlineDataUrl(): String {
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

internal suspend fun buildPromptWithAttachmentToolRefs(
    settings: AppSettings,
    prompt: String,
    attachments: List<ChatInputAttachment>,
    apiKey: String?,
    botProfile: String?,
    botMultiplexEnabled: Boolean,
    allowCompatAuth: Boolean
): AttachmentPreparation {
    if (attachments.isEmpty()) return AttachmentPreparation(prompt, emptyList(), 0, emptyList())
    val refs = attachments.map { uploadAttachmentForTool(settings, it, apiKey, botProfile, botMultiplexEnabled, allowCompatAuth) }
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

/**
 * Endpoint upload allegati: SOLO /v1/media/upload esiste sul gateway (nudo
 * 404). resolveHermesUrl gestisce root con/senza /v1; il profilo multiplex
 * reinserisce /v1 dopo /p/<profile> (come AutomationFeature). Puro e testabile.
 */
internal fun attachmentUploadEndpoint(settings: AppSettings, botProfile: String?, botMultiplexEnabled: Boolean): String {
    return if (botProfile.isNullOrBlank()) {
        resolveHermesUrl(settings, "/v1/media/upload")
    } else {
        resolveHermesProfileUrl(settings, botProfile, "/v1/media/upload", botMultiplexEnabled)
    }
}

internal suspend fun uploadAttachmentForTool(
    settings: AppSettings,
    attachment: ChatInputAttachment,
    apiKey: String?,
    botProfile: String?,
    botMultiplexEnabled: Boolean,
    allowCompatAuth: Boolean
): UploadedAttachmentRef {
    // Upload media: SOLO /v1/media/upload esiste sul gateway (nudo 404).
    val endpoint = attachmentUploadEndpoint(settings, botProfile, botMultiplexEnabled)
    val body = attachment.streamingJsonUploadBody()
    val authCandidates = hermesAuthCandidates(apiKey, allowCompatAuth)
    val requestContext = HermesHubProtocol.newCorrelationContext()
    var lastError = "gateway non raggiungibile"
    candidateLoop@ for (candidateUrl in plugAndPlayStreamUrlCandidates(endpoint)) {
        for ((index, token) in authCandidates.withIndex()) {
            // Fail-closed profilo: mai fallback anonimo su /p/<profile>/.
            if (candidateUrl.contains("/p/", ignoreCase = true) && token.isNullOrBlank()) continue
            val builder = Request.Builder()
                .url(candidateUrl)
                .header("Accept", "application/json")
                .header("User-Agent", "HermesHub-Android")
                .post(body)
            HermesHubProtocol.addCorrelationHeaders(builder, requestContext)
            token?.let { builder.header("Authorization", "Bearer $it") }
            val response = executeCancellableRequest(builder.build())
            if (response.first == 0) {
                lastError = response.second
                continue@candidateLoop
            }
            if (response.first !in 200..299) {
                if (index < authCandidates.lastIndex && shouldRetryHermesWithBearerAuth(response.first, response.second, token)) {
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

/** Lunghezza base64 NO_WRAP esatta per n byte: serve al Content-Length senza bufferizzare. */
internal fun base64EncodedLength(rawBytes: Long): Long = ((rawBytes + 2) / 3) * 4

internal fun ChatInputAttachment.streamingJsonUploadBody(): RequestBody {
    val file = localFilePath?.let(::File)?.takeIf { it.isFile }
    if (file == null) {
        val json = JSONObject()
            .put("filename", filename)
            .put("mime_type", mimeType)
            .put("data_url", dataUrl)
            .toString()
            .toByteArray(Charsets.UTF_8)
        return object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = json.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                sink.write(json)
            }
        }
    }
    // Content-Length esplicita: senza, OkHttp va in chunked e il gateway
    // risponde 411 (Content-Length required). Lunghezza base64 esatta:
    // 4 byte ogni 3 di file, senza wrap.
    val prefix = "{\"filename\":${JSONObject.quote(filename)},\"mime_type\":${JSONObject.quote(mimeType)},\"data_url\":\"data:${mimeType};base64,"
        .toByteArray(Charsets.UTF_8)
    val suffix = "\"}".toByteArray(Charsets.UTF_8)
    val base64Len = base64EncodedLength(file.length())
    return object : RequestBody() {
        override fun contentType() = "application/json; charset=utf-8".toMediaType()
        override fun contentLength() = prefix.size + base64Len + suffix.size

        override fun writeTo(sink: BufferedSink) {
            sink.write(prefix)
            sink.flush()
            val encoder = Base64OutputStream(sink.outputStream(), Base64.NO_WRAP or Base64.NO_CLOSE)
            file.inputStream().use { input -> input.copyTo(encoder) }
            encoder.close()
            sink.write(suffix)
        }
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

