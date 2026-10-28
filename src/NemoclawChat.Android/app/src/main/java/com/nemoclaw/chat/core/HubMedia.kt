package com.nemoclaw.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** Encode per URL media/proxy: %20 per spazi (unquote server non tratta "+"). Puro. */
internal fun encodeMediaPathSegment(raw: String): String =
    URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

/** Radice immagini di default (denudate): usata quando le impostazioni non danno un percorso. */
internal const val DEFAULT_IMAGE_LIBRARY_PATH = "~/.hermes/hub_uploads/denudate"

/** Percorso iniziale del browser: impostazione se presente, senno denudate. Pura e testabile. */
internal fun defaultImageStartPath(imageLibraryPath: String, extraPaths: List<String>): String =
    imageLibraryPath.trim().ifBlank {
        extraPaths.firstOrNull { it.isNotBlank() }.orEmpty()
    }.trim().ifBlank { DEFAULT_IMAGE_LIBRARY_PATH }

private val BROWSABLE_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

/** Solo immagini nel browser: mime image oppure estensione foto nota. Pura e testabile. */
internal fun isBrowsableImage(filename: String, mimeType: String): Boolean {
    if (mimeType.trim().lowercase(java.util.Locale.ROOT).startsWith("image/")) return true
    val ext = filename.substringAfterLast('.', "").trim().lowercase(java.util.Locale.ROOT)
    return ext in BROWSABLE_IMAGE_EXTENSIONS
}

/** Cartella del browser immagini (manager /browse, un livello). */
internal data class ImageBrowseFolder(
    val name: String,
    val path: String
)

internal data class ImageBrowseResult(
    val path: String,
    val parent: String?,
    val folders: List<ImageBrowseFolder>,
    val items: List<ImageLibraryItem>,
    val status: String,
    // True solo a risposta 200 parsata: in errore la UI tiene la posizione buona.
    val ok: Boolean = false
)

/** Breadcrumb cumulativo: ("nome", pathCompleto) dalla root al corrente. Puro. */
internal fun browseSegments(currentPath: String): List<Pair<String, String>> {
    val normalized = currentPath.trim().replace('\\', '/').trimEnd('/')
    if (normalized.isEmpty()) return emptyList()
    val absolute = normalized.startsWith("/")
    val parts = normalized.split('/').filter { it.isNotBlank() }
    if (parts.isEmpty()) return emptyList()
    val out = mutableListOf<Pair<String, String>>()
    var acc = ""
    for (part in parts) {
        acc = if (acc.isEmpty()) (if (absolute) "/$part" else part) else "$acc/$part"
        out += part to acc
    }
    return out
}

/**
 * Sfoglia immagini via manager (/browse, un livello, solo immagini):
 * funziona col gateway attuale (il proxy /v1/media e live). I byte
 * arrivano dal proxy gateway con ?root= (doppio jail: manager + gateway).
 */
