using Windows.Security.Credentials;
using System.Text.RegularExpressions;

namespace NemoclawChat_Windows.Services;

public static class GatewayCredentialStore
{
    private const string Resource = "HermesHub.ApiKey";
    private const string LegacyResource = "ChatClaw.OpenClawGateway";
    private const string UserName = "hermes";
    private const string LegacyUserName = "operator";
    private static readonly Regex ConnectionIdRegex = new("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$", RegexOptions.CultureInvariant | RegexOptions.Compiled);

    // PasswordVault e' wrapper COM thread-safe: cachiamo singleton invece di allocare per call.
    private static readonly Lazy<PasswordVault> SharedVault = new(() => new PasswordVault());
    private static readonly object MutationLock = new();

    public static bool HasSecret()
    {
        return TryLoadSecret(Resource, UserName, out _) ||
               TryLoadSecret(LegacyResource, LegacyUserName, out _);
    }

    public static string LoadSecret()
    {
        if (TryLoadSecret(Resource, UserName, out var secret))
        {
            return secret;
        }

        if (TryLoadSecret(LegacyResource, LegacyUserName, out var legacySecret))
        {
            SaveSecret(legacySecret);
            return legacySecret;
        }

        return string.Empty;
    }

    public static bool SaveSecret(string secret)
    {
        lock (MutationLock)
        {
            try
            {
                if (string.IsNullOrWhiteSpace(secret))
                {
                    DeleteSecret();
                    return true;
                }
                var normalized = secret.Trim();
                var hadPrevious = TryLoadSecret(Resource, UserName, out var previous) ||
                                  TryLoadSecret(LegacyResource, LegacyUserName, out previous);
                var vault = SharedVault.Value;
                vault.Add(new PasswordCredential(Resource, UserName, normalized));
                if (!TryLoadSecret(Resource, UserName, out var saved) || !string.Equals(saved, normalized, StringComparison.Ordinal))
                {
                    if (hadPrevious)
                    {
                        vault.Add(new PasswordCredential(Resource, UserName, previous));
                    }
                    else
                    {
                        RemoveSecret(Resource, UserName);
                    }
                    throw new InvalidOperationException("Verifica credenziale salvata non riuscita.");
                }

                RemoveSecret(LegacyResource, LegacyUserName);
                return true;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Trace.WriteLine($"[GatewayCredentialStore] SaveSecret: {ex.GetType().Name}: {ex.Message}");
                return false;
            }
        }
    }

    public static void DeleteSecret()
    {
        lock (MutationLock)
        {
            RemoveSecret(Resource, UserName);
            RemoveSecret(LegacyResource, LegacyUserName);
        }
    }

    public static bool HasConnectionSecret(string connectionId)
    {
        return TryLoadSecret(ConnectionResource(connectionId), UserName, out _);
    }

    public static string LoadConnectionSecret(string connectionId)
    {
        return TryLoadSecret(ConnectionResource(connectionId), UserName, out var secret) ? secret : string.Empty;
    }

    public static bool SaveConnectionSecret(string connectionId, string secret)
    {
        var resource = ConnectionResource(connectionId);
        lock (MutationLock)
        {
            try
            {
                if (string.IsNullOrWhiteSpace(secret))
                {
                    DeleteConnectionSecret(connectionId);
                    return true;
                }

                var normalized = secret.Trim();
                SharedVault.Value.Add(new PasswordCredential(resource, UserName, normalized));
                if (!TryLoadSecret(resource, UserName, out var saved) || !string.Equals(saved, normalized, StringComparison.Ordinal))
                {
                    RemoveSecret(resource, UserName);
                    throw new InvalidOperationException("Verifica credenziale connessione non riuscita.");
                }

                return true;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Trace.WriteLine($"[GatewayCredentialStore] SaveConnectionSecret: {ex.GetType().Name}");
                return false;
            }
        }
    }

    public static void DeleteConnectionSecret(string connectionId)
    {
        lock (MutationLock)
        {
            RemoveSecret(ConnectionResource(connectionId), UserName);
        }
    }

    private static string ConnectionResource(string connectionId)
    {
        var normalized = (connectionId ?? string.Empty).Trim();
        if (!ConnectionIdRegex.IsMatch(normalized) || string.Equals(normalized, "primary", StringComparison.OrdinalIgnoreCase))
        {
            throw new ArgumentException("Identificativo connessione non valido.", nameof(connectionId));
        }

        return $"HermesHub.Connection.{normalized}";
    }

    private static void RemoveSecret(string resource, string userName)
    {
        try
        {
            var vault = SharedVault.Value;
            vault.Remove(vault.Retrieve(resource, userName));
        }
        catch (Exception ex) when (IsCredentialNotFound(ex))
        {
        }
        catch (Exception ex)
        {
            System.Diagnostics.Trace.WriteLine($"[GatewayCredentialStore] DeleteSecret: {ex.GetType().Name}: {ex.Message}");
        }
    }

    private static bool TryLoadSecret(string resource, string userName, out string secret)
    {
        secret = string.Empty;
        try
        {
            var credential = SharedVault.Value.Retrieve(resource, userName);
            credential.RetrievePassword();
            secret = credential.Password ?? string.Empty;
            return !string.IsNullOrWhiteSpace(secret);
        }
        catch (Exception ex) when (IsCredentialNotFound(ex))
        {
            return false;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Trace.WriteLine($"[GatewayCredentialStore] LoadSecret: {ex.GetType().Name}: {ex.Message}");
            return false;
        }
    }

    private static bool IsCredentialNotFound(Exception exception) =>
        exception.HResult == unchecked((int)0x80070490);
}
