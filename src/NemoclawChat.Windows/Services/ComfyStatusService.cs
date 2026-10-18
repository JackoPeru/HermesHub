using System.Text.Json;

namespace NemoclawChat_Windows.Services;

public sealed record ComfyStatusRecord
{
    public bool Reachable { get; init; }
    public string State { get; init; } = string.Empty;
    public string Desired { get; init; } = string.Empty;
    public string JobId { get; init; } = string.Empty;
    public float? Progress { get; init; }
    public int Queue { get; init; }
    public string Preset { get; init; } = string.Empty;
    public string Model { get; init; } = string.Empty;
    public string Error { get; init; } = string.Empty;
    public string Hint { get; init; } = string.Empty;
    public bool LlmLoaded { get; init; }
    public int? ComfyQueue { get; init; }
    public string ComfyError { get; init; } = string.Empty;
    public bool ComfyDirect { get; init; }
    public string JobShort => string.IsNullOrWhiteSpace(JobId) ? "—" : JobId.Length <= 8 ? JobId : JobId[..8];
    public string ProgressLabel => Progress.HasValue ? $"{Math.Round(Progress.Value * 100)}%" : "...";
    public string QueueLabel => Queue > 0 ? $"{Queue} in attesa" : "vuota";
    public string StateLabel => ComfyStatusService.StateLabel(State);
    public string Subtitle => ComfyStatusService.Subtitle(this);
}

/// <summary>
/// Stato Comfy/GPU via gpu-manager GET /status + ComfyUI diretto
/// (stesso contratto del client Android): poll manager, prompt/history
/// diretti solo quando ha senso (MEDIA_*, ERROR, DIRECT).
/// </summary>
public static class ComfyStatusService
{
    public static async Task<ComfyStatusRecord> LoadAsync(AppSettings settings, CancellationToken cancellationToken = default)
    {
        (int StatusCode, string Body) res;
        try
        {
            res = await GatewayService.SendManagerRequestAsync(settings, HttpMethod.Get, "/status", null, cancellationToken);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            return new ComfyStatusRecord { Reachable = false, Error = $"Manager non raggiungibile ({Trim(ex.Message, 120)})" };
        }

        if (res.StatusCode is 401 or 403)
        {
            return new ComfyStatusRecord { Reachable = false, Error = $"Chiave rifiutata dal manager (HTTP {res.StatusCode})" };
        }
        if (res.StatusCode == 0)
        {
            return new ComfyStatusRecord { Reachable = false, Error = $"Manager non raggiungibile ({Trim(res.Body, 120)})" };
        }
        if (res.StatusCode is < 200 or > 299)
        {
            return new ComfyStatusRecord { Reachable = false, Error = $"Manager HTTP {res.StatusCode}: {Trim(res.Body, 160)}" };
        }

        JsonDocument doc;
        try
        {
            doc = JsonDocument.Parse(res.Body);
        }
        catch (JsonException)
        {
            return new ComfyStatusRecord { Reachable = false, Error = "Risposta manager illeggibile" };
        }

        using (doc)
        {
            var root = doc.RootElement;
            var state = ReadString(root, "current_state");
            var record = new ComfyStatusRecord
            {
                Reachable = true,
                State = state,
                Desired = ReadString(root, "desired_mode"),
                JobId = ReadString(root, "current_job"),
                Progress = ReadProgress(root),
                Queue = ReadInt(root, "queue_length"),
                Preset = ReadString(root, "active_preset"),
                Model = ReadString(root, "active_media_model"),
                Error = ReadString(root, "last_error"),
                Hint = ReadString(root, "hint"),
                LlmLoaded = ReadBool(root, "llm_loaded")
            };

            var needsDirect = state.StartsWith("MEDIA_", StringComparison.Ordinal) ||
                              string.Equals(state, "ERROR", StringComparison.OrdinalIgnoreCase) ||
                              string.Equals(state, "DIRECT", StringComparison.OrdinalIgnoreCase);
            if (!needsDirect)
            {
                return record;
            }

            var (queue, directOk) = await LoadDirectQueueAsync(settings, cancellationToken);
            var comfyError = await LoadDirectErrorsAsync(settings, cancellationToken);
            return record with { ComfyQueue = queue, ComfyDirect = directOk, ComfyError = comfyError ?? string.Empty };
        }
    }

    public static string StateLabel(string state) => state switch
    {
        "MEDIA_BUSY" => "Sta generando",
        "MEDIA_READY" => "Comfy pronto",
        "MEDIA_STARTING" => "Avvio Comfy…",
        "MEDIA_STOPPING" => "Arresto Comfy…",
        "LLM_READY" => "LLM attivo (chat)",
        "LLM_LOADING" => "Caricamento LLM…",
        "LLM_UNLOADING" => "Scaricamento LLM…",
        "GPU_FREE" => "GPU libere",
        "DIRECT" => "Comfy diretto",
        "BOOT" => "Avvio…",
        "ERROR" => "Errore",
        "" => "Sconosciuto",
        _ => state
    };

