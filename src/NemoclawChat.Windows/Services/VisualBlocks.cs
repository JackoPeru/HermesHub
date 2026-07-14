using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace NemoclawChat_Windows.Services;

public static class VisualBlocksContract
{
    public const int Version = 1;
    public const int MaxBlocks = 20;
    public const int MaxPayloadBytes = 500 * 1024;
    public const int MaxTableColumns = 12;
    public const int MaxTableRows = 100;
    public const int MaxChartSeries = 8;
    public const int MaxChartPointsPerSeries = 200;
    public static readonly string[] VisualModes = ["auto", "always", "never"];
}

public sealed record VisualBlockRecord
{
    [JsonPropertyName("id")]
    public string Id { get; init; } = string.Empty;

    [JsonPropertyName("type")]
    public string Type { get; init; } = string.Empty;

    [JsonPropertyName("title")]
    public string? Title { get; init; }

    [JsonPropertyName("caption")]
    public string? Caption { get; init; }

    [JsonPropertyName("text")]
    public string? Text { get; init; }

    [JsonPropertyName("language")]
    public string? Language { get; init; }

    [JsonPropertyName("filename")]
    public string? Filename { get; init; }

    [JsonPropertyName("code")]
    public string? Code { get; init; }

    [JsonPropertyName("highlight_lines")]
    public List<int> HighlightLines { get; init; } = [];

    [JsonPropertyName("columns")]
    public List<VisualTableColumn> Columns { get; init; } = [];

    [JsonPropertyName("rows")]
    public List<Dictionary<string, JsonElement>> Rows { get; init; } = [];

    [JsonPropertyName("chart_type")]
    public string? ChartType { get; init; }

    [JsonPropertyName("x_label")]
    public string? XLabel { get; init; }

    [JsonPropertyName("y_label")]
    public string? YLabel { get; init; }

    [JsonPropertyName("unit")]
    public string? Unit { get; init; }

    [JsonPropertyName("summary")]
    public string? Summary { get; init; }

    [JsonPropertyName("series")]
    public List<VisualChartSeries> Series { get; init; } = [];

    [JsonPropertyName("source_format")]
    public string? SourceFormat { get; init; }

    [JsonPropertyName("source")]
    public string? Source { get; init; }

    [JsonPropertyName("rendered_media_url")]
    public string? RenderedMediaUrl { get; init; }

    [JsonPropertyName("alt")]
    public string? Alt { get; init; }

    [JsonPropertyName("layout")]
    public string? Layout { get; init; }

    [JsonPropertyName("images")]
    public List<VisualGalleryImage> Images { get; init; } = [];

    [JsonPropertyName("media_url")]
    public string? MediaUrl { get; init; }

    [JsonPropertyName("download_url")]
    public string? DownloadUrl { get; init; }

    [JsonPropertyName("downloadUrl")]
    public string? DownloadUrlCamel { get; init; }

    [JsonPropertyName("url")]
    public string? Url { get; init; }

    [JsonPropertyName("file_url")]
    public string? FileUrl { get; init; }

    [JsonPropertyName("fileUrl")]
    public string? FileUrlCamel { get; init; }

    [JsonPropertyName("media_kind")]
    public string? MediaKind { get; init; }

    [JsonPropertyName("mime_type")]
    public string? MimeType { get; init; }

    [JsonPropertyName("size_bytes")]
    public long? SizeBytes { get; init; }

    [JsonPropertyName("duration_ms")]
    public long? DurationMs { get; init; }

    [JsonPropertyName("thumbnail_url")]
    public string? ThumbnailUrl { get; init; }

    [JsonPropertyName("variant")]
    public string? Variant { get; init; }

    [JsonPropertyName("raw_json")]
    public string? RawJson { get; init; }

    // Ephemeral preview for an attachment the user has just sent. Never archive raw bytes.
    [JsonIgnore]
    public string? LocalDataUrl { get; init; }
}

public sealed record VisualTableColumn
{
    [JsonPropertyName("key")]
    public string Key { get; init; } = string.Empty;

    [JsonPropertyName("label")]
    public string Label { get; init; } = string.Empty;

    [JsonPropertyName("align")]
    public string Align { get; init; } = "left";

    [JsonPropertyName("format")]
    public string Format { get; init; } = "text";

    [JsonPropertyName("sortable")]
    public bool Sortable { get; init; }
}

