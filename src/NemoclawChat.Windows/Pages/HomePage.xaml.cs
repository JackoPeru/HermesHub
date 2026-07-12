using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Navigation;
using Microsoft.UI.Xaml.Shapes;
using NemoclawChat_Windows.Services;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using Windows.ApplicationModel.DataTransfer;
using Windows.Foundation;
using Windows.Media.Capture;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Media.MediaProperties;
using Windows.Storage.Pickers;
using Windows.Storage;
using Windows.Storage.Streams;
using Windows.System;
using WinRT.Interop;

namespace NemoclawChat_Windows.Pages;

public sealed partial class HomePage : Page
{
    private const int DefaultContextWindowTokens = 90000;
    private const int ContextSystemOverheadTokens = 900;
    private const int MessageContextOverheadTokens = 6;
    private const int StreamingCheckpointIntervalMs = 5000;
    private const int StreamingCheckpointMaxChars = 50_000;
    private const int StreamAccumMaxChars = 2_000_000;

    public ObservableCollection<MessageViewModel> Messages { get; } = [];
    private string _mode = "Chat";
    private string? _conversationId;
    private string? _previousResponseId;
    private readonly List<ChatMessageRecord> _messageHistory = [];
    private int _lastServerContextTokens;
    private int _lastServerContextLength;
    private int? _lastServerContextPercent;
    
    private sealed record ActiveStreamState(
        string ComposerRunId,
        StreamingBubble Bubble,
        CancellationTokenSource Cts,
        string? GatewayRunId
    ) {
        public string? GatewayRunId { get; set; } = GatewayRunId;
    }

    private readonly Dictionary<string, ActiveStreamState> _activeStreams = new();
    
    private string? _currentComposerRunId;
    private StreamingBubble? _currentStreamingBubble;
    private CancellationTokenSource? _activeStreamCts;
    private string? _activeGatewayRunId;
    private readonly List<ChatInputAttachment> _pendingAttachments = [];

    private Popup? _slashPopup;
    private ListView? _slashList;
    private readonly List<SlashCommand> _slashCommands;

    private MediaCapture? _mediaCapture;
    private MediaPlayer? _ttsPlayer;
    private bool _isRecordingVoiceNote;
    private StorageFile? _tempVoiceNoteFile;

    public HomePage()
    {
        InitializeComponent();
        NavigationCacheMode = NavigationCacheMode.Required;
        _slashCommands = BuildSlashCommands();
        UpdateSendButtonVisual(isStreaming: false);
        PromptBox.TextChanged += PromptBox_TextChanged;
        Loaded += HomePage_Loaded;
        Unloaded += HomePage_Unloaded;
    }

    private void HomePage_Unloaded(object sender, RoutedEventArgs e)
    {
        try
        {
            if (_slashPopup is not null) _slashPopup.IsOpen = false;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[HomePage] Unload error: {ex.Message}");
        }
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);

