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
    List<HermesRawEventRecord>? RawEvents = null)
{
    public string Id { get; init; } = Guid.NewGuid().ToString("N");
}

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
    private static readonly object _cacheLock = new();
    private static List<ConversationRecord>? _cache;

    private static string DataDirectoryPath
    {
        get
        {
            AppDataMigration.Run();
            return AppDataMigration.CurrentDirectoryPath;
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

    public static ConversationRecord? Find(string id)
    {
        return Load().FirstOrDefault(item => item.Id == id);
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

                var newer = incomingUpdated > existing.UpdatedAt;
                var newerTombstoneAtSameRevision = incomingUpdated == existing.UpdatedAt &&
                                                    incomingDeletedAt is not null &&
                                                    (existing.DeletedAt is null || incomingDeletedAt > existing.DeletedAt);
                var deterministicTieWinner = incomingUpdated == existing.UpdatedAt &&
                                             incomingDeletedAt == existing.DeletedAt &&
                                             string.CompareOrdinal(RevisionKey(conversation), RevisionKey(existing)) > 0;
                if (newer || newerTombstoneAtSameRevision || deterministicTieWinner)
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
        var changedHandlers = _changed;
        if (changedHandlers is not null)
        {
            foreach (Action handler in changedHandlers.GetInvocationList())
            {
                try { handler(); }
                catch (Exception ex) { System.Diagnostics.Trace.WriteLine($"[ChatArchiveStore] Changed handler failed: {ex}"); }
            }
        }
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
            Messages = item.Messages.Select(CloneMessage).ToList()
        };

    private static ChatMessageRecord CloneMessage(ChatMessageRecord message) => message with
    {
        VisualBlocks = message.VisualBlocks?.Select(CloneVisualBlock).ToList(),
        RawEvents = message.RawEvents?.ToList()
    };

    private static VisualBlockRecord CloneVisualBlock(VisualBlockRecord block) => block with
    {
        HighlightLines = block.HighlightLines.ToList(),
        Columns = block.Columns.ToList(),
        Rows = block.Rows.Select(row => new Dictionary<string, JsonElement>(row, StringComparer.Ordinal)).ToList(),
        Series = block.Series.Select(series => series with { Points = series.Points.ToList() }).ToList(),
        Images = block.Images.ToList()
    };

    private static string RevisionKey(ConversationRecord item) => JsonSerializer.Serialize(new
    {
        item.Title,
        item.Kind,
        item.Description,
        item.Prompt,
        item.PreviousResponseId,
        item.ServerConversationId,
        item.DeletedAt,
        item.Messages
    });

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
