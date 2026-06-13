using System.Net.Http;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml;
using NemoclawChat_Windows.Services;

namespace NemoclawChat_Windows.Pages;

public sealed partial class SettingsPage : Page
{
    private static readonly HttpClient HttpClient = new() { Timeout = TimeSpan.FromSeconds(5) };

    public SettingsPage()
    {
        InitializeComponent();
        LoadSettings();
    }

    private void LoadSettings()
    {
        var settings = AppSettingsStore.Load();
        GatewayUrlBox.Text = settings.GatewayUrl;
        ApiKeyBox.Password = GatewayCredentialStore.LoadSecret();
        GatewayWsUrlBox.Text = settings.GatewayWsUrl;
        AdminBridgeUrlBox.Text = settings.AdminBridgeUrl;
        ProviderBox.Text = settings.Provider;
        InferenceEndpointBox.Text = settings.InferenceEndpoint;
        ModelBox.Text = settings.Model;
        VideoLibraryPathBox.Text = settings.VideoLibraryPath;
        ActiveProjectNameBox.Text = settings.ActiveProjectName;
        StrictNativeModeSwitch.IsOn = settings.StrictNativeMode;
        AdvancedChatDetailsSwitch.IsOn = settings.AdvancedChatDetails;
        DemoModeSwitch.IsOn = settings.DemoMode;
        SelectComboItem(PreferredApiBox, settings.PreferredApi);
        SelectComboItem(AccessModeBox, settings.AccessMode);
        SelectComboItem(VisualBlocksModeBox, settings.VisualBlocksMode);
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        var settings = ReadSettings();
        var error = Validate(settings);
        if (error is not null)
        {
            StatusText.Text = error;
            return;
        }

        AppSettingsStore.Save(settings);
        GatewayCredentialStore.SaveSecret(ApiKeyBox.Password);

        StatusText.Text = "Impostazioni salvate. Hermes usa API key Bearer salvata.";
    }

    private async void TestGatewayWs_Click(object sender, RoutedEventArgs e)
    {
        var settings = ReadSettings();
        StatusText.Text = "Lettura capabilities Hermes...";
        StatusText.Text = await GatewayService.SendHermesRequestAsync(settings, HttpMethod.Get, "/v1/capabilities");
    }

    private async void TestGateway_Click(object sender, RoutedEventArgs e)
    {
        var settings = ReadSettings();
        var error = ValidateGateway(settings.GatewayUrl);
        if (error is not null)
        {
            StatusText.Text = error;
            return;
        }

        var healthUrl = $"{GatewayService.HermesRoot(settings)}/health";
        StatusText.Text = $"Test: {healthUrl}";

        try
        {
            var snapshot = await GatewayService.GetServerSnapshotAsync(settings);
            StatusText.Text = snapshot.StatusMessage;
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Hermes non raggiungibile: {ex.Message}";
        }
    }

    private void Reset_Click(object sender, RoutedEventArgs e)
    {
        AppSettingsStore.Reset();
        GatewayCredentialStore.SaveSecret(GatewayCredentialStore.DefaultApiKey);
        LoadSettings();
        StatusText.Text = "Default ripristinati.";
    }

    private void ResetApiKey_Click(object sender, RoutedEventArgs e)
    {
        GatewayCredentialStore.SaveSecret(GatewayCredentialStore.DefaultApiKey);
        ApiKeyBox.Password = GatewayCredentialStore.DefaultApiKey;
        StatusText.Text = "API key ripristinata a hermes-hub.";
    }

