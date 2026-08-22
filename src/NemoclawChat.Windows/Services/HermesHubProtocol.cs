using System.Net.Http;
using System.Text.Json;

namespace NemoclawChat_Windows.Services;

public sealed record HermesRequestContext(string RequestId, string CorrelationId);

public sealed record HermesEventEnvelope(
    int ProtocolVersion,
    string EventId,
    long Sequence,
    string RequestId,
    string CorrelationId,
    string Type,
    JsonElement Payload,
    string SourceType,
    string? RunId = null);

public sealed record HermesProtocolError(
    int ProtocolVersion,
    string Code,
    string Message,
    string RequestId,
    string CorrelationId,
    bool Retryable,
    string? RunId = null);

public static class HermesHubProtocol
{
    public const int ProtocolVersion = 1;
    public const string RequestIdHeader = "X-Hermes-Request-Id";
    public const string CorrelationIdHeader = "X-Hermes-Correlation-Id";
    public const string CompatibilityRequestIdHeader = "X-Request-Id";
    public const string WindowsSurface = "windows-app";

    public static HermesRequestContext NewRequestContext()
    {
        var token = Guid.NewGuid().ToString("N");
        return new HermesRequestContext($"req_{token}", $"corr_{token}");
    }

    public static void AddCorrelationHeaders(HttpRequestMessage request, HermesRequestContext? context = null)
    {
        var ids = context ?? NewRequestContext();
        request.Headers.TryAddWithoutValidation(RequestIdHeader, ids.RequestId);
        request.Headers.TryAddWithoutValidation(CorrelationIdHeader, ids.CorrelationId);
        request.Headers.TryAddWithoutValidation(CompatibilityRequestIdHeader, ids.RequestId);
    }

    public static bool TryReadEventEnvelope(
        JsonElement element,
        string? eventName,
        out HermesEventEnvelope envelope)
    {
        envelope = default!;
        if (element.ValueKind != JsonValueKind.Object ||
            !element.TryGetProperty("protocol_version", out var version) ||
            version.ValueKind != JsonValueKind.Number ||
            !version.TryGetInt32(out var protocolVersion) ||
            protocolVersion != ProtocolVersion ||
            !element.TryGetProperty("payload", out var payload) ||
            !element.TryGetProperty("event_id", out var eventId) ||
            !element.TryGetProperty("sequence", out var sequence) ||
            !element.TryGetProperty("request_id", out var requestId) ||
            !element.TryGetProperty("correlation_id", out var correlationId) ||
            !element.TryGetProperty("type", out var type) ||
            !element.TryGetProperty("source_type", out var sourceType))
        {
            return false;
        }

        if (eventId.ValueKind != JsonValueKind.String ||
            !long.TryParse(sequence.GetRawText(), out var sequenceValue) ||
            sequenceValue < 0 ||
            requestId.ValueKind != JsonValueKind.String ||
            correlationId.ValueKind != JsonValueKind.String ||
            type.ValueKind != JsonValueKind.String ||
            sourceType.ValueKind != JsonValueKind.String)
        {
            return false;
        }

        var requestIdValue = requestId.GetString();
        var correlationIdValue = correlationId.GetString();
        var eventIdValue = eventId.GetString();
        var typeValue = type.GetString();
        var sourceValue = sourceType.GetString();
        if (!IsWireId(requestIdValue) ||
            !IsWireId(correlationIdValue) ||
            !IsWireId(eventIdValue) ||
            string.IsNullOrWhiteSpace(typeValue) ||
            typeValue.Length > 160 ||
            sourceValue is not ("hermes-agent" or "hermes-gateway" or "hermes-hub" or "unknown"))
        {
            return false;
        }

        var runId = element.TryGetProperty("run_id", out var run) && run.ValueKind == JsonValueKind.String
            ? run.GetString()
            : null;
        envelope = new HermesEventEnvelope(
            protocolVersion,
            eventIdValue!,
            sequenceValue,
            requestIdValue!,
            correlationIdValue!,
            typeValue,
            payload.Clone(),
            sourceValue,
            runId);
        return true;
    }

