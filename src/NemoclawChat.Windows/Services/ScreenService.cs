using System.Text.Json;

namespace NemoclawChat_Windows.Services;

public sealed record ScreenStatusRecord(
    bool Running,
    string Holder,
    string ViewerId,
    int Width,
    int Height)
{
    public bool HumanInControl => string.Equals(Holder, "human", StringComparison.OrdinalIgnoreCase);
}

public sealed record ScreenTicket(string Ticket, string ViewerId);

/// <summary>
/// Desktop remoto bot via gpu-manager (stesso contratto del client Android):
/// ticket single-use 30s, WS noVNC, takeover/release con viewer_id.
/// Tutti gli endpoint display stanno sul manager :8643 con Bearer.
/// </summary>
public static class ScreenService
{
    public static async Task<ScreenStatusRecord?> GetStatusAsync(AppSettings settings, CancellationToken ct = default)
    {
        (int StatusCode, string Body) res;
        try
        {
            res = await GatewayService.SendManagerRequestAsync(settings, HttpMethod.Get, "/display/status", null, ct);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            return null;
        }
        if (res.StatusCode is < 200 or > 299)
        {
            return null;
        }
        try
        {
            using var doc = JsonDocument.Parse(res.Body);
            var root = doc.RootElement;
            var running = root.TryGetProperty("running", out var r) && r.ValueKind == JsonValueKind.True;
            var holder = root.TryGetProperty("holder", out var h) && h.ValueKind == JsonValueKind.String ? h.GetString() ?? "bot" : "bot";
            var viewerId = root.TryGetProperty("viewer_id", out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? string.Empty : string.Empty;
            var width = 0;
            var height = 0;
            if (root.TryGetProperty("geometry", out var g) && g.ValueKind == JsonValueKind.Object)
            {
                if (g.TryGetProperty("width", out var w) && w.TryGetInt32(out var wi))
                {
                    width = wi;
                }
                if (g.TryGetProperty("height", out var hh) && hh.TryGetInt32(out var hi))
                {
                    height = hi;
                }
            }
            return new ScreenStatusRecord(running, holder, viewerId, width, height);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public static async Task<ScreenTicket?> MintTicketAsync(AppSettings settings, CancellationToken ct = default)
    {
        (int StatusCode, string Body) res;
        try
        {
            res = await GatewayService.SendManagerRequestAsync(settings, HttpMethod.Post, "/display/ticket", "{}", ct);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            return null;
        }
        if (res.StatusCode is < 200 or > 299)
        {
            return null;
        }
        try
        {
            using var doc = JsonDocument.Parse(res.Body);
            var root = doc.RootElement;
            var ticket = root.TryGetProperty("ticket", out var t) && t.ValueKind == JsonValueKind.String ? t.GetString() ?? string.Empty : string.Empty;
            var viewerId = root.TryGetProperty("viewer_id", out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? string.Empty : string.Empty;
            if (string.IsNullOrWhiteSpace(ticket))
            {
                return null;
            }
            return new ScreenTicket(ticket, viewerId);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public static async Task<(bool Ok, string Holder)> TakeoverAsync(AppSettings settings, string? viewerId, CancellationToken ct = default)
    {
        var payload = string.IsNullOrWhiteSpace(viewerId)
            ? "{}"
            : JsonSerializer.Serialize(new { viewer_id = viewerId.Trim() });
        return await LeaseAsync(settings, "/display/takeover", payload, ct);
    }

    public static async Task<(bool Ok, string Holder)> ReleaseAsync(AppSettings settings, string? viewerId, CancellationToken ct = default)
    {
        var payload = string.IsNullOrWhiteSpace(viewerId)
            ? "{}"
            : JsonSerializer.Serialize(new { viewer_id = viewerId.Trim() });
        return await LeaseAsync(settings, "/display/release", payload, ct);
    }

    private static async Task<(bool Ok, string Holder)> LeaseAsync(AppSettings settings, string path, string payload, CancellationToken ct)
    {
        (int StatusCode, string Body) res;
        try
        {
            res = await GatewayService.SendManagerRequestAsync(settings, HttpMethod.Post, path, payload, ct);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            return (false, string.Empty);
        }
        if (res.StatusCode is < 200 or > 299)
        {
            return (false, string.Empty);
        }
        try
        {
            using var doc = JsonDocument.Parse(res.Body);
            var holder = doc.RootElement.TryGetProperty("holder", out var h) && h.ValueKind == JsonValueKind.String
                ? h.GetString() ?? string.Empty : string.Empty;
            return (true, holder);
        }
        catch (JsonException)
        {
            return (true, string.Empty);
        }
    }

    /// <summary>
    /// ws:// o wss:// verso /display/ws col ticket (già URL-safe). Puro.
    /// </summary>
    public static string? BuildWsUrl(string managerHttpBase, string ticket)
    {
        if (string.IsNullOrWhiteSpace(managerHttpBase) || string.IsNullOrWhiteSpace(ticket))
        {
            return null;
        }
        var trimmed = managerHttpBase.Trim().TrimEnd('/');
        string wsBase;
        if (trimmed.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            wsBase = "wss://" + trimmed["https://".Length..];
        }
        else if (trimmed.StartsWith("http://", StringComparison.OrdinalIgnoreCase))
        {
            wsBase = "ws://" + trimmed["http://".Length..];
        }
        else
        {
            return null;
        }
        return $"{wsBase}/display/ws?display_ticket={ticket.Trim()}";
    }

    /// <summary>
    /// Sottotitolo stato come il client Android. Puro.
    /// </summary>
    public static string StatusSubtitle(ScreenStatusRecord? status, bool holding, string? viewerError)
    {
        if (!string.IsNullOrWhiteSpace(viewerError))
        {
            return viewerError;
        }
        if (status is null)
        {
            return "Connessione…";
        }
        if (!status.Running)
        {
            return "Schermo spento: avvialo dal bot o via CLI.";
        }
        if (holding)
        {
            return "Tu hai il controllo";
        }
        return status.HumanInControl ? "Umano al controllo" : "Bot al controllo";
    }
}
