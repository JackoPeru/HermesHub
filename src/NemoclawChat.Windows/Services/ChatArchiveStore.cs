using System.Text.Json;

namespace NemoclawChat_Windows.Services;

public sealed record HermesRawEventRecord(string Name, string Json, DateTimeOffset Timestamp);

public sealed record ChatMessageRecord(
    string Author,
    string Text,
    DateTimeOffset Timestamp,
    int? VisualBlocksVersion = null,
    List<VisualBlockRecord>? VisualBlocks = null,
    ChatStreamStats? Stats = null,
    List<HermesRawEventRecord>? RawEvents = null);

public sealed class ConversationRecord
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Title { get; set; } = "Nuova chat";
    public string Kind { get; set; } = "Chat";
    public string Description { get; set; } = string.Empty;
    public string Prompt { get; set; } = string.Empty;
    public string PreviousResponseId { get; set; } = string.Empty;
    public string ServerConversationId { get; set; } = string.Empty;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.Now;
    public DateTimeOffset? DeletedAt { get; set; }
    public List<ChatMessageRecord> Messages { get; set; } = [];
}

public sealed record HomeNavigationRequest(string? ConversationId = null, string? Prompt = null);

public static class ChatArchiveStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private static readonly TimeSpan DeletedRetention = TimeSpan.FromDays(30);
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

    public static List<ConversationRecord> Load(bool includeDeleted = false)
    {
        lock (_cacheLock)
        {
            if (_cache is not null)
            {
                return CloneConversations(includeDeleted ? _cache : _cache.Where(item => item.DeletedAt is null));
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
            return CloneConversations(includeDeleted ? _cache : _cache.Where(item => item.DeletedAt is null));
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
        lock (_cacheLock)
        {
            var items = Load(includeDeleted: true);
            var conversation = string.IsNullOrWhiteSpace(conversationId)
                ? null
                : items.FirstOrDefault(item => item.Id == conversationId && item.DeletedAt is null);

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
            conversation.ServerConversationId = HermesHubProtocol.ServerConversationId(conversation.Id) ?? string.Empty;
            if (previousResponseId is not null)
            {
                conversation.PreviousResponseId = previousResponseId.Trim();
            }
            conversation.UpdatedAt = DateTimeOffset.Now;
            conversation.Messages.Add(new ChatMessageRecord("Tu", prompt, DateTimeOffset.Now));
            conversation.Messages.Add(new ChatMessageRecord("Hermes", response, DateTimeOffset.Now, visualBlocksVersion, visualBlocks?.ToList()));
            SaveAll(items);
            return CloneConversation(conversation);
        }
    }

    public static ConversationRecord SaveSnapshot(
        string? conversationId,
        string mode,
        string prompt,
        IReadOnlyList<ChatMessageRecord> messages,
        string source,
        string? previousResponseId = null)
    {
        lock (_cacheLock)
        {
            var items = Load(includeDeleted: true);
            var conversation = string.IsNullOrWhiteSpace(conversationId)
                ? null
                : items.FirstOrDefault(item => item.Id == conversationId && item.DeletedAt is null);

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
            conversation.ServerConversationId = HermesHubProtocol.ServerConversationId(conversation.Id) ?? string.Empty;
            if (previousResponseId is not null)
            {
                conversation.PreviousResponseId = previousResponseId.Trim();
            }
            conversation.UpdatedAt = DateTimeOffset.Now;
            conversation.Messages = messages.ToList();
            SaveAll(items);
            return CloneConversation(conversation);
        }
    }

    public static ConversationRecord SaveProject(string title, string description, string prompt)
    {
        lock (_cacheLock)
        {
            var items = Load(includeDeleted: true);
            var existing = items.FirstOrDefault(item =>
                item.DeletedAt is null &&
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
            return CloneConversation(existing);
        }
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
        lock (_cacheLock)
        {
            var items = Load(includeDeleted: true);
            var existing = items.FirstOrDefault(item => item.Id == id);
            if (existing is null)
            {
                return false;
            }

            var now = DateTimeOffset.Now;
            existing.Title = "Chat eliminata";
            existing.Kind = "Deleted";
            existing.Description = string.Empty;
            existing.Prompt = string.Empty;
            existing.PreviousResponseId = string.Empty;
            existing.ServerConversationId = string.Empty;
            existing.Messages = [];
            existing.UpdatedAt = now;
            existing.DeletedAt = now;
            SaveAll(items);
            return true;
        }
    }

    public static bool Rename(string id, string newTitle)
    {
        lock (_cacheLock)
        {
            var items = Load(includeDeleted: true);
            var conversation = items.FirstOrDefault(item => item.Id == id && item.DeletedAt is null);
            if (conversation is not null)
            {
                conversation.Title = newTitle;
                conversation.UpdatedAt = DateTimeOffset.Now;
                SaveAll(items);
                return true;
            }

            return false;
        }
    }

    public static int Merge(IEnumerable<ConversationRecord> incoming)
    {
        lock (_cacheLock)
        {
            var items = Load(includeDeleted: true);
            var byId = items
                .Where(item => !string.IsNullOrWhiteSpace(item.Id))
                .ToDictionary(item => item.Id, item => item, StringComparer.OrdinalIgnoreCase);
            var changed = 0;

            foreach (var conversation in incoming)
            {
                if (string.IsNullOrWhiteSpace(conversation.Id))
                {
                    continue;
                }

                var incomingUpdated = conversation.UpdatedAt;
                var incomingDeletedAt = conversation.DeletedAt;

                if (!byId.TryGetValue(conversation.Id, out var existing))
                {
                    items.Add(conversation);
                    byId[conversation.Id] = conversation;
                    changed++;
                    continue;
                }

                if (incomingUpdated >= existing.UpdatedAt)
                {
                    existing.Title = conversation.Title;
                    existing.Kind = conversation.Kind;
                    existing.Description = conversation.Description;
                    existing.Prompt = conversation.Prompt;
                    existing.PreviousResponseId = conversation.PreviousResponseId;
                    existing.ServerConversationId = conversation.ServerConversationId;
                    existing.UpdatedAt = conversation.UpdatedAt;
                    existing.DeletedAt = incomingDeletedAt;
                    existing.Messages = conversation.Messages.ToList();
                    changed++;
                }
            }

            if (changed > 0)
            {
                SaveAll(items);
            }

            return changed;
        }
    }

    private static void SaveAll(List<ConversationRecord> items)
    {
        var deleteCutoff = DateTimeOffset.Now - DeletedRetention;
        var active = items
            .Where(item => item.DeletedAt is null)
            .OrderByDescending(item => item.UpdatedAt);
        var deleted = items
            .Where(item => item.DeletedAt is not null && item.DeletedAt >= deleteCutoff)
            .OrderByDescending(item => item.UpdatedAt);
        var ordered = active
            .Concat(deleted)
            .OrderByDescending(item => item.UpdatedAt)
            .ToList();
        AtomicJsonFile.Write(StorePath, JsonSerializer.Serialize(ordered, JsonOptions));
        lock (_cacheLock)
        {
            _cache = CloneConversations(ordered);
        }
        _changed?.Invoke();
    }

    private static List<ConversationRecord> CloneConversations(IEnumerable<ConversationRecord> items) =>
        items.Select(CloneConversation).ToList();

    private static ConversationRecord CloneConversation(ConversationRecord item) =>
        new()
        {
            Id = item.Id,
            Title = item.Title,
            Kind = item.Kind,
            Description = item.Description,
            Prompt = item.Prompt,
            PreviousResponseId = item.PreviousResponseId,
            ServerConversationId = item.ServerConversationId,
            UpdatedAt = item.UpdatedAt,
            DeletedAt = item.DeletedAt,
            Messages = item.Messages.ToList()
        };

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
