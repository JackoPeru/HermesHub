using System.Collections.Concurrent;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace NemoclawChat_Windows.Services;

public sealed record HermesBotConnectionFailure(string ConnectionId, string Label, string Message);

public sealed record HermesBotConnectionsRoster(
    IReadOnlyList<HermesBotRecord> Items,
    IReadOnlyList<HermesBotConnectionFailure> Failures,
    string Status);

public sealed record HermesBotChatContext(
    HermesBotChat Chat,
    HermesBotConnection Connection,
    string LocalConversationId);

public sealed record HermesBotGroupMemberReply(
    HermesBotGroupMember Member,
    string Reply,
    bool Silent,
    string? SessionId = null);

public sealed record HermesBotGroupTurnResult(
    string GroupName,
    string Outcome,
    string Reply,
    int Rounds,
    int BotMessages,
    IReadOnlyList<HermesBotGroupMemberReply> MemberReplies,
    IReadOnlyList<HermesBotConnectionFailure> MemberFailures);

public static class HermesBotConnectionService
{
    private static readonly Regex MentionRegex = new("(?<![\\w])@([A-Za-z0-9][A-Za-z0-9_.-]{0,63})(?![\\w])", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly ConcurrentDictionary<string, SemaphoreSlim> MemberLocks = new(StringComparer.OrdinalIgnoreCase);
    private static readonly object TurnLock = new();
    private static readonly Dictionary<string, (long Epoch, CancellationTokenSource Cancellation)> ActiveTurns = new(StringComparer.OrdinalIgnoreCase);
    private static long _epoch;

    public static async Task<HermesBotConnectionsRoster> LoadAllRostersAsync(
        AppSettings settings,
        CancellationToken cancellationToken = default)
    {
        var connections = HermesBotConnectionStore.Load(settings)
            .Where(connection => connection.Enabled && !string.IsNullOrWhiteSpace(connection.Endpoint))
            .ToArray();
        var results = await Task.WhenAll(connections.Select(connection => LoadOneRosterAsync(settings, connection, cancellationToken)));
        var items = results.SelectMany(result => result.Items).ToList();
        var failures = results.SelectMany(result => result.Failures).ToList();
        AssignStableHandles(items);
        var status = failures.Count == 0
            ? $"{items.Count} bot da {connections.Length} connessioni Hermes."
            : $"{items.Count} bot disponibili; {failures.Count} connessione/i non raggiungibile/i: " + string.Join(", ", failures.Select(failure => failure.Label));
        return new HermesBotConnectionsRoster(items, failures, status);
    }

    public static async Task<HermesBotRecord> CreateBotAsync(
        AppSettings settings,
        string connectionId,
        string profile,
        string displayName,
        string description,
        string soul,
        CancellationToken cancellationToken = default)
    {
        var connection = ResolveConnection(settings, connectionId);
        var effective = SettingsForConnection(settings, connection);
        var token = LoadConnectionToken(connection);
        var bot = connection.IsPrimary
            ? await GatewayService.CreateBotAsync(effective, profile, displayName, description, soul, cancellationToken: cancellationToken)
            : await GatewayService.CreateBotOnConnectionAsync(effective, token, profile, displayName, description, soul, cancellationToken: cancellationToken);
        return Decorate(bot, connection);
    }

    public static async Task<HermesBotRecord> UpdateBotAsync(
        AppSettings settings,
        HermesBotRecord existing,
        string description,
        string displayName,
        string? soul,
        CancellationToken cancellationToken = default)
    {
        var connection = ResolveConnection(settings, existing.ConnectionId);
        var effective = SettingsForConnection(settings, connection);
        var token = LoadConnectionToken(connection);
        var bot = connection.IsPrimary
            ? await GatewayService.UpdateBotAsync(effective, existing, description, displayName, soul, cancellationToken)
            : await GatewayService.UpdateBotOnConnectionAsync(effective, token, existing, description, displayName, soul, cancellationToken);
        return Decorate(bot, connection);
    }

    public static async Task DeleteBotAsync(
        AppSettings settings,
        HermesBotRecord bot,
        string confirmation,
        CancellationToken cancellationToken = default)
    {
        var connection = ResolveConnection(settings, bot.ConnectionId);
        var effective = SettingsForConnection(settings, connection);
        var token = LoadConnectionToken(connection);
        if (connection.IsPrimary)
        {
            await GatewayService.DeleteBotAsync(effective, bot, confirmation, cancellationToken);
        }
        else
        {
            await GatewayService.DeleteBotOnConnectionAsync(effective, token, bot, confirmation, cancellationToken);
        }
    }

    public static async Task<HermesBotChatContext> OpenBotChatAsync(
        AppSettings settings,
        HermesBotRecord bot,
        CancellationToken cancellationToken = default)
    {
        var connection = ResolveConnection(settings, bot.ConnectionId);
        var effective = SettingsForConnection(settings, connection);
        var token = LoadConnectionToken(connection);
        var chat = connection.IsPrimary
            ? await GatewayService.OpenBotChatAsync(effective, bot, cancellationToken)
            : await GatewayService.OpenBotChatOnConnectionAsync(effective, token, bot, cancellationToken);
        var localId = $"bot-{Sanitize(bot.ConnectionId)}-{Sanitize(bot.Profile)}-{Sanitize(chat.SessionId)}";
        return new HermesBotChatContext(chat, connection, localId);
    }

    public static async Task<HermesBotGroupTurnResult> RunGroupTurnAsync(
        AppSettings settings,
        HermesBotGroup group,
        string userMessage,
        CancellationToken cancellationToken = default)
    {
        var normalized = HermesBotGroupStore.Normalize(group);
        if (string.IsNullOrWhiteSpace(userMessage)) throw new ArgumentException("Il messaggio del gruppo è obbligatorio.", nameof(userMessage));
        var originalUserMessage = userMessage.Trim();
        if (originalUserMessage.Length > 20_000) throw new ArgumentException("Il messaggio del gruppo è limitato a 20.000 caratteri.", nameof(userMessage));
        var (epoch, turnToken) = BeginTurn(normalized.Name, cancellationToken);
        try
        {
            var allMembers = normalized.Members.ToArray();
            var available = SelectInitialMembers(allMembers, userMessage);
            var pending = available.ToList();
            var replies = new List<HermesBotGroupMemberReply>();
            var failures = new List<HermesBotConnectionFailure>();
            var failedIdentities = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var lastReply = string.Empty;
            var rounds = 0;
            var botMessages = 0;
            var outcome = "reply";
            while (pending.Count > 0 && rounds < 3 && botMessages < 10)
            {
                rounds++;
                var current = pending.ToArray();
                pending.Clear();
                var next = new List<HermesBotGroupMember>();
                var roundNonPass = 0;
                var roundFailures = 0;
                var immediateEscalation = false;
                foreach (var member in current)
                {
                    if (failedIdentities.Contains(member.IdentityKey)) continue;
                    if (botMessages >= 10) break;
                    await EnsureCurrentTurnAsync(normalized.Name, epoch, turnToken);
                    var semaphore = MemberLocks.GetOrAdd(member.IdentityKey, _ => new SemaphoreSlim(1, 1));
                    await semaphore.WaitAsync(turnToken);
                    try
                    {
                        await EnsureCurrentTurnAsync(normalized.Name, epoch, turnToken);
                        var connection = ResolveConnection(settings, member.ConnectionId);
                        if (!connection.Enabled || string.IsNullOrWhiteSpace(connection.Endpoint))
                        {
                            throw new InvalidOperationException("Endpoint connessione non configurato.");
                        }
                        var effective = SettingsForConnection(settings, connection);
                        var payload = new
                        {
                            group_name = normalized.Name,
                            members = allMembers.Select(ToGatewayMember),
                            user_message = userMessage,
                            original_user_message = originalUserMessage,
                            max_rounds = 1,
                            max_messages = 1,
                            local_connection_id = member.ConnectionId,
                            target_profiles = new[] { member.Profile },
                            transcript = replies.TakeLast(8).Select(item => new { handle = item.Member.Handle, reply = item.Reply })
                        };
                        var response = connection.IsPrimary
                            ? await GatewayService.SendBotGroupTurnOnConnectionAsync(effective, GatewayCredentialStore.LoadSecret(), payload, turnToken)
                            : await GatewayService.SendBotGroupTurnOnConnectionAsync(effective, GatewayCredentialStore.LoadConnectionSecret(connection.Id), payload, turnToken);
                        if (response.StatusCode is < 200 or > 299)
                        {
                            throw new InvalidOperationException($"HTTP {response.StatusCode}: turno gruppo rifiutato.");
                        }

                        var parsed = ParseGroupMemberResponse(response.Body, member);
                        replies.Add(parsed);
                        if (!parsed.Silent)
                        {
                            botMessages++;
                            roundNonPass++;
                            lastReply = parsed.Reply;
                            if (ContainsMention(parsed.Reply, "user"))
                            {
                                outcome = "escalation";
                                immediateEscalation = true;
                                break;
                            }
                            next.AddRange(SelectMentionedMembers(parsed.Reply, allMembers));
                        }
                    }
                    catch (OperationCanceledException) when (turnToken.IsCancellationRequested)
                    {
                        throw;
                    }
                    catch (Exception ex)
                    {
                        roundFailures++;
                        failedIdentities.Add(member.IdentityKey);
                        failures.Add(new HermesBotConnectionFailure(member.ConnectionId, ResolveLabel(settings, member.ConnectionId), SafeFailureMessage(ex)));
                    }
                    finally
                    {
                        semaphore.Release();
                    }
                }

                if (immediateEscalation) break;
                if (roundNonPass == 0)
                {
                    outcome = roundFailures > 0 || failures.Count > 0 ? "partial" : "pass";
                    break;
                }
                if (botMessages >= 10 || rounds >= 3)
                {
                    outcome = "bounded";
                    break;
                }

                var routed = next
                    .Concat(next.Count == 0 ? allMembers : [])
                    .Where(member => !failedIdentities.Contains(member.IdentityKey))
                    .Where(member => available.Any(item => string.Equals(item.IdentityKey, member.IdentityKey, StringComparison.OrdinalIgnoreCase)))
                    .GroupBy(member => member.IdentityKey, StringComparer.OrdinalIgnoreCase)
                    .Select(grouping => grouping.First())
                    .ToList();
                var candidates = routed.Count == 0 ? available : routed.ToArray();
                pending.AddRange(candidates.Where(member => !failedIdentities.Contains(member.IdentityKey)));
                userMessage = BuildFollowUpPrompt(originalUserMessage);
            }

            if (outcome == "reply" && (rounds >= 3 || botMessages >= 10)) outcome = "bounded";
            if (failures.Count > 0 && replies.Count > 0 && outcome is not ("pass" or "escalation" or "partial")) outcome = "partial";
            if (failures.Count > 0 && replies.Count == 0) outcome = "error";
            return new HermesBotGroupTurnResult(normalized.Name, outcome, lastReply, rounds, botMessages, replies, failures);
        }
        finally
        {
            EndTurn(normalized.Name, epoch, turnToken);
        }
    }

    private static async Task<(IReadOnlyList<HermesBotRecord> Items, IReadOnlyList<HermesBotConnectionFailure> Failures)> LoadOneRosterAsync(
        AppSettings settings,
        HermesBotConnection connection,
        CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(15));
        try
        {
            var effective = SettingsForConnection(settings, connection);
            var roster = connection.IsPrimary
                ? await GatewayService.LoadBotRosterAsync(effective, timeout.Token)
                : await GatewayService.LoadBotRosterOnConnectionAsync(effective, GatewayCredentialStore.LoadConnectionSecret(connection.Id), timeout.Token);
            var items = roster.Items.Select(bot => Decorate(bot, connection)).ToList();
            return (items, []);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return ([], [new HermesBotConnectionFailure(connection.Id, connection.Label, "Timeout roster connessione.")]);
        }
        catch (Exception ex)
        {
            return ([], [new HermesBotConnectionFailure(connection.Id, connection.Label, SafeFailureMessage(ex))]);
        }
    }

