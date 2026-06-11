using System.Net.Http;
using System.Text.Json;
using Windows.Storage;
using Windows.System;

namespace NemoclawChat_Windows.Services;

public sealed record UpdateCheckResult(
    bool HasUpdate,
    string LocalVersion,
    string? LatestVersion,
    string Message,
    string ReleaseUrl,
    string? AssetUrl,
    string? AssetName,
    string ReleaseSummary);

public sealed record UpdateDownloadProgress(double? Percent, string Status, string Detail);

public static class AppUpdateService
{
    public const string RepositoryOwner = "JackoPeru";
    public const string RepositoryName = "app-interazione-nemoclaw";
    public const string ReleasesPage = "https://github.com/JackoPeru/app-interazione-nemoclaw/releases";
    private const long MaxAssetBytes = 500L * 1024 * 1024;
    private const string TrustedAssetHost = "objects.githubusercontent.com";
    private const string TrustedReleaseHost = "github.com";

    private static readonly HttpClientHandler HttpHandler = new()
    {
        AllowAutoRedirect = true,
        MaxAutomaticRedirections = 5
    };

    // Timeout 30 min: asset > 100MB su conn lenta puo' richiedere tempo.
    private static readonly HttpClient HttpClient = new(HttpHandler)
    {
        Timeout = TimeSpan.FromMinutes(30)
    };

    public static async Task<UpdateCheckResult> CheckAsync(string localVersion)
    {
        var request = new HttpRequestMessage(
            HttpMethod.Get,
            $"https://api.github.com/repos/{RepositoryOwner}/{RepositoryName}/releases/latest");
        request.Headers.UserAgent.ParseAdd("HermesHub-Windows");
        request.Headers.Accept.ParseAdd("application/vnd.github+json");

        try
        {
            using var response = await HttpClient.SendAsync(request);
            if (!response.IsSuccessStatusCode)
            {
                return new UpdateCheckResult(
                    false,
                    localVersion,
                    null,
                    "Nessuna release GitHub trovata. Crea una release con tag vX.Y.Z e asset Windows/Android.",
                    ReleasesPage,
                    null,
                    null,
                    string.Empty);
            }

            using var stream = await response.Content.ReadAsStreamAsync();
            using var document = await JsonDocument.ParseAsync(stream);
            var root = document.RootElement;
            var tag = root.GetProperty("tag_name").GetString() ?? string.Empty;
            var latest = NormalizeVersion(tag);
            var releaseUrl = root.GetProperty("html_url").GetString() ?? ReleasesPage;
            var releaseSummary = SummarizeReleaseNotes(root.TryGetProperty("body", out var bodyElement) ? bodyElement.GetString() ?? string.Empty : string.Empty);
            var asset = FindPreferredAsset(root, [".msix", ".appinstaller", ".exe", ".zip"]);

            var hasUpdate = CompareVersions(latest, localVersion) > 0;
            var message = hasUpdate
                ? $"Aggiornamento disponibile: {localVersion} -> {latest}."
                : $"App aggiornata. Versione locale: {localVersion}, GitHub: {latest}.";

            if (hasUpdate && asset is null)
            {
                message += " Release trovata, ma manca asset Windows (.msix/.appinstaller/.exe/.zip).";
            }

            return new UpdateCheckResult(hasUpdate, localVersion, latest, message, releaseUrl, asset?.Url, asset?.Name, releaseSummary);
        }
        catch (Exception ex)
        {
            return new UpdateCheckResult(
                false,
                localVersion,
                null,
                $"Controllo update non riuscito: {ex.Message}",
                ReleasesPage,
                null,
                null,
                string.Empty);
        }
    }

    private static string SummarizeReleaseNotes(string body)
    {
        var trimmed = body.Trim();
        return trimmed.Length <= 4000 ? trimmed : trimmed[..4000].TrimEnd() + "...";
    }

