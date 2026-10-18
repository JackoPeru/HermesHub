using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace NemoclawChat_Windows.Services;

public sealed record CharacterSummary(
    string Id,
    string Name,
    string Status,
    int ImageCount,
    double? IdentityScore,
    string RecommendedEngine,
    string DefaultMode);

public sealed record CharacterJob(
    string Id,
    string Kind,
    string Status,
    double Progress,
    string Detail,
    string Error);

public sealed record CharacterStatusResult(string Status, CharacterJob? ActiveJob);

public sealed record CharacterImageVerdicts(int Good, int Warn, int Bad);

public sealed record CharacterManifest(
    string Id,
    string Name,
    int ImageCount,
    int TrainingVersion,
    double? IdentityScore,
    string RecommendedEngine,
    double LoraStrength,
    IReadOnlyDictionary<string, bool> References);

public sealed record CharacterJobState(string Status, int ResultCount, string Error);

public sealed record CharacterUploadResult(int Uploaded, IReadOnlyList<string> Failed, string Message);

public static class CharactersService
{
    public const long MaxPhotoBytes = 15L * 1024 * 1024;
    private const int UploadChunkSize = 10;

    private static readonly HttpClient UploadHttpClient = new()
    {
        Timeout = TimeSpan.FromMinutes(5),
    };

    public static readonly IReadOnlyList<(string Key, string Label)> ReferenceSlots =
    [
        ("front", "Frontale"),
        ("left_three_quarter", "3/4 sinistra"),
        ("right_three_quarter", "3/4 destra"),
        ("profile_left", "Profilo sx"),
        ("profile_right", "Profilo dx"),
        ("full_body", "Figura intera"),
    ];

    public static string StatusLabel(string status) => status switch
    {
        "draft" => "Bozza",
        "analyzing" => "Analisi foto…",
        "ready_to_train" => "Pronto a creare",
        "training" => "Creazione…",
        "validating" => "Verifica…",
        "ready" => "Pronto",
        "failed" => "Errore",
        "needs_retrain" => "Da migliorare",
        "interrupted" => "Interrotto",
        _ => string.IsNullOrWhiteSpace(status) ? "Sconosciuto" : status,
    };

    public static string JobLabel(string status) => status switch
    {
        "queued" => "In coda",
        "preparing" => "Preparazione",
        "caching" => "Cache identità",
        "smoke_test" => "Prova fumo",
        "training" => "Apprendimento",
        "validating" => "Verifica",
        "ready" => "Finito",
        "failed" => "Fallito",
        "cancelled" => "Annullato",
        _ => string.IsNullOrWhiteSpace(status) ? "—" : status,
    };

    public static string TrainingPhaseLabel(CharacterJob? job)
    {
        if (job is null)
        {
            return "Pronto";
        }

        var pct = $"{(int)Math.Round(job.Progress * 100)}%";
        return job.Kind switch
        {
            "analyze" => $"Analisi foto {pct}",
            "train" => job.Status == "caching" || job.Progress < 0.26
                ? $"Cache identità {pct}"
                : $"Apprendimento identità {pct}",
            "evaluate" => $"Verifica coerenza {pct}",
            "benchmark" => $"Confronto motori {pct}",
            _ => $"{JobLabel(job.Status)} {pct}",
        };
    }

    public static string IdentityLine(CharacterSummary item)
    {
        if (item.IdentityScore.HasValue)
        {
            var pct = (int)Math.Round(item.IdentityScore.Value * 100);
            var engine = string.Equals(item.RecommendedEngine, "lora", StringComparison.OrdinalIgnoreCase)
                ? "LoRA"
                : "Reference";
            return $"Identità {pct}% · {engine}";
        }

        return $"Foto: {item.ImageCount}";
    }

