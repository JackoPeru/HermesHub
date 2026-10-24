package com.nemoclaw.chat

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

internal fun visualBlocksMetadata(settings: AppSettings, conversationId: String?): JSONObject {
    val serverConversationId = hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, conversationId)
    return JSONObject()
        .put("client", "hermes-hub")
        .put("hub_client", true)
        .put("client_surface", HERMES_HUB_ANDROID_SURFACE)
        .put("requested_protocol", settings.preferredApi)
        .put("strict_native_mode", settings.strictNativeMode)
        .put("profile", "user")
        .put("project_id", settings.activeProjectId)
        .put("project_name", settings.activeProjectName)
        .put("workspace", settings.activeProjectName.ifBlank { "default" })
        .put(
            "project_context",
            if (settings.activeProjectId.isBlank()) JSONObject.NULL else JSONObject()
                .put("id", settings.activeProjectId)
                .put("name", settings.activeProjectName)
                .put("system_prompt", settings.activeProjectInstructions)
        )
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
                .put("video", "Feed personale video: Hermes conosce video_library_path/HERMES_VIDEO_LIBRARY_PATH; ogni video creato/scaricato per l'utente deve essere salvato o registrato li; Android legge /v1/video/library, desktop mostra file locali, app salva feedback e metadata.")
                .put("news", "Feed personale articoli: Hermes produce articoli con fonti; se crea HTML/giornale online salva in ${settings.newsLibraryPath} per /v1/news/library; app salva feedback.")
                .put("cron", "Automazioni Hermes programmate condivise con CLI/server.")
                .put("wellbeing", "Riepiloghi giornalieri volontari da Health Connect/Galaxy Watch. Consulta GET /v1/hub/wellbeing o /v1/hub/wellbeing/daily/{date} solo se l'utente chiede salute, attivita', sonno o trend; dati wellness, non medici.")
                .put("notifications", "Inbox notifiche: cron/agenti devono usare POST /v1/hub/notifications per avvisi importanti quando l'app non e' aperta.")
        )
        .put(
            "notification_contract",
            JSONObject()
                .put("endpoint", "/v1/hub/notifications")
                .put("required_behavior", "When a cron, monitor or long-running agent finds something the user must know, create a notification with title, message, severity, source and conversation_prompt. Keep it concise and self-contained.")
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
                .put("required_behavior", "When the user asks for video creation/download/editing, store the final video file in video_library_path/HERMES_VIDEO_LIBRARY_PATH, let /v1/video/library expose it, and use media proxy if referenced in chat.")
        )
        .put(
            "visual_blocks",
            JSONObject()
                .put("min_supported_version", VISUAL_BLOCKS_VERSION)
                .put("max_supported_version", VISUAL_BLOCKS_VERSION)
                .put("mode", settings.visualBlocksMode)
                .put("image_gallery", "supported via /v1/media proxy URLs only")
                .put("media_file", "supported for image/video/audio/document via safe proxy URLs; include media_kind, mime_type, filename, size_bytes, duration_ms, thumbnail_url when known")
                .put("genui", "supported: interactive declarative UI v1 (type genui with id, genui_version 1, fallback_text, state, spec tree). See GenUI instructions in system prompt.")
                .put("screenshot_contract", "For browser screenshots: capture real image, copy to HERMES_HUB_UPLOAD_PATH (~/.hermes/hub_uploads by default), then emit media_file image with media_url /v1/media/<filename>. Never return a local path or URL as chat text.")
        )
}