public sealed record VisualChartSeries
{
    [JsonPropertyName("name")]
    public string Name { get; init; } = string.Empty;

    [JsonPropertyName("points")]
    public List<VisualChartPoint> Points { get; init; } = [];
}

public sealed record VisualChartPoint
{
    [JsonPropertyName("x")]
    public JsonElement X { get; init; }

    [JsonPropertyName("y")]
    public double Y { get; init; }
}

public sealed record VisualGalleryImage
{
    [JsonPropertyName("media_url")]
    public string MediaUrl { get; init; } = string.Empty;

    [JsonPropertyName("alt")]
    public string Alt { get; init; } = string.Empty;

    [JsonPropertyName("caption")]
    public string? Caption { get; init; }
}

public static class VisualBlockParser
{
    private static readonly Regex InlineMediaRegex = new("""MEDIA\s*:\s*\[([^\]]{1,500})]\(([^)\s]{1,1200})\)|!\[([^\]]{1,500})]\(([^)\s]{1,1200})\)""", RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.Compiled);
    private static readonly Regex MediaProxyLineRegex = new("""^\s*(?:Media\s+proxy\s+URL|Media\s+URL|Download\s+URL|File\s+URL|URL\s+media|Link\s+download)\s*:\s*([^\s<>"'`]{1,1200})\s*$""", RegexOptions.IgnoreCase | RegexOptions.Multiline | RegexOptions.Compiled);
    private static readonly Regex RawMediaProxyUrlRegex = new("""(?:https?://[^\s<>)"']+/v1/media/[^\s<>)"']{1,1200}|/v1/media/[^\s<>)"']{1,1200})""", RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly string[] ImageExtensions = [".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp"];
    private static readonly string[] VideoExtensions = [".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi", ".wmv", ".flv", ".mpg", ".mpeg", ".ts", ".m2ts", ".3gp", ".ogv"];
    private static readonly string[] AudioExtensions = [".mp3", ".wav", ".m4a", ".flac", ".ogg"];

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        MaxDepth = 16
    };
    private static readonly JsonDocumentOptions DocumentOptions = new()
    {
        MaxDepth = 16
    };

    private static readonly HashSet<string> BlockTypes = new(StringComparer.OrdinalIgnoreCase)
    {
        "markdown",
        "code",
        "table",
        "chart",
        "diagram",
        "image_gallery",
        "media_file",
        "callout",
        "unknown_block"
    };

    public static IReadOnlyList<VisualBlockRecord> ExtractFromResponse(string body)
    {
        if (string.IsNullOrWhiteSpace(body) || EncodingSize(body) > VisualBlocksContract.MaxPayloadBytes * 3)
        {
            return [];
        }

        var trimmed = body.Trim();
        if (!trimmed.StartsWith('{') && !trimmed.StartsWith('['))
        {
            return [];
        }

        try
        {
            using var document = JsonDocument.Parse(trimmed, DocumentOptions);
            var root = document.RootElement;
            if (root.ValueKind == JsonValueKind.Object &&
                root.TryGetProperty("visual_blocks_version", out var version) &&
                (version.ValueKind != JsonValueKind.Number ||
                 !version.TryGetInt32(out var contractVersion) ||
                 contractVersion != VisualBlocksContract.Version))
            {
                return [];
            }

            if (TryGetPropertyRecursive(root, "visual_blocks", 0, out var blocksElement) &&
                blocksElement.ValueKind == JsonValueKind.Array)
            {
                var raw = blocksElement.GetRawText();
                if (EncodingSize(raw) > VisualBlocksContract.MaxPayloadBytes)
                {
                    return [];
                }

                var blocks = new List<VisualBlockRecord>();
                foreach (var item in blocksElement.EnumerateArray().Take(VisualBlocksContract.MaxBlocks))
                {
                    try
                    {
                        var block = JsonSerializer.Deserialize<VisualBlockRecord>(item.GetRawText(), JsonOptions);
                        if (block is not null)
                        {
                            block = NormalizeBlock(block);
                        }
                        blocks.Add(block is not null && IsValid(block) ? block : ToUnknownBlock(item));
                    }
                    catch (JsonException)
                    {
                        blocks.Add(ToUnknownBlock(item));
                    }
                }
                return blocks;
            }
        }
        catch
        {
            return [];
        }

        return [];
    }