internal suspend fun loadImageBrowse(
    settings: AppSettings,
    managerKey: String?,
    path: String? = null
): ImageBrowseResult = withContext(Dispatchers.IO) {
    fun empty(status: String, current: String = path.orEmpty(), parent: String? = null) =
        ImageBrowseResult(current, parent, emptyList(), emptyList(), status)
    return@withContext try {
        val base = gpuManagerBase(settings.gatewayUrl)
        if (base.isBlank()) return@withContext empty("Indirizzo server non configurato.")
        // " /.hermes/..." senza home = quasi sempre "~/.hermes/...": il
        // server espande ~ e ri-applica il jail (mai un bypass).
        val wanted = path?.trim()?.takeIf { it.isNotBlank() }?.let {
            if (it == "/.hermes" || it.startsWith("/.hermes/")) "~" + it.substring(1) else it
        }
        val query = wanted?.let {
            "?path=${URLEncoder.encode(it, "UTF-8")}"
        }.orEmpty()
        val response = httpGetResponse("$base/browse$query", managerKey)
        if (response.first == 401) {
            return@withContext empty("Sfoglia immagini non autorizzato (HTTP 401): verifica la chiave API.")
        }
        if (response.first == 403) {
            return@withContext empty(
                "Cartella non consentita sul server (HTTP 403): controlla il percorso " +
                    "(es. ~/.hermes/hub_uploads/denudate)."
            )
        }
        if (response.first == 404) {
            return@withContext empty("Cartella non trovata sul server.")
        }
        if (response.first !in 200..299) {
            return@withContext empty("Sfoglia immagini HTTP ${response.first}: ${extractHumanError(response.second)}")
        }
        val body = response.second
        if (body.isBlank()) {
            return@withContext empty("Il manager non ha restituito dati.")
        }
        val json = JSONObject(body)
        if (json.has("error")) {
            return@withContext empty(extractHumanError(body))
        }
        val current = json.optString("path", path.orEmpty())
        val parent = json.optString("parent").takeIf { it.isNotBlank() }
        val folders = buildList {
            val array = json.optJSONArray("folders") ?: JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                val folderPath = obj.optString("path")
                if (name.isBlank() || folderPath.isBlank()) continue
                add(ImageBrowseFolder(name, folderPath))
            }
        }
        val items = buildList {
            val array = json.optJSONArray("items") ?: JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val filename = obj.optString("filename")
                val root = obj.optString("root", current)
                if (id.isBlank() || filename.isBlank() || root.isBlank()) continue
                val mime = obj.optString("mime_type", "image/*")
                // Il browser mostra solo immagini: mai anteprime rotte da altri file.
                if (!isBrowsableImage(filename, mime)) continue
                add(
                    ImageLibraryItem(
                        id = id,
                        title = obj.optString("title", filename.substringBeforeLast('.')),
                        filename = filename,
                        mediaUrl = "/v1/media/${encodeMediaPathSegment(id)}?root=${encodeMediaPathSegment(root)}",
                        path = obj.optString("path"),
                        mimeType = mime,
                        sizeBytes = obj.optLong("size_bytes", 0L),
                        modifiedAt = (obj.optDouble("modified_at", 0.0) * 1000).toLong(),
                        folder = current
                    )
                )
            }
        }
        val status = if (items.isEmpty() && folders.isEmpty()) {
            "Cartella vuota: ${current.ifBlank { "root server" }}."
        } else {
            "${items.size} immagini, ${folders.size} cartelle in: ${current.ifBlank { "root server" }}"
        }
        ImageBrowseResult(current, parent, folders, items, status, ok = true)
    } catch (ex: Exception) {
        empty("Errore sfoglia immagini: ${ex.message ?: ex.javaClass.simpleName}")
    }
}