internal suspend fun loadServerSnapshot(context: Context, settings: AppSettings, apiKey: String?): ServerSnapshot = withContext(Dispatchers.IO) {
    val healthUrl = "${hermesRoot(settings)}/health"
    val baseSnapshot = ServerSnapshot(
        gateway = settings.gatewayUrl,
        model = settings.model,
        providerDetail = "Provider: ${settings.provider} | API: ${settings.preferredApi}",
        inferenceEndpoint = settings.inferenceEndpoint,
        policy = settings.accessMode,
        statusMessage = if (settings.demoMode) "Fallback locale attivo. Provero' comunque a usare Hermes." else "Solo Hermes. Verifica lo stato del server.",
        videoLibraryPath = settings.videoLibraryPath
    )

    try {
        // /health e anonimo per disegno server: mai inviare la chiave
        // (solo rumore in log/proxy, zero benefici).
        val healthStatus = testGateway(healthUrl, null)
        try {
            val body = httpGet("${hermesRoot(settings)}/health/detailed", null)
            val json = JSONObject(body)
            val syncedVideoPath = json.extractString("video_library_path")
                ?: json.extractString("videoLibraryPath")
                ?: json.extractNestedString("video", "library_path")
                ?: json.extractNestedString("video", "video_library_path")
                ?: json.extractNestedString("config", "video_library_path")
                ?: settings.videoLibraryPath
            if (syncedVideoPath != settings.videoLibraryPath) {
                saveSettings(context, settings.copy(videoLibraryPath = syncedVideoPath))
            }
            ServerSnapshot(
                gateway = settings.gatewayUrl,
                model = json.extractString("model")
                    ?: json.extractNestedString("server", "model")
                    ?: json.extractNestedString("runtime", "model")
                    ?: settings.model,
                providerDetail = "Provider: ${json.extractString("provider") ?: json.extractNestedString("server", "provider") ?: settings.provider} | API: ${json.extractString("preferredApi") ?: json.extractString("api") ?: settings.preferredApi}",
                inferenceEndpoint = json.extractString("inferenceEndpoint")
                    ?: json.extractNestedString("server", "inferenceEndpoint")
                    ?: json.extractNestedString("runtime", "endpoint")
                    ?: settings.inferenceEndpoint,
                policy = json.extractString("accessMode")
                    ?: json.extractString("networkPolicy")
                    ?: json.extractNestedString("security", "networkPolicy")
                    ?: settings.accessMode,
                statusMessage = json.extractString("status")
                    ?: json.extractString("message")
                    ?: healthStatus,
                videoLibraryPath = syncedVideoPath
            )
        } catch (_: Exception) {
            baseSnapshot.copy(statusMessage = if (settings.demoMode) "$healthStatus Fallback locale attivo." else "$healthStatus Solo Hermes.")
        }
    } catch (ex: Exception) {
        baseSnapshot.copy(statusMessage = "Hermes non raggiungibile: ${ex.message ?: ex.javaClass.simpleName}")
    }
}

internal suspend fun loadHardwareSnapshot(settings: AppSettings, apiKey: String?): HardwareSnapshot = withContext(Dispatchers.IO) {
    try {
        val body = httpGet(resolveHermesUrl(settings, "/v1/hub/hardware"), apiKey, allowCompatAuth = false)
        parseHardwareSnapshot(JSONObject(body))
    } catch (ex: Exception) {
        HardwareSnapshot(
            status = "unavailable",
            message = "Hardware gateway non disponibile: ${ex.message ?: ex.javaClass.simpleName}"
        )
    }
}