    public static FileInfo? FindDownloadedAsset(string version, string? assetName)
    {
        if (string.IsNullOrWhiteSpace(version))
        {
            return null;
        }

        var updatesDirectory = GetUpdatesDirectoryPath();
        if (!Directory.Exists(updatesDirectory))
        {
            return null;
        }

        var normalizedVersion = NormalizeVersion(version);

        if (!string.IsNullOrWhiteSpace(assetName))
        {
            var exactPath = Path.Combine(updatesDirectory, assetName);
            if (File.Exists(exactPath))
            {
                return new FileInfo(exactPath);
            }
        }

        return new DirectoryInfo(updatesDirectory)
            .GetFiles()
            .FirstOrDefault(file => file.Name.Contains(normalizedVersion, StringComparison.OrdinalIgnoreCase));
    }

    public static async Task<string?> DownloadAssetAsync(
        string assetUrl,
        string version,
        string? assetName,
        IProgress<UpdateDownloadProgress>? progress)
    {
        if (!IsTrustedAssetUrl(assetUrl))
        {
            progress?.Report(new UpdateDownloadProgress(null, "Download annullato.", "URL asset non fidato (atteso github.com)."));
            return null;
        }

        var updatesDirectory = GetUpdatesDirectoryPath();
        Directory.CreateDirectory(updatesDirectory);

        var requestedFileName = !string.IsNullOrWhiteSpace(assetName)
            ? assetName
            : $"ChatClaw-{NormalizeVersion(version)}{GuessExtension(assetUrl)}";
        var sanitizedFileName = SanitizeFileName(requestedFileName);
        if (sanitizedFileName is null)
        {
            progress?.Report(new UpdateDownloadProgress(null, "Download annullato.", "Nome asset non valido."));
            return null;
        }
        var destinationPath = Path.Combine(updatesDirectory, sanitizedFileName);
        var canonicalUpdates = Path.GetFullPath(updatesDirectory);
        var canonicalDest = Path.GetFullPath(destinationPath);
        if (!canonicalDest.StartsWith(canonicalUpdates + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) &&
            !canonicalDest.Equals(canonicalUpdates, StringComparison.OrdinalIgnoreCase))
        {
            progress?.Report(new UpdateDownloadProgress(null, "Download annullato.", "Path asset fuori dalla directory updates."));
            return null;
        }

        using var request = new HttpRequestMessage(HttpMethod.Get, assetUrl);
        request.Headers.UserAgent.ParseAdd("HermesHub-Windows");
        request.Headers.Accept.ParseAdd("application/octet-stream");

        try
        {
            using var response = await HttpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead);
            if (!response.IsSuccessStatusCode)
            {
                return null;
            }

            var total = response.Content.Headers.ContentLength;
            if (total is long advertised && advertised > MaxAssetBytes)
            {
                progress?.Report(new UpdateDownloadProgress(null, "Download annullato.", $"Asset troppo grande ({ToReadableSize(advertised)})."));
                return null;
            }

            await using var input = await response.Content.ReadAsStreamAsync();
            await using var output = File.Create(destinationPath);
            var buffer = new byte[81920];
            long readTotal = 0;

            while (true)
            {
                var read = await input.ReadAsync(buffer);
                if (read <= 0)
                {
                    break;
                }

                await output.WriteAsync(buffer.AsMemory(0, read));
                readTotal += read;
                if (readTotal > MaxAssetBytes)
                {
                    progress?.Report(new UpdateDownloadProgress(null, "Download annullato.", $"Asset oltre {ToReadableSize(MaxAssetBytes)}, interrotto."));
                    output.Close();
                    if (File.Exists(destinationPath))
                    {
                        File.Delete(destinationPath);
                    }
                    return null;
                }

                if (total is > 0)
                {
                    var percent = (double)readTotal / total.Value * 100d;
                    progress?.Report(new UpdateDownloadProgress(
                        percent,
                        "Scaricamento update...",
                        $"{ToReadableSize(readTotal)} / {ToReadableSize(total.Value)}"));
                }
                else
                {
                    progress?.Report(new UpdateDownloadProgress(null, "Scaricamento update...", ToReadableSize(readTotal)));
                }
            }

            progress?.Report(new UpdateDownloadProgress(100d, "Download completato.", ToReadableSize(readTotal)));
            return destinationPath;
        }
        catch
        {
            if (File.Exists(destinationPath))
            {
                File.Delete(destinationPath);
            }

            return null;
        }
    }

    private static bool IsTrustedAssetUrl(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            return false;
        }
        if (uri.Scheme != Uri.UriSchemeHttps)
        {
            return false;
        }
        return uri.Host.Equals(TrustedAssetHost, StringComparison.OrdinalIgnoreCase) ||
               uri.Host.Equals(TrustedReleaseHost, StringComparison.OrdinalIgnoreCase) ||
               uri.Host.EndsWith(".github.com", StringComparison.OrdinalIgnoreCase) ||
               uri.Host.EndsWith(".githubusercontent.com", StringComparison.OrdinalIgnoreCase);
    }

    private static string? SanitizeFileName(string name)
    {
        if (string.IsNullOrWhiteSpace(name))
        {
            return null;
        }
        var bare = Path.GetFileName(name);
        if (string.IsNullOrWhiteSpace(bare) || !string.Equals(bare, name, StringComparison.Ordinal))
        {
            return null;
        }
        if (bare.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)
        {
            return null;
        }
        if (bare.Contains("..", StringComparison.Ordinal))
        {
            return null;
        }
        return bare;
    }

    public static async Task<bool> LaunchDownloadedAssetAsync(string path)
    {
        try
        {
            var file = await StorageFile.GetFileFromPathAsync(path);
            return await Launcher.LaunchFileAsync(file);
        }
        catch
        {
            return false;
        }
    }

    private static string GetUpdatesDirectoryPath()
    {
        var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(localAppData, "ChatClaw", "updates");
    }

    private static AssetReference? FindPreferredAsset(JsonElement root, string[] suffixes)
    {
        if (!root.TryGetProperty("assets", out var assets) || assets.ValueKind != JsonValueKind.Array)
        {
            return null;
        }

        var allAssets = assets.EnumerateArray()
            .Select(asset => new AssetReference(
                asset.GetProperty("name").GetString() ?? string.Empty,
                asset.GetProperty("browser_download_url").GetString()))
            .Where(asset => !string.IsNullOrWhiteSpace(asset.Name))
            .ToList();

        foreach (var suffix in suffixes)
        {
            var match = allAssets.FirstOrDefault(asset => asset.Name.EndsWith(suffix, StringComparison.OrdinalIgnoreCase));
            if (match is not null) return match;
        }

        return null;
    }

    private static string GuessExtension(string assetUrl)
    {
        var extension = Path.GetExtension(assetUrl);
        return string.IsNullOrWhiteSpace(extension) ? ".bin" : extension;
    }

    private static string ToReadableSize(long bytes)
    {
        if (bytes <= 0)
        {
            return "0 B";
        }

        var units = new[] { "B", "KB", "MB", "GB" };
        var value = (double)bytes;
        var unitIndex = 0;
        while (value >= 1024 && unitIndex < units.Length - 1)
        {
            value /= 1024;
            unitIndex++;
        }

        return unitIndex == 0
            ? $"{bytes} {units[unitIndex]}"
            : $"{value:F1} {units[unitIndex]}";
    }

    private static string NormalizeVersion(string version)
    {
        return version.Trim().TrimStart('v', 'V');
    }

    private static int CompareVersions(string latest, string local)
    {
        return ParseVersion(latest).CompareTo(ParseVersion(local));
    }

    private static Version ParseVersion(string version)
    {
        var clean = NormalizeVersion(version).Split('-', '+')[0];
        return Version.TryParse(clean, out var parsed) ? parsed : new Version(0, 0, 0);
    }

    private sealed record AssetReference(string Name, string? Url);
}
