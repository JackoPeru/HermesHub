using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Pages;
using NemoclawChat_Windows.Services;
using System;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Windows.Graphics;
using Windows.Storage;

namespace NemoclawChat_Windows;

[System.Diagnostics.CodeAnalysis.SuppressMessage(
    "Design",
    "CA1001:Types that own disposable fields should be disposable",
    Justification = "La finestra WinUI chiude e dispone i servizi nel proprio evento Closed; il framework non consuma IDisposable.")]
public sealed partial class MainWindow : Window
{
    private bool _sidebarCollapsed;
    private bool _closing;
    private readonly HubNotificationPoller _notificationPoller;
    private readonly ChatArchiveSyncService _archiveSyncService;
    private readonly WakeWordListener _wakeWordListener;
    private readonly CancellationTokenSource _gatewayRuntimeCancellation = new();
    private readonly Task _gatewayRuntimePollTask;
    // Menu rapido manager/laya (i 3 puntini): polling solo a menu aperto.
    private DispatcherTimer? _chatManagerTimer;
    private CancellationTokenSource? _chatManagerCts;
    private ComfyStatusRecord? _chatManagerStatus;
    private bool _chatManagerBusy;
    private bool _managerToggleSyncing;

    public MainWindow()
    {
        InitializeComponent();

        ExtendsContentIntoTitleBar = true;
        SetTitleBar(DragRegion);
        AppWindow.Title = "Hermes Hub";
        AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Standard;
        AppWindow.SetIcon("Assets/AppIcon.ico");
        RestoreWindowState();
        _wakeWordListener = new WakeWordListener(DispatcherQueue);
        _wakeWordListener.Detected += WakeWordListener_Detected;
        VoicePreferencesStore.Changed += VoicePreferencesStore_Changed;
        ContentFrame.Navigated += ContentFrame_Navigated;
        ContentFrame.Navigate(typeof(HomePage));
        ChatArchiveStore.Changed += RefreshRecentChats;
        Closed += MainWindow_Closed;
        _notificationPoller = new HubNotificationPoller(DispatcherQueue);
        _notificationPoller.Start();
        _archiveSyncService = new ChatArchiveSyncService();
        _archiveSyncService.Start();
        RefreshWakeWordListener();
        RefreshRecentChats();
        _gatewayRuntimePollTask = RefreshGatewayRuntimeAsync(_gatewayRuntimeCancellation.Token);
    }

    private async void MainWindow_Closed(object sender, WindowEventArgs args)
    {
        if (_closing) return;
        _closing = true;
        ChatArchiveStore.Changed -= RefreshRecentChats;
        VoicePreferencesStore.Changed -= VoicePreferencesStore_Changed;
        _wakeWordListener.Detected -= WakeWordListener_Detected;
        ContentFrame.Navigated -= ContentFrame_Navigated;
        _notificationPoller.Stop();
        _gatewayRuntimeCancellation.Cancel();
        try { await _gatewayRuntimePollTask; }
        catch (Exception ex) { System.Diagnostics.Trace.WriteLine($"[MainWindow] gateway runtime poll cleanup error: {ex}"); }
        finally { _gatewayRuntimeCancellation.Dispose(); }
        try { await _wakeWordListener.DisposeAsync(); }
        catch (Exception ex) { System.Diagnostics.Trace.WriteLine($"[MainWindow] wake listener cleanup error: {ex}"); }
        try { await _archiveSyncService.StopAsync(); }
        catch (Exception ex) { System.Diagnostics.Trace.WriteLine($"[MainWindow] sync cleanup error: {ex}"); }
        try { SaveWindowState(); }
        catch (Exception ex) { System.Diagnostics.Trace.WriteLine($"[MainWindow] state cleanup error: {ex}"); }
        Closed -= MainWindow_Closed;
    }

