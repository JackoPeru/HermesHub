using System.Text.Json;

namespace NemoclawChat_Windows.Services;

public sealed class LocalImageRecord
{
    public string Id { get; init; } = string.Empty;
    public string FileName { get; init; } = string.Empty;
    public string Title { get; init; } = string.Empty;
    public string MediaUrl { get; init; } = string.Empty;
    public string SourcePath { get; init; } = string.Empty;
    public string Extension { get; init; } = string.Empty;
    public DateTimeOffset ModifiedAt { get; init; }
    public long SizeBytes { get; init; }
    public bool IsRemote { get; init; }
    public string SizeLabel => $"Peso {FormatSize(SizeBytes)}";
    public string ModifiedLabel => $"Aggiornato {ModifiedAt.LocalDateTime:g}";

    private static string FormatSize(long sizeBytes)
    {
        double value = sizeBytes;
        string[] units = ["B", "KB", "MB", "GB"];
        var unitIndex = 0;
        while (value >= 1024 && unitIndex < units.Length - 1)
        {
            value /= 1024;
            unitIndex++;
        }

        return $"{value:0.#} {units[unitIndex]}";
    }
}

public sealed class ImageBrowseFolder
{
    public string Name { get; init; } = string.Empty;
    public string Path { get; init; } = string.Empty;
}

/// <summary>
/// Libreria immagini via gateway GET /v1/image/library (stesso contratto del
/// client Android): flat per percorso, byte via media proxy con ?root=.
/// Browse cartelle via manager /browse (stesso contratto Android, un livello,
/// solo immagini): chiave app GPU come su Android (fail-soft, mai throw).
/// </summary>
public static class ImageLibraryService
{
    public static string GetLibraryPath(AppSettings settings)
    {
        return settings.ImageLibraryPath.Trim();
    }

    public static async Task<(IReadOnlyList<LocalImageRecord> Items, string Status, string LibraryPath)> LoadAsync(
        AppSettings settings,
        string? path = null)
    {
        var wanted = string.IsNullOrWhiteSpace(path) ? GetLibraryPath(settings) : path.Trim();
        // "/.hermes/..." senza home = quasi sempre "~/.hermes/...": il server
        // espande ~ e ri-applica il jail (mai un bypass).
        if (wanted == "/.hermes" || wanted.StartsWith("/.hermes/", StringComparison.Ordinal))
        {
            wanted = "~" + wanted[1..];
        }
        var query = string.IsNullOrWhiteSpace(wanted)
            ? string.Empty
            : "?path=" + Uri.EscapeDataString(wanted);
        string body;
        try
        {
            body = await GatewayService.SendHermesRequestAsync(settings, HttpMethod.Get, "/v1/image/library" + query);
        }
        catch (Exception ex)
        {
            return ([], $"Errore feed immagini: {ex.Message}", wanted);
        }

        if (body.StartsWith("HTTP ", StringComparison.OrdinalIgnoreCase))
        {
            var code = ParseHttpCode(body);
            return code switch
            {
                401 => ([], "Sfoglia immagini non autorizzato (HTTP 401): verifica la chiave API.", wanted),
                403 => ([], "Cartella non consentita sul server (HTTP 403): controlla il percorso (es. ~/.hermes/hub_uploads/denudate).", wanted),
                404 => ([], "Cartella non trovata sul server.", wanted),
                _ => ([], $"Sfoglia immagini {body}.", wanted),
            };
        }
        if (string.IsNullOrWhiteSpace(body))
        {
            return ([], "Gateway non ha restituito dati immagini.", wanted);
        }

        JsonDocument doc;
        try
        {
            doc = JsonDocument.Parse(body);
        }
        catch (JsonException)
        {
            return ([], "Risposta immagini non valida dal gateway.", wanted);
        }

        using (doc)
        {
            var root = doc.RootElement;
            var libraryPath = ReadString(root, "image_library_path");
            if (string.IsNullOrWhiteSpace(libraryPath))
            {
                libraryPath = ReadString(root, "library_path");
            }
            if (string.IsNullOrWhiteSpace(libraryPath))
            {
                libraryPath = wanted;
            }
            if (!root.TryGetProperty("items", out var items) || items.ValueKind != JsonValueKind.Array)
            {
                if (!string.IsNullOrWhiteSpace(libraryPath) && !string.Equals(libraryPath, GetLibraryPath(settings), StringComparison.Ordinal))
                {
                    settings.ImageLibraryPath = libraryPath;
                    AppSettingsStore.Save(settings);
                    return ([], $"Gateway sincronizzato. Cartella monitorata: {libraryPath}", libraryPath);
                }
                return ([], "Nessuna immagine in: " + (string.IsNullOrWhiteSpace(libraryPath) ? "cartella server" : libraryPath), libraryPath);
            }

            var list = new List<LocalImageRecord>();
            foreach (var item in items.EnumerateArray())
            {
                if (item.ValueKind != JsonValueKind.Object)
                {
                    continue;
                }
                var mediaUrl = ReadString(item, "media_url");
                if (string.IsNullOrWhiteSpace(mediaUrl))
                {
                    mediaUrl = ReadString(item, "url");
                }
                var filename = ReadString(item, "filename");
                if (string.IsNullOrWhiteSpace(mediaUrl) || string.IsNullOrWhiteSpace(filename))
                {
                    continue;
                }
                var modified = ReadDouble(item, "modified_at");
                list.Add(new LocalImageRecord
                {
                    Id = ReadString(item, "id") is { Length: > 0 } id ? id : filename,
                    FileName = filename,
                    Title = ReadString(item, "title") is { Length: > 0 } title ? title : Path.GetFileNameWithoutExtension(filename),
                    MediaUrl = GatewayService.ResolveHermesUri(settings, mediaUrl),
                    SourcePath = ReadString(item, "path"),
                    Extension = Path.GetExtension(filename).TrimStart('.').ToUpperInvariant(),
                    ModifiedAt = modified > 0 ? DateTimeOffset.FromUnixTimeSeconds((long)modified) : DateTimeOffset.Now,
                    SizeBytes = ReadLong(item, "size_bytes"),
                    IsRemote = true
                });
            }

            if (list.Count == 0)
            {
                return ([], "Nessuna immagine in: " + (string.IsNullOrWhiteSpace(libraryPath) ? "cartella server" : libraryPath), libraryPath);
            }
            return (list, $"{list.Count} immagini in: {(string.IsNullOrWhiteSpace(libraryPath) ? "cartella server" : libraryPath)}", libraryPath);
        }
    }

