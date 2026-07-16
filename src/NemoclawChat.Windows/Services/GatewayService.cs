using System.Net.Http;
using System.Text;
using System.Text.Json;

namespace NemoclawChat_Windows.Services;

public sealed record GatewayChatResult(
    string Message,
    string Source,
    string StatusMessage,
    bool UsedFallback,
    string? ResponseId = null,
    IReadOnlyList<VisualBlockRecord>? VisualBlocks = null,
    int? VisualBlocksVersion = null);

public sealed record ServerSnapshot(
    string Gateway,
    string Model,
    string ProviderDetail,
    string InferenceEndpoint,
    string Policy,
    string StatusMessage,
    string VideoLibraryPath);

public enum TaskCommand
{
    Approve,
    Deny,
    Complete
}

public sealed record GatewayTaskResult(AgentTaskRecord Task, string Message);

public sealed record WorkspaceRunResult(string Result, string Source, string Status);

public sealed record NewsHtmlRecord(
    string Id,
    string Title,
    string FileName,
    string Url,
    string Path,
    string MimeType,
    long SizeBytes,
    DateTimeOffset ModifiedAt);

public sealed record NewsLibraryResult(
    IReadOnlyList<NewsHtmlRecord> Items,
    string Status);

public sealed record HubMemoryState(
    string VideoPreferences,
    string NewsPreferences,
    string ResponseStyle,
    string ProjectRules,
    string GeneralNotes);

public sealed record DiagnosticCheckResult(string Label, string Endpoint, bool Ok, string Message, string Action);

public sealed record CronJobRecord(
    string Id,
    string Name,
    string Prompt,
    string Schedule,
    string State,
    bool Enabled,
    string NextRunAt,
    string LastRunAt,
    string LastStatus,
    string Deliver,
    string Origin);

public sealed record CronJobsResult(
    IReadOnlyList<CronJobRecord> Jobs,
    string Status);

public sealed record HubNotificationRecord(
    string Id,
    string Title,
    string Message,
    string Kind,
    string Severity,
    string Source,
    string ConversationPrompt,
    DateTimeOffset CreatedAt,
    DateTimeOffset? ReadAt,
    string Category = "Generale",
    string Priority = "Normale",
    bool Archived = false,
    DateTimeOffset? SnoozedUntil = null,
    string AutomationId = "",
    string RunId = "",
    string FileUrl = "",
    string ProjectId = "");

public sealed record HubNotificationsResult(
    IReadOnlyList<HubNotificationRecord> Items,
    string Status);

public sealed record HubConversationsResult(
    IReadOnlyList<ConversationRecord> Items,
    string Status,
    bool Success = false);

public sealed record HubOperationResult(bool Success, string Status);

public sealed record HardwareDiskRecord(
    string Device,
    string Mountpoint,
    string FileSystem,
    long TotalBytes,
    long UsedBytes,
    long FreeBytes,
    double Percent);

public sealed record HardwareTemperatureRecord(
    string Name,
    string Label,
    double CurrentC,
    double? HighC,
    double? CriticalC);

public sealed record HardwareGpuRecord(
    int Index,
    string Name,
    double UtilizationPercent,
    double MemoryUtilizationPercent,
    long MemoryUsedBytes,
    long MemoryTotalBytes,
    double? TemperatureC,
    double? PowerDrawWatts,
    double? PowerLimitWatts,
    string DriverVersion);

public sealed record HardwareSnapshot(
    string Status,
    DateTimeOffset Timestamp,
    string Hostname,
    string OperatingSystem,
    string Platform,
    string Architecture,
    string Processor,
    long UptimeSeconds,
    double CpuPercent,
    int PhysicalCores,
    int LogicalCores,
    double? CurrentMhz,
    double? MaxMhz,
    double MemoryPercent,
    long MemoryTotalBytes,
    long MemoryUsedBytes,
    long MemoryAvailableBytes,
    double SwapPercent,
    long SwapTotalBytes,
    long SwapUsedBytes,
    long NetworkBytesSent,
    long NetworkBytesReceived,
    int ProcessCount,
    string TemperatureSupport,
    IReadOnlyList<HardwareDiskRecord> Disks,
    IReadOnlyList<HardwareTemperatureRecord> Temperatures,
    IReadOnlyList<HardwareGpuRecord> Gpus,
    string Message);

public static class GatewayService
{
    private const int MaxBufferedResponseBytes = 10 * 1024 * 1024;
    private const int MaxErrorResponseBytes = 64 * 1024;
    private static readonly string[] PlugAndPlayGatewayHosts = [];
    private static readonly JsonSerializerOptions CaseInsensitiveJsonOptions = new()
    {
        PropertyNameCaseInsensitive = true
    };

    private static readonly HttpClientHandler HttpHandler = new()
    {
        AllowAutoRedirect = false
    };

    // Timeout default 5 min: chat non-streaming su modelli grandi puo' impiegare minuti.
    // Body cap 10 MB: protezione contro response gigante / server malevolo.
    private static readonly HttpClient HttpClient = new(HttpHandler)
    {
        Timeout = TimeSpan.FromMinutes(5),
        MaxResponseContentBufferSize = 10L * 1024 * 1024
    };

    private static readonly HttpClient HubEventsHttpClient = new(HttpHandler, disposeHandler: false)
    {
        Timeout = Timeout.InfiniteTimeSpan
    };

    private sealed record BufferedHermesResponse(int StatusCode, string? ReasonPhrase, string Body, string? MediaType)
    {
        public bool IsSuccessStatusCode => StatusCode is >= 200 and <= 299;
    }

    public static async Task<GatewayChatResult> SendChatAsync(
        AppSettings settings,
        string mode,
        string prompt,
        IReadOnlyList<ChatMessageRecord> history,
        string? conversationId = null,
        string? previousResponseId = null)
    {
        await EnsureReachableGatewayAsync(settings);
        string? lastError = null;

        if (ShouldUseResponsesFirst(settings, mode) && await SupportsResponsesAsync(settings))
        {
            try
            {
                var serverConversationId = HermesHubProtocol.ServerConversationId(conversationId);
                var responsePayload = JsonSerializer.Serialize(new
                {
                    model = settings.Model,
                    input = prompt,
                    instructions = HermesHubProtocol.IsNativePreferred(settings) ? null : HermesHubProtocol.Instructions(settings, mode),
                    store = true,
                    conversation = serverConversationId,
                    previous_response_id = string.IsNullOrWhiteSpace(serverConversationId) && !string.IsNullOrWhiteSpace(previousResponseId) ? previousResponseId : null,
                    metadata = HermesHubProtocol.Metadata(settings, conversationId: conversationId)
                });

                var response = await SendBufferedAsync(
                    token => BuildJsonRequest(HttpMethod.Post, $"{settings.GatewayUrl.TrimEnd('/')}/responses", responsePayload, token),
                    allowCompatAuth: true);
                var body = response.Body;

                if (!response.IsSuccessStatusCode)
                {
                    lastError = $"HTTP {response.StatusCode} {response.ReasonPhrase}";
                    if (!string.IsNullOrWhiteSpace(body))
                    {
                        lastError += $": {ExtractHumanError(body)}";
                    }
                    lastError = $"Responses API {lastError}";
                }
                else
                {
                    var assistantText = ExtractAssistantText(response.MediaType, body);
                    if (!string.IsNullOrWhiteSpace(assistantText))
                    {
                        return new GatewayChatResult(
                            assistantText,
                            "Hermes",
                            "Risposta ricevuta da Hermes Responses API.",
                            false,
                            ExtractResponseId(body),
                            VisualBlockParser.ExtractFromResponse(body),
                            VisualBlocksContract.Version);
                    }

                    lastError = "Hermes Responses API raggiunta, ma senza contenuto utile.";
                }
            }
            catch (Exception ex)
            {
                lastError = ex.Message;
            }

            if (settings.StrictNativeMode && HermesHubProtocol.IsNativePreferred(settings))
            {
                return new GatewayChatResult(
                    $"Hermes native non disponibile: {lastError ?? "errore sconosciuto"}.",
                    "Errore Hermes Native",
                    $"Strict native mode: nessun fallback compat eseguito. {lastError ?? "errore sconosciuto"}",
                    false,
                    null,
                    null,
                    null);
            }
        }

        try
        {
            var nativeMode = HermesHubProtocol.IsNativePreferred(settings);
            var serverConversationId = HermesHubProtocol.ServerConversationId(conversationId);
            var chatMessages = (nativeMode
                ? Enumerable.Empty<object>()
                : new object[]
                {
                    new
                    {
                        role = "system",
                        content = HermesHubProtocol.Instructions(settings, mode)
                    }
                }).Concat(history.Select(message => new
                {
                    role = string.Equals(message.Author, "Tu", StringComparison.OrdinalIgnoreCase) ? "user" : "assistant",
                    content = message.Text
                }));
            var chatPayload = JsonSerializer.Serialize(new
            {
                model = settings.Model,
                stream = false,
                session_id = serverConversationId,
                metadata = HermesHubProtocol.Metadata(settings, conversationId: conversationId),
                messages = chatMessages
            });

            var response = await SendBufferedAsync(
                token => BuildJsonRequest(HttpMethod.Post, $"{settings.GatewayUrl.TrimEnd('/')}/chat/completions", chatPayload, token, serverConversationId),
                allowCompatAuth: true);
            var body = response.Body;

            if (response.IsSuccessStatusCode)
            {
                var assistantText = ExtractAssistantText(response.MediaType, body);
                if (!string.IsNullOrWhiteSpace(assistantText))
                {
                    return new GatewayChatResult(
                        assistantText,
                        "Hermes",
                        "Risposta ricevuta da Hermes Chat Completions.",
                        false,
                        ExtractResponseId(body),
                        VisualBlockParser.ExtractFromResponse(body),
                        VisualBlocksContract.Version);
                }

                lastError = "Hermes Chat Completions raggiunta, ma senza contenuto utile.";
            }
            else
            {
                lastError = $"HTTP {response.StatusCode} {response.ReasonPhrase}: {ExtractHumanError(body)}";
            }
        }
        catch (Exception ex)
        {
            lastError = ex.Message;
        }

        if (settings.DemoMode)
        {
            return new GatewayChatResult(
                BuildFallbackReply(settings, mode, lastError),
                "Fallback locale",
                $"Hermes non disponibile, uso fallback locale: {lastError ?? "errore sconosciuto"}.",
                true,
                null,
                VisualBlockFixtures.ShouldAttach(settings, prompt) ? VisualBlockFixtures.Create() : [],
                VisualBlocksContract.Version);
        }

        return new GatewayChatResult(
            $"Hermes non raggiungibile: {lastError ?? "errore sconosciuto"}.",
            "Errore Hermes",
            $"Invio fallito: {lastError ?? "errore sconosciuto"}.",
            false,
            null,
            [],
            null);
    }

