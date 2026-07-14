using System.Text.Json;

namespace NemoclawChat_Windows.Services;

public sealed class LocalVideoRecord
{
    public string Path { get; init; } = string.Empty;
    public string PlaybackPath { get; init; } = string.Empty;
    public string CompatPath { get; init; } = string.Empty;
    public bool IsRemote { get; init; }
    public string FileName { get; init; } = string.Empty;
    public string Title { get; init; } = string.Empty;
    public string Extension { get; init; } = string.Empty;
    public DateTimeOffset ModifiedAt { get; init; }
    public long SizeBytes { get; init; }
    public string LastFeedback { get; init; } = string.Empty;
    public string LastAgentStatus { get; init; } = string.Empty;
    public string LastAgentResponse { get; init; } = string.Empty;
    public int FeedbackCount { get; init; }
    public string ModifiedLabel => $"Aggiornato {ModifiedAt.LocalDateTime:g}";
    public string SizeLabel => $"Peso {VideoLibraryService.FormatSize(SizeBytes)}";
    public string FeedbackCountLabel => FeedbackCount == 0 ? "Nessun feedback" : $"{FeedbackCount} feedback";
    public string FeedbackPreview => string.IsNullOrWhiteSpace(LastFeedback) ? "Nessun feedback inviato." : $"Feedback: {LastFeedback}";
}

public static class VideoLibraryService
{
    private static readonly string[] SupportedExtensions =
    [
        ".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi",
        ".wmv", ".flv", ".mpg", ".mpeg", ".ts", ".m2ts", ".3gp", ".ogv"
    ];

    public static string GetLibraryPath(AppSettings settings)
    {
        return settings.VideoLibraryPath.Trim();
    }

    public static string EnsureLibraryPath(AppSettings settings)
    {
        var path = GetLibraryPath(settings);
        if (string.IsNullOrWhiteSpace(path))
        {
            return string.Empty;
        }
        if (IsRemoteLinuxPath(path))
        {
            return path;
        }
        Directory.CreateDirectory(path);
        return path;
    }

    public static async Task<(IReadOnlyList<LocalVideoRecord> Items, string Status)> LoadAsync(AppSettings settings)
    {
        try
        {
            var body = await GatewayService.SendHermesRequestAsync(settings, HttpMethod.Get, "/v1/video/library");
            if (!body.StartsWith("HTTP ", StringComparison.OrdinalIgnoreCase))
            {
                var remote = ParseRemoteLibrary(settings, body);
                if (remote.Count > 0)
                {
                    return (remote, $"{remote.Count} video trovati dal gateway.");
                }

                using var doc = JsonDocument.Parse(body);
                var path = doc.RootElement.TryGetProperty("video_library_path", out var videoPath)
                    ? videoPath.GetString()
                    : doc.RootElement.TryGetProperty("library_path", out var libraryPath) ? libraryPath.GetString() : null;
                if (!string.IsNullOrWhiteSpace(path))
                {
                    settings.VideoLibraryPath = path!;
                    AppSettingsStore.Save(settings);
                    return ([], $"Gateway sincronizzato. Cartella monitorata: {path}");
                }
            }
        }
        catch
        {
        }

        var local = Scan(settings);
        return (local, local.Count == 0 ? "Nessun video locale trovato." : $"{local.Count} video locali trovati.");
    }

