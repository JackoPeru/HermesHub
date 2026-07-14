namespace NemoclawChat_Windows.Services;

internal static class AppDataMigration
{
    private const string CurrentDirectoryName = "ChatClaw";
    private const string LegacyDirectoryName = "NemoclawChat";

    public static string CurrentDirectoryPath
    {
        get
        {
            var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            return Path.Combine(localAppData, CurrentDirectoryName);
        }
    }

    public static void Run()
    {
        var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var currentDirectory = Path.Combine(localAppData, CurrentDirectoryName);
        var legacyDirectory = Path.Combine(localAppData, LegacyDirectoryName);

        try
        {
            Directory.CreateDirectory(currentDirectory);
            if (!Directory.Exists(legacyDirectory))
            {
                return;
            }

            foreach (var source in Directory.EnumerateFiles(legacyDirectory, "*", SearchOption.TopDirectoryOnly))
            {
                var destination = Path.Combine(currentDirectory, Path.GetFileName(source));
                if (File.Exists(destination))
                {
                    continue;
                }

                try
                {
                    File.Copy(source, destination, overwrite: false);
                }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
                {
                    System.Diagnostics.Trace.WriteLine($"[AppDataMigration] {Path.GetFileName(source)}: {ex.Message}");
                }
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            System.Diagnostics.Trace.WriteLine($"[AppDataMigration] init: {ex.Message}");
        }
    }
}