    public static async Task<GatewayTaskResult> QueueTaskAsync(AppSettings settings, AgentTaskRecord task)
    {
        var payload = JsonSerializer.Serialize(new
        {
            title = task.Title,
            instructions = task.Detail,
            detail = task.Detail,
            mode = task.Mode,
            requiresApproval = task.RequiresApproval,
            approvalRequired = task.RequiresApproval,
            model = settings.Model,
            provider = settings.Provider,
            metadata = HermesHubProtocol.Metadata(settings, source: "jobs-section")
        });

        try
        {
            var response = await SendBufferedAsync(token =>
                BuildJsonRequest(HttpMethod.Post, $"{HermesRoot(settings)}/api/jobs", payload, token));
            var body = response.Body;

            if (response.IsSuccessStatusCode)
            {
                var remoteId = ExtractTaskId(body);
                var remoteStatus = ExtractTaskStatus(body) ?? task.Status;
                var syncedTask = task with
                {
                    RemoteId = remoteId,
                    Status = remoteStatus,
                    Source = "Hermes Jobs",
                    Mode = "Job",
                    UpdatedAt = DateTimeOffset.Now
                };
                return new GatewayTaskResult(syncedTask, "Job creato su Hermes.");
            }

            var error = $"HTTP {response.StatusCode} {response.ReasonPhrase}";
            if (!string.IsNullOrWhiteSpace(body))
            {
                error += $": {ExtractHumanError(body)}";
            }

            return BuildTaskFallback(settings, task, $"Creazione task fallita: {error}");
        }
        catch (Exception ex)
        {
            return BuildTaskFallback(settings, task, $"Creazione task fallita: {ex.Message}");
        }
    }

    public static async Task<GatewayTaskResult> UpdateTaskAsync(AppSettings settings, AgentTaskRecord task, TaskCommand command)
    {
        var targetStatus = command switch
        {
            TaskCommand.Approve => "Run richiesto",
            TaskCommand.Deny => "Pausa richiesta",
            _ => "Eliminato"
        };

        if (string.IsNullOrWhiteSpace(task.RemoteId))
        {
            var localTask = task with
            {
                Status = $"{targetStatus} locale",
                Source = "Fallback locale",
                UpdatedAt = DateTimeOffset.Now
            };
            return new GatewayTaskResult(localTask, $"Job aggiornato in locale: {targetStatus}.");
        }

        var requestUri = command switch
        {
            TaskCommand.Approve => $"{HermesRoot(settings)}/api/jobs/{task.RemoteId}/run",
            TaskCommand.Deny => $"{HermesRoot(settings)}/api/jobs/{task.RemoteId}/pause",
            _ => $"{HermesRoot(settings)}/api/jobs/{task.RemoteId}"
        };
        var method = command switch
        {
            TaskCommand.Complete => HttpMethod.Delete,
            _ => HttpMethod.Post
        };

        try
        {
            var response = await SendBufferedAsync(token =>
                BuildJsonRequest(method, requestUri, "{}", token));
            var body = response.Body;

            if (response.IsSuccessStatusCode)
            {
                var remoteStatus = command == TaskCommand.Complete
                    ? "Eliminato"
                    : ExtractTaskStatus(body) ?? targetStatus;
                var syncedTask = task with
                {
                    Status = remoteStatus,
                    Source = "Hermes Jobs",
                    UpdatedAt = DateTimeOffset.Now
                };
                return new GatewayTaskResult(syncedTask, $"Job aggiornato su Hermes: {remoteStatus}.");
            }

            var error = $"HTTP {response.StatusCode} {response.ReasonPhrase}";
            if (!string.IsNullOrWhiteSpace(body))
            {
                error += $": {ExtractHumanError(body)}";
            }

            return BuildTaskFallback(settings, task with { Status = targetStatus }, $"Aggiornamento job fallito: {error}");
        }
        catch (Exception ex)
        {
            return BuildTaskFallback(settings, task with { Status = targetStatus }, $"Aggiornamento job fallito: {ex.Message}");
        }
    }

    public static async Task<WorkspaceRunResult> SendWorkspaceRunAsync(
        AppSettings settings,
        string kind,
        string prompt,
        CancellationToken cancellationToken = default)
    {
        var runPrompt = kind.Equals("Video", StringComparison.OrdinalIgnoreCase)
            ? $"Destinazione: Hermes Hub / Video.\nCartella video monitorata: {settings.VideoLibraryPath}\nMemoria: usa la memoria agente condivisa Hermes/CLI/app per preferenze utente, stile, durata, ritmo, fonti e regole editoriali. Se impari una preferenza stabile, salvala lato Hermes se possibile.\nObiettivo: crea output operativo per produzione video su PC/Hermes: brief, script, storyboard, scene, asset, voce, musica, rischi, prossimi step. Tutti i file finali devono essere pensati per comparire nel feed Video tramite quella cartella monitorata. Se utile, indica stream_url/download_url.\n\nRichiesta utente:\n{prompt}"
            : $"Destinazione: Hermes Hub / News.\nCartella news monitorata: {settings.NewsLibraryPath}\nMemoria: usa la memoria agente condivisa Hermes/CLI/app per interessi, fonti preferite, profondita, tono e filtri di qualita. Se impari una preferenza stabile, salvala lato Hermes se possibile.\nObiettivo: crea output operativo per articolo/briefing: query, fonti consultate, filtri, sintesi, frequenza, formato briefing, rischi di affidabilita. Se serve HTML/giornale online, salva il file finale nella cartella news monitorata.\n\nRichiesta utente:\n{prompt}";

        var payload = JsonSerializer.Serialize(new
        {
            model = settings.Model,
            input = runPrompt,
            metadata = new
            {
                client = "hermes-hub",
                client_surface = "windows-app",
                workspace = kind.ToLowerInvariant(),
                source = "workspace-section",
                memory_scope = "shared-hermes-agent-memory",
                share_with_cli = true
            }
        });

        var startResponse = await SendBufferedAsync(
            token => BuildJsonRequest(HttpMethod.Post, ResolveHermesUri(settings, "/v1/runs"), payload, token),
            cancellationToken: cancellationToken);
        if (startResponse.IsSuccessStatusCode)
        {
            string? runId = null;
            try
            {
                using var document = JsonDocument.Parse(startResponse.Body);
                runId = ExtractString(document.RootElement, "run_id", "id");
            }
            catch (JsonException)
            {
                // La risposta non valida viene gestita sotto senza creare un secondo lavoro duplicato.
            }

            if (string.IsNullOrWhiteSpace(runId))
            {
                return new WorkspaceRunResult(
                    "Hermes ha accettato la run ma non ha restituito un identificativo valido.",
                    "Hermes Runs",
                    "Run avviata con risposta non valida: controlla lo stato sul gateway.");
            }

            var deadline = DateTimeOffset.UtcNow.AddMinutes(30);
            var failures = 0;
            while (DateTimeOffset.UtcNow < deadline)
            {
                await Task.Delay(TimeSpan.FromSeconds(2), cancellationToken);
                var statusResponse = await SendBufferedAsync(
                    token => BuildRequest(
                        HttpMethod.Get,
                        ResolveHermesUri(settings, $"/v1/runs/{Uri.EscapeDataString(runId)}"),
                        token),
                    cancellationToken: cancellationToken);
                if (!statusResponse.IsSuccessStatusCode)
                {
                    failures++;
                    if (failures >= 5)
                    {
                        return new WorkspaceRunResult(
                            $"Run {runId}: polling non disponibile dopo {failures} tentativi (HTTP {statusResponse.StatusCode}).",
                            "Hermes Runs",
                            "La run può continuare sul server; controlla il gateway.");
                    }
                    continue;
                }

                failures = 0;
                try
                {
                    using var document = JsonDocument.Parse(statusResponse.Body);
                    var root = document.RootElement;
                    var state = (ExtractString(root, "status") ?? "running").Trim().ToLowerInvariant();
                    if (state is not ("completed" or "failed" or "cancelled"))
                    {
                        continue;
                    }

                    var output = root.TryGetProperty("output", out var outputElement)
                        ? ExtractTextFromJson(outputElement)
                        : string.Empty;
                    if (state == "completed")
                    {
                        return new WorkspaceRunResult(
                            string.IsNullOrWhiteSpace(output) ? $"Run {runId} completata senza output testuale." : output,
                            "Hermes Runs",
                            $"Run {runId} completata.");
                    }

                    var error = ExtractString(root, "error", "message") ?? $"Run {runId}: {state}.";
                    return new WorkspaceRunResult(error, "Hermes Runs", $"Run {runId} terminata con stato {state}.");
                }
                catch (JsonException ex)
                {
                    return new WorkspaceRunResult(
                        $"Run {runId}: risposta di stato non valida ({ex.Message}).",
                        "Hermes Runs",
                        "Impossibile leggere il risultato della run.");
                }
            }

            return new WorkspaceRunResult(
                $"Run {runId} ancora attiva dopo 30 minuti.",
                "Hermes Runs",
                "Polling locale terminato; la run continua sul server.");
        }

        var history = new List<ChatMessageRecord>
        {
            new("Tu", runPrompt, DateTimeOffset.Now)
        };
        var chat = await SendChatAsync(settings, "Agente", runPrompt, history);
        return new WorkspaceRunResult(chat.Message, chat.Source, chat.StatusMessage);
    }

    public static async Task<ServerSnapshot> GetServerSnapshotAsync(AppSettings settings)
    {
        await EnsureReachableGatewayAsync(settings);
        var root = HermesRoot(settings);
        var healthUrl = $"{root}/health";

        try
        {
            var healthResponse = await SendBufferedAsync(token => BuildRequest(HttpMethod.Get, healthUrl, token));
            var healthStatus = healthResponse.IsSuccessStatusCode
                ? "Hermes raggiungibile."
                : $"Hermes risponde: HTTP {healthResponse.StatusCode} {healthResponse.ReasonPhrase}";

            try
            {
                var statusResponse = await SendBufferedAsync(token => BuildRequest(HttpMethod.Get, $"{root}/health/detailed", token));
                var statusBody = statusResponse.Body;

                if (statusResponse.IsSuccessStatusCode && TryParseServerSnapshot(statusBody, settings, healthStatus, out var snapshot))
                {
                    return snapshot;
                }
            }
            catch
            {
                // Keep health result and fall back to local settings snapshot.
            }

            return new ServerSnapshot(
                settings.GatewayUrl,
                settings.Model,
                $"Provider: {settings.Provider} | API: {settings.PreferredApi}",
                settings.InferenceEndpoint,
                settings.AccessMode,
                settings.DemoMode
                    ? $"{healthStatus} Fallback locale attivo."
                    : $"{healthStatus} Solo Hermes, nessun fallback locale.",
                settings.VideoLibraryPath);
        }
        catch (Exception ex)
        {
            var modeText = settings.DemoMode
                ? "Fallback locale attivo."
                : "Solo Hermes, nessun fallback locale.";
            return new ServerSnapshot(
                settings.GatewayUrl,
                settings.Model,
                $"Provider: {settings.Provider} | API: {settings.PreferredApi}",
                settings.InferenceEndpoint,
                settings.AccessMode,
                $"Hermes non raggiungibile: {ex.Message}. {modeText}",
                settings.VideoLibraryPath);
        }
    }

