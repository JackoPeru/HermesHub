using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;

namespace NemoclawChat_Windows.Pages;

public sealed partial class HardwarePage : Page
{
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(1) };
    private AppSettings _settings = new();
    private HardwareSnapshot? _previous;
    private bool _loading;

    public HardwarePage()
    {
        InitializeComponent();
        _timer.Tick += Timer_Tick;
    }

    private async void Page_Loaded(object sender, RoutedEventArgs e)
    {
        _settings = AppSettingsStore.Load();
        await RefreshAsync();
        _timer.Start();
    }

    private void Page_Unloaded(object sender, RoutedEventArgs e)
    {
        _timer.Stop();
        _timer.Tick -= Timer_Tick;
    }

    private async void Timer_Tick(object? sender, object e)
    {
        await RefreshAsync();
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e)
    {
        await RefreshAsync();
    }

    private async Task RefreshAsync()
    {
        if (_loading)
        {
            return;
        }

        _loading = true;
        try
        {
            var snapshot = await GatewayService.GetHardwareSnapshotAsync(_settings);
            Render(snapshot, _previous);
            _previous = snapshot;
        }
        finally
        {
            _loading = false;
        }
    }

    private void Render(HardwareSnapshot snapshot, HardwareSnapshot? previous)
    {
        HostText.Text = $"{snapshot.Hostname} - {snapshot.OperatingSystem} {snapshot.Architecture}";
        StatusText.Text = $"{snapshot.Message} Ultimo update: {snapshot.Timestamp.LocalDateTime:g}. Uptime: {FormatDuration(snapshot.UptimeSeconds)}. Processi: {snapshot.ProcessCount}.";

        CpuText.Text = $"{snapshot.CpuPercent:0}%";
        CpuBar.Value = ClampPercent(snapshot.CpuPercent);
        CpuDetailText.Text = $"{snapshot.PhysicalCores} core fisici / {snapshot.LogicalCores} thread. Frequenza: {FormatMhz(snapshot.CurrentMhz)} / max {FormatMhz(snapshot.MaxMhz)}. CPU: {snapshot.Processor}";

        MemoryText.Text = $"{snapshot.MemoryPercent:0}%";
        MemoryBar.Value = ClampPercent(snapshot.MemoryPercent);
        MemoryDetailText.Text = $"{FormatBytes(snapshot.MemoryUsedBytes)} usati / {FormatBytes(snapshot.MemoryTotalBytes)} totali. Disponibili: {FormatBytes(snapshot.MemoryAvailableBytes)}.";

        SwapText.Text = $"{snapshot.SwapPercent:0}%";
        SwapBar.Value = ClampPercent(snapshot.SwapPercent);
        SwapDetailText.Text = $"{FormatBytes(snapshot.SwapUsedBytes)} usati / {FormatBytes(snapshot.SwapTotalBytes)} totali.";

        var seconds = previous is null ? 0 : Math.Max(0.1, (snapshot.Timestamp - previous.Timestamp).TotalSeconds);
        var downRate = previous is null ? 0 : Math.Max(0, snapshot.NetworkBytesReceived - previous.NetworkBytesReceived) / seconds;
        var upRate = previous is null ? 0 : Math.Max(0, snapshot.NetworkBytesSent - previous.NetworkBytesSent) / seconds;
        NetworkText.Text = $"Down {FormatBytesPerSecond(downRate)} / Up {FormatBytesPerSecond(upRate)}";
        NetworkDetailText.Text = $"Totale ricevuto {FormatBytes(snapshot.NetworkBytesReceived)} - inviato {FormatBytes(snapshot.NetworkBytesSent)}.";

        RenderDisks(snapshot.Disks);
        RenderTemperatures(snapshot);
    }

    private void RenderDisks(IReadOnlyList<HardwareDiskRecord> disks)
    {
        DisksPanel.Children.Clear();
        if (disks.Count == 0)
        {
            DisksPanel.Children.Add(MutedText("Nessun disco esposto dal gateway."));
            return;
        }

        foreach (var disk in disks)
        {
            DisksPanel.Children.Add(new StackPanel
            {
                Spacing = 6,
                Children =
                {
                    new TextBlock { Text = $"{disk.Mountpoint} ({disk.FileSystem})", Foreground = WhiteBrush(), FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap },
                    new ProgressBar { Maximum = 100, Value = ClampPercent(disk.Percent) },
                    MutedText($"{disk.Percent:0}% - {FormatBytes(disk.UsedBytes)} usati / {FormatBytes(disk.TotalBytes)} totali - libero {FormatBytes(disk.FreeBytes)} - {disk.Device}")
                }
            });
        }
    }

    private void RenderTemperatures(HardwareSnapshot snapshot)
    {
        TemperaturesPanel.Children.Clear();
        if (snapshot.Temperatures.Count == 0)
        {
            TemperaturesPanel.Children.Add(MutedText($"Sensori non disponibili o non esposti. Stato: {snapshot.TemperatureSupport}. Su Windows spesso servono driver/tool vendor; su Ubuntu installa psutil e abilita lm-sensors."));
            return;
        }

        foreach (var temp in snapshot.Temperatures.OrderByDescending(item => item.CurrentC).Take(24))
        {
            TemperaturesPanel.Children.Add(new StackPanel
            {
                Spacing = 4,
                Children =
                {
                    new TextBlock { Text = $"{temp.Label}: {temp.CurrentC:0.0} C", Foreground = WhiteBrush(), FontWeight = Microsoft.UI.Text.FontWeights.SemiBold },
                    MutedText($"Fonte {temp.Name}. High {FormatTemp(temp.HighC)}, critical {FormatTemp(temp.CriticalC)}.")
                }
            });
        }
    }

    private static TextBlock MutedText(string text) => new()
    {
        Text = text,
        Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
        FontSize = 12,
        TextWrapping = TextWrapping.Wrap
    };

    private static Brush WhiteBrush() => new SolidColorBrush(Microsoft.UI.Colors.White);

    private static double ClampPercent(double value) => double.IsFinite(value) ? Math.Clamp(value, 0, 100) : 0;

    private static string FormatMhz(double? value) => value is > 0 ? $"{value:0} MHz" : "n/d";

    private static string FormatTemp(double? value) => value is null ? "n/d" : $"{value:0.0} C";

    private static string FormatDuration(long seconds)
    {
        if (seconds <= 0)
        {
            return "n/d";
        }

        var span = TimeSpan.FromSeconds(seconds);
        return span.TotalDays >= 1
            ? $"{(int)span.TotalDays}g {span.Hours}h"
            : $"{span.Hours}h {span.Minutes}m";
    }

    private static string FormatBytesPerSecond(double bytesPerSecond) => $"{FormatBytes((long)bytesPerSecond)}/s";

    private static string FormatBytes(long bytes)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB", "PB"];
        var value = Math.Max(0, (double)bytes);
        var unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }

        return unit == 0 ? $"{value:0} {units[unit]}" : $"{value:0.0} {units[unit]}";
    }
}
