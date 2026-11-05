using System.Net.Http;
using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;

namespace NemoclawChat_Windows.Pages;

[System.Diagnostics.CodeAnalysis.SuppressMessage("Design", "CA1001:Types that own disposable fields should be disposable", Justification = "La pagina WinUI rilascia il debounce al successivo evento; il framework non consuma IDisposable.")]
public sealed partial class ServerPage : Page
{
    private AppSettings _settings = new();
    private CancellationTokenSource? _logFilterDebounce;

    public ServerPage()
    {
        InitializeComponent();
        Unloaded += (_, _) => { _logFilterDebounce?.Cancel(); _logFilterDebounce?.Dispose(); _logFilterDebounce = null; };
        _ = LoadServerSnapshotAsync();
        _ = LoadBoardAsync();
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e)
    {
        await LoadServerSnapshotAsync();
        await LoadBoardAsync();
    }

    private async void ReloadBoard_Click(object sender, RoutedEventArgs e)
    {
        await LoadBoardAsync();
    }

    private async void TestGateway_Click(object sender, RoutedEventArgs e)
    {
        var healthUrl = $"{GatewayService.HermesRoot(_settings)}/health";
        StatusText.Text = $"Test: {healthUrl}";
        var snapshot = await GatewayService.GetServerSnapshotAsync(_settings);
        StatusText.Text = snapshot.StatusMessage;
    }

    private async void TestGatewayWs_Click(object sender, RoutedEventArgs e)
    {
        GatewayWsResultText.Text = "Lettura /v1/capabilities e /v1/models...";
        var capabilities = await GatewayService.SendHermesRequestAsync(_settings, HttpMethod.Get, "/v1/capabilities");
        var models = await GatewayService.SendHermesRequestAsync(_settings, HttpMethod.Get, "/v1/models");
        GatewayWsResultText.Text = $"Capabilities:\n{capabilities}\n\nModels:\n{models}";
    }

    private void ShowApiContract_Click(object sender, RoutedEventArgs e)
    {
        ContractText.Text =
            "GET /health -> stato Hermes API Server\n" +
            "GET /health/detailed -> stato dettagliato\n" +
            "GET /v1/hub/hardware -> prestazioni hardware host ogni secondo lato client\n" +
            "GET /v1/models -> modelli disponibili\n" +
            "GET /v1/capabilities -> API supportate\n" +
            "POST /v1/responses -> chat primaria con store/conversation\n" +
            "POST /v1/chat/completions -> fallback OpenAI-compatible\n" +
            "POST /v1/runs -> run agente distaccato\n" +
            "GET /api/jobs -> lista cron Hermes\n" +
            "POST /api/jobs -> crea cron Hermes";
    }

    private string? _lastPhoneCommandId;

    private async void PhoneSend_Click(object sender, RoutedEventArgs e)
    {
        var kind = (PhoneKindBox.SelectedItem as ComboBoxItem)?.Content?.ToString() ?? "tap";
        JsonDocument payload;
        try
        {
            payload = JsonDocument.Parse(
                string.IsNullOrWhiteSpace(PhoneParamsBox.Text) ? "{}" : PhoneParamsBox.Text);
        }
        catch (JsonException)
        {
            PhoneOutputBox.Text = "Payload JSON non valido.";
            return;
        }

        PhoneOutputBox.Text = $"Accodo {kind}…";
        var body = JsonSerializer.Serialize(new
        {
            kind,
            payload = JsonSerializer.Deserialize<Dictionary<string, object>>(payload.RootElement.GetRawText()),
        });
        var response = await GatewayService.SendHermesRequestAsync(
            _settings, HttpMethod.Post, "/v1/hub/phone/commands", body);
        PhoneOutputBox.Text = response;
        try
        {
            using var doc = JsonDocument.Parse(response);
            if (doc.RootElement.TryGetProperty("id", out var id))
            {
                _lastPhoneCommandId = id.GetString();
            }
        }
        catch (JsonException)
        {
        }
    }

