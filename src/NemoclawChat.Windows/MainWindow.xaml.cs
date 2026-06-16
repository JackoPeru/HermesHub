using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using NemoclawChat_Windows.Pages;
using NemoclawChat_Windows.Services;
using Windows.Graphics;
using Windows.Storage;

// To learn more about WinUI, the WinUI project structure,
// and more about our project templates, see: http://aka.ms/winui-project-info.

namespace NemoclawChat_Windows;

public sealed partial class MainWindow : Window
{
    private bool _sidebarCollapsed;
    private bool _closing;

    public MainWindow()
    {
        InitializeComponent();

        ExtendsContentIntoTitleBar = true;
        SetTitleBar(DragRegion);
        AppWindow.Title = "Hermes Hub";
        AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Standard;
        AppWindow.SetIcon("Assets/AppIcon.ico");
        RestoreWindowState();
        ContentFrame.Navigate(typeof(HomePage));
        ContentFrame.Navigated += ContentFrame_Navigated;
        ChatArchiveStore.Changed += RefreshRecentChats;
        Closed += MainWindow_Closed;
        RefreshRecentChats();
    }

    private void MainWindow_Closed(object sender, WindowEventArgs args)
    {
        if (_closing) return;
        _closing = true;
        try
        {
            ChatArchiveStore.Changed -= RefreshRecentChats;
            ContentFrame.Navigated -= ContentFrame_Navigated;
            SaveWindowState();
            Closed -= MainWindow_Closed;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[MainWindow] cleanup error: {ex.Message}");
        }
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

    private void Tasks_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(TasksPage));
    }

    private void Server_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(ServerPage));
    }

    private void Hardware_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(HardwarePage));
    }

    private void Operator_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(OperatorPage));
    }

    private void Voice_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(VoicePage));
    }

    private void Video_Click(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(VideoPage));
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
            var button = new Button
            {
                Style = (Style)Application.Current.Resources["SidebarButtonStyle"],
                Tag = conversation,
                Content = new TextBlock
                {
                    Text = conversation.Title,
                    TextTrimming = Microsoft.UI.Xaml.TextTrimming.CharacterEllipsis
                }
            };
            Microsoft.UI.Xaml.Controls.ToolTipService.SetToolTip(button, conversation.Title);
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, $"Apri chat: {conversation.Title}");
            button.Click += OpenChat_Click;
            RecentChatsPanel.Children.Add(button);
        }
    }

    private void ContentFrame_Navigated(object sender, Microsoft.UI.Xaml.Navigation.NavigationEventArgs e)
    {
        if (e.SourcePageType == typeof(VoicePage))
        {
            ApplyVoiceShell();
        }
        else
        {
            ApplyStandardShell();
        }
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

}
