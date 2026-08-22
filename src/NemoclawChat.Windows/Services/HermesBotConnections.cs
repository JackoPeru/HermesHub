using System.Text.Json;
using System.Text.RegularExpressions;

namespace NemoclawChat_Windows.Services;

public sealed record HermesBotConnection
{
    public HermesBotConnection(string id, string label, string endpoint, bool enabled = true, bool isPrimary = false)
    {
        Id = id;
        Label = label;
        Endpoint = endpoint;
        Enabled = enabled;
        IsPrimary = isPrimary;
    }

    public string Id { get; set; }
    public string Label { get; set; }
    public string Endpoint { get; set; }
    public bool Enabled { get; set; }
    public bool IsPrimary { get; set; }
}

public static class HermesBotConnectionStore
{
    private static readonly object StoreLock = new();
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private static readonly Regex IdRegex = new("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$", RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static IReadOnlyList<string> _lastLoadWarnings = [];
    private static string StorePath => Path.Combine(AppDataMigration.CurrentDirectoryPath, "bot-connections.json");

    public static IReadOnlyList<string> LastLoadWarnings
    {
        get
        {
            lock (StoreLock) return _lastLoadWarnings.ToArray();
        }
    }

    public static IReadOnlyList<HermesBotConnection> Load(AppSettings settings)
    {
        lock (StoreLock)
        {
            AppDataMigration.Run();
            var warnings = new List<string>();
            var result = new List<HermesBotConnection>
            {
                new("primary", "Gateway principale", NormalizeEndpoint(settings.GatewayUrl), !string.IsNullOrWhiteSpace(settings.GatewayUrl), true)
            };
            result.AddRange(ReadCustom(warnings));
            result = EnforceRegistry(result, warnings);
            _lastLoadWarnings = warnings;

            return result;
        }
    }

    public static HermesBotConnection Add(string label, string endpoint, AppSettings? settings = null)
    {
        var connection = new HermesBotConnection(
            $"connection-{Guid.NewGuid():N}",
            NormalizeLabel(label, "Connessione Hermes"),
            endpoint,
            true,
            false);
        Upsert(connection, settings);
        return connection;
    }

    public static void Upsert(HermesBotConnection connection, AppSettings? settings = null)
    {
        var normalized = Normalize(connection);
        if (normalized.IsPrimary || string.Equals(normalized.Id, "primary", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException("La connessione principale deriva dalle impostazioni esistenti.");
        }

        lock (StoreLock)
        {
            AppDataMigration.Run();
            var existingRegistry = Load(settings ?? AppSettingsStore.Load());
            var items = existingRegistry.Where(item => !item.IsPrimary).ToList();
            items.RemoveAll(item => string.Equals(item.Id, normalized.Id, StringComparison.OrdinalIgnoreCase));
            EnsureUniqueCustomRegistry(
                existingRegistry.Where(item => !string.Equals(item.Id, normalized.Id, StringComparison.OrdinalIgnoreCase)),
                normalized);
            items.Add(normalized with { IsPrimary = false });
            WriteCustom(items);
        }
    }

    public static void Delete(string connectionId)
    {
        ValidateId(connectionId);
        if (string.Equals(connectionId, "primary", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException("La connessione principale non può essere eliminata da qui.");
        }

        lock (StoreLock)
        {
            AppDataMigration.Run();
            var items = ReadCustom(new List<string>());
            items.RemoveAll(item => string.Equals(item.Id, connectionId, StringComparison.OrdinalIgnoreCase));
            WriteCustom(items);
        }
        GatewayCredentialStore.DeleteConnectionSecret(connectionId);
    }

    public static string NormalizeEndpoint(string endpoint)
    {
        var value = (endpoint ?? string.Empty).Trim().TrimEnd('/');
        if (string.IsNullOrWhiteSpace(value)) return string.Empty;
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) || uri is null || uri.Scheme is not ("http" or "https") || string.IsNullOrWhiteSpace(uri.Host) || !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment))
        {
            throw new ArgumentException("Inserisci un endpoint Hermes esplicito http:// o https:// senza credenziali nell'URL.", nameof(endpoint));
        }

        return uri.GetComponents(UriComponents.HttpRequestUrl, UriFormat.UriEscaped).TrimEnd('/');
    }

