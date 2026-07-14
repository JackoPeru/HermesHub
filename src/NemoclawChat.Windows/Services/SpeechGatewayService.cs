using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace NemoclawChat_Windows.Services;

public static class SpeechGatewayService
{
    private const long MaxSynthesizedAudioBytes = 100L * 1024 * 1024;
    private const int MaxTranscriptionResponseBytes = 1024 * 1024;
    private const int MaxErrorResponseBytes = 64 * 1024;
    private static readonly HttpClient HttpClient = new()
    {
        Timeout = TimeSpan.FromMinutes(3)
    };

    public static async Task EnsureReadyAsync(
        AppSettings settings,
        CancellationToken cancellationToken = default)
    {
        string? lastError = null;
        foreach (var root in GatewayRoots(settings))
        {
            foreach (var token in AuthCandidates())
            {
                using var request = new HttpRequestMessage(HttpMethod.Get, $"{root}/capabilities");
                request.Headers.TryAddWithoutValidation("Accept", "application/json");
                request.Headers.TryAddWithoutValidation("User-Agent", "HermesHub-Windows-Voice");
                if (!string.IsNullOrWhiteSpace(token))
                {
                    request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
                }

                try
                {
                    using var response = await HttpClient.SendAsync(
                        request,
                        HttpCompletionOption.ResponseHeadersRead,
                        cancellationToken).ConfigureAwait(false);
                    if (response.IsSuccessStatusCode)
                    {
                        return;
                    }
                    lastError = $"HTTP {(int)response.StatusCode} {response.ReasonPhrase}";
                    if (response.StatusCode != HttpStatusCode.Unauthorized)
                    {
                        break;
                    }
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    lastError = ex.Message;
                }
            }
        }

        throw new InvalidOperationException(lastError ?? "Gateway Hermes non raggiungibile.");
    }

    public static async Task<string> SynthesizeToFileAsync(
        AppSettings settings,
        string text,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            throw new InvalidOperationException("Testo TTS vuoto.");
        }

        var payload = JsonSerializer.Serialize(new
        {
            input = text.Trim(),
            voice = "if_sara",
            lang = "it",
            speed = 1.08,
            response_format = "wav"
        });

        string? lastError = null;
        foreach (var url in SpeechUrlCandidates(settings))
        {
            foreach (var token in AuthCandidates())
            {
                try
                {
                    using var request = new HttpRequestMessage(HttpMethod.Post, url);
                    request.Headers.TryAddWithoutValidation("Accept", "audio/wav");
                    request.Headers.TryAddWithoutValidation("User-Agent", "HermesHub-Windows");
                    if (!string.IsNullOrWhiteSpace(token))
                    {
                        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
                    }

                    request.Content = new StringContent(payload, Encoding.UTF8, "application/json");
                    using var response = await HttpClient
                        .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
                        .ConfigureAwait(false);

                    if (response.IsSuccessStatusCode)
                    {
                        return await SaveValidatedWavAsync(response, cancellationToken).ConfigureAwait(false);
                    }

                    var detail = await SafeReadErrorAsync(response, cancellationToken).ConfigureAwait(false);
                    lastError = $"HTTP {(int)response.StatusCode} {response.ReasonPhrase} {detail}".Trim();
                    if (response.StatusCode != HttpStatusCode.Unauthorized)
                    {
                        break;
                    }
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    lastError = ex.Message;
                    break;
                }
            }
        }

