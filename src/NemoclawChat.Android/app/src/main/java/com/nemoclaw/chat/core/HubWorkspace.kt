package com.nemoclaw.chat

import android.webkit.WebView
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

internal fun workspaceInstructions(settings: AppSettings, kind: String, prompt: String): String {
    return if (kind.equals("Video", ignoreCase = true)) {
        """
            Destinazione: Hermes Hub / Video.
            Cartella video monitorata: ${settings.videoLibraryPath}
            Memoria: usa la memoria agente condivisa Hermes/CLI/app per preferenze utente, stile, durata, ritmo, fonti e regole editoriali. Se impari una preferenza stabile, salvala lato Hermes se possibile.
            Obiettivo: crea o programma un video personale per l'utente. File finale pensato per comparire automaticamente nella cartella video monitorata; il telefono riceve solo metadati, stream_url e download_url opzionale.
            Produzione: ricerca tema se necessario, crea script, storyboard, asset plan, eventuale Remotion project/render o pipeline IA se disponibile lato server.
            Feedback: usa feedback precedenti per adattare durata, ritmo, editing, tono, fonti, voce, musica e livello tecnico.
            Output JSON richiesto: {"kind":"Video","title":"...","summary":"...","status":"...","job_id":"...","stream_url":"...","download_url":"...","sources":[]}

            Richiesta utente:
            $prompt
        """.trimIndent()
    } else {
        """
            Destinazione: Hermes Hub / News.
            Cartella news monitorata: ${settings.newsLibraryPath}
            Memoria: usa la memoria agente condivisa Hermes/CLI/app per interessi, fonti preferite, profondita, tono e filtri di qualita. Se impari una preferenza stabile, salvala lato Hermes se possibile.
            Obiettivo: crea un articolo/briefing personale per l'utente con fonti verificabili e sintesi ragionata.
            Produzione: cerca notizie rilevanti, filtra per interesse, cita fonti, separa fatti da inferenze e prepara testo leggibile come giornale personale. Se l'utente chiede formato giornale online/HTML, salva il file finale nella cartella news monitorata/news_library_path/HERMES_NEWS_LIBRARY_PATH: Hermes Hub lo legge con /v1/news/library e lo mostra in WebView interna.
            Feedback: usa feedback precedenti per adattare argomenti, profondita, tono, fonti e frequenza.
            Output JSON richiesto: {"kind":"News","title":"...","summary":"...","status":"...","job_id":"...","download_url":"/v1/media/...","sources":[{"title":"...","url":"..."}]}

            Richiesta utente:
            $prompt
        """.trimIndent()
    }
}

internal fun parseWorkspaceArtifact(kind: String, body: String): WorkspaceArtifact {
    val json = findFirstJSONObject(body) ?: return WorkspaceArtifact(result = body.limitText(1600))
    val title = json.extractString("title").orEmpty()
    val status = json.extractString("status")
        ?: json.extractNestedString("job", "status")
        ?: json.extractNestedString("task", "status")
        ?: ""
    val streamUrl = json.extractString("stream_url")
        ?: json.extractString("streamUrl")
        ?: json.extractNestedString("media", "stream_url")
        ?: ""
    val downloadUrl = json.extractString("download_url")
        ?: json.extractString("downloadUrl")
        ?: json.extractNestedString("media", "download_url")
        ?: ""
    val summary = json.extractString("summary")
        ?: json.extractString("article")
        ?: json.extractString("body")
        ?: json.extractString("result")
        ?: body.limitText(1600)
    val sources = json.optJSONArray("sources")?.let { array ->
        buildString {
            append("\n\nFonti:\n")
            for (i in 0 until minOf(array.length(), 12)) {
                val src = array.optJSONObject(i) ?: continue
                append("- ")
                append(src.optString("title", src.optString("url", "Fonte")))
                src.optString("url").takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
                append('\n')
            }
        }
    }.orEmpty()
    return WorkspaceArtifact(
        title = title.ifBlank { makeTitle(summary.ifBlank { kind }) },
        result = (summary + sources).trim(),
        status = status,
        streamUrl = streamUrl,
        downloadUrl = downloadUrl
    )
}

