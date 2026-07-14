using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.AspNetCore.RateLimiting;
using System.Threading.RateLimiting;

var builder = WebApplication.CreateBuilder(args);
builder.Services.ConfigureHttpJsonOptions(options =>
{
    options.SerializerOptions.WriteIndented = true;
    options.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase;
});
builder.Services.AddCors(options =>
{
    options.AddPolicy("default", policy =>
    {
        policy.WithOrigins("https://hermes.local", "http://hermes.local")
            .AllowAnyHeader()
            .AllowAnyMethod();
    });
});
builder.Services.AddRateLimiter(options =>
{
    options.GlobalLimiter = PartitionedRateLimiter.Create<HttpContext, string>(httpCtx =>
        RateLimitPartition.GetFixedWindowLimiter(
            partitionKey: httpCtx.Connection.RemoteIpAddress?.ToString() ?? "unknown",
            factory: _ => new FixedWindowRateLimiterOptions
            {
                PermitLimit = 60,
                Window = TimeSpan.FromMinutes(1),
                QueueLimit = 0,
                QueueProcessingOrder = QueueProcessingOrder.OldestFirst
            }));
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
});

var config = BridgeConfig.Load();
if (string.IsNullOrWhiteSpace(config.Token))
{
    await Console.Error.WriteLineAsync("[admin-bridge] FATAL: variabile CHATCLAW_ADMIN_TOKEN non impostata. Impossibile avviare senza auth.").ConfigureAwait(false);
    return 1;
}

builder.WebHost.ConfigureKestrel(options =>
{
    options.Limits.MaxRequestBodySize = config.MaxRequestBytes;
});

var app = builder.Build();
var audit = new AuditLog(config.AuditPath);
var appVersion = System.Reflection.Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "unknown";
app.UseCors("default");
app.UseRateLimiter();

app.Use(async (context, next) =>
{
    if (context.Request.Path == "/v1/health")
    {
        await next().ConfigureAwait(false);
        return;
    }

    var token = context.Request.Headers.Authorization.ToString();
    var expected = config.Token;
    const string prefix = "Bearer ";
    var presented = token.Length > prefix.Length && token.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)
        ? token[prefix.Length..].Trim()
        : string.Empty;
    if (presented.Length == 0 || !CryptographicEquals(presented, expected))
    {
        context.Response.StatusCode = StatusCodes.Status401Unauthorized;
        await context.Response.WriteAsJsonAsync(new { status = "unauthorized", message = "Bearer token obbligatorio." }).ConfigureAwait(false);
        return;
    }

    await next().ConfigureAwait(false);
});

app.MapGet("/v1/health", () => Results.Json(new { status = "ok", app = "Hermes Hub Admin Bridge", version = appVersion }));

app.MapPost("/v1/reload", () =>
{
    var reloaded = BridgeConfig.Load(config.MaxRequestBytes);
    if (string.IsNullOrWhiteSpace(reloaded.Token))
    {
        audit.Write("config.reload", "denied:missing-token");
        return Results.BadRequest(new { status = "denied", message = "CHATCLAW_ADMIN_TOKEN mancante: config non applicata." });
    }

    config = reloaded;
    audit = new AuditLog(config.AuditPath);
    audit.Write("config.reload", "ok");
    return Results.Json(new { status = "ok", roots = config.Roots });
});

app.MapGet("/v1/status", () =>
{
    audit.Write("status", "read");
    var drives = DriveInfo.GetDrives()
        .Where(drive => drive.IsReady)
        .Select(drive => new
        {
            name = drive.Name,
            totalBytes = drive.TotalSize,
            freeBytes = drive.AvailableFreeSpace
        });

    return Results.Json(new
    {
        status = "ok",
        machine = Environment.MachineName,
        os = RuntimeInformation.OSDescription,
        arch = RuntimeInformation.OSArchitecture.ToString(),
        processUptimeSeconds = (long)(DateTimeOffset.UtcNow - Process.GetCurrentProcess().StartTime.ToUniversalTime()).TotalSeconds,
        memory = new
        {
            processBytes = Environment.WorkingSet,
            gcBytes = GC.GetTotalMemory(false)
        },
        roots = config.Roots,
        drives
    });
});