    private async Task RefreshGatewayRuntimeAsync(CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                var runtime = await GatewayService.GetRuntimeInfoAsync(AppSettingsStore.Load(), cancellationToken);
                if (cancellationToken.IsCancellationRequested)
                {
                    return;
                }

                if (DispatcherQueue.HasThreadAccess)
                {
                    ApplyGatewayRuntime(runtime);
                }
                else
                {
                    DispatcherQueue.TryEnqueue(() => ApplyGatewayRuntime(runtime));
                }

                await Task.Delay(TimeSpan.FromSeconds(runtime.Available ? 15 : 5), cancellationToken);
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
        catch (Exception ex)
        {
            System.Diagnostics.Trace.WriteLine($"[MainWindow] gateway runtime poll error: {ex}");
        }
    }

    private void ApplyGatewayRuntime(GatewayRuntimeInfo runtime)
    {
        if (_closing) return;
        var okBrush = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["SuccessBrush"];
        var faintBrush = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["FaintTextBrush"];
        var warnBrush = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["WarningBrush"];
        if (!runtime.Available)
        {
            GatewayRuntimeStatus.Text = "Gateway non disponibile";
            GatewayStatusDot.Fill = warnBrush;
            return;
        }

        var version = string.IsNullOrWhiteSpace(runtime.AgentVersion) ? "versione non letta" : runtime.AgentVersion;
        var updateStatus = runtime.Status.Trim().ToLowerInvariant() switch
        {
            "rolled_back" => " · rollback",
            "rollback_failed" => " · ripristino fallito",
            "blocked" => " · aggiornamento bloccato",
            "unhealthy" => " · stato da verificare",
            "updating" => " · aggiornamento in corso",
            _ => string.Empty
        };
        GatewayRuntimeStatus.Text = $"Gateway · Agent {version}{updateStatus}";
        GatewayStatusDot.Fill = string.IsNullOrEmpty(updateStatus) ? okBrush : warnBrush;
    }

    private void SaveWindowState()
    {
        var prefs = ApplicationData.Current.LocalSettings.Values;
        prefs["WindowX"] = AppWindow.Position.X;
        prefs["WindowY"] = AppWindow.Position.Y;
        prefs["WindowW"] = AppWindow.Size.Width;
        prefs["WindowH"] = AppWindow.Size.Height;
    }

    private void RestoreWindowState()
    {
        var prefs = ApplicationData.Current.LocalSettings.Values;
        if (prefs.TryGetValue("WindowW", out var w) &&
            prefs.TryGetValue("WindowH", out var h) &&
            w is int width &&
            h is int height &&
            width > 200 &&
            height > 200)
        {
            AppWindow.Resize(new SizeInt32(width, height));
            if (prefs.TryGetValue("WindowX", out var x) &&
                prefs.TryGetValue("WindowY", out var y) &&
                x is int posX &&
                y is int posY)
            {
                AppWindow.Move(new PointInt32(posX, posY));
            }
        }
    }

    private void CollapseSidebar_Click(object sender, RoutedEventArgs e)
    {
        _sidebarCollapsed = !_sidebarCollapsed;
        ApplyStandardShell();
    }

