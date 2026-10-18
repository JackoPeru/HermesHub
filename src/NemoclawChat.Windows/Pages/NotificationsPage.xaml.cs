using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;
using Windows.Storage;
using Windows.System;

namespace NemoclawChat_Windows.Pages;

public sealed partial class NotificationsPage : Page
{
    private IReadOnlyList<HubNotificationRecord> _items = [];

    public NotificationsPage()
    {
        InitializeComponent();
        DoNotDisturbSwitch.IsOn = ApplicationData.Current.LocalSettings.Values["NotificationsDnd"] as bool? ?? false;
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
        var category = (CategoryFilterBox?.SelectedItem as ComboBoxItem)?.Content?.ToString();
        var priority = (PriorityFilterBox?.SelectedItem as ComboBoxItem)?.Content?.ToString();
        var now = DateTimeOffset.Now;
        var visible = _items.Where(item =>
            (ArchivedBox?.IsChecked == true ? item.Archived : !item.Archived) &&
            (UnreadOnlyBox?.IsChecked != true || item.ReadAt is null) &&
            (item.SnoozedUntil is null || item.SnoozedUntil <= now) &&
            (category is null || category.StartsWith("Tutte", StringComparison.Ordinal) || item.Category.Equals(category, StringComparison.OrdinalIgnoreCase)) &&
            (priority is null || priority.StartsWith("Tutte", StringComparison.Ordinal) || item.Priority.Equals(priority, StringComparison.OrdinalIgnoreCase)))
            .OrderByDescending(item => PriorityRank(item.Priority)).ThenByDescending(item => item.CreatedAt).ToList();
        BadgesText.Text = $"Non lette: {_items.Count(item => item.ReadAt is null && !item.Archived)} · Critiche: {_items.Count(item => item.Priority.Equals("Critica", StringComparison.OrdinalIgnoreCase) && !item.Archived)} · Automazioni: {_items.Count(item => !string.IsNullOrWhiteSpace(item.AutomationId) && !item.Archived)}";
        if (visible.Count == 0)
        {
            NotificationsPanel.Children.Add(new TextBlock
            {
                Text = "Nessuna notifica. Quando un cron deve avvisarti, Hermes scrive qui.",
                Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
                TextWrapping = TextWrapping.Wrap
            });
            return;
        }

        string? currentGroup = null;
        foreach (var item in visible)
        {
            var group = string.IsNullOrWhiteSpace(item.AutomationId) ? item.Category : $"Automazione · {item.AutomationId}";
            if (!string.Equals(group, currentGroup, StringComparison.OrdinalIgnoreCase))
            {
                NotificationsPanel.Children.Add(PremiumRows.SectionHeader(group));
                currentGroup = group;
            }
            NotificationsPanel.Children.Add(CreateCard(item));
        }
    }

    private StackPanel CreateCard(HubNotificationRecord item)
    {
        var unread = item.ReadAt is null;
        var header = PremiumRows.LabeledRow("\uE7B8", item.Title, null, PremiumRows.MetaLine(item.Priority));
        var meta = PremiumRows.MetaLine($"{item.Category} · {item.Source} · {item.CreatedAt.LocalDateTime:g} · {(unread ? "non letta" : "letta")}");
        if (unread)
        {
            meta.Foreground = (Brush)Application.Current.Resources["AccentGreenBrush"];
        }
        var message = new TextBlock
        {
            Text = item.Message,
            Foreground = new SolidColorBrush(Microsoft.UI.Colors.White),
            TextWrapping = TextWrapping.Wrap
        };
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        actions.Children.Add(Button("Apri chat", item, OpenChat_Click));
        actions.Children.Add(Button("Segna letta", item, MarkRead_Click));
        actions.Children.Add(Button("Tra 1 ora", item, Snooze_Click));
        actions.Children.Add(Button(item.Archived ? "Ripristina" : "Archivia", item, Archive_Click));
        if (!string.IsNullOrWhiteSpace(item.FileUrl) || !string.IsNullOrWhiteSpace(item.RunId) || !string.IsNullOrWhiteSpace(item.AutomationId) || !string.IsNullOrWhiteSpace(item.ProjectId)) actions.Children.Add(Button("Apri riferimento", item, OpenReference_Click));

        return new StackPanel { Spacing = 10, Children = { header, meta, message, actions, PremiumRows.Hairline() } };
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

    private async void Snooze_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: HubNotificationRecord item }) return;
        StatusText.Text = await GatewayService.PatchHubNotificationAsync(AppSettingsStore.Load(), item.Id, new { snoozed_until = DateTimeOffset.Now.AddHours(1).ToUnixTimeSeconds() });
        await RefreshAsync();
    }

    private async void Archive_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: HubNotificationRecord item }) return;
        StatusText.Text = await GatewayService.PatchHubNotificationAsync(AppSettingsStore.Load(), item.Id, new { archived = !item.Archived });
        await RefreshAsync();
    }

    private async void OpenReference_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: HubNotificationRecord item }) return;
        if (!string.IsNullOrWhiteSpace(item.FileUrl) && Uri.TryCreate(item.FileUrl, UriKind.Absolute, out var uri)) { await Launcher.LaunchUriAsync(uri); return; }
        if (!string.IsNullOrWhiteSpace(item.ProjectId)) { Frame.Navigate(typeof(ProjectsPage), item.ProjectId); return; }
        if (!string.IsNullOrWhiteSpace(item.AutomationId)) { Frame.Navigate(typeof(CronPage)); return; }
        Frame.Navigate(typeof(HomePage), new HomeNavigationRequest(Prompt: $"Controlla lo stato della run {item.RunId} e spiegami il risultato."));
    }

    private void Filter_Changed(object sender, RoutedEventArgs e) { if (NotificationsPanel is not null) Render(); }
    private void Filter_Changed(object sender, SelectionChangedEventArgs e) { if (NotificationsPanel is not null) Render(); }
    private void DoNotDisturbSwitch_Toggled(object sender, RoutedEventArgs e) => ApplicationData.Current.LocalSettings.Values["NotificationsDnd"] = DoNotDisturbSwitch.IsOn;
    private static int PriorityRank(string priority) => priority.ToLowerInvariant() switch { "critica" or "critical" => 4, "alta" or "high" => 3, "normale" or "normal" => 2, _ => 1 };
}
