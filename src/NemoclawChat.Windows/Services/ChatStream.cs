using System.Diagnostics;
using System.Net.Http;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;

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

public abstract record ChatStreamEvent;
public sealed record StreamTextDelta(string Delta) : ChatStreamEvent;
public sealed record StreamThinkingDelta(string Delta) : ChatStreamEvent;
public sealed record StreamToolCallStart(string Id, string Name) : ChatStreamEvent;
public sealed record StreamToolCallArguments(string Id, string Delta) : ChatStreamEvent;
public sealed record StreamToolCallEnd(string Id) : ChatStreamEvent;
public sealed record StreamToolResult(string? Id, string? Name, string Output) : ChatStreamEvent;
public sealed record StreamResponseId(string Id) : ChatStreamEvent;
public sealed record StreamVisualBlocks(IReadOnlyList<VisualBlockRecord> Blocks, int Version) : ChatStreamEvent;
public sealed record StreamRawHermesEvent(string Name, string Json) : ChatStreamEvent;
public sealed record StreamDone(ChatStreamStats Stats, string AccumulatedText, string AccumulatedThinking) : ChatStreamEvent;
public sealed record StreamError(string Message) : ChatStreamEvent;
public sealed record StreamStatus(string Message) : ChatStreamEvent;

public static class ChatStreamClient
{
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
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
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
        bool sawAnyDelta = false;
        bool retriedWithoutPreviousResponseId = false;
        string? lastError = null;
        var nativeMode = HermesHubProtocol.IsNativePreferred(settings);
        await GatewayService.EnsureReachableGatewayAsync(settings);

        if (GatewayService.ShouldUseResponsesFirst(settings, mode))
        {
            yield return new StreamStatus(nativeMode
                ? "Protocollo effettivo: Hermes Native via Responses. Context delegato a Hermes."
                : "Protocollo effettivo: Hermes Responses compat.");
            var serverConversationId = HermesHubProtocol.ServerConversationId(conversationId);
            string BuildResponsesPayload(string? candidatePreviousResponseId) => JsonSerializer.Serialize(new
            {
                model = settings.Model,
                input = prompt,
                instructions = nativeMode ? null : HermesHubProtocol.Instructions(settings, mode),
                store = true,
                stream = true,
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
                        if (!sawAnyDelta)
                        {
                            ttft = stopwatch.Elapsed.TotalMilliseconds;
                            sawAnyDelta = true;
                        }
                        accumulatedText.Append(td.Delta);
                    }
                    else if (ev is StreamThinkingDelta th)
                    {
                        if (!sawAnyDelta)
                        {
                            ttft = stopwatch.Elapsed.TotalMilliseconds;
                            sawAnyDelta = true;
                        }
                        accumulatedThinking.Append(th.Delta);
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
            var chatMessages = (nativeMode
                ? Enumerable.Empty<object>()
                : new object[]
                {
                    new
                    {
                        role = "system",
                        content = HermesHubProtocol.Instructions(settings, mode)
                    }
                }).Concat(history.Select(m => new
                {
                    role = string.Equals(m.Author, "Tu", StringComparison.OrdinalIgnoreCase) ? "user" : "assistant",
                    content = m.Text
                }));
            var chatPayload = JsonSerializer.Serialize(new
            {
                model = settings.Model,
                stream = true,
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
                    if (!sawAnyDelta)
                    {
                        ttft = stopwatch.Elapsed.TotalMilliseconds;
                        sawAnyDelta = true;
                    }
                    accumulatedText.Append(td.Delta);
                }
                else if (ev is StreamThinkingDelta th)
                {
                    if (!sawAnyDelta)
                    {
                        ttft = stopwatch.Elapsed.TotalMilliseconds;
                        sawAnyDelta = true;
                    }
                    accumulatedThinking.Append(th.Delta);
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
                }
                else if (ev is StreamContextUsage cu)
                {
                    contextTokens = cu.ContextTokens ?? contextTokens;
                    contextLength = cu.ContextLength ?? contextLength;
                    contextPercent = cu.ContextPercent ?? contextPercent;
                }

                yield return ev;
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
        double? tps = null;
        if (tokensOut > 0 && totalMs > 0)
        {
            var sinceFirst = ttft.HasValue ? Math.Max(1, totalMs - ttft.Value) : totalMs;
            tps = tokensOut / (sinceFirst / 1000.0);
        }

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
            accumulatedText.ToString(),
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
                        var part = line[5..].TrimStart();
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

    private static IEnumerable<ChatStreamEvent> ParseSseEvent(string? eventName, string data)
    {
        if (string.IsNullOrWhiteSpace(data) || data.Trim() == "[DONE]")
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
                if (!string.IsNullOrWhiteSpace(reasoning))
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
                        if (!string.IsNullOrWhiteSpace(args))
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
                    var id = GetString(item, "call_id") ?? GetString(item, "id") ?? "tool";
                    var result = GetString(item, "result") ?? GetString(item, "output") ?? GetString(item, "content");
                    if (!string.IsNullOrWhiteSpace(result))
                    {
                        yield return new StreamToolResult(id, null, result!);
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
                        yield return ExtractUsage(usage);
                    }

                    var blocks = ExtractVisualBlocksFromElement(resp);
                    if (blocks is { Count: > 0 })
                    {
                        yield return new StreamVisualBlocks(blocks, VisualBlocksContract.Version);
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
                yield return ExtractUsage(usageChat);
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
        IReadOnlyList<VisualBlockRecord>? blocks = null;
        string? responseId = null;
        try
        {
            using var doc = JsonDocument.Parse(body.Trim());
            text = ExtractText(doc.RootElement);
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

    private static StreamUsage ExtractUsage(JsonElement usage)
    {
        int? prompt = null, completion = null;
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
        return new StreamUsage(prompt, completion);
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
            foreach (var key in new[] { "output_text", "text", "content", "message", "reply" })
            {
                if (element.TryGetProperty(key, out var prop))
                {
                    var nested = ExtractText(prop);
                    if (!string.IsNullOrWhiteSpace(nested))
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
                        if (!string.IsNullOrWhiteSpace(nested))
                        {
                            return nested;
                        }
                    }
                    var nested2 = ExtractText(choice);
                    if (!string.IsNullOrWhiteSpace(nested2))
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
                if (!string.IsNullOrWhiteSpace(nested))
                {
                    builder.Append(nested);
                }
            }
            return builder.ToString();
        }

        return string.Empty;
    }

    private static int EstimateTokens(string text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return 0;
        }
        return Math.Max(1, text.Length / 4);
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
}

internal sealed record StreamUsage(int? PromptTokens, int? CompletionTokens) : ChatStreamEvent;
internal sealed record StreamContextUsage(int? ContextTokens, int? ContextLength, int? ContextPercent) : ChatStreamEvent;