app.MapPost("/v1/actions/{action}", async (string action, HttpContext context) =>
{
    var command = config.ResolveAction(action);
    if (command is null)
    {
        audit.Write(action, "denied");
        return Results.BadRequest(new { status = "denied", message = $"Azione non in allowlist: {action}" });
    }

    audit.Write(action, "run");
    try
    {
        var result = await CommandRunner.RunAsync(
            command.Value.FileName,
            command.Value.Arguments,
            config.CommandTimeoutSeconds,
            context.RequestAborted).ConfigureAwait(false);
        audit.Write(action, $"exit:{result.ExitCode}");
        return Results.Json(new
        {
            status = result.ExitCode == 0 ? "ok" : "error",
            action,
            result.ExitCode,
            result.Stdout,
            result.Stderr
        });
    }
    catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception or IOException)
    {
        audit.Write(action, $"start-failed:{ex.GetType().Name}");
        return Results.Problem($"Avvio azione fallito: {ex.Message}", statusCode: 500);
    }
});

app.MapPost("/v1/logs/tail", async (TailRequest request, HttpContext context) =>
{
    var path = config.ResolveSafePath(request.Path);
    if (path is null || !File.Exists(path))
    {
        audit.Write("logs.tail", "denied");
        return Results.BadRequest(new { status = "denied", message = "Log non trovato o fuori dalle root consentite." });
    }

    var logInfo = new FileInfo(path);
    if (logInfo.Length > config.MaxReadBytes)
    {
        audit.Write("logs.tail", "denied:size");
        return Results.BadRequest(new { status = "denied", message = $"Log troppo grande. Limite {config.MaxReadBytes} bytes." });
    }

    audit.Write("logs.tail", path);
    var requested = Math.Clamp(request.Lines <= 0 ? 200 : request.Lines, 1, 2000);
    var lines = await ReadLastLinesAsync(path, requested, config.MaxReadBytes, context.RequestAborted).ConfigureAwait(false);
    return Results.Json(new
    {
        status = "ok",
        path,
        lines
    });
});

app.MapPost("/v1/files/list", (FileListRequest request) =>
{
    var path = config.ResolveSafePath(request.Path);
    if (path is null || !Directory.Exists(path))
    {
        audit.Write("files.list", "denied");
        return Results.BadRequest(new { status = "denied", message = "Directory non trovata o fuori dalle root consentite." });
    }

    audit.Write("files.list", path);
    try
    {
        var entries = Directory.EnumerateFileSystemEntries(path)
            .Take(500)
            .Select(entry => new
            {
                path = entry,
                name = Path.GetFileName(entry),
                type = Directory.Exists(entry) ? "directory" : "file",
                bytes = File.Exists(entry) ? new FileInfo(entry).Length : 0
            })
            .ToArray();

        return Results.Json(new { status = "ok", path, entries });
    }
    catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
    {
        audit.Write("files.list", $"failed:{ex.GetType().Name}");
        return Results.Problem($"Lettura directory fallita: {ex.Message}", statusCode: 500);
    }
});

app.MapPost("/v1/files/read", async (FileReadRequest request, HttpContext context) =>
{
    var path = config.ResolveSafePath(request.Path);
    if (path is null || !File.Exists(path))
    {
        audit.Write("files.read", "denied");
        return Results.BadRequest(new { status = "denied", message = "File non trovato o fuori dalle root consentite." });
    }

    var info = new FileInfo(path);
    if (info.Length > config.MaxReadBytes)
    {
        return Results.BadRequest(new { status = "denied", message = $"File troppo grande. Limite {config.MaxReadBytes} bytes." });
    }

    try
    {
        var text = await ReadTextBoundedAsync(path, config.MaxReadBytes, context.RequestAborted).ConfigureAwait(false);
        audit.Write("files.read", path);
        return Results.Json(new { status = "ok", path, text });
    }
    catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
    {
        audit.Write("files.read", $"failed:{ex.GetType().Name}");
        return Results.Problem($"Lettura file fallita: {ex.Message}", statusCode: 500);
    }
});

