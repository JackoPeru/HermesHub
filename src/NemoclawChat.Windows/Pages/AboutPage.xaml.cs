using System.Reflection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using NemoclawChat_Windows.Services;

namespace NemoclawChat_Windows.Pages;

public sealed partial class AboutPage : Page
{
    private UpdateCheckResult? _lastUpdateResult;
    private string? _downloadedAssetPath;

    public AboutPage()
    {
        InitializeComponent();
        var settings = AppSettingsStore.Load();
        VersionText.Text = CurrentVersion;
        GatewayText.Text = settings.GatewayUrl;
        ModeText.Text = settings.DemoMode ? "Fallback locale attivo" : "Solo Hermes";
        SettingsPathText.Text = "Settings: %LOCALAPPDATA%\\ChatClaw\\settings.json";
    }

    private static string CurrentVersion =>
        Assembly.GetExecutingAssembly().GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion
        ?? Assembly.GetExecutingAssembly().GetName().Version?.ToString()
        ?? "0.0.0";

    private async void CheckUpdates_Click(object sender, RoutedEventArgs e)
    {
        UpdateStatusText.Text = "Controllo GitHub Releases...";
        UpdateProgressBar.Visibility = Visibility.Collapsed;
        DownloadUpdateButton.Visibility = Visibility.Collapsed;
        DownloadUpdateButton.IsEnabled = false;
        InstallUpdateButton.Visibility = Visibility.Collapsed;
        InstallUpdateButton.IsEnabled = false;
        UpdateSummaryText.Visibility = Visibility.Collapsed;
        UpdateSummaryText.Text = string.Empty;
        UpdateDetailText.Text = string.Empty;
        _downloadedAssetPath = null;

        _lastUpdateResult = await AppUpdateService.CheckAsync(CurrentVersion);
        UpdateStatusText.Text = _lastUpdateResult.Message;
        UpdateDetailText.Text =
            $"Release: {_lastUpdateResult.ReleaseUrl}\n" +
            $"Asset: {_lastUpdateResult.AssetName ?? "nessun asset selezionato"}\n" +
            $"Cartella update: {AppUpdateService.GetUpdatesDirectoryDisplayPath()}\n" +
            $"Log installazione MSIX: {AppUpdateService.GetUpdateInstallLogDisplayPath()}";
        if (!string.IsNullOrWhiteSpace(_lastUpdateResult.ReleaseSummary))
        {
            UpdateSummaryText.Text = $"Changelog:\n{_lastUpdateResult.ReleaseSummary}";
            UpdateSummaryText.Visibility = Visibility.Visible;
        }

        if (!_lastUpdateResult.HasUpdate || string.IsNullOrWhiteSpace(_lastUpdateResult.AssetUrl))
        {
            return;
        }

        var downloaded = AppUpdateService.FindDownloadedAsset(_lastUpdateResult.LatestVersion ?? string.Empty, _lastUpdateResult.AssetName);
        if (downloaded is not null)
        {
            _downloadedAssetPath = downloaded.FullName;
            UpdateDetailText.Text += $"\nAsset scaricato: {_downloadedAssetPath}";
            InstallUpdateButton.Visibility = Visibility.Visible;
            InstallUpdateButton.IsEnabled = true;
            UpdateStatusText.Text = $"{_lastUpdateResult.Message} Asset gia' scaricato.";
            return;
        }

        DownloadUpdateButton.Visibility = Visibility.Visible;
        DownloadUpdateButton.IsEnabled = true;
    }

    private async void DownloadUpdate_Click(object sender, RoutedEventArgs e)
    {
        if (_lastUpdateResult is null || string.IsNullOrWhiteSpace(_lastUpdateResult.AssetUrl))
        {
            return;
        }

        DownloadUpdateButton.IsEnabled = false;
        UpdateProgressBar.Visibility = Visibility.Visible;
        UpdateProgressBar.Value = 0;
        UpdateStatusText.Text = "Download update in corso...";

        var progress = new Progress<UpdateDownloadProgress>(item =>
        {
            UpdateProgressBar.IsIndeterminate = item.Percent is null;
            if (item.Percent is not null)
            {
                UpdateProgressBar.Value = item.Percent.Value;
            }

            UpdateStatusText.Text = string.IsNullOrWhiteSpace(item.Detail)
                ? item.Status
                : $"{item.Status} {item.Detail}";
        });

        _downloadedAssetPath = await AppUpdateService.DownloadAssetAsync(
            _lastUpdateResult.AssetUrl,
            _lastUpdateResult.LatestVersion ?? CurrentVersion,
            _lastUpdateResult.AssetName,
            progress);

        if (_downloadedAssetPath is null)
        {
            UpdateStatusText.Text = "Download update non riuscito.";
            DownloadUpdateButton.IsEnabled = true;
            UpdateProgressBar.Visibility = Visibility.Collapsed;
            return;
        }

        UpdateStatusText.Text = "Download completato. Premi Installa e riavvia.";
        InstallUpdateButton.Visibility = Visibility.Visible;
        InstallUpdateButton.IsEnabled = true;
        UpdateProgressBar.Visibility = Visibility.Collapsed;
    }

    private async void InstallUpdate_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(_downloadedAssetPath))
        {
            return;
        }

        var launched = await AppUpdateService.LaunchDownloadedAssetAsync(_downloadedAssetPath);
        if (!launched)
        {
            UpdateStatusText.Text = "Impossibile aprire l'asset scaricato.";
            return;
        }

        UpdateStatusText.Text = "Update avviato. Hermes Hub si chiude e si riapre dopo l'installazione. Se fallisce, controlla il log MSIX nella cartella update.";
        await Task.Delay(900);
        Application.Current.Exit();
    }
}
