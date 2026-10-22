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
    public const int MaxChoiceOptions = 10;
    public const int MaxFormFields = 12;
    public const int MaxChoiceLabelChars = 120;
    public const int MaxFormLabelChars = 80;
    public const int MaxSelectOptions = 10;
    public static readonly string[] VisualModes = ["auto", "always", "never"];
    public static readonly string[] FormFieldKinds = ["text", "multiline", "number", "date", "bool", "select"];
}

/// <summary>
/// Generative UI v1: interfacce dichiarative generate dall'agente dentro
/// visual_blocks (type "genui"). Limiti e grammatica identici al contract
/// test (tests/test_genui_contract.py) e a GenUi.kt su Android.
/// Sicurezza: solo dati, mai codice. Formule = grammatica matematica chiusa.
/// </summary>
public static class GenUiContract
{
    public const int Version = 1;
    public const int MaxNodes = 150;
    public const int MaxDepth = 8;
    public const int MaxTitle = 160;
    public const int MaxLabel = 120;
    public const int MaxText = 4000;
    public const int MaxFallback = 2000;
    public const int MaxFormula = 500;
    public const int MaxUrl = 1200;
    public const int MaxOptions = 20;
    public const int MaxOptionChars = 120;
    public const int MaxStateKeys = 64;
    public const int MaxStateString = 2000;
    public const int MaxDiagramNodes = 40;
    public const int MaxEventBytes = 4096;
    public static readonly Regex IdPattern = new("^[A-Za-z0-9_-]{1,64}$", RegexOptions.Compiled);
    public static readonly Regex KeyPattern = new("^[A-Za-z0-9_.]{1,64}$", RegexOptions.Compiled);
    public static readonly Regex HandlerPattern = new("^on[A-Z]", RegexOptions.Compiled);
    public static readonly HashSet<string> ForbiddenKeys = new(StringComparer.OrdinalIgnoreCase) { "script", "eval" };
    public static readonly HashSet<string> Kinds = new(StringComparer.Ordinal)
    {
        "column", "row", "grid", "card", "tabs", "accordion", "section",
        "text", "markdown", "metric", "progress", "button", "slider", "switch",
        "checkbox", "radio", "select", "input", "number", "date", "textarea",
        "table", "chart", "steps", "nodes", "image", "code", "alert",
        "divider", "spacer",
    };
    public static readonly HashSet<string> CondOps = new(StringComparer.Ordinal)
        { "eq", "neq", "gt", "gte", "lt", "lte", "in", "contains", "empty", "notempty" };
    public static readonly HashSet<string> LocalOps = new(StringComparer.Ordinal)
        { "set", "toggle", "step", "reset", "goto" };
    public static readonly HashSet<string> ChartTypes = new(StringComparer.Ordinal)
        { "line", "bar", "area", "pie", "scatter" };
    public static readonly HashSet<string> AlertVariants = new(StringComparer.Ordinal)
        { "info", "warning", "error", "success" };
    public static readonly HashSet<string> FuncNames = new(StringComparer.Ordinal)
    {
        "min", "max", "clamp", "round", "floor", "ceil", "abs", "sqrt",
        "pow", "sin", "cos", "tan", "log", "exp", "if",
    };
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

    [JsonPropertyName("value")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public JsonElement? Value { get; init; }

    [JsonPropertyName("progress")]
    public double? Progress { get; init; }

    [JsonPropertyName("status")]
    public string? Status { get; init; }

    [JsonPropertyName("action")]
    public string? Action { get; init; }

    [JsonPropertyName("device_name")]
    public string? DeviceName { get; init; }

    [JsonPropertyName("device_kind")]
    public string? DeviceKind { get; init; }

    [JsonPropertyName("device_status")]
    public string? DeviceStatus { get; init; }

    [JsonPropertyName("battery_percent")]
    public double? BatteryPercent { get; init; }

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

    [JsonPropertyName("options")]
    public List<VisualChoiceOption> Options { get; init; } = [];

    [JsonPropertyName("fields")]
    public List<VisualFormField> Fields { get; init; } = [];

    [JsonPropertyName("multi")]
    public bool Multi { get; init; }

    [JsonPropertyName("submit_label")]
    public string? SubmitLabel { get; init; }

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

    // Generative UI v1 (type "genui"): spec dichiarativa + stato. Persistiti
    // in archivio come gli altri campi (lo snapshot di stato viene fuso al salvataggio).
    [JsonPropertyName("genui_version")]
    public int GenuiVersion { get; init; }

    [JsonPropertyName("fallback_text")]
    public string? FallbackText { get; init; }

    [JsonPropertyName("spec")]
    public JsonElement? Spec { get; init; }

    [JsonPropertyName("state")]
    public JsonElement? State { get; init; }
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

public sealed record VisualChoiceOption
{
    [JsonPropertyName("id")]
    public string Id { get; init; } = string.Empty;

    [JsonPropertyName("label")]
    public string Label { get; init; } = string.Empty;

    [JsonPropertyName("detail")]
    public string? Detail { get; init; }
}

public sealed record VisualFormField
{
    [JsonPropertyName("key")]
    public string Key { get; init; } = string.Empty;

    [JsonPropertyName("label")]
    public string Label { get; init; } = string.Empty;

    [JsonPropertyName("kind")]
    public string Kind { get; init; } = "text";

    [JsonPropertyName("required")]
    public bool Required { get; init; }

    [JsonPropertyName("options")]
    public List<string> Options { get; init; } = [];

    [JsonPropertyName("placeholder")]
    public string? Placeholder { get; init; }
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
        "metric",
        "progress",
        "approval",
        "choice",
        "form",
        "device",
        "genui",
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
            "metric" => block.Value is { ValueKind: not JsonValueKind.Undefined and not JsonValueKind.Null } value &&
                        !string.IsNullOrWhiteSpace(JsonValueToText(value)),
            "progress" => block.Progress is >= 0 and <= 1 &&
                          (string.IsNullOrWhiteSpace(block.Status) ||
                           block.Status is "pending" or "active" or "complete" or "blocked" or "failed"),
            "approval" => block.Status is "pending" or "approved" or "rejected" &&
                           !string.IsNullOrWhiteSpace(block.Action),
            "choice" => block.Status is "pending" or "answered" &&
                        block.Options.Count is > 0 and <= VisualBlocksContract.MaxChoiceOptions &&
                        block.Options.All(option =>
                            !string.IsNullOrWhiteSpace(option.Id) &&
                            !string.IsNullOrWhiteSpace(option.Label) &&
                            option.Label.Length <= VisualBlocksContract.MaxChoiceLabelChars),
            "form" => block.Status is "pending" or "submitted" &&
                      block.Fields.Count is > 0 and <= VisualBlocksContract.MaxFormFields &&
                      block.Fields.All(IsValidFormField),
            "device" => !string.IsNullOrWhiteSpace(block.DeviceName) &&
                        block.DeviceStatus is "connected" or "connecting" or "disconnected" or "unknown" &&
                        (!block.BatteryPercent.HasValue || block.BatteryPercent is >= 0 and <= 100),
            "genui" => GenUiValidator.IsValidBlock(block),
            "unknown_block" => !string.IsNullOrWhiteSpace(block.RawJson),
            _ => false
        };
    }