    private static bool IsWireId(string? value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Length > 128)
        {
            return false;
        }

        for (var i = 0; i < value.Length; i++)
        {
            var ch = value[i];
            var valid = char.IsLetterOrDigit(ch) || ch is '.' or '_' or ':' or '-';
            if (!valid || i == 0 && !char.IsLetterOrDigit(ch))
            {
                return false;
            }
        }

        return true;
    }

    public static string Instructions(string mode)
    {
        return Instructions(new AppSettings(), mode);
    }

    public static string Instructions(AppSettings settings, string mode)
    {
        if (IsNativePreferred(settings))
        {
            return NativeInstructions(mode) + ProjectContextInstructions(settings);
        }

        var shared = """
            Stai ricevendo messaggi da Hermes Hub, client operativo mobile/desktop di Hermes Agent.
            Hermes Hub non e' un modello separato: deve usare la stessa memoria agente, gli stessi jobs e lo stesso profilo operativo disponibili anche da CLI Hermes.
            Sezioni app:
            - Chat: conversazione principale.
            - Video: feed personale di video generati su PC/Hermes. Esiste una Video Library ufficiale annunciata dal gateway in video_library_path e interrogabile da Android con /v1/video/library. Se l'utente chiede di creare, scaricare, montare o preparare un video, salva/registra il file finale in quella cartella, cosi la sezione Video lo vede. Il telefono riceve media proxy /v1/media/..., non file locali diretti.
            - News: feed personale di articoli/briefing con fonti e feedback utente. Se l'utente chiede un giornale online o una pagina HTML, salva il file finale in news_library_path/HERMES_NEWS_LIBRARY_PATH, cosi Hermes Hub lo apre nella WebView interna tramite /v1/news/library e /v1/media/....
            - Cron: automazioni Hermes programmate sul gateway.
            - Notifiche: inbox persistente per messaggi autonomi da cron/agenti. Quando un cron deve avvisare l'utente, pubblica un item con POST /v1/hub/notifications includendo title, message, severity, source e conversation_prompt.
            - Archivio: storico locale dell'app, non memoria agente principale.
            Video Library: non ignorare la sezione Video. Ogni output video finale destinato all'utente deve finire in video_library_path/HERMES_VIDEO_LIBRARY_PATH; ogni file video comune (.mp4/.m4v/.mov/.mkv/.webm/.avi/.wmv/.flv/.mpg/.mpeg/.ts/.m2ts/.3gp/.ogv) in quella cartella appare tramite /v1/video/library. Se lo mostri in chat, usa anche visual_blocks media_file con media_url proxy /v1/media/...; il gateway puo' esporre playback compat MP4 con ?format=mp4.
            File multimediali in chat: usa visual_blocks image_gallery per piu' immagini o media_file per singoli asset image/video/audio/document. Quando l'utente chiede "condividimi/inviami/scaricami un file", la risposta deve includere una card media_file scaricabile stile chat, non solo path o URL nel testo. media_url e thumbnail_url devono puntare a proxy Hermes/same-host tipo /v1/media/...; vietati file://, data: e path locali diretti.
            Non scrivere mai markdown MEDIA:[path](file://...) o path Windows/Linux nel testo finale. Se un tool produce un file locale, pubblicalo prima tramite proxy Hermes e restituisci solo /v1/media/... dentro visual_blocks. Se non puoi pubblicarlo, dillo esplicitamente invece di inviare path locali.
            Screenshot browser: quando l'utente chiede uno screen o una foto di cio' che stai facendo, cattura davvero lo screenshot, copialo prima in HERMES_HUB_UPLOAD_PATH (default ~/.hermes/hub_uploads), poi rispondi con un visual_blocks media_file di tipo image e media_url /v1/media/<nome-file>. La chat deve mostrare immagine dentro canvas; risposta testuale puo' descrivere contenuto ma non deve contenere path o URL. Non dichiarare screen inviato senza una card immagine valida.
            Durante lavori agente lunghi, inoltra eventi realtime per reasoning, tool call, argomenti tool, risultati tool e chiamate modello intermedie quando il gateway li supporta: Hermes Hub deve mostrare all'utente cosa stai facendo.
            """;

        shared += ProjectContextInstructions(settings);

        if (mode.Equals("Agente", StringComparison.OrdinalIgnoreCase))
        {
            return shared + """

                Agisci come Hermes Agent operativo. Usa strumenti, memoria, jobs e filesystem disponibili lato server e conserva un riepilogo chiaro delle azioni.
                Memoria: app, CLI, jobs, Video e News devono contribuire alla stessa memoria agente/profilo utente quando l'informazione e' stabile o utile in futuro. Se esiste un tool di memoria, usalo. Se non esiste, conserva la preferenza nel riepilogo operativo e nel job/artifact server.
                Se l'utente chiede un video, articolo, cron, briefing o contenuto ricorrente, crea/aggiorna job o artifact lato Hermes con metadata workspace=video/news, cosi Hermes Hub puo' mostrarlo nella sezione corretta.
                Quando crei un output destinato a Video o News, produci anche un oggetto JSON compatto con: kind, title, summary, status, job_id, stream_url, download_url, sources.
                """;
        }

        return shared + """

            Rispondi come assistente conversazionale Hermes.
            Se l'utente esprime una preferenza stabile, un gusto editoriale, una regola di lavoro o una decisione di progetto, trattala come memoria agente condivisa e persistente usando gli strumenti/memoria disponibili lato Hermes. Non considerare la chat dell'app una memoria separata.
            Se l'utente chiede contenuti destinati a Video o News, dichiara chiaramente destinazione, titolo, stato job/artifact e prossimi passi.
            """;
    }

    public static string? ServerConversationId(string? conversationId, string surface = WindowsSurface)
    {
        if (string.IsNullOrWhiteSpace(conversationId))
        {
            return null;
        }

        var localId = conversationId.Trim();
        if (localId.StartsWith("hermes-hub:", StringComparison.OrdinalIgnoreCase))
        {
            return localId;
        }

        static string Clean(string value)
        {
            var chars = value.Select(ch => char.IsLetterOrDigit(ch) || ch is '_' or '-' or '.' or ':' ? ch : '-').ToArray();
            return new string(chars).Trim('-');
        }

        var safeSurface = Clean(surface.ToLowerInvariant());
        var safeLocal = Clean(localId);
        return string.IsNullOrWhiteSpace(safeLocal) ? null : $"hermes-hub:{safeSurface}:{safeLocal}";
    }

    public static object Metadata(
        AppSettings settings,
        string? workspace = null,
        string? source = null,
        string? conversationId = null,
        string? profile = null,
        string? canonicalSessionId = null)
    {
        var serverConversationId = string.IsNullOrWhiteSpace(canonicalSessionId)
            ? ServerConversationId(conversationId)
            : canonicalSessionId.Trim();
        var project = ResolveActiveProject(settings);
        return new
        {
            client = "hermes-hub",
            client_surface = WindowsSurface,
            hub_client = true,
            requested_protocol = settings.PreferredApi,
            strict_native_mode = settings.StrictNativeMode,
            profile = string.IsNullOrWhiteSpace(profile) ? "user" : profile.Trim(),
            bot_profile = string.IsNullOrWhiteSpace(profile) ? null : profile.Trim(),
            bot_session_id = string.IsNullOrWhiteSpace(canonicalSessionId) ? null : canonicalSessionId.Trim(),
            project_id = settings.ActiveProjectId,
            project_name = settings.ActiveProjectName,
            workspace = workspace ?? (string.IsNullOrWhiteSpace(settings.ActiveProjectName) ? "default" : settings.ActiveProjectName),
            project_context = project is null ? null : new
            {
                id = project.Id,
                name = project.Title,
                system_prompt = project.ProjectInstructions
            },
            source,
            hub_conversation = new
            {
                id = serverConversationId,
                local_id = string.IsNullOrWhiteSpace(conversationId) ? null : conversationId.Trim(),
                surface = WindowsSurface,
                scope = "per-chat-per-surface",
                isolation_required = true,
                do_not_merge_with_other_conversations = true,
                do_not_merge_with_other_surfaces = true,
                shared_memory_policy = "Only stable user preferences may be shared; transient chat context must stay in this conversation id."
            },
            memory_policy = new
            {
                scope = "shared-hermes-agent-memory",
                share_with_cli = true,
                use_server_memory_tools = true,
                do_not_create_app_only_memory = true,
                runtime_context_scope = "isolated_conversation",
                do_not_use_other_active_chats_as_context = true,
                context_owner = IsNativePreferred(settings) ? "hermes-agent" : "client-compat"
            },
            native_context = new
            {
                delegated = IsNativePreferred(settings),
                conversation_id_required = true,
                client_history_is_snapshot_only = IsNativePreferred(settings),
                client_context_meter = IsNativePreferred(settings) ? "server-authoritative" : "local-estimate"
            },
            hub_sections = new
            {
                chat = "Conversazione principale Hermes Hub.",
                video = "Feed personale video: Hermes conosce video_library_path/HERMES_VIDEO_LIBRARY_PATH; ogni video creato/scaricato per l'utente deve essere salvato o registrato li; Android legge /v1/video/library, desktop mostra file locali, app salva feedback e metadata.",
                news = $"Feed personale articoli: Hermes produce articoli con fonti; se crea HTML/giornale online salva il file in {settings.NewsLibraryPath} per /v1/news/library; app salva feedback.",
                cron = "Automazioni Hermes programmate condivise con CLI/server.",
                notifications = "Inbox notifiche: cron/agenti devono usare POST /v1/hub/notifications per avvisi importanti quando l'app non e' aperta."
            },
            notification_contract = new
            {
                endpoint = "/v1/hub/notifications",
                required_behavior = "When a cron, monitor or long-running agent finds something the user must know, create a notification with title, message, severity, source and conversation_prompt. Keep it concise and self-contained."
            },
            news_library_path = settings.NewsLibraryPath,
            news_contract = new
            {
                mode = "watched-folder",
                library_path = settings.NewsLibraryPath,
                required_behavior = "When the user asks for news, articles, briefings, online newspapers or HTML pages, store the final HTML file in news_library_path/HERMES_NEWS_LIBRARY_PATH, let /v1/news/library expose it, and use media proxy if referenced in chat."
            },
            video_ingest = new
            {
                mode = "watched-folder",
                folder_path = settings.VideoLibraryPath,
                behavior = "Ogni file video messo in cartella deve apparire nel feed Video tramite /v1/video/library.",
                required_behavior = "When the user asks for video creation/download/editing, store the final video file in video_library_path/HERMES_VIDEO_LIBRARY_PATH, let /v1/video/library expose it, and use media proxy if referenced in chat."
            },
            activity_stream = new
            {
                requested = true,
                include_reasoning = true,
                include_tool_calls = true,
                include_tool_results = true,
                include_intermediate_model_calls = true,
                client_requires_realtime_visibility = true
            },
            visual_blocks = new
            {
                min_supported_version = VisualBlocksContract.Version,
                max_supported_version = VisualBlocksContract.Version,
                mode = settings.VisualBlocksMode,
                image_gallery = "supported via /v1/media proxy URLs only",
                media_file = "supported for image/video/audio/document via safe proxy URLs; include media_kind, mime_type, filename, size_bytes, duration_ms, thumbnail_url when known",
                screenshot_contract = "For browser screenshots: capture real image, copy to HERMES_HUB_UPLOAD_PATH (~/.hermes/hub_uploads by default), then emit media_file image with media_url /v1/media/<filename>. Never return a local path or URL as chat text."
            }
        };
    }

    public static bool IsNativePreferred(AppSettings settings)
    {
        return string.Equals(settings.PreferredApi, "hermes-native", StringComparison.OrdinalIgnoreCase);
    }

    public static string ProfileScopedUri(
        AppSettings settings,
        string path,
        string profile,
        bool multiplexEnabled)
    {
        if (!multiplexEnabled)
        {
            throw new InvalidOperationException("Il multiplexing dei profili Hermes non è pronto.");
        }

        var name = NormalizeProfileName(profile);

        var root = settings.GatewayUrl.TrimEnd('/');
        if (root.EndsWith("/v1", StringComparison.OrdinalIgnoreCase))
        {
            root = root[..^3];
        }

        var normalized = path.StartsWith('/') ? path : $"/{path}";
        var suffix = normalized.StartsWith("/v1", StringComparison.OrdinalIgnoreCase)
            ? normalized[3..]
            : normalized;
        return $"{root}/p/{Uri.EscapeDataString(name)}/v1{suffix}";
    }

    public static string ProfileScopedApiUri(
        AppSettings settings,
        string path,
        string profile,
        bool multiplexEnabled)
    {
        if (!multiplexEnabled)
        {
            throw new InvalidOperationException("Il multiplexing dei profili Hermes non è pronto.");
        }

        var name = NormalizeProfileName(profile);
        var root = settings.GatewayUrl.TrimEnd('/');
        if (root.EndsWith("/v1", StringComparison.OrdinalIgnoreCase))
        {
            root = root[..^3];
        }

        var normalized = path.StartsWith('/') ? path : $"/{path}";
        return $"{root}/p/{Uri.EscapeDataString(name)}{normalized}";
    }

    public static string NormalizeProfileName(string? profile)
    {
        var name = (profile ?? string.Empty).Trim().ToLowerInvariant();
        if (name.Length is < 1 or > 64 || name.Any(ch => !(
            (ch >= 'a' && ch <= 'z') ||
            (ch >= '0' && ch <= '9') ||
            ch is '_' or '-')))
        {
            throw new ArgumentException("Nome profilo non valido.", nameof(profile));
        }

        return name;
    }

    public static string NativeInstructions(string mode)
    {
        return """
            Hermes Hub media contract: never answer with a local filesystem path, file:// URL, or bracketed media address. For each file requested by the user return a visual_blocks media_file card using /v1/media/...; use image_gallery for multiple images. For a browser screenshot, capture it, copy it to HERMES_HUB_UPLOAD_PATH (default ~/.hermes/hub_uploads), and return a media_file image card with media_url /v1/media/<filename>. Do not claim a screenshot was shared unless that image card is present.
            """;
    }

    private static ConversationRecord? ResolveActiveProject(AppSettings settings)
    {
        if (string.IsNullOrWhiteSpace(settings.ActiveProjectId))
        {
            return null;
        }

        var project = ChatArchiveStore.Find(settings.ActiveProjectId);
        return project?.Kind.Equals("Progetto", StringComparison.OrdinalIgnoreCase) == true ? project : null;
    }

    private static string ProjectContextInstructions(AppSettings settings)
    {
        var project = ResolveActiveProject(settings);
        if (project is null)
        {
            return string.Empty;
        }

        return $"""

            Contesto progetto attivo Hermes Hub:
            - ID: {project.Id}
            - Nome: {project.Title}
            - System prompt personalizzato: {project.ProjectInstructions}
            Applica automaticamente il system prompt a tutte le chat del progetto.
            """;
    }
}