    private void Backup_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var path = LocalBackupService.Export();
            StatusText.Text = $"Backup locale creato: {path}";
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Backup non riuscito: {ex.Message}";
        }
    }

    private AppSettings ReadSettings()
    {
        return new AppSettings
        {
            GatewayUrl = GatewayUrlBox.Text.Trim(),
            GatewayWsUrl = string.Empty,
            AdminBridgeUrl = GatewayUrlBox.Text.Trim().TrimEnd('/').EndsWith("/v1", StringComparison.OrdinalIgnoreCase)
                ? GatewayUrlBox.Text.Trim().TrimEnd('/')[..^3]
                : GatewayUrlBox.Text.Trim().TrimEnd('/'),
            Provider = ProviderBox.Text.Trim(),
            InferenceEndpoint = InferenceEndpointBox.Text.Trim(),
            PreferredApi = SelectedComboText(PreferredApiBox),
            Model = ModelBox.Text.Trim(),
            VideoLibraryPath = VideoLibraryPathBox.Text.Trim(),
            ActiveProjectId = string.IsNullOrWhiteSpace(ActiveProjectNameBox.Text) ? string.Empty : $"project_{ActiveProjectNameBox.Text.Trim().ToLowerInvariant().Replace(' ', '_')}",
            ActiveProjectName = ActiveProjectNameBox.Text.Trim(),
            AccessMode = SelectedComboText(AccessModeBox),
            VisualBlocksMode = SelectedComboText(VisualBlocksModeBox),
            StrictNativeMode = StrictNativeModeSwitch.IsOn,
            AdvancedChatDetails = AdvancedChatDetailsSwitch.IsOn,
            DemoMode = DemoModeSwitch.IsOn
        };
    }

    private static string? Validate(AppSettings settings)
    {
        return ValidateGateway(settings.GatewayUrl)
            ?? ValidateRequired(settings.Provider, "Provider")
            ?? ValidateHttpUrl(settings.InferenceEndpoint, "Endpoint inferenza")
            ?? ValidatePreferredApi(settings.PreferredApi)
            ?? ValidateRequired(settings.Model, "Modello")
            ?? ValidateRequired(settings.AccessMode, "Accesso")
            ?? ValidateVisualBlocksMode(settings.VisualBlocksMode);
    }

    private static string? ValidateWebSocket(string gatewayWsUrl)
    {
        if (string.IsNullOrWhiteSpace(gatewayWsUrl))
        {
            return "Gateway WebSocket URL obbligatorio.";
        }

        if (!Uri.TryCreate(gatewayWsUrl, UriKind.Absolute, out var uri) ||
            (uri.Scheme != "ws" && uri.Scheme != "wss"))
        {
            return "Gateway WebSocket URL deve essere ws/wss valido.";
        }

        return null;
    }

    private static string? ValidateGateway(string gatewayUrl)
    {
        return ValidateHttpUrl(gatewayUrl, "Hermes API URL");
    }

    private static string? ValidateHttpUrl(string value, string label)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return $"{label} obbligatorio.";
        }

        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) ||
            (uri.Scheme != Uri.UriSchemeHttps && uri.Scheme != Uri.UriSchemeHttp))
        {
            return $"{label} deve essere URL http/https valido.";
        }

        return null;
    }

    private static string? ValidateRequired(string value, string label)
    {
        return string.IsNullOrWhiteSpace(value) ? $"{label} obbligatorio." : null;
    }

    private static string? ValidateVisualBlocksMode(string value)
    {
        return value is "auto" or "always" or "never" ? null : "Modalita visuale deve essere auto, always o never.";
    }

    private static string? ValidatePreferredApi(string value)
    {
        return value is "hermes-native" or "openai-completions" or "openai-responses"
            ? null
            : "API preferita deve essere hermes-native, openai-completions o openai-responses.";
    }

    private static string SelectedComboText(ComboBox comboBox)
    {
        return (comboBox.SelectedItem as ComboBoxItem)?.Content?.ToString() ?? string.Empty;
    }

    private static void SelectComboItem(ComboBox comboBox, string value)
    {
        foreach (var item in comboBox.Items.OfType<ComboBoxItem>())
        {
            if (string.Equals(item.Content?.ToString(), value, StringComparison.OrdinalIgnoreCase))
            {
                comboBox.SelectedItem = item;
                return;
            }
        }
    }

    private async void LoadMemory_Click(object sender, RoutedEventArgs e)
    {
        MemoryStatusText.Text = "Lettura memoria gateway...";
        var result = await GatewayService.LoadHubMemoryAsync(ReadSettings());
        MemoryVideoBox.Text = result.Memory.VideoPreferences;
        MemoryNewsBox.Text = result.Memory.NewsPreferences;
        MemoryStyleBox.Text = result.Memory.ResponseStyle;
        MemoryProjectBox.Text = result.Memory.ProjectRules;
        MemoryNotesBox.Text = result.Memory.GeneralNotes;
        MemoryStatusText.Text = result.Status;
    }

    private async void SaveMemory_Click(object sender, RoutedEventArgs e)
    {
        MemoryStatusText.Text = "Salvataggio memoria gateway...";
        MemoryStatusText.Text = await GatewayService.SaveHubMemoryAsync(
            ReadSettings(),
            new HubMemoryState(
                MemoryVideoBox.Text,
                MemoryNewsBox.Text,
                MemoryStyleBox.Text,
                MemoryProjectBox.Text,
                MemoryNotesBox.Text));
    }
}