    public static async Task<HardwareSnapshot> GetHardwareSnapshotAsync(AppSettings settings)
    {
        try
        {
            await EnsureReachableGatewayAsync(settings);
            var response = await SendBufferedAsync(token => BuildRequest(HttpMethod.Get, ResolveHermesUri(settings, "/v1/hub/hardware"), token));
            if (!response.IsSuccessStatusCode)
            {
                return EmptyHardwareSnapshot($"Hardware gateway non disponibile: HTTP {response.StatusCode} {ExtractHumanError(response.Body)}");
            }

            using var document = JsonDocument.Parse(response.Body);
            return ParseHardwareSnapshot(document.RootElement);
        }
        catch (Exception ex)
        {
            return EmptyHardwareSnapshot($"Hardware gateway non disponibile: {ex.Message}");
        }
    }

    private static HardwareSnapshot EmptyHardwareSnapshot(string message)
    {
        return new HardwareSnapshot(
            "unavailable",
            DateTimeOffset.Now,
            "-",
            "-",
            "-",
            "-",
            "-",
            0,
            0,
            0,
            0,
            null,
            null,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            "unavailable",
            [],
            [],
            [],
            message);
    }

    private static HardwareSnapshot ParseHardwareSnapshot(JsonElement root)
    {
        var host = root.TryGetProperty("host", out var hostElement) ? hostElement : default;
        var cpu = root.TryGetProperty("cpu", out var cpuElement) ? cpuElement : default;
        var memory = root.TryGetProperty("memory", out var memoryElement) ? memoryElement : default;
        var swap = root.TryGetProperty("swap", out var swapElement) ? swapElement : default;
        var network = root.TryGetProperty("network", out var networkElement) ? networkElement : default;
        var timestampSeconds = ExtractDouble(root, "timestamp");
        var timestamp = timestampSeconds > 0
            ? DateTimeOffset.FromUnixTimeMilliseconds((long)(timestampSeconds * 1000))
            : DateTimeOffset.Now;

        var disks = new List<HardwareDiskRecord>();
        if (root.TryGetProperty("disks", out var diskArray) && diskArray.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in diskArray.EnumerateArray())
            {
                var device = ExtractString(item, "device") ?? "-";
                var fileSystem = ExtractString(item, "fstype") ?? "-";
                if (!IsMeaningfulHardwareDisk(device, fileSystem))
                {
                    continue;
                }

                disks.Add(new HardwareDiskRecord(
                    device,
                    ExtractString(item, "mountpoint") ?? "-",
                    fileSystem,
                    ExtractLong(item, "total_bytes"),
                    ExtractLong(item, "used_bytes"),
                    ExtractLong(item, "free_bytes"),
                    ExtractDouble(item, "percent")));
            }
        }