app.MapPost("/v1/files/write", async (FileWriteRequest request, HttpContext context) =>
{
    var path = config.ResolveSafePath(request.Path);
    if (path is null)
    {
        audit.Write("files.write", "denied");
        return Results.BadRequest(new { status = "denied", message = "Path fuori dalle root consentite." });
    }

    var payload = request.Text ?? string.Empty;
    if (payload.Length > config.MaxWriteChars)
    {
        audit.Write("files.write", "denied:size");
        return Results.BadRequest(new { status = "denied", message = $"Payload troppo grande. Limite {config.MaxWriteChars} caratteri." });
    }

    using var writeLease = await PathWriteLocks.AcquireAsync(path, context.RequestAborted).ConfigureAwait(false);
    string? backupPath = null;
    try
    {
        backupPath = await WriteTextAtomicallyAsync(path, payload, context.RequestAborted).ConfigureAwait(false);
    }
    catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
    {
        audit.Write("files.write", $"failed:{ex.GetType().Name}");
        return Results.Problem($"Scrittura fallita: {ex.Message}", statusCode: 500);
    }

    audit.Write("files.write", path);
    return Results.Json(new { status = "ok", path, backupPath });
});

var lifetime = app.Services.GetRequiredService<IHostApplicationLifetime>();
lifetime.ApplicationStopping.Register(() =>
{
    Console.WriteLine("[admin-bridge] shutdown gracefully...");
});

await app.RunAsync().ConfigureAwait(false);
return 0;

static async Task<IReadOnlyList<string>> ReadLastLinesAsync(
    string path,
    int count,
    long maxBytes,
    CancellationToken cancellationToken)
{
    var text = await ReadTextBoundedAsync(path, maxBytes, cancellationToken).ConfigureAwait(false);
    var lines = text
        .Replace("\r\n", "\n", StringComparison.Ordinal)
        .Replace('\r', '\n')
        .Split('\n');
    var available = lines.Length > 0 && lines[^1].Length == 0 ? lines[..^1] : lines;
    return available
        .TakeLast(count)
        .ToArray();
}

static async Task<string> ReadTextBoundedAsync(string path, long maxBytes, CancellationToken cancellationToken)
{
    using var stream = new FileStream(
        path,
        FileMode.Open,
        FileAccess.Read,
        FileShare.ReadWrite,
        64 * 1024,
        FileOptions.Asynchronous | FileOptions.SequentialScan);
    var snapshotLength = stream.Length;
    if (snapshotLength > maxBytes || snapshotLength > int.MaxValue)
    {
        throw new IOException($"File oltre il limite di {maxBytes} bytes.");
    }

    var bytes = new byte[(int)snapshotLength];
    var offset = 0;
    while (offset < bytes.Length)
    {
        var read = await stream.ReadAsync(bytes.AsMemory(offset), cancellationToken).ConfigureAwait(false);
        if (read == 0)
        {
            break;
        }
        offset += read;
    }
    return System.Text.Encoding.UTF8.GetString(bytes, 0, offset);
}