internal fun findFirstJSONObject(body: String): JSONObject? {
    val trimmed = body.trim()
    if (trimmed.startsWith("{")) {
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    if (start >= 0 && end > start) {
        return runCatching { JSONObject(trimmed.substring(start, end + 1)) }.getOrNull()
    }
    return null
}

internal fun detectWorkspaceIntent(prompt: String): String? {
    val text = prompt.lowercase()
    val asksProduction = listOf("crea", "genera", "fammi", "prepara", "programma", "cron", "job", "ogni mattina", "ogni giorno")
        .any { text.contains(it) }
    if (!asksProduction) return null
    if (listOf("video", "remotion", "youtube", "montaggio", "storyboard").any { text.contains(it) }) return "Video"
    if (listOf("news", "notizie", "articolo", "giornale", "fonti", "briefing").any { text.contains(it) }) return "News"
    return null
}

internal fun resolveWorkspaceUrl(settings: AppSettings, url: String): String {
    if (url.startsWith("http://", true) || url.startsWith("https://", true)) return url
    return "${hermesRoot(settings)}${if (url.startsWith('/')) url else "/$url"}"
}

internal fun resolveHermesUrl(settings: AppSettings, path: String): String {
    if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) return path
    val normalized = if (path.startsWith("/")) path else "/$path"
    return if (normalized.startsWith("/v1", ignoreCase = true) ||
        normalized.startsWith("/api/", ignoreCase = true) ||
        normalized.startsWith("/health", ignoreCase = true)
    ) {
        "${hermesRoot(settings)}$normalized"
    } else {
        "${settings.gatewayUrl.trimEnd('/')}$normalized"
    }
}

internal fun resolveHermesProfileUrl(
    settings: AppSettings,
    profile: String,
    path: String,
    multiplexEnabled: Boolean
): String {
    check(multiplexEnabled) { "Il multiplexing dei profili Hermes non è pronto." }
    val name = normalizeHermesProfileName(profile)
    val root = hermesRoot(settings).trimEnd('/')
    val normalized = if (path.startsWith('/')) path else "/$path"
    val suffix = if (normalized.startsWith("/v1", ignoreCase = true)) normalized.removePrefix("/v1") else normalized
    return "$root/p/${URLEncoder.encode(name, "UTF-8")}/v1$suffix"
}

internal fun resolveHermesProfileApiUrl(
    settings: AppSettings,
    profile: String,
    path: String,
    multiplexEnabled: Boolean
): String {
    check(multiplexEnabled) { "Il multiplexing dei profili Hermes non è pronto." }
    val name = normalizeHermesProfileName(profile)
    val root = hermesRoot(settings).trimEnd('/')
    val normalized = if (path.startsWith('/')) path else "/$path"
    return "$root/p/${URLEncoder.encode(name, "UTF-8")}$normalized"
}

internal fun normalizeHermesProfileName(profile: String): String {
    val name = profile.trim().lowercase()
    require(name.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) { "Nome profilo non valido." }
    return name
}

internal fun normalizeBotRoutineName(profile: String, name: String): String {
    val normalizedProfile = normalizeHermesProfileName(profile)
    var value = name.trim()
    if (value.startsWith("[bot:", ignoreCase = true)) {
        val close = value.indexOf(']')
        if (close >= 0) value = value.substring(close + 1).trim()
    }
    require(value.isNotBlank()) { "Il nome della routine è obbligatorio." }
    return "[bot:$normalizedProfile] $value"
}

