using Windows.Security.Credentials;

namespace NemoclawChat_Windows.Services;

public static class GatewayCredentialStore
{
    private const string Resource = "HermesHub.ApiKey";
    private const string LegacyResource = "ChatClaw.OpenClawGateway";
    private const string UserName = "hermes";
    private const string LegacyUserName = "operator";

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