    /// <summary>
    /// Sfoglia immagini via manager (/browse, un livello, solo immagini):
    /// stesso contratto di Android (path/parent/folders/items), byte dal
    /// proxy gateway con ?root=. In errore non solleva: la UI tiene la
    /// posizione buona (fail-soft).
    /// </summary>
    public static async Task<(string CurrentPath, string? Parent, IReadOnlyList<ImageBrowseFolder> Folders, IReadOnlyList<LocalImageRecord> Items, string Status, bool Ok)> BrowseAsync(
        AppSettings settings,
        string? path = null)
    {
        (string, string?, IReadOnlyList<ImageBrowseFolder>, IReadOnlyList<LocalImageRecord>, string, bool) Fail(string status, string current, string? parent) =>
            (current, parent, [], [], status, false);
        var wanted = string.IsNullOrWhiteSpace(path) ? GetLibraryPath(settings) : path.Trim();
        if (wanted == "/.hermes" || wanted.StartsWith("/.hermes/", StringComparison.Ordinal))
        {
            wanted = "~" + wanted[1..];
        }
        if (string.IsNullOrWhiteSpace(wanted))
        {
            wanted = "~/.hermes/hub_uploads/denudate";
        }
        var query = "?path=" + Uri.EscapeDataString(wanted);
        int code;
        string body;
        try
        {
            (code, body) = await GatewayService.SendManagerRequestAsync(settings, HttpMethod.Get, "/browse" + query);
        }
        catch (Exception ex)
        {
            return Fail($"Errore sfoglia immagini: {ex.Message}", wanted, null);
        }
        if (code == 401)
        {
            return Fail("Sfoglia immagini non autorizzato (HTTP 401): verifica la chiave API.", wanted, null);
        }
        if (code == 403)
        {
            return Fail("Cartella non consentita sul server (HTTP 403): controlla il percorso (es. ~/.hermes/hub_uploads/denudate).", wanted, null);
        }
        if (code == 404)
        {
            return Fail("Cartella non trovata sul server.", wanted, null);
        }
        if (code is < 200 or > 299)
        {
            return Fail($"Sfoglia immagini HTTP {code}.", wanted, null);
        }
        if (string.IsNullOrWhiteSpace(body))
        {
            return Fail("Il manager non ha restituito dati.", wanted, null);
        }
        JsonDocument doc;
        try
        {
            doc = JsonDocument.Parse(body);
        }
        catch (JsonException)
        {
            return Fail("Risposta immagini non valida dal manager.", wanted, null);
        }
        using (doc)
        {
            var root = doc.RootElement;
            if (root.TryGetProperty("error", out _))
            {
                return Fail("Errore manager sfoglia immagini.", wanted, null);
            }
            var current = ReadString(root, "path");
            if (string.IsNullOrWhiteSpace(current)) current = wanted;
            string? parent = ReadString(root, "parent");
            if (string.IsNullOrWhiteSpace(parent)) parent = null;
            var folders = new List<ImageBrowseFolder>();
            if (root.TryGetProperty("folders", out var folderArray) && folderArray.ValueKind == JsonValueKind.Array)
            {
                foreach (var folder in folderArray.EnumerateArray())
                {
                    if (folder.ValueKind != JsonValueKind.Object) continue;
                    var name = ReadString(folder, "name");
                    var folderPath = ReadString(folder, "path");
                    if (string.IsNullOrWhiteSpace(name) || string.IsNullOrWhiteSpace(folderPath)) continue;
                    folders.Add(new ImageBrowseFolder { Name = name, Path = folderPath });
                }
            }
            var items = new List<LocalImageRecord>();
            if (root.TryGetProperty("items", out var itemArray) && itemArray.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in itemArray.EnumerateArray())
                {
                    if (item.ValueKind != JsonValueKind.Object) continue;
                    var id = ReadString(item, "id");
                    var filename = ReadString(item, "filename");
                    var itemRoot = ReadString(item, "root");
                    if (string.IsNullOrWhiteSpace(itemRoot)) itemRoot = current;
                    if (string.IsNullOrWhiteSpace(id) || string.IsNullOrWhiteSpace(filename) || string.IsNullOrWhiteSpace(itemRoot)) continue;
                    var mime = ReadString(item, "mime_type");
                    if (string.IsNullOrWhiteSpace(mime)) mime = "image/*";
                    if (!IsBrowsableImage(filename, mime)) continue;
                    var title = ReadString(item, "title");
                    if (string.IsNullOrWhiteSpace(title)) title = Path.GetFileNameWithoutExtension(filename);
                    var mediaUrl = "/v1/media/" + Uri.EscapeDataString(id) + "?root=" + Uri.EscapeDataString(itemRoot);
                    var modified = ReadDouble(item, "modified_at");
                    items.Add(new LocalImageRecord
                    {
                        Id = id,
                        FileName = filename,
                        Title = title,
                        MediaUrl = GatewayService.ResolveHermesUri(settings, mediaUrl),
                        SourcePath = ReadString(item, "path"),
                        Extension = Path.GetExtension(filename).TrimStart('.').ToUpperInvariant(),
                        ModifiedAt = modified > 0 ? DateTimeOffset.FromUnixTimeSeconds((long)modified) : DateTimeOffset.Now,
                        SizeBytes = ReadLong(item, "size_bytes"),
                        IsRemote = true
                    });
                }
            }
            var status = items.Count == 0 && folders.Count == 0
                ? $"Cartella vuota: {current}."
                : $"{items.Count} immagini, {folders.Count} cartelle in: {current}";
            return (current, parent, folders, items, status, true);
        }
    }

    private static readonly string[] BrowsableImageExtensions =
        ["jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif"];

    private static bool IsBrowsableImage(string filename, string mimeType)
    {
        if (mimeType.Trim().StartsWith("image/", StringComparison.OrdinalIgnoreCase)) return true;
        var ext = Path.GetExtension(filename).TrimStart('.').ToLowerInvariant();
        return Array.IndexOf(BrowsableImageExtensions, ext) >= 0;
    }

    /// <summary>
    /// URI immagine con hub_token per /v1/media (BitmapImage non invia
    /// header Authorization: stesso pattern di HomePage/VideoPage).
    /// </summary>
    public static Uri ResolveImageUri(AppSettings settings, string mediaUrl)
    {
        var uri = Uri.TryCreate(mediaUrl, UriKind.Absolute, out var parsed)
            ? parsed
            : new Uri(GatewayService.HermesRoot(settings).TrimEnd('/') + mediaUrl);
        var apiKey = GatewayCredentialStore.LoadSecret();
        if (uri.AbsolutePath.StartsWith("/v1/media/", StringComparison.OrdinalIgnoreCase) &&
            GatewayService.IsTrustedGatewayUri(settings, uri) &&
            !string.IsNullOrWhiteSpace(apiKey))
        {
            var builder = new UriBuilder(uri);
            var query = builder.Query;
            query = query.Length > 1 ? string.Concat(query.AsSpan(1), "&") : string.Empty;
            query += $"hub_token={Uri.EscapeDataString(apiKey)}";
            builder.Query = query;
            return builder.Uri;
        }

        return uri;
    }

    private static int ParseHttpCode(string failure)
    {
        // Forma: "HTTP {code} ...".
        var parts = failure.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length >= 2 && int.TryParse(parts[1], out var code))
        {
            return code;
        }

        return 0;
    }

    private static string ReadString(JsonElement item, string name) =>
        item.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() ?? string.Empty : string.Empty;

    private static long ReadLong(JsonElement item, string name) =>
        item.TryGetProperty(name, out var value) && value.TryGetInt64(out var parsed) ? parsed : 0;

    private static double ReadDouble(JsonElement item, string name) =>
        item.TryGetProperty(name, out var value) && value.TryGetDouble(out var parsed) ? parsed : 0;
}
