using System.Text.Json;

namespace NemoclawChat_Windows.Services;

public static class AppSettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private const string CurrentDirectoryName = "ChatClaw";
    private const string LegacyDirectoryName = "NemoclawChat";

    private static string DataDirectoryPath
    {
        get
        {
            var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            var currentDirectory = Path.Combine(localAppData, CurrentDirectoryName);
            var legacyDirectory = Path.Combine(localAppData, LegacyDirectoryName);

            try
            {
                if (!Directory.Exists(currentDirectory) && Directory.Exists(legacyDirectory))
                {
                    Directory.CreateDirectory(currentDirectory);
                    foreach (var file in Directory.GetFiles(legacyDirectory))
                    {
                        var destination = Path.Combine(currentDirectory, Path.GetFileName(file));
                        if (!File.Exists(destination))
                        {
                            try { File.Copy(file, destination); } catch (IOException) { }
                        }
                    }
                }
                Directory.CreateDirectory(currentDirectory);
            }
            catch (IOException)
            {
            }
            catch (UnauthorizedAccessException)
            {
            }
            return currentDirectory;
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

    public static void Save(AppSettings settings)
    {
        AtomicJsonFile.Write(SettingsPath, JsonSerializer.Serialize(settings, JsonOptions));
    }

    private static AppSettings NormalizePlugAndPlayDefaults(AppSettings settings)
    {
        var changed = false;

        if (string.IsNullOrWhiteSpace(settings.GatewayUrl) ||
            settings.GatewayUrl.Contains("127.0.0.1", StringComparison.OrdinalIgnoreCase) ||
            settings.GatewayUrl.Contains("localhost", StringComparison.OrdinalIgnoreCase) ||
            settings.GatewayUrl.Contains("100.105.46.6", StringComparison.OrdinalIgnoreCase))
        {
            settings.GatewayUrl = "http://hermes:8642/v1";
            changed = true;
        }

        if (!string.Equals(settings.PreferredApi, "hermes-native", StringComparison.OrdinalIgnoreCase))
        {
            settings.PreferredApi = "hermes-native";
            changed = true;
        }

        if (settings.StrictNativeMode)
        {
            settings.StrictNativeMode = false;
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

        settings.GatewayUrl = settings.GatewayUrl.Trim().TrimEnd('/');
        settings.InferenceEndpoint = settings.GatewayUrl;
        settings.AdminBridgeUrl = GatewayService.HermesRoot(settings);

        if (changed)
        {
            Save(settings);
        }

        return settings;
    }

    public static void Reset()
    {
        if (File.Exists(SettingsPath))
        {
            File.Delete(SettingsPath);
        }
    }
}
