using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Navigation;
using NemoclawChat_Windows.Services;
using System.Collections.ObjectModel;
using Windows.ApplicationModel.DataTransfer;
using Windows.Foundation;
using Windows.Storage.Pickers;
using Windows.Storage;
using Windows.System;
using WinRT.Interop;

namespace NemoclawChat_Windows.Pages;

public sealed partial class HomePage : Page
{
    public ObservableCollection<MessageViewModel> Messages { get; } = [];
    private string _mode = "Chat";
    private string? _conversationId;
    private string? _previousResponseId;
    private readonly List<ChatMessageRecord> _messageHistory = [];
    private volatile bool _isSending;
    private StreamingBubble? _currentStreamingBubble;

    private Popup? _slashPopup;
    private ListView? _slashList;
    private readonly List<SlashCommand> _slashCommands;

    public HomePage()
    {
        InitializeComponent();
        _slashCommands = BuildSlashCommands();
        PromptBox.TextChanged += PromptBox_TextChanged;
        Loaded += HomePage_Loaded;
        Unloaded += HomePage_Unloaded;
    }

    private void HomePage_Unloaded(object sender, RoutedEventArgs e)
    {
        try
        {
            _currentStreamingBubble?.StopShimmer();
            _currentStreamingBubble = null;
            PromptBox.TextChanged -= PromptBox_TextChanged;
            Loaded -= HomePage_Loaded;
            Unloaded -= HomePage_Unloaded;
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
    }

    private async void Send_Click(object sender, RoutedEventArgs e)
    {
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

        AddAction("File task", $"File selezionato: {file.Name}");
        PromptBox.Text = AppendPrompt($"Usa questo file come contesto per il task: {file.Path}");
    }

    private async void CaptureScreenshot_Click(object sender, RoutedEventArgs e)
    {
        var launched = await Launcher.LaunchUriAsync(new Uri("ms-screenclip:"));
        AddAction(
            "Screenshot",
            launched
                ? "Strumento cattura di Windows aperto. Incolla o salva lo screenshot, poi allegalo al task."
                : "Impossibile aprire lo strumento cattura. Usa una cattura manuale e allega il file al task.");
        PromptBox.Text = AppendPrompt("Usa uno screenshot come contesto visivo per capire lo stato dell'app o del server.");
    }

    private async void TakePhoto_Click(object sender, RoutedEventArgs e)
    {
        var launched = await Launcher.LaunchUriAsync(new Uri("microsoft.windows.camera:"));
        AddAction(
            "Foto",
            launched
                ? "App Fotocamera aperta. Salva la foto e allegala al task quando pronta."
                : "Impossibile aprire Fotocamera. Scatta o seleziona una foto manualmente e allegala al task.");
        PromptBox.Text = AppendPrompt("Acquisisci una foto e usala come allegato per la conversazione.");
    }

    private void VoiceNote_Click(object sender, RoutedEventArgs e)
    {
        AddAction("Voce", "Nota vocale preparata. Usa dettatura Windows o scrivi qui il testo da inviare.");
        PromptBox.Text = AppendPrompt("Trascrivi questa nota vocale e usala come contesto: ");
        PromptBox.Focus(FocusState.Programmatic);
    }

    private void CreateImage_Click(object sender, RoutedEventArgs e)
    {
        AddAction("Immagine", "Generazione immagine richiedera' tool Hermes dedicato e conferma prima di chiamate esterne.");
        PromptBox.Text = AppendPrompt("Prepara una richiesta di generazione immagine, ma chiedi conferma prima di usare tool esterni.");
    }

    private void DeepResearch_Click(object sender, RoutedEventArgs e)
    {
        AddAction("Deep Research", "Ricerca approfondita abilitera' rete solo dopo approvazione esplicita.");
        PromptBox.Text = AppendPrompt("Esegui una ricerca approfondita e cita fonti, usando rete solo dopo approvazione.");
    }

    private void WebSearch_Click(object sender, RoutedEventArgs e)
    {
        AddAction("Web", "Ricerca web marcata come azione autorizzabile: nessuna rete fuori LAN/VPN senza conferma.");
        PromptBox.Text = AppendPrompt("Cerca sul web informazioni aggiornate, chiedendo conferma prima di uscire dalla LAN/VPN.");
    }

    private void VisualExplanation_Click(object sender, RoutedEventArgs e)
    {
        AddAction("Visuale", "Spiegazione visiva richiesta: Hermes usera' blocchi statici sicuri se disponibili.");
        PromptBox.Text = AppendPrompt("Spiega anche con blocchi visuali se utile: tabella, diagramma, chart o callout. Mantieni output_text completo.");
    }

    private void Projects_Click(object sender, RoutedEventArgs e)
    {
        AddAction("Workspace", "Workspace/progetti saranno collegati ai Jobs Hermes con audit trail.");
        PromptBox.Text = AppendPrompt("Lavora sul workspace/progetto selezionato e mostra piano prima di modificare file.");
    }

    private void SetModeChat_Click(object sender, RoutedEventArgs e)
    {
        SetMode("Chat");
        AddAction("Modalita", "Chat attiva: messaggi normali, nessun task agente automatico.");
    }

    private void SetModeAgent_Click(object sender, RoutedEventArgs e)
    {
        SetMode("Agente");
        AddAction("Modalita", "Agente attivo: usa Hermes Runs/Jobs se disponibili, altrimenti fallback locale.");
    }

    private void ToggleMode_Click(object sender, RoutedEventArgs e)
    {
        SetMode(_mode == "Agente" ? "Chat" : "Agente");
    }

    private void NewChat_Click(object sender, RoutedEventArgs e)
    {
        _messageHistory.Clear();
        _conversationId = null;
        _previousResponseId = null;
        Messages.Clear();
        EmptyState.Visibility = Visibility.Visible;
        PromptBox.Text = string.Empty;
        PromptBox.Focus(FocusState.Programmatic);
    }

    private void SetMode(string mode)
    {
        _mode = mode;
        ModeBadge.Text = _mode;
        ModeToggleIcon.Glyph = _mode == "Agente" ? "" : "";
    }

    private async Task SendCurrentPromptAsync()
    {
        // Atomic guard: set flag prima di qualsiasi await. UI thread single-threaded ma
        // multipli entry-point (Send_Click, PromptBox_KeyDown, slash command activate)
        // possono entrare back-to-back prima che Send button si disabiliti.
        if (_isSending)
        {
            return;
        }
        _isSending = true;
        SendButton.IsEnabled = false;

        var prompt = PromptBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(prompt))
        {
            _isSending = false;
            SendButton.IsEnabled = true;
            return;
        }

        CloseSlashPopup();

        StreamingBubble? bubble = null;

        try
        {
            EmptyState.Visibility = Visibility.Collapsed;
            AddBubble("Tu", prompt, "UserBubbleBrush", HorizontalAlignment.Right);
            _messageHistory.Add(new ChatMessageRecord("Tu", prompt, DateTimeOffset.Now));
            PromptBox.Text = string.Empty;
            var initialSave = ChatArchiveStore.SaveSnapshot(_conversationId, _mode, prompt, _messageHistory, "Hermes in corso", _previousResponseId);
            _conversationId = initialSave.Id;
            _previousResponseId = initialSave.PreviousResponseId;

            var settings = AppSettingsStore.Load();
            bubble = CreateStreamingAssistantBubble();
            _currentStreamingBubble = bubble;

            IReadOnlyList<VisualBlockRecord>? finalBlocks = null;
            int? finalBlocksVersion = null;
            string? finalResponseId = null;
            string finalText = string.Empty;
            string finalThinking = string.Empty;
            string statusMessage = "Risposta ricevuta da Hermes.";
            string source = "Hermes";
            bool usedFallback = false;
            string? streamError = null;
            var lastCheckpointAt = DateTimeOffset.MinValue;

            await foreach (var ev in ChatStreamClient.StreamChatAsync(settings, _mode, prompt, _messageHistory, _conversationId, _previousResponseId))
            {
                switch (ev)
                {
                    case StreamTextDelta td:
                        bubble.AppendText(td.Delta);
                        finalText += td.Delta;
                        break;
                    case StreamThinkingDelta th:
                        bubble.AppendThinking(th.Delta);
                        finalThinking += th.Delta;
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
                    case StreamVisualBlocks vb:
                        finalBlocks = vb.Blocks;
                        finalBlocksVersion = vb.Version;
                        bubble.SetVisualBlocks(vb.Blocks, RenderVisualBlock);
                        break;
                    case StreamStatus ss:
                        statusMessage = ss.Message;
                        break;
                    case StreamDone done:
                        bubble.Complete(done.Stats);
                        if (string.IsNullOrEmpty(finalText) && !string.IsNullOrEmpty(done.AccumulatedText))
                        {
                            finalText = done.AccumulatedText;
                        }
                        if (string.IsNullOrEmpty(finalThinking) && !string.IsNullOrEmpty(done.AccumulatedThinking))
                        {
                            finalThinking = done.AccumulatedThinking;
                        }
                        break;
                    case StreamError se:
                        streamError = se.Message;
                        break;
                }

                if ((DateTimeOffset.Now - lastCheckpointAt).TotalSeconds >= 2 &&
                    (!string.IsNullOrWhiteSpace(finalText) || finalBlocks is { Count: > 0 }))
                {
                    lastCheckpointAt = DateTimeOffset.Now;
                    var partialMessages = _messageHistory
                        .Concat(new[]
                        {
                            new ChatMessageRecord(
                                "Hermes",
                                string.IsNullOrWhiteSpace(finalText) ? "Hermes sta lavorando..." : finalText,
                                DateTimeOffset.Now,
                                finalBlocksVersion,
                                finalBlocks?.ToList())
                        })
                        .ToList();
                    var checkpoint = ChatArchiveStore.SaveSnapshot(_conversationId, _mode, prompt, partialMessages, "Hermes in corso", finalResponseId ?? _previousResponseId);
                    _conversationId = checkpoint.Id;
                    _previousResponseId = checkpoint.PreviousResponseId;
                }
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

            _messageHistory.Add(new ChatMessageRecord("Hermes", finalText, DateTimeOffset.Now, finalBlocksVersion, finalBlocks?.ToList()));
            var saved = ChatArchiveStore.SaveSnapshot(_conversationId, _mode, prompt, _messageHistory, source, finalResponseId ?? _previousResponseId);
            _conversationId = saved.Id;
            _previousResponseId = saved.PreviousResponseId;

            if (usedFallback || source.Contains("Errore", StringComparison.OrdinalIgnoreCase))
            {
                AddAction("Stato", statusMessage);
            }
        }
        catch (Exception ex)
        {
            ShowError($"Stream interrotto: {ex.Message}");
            bubble?.AppendText($"\n[errore stream] {ex.Message}");
            bubble?.Complete(new ChatStreamStats(null, null, null, null, null));
            var interruptedMessages = _messageHistory
                .Concat(new[] { new ChatMessageRecord("Stato", $"Stream interrotto: {ex.Message}", DateTimeOffset.Now) })
                .ToList();
            var saved = ChatArchiveStore.SaveSnapshot(_conversationId, _mode, prompt, interruptedMessages, "Errore Hermes", _previousResponseId);
            _conversationId = saved.Id;
            _previousResponseId = saved.PreviousResponseId;
        }
        finally
        {
            bubble?.StopShimmer();
            if (ReferenceEquals(_currentStreamingBubble, bubble))
            {
                _currentStreamingBubble = null;
            }
            _isSending = false;
            SendButton.IsEnabled = true;
        }
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

        _conversationId = conversation.Id;
        _previousResponseId = conversation.PreviousResponseId;
        EmptyState.Visibility = Visibility.Collapsed;
        Messages.Clear();
        _messageHistory.Clear();

        foreach (var message in conversation.Messages)
        {
            _messageHistory.Add(message);
            AddBubble(
                message.Author,
                message.Text,
                message.Author == "Tu" ? "UserBubbleBrush" : "AssistantBubbleBrush",
                message.Author == "Tu" ? HorizontalAlignment.Right : HorizontalAlignment.Left,
                message.VisualBlocks);
        }
    }

    private void AddBubble(
        string author,
        string text,
        string brushKey,
        HorizontalAlignment alignment,
        IReadOnlyList<VisualBlockRecord>? visualBlocks = null)
    {
        var content = new StackPanel { Spacing = 8 };
        content.Children.Add(new TextBlock
        {
            Text = author,
            FontSize = 12,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            Foreground = new SolidColorBrush(Colors.White)
        });
        if (string.Equals(author, "Hermes", StringComparison.OrdinalIgnoreCase))
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

        var bubble = new Border
        {
            MaxWidth = 720,
            Padding = new Thickness(18, 14, 18, 14),
            CornerRadius = new CornerRadius(20),
            Background = (Brush)Application.Current.Resources[brushKey],
            BorderBrush = (Brush)Application.Current.Resources["BorderBrushSoft"],
            BorderThickness = new Thickness(1),
            HorizontalAlignment = alignment,
            Child = content
        };
        bubble.ContextFlyout = BuildCopyFlyout(text);

        Messages.Add(new MessageViewModel(bubble));
        _ = MessagesScroll.ChangeView(null, MessagesScroll.ScrollableHeight, null);
    }

    private StreamingBubble CreateStreamingAssistantBubble()
    {
        var bubble = new StreamingBubble(this, element => Messages.Add(new MessageViewModel(element)), MessagesScroll);
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

    private void NewChat_Invoked(KeyboardAccelerator sender, KeyboardAcceleratorInvokedEventArgs args)
    {
        NewChat_Click(sender, new RoutedEventArgs());
        args.Handled = true;
    }

    private void ClearChat_Invoked(KeyboardAccelerator sender, KeyboardAcceleratorInvokedEventArgs args)
    {
        _messageHistory.Clear();
        _conversationId = null;
        _previousResponseId = null;
        Messages.Clear();
        EmptyState.Visibility = Visibility.Visible;
        PromptBox.Text = string.Empty;
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
                PromptBox.Text = AppendPrompt($"File allegato: {file.Path}");
            }
        }
    }

