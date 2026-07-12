using System.Diagnostics;
using System.Net.Http;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace NemoclawChat_Windows.Services;

public sealed record ChatStreamStats(
    double? TimeToFirstTokenMs,
    double? TotalMs,
    int? TokensOut,
    double? TokensPerSecond,
    int? PromptTokens,
    int? ContextTokens = null,
    int? ContextLength = null,
    int? ContextPercent = null);

public sealed record ChatInputAttachment(string FileName, string MimeType, string DataUrl, long SizeBytes);
internal sealed record UploadedAttachmentRef(string FileName, string MimeType, string? Path, string? MediaUrl, string? Error);

public abstract record ChatStreamEvent;
public sealed record StreamTextDelta(string Delta) : ChatStreamEvent;
public sealed record StreamThinkingDelta(string Delta) : ChatStreamEvent;
public sealed record StreamToolCallStart(string Id, string Name) : ChatStreamEvent;
public sealed record StreamToolCallArguments(string Id, string Delta) : ChatStreamEvent;
public sealed record StreamToolCallEnd(string Id) : ChatStreamEvent;
public sealed record StreamToolResult(string? Id, string? Name, string Output) : ChatStreamEvent;
public sealed record StreamResponseId(string Id) : ChatStreamEvent;
public sealed record StreamRunId(string Id) : ChatStreamEvent;
public sealed record StreamVisualBlocks(IReadOnlyList<VisualBlockRecord> Blocks, int Version) : ChatStreamEvent;
public sealed record StreamRawHermesEvent(string Name, string Json) : ChatStreamEvent;
public sealed record StreamDone(ChatStreamStats Stats, string AccumulatedText, string AccumulatedThinking) : ChatStreamEvent;
public sealed record StreamError(string Message) : ChatStreamEvent;
public sealed record StreamStatus(string Message) : ChatStreamEvent;
public sealed record StreamPromptProgress(
    int Percent,
    string Label = "llama.cpp: prefill prompt",
    bool Estimated = false,
    int? ProcessedTokens = null,
    int? TotalTokens = null,
    int? CachedTokens = null,
    double? TimeMs = null) : ChatStreamEvent;