    private static VisualBlockRecord NormalizeBlock(VisualBlockRecord block)
    {
        if (!string.Equals(block.Type, "media_file", StringComparison.OrdinalIgnoreCase))
        {
            return block;
        }

        var mediaUrl = FirstNonBlank(
            block.MediaUrl,
            block.DownloadUrl,
            block.DownloadUrlCamel,
            block.Url,
            block.FileUrl,
            block.FileUrlCamel).TrimEnd('.', ',', ';', ':');
        var filename = FirstNonBlank(block.Filename, InferMediaFilename(block.Title ?? "File Hermes", mediaUrl), "download");
        var mediaKind = NormalizeMediaKind(block.MediaKind, InferMediaKind(filename, mediaUrl));
        var mimeType = FirstNonBlank(block.MimeType, InferMimeType(filename, mediaUrl));
        var alt = FirstNonBlank(block.Alt, block.Title, filename, "File Hermes");

        return block with
        {
            MediaUrl = mediaUrl,
            Filename = filename,
            MediaKind = mediaKind,
            MimeType = mimeType,
            Alt = alt
        };
    }

    private static string NormalizeMediaKind(string? value, string inferred)
    {
        var candidate = (value ?? string.Empty).Trim().ToLowerInvariant().Replace("_", "-");
        return candidate switch
        {
            "image" or "video" or "audio" or "document" => candidate,
            "file" or "attachment" or "download" or "binary" => "document",
            _ => FirstNonBlank(inferred, "document")
        };
    }

    public static IReadOnlyList<VisualBlockRecord> ExtractInlineMediaBlocks(string text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return [];
        }

        var blocks = new List<VisualBlockRecord>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (var match in InlineMediaRegex.Matches(text).Cast<Match>().Take(12))
        {
            var label = FirstNonBlank(match.Groups[1].Value, match.Groups[3].Value, "Media Hermes");
            var url = FirstNonBlank(match.Groups[2].Value, match.Groups[4].Value).TrimEnd('.', ',', ';', ':');
            AddInlineMedia(blocks, seen, label, url);
        }

        foreach (var url in MediaProxyLineRegex.Matches(text).Cast<Match>()
                     .Select(match => match.Groups[1].Value)
                     .Concat(RawMediaProxyUrlRegex.Matches(text).Cast<Match>().Select(match => match.Value))
                     .Select(value => value.TrimEnd('.', ',', ';', ':'))
                     .Where(IsSafeInlineMediaUrl)
                     .Distinct(StringComparer.OrdinalIgnoreCase)
                     .Take(12 - blocks.Count))
        {
            AddInlineMedia(blocks, seen, InferMediaFilename("File Hermes", url), url);
        }