static async Task<string?> WriteTextAtomicallyAsync(string path, string text, CancellationToken cancellationToken)
{
    var directory = Path.GetDirectoryName(path) ?? throw new IOException("Directory destinazione non valida.");
    Directory.CreateDirectory(directory);
    var temporaryPath = Path.Combine(directory, $".{Path.GetFileName(path)}.{Guid.NewGuid():N}.tmp");
    var backupPath = File.Exists(path)
        ? $"{path}.{DateTimeOffset.UtcNow:yyyyMMddHHmmssfff}-{Guid.NewGuid():N}.bak"
        : null;

    try
    {
        var stream = new FileStream(
                         temporaryPath,
                         FileMode.CreateNew,
                         FileAccess.Write,
                         FileShare.None,
                         64 * 1024,
                         FileOptions.Asynchronous | FileOptions.WriteThrough);
        await using (stream.ConfigureAwait(false))
        {
            var writer = new StreamWriter(stream, new System.Text.UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            await using (writer.ConfigureAwait(false))
            {
                await writer.WriteAsync(text.AsMemory(), cancellationToken).ConfigureAwait(false);
                await writer.FlushAsync(cancellationToken).ConfigureAwait(false);
            }
        }

        if (backupPath is not null)
        {
            try
            {
                File.Replace(temporaryPath, path, backupPath, ignoreMetadataErrors: true);
            }
            catch (PlatformNotSupportedException)
            {
                File.Copy(path, backupPath, overwrite: false);
                File.Move(temporaryPath, path, overwrite: true);
            }
        }
        else
        {
            File.Move(temporaryPath, path);
        }
        PruneWriteBackups(path, keep: 5);
        return backupPath;
    }
    finally
    {
        try { File.Delete(temporaryPath); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            await Console.Error.WriteLineAsync($"[admin-bridge] temp cleanup failed: {ex.Message}").ConfigureAwait(false);
        }
    }
}

static void PruneWriteBackups(string path, int keep)
{
    try
    {
        var directory = Path.GetDirectoryName(path);
        if (string.IsNullOrWhiteSpace(directory) || !Directory.Exists(directory))
        {
            return;
        }

        var pattern = $"{Path.GetFileName(path)}.*.bak";
        foreach (var backup in new DirectoryInfo(directory)
                     .EnumerateFiles(pattern, SearchOption.TopDirectoryOnly)
                     .OrderByDescending(file => file.LastWriteTimeUtc)
                     .Skip(Math.Max(0, keep)))
        {
            backup.Delete();
        }
    }
    catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
    {
        Console.Error.WriteLine($"[admin-bridge] backup pruning failed: {ex.Message}");
    }
}

static bool CryptographicEquals(string left, string right)
{
    var leftBytes = System.Text.Encoding.UTF8.GetBytes(left);
    var rightBytes = System.Text.Encoding.UTF8.GetBytes(right);
    return System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(leftBytes, rightBytes);
}

static class PathWriteLocks
{
    private static readonly object Gate = new();
    private static readonly Dictionary<string, LockEntry> Locks = new(
        OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal);

    public static async Task<IDisposable> AcquireAsync(string path, CancellationToken cancellationToken)
    {
        LockEntry entry;
        lock (Gate)
        {
            if (!Locks.TryGetValue(path, out entry!))
            {
                entry = new LockEntry();
                Locks[path] = entry;
            }
            entry.References++;
        }

        try
        {
            await entry.Semaphore.WaitAsync(cancellationToken).ConfigureAwait(false);
            return new Lease(path, entry);
        }
        catch
        {
            ReleaseReference(path, entry, releaseSemaphore: false);
            throw;
        }
    }

    private static void ReleaseReference(string path, LockEntry entry, bool releaseSemaphore)
    {
        if (releaseSemaphore)
        {
            entry.Semaphore.Release();
        }

        lock (Gate)
        {
            entry.References--;
            if (entry.References == 0)
            {
                Locks.Remove(path);
                entry.Semaphore.Dispose();
            }
        }
    }

    private sealed class LockEntry
    {
        public SemaphoreSlim Semaphore { get; } = new(1, 1);
        public int References { get; set; }
    }

    private sealed class Lease(string path, LockEntry entry) : IDisposable
    {
        private int _disposed;

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) == 0)
            {
                ReleaseReference(path, entry, releaseSemaphore: true);
            }
        }
    }
}

[System.Diagnostics.CodeAnalysis.SuppressMessage("Performance", "CA1812:Avoid uninstantiated internal classes", Justification = "Creato dal model binder ASP.NET Core.")]
sealed record TailRequest(string Path, int Lines);
[System.Diagnostics.CodeAnalysis.SuppressMessage("Performance", "CA1812:Avoid uninstantiated internal classes", Justification = "Creato dal model binder ASP.NET Core.")]
sealed record FileListRequest(string Path);
[System.Diagnostics.CodeAnalysis.SuppressMessage("Performance", "CA1812:Avoid uninstantiated internal classes", Justification = "Creato dal model binder ASP.NET Core.")]
sealed record FileReadRequest(string Path);
[System.Diagnostics.CodeAnalysis.SuppressMessage("Performance", "CA1812:Avoid uninstantiated internal classes", Justification = "Creato dal model binder ASP.NET Core.")]
sealed record FileWriteRequest(string Path, string? Text);