public static class ChatStreamClient
{
    private const int StreamAccumMaxChars = 2_000_000;
    private static readonly Regex CompleteThinkBlockRegex = new(@"<\s*think\s*>.*?<\s*/\s*think\s*>", RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex OpenThinkToEndRegex = new(@"<\s*think\s*>.*\z", RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex ThinkTagRegex = new(@"<\s*/?\s*think\s*>", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);

    private static readonly HttpClient StreamClient = new()
    {
        Timeout = TimeSpan.FromMinutes(60)
    };

    public static async IAsyncEnumerable<ChatStreamEvent> StreamChatAsync(
        AppSettings settings,
        string mode,
        string prompt,
        IReadOnlyList<ChatMessageRecord> history,
        string? conversationId,
        string? previousResponseId,
        IReadOnlyList<ChatInputAttachment>? attachments = null,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        var thinkExtractor = new ThinkExtractor();
        var stopwatch = Stopwatch.StartNew();
        string? responseId = null;
        var accumulatedText = new StringBuilder();
        var accumulatedThinking = new StringBuilder();
        IReadOnlyList<VisualBlockRecord>? visualBlocks = null;
        double? ttft = null;
        int? promptTokens = null;
        int? completionTokens = null;
        int? contextTokens = null;
        int? contextLength = null;
        int? contextPercent = null;
        double? serverTokensPerSecond = null;
        bool sawAnyDelta = false;
        bool retriedWithoutPreviousResponseId = false;
        string? lastError = null;
        var nativeMode = HermesHubProtocol.IsNativePreferred(settings);
        attachments ??= Array.Empty<ChatInputAttachment>();
        await GatewayService.EnsureReachableGatewayAsync(settings);
        var (promptForModel, uploadedRefs) = await BuildPromptWithAttachmentToolRefsAsync(settings, prompt, attachments, cancellationToken);
        var payloadAttachments = uploadedRefs > 0
            ? attachments.Where(attachment => !attachment.MimeType.StartsWith("image/", StringComparison.OrdinalIgnoreCase)).ToArray()
            : attachments;
        if (uploadedRefs > 0)
        {
            yield return new StreamStatus($"Allegati caricati sul gateway per tool vision: {uploadedRefs}.");
        }

        if (string.Equals(mode, "Agente", StringComparison.OrdinalIgnoreCase))
        {
            await foreach (var ev in RunDetachedAgentAsync(settings, promptForModel, history, conversationId, payloadAttachments, cancellationToken))
            {
                yield return ev;
            }
            yield break;
        }

        if (GatewayService.ShouldUseResponsesFirst(settings, mode))
        {
            yield return new StreamStatus(nativeMode
                ? "Protocollo effettivo: Hermes Native via Responses. Context delegato a Hermes."
                : "Protocollo effettivo: Hermes Responses compat.");
            yield return new StreamStatus("llama.cpp: prefill prompt...");
            var serverConversationId = HermesHubProtocol.ServerConversationId(conversationId);
            string BuildResponsesPayload(string? candidatePreviousResponseId) => JsonSerializer.Serialize(new
            {
                model = settings.Model,
                input = BuildResponsesInput(promptForModel, payloadAttachments),
                instructions = HermesHubProtocol.Instructions(settings, mode),
                store = true,
                stream = true,
                return_progress = true,
                timings_per_token = true,
                conversation = serverConversationId,
                previous_response_id = string.IsNullOrWhiteSpace(serverConversationId) && !string.IsNullOrWhiteSpace(candidatePreviousResponseId) ? candidatePreviousResponseId : null,
                metadata = HermesHubProtocol.Metadata(settings, conversationId: conversationId)
            });

            var responsesUrl = $"{settings.GatewayUrl.TrimEnd('/')}/responses";
            var previousCandidates = string.IsNullOrWhiteSpace(previousResponseId)
                ? new string?[] { null }
                : [previousResponseId, null];
            foreach (var candidatePreviousResponseId in previousCandidates)
            {
                if (candidatePreviousResponseId is null && !string.IsNullOrWhiteSpace(previousResponseId))
                {
                    retriedWithoutPreviousResponseId = true;
                    yield return new StreamStatus("Contesto server rifiutato. Riprovo senza previous_response_id...");
                }

                lastError = null;
                await foreach (var ev in OpenStreamAsync(
                                   responsesUrl,
                                   BuildResponsesPayload(candidatePreviousResponseId),
                                   "Hermes Responses API stream",
                                   true,
                                   cancellationToken))
                {
                    if (ev is StreamError err)
                    {
                        lastError = err.Message;
                        break;
                    }

                    if (ev is StreamTextDelta td)
                    {
                        var tokenAt = stopwatch.Elapsed.TotalMilliseconds;
                        if (!sawAnyDelta)
                        {
                            ttft = tokenAt;
                            sawAnyDelta = true;
                        }
                        foreach (var extractedEvent in thinkExtractor.Process(td.Delta))
                        {
                            if (extractedEvent is StreamTextDelta etd)
                            {
                                AppendBounded(accumulatedText, etd.Delta);
                                yield return etd;
                            }
                            else if (extractedEvent is StreamThinkingDelta eth)
                            {
                                AppendBounded(accumulatedThinking, eth.Delta);
                                yield return eth;
                            }
                        }
                        continue;
                    }
                    else if (ev is StreamThinkingDelta th)
                    {
                        if (!sawAnyDelta)
                        {
                            ttft = stopwatch.Elapsed.TotalMilliseconds;
                            sawAnyDelta = true;
                        }
                        AppendBounded(accumulatedThinking, th.Delta);
                        yield return th;
                    }
                    else if (ev is StreamResponseId rid)
                    {
                        responseId = rid.Id;
                    }
                    else if (ev is StreamVisualBlocks vb)
                    {
                        visualBlocks = vb.Blocks;
                    }
                    else if (ev is StreamUsage u)
                    {
                        promptTokens = u.PromptTokens;
                        completionTokens = u.CompletionTokens;
                        serverTokensPerSecond = ValidateTokensPerSecond(u.TokensPerSecond) ?? serverTokensPerSecond;
                    }
                    else if (ev is StreamContextUsage cu)
                    {
                        contextTokens = cu.ContextTokens ?? contextTokens;
                        contextLength = cu.ContextLength ?? contextLength;
                        contextPercent = cu.ContextPercent ?? contextPercent;
                    }

                    yield return ev;
                }

                if (sawAnyDelta || lastError is null)
                {
                    break;
                }

                if (candidatePreviousResponseId is not null && GatewayService.IsRecoverablePreviousResponseError(lastError))
                {
                    continue;
                }

                break;
            }

            foreach (var extractedEvent in thinkExtractor.Flush())
            {
                if (extractedEvent is StreamTextDelta etd)
                {
                    AppendBounded(accumulatedText, etd.Delta);
                    yield return etd;
                }
                else if (extractedEvent is StreamThinkingDelta eth)
                {
                    AppendBounded(accumulatedThinking, eth.Delta);
                    yield return eth;
                }
            }
        }

        if (nativeMode && settings.StrictNativeMode && !sawAnyDelta)
        {
            yield return new StreamError($"Strict native mode: Hermes Native/Responses non disponibile. {lastError ?? "stream vuoto"}");
            yield break;
        }

        if (!sawAnyDelta)
        {
            if (nativeMode)
            {
                yield return new StreamStatus("Fallback compat: Chat Completions. Strict native mode disattivato.");
            }
            else if (lastError is not null)
            {
                yield return new StreamStatus($"Responses API non disponibile, fallback Chat Completions: {lastError}");
            }
            else
            {
                yield return new StreamStatus("Protocollo effettivo: Hermes Chat Completions compat.");
            }
            var serverConversationId = HermesHubProtocol.ServerConversationId(conversationId);
            var chatMessages = new List<Dictionary<string, object?>>();
            if (!nativeMode)
            {
                chatMessages.Add(new Dictionary<string, object?>
                {
                    ["role"] = "system",
                    ["content"] = HermesHubProtocol.Instructions(settings, mode)
                });
            }
            for (var i = 0; i < history.Count; i++)
            {
                var m = history[i];
                var isLastUser = i == history.Count - 1 && string.Equals(m.Author, "Tu", StringComparison.OrdinalIgnoreCase);
                chatMessages.Add(new Dictionary<string, object?>
                {
                    ["role"] = string.Equals(m.Author, "Tu", StringComparison.OrdinalIgnoreCase) ? "user" : "assistant",
                    ["content"] = isLastUser ? BuildChatCompletionsContent(promptForModel, payloadAttachments) : m.Text
                });
            }
            var chatPayload = JsonSerializer.Serialize(new
            {
                model = settings.Model,
                stream = true,
                return_progress = true,
                timings_per_token = true,
                session_id = serverConversationId,
                metadata = HermesHubProtocol.Metadata(settings, conversationId: conversationId),
                messages = chatMessages
            });

            var chatUrl = $"{settings.GatewayUrl.TrimEnd('/')}/chat/completions";
            await foreach (var ev in OpenStreamAsync(
                               chatUrl,
                               chatPayload,
                               "Hermes Chat Completions stream",
                               true,
                               cancellationToken,
                               serverConversationId))
            {
                if (ev is StreamError err)
                {
                    lastError = err.Message;
                    break;
                }

                if (ev is StreamTextDelta td)
                {
                    var tokenAt = stopwatch.Elapsed.TotalMilliseconds;
                    if (!sawAnyDelta)
                    {
                        ttft = tokenAt;
                        sawAnyDelta = true;
                    }
                    foreach (var extractedEvent in thinkExtractor.Process(td.Delta))
                    {
                        if (extractedEvent is StreamTextDelta etd)
                        {
                            AppendBounded(accumulatedText, etd.Delta);
                            yield return etd;
                        }
                        else if (extractedEvent is StreamThinkingDelta eth)
                        {
                            AppendBounded(accumulatedThinking, eth.Delta);
                            yield return eth;
                        }
                    }
                    continue;
                }
                else if (ev is StreamThinkingDelta th)
                {
                    if (!sawAnyDelta)
                    {
                        ttft = stopwatch.Elapsed.TotalMilliseconds;
                        sawAnyDelta = true;
                    }
                    AppendBounded(accumulatedThinking, th.Delta);
                    yield return th;
                }
                else if (ev is StreamResponseId rid)
                {
                    responseId = rid.Id;
                }
                else if (ev is StreamVisualBlocks vb)
                {
                    visualBlocks = vb.Blocks;
                }
                else if (ev is StreamUsage u)
                {
                    promptTokens = u.PromptTokens;
                    completionTokens = u.CompletionTokens;
                    serverTokensPerSecond = ValidateTokensPerSecond(u.TokensPerSecond) ?? serverTokensPerSecond;
                }
                else if (ev is StreamContextUsage cu)
                {
                    contextTokens = cu.ContextTokens ?? contextTokens;
                    contextLength = cu.ContextLength ?? contextLength;
                    contextPercent = cu.ContextPercent ?? contextPercent;
                }

                yield return ev;
            }

            foreach (var extractedEvent in thinkExtractor.Flush())
            {
                if (extractedEvent is StreamTextDelta etd)
                {
                    AppendBounded(accumulatedText, etd.Delta);
                    yield return etd;
                }
                else if (extractedEvent is StreamThinkingDelta eth)
                {
                    AppendBounded(accumulatedThinking, eth.Delta);
                    yield return eth;
                }
            }
        }

        stopwatch.Stop();

        if (!sawAnyDelta)
        {
            yield return new StreamError(lastError ?? "Stream Hermes vuoto.");
            yield break;
        }

        var totalMs = stopwatch.Elapsed.TotalMilliseconds;
        var tokensOut = completionTokens ?? EstimateTokens(accumulatedText.ToString());
        var tps = serverTokensPerSecond;

        if (responseId is not null)
        {
            yield return new StreamResponseId(responseId);
        }
        else if (retriedWithoutPreviousResponseId)
        {
            yield return new StreamResponseId(string.Empty);
        }

        yield return new StreamDone(
            new ChatStreamStats(ttft, totalMs, tokensOut, tps, promptTokens, contextTokens, contextLength, contextPercent),
            StripReasoningArtifacts(accumulatedText.ToString()),
            accumulatedThinking.ToString());
    }

    private static async IAsyncEnumerable<ChatStreamEvent> OpenStreamAsync(
        string url,
        string jsonPayload,
        string label,
        bool allowCompatAuth,
        [EnumeratorCancellation] CancellationToken cancellationToken,
        string? sessionId = null)
    {
        var authCandidates = GatewayService.BuildHermesAuthCandidates(allowCompatAuth).ToArray();
        for (var attempt = 0; attempt < authCandidates.Length; attempt++)
        {
            yield return new StreamStatus($"{label}: connessione stream...");
            using var request = new HttpRequestMessage(HttpMethod.Post, url)
            {
                Content = new StringContent(jsonPayload, Encoding.UTF8, "application/json")
            };
            request.Headers.TryAddWithoutValidation("Accept", "text/event-stream, application/json");
            request.Headers.TryAddWithoutValidation("User-Agent", "HermesHub-Windows");
            if (!string.IsNullOrWhiteSpace(authCandidates[attempt]))
            {
                request.Headers.TryAddWithoutValidation("Authorization", $"Bearer {authCandidates[attempt]}");
            }
            if (!string.IsNullOrWhiteSpace(sessionId))
            {
                request.Headers.TryAddWithoutValidation("X-Hermes-Session-Id", sessionId);
            }

            HttpResponseMessage? response = null;
            string? sendError = null;
            try
            {
                response = await StreamClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            }
            catch (Exception ex)
            {
                sendError = ex.Message;
            }

            if (sendError is not null || response is null)
            {
                yield return new StreamError($"{label}: {sendError ?? "errore sconosciuto"}");
                yield break;
            }

            if (!response.IsSuccessStatusCode)
            {
                var body = await response.Content.ReadAsStringAsync(cancellationToken);
                var canRetry = attempt < authCandidates.Length - 1 &&
                               GatewayService.ShouldRetryWithBearerAuth((int)response.StatusCode, body);
                if (canRetry)
                {
                    yield return new StreamStatus("API key Hermes non accettata. Riprovo automaticamente...");
                    continue;
                }
                yield return new StreamError($"{label}: HTTP {(int)response.StatusCode} {response.ReasonPhrase}: {Trim(body)}");
                yield break;
            }

            try
            {
                yield return new StreamStatus("Prompt inviato. Attendo primo token...");
                var mediaType = response.Content.Headers.ContentType?.MediaType ?? string.Empty;
                var isSse = mediaType.Contains("event-stream", StringComparison.OrdinalIgnoreCase);

                if (!isSse)
                {
                    var body = await response.Content.ReadAsStringAsync(cancellationToken);
                    foreach (var ev in FallbackParseFullBody(body))
                    {
                        yield return ev;
                    }
                    yield break;
                }

                await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
                using var reader = new StreamReader(stream, Encoding.UTF8);

                const int SSE_EVENT_MAX_CHARS = 10 * 1024 * 1024;
                const long SSE_TOTAL_MAX_BYTES = 50L * 1024 * 1024;
                long totalReadBytes = 0;
                var dataBuilder = new StringBuilder();
                string? eventName = null;
                bool eventOverflow = false;

                while (!reader.EndOfStream)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    var line = await reader.ReadLineAsync(cancellationToken);
                    if (line is null)
                    {
                        break;
                    }
                    totalReadBytes += Encoding.UTF8.GetByteCount(line) + 1;
                    if (totalReadBytes > SSE_TOTAL_MAX_BYTES)
                    {
                        yield return new StreamError("Stream totale > 50MB, interrotto.");
                        yield break;
                    }

                    if (line.Length == 0)
                    {
                        if (eventOverflow)
                        {
                            dataBuilder.Clear();
                            eventOverflow = false;
                            eventName = null;
                            yield return new StreamError("SSE event > 10MB, scartato.");
                            continue;
                        }
                        if (dataBuilder.Length > 0)
                        {
                            var data = dataBuilder.ToString();
                            dataBuilder.Clear();
                            var ev = eventName;
                            eventName = null;
                            foreach (var parsed in ParseSseEvent(ev, data))
                            {
                                yield return parsed;
                            }
                        }
                        continue;
                    }

                    if (line.StartsWith(":", StringComparison.Ordinal))
                    {
                        continue;
                    }

                    if (line.StartsWith("event:", StringComparison.OrdinalIgnoreCase))
                    {
                        eventName = line[6..].Trim();
                        continue;
                    }

                    if (line.StartsWith("data:", StringComparison.OrdinalIgnoreCase))
                    {
                        if (eventOverflow) continue;
                        var part = line[5..];
                        if (part.StartsWith(' '))
                        {
                            part = part[1..];
                        }
                        if (dataBuilder.Length + part.Length + 1 > SSE_EVENT_MAX_CHARS)
                        {
                            eventOverflow = true;
                            continue;
                        }
                        if (dataBuilder.Length > 0)
                        {
                            dataBuilder.Append('\n');
                        }
                        dataBuilder.Append(part);
                        continue;
                    }
                }

                if (dataBuilder.Length > 0)
                {
                    foreach (var parsed in ParseSseEvent(eventName, dataBuilder.ToString()))
                    {
                        yield return parsed;
                    }
                }

                yield break;
            }
            finally
            {
                response.Dispose();
            }
        }

        yield return new StreamError($"{label}: errore sconosciuto");
    }

    private static async IAsyncEnumerable<ChatStreamEvent> RunDetachedAgentAsync(
        AppSettings settings,
        string prompt,
        IReadOnlyList<ChatMessageRecord> history,
        string? conversationId,
        IReadOnlyList<ChatInputAttachment> attachments,
        [EnumeratorCancellation] CancellationToken cancellationToken)
    {
        var serverConversationId = HermesHubProtocol.ServerConversationId(conversationId);
        var payload = JsonSerializer.Serialize(new
        {
            model = settings.Model,
            input = BuildResponsesInput(prompt, attachments),
            instructions = HermesHubProtocol.Instructions(settings, "Agente"),
            session_id = serverConversationId,
            metadata = HermesHubProtocol.Metadata(settings, conversationId: conversationId),
            conversation_history = history
                .Where(m => !string.Equals(m.Author, "Tu", StringComparison.OrdinalIgnoreCase) ||
                            !string.Equals(m.Text, prompt, StringComparison.Ordinal))
                .TakeLast(30)
                .Select(m => new
                {
                    role = string.Equals(m.Author, "Tu", StringComparison.OrdinalIgnoreCase) ? "user" : "assistant",
                    content = m.Text
                })
        });

        yield return new StreamStatus("Modalita Agente: avvio run server-side persistente...");
        var started = await SendJsonAsync(HttpMethod.Post, $"{settings.GatewayUrl.TrimEnd('/')}/runs", payload, true, cancellationToken);
        if (started.StatusCode is < 200 or > 299)
        {
            yield return new StreamError($"Hermes Runs: HTTP {started.StatusCode}: {Trim(started.Body)}");
            yield break;
        }

        string? runId = null;
        try
        {
            using var doc = JsonDocument.Parse(started.Body);
            runId = GetString(doc.RootElement, "run_id") ?? GetString(doc.RootElement, "id");
        }
        catch
        {
            // handled below
        }

        if (string.IsNullOrWhiteSpace(runId))
        {
            yield return new StreamError("Hermes Runs: run_id assente nella risposta.");
            yield break;
        }

        yield return new StreamRunId(runId);
        yield return new StreamStatus($"Run server-side avviata: {runId}. Se chiudi l'app, Hermes continua sul server.");

        string? lastOutput = null;
        ChatStreamStats? finalStats = null;
        var lastStatus = string.Empty;
        var startedAt = Stopwatch.StartNew();
        while (!cancellationToken.IsCancellationRequested)
        {
            await Task.Delay(TimeSpan.FromSeconds(2), cancellationToken);
            var status = await SendJsonAsync(HttpMethod.Get, $"{settings.GatewayUrl.TrimEnd('/')}/runs/{runId}", null, true, cancellationToken);
            if (status.StatusCode == 404)
            {
                yield return new StreamStatus($"Run {runId}: non piu' in cache gateway. Se il lavoro era lungo, puo' essere ancora in esecuzione sul processo Hermes.");
                continue;
            }
            if (status.StatusCode is < 200 or > 299)
            {
                yield return new StreamStatus($"Run {runId}: polling HTTP {status.StatusCode}");
                continue;
            }

            using var doc = JsonDocument.Parse(status.Body);
            var root = doc.RootElement;
            var state = GetString(root, "status") ?? "running";
            var lastEvent = GetString(root, "last_event");
            var message = string.IsNullOrWhiteSpace(lastEvent) ? $"Run {runId}: {state}" : $"Run {runId}: {state} ({lastEvent})";
            if (!string.Equals(message, lastStatus, StringComparison.Ordinal))
            {
                lastStatus = message;
                yield return new StreamStatus(message);
            }

            if (root.TryGetProperty("output", out var outputElement))
            {
                lastOutput = ExtractText(outputElement);
            }
            if (root.TryGetProperty("usage", out var usageElement) && usageElement.ValueKind == JsonValueKind.Object)
            {
                var usage = ExtractUsage(usageElement);
                finalStats = new ChatStreamStats(null, startedAt.Elapsed.TotalMilliseconds, usage.CompletionTokens, usage.TokensPerSecond, usage.PromptTokens);
            }

            if (state is "completed" or "failed" or "cancelled")
            {
                var blocks = VisualBlockParser.ExtractFromResponse(root.GetRawText());
                if (blocks.Count > 0)
                {
                    yield return new StreamVisualBlocks(blocks, VisualBlocksContract.Version);
                }
                if (!string.IsNullOrWhiteSpace(lastOutput))
                {
                    yield return new StreamTextDelta(lastOutput);
                }
                else if (state != "completed")
                {
                    yield return new StreamError(GetString(root, "error") ?? $"Run {runId}: {state}");
                    yield break;
                }
                yield return new StreamDone(
                    finalStats ?? new ChatStreamStats(null, startedAt.Elapsed.TotalMilliseconds, EstimateTokens(lastOutput ?? ""), null, null),
                    lastOutput ?? "",
                    "");
                yield break;
            }
        }
    }

    private static object BuildResponsesInput(string prompt, IReadOnlyList<ChatInputAttachment> attachments)
    {
        if (attachments.Count == 0)
        {
            return prompt;
        }

        return new object[]
        {
            new Dictionary<string, object?>
            {
                ["role"] = "user",
                ["content"] = BuildContentParts(prompt, attachments, includeGenericFiles: true)
            }
        };
    }

    private static List<Dictionary<string, object?>> BuildContentParts(string prompt, IReadOnlyList<ChatInputAttachment> attachments, bool includeGenericFiles)
    {
        var content = new List<Dictionary<string, object?>>
        {
            new()
            {
                ["type"] = "input_text",
                ["text"] = prompt
            }
        };
        foreach (var attachment in attachments)
        {
            if (attachment.MimeType.StartsWith("image/", StringComparison.OrdinalIgnoreCase))
            {
                content.Add(new Dictionary<string, object?>
                {
                    ["type"] = "input_image",
                    ["image_url"] = attachment.DataUrl,
                    ["detail"] = "auto"
                });
                continue;
            }

            if (includeGenericFiles)
            {
                content.Add(new Dictionary<string, object?>
                {
                    ["type"] = "input_file",
                    ["filename"] = attachment.FileName,
                    ["file_data"] = attachment.DataUrl
                });
            }
            else
            {
                content.Add(new Dictionary<string, object?>
                {
                    ["type"] = "text",
                    ["text"] = $"Allegato disponibile solo in Responses API: {attachment.FileName} ({attachment.MimeType}, {attachment.SizeBytes} bytes)."
                });
            }
        }
        return content;
    }

    private static async Task<(string Prompt, int UploadedCount)> BuildPromptWithAttachmentToolRefsAsync(
        AppSettings settings,
        string prompt,
        IReadOnlyList<ChatInputAttachment> attachments,
        CancellationToken cancellationToken)
    {
        var imageAttachments = attachments
            .Where(attachment => attachment.MimeType.StartsWith("image/", StringComparison.OrdinalIgnoreCase))
            .ToArray();
        if (imageAttachments.Length == 0)
        {
            return (prompt, 0);
        }

        var refs = new List<UploadedAttachmentRef>();
        foreach (var attachment in imageAttachments)
        {
            refs.Add(await TryUploadAttachmentAsync(settings, attachment, cancellationToken));
        }

        var uploaded = refs.Where(item => string.IsNullOrWhiteSpace(item.Error)).ToArray();
        if (uploaded.Length == 0)
        {
            return (prompt, 0);
        }

        var sb = new StringBuilder(prompt.TrimEnd());
        sb.AppendLine();
        sb.AppendLine();
        sb.AppendLine("Allegati immagine disponibili per tool vision_analyze sul server Hermes:");
        foreach (var item in uploaded)
        {
            sb.Append("- ");
            sb.Append(item.FileName);
            if (!string.IsNullOrWhiteSpace(item.Path))
            {
                sb.Append(" | image_url da usare nel tool vision_analyze: ");
                sb.Append(item.Path);
            }
            if (!string.IsNullOrWhiteSpace(item.MediaUrl))
            {
                sb.Append(" | URL proxy fallback: ");
                sb.Append(item.MediaUrl);
            }
            sb.AppendLine();
        }
        sb.AppendLine("Se devi vedere/leggere l'immagine, chiama vision_analyze usando esattamente il path server indicato come image_url.");
        sb.AppendLine("Non usare attachment:image/png, None, /tmp/... inventati o URL incompleti. Se vision_analyze fallisce, riporta l'errore tecnico; non concludere che il modello non supporta vision.");
        return (sb.ToString(), uploaded.Length);
    }

    private static async Task<UploadedAttachmentRef> TryUploadAttachmentAsync(
        AppSettings settings,
        ChatInputAttachment attachment,
        CancellationToken cancellationToken)
    {
        var endpoint = $"{settings.GatewayUrl.TrimEnd('/')}/media/upload";
        var payload = JsonSerializer.Serialize(new Dictionary<string, object?>
        {
            ["filename"] = attachment.FileName,
            ["mime_type"] = attachment.MimeType,
            ["data_url"] = attachment.DataUrl
        });

        foreach (var token in GatewayService.BuildHermesAuthCandidates(allowCompatAuth: true))
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, endpoint)
            {
                Content = new StringContent(payload, Encoding.UTF8, "application/json")
            };
            request.Headers.TryAddWithoutValidation("Accept", "application/json");
            request.Headers.TryAddWithoutValidation("User-Agent", "HermesHub-Windows");
            if (!string.IsNullOrWhiteSpace(token))
            {
                request.Headers.TryAddWithoutValidation("Authorization", $"Bearer {token}");
            }

            try
            {
                using var response = await StreamClient.SendAsync(request, HttpCompletionOption.ResponseContentRead, cancellationToken);
                var body = await response.Content.ReadAsStringAsync(cancellationToken);
                if (!response.IsSuccessStatusCode)
                {
                    if (GatewayService.ShouldRetryWithBearerAuth((int)response.StatusCode, body))
                    {
                        continue;
                    }
                    return new UploadedAttachmentRef(attachment.FileName, attachment.MimeType, null, null, $"HTTP {(int)response.StatusCode}");
                }

                using var doc = JsonDocument.Parse(body);
                var root = doc.RootElement;
                var path = GetString(root, "path") ?? GetString(root, "server_path");
                var mediaUrl = GetString(root, "media_url") ?? GetString(root, "url");
                if (!string.IsNullOrWhiteSpace(mediaUrl) && mediaUrl.StartsWith("/", StringComparison.Ordinal))
                {
                    mediaUrl = $"{GatewayOrigin(settings.GatewayUrl)}{mediaUrl}";
                }
                return new UploadedAttachmentRef(
                    GetString(root, "filename") ?? attachment.FileName,
                    GetString(root, "mime_type") ?? attachment.MimeType,
                    path,
                    mediaUrl,
                    null);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                return new UploadedAttachmentRef(attachment.FileName, attachment.MimeType, null, null, ex.Message);
            }
        }