    public static async Task<(IReadOnlyList<CharacterSummary> Items, string Message)> LoadCharactersAsync(
        AppSettings settings,
        CancellationToken ct = default)
    {
        try
        {
            var (code, body) = await GatewayService.SendManagerRequestAsync(
                settings, HttpMethod.Get, "/characters", null, ct).ConfigureAwait(false);
            if (code is < 200 or > 299)
            {
                return ([], $"Personaggi HTTP {code}");
            }

            using var doc = JsonDocument.Parse(body);
            if (!doc.RootElement.TryGetProperty("characters", out var array) ||
                array.ValueKind != JsonValueKind.Array)
            {
                return ([], "Nessun personaggio");
            }

            var list = new List<CharacterSummary>();
            foreach (var el in array.EnumerateArray())
            {
                var parsed = ParseSummary(el);
                if (parsed is not null)
                {
                    list.Add(parsed);
                }
            }

            return (list, list.Count == 0 ? "Nessun personaggio ancora." : $"{list.Count} personaggi.");
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            return ([], "Personaggi non disponibili");
        }
    }

    public static async Task<(string? Id, string Message)> CreateCharacterAsync(
        AppSettings settings,
        string name,
        CancellationToken ct = default)
    {
        var trimmed = (name ?? string.Empty).Trim();
        if (string.IsNullOrWhiteSpace(trimmed))
        {
            return (null, "Nome richiesto.");
        }

        try
        {
            var payload = JsonSerializer.Serialize(new { name = trimmed });
            var (code, body) = await GatewayService.SendManagerRequestAsync(
                settings, HttpMethod.Post, "/characters", payload, ct).ConfigureAwait(false);
            if (code is < 200 or > 299)
            {
                return (null, $"Creazione HTTP {code}: {Truncate(body, 160)}");
            }

            using var doc = JsonDocument.Parse(body);
            var id = GetString(doc.RootElement, "id");
            return string.IsNullOrWhiteSpace(id)
                ? (null, "Risposta illeggibile")
                : (id, id!);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            return (null, "Creazione fallita");
        }
    }

    public static async Task<CharacterManifest?> LoadManifestAsync(
        AppSettings settings,
        string id,
        CancellationToken ct = default)
    {
        try
        {
            var (code, body) = await GatewayService.SendManagerRequestAsync(
                settings, HttpMethod.Get, $"/characters/{id}", null, ct).ConfigureAwait(false);
            if (code is < 200 or > 299)
            {
                return null;
            }

            using var doc = JsonDocument.Parse(body);
            return ParseManifest(id, doc.RootElement);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            return null;
        }
    }

    public static async Task<CharacterImageVerdicts> LoadImageVerdictsAsync(
        AppSettings settings,
        string id,
        CancellationToken ct = default)
    {
        try
        {
            var (code, body) = await GatewayService.SendManagerRequestAsync(
                settings, HttpMethod.Get, $"/characters/{id}/images", null, ct).ConfigureAwait(false);
            if (code is < 200 or > 299)
            {
                return new CharacterImageVerdicts(0, 0, 0);
            }

            using var doc = JsonDocument.Parse(body);
            if (!doc.RootElement.TryGetProperty("images", out var array) ||
                array.ValueKind != JsonValueKind.Array)
            {
                return new CharacterImageVerdicts(0, 0, 0);
            }

            var good = 0;
            var warn = 0;
            var bad = 0;
            foreach (var el in array.EnumerateArray())
            {
                switch (GetString(el, "accepted"))
                {
                    case "accepted": good++; break;
                    case "warning": warn++; break;
                    case "rejected": bad++; break;
                }
            }

            return new CharacterImageVerdicts(good, warn, bad);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            return new CharacterImageVerdicts(0, 0, 0);
        }
    }

    public static async Task<CharacterStatusResult> LoadStatusAsync(
        AppSettings settings,
        string id,
        CancellationToken ct = default)
    {
        try
        {
            var (code, body) = await GatewayService.SendManagerRequestAsync(
                settings, HttpMethod.Get, $"/characters/{id}/status", null, ct).ConfigureAwait(false);
            if (code is < 200 or > 299)
            {
                return new CharacterStatusResult("draft", null);
            }

            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;
            var status = GetString(root, "status") ?? "draft";
            CharacterJob? job = null;
            if (root.TryGetProperty("active_job", out var jobEl) && jobEl.ValueKind == JsonValueKind.Object)
            {
                job = ParseJob(jobEl);
            }

            return new CharacterStatusResult(status, job);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            return new CharacterStatusResult("draft", null);
        }
    }

    public static Task<(int StatusCode, string Body)> AnalyzeAsync(AppSettings settings, string id, CancellationToken ct = default) =>
        GatewayService.SendManagerRequestAsync(settings, HttpMethod.Post, $"/characters/{id}/analyze", "{}", ct);

