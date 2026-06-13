namespace NemoclawChat_Windows.Services;

public static class HermesHubProtocol
{
    public static string Instructions(string mode)
    {
        return Instructions(new AppSettings(), mode);
    }

    public static string Instructions(AppSettings settings, string mode)
    {
        if (IsNativePreferred(settings))
        {
            return NativeInstructions(mode);
        }

        var shared = """
            Stai ricevendo messaggi da Hermes Hub, client operativo mobile/desktop di Hermes Agent.
            Hermes Hub non e' un modello separato: deve usare la stessa memoria agente, gli stessi jobs e lo stesso profilo operativo disponibili anche da CLI Hermes.
            Sezioni app:
            - Chat: conversazione principale.
            - Video: feed personale di video generati su PC/Hermes. Esiste una Video Library ufficiale annunciata dal gateway in video_library_path e interrogabile da Android con /v1/video/library. Se l'utente chiede di creare, scaricare, montare o preparare un video, salva/registra il file finale in quella cartella, cosi la sezione Video lo vede. Il telefono riceve media proxy /v1/media/..., non file locali diretti.
            - News: feed personale di articoli/briefing con fonti e feedback utente.
            - Jobs/Runs: coda operativa Hermes e lavori programmati.
            - Archivio: storico locale dell'app, non memoria agente principale.
            Video Library: non ignorare la sezione Video. Ogni output video finale destinato all'utente deve finire in video_library_path/HERMES_VIDEO_LIBRARY_PATH; ogni file .mp4/.m4v/.mov/.mkv/.webm/.avi in quella cartella appare tramite /v1/video/library. Se lo mostri in chat, usa anche visual_blocks media_file con media_url proxy /v1/media/...
            File multimediali in chat: usa visual_blocks image_gallery per piu' immagini o media_file per singoli asset image/video/audio/document. media_url e thumbnail_url devono puntare a proxy Hermes/same-host tipo /v1/media/...; vietati file://, data: e path locali diretti.
            Non scrivere mai markdown MEDIA:[path](file://...) o path Windows/Linux nel testo finale. Se un tool produce un file locale, pubblicalo prima tramite proxy Hermes e restituisci solo /v1/media/... dentro visual_blocks. Se non puoi pubblicarlo, dillo esplicitamente invece di inviare path locali.
            Durante lavori agente lunghi, inoltra eventi realtime per reasoning, tool call, argomenti tool, risultati tool e chiamate modello intermedie quando il gateway li supporta: Hermes Hub deve mostrare all'utente cosa stai facendo.
            """;

        if (mode.Equals("Agente", StringComparison.OrdinalIgnoreCase))
        {
            return shared + """

                Agisci come Hermes Agent operativo. Usa strumenti, memoria, jobs e filesystem disponibili lato server e conserva un riepilogo chiaro delle azioni.
                Memoria: app, CLI, jobs, Video e News devono contribuire alla stessa memoria agente/profilo Matteo quando l'informazione e' stabile o utile in futuro. Se esiste un tool di memoria, usalo. Se non esiste, conserva la preferenza nel riepilogo operativo e nel job/artifact server.
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

    public static object Metadata(AppSettings settings, string? workspace = null, string? source = null)
    {
        return new
        {
            client = "hermes-hub",
            client_surface = "windows-app",
            requested_protocol = settings.PreferredApi,
            strict_native_mode = settings.StrictNativeMode,
            profile = "Matteo",
            project_id = settings.ActiveProjectId,
            project_name = settings.ActiveProjectName,
            workspace = workspace ?? (string.IsNullOrWhiteSpace(settings.ActiveProjectName) ? "default" : settings.ActiveProjectName),
            source,
            memory_policy = new
            {
                scope = "shared-hermes-agent-memory",
                share_with_cli = true,
                use_server_memory_tools = true,
                do_not_create_app_only_memory = true,
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
                video = "Feed personale video: Hermes conosce video_library_path/HERMES_VIDEO_LIBRARY_PATH; ogni video creato/scaricato per Matteo deve essere salvato o registrato li; Android legge /v1/video/library, desktop mostra file locali, app salva feedback e metadata.",
                news = "Feed personale articoli: Hermes produce articoli con fonti, app salva feedback.",
                jobs = "Coda Hermes Jobs condivisa con CLI/server.",
                runs = "Runs operative Hermes."
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
                media_file = "supported for image/video/audio/document via safe proxy URLs; include media_kind, mime_type, filename, size_bytes, duration_ms, thumbnail_url when known"
            }
        };
    }

    public static bool IsNativePreferred(AppSettings settings)
    {
        return string.Equals(settings.PreferredApi, "hermes-native", StringComparison.OrdinalIgnoreCase);
    }

    public static string NativeInstructions(string mode)
    {
        var role = mode.Equals("Agente", StringComparison.OrdinalIgnoreCase)
            ? "agent"
            : "chat";
        return $"""
            Hermes Hub client surface: windows-app.
            Protocol mode: hermes-native/{role}.
            Reply in the user's language. If the user writes Italian, answer in Italian.
            Use Hermes Agent server-side memory, planner, tools, jobs, artifacts and policy as source of truth.
            Client history is UI snapshot only; recover conversation context from Hermes conversation/response ids when available.
            Emit realtime Hermes events for planner, memory, retrieval, tool, artifact and model-call state when supported.
            Return user-facing answer plus structured artifacts/media through Hermes-declared capabilities.
            """;
    }
}