        return new UploadedAttachmentRef(attachment.FileName, attachment.MimeType, null, null, "auth failed");
    }

    private static string GatewayOrigin(string gatewayUrl)
    {
        var root = gatewayUrl.TrimEnd('/');
        return root.EndsWith("/v1", StringComparison.OrdinalIgnoreCase) ? root[..^3] : root;
    }

    private static object BuildChatCompletionsContent(string prompt, IReadOnlyList<ChatInputAttachment> attachments)
    {
        if (attachments.Count == 0)
        {
            return prompt;
        }

        var content = new List<Dictionary<string, object?>>
        {
            new()
            {
                ["type"] = "text",
                ["text"] = prompt
            }
        };

        foreach (var attachment in attachments)
        {
            if (attachment.MimeType.StartsWith("image/", StringComparison.OrdinalIgnoreCase))
            {
                content.Add(new Dictionary<string, object?>
                {
                    ["type"] = "image_url",
                    ["image_url"] = new Dictionary<string, object?>
                    {
                        ["url"] = attachment.DataUrl,
                        ["detail"] = "auto"
                    }
                });
                continue;
            }

            content.Add(new Dictionary<string, object?>
            {
                ["type"] = "text",
                ["text"] = $"Allegato file: {attachment.FileName} ({attachment.MimeType}, {attachment.SizeBytes} bytes). Se serve il contenuto binario, usa Responses API/input_file."
            });
        }

        return content;
    }

    private static async Task<(int StatusCode, string Body)> SendJsonAsync(HttpMethod method, string url, string? jsonPayload, bool allowCompatAuth, CancellationToken cancellationToken)
    {
        var authCandidates = GatewayService.BuildHermesAuthCandidates(allowCompatAuth).ToArray();
        foreach (var token in authCandidates)
        {
            using var request = new HttpRequestMessage(method, url);
            request.Headers.TryAddWithoutValidation("Accept", "application/json");
            request.Headers.TryAddWithoutValidation("User-Agent", "HermesHub-Windows");
            if (!string.IsNullOrWhiteSpace(token))
            {
                request.Headers.TryAddWithoutValidation("Authorization", $"Bearer {token}");
            }
            if (jsonPayload is not null)
            {
                request.Content = new StringContent(jsonPayload, Encoding.UTF8, "application/json");
            }

            using var response = await StreamClient.SendAsync(request, HttpCompletionOption.ResponseContentRead, cancellationToken);
            var body = await response.Content.ReadAsStringAsync(cancellationToken);
            if (GatewayService.ShouldRetryWithBearerAuth((int)response.StatusCode, body))
            {
                continue;
            }
            return ((int)response.StatusCode, body);
        }

        return (0, "Nessuna credenziale Hermes valida.");
    }

    private static IEnumerable<ChatStreamEvent> ParseSseEvent(string? eventName, string data)
    {
        if (data.Length == 0 || data.Trim() == "[DONE]")
        {
            yield break;
        }

        JsonDocument? document = null;
        try
        {
            document = JsonDocument.Parse(data);
        }
        catch (JsonException ex)
        {
            System.Diagnostics.Debug.WriteLine($"[ChatStream] JSON parse fallito ({eventName ?? "<no-event>"}): {ex.Message}. Payload: {data[..Math.Min(120, data.Length)]}");
            document = null;
        }

        if (document is null)
        {
            yield return new StreamTextDelta(data);
            yield break;
        }

        using (document)
        {
            var parsed = ParseEventElement(eventName, document.RootElement).ToList();
            foreach (var ev in parsed)
            {
                yield return ev;
            }
            if (parsed.Count == 0)
            {
                yield return new StreamRawHermesEvent(eventName ?? TryGetString(document.RootElement, "type", "event") ?? "hermes.event", document.RootElement.GetRawText());
            }
        }
    }

    private static IEnumerable<ChatStreamEvent> ParseEventElement(string? eventName, JsonElement element)
    {
        var realPromptProgress = ExtractPromptProgress(element);
        if (realPromptProgress is not null)
        {
            yield return realPromptProgress;
        }
        var realTimingUsage = ExtractUsage(element, element);
        if (realTimingUsage.TokensPerSecond is not null)
        {
            yield return realTimingUsage;
        }

        var type = eventName;
        if (type is null && element.ValueKind == JsonValueKind.Object && element.TryGetProperty("type", out var typeProp) && typeProp.ValueKind == JsonValueKind.String)
        {
            type = typeProp.GetString();
        }

        if (!string.IsNullOrWhiteSpace(type))
        {
            var t = type!.ToLowerInvariant();
            if (t.Contains("hermes.context.usage") || t.Contains("context.usage"))
            {
                yield return new StreamContextUsage(
                    GetInt(element, "context_tokens") ?? GetInt(element, "tokens"),
                    GetInt(element, "context_length") ?? GetInt(element, "max_tokens"),
                    GetInt(element, "context_percent") ?? GetInt(element, "percent"));
                yield break;
            }

            if (t.Contains("prompt.progress") || t.Contains("processing.progress"))
            {
                if (GetBool(element, "estimated") == true)
                {
                    yield break;
                }

                var percent = GetProgressPercent(element);
                if (percent is >= 0 and <= 100)
                {
                    var rawLabel = GetString(element, "label") ?? string.Empty;
                    var label = string.IsNullOrWhiteSpace(rawLabel) ||
                                rawLabel.Contains("processing prompt", StringComparison.OrdinalIgnoreCase)
                        ? "llama.cpp: prefill prompt"
                        : rawLabel;
                    yield return new StreamPromptProgress(percent.Value, label);
                }
                yield break;
            }

            if (t.Contains("hermes.visual_blocks") || t.Contains("visual_blocks"))
            {
                var blocks = ExtractVisualBlocksFromElement(element);
                if (blocks is { Count: > 0 })
                {
                    yield return new StreamVisualBlocks(blocks, VisualBlocksContract.Version);
                }
                yield break;
            }

            if (t.Contains("hermes.tool.progress") || t.Contains("tool.progress"))
            {
                var id = GetString(element, "toolCallId") ?? GetString(element, "call_id") ?? GetString(element, "id") ?? "tool";
                var name = GetString(element, "tool") ?? GetString(element, "name") ?? "tool";
                var status = (GetString(element, "status") ?? string.Empty).ToLowerInvariant();
                var label = GetString(element, "label");
                yield return new StreamToolCallStart(id, name);
                if (!string.IsNullOrWhiteSpace(label) && !string.Equals(label, name, StringComparison.OrdinalIgnoreCase))
                {
                    yield return new StreamToolCallArguments(id, label!);
                }
                var result = GetString(element, "result") ?? GetString(element, "output") ?? GetString(element, "content");
                if (!string.IsNullOrWhiteSpace(result))
                {
                    yield return new StreamToolResult(id, name, result!);
                }
                if (status.Contains("complete") || status.Contains("done") || status.Contains("success") || status.Contains("failed") || status.Contains("error"))
                {
                    yield return new StreamToolCallEnd(id);
                }
                yield break;
            }

            if (t.Contains("reasoning.available"))
            {
                var reasoning = GetString(element, "reasoning") ?? GetString(element, "summary") ?? GetString(element, "text") ?? GetString(element, "preview");
                if (!string.IsNullOrEmpty(reasoning))
                {
                    yield return new StreamThinkingDelta(reasoning!);
                }
                yield break;
            }

            if (t.Contains("output_text.delta") || t.EndsWith(".delta") && t.Contains("output_text"))
            {
                var delta = GetString(element, "delta") ?? GetString(element, "text");
                if (!string.IsNullOrEmpty(delta))
                {
                    yield return new StreamTextDelta(delta);
                }
                yield break;
            }

            if (t.Contains("reasoning") && t.Contains("delta"))
            {
                var delta = GetString(element, "delta") ?? GetString(element, "text");
                if (!string.IsNullOrEmpty(delta))
                {
                    yield return new StreamThinkingDelta(delta);
                }
                yield break;
            }

            if (t.Contains("function_call") && t.Contains("arguments") && t.Contains("delta"))
            {
                var id = GetString(element, "item_id") ?? GetString(element, "id") ?? "tool";
                var delta = GetString(element, "delta") ?? string.Empty;
                yield return new StreamToolCallArguments(id, delta);
                yield break;
            }

            if (t.Contains("output_item.added"))
            {
                if (element.TryGetProperty("item", out var item) && item.ValueKind == JsonValueKind.Object)
                {
                    var itemType = GetString(item, "type") ?? string.Empty;
                    if (itemType.Contains("function", StringComparison.OrdinalIgnoreCase) || itemType.Contains("tool", StringComparison.OrdinalIgnoreCase))
                    {
                        var id = GetString(item, "call_id") ?? GetString(item, "id") ?? "tool";
                        var name = GetString(item, "name") ?? "tool";
                        yield return new StreamToolCallStart(id, name);
                        var args = GetString(item, "arguments");
                        if (!string.IsNullOrEmpty(args))
                        {
                            yield return new StreamToolCallArguments(id, args!);
                        }
                    }
                }
                yield break;
            }

            if (t.Contains("output_item.done") || t.Contains("function_call.completed"))
            {
                if (element.TryGetProperty("item", out var item) && item.ValueKind == JsonValueKind.Object)
                {
                    var itemType = GetString(item, "type") ?? string.Empty;
                    if (itemType.Contains("reasoning", StringComparison.OrdinalIgnoreCase))
                    {
                        var reasoning = ExtractReasoningText(item);
                        if (!string.IsNullOrEmpty(reasoning))
                        {
                            yield return new StreamThinkingDelta(reasoning);
                        }

                        yield break;
                    }

                    if (itemType.Contains("message", StringComparison.OrdinalIgnoreCase))
                    {
                        var blocks = ExtractVisualBlocksFromElement(item);
                        if (blocks is { Count: > 0 })
                        {
                            yield return new StreamVisualBlocks(blocks, VisualBlocksContract.Version);
                        }

                        yield break;
                    }

                    var id = GetString(item, "call_id") ?? GetString(item, "id") ?? "tool";
                    if (itemType.Contains("output", StringComparison.OrdinalIgnoreCase) ||
                        itemType.Contains("result", StringComparison.OrdinalIgnoreCase))
                    {
                        var result = GetString(item, "result") ?? GetString(item, "output") ?? GetString(item, "content");
                        if (!string.IsNullOrWhiteSpace(result))
                        {
                            yield return new StreamToolResult(id, null, result!);
                        }
                    }
                    else if (!itemType.Contains("function", StringComparison.OrdinalIgnoreCase) &&
                             !itemType.Contains("tool", StringComparison.OrdinalIgnoreCase))
                    {
                        yield break;
                    }

                    yield return new StreamToolCallEnd(id);
                }
                yield break;
            }

            if (t.Contains("response.created") || t.Contains("response.started"))
            {
                if (element.TryGetProperty("response", out var resp) && resp.ValueKind == JsonValueKind.Object)
                {
                    var rid = GetString(resp, "id");
                    if (!string.IsNullOrWhiteSpace(rid))
                    {
                        yield return new StreamResponseId(rid!);
                    }
                }
                yield break;
            }

            if (t.Contains("response.completed") || t.Contains("response.done"))
            {
                if (element.TryGetProperty("response", out var resp) && resp.ValueKind == JsonValueKind.Object)
                {
                    var rid = GetString(resp, "id");
                    if (!string.IsNullOrWhiteSpace(rid))
                    {
                        yield return new StreamResponseId(rid!);
                    }

                    if (resp.TryGetProperty("usage", out var usage) && usage.ValueKind == JsonValueKind.Object)
                    {
                        yield return ExtractUsage(usage, resp);
                    }

                    var blocks = ExtractVisualBlocksFromElement(resp);
                    if (blocks is { Count: > 0 })
                    {
                        yield return new StreamVisualBlocks(blocks, VisualBlocksContract.Version);
                    }

                    var reasoning = ExtractReasoningText(resp);
                    if (!string.IsNullOrEmpty(reasoning))
                    {
                        yield return new StreamThinkingDelta(reasoning);
                    }
                }
                yield break;
            }
        }

        // Chat Completions delta shape
        if (element.ValueKind == JsonValueKind.Object && element.TryGetProperty("choices", out var choices) && choices.ValueKind == JsonValueKind.Array)
        {
            foreach (var choice in choices.EnumerateArray())
            {
                if (choice.ValueKind != JsonValueKind.Object)
                {
                    continue;
                }

                if (choice.TryGetProperty("delta", out var delta) && delta.ValueKind == JsonValueKind.Object)
                {
                    var content = GetString(delta, "content");
                    if (!string.IsNullOrEmpty(content))
                    {
                        yield return new StreamTextDelta(content);
                    }
                    var reasoning = GetString(delta, "reasoning") ?? GetString(delta, "reasoning_content");
                    if (!string.IsNullOrEmpty(reasoning))
                    {
                        yield return new StreamThinkingDelta(reasoning);
                    }

                    if (delta.TryGetProperty("tool_calls", out var toolCalls) && toolCalls.ValueKind == JsonValueKind.Array)
                    {
                        foreach (var call in toolCalls.EnumerateArray())
                        {
                            if (call.ValueKind != JsonValueKind.Object)
                            {
                                continue;
                            }
                            var id = GetString(call, "id") ?? "tool";
                            if (call.TryGetProperty("function", out var fn) && fn.ValueKind == JsonValueKind.Object)
                            {
                                var name = GetString(fn, "name");
                                if (!string.IsNullOrWhiteSpace(name))
                                {
                                    yield return new StreamToolCallStart(id, name!);
                                }
                                var args = GetString(fn, "arguments");
                                if (!string.IsNullOrEmpty(args))
                                {
                                    yield return new StreamToolCallArguments(id, args);
                                }
                            }
                        }
                    }
                }

                if (choice.TryGetProperty("finish_reason", out var finish) && finish.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(finish.GetString()))
                {
                    // End of stream message; usage may come on the same chunk or next
                }
            }

            if (element.TryGetProperty("usage", out var usageChat) && usageChat.ValueKind == JsonValueKind.Object)
            {
                yield return ExtractUsage(usageChat, element);
            }

            var blocksChat = ExtractVisualBlocksFromElement(element);
            if (blocksChat is { Count: > 0 })
            {
                yield return new StreamVisualBlocks(blocksChat, VisualBlocksContract.Version);
            }
        }
    }

    private static IEnumerable<ChatStreamEvent> FallbackParseFullBody(string body)
    {
        if (string.IsNullOrWhiteSpace(body))
        {
            yield break;
        }

        string text = string.Empty;
        string reasoning = string.Empty;
        IReadOnlyList<VisualBlockRecord>? blocks = null;
        string? responseId = null;
        try
        {
            using var doc = JsonDocument.Parse(body.Trim());
            text = ExtractText(doc.RootElement);
            reasoning = ExtractReasoningText(doc.RootElement);
            blocks = VisualBlockParser.ExtractFromResponse(body);
            responseId = TryGetString(doc.RootElement, "id", "response_id", "responseId");
        }
        catch
        {
            text = body.Trim();
        }

        if (!string.IsNullOrWhiteSpace(responseId))
        {
            yield return new StreamResponseId(responseId);
        }

        if (!string.IsNullOrEmpty(text))
        {
            yield return new StreamTextDelta(text);
        }

        if (!string.IsNullOrEmpty(reasoning))
        {
            yield return new StreamThinkingDelta(reasoning);
        }

        if (blocks is { Count: > 0 })
        {
            yield return new StreamVisualBlocks(blocks, VisualBlocksContract.Version);
        }
    }

    private static string? GetString(JsonElement element, string key)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(key, out var prop))
        {
            return null;
        }
        if (prop.ValueKind == JsonValueKind.String)
        {
            return prop.GetString();
        }
        if (prop.ValueKind == JsonValueKind.Object && prop.TryGetProperty("value", out var inner) && inner.ValueKind == JsonValueKind.String)
        {
            return inner.GetString();
        }
        return null;
    }

    private static string? TryGetString(JsonElement element, params string[] keys)
    {
        foreach (var k in keys)
        {
            var s = GetString(element, k);
            if (!string.IsNullOrWhiteSpace(s))
            {
                return s;
            }
        }
        return null;
    }

    private static int? GetInt(JsonElement element, string key)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(key, out var prop))
        {
            return null;
        }
        if (prop.ValueKind == JsonValueKind.Number && prop.TryGetInt32(out var value))
        {
            return value;
        }
        if (prop.ValueKind == JsonValueKind.String && int.TryParse(prop.GetString(), out var parsed))
        {
            return parsed;
        }
        return null;
    }

    private static int? GetProgressPercent(JsonElement element)
    {
        var percent = GetInt(element, "percent");
        if (percent is not null)
        {
            return percent.Value;
        }

        var progress = GetDouble(element, "progress");
        if (progress is null)
        {
            return null;
        }

        var value = progress.Value <= 1.0 ? progress.Value * 100.0 : progress.Value;
        return (int)Math.Round(Math.Clamp(value, 0.0, 100.0));
    }

    private static StreamPromptProgress? ExtractPromptProgress(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object ||
            !element.TryGetProperty("prompt_progress", out var progress) ||
            progress.ValueKind != JsonValueKind.Object)
        {
            return null;
        }

        var total = GetInt(progress, "total");
        var processed = GetInt(progress, "processed");
        var cache = GetInt(progress, "cache");
        var timeMs = GetDouble(progress, "time_ms");
        if (total is null || total <= 0 || processed is null)
        {
            return null;
        }

        var percent = (int)Math.Round(Math.Clamp((processed.Value / (double)total.Value) * 100.0, 0.0, 100.0));
        return new StreamPromptProgress(
            percent,
            "llama.cpp: prefill prompt",
            Estimated: false,
            ProcessedTokens: processed,
            TotalTokens: total,
            CachedTokens: cache,
            TimeMs: timeMs);
    }

    private static bool? GetBool(JsonElement element, string key)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(key, out var prop))
        {
            return null;
        }
        if (prop.ValueKind == JsonValueKind.True || prop.ValueKind == JsonValueKind.False)
        {
            return prop.GetBoolean();
        }
        if (prop.ValueKind == JsonValueKind.String && bool.TryParse(prop.GetString(), out var parsed))
        {
            return parsed;
        }
        return null;
    }

    private static StreamUsage ExtractUsage(JsonElement usage, JsonElement? envelope = null)
    {
        int? prompt = null, completion = null;
        double? tokensPerSecond = null;
        if (usage.TryGetProperty("input_tokens", out var p) && p.ValueKind == JsonValueKind.Number)
        {
            prompt = p.GetInt32();
        }
        else if (usage.TryGetProperty("prompt_tokens", out var p2) && p2.ValueKind == JsonValueKind.Number)
        {
            prompt = p2.GetInt32();
        }
        if (usage.TryGetProperty("output_tokens", out var c) && c.ValueKind == JsonValueKind.Number)
        {
            completion = c.GetInt32();
        }
        else if (usage.TryGetProperty("completion_tokens", out var c2) && c2.ValueKind == JsonValueKind.Number)
        {
            completion = c2.GetInt32();
        }
        tokensPerSecond =
            GetDouble(usage, "tokens_per_second") ??
            GetDouble(usage, "predicted_per_second") ??
            GetDouble(usage, "generation_tokens_per_second");
        if (usage.TryGetProperty("timings", out var timings) && timings.ValueKind == JsonValueKind.Object)
        {
            tokensPerSecond ??= GetDouble(timings, "predicted_per_second") ??
                                GetDouble(timings, "tokens_per_second");
        }
        if (envelope is { } outer)
        {
            tokensPerSecond ??= GetDouble(outer, "tokens_per_second") ??
                                GetDouble(outer, "predicted_per_second");
            if (outer.TryGetProperty("timings", out var outerTimings) && outerTimings.ValueKind == JsonValueKind.Object)
            {
                tokensPerSecond ??= GetDouble(outerTimings, "predicted_per_second") ??
                                    GetDouble(outerTimings, "tokens_per_second");
            }
        }
        return new StreamUsage(prompt, completion, ValidateTokensPerSecond(tokensPerSecond));
    }

    private static IReadOnlyList<VisualBlockRecord>? ExtractVisualBlocksFromElement(JsonElement element)
    {
        try
        {
            var json = element.GetRawText();
            return VisualBlockParser.ExtractFromResponse(json);
        }
        catch
        {
            return null;
        }
    }

    private static string ExtractText(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.String)
        {
            return element.GetString() ?? string.Empty;
        }

        if (element.ValueKind == JsonValueKind.Object)
        {
            if (IsNonAssistantTextPayload(element))
            {
                return string.Empty;
            }

            foreach (var key in new[] { "output_text", "text", "content", "message", "reply" })
            {
                if (element.TryGetProperty(key, out var prop))
                {
                    var nested = ExtractText(prop);
                    if (!string.IsNullOrEmpty(nested))
                    {
                        return nested;
                    }
                }
            }
            if (element.TryGetProperty("choices", out var choices) && choices.ValueKind == JsonValueKind.Array)
            {
                foreach (var choice in choices.EnumerateArray())
                {
                    if (choice.TryGetProperty("message", out var msg))
                    {
                        var nested = ExtractText(msg);
                        if (!string.IsNullOrEmpty(nested))
                        {
                            return nested;
                        }
                    }
                    var nested2 = ExtractText(choice);
                    if (!string.IsNullOrEmpty(nested2))
                    {
                        return nested2;
                    }
                }
            }
        }

        if (element.ValueKind == JsonValueKind.Array)
        {
            var builder = new StringBuilder();
            foreach (var item in element.EnumerateArray())
            {
                var nested = ExtractText(item);
                if (!string.IsNullOrEmpty(nested))
                {
                    builder.Append(nested);
                }
            }
            return builder.ToString();
        }

        return string.Empty;
    }

    private static bool IsNonAssistantTextPayload(JsonElement element)
    {
        var type = GetString(element, "type") ?? string.Empty;
        if (type.Contains("reasoning", StringComparison.OrdinalIgnoreCase) ||
            type.Contains("function_call", StringComparison.OrdinalIgnoreCase) ||
            type.Contains("tool", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        return element.TryGetProperty("tool_call_id", out _) ||
               element.TryGetProperty("toolCallId", out _) ||
               element.TryGetProperty("call_id", out _) ||
               element.TryGetProperty("is_error", out _);
    }

    private static string ExtractReasoningText(JsonElement element, bool insideReasoning = false)
    {
        if (element.ValueKind == JsonValueKind.String)
        {
            return insideReasoning ? element.GetString() ?? string.Empty : string.Empty;
        }

        if (element.ValueKind == JsonValueKind.Array)
        {
            var builder = new StringBuilder();
            foreach (var item in element.EnumerateArray())
            {
                builder.Append(ExtractReasoningText(item, insideReasoning));
            }
            return builder.ToString();
        }

        if (element.ValueKind != JsonValueKind.Object)
        {
            return string.Empty;
        }

        var type = GetString(element, "type") ?? string.Empty;
        var nowInsideReasoning = insideReasoning || type.Contains("reasoning", StringComparison.OrdinalIgnoreCase);
        var output = new StringBuilder();

        foreach (var property in element.EnumerateObject())
        {
            var name = property.Name;
            var value = property.Value;
            var childInsideReasoning = nowInsideReasoning || name.Contains("reasoning", StringComparison.OrdinalIgnoreCase);
            if (!childInsideReasoning && IsNonAssistantTextPayload(element))
            {
                continue;
            }

            if (childInsideReasoning ||
                name is "output" or "item" or "response" or "content" or "summary")
            {
                output.Append(ExtractReasoningText(value, childInsideReasoning));
            }
        }

        return output.ToString();
    }

    private static int EstimateTokens(string text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return 0;
        }
        return Math.Max(1, text.Length / 4);
    }

    private static void AppendBounded(StringBuilder builder, string text)
    {
        if (string.IsNullOrEmpty(text) || builder.Length >= StreamAccumMaxChars)
        {
            return;
        }

        var remaining = StreamAccumMaxChars - builder.Length;
        if (text.Length <= remaining)
        {
            builder.Append(text);
            return;
        }

        builder.Append(text, 0, remaining);
        builder.Append("\n\n[…troncato: limite 2000000 caratteri raggiunto.]");
    }

    private static double? ValidateTokensPerSecond(double? value)
    {
        if (!value.HasValue || double.IsNaN(value.Value) || double.IsInfinity(value.Value))
        {
            return null;
        }

        return value.Value is > 0 and <= 70 ? value.Value : null;
    }

    private static double? GetDouble(JsonElement element, string key)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(key, out var prop))
        {
            return null;
        }
        if (prop.ValueKind == JsonValueKind.Number && prop.TryGetDouble(out var value))
        {
            return value;
        }
        return prop.ValueKind == JsonValueKind.String &&
               double.TryParse(prop.GetString(), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var parsed)
            ? parsed
            : null;
    }

    private static string Trim(string body)
    {
        if (string.IsNullOrWhiteSpace(body))
        {
            return string.Empty;
        }
        var t = body.Trim();
        return t.Length > 240 ? t[..240] + "…" : t;
    }

    private static string StripReasoningArtifacts(string text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return string.Empty;
        }

        var cleaned = CompleteThinkBlockRegex.Replace(text, string.Empty);
        cleaned = OpenThinkToEndRegex.Replace(cleaned, string.Empty);
        cleaned = ThinkTagRegex.Replace(cleaned, string.Empty);
        return cleaned.TrimStart('\r', '\n');
    }
}