internal suspend fun loadNewsLibrary(settings: AppSettings, apiKey: String?): Pair<List<NewsHtmlItem>, String> = withContext(Dispatchers.IO) {    return@withContext try {
        val newsPath = settings.newsLibraryPath.ifBlank { AppDefaults.newsLibraryPath }
        val encodedPath = URLEncoder.encode(newsPath, "UTF-8")
        val response = httpGetResponse(resolveHermesUrl(settings, "/v1/news/library?path=$encodedPath"), apiKey)
        if (response.first !in 200..299) {
            return@withContext emptyList<NewsHtmlItem>() to "News HTML HTTP ${response.first}: ${extractHumanError(response.second)}"
        }
        val body = response.second
        if (body.isBlank()) {
            return@withContext emptyList<NewsHtmlItem>() to "Gateway non ha restituito dati news."
        }
        val json = JSONObject(body)
        if (json.has("error")) {
            return@withContext emptyList<NewsHtmlItem>() to extractHumanError(body)
        }
        val libraryPath = json.optString("news_library_path", json.optString("library_path", newsPath))
        val array = json.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val url = obj.optString("url", obj.optString("media_url", obj.optString("download_url")))
                val filename = obj.optString("filename")
                if (url.isBlank() || filename.isBlank()) continue
                add(
                    NewsHtmlItem(
                        id = obj.optString("id", filename),
                        title = obj.optString("title", filename.substringBeforeLast('.')),
                        filename = filename,
                        url = url,
                        path = obj.optString("path"),
                        mimeType = obj.optString("mime_type", "text/html"),
                        sizeBytes = obj.optLong("size_bytes", 0L),
                        modifiedAt = (obj.optDouble("modified_at", 0.0) * 1000).toLong()
                    )
                )
            }
        }.sortedByDescending { it.modifiedAt }
        val status = if (items.isEmpty()) {
            "Cartella news sincronizzata: $libraryPath. Nessun HTML trovato."
        } else {
            "${items.size} pagine HTML trovate in: $libraryPath"
        }
        items to status
    } catch (ex: Exception) {
        emptyList<NewsHtmlItem>() to "Errore feed news: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun loadNewsHtml(settings: AppSettings, item: NewsHtmlItem, apiKey: String?): Pair<String, String> = withContext(Dispatchers.IO) {
    return@withContext try {
        val url = resolveWorkspaceUrl(settings, item.url)
        val response = httpGetResponse(
            url,
            apiKey?.takeIf { shouldAuthenticateHermesUrl(settings, url) }
        )
        if (response.first in 200..299) {
            response.second to "Pagina caricata in app."
        } else {
            "<html><body style=\"font-family:sans-serif;background:#111827;color:#fff\"><h1>Errore News</h1><p>HTTP ${response.first}: ${extractHumanError(response.second)}</p></body></html>" to "Errore HTTP ${response.first}."
        }
    } catch (ex: Exception) {
        "<html><body style=\"font-family:sans-serif;background:#111827;color:#fff\"><h1>Errore News</h1><p>${(ex.message ?: ex.javaClass.simpleName).replace("<", "&lt;").replace(">", "&gt;")}</p></body></html>" to "Errore apertura HTML."
    }
}

internal fun injectHtmlBase(html: String, baseUrl: String): String {
    val base = baseUrl.replace("\"", "%22")
    val tag = "<base href=\"$base\">"
    val headIndex = html.indexOf("<head", ignoreCase = true)
    if (headIndex >= 0) {
        val headEnd = html.indexOf('>', headIndex)
        if (headEnd >= 0) {
            return html.substring(0, headEnd + 1) + tag + html.substring(headEnd + 1)
        }
    }
    return "<!doctype html><html><head>$tag<meta charset=\"utf-8\"></head><body>$html</body></html>"
}