    private static bool IsValidFormField(VisualFormField field)
    {
        if (string.IsNullOrWhiteSpace(field.Key) ||
            string.IsNullOrWhiteSpace(field.Label) ||
            field.Label.Length > VisualBlocksContract.MaxFormLabelChars)
        {
            return false;
        }
        if (!VisualBlocksContract.FormFieldKinds.Contains(field.Kind))
        {
            return false;
        }
        if (string.Equals(field.Kind, "select", StringComparison.OrdinalIgnoreCase))
        {
            return field.Options.Count is > 0 and <= VisualBlocksContract.MaxSelectOptions &&
                   field.Options.All(option => !string.IsNullOrWhiteSpace(option));
        }
        return true;
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
            },
            new VisualBlockRecord
            {
                Id = "fixture-metric",
                Type = "metric",
                Title = "Copertura contratto",
                Value = JsonSerializer.SerializeToElement(100),
                Unit = "%",
                Status = "verified"
            },
            new VisualBlockRecord
            {
                Id = "fixture-progress",
                Type = "progress",
                Title = "Migrazione",
                Progress = 0.75,
                Status = "active",
                Unit = "%",
                Summary = "Tre quarti dei dati sono stati verificati."
            },
            new VisualBlockRecord
            {
                Id = "fixture-approval",
                Type = "approval",
                Title = "Conferma operazione",
                Status = "pending",
                Action = "Approvare la sincronizzazione",
                Text = "L'operazione richiede conferma esplicita."
            },
            new VisualBlockRecord
            {
                Id = "fixture-choice",
                Type = "choice",
                Title = "Quale formato preferisci?",
                Text = "Tocca una risposta: parte come tuo messaggio.",
                Status = "pending",
                Options =
                [
                    new VisualChoiceOption { Id = "pdf", Label = "PDF" },
                    new VisualChoiceOption { Id = "md", Label = "Markdown", Detail = "leggero e modificabile" }
                ]
            },
            new VisualBlockRecord
            {
                Id = "fixture-form",
                Type = "form",
                Title = "Dati consegna",
                Status = "pending",
                SubmitLabel = "Invia dati",
                Fields =
                [
                    new VisualFormField { Key = "nome", Label = "Nome", Kind = "text", Required = true },
                    new VisualFormField { Key = "formato", Label = "Formato", Kind = "select", Options = ["PDF", "Markdown"] }
                ]
            },
            new VisualBlockRecord
            {
                Id = "fixture-device",
                Type = "device",
                Title = "Dispositivo DAT",
                DeviceName = "Ray-Ban Meta",
                DeviceKind = "wearable",
                DeviceStatus = "connected",
                BatteryPercent = 82,
                Summary = "Camera connessa; audio sul telefono."
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

/// <summary>Valore di stato GenUI: scalari soltanto (double/string/bool).</summary>
public sealed record GenUiValue(bool IsNumber, double Number, string? Text, bool Bool)
{
    public static GenUiValue FromJson(JsonElement element) => element.ValueKind switch
    {
        JsonValueKind.Number => new GenUiValue(true, element.GetDouble(), null, false),
        JsonValueKind.String => new GenUiValue(false, double.NaN, element.GetString() ?? string.Empty, false),
        JsonValueKind.True => new GenUiValue(false, double.NaN, null, true),
        JsonValueKind.False => new GenUiValue(false, double.NaN, null, false),
        _ => new GenUiValue(false, double.NaN, null, false),
    };

    public static GenUiValue FromObject(object? value) => value switch
    {
        double d => new GenUiValue(true, d, null, false),
        float f => new GenUiValue(true, f, null, false),
        int i => new GenUiValue(true, i, null, false),
        long l => new GenUiValue(true, l, null, false),
        string s => new GenUiValue(false, double.NaN, s, false),
        bool b => new GenUiValue(false, double.NaN, null, b),
        JsonElement e => FromJson(e),
        _ => new GenUiValue(false, double.NaN, null, false),
    };

    public double AsNumber()
    {
        if (IsNumber) return Number;
        if (Text is not null)
        {
            if (double.TryParse(Text, System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var parsed)) return parsed;
            return double.NaN;
        }
        return Bool ? 1 : 0;
    }

    public string AsText()
    {
        if (IsNumber)
        {
            if (double.IsFinite(Number) && Number == Math.Truncate(Number) && Math.Abs(Number) < 1e15)
                return ((long)Number).ToString(System.Globalization.CultureInfo.InvariantCulture);
            return Number.ToString(System.Globalization.CultureInfo.InvariantCulture);
        }
        if (Text is not null) return Text;
        return Bool ? "true" : "false";
    }

    public JsonElement ToJson()
    {
        if (IsNumber) return JsonSerializer.SerializeToElement(Number);
        if (Text is not null) return JsonSerializer.SerializeToElement(Text);
        return JsonSerializer.SerializeToElement(Bool);
    }
}

/// <summary>
/// Formule GenUI: tokenizer + recursive descent, stessa grammatica del
/// contract test e di GenUiFormula su Android. Mai codice, solo matematica.
/// </summary>
public static class GenUiFormula
{
    internal abstract record Token;
    internal sealed record NumToken(double Value) : Token;
    internal sealed record NameToken(string Value) : Token;
    internal sealed record OpToken(string Value) : Token;

    internal abstract record Node;
    internal sealed record NumNode(double Value) : Node;
    internal sealed record RefNode(string Value) : Node;
    internal sealed record NegNode(Node Inner) : Node;
    internal sealed record BinNode(string Op, Node Left, Node Right) : Node;
    internal sealed record CmpNode(string Op, Node Left, Node Right) : Node;
    internal sealed record CallNode(string Name, List<Node> Args) : Node;

    private static readonly Regex TokenPattern = new(
        @"\s*(?:(\d+(?:\.\d+)?)|([A-Za-z_][A-Za-z0-9_.]*)|(==|!=|>=|<=|[+\-*/%^(),><]))",
        RegexOptions.Compiled);

    public static void Check(string expr)
    {
        Parse(expr);
    }

    internal static Node Parse(string expr)
    {
        if (string.IsNullOrWhiteSpace(expr) || expr.Length > GenUiContract.MaxFormula)
            throw new ArgumentException("formula length");
        var tokens = new List<Token>();
        var pos = 0;
        while (pos < expr.Length)
        {
            var match = TokenPattern.Match(expr, pos);
            if (!match.Success || match.Length == 0 || match.Index != pos)
                throw new ArgumentException($"bad token at {pos}");
            if (match.Groups[1].Success) tokens.Add(new NumToken(double.Parse(match.Groups[1].Value,
                System.Globalization.CultureInfo.InvariantCulture)));
            else if (match.Groups[2].Success) tokens.Add(new NameToken(match.Groups[2].Value));
            else tokens.Add(new OpToken(match.Groups[3].Value));
            pos = match.Index + match.Length;
        }
        var parser = new Parser(tokens);
        var node = parser.ParseComparison();
        if (parser.Position != tokens.Count) throw new ArgumentException("trailing tokens");
        return node;
    }

    internal static GenUiValue Eval(Node node, IReadOnlyDictionary<string, GenUiValue> state,
        IReadOnlyDictionary<string, GenUiValue>? extra = null)
    {
        return node switch
        {
            NumNode n => new GenUiValue(true, n.Value, null, false),
            RefNode r => extra is not null && extra.TryGetValue(r.Value, out var e) ? e
                : state.TryGetValue(r.Value, out var s) ? s : new GenUiValue(true, double.NaN, null, false),
            NegNode n => new GenUiValue(true, -EvalNumber(n.Inner, state, extra), null, false),
            BinNode b => new GenUiValue(true, ApplyBin(b.Op, EvalNumber(b.Left, state, extra), EvalNumber(b.Right, state, extra)), null, false),
            CmpNode c => new GenUiValue(false, double.NaN, null, Compare(c.Op, Eval(c.Left, state, extra), Eval(c.Right, state, extra))),
            CallNode c => EvalCall(c, state, extra),
            _ => new GenUiValue(true, double.NaN, null, false),
        };
    }

    public static double EvalNumber(string expr, IReadOnlyDictionary<string, GenUiValue> state,
        IReadOnlyDictionary<string, GenUiValue>? extra = null)
    {
        try { return Eval(Parse(expr), state, extra).AsNumber(); }
        catch { return double.NaN; }
    }

    public static string EvalText(string expr, IReadOnlyDictionary<string, GenUiValue> state,
        IReadOnlyDictionary<string, GenUiValue>? extra = null)
    {
        try { return Eval(Parse(expr), state, extra).AsText(); }
        catch { return string.Empty; }
    }

    private static double EvalNumber(Node node, IReadOnlyDictionary<string, GenUiValue> state,
        IReadOnlyDictionary<string, GenUiValue>? extra) => Eval(node, state, extra).AsNumber();

    private static double ApplyBin(string op, double left, double right) => op switch
    {
        "+" => left + right,
        "-" => left - right,
        "*" => left * right,
        "/" => left / right,
        "%" => left % right,
        "^" => Math.Pow(left, right),
        _ => double.NaN,
    };

    private static bool Compare(string op, GenUiValue left, GenUiValue right)
    {
        var ln = left.AsNumber();
        var rn = right.AsNumber();
        if (double.IsFinite(ln) && double.IsFinite(rn) && left.Text is null && right.Text is null)
        {
            return op switch
            {
                "==" => ln == rn,
                "!=" => ln != rn,
                ">" => ln > rn,
                ">=" => ln >= rn,
                "<" => ln < rn,
                "<=" => ln <= rn,
                _ => false,
            };
        }
        return op switch
        {
            "==" => left.AsText() == right.AsText(),
            "!=" => left.AsText() != right.AsText(),
            _ => false,
        };
    }

    private static GenUiValue EvalCall(CallNode node, IReadOnlyDictionary<string, GenUiValue> state,
        IReadOnlyDictionary<string, GenUiValue>? extra)
    {
        if (node.Name == "if")
        {
            if (node.Args.Count != 3) throw new ArgumentException("if needs 3 args");
            var cond = Eval(node.Args[0], state, extra);
            var truthy = cond.Bool || (cond.IsNumber && double.IsFinite(cond.Number) && cond.Number != 0) ||
                         (cond.Text is not null && cond.Text.Length > 0);
            return Eval(truthy ? node.Args[1] : node.Args[2], state, extra);
        }
        var args = node.Args.Select(arg => EvalNumber(arg, state, extra)).ToList();
        static double First(List<double> values) => values.Count > 0 ? values[0] : double.NaN;
        var result = node.Name switch
        {
            "min" => args.Count > 0 ? args.Min() : double.NaN,
            "max" => args.Count > 0 ? args.Max() : double.NaN,
            "clamp" => args.Count == 3 ? Math.Clamp(args[0], args[1], args[2]) : double.NaN,
            "round" => Math.Round(First(args)),
            "floor" => Math.Floor(First(args)),
            "ceil" => Math.Ceiling(First(args)),
            "abs" => Math.Abs(First(args)),
            "sqrt" => Math.Sqrt(First(args)),
            "pow" => args.Count == 2 ? Math.Pow(args[0], args[1]) : double.NaN,
            "sin" => Math.Sin(First(args)),
            "cos" => Math.Cos(First(args)),
            "tan" => Math.Tan(First(args)),
            "log" => Math.Log(First(args)),
            "exp" => Math.Exp(First(args)),
            _ => double.NaN,
        };
        return new GenUiValue(true, result, null, false);
    }

    internal sealed class Parser(List<Token> tokens)
    {
        public int Position { get; private set; }

        public Node ParseComparison()
        {
            var node = ParseAdditive();
            while (PeekOp("==", "!=", ">", ">=", "<", "<=") is string op)
            {
                Position++;
                node = new CmpNode(op, node, ParseAdditive());
            }
            return node;
        }

        private Node ParseAdditive()
        {
            var node = ParseTerm();
            while (PeekOp("+", "-") is string op)
            {
                Position++;
                node = new BinNode(op, node, ParseTerm());
            }
            return node;
        }

        private Node ParseTerm()
        {
            var node = ParsePower();
            while (PeekOp("*", "/", "%") is string op)
            {
                Position++;
                node = new BinNode(op, node, ParsePower());
            }
            return node;
        }

        private Node ParsePower()
        {
            var node = ParseUnary();
            if (PeekOp("^") is not null)
            {
                Position++;
                node = new BinNode("^", node, ParsePower());
            }
            return node;
        }

        private Node ParseUnary()
        {
            if (PeekOp("-") is not null)
            {
                Position++;
                return new NegNode(ParseUnary());
            }
            return ParsePrimary();
        }

        private Node ParsePrimary()
        {
            if (Position >= tokens.Count) throw new ArgumentException("unexpected end");
            var token = tokens[Position];
            if (token is NumToken num)
            {
                Position++;
                return new NumNode(num.Value);
            }
            if (token is NameToken name)
            {
                Position++;
                if (!GenUiContract.FuncNames.Contains(name.Value) && !GenUiContract.KeyPattern.IsMatch(name.Value))
                    throw new ArgumentException($"bad identifier {name.Value}");
                if (PeekOp("(") is not null)
                {
                    if (!GenUiContract.FuncNames.Contains(name.Value))
                        throw new ArgumentException($"unknown function {name.Value}");
                    Position++;
                    var args = new List<Node>();
                    if (PeekOp(")") is null)
                    {
                        args.Add(ParseComparison());
                        while (PeekOp(",") is not null)
                        {
                            Position++;
                            args.Add(ParseComparison());
                        }
                    }
                    if (PeekOp(")") is null) throw new ArgumentException("missing )");
                    Position++;
                    return new CallNode(name.Value, args);
                }
                return new RefNode(name.Value);
            }
            if (token is OpToken op && op.Value == "(")
            {
                Position++;
                var node = ParseComparison();
                if (PeekOp(")") is null) throw new ArgumentException("missing )");
                Position++;
                return node;
            }
            throw new ArgumentException($"unexpected {token}");
        }

        private string? PeekOp(params string[] candidates)
        {
            if (Position < tokens.Count && tokens[Position] is OpToken op && candidates.Contains(op.Value))
                return op.Value;
            return null;
        }
    }
}

/// <summary>Validatore GenUI: APPROVA oppure RIFIUTA (fallback). Mai eccezioni verso il renderer.</summary>
public static class GenUiValidator
{
    public static bool IsValidBlock(VisualBlockRecord block)
    {
        try
        {
            if (!GenUiContract.IdPattern.IsMatch(block.Id ?? string.Empty)) return false;
            if (block.GenuiVersion != GenUiContract.Version) return false;
            if (string.IsNullOrWhiteSpace(block.FallbackText) || block.FallbackText.Length > GenUiContract.MaxFallback)
                return false;
            if (block.State is { ValueKind: JsonValueKind.Object } state)
            {
                var keys = 0;
                foreach (var property in state.EnumerateObject())
                {
                    keys++;
                    if (!GenUiContract.KeyPattern.IsMatch(property.Name)) return false;
                    var value = property.Value;
                    if (value.ValueKind is JsonValueKind.Object or JsonValueKind.Array) return false;
                    if (value.ValueKind == JsonValueKind.String && (value.GetString()?.Length ?? 0) > GenUiContract.MaxStateString)
                        return false;
                    if (value.ValueKind == JsonValueKind.Number && !double.IsFinite(value.GetDouble())) return false;
                }
                if (keys > GenUiContract.MaxStateKeys) return false;
            }
            else if (block.State is not null && block.State.Value.ValueKind != JsonValueKind.Null)
            {
                return false;
            }
            if (block.Spec is not { ValueKind: JsonValueKind.Object } spec) return false;
            var counter = new Counter();
            CheckNode(spec, 1, counter);
            return counter.Count <= GenUiContract.MaxNodes;
        }
        catch
        {
            return false;
        }
    }

    public static Dictionary<string, GenUiValue> InitialState(JsonElement? state)
    {
        var result = new Dictionary<string, GenUiValue>(StringComparer.Ordinal);
        if (state is { ValueKind: JsonValueKind.Object } obj)
        {
            foreach (var property in obj.EnumerateObject())
            {
                if (property.Value.ValueKind is JsonValueKind.Number or JsonValueKind.String or JsonValueKind.True or JsonValueKind.False)
                    result[property.Name] = GenUiValue.FromJson(property.Value);
            }
        }
        return result;
    }

    public static void CheckUrl(string url)
    {
        if (string.IsNullOrWhiteSpace(url) || url.Length > GenUiContract.MaxUrl)
            throw new ArgumentException("url length");
        var low = url.Trim().ToLowerInvariant();
        if (low.StartsWith("javascript:", StringComparison.Ordinal) ||
            low.StartsWith("data:", StringComparison.Ordinal) ||
            low.StartsWith("vbscript:", StringComparison.Ordinal) ||
            low.StartsWith("file:", StringComparison.Ordinal))
            throw new ArgumentException("url scheme");
        var trimmed = url.Trim();
        if (!trimmed.StartsWith("/v1/media/", StringComparison.Ordinal) &&
            !trimmed.StartsWith("/v1/image/", StringComparison.Ordinal) &&
            !low.StartsWith("https://", StringComparison.Ordinal))
            throw new ArgumentException("url allowlist");
    }

    internal sealed class Counter
    {
        public int Count;
        public readonly HashSet<string> Ids = new(StringComparer.Ordinal);
    }

    private static void CheckNode(JsonElement node, int depth, Counter counter)
    {
        if (node.ValueKind != JsonValueKind.Object) throw new ArgumentException("node shape");
        foreach (var property in node.EnumerateObject())
        {
            if (GenUiContract.HandlerPattern.IsMatch(property.Name) ||
                GenUiContract.ForbiddenKeys.Contains(property.Name))
                throw new ArgumentException("handler key");
        }
        var kind = GetString(node, "kind") ?? string.Empty;
        if (!GenUiContract.Kinds.Contains(kind)) throw new ArgumentException($"kind {kind}");
        var nodeId = GetString(node, "id") ?? string.Empty;
        if (!GenUiContract.IdPattern.IsMatch(nodeId)) throw new ArgumentException("node id");
        if (!counter.Ids.Add(nodeId)) throw new ArgumentException("duplicate id");
        counter.Count++;
        if (depth > GenUiContract.MaxDepth) throw new ArgumentException("node nested too deep");
        CheckCapped(GetString(node, "title"), GenUiContract.MaxTitle, "title");
        CheckCapped(GetString(node, "label"), GenUiContract.MaxLabel, "label");
        CheckCapped(GetString(node, "text"), GenUiContract.MaxText, "text");
        foreach (var formulaKey in new[] { "formula", "points_formula", "y_formula" })
        {
            if (node.TryGetProperty(formulaKey, out var formula) && formula.ValueKind == JsonValueKind.String)
                GenUiFormula.Check(formula.GetString() ?? string.Empty);
        }
        foreach (var condKey in new[] { "visible_if", "enabled_if" })
        {
            if (node.TryGetProperty(condKey, out var cond) && cond.ValueKind == JsonValueKind.Object)
                CheckCondition(cond);
        }
        if (node.TryGetProperty("bind", out var bind) && bind.ValueKind == JsonValueKind.String &&
            !GenUiContract.KeyPattern.IsMatch(bind.GetString() ?? string.Empty))
            throw new ArgumentException("bind");
        if (node.TryGetProperty("action", out var action) && action.ValueKind == JsonValueKind.Object)
            CheckAction(action);
        if (node.TryGetProperty("src", out var src) && src.ValueKind == JsonValueKind.String)
            CheckUrl(src.GetString() ?? string.Empty);
        if (node.TryGetProperty("options", out var options) && options.ValueKind == JsonValueKind.Array)
        {
            var count = 0;
            foreach (var opt in options.EnumerateArray())
            {
                count++;
                var text = opt.ValueKind == JsonValueKind.Object
                    ? opt.TryGetProperty("label", out var label) && label.ValueKind == JsonValueKind.String ? label.GetString() ?? string.Empty
                      : opt.TryGetProperty("value", out var val) ? val.GetRawText() : string.Empty
                    : opt.ValueKind == JsonValueKind.String ? opt.GetString() ?? string.Empty : opt.GetRawText();
                if (string.IsNullOrWhiteSpace(text) || text.Length > GenUiContract.MaxOptionChars)
                    throw new ArgumentException("option");
            }
            if (count == 0 || count > GenUiContract.MaxOptions) throw new ArgumentException("options");
        }
        else if (kind is "radio" or "select")
        {
            throw new ArgumentException("options required");
        }
        if (kind == "table")
        {
            if (node.TryGetProperty("filter", out var filter) &&
                filter.ValueKind is not JsonValueKind.True and not JsonValueKind.False)
                throw new ArgumentException("filter");
            if (!node.TryGetProperty("columns", out var columns) || columns.ValueKind != JsonValueKind.Array)
                throw new ArgumentException("columns");
            var count = 0;
            foreach (var column in columns.EnumerateArray())
            {
                count++;
                if (column.ValueKind != JsonValueKind.Object ||
                    string.IsNullOrWhiteSpace(GetString(column, "key")))
                    throw new ArgumentException("column shape");
            }
            if (count == 0 || count > VisualBlocksContract.MaxTableColumns) throw new ArgumentException("columns");
            if (node.TryGetProperty("rows", out var rows) && rows.ValueKind == JsonValueKind.Array)
            {
                var rowCount = 0;
                foreach (var _ in rows.EnumerateArray()) rowCount++;
                if (rowCount > VisualBlocksContract.MaxTableRows) throw new ArgumentException("rows");
            }
        }
        if (kind == "grid" && node.TryGetProperty("columns", out var gridCols) &&
            gridCols.ValueKind == JsonValueKind.Number)
        {
            var cols = gridCols.GetInt32();
            if (cols is < 1 or > 6) throw new ArgumentException("grid columns");
        }
        if (node.TryGetProperty("tabs", out var tabs) && tabs.ValueKind == JsonValueKind.Array)
        {
            foreach (var tab in tabs.EnumerateArray())
            {
                if (tab.ValueKind != JsonValueKind.Object || string.IsNullOrWhiteSpace(GetString(tab, "title")))
                    throw new ArgumentException("tab shape");
            }
        }
        if (node.TryGetProperty("items", out var items) && items.ValueKind == JsonValueKind.Array)
        {
            var count = 0;
            foreach (var item in items.EnumerateArray())
            {
                count++;
                if (item.ValueKind != JsonValueKind.Object || string.IsNullOrWhiteSpace(GetString(item, "title")))
                    throw new ArgumentException("item shape");
            }
            if (kind == "steps" && count == 0) throw new ArgumentException("steps need items");
        }
        else if (kind == "steps")
        {
            throw new ArgumentException("steps need items");
        }
        if (kind == "nodes") CheckDiagramNodes(node);
        if (kind is "slider" or "number")
        {
            double? min = null, max = null;
            foreach (var bound in new[] { "min", "max", "step" })
            {
                if (node.TryGetProperty(bound, out var boundElement) && boundElement.ValueKind == JsonValueKind.Number)
                {
                    var value = boundElement.GetDouble();
                    if (!double.IsFinite(value)) throw new ArgumentException(bound);
                    if (bound == "min") min = value;
                    if (bound == "max") max = value;
                }
            }
            if (min.HasValue && max.HasValue && !(min.Value < max.Value)) throw new ArgumentException("min/max");
        }
        if (kind is "alert" or "callout" &&
            node.TryGetProperty("variant", out var variant) && variant.ValueKind == JsonValueKind.String &&
            !GenUiContract.AlertVariants.Contains(variant.GetString() ?? string.Empty))
            throw new ArgumentException("variant");
        if (kind == "chart")
        {
            var chartType = (GetString(node, "chart_type") ?? "line").ToLowerInvariant();
            if (!GenUiContract.ChartTypes.Contains(chartType)) throw new ArgumentException("chart_type");
            if (!node.TryGetProperty("series", out var series) || series.ValueKind != JsonValueKind.Array)
                throw new ArgumentException("series");
            var seriesCount = 0;
            foreach (var entry in series.EnumerateArray())
            {
                seriesCount++;
                if (entry.ValueKind != JsonValueKind.Object ||
                    !entry.TryGetProperty("points", out var points) || points.ValueKind != JsonValueKind.Array)
                    throw new ArgumentException("points");
                var pointCount = 0;
                foreach (var point in points.EnumerateArray())
                {
                    pointCount++;
                    if (point.ValueKind != JsonValueKind.Object) throw new ArgumentException("point");
                    if (point.TryGetProperty("y_formula", out var yFormula) && yFormula.ValueKind == JsonValueKind.String)
                    {
                        if (!point.TryGetProperty("x", out var x) || x.ValueKind != JsonValueKind.Number)
                            throw new ArgumentException("y_formula needs numeric x");
                        GenUiFormula.Check(yFormula.GetString() ?? string.Empty);
                    }
                }
                if (pointCount == 0 || pointCount > VisualBlocksContract.MaxChartPointsPerSeries)
                    throw new ArgumentException("points");
            }
            if (seriesCount == 0 || seriesCount > VisualBlocksContract.MaxChartSeries)
                throw new ArgumentException("series");
        }
        else if (node.TryGetProperty("series", out var stray) && stray.ValueKind == JsonValueKind.Array)
        {
            throw new ArgumentException("series only on chart");
        }
        if (node.TryGetProperty("children", out var children) && children.ValueKind == JsonValueKind.Array)
        {
            foreach (var child in children.EnumerateArray()) CheckNode(child, depth + 1, counter);
        }
        if (node.TryGetProperty("tabs", out var tabsBody) && tabsBody.ValueKind == JsonValueKind.Array)
        {
            foreach (var tab in tabsBody.EnumerateArray())
            {
                if (tab.ValueKind == JsonValueKind.Object &&
                    tab.TryGetProperty("body", out var body) && body.ValueKind == JsonValueKind.Object)
                    CheckNode(body, depth + 1, counter);
            }
        }
        if (node.TryGetProperty("items", out var itemsBody) && itemsBody.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in itemsBody.EnumerateArray())
            {
                if (item.ValueKind == JsonValueKind.Object &&
                    item.TryGetProperty("body", out var body) && body.ValueKind == JsonValueKind.Object)
                    CheckNode(body, depth + 1, counter);
            }
        }
    }

    private static void CheckCapped(string? value, int cap, string what)
    {
        if (value is not null && value.Length > cap) throw new ArgumentException(what);
    }

    private static void CheckCondition(JsonElement cond)
    {
        if (!GenUiContract.KeyPattern.IsMatch(GetString(cond, "key") ?? string.Empty))
            throw new ArgumentException("condition key");
        if (!GenUiContract.CondOps.Contains(GetString(cond, "op") ?? string.Empty))
            throw new ArgumentException("condition op");
    }

    private static void CheckAction(JsonElement action)
    {
        switch (GetString(action, "kind"))
        {
            case "local":
                if (!GenUiContract.LocalOps.Contains(GetString(action, "op") ?? string.Empty))
                    throw new ArgumentException("local op");
                if (GetString(action, "op") is "set" or "toggle" or "step" or "goto" &&
                    !GenUiContract.KeyPattern.IsMatch(GetString(action, "key") ?? string.Empty))
                    throw new ArgumentException("action key");
                if (GetString(action, "op") is "set" or "goto" &&
                    action.TryGetProperty("value", out var actionValue))
                {
                    if (actionValue.ValueKind is not JsonValueKind.String and not JsonValueKind.Number and not JsonValueKind.True and not JsonValueKind.False and not JsonValueKind.Null)
                        throw new ArgumentException("action value");
                    if (actionValue.ValueKind == JsonValueKind.Number && !double.IsFinite(actionValue.GetDouble()))
                        throw new ArgumentException("action value");
                }
                if (GetString(action, "op") == "step" &&
                    action.TryGetProperty("delta", out var delta) &&
                    (delta.ValueKind != JsonValueKind.Number || !double.IsFinite(delta.GetDouble())))
                    throw new ArgumentException("delta");
                break;
            case "agent":
                if (!GenUiContract.IdPattern.IsMatch(GetString(action, "action_id") ?? string.Empty))
                    throw new ArgumentException("action_id");
                if (action.TryGetProperty("state_keys", out var keys) && keys.ValueKind == JsonValueKind.Array)
                {
                    var count = 0;
                    foreach (var key in keys.EnumerateArray())
                    {
                        count++;
                        if (key.ValueKind != JsonValueKind.String ||
                            !GenUiContract.KeyPattern.IsMatch(key.GetString() ?? string.Empty))
                            throw new ArgumentException("state_keys");
                    }
                    if (count > 16) throw new ArgumentException("state_keys");
                }
                if (action.TryGetProperty("payload", out var payload) && payload.ValueKind == JsonValueKind.Object &&
                    System.Text.Encoding.UTF8.GetByteCount(payload.GetRawText()) > 4096)
                    throw new ArgumentException("payload");
                break;
            default:
                throw new ArgumentException("action kind");
        }
    }

    private static void CheckDiagramNodes(JsonElement node)
    {
        if (!node.TryGetProperty("nodes", out var nodes) || nodes.ValueKind != JsonValueKind.Array)
            throw new ArgumentException("nodes");
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var count = 0;
        foreach (var entry in nodes.EnumerateArray())
        {
            count++;
            if (entry.ValueKind != JsonValueKind.Object ||
                !GenUiContract.IdPattern.IsMatch(GetString(entry, "id") ?? string.Empty))
                throw new ArgumentException("node entry");
            seen.Add(GetString(entry, "id")!);
        }
        if (count == 0 || count > GenUiContract.MaxDiagramNodes) throw new ArgumentException("nodes");
        if (node.TryGetProperty("edges", out var edges) && edges.ValueKind == JsonValueKind.Array)
        {
            foreach (var edge in edges.EnumerateArray())
            {
                if (edge.ValueKind != JsonValueKind.Array || edge.GetArrayLength() != 2 ||
                    !seen.Contains(edge[0].GetString() ?? string.Empty) ||
                    !seen.Contains(edge[1].GetString() ?? string.Empty))
                    throw new ArgumentException("edge");
            }
        }
    }

    private static string? GetString(JsonElement element, string key)
    {
        return element.ValueKind == JsonValueKind.Object &&
               element.TryGetProperty(key, out var value) &&
               value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;
    }
}

/// <summary>
/// Stato locale per istanza GenUI (chiave = uiId + hash spec). Snapshot fusi
/// in archivio al salvataggio: le interazioni sopravvivono al riavvio.
/// </summary>
public static class GenUiStateStore
{
    private static readonly object Gate = new();
    private static readonly Dictionary<string, Dictionary<string, GenUiValue>> Snapshots = new(StringComparer.Ordinal);