    private void PromptBox_Paste(object sender, TextControlPasteEventArgs e)
    {
        var view = Clipboard.GetContent();
        if (view.Contains(StandardDataFormats.Bitmap))
        {
            e.Handled = true;
            ShowError("Paste immagine non ancora supportato. Salva file e usa allega.");
        }
    }

    // ----- Slash commands -----

    private static List<SlashCommand> BuildSlashCommands()
    {
        return new List<SlashCommand>
        {
            new("/chat", "Modalita Chat", "Conversazione normale", SlashAction.ModeChat),
            new("/agente", "Modalita Agente", "Esegui Runs/Jobs Hermes", SlashAction.ModeAgent),
            new("/agent", "Modalita Agente", "Alias di /agente", SlashAction.ModeAgent),
            new("/clear", "Pulisci chat", "Svuota la conversazione corrente", SlashAction.Clear),
            new("/new", "Nuova chat", "Inizia una conversazione nuova", SlashAction.Clear),
            new("/health", "Controlla Hermes", "Verifica /health e capabilities", SlashAction.Health),
            new("/server", "Apri Server", "Vai alla dashboard server", SlashAction.OpenServer),
            new("/runs", "Apri Operator/Runs", "Apri probe API Hermes", SlashAction.OpenOperator),
            new("/archive", "Apri Archivio", "Cerca conversazioni salvate", SlashAction.OpenArchive),
            new("/tasks", "Apri Task agente", "Coda jobs Hermes", SlashAction.OpenTasks),
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
                _messageHistory.Clear();
                _conversationId = null;
                _previousResponseId = null;
                Messages.Clear();
                EmptyState.Visibility = Visibility.Visible;
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
            case SlashAction.OpenOperator:
                Frame?.Navigate(typeof(OperatorPage));
                break;
            case SlashAction.OpenArchive:
                Frame?.Navigate(typeof(ArchivePage));
                break;
            case SlashAction.OpenTasks:
                Frame?.Navigate(typeof(TasksPage));
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
        var allowExternalImage = block.MediaKind == "image";
        var safeMedia = IsSafeMediaUrl(block.MediaUrl, allowExternalImage);
        var previewUrl = block.MediaKind == "image" && safeMedia
            ? block.MediaUrl
            : IsSafeMediaUrl(block.ThumbnailUrl, allowExternalImage) ? block.ThumbnailUrl : null;

        if (!string.IsNullOrWhiteSpace(previewUrl))
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

        if (!safeMedia)
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
            if (block.MediaUrl is { Length: > 0 } value && IsSafeMediaUrl(value))
            {
                await Launcher.LaunchUriAsync(ResolveMediaUri(value));
            }
        };
        actions.Children.Add(open);

        var copy = new Button
        {
            Content = "Copia link",
            IsEnabled = safeMedia,
            Padding = new Thickness(12, 4, 12, 4)
        };
        copy.Click += (_, _) =>
        {
            if (block.MediaUrl is not { Length: > 0 } value || !IsSafeMediaUrl(value))
            {
                return;
            }

            var package = new DataPackage();
            package.SetText(ResolveMediaUri(value).ToString());
            Clipboard.SetContent(package);
        };
        actions.Children.Add(copy);
        meta.Children.Add(actions);

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

    private static bool IsSafeMediaUrl(string? value, bool allowExternalImage = false)
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

        if (uri.Scheme is not ("http" or "https") || !uri.AbsolutePath.StartsWith("/v1/media/", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        return uri.Host.Equals(new Uri(GatewayService.HermesRoot(AppSettingsStore.Load())).Host, StringComparison.OrdinalIgnoreCase);
    }

    private static Uri ResolveMediaUri(string value)
    {
        return Uri.TryCreate(value, UriKind.Absolute, out var uri)
            ? uri
            : new Uri($"{GatewayService.HermesRoot(AppSettingsStore.Load()).TrimEnd('/')}{value}");
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
    OpenOperator,
    OpenArchive,
    OpenTasks,
    OpenSettings,
    OpenAbout,
    PromptSetup,
    PromptVisual,
    PromptResearch,
    PromptWeb,
    PromptImage
}

internal sealed record SlashCommand(string Display, string Title, string Description, SlashAction Action);