internal sealed record StreamUsage(int? PromptTokens, int? CompletionTokens, double? TokensPerSecond = null) : ChatStreamEvent;
internal sealed record StreamContextUsage(int? ContextTokens, int? ContextLength, int? ContextPercent) : ChatStreamEvent;

internal sealed class ThinkExtractor
{
    private const int MaxThinkTagFragmentChars = 24;
    private static readonly Regex OpenThinkTagRegex = new(@"<\s*think\s*>", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex CloseThinkTagRegex = new(@"<\s*/\s*think\s*>", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);

    private readonly StringBuilder _buffer = new();
    private bool _inThink;

    public IEnumerable<ChatStreamEvent> Process(string delta)
    {
        if (string.IsNullOrEmpty(delta)) yield break;
        _buffer.Append(delta);
        var text = _buffer.ToString();

        while (true)
        {
            if (!_inThink)
            {
                var start = OpenThinkTagRegex.Match(text);
                if (start.Success)
                {
                    if (start.Index > 0)
                    {
                        yield return new StreamTextDelta(text[..start.Index]);
                    }
                    text = text[(start.Index + start.Length)..];
                    _buffer.Clear();
                    _buffer.Append(text);
                    _inThink = true;
                }
                else
                {
                    int safeLen = Math.Max(0, text.Length - MaxThinkTagFragmentChars);
                    if (safeLen > 0)
                    {
                        yield return new StreamTextDelta(text[..safeLen]);
                        text = text[safeLen..];
                        _buffer.Clear();
                        _buffer.Append(text);
                    }
                    break;
                }
            }
            else
            {
                var end = CloseThinkTagRegex.Match(text);
                if (end.Success)
                {
                    if (end.Index > 0)
                    {
                        yield return new StreamThinkingDelta(text[..end.Index]);
                    }
                    text = text[(end.Index + end.Length)..];
                    _buffer.Clear();
                    _buffer.Append(text);
                    _inThink = false;
                }
                else
                {
                    int safeLen = Math.Max(0, text.Length - MaxThinkTagFragmentChars);
                    if (safeLen > 0)
                    {
                        yield return new StreamThinkingDelta(text[..safeLen]);
                        text = text[safeLen..];
                        _buffer.Clear();
                        _buffer.Append(text);
                    }
                    break;
                }
            }
        }
    }

    public IEnumerable<ChatStreamEvent> Flush()
    {
        var text = _buffer.ToString();
        if (text.Length > 0)
        {
            if (_inThink) yield return new StreamThinkingDelta(text);
            else yield return new StreamTextDelta(text);
        }
        _buffer.Clear();
    }
}
