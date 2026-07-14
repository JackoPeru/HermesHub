using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using NemoclawChat_Windows.Services;
using Windows.Foundation;

namespace NemoclawChat_Windows.Pages;

public sealed partial class HardwarePage : Page
{
    private const int HistoryLimit = 120;
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly Dictionary<string, List<HardwareSample>> _history = [];
    private AppSettings _settings = new();
    private HardwareSnapshot? _previous;
    private IReadOnlyList<HardwareComponent> _components = [];
    private string _selectedComponentId = "cpu";
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
        _components = BuildComponents(snapshot, previous);
        if (_components.Count > 0 && !_components.Any(item => item.Id == _selectedComponentId))
        {
            _selectedComponentId = _components[0].Id;
        }

        foreach (var component in _components)
        {
            AddHistory(component);
        }

        HostText.Text = $"{snapshot.Hostname} - {snapshot.OperatingSystem} {snapshot.Architecture}";
        StatusText.Text = $"{snapshot.Message} Ultimo update: {snapshot.Timestamp.LocalDateTime:g}. Uptime: {FormatDuration(snapshot.UptimeSeconds)}. Processi: {snapshot.ProcessCount}.";

        RenderComponentList();
        RenderDetail();
    }

    private void RenderComponentList()
    {
        ComponentsPanel.Children.Clear();
        foreach (var component in _components)
        {
            var selected = component.Id == _selectedComponentId;
            var button = new Button
            {
                HorizontalAlignment = HorizontalAlignment.Stretch,
                HorizontalContentAlignment = HorizontalAlignment.Stretch,
                Padding = new Thickness(10),
                MinHeight = 78,
                CornerRadius = new CornerRadius(10),
                BorderThickness = new Thickness(1),
                Background = selected ? AccentBrush(0.18) : PanelBrush(),
                BorderBrush = selected ? AccentBrush() : UiBorderBrush(),
                Content = ComponentCard(component)
            };
            var componentId = component.Id;
            button.Click += (_, _) =>
            {
                _selectedComponentId = componentId;
                RenderComponentList();
                RenderDetail();
            };
            ComponentsPanel.Children.Add(button);
        }
    }

    private static Grid ComponentCard(HardwareComponent component)
    {
        var root = new Grid { RowSpacing = 6 };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var title = new TextBlock
        {
            Text = component.Title,
            Foreground = WhiteBrush(),
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            TextTrimming = Microsoft.UI.Xaml.TextTrimming.CharacterEllipsis
        };
        var value = new TextBlock
        {
            Text = component.PrimaryValue,
            Foreground = AccentBrush(),
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold
        };
        var subtitle = new TextBlock
        {
            Text = component.Subtitle,
            Foreground = MutedBrush(),
            FontSize = 12,
            TextTrimming = Microsoft.UI.Xaml.TextTrimming.CharacterEllipsis
        };
        var bar = UsageBar(component.UtilizationPercent);

        Grid.SetColumn(value, 1);
        Grid.SetRow(subtitle, 1);
        Grid.SetColumnSpan(subtitle, 2);
        Grid.SetRow(bar, 2);
        Grid.SetColumnSpan(bar, 2);
        root.Children.Add(title);
        root.Children.Add(value);
        root.Children.Add(subtitle);
        root.Children.Add(bar);
        return root;
    }

    private static Grid UsageBar(double percent)
    {
        var root = new Grid
        {
            Height = 4,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Background = new SolidColorBrush(Microsoft.UI.ColorHelper.FromArgb(0xFF, 0x55, 0x5D, 0x68))
        };
        var fill = new Border
        {
            HorizontalAlignment = HorizontalAlignment.Left,
            Background = AccentBrush(),
            Width = 0
        };
        root.Children.Add(fill);
        root.SizeChanged += (_, args) =>
        {
            fill.Width = args.NewSize.Width * ClampPercent(percent) / 100.0;
        };
        return root;
    }

    private void RenderDetail()
    {
        DetailPanel.Children.Clear();
        var component = _components.FirstOrDefault(item => item.Id == _selectedComponentId);
        if (component is null)
        {
            DetailPanel.Children.Add(MutedText("Nessun componente disponibile."));
            return;
        }

        var header = new Grid { ColumnSpacing = 16 };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var titleStack = new StackPanel { Spacing = 4 };
        titleStack.Children.Add(new TextBlock { Text = component.Title, Foreground = WhiteBrush(), FontSize = 32, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        titleStack.Children.Add(new TextBlock { Text = component.Subtitle, Foreground = MutedBrush(), TextWrapping = TextWrapping.Wrap });
        var primary = new TextBlock { Text = component.PrimaryValue, Foreground = AccentBrush(), FontSize = 30, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold };
        Grid.SetColumn(primary, 1);
        header.Children.Add(titleStack);
        header.Children.Add(primary);
        DetailPanel.Children.Add(header);

        var points = _history.TryGetValue(component.Id, out var history) ? history : [];
        DetailPanel.Children.Add(ChartBlock("Utilizzo", points.Select(item => (double?)item.UtilizationPercent).ToList(), 100, "%", AccentBrush()));
        DetailPanel.Children.Add(ChartBlock("Temperatura", points.Select(item => item.TemperatureC).ToList(), 100, " C", new SolidColorBrush(Microsoft.UI.ColorHelper.FromArgb(255, 255, 112, 98))));
        DetailPanel.Children.Add(StatsGrid(component.Stats));
    }

    private static Border ChartBlock(string title, IReadOnlyList<double?> values, double maxValue, string unit, Brush lineBrush)
    {
        const double width = 900;
        const double height = 220;
        var validValues = values.Where(item => item is not null).Select(item => item!.Value).ToList();
        var root = new Border
        {
            Background = PanelBrush(),
            BorderBrush = UiBorderBrush(),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(10),
            Padding = new Thickness(12)
        };
        var stack = new StackPanel { Spacing = 8 };
        stack.Children.Add(new TextBlock { Text = validValues.Count == 0 ? $"{title}: n/d" : $"{title}: {validValues[^1]:0.#}{unit}", Foreground = WhiteBrush(), FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });

        var canvas = new Canvas { Height = height, MinWidth = 640, HorizontalAlignment = HorizontalAlignment.Stretch };
        for (var i = 0; i <= 4; i++)
        {
            var y = height * i / 4;
            canvas.Children.Add(new Line
            {
                X1 = 0,
                X2 = width,
                Y1 = y,
                Y2 = y,
                Stroke = UiBorderBrush(),
                StrokeThickness = 1
            });
        }

        var plotValues = values.Count == 0 ? Array.Empty<double?>() : values;
        if (plotValues.Any(item => item is not null))
        {
            var points = new PointCollection();
            var count = Math.Max(2, plotValues.Count);
            for (var i = 0; i < plotValues.Count; i++)
            {
                var value = Math.Clamp(plotValues[i] ?? 0, 0, maxValue);
                var x = plotValues.Count == 1 ? width : width * i / (count - 1);
                var y = height - (value / maxValue * height);
                points.Add(new Point(x, y));
            }
            canvas.Children.Add(new Polyline
            {
                Points = points,
                Stroke = lineBrush,
                StrokeThickness = 2.5
            });
        }
        else
        {
            canvas.Children.Add(new TextBlock
            {
                Text = "Sensore non disponibile",
                Foreground = MutedBrush(),
                Margin = new Thickness(12, 92, 0, 0)
            });
        }

        stack.Children.Add(canvas);
        root.Child = stack;
        return root;
    }

    private static Grid StatsGrid(IReadOnlyList<HardwareStat> stats)
    {
        var grid = new Grid { ColumnSpacing = 12, RowSpacing = 12 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        for (var i = 0; i < stats.Count; i++)
        {
            if (i % 3 == 0)
            {
                grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            }
            var stat = stats[i];
            var card = new Border
            {
                Background = PanelBrush(),
                BorderBrush = UiBorderBrush(),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(12)
            };
            var stack = new StackPanel { Spacing = 4 };
            stack.Children.Add(new TextBlock { Text = stat.Label, Foreground = MutedBrush(), FontSize = 12 });
            stack.Children.Add(new TextBlock { Text = stat.Value, Foreground = WhiteBrush(), FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
            card.Child = stack;
            Grid.SetRow(card, i / 3);
            Grid.SetColumn(card, i % 3);
            grid.Children.Add(card);
        }

        return grid;
    }

    private void AddHistory(HardwareComponent component)
    {
        if (!_history.TryGetValue(component.Id, out var points))
        {
            points = [];
            _history[component.Id] = points;
        }
        points.Add(new HardwareSample(component.UtilizationPercent, component.TemperatureC));
        if (points.Count > HistoryLimit)
        {
            points.RemoveRange(0, points.Count - HistoryLimit);
        }
    }

    private static List<HardwareComponent> BuildComponents(HardwareSnapshot snapshot, HardwareSnapshot? previous)
    {
        var components = new List<HardwareComponent>();
        var temperatures = NormalizeTemperatures(snapshot.Temperatures);
        var tempByTitle = temperatures.ToDictionary(item => item.Title, item => item.CurrentC, StringComparer.OrdinalIgnoreCase);
        var cpuTemp = tempByTitle.TryGetValue("CPU package", out var cpuTempValue) ? cpuTempValue : (double?)null;

        components.Add(new HardwareComponent(
            "cpu",
            "CPU",
            snapshot.Processor == "-" ? $"{snapshot.PhysicalCores} core / {snapshot.LogicalCores} thread" : snapshot.Processor,
            $"{snapshot.CpuPercent:0}%",
            snapshot.CpuPercent,
            cpuTemp,
            [
                new("Utilizzo", $"{snapshot.CpuPercent:0}%"),
                new("Temperatura", FormatTemp(cpuTemp)),
                new("Core", $"{snapshot.PhysicalCores} fisici / {snapshot.LogicalCores} thread"),
                new("Frequenza", $"{FormatMhz(snapshot.CurrentMhz)} / max {FormatMhz(snapshot.MaxMhz)}"),
                new("Processi", $"{snapshot.ProcessCount}"),
                new("Uptime", FormatDuration(snapshot.UptimeSeconds))
            ]));

        components.Add(new HardwareComponent(
            "memory",
            "Memoria",
            $"{FormatBytes(snapshot.MemoryUsedBytes)} / {FormatBytes(snapshot.MemoryTotalBytes)}",
            $"{snapshot.MemoryPercent:0}%",
            snapshot.MemoryPercent,
            null,
            [
                new("Uso RAM", $"{snapshot.MemoryPercent:0}%"),
                new("Usata", FormatBytes(snapshot.MemoryUsedBytes)),
                new("Totale", FormatBytes(snapshot.MemoryTotalBytes)),
                new("Disponibile", FormatBytes(snapshot.MemoryAvailableBytes))
            ]));

        if (snapshot.SwapTotalBytes > 0)
        {
            components.Add(new HardwareComponent(
                "swap",
                "Swap",
                $"{FormatBytes(snapshot.SwapUsedBytes)} / {FormatBytes(snapshot.SwapTotalBytes)}",
                $"{snapshot.SwapPercent:0}%",
                snapshot.SwapPercent,
                null,
                [
                    new("Uso swap", $"{snapshot.SwapPercent:0}%"),
                    new("Usata", FormatBytes(snapshot.SwapUsedBytes)),
                    new("Totale", FormatBytes(snapshot.SwapTotalBytes))
                ]));
        }

        var seconds = previous is null ? 0 : Math.Max(0.1, (snapshot.Timestamp - previous.Timestamp).TotalSeconds);
        var downRate = previous is null ? 0 : Math.Max(0, snapshot.NetworkBytesReceived - previous.NetworkBytesReceived) / seconds;
        var upRate = previous is null ? 0 : Math.Max(0, snapshot.NetworkBytesSent - previous.NetworkBytesSent) / seconds;
        var networkPercent = Math.Min(100, (downRate + upRate) / (125 * 1024 * 1024) * 100);
        components.Add(new HardwareComponent(
            "network",
            "Ethernet",
            $"Down {FormatBytesPerSecond(downRate)} / Up {FormatBytesPerSecond(upRate)}",
            FormatBytesPerSecond(downRate + upRate),
            networkPercent,
            temperatures.FirstOrDefault(item => item.Title.StartsWith("Ethernet", StringComparison.OrdinalIgnoreCase))?.CurrentC,
            [
                new("Ricezione", FormatBytesPerSecond(downRate)),
                new("Invio", FormatBytesPerSecond(upRate)),
                new("Totale ricevuto", FormatBytes(snapshot.NetworkBytesReceived)),
                new("Totale inviato", FormatBytes(snapshot.NetworkBytesSent))
            ]));

        foreach (var gpu in snapshot.Gpus.OrderBy(item => item.Index))
        {
            var memoryPercent = gpu.MemoryTotalBytes > 0
                ? Math.Clamp((double)gpu.MemoryUsedBytes / gpu.MemoryTotalBytes * 100.0, 0, 100)
                : gpu.MemoryUtilizationPercent;
            components.Add(new HardwareComponent(
                $"gpu-{gpu.Index}",
                $"GPU {gpu.Index}",
                TrimGpuName(gpu.Name),
                $"{gpu.UtilizationPercent:0}%",
                gpu.UtilizationPercent,
                gpu.TemperatureC,
                [
                    new("Utilizzo GPU", $"{gpu.UtilizationPercent:0}%"),
                    new("Temperatura", FormatTemp(gpu.TemperatureC)),
                    new("VRAM", $"{FormatBytes(gpu.MemoryUsedBytes)} / {FormatBytes(gpu.MemoryTotalBytes)} ({memoryPercent:0}%)"),
                    new("Power", gpu.PowerDrawWatts is null || gpu.PowerLimitWatts is null ? "n/d" : $"{gpu.PowerDrawWatts:0} W / {gpu.PowerLimitWatts:0} W"),
                    new("Driver", gpu.DriverVersion)
                ]));
        }

        var diskIndex = 0;
        foreach (var disk in BuildDiskGroups(snapshot.Disks))
        {
            var diskTemp = disk.IsSsd && tempByTitle.TryGetValue("SSD NVMe", out var ssdTemp)
                ? ssdTemp
                : (double?)null;
            components.Add(new HardwareComponent(
                $"disk-{diskIndex}",
                disk.IsSsd ? $"SSD {diskIndex}" : $"Disco {diskIndex}",
                disk.Subtitle,
                $"{disk.Percent:0}%",
                disk.Percent,
                diskTemp,
                [
                    new("Spazio usato", $"{disk.Percent:0}%"),
                    new("Usato", FormatBytes(disk.UsedBytes)),
                    new("Libero", FormatBytes(disk.FreeBytes)),
                    new("Totale", FormatBytes(disk.TotalBytes)),
                    new("Temperatura", FormatTemp(diskTemp)),
                    new("Partizioni", disk.PartitionsText),
                    new("Device", disk.DevicesText)
                ]));
            diskIndex++;
        }

        return components;
    }

    private sealed record HardwareComponent(string Id, string Title, string Subtitle, string PrimaryValue, double UtilizationPercent, double? TemperatureC, IReadOnlyList<HardwareStat> Stats);
    private sealed record HardwareStat(string Label, string Value);
    private sealed record HardwareSample(double UtilizationPercent, double? TemperatureC);
    private sealed record DiskGroup(string Key, bool IsSsd, string Subtitle, long TotalBytes, long UsedBytes, long FreeBytes, double Percent, string PartitionsText, string DevicesText);
    private sealed record TemperatureView(string Title, string Source, double CurrentC, double? HighC, double? CriticalC, int SortKey);

    private static List<DiskGroup> BuildDiskGroups(IReadOnlyList<HardwareDiskRecord> disks)
    {
        var physicalKeys = disks
            .Select(disk => TryPhysicalDiskKey(disk.Device))
            .Where(key => !string.IsNullOrWhiteSpace(key))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        var singlePhysicalKey = physicalKeys.Count == 1 ? physicalKeys[0] : null;

        return disks
            .GroupBy(disk => PhysicalDiskKey(disk.Device, singlePhysicalKey), StringComparer.OrdinalIgnoreCase)
            .OrderBy(group => group.Key)
            .Select(group =>
            {
                var items = group.OrderBy(disk => DiskMountSortKey(disk.Mountpoint)).ToList();
                var total = items.Sum(disk => Math.Max(0, disk.TotalBytes));
                var used = items.Sum(disk => Math.Max(0, disk.UsedBytes));
                var free = items.Sum(disk => Math.Max(0, disk.FreeBytes));
                var percent = total > 0 ? (double)used / total * 100.0 : 0.0;
                var isSsd = group.Key.Contains("nvme", StringComparison.OrdinalIgnoreCase) || items.Any(disk => disk.Device.Contains("nvme", StringComparison.OrdinalIgnoreCase));
                var filesystems = string.Join(", ", items.Select(disk => disk.FileSystem).Where(text => !string.IsNullOrWhiteSpace(text)).Distinct(StringComparer.OrdinalIgnoreCase));
                var mounts = string.Join(", ", items.Select(disk => disk.Mountpoint));
                var devices = string.Join(", ", items.Select(disk => disk.Device).Distinct(StringComparer.OrdinalIgnoreCase));
                var subtitle = items.Count == 1
                    ? $"{items[0].Mountpoint} ({items[0].FileSystem})"
                    : $"{items.Count} partizioni - {filesystems}";
                return new DiskGroup(group.Key, isSsd, subtitle, total, used, free, percent, mounts, devices);
            })
            .ToList();
    }

    private static string PhysicalDiskKey(string device, string? singlePhysicalKey)
    {
        var direct = TryPhysicalDiskKey(device);
        if (!string.IsNullOrWhiteSpace(direct))
        {
            return direct;
        }

        if (!string.IsNullOrWhiteSpace(singlePhysicalKey) && device.StartsWith("/dev/mapper/", StringComparison.OrdinalIgnoreCase))
        {
            return singlePhysicalKey;
        }

        return device;
    }

    private static string? TryPhysicalDiskKey(string device)
    {
        var name = device.Trim().Replace("\\", "/").Split('/', StringSplitOptions.RemoveEmptyEntries).LastOrDefault() ?? device;
        if (name.StartsWith("nvme", StringComparison.OrdinalIgnoreCase))
        {
            var partitionIndex = name.IndexOf('p');
            return partitionIndex > 0 ? name[..partitionIndex] : name;
        }

        if (name.StartsWith("sd", StringComparison.OrdinalIgnoreCase) && name.Length > 3 && char.IsDigit(name[^1]))
        {
            return name.TrimEnd('0', '1', '2', '3', '4', '5', '6', '7', '8', '9');
        }

        return null;
    }

    private static string DiskMountSortKey(string mountpoint)
    {
        return mountpoint == "/" ? " " : mountpoint;
    }

    private static List<TemperatureView> NormalizeTemperatures(IReadOnlyList<HardwareTemperatureRecord> temperatures)
    {
        var hasNvmeComposite = temperatures.Any(item =>
            item.Name.Equals("nvme", StringComparison.OrdinalIgnoreCase) &&
            item.Label.Equals("Composite", StringComparison.OrdinalIgnoreCase));

        return temperatures
            .Select(temp => NormalizeTemperature(temp, hasNvmeComposite))
            .Where(temp => temp is not null)
            .Select(temp => temp!)
            .GroupBy(temp => temp.Title)
            .Select(group => group.OrderByDescending(temp => temp.CurrentC).First())
            .OrderBy(temp => temp.SortKey)
            .ThenByDescending(temp => temp.CurrentC)
            .ToList();
    }

    private static TemperatureView? NormalizeTemperature(HardwareTemperatureRecord temp, bool hasNvmeComposite)
    {
        if (!double.IsFinite(temp.CurrentC) || temp.CurrentC < 0 || temp.CurrentC > 150)
        {
            return null;
        }

        var name = temp.Name.Trim();
        var label = temp.Label.Trim();
        var lowerName = name.ToLowerInvariant();
        var lowerLabel = label.ToLowerInvariant();
        if (hasNvmeComposite && lowerName == "nvme" && lowerLabel.StartsWith("sensor 2", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        var title = (lowerName, lowerLabel) switch
        {
            ("k10temp", "tctl") => "CPU package",
            ("nvme", "composite") => "SSD NVMe",
            ("nvme", "sensor 1") => "SSD NVMe controller",
            ("nvme", "sensor 3") => "SSD NVMe NAND",
            _ when lowerName == "k10temp" && lowerLabel.StartsWith("tccd", StringComparison.OrdinalIgnoreCase) => $"CPU CCD {new string(label.Where(char.IsDigit).ToArray())}",
            _ when lowerName.StartsWith("spd", StringComparison.OrdinalIgnoreCase) => "RAM DIMM",
            _ when lowerName.StartsWith("r8169", StringComparison.OrdinalIgnoreCase) => $"Ethernet controller {name.Split("_0_").LastOrDefault() ?? ""}".Trim(),
            _ when !string.IsNullOrWhiteSpace(label) && label != "-" => label,
            _ => string.IsNullOrWhiteSpace(name) ? "Sensore temperatura" : name
        };
        if (title == "CPU CCD ")
        {
            title = "CPU CCD 1";
        }

        var sortKey = title.StartsWith("CPU package", StringComparison.OrdinalIgnoreCase) ? 0 :
            title.StartsWith("CPU CCD", StringComparison.OrdinalIgnoreCase) ? 1 :
            title.StartsWith("SSD", StringComparison.OrdinalIgnoreCase) ? 2 :
            title.StartsWith("RAM", StringComparison.OrdinalIgnoreCase) ? 3 :
            title.StartsWith("Ethernet", StringComparison.OrdinalIgnoreCase) ? 4 : 9;

        var source = $"Sensore {name}";
        if (!string.IsNullOrWhiteSpace(label) && !label.Equals(name, StringComparison.OrdinalIgnoreCase))
        {
            source += $" / {label}";
        }

        return new TemperatureView(title, source, temp.CurrentC, SanitizeTemperatureLimit(temp.HighC), SanitizeTemperatureLimit(temp.CriticalC), sortKey);
    }

    private static double? SanitizeTemperatureLimit(double? value)
    {
        return value is > 1 and <= 150 ? value : null;
    }

    private static TextBlock MutedText(string text) => new()
    {
        Text = text,
        Foreground = MutedBrush(),
        FontSize = 12,
        TextWrapping = TextWrapping.Wrap
    };

    private static SolidColorBrush WhiteBrush() => new(Colors.White);
    private static Brush MutedBrush() => (Brush)Application.Current.Resources["MutedTextBrush"];
    private static SolidColorBrush AccentBrush(double alpha = 1.0) => new(Microsoft.UI.ColorHelper.FromArgb((byte)(255 * alpha), 245, 165, 36));
    private static Brush UiBorderBrush() => (Brush)Application.Current.Resources["BorderBrushSoft"];
    private static SolidColorBrush PanelBrush() => new(Microsoft.UI.ColorHelper.FromArgb(255, 21, 25, 34));

    private static double ClampPercent(double value) => double.IsFinite(value) ? Math.Clamp(value, 0, 100) : 0;

    private static string FormatMhz(double? value) => value is > 0 ? $"{value:0} MHz" : "n/d";

    private static string FormatTemp(double? value) => value is null ? "n/d" : $"{value:0.0} C";

    private static string TrimGpuName(string name)
    {
        return name.Replace("NVIDIA ", "", StringComparison.OrdinalIgnoreCase).Trim();
    }

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
