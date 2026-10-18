using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Web.WebView2.Core;
using NemoclawChat_Windows.Services;
using Windows.System;

namespace NemoclawChat_Windows.Pages;

[System.Diagnostics.CodeAnalysis.SuppressMessage(
    "Design",
    "CA1001:Types that own disposable fields should be disposable",
    Justification = "Pagina WinUI: il CTS viene cancellato e rilasciato nel ciclo Unloaded; il framework non consuma IDisposable.")]
public sealed partial class ScreenPage : Page
{
    private const string ViewerHost = "hermes-noc";
    private const string StateShimJs = "window.HermesScreen={onState:function(s){try{window.chrome.webview.postMessage(JSON.stringify(s));}catch(e){}}};";

    private readonly DispatcherTimer _poller = new() { Interval = TimeSpan.FromSeconds(5) };
    private CancellationTokenSource? _cts;
    private bool _polling;
    private bool _viewerReady;
    private bool _holding;
    private string _viewerId = string.Empty;
    private string? _viewerError;

    public ScreenPage()
    {
        InitializeComponent();
        _poller.Tick += async (_, _) => await PollStatusAsync();
        Loaded += ScreenPage_Loaded;
        Unloaded += ScreenPage_Unloaded;
    }

    private async void ScreenPage_Loaded(object sender, RoutedEventArgs e)
    {
        _cts = new CancellationTokenSource();
        _poller.Start();
        await PollStatusAsync();
        await ConnectViewerAsync();
    }

    private void ScreenPage_Unloaded(object sender, RoutedEventArgs e)
    {
        _poller.Stop();
        _cts?.Cancel();
        _cts?.Dispose();
        _cts = null;
        if (_holding)
        {
            var viewerId = _viewerId;
            _holding = false;
            FireAndForget(ScreenService.ReleaseAsync(AppSettingsStore.Load(), viewerId));
        }
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e)
    {
        await PollStatusAsync();
        await ConnectViewerAsync();
    }

    private async Task PollStatusAsync()
    {
        if (_polling)
        {
            return;
        }
        _polling = true;
        try
        {
            var token = _cts?.Token ?? CancellationToken.None;
            var status = await ScreenService.GetStatusAsync(AppSettingsStore.Load(), token);
            if (status is not null && _holding && !string.Equals(status.Holder, "human", StringComparison.OrdinalIgnoreCase))
            {
                // Lease perso (un altro viewer ha preso il controllo).
                _holding = false;
                await SetViewOnlyAsync(true);
            }
            RenderStatus(status);
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            SubtitleText.Text = $"Stato display non leggibile: {ex.Message}";
        }
        finally
        {
            _polling = false;
        }
    }

    private void RenderStatus(ScreenStatusRecord? status)
    {
        SubtitleText.Text = ScreenService.StatusSubtitle(status, _holding, _viewerError);
        if (status is null || !status.Running)
        {
            DetailText.Text = status is null
                ? "Manager non raggiungibile."
                : "Schermo spento: avvialo dal bot o via CLI.";
            TakeoverButton.IsEnabled = false;
            HandBackButton.Visibility = Visibility.Collapsed;
            return;
        }
        DetailText.Text = status.Width > 0
            ? $"Display {status.Width}×{status.Height} · holder {status.Holder}"
            : $"Display attivo · holder {status.Holder}";
        TakeoverButton.IsEnabled = !_holding;
        TakeoverButton.Visibility = _holding ? Visibility.Collapsed : Visibility.Visible;
        HandBackButton.Visibility = _holding ? Visibility.Visible : Visibility.Collapsed;
    }

    private async Task ConnectViewerAsync()
    {
        if (_viewerReady)
        {
            return;
        }
        try
        {
            var token = _cts?.Token ?? CancellationToken.None;
            var settings = AppSettingsStore.Load();
            var status = await ScreenService.GetStatusAsync(settings, token);
            if (status is null || !status.Running)
            {
                _viewerError = status is null ? "Manager non raggiungibile." : null;
                RenderStatus(status);
                PlaceholderText.Text = status is null ? "Manager non raggiungibile." : "Schermo spento: avvialo dal bot o via CLI.";
                return;
            }
            var ticket = await ScreenService.MintTicketAsync(settings, token);
            if (ticket is null)
            {
                _viewerError = "Ticket display rifiutato (schermo spento?).";
                RenderStatus(status);
                PlaceholderText.Text = _viewerError;
                return;
            }
            _viewerId = ticket.ViewerId;
            var wsUrl = ScreenService.BuildWsUrl(GatewayService.ManagerBase(settings), ticket.Ticket);
            if (string.IsNullOrWhiteSpace(wsUrl))
            {
                _viewerError = "URL display non costruibile.";
                RenderStatus(status);
                return;
            }
            EnsureViewerMapping();
            await ScreenViewer.EnsureCoreWebView2Async();
            HardenScreenViewer();
            ScreenViewer.WebMessageReceived += ScreenViewer_WebMessageReceived;
            var viewerUrl = $"http://{ViewerHost}/viewer.html?url={Uri.EscapeDataString(wsUrl)}&viewonly={(_holding ? "0" : "1")}";
            ScreenViewer.Source = new Uri(viewerUrl);
            await ScreenViewer.ExecuteScriptAsync(StateShimJs);
            ScreenViewer.Visibility = Visibility.Visible;
            ViewerPlaceholder.Visibility = Visibility.Collapsed;
            _viewerError = null;
            _viewerReady = true;
            RenderStatus(status);
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            _viewerError = $"Connessione allo schermo fallita: {ex.Message}";
            PlaceholderText.Text = _viewerError;
            SubtitleText.Text = _viewerError;
        }
    }