sealed class BridgeConfig
{
    public required string Token { get; init; }
    public required string[] Roots { get; init; }
    public required string AuditPath { get; init; }
    public required int CommandTimeoutSeconds { get; init; }
    public required long MaxReadBytes { get; init; }
    public required long MaxRequestBytes { get; init; }
    public required int MaxWriteChars { get; init; }
    public required Dictionary<string, (string FileName, string Arguments)> Actions { get; init; }

    public static BridgeConfig Load(long? fixedMaxRequestBytes = null)
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var data = Path.Combine(home, ".chatclaw-admin-bridge");
        Directory.CreateDirectory(data);

        var configuredRoots = Environment.GetEnvironmentVariable("CHATCLAW_ADMIN_ROOTS");
        if (string.IsNullOrWhiteSpace(configuredRoots))
        {
            configuredRoots = Path.Combine(home, "hermes-workspace");
        }
        var roots = configuredRoots
            .Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(path => Path.GetFullPath(path))
            .Distinct(OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal)
            .ToArray();
        foreach (var root in roots)
        {
            Directory.CreateDirectory(root);
        }

        var actions = new Dictionary<string, (string FileName, string Arguments)>(StringComparer.OrdinalIgnoreCase);

        var restart = Environment.GetEnvironmentVariable("CHATCLAW_RESTART_GATEWAY_COMMAND");
        if (!string.IsNullOrWhiteSpace(restart))
        {
            var parts = SplitCommand(restart);
            actions["restart-service"] = (parts.FileName, parts.Arguments);
        }

