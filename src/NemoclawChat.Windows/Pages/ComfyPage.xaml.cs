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
    private string _lastComfyUrl = string.Empty;
    private string _runningJobId = string.Empty;
    private string _firstQueuedJobId = string.Empty;

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
            var settings = AppSettingsStore.Load();
            var status = await ComfyStatusService.LoadAsync(settings, token);
            Render(status);
            await RefreshWorkflowAsync(settings, status, token);
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
        var loadedVideo = status.MediaLoadedModels.FirstOrDefault(m => !string.IsNullOrWhiteSpace(m));
        LlmText.Text = loadedVideo is not null
            ? $"Video su GPU: {loadedVideo}" + (status.MediaLoadedModels.Count > 1 ? $" +{status.MediaLoadedModels.Count - 1}" : string.Empty)
            : $"LLM {(status.LlmLoaded ? "caricato" : "scarico")}" + (!string.IsNullOrWhiteSpace(status.LlmModel) && status.LlmLoaded ? $": {status.LlmModel}" : string.Empty);
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

    // ------------------------------------------------------- workflow live ---
    // Vista del workflow come nella pagina web di Comfy (WebView2 su :8188,
    // stesso URL "Comfy diretto" della sezione diresta) piu interventi via
    // manager. NESSUNA chiamata ai modi del manager: annullare/Edit/requeue
    // toccano solo la riga del job, la modalita' resta quella decisa dal
    // manager (AUTO in genere).

    private void ComfyWebView_NavigationCompleted(object sender, Microsoft.Web.WebView2.Core.CoreWebView2NavigationCompletedEventArgs e)
    {
        WebViewFallbackText.Visibility = e.IsSuccess ? Visibility.Collapsed : Visibility.Visible;
        if (!e.IsSuccess)
        {
            WebViewFallbackText.Text = "Comfy non è raggiungibile qui (errore di navigazione). La vista workflow serve mentre Comfy genera: se l'LLM è residente, il manager tiene Comfy fermo e qui non si vede nulla finché non riparte un job.";
        }
    }

    private async Task RefreshWorkflowAsync(AppSettings settings, ComfyStatusRecord status, CancellationToken token)
    {
        if (!status.Reachable)
        {
            WorkflowJobText.Text = "Job attivo: — (manager non raggiungibile)";
            CancelJobButton.IsEnabled = false;
            EditQueuedButton.IsEnabled = false;
            InterruptResubmitButton.IsEnabled = false;
            return;
        }

        _runningJobId = status.JobId;
        _firstQueuedJobId = await FindFirstQueuedJobIdAsync(token) ?? string.Empty;

        if (!string.Equals(_lastComfyUrl, GatewayService.ComfyDirectBase(settings), StringComparison.OrdinalIgnoreCase))
        {
            _lastComfyUrl = GatewayService.ComfyDirectBase(settings);
            try
            {
                ComfyWebView.Source = new Uri(_lastComfyUrl);
                WebViewFallbackText.Visibility = Visibility.Collapsed;
            }
            catch (UriFormatException)
            {
                // Server non ancora impostato nei settings: la vista resta vuota.
            }
        }

        var label = "Nessun job attivo";
        if (!string.IsNullOrWhiteSpace(status.JobId))
        {
            label = $"In esecuzione: {ShortJob(status.JobId)}";
            var what = !string.IsNullOrWhiteSpace(status.Preset) ? status.Preset : status.Model;
            if (!string.IsNullOrWhiteSpace(what))
            {
                label += $" · {what}";
            }
            if (status.Progress.HasValue)
            {
                label += $" · {status.ProgressLabel}";
            }
        }
        else if (_firstQueuedJobId.Length > 0)
        {
            label = $"Nessun job attivo · prossimo in coda: {ShortJob(_firstQueuedJobId)}";
        }
        WorkflowJobText.Text = label + $" · Gestione: {(string.IsNullOrWhiteSpace(status.Desired) ? "AUTO" : status.Desired)}";

        CancelJobButton.IsEnabled = !string.IsNullOrWhiteSpace(_runningJobId);
        EditQueuedButton.IsEnabled = _firstQueuedJobId.Length > 0;
        InterruptResubmitButton.IsEnabled = !string.IsNullOrWhiteSpace(_runningJobId) || _firstQueuedJobId.Length > 0;
    }

    private static string ShortJob(string jobId) => jobId.Length <= 8 ? jobId : jobId[..8];

    private static async Task<string?> FindFirstQueuedJobIdAsync(CancellationToken token)
    {
        try
        {
            var (code, body) = await GatewayService.SendManagerRequestAsync(
                AppSettingsStore.Load(), HttpMethod.Get, "/jobs?limit=40", null, token);
            if (code is < 200 or > 299)
            {
                return null;
            }
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.ValueKind != JsonValueKind.Object ||
                !doc.RootElement.TryGetProperty("jobs", out var jobs) ||
                jobs.ValueKind != JsonValueKind.Array)
            {
                return null;
            }
            foreach (var job in jobs.EnumerateArray())
            {
                if (job.ValueKind != JsonValueKind.Object || !job.TryGetProperty("status", out var st))
                {
                    continue;
                }
                var stValue = st.ValueKind == JsonValueKind.String ? st.GetString() : null;
                if (string.Equals(stValue, "queued", StringComparison.OrdinalIgnoreCase) &&
                    job.TryGetProperty("job_id", out var jid) && jid.ValueKind == JsonValueKind.String)
                {
                    return jid.GetString();
                }
            }
        }
        catch (JsonException)
        {
        }
        catch (Exception)
        {
        }
        return null;
    }

    private async void CancelJob_Click(object sender, RoutedEventArgs e) => await CancelJobAsync();

    private async Task CancelJobAsync()
    {
        var jobId = _runningJobId;
        if (string.IsNullOrWhiteSpace(jobId) || _layaBusy)
        {
            return;
        }
        _layaBusy = true;
        WorkflowActionText.Text = $"Annullamento {ShortJob(jobId)}…";
        try
        {
            var (code, body) = await GatewayService.SendManagerRequestAsync(
                AppSettingsStore.Load(), HttpMethod.Post, $"/jobs/{jobId}/cancel", "{}",
                _cts?.Token ?? CancellationToken.None);
            WorkflowActionText.Text = FormatActionOutcome("Annullato", code, body);
        }
        finally
        {
            _layaBusy = false;
            await RefreshAsync();
        }
    }

    private async void EditQueued_Click(object sender, RoutedEventArgs e) =>
        await EditWorkflowAsync(_firstQueuedJobId, "Modifica workflow", "Applica", WorkflowKind.Edit);

    private async void InterruptResubmit_Click(object sender, RoutedEventArgs e) =>
        await EditWorkflowAsync(_runningJobId is { Length: > 0 } ? _runningJobId : _firstQueuedJobId,
            "Interrompi e risottometti", "Interrompi e risottometti", WorkflowKind.Resubmit);

    private enum WorkflowKind { Edit, Resubmit }

    private async Task EditWorkflowAsync(string? jobId, string title, string confirmLabel, WorkflowKind kind)
    {
        if (string.IsNullOrWhiteSpace(jobId))
        {
            return;
        }
        if (_layaBusy)
        {
            return;
        }
        _layaBusy = true;
        try
        {
            var (getStatusCode, getBody) = await GatewayService.SendManagerRequestAsync(
                AppSettingsStore.Load(), HttpMethod.Get, $"/jobs/{jobId}/workflow", null,
                _cts?.Token ?? CancellationToken.None);
            if (getStatusCode is < 200 or > 299)
            {
                WorkflowActionText.Text = $"Workflow di {ShortJob(jobId)} non letto: {ReadableError(getStatusCode, getBody)}";
                return;
            }
            var initialJson = ExtractWorkflowJson(getBody);
            if (initialJson is null)
            {
                WorkflowActionText.Text = "Workflow nel job illeggibile (non è un dict JSON).";
                return;
            }

            var editor = new TextBox
            {
                AcceptsReturn = true,
                TextWrapping = TextWrapping.NoWrap,
                FontFamily = new Microsoft.UI.Xaml.Media.FontFamily("Consolas"),
                FontSize = 12,
                Height = 420,
                Text = initialJson,
                PlaceholderText = "Workflow di Comfy in formato API ({\"node_id\": {...}, ...})",
            };
            var dialog = new ContentDialog
            {
                Title = $"{title} · job {ShortJob(jobId)}",
                Content = new ScrollViewer { Content = editor, MaxHeight = 480, VerticalScrollBarVisibility = ScrollBarVisibility.Auto },
                PrimaryButtonText = confirmLabel,
                CloseButtonText = "Annulla",
                DefaultButton = ContentDialogButton.Primary,
                XamlRoot = XamlRoot,
            };
            var result = await dialog.ShowAsync();
            if (result != ContentDialogResult.Primary)
            {
                return;
            }
            using var doc = JsonDocument.Parse(editor.Text ?? string.Empty);  // throw: testo non valido
            var payload = $"{{\"workflow\":{editor.Text?.Trim()}}}";
            var (postCode, postBody) = await GatewayService.SendManagerRequestAsync(
                AppSettingsStore.Load(), HttpMethod.Post,
                kind == WorkflowKind.Resubmit ? $"/jobs/{jobId}/resubmit" : $"/jobs/{jobId}/workflow",
                payload, _cts?.Token ?? CancellationToken.None);
            WorkflowActionText.Text = kind == WorkflowKind.Resubmit
                ? FormatResubmitOutcome(postCode, postBody, ShortJob(jobId))
                : FormatActionOutcome("Workflow applicato", postCode, postBody);
        }
        catch (JsonException)
        {
            WorkflowActionText.Text = "JSON non valido: correggi il testo e riprova (niente inviato).";
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            _layaBusy = false;
            await RefreshAsync();
        }
    }

    private static string? ExtractWorkflowJson(string body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.ValueKind == JsonValueKind.Object &&
                doc.RootElement.TryGetProperty("workflow", out var wf) &&
                wf.ValueKind == JsonValueKind.Object)
            {
                return JsonSerializer.Serialize(wf);
            }
        }
        catch (JsonException)
        {
        }
        return null;
    }

    private static string FormatActionOutcome(string okLabel, int code, string body) =>
        code is >= 200 and <= 299 ? $"{okLabel} ({ShortManagerMessage(body)})."
            : $"Manager HTTP {code}: {ReadableError(code, body)}";

    private static string FormatResubmitOutcome(int code, string body, string oldShort)
    {
        if (code is < 200 or > 299)
        {
            return $"Risubmit fallito: {ReadableError(code, body)}";
        }
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.TryGetProperty("queued_job", out var queued) &&
                queued.ValueKind == JsonValueKind.Object &&
                queued.TryGetProperty("job_id", out var jid) && jid.ValueKind == JsonValueKind.String)
            {
                var newId = jid.GetString();
                if (!string.IsNullOrWhiteSpace(newId))
                {
                    return $"Job {oldShort} interrotto, risottomesso come {ShortJob(newId)}.";
                }
            }
        }
        catch (JsonException)
        {
        }
        return "Risubmit inviato.";
    }

    private static string ShortManagerMessage(string body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.ValueKind == JsonValueKind.Object &&
                doc.RootElement.TryGetProperty("status", out var st) && st.ValueKind == JsonValueKind.String)
            {
                return st.GetString() ?? "ok";
            }
        }
        catch (JsonException)
        {
        }
        return "ok";
    }

    private static string ReadableError(int code, string body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.ValueKind == JsonValueKind.Object &&
                doc.RootElement.TryGetProperty("detail", out var detail))
            {
                return detail.ToString();
            }
        }
        catch (JsonException)
        {
        }
        return $"HTTP {code}";
    }

    private void WorkflowRefresh_Click(object sender, RoutedEventArgs e)
    {
        if (ComfyWebView.CoreWebView2 is { } core)
        {
            core.Reload();
        }
        else if (!string.IsNullOrEmpty(_lastComfyUrl))
        {
            _lastComfyUrl = string.Empty;
        }
    }

    private async void WorkflowOpenBrowser_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrEmpty(_lastComfyUrl))
        {
            return;
        }
        _ = await Windows.System.Launcher.LaunchUriAsync(new Uri(_lastComfyUrl));
    }
}