    private static HermesBotRecord Decorate(HermesBotRecord bot, HermesBotConnection connection)
    {
        var decorated = new HermesBotRecord(bot.Profile, bot.DisplayName, bot.Description, bot.Hidden, bot.ChatId, bot.IsDefault, connection.Id, connection.Label);
        return decorated;
    }

    private static void AssignStableHandles(IList<HermesBotRecord> items)
    {
        var used = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var bases = items.GroupBy(item => Slug(item.DisplayName, item.Profile), StringComparer.OrdinalIgnoreCase);
        foreach (var group in bases)
        {
            var rows = group.ToArray();
            foreach (var row in rows)
            {
                var handle = rows.Length == 1
                    ? group.Key
                    : $"{group.Key}-{Slug(row.ConnectionLabel, row.ConnectionId)}";
                if (string.IsNullOrWhiteSpace(handle))
                {
                    handle = Slug(row.ConnectionId, "connection");
                }
                var candidate = handle;
                var suffix = 0;
                while (!used.Add(candidate))
                {
                    suffix++;
                    candidate = $"{handle}-{Slug(row.Profile, "bot")}{(suffix == 1 ? string.Empty : $"-{suffix}")}";
                }
                row.Handle = candidate;
            }
        }
    }

    private static HermesBotConnection ResolveConnection(AppSettings settings, string connectionId)
    {
        return HermesBotConnectionStore.Load(settings).FirstOrDefault(connection => string.Equals(connection.Id, connectionId, StringComparison.OrdinalIgnoreCase))
            ?? throw new InvalidOperationException("Connessione Hermes non trovata.");
    }