internal suspend fun loadVideoLibrary(settings: AppSettings, apiKey: String?): Pair<List<VideoLibraryItem>, String> = withContext(Dispatchers.IO) {
    return@withContext try {
        val response = httpGetResponse(resolveHermesUrl(settings, "/v1/video/library"), apiKey)
        if (response.first !in 200..299) {
            return@withContext emptyList<VideoLibraryItem>() to "Feed video HTTP ${response.first}: ${extractHumanError(response.second)}"
        }
        val body = response.second
        if (body.isBlank()) {
            return@withContext emptyList<VideoLibraryItem>() to "Gateway non ha restituito dati video."
        }
        val json = JSONObject(body)
        if (json.has("error")) {
            return@withContext emptyList<VideoLibraryItem>() to extractHumanError(body)
        }
        val libraryPath = json.optString("video_library_path", json.optString("library_path", settings.videoLibraryPath))
        val array = json.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val mediaUrl = obj.optString("media_url")
                val filename = obj.optString("filename")
                if (mediaUrl.isBlank() || filename.isBlank()) continue
                add(
                    VideoLibraryItem(
                        id = obj.optString("id", filename),
                        title = obj.optString("title", filename.substringBeforeLast('.')),
                        filename = filename,
                        mediaUrl = mediaUrl,
                        playbackUrl = obj.optString("playback_url", obj.optString("playbackUrl")),
                        compatUrl = obj.optString("compat_url", obj.optString("compatUrl")),
                        thumbnailUrl = obj.optString("thumbnail_url", obj.optString("thumbnailUrl")),
                        path = obj.optString("path"),
                        mimeType = obj.optString("mime_type", "video/*"),
                        sizeBytes = obj.optLong("size_bytes", 0L),
                        durationMs = obj.optLong("duration_ms", obj.optLong("durationMs", 0L)),
                        modifiedAt = (obj.optDouble("modified_at", 0.0) * 1000).toLong()
                    )
                )
            }
        }
        val status = if (items.isEmpty()) {
            "Cartella video sincronizzata: $libraryPath. Nessun video trovato."
        } else {
            "${items.size} video trovati in: $libraryPath"
        }
        items to status
    } catch (ex: Exception) {
        emptyList<VideoLibraryItem>() to "Errore feed video: ${ex.message ?: ex.javaClass.simpleName}"
    }
}

/**
 * Libreria immagini: interroga la cartella indicata (o quella di default del
 * server se path e nullo/vuoto). Ritorna item, messaggio stato e path reale.
 */
internal suspend fun loadImageLibrary(    settings: AppSettings,
    apiKey: String?,
    path: String? = null
): Triple<List<ImageLibraryItem>, String, String> = withContext(Dispatchers.IO) {
    return@withContext try {
        val query = path?.trim()?.takeIf { it.isNotBlank() }?.let {
            "?path=${URLEncoder.encode(it, "UTF-8")}"
        }.orEmpty()
        val response = httpGetResponse(resolveHermesUrl(settings, "/v1/image/library$query"), apiKey)
        if (response.first !in 200..299) {
            return@withContext Triple(
                emptyList(),
                "Immagini HTTP ${response.first}: ${extractHumanError(response.second)}",
                path.orEmpty()
            )
        }
        val body = response.second
        if (body.isBlank()) {
            return@withContext Triple(emptyList(), "Gateway non ha restituito dati immagini.", path.orEmpty())
        }
        val json = JSONObject(body)
        if (json.has("error")) {
            return@withContext Triple(emptyList(), extractHumanError(body), path.orEmpty())
        }
        val libraryPath = json.optString("image_library_path", json.optString("library_path", path.orEmpty()))
        val array = json.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val mediaUrl = obj.optString("media_url", obj.optString("url"))
                val filename = obj.optString("filename")
                if (mediaUrl.isBlank() || filename.isBlank()) continue
                add(
                    ImageLibraryItem(
                        id = obj.optString("id", filename),
                        title = obj.optString("title", filename.substringBeforeLast('.')),
                        filename = filename,
                        mediaUrl = mediaUrl,
                        path = obj.optString("path"),
                        mimeType = obj.optString("mime_type", "image/*"),
                        sizeBytes = obj.optLong("size_bytes", 0L),
                        modifiedAt = (obj.optDouble("modified_at", 0.0) * 1000).toLong(),
                        folder = libraryPath
                    )
                )
            }
        }
        val status = if (items.isEmpty()) {
            "Nessuna immagine in: ${libraryPath.ifBlank { "cartella server" }}."
        } else {
            "${items.size} immagini in: ${libraryPath.ifBlank { "cartella server" }}"
        }
        Triple(items, status, libraryPath)
    } catch (ex: Exception) {
        Triple(emptyList(), "Errore feed immagini: ${ex.message ?: ex.javaClass.simpleName}", path.orEmpty())
    }
}