        return new BridgeConfig
        {
            Token = Environment.GetEnvironmentVariable("CHATCLAW_ADMIN_TOKEN") ?? string.Empty,
            Roots = roots,
            AuditPath = Path.GetFullPath(Environment.GetEnvironmentVariable("CHATCLAW_ADMIN_AUDIT") ?? Path.Combine(data, "audit.log")),
            CommandTimeoutSeconds = Math.Clamp(int.TryParse(Environment.GetEnvironmentVariable("CHATCLAW_ADMIN_TIMEOUT"), out var timeout) ? timeout : 120, 1, 3600),
            MaxReadBytes = Math.Clamp(long.TryParse(Environment.GetEnvironmentVariable("CHATCLAW_ADMIN_MAX_READ_BYTES"), out var maxRead) ? maxRead : 1_000_000, 1024, 100L * 1024 * 1024),
            MaxRequestBytes = fixedMaxRequestBytes ?? Math.Clamp(long.TryParse(Environment.GetEnvironmentVariable("CHATCLAW_ADMIN_MAX_REQUEST_BYTES"), out var maxReq) ? maxReq : 4_000_000, 1024, 100L * 1024 * 1024),
            MaxWriteChars = Math.Clamp(int.TryParse(Environment.GetEnvironmentVariable("CHATCLAW_ADMIN_MAX_WRITE_CHARS"), out var maxWrite) ? maxWrite : 2_000_000, 1, 10_000_000),
            Actions = actions
        };
    }

    public (string FileName, string Arguments)? ResolveAction(string action)
    {
        return Actions.TryGetValue(action, out var command) ? command : null;
    }

    public string? ResolveSafePath(string requested)
    {
        if (string.IsNullOrWhiteSpace(requested))
        {
            requested = Roots.FirstOrDefault() ?? ".";
        }
        else if (!Path.IsPathRooted(requested) && Roots.FirstOrDefault() is { } firstRoot)
        {
            requested = Path.Combine(firstRoot, requested);
        }

        string canonical;
        try
        {
            canonical = Path.GetFullPath(requested);
            // Resolve symlinks / junctions so attacker non puo' creare un junction
            // dentro una root che punta fuori (e.g. C:\Windows\System32).
            if (Directory.Exists(canonical))
            {
                var info = new DirectoryInfo(canonical);
                if (info.Attributes.HasFlag(FileAttributes.ReparsePoint))
                {
                    return null;
                }
                var resolved = info.ResolveLinkTarget(returnFinalTarget: true);
                if (resolved is DirectoryInfo dirResolved)
                {
                    canonical = dirResolved.FullName;
                }
            }
            else if (File.Exists(canonical))
            {
                var info = new FileInfo(canonical);
                if (info.Attributes.HasFlag(FileAttributes.ReparsePoint))
                {
                    return null;
                }
                var resolved = info.ResolveLinkTarget(returnFinalTarget: true);
                if (resolved is FileInfo fileResolved)
                {
                    canonical = fileResolved.FullName;
                }
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException or System.Security.SecurityException)
        {
            return null;
        }

        var comparison = OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
        foreach (var root in Roots)
        {
            var relative = Path.GetRelativePath(root, canonical);
            var outsideRoot = Path.IsPathRooted(relative) ||
                              relative.Equals("..", comparison) ||
                              relative.StartsWith($"..{Path.DirectorySeparatorChar}", comparison) ||
                              relative.StartsWith($"..{Path.AltDirectorySeparatorChar}", comparison);
            if (!outsideRoot && !ContainsReparsePoint(root, canonical))
            {
                return canonical;
            }
        }
        return null;
    }

    private static bool ContainsReparsePoint(string root, string target)
    {
        try
        {
            var relative = Path.GetRelativePath(root, target);
            var current = root;
            if (File.GetAttributes(current).HasFlag(FileAttributes.ReparsePoint))
            {
                return true;
            }

            foreach (var segment in relative.Split(
                         [Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar],
                         StringSplitOptions.RemoveEmptyEntries))
            {
                current = Path.Combine(current, segment);
                if (!File.Exists(current) && !Directory.Exists(current))
                {
                    break;
                }
                if (File.GetAttributes(current).HasFlag(FileAttributes.ReparsePoint))
                {
                    return true;
                }
            }
            return false;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return true;
        }
    }

    private static (string FileName, string Arguments) SplitCommand(string command)
    {
        var trimmed = command.Trim();
        if (trimmed.StartsWith('"'))
        {
            var closingQuote = trimmed.IndexOf('"', 1);
            if (closingQuote < 0)
            {
                throw new FormatException("Comando allowlist con virgolette non chiuse.");
            }
            return (trimmed[1..closingQuote], trimmed[(closingQuote + 1)..].TrimStart());
        }

        var firstSpace = trimmed.IndexOfAny([' ', '\t']);
        return firstSpace < 0
            ? (trimmed, string.Empty)
            : (trimmed[..firstSpace], trimmed[(firstSpace + 1)..].TrimStart());
    }
}

sealed class AuditLog(string path)
{
    private const long MaxBytes = 10L * 1024 * 1024;
    private const int MaxRotations = 5;
    private readonly object _lock = new();

    public void Write(string action, string detail)
    {
        var line = JsonSerializer.Serialize(new
        {
            at = DateTimeOffset.UtcNow,
            action,
            detail
        }) + Environment.NewLine;

        lock (_lock)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                RotateIfNeeded();
                File.AppendAllText(path, line, System.Text.Encoding.UTF8);
            }
            catch (IOException ex)
            {
                Console.Error.WriteLine($"[admin-bridge] AuditLog I/O error: {ex.Message}");
            }
            catch (UnauthorizedAccessException ex)
            {
                Console.Error.WriteLine($"[admin-bridge] AuditLog auth error: {ex.Message}");
            }
        }
    }

    private void RotateIfNeeded()
    {
        try
        {
            var info = new FileInfo(path);
            if (!info.Exists || info.Length < MaxBytes) return;
            for (var i = MaxRotations - 1; i >= 1; i--)
            {
                var src = $"{path}.{i}";
                var dst = $"{path}.{i + 1}";
                if (!File.Exists(src)) continue;
                if (File.Exists(dst)) File.Delete(dst);
                File.Move(src, dst);
            }
            File.Move(path, $"{path}.1", overwrite: true);
        }
        catch (IOException ex) { Console.Error.WriteLine($"[admin-bridge] Rotate I/O error: {ex.Message}"); }
        catch (UnauthorizedAccessException ex) { Console.Error.WriteLine($"[admin-bridge] Rotate auth error: {ex.Message}"); }
    }
}