        var temperatures = new List<HardwareTemperatureRecord>();
        if (root.TryGetProperty("temperatures", out var tempArray) && tempArray.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in tempArray.EnumerateArray())
            {
                var current = ExtractDouble(item, "current_c");
                if (current < 0 || current > 150)
                {
                    continue;
                }

                temperatures.Add(new HardwareTemperatureRecord(
                    ExtractString(item, "name") ?? "-",
                    ExtractString(item, "label") ?? "-",
                    current,
                    ExtractNullableDouble(item, "high_c"),
                    ExtractNullableDouble(item, "critical_c")));
            }
        }

        var gpus = new List<HardwareGpuRecord>();
        if (root.TryGetProperty("gpus", out var gpuArray) && gpuArray.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in gpuArray.EnumerateArray())
            {
                var memoryTotalMb = ExtractDouble(item, "memory_total_mb");
                var memoryUsedMb = ExtractDouble(item, "memory_used_mb");
                var memoryTotalBytes = memoryTotalMb > 0 ? (long)(memoryTotalMb * 1024 * 1024) : ExtractLong(item, "memory_total_bytes");
                var memoryUsedBytes = memoryUsedMb > 0 ? (long)(memoryUsedMb * 1024 * 1024) : ExtractLong(item, "memory_used_bytes");
                gpus.Add(new HardwareGpuRecord(
                    ExtractInt(item, "index"),
                    ExtractString(item, "name") ?? "GPU",
                    ExtractDouble(item, "utilization_gpu_percent"),
                    ExtractDouble(item, "utilization_memory_percent"),
                    memoryUsedBytes,
                    memoryTotalBytes,
                    SanitizeNullableRange(ExtractNullableDouble(item, "temperature_c"), 0, 150),
                    SanitizeNullableRange(ExtractNullableDouble(item, "power_draw_watts"), 0, 1000),
                    SanitizeNullableRange(ExtractNullableDouble(item, "power_limit_watts"), 0, 1000),
                    ExtractString(item, "driver_version") ?? "-"));
            }
        }

        return new HardwareSnapshot(
            ExtractString(root, "status") ?? "ok",
            timestamp,
            ExtractString(host, "hostname") ?? "-",
            ExtractString(host, "os") ?? "-",
            ExtractString(host, "platform") ?? "-",
            ExtractString(host, "architecture") ?? "-",
            ExtractString(host, "processor") ?? "-",
            ExtractLong(host, "uptime_seconds"),
            ExtractDouble(cpu, "percent"),
            ExtractInt(cpu, "physical_cores"),
            ExtractInt(cpu, "logical_cores"),
            ExtractNullableDouble(cpu, "current_mhz"),
            ExtractNullableDouble(cpu, "max_mhz"),
            ExtractDouble(memory, "percent"),
            ExtractLong(memory, "total_bytes"),
            ExtractLong(memory, "used_bytes"),
            ExtractLong(memory, "available_bytes"),
            ExtractDouble(swap, "percent"),
            ExtractLong(swap, "total_bytes"),
            ExtractLong(swap, "used_bytes"),
            ExtractLong(network, "bytes_sent"),
            ExtractLong(network, "bytes_recv"),
            ExtractInt(root, "process_count"),
            ExtractString(root, "temperature_support") ?? "unavailable",
            disks,
            temperatures,
            gpus,
            "Statistiche aggiornate dal gateway Hermes.");
    }

    private static bool IsMeaningfulHardwareDisk(string device, string fileSystem)
    {
        if (device.Trim().StartsWith("/dev/loop", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        return fileSystem.Trim().ToLowerInvariant() is not (
            "autofs" or "cgroup" or "cgroup2" or "configfs" or "debugfs" or "devtmpfs" or
            "efivarfs" or "fusectl" or "hugetlbfs" or "mqueue" or "nsfs" or "overlay" or
            "proc" or "pstore" or "ramfs" or "securityfs" or "squashfs" or "sysfs" or
            "tmpfs" or "tracefs");
    }

    private static double? SanitizeNullableRange(double? value, double min, double max)
    {
        return value is not null && double.IsFinite(value.Value) && value.Value >= min && value.Value <= max ? value : null;
    }

    private static GatewayTaskResult BuildTaskFallback(AppSettings settings, AgentTaskRecord task, string message)
    {
        if (settings.DemoMode)
        {
            var fallbackTask = task with
            {
                Status = task.RequiresApproval ? "In attesa approvazione" : "Pronto",
                Source = "Fallback locale",
                Mode = "Locale",
                UpdatedAt = DateTimeOffset.Now
            };
            return new GatewayTaskResult(fallbackTask, $"{message} Salvo il task in locale.");
        }

        var failedTask = task with
        {
            Status = "Errore Hermes",
            Source = "Errore Hermes",
            UpdatedAt = DateTimeOffset.Now
        };
        return new GatewayTaskResult(failedTask, message);
    }

    private static bool TryParseServerSnapshot(
        string body,
        AppSettings settings,
        string healthStatus,
        out ServerSnapshot snapshot)
    {
        snapshot = default!;

        try
        {
            using var document = JsonDocument.Parse(body);
            var root = document.RootElement;
            var model = ExtractString(root, "model")
                ?? ExtractNestedString(root, "server", "model")
                ?? ExtractNestedString(root, "runtime", "model")
                ?? settings.Model;
            var provider = ExtractString(root, "provider")
                ?? ExtractNestedString(root, "server", "provider")
                ?? settings.Provider;
            var api = ExtractString(root, "preferredApi")
                ?? ExtractString(root, "api")
                ?? ExtractNestedString(root, "server", "api")
                ?? settings.PreferredApi;
            var inference = ExtractString(root, "inferenceEndpoint")
                ?? ExtractNestedString(root, "server", "inferenceEndpoint")
                ?? ExtractNestedString(root, "runtime", "endpoint")
                ?? settings.InferenceEndpoint;
            var policy = ExtractString(root, "accessMode")
                ?? ExtractString(root, "networkPolicy")
                ?? ExtractNestedString(root, "security", "networkPolicy")
                ?? settings.AccessMode;
            var videoLibraryPath = ExtractString(root, "video_library_path")
                ?? ExtractString(root, "videoLibraryPath")
                ?? ExtractNestedString(root, "video", "library_path")
                ?? ExtractNestedString(root, "video", "video_library_path")
                ?? ExtractNestedString(root, "config", "video_library_path")
                ?? settings.VideoLibraryPath;
            var statusMessage = ExtractString(root, "status")
                ?? ExtractString(root, "message")
                ?? healthStatus;

            if (!string.Equals(videoLibraryPath, settings.VideoLibraryPath, StringComparison.Ordinal))
            {
                settings.VideoLibraryPath = videoLibraryPath;
                AppSettingsStore.Save(settings);
            }

            snapshot = new ServerSnapshot(
                settings.GatewayUrl,
                model,
                $"Provider: {provider} | API: {api}",
                inference,
                policy,
                statusMessage,
                settings.VideoLibraryPath);
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static string BuildFallbackReply(AppSettings settings, string mode, string? reason)
    {
        var prefix = string.Equals(mode, "Agente", StringComparison.OrdinalIgnoreCase)
            ? "Hermes assente: preparo un job locale e tengo il contesto pronto."
            : "Hermes assente: uso la risposta locale di emergenza senza perdere la conversazione.";

        var detail = string.IsNullOrWhiteSpace(reason) ? string.Empty : $" Motivo: {reason}.";
        return $"{prefix} Preset: API {settings.GatewayUrl}, modello {settings.Model}, protocollo {settings.PreferredApi}.{detail}";
    }

    public static async Task<bool> SupportsResponsesAsync(AppSettings settings)
    {
        await EnsureReachableGatewayAsync(settings);
        try
        {
            var response = await SendBufferedAsync(token => BuildRequest(HttpMethod.Get, $"{settings.GatewayUrl.TrimEnd('/')}/capabilities", token));
            var body = response.Body;
            if (!response.IsSuccessStatusCode || string.IsNullOrWhiteSpace(body))
            {
                return true;
            }

            return body.Contains("responses", StringComparison.OrdinalIgnoreCase) ||
                   body.Contains("Responses", StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return true;
        }
    }

    internal static bool ShouldUseResponsesFirst(AppSettings settings, string mode)
    {
        if (HermesHubProtocol.IsNativePreferred(settings))
        {
            return true;
        }

        return string.Equals(settings.PreferredApi, "openai-responses", StringComparison.OrdinalIgnoreCase) &&
               string.Equals(mode, "Agente", StringComparison.OrdinalIgnoreCase);
    }

    public static async Task<string> SendHermesRequestAsync(AppSettings settings, HttpMethod method, string path, string? jsonPayload = null)
    {
        var uri = ResolveHermesUri(settings, path);
        var response = await SendBufferedAsync(token =>
            string.IsNullOrWhiteSpace(jsonPayload)
                ? BuildRequest(method, uri, token)
                : BuildJsonRequest(method, uri, jsonPayload, token));
        var body = response.Body;
        if (response.IsSuccessStatusCode)
        {
            return string.IsNullOrWhiteSpace(body) ? $"HTTP {response.StatusCode} {response.ReasonPhrase}" : body;
        }

        return $"HTTP {response.StatusCode} {response.ReasonPhrase}: {ExtractHumanError(body)}";
    }

    public static async Task<(string Url, string Status)> UploadContinuityFileAsync(AppSettings settings, string path, CancellationToken cancellationToken = default)
    {
        var info = new FileInfo(path);
        if (!info.Exists || info.Length <= 0 || info.Length > 100 * 1024 * 1024) return (string.Empty, "File assente, vuoto o oltre 100 MB.");
        await EnsureReachableGatewayAsync(settings, cancellationToken);
        using var request = new HttpRequestMessage(HttpMethod.Post, ResolveHermesUri(settings, "/v1/media/upload"));
        var token = GatewayCredentialStore.LoadSecret();
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        using var content = new MultipartFormDataContent();
        using var fileContent = new StreamContent(stream);
        fileContent.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/octet-stream");
        content.Add(fileContent, "file", info.Name);
        request.Content = content;
        using var response = await HttpClient.SendAsync(request, HttpCompletionOption.ResponseContentRead, cancellationToken);
        var body = await ReadBoundedStringAsync(response.Content, MaxBufferedResponseBytes, cancellationToken);
        if (!response.IsSuccessStatusCode) return (string.Empty, $"Upload fallito: HTTP {(int)response.StatusCode} {ExtractHumanError(body)}");
        try
        {
            using var document = JsonDocument.Parse(body);
            var root = document.RootElement;
            var url = ExtractString(root, "media_url", "url", "file_url") ?? string.Empty;
            return (url, string.IsNullOrWhiteSpace(url) ? "Upload riuscito ma URL mancante." : "File caricato.");
        }
        catch (JsonException) { return (string.Empty, "Risposta upload non valida."); }
    }

    public static async Task<NewsLibraryResult> LoadNewsLibraryAsync(AppSettings settings)
    {
        try
        {
            var newsPath = settings.NewsLibraryPath?.Trim() ?? string.Empty;
            var pathQuery = string.IsNullOrWhiteSpace(newsPath) ? string.Empty : $"?path={Uri.EscapeDataString(newsPath)}";
            var response = await SendBufferedAsync(
                token => BuildRequest(HttpMethod.Get, ResolveHermesUri(settings, "/v1/news/library" + pathQuery), token),
                allowCompatAuth: true);
            if (!response.IsSuccessStatusCode)
            {
                return new NewsLibraryResult([], $"News HTML non disponibile: HTTP {response.StatusCode} {ExtractHumanError(response.Body)}");
            }

            using var document = JsonDocument.Parse(response.Body);
            var root = document.RootElement;
            var libraryPath = ExtractString(root, "news_library_path", "library_path") ?? newsPath;
            var items = new List<NewsHtmlRecord>();
            if (root.TryGetProperty("items", out var array) && array.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in array.EnumerateArray())
                {
                    var url = ExtractString(item, "url", "media_url", "download_url") ?? string.Empty;
                    var filename = ExtractString(item, "filename", "name") ?? string.Empty;
                    if (string.IsNullOrWhiteSpace(url) || string.IsNullOrWhiteSpace(filename))
                    {
                        continue;
                    }

                    var modified = ExtractDouble(item, "modified_at");
                    items.Add(new NewsHtmlRecord(
                        ExtractString(item, "id") ?? filename,
                        ExtractString(item, "title") ?? Path.GetFileNameWithoutExtension(filename),
                        filename,
                        ResolveHermesUri(settings, url),
                        ExtractString(item, "path") ?? string.Empty,
                        ExtractString(item, "mime_type") ?? "text/html",
                        ExtractLong(item, "size_bytes"),
                        modified > 0 ? DateTimeOffset.FromUnixTimeSeconds((long)modified) : DateTimeOffset.Now));
                }
            }

            var ordered = items.OrderByDescending(item => item.ModifiedAt).ToArray();
            return new NewsLibraryResult(
                ordered,
                ordered.Length == 0 ? $"Cartella news sincronizzata: {libraryPath}. Nessun HTML trovato." : $"{ordered.Length} pagine HTML trovate in: {libraryPath}");
        }
        catch (Exception ex)
        {
            return new NewsLibraryResult([], $"News HTML non disponibile: {ex.Message}");
        }
    }

    public static async Task<string> LoadGatewayTextAsync(AppSettings settings, string url)
    {
        var resolved = ResolveHermesUri(settings, url);
        BufferedHermesResponse response;
        if (HasSameAuthority(resolved, settings.GatewayUrl))
        {
            response = await SendBufferedAsync(
                token => BuildRequest(HttpMethod.Get, resolved, token),
                allowCompatAuth: true);
        }
        else
        {
            using var request = BuildRequest(HttpMethod.Get, resolved);
            using var externalResponse = await HttpClient.SendAsync(
                request,
                HttpCompletionOption.ResponseHeadersRead);
            response = new BufferedHermesResponse(
                (int)externalResponse.StatusCode,
                externalResponse.ReasonPhrase,
                await ReadBoundedStringAsync(
                    externalResponse.Content,
                    MaxBufferedResponseBytes,
                    CancellationToken.None),
                externalResponse.Content.Headers.ContentType?.MediaType);
        }
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException($"HTTP {response.StatusCode}: {ExtractHumanError(response.Body)}");
        }

        return response.Body;
    }

    public static async Task<CronJobsResult> LoadCronJobsAsync(AppSettings settings, bool includeDisabled = true)
    {
        try
        {
            var path = includeDisabled ? "/api/jobs?type=cron&include_disabled=1" : "/api/jobs?type=cron";
            var response = await SendBufferedAsync(token => BuildRequest(HttpMethod.Get, ResolveHermesUri(settings, path), token));
            if (!response.IsSuccessStatusCode)
            {
                return new CronJobsResult([], $"Cron non disponibile: HTTP {response.StatusCode} {ExtractHumanError(response.Body)}");
            }

            var jobs = ParseCronJobs(response.Body)
                .OrderByDescending(job => job.Enabled)
                .ThenBy(job => string.IsNullOrWhiteSpace(job.NextRunAt) ? "9999" : job.NextRunAt, StringComparer.OrdinalIgnoreCase)
                .ThenBy(job => job.Name, StringComparer.OrdinalIgnoreCase)
                .ToArray();
            var active = jobs.Count(job => job.Enabled);
            return new CronJobsResult(jobs, active == 1 ? "1 cron attivo." : $"{active} cron attivi.");
        }
        catch (Exception ex)
        {
            return new CronJobsResult([], $"Cron non disponibile: {ex.Message}");
        }
    }

    public static async Task<string> RunCronJobAsync(AppSettings settings, string id)
    {
        return await CronJobActionAsync(settings, id, "run", HttpMethod.Post, "Cron avviato.");
    }

    public static async Task<string> SaveCronJobAsync(
        AppSettings settings,
        string? id,
        string name,
        string schedule,
        string prompt,
        string deliver)
    {
        var payload = JsonSerializer.Serialize(new
        {
            name = name.Trim(),
            schedule = schedule.Trim(),
            prompt,
            deliver = string.IsNullOrWhiteSpace(deliver) ? "local" : deliver.Trim()
        });
        var creating = string.IsNullOrWhiteSpace(id);
        var path = creating ? "/api/jobs" : $"/api/jobs/{Uri.EscapeDataString(id!)}";
        var method = creating ? HttpMethod.Post : HttpMethod.Patch;
        var response = await SendBufferedAsync(token => BuildJsonRequest(method, ResolveHermesUri(settings, path), payload, token));
        return response.IsSuccessStatusCode
            ? creating ? "Automazione creata." : "Automazione aggiornata."
            : $"Automazione non salvata: HTTP {response.StatusCode} {ExtractHumanError(response.Body)}";
    }

    public static async Task<string> PauseCronJobAsync(AppSettings settings, string id)
    {
        return await CronJobActionAsync(settings, id, "pause", HttpMethod.Post, "Cron messo in pausa.");
    }

    public static async Task<string> ResumeCronJobAsync(AppSettings settings, string id)
    {
        return await CronJobActionAsync(settings, id, "resume", HttpMethod.Post, "Cron riattivato.");
    }

    public static async Task<string> DeleteCronJobAsync(AppSettings settings, string id)
    {
        return await CronJobActionAsync(settings, id, null, HttpMethod.Delete, "Cron eliminato.");
    }

    public static async Task<(HubMemoryState Memory, string Status)> LoadHubMemoryAsync(AppSettings settings)
    {
        try
        {
            var response = await SendBufferedAsync(token => BuildRequest(HttpMethod.Get, ResolveHermesUri(settings, "/v1/hub/memory"), token));
            if (!response.IsSuccessStatusCode)
            {
                return (new HubMemoryState("", "", "", "", ""), $"Memoria gateway non esposta: HTTP {response.StatusCode} {ExtractHumanError(response.Body)}");
            }

            using var doc = JsonDocument.Parse(response.Body);
            var categories = doc.RootElement.TryGetProperty("categories", out var cat) ? cat : default;
            string Read(string name) => categories.ValueKind == JsonValueKind.Object && categories.TryGetProperty(name, out var value) ? value.GetString() ?? "" : "";
            return (
                new HubMemoryState(
                    Read("video_preferences"),
                    Read("news_preferences"),
                    Read("response_style"),
                    Read("project_rules"),
                    Read("general_notes")),
                "Memoria caricata da gateway.");
        }
        catch (Exception ex)
        {
            return (new HubMemoryState("", "", "", "", ""), $"Memoria gateway non esposta: {ex.Message}");
        }
    }

    public static async Task<string> SaveHubMemoryAsync(AppSettings settings, HubMemoryState memory)
    {
        var payload = JsonSerializer.Serialize(new
        {
            categories = new
            {
                video_preferences = memory.VideoPreferences,
                news_preferences = memory.NewsPreferences,
                response_style = memory.ResponseStyle,
                project_rules = memory.ProjectRules,
                general_notes = memory.GeneralNotes
            }
        });
        var response = await SendBufferedAsync(token => BuildJsonRequest(HttpMethod.Patch, ResolveHermesUri(settings, "/v1/hub/memory"), payload, token));
        return response.IsSuccessStatusCode
            ? "Memoria salvata sul gateway."
            : $"Memoria gateway non esposta: HTTP {response.StatusCode} {ExtractHumanError(response.Body)}";
    }

    public static async Task<string> SaveHubStateAsync(AppSettings settings, string kind, string entityId, object payload)
    {
        var body = JsonSerializer.Serialize(new
        {
            kind,
            entity_id = entityId,
            project_id = settings.ActiveProjectId,
            project_name = settings.ActiveProjectName,
            payload
        });
        var response = await SendBufferedAsync(token => BuildJsonRequest(HttpMethod.Post, ResolveHermesUri(settings, "/v1/hub/state"), body, token));
        return response.IsSuccessStatusCode
            ? "Sincronizzato con Hub State."
            : $"Hub State non disponibile: HTTP {response.StatusCode} {ExtractHumanError(response.Body)}";
    }

    public static async Task<HubOperationResult> SaveHubConversationsAsync(
        AppSettings settings,
        IEnumerable<ConversationRecord> conversations,
        CancellationToken cancellationToken = default)
    {
        await EnsureReachableGatewayAsync(settings, cancellationToken);
        var payload = JsonSerializer.Serialize(new
        {
            items = conversations
                .OrderByDescending(item => item.UpdatedAt)
                .Select(item => new
                {
                    id = item.Id,
                    title = item.Title,
                    kind = item.Kind,
                    description = item.Description,
                    prompt = item.Prompt,
                    updatedAt = item.UpdatedAt.ToUnixTimeMilliseconds(),
                    deletedAt = item.DeletedAt?.ToUnixTimeMilliseconds(),
                    previousResponseId = string.IsNullOrWhiteSpace(item.PreviousResponseId) ? null : item.PreviousResponseId,
                    serverConversationId = string.IsNullOrWhiteSpace(item.ServerConversationId) ? null : item.ServerConversationId,
                    projectId = string.IsNullOrWhiteSpace(item.ProjectId) ? null : item.ProjectId,
                    workspacePath = string.IsNullOrWhiteSpace(item.WorkspacePath) ? null : item.WorkspacePath,
                    repositoryUrl = string.IsNullOrWhiteSpace(item.RepositoryUrl) ? null : item.RepositoryUrl,
                    projectInstructions = string.IsNullOrWhiteSpace(item.ProjectInstructions) ? null : item.ProjectInstructions,
                    projectMemory = string.IsNullOrWhiteSpace(item.ProjectMemory) ? null : item.ProjectMemory,
                    authorizedTools = item.AuthorizedTools,
                    artifactType = string.IsNullOrWhiteSpace(item.ArtifactType) ? null : item.ArtifactType,
                    artifactUrl = string.IsNullOrWhiteSpace(item.ArtifactUrl) ? null : item.ArtifactUrl,
                    artifactFileName = string.IsNullOrWhiteSpace(item.ArtifactFileName) ? null : item.ArtifactFileName,
                    artifactMimeType = string.IsNullOrWhiteSpace(item.ArtifactMimeType) ? null : item.ArtifactMimeType,
                    sourceConversationId = string.IsNullOrWhiteSpace(item.SourceConversationId) ? null : item.SourceConversationId,
                    sourceRunId = string.IsNullOrWhiteSpace(item.SourceRunId) ? null : item.SourceRunId,
                    version = item.Version,
                    tags = item.Tags,
                    folder = string.IsNullOrWhiteSpace(item.Folder) ? null : item.Folder,
                    summary = string.IsNullOrWhiteSpace(item.Summary) ? null : item.Summary,
                    parentConversationId = string.IsNullOrWhiteSpace(item.ParentConversationId) ? null : item.ParentConversationId,
                    branchFromMessageId = string.IsNullOrWhiteSpace(item.BranchFromMessageId) ? null : item.BranchFromMessageId,
                    linkedConversationIds = item.LinkedConversationIds,
                    messages = item.Messages.Select(message => new
                    {
                        id = message.Id,
                        author = message.Author,
                        text = message.Text,
                        fromUser = string.Equals(message.Author, "Tu", StringComparison.OrdinalIgnoreCase) ||
                                   string.Equals(message.Author, "User", StringComparison.OrdinalIgnoreCase),
                        timestamp = message.Timestamp.ToUnixTimeMilliseconds(),
                        visualBlocksVersion = message.VisualBlocksVersion,
                        visualBlocks = message.VisualBlocks ?? [],
                        stats = message.Stats,
                        thinking = message.Thinking,
                        rawEvents = message.RawEvents ?? [],
                        bookmarked = message.IsBookmarked
                    })
                })
        });
        var response = await SendBufferedAsync(
            token => BuildJsonRequest(HttpMethod.Post, ResolveHermesUri(settings, "/v1/hub/conversations/import"), payload, token),
            cancellationToken: cancellationToken);
        return response.IsSuccessStatusCode
            ? new HubOperationResult(true, "Archivio caricato sul gateway.")
            : new HubOperationResult(false, $"Archivio server non disponibile: HTTP {response.StatusCode} {ExtractHumanError(response.Body)}");
    }

    public static async Task<HubConversationsResult> LoadHubConversationsAsync(
        AppSettings settings,
        CancellationToken cancellationToken = default)
    {
        try
        {
            await EnsureReachableGatewayAsync(settings, cancellationToken);
            var response = await SendBufferedAsync(
                token => BuildRequest(HttpMethod.Get, ResolveHermesUri(settings, "/v1/hub/conversations"), token),
                cancellationToken: cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                return new HubConversationsResult([], $"Archivio server non disponibile: HTTP {response.StatusCode} {ExtractHumanError(response.Body)}");
            }

            using var doc = JsonDocument.Parse(response.Body);
            var items = new List<ConversationRecord>();
            if (doc.RootElement.TryGetProperty("items", out var array) && array.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in array.EnumerateArray())
                {
                    var id = ExtractString(item, "id", "conversationId", "conversation_id");
                    if (string.IsNullOrWhiteSpace(id)) continue;
                    var updated = ExtractDouble(item, "updatedAt");
                    var deleted = ExtractDouble(item, "deletedAt");
                    if (deleted <= 0)
                    {
                        deleted = ExtractDouble(item, "deleted_at");
                    }
                    var messages = new List<ChatMessageRecord>();
                    if (item.TryGetProperty("messages", out var messageArray) && messageArray.ValueKind == JsonValueKind.Array)
                    {
                        foreach (var message in messageArray.EnumerateArray())
                        {
                            var timestamp = ExtractDouble(message, "timestamp");
                            var visualBlocks = ReadMessageVisualBlocks(message);
                            var role = ExtractString(message, "author", "role") ?? "Hermes";
                            var fromUser = role.Equals("user", StringComparison.OrdinalIgnoreCase) ||
                                           role.Equals("Tu", StringComparison.OrdinalIgnoreCase) ||
                                           message.TryGetProperty("fromUser", out var fromUserElement) && fromUserElement.ValueKind == JsonValueKind.True;
                            messages.Add(new ChatMessageRecord(
                                fromUser ? "Tu" : role.Equals("assistant", StringComparison.OrdinalIgnoreCase) ? "Hermes" : role,
                                ExtractString(message, "text", "content", "message") ?? string.Empty,
                                timestamp > 0 ? DateTimeOffset.FromUnixTimeMilliseconds((long)timestamp) : DateTimeOffset.Now,
                                ReadMessageVisualBlocksVersion(message),
                                visualBlocks.Count > 0 ? visualBlocks : null,
                                ReadMessageStats(message),
                                ReadMessageRawEvents(message),
                                message.TryGetProperty("bookmarked", out var bookmarked) && bookmarked.ValueKind == JsonValueKind.True)
                            {
                                Id = ExtractString(message, "id", "messageId", "message_id") ?? Guid.NewGuid().ToString("N"),
                                Thinking = ExtractString(message, "thinking", "reasoning") ?? string.Empty
                            });
                        }
                    }

                    items.Add(new ConversationRecord
                    {
                        Id = id,
                        Title = ExtractString(item, "title", "name") ?? "Nuova chat",
                        Kind = ExtractString(item, "kind") ?? "Chat",
                        Description = ExtractString(item, "description") ?? string.Empty,
                        Prompt = ExtractString(item, "prompt") ?? string.Empty,
                        UpdatedAt = updated > 0 ? DateTimeOffset.FromUnixTimeMilliseconds((long)updated) : DateTimeOffset.Now,
                        DeletedAt = deleted > 0 ? DateTimeOffset.FromUnixTimeMilliseconds((long)deleted) : null,
                        PreviousResponseId = ExtractString(item, "previousResponseId", "previous_response_id") ?? string.Empty,
                        ServerConversationId = ExtractString(item, "serverConversationId", "server_conversation_id") ?? string.Empty,
                        ProjectId = ExtractString(item, "projectId", "project_id") ?? string.Empty,
                        WorkspacePath = ExtractString(item, "workspacePath", "workspace_path") ?? string.Empty,
                        RepositoryUrl = ExtractString(item, "repositoryUrl", "repository_url") ?? string.Empty,
                        ProjectInstructions = ExtractString(item, "projectInstructions", "project_instructions") ?? string.Empty,
                        ProjectMemory = ExtractString(item, "projectMemory", "project_memory") ?? string.Empty,
                        AuthorizedTools = ExtractStringArray(item, "authorizedTools", "authorized_tools"),
                        ArtifactType = ExtractString(item, "artifactType", "artifact_type") ?? string.Empty,
                        ArtifactUrl = ExtractString(item, "artifactUrl", "artifact_url") ?? string.Empty,
                        ArtifactFileName = ExtractString(item, "artifactFileName", "artifact_file_name") ?? string.Empty,
                        ArtifactMimeType = ExtractString(item, "artifactMimeType", "artifact_mime_type") ?? string.Empty,
                        SourceConversationId = ExtractString(item, "sourceConversationId", "source_conversation_id") ?? string.Empty,
                        SourceRunId = ExtractString(item, "sourceRunId", "source_run_id") ?? string.Empty,
                        Version = ExtractInt(item, "version"),
                        Tags = ExtractStringArray(item, "tags"),
                        Folder = ExtractString(item, "folder") ?? string.Empty,
                        Summary = ExtractString(item, "summary") ?? string.Empty,
                        ParentConversationId = ExtractString(item, "parentConversationId", "parent_conversation_id") ?? string.Empty,
                        BranchFromMessageId = ExtractString(item, "branchFromMessageId", "branch_from_message_id") ?? string.Empty,
                        LinkedConversationIds = ExtractStringArray(item, "linkedConversationIds", "linked_conversation_ids"),
                        Messages = messages
                    });
                }
            }

            return new HubConversationsResult(items.OrderByDescending(item => item.UpdatedAt).ToArray(), $"Archivio server: {items.Count} chat.", true);
        }
        catch (Exception ex)
        {
            return new HubConversationsResult([], $"Archivio server non disponibile: {ex.Message}");
        }
    }

    private static List<string> ExtractStringArray(JsonElement root, params string[] keys)
    {
        foreach (var key in keys)
        {
            if (!root.TryGetProperty(key, out var value) || value.ValueKind != JsonValueKind.Array)
            {
                continue;
            }

            return value.EnumerateArray()
                .Where(item => item.ValueKind == JsonValueKind.String)
                .Select(item => item.GetString()?.Trim() ?? string.Empty)
                .Where(item => !string.IsNullOrWhiteSpace(item))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .Take(100)
                .ToList();
        }

        return [];
    }

    private static int? ReadMessageVisualBlocksVersion(JsonElement message)
    {
        var version = ExtractDouble(message, "visualBlocksVersion");
        if (version <= 0)
        {
            version = ExtractDouble(message, "visual_blocks_version");
        }

        return version > 0 ? (int)version : null;
    }

    private static List<VisualBlockRecord> ReadMessageVisualBlocks(JsonElement message)
    {
        if (message.ValueKind != JsonValueKind.Object)
        {
            return [];
        }

        JsonElement blocksElement;
        if (!message.TryGetProperty("visualBlocks", out blocksElement) &&
            !message.TryGetProperty("visual_blocks", out blocksElement))
        {
            return [];
        }

        if (blocksElement.ValueKind != JsonValueKind.Array)
        {
            return [];
        }

        var wrapped = $$"""{"visual_blocks":{{blocksElement.GetRawText()}}}""";
        return VisualBlockParser.ExtractFromResponse(wrapped).ToList();
    }

    private static ChatStreamStats? ReadMessageStats(JsonElement message)
    {
        if (!message.TryGetProperty("stats", out var stats) || stats.ValueKind != JsonValueKind.Object)
        {
            return null;
        }

        try
        {
            return JsonSerializer.Deserialize<ChatStreamStats>(stats.GetRawText(), CaseInsensitiveJsonOptions);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static List<HermesRawEventRecord>? ReadMessageRawEvents(JsonElement message)
    {
        if ((!message.TryGetProperty("rawEvents", out var rawEvents) &&
             !message.TryGetProperty("raw_events", out rawEvents)) ||
            rawEvents.ValueKind != JsonValueKind.Array)
        {
            return null;
        }

        try
        {
            return JsonSerializer.Deserialize<List<HermesRawEventRecord>>(rawEvents.GetRawText(), CaseInsensitiveJsonOptions);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public static async Task StreamHubConversationEventsAsync(AppSettings settings, Func<CancellationToken, Task> onChanged, CancellationToken cancellationToken)
    {
        await EnsureReachableGatewayAsync(settings, cancellationToken);
        var endpoint = ResolveHermesUri(settings, "/v1/hub/conversations/events");
        var candidates = BuildHermesAuthCandidates().ToArray();
        Exception? lastError = null;

        for (var i = 0; i < candidates.Length; i++)
        {
            try
            {
                using var request = BuildRequest(HttpMethod.Get, endpoint, candidates[i]);
                request.Headers.TryAddWithoutValidation("Accept", "text/event-stream");
                using var response = await HubEventsHttpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
                if (!response.IsSuccessStatusCode)
                {
                    var body = await ReadBoundedStringAsync(
                        response.Content,
                        MaxErrorResponseBytes,
                        cancellationToken);
                    var buffered = new BufferedHermesResponse((int)response.StatusCode, response.ReasonPhrase, body, response.Content.Headers.ContentType?.MediaType);
                    if (ShouldRetryWithBearerAuth(buffered.StatusCode, buffered.Body) && i < candidates.Length - 1)
                    {
                        continue;
                    }
                    throw new HttpRequestException($"Archivio events HTTP {buffered.StatusCode}: {ExtractHumanError(buffered.Body)}");
                }

                await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
                using var reader = new StreamReader(stream, Encoding.UTF8);
                while (!reader.EndOfStream && !cancellationToken.IsCancellationRequested)
                {
                    var line = await reader.ReadLineAsync(cancellationToken);
                    if (line is null)
                    {
                        break;
                    }
                    if (line.StartsWith("data:", StringComparison.OrdinalIgnoreCase))
                    {
                        await onChanged(cancellationToken);
                    }
                }
                return;
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex)
            {
                lastError = ex;
            }
        }

        throw lastError ?? new HttpRequestException("Archivio events non disponibile.");
    }

    public static async Task<HubNotificationsResult> LoadHubNotificationsAsync(AppSettings settings, bool unreadOnly = false)
    {
        try
        {
            await EnsureReachableGatewayAsync(settings);
            var path = unreadOnly ? "/v1/hub/notifications?unread=1" : "/v1/hub/notifications";
            var response = await SendBufferedAsync(token => BuildRequest(HttpMethod.Get, ResolveHermesUri(settings, path), token));
            if (!response.IsSuccessStatusCode)
            {
                return new HubNotificationsResult([], $"Notifiche non disponibili: HTTP {response.StatusCode} {ExtractHumanError(response.Body)}");
            }

            using var doc = JsonDocument.Parse(response.Body);
            var items = new List<HubNotificationRecord>();
            if ((doc.RootElement.TryGetProperty("items", out var array) || doc.RootElement.TryGetProperty("notifications", out array)) && array.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in array.EnumerateArray())
                {
                    var id = ExtractString(item, "id") ?? string.Empty;
                    if (string.IsNullOrWhiteSpace(id)) continue;
                    var created = ExtractDouble(item, "created_at");
                    var readAt = ExtractDouble(item, "read_at");
                    var snoozedUntil = ExtractDouble(item, "snoozed_until");
                    var read = false;
                    if (item.TryGetProperty("read", out var r) && (r.ValueKind == JsonValueKind.True || r.ValueKind == JsonValueKind.False))
                    {
                        read = r.GetBoolean();
                    }
                    if (readAt == 0 && read) readAt = DateTimeOffset.Now.ToUnixTimeSeconds();

                    items.Add(new HubNotificationRecord(
                        id,
                        ExtractString(item, "title") ?? "Hermes",
                        ExtractString(item, "message", "body", "text") ?? string.Empty,
                        ExtractString(item, "kind") ?? "agent_message",
                        ExtractString(item, "severity", "type") ?? "info",
                        ExtractString(item, "source") ?? "hermes-agent",
                        ExtractString(item, "conversation_prompt") ?? ExtractString(item, "message", "body", "text") ?? string.Empty,
                        created > 0 ? DateTimeOffset.FromUnixTimeSeconds((long)created) : DateTimeOffset.Now,
                        readAt > 0 ? DateTimeOffset.FromUnixTimeSeconds((long)readAt) : null,
                        ExtractString(item, "category") ?? ExtractString(item, "kind") ?? "Generale",
                        ExtractString(item, "priority") ?? ExtractString(item, "severity") ?? "Normale",
                        item.TryGetProperty("archived", out var archived) && archived.ValueKind == JsonValueKind.True,
                        snoozedUntil > 0 ? DateTimeOffset.FromUnixTimeSeconds((long)snoozedUntil) : null,
                        ExtractString(item, "automation_id", "cron_id") ?? string.Empty,
                        ExtractString(item, "run_id") ?? string.Empty,
                        ExtractString(item, "file_url", "url") ?? string.Empty,
                        ExtractString(item, "project_id") ?? string.Empty));
                }
            }

            var ordered = items.OrderByDescending(item => item.CreatedAt).ToArray();
            var unread = ordered.Count(item => item.ReadAt is null);
            return new HubNotificationsResult(ordered, unread == 1 ? "1 notifica non letta." : $"{unread} notifiche non lette.");
        }
        catch (Exception ex)
        {
            return new HubNotificationsResult([], $"Notifiche non disponibili: {ex.Message}");
        }
    }

    public static async Task<string> MarkHubNotificationReadAsync(AppSettings settings, string id)
    {
        await EnsureReachableGatewayAsync(settings);
        var payload = JsonSerializer.Serialize(new { read = true });
        var response = await SendBufferedAsync(token => BuildJsonRequest(HttpMethod.Patch, ResolveHermesUri(settings, $"/v1/hub/notifications/{Uri.EscapeDataString(id)}"), payload, token));
        return response.IsSuccessStatusCode
            ? "Notifica segnata come letta."
            : $"Notifica non aggiornata: HTTP {response.StatusCode} {ExtractHumanError(response.Body)}";
    }

    public static async Task<string> PatchHubNotificationAsync(AppSettings settings, string id, object patch)
    {
        await EnsureReachableGatewayAsync(settings);
        var payload = JsonSerializer.Serialize(patch);
        var response = await SendBufferedAsync(token => BuildJsonRequest(HttpMethod.Patch, ResolveHermesUri(settings, $"/v1/hub/notifications/{Uri.EscapeDataString(id)}"), payload, token));
        return response.IsSuccessStatusCode ? "Notifica aggiornata." : $"Notifica non aggiornata: HTTP {response.StatusCode} {ExtractHumanError(response.Body)}";
    }

    public static async Task<string> TryStopRunAsync(AppSettings settings, string runId, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(runId))
        {
            return "Run Hermes assente.";
        }

        await EnsureReachableGatewayAsync(settings, cancellationToken);
        var payload = JsonSerializer.Serialize(new { reason = "user_cancelled" });
        var response = await SendBufferedAsync(
            token => BuildJsonRequest(
                HttpMethod.Post,
                ResolveHermesUri(settings, $"/v1/runs/{Uri.EscapeDataString(runId)}/stop"),
                payload,
                token),
            cancellationToken: cancellationToken);

        return response.IsSuccessStatusCode || response.StatusCode == 404
            ? "Run Hermes arrestata."
            : $"Stop run fallito: HTTP {response.StatusCode} {ExtractHumanError(response.Body)}";
    }

    public static async Task<IReadOnlyList<DiagnosticCheckResult>> RunDiagnosticsAsync(AppSettings settings)
    {
        var checks = new (string Label, string Path, string Action)[]
        {
            ("Tailscale/API", "/health", "Avvia Tailscale e hermes-hub, verifica IP/porta 8642."),
            ("Health dettagliata", "/health/detailed", "Controlla log gateway."),
            ("Modelli", "/v1/models", "Controlla LM Studio/vLLM e modello caricato."),
            ("Capabilities", "/v1/capabilities", "Controlla API key e versione gateway."),
            ("Hardware", "/v1/hub/hardware", "Aggiorna Hermes Gateway/patcher e installa psutil su Linux se mancano metriche live."),
            ("Video library", "/v1/video/library", "Aggiorna Hermes Gateway alla latest release. Se il feed e' vuoto, imposta HERMES_VIDEO_LIBRARY_PATH sul server."),
            ("Memoria", "/v1/hub/memory", "Aggiorna Hermes Gateway alla latest release e riavvia hermes-hub. Memoria = preferenze/profilo Hermes Agent lato server, non RAM telefono."),
            ("Hub state", "/v1/hub/state", "Aggiorna Hermes Gateway alla latest release e riavvia hermes-hub."),
        };
        var list = new List<DiagnosticCheckResult>();
        foreach (var check in checks)
        {
            var endpoint = ResolveHermesUri(settings, check.Path);
            try
            {
                var response = await SendBufferedAsync(token => BuildRequest(HttpMethod.Get, endpoint, token));
                var ok = response.IsSuccessStatusCode;
                list.Add(new DiagnosticCheckResult(
                    check.Label,
                    endpoint,
                    ok,
                    ok ? Truncate(response.Body, 220) : $"HTTP {response.StatusCode}: {ExtractHumanError(response.Body)}",
                    check.Action));
            }
            catch (Exception ex)
            {
                list.Add(new DiagnosticCheckResult(check.Label, endpoint, false, ex.Message, check.Action));
            }
        }
        return list;
    }

    public static string HermesRoot(AppSettings settings)
    {
        var api = settings.GatewayUrl.TrimEnd('/');
        return api.EndsWith("/v1", StringComparison.OrdinalIgnoreCase)
            ? api[..^3]
            : api;
    }

    internal static string ResolveHermesUri(AppSettings settings, string path)
    {
        if (Uri.TryCreate(path, UriKind.Absolute, out _))
        {
            return path;
        }

        var normalizedPath = path.StartsWith('/') ? path : $"/{path}";
        if (normalizedPath.StartsWith("/v1/", StringComparison.OrdinalIgnoreCase) ||
            normalizedPath.Equals("/v1", StringComparison.OrdinalIgnoreCase) ||
            normalizedPath.StartsWith("/api/", StringComparison.OrdinalIgnoreCase) ||
            normalizedPath.StartsWith("/health", StringComparison.OrdinalIgnoreCase))
        {
            return $"{HermesRoot(settings)}{normalizedPath}";
        }

        return $"{settings.GatewayUrl.TrimEnd('/')}{normalizedPath}";
    }

    private static bool HasSameAuthority(string left, string right)
    {
        return Uri.TryCreate(left, UriKind.Absolute, out var leftUri) &&
               Uri.TryCreate(right, UriKind.Absolute, out var rightUri) &&
               string.Equals(leftUri.Scheme, rightUri.Scheme, StringComparison.OrdinalIgnoreCase) &&
               string.Equals(leftUri.Host, rightUri.Host, StringComparison.OrdinalIgnoreCase) &&
               leftUri.Port == rightUri.Port;
    }

    internal static bool IsTrustedGatewayUri(AppSettings settings, Uri uri)
    {
        if (HasSameAuthority(uri.ToString(), settings.GatewayUrl))
        {
            return true;
        }

        return uri.Port == 8642 && PlugAndPlayGatewayHosts.Contains(uri.Host, StringComparer.OrdinalIgnoreCase);
    }

    private static string Truncate(string value, int maxLength)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return string.Empty;
        }

        return value.Length <= maxLength ? value : value[..maxLength] + "...";
    }

    internal static IEnumerable<string?> BuildHermesAuthCandidates(bool allowCompatAuth = true)
    {
        var saved = GatewayCredentialStore.LoadSecret();
        if (!string.IsNullOrWhiteSpace(saved))
        {
            yield return saved.Trim();
        }

        if (allowCompatAuth)
        {
            yield return null;
        }
    }

    private static async Task<string> CronJobActionAsync(AppSettings settings, string id, string? action, HttpMethod method, string success)
    {
        if (string.IsNullOrWhiteSpace(id))
        {
            return "ID cron mancante.";
        }

        var safeId = Uri.EscapeDataString(id.Trim());
        var path = string.IsNullOrWhiteSpace(action) ? $"/api/jobs/{safeId}" : $"/api/jobs/{safeId}/{action}";
        try
        {
            var response = await SendBufferedAsync(token => BuildJsonRequest(method, ResolveHermesUri(settings, path), "{}", token));
            return response.IsSuccessStatusCode
                ? success
                : $"Azione cron fallita: HTTP {response.StatusCode} {ExtractHumanError(response.Body)}";
        }
        catch (Exception ex)
        {
            return $"Azione cron fallita: {ex.Message}";
        }
    }

    internal static async Task EnsureReachableGatewayAsync(
        AppSettings settings,
        CancellationToken cancellationToken = default)
    {
        var current = settings.GatewayUrl.Trim().TrimEnd('/');
        if (await CanReachGatewayAsync(current, cancellationToken))
        {
            return;
        }

        foreach (var candidate in BuildPlugAndPlayGatewayUrls(current))
        {
            if (string.Equals(candidate, current, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            if (!await CanReachGatewayAsync(candidate, cancellationToken))
            {
                continue;
            }

            settings.GatewayUrl = candidate;
            settings.InferenceEndpoint = candidate;
            settings.AdminBridgeUrl = HermesRoot(settings);
            settings.AccessMode = "Tailscale/LAN plug-and-play";
            AppSettingsStore.Save(settings);
            return;
        }
    }

    private static IEnumerable<string> BuildPlugAndPlayGatewayUrls(string current)
    {
        if (!Uri.TryCreate(current, UriKind.Absolute, out var currentUri))
        {
            yield break;
        }

        var scheme = currentUri?.Scheme?.Trim().ToLowerInvariant();
        if (string.IsNullOrWhiteSpace(scheme))
        {
            scheme = "http";
        }
        var port = currentUri?.IsDefaultPort == false ? currentUri.Port : 8642;
        var path = currentUri?.AbsolutePath?.TrimEnd('/');
        if (string.IsNullOrWhiteSpace(path) || path == "/")
        {
            path = "/v1";
        }

        var yielded = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var host in PlugAndPlayGatewayHosts)
        {
            var candidate = $"{scheme}://{host}:{port}{path}";
            if (yielded.Add(candidate))
            {
                yield return candidate;
            }
        }

        if (port != 8642 || !string.Equals(path, "/v1", StringComparison.OrdinalIgnoreCase))
        {
            foreach (var host in PlugAndPlayGatewayHosts)
            {
                var candidate = $"http://{host}:8642/v1";
                if (yielded.Add(candidate))
                {
                    yield return candidate;
                }
            }
        }
    }

    private static async Task<bool> CanReachGatewayAsync(string gatewayUrl, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(gatewayUrl))
        {
            return false;
        }

        var probeSettings = new AppSettings
        {
            GatewayUrl = gatewayUrl.Trim().TrimEnd('/'),
            StrictNativeMode = false
        };
        var healthUrl = $"{HermesRoot(probeSettings)}/health";
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TimeSpan.FromSeconds(2));
            var response = await SendBufferedAsync(
                token => BuildRequest(HttpMethod.Get, healthUrl, token),
                allowCompatAuth: true,
                cancellationToken: timeout.Token);
            return response.IsSuccessStatusCode || response.StatusCode == 401;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            return false;
        }
    }

    internal static bool ShouldRetryWithBearerAuth(int statusCode, string body)
    {
        if (statusCode != 401)
        {
            return false;
        }

        return body.Contains("invalid api key", StringComparison.OrdinalIgnoreCase) ||
               body.Contains("invalid_api_key", StringComparison.OrdinalIgnoreCase) ||
               body.Contains("invalidapikey", StringComparison.OrdinalIgnoreCase);
    }

    internal static bool IsRecoverablePreviousResponseError(string? message)
    {
        if (string.IsNullOrWhiteSpace(message))
        {
            return false;
        }

        return message.Contains("401", StringComparison.OrdinalIgnoreCase) ||
               message.Contains("invalid api key", StringComparison.OrdinalIgnoreCase) ||
               message.Contains("invalid_api_key", StringComparison.OrdinalIgnoreCase) ||
               message.Contains("invalidapikey", StringComparison.OrdinalIgnoreCase) ||
               message.Contains("API key rifiutata", StringComparison.OrdinalIgnoreCase) ||
               message.Contains("previous_response_id", StringComparison.OrdinalIgnoreCase) ||
               message.Contains("previous response", StringComparison.OrdinalIgnoreCase) ||
               message.Contains("conversation", StringComparison.OrdinalIgnoreCase) ||
               message.Contains("response not found", StringComparison.OrdinalIgnoreCase) ||
               message.Contains("not found", StringComparison.OrdinalIgnoreCase);
    }

    private static async Task<BufferedHermesResponse> SendBufferedAsync(
        Func<string?, HttpRequestMessage> requestFactory,
        bool allowCompatAuth = true,
        CancellationToken cancellationToken = default)
    {
        BufferedHermesResponse? last = null;
        var candidates = BuildHermesAuthCandidates(allowCompatAuth).ToArray();
        for (var i = 0; i < candidates.Length; i++)
        {
            using var request = requestFactory(candidates[i]);
            using var response = await HttpClient.SendAsync(
                request,
                HttpCompletionOption.ResponseHeadersRead,
                cancellationToken);
            var body = await ReadBoundedStringAsync(
                response.Content,
                MaxBufferedResponseBytes,
                cancellationToken);
            var buffered = new BufferedHermesResponse(
                (int)response.StatusCode,
                response.ReasonPhrase,
                body,
                response.Content.Headers.ContentType?.MediaType);
            last = buffered;
            if (!ShouldRetryWithBearerAuth(buffered.StatusCode, buffered.Body) || i == candidates.Length - 1)
            {
                return buffered;
            }
        }

        return last ?? new BufferedHermesResponse(0, "No Response", string.Empty, null);
    }

    private static async Task<string> ReadBoundedStringAsync(
        HttpContent content,
        int maxBytes,
        CancellationToken cancellationToken)
    {
        if (content.Headers.ContentLength is long contentLength && contentLength > maxBytes)
        {
            throw new InvalidOperationException($"Risposta gateway oltre il limite di {maxBytes / 1024} KB.");
        }

        await using var input = await content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using var buffer = new MemoryStream(Math.Min(
            maxBytes,
            (int)Math.Max(0, content.Headers.ContentLength ?? 0)));
        var chunk = new byte[16 * 1024];
        while (true)
        {
            var read = await input.ReadAsync(chunk, cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }

            if (buffer.Length + read > maxBytes)
            {
                throw new InvalidOperationException($"Risposta gateway oltre il limite di {maxBytes / 1024} KB.");
            }

            buffer.Write(chunk, 0, read);
        }

        var text = ResolveContentEncoding(content).GetString(buffer.GetBuffer(), 0, checked((int)buffer.Length));
        return text.Length > 0 && text[0] == '\uFEFF' ? text[1..] : text;
    }

    private static Encoding ResolveContentEncoding(HttpContent content)
    {
        var charset = content.Headers.ContentType?.CharSet?.Trim('"');
        if (!string.IsNullOrWhiteSpace(charset))
        {
            try
            {
                return Encoding.GetEncoding(charset);
            }
            catch (ArgumentException ex)
            {
                System.Diagnostics.Trace.WriteLine($"[GatewayService] Charset non valido '{charset}': {ex.Message}");
            }
        }

        return Encoding.UTF8;
    }

    private static HttpRequestMessage BuildRequest(HttpMethod method, string uri, string? bearerToken = null, string? sessionId = null)
    {
        var request = new HttpRequestMessage(method, uri);
        request.Headers.TryAddWithoutValidation("Accept", "application/json");
        request.Headers.TryAddWithoutValidation("User-Agent", "HermesHub-Windows");
        if (!string.IsNullOrWhiteSpace(bearerToken))
        {
            request.Headers.TryAddWithoutValidation("Authorization", $"Bearer {bearerToken}");
        }
        if (!string.IsNullOrWhiteSpace(sessionId))
        {
            request.Headers.TryAddWithoutValidation("X-Hermes-Session-Id", sessionId);
        }

        return request;
    }

    private static HttpRequestMessage BuildJsonRequest(HttpMethod method, string uri, string payload, string? bearerToken = null, string? sessionId = null)
    {
        var request = BuildRequest(method, uri, bearerToken, sessionId);
        if (method != HttpMethod.Get && method != HttpMethod.Delete)
        {
            request.Content = new StringContent(payload, Encoding.UTF8, "application/json");
        }

        return request;
    }

    private static string ExtractAssistantText(string? mediaType, string body)
    {
        if (string.IsNullOrWhiteSpace(body))
        {
            return string.Empty;
        }

        if ((mediaType?.Contains("event-stream", StringComparison.OrdinalIgnoreCase) ?? false) ||
            body.Contains("data:", StringComparison.OrdinalIgnoreCase))
        {
            var sseText = ParseSseText(body);
            if (!string.IsNullOrWhiteSpace(sseText))
            {
                return sseText;
            }
        }

        var trimmed = body.Trim();
        if (!trimmed.StartsWith('{') && !trimmed.StartsWith('['))
        {
            return trimmed;
        }

        try
        {
            using var document = JsonDocument.Parse(trimmed);
            return ExtractTextFromJson(document.RootElement);
        }
        catch
        {
            return trimmed;
        }
    }

    private static string ParseSseText(string body)
    {
        var builder = new StringBuilder();
        foreach (var rawLine in body.Split('\n'))
        {
            var line = rawLine.Trim();
            if (!line.StartsWith("data:", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            var payload = line[5..].Trim();
            if (string.IsNullOrWhiteSpace(payload) || payload == "[DONE]")
            {
                continue;
            }

            if (payload.StartsWith('{'))
            {
                try
                {
                    using var document = JsonDocument.Parse(payload);
                    var piece = ExtractTextFromJson(document.RootElement);
                    if (!string.IsNullOrWhiteSpace(piece))
                    {
                        builder.Append(piece);
                    }
                }
                catch
                {
                    builder.Append(payload);
                }
            }
            else
            {
                builder.Append(payload);
            }
        }

        return builder.ToString().Trim();
    }

    private static string ExtractTextFromJson(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.String)
        {
            return element.GetString() ?? string.Empty;
        }

        if (element.ValueKind == JsonValueKind.Object)
        {
            foreach (var key in new[] { "output_text", "text", "content", "message", "reply" })
            {
                if (element.TryGetProperty(key, out var property))
                {
                    var nested = ExtractTextFromJson(property);
                    if (!string.IsNullOrWhiteSpace(nested))
                    {
                        return nested;
                    }
                }
            }

            foreach (var key in new[] { "delta", "choice", "data" })
            {
                if (element.TryGetProperty(key, out var property))
                {
                    var nested = ExtractTextFromJson(property);
                    if (!string.IsNullOrWhiteSpace(nested))
                    {
                        return nested;
                    }
                }
            }

            if (element.TryGetProperty("choices", out var choices) && choices.ValueKind == JsonValueKind.Array)
            {
                foreach (var choice in choices.EnumerateArray())
                {
                    var nested = ExtractTextFromJson(choice);
                    if (!string.IsNullOrWhiteSpace(nested))
                    {
                        return nested;
                    }
                }
            }
        }

        if (element.ValueKind == JsonValueKind.Array)
        {
            var builder = new StringBuilder();
            foreach (var item in element.EnumerateArray())
            {
                var nested = ExtractTextFromJson(item);
                if (!string.IsNullOrWhiteSpace(nested))
                {
                    builder.Append(nested);
                }
            }

            return builder.ToString().Trim();
        }

        return string.Empty;
    }

    private static string? ExtractTaskId(string body)
    {
        try
        {
            using var document = JsonDocument.Parse(body);
            return ExtractString(document.RootElement, "id", "taskId", "job_id", "jobId")
                ?? ExtractNestedString(document.RootElement, "task", "id")
                ?? ExtractNestedString(document.RootElement, "job", "id");
        }
        catch
        {
            return null;
        }
    }

    private static CronJobRecord[] ParseCronJobs(string body)
    {
        if (string.IsNullOrWhiteSpace(body))
        {
            return [];
        }

        using var document = JsonDocument.Parse(body);
        var root = document.RootElement;
        var jobs = new List<CronJobRecord>();
        if (root.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in root.EnumerateArray())
            {
                AddCronJob(jobs, item);
            }
        }
        else if (root.ValueKind == JsonValueKind.Object)
        {
            if (TryGetArray(root, out var array, "jobs", "crons", "items", "schedules", "data"))
            {
                foreach (var item in array.EnumerateArray())
                {
                    AddCronJob(jobs, item);
                }
            }
            else if (TryGetObject(root, out var job, "job", "cron", "schedule"))
            {
                AddCronJob(jobs, job);
            }
            else if (root.TryGetProperty("id", out _))
            {
                AddCronJob(jobs, root);
            }
        }

        return jobs
            .Where(job => !string.IsNullOrWhiteSpace(job.Id) || !string.IsNullOrWhiteSpace(job.Name))
            .ToArray();
    }

    private static void AddCronJob(List<CronJobRecord> jobs, JsonElement item)
    {
        if (item.ValueKind != JsonValueKind.Object)
        {
            return;
        }

        var id = ExtractString(item, "id", "job_id", "jobId", "name") ?? "";
        var name = ExtractString(item, "name", "title", "label", "id") ?? "Cron";
        var state = ExtractString(item, "state", "status") ?? "";
        var enabled = ExtractBool(item, true, "enabled", "active");
        if (state.Equals("paused", StringComparison.OrdinalIgnoreCase) || state.Equals("disabled", StringComparison.OrdinalIgnoreCase))
        {
            enabled = false;
        }

        jobs.Add(new CronJobRecord(
            id,
            name,
            ExtractString(item, "prompt", "instructions", "description", "input") ?? "",
            ExtractSchedule(item),
            string.IsNullOrWhiteSpace(state) ? (enabled ? "attivo" : "pausa") : state,
            enabled,
            ExtractString(item, "next_run_at", "nextRunAt", "next_run", "nextRun") ?? "",
            ExtractString(item, "last_run_at", "lastRunAt", "last_run", "lastRun") ?? "",
            ExtractString(item, "last_status", "lastStatus", "last_result", "lastResult") ?? "",
            ExtractString(item, "deliver", "delivery", "target") ?? "",
            ExtractOrigin(item)));
    }

    private static string ExtractSchedule(JsonElement item)
    {
        var display = ExtractString(item, "schedule_display", "scheduleDisplay", "cron", "cron_expr", "expression");
        if (!string.IsNullOrWhiteSpace(display))
        {
            return display;
        }

        if (item.TryGetProperty("schedule", out var schedule))
        {
            if (schedule.ValueKind == JsonValueKind.String)
            {
                return schedule.GetString() ?? "";
            }

            if (schedule.ValueKind == JsonValueKind.Object)
            {
                var expr = ExtractString(schedule, "expr", "cron", "display");
                if (!string.IsNullOrWhiteSpace(expr))
                {
                    return expr;
                }

                var kind = ExtractString(schedule, "kind") ?? "";
                var minutes = ExtractLong(schedule, "minutes");
                var seconds = ExtractLong(schedule, "seconds");
                if (kind.Equals("interval", StringComparison.OrdinalIgnoreCase) && minutes > 0)
                {
                    return $"ogni {minutes} min";
                }

                if (kind.Equals("interval", StringComparison.OrdinalIgnoreCase) && seconds > 0)
                {
                    return $"ogni {seconds} sec";
                }

                return schedule.ToString();
            }
        }

        return "";
    }

    private static string ExtractOrigin(JsonElement item)
    {
        if (item.TryGetProperty("origin", out var origin))
        {
            if (origin.ValueKind == JsonValueKind.String)
            {
                return origin.GetString() ?? "";
            }

            if (origin.ValueKind == JsonValueKind.Object)
            {
                return ExtractString(origin, "client", "surface", "host", "user") ?? origin.ToString();
            }
        }

        return ExtractString(item, "source", "created_by", "createdBy") ?? "";
    }

    private static bool TryGetArray(JsonElement root, out JsonElement array, params string[] keys)
    {
        foreach (var key in keys)
        {
            if (root.TryGetProperty(key, out array) && array.ValueKind == JsonValueKind.Array)
            {
                return true;
            }
        }

        array = default;
        return false;
    }

    private static bool TryGetObject(JsonElement root, out JsonElement obj, params string[] keys)
    {
        foreach (var key in keys)
        {
            if (root.TryGetProperty(key, out obj) && obj.ValueKind == JsonValueKind.Object)
            {
                return true;
            }
        }

        obj = default;
        return false;
    }

    private static bool ExtractBool(JsonElement root, bool fallback, params string[] keys)
    {
        if (root.ValueKind != JsonValueKind.Object)
        {
            return fallback;
        }

        foreach (var key in keys)
        {
            if (!root.TryGetProperty(key, out var property))
            {
                continue;
            }

            if (property.ValueKind == JsonValueKind.True)
            {
                return true;
            }

            if (property.ValueKind == JsonValueKind.False)
            {
                return false;
            }

            if (property.ValueKind == JsonValueKind.String && bool.TryParse(property.GetString(), out var value))
            {
                return value;
            }
        }

        return fallback;
    }

    private static string? ExtractTaskStatus(string body)
    {
        try
        {
            using var document = JsonDocument.Parse(body);
            return ExtractString(document.RootElement, "status")
                ?? ExtractNestedString(document.RootElement, "task", "status")
                ?? ExtractNestedString(document.RootElement, "job", "status");
        }
        catch
        {
            return null;
        }
    }

    private static string? ExtractResponseId(string body)
    {
        try
        {
            using var document = JsonDocument.Parse(body);
            return ExtractString(document.RootElement, "id", "response_id", "responseId")
                ?? ExtractNestedString(document.RootElement, "response", "id");
        }
        catch
        {
            return null;
        }
    }

    private static string ExtractHumanError(string body)
    {
        try
        {
            using var document = JsonDocument.Parse(body);
            return ExtractString(document.RootElement, "error", "message", "detail")
                ?? body.Trim();
        }
        catch
        {
            return body.Trim();
        }
    }

    private static string? ExtractNestedString(JsonElement root, string parentKey, string childKey)
    {
        if (root.ValueKind == JsonValueKind.Object &&
            root.TryGetProperty(parentKey, out var parent) &&
            parent.ValueKind == JsonValueKind.Object)
        {
            return ExtractString(parent, childKey);
        }

        return null;
    }

    private static int ExtractInt(JsonElement root, string key)
    {
        return (int)Math.Clamp(ExtractLong(root, key), int.MinValue, int.MaxValue);
    }

    private static long ExtractLong(JsonElement root, string key)
    {
        if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty(key, out var property))
        {
            return 0;
        }

        if (property.ValueKind == JsonValueKind.Number && property.TryGetInt64(out var value))
        {
            return value;
        }

        if (property.ValueKind == JsonValueKind.String && long.TryParse(property.GetString(), out value))
        {
            return value;
        }

        return 0;
    }

    private static double ExtractDouble(JsonElement root, string key)
    {
        if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty(key, out var property))
        {
            return 0;
        }

        if (property.ValueKind == JsonValueKind.Number && property.TryGetDouble(out var value))
        {
            return double.IsFinite(value) ? value : 0;
        }

        if (property.ValueKind == JsonValueKind.String &&
            double.TryParse(property.GetString(), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out value))
        {
            return double.IsFinite(value) ? value : 0;
        }

        return 0;
    }

    private static double? ExtractNullableDouble(JsonElement root, string key)
    {
        if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty(key, out var property) || property.ValueKind == JsonValueKind.Null)
        {
            return null;
        }

        var value = ExtractDouble(root, key);
        return double.IsFinite(value) ? value : null;
    }

    private static string? ExtractString(JsonElement root, params string[] keys)
    {
        if (root.ValueKind != JsonValueKind.Object)
        {
            return null;
        }

        foreach (var key in keys)
        {
            if (!root.TryGetProperty(key, out var property))
            {
                continue;
            }

            if (property.ValueKind == JsonValueKind.String)
            {
                var value = property.GetString();
                if (!string.IsNullOrWhiteSpace(value))
                {
                    return value;
                }
            }

            var nested = ExtractTextFromJson(property);
            if (!string.IsNullOrWhiteSpace(nested))
            {
                return nested;
            }
        }

        return null;
    }
}
