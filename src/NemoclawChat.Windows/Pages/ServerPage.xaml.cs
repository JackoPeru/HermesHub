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
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e)
    {
        await LoadServerSnapshotAsync();
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
        GatewayText.Text = snapshot.Gateway;
        GatewayDetailText.Text = $"Health: {GatewayService.HermesRoot(_settings)}/health";
        GatewayWsText.Text = $"{_settings.GatewayUrl.TrimEnd('/')}/capabilities";
        ModelText.Text = snapshot.Model;
        ProviderText.Text = snapshot.ProviderDetail;
        InferenceText.Text = $"{snapshot.InferenceEndpoint}\nCartella video Hermes: {(string.IsNullOrWhiteSpace(snapshot.VideoLibraryPath) ? "in attesa di sync server" : snapshot.VideoLibraryPath)}";
        PolicyText.Text = snapshot.Policy;
        StatusText.Text = snapshot.StatusMessage;
        await RefreshControlAsync();
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