internal suspend fun loadHubNotifications(settings: AppSettings, apiKey: String?, unreadOnly: Boolean): Pair<List<HubNotification>, String> = withContext(Dispatchers.IO) {
    return@withContext try {
        val path = if (unreadOnly) "/v1/hub/notifications?unread=1" else "/v1/hub/notifications"
        val response = httpGetResponse(resolveHermesUrl(settings, path), apiKey, allowCompatAuth = false)
        if (response.first !in 200..299) {
            return@withContext emptyList<HubNotification>() to "Notifiche HTTP ${response.first}: ${extractHumanError(response.second)}"
        }
        val root = JSONObject(response.second)
        val array = root.optJSONArray("items") ?: root.optJSONArray("notifications") ?: JSONArray()
        val items = buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank()) continue
                add(
                    HubNotification(
                        id = id,
                        title = obj.optString("title", "Hermes"),
                        message = obj.optString("message", obj.optString("body", obj.optString("text", ""))),
                        kind = obj.optString("kind", "agent_message"),
                        severity = obj.optString("severity", obj.optString("type", "info")),
                        source = obj.optString("source", "hermes-agent"),
                        conversationPrompt = obj.optString("conversation_prompt", obj.optString("message", obj.optString("body", obj.optString("text", "")))),
                        createdAt = (obj.optDouble("created_at", 0.0) * 1000).toLong().let { if (it > 0) it else System.currentTimeMillis() },
                        readAt = (obj.optDouble("read_at", 0.0) * 1000).toLong().let { if (it == 0L && obj.optBoolean("read", false)) System.currentTimeMillis() else if (it == 0L) 0L else it },
                        category = obj.optString("category", obj.optString("kind", "Generale")),
                        priority = obj.optString("priority", obj.optString("severity", "Normale")),
                        archived = obj.optBoolean("archived", false),
                        snoozedUntil = (obj.optDouble("snoozed_until", 0.0) * 1000).toLong(),
                        automationId = obj.optString("automation_id", obj.optString("cron_id")),
                        runId = obj.optString("run_id"),
                        fileUrl = obj.optString("file_url", obj.optString("url")),
                        projectId = obj.optString("project_id")
                    )
                )
            }
        }.sortedByDescending { it.createdAt }
        val unread = items.count { it.readAt <= 0L }
        items to if (unread == 1) "1 notifica non letta." else "$unread notifiche non lette."
    } catch (ex: Exception) {
        emptyList<HubNotification>() to "Notifiche non disponibili: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun markHubNotificationRead(settings: AppSettings, id: String, apiKey: String?): String = withContext(Dispatchers.IO) {
    return@withContext try {
        val response = postJson(resolveHermesUrl(settings, "/v1/hub/notifications/${URLEncoder.encode(id, "UTF-8")}"), JSONObject().put("read", true), apiKey, "PATCH", allowCompatAuth = false)
        if (response.first in 200..299) "Notifica segnata come letta." else "Notifica non aggiornata: HTTP ${response.first}: ${extractHumanError(response.second)}"
    } catch (ex: Exception) {
        "Notifica non aggiornata: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun patchHubNotification(settings: AppSettings, id: String, patch: JSONObject, apiKey: String?): String = withContext(Dispatchers.IO) {
    return@withContext try {
        val response = postJson(resolveHermesUrl(settings, "/v1/hub/notifications/${URLEncoder.encode(id, "UTF-8")}"), patch, apiKey, "PATCH", allowCompatAuth = false)
        if (response.first in 200..299) "Notifica aggiornata." else "Notifica non aggiornata: HTTP ${response.first}: ${extractHumanError(response.second)}"
    } catch (ex: Exception) { "Notifica non aggiornata: ${ex.message ?: ex.javaClass.simpleName}" }
}

internal suspend fun loadCronJobs(
    settings: AppSettings,
    apiKey: String?,
    profile: String? = null,
    profileMultiplexEnabled: Boolean = false
): Pair<List<CronJob>, String> = withContext(Dispatchers.IO) {
    return@withContext try {
        val path = "/api/jobs?type=cron&include_disabled=1"
        val endpoint = if (profile.isNullOrBlank()) {
            resolveHermesUrl(settings, path)
        } else {
            resolveHermesProfileApiUrl(settings, profile, path, profileMultiplexEnabled)
        }
        val response = httpGetResponse(endpoint, apiKey)
        if (response.first !in 200..299) {
            return@withContext emptyList<CronJob>() to "Cron HTTP ${response.first}: ${extractHumanError(response.second)}"
        }
        val jobs = parseCronJobs(response.second)
            .sortedWith(compareByDescending<CronJob> { it.enabled }
                .thenBy { it.nextRunAt.ifBlank { "9999" } }
                .thenBy { it.name })
        val active = jobs.count { it.enabled }
        jobs to if (active == 1) "1 cron attivo." else "$active cron attivi."
    } catch (ex: Exception) {
        emptyList<CronJob>() to "Cron non disponibile: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun cronAction(
    settings: AppSettings,
    id: String,
    action: String,
    apiKey: String?,
    profile: String? = null,
    profileMultiplexEnabled: Boolean = false
): String = withContext(Dispatchers.IO) {
    if (id.isBlank()) return@withContext "ID cron mancante."
    return@withContext try {
        val encodedId = URLEncoder.encode(id, "UTF-8")
        val path = if (action == "delete") "/api/jobs/$encodedId" else "/api/jobs/$encodedId/$action"
        val method = if (action == "delete") "DELETE" else "POST"
        val endpoint = if (profile.isNullOrBlank()) {
            resolveHermesUrl(settings, path)
        } else {
            resolveHermesProfileApiUrl(settings, profile, path, profileMultiplexEnabled)
        }
        val response = postJson(endpoint, JSONObject(), apiKey, method)
        if (response.first in 200..299) {
            when (action) {
                "run" -> "Cron avviato."
                "pause" -> "Cron messo in pausa."
                "resume" -> "Cron riattivato."
                "delete" -> "Cron eliminato."
                else -> "Cron aggiornato."
            }
        } else {
            "Azione cron fallita: HTTP ${response.first}: ${extractHumanError(response.second)}"
        }
    } catch (ex: Exception) {
        "Azione cron fallita: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

internal suspend fun saveCronJob(
    settings: AppSettings,
    id: String?,
    name: String,
    schedule: String,
    prompt: String,
    deliver: String,
    apiKey: String?,
    profile: String? = null,
    profileMultiplexEnabled: Boolean = false,
    skills: String = ""
): String = withContext(Dispatchers.IO) {
    return@withContext try {
        val creating = id.isNullOrBlank()
        val path = if (creating) "/api/jobs" else "/api/jobs/${URLEncoder.encode(id, "UTF-8")}"
        val scopedName = if (profile.isNullOrBlank()) name.trim() else normalizeBotRoutineName(profile, name)
        val payload = JSONObject()
            .put("name", scopedName)
            .put("schedule", schedule.trim())
            .put("prompt", prompt)
            .put("deliver", deliver.trim().ifBlank { "local" })
        // Solo campi della whitelist /api/jobs (main): model/provider/reasoning/workdir/script/
        // no_agent/monitor/context_from/enabled_toolsets vivono sulla superficie dashboard
        // /api/cron/jobs (listener/auth diversi, non adatta al client esterno) e verrebbero scartati.
        val skillList = skills.split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (skillList.isNotEmpty()) payload.put("skills", JSONArray(skillList))
        val endpoint = if (profile.isNullOrBlank()) {
            resolveHermesUrl(settings, path)
        } else {
            resolveHermesProfileApiUrl(settings, profile, path, profileMultiplexEnabled)
        }
        val response = postJson(endpoint, payload, apiKey, if (creating) "POST" else "PATCH")
        if (response.first in 200..299) {
            if (creating) "Automazione creata." else "Automazione aggiornata."
        } else {
            "Automazione non salvata: HTTP ${response.first}: ${extractHumanError(response.second)}"
        }
    } catch (ex: Exception) {
        "Automazione non salvata: ${ex.message ?: ex.javaClass.simpleName}"
    }
}


internal fun createAttachmentFromUri(context: Context, uri: Uri, maxAttachmentMb: Int): ChatInputAttachment? {
    val resolver = context.contentResolver
    var declaredSize = -1L
    val filename = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        } else null
    } ?: (uri.lastPathSegment ?: "allegato")
    val mimeType = resolver.getType(uri)?.takeIf { it.isNotBlank() }
        ?: mimeTypeFromFilename(filename)
    val maxBytes = maxAttachmentMb.coerceIn(1, 150).toLong() * 1024L * 1024L
    if (declaredSize > maxBytes) return null
    val cacheDir = File(context.cacheDir, "attachments").apply { mkdirs() }
    pruneAttachmentCache(cacheDir)
    val extension = filename.substringAfterLast('.', "bin").filter { it.isLetterOrDigit() }.take(12).ifBlank { "bin" }
    val cached = File.createTempFile("attachment-", ".$extension", cacheDir)
    var total = 0L
    try {
        resolver.openInputStream(uri)?.use { input ->
            cached.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        cached.delete()
                        return null
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: run {
            cached.delete()
            return null
        }
    } catch (_: Exception) {
        cached.delete()
        return null
    }
    if (total <= 0L) {
        cached.delete()
        return null
    }
    return ChatInputAttachment(
        filename = filename,
        mimeType = mimeType,
        sizeBytes = total,
        localFilePath = cached.absolutePath
    )
}


internal fun pruneAttachmentCache(directory: File) {
    val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
    directory.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { runCatching { it.delete() } }
}

internal fun createLocalAttachmentBlocks(attachments: List<ChatInputAttachment>): List<VisualBlock> =
    attachments.take(12).mapIndexed { index, attachment ->
        VisualBlock(
            id = "local-media-$index-${java.util.UUID.randomUUID()}",
            type = "media_file",
            title = attachment.filename,
            filename = attachment.filename,
            mediaKind = inferVisualBlockMediaKind(attachment.filename, attachment.localFilePath ?: attachment.dataUrl),
            mimeType = attachment.mimeType,
            sizeBytes = attachment.sizeBytes,
            alt = attachment.filename,
            caption = "Condiviso con Hermes.",
            localDataUrl = attachment.localFilePath ?: attachment.dataUrl
        )
    }


internal fun mimeTypeFromFilename(filename: String): String {
    val lower = filename.lowercase(java.util.Locale.ROOT)
    return when {
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".bmp") -> "image/bmp"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".pdf") -> "application/pdf"
        lower.endsWith(".txt") -> "text/plain"
        lower.endsWith(".md") -> "text/markdown"
        lower.endsWith(".csv") -> "text/csv"
        lower.endsWith(".json") -> "application/json"
        lower.endsWith(".xml") -> "application/xml"
        lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
        lower.endsWith(".doc") -> "application/msword"
        lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        lower.endsWith(".xls") -> "application/vnd.ms-excel"
        lower.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        lower.endsWith(".ppt") -> "application/vnd.ms-powerpoint"
        lower.endsWith(".pptx") -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        lower.endsWith(".zip") -> "application/zip"
        else -> "application/octet-stream"
    }
}

internal suspend fun runDiagnostics(settings: AppSettings, apiKey: String?): List<DiagnosticCheck> = withContext(Dispatchers.IO) {
    val checks = listOf(
        "Tailscale/API" to "/health",
        "Health dettagliata" to "/health/detailed",
        "Modelli" to "/v1/models",
        "Capabilities" to "/v1/capabilities",
        "Hardware" to "/v1/hub/hardware",
        "Media proxy" to "/v1/media/not-a-real-media-id",
        "Video library" to "/v1/video/library",
        "Memoria" to "/v1/hub/memory",
        "Hub state" to "/v1/hub/state"
    )

    val (discoveryLabel, discoveryPath) = checks.first()
    val discoveryEndpoint = resolveHermesUrl(settings, discoveryPath)
    val discovery = probeDiagnosticEndpoint(discoveryEndpoint, apiKey)
    val discoveryCheck = diagnosticCheck(discoveryLabel, discoveryPath, discovery)

    if (discovery.statusCode == null || discovery.statusCode == 401) {
        val reason = if (discovery.statusCode == 401) {
            "Probe base HTTP 401: check non eseguito per evitare retry auth ridondanti."
        } else {
            "Gateway non raggiungibile: check non eseguito per evitare retry host/auth ridondanti."
        }
        return@withContext listOf(discoveryCheck) + checks.drop(1).map { (label, path) ->
            val requestedEndpoint = resolveHermesUrl(settings, path)
            DiagnosticCheck(
                label = label,
                endpoint = diagnosticEffectiveUrl(requestedEndpoint, discovery.effectiveUrl),
                ok = false,
                message = reason,
                action = diagnosticAction(label)
            )
        }
    }

    val remaining = coroutineScope {
        checks.drop(1).map { (label, path) ->
            async {
                val requestedEndpoint = resolveHermesUrl(settings, path)
                val effectiveEndpoint = diagnosticEffectiveUrl(requestedEndpoint, discovery.effectiveUrl)
                val response = probePinnedDiagnosticEndpoint(effectiveEndpoint, discovery.bearerToken)
                diagnosticCheck(label, path, response)
            }
        }.awaitAll()
    }
    listOf(discoveryCheck) + remaining
}