    private static AppSettings SettingsForConnection(AppSettings settings, HermesBotConnection connection)
    {
        if (string.IsNullOrWhiteSpace(connection.Endpoint)) throw new InvalidOperationException("Endpoint Hermes esplicito non configurato.");
        var effective = settings.ForModel(settings.Model);
        effective.GatewayUrl = connection.Endpoint;
        effective.GatewayWsUrl = string.Empty;
        effective.InferenceEndpoint = connection.Endpoint;
        effective.AdminBridgeUrl = connection.Endpoint;
        return effective;
    }

    private static string? LoadConnectionToken(HermesBotConnection connection) =>
        connection.IsPrimary ? GatewayCredentialStore.LoadSecret() : GatewayCredentialStore.LoadConnectionSecret(connection.Id);

    private static object ToGatewayMember(HermesBotGroupMember member) => new
    {
        connection_id = member.ConnectionId,
        profile = member.Profile,
        display_name = member.DisplayName,
        handle = member.Handle
    };

    private static HermesBotGroupMemberReply ParseGroupMemberResponse(string body, HermesBotGroupMember fallback)
    {
        using var document = JsonDocument.Parse(body);
        var root = document.RootElement;
        if (root.TryGetProperty("member_failures", out var failures) && failures.ValueKind == JsonValueKind.Array && failures.GetArrayLength() > 0)
            throw new InvalidOperationException("Il membro Hermes non ha completato il turno.");
        var reply = root.TryGetProperty("reply", out var replyElement) && replyElement.ValueKind == JsonValueKind.String ? replyElement.GetString() ?? string.Empty : string.Empty;
        var sessionId = (string?)null;
        if (root.TryGetProperty("member_results", out var results) && results.ValueKind == JsonValueKind.Array && results.GetArrayLength() > 0)
        {
            var first = results[0];
            if (first.TryGetProperty("reply", out var firstReply) && firstReply.ValueKind == JsonValueKind.String) reply = firstReply.GetString() ?? reply;
            if (first.TryGetProperty("session_id", out var session) && session.ValueKind == JsonValueKind.String) sessionId = session.GetString();
        }
        return new HermesBotGroupMemberReply(fallback, reply, IsSilentReply(reply), sessionId);
    }

