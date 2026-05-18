using System.Text.Json;

namespace NemoclawChat_Windows.Services;

public sealed record ChatMessageRecord(
    string Author,
    string Text,
    DateTimeOffset Timestamp,
    int? VisualBlocksVersion = null,
    List<VisualBlockRecord>? VisualBlocks = null);

public sealed class ConversationRecord
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Title { get; set; } = "Nuova chat";
    public string Kind { get; set; } = "Chat";
    public string Description { get; set; } = string.Empty;
    public string Prompt { get; set; } = string.Empty;
    public string PreviousResponseId { get; set; } = string.Empty;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.Now;
    public List<ChatMessageRecord> Messages { get; set; } = [];
}

public sealed record HomeNavigationRequest(string? ConversationId = null, string? Prompt = null);

public static class ChatArchiveStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private static event Action? _changed;
    public static event Action? Changed
    {
        add { _changed += value; }
        remove { _changed -= value; }
    }
    private const string CurrentDirectoryName = "ChatClaw";
    private const string LegacyDirectoryName = "NemoclawChat";

    private static readonly object _cacheLock = new();
    private static List<ConversationRecord>? _cache;

    private static string DataDirectoryPath
    {
        get
        {
            var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            var currentDirectory = Path.Combine(localAppData, CurrentDirectoryName);
            var legacyDirectory = Path.Combine(localAppData, LegacyDirectoryName);

            if (!Directory.Exists(currentDirectory) && Directory.Exists(legacyDirectory))
            {
                Directory.CreateDirectory(currentDirectory);
                foreach (var file in Directory.GetFiles(legacyDirectory))
                {
                    var destination = Path.Combine(currentDirectory, Path.GetFileName(file));
                    if (!File.Exists(destination))
                    {
                        File.Copy(file, destination);
                    }
                }
            }

            Directory.CreateDirectory(currentDirectory);
            return currentDirectory;
        }
    }

    private static string StorePath
    {
        get
        {
            return Path.Combine(DataDirectoryPath, "conversations.json");
        }
    }

    public static List<ConversationRecord> Load()
    {
        lock (_cacheLock)
        {
            if (_cache is not null)
            {
                // copia difensiva: caller puo' mutare la lista senza corrompere la cache.
                return new List<ConversationRecord>(_cache);
            }

            var content = AtomicJsonFile.Read(StorePath);
            if (string.IsNullOrEmpty(content))
            {
                _cache = [];
                return [];
            }

            try
            {
                _cache = JsonSerializer.Deserialize<List<ConversationRecord>>(content) ?? [];
            }
            catch (JsonException)
            {
                _cache = [];
            }
            return new List<ConversationRecord>(_cache);
        }
    }

    private static void InvalidateCache()
    {
        lock (_cacheLock)
        {
            _cache = null;
        }
    }

    public static ConversationRecord? Find(string id)
    {
        return Load().FirstOrDefault(item => item.Id == id);
    }

    public static ConversationRecord SaveExchange(
        string? conversationId,
        string mode,
        string prompt,
        string response,
        string source,
        string? previousResponseId = null,
        IReadOnlyList<VisualBlockRecord>? visualBlocks = null,
        int? visualBlocksVersion = null)
    {
        var items = Load();
        var conversation = string.IsNullOrWhiteSpace(conversationId)
            ? null
            : items.FirstOrDefault(item => item.Id == conversationId);

        if (conversation is null)
        {
            conversation = new ConversationRecord
            {
                Id = Guid.NewGuid().ToString("N"),
                Title = MakeTitle(prompt),
                Kind = mode == "Agente" ? "Task" : "Chat",
                Description = mode == "Agente"
                    ? $"Conversazione agente via {source}."
                    : $"Conversazione chat via {source}.",
                Prompt = prompt
            };
            items.Insert(0, conversation);
        }

        conversation.Kind = mode == "Agente" ? "Task" : conversation.Kind;
        conversation.Description = mode == "Agente"
            ? $"Conversazione agente via {source}."
            : $"Conversazione chat via {source}.";
        conversation.Prompt = prompt;
        if (!string.IsNullOrWhiteSpace(previousResponseId))
        {
            conversation.PreviousResponseId = previousResponseId;
        }
        conversation.UpdatedAt = DateTimeOffset.Now;
        conversation.Messages.Add(new ChatMessageRecord("Tu", prompt, DateTimeOffset.Now));
        conversation.Messages.Add(new ChatMessageRecord("Hermes", response, DateTimeOffset.Now, visualBlocksVersion, visualBlocks?.ToList()));
        SaveAll(items);
        return conversation;
    }

    public static ConversationRecord SaveSnapshot(
        string? conversationId,
        string mode,
        string prompt,
        IReadOnlyList<ChatMessageRecord> messages,
        string source,
        string? previousResponseId = null)
    {
        var items = Load();
        var conversation = string.IsNullOrWhiteSpace(conversationId)
            ? null
            : items.FirstOrDefault(item => item.Id == conversationId);

        if (conversation is null)
        {
            conversation = new ConversationRecord
            {
                Id = Guid.NewGuid().ToString("N"),
                Title = MakeTitle(prompt),
                Kind = mode == "Agente" ? "Task" : "Chat"
            };
            items.Insert(0, conversation);
        }

        conversation.Kind = mode == "Agente" ? "Task" : conversation.Kind;
        conversation.Description = mode == "Agente"
            ? $"Conversazione agente via {source}."
            : $"Conversazione chat via {source}.";
        conversation.Prompt = prompt;
        if (!string.IsNullOrWhiteSpace(previousResponseId))
        {
            conversation.PreviousResponseId = previousResponseId;
        }
        conversation.UpdatedAt = DateTimeOffset.Now;
        conversation.Messages = messages.ToList();
        SaveAll(items);
        return conversation;
    }

    public static ConversationRecord SaveProject(string title, string description, string prompt)
    {
        var items = Load();
        var existing = items.FirstOrDefault(item =>
            item.Kind == "Progetto" &&
            string.Equals(item.Title, title, StringComparison.OrdinalIgnoreCase));

        if (existing is null)
        {
            existing = new ConversationRecord
            {
                Id = Guid.NewGuid().ToString("N"),
                Kind = "Progetto",
                Title = title,
                Description = description,
                Prompt = prompt
            };
            items.Insert(0, existing);
        }

        existing.Description = description;
        existing.Prompt = prompt;
        existing.UpdatedAt = DateTimeOffset.Now;
        SaveAll(items);
        return existing;
    }

    public static IReadOnlyList<ConversationRecord> Recent(int count)
    {
        return Load()
            .OrderByDescending(item => item.UpdatedAt)
            .Take(count)
            .ToList();
    }

    public static bool Delete(string id)
    {
        var items = Load();
        var removed = items.RemoveAll(item => item.Id == id) > 0;
        if (removed)
        {
            SaveAll(items);
        }

        return removed;
    }

    private static void SaveAll(List<ConversationRecord> items)
    {
        var ordered = items
            .OrderByDescending(item => item.UpdatedAt)
            .Take(200)
            .ToList();
        AtomicJsonFile.Write(StorePath, JsonSerializer.Serialize(ordered, JsonOptions));
        lock (_cacheLock)
        {
            _cache = ordered;
        }
        _changed?.Invoke();
    }

    private static string MakeTitle(string prompt)
    {
        var oneLine = prompt.ReplaceLineEndings(" ").Trim();
        if (oneLine.Length <= 46)
        {
            return oneLine;
        }

        return oneLine[..46].TrimEnd() + "...";
    }
}
