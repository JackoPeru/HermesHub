using System.Diagnostics;

namespace NemoclawChat_Windows.Services;

public static class FileLogger
{
    private const long MaxBytes = 2L * 1024 * 1024;
    private const int MaxRotations = 5;
    private static TextWriterTraceListener? _listener;

    public static void Initialize()
    {
        try
        {
            var directory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "ChatClaw",
                "logs");
            Directory.CreateDirectory(directory);
            var logPath = Path.Combine(directory, "app.log");
            Rotate(logPath);
            var writer = new StreamWriter(File.Open(logPath, FileMode.Append, FileAccess.Write, FileShare.Read))
            {
                AutoFlush = true
            };
            _listener = new TextWriterTraceListener(writer);
            Trace.Listeners.Add(_listener);
            Debug.AutoFlush = true;
            Trace.AutoFlush = true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            Trace.WriteLine($"[FileLogger] init failed: {ex.Message}");
        }
    }

    private static void Rotate(string logPath)
    {
        var info = new FileInfo(logPath);
        if (!info.Exists || info.Length < MaxBytes) return;
        for (var i = MaxRotations - 1; i >= 1; i--)
        {
            var src = $"{logPath}.{i}";
            var dst = $"{logPath}.{i + 1}";
            if (!File.Exists(src)) continue;
            if (File.Exists(dst)) File.Delete(dst);
            File.Move(src, dst);
        }
        File.Move(logPath, $"{logPath}.1", overwrite: true);
    }
}
