using System.Net.Http;
using System.Diagnostics;
using System.Text.Json;
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Xml.Linq;
using Windows.ApplicationModel;
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
    private const int MaxReleaseMetadataBytes = 2 * 1024 * 1024;
    private const string TrustedAssetHost = "objects.githubusercontent.com";
    private const string TrustedReleaseHost = "github.com";
    private static readonly SemaphoreSlim DownloadLock = new(1, 1);
    private static readonly string[] SupportedAssetExtensions = [".msix", ".appx", ".appinstaller", ".exe", ".zip"];

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
        using var request = new HttpRequestMessage(
            HttpMethod.Get,
            $"https://api.github.com/repos/{RepositoryOwner}/{RepositoryName}/releases/latest");
        request.Headers.UserAgent.ParseAdd("HermesHub-Windows");
        request.Headers.Accept.ParseAdd("application/vnd.github+json");

        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
            using var response = await HttpClient.SendAsync(
                request,
                HttpCompletionOption.ResponseHeadersRead,
                timeout.Token);
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

            using var document = await ReadBoundedJsonAsync(
                response.Content,
                MaxReleaseMetadataBytes,
                timeout.Token);
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
            var safeAssetName = SanitizeFileName(assetName);
            var exactPath = safeAssetName is null ? null : Path.Combine(updatesDirectory, safeAssetName);
            if (exactPath is not null && IsUsableDownloadedAsset(exactPath))
            {
                return new FileInfo(exactPath);
            }
        }

        return new DirectoryInfo(updatesDirectory)
            .GetFiles()
            .Where(file => file.Length > 0 && SupportedAssetExtensions.Contains(file.Extension, StringComparer.OrdinalIgnoreCase))
            .Where(file => AssetMatchesCurrentArchitecture(file.Name))
            .FirstOrDefault(file => AssetNameMatchesVersion(file.Name, normalizedVersion));
    }

    public static async Task<string?> DownloadAssetAsync(
        string assetUrl,
        string version,
        string? assetName,
        IProgress<UpdateDownloadProgress>? progress,
        CancellationToken cancellationToken = default)
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
        var partialPath = $"{destinationPath}.{Guid.NewGuid():N}.partial";
        var canonicalUpdates = Path.GetFullPath(updatesDirectory);
        var canonicalDest = Path.GetFullPath(destinationPath);
        if (!canonicalDest.StartsWith(canonicalUpdates + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) &&
            !canonicalDest.Equals(canonicalUpdates, StringComparison.OrdinalIgnoreCase))
        {
            progress?.Report(new UpdateDownloadProgress(null, "Download annullato.", "Path asset fuori dalla directory updates."));
            return null;
        }

        await DownloadLock.WaitAsync(cancellationToken);
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, assetUrl);
            request.Headers.UserAgent.ParseAdd("HermesHub-Windows");
            request.Headers.Accept.ParseAdd("application/octet-stream");
            using var response = await HttpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                return null;
            }
            if (response.RequestMessage?.RequestUri is not { } finalUri || !IsTrustedAssetUrl(finalUri.ToString()))
            {
                progress?.Report(new UpdateDownloadProgress(null, "Download annullato.", "Redirect finale fuori dagli host GitHub consentiti."));
                return null;
            }

            var total = response.Content.Headers.ContentLength;
            if (total is long advertised && advertised > MaxAssetBytes)
            {
                progress?.Report(new UpdateDownloadProgress(null, "Download annullato.", $"Asset troppo grande ({ToReadableSize(advertised)})."));
                return null;
            }

            await using var input = await response.Content.ReadAsStreamAsync(cancellationToken);
            await using var output = new FileStream(
                partialPath,
                FileMode.CreateNew,
                FileAccess.Write,
                FileShare.None,
                81920,
                FileOptions.Asynchronous | FileOptions.WriteThrough);
            var buffer = new byte[81920];
            long readTotal = 0;

            while (true)
            {
                var read = await input.ReadAsync(buffer, cancellationToken);
                if (read <= 0)
                {
                    break;
                }

                await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
                readTotal += read;
                if (readTotal > MaxAssetBytes)
                {
                    progress?.Report(new UpdateDownloadProgress(null, "Download annullato.", $"Asset oltre {ToReadableSize(MaxAssetBytes)}, interrotto."));
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

            await output.FlushAsync(cancellationToken);
            output.Flush(flushToDisk: true);
            if (total is long expected && readTotal != expected)
            {
                progress?.Report(new UpdateDownloadProgress(null, "Download incompleto.", $"Ricevuti {readTotal} byte su {expected}."));
                return null;
            }

            output.Close();
            if (!ValidateDownloadedAsset(partialPath, version, out var validationError))
            {
                progress?.Report(new UpdateDownloadProgress(null, "Download non valido.", validationError));
                return null;
            }

            File.Move(partialPath, destinationPath, overwrite: true);

            progress?.Report(new UpdateDownloadProgress(100d, "Download completato.", ToReadableSize(readTotal)));
            return destinationPath;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            progress?.Report(new UpdateDownloadProgress(null, "Download annullato.", string.Empty));
            return null;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Trace.WriteLine($"[AppUpdate] download failed: {ex}");
            return null;
        }
        finally
        {
            try { File.Delete(partialPath); }
            catch (Exception ex) { System.Diagnostics.Trace.WriteLine($"[AppUpdateService] Partial cleanup failed: {ex.Message}"); }
            DownloadLock.Release();
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
        if (IsMsixPackage(path))
        {
            if (!ValidateDownloadedAsset(path, expectedVersion: null, out _))
            {
                return false;
            }
            return LaunchMsixInstallScript(path);
        }

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

    private static bool IsMsixPackage(string path)
    {
        var extension = Path.GetExtension(path);
        return extension.Equals(".msix", StringComparison.OrdinalIgnoreCase) ||
               extension.Equals(".appx", StringComparison.OrdinalIgnoreCase);
    }

    private static bool LaunchMsixInstallScript(string path)
    {
        try
        {
            var fullPath = Path.GetFullPath(path);
            if (!File.Exists(fullPath))
            {
                return false;
            }

            var aumid = GetCurrentAppUserModelId();
            var escapedAumid = string.IsNullOrWhiteSpace(aumid) ? string.Empty : EscapePowerShellSingleQuoted(aumid);
            var currentPid = Environment.ProcessId;
            var currentProcessName = EscapePowerShellSingleQuoted(Process.GetCurrentProcess().ProcessName);
            var updatesDirectory = GetUpdatesDirectoryPath();
            Directory.CreateDirectory(updatesDirectory);
            var scriptPath = Path.Combine(updatesDirectory, "install-msix-update.ps1");
            var logPath = Path.Combine(updatesDirectory, "install-msix-update.log");
            var relaunch = string.IsNullOrWhiteSpace(escapedAumid)
                ? string.Empty
                : "`nStart-Sleep -Seconds 2`n" +
                  "for ($i = 1; $i -le 20; $i++) {`n" +
                  "  Write-Log \"Relaunch attempt $i\"`n" +
                  $"  try {{ Start-Process explorer.exe 'shell:AppsFolder\\{escapedAumid}'; break }} catch {{ Write-Log (\"Relaunch error: \" + $_.Exception.Message); Start-Sleep -Seconds 1 }}`n" +
                  "}`n";
            var script =
                "$ErrorActionPreference = 'Stop'`n" +
                $"$logPath = '{EscapePowerShellSingleQuoted(logPath)}'`n" +
                $"$packagePath = '{EscapePowerShellSingleQuoted(fullPath)}'`n" +
                $"$targetPid = {currentPid}`n" +
                $"$targetProcessName = '{currentProcessName}'`n" +
                "function Write-Log([string]$message) { Add-Content -LiteralPath $logPath -Value (\"$(Get-Date -Format o) \" + $message) }`n" +
                "\"Started $(Get-Date -Format o)\" | Set-Content -LiteralPath $logPath`n" +
                "Write-Log \"Package: $packagePath\"`n" +
                "try {`n" +
                "  if ($targetPid -gt 0) {`n" +
                "    $p = Get-Process -Id $targetPid -ErrorAction SilentlyContinue`n" +
                "    if ($p) { Write-Log \"Waiting for app PID $targetPid\"; Wait-Process -Id $targetPid -Timeout 90 -ErrorAction SilentlyContinue }`n" +
                "  }`n" +
                "  for ($i = 1; $i -le 30; $i++) {`n" +
                "    $running = Get-Process -Name $targetProcessName -ErrorAction SilentlyContinue`n" +
                "    if (-not $running) { break }`n" +
                "    Write-Log \"Still running: $targetProcessName ($i)\"`n" +
                "    Start-Sleep -Seconds 1`n" +
                "  }`n" +
                "  try { Unblock-File -LiteralPath $packagePath -ErrorAction SilentlyContinue } catch { Write-Log (\"Unblock warning: \" + $_.Exception.Message) }`n" +
                "  $installed = $false`n" +
                "  for ($attempt = 1; $attempt -le 3 -and -not $installed; $attempt++) {`n" +
                "    try {`n" +
                "      Write-Log \"Install attempt $attempt\"`n" +
                "      Add-AppxPackage -Path $packagePath -ForceUpdateFromAnyVersion -ForceApplicationShutdown -ErrorAction Stop`n" +
                "      $installed = $true`n" +
                "      Write-Log \"Installed\"`n" +
                "    } catch {`n" +
                "      Write-Log (\"Install attempt $attempt failed: \" + $_.Exception.Message)`n" +
                "      Start-Sleep -Seconds 4`n" +
                "    }`n" +
                "  }`n" +
                "  if (-not $installed) {`n" +
                "    Write-Log \"Installazione MSIX fallita. Nessun fallback verso App Installer UI per evitare blocchi Smart App Control.\"`n" +
                "    exit 3`n" +
                "  }`n" +
                relaunch +
                "`n} catch {`n" +
                "  Write-Log (\"ERROR \" + $_.Exception.Message)`n" +
                "  throw`n" +
                "}`n";
            script = script.Replace("`n", Environment.NewLine, StringComparison.Ordinal);
            File.WriteAllText(scriptPath, script);

            var startInfo = new ProcessStartInfo
            {
                FileName = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.System),
                    "WindowsPowerShell",
                    "v1.0",
                    "powershell.exe"),
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
                WorkingDirectory = updatesDirectory
            };
            startInfo.ArgumentList.Add("-NoProfile");
            startInfo.ArgumentList.Add("-ExecutionPolicy");
            startInfo.ArgumentList.Add("Bypass");
            startInfo.ArgumentList.Add("-File");
            startInfo.ArgumentList.Add(scriptPath);

            return Process.Start(startInfo) is not null;
        }
        catch
        {
            return false;
        }
    }

    public static string GetUpdatesDirectoryDisplayPath()
    {
        return GetUpdatesDirectoryPath();
    }

    public static string GetUpdateInstallLogDisplayPath()
    {
        return Path.Combine(GetUpdatesDirectoryPath(), "install-msix-update.log");
    }

    private static string? GetCurrentAppUserModelId()
    {
        try
        {
            return $"{Package.Current.Id.FamilyName}!App";
        }
        catch
        {
            return null;
        }
    }

    private static string EscapePowerShellSingleQuoted(string value)
    {
        return value.Replace("'", "''", StringComparison.Ordinal);
    }

    private static string GetUpdatesDirectoryPath()
    {
        var localAppData = GetPhysicalLocalAppDataPath();
        return Path.Combine(localAppData, "ChatClaw", "updates");
    }

    private static string GetPhysicalLocalAppDataPath()
    {
        try
        {
            return Path.Combine(ApplicationData.Current.LocalCacheFolder.Path, "Local");
        }
        catch
        {
            return Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        }
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
            .Where(asset => !string.IsNullOrWhiteSpace(asset.Name) && AssetMatchesCurrentArchitecture(asset.Name))
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

    private static bool IsUsableDownloadedAsset(string path)
    {
        if (!File.Exists(path))
        {
            return false;
        }

        var info = new FileInfo(path);
        return info.Length > 0 &&
               SupportedAssetExtensions.Contains(info.Extension, StringComparer.OrdinalIgnoreCase) &&
               AssetMatchesCurrentArchitecture(info.Name) &&
               ValidateDownloadedAsset(path, expectedVersion: null, out _);
    }

    private static bool AssetMatchesCurrentArchitecture(string assetName)
    {
        var lower = assetName.ToLowerInvariant();
        var mentionsX64 = lower.Contains("x64");
        var mentionsX86 = lower.Contains("x86");
        var mentionsArm64 = lower.Contains("arm64");
        if (!mentionsX64 && !mentionsX86 && !mentionsArm64)
        {
            return true;
        }

        return RuntimeInformation.ProcessArchitecture switch
        {
            Architecture.X64 => mentionsX64,
            Architecture.X86 => mentionsX86,
            Architecture.Arm64 => mentionsArm64,
            _ => false
        };
    }

    private static bool ValidateDownloadedAsset(string path, string? expectedVersion, out string error)
    {
        error = string.Empty;
        var info = new FileInfo(path);
        if (!info.Exists || info.Length <= 0 || info.Length > MaxAssetBytes)
        {
            error = "File update vuoto o oltre il limite consentito.";
            return false;
        }

        if (!IsMsixPackage(path))
        {
            return SupportedAssetExtensions.Contains(info.Extension, StringComparer.OrdinalIgnoreCase);
        }

        try
        {
            using var archive = ZipFile.OpenRead(path);
            var manifestEntry = archive.GetEntry("AppxManifest.xml");
            if (manifestEntry is null)
            {
                error = "AppxManifest.xml assente nel pacchetto.";
                return false;
            }

            using var manifestStream = manifestEntry.Open();
            var document = XDocument.Load(manifestStream, LoadOptions.None);
            var identity = document.Descendants().FirstOrDefault(element => element.Name.LocalName == "Identity");
            var publisher = identity?.Attribute("Publisher")?.Value;
            var packageVersion = identity?.Attribute("Version")?.Value;
            var architecture = identity?.Attribute("ProcessorArchitecture")?.Value;
            var expectedPublisher = GetCurrentPublisher();
            if (!string.IsNullOrWhiteSpace(expectedPublisher) &&
                !string.Equals(publisher, expectedPublisher, StringComparison.OrdinalIgnoreCase))
            {
                error = $"Publisher MSIX inatteso: {publisher ?? "assente"}.";
                return false;
            }

            if (!string.IsNullOrWhiteSpace(architecture) &&
                !architecture.Equals("neutral", StringComparison.OrdinalIgnoreCase) &&
                !ArchitectureMatches(architecture))
            {
                error = $"Architettura MSIX non compatibile: {architecture}.";
                return false;
            }

            if (!string.IsNullOrWhiteSpace(expectedVersion))
            {
                if (!Version.TryParse(packageVersion, out var manifestVersion) ||
                    !Version.TryParse(NormalizeVersion(expectedVersion), out var requestedVersion))
                {
                    error = "Versione MSIX assente o non valida.";
                    return false;
                }
                if (manifestVersion.Major != requestedVersion.Major ||
                    manifestVersion.Minor != requestedVersion.Minor ||
                    manifestVersion.Build != requestedVersion.Build)
                {
                    error = $"Versione MSIX {manifestVersion} diversa dalla release {requestedVersion}.";
                    return false;
                }
            }

            return true;
        }
        catch (Exception ex) when (ex is InvalidDataException or IOException or System.Xml.XmlException)
        {
            error = $"Pacchetto MSIX non leggibile: {ex.Message}";
            return false;
        }
    }

    private static async Task<JsonDocument> ReadBoundedJsonAsync(
        HttpContent content,
        int maxBytes,
        CancellationToken cancellationToken)
    {
        if (content.Headers.ContentLength is long contentLength && contentLength > maxBytes)
        {
            throw new InvalidDataException($"Metadati release oltre il limite di {maxBytes / 1024} KB.");
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
                throw new InvalidDataException($"Metadati release oltre il limite di {maxBytes / 1024} KB.");
            }

            await buffer.WriteAsync(chunk.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
        }

        return JsonDocument.Parse(buffer.ToArray());
    }

    private static string GetCurrentPublisher()
    {
        try { return Package.Current.Id.Publisher; }
        catch (Exception ex)
        {
            System.Diagnostics.Trace.WriteLine($"[AppUpdateService] Package publisher lookup failed: {ex.Message}");
            return "CN=AppPublisher";
        }
    }

    private static bool ArchitectureMatches(string architecture) => RuntimeInformation.ProcessArchitecture switch
    {
        Architecture.X64 => architecture.Equals("x64", StringComparison.OrdinalIgnoreCase),
        Architecture.X86 => architecture.Equals("x86", StringComparison.OrdinalIgnoreCase),
        Architecture.Arm64 => architecture.Equals("arm64", StringComparison.OrdinalIgnoreCase),
        _ => false
    };

    private static bool AssetNameMatchesVersion(string assetName, string version)
    {
        var escaped = System.Text.RegularExpressions.Regex.Escape(version);
        return System.Text.RegularExpressions.Regex.IsMatch(
            assetName,
            $@"(?<!\d){escaped}(?:\.0)?(?!\d)",
            System.Text.RegularExpressions.RegexOptions.IgnoreCase |
            System.Text.RegularExpressions.RegexOptions.CultureInvariant);
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