    private void NewChat_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(HomePage), new HomeNavigationRequest());
        RefreshRecentChats();
    }

    private void ChatManagerFlyout_Opened(object sender, object e)
    {
        _chatManagerCts = new CancellationTokenSource();
        _ = RefreshChatManagerStatusAsync();
        _chatManagerTimer?.Stop();
        _chatManagerTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
        _chatManagerTimer.Tick += async (_, _) => await RefreshChatManagerStatusAsync();
        _chatManagerTimer.Start();
    }

    private void ChatManagerFlyout_Closed(object sender, object e)
    {
        _chatManagerTimer?.Stop();
        _chatManagerTimer = null;
    }

    private void ChatMenuNewChat_Click(object sender, RoutedEventArgs e)
    {
        ChatManagerFlyout.Hide();
        NewChat_Click(sender, e);
    }

    private async void ChatManagerRetry_Click(object sender, RoutedEventArgs e)
    {
        ChatManagerRetryLabel.Text = "Lettura...";
        await RefreshChatManagerStatusAsync();
    }

    private async Task RefreshChatManagerStatusAsync()
    {
        if (_chatManagerBusy) return;
        _chatManagerBusy = true;
        try
        {
            var token = _chatManagerCts?.Token ?? CancellationToken.None;
            var status = await ComfyStatusService.LoadAsync(AppSettingsStore.Load(), token);
            _chatManagerStatus = status;
            RenderChatManagerFlyout(status);
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception)
        {
            ChatManagerStatus.Text = "Errore lettura stato manager.";
        }
        finally
        {
            _chatManagerBusy = false;
        }
    }

    private void RenderChatManagerFlyout(ComfyStatusRecord status)
    {
        _ = DispatcherQueue?.TryEnqueue(() => RenderChatManagerFlyoutCore(status));
    }

    private void RenderChatManagerFlyoutCore(ComfyStatusRecord status)
    {
        if (!status.Reachable)
        {
            ChatManagerStatus.Text = "Non raggiungibile: verifica server e chiave.";
            ChatLlmSubtitle.Text = status.Error.Length == 0 ? "Non raggiungibile" : TruncateMenu(status.Error, 90);
            ChatAutoSubtitle.Text = "Non raggiungibile";
            ChatDirectSubtitle.Text = "Non raggiungibile";
            ChatComfySubtitle.Text = "Non raggiungibile";
            ChatManagerRetryLabel.Visibility = Visibility.Visible;
            SetManagerSwitches(false, false, false, enabled: false);
            return;
        }
        ChatManagerStatus.Text = ReadManagerLastLine(status.Hint, status);
        ChatManagerRetryLabel.Visibility = string.IsNullOrWhiteSpace(status.Error)
            ? Visibility.Collapsed : Visibility.Visible;
        _managerToggleSyncing = true;
        ChatLlmSwitch.IsEnabled = true;
        ChatLlmSwitch.IsOn = status.LlmLoaded;
        ChatAutoSwitch.IsEnabled = true;
        ChatAutoSwitch.IsOn = string.Equals(status.Desired, "AUTO", StringComparison.OrdinalIgnoreCase);
        ChatDirectSwitch.IsEnabled = true;
        ChatDirectSwitch.IsOn = string.Equals(status.Desired, "DIRECT", StringComparison.OrdinalIgnoreCase);
        _managerToggleSyncing = false;

        ChatLlmSubtitle.Text = status.LlmLoaded
            ? "Caricato sulle GPU"
            : status.State is "LLM_LOADING" ? "Caricamento in corso..." : "Scaricato";
        var video = status.MediaLoadedModels.FirstOrDefault(m => !string.IsNullOrWhiteSpace(m));
        if (video is not null)
        {
            var extra = status.MediaLoadedModels.Count > 1 ? $" +{status.MediaLoadedModels.Count - 1}" : string.Empty;
            ChatLlmSwitch.Header = "Video su GPU";
            ChatLlmSubtitle.Text = $"{video}{extra}";
        }
        else
        {
            ChatLlmSwitch.Header = "LLM su GPU";
            if (!string.IsNullOrWhiteSpace(status.LlmModel) && status.LlmLoaded)
            {
                ChatLlmSubtitle.Text = $"Caricato: {status.LlmModel}";
            }
        }
        var queue = status.Queue > 0 ? $" · coda {status.Queue}" : string.Empty;
        ChatAutoSubtitle.Text = status.Desired switch
        {
            "AUTO" => "Il manager cambia da solo" + queue,
            "" => "Stato sconosciuto",
            _ => $"Manuale ({status.Desired})" + queue
        };
        ChatDirectSubtitle.Text = status.Desired switch
        {
            "DIRECT" => "Attivo: apri Comfy nel browser",
            "" => "Stato sconosciuto",
            _ => "LLM scarico, Comfy pronto in rete."
        };
        ChatComfySubtitle.Text = status.State switch
        {
            "MEDIA_BUSY" => $"Sta generando · {status.ProgressLabel} · {(status.Preset.Length > 0 ? status.Preset : status.Model)}",
            "LLM_READY" => $"LLM attivo · {(string.IsNullOrWhiteSpace(status.Desired) ? "AUTO" : status.Desired)}",
            _ => (status.State.Length == 0 ? "Stato sconosciuto" : status.StateLabel) + queue
        };
    }

    private void SetManagerSwitches(bool llm, bool auto, bool direct, bool enabled)
    {
        _managerToggleSyncing = true;
        ChatLlmSwitch.IsEnabled = enabled;
        ChatLlmSwitch.IsOn = llm;
        ChatAutoSwitch.IsEnabled = enabled;
        ChatAutoSwitch.IsOn = auto;
        ChatDirectSwitch.IsEnabled = enabled;
        ChatDirectSwitch.IsOn = direct;
        _managerToggleSyncing = false;
    }

    private async void ChatLlmSwitch_Toggled(object sender, RoutedEventArgs e)
    {
        if (_managerToggleSyncing) return;
        var wanted = ChatLlmSwitch.IsOn;
        await SendChatManagerModeAsync(wanted ? "llm" : "media", ChatLlmSubtitle);
    }

    private async void ChatAutoSwitch_Toggled(object sender, RoutedEventArgs e)
    {
        if (_managerToggleSyncing) return;
        var wanted = ChatAutoSwitch.IsOn;
        if (!wanted)
        {
            var llm = _chatManagerStatus?.LlmLoaded == true;
            await SendChatManagerModeAsync(llm ? "llm" : "media", ChatAutoSubtitle);
        }
        else
        {
            await SendChatManagerModeAsync("auto", ChatAutoSubtitle);
        }
    }

    private async void ChatDirectSwitch_Toggled(object sender, RoutedEventArgs e)
    {
        if (_managerToggleSyncing) return;
        var wanted = ChatDirectSwitch.IsOn;
        await SendChatManagerModeAsync(wanted ? "direct" : "auto", ChatDirectSubtitle);
    }

    private async Task SendChatManagerModeAsync(string mode, TextBlock subtitle)
    {
        if (_chatManagerBusy) return;
        _chatManagerBusy = true;
        SetManagerSwitches(_chatManagerStatus?.LlmLoaded == true,
                           _chatManagerStatus?.Desired == "AUTO",
                           _chatManagerStatus?.Desired == "DIRECT",
                           enabled: false);
        ChatManagerStatus.Text = "Applicazione in corso...";
        try
        {
            var settings = AppSettingsStore.Load();
            var (code, body) = await GatewayService.SendManagerRequestAsync(
                settings, HttpMethod.Post, $"/mode/{mode}", "{}", _chatManagerCts?.Token ?? CancellationToken.None);
            if (code is < 200 or > 299)
            {
                subtitle.Text = code == 0 ? "Manager non raggiungibile." : $"HTTP {code}: {TruncateMenu(body, 80)}";
            }
            await Task.Delay(3000);
            _chatManagerBusy = false;
            await RefreshChatManagerStatusAsync();
            return;
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception)
        {
            subtitle.Text = "Errore di rete verso il manager.";
        }
        finally
        {
            _chatManagerBusy = false;
        }
    }

    private void ChatLaya_Click(object sender, RoutedEventArgs e)
    {
        ChatManagerFlyout.Hide();
        _ = ShowChatLayaDialogAsync();
    }

    private async Task ShowChatLayaDialogAsync()
    {
        var input = new TextBox
        {
            PlaceholderText = "Chiedi a laya...",
            MaxLength = 500
        };
        var reply = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            FontSize = 13,
            Foreground = new SolidColorBrush(Microsoft.UI.Colors.White),
            Visibility = Visibility.Collapsed
        };
        var panel = new StackPanel { Spacing = 8 };
        panel.Children.Add(new TextBlock
        {
            Text = "Chiedi in linguaggio naturale: \"metti l'LLM ready\", \"attiva comfy\", \"metti in auto\", \"come sta?\".",
            TextWrapping = TextWrapping.Wrap,
            FontSize = 12,
            Foreground = new SolidColorBrush(Microsoft.UI.Colors.Gray)
        });
        panel.Children.Add(input);
        panel.Children.Add(reply);

        var dialog = new ContentDialog
        {
            Title = "Chat laya",
            Content = panel,
            PrimaryButtonText = "Invia",
            CloseButtonText = "Chiudi",
            DefaultButton = ContentDialogButton.Primary
        };

        bool dispatching = false;
        dialog.PrimaryButtonClick += async (_, args) =>
        {
            args.Cancel = true;
            var text = input.Text?.Trim() ?? string.Empty;
            if (text.Length == 0 || dispatching) return;
            dispatching = true;
            reply.Text = "...";
            reply.Visibility = Visibility.Visible;
            try
            {
                var settings = AppSettingsStore.Load();
                var payload = JsonSerializer.Serialize(new { text });
                var (code, body) = await GatewayService.SendBridgeRequestAsync(
                    settings, HttpMethod.Post, "/laya", payload, CancellationToken.None);
                reply.Text = code == 0
                    ? $"Bridge non raggiungibile ({GatewayService.BridgeBase(settings)})."
                    : code is < 200 or > 299
                        ? $"Bridge HTTP {code}."
                        : ReadLayaReply(body);
            }
            catch (Exception ex)
            {
                reply.Text = $"Bridge non raggiungibile ({ex.Message})";
            }
            reply.Visibility = Visibility.Visible;
            dispatching = false;
        };
        dialog.Closed += async (_, _) => await RefreshChatManagerStatusAsync();
        var root = Content as FrameworkElement;
        if (root?.XamlRoot is not null)
        {
            dialog.XamlRoot = root.XamlRoot;
            try
            {
                await dialog.ShowAsync();
            }
            catch (Exception)
            {
            }
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

    private void ChatComfy_Click(object sender, RoutedEventArgs e)
    {
        ChatManagerFlyout.Hide();
        ContentFrame.Navigate(typeof(ComfyPage));
    }

    private static string TruncateMenu(string value, int max) =>
        string.IsNullOrWhiteSpace(value)
            ? string.Empty
            : value.Length <= max ? value : value[..max];

    private static string ReadManagerLastLine(string hint, ComfyStatusRecord status)
    {
        if (string.Equals(status.State, "ERROR", StringComparison.OrdinalIgnoreCase) &&
            !string.IsNullOrWhiteSpace(status.Error))
        {
            return $"Errore manager: {TruncateMenu(status.Error, 80)} (recovery AUTO attivo)";
        }
        return string.IsNullOrWhiteSpace(hint)
            ? status.StateLabel.Replace("  ", " ")
            : TruncateMenu(hint, 110);
    }

    private void OpenChat_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: ConversationRecord conversation })
        {
            ContentFrame.Navigate(typeof(HomePage), new HomeNavigationRequest(conversation.Id));
        }
        else if (sender is FrameworkElement { Tag: string prompt })
        {
            ContentFrame.Navigate(typeof(HomePage), new HomeNavigationRequest(Prompt: prompt));
        }
        else
        {
            ContentFrame.Navigate(typeof(HomePage), new HomeNavigationRequest());
        }
    }

    private void Archive_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(ArchivePage));
    }

    private void Search_Click(object sender, RoutedEventArgs e) => ContentFrame.Navigate(typeof(SearchPage));

    private void SearchAccelerator_Invoked(KeyboardAccelerator sender, KeyboardAcceleratorInvokedEventArgs args)
    {
        ContentFrame.Navigate(typeof(SearchPage));
        args.Handled = true;
    }

    private void Projects_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(ProjectsPage));
    }

    private void Bots_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(BotsPage));
    }

    private void Cron_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(CronPage));
    }

    private void Notifications_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(NotificationsPage));
    }

    private void Continuity_Click(object sender, RoutedEventArgs e) => ContentFrame.Navigate(typeof(ContinuityPage));
    private void Audit_Click(object sender, RoutedEventArgs e) => ContentFrame.Navigate(typeof(AuditPage));

    private void Server_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(ServerPage));
    }

    private void Hardware_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(HardwarePage));
    }

    private void Screen_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(ScreenPage));
    }

    private void Voice_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(VoicePage));
    }

    private void WakeWordListener_Detected()
    {
        if (_closing || ContentFrame.CurrentSourcePageType == typeof(VoicePage))
        {
            return;
        }

        _wakeWordListener.Stop();
        Activate();
        ContentFrame.Navigate(typeof(VoicePage), new VoiceNavigationRequest(AutoStart: true));
    }

    private void VoicePreferencesStore_Changed() => RefreshWakeWordListener();

    private void RefreshWakeWordListener()
    {
        if (_closing || ContentFrame.CurrentSourcePageType == typeof(VoicePage))
        {
            _wakeWordListener.Stop();
            return;
        }

        var settings = AppSettingsStore.Load();
        if (VoicePreferencesStore.Load(settings.ActiveProjectId).WakeWord)
        {
            _wakeWordListener.Start();
        }
        else
        {
            _wakeWordListener.Stop();
        }
    }

    private void Video_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(VideoPage));
    }

    private void Artifacts_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(ArtifactsPage));
    }

    private void News_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(NewsPage));
    }

    private void Images_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(ImagesPage));
    }

    private void Comfy_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(ComfyPage));
    }

    private void Characters_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(CharactersPage));
    }

    private void Settings_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(SettingsPage));
    }

    private void About_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(AboutPage));
    }

    private void RefreshRecentChats()
    {
        if (!DispatcherQueue.HasThreadAccess)
        {
            DispatcherQueue.TryEnqueue(RefreshRecentChats);
            return;
        }

        RecentChatsPanel.Children.Clear();
        var recent = ChatArchiveStore.Recent(5);

        if (recent.Count == 0)
        {
            RecentChatsPanel.Children.Add(new TextBlock
            {
                Text = "Nessuna chat recente.",
                Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["MutedTextBrush"],
                Margin = new Thickness(12, 6, 12, 0)
            });
            return;
        }

        foreach (var conversation in recent)
        {
            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var textBlock = new TextBlock
            {
                Text = conversation.Title,
                TextTrimming = Microsoft.UI.Xaml.TextTrimming.CharacterEllipsis,
                VerticalAlignment = VerticalAlignment.Center
            };
            grid.Children.Add(textBlock);
            Grid.SetColumn(textBlock, 0);

            var moreButton = new Button
            {
                Content = new FontIcon { Glyph = "\uE712", FontSize = 14 },
                Background = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Transparent),
                BorderThickness = new Thickness(0),
                Padding = new Thickness(4),
                Margin = new Thickness(4, 0, 0, 0),
                VerticalAlignment = VerticalAlignment.Center,
                Visibility = Visibility.Collapsed
            };
            grid.Children.Add(moreButton);
            Grid.SetColumn(moreButton, 1);

            var flyout = new MenuFlyout();

            var renameItem = new MenuFlyoutItem { Text = "Rinomina" };
            renameItem.Icon = new FontIcon { Glyph = "\uE70F" };
            renameItem.Click += async (s, e) =>
            {
                var tb = new TextBox { Text = conversation.Title, MaxLength = 160 };
                var dialog = new ContentDialog
                {
                    Title = "Rinomina chat",
                    Content = tb,
                    PrimaryButtonText = "Salva",
                    CloseButtonText = "Annulla",
                    XamlRoot = this.Content.XamlRoot
                };
                var result = await dialog.ShowAsync();
                if (result == ContentDialogResult.Primary && !string.IsNullOrWhiteSpace(tb.Text))
                {
                    ChatArchiveStore.Rename(conversation.Id, tb.Text);
                    RefreshRecentChats();
                }
            };
            flyout.Items.Add(renameItem);

            var deleteItem = new MenuFlyoutItem { Text = "Elimina", Foreground = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Red) };
            deleteItem.Icon = new FontIcon { Glyph = "\uE74D", Foreground = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Red) };
            deleteItem.Click += (s, e) =>
            {
                ChatArchiveStore.Delete(conversation.Id);
                RefreshRecentChats();
            };
            flyout.Items.Add(deleteItem);

            moreButton.Flyout = flyout;

            var button = new Button
            {
                Style = (Style)Application.Current.Resources["SidebarButtonStyle"],
                Tag = conversation,
                Content = grid,
                HorizontalContentAlignment = HorizontalAlignment.Stretch
            };

            button.PointerEntered += (s, e) => moreButton.Visibility = Visibility.Visible;
            button.PointerExited += (s, e) => moreButton.Visibility = Visibility.Collapsed;

            Microsoft.UI.Xaml.Controls.ToolTipService.SetToolTip(button, conversation.Title);
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, $"Apri chat: {conversation.Title}");
            button.Click += OpenChat_Click;
            RecentChatsPanel.Children.Add(button);
        }
    }

    private void ContentFrame_Navigated(object sender, Microsoft.UI.Xaml.Navigation.NavigationEventArgs e)
    {
        UpdateShellNavigation(e.SourcePageType);
        if (e.SourcePageType == typeof(VoicePage))
        {
            ApplyVoiceShell();
        }
        else
        {
            ApplyStandardShell();
        }
        RefreshWakeWordListener();
    }

    private void UpdateShellNavigation(Type pageType)
    {
        var (title, subtitle, selected) = pageType == typeof(HomePage)
            ? ("Chat", "Conversazione principale Hermes Agent", NavNewChatButton)
            : pageType == typeof(ArchivePage)
                ? ("Archivio chat", "Conversazioni sincronizzate", NavArchiveButton)
                : pageType == typeof(ProjectsPage) || pageType == typeof(ConversationManagerPage)
                    ? ("Progetti", "Workspace e attività operative", NavProjectsButton)
                    : pageType == typeof(BotsPage)
                        ? ("Bot Hermes", "Profili reali e Bot Chat canoniche", NavBotsButton)
                    : pageType == typeof(CronPage)
                        ? ("Cron", "Automazioni Hermes programmate", NavCronButton)
                        : pageType == typeof(NotificationsPage)
                            ? ("Notifiche", "Avvisi autonomi da Hermes", NavNotificationsButton)
                            : pageType == typeof(ContinuityPage)
                                ? ("Continuità", "Presenza e handoff tra dispositivi", NavContinuityButton)
                                : pageType == typeof(AuditPage)
                                    ? ("Timeline audit", "Eventi, run e operazioni server", NavAuditButton)
                                    : pageType == typeof(SearchPage)
                                        ? ("Ricerca globale", "Chat, progetti e contenuti Hermes", NavSearchButton)
                                        : pageType == typeof(ServerPage)
                                            ? ("Server", "Stato e controlli Hermes Agent", NavServerButton)
                                            : pageType == typeof(HardwarePage)
                                                ? ("Prestazioni", "Hardware del server Hermes", NavHardwareButton)
                                                : pageType == typeof(ScreenPage)
                                                    ? ("Schermo", "Desktop remoto del bot", NavScreenButton)
                                                    : pageType == typeof(VoicePage)
                                                    ? ("Voce", "Conversazione vocale live", NavVoiceButton)
                                                    : pageType == typeof(VideoPage)
                                                        ? ("Video", "Libreria e produzione video", NavVideoButton)
                                                        : pageType == typeof(ArtifactsPage)
                                                            ? ("Artifact", "Output strutturati di Hermes", NavArtifactsButton)
                                                            : pageType == typeof(NewsPage)
                                                                ? ("News", "Articoli e briefing personali", NavNewsButton)
                                                                : pageType == typeof(ImagesPage)
                                                                    ? ("Immagini", "Libreria immagini Hermes", NavImagesButton)
                                                                    : pageType == typeof(ComfyPage)
                                                                        ? ("Comfy", "Stato GPU e generazione", NavComfyButton)
                                                                        : pageType == typeof(CharactersPage)
                                                                            ? ("Personaggi", "Identità e personaggi Hermes", NavCharactersButton)
                                                                            : pageType == typeof(SettingsPage)
                                                                    ? ("Impostazioni", "Connessione, voce e preferenze", NavSettingsButton)
                                                                    : pageType == typeof(AboutPage)
                                                                        ? ("Profilo locale", "Hermes Hub", ProfileButton)
                                                                        : ("Hermes Hub", "Workspace operativo", (Button?)null);

        ShellPageTitle.Text = title;
        ShellPageSubtitle.Text = subtitle;
        ShellPageIcon.Glyph = pageType == typeof(HomePage) ? "\uE8BD"
            : pageType == typeof(ArchivePage) ? "\uE8B7"
            : pageType == typeof(ProjectsPage) || pageType == typeof(ConversationManagerPage) ? "\uE8F1"
            : pageType == typeof(BotsPage) ? "\uE7B2"
            : pageType == typeof(CronPage) ? "\uE7C1"
            : pageType == typeof(NotificationsPage) ? "\uE91C"
            : pageType == typeof(ContinuityPage) ? "\uE8C8"
            : pageType == typeof(AuditPage) ? "\uE9D5"
            : pageType == typeof(SearchPage) ? "\uE721"
            : pageType == typeof(ServerPage) ? "\uE968"
            : pageType == typeof(HardwarePage) ? "\uE9D9"
            : pageType == typeof(ScreenPage) ? "\uE7B4"
            : pageType == typeof(VoicePage) ? "\uE720"
            : pageType == typeof(VideoPage) ? "\uE714"
            : pageType == typeof(ArtifactsPage) ? "\uE8B7"
            : pageType == typeof(NewsPage) ? "\uE789"
            : pageType == typeof(ImagesPage) ? "\uE7B8"
            : pageType == typeof(ComfyPage) ? "\uE768"
            : pageType == typeof(CharactersPage) ? "\uE77B"
            : pageType == typeof(SettingsPage) ? "\uE713"
            : "\uE8BD";
        var normalStyle = (Style)Application.Current.Resources["SidebarButtonStyle"];
        var selectedStyle = (Style)Application.Current.Resources["SidebarSelectedButtonStyle"];
        foreach (var button in NavigationButtons())
        {
            button.Style = ReferenceEquals(button, selected) ? selectedStyle : normalStyle;
        }
    }

    private IEnumerable<Button> NavigationButtons()
    {
        yield return NavNewChatButton;
        yield return NavArchiveButton;
        yield return NavProjectsButton;
        yield return NavBotsButton;
        yield return NavCronButton;
        yield return NavNotificationsButton;
        yield return NavContinuityButton;
        yield return NavAuditButton;
        yield return NavSearchButton;
        yield return NavServerButton;
        yield return NavHardwareButton;
        yield return NavScreenButton;
        yield return NavVoiceButton;
        yield return NavVideoButton;
        yield return NavArtifactsButton;
        yield return NavNewsButton;
        yield return NavImagesButton;
        yield return NavComfyButton;
        yield return NavCharactersButton;
        yield return NavSettingsButton;
        yield return ProfileButton;
    }

    private void ApplyVoiceShell()
    {
        Sidebar.Visibility = Visibility.Collapsed;
        SidebarColumn.Width = new GridLength(0);
        DragRegion.Visibility = Visibility.Collapsed;
        RestoreSidebarButton.Visibility = Visibility.Collapsed;
        Grid.SetRow(ContentFrame, 0);
        Grid.SetRowSpan(ContentFrame, 2);
    }

    private void ApplyStandardShell()
    {
        Sidebar.Visibility = _sidebarCollapsed ? Visibility.Collapsed : Visibility.Visible;
        SidebarColumn.Width = _sidebarCollapsed ? new GridLength(0) : new GridLength(280);
        DragRegion.Visibility = Visibility.Visible;
        RestoreSidebarButton.Visibility = _sidebarCollapsed ? Visibility.Visible : Visibility.Collapsed;
        Grid.SetRow(ContentFrame, 1);
        Grid.SetRowSpan(ContentFrame, 1);
    }

    public void ToggleFullScreenVideo(bool isFullScreen)
    {
        var hWnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        var windowId = Microsoft.UI.Win32Interop.GetWindowIdFromWindow(hWnd);
        var appWindow = Microsoft.UI.Windowing.AppWindow.GetFromWindowId(windowId);

        if (isFullScreen)
        {
            appWindow.SetPresenter(Microsoft.UI.Windowing.AppWindowPresenterKind.FullScreen);
            Sidebar.Visibility = Visibility.Collapsed;
            SidebarColumn.Width = new GridLength(0);
            DragRegion.Visibility = Visibility.Collapsed;
            RestoreSidebarButton.Visibility = Visibility.Collapsed;
            Grid.SetRow(ContentFrame, 0);
            Grid.SetRowSpan(ContentFrame, 2);
        }
        else
        {
            appWindow.SetPresenter(Microsoft.UI.Windowing.AppWindowPresenterKind.Default);
            ApplyStandardShell();
        }
    }

}
