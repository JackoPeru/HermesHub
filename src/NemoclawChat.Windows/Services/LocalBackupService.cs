using System.Text.Json;

namespace NemoclawChat_Windows.Services;

public static class LocalBackupService
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    public static string Export()
    {
        var documents = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
        var directory = Path.Combine(documents, "HermesHubBackups");
        Directory.CreateDirectory(directory);

        var fileName = $"HermesHub-backup-{DateTimeOffset.Now:yyyyMMdd-HHmmssfff}-{Guid.NewGuid():N}.json";
        var path = Path.Combine(directory, fileName);

        var payload = new
        {
            schema = "hermes-hub.local-backup.v1",
            exportedAt = DateTimeOffset.Now,
            settings = AppSettingsStore.Load(),
            conversations = ChatArchiveStore.Load()
        };

        AtomicJsonFile.Write(path, JsonSerializer.Serialize(payload, JsonOptions));
        return path;
    }
}
