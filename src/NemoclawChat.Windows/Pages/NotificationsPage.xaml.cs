using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;

namespace NemoclawChat_Windows.Pages;

public sealed partial class NotificationsPage : Page
{
    private IReadOnlyList<HubNotificationRecord> _items = [];

    public NotificationsPage()
    {
        InitializeComponent();
        Loaded += NotificationsPage_Loaded;
    }

    private async void NotificationsPage_Loaded(object sender, RoutedEventArgs e)
    {
        Loaded -= NotificationsPage_Loaded;
        await RefreshAsync();
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e)
    {
        await RefreshAsync();
    }

    private async Task RefreshAsync()
    {
        StatusText.Text = "Carico notifiche Hermes...";
        var result = await GatewayService.LoadHubNotificationsAsync(AppSettingsStore.Load());
        _items = result.Items;
        StatusText.Text = result.Status;
        Render();
    }

    private void Render()
    {
        NotificationsPanel.Children.Clear();
        if (_items.Count == 0)
        {
            NotificationsPanel.Children.Add(new TextBlock
            {
                Text = "Nessuna notifica. Quando un cron deve avvisarti, Hermes scrive qui.",
                Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
                TextWrapping = TextWrapping.Wrap
            });
            return;
        }

        foreach (var item in _items)
        {
            NotificationsPanel.Children.Add(CreateCard(item));
        }
    }

    private Border CreateCard(HubNotificationRecord item)
    {
        var unread = item.ReadAt is null;
        var title = new TextBlock
        {
            Text = item.Title,
            Foreground = new SolidColorBrush(Microsoft.UI.Colors.White),
            FontSize = 18,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            TextWrapping = TextWrapping.Wrap
        };
        var meta = new TextBlock
        {
            Text = $"{item.Source} · {item.CreatedAt.LocalDateTime:g} · {(unread ? "non letta" : "letta")}",
            Foreground = unread ? (Brush)Application.Current.Resources["AccentGreenBrush"] : (Brush)Application.Current.Resources["MutedTextBrush"],
            FontSize = 12,
            TextWrapping = TextWrapping.Wrap
        };
        var message = new TextBlock
        {
            Text = item.Message,
            Foreground = new SolidColorBrush(Microsoft.UI.Colors.White),
            TextWrapping = TextWrapping.Wrap
        };
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        actions.Children.Add(Button("Apri chat", item, OpenChat_Click));
        actions.Children.Add(Button("Segna letta", item, MarkRead_Click));

        return new Border
        {
            Padding = new Thickness(18),
            Background = (Brush)Application.Current.Resources["SurfaceBrush"],
            BorderBrush = new SolidColorBrush(Microsoft.UI.ColorHelper.FromArgb(255, unread ? (byte)34 : (byte)58, unread ? (byte)197 : (byte)58, unread ? (byte)94 : (byte)58)),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(16),
            Child = new StackPanel { Spacing = 10, Children = { title, meta, message, actions } }
        };
    }

    private static Button Button(string label, HubNotificationRecord item, RoutedEventHandler handler)
    {
        var button = new Button { Content = label, Tag = item, Padding = new Thickness(12, 6, 12, 6) };
        button.Click += handler;
        return button;
    }

    private async void MarkAllAsRead_Click(object sender, RoutedEventArgs e)
    {
        var settings = AppSettingsStore.Load();
        var unreadItems = _items.Where(i => i.ReadAt is null).ToList();
        if (unreadItems.Count == 0) return;

        StatusText.Text = $"Segnando {unreadItems.Count} notifiche come lette...";
        var tasks = unreadItems.Select(item => GatewayService.MarkHubNotificationReadAsync(settings, item.Id));
        await Task.WhenAll(tasks);
        await RefreshAsync();
    }

    private async void MarkRead_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: HubNotificationRecord item })
        {
            StatusText.Text = await GatewayService.MarkHubNotificationReadAsync(AppSettingsStore.Load(), item.Id);
            await RefreshAsync();
        }
    }

    private async void OpenChat_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: HubNotificationRecord item }) return;
        await GatewayService.MarkHubNotificationReadAsync(AppSettingsStore.Load(), item.Id);
        var prompt = string.IsNullOrWhiteSpace(item.ConversationPrompt)
            ? $"Riprendiamo da questa notifica Hermes:\n\nTitolo: {item.Title}\nMessaggio: {item.Message}\n\nVoglio chiederti una cosa su questa notifica."
            : item.ConversationPrompt;
        Frame.Navigate(typeof(HomePage), new HomeNavigationRequest(Prompt: prompt));
    }
}