        return blocks;
    }

    public static IReadOnlyList<VisualBlockRecord> CreateLocalAttachmentBlocks(IEnumerable<ChatInputAttachment> attachments)
    {
        return attachments.Take(12).Select((attachment, index) => new VisualBlockRecord
        {
            Id = $"local-media-{index}-{StableInlineId(attachment.FileName, index)}",
            Type = "media_file",
            Title = attachment.FileName,
            Filename = attachment.FileName,
            MediaKind = InferMediaKind(attachment.FileName, attachment.DataUrl),
            MimeType = attachment.MimeType,
            SizeBytes = attachment.SizeBytes,
            Alt = attachment.FileName,
            Caption = "Condiviso con Hermes.",
            LocalDataUrl = attachment.DataUrl
        }).ToArray();
    }

    public static string StripInlineMediaMarkup(string text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return text;
        }

        var cleaned = InlineMediaRegex.Replace(text, string.Empty);
        cleaned = MediaProxyLineRegex.Replace(cleaned, string.Empty);
        cleaned = RawMediaProxyUrlRegex.Replace(cleaned, string.Empty);
        return Regex.Replace(cleaned, @"\n{3,}", "\n\n").Trim();
    }

    private static void AddInlineMedia(List<VisualBlockRecord> blocks, HashSet<string> seen, string label, string url)
    {
        if (blocks.Count >= 12 || string.IsNullOrWhiteSpace(url) || !seen.Add(url))
        {
            return;
        }

        var filename = InferMediaFilename(label, url);
        blocks.Add(new VisualBlockRecord
        {
            Id = $"inline-media-{StableInlineId(url, blocks.Count)}",
            Type = "media_file",
            Title = string.IsNullOrWhiteSpace(filename) ? "File Hermes" : filename,
            MediaUrl = url,
            MediaKind = InferMediaKind(filename, url),
            MimeType = InferMimeType(filename, url),
            Filename = filename,
            Alt = string.IsNullOrWhiteSpace(label) ? filename : label,
            Caption = "File rilevato dal testo Hermes."
        });
    }

    private static string InferMediaFilename(string label, string url)
    {
        var candidate = label.Contains('.', StringComparison.Ordinal) ? label : url.Split('/', '\\').LastOrDefault() ?? string.Empty;
        return candidate.Split('?', '#')[0].Trim();
    }

    private static string InferMediaKind(string filename, string url)
    {
        var value = $"{filename} {url}".ToLowerInvariant();
        if (ImageExtensions.Any(value.Contains))
        {
            return "image";
        }
        if (VideoExtensions.Any(value.Contains))
        {
            return "video";
        }
        if (AudioExtensions.Any(value.Contains))
        {
            return "audio";
        }
        return "document";
    }

    private static string InferMimeType(string filename, string url)
    {
        var value = $"{filename} {url}".ToLowerInvariant();
        return value switch
        {
            var v when v.Contains(".png") => "image/png",
            var v when v.Contains(".jpg") || v.Contains(".jpeg") => "image/jpeg",
            var v when v.Contains(".webp") => "image/webp",
            var v when v.Contains(".gif") => "image/gif",
            var v when v.Contains(".mp4") || v.Contains(".m4v") => "video/mp4",
            var v when v.Contains(".mov") => "video/quicktime",
            var v when v.Contains(".webm") => "video/webm",
            var v when v.Contains(".mkv") => "video/x-matroska",
            var v when v.Contains(".avi") => "video/x-msvideo",
            var v when v.Contains(".wmv") => "video/x-ms-wmv",
            var v when v.Contains(".flv") => "video/x-flv",
            var v when v.Contains(".mpg") || v.Contains(".mpeg") => "video/mpeg",
            var v when v.Contains(".ts") || v.Contains(".m2ts") => "video/mp2t",
            var v when v.Contains(".3gp") => "video/3gpp",
            var v when v.Contains(".ogv") => "video/ogg",
            var v when v.Contains(".mp3") => "audio/mpeg",
            var v when v.Contains(".wav") => "audio/wav",
            var v when v.Contains(".m4a") => "audio/mp4",
            var v when v.Contains(".flac") => "audio/flac",
            var v when v.Contains(".ogg") => "audio/ogg",
            var v when v.Contains(".pdf") => "application/pdf",
            var v when v.Contains(".md") || v.Contains(".markdown") => "text/markdown",
            var v when v.Contains(".txt") => "text/plain",
            var v when v.Contains(".csv") => "text/csv",
            var v when v.Contains(".json") => "application/json",
            var v when v.Contains(".html") || v.Contains(".htm") => "text/html",
            var v when v.Contains(".zip") => "application/zip",
            _ => string.Empty
        };
    }

    private static bool IsSafeInlineMediaUrl(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return false;
        }
        if (value.StartsWith("/v1/media/", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }
        return Uri.TryCreate(value, UriKind.Absolute, out var uri) &&
               uri.Scheme is "http" or "https" &&
               uri.AbsolutePath.StartsWith("/v1/media/", StringComparison.OrdinalIgnoreCase);
    }

    private static string StableInlineId(string value, int index)
    {
        unchecked
        {
            var hash = 17;
            foreach (var ch in value)
            {
                hash = hash * 31 + ch;
            }
            return $"{index}-{(uint)hash:x8}";
        }
    }

    private static string FirstNonBlank(params string?[] values)
    {
        return values.FirstOrDefault(value => !string.IsNullOrWhiteSpace(value)) ?? string.Empty;
    }

    public static bool IsValid(VisualBlockRecord block)
    {
        if (string.IsNullOrWhiteSpace(block.Id) ||
            string.IsNullOrWhiteSpace(block.Type) ||
            !BlockTypes.Contains(block.Type))
        {
            return false;
        }

        return block.Type.ToLowerInvariant() switch
        {
            "markdown" => !string.IsNullOrWhiteSpace(block.Text),
            "code" => !string.IsNullOrWhiteSpace(block.Code) && IsAllowedCodeLanguage(block.Language),
            "table" => block.Columns.Count is > 0 and <= VisualBlocksContract.MaxTableColumns &&
                       block.Rows.Count <= VisualBlocksContract.MaxTableRows,
            "chart" => (block.ChartType is "bar" or "line") &&
                       !string.IsNullOrWhiteSpace(block.Summary) &&
                       block.Series.Count is > 0 and <= VisualBlocksContract.MaxChartSeries &&
                       block.Series.All(series => series.Points.Count is > 0 and <= VisualBlocksContract.MaxChartPointsPerSeries),
            "diagram" => block.SourceFormat == "mermaid" &&
                         !string.IsNullOrWhiteSpace(block.Source) &&
                         !string.IsNullOrWhiteSpace(block.Alt),
            "image_gallery" => block.Images.Count is > 0 and <= 12 &&
                               block.Images.All(image => !string.IsNullOrWhiteSpace(image.MediaUrl) && !string.IsNullOrWhiteSpace(image.Alt)),
            "media_file" => block.MediaKind is "image" or "video" or "audio" or "document" &&
                            (!string.IsNullOrWhiteSpace(block.MediaUrl) || !string.IsNullOrWhiteSpace(block.LocalDataUrl)) &&
                            !string.IsNullOrWhiteSpace(block.Alt),
            "callout" => block.Variant is "info" or "warning" or "error" or "success" &&
                         !string.IsNullOrWhiteSpace(block.Text),
            "unknown_block" => !string.IsNullOrWhiteSpace(block.RawJson),
            _ => false
        };
    }

    public static string JsonValueToText(JsonElement value)
    {
        return value.ValueKind switch
        {
            JsonValueKind.String => value.GetString() ?? string.Empty,
            JsonValueKind.Number => value.GetRawText(),
            JsonValueKind.True => "true",
            JsonValueKind.False => "false",
            JsonValueKind.Null => string.Empty,
            _ => value.GetRawText()
        };
    }

    private static bool TryGetPropertyRecursive(JsonElement element, string name, int depth, out JsonElement value)
    {
        value = default;
        if (depth > 16)
        {
            return false;
        }
        if (element.ValueKind == JsonValueKind.Object)
        {
            if (element.TryGetProperty(name, out value))
            {
                return true;
            }

            foreach (var property in element.EnumerateObject())
            {
                if (TryGetPropertyRecursive(property.Value, name, depth + 1, out value))
                {
                    return true;
                }
            }
        }

        if (element.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in element.EnumerateArray())
            {
                if (TryGetPropertyRecursive(item, name, depth + 1, out value))
                {
                    return true;
                }
            }
        }

        return false;
    }

    private static bool IsAllowedCodeLanguage(string? language)
    {
        return language is "plaintext" or "mermaid" or "powershell" or "bash" or "json" or "xml" or
            "csharp" or "kotlin" or "python" or "javascript" or "typescript" or "sql" or "yaml" or "markdown";
    }

    private static VisualBlockRecord ToUnknownBlock(JsonElement element)
    {
        var id = TryGetString(element, "id") ?? $"unknown-{Guid.NewGuid():N}";
        var type = TryGetString(element, "type") ?? "unknown";
        return new VisualBlockRecord
        {
            Id = id,
            Type = "unknown_block",
            Title = $"Blocco Hermes non renderizzato: {type}",
            Caption = "Payload conservato per compatibilita' forward.",
            RawJson = element.GetRawText()
        };
    }

    private static string? TryGetString(JsonElement element, string key)
    {
        return element.ValueKind == JsonValueKind.Object &&
               element.TryGetProperty(key, out var value) &&
               value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;
    }

    private static int EncodingSize(string value)
    {
        return System.Text.Encoding.UTF8.GetByteCount(value);
    }
}