    public static IReadOnlyList<LocalVideoRecord> Scan(AppSettings settings)
    {
        var folder = EnsureLibraryPath(settings);
        if (string.IsNullOrWhiteSpace(folder) || !Directory.Exists(folder))
        {
            return [];
        }
        var feedbackByPath = VideoFeedbackStore.Load()
            .GroupBy(item => item.VideoPath, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(group => group.Key, group => group.OrderByDescending(item => item.UpdatedAt).First(), StringComparer.OrdinalIgnoreCase);

        return new DirectoryInfo(folder)
            .EnumerateFiles("*", SearchOption.TopDirectoryOnly)
            .Where(file => SupportedExtensions.Contains(file.Extension, StringComparer.OrdinalIgnoreCase))
            .OrderByDescending(file => file.LastWriteTimeUtc)
            .Select(file =>
            {
                feedbackByPath.TryGetValue(file.FullName, out var feedback);
                return new LocalVideoRecord
                {
                    Path = file.FullName,
                    PlaybackPath = file.FullName,
                    CompatPath = file.FullName,
                    FileName = file.Name,
                    Title = Path.GetFileNameWithoutExtension(file.Name).Replace('_', ' ').Replace('-', ' ').Trim(),
                    Extension = file.Extension.TrimStart('.').ToUpperInvariant(),
                    ModifiedAt = new DateTimeOffset(file.LastWriteTimeUtc),
                    SizeBytes = file.Length,
                    LastFeedback = feedback?.Feedback ?? string.Empty,
                    LastAgentStatus = feedback?.AgentStatus ?? string.Empty,
                    LastAgentResponse = feedback?.AgentResponse ?? string.Empty,
                    FeedbackCount = feedback?.FeedbackCount ?? 0
                };
            })
            .ToList();
    }

    private static List<LocalVideoRecord> ParseRemoteLibrary(AppSettings settings, string body)
    {
        using var doc = JsonDocument.Parse(body);
        if (doc.RootElement.TryGetProperty("video_library_path", out var videoPath) && videoPath.GetString() is { Length: > 0 } path)
        {
            settings.VideoLibraryPath = path;
            AppSettingsStore.Save(settings);
        }

        if (!doc.RootElement.TryGetProperty("items", out var items) || items.ValueKind != JsonValueKind.Array)
        {
            return [];
        }

        var list = new List<LocalVideoRecord>();
        foreach (var item in items.EnumerateArray())
        {
            var filename = ReadString(item, "filename");
            var mediaUrl = ReadString(item, "media_url");
            if (string.IsNullOrWhiteSpace(filename) || string.IsNullOrWhiteSpace(mediaUrl))
            {
                continue;
            }

            var resolved = GatewayService.ResolveHermesUri(settings, mediaUrl);
            var playbackUrl = ReadString(item, "playback_url");
            var resolvedPlayback = string.IsNullOrWhiteSpace(playbackUrl)
                ? resolved
                : GatewayService.ResolveHermesUri(settings, playbackUrl);
            var compatUrl = ReadString(item, "compat_url");
            var resolvedCompat = string.IsNullOrWhiteSpace(compatUrl)
                ? BuildCompatiblePlaybackUrl(resolved)
                : GatewayService.ResolveHermesUri(settings, compatUrl);
            if (string.IsNullOrWhiteSpace(compatUrl) &&
                resolvedPlayback.Contains("format=mp4", StringComparison.OrdinalIgnoreCase) &&
                resolvedPlayback.Contains("/v1/media/", StringComparison.OrdinalIgnoreCase))
            {
                resolvedCompat = resolvedPlayback;
                resolvedPlayback = resolved;
            }
            var modified = ReadDouble(item, "modified_at");
            var modifiedAt = modified > 0
                ? DateTimeOffset.FromUnixTimeSeconds((long)modified)
                : DateTimeOffset.Now;
            var sourcePath = ReadString(item, "path");
            list.Add(new LocalVideoRecord
            {
                Path = resolved,
                PlaybackPath = resolvedPlayback,
                CompatPath = resolvedCompat,
                IsRemote = true,
                FileName = filename,
                Title = ReadString(item, "title") is { Length: > 0 } title ? title : Path.GetFileNameWithoutExtension(filename),
                Extension = Path.GetExtension(filename).TrimStart('.').ToUpperInvariant(),
                ModifiedAt = modifiedAt,
                SizeBytes = ReadLong(item, "size_bytes"),
                LastFeedback = string.Empty,
                LastAgentStatus = string.Empty,
                LastAgentResponse = string.IsNullOrWhiteSpace(sourcePath) ? resolved : sourcePath,
                FeedbackCount = 0
            });
        }

        return list;
    }

    private static bool IsRemoteLinuxPath(string path) =>
        path.StartsWith('/') && OperatingSystem.IsWindows();

    private static string BuildCompatiblePlaybackUrl(string mediaUrl)
    {
        if (!Uri.TryCreate(mediaUrl, UriKind.Absolute, out var uri))
        {
            return mediaUrl;
        }

        if (!uri.AbsolutePath.Contains("/v1/media/", StringComparison.OrdinalIgnoreCase))
        {
            return mediaUrl;
        }

        var separator = string.IsNullOrWhiteSpace(uri.Query) ? "?" : "&";
        return mediaUrl.Contains("format=mp4", StringComparison.OrdinalIgnoreCase)
            ? mediaUrl
            : $"{mediaUrl}{separator}format=mp4";
    }

    private static string ReadString(JsonElement item, string name) =>
        item.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() ?? string.Empty : string.Empty;

    private static long ReadLong(JsonElement item, string name) =>
        item.TryGetProperty(name, out var value) && value.TryGetInt64(out var parsed) ? parsed : 0;

    private static double ReadDouble(JsonElement item, string name) =>
        item.TryGetProperty(name, out var value) && value.TryGetDouble(out var parsed) ? parsed : 0;

    public static string FormatSize(long sizeBytes)
    {
        var size = sizeBytes;
        string[] units = ["B", "KB", "MB", "GB"];
        var unitIndex = 0;
        double value = size;
        while (value >= 1024 && unitIndex < units.Length - 1)
        {
            value /= 1024;
            unitIndex++;
        }

        return $"{value:0.#} {units[unitIndex]}";
    }
}