        throw new InvalidOperationException(lastError ?? "TTS Kokoro non disponibile.");
    }

    public static async Task<string> TranscribeFileAsync(
        AppSettings settings,
        string filePath,
        CancellationToken cancellationToken = default)
    {
        if (!File.Exists(filePath))
        {
            throw new FileNotFoundException("File audio non trovato.", filePath);
        }

        string? lastError = null;
        foreach (var url in TranscriptionUrlCandidates(settings))
        {
            foreach (var token in AuthCandidates())
            {
                try
                {
                    await using var fs = File.OpenRead(filePath);
                    using var form = new MultipartFormDataContent();
                    using var streamContent = new StreamContent(fs);
                    streamContent.Headers.ContentType = MediaTypeHeaderValue.Parse(AudioContentType(filePath));
                    form.Add(streamContent, "file", Path.GetFileName(filePath));

                    using var request = new HttpRequestMessage(HttpMethod.Post, url);
                    request.Headers.TryAddWithoutValidation("Accept", "application/json");
                    request.Headers.TryAddWithoutValidation("User-Agent", "HermesHub-Windows");
                    if (!string.IsNullOrWhiteSpace(token))
                    {
                        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
                    }
                    request.Content = form;

                    using var response = await HttpClient
                        .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
                        .ConfigureAwait(false);
                    if (response.IsSuccessStatusCode)
                    {
                        var body = await ReadBoundedStringAsync(
                            response.Content,
                            MaxTranscriptionResponseBytes,
                            cancellationToken).ConfigureAwait(false);
                        using var json = JsonDocument.Parse(body);
                        if (json.RootElement.TryGetProperty("text", out var textProp))
                        {
                            return textProp.GetString()?.Trim() ?? string.Empty;
                        }
                        return string.Empty;
                    }

                    var detail = await SafeReadErrorAsync(response, cancellationToken).ConfigureAwait(false);
                    lastError = $"HTTP {(int)response.StatusCode} {response.ReasonPhrase} {detail}".Trim();
                    if (response.StatusCode != HttpStatusCode.Unauthorized)
                    {
                        break;
                    }
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    lastError = ex.Message;
                    break;
                }
            }
        }

        throw new InvalidOperationException(lastError ?? "Trascrizione non disponibile.");
    }

    private static async Task<string> SafeReadErrorAsync(HttpResponseMessage response, CancellationToken cancellationToken)
    {
        try
        {
            var body = await ReadBoundedStringAsync(
                response.Content,
                MaxErrorResponseBytes,
                cancellationToken).ConfigureAwait(false);
            return body.Length > 400 ? body[..400] : body;
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            System.Diagnostics.Trace.WriteLine($"[SpeechGatewayService] Impossibile leggere il dettaglio errore: {ex.Message}");
            return string.Empty;
        }
    }

    private static async Task<string> ReadBoundedStringAsync(
        HttpContent content,
        int maxBytes,
        CancellationToken cancellationToken)
    {
        if (content.Headers.ContentLength is long contentLength && contentLength > maxBytes)
        {
            throw new InvalidOperationException($"Risposta speech oltre il limite di {maxBytes / 1024} KB.");
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
                throw new InvalidOperationException($"Risposta speech oltre il limite di {maxBytes / 1024} KB.");
            }

            buffer.Write(chunk, 0, read);
        }

        var text = ResolveContentEncoding(content).GetString(buffer.GetBuffer(), 0, checked((int)buffer.Length));
        return text.Length > 0 && text[0] == '\uFEFF' ? text[1..] : text;
    }

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
                System.Diagnostics.Trace.WriteLine($"[SpeechGatewayService] Charset non valido '{charset}': {ex.Message}");
            }
        }

        return Encoding.UTF8;
    }

    private static async Task<string> SaveValidatedWavAsync(HttpResponseMessage response, CancellationToken cancellationToken)
    {
        if (response.Content.Headers.ContentLength is > MaxSynthesizedAudioBytes)
        {
            throw new InvalidOperationException("Risposta TTS oltre il limite di 100 MB.");
        }

        var path = Path.Combine(Path.GetTempPath(), $"hermes-tts-{Guid.NewGuid():N}.wav");
        try
        {
            await using var input = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
            await using (var output = new FileStream(
                             path,
                             FileMode.CreateNew,
                             FileAccess.Write,
                             FileShare.None,
                             64 * 1024,
                             FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                var buffer = new byte[64 * 1024];
                long total = 0;
                while (true)
                {
                    var read = await input.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
                    if (read == 0)
                    {
                        break;
                    }

                    total += read;
                    if (total > MaxSynthesizedAudioBytes)
                    {
                        throw new InvalidOperationException("Risposta TTS oltre il limite di 100 MB.");
                    }
                    await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
                }
                await output.FlushAsync(cancellationToken).ConfigureAwait(false);
                output.Flush(flushToDisk: true);
            }

            var header = new byte[12];
            using (var file = File.OpenRead(path))
            {
                if (file.Length < 44 || file.Read(header) != header.Length ||
                    !header.AsSpan(0, 4).SequenceEqual("RIFF"u8) ||
                    !header.AsSpan(8, 4).SequenceEqual("WAVE"u8))
                {
                    throw new InvalidOperationException("Il gateway ha restituito un WAV non valido.");
                }
            }
            return path;
        }
        catch
        {
            try { File.Delete(path); }
            catch (Exception ex) { System.Diagnostics.Trace.WriteLine($"[SpeechGatewayService] Temp cleanup failed: {ex.Message}"); }
            throw;
        }
    }

    private static string AudioContentType(string filePath) => Path.GetExtension(filePath).ToLowerInvariant() switch
    {
        ".m4a" => "audio/mp4",
        ".mp3" => "audio/mpeg",
        ".ogg" or ".opus" => "audio/ogg",
        ".webm" => "audio/webm",
        ".flac" => "audio/flac",
        _ => "audio/wav"
    };

    private static IEnumerable<string> SpeechUrlCandidates(AppSettings settings)
    {
        foreach (var root in GatewayRoots(settings))
        {
            yield return $"{root}/audio/speech";
        }
    }

    private static IEnumerable<string> TranscriptionUrlCandidates(AppSettings settings)
    {
        foreach (var root in GatewayRoots(settings))
        {
            yield return $"{root}/audio/transcriptions";
        }
    }

    private static IEnumerable<string> GatewayRoots(AppSettings settings)
    {
        var roots = new List<string>();
        if (Uri.TryCreate(settings.GatewayUrl, UriKind.Absolute, out var uri) && !string.IsNullOrWhiteSpace(uri.Host))
        {
            var path = uri.AbsolutePath.TrimEnd('/');
            if (string.IsNullOrWhiteSpace(path) || path == "/")
            {
                path = "/v1";
            }
            var builder = new UriBuilder(uri)
            {
                Path = path,
                Query = string.Empty,
                Fragment = string.Empty
            };
            roots.Add(builder.Uri.ToString().TrimEnd('/'));
        }

        roots.Add("http://hermes:8642/v1");
        roots.Add("http://100.94.223.14:8642/v1");
        roots.Add("http://hermes.local:8642/v1");
        return roots.Distinct(StringComparer.OrdinalIgnoreCase);
    }

    private static IEnumerable<string> AuthCandidates()
    {
        var saved = GatewayCredentialStore.LoadSecret();
        if (!string.IsNullOrWhiteSpace(saved))
        {
            yield return saved.Trim();
        }

        if (!string.Equals(saved, GatewayCredentialStore.DefaultApiKey, StringComparison.Ordinal))
        {
            yield return GatewayCredentialStore.DefaultApiKey;
        }
    }
}