public static class VisualBlockFixtures
{
    public static bool ShouldAttach(AppSettings settings, string prompt)
    {
        if (settings.VisualBlocksMode.Equals("never", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        if (settings.VisualBlocksMode.Equals("always", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        return prompt.Contains("visual", StringComparison.OrdinalIgnoreCase) ||
               prompt.Contains("file", StringComparison.OrdinalIgnoreCase) ||
               prompt.Contains("condivid", StringComparison.OrdinalIgnoreCase) ||
               prompt.Contains("scaric", StringComparison.OrdinalIgnoreCase) ||
               prompt.Contains("inviami", StringComparison.OrdinalIgnoreCase) ||
               prompt.Contains("diagram", StringComparison.OrdinalIgnoreCase) ||
               prompt.Contains("grafico", StringComparison.OrdinalIgnoreCase) ||
               prompt.Contains("tabella", StringComparison.OrdinalIgnoreCase) ||
               prompt.Contains("spiegazione visiva", StringComparison.OrdinalIgnoreCase);
    }

    public static IReadOnlyList<VisualBlockRecord> Create()
    {
        return
        [
            new VisualBlockRecord
            {
                Id = "fixture-markdown",
                Type = "markdown",
                Title = "Schema operativo",
                Text = "## Hermes Visual Blocks\n- Testo fallback sempre completo\n- Blocchi tipizzati validati\n- Renderer statici sicuri"
            },
            new VisualBlockRecord
            {
                Id = "fixture-code",
                Type = "code",
                Title = "Esempio payload",
                Language = "json",
                Filename = "visual-response.json",
                Code = "{\n  \"output_text\": \"Risposta completa.\",\n  \"visual_blocks_version\": 1\n}",
                HighlightLines = [2]
            },
            new VisualBlockRecord
            {
                Id = "fixture-table",
                Type = "table",
                Title = "Limiti v1",
                Columns =
                [
                    new VisualTableColumn { Key = "item", Label = "Elemento" },
                    new VisualTableColumn { Key = "limit", Label = "Limite", Align = "right" }
                ],
                Rows =
                [
                    Row(("item", "Blocchi"), ("limit", "20")),
                    Row(("item", "Payload"), ("limit", "500 KB")),
                    Row(("item", "Chart"), ("limit", "8x200"))
                ]
            },
            new VisualBlockRecord
            {
                Id = "fixture-chart",
                Type = "chart",
                Title = "Esempio chart",
                ChartType = "bar",
                XLabel = "Piattaforma",
                YLabel = "Copertura",
                Unit = "%",
                Summary = "Windows e Android usano lo stesso contratto Visual Blocks v1.",
                Series =
                [
                    new VisualChartSeries
                    {
                        Name = "Copertura",
                        Points =
                        [
                            Point("Windows", 100),
                            Point("Android", 100),
                            Point("Legacy fallback", 100)
                        ]
                    }
                ]
            },
            new VisualBlockRecord
            {
                Id = "fixture-diagram",
                Type = "diagram",
                Title = "Flusso",
                SourceFormat = "mermaid",
                Source = "graph TD; User-->HermesHub; HermesHub-->HermesAgent; HermesAgent-->VisualBlocks;",
                Alt = "Utente verso Hermes Hub, Hermes Agent e Visual Blocks"
            },
            new VisualBlockRecord
            {
                Id = "fixture-gallery",
                Type = "image_gallery",
                Title = "Media proxy",
                Layout = "grid",
                Images =
                [
                    new VisualGalleryImage { MediaUrl = "/v1/media/example.webp", Alt = "Esempio asset da proxy Hermes", Caption = "Placeholder proxy" }
                ]
            },
            new VisualBlockRecord
            {
                Id = "fixture-media",
                Type = "media_file",
                Title = "File multimediale",
                MediaUrl = "/v1/media/video-demo.mp4",
                MediaKind = "video",
                MimeType = "video/mp4",
                Filename = "video-demo.mp4",
                SizeBytes = 1048576,
                DurationMs = 12000,
                ThumbnailUrl = "/v1/media/video-demo-thumb.webp",
                Alt = "Anteprima video demo",
                Caption = "Video condiviso dall'agente via proxy Hermes"
            },
            new VisualBlockRecord
            {
                Id = "fixture-callout",
                Type = "callout",
                Variant = "info",
                Title = "Sicurezza",
                Text = "Niente HTML, niente JS, niente SVG client-side."
            }
        ];
    }

    private static Dictionary<string, JsonElement> Row(params (string Key, string Value)[] cells)
    {
        return cells.ToDictionary(cell => cell.Key, cell => JsonSerializer.SerializeToElement(cell.Value));
    }

    private static VisualChartPoint Point(string x, double y)
    {
        return new VisualChartPoint { X = JsonSerializer.SerializeToElement(x), Y = y };
    }
}