    private static HermesBotConnection Normalize(HermesBotConnection connection)
    {
        ValidateId(connection.Id);
        var endpoint = NormalizeEndpoint(connection.Endpoint);
        if (string.IsNullOrWhiteSpace(endpoint))
        {
            throw new ArgumentException("L'endpoint della connessione deve essere esplicito.", nameof(connection));
        }
        return connection with
        {
            Id = connection.Id.Trim(),
            Label = NormalizeLabel(connection.Label, connection.Id),
            Endpoint = endpoint,
            Enabled = connection.Enabled,
            IsPrimary = false
        };
    }

    private static string NormalizeLabel(string? label, string fallback) =>
        string.IsNullOrWhiteSpace(label) ? fallback : label.Trim()[..Math.Min(120, label.Trim().Length)];

    private static void ValidateId(string? value)
    {
        if (string.IsNullOrWhiteSpace(value) || !IdRegex.IsMatch(value.Trim()))
        {
            throw new ArgumentException("Identificativo connessione non valido.", nameof(value));
        }
    }

    private static List<HermesBotConnection> ReadCustom(List<string> warnings)
    {
        var content = AtomicJsonFile.Read(StorePath);
        if (string.IsNullOrWhiteSpace(content)) return [];
        try
        {
            using var document = JsonDocument.Parse(content);
            if (document.RootElement.ValueKind != JsonValueKind.Array)
            {
                warnings.Add("Registry connessioni non valido: elenco ignorato.");
                return [];
            }

            var items = new List<HermesBotConnection>();
            var index = 0;
            foreach (var element in document.RootElement.EnumerateArray())
            {
                index++;
                try
                {
                    var item = JsonSerializer.Deserialize<HermesBotConnection>(element.GetRawText())
                        ?? throw new JsonException();
                    if (item.IsPrimary || string.Equals(item.Id, "primary", StringComparison.OrdinalIgnoreCase))
                    {
                        warnings.Add($"Riga connessione {index} ignorata: la primaria è gestita dalle impostazioni.");
                        continue;
                    }
                    items.Add(Normalize(item));
                }
                catch (Exception)
                {
                    warnings.Add($"Riga connessione {index} ignorata: dati non validi.");
                }
            }
            return items;
        }
        catch (JsonException)
        {
            warnings.Add("Registry connessioni illeggibile: righe custom ignorate.");
            return [];
        }
    }

    private static List<HermesBotConnection> EnforceRegistry(List<HermesBotConnection> items, List<string> warnings)
    {
        var result = new List<HermesBotConnection>();
        var ids = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var labels = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var endpoints = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var item in items)
        {
            if (!ids.Add(item.Id) || !labels.Add(item.Label) || (!string.IsNullOrWhiteSpace(item.Endpoint) && !endpoints.Add(item.Endpoint)))
            {
                warnings.Add($"Connessione {item.Id} ignorata: label o endpoint duplicato.");
                continue;
            }
            result.Add(item);
        }
        return result;
    }

    private static void EnsureUniqueCustomRegistry(IEnumerable<HermesBotConnection> existing, HermesBotConnection candidate)
    {
        if (existing.Any(item => string.Equals(item.Label, candidate.Label, StringComparison.OrdinalIgnoreCase) || string.Equals(item.Endpoint, NormalizeEndpoint(candidate.Endpoint), StringComparison.OrdinalIgnoreCase)))
        {
            throw new InvalidOperationException("Label o endpoint già usato da un'altra connessione Hermes.");
        }
    }

    private static void WriteCustom(IEnumerable<HermesBotConnection> items)
    {
        AtomicJsonFile.Write(StorePath, JsonSerializer.Serialize(items.Where(item => !item.IsPrimary), JsonOptions));
    }
}

public sealed record HermesBotGroupMember
{
    public HermesBotGroupMember(string connectionId, string profile, string displayName, string handle, string identityKey)
    {
        ConnectionId = connectionId;
        Profile = profile;
        DisplayName = displayName;
        Handle = handle;
        IdentityKey = identityKey;
    }

