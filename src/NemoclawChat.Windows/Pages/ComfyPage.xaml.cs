using System.Net.Http;
using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using NemoclawChat_Windows.Services;

namespace NemoclawChat_Windows.Pages;

[System.Diagnostics.CodeAnalysis.SuppressMessage(
    "Design",
    "CA1001:Types that own disposable fields should be disposable",
    Justification = "Pagina WinUI: il CTS viene cancellato e rilasciato nel ciclo Unloaded; il framework non consuma IDisposable.")]
public sealed partial class ComfyPage : Page
{
    private readonly DispatcherTimer _poller = new() { Interval = TimeSpan.FromSeconds(5) };
    private CancellationTokenSource? _cts;
    private bool _polling;
    private bool _layaBusy;

    public ComfyPage()
    {
        InitializeComponent();
        _poller.Tick += async (_, _) => await RefreshAsync();
        Loaded += ComfyPage_Loaded;
        Unloaded += ComfyPage_Unloaded;
    }

    private async void ComfyPage_Loaded(object sender, RoutedEventArgs e)
    {
        _cts = new CancellationTokenSource();
        _poller.Start();
        await RefreshAsync();
    }

    private void ComfyPage_Unloaded(object sender, RoutedEventArgs e)
    {
        _poller.Stop();
        _cts?.Cancel();
        _cts?.Dispose();
        _cts = null;
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        base.OnNavigatedFrom(e);
        _poller.Stop();
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await RefreshAsync();

    private async void LayaSend_Click(object sender, RoutedEventArgs e) => await SendLayaAsync();

    private async void LayaInput_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == Windows.System.VirtualKey.Enter)
        {
            e.Handled = true;
            await SendLayaAsync();
        }
    }

    private async Task SendLayaAsync()
    {
        var text = LayaInput.Text?.Trim() ?? string.Empty;
        if (text.Length == 0 || _layaBusy)
        {
            return;
        }
        _layaBusy = true;
        LayaSendButton.IsEnabled = false;
        LayaReplyText.Text = "…";
        try
        {
            var settings = AppSettingsStore.Load();
            var payload = JsonSerializer.Serialize(new { text });
            var (code, body) = await GatewayService.SendBridgeRequestAsync(
                settings, HttpMethod.Post, "/laya", payload, _cts?.Token ?? CancellationToken.None);
            if (code == 0)
            {
                LayaReplyText.Text = $"Bridge non raggiungibile ({GatewayService.BridgeBase(settings)}).";
            }
            else if (code is < 200 or > 299)
            {
                LayaReplyText.Text = $"Bridge HTTP {code}.";
            }
            else
            {
                LayaReplyText.Text = ReadLayaReply(body);
            }
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            _layaBusy = false;
            LayaSendButton.IsEnabled = true;
        }
    }

    private static string ReadLayaReply(string body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.ValueKind == JsonValueKind.Object &&
                doc.RootElement.TryGetProperty("reply", out var reply) &&
                reply.ValueKind == JsonValueKind.String)
            {
                var text = reply.GetString();
                return string.IsNullOrWhiteSpace(text) ? "Nessuna risposta." : text;
            }
        }
        catch (JsonException)
        {
        }
        return "Risposta illeggibile.";
    }

    private async Task RefreshAsync()
    {
        if (_polling)
        {
            return;
        }
        _polling = true;
        try
        {
            var token = _cts?.Token ?? CancellationToken.None;
            var status = await ComfyStatusService.LoadAsync(AppSettingsStore.Load(), token);
            Render(status);
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            StateText.Text = "Errore lettura stato.";
            DetailText.Text = ex.Message;
            PageStatusText.Text = "Riprova tra poco.";
        }
        finally
        {
            _polling = false;
        }
    }

    private void Render(ComfyStatusRecord status)
    {
        if (!status.Reachable)
        {
            StateText.Text = "Non raggiungibile";
            DetailText.Text = status.Error;
            QueueText.Text = string.Empty;
            LlmText.Text = string.Empty;
            ProgressBar.IsIndeterminate = false;
            ProgressBar.Value = 0;
            DirectPanel.Visibility = Visibility.Collapsed;
            PageStatusText.Text = "Verifica che il server sia acceso e la chiave API sia valida.";
            return;
        }

        StateText.Text = status.StateLabel;
        if (status.Progress.HasValue)
        {
            ProgressBar.IsIndeterminate = false;
            ProgressBar.Value = status.Progress.Value * 100;
        }
        else
        {
            ProgressBar.IsIndeterminate = !string.Equals(status.State, "MEDIA_BUSY", StringComparison.OrdinalIgnoreCase);
            if (!ProgressBar.IsIndeterminate)
            {
                ProgressBar.Value = 0;
            }
        }
        var what = !string.IsNullOrWhiteSpace(status.Preset) ? status.Preset
            : !string.IsNullOrWhiteSpace(status.Model) ? status.Model : string.Empty;
        DetailText.Text = $"Job {status.JobShort}" +
            (string.IsNullOrWhiteSpace(what) ? string.Empty : $" · {what}") +
            (string.IsNullOrWhiteSpace(status.Hint) ? string.Empty : $"\n{status.Hint}") +
            (string.IsNullOrWhiteSpace(status.Error) ? string.Empty : $"\nErrore: {status.Error}");
        QueueText.Text = $"Coda manager: {status.QueueLabel}";
        LlmText.Text = $"LLM {(status.LlmLoaded ? "caricato" : "scarico")}";
        PageStatusText.Text = "Aggiornato ogni 5 secondi mentre guardi.";

        var showDirect = status.ComfyQueue.HasValue || !string.IsNullOrWhiteSpace(status.ComfyError);
        DirectPanel.Visibility = showDirect ? Visibility.Visible : Visibility.Collapsed;
        if (showDirect)
        {
            DirectQueueText.Text = status.ComfyQueue.HasValue
                ? (status.ComfyQueue.Value > 0 ? $"{status.ComfyQueue.Value} in esecuzione/coda" : "libera")
                : "Comfy diretto non raggiungibile: se doveva generare, qualcosa non va.";
            DirectErrorText.Text = string.IsNullOrWhiteSpace(status.ComfyError) ? "Nessun errore recente." : status.ComfyError;
        }
    }
}
