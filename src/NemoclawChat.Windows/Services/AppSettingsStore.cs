using System.Text.Json;

namespace NemoclawChat_Windows.Services;

public static class AppSettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private static readonly object StoreLock = new();
    private static string DataDirectoryPath
    {
        get
        {
            AppDataMigration.Run();
            return AppDataMigration.CurrentDirectoryPath;
        }
    }

    private static string SettingsPath
    {
        get
        {
            return Path.Combine(DataDirectoryPath, "settings.json");
        }
    }

    public static AppSettings Load()
    {
        lock (StoreLock)
        {
            var content = AtomicJsonFile.Read(SettingsPath);
            if (string.IsNullOrEmpty(content))
            {
                return new AppSettings();
            }

            try
            {
                var settings = JsonSerializer.Deserialize<AppSettings>(content) ?? new AppSettings();
                return NormalizePlugAndPlayDefaults(settings);
            }
            catch (JsonException)
            {
                return new AppSettings();
            }
        }
    }

    public static void Save(AppSettings settings)
    {
        lock (StoreLock)
        {
            AtomicJsonFile.Write(SettingsPath, JsonSerializer.Serialize(settings, JsonOptions));
        }
    }

    private static AppSettings NormalizePlugAndPlayDefaults(AppSettings settings)
    {
        var changed = false;

        if (string.IsNullOrWhiteSpace(settings.GatewayUrl))
        {
            settings.GatewayUrl = "http://hermes:8642/v1";
            changed = true;
        }

        if (string.IsNullOrWhiteSpace(settings.PreferredApi))
        {
            settings.PreferredApi = "hermes-native";
            changed = true;
        }

        if (string.IsNullOrWhiteSpace(settings.Model))
        {
            settings.Model = "hermes-agent";
            changed = true;
        }

        if (string.IsNullOrWhiteSpace(settings.Provider))
        {
            settings.Provider = "hermes-agent";
            changed = true;
        }

        if (string.IsNullOrWhiteSpace(settings.AccessMode))
        {
            settings.AccessMode = "Tailscale/LAN plug-and-play";
            changed = true;
        }

        var normalizedGateway = settings.GatewayUrl.Trim().TrimEnd('/');
        if (!string.Equals(settings.GatewayUrl, normalizedGateway, StringComparison.Ordinal))
        {
            settings.GatewayUrl = normalizedGateway;
            changed = true;
        }
        if (string.IsNullOrWhiteSpace(settings.InferenceEndpoint))
        {
            settings.InferenceEndpoint = settings.GatewayUrl;
            changed = true;
        }
        if (string.IsNullOrWhiteSpace(settings.AdminBridgeUrl))
        {
            settings.AdminBridgeUrl = GatewayService.HermesRoot(settings);
            changed = true;
        }
        if (string.IsNullOrWhiteSpace(settings.VideoLibraryPath) ||
            settings.VideoLibraryPath.EndsWith("/.hermes/media/video", StringComparison.OrdinalIgnoreCase) ||
            settings.VideoLibraryPath.EndsWith("\\.hermes\\media\\video", StringComparison.OrdinalIgnoreCase))
        {
            settings.VideoLibraryPath = "/home/matteo/video";
            changed = true;
        }
        else
        {
            var normalizedVideoPath = settings.VideoLibraryPath.Trim();
            if (!string.Equals(settings.VideoLibraryPath, normalizedVideoPath, StringComparison.Ordinal))
            {
                settings.VideoLibraryPath = normalizedVideoPath;
                changed = true;
            }
        }

        if (string.IsNullOrWhiteSpace(settings.NewsLibraryPath) ||
            settings.NewsLibraryPath.EndsWith("/.hermes/media/news", StringComparison.OrdinalIgnoreCase) ||
            settings.NewsLibraryPath.EndsWith("\\.hermes\\media\\news", StringComparison.OrdinalIgnoreCase))
        {
            settings.NewsLibraryPath = "/home/matteo/news";
            changed = true;
        }
        else
        {
            var normalizedNewsPath = settings.NewsLibraryPath.Trim();
            if (!string.Equals(settings.NewsLibraryPath, normalizedNewsPath, StringComparison.Ordinal))
            {
                settings.NewsLibraryPath = normalizedNewsPath;
                changed = true;
            }
        }
        var normalizedAttachmentMb = Math.Clamp(settings.MaxAttachmentMb <= 0 || settings.MaxAttachmentMb == 6 ? 150 : settings.MaxAttachmentMb, 1, 150);
        if (settings.MaxAttachmentMb != normalizedAttachmentMb)
        {
            settings.MaxAttachmentMb = normalizedAttachmentMb;
            changed = true;
        }

        if (changed)
        {
            Save(settings);
        }

        return settings;
    }

    public static void Reset()
    {
        lock (StoreLock)
        {
            var settingsPath = SettingsPath;
            if (File.Exists(settingsPath))
            {
                File.Delete(settingsPath);
            }
            var backupPath = settingsPath + ".bak";
            if (File.Exists(backupPath))
            {
                File.Delete(backupPath);
            }
        }
    }
}
