using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace NemoclawChat_Windows.Services;

public static class SpeechGatewayService
{
    private static readonly HttpClient HttpClient = new()
    {
        Timeout = TimeSpan.FromMinutes(3)
    };

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
            speed = 1.0,
            response_format = "wav"
        });

        string? lastError = null;
        foreach (var url in SpeechUrlCandidates(settings))
        {
            foreach (var token in AuthCandidates())
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
                    var path = Path.Combine(Path.GetTempPath(), $"hermes-tts-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}.wav");
                    await using var input = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
                    await using var output = File.Create(path);
                    await input.CopyToAsync(output, cancellationToken).ConfigureAwait(false);
                    if (new FileInfo(path).Length <= 0)
                    {
                        throw new InvalidOperationException("Risposta TTS vuota.");
                    }
                    return path;
                }

                var detail = await SafeReadErrorAsync(response, cancellationToken).ConfigureAwait(false);
                lastError = $"HTTP {(int)response.StatusCode} {response.ReasonPhrase} {detail}".Trim();
                if (response.StatusCode != HttpStatusCode.Unauthorized)
                {
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
                await using var fs = File.OpenRead(filePath);
                using var form = new MultipartFormDataContent();
                using var streamContent = new StreamContent(fs);
                streamContent.Headers.ContentType = MediaTypeHeaderValue.Parse("audio/mp4");
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
                var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
                if (response.IsSuccessStatusCode)
                {
                    using var json = JsonDocument.Parse(body);
                    if (json.RootElement.TryGetProperty("text", out var textProp))
                    {
                        return textProp.GetString()?.Trim() ?? string.Empty;
                    }
                    return string.Empty;
                }

                lastError = $"HTTP {(int)response.StatusCode} {response.ReasonPhrase} {body}".Trim();
                if (response.StatusCode != HttpStatusCode.Unauthorized)
                {
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
            var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            return body.Length > 400 ? body[..400] : body;
        }
        catch
        {
            return string.Empty;
        }
    }

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
            roots.Add(new UriBuilder(uri.Scheme, uri.Host, uri.IsDefaultPort ? 8642 : uri.Port, path).Uri.ToString().TrimEnd('/'));
        }

        roots.Add("http://100.94.223.14:8642/v1");
        roots.Add("http://hermes:8642/v1");
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