    public static string Subtitle(ComfyStatusRecord status)
    {
        if (!status.Reachable)
        {
            return "Non raggiungibile";
        }
        if (string.Equals(status.State, "MEDIA_BUSY", StringComparison.OrdinalIgnoreCase))
        {
            var what = !string.IsNullOrWhiteSpace(status.Preset) ? status.Preset
                : !string.IsNullOrWhiteSpace(status.Model) ? status.Model : "lavoro";
            return $"Sta generando · {status.ProgressLabel} · {what}";
        }
        if (string.Equals(status.State, "LLM_READY", StringComparison.OrdinalIgnoreCase))
        {
            var mode = string.IsNullOrWhiteSpace(status.Desired) ? "AUTO" : status.Desired;
            return $"LLM attivo · {mode}";
        }
        if (string.IsNullOrWhiteSpace(status.State))
        {
            return "Stato sconosciuto";
        }
        return status.Queue > 0 ? $"{status.State} · coda {status.Queue}" : status.State;
    }

    private static async Task<(int? Queue, bool DirectOk)> LoadDirectQueueAsync(AppSettings settings, CancellationToken ct)
    {
        try
        {
            var (code, body) = await GatewayService.SendComfyDirectRequestAsync(settings, "/prompt", ct);
            if (code is < 200 or > 299)
            {
                return (null, false);
            }
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.TryGetProperty("exec_info", out var exec) && exec.ValueKind == JsonValueKind.Object &&
                exec.TryGetProperty("queue_remaining", out var remaining) && remaining.TryGetInt32(out var n) && n >= 0)
            {
                return (n, true);
            }
            return (null, true);
        }
        catch
        {
            return (null, false);
        }
    }

    private static async Task<string?> LoadDirectErrorsAsync(AppSettings settings, CancellationToken ct)
    {
        string body;
        try
        {
            var (code, responseBody) = await GatewayService.SendComfyDirectRequestAsync(settings, "/history?max_items=10", ct);
            if (code is < 200 or > 299)
            {
                return null;
            }
            body = responseBody;
        }
        catch
        {
            return null;
        }

        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.ValueKind != JsonValueKind.Object)
            {
                return null;
            }
            var errors = new List<string>();
            foreach (var entry in doc.RootElement.EnumerateObject())
            {
                if (entry.Value.ValueKind != JsonValueKind.Object ||
                    !entry.Value.TryGetProperty("status", out var status) || status.ValueKind != JsonValueKind.Object)
                {
                    continue;
                }
                var statusStr = status.TryGetProperty("status_str", out var s) && s.ValueKind == JsonValueKind.String
                    ? s.GetString() ?? string.Empty : string.Empty;
                if (!string.Equals(statusStr, "error", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }
                var text = string.Empty;
                if (status.TryGetProperty("messages", out var messages) && messages.ValueKind == JsonValueKind.Array)
                {
                    var parts = new List<string>();
                    foreach (var m in messages.EnumerateArray())
                    {
                        if (m.ValueKind == JsonValueKind.String)
                        {
                            parts.Add(m.GetString() ?? string.Empty);
                        }
                        else if (m.ValueKind == JsonValueKind.Array)
                        {
                            var first = m.EnumerateArray().Select(e => e.ValueKind == JsonValueKind.String ? e.GetString() ?? string.Empty : e.ToString()).FirstOrDefault();
                            parts.Add(first ?? m.ToString());
                        }
                        else
                        {
                            parts.Add(m.ToString());
                        }
                    }
                    text = string.Join(" · ", parts.Where(p => !string.IsNullOrWhiteSpace(p)));
                }
                else
                {
                    text = status.TryGetProperty("message", out var msg) && msg.ValueKind == JsonValueKind.String ? msg.GetString() ?? string.Empty : string.Empty;
                }
                if (!string.IsNullOrWhiteSpace(text))
                {
                    errors.Add(text.Trim());
                }
            }
            if (errors.Count == 0)
            {
                return null;
            }
            var joined = string.Join("\n\n", errors.TakeLast(3));
            return joined.Length <= 600 ? joined : joined[..600];
        }
        catch
        {
            return null;
        }
    }

    private static string ReadString(JsonElement root, string name) =>
        root.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? string.Empty : string.Empty;

    private static int ReadInt(JsonElement root, string name) =>
        root.TryGetProperty(name, out var v) && v.TryGetInt32(out var n) ? n : 0;

    private static bool ReadBool(JsonElement root, string name) =>
        root.TryGetProperty(name, out var v) && v.ValueKind is JsonValueKind.True or JsonValueKind.False && v.GetBoolean();

    private static float? ReadProgress(JsonElement root)
    {
        if (root.TryGetProperty("media_progress", out var v) && v.TryGetDouble(out var d) && !double.IsNaN(d))
        {
            return (float)Math.Clamp(d, 0.0, 1.0);
        }

        return null;
    }

    private static string Trim(string value, int max) =>
        string.IsNullOrEmpty(value) ? string.Empty : value.Length <= max ? value : value[..max];
}
