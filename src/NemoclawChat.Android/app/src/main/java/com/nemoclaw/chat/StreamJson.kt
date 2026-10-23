package com.nemoclaw.chat

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

internal fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return try { getInt(key) } catch (_: Exception) { null }
}

internal fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return try { getDouble(key) } catch (_: Exception) { null }
}

internal fun JSONObject.optProgressPercent(): Int? {
    optIntOrNull("percent")?.let { return it.coerceIn(0, 100) }
    val progress = optDoubleOrNull("progress") ?: return null
    val value = if (progress <= 1.0) progress * 100.0 else progress
    return value.roundToInt().coerceIn(0, 100)
}

internal fun JSONObject.promptProgressEventOrNull(): ChatStreamEvent.PromptProgress? {
    val estimated = optBoolean("estimated", false)
    val progress = optJSONObject("prompt_progress") ?: return null
    val total = progress.optIntOrNull("total")?.takeIf { it > 0 }
    val processed = progress.optIntOrNull("processed")
    val cache = progress.optIntOrNull("cache")
    val timeMs = progress.optDoubleOrNull("time_ms")
    // Stimato senza totale: si mostra comunque (marcato ~), non si scarta.
    if (!estimated && total == null) return null
    if (estimated && total == null && processed == null) return null
    val percent = if (total != null && processed != null) {
        ((processed.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    } else {
        optProgressPercent() ?: 0
    }
    return ChatStreamEvent.PromptProgress(
        percent = percent,
        label = "Elaborazione prompt",
        estimated = estimated,
        processedTokens = processed,
        totalTokens = total,
        cachedTokens = cache,
        timeMs = timeMs
    )
}

internal fun JSONObject.tokensPerSecondOrNull(): Double? {
    val direct = optDoubleOrNull("tokens_per_second")
        ?: optDoubleOrNull("predicted_per_second")
        ?: optDoubleOrNull("generation_tokens_per_second")
    if (direct != null) return validateTokensPerSecond(direct)
    val timings = optJSONObject("timings") ?: return null
    return validateTokensPerSecond(
        timings.optDoubleOrNull("predicted_per_second")
            ?: timings.optDoubleOrNull("tokens_per_second")
    )
}

internal data class DraftAcceptanceData(val rate: Double, val label: String?)

/**
 * Acceptance rate speculative decoding da `timings` llama.cpp
 * (`draft_n` / `draft_n_accepted`, PR ggml-org/llama.cpp#12603).
 * Null quando lo speculative decoding non è attivo: niente stime client-side.
 */
internal fun JSONObject.draftAcceptanceOrNull(): DraftAcceptanceData? {
    val timings = optJSONObject("timings") ?: this
    if (!timings.has("draft_n") && !timings.has("draft_n_accepted")) return null
    val total = timings.optIntOrNull("draft_n") ?: return null
    val accepted = timings.optIntOrNull("draft_n_accepted") ?: return null
    val rate = validateDraftAcceptance(accepted, total) ?: return null
    val label = normalizeSpecTypeLabel(timings.optString("spec_type", "").takeIf { it.isNotBlank() }
        ?: timings.optString("draft_spec_type", "").takeIf { it.isNotBlank() })
    return DraftAcceptanceData(rate, label)
}

internal fun visualBlocksMetadataJson(
    settings: AppSettings,
    conversationId: String?,
    botProfile: String? = null,
    botSessionId: String? = null
): JSONObject {
    val serverConversationId = botSessionId?.trim()?.takeIf { it.isNotBlank() }
        ?: hermesHubServerConversationId(HERMES_HUB_ANDROID_SURFACE, conversationId)
    return JSONObject()
        .put("client", "hermes-hub")
        .put("client_surface", HERMES_HUB_ANDROID_SURFACE)
        .put("hub_client", true)
        .put("requested_protocol", settings.preferredApi)
        .put("strict_native_mode", settings.strictNativeMode)
        .put("profile", botProfile?.takeIf { it.isNotBlank() } ?: "user")
        .put("bot_profile", botProfile?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        .put("bot_session_id", botSessionId?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        .put("project_id", settings.activeProjectId)
        .put("project_name", settings.activeProjectName)
        .put("workspace", settings.activeProjectName.ifBlank { "default" })
        .put(
            "project_context",
            if (settings.activeProjectId.isBlank()) JSONObject.NULL else JSONObject()
                .put("id", settings.activeProjectId)
                .put("name", settings.activeProjectName)
                .put("workspace_path", settings.activeProjectWorkspacePath)
                .put("repository_url", settings.activeProjectRepositoryUrl)
                .put("instructions", settings.activeProjectInstructions)
                .put("memory", settings.activeProjectMemory)
                .put("authorized_tools", JSONArray(settings.activeProjectTools.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()))
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
                .put("video", "Feed personale video: Hermes conosce video_library_path/HERMES_VIDEO_LIBRARY_PATH; ogni video creato/scaricato per l'utente deve essere salvato o registrato li, desktop mostra file locali, app salva feedback e metadata.")
                .put("news", "Feed personale articoli: Hermes produce articoli con fonti; se crea HTML/giornale online salva in ${settings.newsLibraryPath} per /v1/news/library; app salva feedback.")
                .put("notifications", "Inbox notifiche: cron/agenti devono usare POST /v1/hub/notifications per avvisi importanti quando l'app non e' aperta.")
                .put("jobs", "Coda Hermes Jobs condivisa con CLI/server.")
                .put("runs", "Runs operative Hermes.")
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
                .put("genui", "supported: interactive declarative UI v1 (type genui with id, genui_version 1, fallback_text, state, spec tree). See GenUI instructions in system prompt.")
        )
}

