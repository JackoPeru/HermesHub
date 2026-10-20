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
    private List<AgentJobInfo> _jobs = new();
    private string _selectedJobId = string.Empty;

    private sealed record AgentJobInfo
    {
        public string JobId { get; init; } = string.Empty;
        public string Kind { get; init; } = string.Empty;
        public string Status { get; init; } = string.Empty;
        public string Preset { get; init; } = string.Empty;
        public string Backend { get; init; } = string.Empty;
        public string Model { get; init; } = string.Empty;
        public string Phase { get; init; } = string.Empty;
        public double Progress { get; init; }
        public string Error { get; init; } = string.Empty;
        public IReadOnlyList<string> ResultPaths { get; init; } = Array.Empty<string>();
        public IReadOnlyList<string> MediaUrls { get; init; } = Array.Empty<string>();
        public double CreatedAt { get; init; }
        public double UpdatedAt { get; init; }
    }

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
            WebViewFallbackText.Text = "Comfy non è raggiungibile qui (errore di navigazione). La vista workflow serve solo mentre Comfy genera: i job dell'agente restano visibili sotto in JOB AGENTE.";
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
            _jobs = new List<AgentJobInfo>();
            RenderJobs();
            return;
        }

        _runningJobId = status.JobId;
        _jobs = await LoadAgentJobsAsync(token);
        _firstQueuedJobId = _jobs
            .FirstOrDefault(j => string.Equals(j.Status, "queued", StringComparison.OrdinalIgnoreCase))
            ?.JobId ?? string.Empty;
        RenderJobs();
        await RefreshJobGraphAsync(settings, token);

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

    // ------------------------------------------------------- job agente ---
    // Tutti i job che l'agente fa in autonomia (GET /jobs del manager, gli
    // stessi dati che alimentano "prossimo in coda"): visibili anche quando
    // Comfy e' fermo e la WebView diretta non puo' mostrare nulla.

    private static async Task<List<AgentJobInfo>> LoadAgentJobsAsync(CancellationToken token)
    {
        try
        {
            var (code, body) = await GatewayService.SendManagerRequestAsync(
                AppSettingsStore.Load(), HttpMethod.Get, "/jobs?limit=30", null, token);
            if (code is < 200 or > 299)
            {
                return new List<AgentJobInfo>();
            }
            return ParseAgentJobs(body);
        }
        catch (JsonException)
        {
            return new List<AgentJobInfo>();
        }
        catch (Exception)
        {
            return new List<AgentJobInfo>();
        }
    }

    private static List<AgentJobInfo> ParseAgentJobs(string body)
    {
        var list = new List<AgentJobInfo>();
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.ValueKind != JsonValueKind.Object ||
                !doc.RootElement.TryGetProperty("jobs", out var jobs) ||
                jobs.ValueKind != JsonValueKind.Array)
            {
                return list;
            }
            foreach (var job in jobs.EnumerateArray())
            {
                if (job.ValueKind != JsonValueKind.Object)
                {
                    continue;
                }
                list.Add(new AgentJobInfo
                {
                    JobId = ReadJobString(job, "job_id"),
                    Kind = ReadJobString(job, "kind"),
                    Status = ReadJobString(job, "status"),
                    Preset = ReadJobString(job, "preset"),
                    Backend = ReadJobString(job, "backend"),
                    Model = ReadJobString(job, "model"),
                    Phase = ReadJobString(job, "phase"),
                    Progress = ReadJobDouble(job, "progress"),
                    Error = ReadJobString(job, "error"),
                    ResultPaths = ReadJobStringList(job, "result_paths"),
                    MediaUrls = ReadJobMediaUrls(job),
                    CreatedAt = ReadJobDouble(job, "created_at"),
                    UpdatedAt = ReadJobDouble(job, "updated_at"),
                });
            }
        }
        catch (JsonException)
        {
        }
        return list;
    }

    private void RenderJobs()
    {
        JobsHeaderText.Text = _jobs.Count == 0 ? "JOB AGENTE" : $"JOB AGENTE · {_jobs.Count} recenti";
        JobsList.ItemsSource = _jobs
            .Select(j => $"{ShortJob(j.JobId)} · {JobTitle(j)} · {JobStatusLabel(j.Status)}{JobProgressSuffix(j)} · {JobTime(j.UpdatedAt)}")
            .ToList();
        var selected = _jobs.FirstOrDefault(j => string.Equals(j.JobId, _selectedJobId, StringComparison.Ordinal))
            ?? _jobs.FirstOrDefault(j => string.Equals(j.JobId, _runningJobId, StringComparison.Ordinal));
        if (selected is not null)
        {
            _selectedJobId = selected.JobId;
            JobsList.SelectedIndex = _jobs.IndexOf(selected);
        }
        else
        {
            _selectedJobId = string.Empty;
            JobsList.SelectedIndex = -1;
        }
        RenderJobDetail();
    }

    private void RenderJobDetail()
    {
        var job = _jobs.FirstOrDefault(j => string.Equals(j.JobId, _selectedJobId, StringComparison.Ordinal));
        if (job is null)
        {
            JobDetailText.Text = _jobs.Count == 0 ? "Nessun job recente dal manager." : "Seleziona un job per i dettagli.";
            OpenOutputButton.IsEnabled = false;
            return;
        }
        var lines = new List<string>
        {
            $"Job {job.JobId}",
            $"Stato: {JobStatusLabel(job.Status)}{JobProgressSuffix(job)}" +
                (string.IsNullOrWhiteSpace(job.Phase) ? string.Empty : $" · fase {job.Phase}"),
            $"Tipo: {JobTitle(job)}" +
                (string.IsNullOrWhiteSpace(job.Backend) ? string.Empty : $" · {job.Backend}") +
                (string.IsNullOrWhiteSpace(job.Model) ? string.Empty : $" · {job.Model}"),
        };
        if (job.ResultPaths.Count > 0)
        {
            lines.Add("Output: " + string.Join(", ", job.ResultPaths.Select(JobFileName)));
        }
        if (!string.IsNullOrWhiteSpace(job.Error))
        {
            var error = job.Error.Trim();
            lines.Add("Errore: " + (error.Length <= 300 ? error : error[..300]));
        }
        lines.Add($"Creato {JobDateTime(job.CreatedAt)} · aggiornato {JobDateTime(job.UpdatedAt)}");
        JobDetailText.Text = string.Join("\n", lines);
        OpenOutputButton.IsEnabled = job.MediaUrls.Count > 0;
    }

    private void JobsList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (JobsList.SelectedIndex >= 0 && JobsList.SelectedIndex < _jobs.Count)
        {
            _selectedJobId = _jobs[JobsList.SelectedIndex].JobId;
        }
        else
        {
            _selectedJobId = string.Empty;
        }
        RenderJobDetail();
    }

    private async void OpenOutput_Click(object sender, RoutedEventArgs e)
    {
        var job = _jobs.FirstOrDefault(j => string.Equals(j.JobId, _selectedJobId, StringComparison.Ordinal));
        var url = job?.MediaUrls.FirstOrDefault(u => !string.IsNullOrWhiteSpace(u));
        if (string.IsNullOrWhiteSpace(url))
        {
            return;
        }
        try
        {
            var uri = ImageLibraryService.ResolveImageUri(AppSettingsStore.Load(), url.Trim());
            _ = await Windows.System.Launcher.LaunchUriAsync(uri);
        }
        catch
        {
        }
    }

    private List<JobGraphNode> _graphNodes = new();
    private HashSet<string> _graphCompleted = new(StringComparer.Ordinal);
    private string _graphJobId = string.Empty;
    private string _graphNodeId = string.Empty;

    private sealed record JobGraphNode
    {
        public string Id { get; init; } = string.Empty;
        public string ClassType { get; init; } = string.Empty;
        public List<KeyValuePair<string, string>> Widgets { get; init; } = new();
        public List<KeyValuePair<string, string>> Incoming { get; init; } = new();
    }

    // ------------------------------------------------------- nodi del job ---
    // Grafo del workflow che l'agente ha messo in coda (formato API del
    // manager: nodi con class_type + inputs, link come ["id", slot]).
    // Nodi completati dal live di Comfy (/queue -> /history) mentre gira;
    // niente cambi al server, solo GET gia' usati altrove nella pagina.

    private async Task RefreshJobGraphAsync(AppSettings settings, CancellationToken token)
    {
        var target = _jobs.FirstOrDefault(j => string.Equals(j.JobId, _selectedJobId, StringComparison.Ordinal))
            ?? _jobs.FirstOrDefault(j => string.Equals(j.JobId, _runningJobId, StringComparison.Ordinal))
            ?? _jobs.FirstOrDefault();
        if (target is null)
        {
            _graphJobId = string.Empty;
            _graphNodes = new List<JobGraphNode>();
            _graphCompleted = new HashSet<string>(StringComparer.Ordinal);
            RenderGraph(null);
            return;
        }
        if (!string.Equals(target.JobId, _graphJobId, StringComparison.Ordinal))
        {
            _graphNodes = await LoadJobGraphAsync(target.JobId, token);
            _graphJobId = target.JobId;
            _graphNodeId = string.Empty;
        }
        if (string.Equals(target.Status, "running", StringComparison.OrdinalIgnoreCase))
        {
            _graphCompleted = await LoadCompletedNodesAsync(settings, token);
        }
        else if (string.Equals(target.Status, "done", StringComparison.OrdinalIgnoreCase))
        {
            _graphCompleted = new HashSet<string>(_graphNodes.Select(n => n.Id), StringComparer.Ordinal);
        }
        else
        {
            _graphCompleted = new HashSet<string>(StringComparer.Ordinal);
        }
        RenderGraph(target);
    }

    private static async Task<List<JobGraphNode>> LoadJobGraphAsync(string jobId, CancellationToken token)
    {
        try
        {
            var (code, body) = await GatewayService.SendManagerRequestAsync(
                AppSettingsStore.Load(), HttpMethod.Get, $"/jobs/{Uri.EscapeDataString(jobId)}/workflow", null, token);
            if (code is < 200 or > 299)
            {
                return new List<JobGraphNode>();
            }
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.ValueKind != JsonValueKind.Object ||
                !doc.RootElement.TryGetProperty("workflow", out var workflow) ||
                workflow.ValueKind != JsonValueKind.Object)
            {
                return new List<JobGraphNode>();
            }
            return ParseJobGraph(workflow);
        }
        catch (JsonException)
        {
            return new List<JobGraphNode>();
        }
        catch (Exception)
        {
            return new List<JobGraphNode>();
        }
    }

    private static List<JobGraphNode> ParseJobGraph(JsonElement workflow)
    {
        var nodes = new List<JobGraphNode>();
        foreach (var prop in workflow.EnumerateObject())
        {
            if (prop.Value.ValueKind != JsonValueKind.Object)
            {
                continue;
            }
            var node = new JobGraphNode { Id = prop.Name };
            if (prop.Value.TryGetProperty("class_type", out var classType) && classType.ValueKind == JsonValueKind.String)
            {
                node = node with { ClassType = classType.GetString() ?? string.Empty };
            }
            if (prop.Value.TryGetProperty("inputs", out var inputs) && inputs.ValueKind == JsonValueKind.Object)
            {
                foreach (var input in inputs.EnumerateObject())
                {
                    if (input.Value.ValueKind == JsonValueKind.Array &&
                        input.Value.GetArrayLength() == 2 &&
                        input.Value[0].ValueKind == JsonValueKind.String)
                    {
                        node.Incoming.Add(new KeyValuePair<string, string>(input.Value[0].GetString() ?? string.Empty, input.Name));
                    }
                    else
                    {
                        node.Widgets.Add(new KeyValuePair<string, string>(input.Name, TrimWidget(input.Value)));
                    }
                }
            }
            nodes.Add(node);
        }
        return nodes;
    }

    private static string TrimWidget(JsonElement value)
    {
        string text = value.ValueKind switch
        {
            JsonValueKind.String => value.GetString() ?? string.Empty,
            JsonValueKind.Number => value.ToString(),
            JsonValueKind.True => "true",
            JsonValueKind.False => "false",
            _ => value.ValueKind.ToString(),
        };
        text = text.Replace("\n", " ", StringComparison.Ordinal).Replace("\r", " ", StringComparison.Ordinal).Trim();
        return text.Length <= 80 ? text : text[..80];
    }

    private static async Task<HashSet<string>> LoadCompletedNodesAsync(AppSettings settings, CancellationToken token)
    {
        var done = new HashSet<string>(StringComparer.Ordinal);
        try
        {
            var (qCode, qBody) = await GatewayService.SendComfyDirectRequestAsync(settings, "/queue", token);
            if (qCode is < 200 or > 299)
            {
                return done;
            }
            var promptId = ExtractRunningPromptId(qBody);
            if (string.IsNullOrWhiteSpace(promptId))
            {
                return done;
            }
            var (hCode, hBody) = await GatewayService.SendComfyDirectRequestAsync(
                settings, $"/history/{Uri.EscapeDataString(promptId)}", token);
            if (hCode is < 200 or > 299)
            {
                return done;
            }
            using var doc = JsonDocument.Parse(hBody);
            if (doc.RootElement.ValueKind == JsonValueKind.Object &&
                doc.RootElement.TryGetProperty(promptId, out var entry) &&
                entry.ValueKind == JsonValueKind.Object &&
                entry.TryGetProperty("outputs", out var outputs) &&
                outputs.ValueKind == JsonValueKind.Object)
            {
                foreach (var output in outputs.EnumerateObject())
                {
                    done.Add(output.Name);
                }
            }
        }
        catch (JsonException)
        {
        }
        catch (Exception)
        {
        }
        return done;
    }

    private static string ExtractRunningPromptId(string body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.ValueKind != JsonValueKind.Object ||
                !doc.RootElement.TryGetProperty("queue_running", out var running) ||
                running.ValueKind != JsonValueKind.Array)
            {
                return string.Empty;
            }
            foreach (var item in running.EnumerateArray())
            {
                if (item.ValueKind == JsonValueKind.Array && item.GetArrayLength() > 0 &&
                    item[0].ValueKind == JsonValueKind.String)
                {
                    return item[0].GetString() ?? string.Empty;
                }
                if (item.ValueKind == JsonValueKind.Object &&
                    item.TryGetProperty("prompt_id", out var pid) && pid.ValueKind == JsonValueKind.String)
                {
                    return pid.GetString() ?? string.Empty;
                }
            }
        }
        catch (JsonException)
        {
        }
        return string.Empty;
    }

    private void RenderGraph(AgentJobInfo? target)
    {
        JobGraphCanvas.Children.Clear();
        if (target is null || _graphNodes.Count == 0)
        {
            GraphHeaderText.Text = "NODI DEL JOB";
            NodeDetailText.Text = target is null
                ? "Nessun job: il grafo compare appena l'agente mette qualcosa in coda."
                : $"Grafo di {ShortJob(target.JobId)} illeggibile (workflow non in formato API).";
            JobGraphCanvas.Width = 0;
            JobGraphCanvas.Height = 0;
            return;
        }
        GraphHeaderText.Text = $"NODI DEL JOB · {ShortJob(target.JobId)} · {_graphNodes.Count} nodi · {_graphCompleted.Count} completati";

        var byId = _graphNodes.ToDictionary(n => n.Id, StringComparer.Ordinal);
        var depth = new Dictionary<string, int>(StringComparer.Ordinal);
        var remaining = _graphNodes.ToDictionary(n => n.Id, n => n.Incoming.Count(i => byId.ContainsKey(i.Key)), StringComparer.Ordinal);
        var queue = new Queue<string>(remaining.Where(kv => kv.Value == 0).Select(kv => kv.Key));
        foreach (var id in queue)
        {
            depth[id] = 0;
        }
        while (queue.Count > 0)
        {
            var current = queue.Dequeue();
            foreach (var node in _graphNodes)
            {
                foreach (var incoming in node.Incoming)
                {
                    if (!string.Equals(incoming.Key, current, StringComparison.Ordinal) || !remaining.TryGetValue(node.Id, out var left))
                    {
                        continue;
                    }
                    depth[node.Id] = Math.Max(depth.TryGetValue(node.Id, out var known) ? known : 0, (depth.TryGetValue(current, out var parent) ? parent : 0) + 1);
                    if (left <= 1)
                    {
                        remaining[node.Id] = 0;
                        queue.Enqueue(node.Id);
                    }
                    else
                    {
                        remaining[node.Id] = left - 1;
                    }
                }
            }
        }
        foreach (var node in _graphNodes)
        {
            if (!depth.ContainsKey(node.Id))
            {
                depth[node.Id] = 0;
            }
        }

        const double nodeWidth = 200;
        const double nodeHeight = 66;
        const double colGap = 250;
        const double rowGap = 88;
        const double margin = 24;
        var columns = _graphNodes.GroupBy(n => depth[n.Id]).OrderBy(g => g.Key).ToList();
        var positions = new Dictionary<string, (double X, double Y)>(StringComparer.Ordinal);
        var row = 0;
        foreach (var column in columns)
        {
            row = 0;
            foreach (var node in column.OrderBy(n => n.Id, StringComparer.Ordinal))
            {
                positions[node.Id] = (margin + column.Key * colGap, margin + row * rowGap);
                row++;
            }
        }
        var maxRows = columns.Count == 0 ? 0 : columns.Max(g => g.Count());
        JobGraphCanvas.Width = margin * 2 + (columns.Count == 0 ? 0 : (columns.Count - 1) * colGap + nodeWidth);
        JobGraphCanvas.Height = margin * 2 + Math.Max(0, maxRows - 1) * rowGap + nodeHeight;

        var running = string.Equals(target.Status, "running", StringComparison.OrdinalIgnoreCase);
        var failed = string.Equals(target.Status, "failed", StringComparison.OrdinalIgnoreCase);
        foreach (var node in _graphNodes)
        {
            foreach (var incoming in node.Incoming)
            {
                if (!positions.TryGetValue(incoming.Key, out var from) || !positions.TryGetValue(node.Id, out var to))
                {
                    continue;
                }
                JobGraphCanvas.Children.Add(new Microsoft.UI.Xaml.Shapes.Line
                {
                    X1 = from.X + nodeWidth,
                    Y1 = from.Y + nodeHeight / 2,
                    X2 = to.X,
                    Y2 = to.Y + nodeHeight / 2,
                    Stroke = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.ColorHelper.FromArgb(0xFF, 0x77, 0x77, 0x77)),
                    StrokeThickness = 1.5,
                });
            }
        }
        foreach (var node in _graphNodes)
        {
            var pos = positions[node.Id];
            var completed = _graphCompleted.Contains(node.Id);
            var borderColor = completed ? Microsoft.UI.ColorHelper.FromArgb(0xFF, 0x4C, 0xAF, 0x50)
                : failed ? Microsoft.UI.ColorHelper.FromArgb(0xFF, 0xFF, 0x7B, 0x8E)
                : running ? Microsoft.UI.ColorHelper.FromArgb(0xFF, 0xF5, 0xA5, 0x24)
                : Microsoft.UI.ColorHelper.FromArgb(0xFF, 0x55, 0x55, 0x55);
            var title = string.IsNullOrWhiteSpace(node.ClassType) ? $"nodo {node.Id}" : node.ClassType;
            if (title.Length > 24)
            {
                title = title[..24];
            }
            var state = completed ? "completato" : failed ? "fallito" : running ? "in esecuzione" : JobStatusLabel(target.Status);
            var panel = new StackPanel { Spacing = 2 };
            panel.Children.Add(new TextBlock
            {
                Text = title,
                FontSize = 12.5,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                Foreground = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.White),
                TextTrimming = TextTrimming.CharacterEllipsis,
            });
            panel.Children.Add(new TextBlock
            {
                Text = $"#{node.Id} · {state}",
                FontSize = 11,
                Foreground = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.ColorHelper.FromArgb(0xFF, 0xAA, 0xAA, 0xAA)),
            });
            var border = new Border
            {
                Width = nodeWidth,
                Height = nodeHeight,
                CornerRadius = new CornerRadius(8),
                BorderThickness = new Thickness(2),
                BorderBrush = new Microsoft.UI.Xaml.Media.SolidColorBrush(borderColor),
                Background = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.ColorHelper.FromArgb(0xFF, 0x17, 0x17, 0x1C)),
                Padding = new Thickness(8),
                Child = panel,
                Tag = node.Id,
            };
            border.PointerPressed += JobGraphNode_Pressed;
            Microsoft.UI.Xaml.Controls.Canvas.SetLeft(border, pos.X);
            Microsoft.UI.Xaml.Controls.Canvas.SetTop(border, pos.Y);
            JobGraphCanvas.Children.Add(border);
        }
        RenderNodeDetail();
    }

    private void JobGraphNode_Pressed(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e)
    {
        if (sender is Border border && border.Tag is string id && !string.IsNullOrWhiteSpace(id))
        {
            _graphNodeId = id;
            RenderNodeDetail();
        }
    }

    private void RenderNodeDetail()
    {
        var node = _graphNodes.FirstOrDefault(n => string.Equals(n.Id, _graphNodeId, StringComparison.Ordinal));
        if (node is null)
        {
            NodeDetailText.Text = _graphNodes.Count == 0
                ? "Seleziona un job sopra, poi tocca un nodo."
                : "Tocca un nodo per i dettagli.";
            return;
        }
        var byId = _graphNodes.ToDictionary(n => n.Id, StringComparer.Ordinal);
        var lines = new List<string>
        {
            $"Nodo #{node.Id} · {(string.IsNullOrWhiteSpace(node.ClassType) ? "?" : node.ClassType)} · " +
                (_graphCompleted.Contains(node.Id) ? "completato" : "non completato"),
        };
        foreach (var incoming in node.Incoming.Take(6))
        {
            var from = byId.TryGetValue(incoming.Key, out var src) && !string.IsNullOrWhiteSpace(src.ClassType)
                ? src.ClassType : $"nodo {incoming.Key}";
            lines.Add($"da {from} → {incoming.Value}");
        }
        foreach (var widget in node.Widgets.Take(6))
        {
            lines.Add($"{widget.Key} = {widget.Value}");
        }
        NodeDetailText.Text = string.Join("\n", lines);
    }

    private static string JobTitle(AgentJobInfo job) =>
        !string.IsNullOrWhiteSpace(job.Preset) ? job.Preset
        : !string.IsNullOrWhiteSpace(job.Kind) ? job.Kind : "job";

    private static string JobStatusLabel(string status) => status switch
    {
        { Length: 0 } => "stato?",
        var s when string.Equals(s, "queued", StringComparison.OrdinalIgnoreCase) => "in coda",
        var s when string.Equals(s, "running", StringComparison.OrdinalIgnoreCase) => "in esecuzione",
        var s when string.Equals(s, "done", StringComparison.OrdinalIgnoreCase) => "completato",
        var s when string.Equals(s, "failed", StringComparison.OrdinalIgnoreCase) => "fallito",
        var s when string.Equals(s, "cancelled", StringComparison.OrdinalIgnoreCase) => "annullato",
        var s => s,
    };

    private static string JobProgressSuffix(AgentJobInfo job) =>
        string.Equals(job.Status, "running", StringComparison.OrdinalIgnoreCase)
            ? $" · {Math.Round(Math.Clamp(job.Progress, 0.0, 1.0) * 100)}%" : string.Empty;

    private static string JobTime(double unixSeconds) => JobDateTime(unixSeconds, "HH:mm");

    private static string JobDateTime(double unixSeconds, string format = "dd/MM HH:mm")
    {
        try
        {
            if (unixSeconds > 0)
            {
                return DateTimeOffset.FromUnixTimeSeconds((long)unixSeconds).ToLocalTime().ToString(format, System.Globalization.CultureInfo.InvariantCulture);
            }
        }
        catch (ArgumentOutOfRangeException)
        {
        }
        return "—";
    }

    private static string JobFileName(string path)
    {
        var name = (path ?? string.Empty).Replace('\\', '/');
        var slash = name.LastIndexOf('/');
        return slash >= 0 ? name[(slash + 1)..] : name;
    }

    private static string ReadJobString(JsonElement job, string name) =>
        job.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() ?? string.Empty : string.Empty;

    private static double ReadJobDouble(JsonElement job, string name) =>
        job.TryGetProperty(name, out var value) && value.TryGetDouble(out var parsed) && !double.IsNaN(parsed) ? parsed : 0.0;

    private static List<string> ReadJobStringList(JsonElement job, string name)
    {
        var result = new List<string>();
        if (job.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in value.EnumerateArray())
            {
                if (item.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(item.GetString()))
                {
                    result.Add(item.GetString()!.Trim());
                }
            }
        }
        return result;
    }

    private static IReadOnlyList<string> ReadJobMediaUrls(JsonElement job)
    {
        if (job.TryGetProperty("parameters", out var parameters) &&
            parameters.ValueKind == JsonValueKind.Object &&
            parameters.TryGetProperty("media_urls", out var urls) &&
            urls.ValueKind == JsonValueKind.Array)
        {
            var result = new List<string>();
            foreach (var item in urls.EnumerateArray())
            {
                if (item.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(item.GetString()))
                {
                    result.Add(item.GetString()!.Trim());
                }
            }
            return result;
        }
        return Array.Empty<string>();
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
