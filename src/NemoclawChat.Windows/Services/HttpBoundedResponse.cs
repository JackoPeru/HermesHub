using System.Text;
using System.Text.Json;

namespace NemoclawChat_Windows.Services;

/// <summary>
/// Lettore bounded condiviso per le risposte HTTP del gateway: un solo
/// algoritmo byte-bound (declared length + accumulo chunked), charset dal
/// header con fallback UTF-8 e strip del BOM. Ogni servizio delega i propri
/// messaggi via label, niente copie private dell'algoritmo.
/// </summary>
internal static class HttpBoundedResponse
{
    public static async Task<string> ReadStringAsync(
        HttpContent content,
        int maxBytes,
        CancellationToken cancellationToken,
        string label = "gateway")
    {
        if (content.Headers.ContentLength is long declared && declared > maxBytes)
        {
            throw OverLimit(label, maxBytes);
        }

        await using var input = await content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using var buffer = new MemoryStream(Math.Min(
            maxBytes,
            (int)Math.Max(0, content.Headers.ContentLength ?? 0)));
        var chunk = new byte[16 * 1024];
        while (true)
        {
            var read = await input.ReadAsync(chunk, cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }

            if (buffer.Length + read > maxBytes)
            {
                throw OverLimit(label, maxBytes);
            }

            buffer.Write(chunk, 0, read);
        }

        var text = ResolveContentEncoding(content).GetString(buffer.GetBuffer(), 0, checked((int)buffer.Length));
        return text.Length > 0 && text[0] == '\uFEFF' ? text[1..] : text;
    }

    public static async Task<JsonDocument> ReadJsonDocumentAsync(
        HttpContent content,
        int maxBytes,
        CancellationToken cancellationToken,
        string label = "metadati release")
    {
        var body = await ReadStringAsync(content, maxBytes, cancellationToken, label).ConfigureAwait(false);
        return JsonDocument.Parse(body);
    }

    private static InvalidDataException OverLimit(string label, int maxBytes) =>
        new($"Risposta {label} oltre il limite di {maxBytes / 1024} KB.");

    private static Encoding ResolveContentEncoding(HttpContent content)
    {
        var charset = content.Headers.ContentType?.CharSet?.Trim('"');
        if (!string.IsNullOrWhiteSpace(charset))
        {
            try
            {
                return Encoding.GetEncoding(charset);
            }
            catch (ArgumentException ex)
            {
                System.Diagnostics.Trace.WriteLine($"[HttpBoundedResponse] Charset non valido '{charset}': {ex.Message}");
            }
        }

        return Encoding.UTF8;
    }
}