    public static Task<(int StatusCode, string Body)> TrainAsync(AppSettings settings, string id, CancellationToken ct = default) =>
        GatewayService.SendManagerRequestAsync(settings, HttpMethod.Post, $"/characters/{id}/train", "{}", ct);

    public static Task<(int StatusCode, string Body)> CancelAsync(AppSettings settings, string id, CancellationToken ct = default) =>
        GatewayService.SendManagerRequestAsync(settings, HttpMethod.Post, $"/characters/{id}/cancel", "{}", ct);

    public static async Task<(int StatusCode, string File, string Body)> ExportAsync(
        AppSettings settings,
        string id,
        CancellationToken ct = default)
    {
        var payload = JsonSerializer.Serialize(new { include_originals = false });
        var (code, body) = await GatewayService.SendManagerRequestAsync(
            settings, HttpMethod.Post, $"/characters/{id}/export", payload, ct).ConfigureAwait(false);
        if (code is < 200 or > 299)
        {
            return (code, string.Empty, body);
        }

        try
        {
            using var doc = JsonDocument.Parse(body);
            return (code, GetString(doc.RootElement, "file") ?? string.Empty, body);
        }
        catch (Exception)
        {
            return (code, string.Empty, body);
        }
    }

    public static async Task<(string? JobId, string Message)> GenerateAsync(
        AppSettings settings,
        string id,
        string prompt,
        string identityMode,
        int duration,
        string aspectRatio,
        long seed,
        CancellationToken ct = default)
    {
        var mode = identityMode switch
        {
            "lora" => "lora",
            "reference" => "reference",
            _ => "auto",
        };
        var safeDuration = duration is 3 or 5 or 10 ? duration : 5;
        var safeAspect = aspectRatio is "16:9" or "9:16" or "1:1" ? aspectRatio : "16:9";
        var payload = JsonSerializer.Serialize(new
        {
            prompt = (prompt ?? string.Empty).Trim(),
            identity_mode = mode,
            duration = safeDuration,
            aspect_ratio = safeAspect,
            seed,
        });

        try
        {
            var (code, body) = await GatewayService.SendManagerRequestAsync(
                settings, HttpMethod.Post, $"/characters/{id}/generate", payload, ct).ConfigureAwait(false);
            if (code is < 200 or > 299)
            {
                return (null, $"Generazione HTTP {code}: {Truncate(body, 160)}");
            }

            using var doc = JsonDocument.Parse(body);
            var jobId = GetString(doc.RootElement, "job_id");
            return string.IsNullOrWhiteSpace(jobId)
                ? (null, "Risposta illeggibile")
                : (jobId, $"Coda: {jobId}");
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            return (null, "Generazione fallita");
        }
    }

    public static async Task<CharacterJobState?> GetJobAsync(
        AppSettings settings,
        string jobId,
        CancellationToken ct = default)
    {
        try
        {
            var (code, body) = await GatewayService.SendManagerRequestAsync(
                settings, HttpMethod.Get, $"/jobs/{jobId}", null, ct).ConfigureAwait(false);
            if (code is < 200 or > 299)
            {
                return null;
            }

            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;
            var status = GetString(root, "status") ?? string.Empty;
            var count = 0;
            if (root.TryGetProperty("result_paths", out var paths) && paths.ValueKind == JsonValueKind.Array)
            {
                count = paths.GetArrayLength();
            }

            return new CharacterJobState(status, count, GetString(root, "error") ?? string.Empty);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            return null;
        }
    }

    public static async Task<(int StatusCode, string Body)> RenameAsync(
        AppSettings settings,
        string id,
        string name,
        CancellationToken ct = default)
    {
        var payload = JsonSerializer.Serialize(new { name = (name ?? string.Empty).Trim() });
        return await GatewayService.SendManagerRequestAsync(
            settings, HttpMethod.Patch, $"/characters/{id}", payload, ct).ConfigureAwait(false);
    }

    public static Task<(int StatusCode, string Body)> DeleteAsync(
        AppSettings settings,
        string id,
        CancellationToken ct = default) =>
        GatewayService.SendManagerRequestAsync(settings, HttpMethod.Delete, $"/characters/{id}", "{}", ct);

