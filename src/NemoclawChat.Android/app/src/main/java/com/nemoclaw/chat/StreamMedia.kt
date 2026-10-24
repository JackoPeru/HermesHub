package com.nemoclaw.chat

import java.io.File
import java.net.URI

internal val INLINE_MEDIA_REGEX = Regex("""(?is)MEDIA\s*:\s*\[([^\]]{1,500})]\(([^)\s]{1,1200})\)|!\[([^\]]{1,500})]\(([^)\s]{1,1200})\)""")
internal val MEDIA_PROXY_LINE_REGEX = Regex("""(?im)^\s*(?:Media\s+proxy\s+URL|Media\s+URL|Download\s+URL|File\s+URL|URL\s+media|Link\s+download)\s*:\s*([^\s<>"'`]{1,1200})\s*$""")
internal val RAW_MEDIA_PROXY_URL_REGEX = Regex("""(?i)(?:https?://[^\s<>)"']+/v1/media/[^\s<>)"']{1,1200}|/v1/media/[^\s<>)"']{1,1200})""")
internal val RAW_IMAGE_URL_REGEX = Regex("""(?i)\bhttps://[^\s<>)"']{6,1200}""")

internal fun extractInlineMediaBlocks(text: String): List<VisualBlock> {
    val explicit = INLINE_MEDIA_REGEX.findAll(text).take(12).mapIndexed { index, match ->
        val label = (match.groups[1]?.value ?: match.groups[3]?.value ?: "Media Hermes").trim()
        val url = (match.groups[2]?.value ?: match.groups[4]?.value ?: "").trim()
        createInlineMediaBlock(index, label, url, explicit = true)
    }.toList()

    val seen = explicit.map { it.mediaUrl }.toMutableSet()
    val proxy = MEDIA_PROXY_LINE_REGEX.findAll(text)
        .map { it.groups[1]?.value.orEmpty().trimEnd('.', ',', ';', ':') }
        .plus(RAW_MEDIA_PROXY_URL_REGEX.findAll(text).map { it.value.trimEnd('.', ',', ';', ':') })
        .filter { it.isNotBlank() && it !in seen && isSafeMediaUrl(it) }
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
        .filter { it !in seen && isStrictRemoteImageUrl(it) }
        .take(12 - explicit.size - proxy.size)
        .mapIndexed { index, url ->
            seen += url
            createInlineMediaBlock(index + explicit.size + proxy.size, inferMediaFilename("immagine", url).ifBlank { "Immagine" }, url, explicit = false)
        }
        .toList()
    return explicit + proxy + raw
}

internal fun createInlineMediaBlock(index: Int, label: String, url: String, explicit: Boolean): VisualBlock {
        // L'agente a volte scrive token nudi (es. `![x](349a92b64d7.webp?hub_token=...)`)
        // invece del proxy completo: sono media_id validi sul gateway.
        val resolvedUrl = resolveBareMediaToken(url)
        val filename = inferMediaFilename(label, resolvedUrl)
        val kind = inferMediaKind(filename, resolvedUrl)
        return VisualBlock(
            id = "inline-media-${stableInlineId(resolvedUrl, index)}",
            type = "media_file",
            title = filename.ifBlank { "File multimediale" },
            mediaUrl = resolvedUrl,
            mediaKind = kind,
            mimeType = inferMimeType(filename, resolvedUrl),
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

internal fun stripInlineMediaMarkup(text: String): String {
    return INLINE_MEDIA_REGEX
        .replace(text, "")
        .let { MEDIA_PROXY_LINE_REGEX.replace(it, "") }
        .let { RAW_MEDIA_PROXY_URL_REGEX.replace(it, "") }
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

internal fun stableInlineId(value: String, index: Int): String {
    val hash = value.fold(17) { acc, ch -> acc * 31 + ch.code }
    return "${index}-${hash.toUInt().toString(16)}"
}

/**
 * Token mediatico nudo (`xxx.webp`, anche con `?...`) -> proxy gateway.
 * Solo token senza slash/schema/due-punti: prose, path Windows/Unix e URL
 * assoluti restano intatti. Puro e testabile.
 */
internal fun resolveBareMediaToken(url: String): String {
    val trimmed = url.trim().trimEnd('.', ',', ';', ':')
    if (trimmed.isEmpty()) return url
    if (trimmed.startsWith("/") || trimmed.startsWith("file:", ignoreCase = true) ||
        trimmed.startsWith("data:") || trimmed.contains("://") || trimmed.contains(':') ||
        trimmed.contains('\\') || trimmed.any { it.isWhitespace() }
    ) {
        return url
    }
    if (!BARE_MEDIA_TOKEN_REGEX.matches(trimmed)) return url
    return "/v1/media/$trimmed"
}

internal val BARE_MEDIA_TOKEN_REGEX = Regex(
    """(?i)^[^/\s?#]+\.(png|jpg|jpeg|webp|gif|bmp|mp4|m4v|mov|webm|mp3|wav|m4a|flac|ogg)([?#].*)?$"""
)

internal fun inferMediaFilename(label: String, url: String): String {
    val candidate = label.takeIf { it.contains('.') } ?: url.substringAfterLast('/').substringAfterLast('\\')
    return candidate.substringBefore('?').substringBefore('#').take(160)
}

internal fun inferMediaKind(filename: String, url: String): String {
    val value = "$filename $url".lowercase()
    return when {
        listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp").any { value.contains(it) } -> "image"
        isStrictRemoteImageUrl(url) -> "image"
        listOf(".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi", ".wmv", ".flv", ".mpg", ".mpeg", ".ts", ".m2ts", ".3gp", ".ogv").any { value.contains(it) } -> "video"
        listOf(".mp3", ".wav", ".m4a", ".flac", ".ogg").any { value.contains(it) } -> "audio"
        else -> "document"
    }
}

internal fun inferMimeType(filename: String, url: String): String {
    val value = "$filename $url".lowercase()
    return when {
        value.contains(".png") -> "image/png"
        value.contains(".jpg") || value.contains(".jpeg") -> "image/jpeg"
        value.contains(".webp") -> "image/webp"
        value.contains(".gif") -> "image/gif"
        isStrictRemoteImageUrl(url) -> "image/*"
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

internal val REMOTE_IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp")

/**
 * Euristica ristretta per le immagini remote: SOLO https con estensione
 * immagine oppure path /media/ o /proxy. La versione precedente accettava
 * qualunque URL contenente sottostringhe generiche ("image", "photo") o
 * host noti (picsum, unsplash, ...): chiaramente troppo permissiva.
 */
internal fun isStrictRemoteImageUrl(url: String): Boolean {
    val value = url.trim().lowercase()
    if (!value.startsWith("https://")) return false
    val withoutQuery = value.substringBefore('?').substringBefore('#')
    if (REMOTE_IMAGE_EXTENSIONS.any { withoutQuery.endsWith(it) }) return true
    val uriPath = runCatching { URI(url.trim()).path?.lowercase().orEmpty() }.getOrDefault("")
    return uriPath.contains("/media/") || uriPath.contains("/proxy/")
}

internal fun extractVisualBlocksFromJson(json: String): List<VisualBlock> {
    return try { extractVisualBlocks(json) } catch (_: Exception) { emptyList() }
}