    public static string SnapshotKey(string uiId, string specRaw) =>
        $"{uiId}:{specRaw.GetHashCode(StringComparison.Ordinal)}";

    public static Dictionary<string, GenUiValue> GetOrSeed(string key, Dictionary<string, GenUiValue> seed)
    {
        lock (Gate)
        {
            if (!Snapshots.TryGetValue(key, out var existing))
            {
                existing = new Dictionary<string, GenUiValue>(seed, StringComparer.Ordinal);
                Snapshots[key] = existing;
            }
            return existing;
        }
    }

    public static void Reset(string key, Dictionary<string, GenUiValue> seed)
    {
        lock (Gate)
        {
            Snapshots[key] = new Dictionary<string, GenUiValue>(seed, StringComparer.Ordinal);
        }
    }

    public static JsonElement? SnapshotElement(string uiId, string specRaw)
    {
        Dictionary<string, GenUiValue>? snapshot;
        lock (Gate)
        {
            Snapshots.TryGetValue(SnapshotKey(uiId, specRaw), out snapshot);
        }
        if (snapshot is null) return null;
        using var stream = new System.IO.MemoryStream();
        using (var writer = new System.Text.Json.Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            foreach (var (key, value) in snapshot)
            {
                writer.WritePropertyName(key);
                if (value.IsNumber) writer.WriteNumberValue(value.Number);
                else if (value.Text is not null) writer.WriteStringValue(value.Text);
                else writer.WriteBooleanValue(value.Bool);
            }
            writer.WriteEndObject();
        }
        stream.Position = 0;
        return JsonDocument.Parse(stream).RootElement.Clone();
    }
}

/// <summary>Eventi e testi GenUI: stesso protocollo su entrambi i client.</summary>
public static class GenUiEvents
{
    private static readonly Regex InterpPattern = new(@"\{\{([^{}]{1,500})\}\}", RegexOptions.Compiled);