    public string ConnectionId { get; set; }
    public string Profile { get; set; }
    public string DisplayName { get; set; }
    public string Handle { get; set; }
    public string IdentityKey { get; set; }
}

public sealed class HermesBotGroup
{
    public string Name { get; set; } = string.Empty;
    public List<HermesBotGroupMember> Members { get; set; } = [];
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
}

public static class HermesBotGroupStore
{
    private static readonly object StoreLock = new();
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private static string StorePath => Path.Combine(AppDataMigration.CurrentDirectoryPath, "bot-groups.json");

    public static IReadOnlyList<HermesBotGroup> Load()
    {
        lock (StoreLock)
        {
            AppDataMigration.Run();
            var content = AtomicJsonFile.Read(StorePath);
            if (string.IsNullOrWhiteSpace(content)) return [];
            try
            {
                return (JsonSerializer.Deserialize<List<HermesBotGroup>>(content) ?? [])
                    .Where(IsValid)
                    .Select(Clone)
                    .OrderByDescending(item => item.UpdatedAt)
                    .ToList();
            }
            catch (JsonException)
            {
                return [];
            }
        }
    }

    public static void Upsert(HermesBotGroup group)
    {
        var normalized = Normalize(group);
        lock (StoreLock)
        {
            AppDataMigration.Run();
            var items = Load().Where(item => !string.Equals(item.Name, normalized.Name, StringComparison.OrdinalIgnoreCase)).ToList();
            items.Add(normalized);
            AtomicJsonFile.Write(StorePath, JsonSerializer.Serialize(items, JsonOptions));
        }
    }

    public static void Delete(string name)
    {
        var normalizedName = NormalizeName(name);
        lock (StoreLock)
        {
            AppDataMigration.Run();
            var items = Load().Where(item => !string.Equals(item.Name, normalizedName, StringComparison.OrdinalIgnoreCase)).ToList();
            AtomicJsonFile.Write(StorePath, JsonSerializer.Serialize(items, JsonOptions));
        }
    }

    public static HermesBotGroup Normalize(HermesBotGroup group)
    {
        var name = NormalizeName(group.Name);
        var members = group.Members ?? [];
        if (members.Count is < 2 or > 6) throw new ArgumentException("Un gruppo Hermes deve contenere da 2 a 6 bot.", nameof(group));
        var normalizedMembers = members.Select(member => member with
        {
            ConnectionId = member.ConnectionId.Trim(),
            Profile = member.Profile.Trim(),
            DisplayName = string.IsNullOrWhiteSpace(member.DisplayName) ? member.Profile.Trim() : member.DisplayName.Trim(),
            Handle = string.IsNullOrWhiteSpace(member.Handle) ? member.Profile.Trim() : member.Handle.Trim(),
            IdentityKey = $"{member.ConnectionId.Trim()}::{member.Profile.Trim()}"
        }).ToList();
        if (normalizedMembers.Any(member => string.IsNullOrWhiteSpace(member.ConnectionId) || string.IsNullOrWhiteSpace(member.Profile)))
        {
            throw new ArgumentException("Ogni membro gruppo deve conservare identità sorgente e profilo.", nameof(group));
        }
        if (normalizedMembers.Select(member => member.IdentityKey).Distinct(StringComparer.OrdinalIgnoreCase).Count() != normalizedMembers.Count)
        {
            throw new ArgumentException("Un gruppo Hermes non può contenere identità duplicate.", nameof(group));
        }

        return new HermesBotGroup
        {
            Name = name,
            Members = normalizedMembers,
            UpdatedAt = DateTimeOffset.UtcNow
        };
    }

    private static string NormalizeName(string? name)
    {
        var normalized = (name ?? string.Empty).Trim();
        if (normalized.Length is < 1 or > 160 || normalized.Contains('\r') || normalized.Contains('\n'))
        {
            throw new ArgumentException("Nome gruppo non valido.", nameof(name));
        }
        return normalized;
    }

    private static bool IsValid(HermesBotGroup group)
    {
        try { _ = Normalize(group); return true; }
        catch (ArgumentException) { return false; }
    }

    private static HermesBotGroup Clone(HermesBotGroup group) => new()
    {
        Name = group.Name,
        Members = [.. group.Members],
        UpdatedAt = group.UpdatedAt
    };
}