    private async void PhoneResult_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(_lastPhoneCommandId))
        {
            PhoneOutputBox.Text = "Accoda prima un comando.";
            return;
        }

        PhoneOutputBox.Text = await GatewayService.SendHermesRequestAsync(
            _settings, HttpMethod.Get, $"/v1/hub/phone/commands/{Uri.EscapeDataString(_lastPhoneCommandId)}");
    }

    private async void RunDiagnostics_Click(object sender, RoutedEventArgs e)
    {
        DiagnosticsPanel.Children.Clear();
        DiagnosticsPanel.Children.Add(new TextBlock
        {
            Text = "Diagnostica in corso...",
            Foreground = (Brush)Application.Current.Resources["MutedTextBrush"]
        });
        var results = await GatewayService.RunDiagnosticsAsync(_settings);
        DiagnosticsPanel.Children.Clear();
        foreach (var item in results)
        {
            var border = new Border
            {
                Padding = new Thickness(12),
                CornerRadius = new CornerRadius(10),
                Background = (Brush)Application.Current.Resources["AssistantBubbleBrush"],
                Child = new StackPanel
                {
                    Spacing = 4,
                    Children =
                    {
                        new TextBlock { Text = $"{(item.Ok ? "OK" : "Errore")} - {item.Label}", Foreground = new SolidColorBrush(Microsoft.UI.Colors.White), FontWeight = Microsoft.UI.Text.FontWeights.SemiBold },
                        new TextBlock { Text = item.Endpoint, Foreground = (Brush)Application.Current.Resources["MutedTextBrush"], FontSize = 11, TextWrapping = TextWrapping.Wrap },
                        new TextBlock { Text = item.Message, Foreground = (Brush)Application.Current.Resources["MutedTextBrush"], TextWrapping = TextWrapping.Wrap },
                        new TextBlock { Text = item.Ok ? string.Empty : $"Azione: {item.Action}", Foreground = (Brush)Application.Current.Resources["AccentGreenBrush"], TextWrapping = TextWrapping.Wrap }
                    }
                }
            };
            DiagnosticsPanel.Children.Add(border);
        }
    }

    private async Task LoadServerSnapshotAsync()
    {
        _settings = AppSettingsStore.Load();
        var snapshot = await GatewayService.GetServerSnapshotAsync(_settings);
        // Valori corti sempre visibili, URL interi dietro gli Expander (come Android).
        GatewayText.Text = ShortHost(snapshot.Gateway);
        GatewayFullText.Text = $"Gateway: {snapshot.Gateway}";
        GatewayDetailText.Text = $"Health: {GatewayService.HermesRoot(_settings)}/health";
        GatewayWsText.Text = $"{_settings.GatewayUrl.TrimEnd('/')}/capabilities";
        ModelText.Text = snapshot.Model;
        ProviderText.Text = snapshot.ProviderDetail;
        InferenceText.Text = ShortHost(snapshot.InferenceEndpoint);
        InferenceFullText.Text = snapshot.InferenceEndpoint;
        VideoPathText.Text = $"Cartella video Hermes: {(string.IsNullOrWhiteSpace(snapshot.VideoLibraryPath) ? "in attesa di sync server" : snapshot.VideoLibraryPath)}";
        PolicyText.Text = snapshot.Policy;
        StatusText.Text = snapshot.StatusMessage;
        await RefreshControlAsync();
    }

    /// <summary>
    /// Solo host:porta ("tutto ok?"), l'URL intero resta dietro il tap.
    /// </summary>
    private static string ShortHost(string url)
    {
        try
        {
            var uri = new Uri(url);
            var host = uri.Host;
            if (!uri.IsDefaultPort) host += $":{uri.Port}";
            return host;
        }
        catch (UriFormatException)
        {
            return url.Length <= 24 ? url : url[..24];
        }
    }

    /// <summary>
    /// Hero + numeri live come Android: probe tri-stato, tinta semantica,
    /// timestamp e ricarica manuale. La chat (HomePage) non si tocca.
    /// </summary>
    private async Task LoadBoardAsync()
    {
        PaintHero(null, 0, null);
        var settings = AppSettingsStore.Load();
        bool? online = null;
        int? bots = null;
        int? cronActive = null;
        int? chats = null;
        string? gpuMode = null;
        int? gpuJobs = null;
        var failedChecks = 0;
        try
        {
            var probeTask = Task.Run(async () =>
            {
                try
                {
                    var result = await GatewayService.SendHermesRequestAsync(settings, HttpMethod.Get, "/v1/capabilities");
                    if (result.StartsWith("HTTP ", StringComparison.Ordinal))
                    {
                        var codeText = result.Length >= 8 ? result.Substring(5, 3) : string.Empty;
                        return int.TryParse(codeText, out var code) && code is >= 200 and <= 399;
                    }
                    return true;
                }
                catch (Exception)
                {
                    return false;
                }
            });
            var botsTask = Task.Run(async () =>
            {
                try { return (int?)((await HermesBotConnectionService.LoadAllRostersAsync(settings)).Items.Count); }
                catch (Exception) { return null; }
            });
            var cronTask = Task.Run(async () =>
            {
                try { return (int?)((await GatewayService.LoadCronJobsAsync(settings)).Jobs.Count(job => job.Enabled)); }
                catch (Exception) { return null; }
            });
            var chatsTask = Task.Run<int?>(() =>
            {
                try { return ChatArchiveStore.Load().Count; }
                catch (Exception) { return null; }
            });
            var gpuTask = Task.Run(async () =>
            {
                try
                {
                    var (statusCode, statusBody) = await GatewayService.SendManagerRequestAsync(settings, HttpMethod.Get, "/status");
                    string? mode = null;
                    if (statusCode is >= 200 and <= 299)
                    {
                        using var statusDoc = JsonDocument.Parse(statusBody);
                        if (statusDoc.RootElement.TryGetProperty("desired_mode", out var modeProp))
                        {
                            var raw = modeProp.GetString();
                            if (!string.IsNullOrWhiteSpace(raw)) mode = raw;
                        }
                    }
                    int? running = null;
                    var (jobCode, jobBody) = await GatewayService.SendManagerRequestAsync(settings, HttpMethod.Get, "jobs?limit=300");
                    if (jobCode is >= 200 and <= 299)
                    {
                        using var jobsDoc = JsonDocument.Parse(jobBody);
                        if (jobsDoc.RootElement.TryGetProperty("jobs", out var jobs) && jobs.ValueKind == JsonValueKind.Array)
                        {
                            running = jobs.EnumerateArray().Count(job =>
                                job.ValueKind == JsonValueKind.Object &&
                                job.TryGetProperty("status", out var state) &&
                                state.GetString() == "running");
                        }
                    }
                    return (mode, running);
                }
                catch (Exception)
                {
                    return (null, (int?)null);
                }
            });
            var diagnosticsTask = Task.Run(async () =>
            {
                try { return (await GatewayService.RunDiagnosticsAsync(settings)).Count(item => !item.Ok); }
                catch (Exception) { return 0; }
            });
            await Task.WhenAll(probeTask, botsTask, cronTask, chatsTask, gpuTask, diagnosticsTask);
            online = probeTask.Result;
            bots = botsTask.Result;
            cronActive = cronTask.Result;
            chats = chatsTask.Result;
            gpuMode = gpuTask.Result.mode;
            gpuJobs = gpuTask.Result.running;
            failedChecks = diagnosticsTask.Result;
        }
        catch (Exception)
        {
            online = false;
        }
        BotValueText.Text = bots?.ToString(System.Globalization.CultureInfo.InvariantCulture) ?? "—";
        CronValueText.Text = cronActive?.ToString(System.Globalization.CultureInfo.InvariantCulture) ?? "—";
        ChatValueText.Text = chats?.ToString(System.Globalization.CultureInfo.InvariantCulture) ?? "—";
        GpuValueText.Text = gpuMode ?? "—";
        GpuDetailText.Text = gpuJobs.HasValue ? $"{gpuJobs} job" : "manager";
        BoardTimeText.Text = $"aggiornato {DateTime.Now:HH:mm}";
        PaintHero(online, failedChecks, string.IsNullOrWhiteSpace(_settings.Model) ? _settings.GatewayUrl : _settings.Model);
    }

    private void PaintHero(bool? online, int failedChecks, string? subtitle)
    {
        string title;
        Windows.UI.Color tint;
        if (online is null)
        {
            title = "Controllo stato…";
            tint = Microsoft.UI.Colors.Gray;
        }
        else if (online == false)
        {
            title = "Server offline";
            tint = Microsoft.UI.Colors.Red;
        }
        else if (failedChecks > 0)
        {
            title = $"Da guardare ({failedChecks})";
            tint = Microsoft.UI.Colors.Orange;
        }
        else
        {
            title = "Tutto operativo";
            tint = Microsoft.UI.Colors.LimeGreen;
        }
        HeroTitle.Text = title;
        HeroSubtitle.Text = subtitle ?? string.Empty;
        HeroDot.Fill = new SolidColorBrush(tint);
        HeroCard.BorderBrush = new SolidColorBrush(tint);
        HeroCard.Background = new SolidColorBrush(Windows.UI.Color.FromArgb(0x14, tint.R, tint.G, tint.B));
    }

    private string SelectedService() => (ServiceBox.SelectedItem as ComboBoxItem)?.Content?.ToString() ?? "gateway";

    private async void StartService_Click(object sender, RoutedEventArgs e) => await RunServiceActionAsync("start", confirm: false);
    private async void StopService_Click(object sender, RoutedEventArgs e) => await RunServiceActionAsync("stop", confirm: true);
    private async void RestartService_Click(object sender, RoutedEventArgs e) => await RunServiceActionAsync("restart", confirm: true);
    private async void RefreshControl_Click(object sender, RoutedEventArgs e) => await RefreshControlAsync();

    private async Task RunServiceActionAsync(string action, bool confirm)
    {
        var service = SelectedService();
        if (confirm)
        {
            var dialog = new ContentDialog { XamlRoot = XamlRoot, Title = $"Conferma {action}", Content = $"Vuoi eseguire {action} su {service}? Le sessioni attive possono interrompersi.", PrimaryButtonText = "Conferma", CloseButtonText = "Annulla", DefaultButton = ContentDialogButton.Close };
            if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        }
        ControlOutputBox.Text = $"Eseguo {action} su {service}...";
        ControlOutputBox.Text = await GatewayService.SendHermesRequestAsync(_settings, HttpMethod.Post, "/v1/hub/server/action", JsonSerializer.Serialize(new { service, action }));
        await RefreshControlAsync();
    }

    private async void Maintenance_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: string operation }) return;
        var dialog = new ContentDialog { XamlRoot = XamlRoot, Title = $"Conferma {operation}", Content = "L'operazione usa esclusivamente il comando preconfigurato sul server e può richiedere diversi minuti.", PrimaryButtonText = "Esegui", CloseButtonText = "Annulla", DefaultButton = ContentDialogButton.Close };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        ControlOutputBox.Text = $"Operazione {operation} in corso...";
        ControlOutputBox.Text = await GatewayService.SendHermesRequestAsync(_settings, HttpMethod.Post, "/v1/hub/server/maintenance", JsonSerializer.Serialize(new { operation }));
    }

    private async void IntegrationTests_Click(object sender, RoutedEventArgs e)
    {
        ControlOutputBox.Text = "Test integrazioni...";
        var capabilities = await GatewayService.SendHermesRequestAsync(_settings, HttpMethod.Get, "/v1/capabilities");
        var media = await GatewayService.SendHermesRequestAsync(_settings, HttpMethod.Get, "/v1/video/library");
        var tool = await GatewayService.SendHermesRequestAsync(_settings, HttpMethod.Post, "/v1/runs", JsonSerializer.Serialize(new { model = "hermes-agent", input = "Esegui un controllo innocuo: rispondi solo HERMES_HUB_TOOL_OK senza modificare dati." }));
        ControlOutputBox.Text = $"CAPABILITIES STT/TTS\n{capabilities}\n\nMEDIA\n{media}\n\nTOOL RUN\n{tool}";
    }

    private async void LogFilterBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        _logFilterDebounce?.Cancel();
        _logFilterDebounce?.Dispose();
        _logFilterDebounce = new CancellationTokenSource();
        try { await Task.Delay(350, _logFilterDebounce.Token); await RefreshControlAsync(); }
        catch (OperationCanceledException) { }
    }

    private async Task RefreshControlAsync()
    {
        var filter = Uri.EscapeDataString(LogFilterBox?.Text?.Trim() ?? string.Empty);
        var json = await GatewayService.SendHermesRequestAsync(_settings, HttpMethod.Get, $"/v1/hub/server/control?filter={filter}");
        ControlOutputBox.Text = json;
        try
        {
            using var document = JsonDocument.Parse(json);
            CompatibilityWarningText.Text = document.RootElement.TryGetProperty("compatibility_warning", out var warning) ? warning.GetString() ?? string.Empty : string.Empty;
        }
        catch (JsonException) { CompatibilityWarningText.Text = "Centro controllo non disponibile: applicare il gateway aggiornato."; }
    }
}