    private void EnsureViewerMapping()
    {
        var folder = NovncFolder();
        if (string.IsNullOrWhiteSpace(folder) || !Directory.Exists(folder))
        {
            throw new InvalidOperationException("Asset noVNC mancanti nel pacchetto.");
        }
        ScreenViewer.CoreWebView2.SetVirtualHostNameToFolderMapping(
            ViewerHost, folder, CoreWebView2HostResourceAccessKind.Deny);
    }

    private static string NovncFolder()
    {
        try
        {
            var installed = Path.Combine(Windows.ApplicationModel.Package.Current.InstalledLocation.Path, "Assets", "novnc");
            if (Directory.Exists(installed))
            {
                return installed;
            }
        }
        catch
        {
        }
        return Path.Combine(AppContext.BaseDirectory, "Assets", "novnc");
    }

    private void HardenScreenViewer()
    {
        // noVNC richiede JavaScript: resta attivo. Blindatura sul perimetro:
        // solo host virtuale locale, niente download, niente popup interni.
        ScreenViewer.NavigationStarting += (_, args) =>
        {
            if (!IsViewerNavigationAllowed(args.Uri))
            {
                args.Cancel = true;
                FireAndForget(OpenExternalIfHttpAsync(args.Uri));
            }
        };
        ScreenViewer.CoreWebView2.DownloadStarting += (_, args) =>
        {
            args.Cancel = true;
            args.Handled = true;
        };
    }

    private static bool IsViewerNavigationAllowed(string? uri)
    {
        if (string.Equals(uri, "about:blank", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }
        if (!Uri.TryCreate(uri, UriKind.Absolute, out var target))
        {
            return false;
        }
        if (target.Scheme is not ("http" or "https"))
        {
            return false;
        }
        // Solo il viewer locale; il WS verso il manager non passa da qui.
        return string.Equals(target.Host, ViewerHost, StringComparison.OrdinalIgnoreCase);
    }

    private static async Task OpenExternalIfHttpAsync(string? uri)
    {
        if (!Uri.TryCreate(uri, UriKind.Absolute, out var target) ||
            target.Scheme is not ("http" or "https"))
        {
            return;
        }
        try
        {
            await Launcher.LaunchUriAsync(target);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Trace.WriteLine($"[ScreenPage] Apertura browser esterno fallita: {ex.Message}");
        }
    }

    private static void FireAndForget(Task task)
    {
    }

    private void ScreenViewer_WebMessageReceived(WebView2 sender, CoreWebView2WebMessageReceivedEventArgs args)
    {
        try
        {
            var state = args.TryGetWebMessageAsString();
            DispatcherQueue.TryEnqueue(() =>
            {
                if (state.Contains("connected", StringComparison.OrdinalIgnoreCase))
                {
                    SubtitleText.Text = _holding ? "Tu hai il controllo" : "Bot al controllo";
                }
                else if (state.Contains("disconnect", StringComparison.OrdinalIgnoreCase))
                {
                    SubtitleText.Text = "Schermo disconnesso: aggiorna per riconnettere.";
                }
            });
        }
        catch (Exception ex)
        {
            System.Diagnostics.Trace.WriteLine($"[ScreenPage] WebMessage non leggibile: {ex.Message}");
        }
    }

    private async Task SetViewOnlyAsync(bool viewOnly)
    {
        if (!_viewerReady)
        {
            return;
        }
        try
        {
            await ScreenViewer.ExecuteScriptAsync(
                $"window.HermesScreenCtl&&HermesScreenCtl.setViewOnly({(viewOnly ? "true" : "false")})");
        }
        catch (Exception ex)
        {
            System.Diagnostics.Trace.WriteLine($"[ScreenPage] setViewOnly fallito: {ex.Message}");
        }
        RenderStatus(await SafeStatusAsync());
    }

    private async Task<ScreenStatusRecord?> SafeStatusAsync()
    {
        try
        {
            return await ScreenService.GetStatusAsync(AppSettingsStore.Load(), _cts?.Token ?? CancellationToken.None);
        }
        catch
        {
            return null;
        }
    }

    private async void Takeover_Click(object sender, RoutedEventArgs e)
    {
        TakeoverButton.IsEnabled = false;
        try
        {
            var (ok, _) = await ScreenService.TakeoverAsync(AppSettingsStore.Load(), _viewerId, _cts?.Token ?? CancellationToken.None);
            if (!ok)
            {
                SubtitleText.Text = "Take over rifiutato.";
                return;
            }
            _holding = true;
            await SetViewOnlyAsync(false);
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            SubtitleText.Text = $"Take over fallito: {ex.Message}";
        }
        finally
        {
            RenderStatus(await SafeStatusAsync());
        }
    }

    private async void HandBack_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            await ScreenService.ReleaseAsync(AppSettingsStore.Load(), _viewerId, _cts?.Token ?? CancellationToken.None);
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            System.Diagnostics.Trace.WriteLine($"[ScreenPage] Hand back fallito: {ex.Message}");
        }
        _holding = false;
        await SetViewOnlyAsync(true);
    }
}
