using System.Text;
using System.Text.Json;

namespace NemoclawChat_Windows.Services;

internal static class AtomicJsonFile
{
    public static void Write(string destinationPath, string content)
    {
        var directory = Path.GetDirectoryName(destinationPath);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var tempPath = $"{destinationPath}.{Guid.NewGuid():N}.tmp";
        var backupPath = destinationPath + ".bak";
        try
        {
            using (var stream = new FileStream(
                       tempPath,
                       FileMode.CreateNew,
                       FileAccess.Write,
                       FileShare.None,
                       64 * 1024,
                       FileOptions.WriteThrough))
            using (var writer = new StreamWriter(stream, new UTF8Encoding(false)))
            {
                writer.Write(content);
                writer.Flush();
                stream.Flush(flushToDisk: true);
            }

            if (File.Exists(destinationPath))
            {
                try
                {
                    File.Replace(tempPath, destinationPath, backupPath);
                }
                catch (Exception ex) when (ex is IOException or PlatformNotSupportedException)
                {
                    File.Copy(destinationPath, backupPath, overwrite: true);
                    File.Move(tempPath, destinationPath, overwrite: true);
                }
            }
            else
            {
                File.Move(tempPath, destinationPath);
            }
        }
        finally
        {
            try { File.Delete(tempPath); }
            catch (Exception ex) { System.Diagnostics.Trace.WriteLine($"[AtomicJsonFile] Temp cleanup failed: {ex.Message}"); }
        }
    }

    public static string? Read(string sourcePath)
    {
        var primary = TryReadValidJson(sourcePath);
        if (primary is not null)
        {
            return primary;
        }

        var backup = sourcePath + ".bak";
        var recovered = TryReadValidJson(backup);
        if (recovered is not null)
        {
            System.Diagnostics.Trace.WriteLine($"[AtomicJsonFile] recovered {Path.GetFileName(sourcePath)} from backup.");
        }

        return recovered;
    }

    private static string? TryReadValidJson(string path)
    {
        if (!File.Exists(path))
        {
            return null;
        }

        try
        {
            var content = File.ReadAllText(path, new UTF8Encoding(false));
            using var _ = JsonDocument.Parse(content);
            return content;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException)
        {
            System.Diagnostics.Trace.WriteLine($"[AtomicJsonFile] invalid {Path.GetFileName(path)}: {ex.Message}");
            return null;
        }
    }
}