    private static HermesBotGroupMember[] SelectInitialMembers(IReadOnlyList<HermesBotGroupMember> members, string prompt)
    {
        var selected = SelectMentionedMembers(prompt, members);
        return selected.Length == 0 ? members.ToArray() : selected;
    }

    private static HermesBotGroupMember[] SelectMentionedMembers(string text, IReadOnlyList<HermesBotGroupMember> members)
    {
        if (ContainsMention(text, "everyone")) return members.ToArray();
        var mentions = MentionRegex.Matches(text).Select(match => match.Groups[1].Value.ToLowerInvariant()).ToHashSet(StringComparer.OrdinalIgnoreCase);
        return members.Where(member => mentions.Contains(member.Handle)).ToArray();
    }

    private static bool ContainsMention(string text, string handle) =>
        MentionRegex.Matches(text).Any(match => string.Equals(match.Groups[1].Value, handle, StringComparison.OrdinalIgnoreCase));

    private static bool IsSilentReply(string? reply)
    {
        var normalized = (reply ?? string.Empty).Trim().ToLowerInvariant();
        return normalized is "" or "(pass)" or "pass" or "pass.";
    }

    private static (long Epoch, CancellationToken Token) BeginTurn(string groupName, CancellationToken callerToken)
    {
        lock (TurnLock)
        {
            if (ActiveTurns.TryGetValue(groupName, out var previous))
            {
                previous.Cancellation.Cancel();
                previous.Cancellation.Dispose();
            }
            var cancellation = CancellationTokenSource.CreateLinkedTokenSource(callerToken);
            var epoch = ++_epoch;
            ActiveTurns[groupName] = (epoch, cancellation);
            return (epoch, cancellation.Token);
        }
    }

