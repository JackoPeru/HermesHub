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
    List<HermesRawEventRecord>? RawEvents = null,
    bool IsBookmarked = false)
{
    public string Id { get; init; } = Guid.NewGuid().ToString("N");
    public string Thinking { get; init; } = string.Empty;
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
    public string ProjectId { get; set; } = string.Empty;
    public string WorkspacePath { get; set; } = string.Empty;
    public string RepositoryUrl { get; set; } = string.Empty;
    public string ProjectInstructions { get; set; } = string.Empty;
    public string ProjectMemory { get; set; } = string.Empty;
    public List<string> AuthorizedTools { get; set; } = [];
    public string ArtifactType { get; set; } = string.Empty;
    public string ArtifactUrl { get; set; } = string.Empty;
    public string ArtifactFileName { get; set; } = string.Empty;
    public string ArtifactMimeType { get; set; } = string.Empty;
    public string SourceConversationId { get; set; } = string.Empty;
    public string SourceRunId { get; set; } = string.Empty;
    public int Version { get; set; }
    public List<string> Tags { get; set; } = [];
    public string Folder { get; set; } = string.Empty;
    public string Summary { get; set; } = string.Empty;
    public string ParentConversationId { get; set; } = string.Empty;
    public string BranchFromMessageId { get; set; } = string.Empty;
    public List<string> LinkedConversationIds { get; set; } = [];
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
        string? previousResponseId = null,
        string? projectId = null)
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
                    Title = "Nuova chat",
                    Kind = mode == "Agente" ? "Task" : "Chat"
                };
                items.Insert(0, conversation);
            }

            conversation.Kind = mode == "Agente" ? "Task" : conversation.Kind;
            conversation.Description = mode == "Agente"
                ? $"Conversazione agente via {source}."
                : $"Conversazione chat via {source}.";
            conversation.Prompt = prompt;
            if (string.IsNullOrWhiteSpace(conversation.ProjectId) && !string.IsNullOrWhiteSpace(projectId))
            {
                conversation.ProjectId = projectId.Trim();
            }
            conversation.ServerConversationId = HermesHubProtocol.ServerConversationId(conversation.Id) ?? string.Empty;
            if (previousResponseId is not null)
            {
                conversation.PreviousResponseId = previousResponseId.Trim();
            }
            conversation.UpdatedAt = DateTimeOffset.Now;
            conversation.Messages = messages.ToList();
            MaterializeArtifacts(items, conversation);
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
                existing.ProjectId = existing.Id;
                items.Insert(0, existing);
            }

            existing.ProjectId = existing.Id;
            existing.Description = description;
            existing.Prompt = prompt;
            existing.UpdatedAt = DateTimeOffset.Now;
            SaveAll(items);
            return CloneConversation(existing);
        }
    }

    public static IReadOnlyList<ConversationRecord> Projects()
    {
        return Load()
            .Where(item => item.Kind.Equals("Progetto", StringComparison.OrdinalIgnoreCase))
            .OrderByDescending(item => item.UpdatedAt)
            .ToList();
    }

    public static IReadOnlyList<ConversationRecord> ForProject(string projectId)
    {
        if (string.IsNullOrWhiteSpace(projectId))
        {
            return [];
        }

        return Load()
            .Where(item => (item.Kind.Equals("Chat", StringComparison.OrdinalIgnoreCase) || item.Kind.Equals("Task", StringComparison.OrdinalIgnoreCase)) &&
                           item.ProjectId.Equals(projectId, StringComparison.OrdinalIgnoreCase))
            .OrderByDescending(item => item.UpdatedAt)
            .ToList();
    }

    public static IReadOnlyList<ConversationRecord> Artifacts(string? query = null, string? projectId = null)
    {
        var items = Load().Where(item => item.Kind.Equals("Artifact", StringComparison.OrdinalIgnoreCase));
        if (!string.IsNullOrWhiteSpace(projectId))
        {
            items = items.Where(item => item.ProjectId.Equals(projectId, StringComparison.OrdinalIgnoreCase));
        }
        if (!string.IsNullOrWhiteSpace(query))
        {
            var text = query.Trim();
            items = items.Where(item => item.Title.Contains(text, StringComparison.OrdinalIgnoreCase) ||
                                        item.ArtifactFileName.Contains(text, StringComparison.OrdinalIgnoreCase) ||
                                        item.Tags.Any(tag => tag.Contains(text, StringComparison.OrdinalIgnoreCase)));
        }
        return items.OrderByDescending(item => item.UpdatedAt).ToList();
    }

    public static bool SetArtifactTags(string id, IEnumerable<string> tags)
    {
        lock (_cacheLock)
        {
            var items = Load(includeDeleted: true);
            var artifact = items.FirstOrDefault(item => item.Id == id && item.DeletedAt is null && item.Kind == "Artifact");
            if (artifact is null) return false;
            artifact.Tags = tags.Select(tag => NormalizeText(tag, 80)).Where(tag => tag.Length > 0).Distinct(StringComparer.OrdinalIgnoreCase).Take(30).ToList();
            artifact.UpdatedAt = DateTimeOffset.Now;
            SaveAll(items);
            return true;
        }
    }

    public static bool UpdateMessage(string conversationId, string messageId, string text)
    {
        lock (_cacheLock)
        {
            var items = Load(includeDeleted: true);
            var conversation = items.FirstOrDefault(item => item.Id == conversationId && item.DeletedAt is null);
            if (conversation is null) return false;
            var index = conversation.Messages.FindIndex(message => message.Id == messageId);
            if (index < 0) return false;
            conversation.Messages[index] = conversation.Messages[index] with { Text = NormalizeText(text, 200_000) };
            conversation.UpdatedAt = DateTimeOffset.Now;
            SaveAll(items);
            return true;
        }
    }

    public static bool DeleteMessages(string conversationId, IEnumerable<string> messageIds)
    {
        lock (_cacheLock)
        {
            var ids = messageIds.ToHashSet(StringComparer.OrdinalIgnoreCase);
            var items = Load(includeDeleted: true);
            var conversation = items.FirstOrDefault(item => item.Id == conversationId && item.DeletedAt is null);
            if (conversation is null) return false;
            conversation.Messages.RemoveAll(message => ids.Contains(message.Id));
            conversation.UpdatedAt = DateTimeOffset.Now;
            SaveAll(items);
            return true;
        }
    }

    public static bool ToggleBookmark(string conversationId, string messageId)
    {
        lock (_cacheLock)
        {
            var items = Load(includeDeleted: true);
            var conversation = items.FirstOrDefault(item => item.Id == conversationId && item.DeletedAt is null);
            if (conversation is null) return false;
            var index = conversation.Messages.FindIndex(message => message.Id == messageId);
            if (index < 0) return false;
            conversation.Messages[index] = conversation.Messages[index] with { IsBookmarked = !conversation.Messages[index].IsBookmarked };
            conversation.UpdatedAt = DateTimeOffset.Now;
            SaveAll(items);
            return true;
        }
    }

    public static bool UpdateConversationMetadata(string id, string folder, IEnumerable<string> tags, string projectId, IEnumerable<string> linkedIds, string? summary = null)
    {
        lock (_cacheLock)
        {
            var items = Load(includeDeleted: true);
            var conversation = items.FirstOrDefault(item => item.Id == id && item.DeletedAt is null);
            if (conversation is null) return false;
            conversation.Folder = NormalizeText(folder, 180);
            conversation.Tags = tags.Select(tag => NormalizeText(tag, 80)).Where(tag => tag.Length > 0).Distinct(StringComparer.OrdinalIgnoreCase).Take(30).ToList();
            conversation.ProjectId = NormalizeText(projectId, 200);
            conversation.LinkedConversationIds = linkedIds.Select(link => NormalizeText(link, 200)).Where(link => link.Length > 0 && link != id).Distinct(StringComparer.OrdinalIgnoreCase).Take(50).ToList();
            if (summary is not null) conversation.Summary = NormalizeText(summary, 20_000);
            conversation.UpdatedAt = DateTimeOffset.Now;
            SaveAll(items);
            return true;
        }
    }

    public static ConversationRecord? CreateBranch(string conversationId, string messageId, string? title = null)
    {
        lock (_cacheLock)
        {
            var items = Load(includeDeleted: true);
            var source = items.FirstOrDefault(item => item.Id == conversationId && item.DeletedAt is null);
            if (source is null) return null;
            var index = source.Messages.FindIndex(message => message.Id == messageId);
            if (index < 0) return null;
            var branch = CloneConversation(source);
            branch.Id = $"branch_{Guid.NewGuid():N}";
            branch.Title = NormalizeText(title, 180) is var branchTitle && branchTitle.Length > 0 ? branchTitle : $"{source.Title} · ramo";
            branch.ParentConversationId = source.Id;
            branch.BranchFromMessageId = messageId;
            branch.PreviousResponseId = string.Empty;
            branch.ServerConversationId = HermesHubProtocol.ServerConversationId(branch.Id) ?? string.Empty;
            branch.Messages = branch.Messages.Take(index + 1).ToList();
            branch.UpdatedAt = DateTimeOffset.Now;
            items.Insert(0, branch);
            source.LinkedConversationIds = source.LinkedConversationIds.Append(branch.Id).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
            SaveAll(items);
            return CloneConversation(branch);
        }
    }

    public static ConversationRecord SaveProjectWorkspace(
        string? projectId,
        string title,
        string description,
        string workspacePath,
        string repositoryUrl,
        string instructions,
        string memory,
        IEnumerable<string> authorizedTools)
    {
        lock (_cacheLock)
        {
            var items = Load(includeDeleted: true);
            var normalizedTitle = NormalizeText(title, 180);
            if (string.IsNullOrWhiteSpace(normalizedTitle))
            {
                throw new ArgumentException("Titolo progetto obbligatorio.", nameof(title));
            }

            var project = string.IsNullOrWhiteSpace(projectId)
                ? null
                : items.FirstOrDefault(item => item.DeletedAt is null &&
                                               item.Kind.Equals("Progetto", StringComparison.OrdinalIgnoreCase) &&
                                               item.Id.Equals(projectId, StringComparison.OrdinalIgnoreCase));
            project ??= items.FirstOrDefault(item => item.DeletedAt is null &&
                                                      item.Kind.Equals("Progetto", StringComparison.OrdinalIgnoreCase) &&
                                                      item.Title.Equals(normalizedTitle, StringComparison.OrdinalIgnoreCase));

            if (project is null)
            {
                project = new ConversationRecord
                {
                    Id = $"project_{Guid.NewGuid():N}",
                    Kind = "Progetto"
                };
                items.Insert(0, project);
            }

            project.ProjectId = project.Id;
            project.Title = normalizedTitle;
            project.Description = NormalizeText(description, 4_000);
            project.WorkspacePath = NormalizeText(workspacePath, 1_024);
            project.RepositoryUrl = NormalizeText(repositoryUrl, 2_048);
            project.ProjectInstructions = NormalizeText(instructions, 20_000);
            project.ProjectMemory = NormalizeText(memory, 20_000);
            project.AuthorizedTools = authorizedTools
                .Select(tool => NormalizeText(tool, 100))
                .Where(tool => !string.IsNullOrWhiteSpace(tool))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .Take(100)
                .ToList();
            project.Prompt = project.ProjectInstructions;
            project.UpdatedAt = DateTimeOffset.Now;
            SaveAll(items);
            return CloneConversation(project);
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
            existing.ProjectId = string.Empty;
            existing.WorkspacePath = string.Empty;
            existing.RepositoryUrl = string.Empty;
            existing.ProjectInstructions = string.Empty;
            existing.ProjectMemory = string.Empty;
            existing.AuthorizedTools = [];
            existing.ArtifactType = string.Empty;
            existing.ArtifactUrl = string.Empty;
            existing.ArtifactFileName = string.Empty;
            existing.ArtifactMimeType = string.Empty;
            existing.SourceConversationId = string.Empty;
            existing.SourceRunId = string.Empty;
            existing.Version = 0;
            existing.Tags = [];
            existing.Folder = string.Empty;
            existing.Summary = string.Empty;
            existing.ParentConversationId = string.Empty;
            existing.BranchFromMessageId = string.Empty;
            existing.LinkedConversationIds = [];
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
                    existing.ProjectId = conversation.ProjectId;
                    existing.WorkspacePath = conversation.WorkspacePath;
                    existing.RepositoryUrl = conversation.RepositoryUrl;
                    existing.ProjectInstructions = conversation.ProjectInstructions;
                    existing.ProjectMemory = conversation.ProjectMemory;
                    existing.AuthorizedTools = conversation.AuthorizedTools.ToList();
                    existing.ArtifactType = conversation.ArtifactType;
                    existing.ArtifactUrl = conversation.ArtifactUrl;
                    existing.ArtifactFileName = conversation.ArtifactFileName;
                    existing.ArtifactMimeType = conversation.ArtifactMimeType;
                    existing.SourceConversationId = conversation.SourceConversationId;
                    existing.SourceRunId = conversation.SourceRunId;
                    existing.Version = conversation.Version;
                    existing.Tags = conversation.Tags.ToList();
                    existing.Folder = conversation.Folder;
                    existing.Summary = conversation.Summary;
                    existing.ParentConversationId = conversation.ParentConversationId;
                    existing.BranchFromMessageId = conversation.BranchFromMessageId;
                    existing.LinkedConversationIds = conversation.LinkedConversationIds.ToList();
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
            ProjectId = item.ProjectId,
            WorkspacePath = item.WorkspacePath,
            RepositoryUrl = item.RepositoryUrl,
            ProjectInstructions = item.ProjectInstructions,
            ProjectMemory = item.ProjectMemory,
            AuthorizedTools = item.AuthorizedTools.ToList(),
            ArtifactType = item.ArtifactType,
            ArtifactUrl = item.ArtifactUrl,
            ArtifactFileName = item.ArtifactFileName,
            ArtifactMimeType = item.ArtifactMimeType,
            SourceConversationId = item.SourceConversationId,
            SourceRunId = item.SourceRunId,
            Version = item.Version,
            Tags = item.Tags.ToList(),
            Folder = item.Folder,
            Summary = item.Summary,
            ParentConversationId = item.ParentConversationId,
            BranchFromMessageId = item.BranchFromMessageId,
            LinkedConversationIds = item.LinkedConversationIds.ToList(),
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
        item.ProjectId,
        item.WorkspacePath,
        item.RepositoryUrl,
        item.ProjectInstructions,
        item.ProjectMemory,
        item.AuthorizedTools,
        item.ArtifactType,
        item.ArtifactUrl,
        item.ArtifactFileName,
        item.ArtifactMimeType,
        item.SourceConversationId,
        item.SourceRunId,
        item.Version,
        item.Tags,
        item.Folder,
        item.Summary,
        item.ParentConversationId,
        item.BranchFromMessageId,
        item.LinkedConversationIds,
        item.DeletedAt,
        item.Messages
    });

    private static string NormalizeText(string? value, int maxLength)
    {
        var normalized = (value ?? string.Empty).Trim();
        return normalized.Length <= maxLength ? normalized : normalized[..maxLength];
    }

    private static void MaterializeArtifacts(List<ConversationRecord> items, ConversationRecord conversation)
    {
        if (conversation.Kind.Equals("Artifact", StringComparison.OrdinalIgnoreCase)) return;
        var artifactTypes = new HashSet<string>(["media_file", "image_gallery", "code", "diagram", "markdown", "table", "chart"], StringComparer.OrdinalIgnoreCase);
        foreach (var message in conversation.Messages)
        {
            foreach (var block in message.VisualBlocks ?? [])
            {
                if (!artifactTypes.Contains(block.Type)) continue;
                var blockKey = string.IsNullOrWhiteSpace(block.Id) ? Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(JsonSerializer.Serialize(block))))[..16] : block.Id;
                var id = $"artifact_{conversation.Id}_{message.Id}_{blockKey}";
                if (items.Any(item => item.Id.Equals(id, StringComparison.OrdinalIgnoreCase))) continue;
                var filename = block.Filename ?? string.Empty;
                var title = block.Title ?? (string.IsNullOrWhiteSpace(filename) ? $"{block.Type} · {conversation.Title}" : filename);
                var groupingKey = string.IsNullOrWhiteSpace(filename) ? title : filename;
                var version = items.Count(item => item.Kind == "Artifact" && item.ProjectId == conversation.ProjectId &&
                                                  (item.ArtifactFileName.Equals(groupingKey, StringComparison.OrdinalIgnoreCase) || item.Title.Equals(groupingKey, StringComparison.OrdinalIgnoreCase))) + 1;
                items.Insert(0, new ConversationRecord
                {
                    Id = id,
                    Kind = "Artifact",
                    Title = title,
                    Description = block.Caption ?? block.Summary ?? string.Empty,
                    ProjectId = conversation.ProjectId,
                    ArtifactType = block.Type,
                    ArtifactUrl = block.MediaUrl ?? block.DownloadUrl ?? block.DownloadUrlCamel ?? block.RenderedMediaUrl ?? string.Empty,
                    ArtifactFileName = filename,
                    ArtifactMimeType = block.MimeType ?? string.Empty,
                    SourceConversationId = conversation.Id,
                    SourceRunId = message.RawEvents?.Select(raw => raw.Json).FirstOrDefault(json => json.Contains("run_id", StringComparison.OrdinalIgnoreCase)) ?? string.Empty,
                    Version = version,
                    UpdatedAt = message.Timestamp
                });
            }
        }
    }
}