static class CommandRunner
{
    private const int MaxCapturedChars = 1_000_000;
    private static readonly SemaphoreSlim ConcurrencyLimit = new(2, 2);

    public static async Task<(int ExitCode, string Stdout, string Stderr)> RunAsync(
        string fileName,
        string arguments,
        int timeoutSeconds,
        CancellationToken cancellationToken)
    {
        await ConcurrencyLimit.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            using var process = new Process();
            var startInfo = new ProcessStartInfo
            {
                FileName = fileName,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            foreach (var arg in SplitArguments(arguments))
            {
                startInfo.ArgumentList.Add(arg);
            }
            process.StartInfo = startInfo;

            if (!process.Start())
            {
                throw new InvalidOperationException("Il processo configurato non è stato avviato.");
            }
            var stdoutTask = ReadBoundedAsync(process.StandardOutput, MaxCapturedChars);
            var stderrTask = ReadBoundedAsync(process.StandardError, MaxCapturedChars);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TimeSpan.FromSeconds(timeoutSeconds));

            try
            {
                await process.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                try
                {
                    if (!process.HasExited)
                    {
                        process.Kill(entireProcessTree: true);
                    }
                }
                catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception)
                {
                    await Console.Error.WriteLineAsync($"[admin-bridge] process kill failed: {ex.Message}").ConfigureAwait(false);
                }

                var stdoutOnTimeout = await AwaitOutputAsync(stdoutTask).ConfigureAwait(false);
                var stderrOnTimeout = await AwaitOutputAsync(stderrTask).ConfigureAwait(false);
                var reason = cancellationToken.IsCancellationRequested
                    ? "Richiesta annullata: processo terminato."
                    : "Timeout: processo terminato.";
                return (-1, stdoutOnTimeout, string.IsNullOrWhiteSpace(stderrOnTimeout) ? reason : $"{reason}\n{stderrOnTimeout}");
            }

            var stdoutFinal = await AwaitOutputAsync(stdoutTask).ConfigureAwait(false);
            var stderrFinal = await AwaitOutputAsync(stderrTask).ConfigureAwait(false);
            return (process.ExitCode, stdoutFinal, stderrFinal);
        }
        finally
        {
            ConcurrencyLimit.Release();
        }
    }

    private static async Task<string> ReadBoundedAsync(TextReader reader, int maxChars)
    {
        var result = new System.Text.StringBuilder(Math.Min(maxChars, 16 * 1024));
        var buffer = new char[8192];
        var truncated = false;
        while (true)
        {
            var read = await reader.ReadAsync(buffer.AsMemory()).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }

            var remaining = maxChars - result.Length;
            if (remaining > 0)
            {
                result.Append(buffer, 0, Math.Min(read, remaining));
            }
            truncated |= read > remaining;
        }
        if (truncated)
        {
            result.Append("\n[output troncato al limite operativo]");
        }
        return result.ToString();
    }

    private static async Task<string> AwaitOutputAsync(Task<string> outputTask)
    {
        try
        {
            return await outputTask.WaitAsync(TimeSpan.FromSeconds(5)).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is TimeoutException or IOException or ObjectDisposedException)
        {
            return string.Empty;
        }
    }

    private static IEnumerable<string> SplitArguments(string arguments)
    {
        if (string.IsNullOrWhiteSpace(arguments))
        {
            yield break;
        }

        var current = new System.Text.StringBuilder();
        var inQuotes = false;
        foreach (var ch in arguments)
        {
            if (ch == '"')
            {
                inQuotes = !inQuotes;
                continue;
            }
            if (!inQuotes && char.IsWhiteSpace(ch))
            {
                if (current.Length > 0)
                {
                    yield return current.ToString();
                    current.Clear();
                }
                continue;
            }
            current.Append(ch);
        }
        if (current.Length > 0)
        {
            yield return current.ToString();
        }
    }
}