    private static async Task EnsureCurrentTurnAsync(string groupName, long epoch, CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        lock (TurnLock)
        {
            if (!ActiveTurns.TryGetValue(groupName, out var active) || active.Epoch != epoch)
            {
                throw new OperationCanceledException(token);
            }
        }
        await Task.CompletedTask;
    }

    private static void EndTurn(string groupName, long epoch, CancellationToken token)
    {
        lock (TurnLock)
        {
            if (ActiveTurns.TryGetValue(groupName, out var active) && active.Epoch == epoch)
            {
                ActiveTurns.Remove(groupName);
                active.Cancellation.Dispose();
            }
        }
    }

    private static string ResolveLabel(AppSettings settings, string connectionId) =>
        HermesBotConnectionStore.Load(settings).FirstOrDefault(connection => string.Equals(connection.Id, connectionId, StringComparison.OrdinalIgnoreCase))?.Label ?? connectionId;

    private static string SafeFailureMessage(Exception exception) =>
        exception is TimeoutException ? "Timeout connessione Hermes." : "Operazione connessione Hermes non riuscita.";

    private static string BuildFollowUpPrompt(string originalUserMessage)
    {
        const string prefix = "Richiesta originale dell'utente:\n";
        const string suffix = "\n\nContinua il turno del gruppo usando il contesto recente.";
        var available = Math.Max(1, 20_000 - prefix.Length - suffix.Length);
        return prefix + originalUserMessage[..Math.Min(available, originalUserMessage.Length)] + suffix;
    }

    private static string Slug(string? value, string fallback)
    {
        var normalized = Regex.Replace((value ?? string.Empty).Trim().ToLowerInvariant(), "[^a-z0-9]+", "-").Trim('-');
        return (normalized.Length == 0 ? fallback : normalized)[..Math.Min(48, (normalized.Length == 0 ? fallback : normalized).Length)];
    }

    private static string Sanitize(string value)
    {
        var normalized = Regex.Replace(value ?? string.Empty, "[^A-Za-z0-9_-]+", "-").Trim('-');
        return string.IsNullOrWhiteSpace(normalized) ? "bot" : normalized;
    }
}
