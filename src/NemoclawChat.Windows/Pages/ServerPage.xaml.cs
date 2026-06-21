using System.Net.Http;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;

namespace NemoclawChat_Windows.Pages;

public sealed partial class ServerPage : Page
{
    private AppSettings _settings = new();

    public ServerPage()
    {
        InitializeComponent();
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
    }
}