    public static async Task<CharacterUploadResult> UploadPhotosAsync(
        AppSettings settings,
        string id,
        IReadOnlyList<string> filePaths,
        CancellationToken ct = default)
    {
        var usable = (filePaths ?? []).Where(p => !string.IsNullOrWhiteSpace(p)).ToList();
        if (usable.Count == 0)
        {
            return new CharacterUploadResult(0, [], "Nessuna foto selezionata.");
        }

        var uploaded = 0;
        var failed = new List<string>();
        foreach (var chunk in usable.Chunk(UploadChunkSize))
        {
            var (count, chunkFailed, _) = await UploadSingleBatchAsync(settings, id, chunk, ct).ConfigureAwait(false);
            uploaded += count;
            failed.AddRange(chunkFailed);
            if (count < chunk.Length)
            {
                break;
            }
        }

        var message = uploaded > 0
            ? $"Caricate {uploaded} foto." + (failed.Count > 0 ? $" Scartate: {string.Join("; ", failed.Take(3))}" : string.Empty)
            : failed.Count > 0 ? failed[0] : "Nessuna foto caricata.";
        return new CharacterUploadResult(uploaded, failed, message);
    }

    private static async Task<(int Uploaded, IReadOnlyList<string> Failed, string Body)> UploadSingleBatchAsync(
        AppSettings settings,
        string id,
        IReadOnlyList<string> chunk,
        CancellationToken ct)
    {
        var staged = new List<(string Path, string FileName)>();
        var failed = new List<string>();
        foreach (var path in chunk)
        {
            try
            {
                var info = new FileInfo(path);
                var fileName = info.Exists ? info.Name : Path.GetFileName(path);
                if (!info.Exists || info.Length <= 0)
                {
                    failed.Add($"{fileName} illeggibile");
                    continue;
                }

                if (info.Length > MaxPhotoBytes)
                {
                    failed.Add($"{fileName} oltre 15MB");
                    continue;
                }

                staged.Add((info.FullName, fileName));
            }
            catch (Exception)
            {
                failed.Add($"{Path.GetFileName(path)} illeggibile");
            }
        }

        if (staged.Count == 0)
        {
            return (0, failed, string.Empty);
        }

        var secret = GatewayCredentialStore.LoadSecret();
        var url = GatewayService.ManagerBase(settings).TrimEnd('/') + $"/characters/{id}/images";
        using var multipart = new MultipartFormDataContent();
        var streams = new List<IDisposable>();
        try
        {
            foreach (var (path, fileName) in staged)
            {
                var stream = File.OpenRead(path);
                streams.Add(stream);
                var content = new StreamContent(stream);
                content.Headers.ContentType = new MediaTypeHeaderValue("image/jpeg");
                multipart.Add(content, "files", fileName);
            }

            using var request = new HttpRequestMessage(HttpMethod.Post, url);
            request.Headers.TryAddWithoutValidation("User-Agent", "HermesHub-Windows");
            if (!string.IsNullOrWhiteSpace(secret))
            {
                request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", secret.Trim());
            }

            request.Content = multipart;
            using var response = await UploadHttpClient.SendAsync(request, ct).ConfigureAwait(false);
            var body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            var code = (int)response.StatusCode;
            if (code is < 200 or > 299)
            {
                return (0, failed, body);
            }

            try
            {
                using var doc = JsonDocument.Parse(body);
                var uploadedCount = staged.Count;
                if (doc.RootElement.TryGetProperty("uploaded", out var up) && up.ValueKind == JsonValueKind.Number)
                {
                    uploadedCount = up.GetInt32();
                }

                if (doc.RootElement.TryGetProperty("failed", out var arr) && arr.ValueKind == JsonValueKind.Array)
                {
                    foreach (var el in arr.EnumerateArray())
                    {
                        var text = el.ValueKind == JsonValueKind.String ? el.GetString() : el.ToString();
                        if (!string.IsNullOrWhiteSpace(text))
                        {
                            failed.Add(text!);
                        }
                    }
                }

                return (uploadedCount, failed, body);
            }
            catch (Exception)
            {
                return (staged.Count, failed, body);
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            return (0, failed, ex.Message);
        }
        finally
        {
            foreach (var s in streams)
            {
                try { s.Dispose(); } catch { }
            }
        }
    }

    private static CharacterSummary? ParseSummary(JsonElement el)
    {
        if (el.ValueKind != JsonValueKind.Object)
        {
            return null;
        }

        var id = GetString(el, "id");
        if (string.IsNullOrWhiteSpace(id))
        {
            return null;
        }

        double? score = null;
        if (el.TryGetProperty("identity_score", out var s) && s.ValueKind == JsonValueKind.Number)
        {
            var v = s.GetDouble();
            if (!double.IsNaN(v) && double.IsFinite(v))
            {
                score = v;
            }
        }

        return new CharacterSummary(
            id!,
            GetString(el, "name") is { Length: > 0 } n ? n : "Senza nome",
            GetString(el, "status") is { Length: > 0 } st ? st : "draft",
            GetInt(el, "image_count", 0),
            score,
            GetString(el, "recommended_engine") is { Length: > 0 } re ? re : "lora",
            GetString(el, "default_mode") is { Length: > 0 } dm ? dm : "auto");
    }

    private static CharacterJob? ParseJob(JsonElement el)
    {
        var id = GetString(el, "id");
        if (string.IsNullOrWhiteSpace(id))
        {
            return null;
        }

        var progress = 0.0;
        if (el.TryGetProperty("progress", out var p) && p.ValueKind == JsonValueKind.Number)
        {
            progress = Math.Clamp(p.GetDouble(), 0.0, 1.0);
        }

        return new CharacterJob(
            id!,
            GetString(el, "kind") ?? string.Empty,
            GetString(el, "status") ?? string.Empty,
            progress,
            GetString(el, "detail") ?? string.Empty,
            GetString(el, "error") ?? string.Empty);
    }

    private static CharacterManifest ParseManifest(string fallbackId, JsonElement root)
    {
        var name = GetString(root, "name");
        if (string.IsNullOrWhiteSpace(name))
        {
            name = "Senza nome";
        }

        var imageCount = GetInt(root, "image_count", 0);
        var trainingVersion = GetInt(root, "training_version", 0);

        double? score = null;
        string engine = "lora";
        var loraStrength = 0.9;
        if (root.TryGetProperty("metrics", out var metrics) && metrics.ValueKind == JsonValueKind.Object)
        {
            if (metrics.TryGetProperty("default_identity_score", out var s) && s.ValueKind == JsonValueKind.Number)
            {
                var v = s.GetDouble();
                if (!double.IsNaN(v) && double.IsFinite(v))
                {
                    score = v;
                }
            }
        }

        if (root.TryGetProperty("identity", out var identity) && identity.ValueKind == JsonValueKind.Object)
        {
            var re = GetString(identity, "recommended_engine");
            if (!string.IsNullOrWhiteSpace(re))
            {
                engine = re!;
            }

            if (identity.TryGetProperty("lora_strength", out var ls) && ls.ValueKind == JsonValueKind.Number)
            {
                loraStrength = ls.GetDouble();
            }
        }

        JsonElement refs = default;
        var hasRefs = root.TryGetProperty("references", out refs) && refs.ValueKind == JsonValueKind.Object;
        var map = new Dictionary<string, bool>(StringComparer.Ordinal);
        foreach (var (key, _) in ReferenceSlots)
        {
            var present = false;
            if (hasRefs && refs.TryGetProperty(key, out var slot))
            {
                present = slot.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(slot.GetString());
            }

            map[key] = present;
        }

        return new CharacterManifest(fallbackId, name!, imageCount, trainingVersion, score, engine, loraStrength, map);
    }

    private static string? GetString(JsonElement el, string name)
    {
        if (el.ValueKind != JsonValueKind.Object)
        {
            return null;
        }

        return el.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString()
            : null;
    }

    private static int GetInt(JsonElement el, string name, int fallback)
    {
        if (el.ValueKind != JsonValueKind.Object)
        {
            return fallback;
        }

        return el.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out var n)
            ? n
            : fallback;
    }

    private static string Truncate(string value, int max)
    {
        if (string.IsNullOrEmpty(value))
        {
            return string.Empty;
        }

        return value.Length <= max ? value : value[..max];
    }
}