    public static string BuildEvent(string uiId, string actionId, JsonElement payload)
    {
        if (!GenUiContract.IdPattern.IsMatch(uiId)) throw new ArgumentException("event ui id");
        if (!GenUiContract.IdPattern.IsMatch(actionId)) throw new ArgumentException("event action id");
        var raw = payload.GetRawText();
        if (System.Text.Encoding.UTF8.GetByteCount(raw) > GenUiContract.MaxEventBytes)
            throw new ArgumentException("event payload");
        return $"[genui {uiId} {actionId} {raw}]";
    }

    public static string RenderText(string template, IReadOnlyDictionary<string, GenUiValue> state)
    {
        if (string.IsNullOrEmpty(template) || !template.Contains("{{")) return template;
        return InterpPattern.Replace(template, match =>
        {
            try { return GenUiFormula.EvalText(match.Groups[1].Value, state); }
            catch { return string.Empty; }
        });
    }

    public static bool EvalCondition(JsonElement cond, IReadOnlyDictionary<string, GenUiValue> state)
    {
        if (cond.ValueKind != JsonValueKind.Object) return true;
        var key = cond.TryGetProperty("key", out var keyElement) && keyElement.ValueKind == JsonValueKind.String
            ? keyElement.GetString() ?? string.Empty : string.Empty;
        var op = cond.TryGetProperty("op", out var opElement) && opElement.ValueKind == JsonValueKind.String
            ? opElement.GetString() ?? string.Empty : string.Empty;
        if (!state.TryGetValue(key, out var actual))
            return op is "neq" or "empty" or "notempty" && EvalEmpty(op, null);
        cond.TryGetProperty("value", out var expected);
        return op switch
        {
            "eq" => EqualsValue(actual, expected),
            "neq" => !EqualsValue(actual, expected),
            "gt" or "gte" or "lt" or "lte" => CompareNumbers(op, actual, expected),
            "in" => expected.ValueKind == JsonValueKind.Array &&
                    expected.EnumerateArray().Any(item => EqualsValue(actual, item)),
            "contains" => actual.AsText().Contains(
                expected.ValueKind == JsonValueKind.String ? expected.GetString() ?? string.Empty : expected.GetRawText(),
                StringComparison.Ordinal),
            "empty" => EvalEmpty(op, actual),
            "notempty" => EvalEmpty(op, actual),
            _ => false,
        };
    }

    private static bool EqualsValue(GenUiValue actual, JsonElement expected)
    {
        return expected.ValueKind switch
        {
            JsonValueKind.True or JsonValueKind.False => actual.Bool == expected.GetBoolean() && actual.Text is null && !actual.IsNumber,
            JsonValueKind.Number => actual.IsNumber && actual.Number == expected.GetDouble(),
            JsonValueKind.String => actual.AsText() == expected.GetString(),
            _ => false,
        };
    }

    private static bool CompareNumbers(string op, GenUiValue actual, JsonElement expected)
    {
        if (!actual.IsNumber || expected.ValueKind != JsonValueKind.Number) return false;
        var left = actual.Number;
        var right = expected.GetDouble();
        if (!double.IsFinite(left) || !double.IsFinite(right)) return false;
        return op switch
        {
            "gt" => left > right,
            "gte" => left >= right,
            "lt" => left < right,
            _ => left <= right,
        };
    }

    private static bool EvalEmpty(string op, GenUiValue? actual)
    {
        var isEmpty = actual is null || (actual.Text is not null && actual.Text.Length == 0) ||
                      (actual.IsNumber && !double.IsFinite(actual.Number));
        return op == "empty" ? isEmpty : !isEmpty;
    }
}