        if (e.Parameter is HomeNavigationRequest request)
        {
            if (!string.IsNullOrWhiteSpace(request.ConversationId))
            {
                LoadConversation(request.ConversationId);
            }

            if (!string.IsNullOrWhiteSpace(request.Prompt))
            {
                PromptBox.Text = request.Prompt;
                PromptBox.Focus(FocusState.Programmatic);
            }
        }
        else if (e.Parameter is string prompt && !string.IsNullOrWhiteSpace(prompt))
        {
            PromptBox.Text = prompt;
            PromptBox.Focus(FocusState.Programmatic);
        }
    }

    private void HomePage_Loaded(object sender, RoutedEventArgs e)
    {
        BuildSlashPopup();
        _currentStreamingBubble?.ResumeLiveIndicators();
        UpdateContextMeter();
    }

    private async void Send_Click(object sender, RoutedEventArgs e)
    {
        if (_currentComposerRunId is not null)
        {
            await RequestStopCurrentStreamAsync();
            return;
        }

        await SendCurrentPromptAsync();
    }

    private async void PromptBox_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (_slashPopup?.IsOpen == true && _slashList is not null)
        {
            if (e.Key == VirtualKey.Down)
            {
                e.Handled = true;
                var next = Math.Min((_slashList.Items?.Count ?? 1) - 1, _slashList.SelectedIndex + 1);
                _slashList.SelectedIndex = Math.Max(0, next);
                return;
            }
            if (e.Key == VirtualKey.Up)
            {
                e.Handled = true;
                var prev = Math.Max(0, _slashList.SelectedIndex - 1);
                _slashList.SelectedIndex = prev;
                return;
            }
            if (e.Key == VirtualKey.Escape)
            {
                e.Handled = true;
                CloseSlashPopup();
                return;
            }
            if (e.Key == VirtualKey.Enter)
            {
                e.Handled = true;
                ActivateSelectedSlashCommand();
                return;
            }
            if (e.Key == VirtualKey.Tab)
            {
                e.Handled = true;
                ActivateSelectedSlashCommand();
                return;
            }
        }

        if (e.Key == VirtualKey.Enter && IsShiftPressed())
        {
            e.Handled = true;
            InsertPromptNewLine();
            return;
        }

        if (e.Key == VirtualKey.Enter)
        {
            e.Handled = true;
            if (_currentComposerRunId is not null)
            {
                await RequestStopCurrentStreamAsync();
                return;
            }

            await SendCurrentPromptAsync();
        }
    }

    private void PromptSetup_Click(object sender, RoutedEventArgs e)
    {
        PromptBox.Text = "Preparami i passaggi per avviare Hermes Agent API Server su Tailscale/LAN.";
    }

    private void PromptHealth_Click(object sender, RoutedEventArgs e)
    {
        PromptBox.Text = "Controlla stato Hermes, modello disponibile e capabilities API.";
    }

    private void PromptAgent_Click(object sender, RoutedEventArgs e)
    {
        PromptBox.Text = "Crea un task agente sicuro con richiesta approve/deny prima di ogni azione rischiosa.";
    }

    private async void AttachFile_Click(object sender, RoutedEventArgs e)
    {
        var picker = new FileOpenPicker();
        picker.FileTypeFilter.Add("*");

        if (App.MainWindow is not null)
        {
            InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.MainWindow));
        }

        var file = await picker.PickSingleFileAsync();
        if (file is null)
        {
            AddAction("File task", "Selezione file annullata.");
            return;
        }

        var settings = AppSettingsStore.Load();
        var attachment = await TryCreateAttachmentAsync(file, settings.MaxAttachmentMb);
        if (attachment is null)
        {
            AddAction("Allegato", $"File vuoto, non leggibile o troppo grande. Limite attuale: {settings.MaxAttachmentMb} MB.");
            return;
        }

        _pendingAttachments.Add(attachment);
        RenderAttachmentPreviews();
        AddAction("Allegato", $"{file.Name} pronto per Hermes ({FormatAttachmentBytes(attachment.SizeBytes)}).");
    }

    private async void PasteImage_Click(object sender, RoutedEventArgs e)
    {
        await TryPasteImageAttachmentAsync();
    }

    private async Task TryPasteImageAttachmentAsync()
    {
        var settings = AppSettingsStore.Load();
        var attachment = await TryCreateClipboardImageAttachmentAsync(settings.MaxAttachmentMb);
        if (attachment is null)
        {
            AddAction("Incolla immagine", "Nessuna immagine valida negli appunti, oppure file oltre limite.");
            return;
        }

        _pendingAttachments.Add(attachment);
        RenderAttachmentPreviews();
        AddAction("Incolla immagine", $"{attachment.FileName} pronta per Hermes ({FormatAttachmentBytes(attachment.SizeBytes)}).");
        PromptBox.Focus(FocusState.Programmatic);
    }

    private async void CaptureScreenshot_Click(object sender, RoutedEventArgs e)
    {
        var launched = await Launcher.LaunchUriAsync(new Uri("ms-screenclip:"));
        AddAction(
            "Screenshot",
            launched
                ? "Strumento cattura di Windows aperto. Incolla o salva lo screenshot, poi allegalo al task."
                : "Impossibile aprire lo strumento cattura. Usa una cattura manuale e allega il file al task.");
    }

    private async void TakePhoto_Click(object sender, RoutedEventArgs e)
    {
        var launched = await Launcher.LaunchUriAsync(new Uri("microsoft.windows.camera:"));
        AddAction(
            "Foto",
            launched
                ? "App Fotocamera aperta. Salva la foto e allegala al task quando pronta."
                : "Impossibile aprire Fotocamera. Scatta o seleziona una foto manualmente e allegala al task.");
    }

    private async void VoiceNote_Click(object sender, RoutedEventArgs e)
    {
        var button = sender as Button;
        if (button == null) return;
        var icon = button.Content as FontIcon;
        if (icon == null) return;

        if (!_isRecordingVoiceNote)
        {
            try
            {
                _mediaCapture = new MediaCapture();
                var settings = new MediaCaptureInitializationSettings
                {
                    StreamingCaptureMode = StreamingCaptureMode.Audio
                };
                await _mediaCapture.InitializeAsync(settings);

                var tempFolder = ApplicationData.Current.TemporaryFolder;
                _tempVoiceNoteFile = await tempFolder.CreateFileAsync("voice_note.m4a", CreationCollisionOption.GenerateUniqueName);

                var profile = MediaEncodingProfile.CreateM4a(AudioEncodingQuality.Medium);
                await _mediaCapture.StartRecordToStorageFileAsync(profile, _tempVoiceNoteFile);

                _isRecordingVoiceNote = true;
                button.Foreground = new SolidColorBrush(Microsoft.UI.Colors.Red);
                icon.Glyph = "\xE71A"; // Stop icon
                PromptBox.PlaceholderText = "Registrazione in corso... Clicca per trascrivere.";
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Error starting voice note: {ex}");
                AddAction("Errore Voce", "Impossibile accedere al microfono. Controlla le impostazioni di privacy di Windows.");
            }
        }
        else
        {
            try
            {
                await _mediaCapture!.StopRecordAsync();
                _isRecordingVoiceNote = false;

                button.Foreground = (Brush)Application.Current.Resources["MutedTextBrush"];
                icon.Glyph = "\xE720"; // Mic icon
                PromptBox.PlaceholderText = "Elaborazione audio...";

                _mediaCapture.Dispose();
                _mediaCapture = null;

                if (_tempVoiceNoteFile != null)
                {
                    await ProcessVoiceNoteAsync(_tempVoiceNoteFile.Path);
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Error stopping voice note: {ex}");
                PromptBox.PlaceholderText = "Fai una domanda";
            }
        }
    }

    private async Task ProcessVoiceNoteAsync(string filePath)
    {
        try
        {
            var settings = AppSettingsStore.Load();
            var transcribedText = await SpeechGatewayService.TranscribeFileAsync(settings, filePath);
            if (!string.IsNullOrWhiteSpace(transcribedText))
            {
                var currentText = PromptBox.Text;
                if (!string.IsNullOrEmpty(currentText) && !currentText.EndsWith(" "))
                    currentText += " ";
                PromptBox.Text = currentText + transcribedText;
                PromptBox.SelectionStart = PromptBox.Text.Length;
            }
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Error uploading voice note: {ex}");
            AddAction("Errore Voce", $"Errore invio audio: {ex.Message}");
        }
        finally
        {
            PromptBox.PlaceholderText = "Fai una domanda";
            try { System.IO.File.Delete(filePath); } catch { }
        }
    }

    private void CreateImage_Click(object sender, RoutedEventArgs e)
    {
        AddAction("Immagine", "Generazione immagine richiedera' tool Hermes dedicato e conferma prima di chiamate esterne.");
    }

    private void DeepResearch_Click(object sender, RoutedEventArgs e)
    {
        AddAction("Deep Research", "Ricerca approfondita abilitera' rete solo dopo approvazione esplicita.");
    }

    private void WebSearch_Click(object sender, RoutedEventArgs e)
    {
        AddAction("Web", "Ricerca web marcata come azione autorizzabile: nessuna rete fuori LAN/VPN senza conferma.");
    }

    private void VisualExplanation_Click(object sender, RoutedEventArgs e)
    {
        AddAction("Visuale", "Spiegazione visiva richiesta: Hermes usera' blocchi statici sicuri se disponibili.");
    }

    private void Projects_Click(object sender, RoutedEventArgs e)
    {
        AddAction("Workspace", "Workspace/progetti saranno collegati agli artifact Hermes con audit trail.");
    }

    private void SetModeChat_Click(object sender, RoutedEventArgs e)
    {
        SetMode("Chat");
        AddAction("Modalita", "Chat attiva: messaggi normali, nessun task agente automatico.");
    }

    private void SetModeAgent_Click(object sender, RoutedEventArgs e)
    {
        SetMode("Agente");
        AddAction("Modalita", "Agente attivo: usa strumenti Hermes se disponibili, altrimenti fallback locale.");
    }

    private void ToggleMode_Click(object sender, RoutedEventArgs e)
    {
        SetMode(_mode == "Agente" ? "Chat" : "Agente");
    }

    private void NewChat_Click(object sender, RoutedEventArgs e)
    {
        ReleaseCurrentComposerRun();
        _messageHistory.Clear();
        ResetServerContextMeter();
        _conversationId = null;
        _previousResponseId = null;
        Messages.Clear();
        EmptyState.Visibility = Visibility.Visible;
        PromptBox.Text = string.Empty;
        UpdateContextMeter();
        PromptBox.Focus(FocusState.Programmatic);
    }

    private void SetMode(string mode)
    {
        _mode = mode;
    }

    private bool IsComposerRunCurrent(string runId)
    {
        return string.Equals(_currentComposerRunId, runId, StringComparison.Ordinal);
    }

    private void ReleaseComposerRun(string runId)
    {
        if (!IsComposerRunCurrent(runId))
        {
            return;
        }

        _currentComposerRunId = null;
        UpdateSendButtonVisual(isStreaming: false);
    }

    private void ReleaseCurrentComposerRun()
    {
        _currentComposerRunId = null;
        UpdateSendButtonVisual(isStreaming: false);
        _activeGatewayRunId = null;
        if (ReferenceEquals(_currentStreamingBubble, null))
        {
            return;
        }

        _currentStreamingBubble = null;
    }

    private void UpdateSendButtonVisual(bool isStreaming)
    {
        SendButton.IsEnabled = true;
        SendButton.Background = isStreaming
            ? new SolidColorBrush(ColorHelper.FromArgb(255, 166, 37, 37))
            : new SolidColorBrush(Colors.White);
        SendButton.Foreground = isStreaming
            ? new SolidColorBrush(Colors.White)
            : new SolidColorBrush(ColorHelper.FromArgb(255, 17, 17, 17));
        AutomationProperties.SetName(SendButton, isStreaming ? "Interrompi risposta" : "Invia messaggio");
        if (SendButton.Content is FontIcon icon)
        {
            icon.Glyph = isStreaming ? "\uE71A" : "\uE724";
        }
    }

    private async Task RequestStopCurrentStreamAsync()
    {
        var cts = _activeStreamCts;
        var runId = _activeGatewayRunId;
        var composerId = _currentComposerRunId;
        _activeStreamCts = null;
        _activeGatewayRunId = null;

        _currentStreamingBubble?.SetStatus("Interruzione richiesta. Chiudo stream Hermes...");
        _currentStreamingBubble?.StopShimmer();
        ReleaseCurrentComposerRun();

        if (composerId is not null)
        {
            var kvp = _activeStreams.FirstOrDefault(x => x.Value.ComposerRunId == composerId);
            if (kvp.Key is not null)
            {
                _activeStreams.Remove(kvp.Key);
            }
        }

        if (cts is not null)
        {
            cts.Cancel();
            cts.Dispose();
        }

        if (string.IsNullOrWhiteSpace(runId))
        {
            return;
        }

        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
            await GatewayService.TryStopRunAsync(AppSettingsStore.Load(), runId, timeout.Token);
        }
        catch
        {
            // Stream stop locale resta prioritario; se il gateway non conferma in tempo non bloccare la UI.
        }
    }

    private async Task SendCurrentPromptAsync()
    {
        // Atomic guard: set flag prima di qualsiasi await. UI thread single-threaded ma
        // multipli entry-point (Send_Click, PromptBox_KeyDown, slash command activate)
        // possono entrare back-to-back prima che Send button si disabiliti.
        if (_currentComposerRunId is not null)
        {
            return;
        }
        var composerRunId = Guid.NewGuid().ToString("N");
        _currentComposerRunId = composerRunId;
        UpdateSendButtonVisual(isStreaming: true);

        var prompt = PromptBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(prompt) && _pendingAttachments.Count == 0)
        {
            ReleaseComposerRun(composerRunId);
            return;
        }
        // No fallback prompt required when only sending attachments

        CloseSlashPopup();

        StreamingBubble? bubble = null;
        CancellationTokenSource? streamCts = null;
        var sendMode = _mode;
        var conversationId = _conversationId;
        var previousResponseId = _previousResponseId;
        var localHistory = _messageHistory.ToList();

        try
        {
            EmptyState.Visibility = Visibility.Collapsed;
            var attachments = _pendingAttachments.ToList();
            _pendingAttachments.Clear();
            RenderAttachmentPreviews();
            var displayPrompt = attachments.Count == 0
                ? prompt
                : string.IsNullOrWhiteSpace(prompt) ? "Media condiviso." : prompt;
            AddBubble("Tu", displayPrompt, "UserBubbleBrush", HorizontalAlignment.Right, VisualBlockParser.CreateLocalAttachmentBlocks(attachments));
            _messageHistory.Add(new ChatMessageRecord("Tu", displayPrompt, DateTimeOffset.Now));
            localHistory.Add(new ChatMessageRecord("Tu", displayPrompt, DateTimeOffset.Now));
            PromptBox.Text = string.Empty;
            var initialSave = await Task.Run(() => ChatArchiveStore.SaveSnapshot(conversationId, sendMode, displayPrompt, localHistory.ToList(), "Hermes in corso", previousResponseId));
            conversationId = initialSave.Id;
            previousResponseId = initialSave.PreviousResponseId;
            if (IsComposerRunCurrent(composerRunId))
            {
                _conversationId = conversationId;
                _previousResponseId = previousResponseId;
            }

            var settings = AppSettingsStore.Load();
            bubble = CreateStreamingAssistantBubble();
            if (IsComposerRunCurrent(composerRunId))
            {
                _currentStreamingBubble = bubble;
            }

            IReadOnlyList<VisualBlockRecord>? finalBlocks = null;
            int? finalBlocksVersion = null;
            string? finalResponseId = null;
            var finalTextBuilder = new StringBuilder();
            var finalThinkingBuilder = new StringBuilder();
            string statusMessage = "Risposta ricevuta da Hermes.";
            string source = "Hermes";
            bool usedFallback = false;
            string? streamError = null;
            ChatStreamStats? finalStats = null;
            var rawEvents = new List<HermesRawEventRecord>();
            var lastCheckpointAt = DateTimeOffset.MinValue;
            var lastUiPumpAt = Stopwatch.GetTimestamp();
            var uiStreamStopwatch = Stopwatch.StartNew();
            double? uiFirstTextMs = null;
            double? uiLastTextMs = null;
            streamCts = new CancellationTokenSource();
            _activeStreamCts = streamCts;
            _activeGatewayRunId = null;
            _activeStreams[conversationId] = new ActiveStreamState(composerRunId, bubble, streamCts, null);
            var streamEvents = StartStreamProducer(settings, sendMode, prompt, localHistory.ToList(), conversationId, previousResponseId, attachments, streamCts.Token);

            void AddRawEventIfEnabled(string name, string json)
            {
                if (!settings.AdvancedChatDetails)
                {
                    return;
                }

                rawEvents.Add(new HermesRawEventRecord(name, json, DateTimeOffset.Now));
                if (rawEvents.Count > 200)
                {
                    rawEvents.RemoveRange(0, rawEvents.Count - 200);
                }

                bubble.AddRawEvent(name, json);
            }

            await foreach (var ev in streamEvents.ReadAllAsync())
            {
                switch (ev)
                {
                    case StreamTextDelta td:
                        var textAtMs = uiStreamStopwatch.Elapsed.TotalMilliseconds;
                        uiFirstTextMs ??= textAtMs;
                        uiLastTextMs = textAtMs;
                        bubble.AppendText(td.Delta);
                        AppendBounded(finalTextBuilder, td.Delta);
                        break;
                    case StreamThinkingDelta th:
                        bubble.AppendThinking(th.Delta);
                        AppendBounded(finalThinkingBuilder, th.Delta);
                        break;
                    case StreamToolCallStart tcs:
                        bubble.StartToolCall(tcs.Id, tcs.Name);
                        break;
                    case StreamToolCallArguments tca:
                        bubble.AppendToolCallArguments(tca.Id, tca.Delta);
                        break;
                    case StreamToolCallEnd tce:
                        bubble.EndToolCall(tce.Id);
                        break;
                    case StreamToolResult tr:
                        bubble.AddToolResult(tr.Id, tr.Name, tr.Output);
                        break;
                    case StreamResponseId rid:
                        finalResponseId = rid.Id;
                        break;
                    case StreamRunId run:
                        if (IsComposerRunCurrent(composerRunId))
                        {
                            _activeGatewayRunId = run.Id;
                        }
                        if (_activeStreams.TryGetValue(conversationId, out var state))
                        {
                            _activeStreams[conversationId] = state with { GatewayRunId = run.Id };
                        }
                        bubble.SetStatus($"Run Hermes attiva: {run.Id}");
                        break;
                    case StreamVisualBlocks vb:
                        finalBlocks = vb.Blocks;
                        finalBlocksVersion = vb.Version;
                        bubble.SetVisualBlocks(vb.Blocks, RenderVisualBlock);
                        break;
                    case StreamStatus ss:
                        statusMessage = ss.Message;
                        bubble.SetStatus(ss.Message);
                        if (ss.Message.Contains("Protocollo", StringComparison.OrdinalIgnoreCase) ||
                            ss.Message.Contains("Fallback compat", StringComparison.OrdinalIgnoreCase) ||
                            ss.Message.Contains("Strict native", StringComparison.OrdinalIgnoreCase))
                        {
                            var rawStatus = $$"""{"message":{{JsonSerializer.Serialize(ss.Message)}}}""";
                            AddRawEventIfEnabled("status", rawStatus);
                        }
                        break;
                    case StreamPromptProgress progress:
                        bubble.SetPromptProgress(progress);
                        break;
                    case StreamRawHermesEvent raw:
                        AddRawEventIfEnabled(raw.Name, raw.Json);
                        break;
                    case StreamDone done:
                        finalStats = done.Stats;
                        if (!string.IsNullOrEmpty(done.AccumulatedText) &&
                            !string.Equals(finalTextBuilder.ToString(), done.AccumulatedText, StringComparison.Ordinal))
                        {
                            finalTextBuilder.Clear();
                            AppendBounded(finalTextBuilder, done.AccumulatedText);
                        }
                        if (!string.IsNullOrEmpty(done.AccumulatedThinking) &&
                            !string.Equals(finalThinkingBuilder.ToString(), done.AccumulatedThinking, StringComparison.Ordinal))
                        {
                            finalThinkingBuilder.Clear();
                            AppendBounded(finalThinkingBuilder, done.AccumulatedThinking);
                        }
                        var inlineBlocks = VisualBlockParser.ExtractInlineMediaBlocks(finalTextBuilder.ToString());
                        if (inlineBlocks.Count > 0)
                        {
                            finalBlocks = MergeVisualBlocks(finalBlocks, inlineBlocks);
                            finalBlocksVersion = VisualBlocksContract.Version;
                            bubble.SetVisualBlocks(inlineBlocks, RenderVisualBlock);
                            var cleanedFinalText = VisualBlockParser.StripInlineMediaMarkup(finalTextBuilder.ToString());
                            finalTextBuilder.Clear();
                            AppendBounded(finalTextBuilder, cleanedFinalText);
                        }
                        bubble.SynchronizeFinalContent(
                            finalTextBuilder.Length == 0 ? null : finalTextBuilder.ToString(),
                            string.IsNullOrEmpty(done.AccumulatedThinking) ? null : done.AccumulatedThinking);
                        bubble.Complete(done.Stats);
                        if (IsComposerRunCurrent(composerRunId))
                        {
                            UpdateContextMeter(done.Stats);
                        }
                        break;
                    case StreamError se:
                        streamError = se.Message;
                        break;
                }

                if (Stopwatch.GetElapsedTime(lastUiPumpAt).TotalMilliseconds >= 33)
                {
                    bubble.FlushPreview();
                    lastUiPumpAt = Stopwatch.GetTimestamp();
                    await Task.Delay(1);
                }

                if ((DateTimeOffset.Now - lastCheckpointAt).TotalMilliseconds >= StreamingCheckpointIntervalMs &&
                    (finalTextBuilder.Length > 0 || finalBlocks is { Count: > 0 }))
                {
                    lastCheckpointAt = DateTimeOffset.Now;
                    var checkpointText = SnapshotPreview(finalTextBuilder);
                    var partialMessages = localHistory
                        .Concat(new[]
                        {
                            new ChatMessageRecord(
                                "Hermes",
                                string.IsNullOrWhiteSpace(checkpointText) ? "Hermes sta lavorando..." : checkpointText,
                                DateTimeOffset.Now,
                                finalBlocksVersion,
                                finalBlocks?.ToList(),
                                null,
                                rawEvents.ToList())
                        })
                        .ToList();
                    var checkpoint = await Task.Run(() => ChatArchiveStore.SaveSnapshot(conversationId, sendMode, prompt, partialMessages, "Hermes in corso", finalResponseId ?? previousResponseId));
                    conversationId = checkpoint.Id;
                    previousResponseId = checkpoint.PreviousResponseId;
                    if (IsComposerRunCurrent(composerRunId))
                    {
                        _conversationId = conversationId;
                        _previousResponseId = previousResponseId;
                    }
                }
            }

            var finalText = finalTextBuilder.ToString();
            var finalThinking = finalThinkingBuilder.ToString();
            if (finalStats is null && finalTextBuilder.Length > 0)
            {
                var totalMs = uiStreamStopwatch.Elapsed.TotalMilliseconds;
                var tokensOut = EstimateTokenCount(finalText);
                finalStats = new ChatStreamStats(
                    uiFirstTextMs,
                    totalMs,
                    tokensOut,
                    CalculateFallbackTokensPerSecond(tokensOut, uiFirstTextMs, uiLastTextMs, totalMs),
                    null);
                bubble.Complete(finalStats);
                if (IsComposerRunCurrent(composerRunId))
                {
                    UpdateContextMeter(finalStats);
                }
                Trace.WriteLine(
                    $"[ChatStreamUi] synthesized stats totalMs={totalMs:0} tokensOut={tokensOut} " +
                    $"firstTextMs={uiFirstTextMs:0} lastTextMs={uiLastTextMs:0}");
            }
            if (string.IsNullOrEmpty(finalText) && streamError is not null && settings.DemoMode)
            {
                finalText = $"Hermes assente, fallback locale: {streamError}";
                bubble.AppendText(finalText);
                source = "Fallback locale";
                usedFallback = true;
                statusMessage = $"Hermes non disponibile: {streamError}";
                bubble.Complete(new ChatStreamStats(null, null, null, null, null));
            }
            else if (string.IsNullOrEmpty(finalText) && streamError is not null)
            {
                finalText = $"Hermes non raggiungibile: {streamError}";
                bubble.AppendText(finalText);
                ShowError(finalText);
                source = "Errore Hermes";
                statusMessage = $"Invio fallito: {streamError}";
                bubble.Complete(new ChatStreamStats(null, null, null, null, null));
            }

            localHistory.Add(new ChatMessageRecord("Hermes", finalText, DateTimeOffset.Now, finalBlocksVersion, finalBlocks?.ToList(), null, rawEvents));
            if (finalStats is not null)
            {
                localHistory[^1] = localHistory[^1] with { Stats = finalStats };
            }
            if (IsComposerRunCurrent(composerRunId))
            {
                _messageHistory.Add(localHistory[^1]);
            }
            var saved = await Task.Run(() => ChatArchiveStore.SaveSnapshot(conversationId, sendMode, prompt, localHistory.ToList(), source, finalResponseId ?? previousResponseId));
            conversationId = saved.Id;
            previousResponseId = saved.PreviousResponseId;
            if (IsComposerRunCurrent(composerRunId))
            {
                _conversationId = conversationId;
                _previousResponseId = previousResponseId;
                UpdateContextMeter();
            }

            if (IsComposerRunCurrent(composerRunId) && (usedFallback || source.Contains("Errore", StringComparison.OrdinalIgnoreCase)))
            {
                AddAction("Stato", statusMessage);
            }
        }
        catch (Exception ex)
        {
            if (IsComposerRunCurrent(composerRunId))
            {
                ShowError($"Stream interrotto: {ex.Message}");
            }
            bubble?.AppendText($"\n[errore stream] {ex.Message}");
            bubble?.Complete(new ChatStreamStats(null, null, null, null, null));
            var interruptedMessages = localHistory
                .Concat(new[] { new ChatMessageRecord("Stato", $"Stream interrotto: {ex.Message}", DateTimeOffset.Now) })
                .ToList();
            var saved = await Task.Run(() => ChatArchiveStore.SaveSnapshot(conversationId, sendMode, prompt, interruptedMessages, "Errore Hermes", previousResponseId));
            conversationId = saved.Id;
            previousResponseId = saved.PreviousResponseId;
            if (IsComposerRunCurrent(composerRunId))
            {
                _conversationId = conversationId;
                _previousResponseId = previousResponseId;
            }
        }
        finally
        {
            var kvp = _activeStreams.FirstOrDefault(x => x.Value.ComposerRunId == composerRunId);
            if (kvp.Key is not null)
            {
                _activeStreams.Remove(kvp.Key);
            }

            if (ReferenceEquals(_activeStreamCts, streamCts))
            {
                _activeStreamCts = null;
            }
            _activeGatewayRunId = null;
            streamCts?.Cancel();
            streamCts?.Dispose();
            bubble?.StopShimmer();
            if (ReferenceEquals(_currentStreamingBubble, bubble))
            {
                _currentStreamingBubble = null;
            }
            ReleaseComposerRun(composerRunId);
        }
    }

    private static ChannelReader<ChatStreamEvent> StartStreamProducer(
        AppSettings settings,
        string mode,
        string prompt,
        IReadOnlyList<ChatMessageRecord> history,
        string? conversationId,
        string? previousResponseId,
        IReadOnlyList<ChatInputAttachment> attachments,
        CancellationToken cancellationToken)
    {
        var channel = Channel.CreateBounded<ChatStreamEvent>(new BoundedChannelOptions(512)
        {
            SingleReader = true,
            SingleWriter = true,
            FullMode = BoundedChannelFullMode.Wait,
            AllowSynchronousContinuations = false
        });

        _ = Task.Run(async () =>
        {
            var producerStopwatch = Stopwatch.StartNew();
            var textBatch = new StringBuilder();
            var thinkingBatch = new StringBuilder();
            var lastBatchFlushAt = Stopwatch.GetTimestamp();
            var inputTextDeltas = 0;
            var inputThinkingDeltas = 0;
            var emittedTextBatches = 0;
            var emittedThinkingBatches = 0;
            var maxBatchChars = 0;

            async Task FlushBatchesAsync()
            {
                if (textBatch.Length > 0)
                {
                    maxBatchChars = Math.Max(maxBatchChars, textBatch.Length);
                    await channel.Writer.WriteAsync(new StreamTextDelta(textBatch.ToString()), cancellationToken).ConfigureAwait(false);
                    emittedTextBatches++;
                    textBatch.Clear();
                }
                if (thinkingBatch.Length > 0)
                {
                    maxBatchChars = Math.Max(maxBatchChars, thinkingBatch.Length);
                    await channel.Writer.WriteAsync(new StreamThinkingDelta(thinkingBatch.ToString()), cancellationToken).ConfigureAwait(false);
                    emittedThinkingBatches++;
                    thinkingBatch.Clear();
                }
                lastBatchFlushAt = Stopwatch.GetTimestamp();
            }

            try
            {
                await foreach (var ev in ChatStreamClient
                                   .StreamChatAsync(settings, mode, prompt, history, conversationId, previousResponseId, attachments, cancellationToken)
                                   .WithCancellation(cancellationToken)
                                   .ConfigureAwait(false))
                {
                    switch (ev)
                    {
                        case StreamTextDelta textDelta:
                            inputTextDeltas++;
                            textBatch.Append(textDelta.Delta);
                            break;
                        case StreamThinkingDelta thinkingDelta:
                            inputThinkingDeltas++;
                            thinkingBatch.Append(thinkingDelta.Delta);
                            break;
                        case StreamRawHermesEvent when !settings.AdvancedChatDetails:
                            break;
                        default:
                            await FlushBatchesAsync().ConfigureAwait(false);
                            await channel.Writer.WriteAsync(ev, cancellationToken).ConfigureAwait(false);
                            break;
                    }

                    if (textBatch.Length + thinkingBatch.Length >= 2048 ||
                        Stopwatch.GetElapsedTime(lastBatchFlushAt).TotalMilliseconds >= 33)
                    {
                        await FlushBatchesAsync().ConfigureAwait(false);
                    }
                }
                await FlushBatchesAsync().ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
            }
            catch (Exception ex)
            {
                await FlushBatchesAsync().ConfigureAwait(false);
                channel.Writer.TryWrite(new StreamError($"Stream interrotto: {ex.Message}"));
            }
            finally
            {
                Trace.WriteLine(
                    $"[ChatStreamProducer] done elapsedMs={producerStopwatch.ElapsedMilliseconds} " +
                    $"inputTextDeltas={inputTextDeltas} inputThinkingDeltas={inputThinkingDeltas} " +
                    $"textBatches={emittedTextBatches} thinkingBatches={emittedThinkingBatches} maxBatchChars={maxBatchChars}");
                channel.Writer.TryComplete();
            }
        }, cancellationToken);

        return channel.Reader;
    }

    private static string SnapshotPreview(StringBuilder builder)
    {
        if (builder.Length == 0)
        {
            return string.Empty;
        }

        if (builder.Length <= StreamingCheckpointMaxChars)
        {
            return builder.ToString();
        }

        return builder.ToString(0, StreamingCheckpointMaxChars) +
               "\n\n[checkpoint parziale limitato; risposta completa salvata a fine stream]";
    }

    private static void AppendBounded(StringBuilder builder, string text)
    {
        if (string.IsNullOrEmpty(text) || builder.Length >= StreamAccumMaxChars)
        {
            return;
        }

        var remaining = StreamAccumMaxChars - builder.Length;
        if (text.Length <= remaining)
        {
            builder.Append(text);
            return;
        }

        builder.Append(text, 0, remaining);
        builder.Append("\n\n[…troncato: limite 2000000 caratteri raggiunto.]");
    }

    private static IReadOnlyList<VisualBlockRecord> MergeVisualBlocks(IReadOnlyList<VisualBlockRecord>? current, IReadOnlyList<VisualBlockRecord> incoming)
    {
        if (incoming.Count == 0)
        {
            return current ?? [];
        }

        var merged = new List<VisualBlockRecord>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var block in (current ?? []).Concat(incoming))
        {
            var key = !string.IsNullOrWhiteSpace(block.MediaUrl)
                ? $"{block.Type}:media:{block.MediaUrl}"
                : !string.IsNullOrWhiteSpace(block.Id)
                    ? block.Id
                    : $"{block.Type}:{block.Title}";
            if (seen.Add(key))
            {
                merged.Add(block);
            }
        }
        return merged;
    }

    private static async Task<ChatInputAttachment?> TryCreateAttachmentAsync(StorageFile file, int maxAttachmentMb)
    {
        var mimeType = MimeTypeFromExtension(file.FileType);

        var buffer = await FileIO.ReadBufferAsync(file);
        var bytes = new byte[buffer.Length];
        using (var reader = DataReader.FromBuffer(buffer))
        {
            reader.ReadBytes(bytes);
        }
        var maxBytes = Math.Clamp(maxAttachmentMb, 1, 150) * 1024 * 1024;
        if (bytes.Length <= 0 || bytes.Length > maxBytes)
        {
            return null;
        }

        var dataUrl = $"data:{mimeType};base64,{Convert.ToBase64String(bytes)}";
        return new ChatInputAttachment(file.Name, mimeType, dataUrl, bytes.LongLength);
    }

    private static async Task<ChatInputAttachment?> TryCreateClipboardImageAttachmentAsync(int maxAttachmentMb)
    {
        var view = Clipboard.GetContent();
        if (!view.Contains(StandardDataFormats.Bitmap))
        {
            return null;
        }

        var reference = await view.GetBitmapAsync();
        using var stream = await reference.OpenReadAsync();
        var maxBytes = Math.Clamp(maxAttachmentMb, 1, 150) * 1024 * 1024;
        if (stream.Size <= 0 || stream.Size > (ulong)maxBytes)
        {
            return null;
        }

        var bytes = new byte[stream.Size];
        using (var reader = new DataReader(stream.GetInputStreamAt(0)))
        {
            await reader.LoadAsync((uint)stream.Size);
            reader.ReadBytes(bytes);
        }

        if (bytes.Length <= 0 || bytes.Length > maxBytes)
        {
            return null;
        }

        var fileName = $"clipboard-{DateTime.Now:yyyyMMdd-HHmmss}.png";
        var dataUrl = $"data:image/png;base64,{Convert.ToBase64String(bytes)}";
        return new ChatInputAttachment(fileName, "image/png", dataUrl, bytes.LongLength);
    }

    private static string MimeTypeFromExtension(string extension)
    {
        return extension.ToLowerInvariant() switch
        {
            ".png" => "image/png",
            ".jpg" or ".jpeg" => "image/jpeg",
            ".webp" => "image/webp",
            ".bmp" => "image/bmp",
            ".gif" => "image/gif",
            ".pdf" => "application/pdf",
            ".txt" => "text/plain",
            ".md" => "text/markdown",
            ".csv" => "text/csv",
            ".json" => "application/json",
            ".xml" => "application/xml",
            ".html" or ".htm" => "text/html",
            ".doc" => "application/msword",
            ".docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ".xls" => "application/vnd.ms-excel",
            ".xlsx" => "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            ".ppt" => "application/vnd.ms-powerpoint",
            ".pptx" => "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            ".zip" => "application/zip",
            _ => "application/octet-stream"
        };
    }

    private void RenderAttachmentPreviews()
    {
        AttachmentPreviewPanel.Children.Clear();
        AttachmentPreviewPanel.Visibility = _pendingAttachments.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        foreach (var attachment in _pendingAttachments)
        {
            AttachmentPreviewPanel.Children.Add(AttachmentPreviewCard(attachment));
        }
    }

    private UIElement AttachmentPreviewCard(ChatInputAttachment attachment)
    {
        var root = new Border
        {
            Padding = new Thickness(8),
            Background = (Brush)Application.Current.Resources["SurfaceBrush"],
            BorderBrush = (Brush)Application.Current.Resources["BorderBrushSoft"],
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(12),
            MaxWidth = 520,
            HorizontalAlignment = HorizontalAlignment.Left
        };
        var grid = new Grid { ColumnSpacing = 10 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var preview = AttachmentPreviewVisual(attachment);
        var text = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        text.Children.Add(new TextBlock
        {
            Text = attachment.FileName,
            Foreground = new SolidColorBrush(Colors.White),
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            TextTrimming = Microsoft.UI.Xaml.TextTrimming.CharacterEllipsis
        });
        text.Children.Add(new TextBlock
        {
            Text = $"{attachment.MimeType} · {FormatAttachmentBytes(attachment.SizeBytes)}",
            Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
            FontSize = 12
        });
        var remove = new Button
        {
            Width = 32,
            Height = 32,
            Content = new FontIcon { Glyph = "\uE711", FontSize = 12 },
            Tag = attachment
        };
        ToolTipService.SetToolTip(remove, "Rimuovi allegato");
        remove.Click += RemoveAttachment_Click;
        Grid.SetColumn(text, 1);
        Grid.SetColumn(remove, 2);
        grid.Children.Add(preview);
        grid.Children.Add(text);
        grid.Children.Add(remove);
        root.Child = grid;
        return root;
    }

    private static UIElement AttachmentPreviewVisual(ChatInputAttachment attachment)
    {
        if (attachment.MimeType.StartsWith("image/", StringComparison.OrdinalIgnoreCase) &&
            BitmapFromDataUrl(attachment.DataUrl) is { } bitmap)
        {
            return new Image
            {
                Width = 54,
                Height = 54,
                Stretch = Stretch.UniformToFill,
                Source = bitmap
            };
        }

        return new Border
        {
            Width = 54,
            Height = 54,
            CornerRadius = new CornerRadius(8),
            Background = (Brush)Application.Current.Resources["ElevatedSurfaceBrush"],
            Child = new FontIcon
            {
                Glyph = "\uE8A5",
                FontSize = 24,
                Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            }
        };
    }

    private void RemoveAttachment_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: ChatInputAttachment attachment })
        {
            _pendingAttachments.Remove(attachment);
            RenderAttachmentPreviews();
        }
    }

    private static BitmapImage? BitmapFromDataUrl(string dataUrl)
    {
        try
        {
            var comma = dataUrl.IndexOf(',');
            if (comma < 0)
            {
                return null;
            }
            var bytes = Convert.FromBase64String(dataUrl[(comma + 1)..]);
            using var stream = new MemoryStream(bytes);
            var bitmap = new BitmapImage();
            bitmap.SetSource(stream.AsRandomAccessStream());
            return bitmap;
        }
        catch
        {
            return null;
        }
    }

    private static string FormatAttachmentBytes(long bytes)
    {
        string[] units = ["B", "KB", "MB", "GB"];
        var value = Math.Max(0, bytes);
        var unit = 0;
        var size = (double)value;
        while (size >= 1024 && unit < units.Length - 1)
        {
            size /= 1024;
            unit++;
        }

        return unit == 0 ? $"{value} {units[unit]}" : $"{size:0.#} {units[unit]}";
    }

    private static bool IsShiftPressed()
    {
        return (Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Shift) &
                Windows.UI.Core.CoreVirtualKeyStates.Down) == Windows.UI.Core.CoreVirtualKeyStates.Down;
    }

    private void InsertPromptNewLine()
    {
        var start = PromptBox.SelectionStart;
        var selectedLength = PromptBox.SelectionLength;
        var text = PromptBox.Text ?? string.Empty;
        PromptBox.Text = text.Remove(start, selectedLength).Insert(start, Environment.NewLine);
        PromptBox.SelectionStart = start + Environment.NewLine.Length;
    }

    private string AppendPrompt(string addition)
    {
        var current = PromptBox.Text.Trim();
        return string.IsNullOrWhiteSpace(current) ? addition : $"{current}\n{addition}";
    }

    private void AddAction(string title, string text)
    {
        EmptyState.Visibility = Visibility.Collapsed;
        AddBubble(title, text, "SurfaceBrush", HorizontalAlignment.Left);
    }

    private void LoadConversation(string conversationId)
    {
        var conversation = ChatArchiveStore.Find(conversationId);
        if (conversation is null)
        {
            return;
        }

        var isStreaming = _activeStreams.TryGetValue(conversation.Id, out var activeStream);

        _conversationId = conversation.Id;
        var expectedServerConversationId = HermesHubProtocol.ServerConversationId(conversation.Id) ?? string.Empty;
        _previousResponseId = string.Equals(conversation.ServerConversationId, expectedServerConversationId, StringComparison.Ordinal)
            ? conversation.PreviousResponseId
            : null;
        _lastServerContextTokens = 0;
        EmptyState.Visibility = Visibility.Collapsed;
        Messages.Clear();
        _messageHistory.Clear();

        for (int i = 0; i < conversation.Messages.Count; i++)
        {
            var message = conversation.Messages[i];
            if (isStreaming && i == conversation.Messages.Count - 1 && message.Author == "Hermes")
            {
                // Salta il salvataggio parziale, ricollegheremo la bolla live
                continue;
            }
            _messageHistory.Add(message);
            AddBubble(
                message.Author,
                message.Text,
                message.Author == "Tu" ? "UserBubbleBrush" : "AssistantBubbleBrush",
                message.Author == "Tu" ? HorizontalAlignment.Right : HorizontalAlignment.Left,
                message.VisualBlocks,
                message.Stats,
                message.RawEvents);
        }
        
        if (isStreaming && activeStream is not null)
        {
            Messages.Add(new MessageViewModel(activeStream.Bubble.Container));
            _currentComposerRunId = activeStream.ComposerRunId;
            _currentStreamingBubble = activeStream.Bubble;
            _activeStreamCts = activeStream.Cts;
            _activeGatewayRunId = activeStream.GatewayRunId;
            UpdateSendButtonVisual(isStreaming: true);
        }
        else
        {
            _currentComposerRunId = null;
            _currentStreamingBubble = null;
            _activeStreamCts = null;
            _activeGatewayRunId = null;
            UpdateSendButtonVisual(isStreaming: false);
        }
        
        UpdateContextMeter();
    }

    private void AddBubble(
        string author,
        string text,
        string brushKey,
        HorizontalAlignment alignment,
        IReadOnlyList<VisualBlockRecord>? visualBlocks = null,
        ChatStreamStats? stats = null,
        IReadOnlyList<HermesRawEventRecord>? rawEvents = null)
    {
        var advanced = AppSettingsStore.Load().AdvancedChatDetails;
        var isUser = string.Equals(author, "Tu", StringComparison.OrdinalIgnoreCase);
        var isAssistant = string.Equals(author, "Hermes", StringComparison.OrdinalIgnoreCase);
        var content = new StackPanel { Spacing = 8 };
        if (!isAssistant)
        {
            content.Children.Add(new TextBlock
            {
                Text = author,
                FontSize = 12,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                Foreground = new SolidColorBrush(Colors.White)
            });
        }
        if (isAssistant)
        {
            content.Children.Add(MarkdownRenderer.Render(text, Colors.White));
        }
        else
        {
            content.Children.Add(new TextBlock
            {
                Text = text,
                TextWrapping = TextWrapping.WrapWholeWords,
                Foreground = new SolidColorBrush(Colors.White)
            });
        }

        if (visualBlocks is { Count: > 0 })
        {
            foreach (var block in visualBlocks.Where(VisualBlockParser.IsValid))
            {
                content.Children.Add(RenderVisualBlock(block));
            }
        }
        if (advanced &&
            isAssistant &&
            rawEvents is { Count: > 0 })
        {
            foreach (var raw in rawEvents.Take(40))
            {
                content.Children.Add(RenderRawHermesEvent(raw));
            }
        }
        AddFooter(content, stats, text);

        var bubble = new Border
        {
            MaxWidth = isUser ? 520 : 820,
            Padding = isAssistant ? new Thickness(4, 2, 4, 2) : new Thickness(18, 14, 18, 14),
            CornerRadius = isAssistant ? new CornerRadius(0) : new CornerRadius(20),
            Background = isAssistant ? new SolidColorBrush(Colors.Transparent) : (Brush)Application.Current.Resources[brushKey],
            BorderBrush = isAssistant ? new SolidColorBrush(Colors.Transparent) : (Brush)Application.Current.Resources["BorderBrushSoft"],
            BorderThickness = isAssistant ? new Thickness(0) : new Thickness(1),
            HorizontalAlignment = alignment,
            Child = content
        };
        bubble.ContextFlyout = BuildCopyFlyout(text);

        Messages.Add(new MessageViewModel(bubble));
        _ = MessagesScroll.ChangeView(null, MessagesScroll.ScrollableHeight, null);
        UpdateContextMeter();
    }

    private void AddFooter(StackPanel content, ChatStreamStats? stats, string copyText)
    {
        var settings = AppSettingsStore.Load();
        var line = FormatChatStats(stats, settings);
        var showStats = !string.IsNullOrWhiteSpace(line) && (settings.AdvancedChatDetails || settings.ShowMessageMetrics);

        var footerGrid = new Grid { Margin = new Thickness(0, 4, 0, 0) };
        footerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        footerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        footerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        if (showStats)
        {
            var statsText = new TextBlock
            {
                Text = line,
                FontSize = 11,
                FontFamily = new FontFamily("Consolas"),
                Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
                TextWrapping = TextWrapping.WrapWholeWords,
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(statsText, 0);
            footerGrid.Children.Add(statsText);
        }

        var speakBtn = new Button
        {
            Content = new FontIcon { Glyph = "\uE768", FontSize = 12 },
            Background = new SolidColorBrush(Windows.UI.Color.FromArgb(0, 0, 0, 0)),
            BorderThickness = new Thickness(0),
            Padding = new Thickness(6),
            VerticalAlignment = VerticalAlignment.Center
        };
        ToolTipService.SetToolTip(speakBtn, "Leggi messaggio");
        speakBtn.Click += async (s, e) => await SpeakMessageAsync(copyText, speakBtn);
        Grid.SetColumn(speakBtn, 1);
        footerGrid.Children.Add(speakBtn);

        var copyBtn = new Button
        {
            Content = new FontIcon { Glyph = "\uE8C8", FontSize = 12 },
            Background = new SolidColorBrush(Windows.UI.Color.FromArgb(0, 0, 0, 0)),
            BorderThickness = new Thickness(0),
            Padding = new Thickness(6),
            VerticalAlignment = VerticalAlignment.Center
        };
        ToolTipService.SetToolTip(copyBtn, "Copia messaggio");
        copyBtn.Click += (s, e) =>
        {
            var dp = new Windows.ApplicationModel.DataTransfer.DataPackage();
            dp.SetText(copyText);
            Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(dp);
            copyBtn.Content = new FontIcon { Glyph = "\uE8FB", FontSize = 12 };
        };
        Grid.SetColumn(copyBtn, 2);
        footerGrid.Children.Add(copyBtn);

        content.Children.Add(footerGrid);
    }

    private async Task SpeakMessageAsync(string text, Button? sourceButton = null)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return;
        }

        object? originalContent = sourceButton?.Content;
        if (sourceButton is not null)
        {
            sourceButton.IsEnabled = false;
            sourceButton.Content = new ProgressRing { Width = 14, Height = 14, IsActive = true };
        }

        try
        {
            var settings = AppSettingsStore.Load();
            var audioPath = await SpeechGatewayService.SynthesizeToFileAsync(settings, text);
            await PlayTtsFileAsync(audioPath);
        }
        catch (Exception ex)
        {
            ShowError($"Errore TTS Kokoro: {ex.Message}");
        }
        finally
        {
            if (sourceButton is not null)
            {
                sourceButton.IsEnabled = true;
                sourceButton.Content = originalContent ?? new FontIcon { Glyph = "\uE768", FontSize = 12 };
            }
        }
    }

    private Task PlayTtsFileAsync(string filePath)
    {
        var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        _ttsPlayer?.Dispose();
        _ttsPlayer = new MediaPlayer
        {
            Source = MediaSource.CreateFromUri(new Uri(filePath))
        };
        _ttsPlayer.MediaEnded += (_, _) => completion.TrySetResult();
        _ttsPlayer.MediaFailed += (_, args) => completion.TrySetException(new InvalidOperationException(args.ErrorMessage));
        _ttsPlayer.Play();
        return completion.Task;
    }

    private static UIElement RenderRawHermesEvent(HermesRawEventRecord raw)
    {
        return new Expander
        {
            Header = new TextBlock
            {
                Text = $"Evento Hermes · {raw.Name}",
                FontSize = 12,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                Foreground = (Brush)Application.Current.Resources["MutedTextBrush"]
            },
            Content = new TextBlock
            {
                Text = raw.Json.Length > 4000 ? raw.Json[..4000] + "..." : raw.Json,
                FontFamily = new FontFamily("Consolas"),
                FontSize = 11,
                Foreground = new SolidColorBrush(Colors.White),
                TextWrapping = TextWrapping.Wrap
            },
            Background = (Brush)Application.Current.Resources["SurfaceBrush"],
            BorderBrush = (Brush)Application.Current.Resources["BorderBrushSoft"],
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(12)
        };
    }

    private static string FormatChatStats(ChatStreamStats? stats, AppSettings settings)
    {
        if (stats is null)
        {
            return string.Empty;
        }

        var parts = new List<string>();
        if (settings.MetricTtft && stats.TimeToFirstTokenMs is { } ttft && ttft > 0)
        {
            parts.Add($"TTFT {ttft / 1000.0:0.0}s");
        }
        if (settings.MetricTokensPerSecond && stats.TokensPerSecond is { } tps && tps > 0)
        {
            parts.Add($"{tps:0.00} t/s");
        }
        if (settings.MetricOutputTokens && stats.TokensOut is { } toks && toks > 0)
        {
            parts.Add($"{toks} tok");
        }
        if (settings.MetricPromptTokens && stats.PromptTokens is { } prompt && prompt > 0)
        {
            parts.Add($"prompt {prompt}");
        }
        var contextTokens = ContextTokensFromStats(stats);
        if (settings.MetricContextTokens && contextTokens > 0)
        {
            parts.Add($"ctx {contextTokens}");
        }
        if (settings.MetricContextTokens && stats.ContextLength is { } maxCtx && maxCtx > 0)
        {
            parts.Add($"max {maxCtx}");
        }
        if (settings.MetricDuration && stats.TotalMs is { } total && total > 0)
        {
            parts.Add($"{total / 1000.0:0.0}s");
        }
        return string.Join("  ·  ", parts);
    }

    private StreamingBubble CreateStreamingAssistantBubble()
    {
        var settings = AppSettingsStore.Load();
        var bubble = new StreamingBubble(
            this,
            element => Messages.Add(new MessageViewModel(element)),
            MessagesScroll,
            settings.AdvancedChatDetails,
            settings.ShowToolCalls,
            settings.ShowMessageMetrics,
            SpeakMessageAsync);
        return bubble;
    }

    private MenuFlyout BuildCopyFlyout(string text)
    {
        var item = new MenuFlyoutItem { Text = "Copia", Tag = text };
        item.Click += CopyMessage_Click;
        var flyout = new MenuFlyout();
        flyout.Items.Add(item);
        return flyout;
    }

    private void CopyMessage_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not MenuFlyoutItem { Tag: string text } || string.IsNullOrEmpty(text))
        {
            return;
        }
        var package = new DataPackage();
        package.SetText(text);
        Clipboard.SetContent(package);
    }

    private void ShowError(string message)
    {
        ErrorInfoBar.Title = "Errore";
        ErrorInfoBar.Message = message;
        ErrorInfoBar.Severity = InfoBarSeverity.Error;
        ErrorInfoBar.IsOpen = true;
    }

    private void UpdateContextMeter(ChatStreamStats? serverStats = null)
    {
        var reportedContextTokens = ContextTokensFromStats(serverStats);
        if (reportedContextTokens > 0)
        {
            _lastServerContextTokens = reportedContextTokens;
        }
        if (serverStats?.ContextLength is > 0)
        {
            _lastServerContextLength = serverStats.ContextLength.Value;
        }
        if (serverStats?.ContextPercent is >= 0 and <= 100)
        {
            _lastServerContextPercent = serverStats.ContextPercent.Value;
        }

        var settings = AppSettingsStore.Load();
        if (HermesHubProtocol.IsNativePreferred(settings) && _lastServerContextTokens <= 0)
        {
            ContextMeterText.Text = "H";
            ContextMeterFill.Data = null;
            ToolTipService.SetToolTip(
                ContextMeter,
                $"Contesto delegato a Hermes Agent. Modalita: {_mode}. Client archive = snapshot UI, non fonte memoria primaria.");
            return;
        }

        var estimatedTokens = EstimateCurrentContextTokens();
        var contextWindow = _lastServerContextLength > 0 ? _lastServerContextLength : DefaultContextWindowTokens;
        var explicitPercent = _lastServerContextPercent;
        var tokens = HermesHubProtocol.IsNativePreferred(settings)
            ? Math.Max(0, _lastServerContextTokens)
            : Math.Max(estimatedTokens, _lastServerContextTokens);
        var percent = explicitPercent ?? (int)Math.Round(
            Math.Min(tokens, contextWindow) * 100.0 / contextWindow,
            MidpointRounding.AwayFromZero);
        percent = Math.Clamp(percent, 0, 100);

        ContextMeterText.Text = $"{percent}%";
        ContextMeterFill.Data = BuildContextMeterGeometry(percent / 100.0, 54, 54);
        ToolTipService.SetToolTip(
            ContextMeter,
            HermesHubProtocol.IsNativePreferred(settings)
                ? $"Contesto server Hermes: {tokens:N0}/{contextWindow:N0} token reported. Modalita: {_mode}."
                : $"Contesto chat: {tokens:N0}/{contextWindow:N0} token stimati. Modalita: {_mode}.");
    }

    private void ResetServerContextMeter()
    {
        _lastServerContextTokens = 0;
        _lastServerContextLength = 0;
        _lastServerContextPercent = null;
    }

    private static int ContextTokensFromStats(ChatStreamStats? stats)
    {
        if (stats is null)
        {
            return 0;
        }

        if (stats.ContextTokens is > 0)
        {
            return stats.ContextTokens.Value;
        }

        var prompt = stats.PromptTokens is > 0 ? stats.PromptTokens.Value : 0;
        var output = stats.TokensOut is > 0 ? stats.TokensOut.Value : 0;
        return Math.Max(Math.Max(prompt, output), prompt + output);
    }

    private int EstimateCurrentContextTokens()
    {
        var historyTokens = _messageHistory.Sum(message =>
            EstimateTokenCount(message.Author) + EstimateTokenCount(message.Text) + MessageContextOverheadTokens);
        var draft = PromptBox.Text?.Trim() ?? string.Empty;
        var draftTokens = string.IsNullOrWhiteSpace(draft)
            ? 0
            : EstimateTokenCount(draft) + MessageContextOverheadTokens;

        if (historyTokens == 0 && draftTokens == 0)
        {
            return 0;
        }

        return ContextSystemOverheadTokens + historyTokens + draftTokens;
    }

    private static int EstimateTokenCount(string text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return 0;
        }

        return Math.Max(1, (text.Length + 3) / 4);
    }

    private static double? CalculateFallbackTokensPerSecond(int tokensOut, double? firstTextMs, double? lastTextMs, double totalMs)
    {
        if (tokensOut < 8)
        {
            return null;
        }

        var durationMs = firstTextMs.HasValue && lastTextMs.HasValue
            ? Math.Max(0, lastTextMs.Value - firstTextMs.Value)
            : totalMs;
        if (durationMs < 1500)
        {
            return null;
        }

        var value = Math.Max(1, tokensOut - 1) / (durationMs / 1000.0);
        return double.IsFinite(value) && value is > 0 and <= 70 ? value : null;
    }

    private static Geometry? BuildContextMeterGeometry(double fraction, double width, double height)
    {
        fraction = Math.Clamp(fraction, 0, 1);
        if (fraction <= 0)
        {
            return null;
        }

        var diameter = Math.Min(width, height) - 8;
        var radius = diameter / 2;
        var center = new Point(width / 2, height / 2);
        if (fraction >= 0.999)
        {
            return new EllipseGeometry
            {
                Center = center,
                RadiusX = radius,
                RadiusY = radius
            };
        }

        var startAngle = -90.0;
        var endAngle = startAngle + (360.0 * fraction);
        var start = PointOnCircle(center, radius, startAngle);
        var end = PointOnCircle(center, radius, endAngle);

        var figure = new PathFigure
        {
            StartPoint = center,
            IsClosed = true,
            IsFilled = true
        };
        figure.Segments.Add(new LineSegment { Point = start });
        figure.Segments.Add(new ArcSegment
        {
            Point = end,
            Size = new Size(radius, radius),
            IsLargeArc = fraction > 0.5,
            SweepDirection = SweepDirection.Clockwise
        });

        var geometry = new PathGeometry();
        geometry.Figures.Add(figure);
        return geometry;
    }

    private static Point PointOnCircle(Point center, double radius, double degrees)
    {
        var radians = degrees * Math.PI / 180.0;
        return new Point(
            center.X + radius * Math.Cos(radians),
            center.Y + radius * Math.Sin(radians));
    }

    private void NewChat_Invoked(KeyboardAccelerator sender, KeyboardAcceleratorInvokedEventArgs args)
    {
        NewChat_Click(sender, new RoutedEventArgs());
        args.Handled = true;
    }

    private void ClearChat_Invoked(KeyboardAccelerator sender, KeyboardAcceleratorInvokedEventArgs args)
    {
        ReleaseCurrentComposerRun();
        _messageHistory.Clear();
        ResetServerContextMeter();
        _conversationId = null;
        _previousResponseId = null;
        Messages.Clear();
        EmptyState.Visibility = Visibility.Visible;
        PromptBox.Text = string.Empty;
        UpdateContextMeter();
        args.Handled = true;
    }

    private void PromptBox_DragOver(object sender, DragEventArgs e)
    {
        if (e.DataView.Contains(StandardDataFormats.StorageItems))
        {
            e.AcceptedOperation = DataPackageOperation.Copy;
        }
    }

    private async void PromptBox_Drop(object sender, DragEventArgs e)
    {
        if (!e.DataView.Contains(StandardDataFormats.StorageItems)) return;
        var items = await e.DataView.GetStorageItemsAsync();
        foreach (var item in items.Take(5))
        {
            if (item is StorageFile file)
            {
                // Let the backend handle empty prompts if needed
            }
        }
    }

    private async void PromptBox_Paste(object sender, TextControlPasteEventArgs e)
    {
        var view = Clipboard.GetContent();
        if (view.Contains(StandardDataFormats.Bitmap))
        {
            e.Handled = true;
            await TryPasteImageAttachmentAsync();
        }
    }

    // ----- Slash commands -----

    private static List<SlashCommand> BuildSlashCommands()
    {
        return new List<SlashCommand>
        {
            new("/chat", "Modalita Chat", "Conversazione normale", SlashAction.ModeChat),
            new("/agente", "Modalita Agente", "Esegui Hermes con tool server", SlashAction.ModeAgent),
            new("/agent", "Modalita Agente", "Alias di /agente", SlashAction.ModeAgent),
            new("/clear", "Pulisci chat", "Svuota la conversazione corrente", SlashAction.Clear),
            new("/new", "Nuova chat", "Inizia una conversazione nuova", SlashAction.Clear),
            new("/health", "Controlla Hermes", "Verifica /health e capabilities", SlashAction.Health),
            new("/server", "Apri Server", "Vai alla dashboard server", SlashAction.OpenServer),
            new("/cron", "Apri Cron", "Vedi automazioni Hermes attive", SlashAction.OpenCron),
            new("/archive", "Apri Archivio", "Cerca conversazioni salvate", SlashAction.OpenArchive),
            new("/settings", "Impostazioni", "Apri pagina settings", SlashAction.OpenSettings),
            new("/about", "Info app", "Versione, profilo, gateway", SlashAction.OpenAbout),
            new("/setup", "Setup Hermes", "Prompt: prepara setup", SlashAction.PromptSetup),
            new("/visual", "Spiegazione visiva", "Richiedi blocchi visuali", SlashAction.PromptVisual),
            new("/research", "Deep research", "Approfondisci con fonti", SlashAction.PromptResearch),
            new("/web", "Ricerca web", "Cerca sul web", SlashAction.PromptWeb),
            new("/image", "Crea immagine", "Prepara richiesta immagine", SlashAction.PromptImage),
            new("/help", "Aiuto", "Mostra comandi disponibili", SlashAction.Help),
        };
    }

    private void BuildSlashPopup()
    {
        if (_slashPopup is not null)
        {
            return;
        }

        var border = new Border
        {
            Background = (Brush)Application.Current.Resources["ElevatedSurfaceBrush"],
            BorderBrush = (Brush)Application.Current.Resources["BorderBrushSoft"],
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(14),
            Padding = new Thickness(6),
            Width = 420,
            MaxHeight = 320
        };

        _slashList = new ListView
        {
            SelectionMode = ListViewSelectionMode.Single,
            Background = new SolidColorBrush(Colors.Transparent),
            ItemTemplate = BuildSlashItemTemplate()
        };
        _slashList.Tapped += (_, _) => ActivateSelectedSlashCommand();

        border.Child = _slashList;

        _slashPopup = new Popup
        {
            Child = border,
            IsLightDismissEnabled = false
        };

        if (XamlRoot is not null)
        {
            _slashPopup.XamlRoot = XamlRoot;
        }
    }

    private DataTemplate BuildSlashItemTemplate()
    {
        var xaml = @"<DataTemplate xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation'>
                        <StackPanel Padding='8,4'>
                            <TextBlock Text='{Binding Display}' Foreground='White' FontWeight='SemiBold' FontSize='13' />
                            <TextBlock Text='{Binding Description}' Foreground='#FFA2ADBF' FontSize='11' TextWrapping='Wrap' />
                        </StackPanel>
                     </DataTemplate>";
        return (DataTemplate)Microsoft.UI.Xaml.Markup.XamlReader.Load(xaml);
    }

    private void PromptBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        var text = PromptBox.Text ?? string.Empty;
        UpdateContextMeter();
        if (string.IsNullOrEmpty(text) || !text.StartsWith("/", StringComparison.Ordinal) || text.Contains('\n'))
        {
            CloseSlashPopup();
            return;
        }

        ShowSlashPopup(text);
    }

    private void ShowSlashPopup(string query)
    {
        if (_slashPopup is null || _slashList is null)
        {
            BuildSlashPopup();
        }
        if (_slashPopup is null || _slashList is null)
        {
            return;
        }

        _slashPopup.XamlRoot = XamlRoot;

        var q = query.ToLowerInvariant();
        var filtered = _slashCommands
            .Where(c => c.Display.StartsWith(q, StringComparison.OrdinalIgnoreCase) || c.Display.Contains(q.TrimStart('/'), StringComparison.OrdinalIgnoreCase) || c.Title.Contains(q.TrimStart('/'), StringComparison.OrdinalIgnoreCase))
            .Take(10)
            .ToList();

        if (filtered.Count == 0)
        {
            CloseSlashPopup();
            return;
        }

        _slashList.ItemsSource = filtered.Select(c => new
        {
            c.Display,
            Description = $"{c.Title} · {c.Description}",
            Command = c
        }).ToList();
        _slashList.SelectedIndex = 0;

        try
        {
            var transform = PromptBox.TransformToVisual(this);
            var prompt = transform.TransformPoint(new Point(0, 0));
            _slashPopup.HorizontalOffset = prompt.X;
            _slashPopup.VerticalOffset = prompt.Y - 330;
        }
        catch
        {
        }

        if (!_slashPopup.IsOpen)
        {
            _slashPopup.IsOpen = true;
        }
    }

    private void CloseSlashPopup()
    {
        if (_slashPopup is not null && _slashPopup.IsOpen)
        {
            _slashPopup.IsOpen = false;
        }
    }

    private void ActivateSelectedSlashCommand()
    {
        if (_slashList?.SelectedItem is null)
        {
            CloseSlashPopup();
            return;
        }

        var dynItem = _slashList.SelectedItem;
        var command = (SlashCommand?)dynItem?.GetType().GetProperty("Command")?.GetValue(dynItem);
        if (command is null)
        {
            CloseSlashPopup();
            return;
        }

        CloseSlashPopup();
        PromptBox.Text = string.Empty;
        ExecuteSlashCommand(command);
    }

    private void ExecuteSlashCommand(SlashCommand command)
    {
        switch (command.Action)
        {
            case SlashAction.ModeChat:
                SetMode("Chat");
                AddAction("Modalita", "Chat attiva.");
                break;
            case SlashAction.ModeAgent:
                SetMode("Agente");
                AddAction("Modalita", "Agente attivo.");
                break;
            case SlashAction.Clear:
                ReleaseCurrentComposerRun();
                _messageHistory.Clear();
                ResetServerContextMeter();
                _conversationId = null;
                _previousResponseId = null;
                Messages.Clear();
                EmptyState.Visibility = Visibility.Visible;
                UpdateContextMeter();
                break;
            case SlashAction.Help:
                var lines = string.Join("\n", _slashCommands
                    .DistinctBy(c => c.Display)
                    .Select(c => $"{c.Display} — {c.Title}"));
                AddAction("Comandi", lines);
                break;
            case SlashAction.PromptSetup:
                PromptBox.Text = "Preparami i passaggi per avviare Hermes Agent API Server su Tailscale/LAN.";
                PromptBox.Focus(FocusState.Programmatic);
                break;
            case SlashAction.PromptVisual:
                PromptBox.Text = "Spiega con blocchi visuali (tabella, diagramma, chart o callout) mantenendo output_text completo.";
                PromptBox.Focus(FocusState.Programmatic);
                break;
            case SlashAction.PromptResearch:
                PromptBox.Text = "Esegui una ricerca approfondita citando fonti e chiedendo conferma prima di uscire dalla LAN/VPN.";
                PromptBox.Focus(FocusState.Programmatic);
                break;
            case SlashAction.PromptWeb:
                PromptBox.Text = "Cerca sul web informazioni aggiornate, chiedendo conferma prima di uscire dalla LAN/VPN.";
                PromptBox.Focus(FocusState.Programmatic);
                break;
            case SlashAction.PromptImage:
                PromptBox.Text = "Prepara una richiesta di generazione immagine, ma chiedi conferma prima di usare tool esterni.";
                PromptBox.Focus(FocusState.Programmatic);
                break;
            case SlashAction.Health:
                PromptBox.Text = "Controlla stato Hermes, modello disponibile e capabilities API.";
                PromptBox.Focus(FocusState.Programmatic);
                break;
            case SlashAction.OpenServer:
                Frame?.Navigate(typeof(ServerPage));
                break;
            case SlashAction.OpenCron:
                Frame?.Navigate(typeof(CronPage));
                break;
            case SlashAction.OpenArchive:
                Frame?.Navigate(typeof(ArchivePage));
                break;
            case SlashAction.OpenSettings:
                Frame?.Navigate(typeof(SettingsPage));
                break;
            case SlashAction.OpenAbout:
                Frame?.Navigate(typeof(AboutPage));
                break;
        }
    }

    // ----- Visual block render preserved -----

    private UIElement RenderVisualBlock(VisualBlockRecord block)
    {
        var panel = new StackPanel { Spacing = 8 };
        if (!string.IsNullOrWhiteSpace(block.Title))
        {
            panel.Children.Add(new TextBlock
            {
                Text = block.Title,
                Foreground = new SolidColorBrush(Colors.White),
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                FontSize = 15
            });
        }

        panel.Children.Add(block.Type.ToLowerInvariant() switch
        {
            "markdown" => RenderMarkdown(block.Text ?? string.Empty),
            "code" => RenderCode(block.Language ?? "plaintext", block.Code ?? string.Empty, block.Filename),
            "table" => RenderTable(block),
            "chart" => RenderChart(block),
            "diagram" => RenderDiagram(block),
            "image_gallery" => RenderGallery(block),
            "media_file" => RenderMediaFile(block),
            "callout" => RenderCallout(block),
            "unknown_block" => RenderCode("json", block.RawJson ?? "{}", "hermes-unknown-block.json"),
            _ => new TextBlock { Text = block.Caption ?? "Blocco visuale non supportato.", Foreground = (Brush)Application.Current.Resources["MutedTextBrush"] }
        });

        if (!string.IsNullOrWhiteSpace(block.Caption))
        {
            panel.Children.Add(new TextBlock
            {
                Text = block.Caption,
                Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
                FontSize = 12,
                TextWrapping = TextWrapping.WrapWholeWords
            });
        }

        return new Border
        {
            Padding = new Thickness(12),
            Margin = new Thickness(0, 4, 0, 0),
            Background = (Brush)Application.Current.Resources["SurfaceBrush"],
            BorderBrush = (Brush)Application.Current.Resources["BorderBrushSoft"],
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(14),
            Child = panel
        };
    }

    private static StackPanel RenderMarkdown(string markdown)
    {
        var panel = new StackPanel { Spacing = 5 };
        foreach (var raw in markdown.Replace("\r\n", "\n").Split('\n'))
        {
            var line = raw.TrimEnd();
            if (string.IsNullOrWhiteSpace(line))
            {
                continue;
            }

            var text = line.TrimStart('#', '-', '*', ' ');
            var block = new TextBlock
            {
                Text = line.StartsWith("- ", StringComparison.Ordinal) || line.StartsWith("* ", StringComparison.Ordinal)
                    ? $"• {text}"
                    : text,
                Foreground = new SolidColorBrush(Colors.White),
                TextWrapping = TextWrapping.WrapWholeWords
            };

            if (line.StartsWith("# ", StringComparison.Ordinal))
            {
                block.FontSize = 20;
                block.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
            }
            else if (line.StartsWith("## ", StringComparison.Ordinal))
            {
                block.FontSize = 17;
                block.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
            }
            else if (line.StartsWith("### ", StringComparison.Ordinal))
            {
                block.FontSize = 15;
                block.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
            }

            panel.Children.Add(block);
        }

        return panel;
    }

    private static UIElement RenderCode(string language, string code, string? filename)
    {
        var panel = new StackPanel { Spacing = 8 };
        var header = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        header.Children.Add(new TextBlock
        {
            Text = string.IsNullOrWhiteSpace(filename) ? language : $"{filename} · {language}",
            Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
            FontSize = 12
        });
        var copy = new Button
        {
            Content = "Copia",
            Padding = new Thickness(10, 4, 10, 4),
            HorizontalAlignment = HorizontalAlignment.Right
        };
        copy.Click += (_, _) =>
        {
            var package = new DataPackage();
            package.SetText(code);
            Clipboard.SetContent(package);
        };
        header.Children.Add(copy);
        panel.Children.Add(header);
        panel.Children.Add(new ScrollViewer
        {
            HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            Content = new TextBlock
            {
                Text = code,
                FontFamily = new Microsoft.UI.Xaml.Media.FontFamily("Consolas"),
                Foreground = new SolidColorBrush(Colors.White),
                TextWrapping = TextWrapping.NoWrap
            }
        });
        return panel;
    }

    private static UIElement RenderTable(VisualBlockRecord block)
    {
        var grid = new Grid { RowSpacing = 1, ColumnSpacing = 1 };
        foreach (var _ in block.Columns)
        {
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        }

        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        for (var columnIndex = 0; columnIndex < block.Columns.Count; columnIndex++)
        {
            AddTableCell(grid, block.Columns[columnIndex].Label, 0, columnIndex, true);
        }

        for (var rowIndex = 0; rowIndex < block.Rows.Count; rowIndex++)
        {
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            for (var columnIndex = 0; columnIndex < block.Columns.Count; columnIndex++)
            {
                var column = block.Columns[columnIndex];
                var value = block.Rows[rowIndex].TryGetValue(column.Key, out var cell)
                    ? VisualBlockParser.JsonValueToText(cell)
                    : string.Empty;
                AddTableCell(grid, value, rowIndex + 1, columnIndex, false);
            }
        }

        return new ScrollViewer
        {
            HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            Content = grid
        };
    }

    private static void AddTableCell(Grid grid, string text, int row, int column, bool header)
    {
        var border = new Border
        {
            Padding = new Thickness(8, 6, 8, 6),
            Background = header
                ? (Brush)Application.Current.Resources["ElevatedSurfaceBrush"]
                : (Brush)Application.Current.Resources["ComposerBrush"],
            Child = new TextBlock
            {
                Text = text,
                Foreground = new SolidColorBrush(Colors.White),
                FontWeight = header ? Microsoft.UI.Text.FontWeights.SemiBold : Microsoft.UI.Text.FontWeights.Normal,
                TextWrapping = TextWrapping.NoWrap
            }
        };
        Grid.SetRow(border, row);
        Grid.SetColumn(border, column);
        grid.Children.Add(border);
    }

    private static UIElement RenderChart(VisualBlockRecord block)
    {
        var panel = new StackPanel { Spacing = 8 };
        panel.Children.Add(new TextBlock
        {
            Text = block.Summary ?? string.Empty,
            Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
            TextWrapping = TextWrapping.WrapWholeWords
        });

        var allPoints = block.Series.SelectMany(series => series.Points).ToList();
        if (allPoints.Count == 0)
        {
            return panel;
        }

        var max = Math.Max(1, allPoints.Max(point => point.Y));
        var chart = new StackPanel { Spacing = 6 };
        foreach (var point in block.Series.First().Points.Take(12))
        {
            var label = VisualBlockParser.JsonValueToText(point.X);
            var width = Math.Max(6, 420 * point.Y / max);
            var row = new Grid { ColumnSpacing = 8 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(96) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.Children.Add(new TextBlock { Text = label, Foreground = new SolidColorBrush(Colors.White), FontSize = 12 });
            var bar = new Border
            {
                Width = width,
                Height = 12,
                CornerRadius = new CornerRadius(6),
                Background = (Brush)Application.Current.Resources["AccentBrush"],
                HorizontalAlignment = HorizontalAlignment.Left
            };
            Grid.SetColumn(bar, 1);
            row.Children.Add(bar);
            var value = new TextBlock { Text = $"{point.Y:0.##}{block.Unit}", Foreground = (Brush)Application.Current.Resources["MutedTextBrush"], FontSize = 12 };
            Grid.SetColumn(value, 2);
            row.Children.Add(value);
            chart.Children.Add(row);
        }

        panel.Children.Add(chart);
        return panel;
    }

    private static BitmapImage CreateBoundedBitmap(Uri uri, int decodePixelWidth)
    {
        var bitmap = new BitmapImage { DecodePixelWidth = decodePixelWidth };
        bitmap.UriSource = uri;
        return bitmap;
    }

    private UIElement RenderDiagram(VisualBlockRecord block)
    {
        if (IsSafeMediaUrl(block.RenderedMediaUrl))
        {
            return new Image
            {
                Source = CreateBoundedBitmap(ResolveMediaUri(block.RenderedMediaUrl!), 720),
                MaxHeight = 280,
                MaxWidth = 720,
                Stretch = Stretch.Uniform
            };
        }

        return RenderCode("mermaid", block.Source ?? string.Empty, "diagram.mmd");
    }

    private UIElement RenderGallery(VisualBlockRecord block)
    {
        var panel = new StackPanel { Spacing = 8 };
        foreach (var image in block.Images.Take(12))
        {
            if (!IsSafeMediaUrl(image.MediaUrl, allowExternalImage: true))
            {
                panel.Children.Add(new TextBlock
                {
                    Text = $"{image.Alt}: media non proxy rifiutato.",
                    Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
                    TextWrapping = TextWrapping.WrapWholeWords
                });
                continue;
            }

            panel.Children.Add(new Image
            {
                Source = CreateBoundedBitmap(ResolveMediaUri(image.MediaUrl), 720),
                MaxHeight = 220,
                MaxWidth = 720,
                Stretch = Stretch.Uniform
            });
            if (!string.IsNullOrWhiteSpace(image.Caption))
            {
                panel.Children.Add(new TextBlock { Text = image.Caption, Foreground = (Brush)Application.Current.Resources["MutedTextBrush"], FontSize = 12 });
            }
        }

        return panel;
    }

    private static UIElement RenderMediaFile(VisualBlockRecord block)
    {
        var panel = new StackPanel { Spacing = 10 };
        var isLocalAttachment = !string.IsNullOrWhiteSpace(block.LocalDataUrl);
        var allowExternalImage = block.MediaKind == "image";
        var safeMedia = IsSafeMediaUrl(block.MediaUrl, allowExternalImage, allowExternalMedia: true);
        var previewUrl = !isLocalAttachment && block.MediaKind == "image" && safeMedia
            ? block.MediaUrl
            : !isLocalAttachment && IsSafeMediaUrl(block.ThumbnailUrl, allowExternalImage) ? block.ThumbnailUrl : null;

        if (isLocalAttachment && block.MediaKind == "image" && BitmapFromDataUrl(block.LocalDataUrl!) is { } localBitmap)
        {
            panel.Children.Add(new Image
            {
                Source = localBitmap,
                MaxHeight = 360,
                MaxWidth = 720,
                Stretch = Stretch.Uniform
            });
        }
        else if (!string.IsNullOrWhiteSpace(previewUrl))
        {
            panel.Children.Add(new Image
            {
                Source = CreateBoundedBitmap(ResolveMediaUri(previewUrl), 720),
                MaxHeight = 260,
                MaxWidth = 720,
                Stretch = Stretch.Uniform
            });
        }

        var meta = new StackPanel { Spacing = 6 };
        meta.Children.Add(new TextBlock
        {
            Text = FirstNonBlank(block.Filename, block.Title, block.Alt, "Media Hermes"),
            Foreground = new SolidColorBrush(Colors.White),
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            TextWrapping = TextWrapping.WrapWholeWords
        });
        meta.Children.Add(new TextBlock
        {
            Text = string.Join(" · ", new[]
            {
                FirstNonBlank(block.MediaKind, "media"),
                block.MimeType,
                FormatMediaBytes(block.SizeBytes),
                FormatMediaDuration(block.DurationMs)
            }.Where(value => !string.IsNullOrWhiteSpace(value))),
            Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
            FontSize = 12,
            TextWrapping = TextWrapping.WrapWholeWords
        });

        if (isLocalAttachment)
        {
            meta.Children.Add(new TextBlock
            {
                Text = "Condiviso con Hermes.",
                Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
                FontSize = 12
            });
        }
        else if (!safeMedia)
        {
            meta.Children.Add(new TextBlock
            {
                Text = "media non proxy rifiutato.",
                Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
                FontSize = 12,
                TextWrapping = TextWrapping.WrapWholeWords
            });
        }

        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var open = new Button
        {
            Content = "Apri",
            IsEnabled = safeMedia,
            Padding = new Thickness(12, 4, 12, 4)
        };
        open.Click += async (_, _) =>
        {
            if (block.MediaUrl is { Length: > 0 } value && IsSafeMediaUrl(value, allowExternalMedia: true))
            {
                await Launcher.LaunchUriAsync(ResolveMediaUri(value));
            }
        };
        actions.Children.Add(open);

        var download = new Button
        {
            Content = "Scarica",
            IsEnabled = safeMedia,
            Padding = new Thickness(12, 4, 12, 4)
        };
        download.Click += async (_, _) =>
        {
            if (block.MediaUrl is not { Length: > 0 } value || !IsSafeMediaUrl(value, allowExternalMedia: true)) return;
            
            var picker = new FileSavePicker();
            picker.SuggestedFileName = block.Filename ?? "download";
            var ext = System.IO.Path.GetExtension(picker.SuggestedFileName);
            if (string.IsNullOrWhiteSpace(ext))
            {
                picker.FileTypeChoices.Add("File", new List<string> { ".bin" });
            }
            else
            {
                picker.FileTypeChoices.Add($"File {ext}", new List<string> { ext });
            }

            if (App.MainWindow is not null)
            {
                InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.MainWindow));
            }

            var file = await picker.PickSaveFileAsync();
            if (file == null) return;

            download.IsEnabled = false;
            download.Content = "Scaricamento...";
            try
            {
                using var httpClient = new System.Net.Http.HttpClient();
                var apiKey = GatewayCredentialStore.LoadSecret();
                if (string.IsNullOrWhiteSpace(apiKey)) apiKey = GatewayCredentialStore.DefaultApiKey;
                var uri = ResolveMediaUri(value);
                Exception? lastError = null;
                foreach (var candidateUri in MediaDownloadUriCandidates(uri))
                {
                    try
                    {
                        using var request = new System.Net.Http.HttpRequestMessage(System.Net.Http.HttpMethod.Get, candidateUri);
                        request.Headers.TryAddWithoutValidation("Accept", "*/*");
                        request.Headers.TryAddWithoutValidation("User-Agent", "HermesHub-Windows");
                        if (IsHermesMediaUri(candidateUri) && !string.IsNullOrWhiteSpace(apiKey))
                        {
                            request.Headers.TryAddWithoutValidation("Authorization", $"Bearer {apiKey}");
                        }

                        using var response = await httpClient.SendAsync(request, System.Net.Http.HttpCompletionOption.ResponseHeadersRead);
                        if (!response.IsSuccessStatusCode)
                        {
                            lastError = new System.Net.Http.HttpRequestException($"HTTP {(int)response.StatusCode}");
                            continue;
                        }

                        await using var stream = await response.Content.ReadAsStreamAsync();
                        using var fileStream = await file.OpenStreamForWriteAsync();
                        await stream.CopyToAsync(fileStream);
                        lastError = null;
                        break;
                    }
                    catch (Exception ex)
                    {
                        lastError = ex;
                    }
                }
                if (lastError is not null) throw lastError;
                download.Content = "Scaricato";
            }
            catch (Exception)
            {
                download.Content = "Errore";
            }
            finally
            {
                await Task.Delay(2000);
                download.Content = "Scarica";
                download.IsEnabled = true;
            }
        };
        actions.Children.Add(download);

        var copy = new Button
        {
            Content = "Copia link",
            IsEnabled = safeMedia,
            Padding = new Thickness(12, 4, 12, 4)
        };
        copy.Click += (_, _) =>
        {
            if (block.MediaUrl is not { Length: > 0 } value || !IsSafeMediaUrl(value, allowExternalMedia: true))
            {
                return;
            }

            var package = new DataPackage();
            package.SetText(ResolveMediaUri(value).ToString());
            Clipboard.SetContent(package);
        };
        actions.Children.Add(copy);
        if (safeMedia)
        {
            meta.Children.Add(actions);
        }

        panel.Children.Add(new Border
        {
            Padding = new Thickness(10),
            Background = (Brush)Application.Current.Resources["ComposerBrush"],
            CornerRadius = new CornerRadius(10),
            Child = meta
        });

        return panel;
    }

    private static string FirstNonBlank(params string?[] values)
    {
        return values.FirstOrDefault(value => !string.IsNullOrWhiteSpace(value)) ?? string.Empty;
    }

    private static string FormatMediaBytes(long? value)
    {
        if (value is null or <= 0)
        {
            return string.Empty;
        }

        var amount = (double)value.Value;
        string[] units = ["B", "KB", "MB", "GB"];
        var unit = 0;
        while (amount >= 1024 && unit < units.Length - 1)
        {
            amount /= 1024;
            unit++;
        }

        return unit == 0 ? $"{value.Value} B" : $"{amount:0.0} {units[unit]}";
    }

    private static string FormatMediaDuration(long? value)
    {
        if (value is null or <= 0)
        {
            return string.Empty;
        }

        var totalSeconds = value.Value / 1000;
        var minutes = totalSeconds / 60;
        var seconds = totalSeconds % 60;
        return minutes > 0 ? $"{minutes}m {seconds}s" : $"{seconds}s";
    }

    private static UIElement RenderCallout(VisualBlockRecord block)
    {
        var accent = block.Variant switch
        {
            "warning" => Colors.Goldenrod,
            "error" => Colors.IndianRed,
            "success" => Colors.MediumSeaGreen,
            _ => Colors.DodgerBlue
        };
        return new Border
        {
            BorderBrush = new SolidColorBrush(accent),
            BorderThickness = new Thickness(3, 0, 0, 0),
            Padding = new Thickness(10, 4, 0, 4),
            Child = RenderMarkdown(block.Text ?? string.Empty)
        };
    }

    private static bool IsSafeMediaUrl(string? value, bool allowExternalImage = false, bool allowExternalMedia = false)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return false;
        }

        if (value.StartsWith("/v1/media/", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri))
        {
            return false;
        }

        if (allowExternalImage && uri.Scheme == "https" && !string.IsNullOrWhiteSpace(uri.Host))
        {
            return true;
        }

        if (allowExternalMedia && uri.Scheme == "https" && !string.IsNullOrWhiteSpace(uri.Host))
        {
            return true;
        }

        if (uri.Scheme is not ("http" or "https") || !uri.AbsolutePath.StartsWith("/v1/media/", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        return uri.Host.Equals(new Uri(GatewayService.HermesRoot(AppSettingsStore.Load())).Host, StringComparison.OrdinalIgnoreCase);
    }

    private static Uri ResolveMediaUri(string value)
    {
        var settings = AppSettingsStore.Load();
        var uri = Uri.TryCreate(value, UriKind.Absolute, out var parsed)
            ? parsed
            : new Uri($"{GatewayService.HermesRoot(settings).TrimEnd('/')}{value}");

        var apiKey = GatewayCredentialStore.LoadSecret();
        if (string.IsNullOrWhiteSpace(apiKey)) apiKey = GatewayCredentialStore.DefaultApiKey;
        if (uri.AbsolutePath.StartsWith("/v1/media/", StringComparison.OrdinalIgnoreCase) && !string.IsNullOrWhiteSpace(apiKey))
        {
            var builder = new UriBuilder(uri);
            var query = builder.Query;
            if (query.Length > 1) query = query.Substring(1) + "&";
            else query = "";
            query += $"hub_token={Uri.EscapeDataString(apiKey)}";
            builder.Query = query;
            return builder.Uri;
        }

        return uri;
    }

    private static bool IsHermesMediaUri(Uri uri)
    {
        return uri.Scheme is "http" or "https" &&
               uri.AbsolutePath.StartsWith("/v1/media/", StringComparison.OrdinalIgnoreCase);
    }

    private static IEnumerable<Uri> MediaDownloadUriCandidates(Uri uri)
    {
        yield return uri;

        if (!IsHermesMediaUri(uri) || uri.Port != 8642)
        {
            yield break;
        }

        var suffix = uri.PathAndQuery;
        var roots = new[]
        {
            "http://hermes:8642",
            "http://100.94.223.14:8642",
            "http://hermes.local:8642",
            "http://hermes-hub:8642",
            "http://hermeshub:8642",
            "http://home-server:8642",
            "http://server:8642",
            "http://100.105.46.6:8642"
        };
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { uri.ToString() };
        foreach (var root in roots)
        {
            if (Uri.TryCreate(root.TrimEnd('/') + suffix, UriKind.Absolute, out var candidate) &&
                seen.Add(candidate.ToString()))
            {
                yield return candidate;
            }
        }
    }
}

public sealed class MessageViewModel
{
    public MessageViewModel()
    {
    }

    public MessageViewModel(UIElement element)
    {
        Element = element;
    }

    public UIElement? Element { get; set; }
}

internal enum SlashAction
{
    ModeChat,
    ModeAgent,
    Clear,
    Help,
    Health,
    OpenServer,
    OpenCron,
    OpenArchive,
    OpenVoice,
    OpenSettings,
    OpenAbout,
    PromptSetup,
    PromptVisual,
    PromptResearch,
    PromptWeb,
    PromptImage
}

internal sealed record SlashCommand(string Display, string Title, string Description, SlashAction Action);
