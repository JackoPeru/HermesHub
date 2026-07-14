using System.Text.Json;

namespace NemoclawChat_Windows.Services;

public sealed class WorkspaceRequestRecord
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Kind { get; set; } = string.Empty;
    public string Title { get; set; } = "Nuova richiesta";
    public string Prompt { get; set; } = string.Empty;
    public string Result { get; set; } = string.Empty;
    public string Source { get; set; } = string.Empty;
    public string Status { get; set; } = string.Empty;
    public string Feedback { get; set; } = string.Empty;
    public bool IsRead { get; set; }
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.Now;
}

public static class WorkspaceRequestStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private const string CurrentDirectoryName = "ChatClaw";

    private static readonly object _cacheLock = new();
    private static List<WorkspaceRequestRecord>? _cache;

    private static string StorePath
    {
        get
        {
            var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            var directory = Path.Combine(localAppData, CurrentDirectoryName);
            Directory.CreateDirectory(directory);
            return Path.Combine(directory, "workspace-requests.json");
        }
    }

    public static List<WorkspaceRequestRecord> Load()
    {
        lock (_cacheLock)
        {
            if (_cache is not null) return CloneRequests(_cache);
            var content = AtomicJsonFile.Read(StorePath);
            if (string.IsNullOrEmpty(content))
            {
                _cache = [];
                return [];
            }
            try
            {
                _cache = JsonSerializer.Deserialize<List<WorkspaceRequestRecord>>(content) ?? [];
            }
            catch (JsonException)
            {
                _cache = [];
            }
            return CloneRequests(_cache);
        }
    }

    public static IReadOnlyList<WorkspaceRequestRecord> Recent(string kind, int count = 8)
    {
        return Load()
            .Where(item => item.Kind.Equals(kind, StringComparison.OrdinalIgnoreCase))
            .OrderByDescending(item => item.UpdatedAt)
            .Take(count)
            .ToList();
    }

    public static WorkspaceRequestRecord Save(string kind, string prompt, string result, string source, string status)
    {
        lock (_cacheLock)
        {
            var items = Load();
            var record = new WorkspaceRequestRecord
            {
                Kind = kind,
                Title = MakeTitle(prompt),
                Prompt = prompt,
                Result = result,
                Source = source,
                Status = status,
                UpdatedAt = DateTimeOffset.Now
            };
            items.Insert(0, record);
            var trimmed = items.Take(200).ToList();
            AtomicJsonFile.Write(StorePath, JsonSerializer.Serialize(trimmed, JsonOptions));
            _cache = CloneRequests(trimmed);
            return CloneRequest(record);
        }
    }

    public static void SaveFeedback(string id, string feedback, string status)
    {
        lock (_cacheLock)
        {
            var items = Load();
            var item = items.FirstOrDefault(candidate => candidate.Id.Equals(id, StringComparison.OrdinalIgnoreCase));
            if (item is null)
            {
                return;
            }

            item.Feedback = feedback;
            item.Status = status;
            item.IsRead = true;
            item.UpdatedAt = DateTimeOffset.Now;
            AtomicJsonFile.Write(StorePath, JsonSerializer.Serialize(items, JsonOptions));
            _cache = CloneRequests(items);
        }
    }

    private static List<WorkspaceRequestRecord> CloneRequests(IEnumerable<WorkspaceRequestRecord> items) =>
        items.Select(CloneRequest).ToList();

    private static WorkspaceRequestRecord CloneRequest(WorkspaceRequestRecord item) =>
        new()
        {
            Id = item.Id,
            Kind = item.Kind,
            Title = item.Title,
            Prompt = item.Prompt,
            Result = item.Result,
            Source = item.Source,
            Status = item.Status,
            Feedback = item.Feedback,
            IsRead = item.IsRead,
            UpdatedAt = item.UpdatedAt
        };

    private static string MakeTitle(string prompt)
    {
        var oneLine = prompt.ReplaceLineEndings(" ").Trim();
        return oneLine.Length <= 54 ? oneLine : oneLine[..54].TrimEnd() + "...";
    }
}
