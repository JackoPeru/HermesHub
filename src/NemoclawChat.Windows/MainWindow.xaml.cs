using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using NemoclawChat_Windows.Pages;
using NemoclawChat_Windows.Services;
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
        try { await _wakeWordListener.DisposeAsync(); }
        catch (Exception ex) { System.Diagnostics.Trace.WriteLine($"[MainWindow] wake listener cleanup error: {ex}"); }
        try { await _archiveSyncService.StopAsync(); }
        catch (Exception ex) { System.Diagnostics.Trace.WriteLine($"[MainWindow] sync cleanup error: {ex}"); }
        try { SaveWindowState(); }
        catch (Exception ex) { System.Diagnostics.Trace.WriteLine($"[MainWindow] state cleanup error: {ex}"); }
        Closed -= MainWindow_Closed;
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
                                                : pageType == typeof(VoicePage)
                                                    ? ("Voce", "Conversazione vocale live", NavVoiceButton)
                                                    : pageType == typeof(VideoPage)
                                                        ? ("Video", "Libreria e produzione video", NavVideoButton)
                                                        : pageType == typeof(ArtifactsPage)
                                                            ? ("Artifact", "Output strutturati di Hermes", NavArtifactsButton)
                                                            : pageType == typeof(NewsPage)
                                                                ? ("News", "Articoli e briefing personali", NavNewsButton)
                                                                : pageType == typeof(SettingsPage)
                                                                    ? ("Impostazioni", "Connessione, voce e preferenze", NavSettingsButton)
                                                                    : pageType == typeof(AboutPage)
                                                                        ? ("Profilo locale", "Hermes Hub", ProfileButton)
                                                                        : ("Hermes Hub", "Workspace operativo", (Button?)null);

        ShellPageTitle.Text = title;
        ShellPageSubtitle.Text = subtitle;
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
        yield return NavCronButton;
        yield return NavNotificationsButton;
        yield return NavContinuityButton;
        yield return NavAuditButton;
        yield return NavSearchButton;
        yield return NavServerButton;
        yield return NavHardwareButton;
        yield return NavVoiceButton;
        yield return NavVideoButton;
        yield return NavArtifactsButton;
        yield return NavNewsButton;
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