internal fun extractVisualBlocks(body: String): List<VisualBlock> {
    val trimmed = body.trim()
    if (!trimmed.startsWith("{") || trimmed.toByteArray(Charsets.UTF_8).size > VISUAL_BLOCKS_MAX_PAYLOAD_BYTES * 3) {
        return emptyList()
    }

    return try {
        val root = JSONObject(trimmed)
        val version = root.optInt("visual_blocks_version", VISUAL_BLOCKS_VERSION)
        if (version < VISUAL_BLOCKS_VERSION) {
            return emptyList()
        }
        val array = findJsonArray(root, "visual_blocks") ?: return emptyList()
        if (array.toString().toByteArray(Charsets.UTF_8).size > VISUAL_BLOCKS_MAX_PAYLOAD_BYTES) {
            return emptyList()
        }
        buildList {
            for (i in 0 until minOf(array.length(), VISUAL_BLOCKS_MAX_BLOCKS)) {
                val obj = array.optJSONObject(i) ?: continue
                val block = readVisualBlock(obj)
                if (block.isValidVisualBlock()) {
                    add(block)
                } else {
                    add(toUnknownVisualBlock(obj))
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun toUnknownVisualBlock(obj: JSONObject): VisualBlock {
    val rawType = obj.optString("type", "unknown")
    return VisualBlock(
        id = obj.optString("id").ifBlank { "unknown-${System.nanoTime()}" },
        type = "unknown_block",
        title = "Blocco Hermes non renderizzato: $rawType",
        caption = "Payload conservato per compatibilita' forward.",
        rawJson = obj.toString()
    )
}

internal fun findJsonArray(value: Any?, key: String): JSONArray? {
    return when (value) {
        is JSONObject -> {
            value.optJSONArray(key) ?: value.keys().asSequence()
                .mapNotNull { findJsonArray(value.opt(it), key) }
                .firstOrNull()
        }
        is JSONArray -> {
            for (i in 0 until value.length()) {
                findJsonArray(value.opt(i), key)?.let { return it }
            }
            null
        }
        else -> null
    }
}

internal fun readVisualBlock(obj: JSONObject): VisualBlock {
    val type = obj.optString("type")
    val isMediaFile = type.equals("media_file", ignoreCase = true)
    val mediaUrl = if (isMediaFile) {
        firstNonBlank(
            obj.optString("media_url"),
            obj.optString("download_url"),
            obj.optString("downloadUrl"),
            obj.optString("url"),
            obj.optString("file_url"),
            obj.optString("fileUrl")
        ).trimEnd('.', ',', ';', ':')
    } else {
        obj.optString("media_url")
    }
    val filename = if (isMediaFile) {
        firstNonBlank(
            obj.optString("filename"),
            inferVisualBlockFilename(obj.optString("title", "File Hermes"), mediaUrl),
            "download"
        )
    } else {
        obj.optString("filename")
    }
    val inferredKind = inferVisualBlockMediaKind(filename, mediaUrl)
    var mediaKind = if (isMediaFile) normalizeVisualBlockMediaKind(obj.optString("media_kind"), inferredKind) else obj.optString("media_kind")
    if (isMediaFile && mediaKind == "document" && inferredKind != "document") {
        // L'agente dichiara document ma URL/mime dicono image/video/audio: fidati dell'evidenza.
        mediaKind = inferredKind
    }
    val mimeType = if (isMediaFile) firstNonBlank(obj.optString("mime_type"), inferVisualBlockMimeType(filename, mediaUrl)) else obj.optString("mime_type")
    val alt = if (isMediaFile) firstNonBlank(obj.optString("alt"), obj.optString("title"), filename, "File Hermes") else obj.optString("alt")

    return VisualBlock(
        id = obj.optString("id"),
        type = type,
        title = obj.optString("title"),
        caption = obj.optString("caption"),
        text = obj.optString("text"),
        language = obj.optString("language", "plaintext"),
        filename = filename,
        code = obj.optString("code"),
        highlightLines = readIntArray(obj.optJSONArray("highlight_lines")),
        columns = readVisualColumns(obj.optJSONArray("columns") ?: JSONArray()),
        rows = readVisualRows(obj.optJSONArray("rows") ?: JSONArray()),
        chartType = obj.optString("chart_type"),
        xLabel = obj.optString("x_label"),
        yLabel = obj.optString("y_label"),
        unit = obj.optString("unit"),
        summary = obj.optString("summary"),
        value = obj.opt("value")?.toString().orEmpty(),
        progressValue = obj.opt("progress").let { value ->
            when (value) {
                is Number -> value.toDouble()
                else -> null
            }
        },
        status = obj.optString("status"),
        action = obj.optString("action"),
        deviceName = obj.optString("device_name"),
        deviceKind = obj.optString("device_kind"),
        deviceStatus = obj.optString("device_status"),
        batteryPercent = obj.opt("battery_percent").let { value ->
            when (value) {
                is Number -> value.toDouble()
                else -> null
            }
        },
        series = readVisualSeries(obj.optJSONArray("series") ?: JSONArray()),
        sourceFormat = obj.optString("source_format"),
        source = obj.optString("source"),
        renderedMediaUrl = obj.optString("rendered_media_url"),
        mediaUrl = mediaUrl,
        mediaKind = mediaKind,
        mimeType = mimeType,
        sizeBytes = obj.optLongOrNull("size_bytes"),
        durationMs = obj.optLongOrNull("duration_ms"),
        thumbnailUrl = obj.optString("thumbnail_url"),
        localDataUrl = obj.optString("local_data_url"),
        alt = alt,
        layout = obj.optString("layout"),
        images = readVisualImages(obj.optJSONArray("images") ?: JSONArray()),
        variant = obj.optString("variant"),
        options = readVisualOptions(obj.optJSONArray("options") ?: JSONArray()),
        fields = readVisualFields(obj.optJSONArray("fields") ?: JSONArray()),
        multi = obj.optBoolean("multi", false),
        submitLabel = obj.optString("submit_label"),
        genuiVersion = obj.optInt("genui_version", 0),
        genuiFallback = obj.optString("fallback_text"),
        genuiSpec = obj.optJSONObject("spec")?.toString().orEmpty(),
        genuiStateSeed = obj.optJSONObject("state")?.toString().orEmpty(),
        rawJson = obj.optString("raw_json", obj.toString())
    )
}

internal fun readVisualColumns(array: JSONArray): List<VisualTableColumn> = buildList {
    for (i in 0 until minOf(array.length(), 12)) {
        val obj = array.optJSONObject(i) ?: continue
        add(
            VisualTableColumn(
                key = obj.optString("key"),
                label = obj.optString("label"),
                align = obj.optString("align", "left"),
                format = obj.optString("format", "text"),
                sortable = obj.optBoolean("sortable", false)
            )
        )
    }
}

internal fun readVisualRows(array: JSONArray): List<Map<String, String>> = buildList {
    for (i in 0 until minOf(array.length(), 100)) {
        val obj = array.optJSONObject(i) ?: continue
        add(obj.keys().asSequence().associateWith { key -> obj.opt(key)?.toString().orEmpty() })
    }
}

internal fun readVisualSeries(array: JSONArray): List<VisualChartSeries> = buildList {
    for (i in 0 until minOf(array.length(), 8)) {
        val obj = array.optJSONObject(i) ?: continue
        val points = obj.optJSONArray("points") ?: JSONArray()
        add(
            VisualChartSeries(
                name = obj.optString("name"),
                points = buildList {
                    for (pointIndex in 0 until minOf(points.length(), 200)) {
                        val point = points.optJSONObject(pointIndex) ?: continue
                        add(VisualChartPoint(point.opt("x")?.toString().orEmpty(), point.optDouble("y")))
                    }
                }
            )
        )
    }
}

internal fun readVisualImages(array: JSONArray): List<VisualGalleryImage> = buildList {
    for (i in 0 until minOf(array.length(), 12)) {
        val obj = array.optJSONObject(i) ?: continue
        add(VisualGalleryImage(obj.optString("media_url"), obj.optString("alt"), obj.optString("caption")))
    }
}

internal fun readVisualOptions(array: JSONArray): List<VisualChoiceOption> = buildList {
    for (i in 0 until minOf(array.length(), 10)) {
        val obj = array.optJSONObject(i) ?: continue
        add(VisualChoiceOption(obj.optString("id"), obj.optString("label"), obj.optString("detail")))
    }
}

internal val VISUAL_FORM_FIELD_KINDS = setOf("text", "multiline", "number", "date", "bool", "select")

internal fun readVisualFields(array: JSONArray): List<VisualFormField> = buildList {
    for (i in 0 until minOf(array.length(), 12)) {
        val obj = array.optJSONObject(i) ?: continue
        val opts = obj.optJSONArray("options")?.let { arr ->
            buildList {
                for (j in 0 until minOf(arr.length(), 10)) {
                    arr.optString(j).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }.orEmpty()
        add(
            VisualFormField(
                key = obj.optString("key"),
                label = obj.optString("label"),
                kind = obj.optString("kind", "text"),
                required = obj.optBoolean("required", false),
                options = opts,
                placeholder = obj.optString("placeholder")
            )
        )
    }
}

internal fun isValidVisualFormField(field: VisualFormField): Boolean {
    if (field.key.isBlank() || field.label.isBlank() || field.label.length > 80) return false
    if (field.kind !in VISUAL_FORM_FIELD_KINDS) return false
    if (field.kind == "select") {
        return field.options.isNotEmpty() && field.options.size <= 10 && field.options.all { it.isNotBlank() }
    }
    return true
}

internal fun readIntArray(array: JSONArray?): List<Int> = buildList {
    if (array == null) return@buildList
    for (i in 0 until minOf(array.length(), 80)) {
        add(array.optInt(i))
    }
}

internal fun VisualBlock.isValidVisualBlock(): Boolean {
    if (id.isBlank()) return false
    return when (type.lowercase()) {
        "markdown" -> text.isNotBlank()
        "code" -> code.isNotBlank() && language in ALLOWED_CODE_LANGUAGES
        "table" -> columns.isNotEmpty() && columns.size <= 12 && rows.size <= 100
        "chart" -> chartType in setOf("bar", "line") && summary.isNotBlank() && series.isNotEmpty() && series.size <= 8 && series.all { it.points.isNotEmpty() && it.points.size <= 200 }
        "diagram" -> sourceFormat == "mermaid" && source.isNotBlank() && alt.isNotBlank()
        "image_gallery" -> images.isNotEmpty() && images.size <= 12 && images.all { it.mediaUrl.isNotBlank() && it.alt.isNotBlank() }
        "media_file" -> mediaKind in setOf("image", "video", "audio", "document") &&
            (mediaUrl.isNotBlank() || isValidLocalAttachmentSource(localDataUrl)) && alt.isNotBlank()
        "callout" -> variant in setOf("info", "warning", "error", "success") && text.isNotBlank()
        "metric" -> value.isNotBlank()
        "progress" -> progressValue?.let { it in 0.0..1.0 } == true &&
            (status.isBlank() || status in setOf("pending", "active", "complete", "blocked", "failed"))
        "approval" -> status in setOf("pending", "approved", "rejected") && action.isNotBlank()
        "choice" -> status in setOf("pending", "answered") &&
            options.isNotEmpty() && options.size <= 10 &&
            options.all { it.id.isNotBlank() && it.label.isNotBlank() && it.label.length <= 120 }
        "form" -> status in setOf("pending", "submitted") &&
            fields.isNotEmpty() && fields.size <= 12 && fields.all { isValidVisualFormField(it) }
        "device" -> deviceName.isNotBlank() &&
            deviceStatus in setOf("connected", "connecting", "disconnected", "unknown") &&
            (batteryPercent == null || batteryPercent in 0.0..100.0)
        "genui" -> GenUiValidator.validateBlock(
            id, genuiVersion, genuiFallback, genuiStateSeed, genuiSpec
        ) != null
        "unknown_block" -> rawJson.isNotBlank()
        else -> false
    }
}

internal fun isSafeMediaUrl(value: String): Boolean {
    if (value.isBlank()) return false
    if (value.startsWith("/v1/media/", ignoreCase = true)) return true
    return try {
        val uri = URI(value)
        (uri.scheme == "http" || uri.scheme == "https") && uri.path.startsWith("/v1/media/")
    } catch (_: Exception) {
        false
    }
}

internal fun isValidLocalAttachmentSource(value: String): Boolean {
    if (value.startsWith("data:", ignoreCase = true)) return true
    return value.isNotBlank() && File(value).isFile
}

internal fun firstNonBlank(vararg values: String?): String {
    return values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
}

internal fun inferVisualBlockFilename(label: String, url: String): String {
    val candidate = label.takeIf { it.contains('.') } ?: url.substringAfterLast('/').substringAfterLast('\\')
    return candidate.substringBefore('?').substringBefore('#').take(180).ifBlank { "download" }
}

internal fun inferVisualBlockMediaKind(filename: String, url: String): String {
    val value = "$filename $url".lowercase()
    return when {
        listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp").any { value.contains(it) } -> "image"
        listOf(".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi", ".wmv", ".flv", ".mpg", ".mpeg", ".ts", ".m2ts", ".3gp", ".ogv").any { value.contains(it) } -> "video"
        listOf(".mp3", ".wav", ".m4a", ".flac", ".ogg").any { value.contains(it) } -> "audio"
        else -> "document"
    }
}

internal fun normalizeVisualBlockMediaKind(value: String?, inferred: String): String {
    return when (value.orEmpty().trim().lowercase().replace("_", "-")) {
        "image", "video", "audio", "document" -> value.orEmpty().trim().lowercase()
        "file", "attachment", "download", "binary" -> "document"
        else -> inferred.ifBlank { "document" }
    }
}

internal fun inferVisualBlockMimeType(filename: String, url: String): String {
    val value = "$filename $url".lowercase()
    return when {
        value.contains(".png") -> "image/png"
        value.contains(".jpg") || value.contains(".jpeg") -> "image/jpeg"
        value.contains(".webp") -> "image/webp"
        value.contains(".gif") -> "image/gif"
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
        value.contains(".pptx") -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        value.contains(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        value.contains(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        value.contains(".svg") -> "image/svg+xml"
        value.contains(".zip") -> "application/zip"
        value.contains(".md") || value.contains(".markdown") -> "text/markdown"
        value.contains(".txt") -> "text/plain"
        value.contains(".csv") -> "text/csv"
        value.contains(".json") -> "application/json"
        value.contains(".html") || value.contains(".htm") -> "text/html"
        else -> ""
    }
}

internal fun shouldAttachVisualBlocks(settings: AppSettings, prompt: String): Boolean {
    if (settings.visualBlocksMode == "never") return false
    if (settings.visualBlocksMode == "always") return true
    return prompt.contains("visual", ignoreCase = true) ||
        prompt.contains("immagine", ignoreCase = true) ||
        prompt.contains("immagini", ignoreCase = true) ||
        prompt.contains("image", ignoreCase = true) ||
        prompt.contains("foto", ignoreCase = true) ||
        prompt.contains("file", ignoreCase = true) ||
        prompt.contains("condivid", ignoreCase = true) ||
        prompt.contains("scaric", ignoreCase = true) ||
        prompt.contains("inviami", ignoreCase = true) ||
        prompt.contains("diagram", ignoreCase = true) ||
        prompt.contains("grafico", ignoreCase = true) ||
        prompt.contains("tabella", ignoreCase = true) ||
        prompt.contains("spiegazione visiva", ignoreCase = true)
}

internal fun visualBlockFixtures(): List<VisualBlock> {
    return listOf(
        VisualBlock(
            id = "fixture-markdown",
            type = "markdown",
            title = "Schema operativo",
            text = "## Hermes Visual Blocks\n- Testo fallback sempre completo\n- Blocchi tipizzati validati\n- Renderer statici sicuri"
        ),
        VisualBlock(
            id = "fixture-code",
            type = "code",
            title = "Esempio payload",
            language = "json",
            filename = "visual-response.json",
            code = "{\n  \"output_text\": \"Risposta completa.\",\n  \"visual_blocks_version\": 1\n}",
            highlightLines = listOf(2)
        ),
        VisualBlock(
            id = "fixture-table",
            type = "table",
            title = "Limiti v1",
            columns = listOf(VisualTableColumn("item", "Elemento"), VisualTableColumn("limit", "Limite", "right")),
            rows = listOf(
                mapOf("item" to "Blocchi", "limit" to "20"),
                mapOf("item" to "Payload", "limit" to "500 KB"),
                mapOf("item" to "Chart", "limit" to "8x200")
            )
        ),
        VisualBlock(
            id = "fixture-chart",
            type = "chart",
            title = "Esempio chart",
            chartType = "bar",
            xLabel = "Piattaforma",
            yLabel = "Copertura",
            unit = "%",
            summary = "Windows e Android usano lo stesso contratto Visual Blocks v1.",
            series = listOf(
                VisualChartSeries(
                    "Copertura",
                    listOf(VisualChartPoint("Windows", 100.0), VisualChartPoint("Android", 100.0), VisualChartPoint("Fallback", 100.0))
                )
            )
        ),
        VisualBlock(
            id = "fixture-diagram",
            type = "diagram",
            title = "Flusso",
            sourceFormat = "mermaid",
            source = "graph TD; User-->HermesHub; HermesHub-->HermesAgent; HermesAgent-->VisualBlocks;",
            alt = "Utente verso Hermes Hub, Hermes Agent e Visual Blocks"
        ),
        VisualBlock(
            id = "fixture-gallery",
            type = "image_gallery",
            title = "Media proxy",
            layout = "grid",
            images = listOf(VisualGalleryImage("/v1/media/example.webp", "Esempio asset da proxy Hermes", "Placeholder proxy"))
        ),
        VisualBlock(
            id = "fixture-media",
            type = "media_file",
            title = "File multimediale",
            mediaUrl = "/v1/media/video-demo.mp4",
            mediaKind = "video",
            mimeType = "video/mp4",
            filename = "video-demo.mp4",
            sizeBytes = 1_048_576,
            durationMs = 12_000,
            thumbnailUrl = "/v1/media/video-demo-thumb.webp",
            alt = "Anteprima video demo",
            caption = "Video condiviso dall'agente via proxy Hermes"
        ),
        VisualBlock(
            id = "fixture-callout",
            type = "callout",
            variant = "info",
            title = "Sicurezza",
            text = "Niente HTML, niente JS, niente SVG client-side."
        ),
        VisualBlock(
            id = "fixture-metric",
            type = "metric",
            title = "Copertura contratto",
            value = "100",
            unit = "%",
            status = "verified"
        ),
        VisualBlock(
            id = "fixture-progress",
            type = "progress",
            title = "Migrazione",
            progressValue = 0.75,
            status = "active",
            unit = "%",
            summary = "Tre quarti dei dati sono stati verificati."
        ),
        VisualBlock(
            id = "fixture-approval",
            type = "approval",
            title = "Conferma operazione",
            status = "pending",
            action = "Approvare la sincronizzazione",
            text = "L'operazione richiede conferma esplicita."
        ),
        VisualBlock(
            id = "fixture-device",
            type = "device",
            title = "Dispositivo DAT",
            deviceName = "Ray-Ban Meta",
            deviceKind = "wearable",
            deviceStatus = "connected",
            batteryPercent = 82.0,
            summary = "Camera connessa; audio sul telefono."
        )
    )
}

