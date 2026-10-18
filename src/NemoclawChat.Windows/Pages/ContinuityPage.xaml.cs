using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;
using WinRT.Interop;
using Windows.Storage.Pickers;
using Windows.System;

namespace NemoclawChat_Windows.Pages;

public sealed partial class ContinuityPage : Page
{
    private IReadOnlyList<ContinuityItem> _items = [];
    public ContinuityPage() { InitializeComponent(); DeviceText.Text = $"{ContinuityService.DeviceId} · Windows"; Loaded += async (_, _) => await RefreshAsync(); }

    private async Task RefreshAsync()
    {
        var result = await ContinuityService.LoadAsync(AppSettingsStore.Load());
        _items = result.Items;
        SyncText.Text = $"{result.Status} · coda offline {ContinuityService.QueueCount}";
        Render();
    }

    private void Render()
    {
        ItemsPanel.Children.Clear(); ConflictsPanel.Children.Clear();
        foreach (var item in _items.Take(80))
        {
            var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            if (item.Type == "continuity.chat" && !string.IsNullOrWhiteSpace(item.ConversationId)) actions.Children.Add(Button("Apri chat", () => Frame.Navigate(typeof(HomePage), new HomeNavigationRequest(item.ConversationId))));
            if (item.Type == "continuity.clipboard") actions.Children.Add(Button("Copia", () => ContinuityService.SetClipboard(item.Value)));
            if (item.Type == "continuity.file" && Uri.TryCreate(item.FileUrl, UriKind.Absolute, out var uri)) actions.Children.Add(Button("Scarica/apri", async () => await Launcher.LaunchUriAsync(uri)));
            if (item.Type == "continuity.voice") actions.Children.Add(Button("Accetta chiamata", () => Frame.Navigate(typeof(VoicePage))));
            ItemsPanel.Children.Add(new StackPanel { Spacing = 5, Children = { PremiumRows.MetaLine($"{item.Type} · {item.Device} · {item.UpdatedAt.LocalDateTime:g}"), new TextBlock { Text = string.IsNullOrWhiteSpace(item.Value) ? item.FileName : item.Value, Foreground = (Brush)Application.Current.Resources["TextBrush"], TextWrapping = TextWrapping.Wrap }, actions, PremiumRows.Hairline() } });
        }
        var conflicts = _items.GroupBy(item => item.Type).Where(group => group.Select(item => item.Device).Distinct(StringComparer.OrdinalIgnoreCase).Count() > 1 && group.Max(item => item.UpdatedAt) - group.Min(item => item.UpdatedAt) < TimeSpan.FromMinutes(5));
        foreach (var conflict in conflicts) ConflictsPanel.Children.Add(new TextBlock { Text = $"{conflict.Key}: aggiornamenti concorrenti da {string.Join(", ", conflict.Select(item => item.Device).Distinct())}. Vince il più recente; puoi aprire i precedenti qui sopra.", Foreground = (Brush)Application.Current.Resources["MutedTextBrush"], TextWrapping = TextWrapping.Wrap });
        if (ConflictsPanel.Children.Count == 0) ConflictsPanel.Children.Add(new TextBlock { Text = "Nessun conflitto rilevato.", Foreground = (Brush)Application.Current.Resources["MutedTextBrush"] });
    }

    private static Button Button(string label, Action action) { var button = new Button { Content = label }; button.Click += (_, _) => action(); return button; }
    private static Button Button(string label, Func<Task> action) { var button = new Button { Content = label }; button.Click += async (_, _) => await action(); return button; }
    private async void PublishPresence_Click(object sender, RoutedEventArgs e) { StatusText.Text = await ContinuityService.PublishAsync(AppSettingsStore.Load(), "continuity.presence", status: "online"); await RefreshAsync(); }
    private async void ResumeLatest_Click(object sender, RoutedEventArgs e) { var recent = ChatArchiveStore.Recent(1); if (recent.Count == 0) return; var latest = recent[0]; StatusText.Text = await ContinuityService.PublishAsync(AppSettingsStore.Load(), "continuity.chat", latest.Title, latest.Id, latest.ProjectId); Frame.Navigate(typeof(HomePage), new HomeNavigationRequest(latest.Id)); }
    private async void TransferVoice_Click(object sender, RoutedEventArgs e) { StatusText.Text = await ContinuityService.PublishAsync(AppSettingsStore.Load(), "continuity.voice", "handoff_requested", status: "ringing"); Frame.Navigate(typeof(VoicePage)); }
    private async void FlushQueue_Click(object sender, RoutedEventArgs e) { StatusText.Text = await ContinuityService.FlushQueueAsync(AppSettingsStore.Load()); await RefreshAsync(); }
    private async void SendClipboard_Click(object sender, RoutedEventArgs e) { var value = string.IsNullOrWhiteSpace(ClipboardBox.Text) ? await ContinuityService.ClipboardTextAsync() : ClipboardBox.Text; StatusText.Text = await ContinuityService.PublishAsync(AppSettingsStore.Load(), "continuity.clipboard", value); await RefreshAsync(); }
    private void ReceiveClipboard_Click(object sender, RoutedEventArgs e) { var item = _items.FirstOrDefault(value => value.Type == "continuity.clipboard" && value.Device != ContinuityService.DeviceId); if (item is null) { StatusText.Text = "Clipboard remota assente."; return; } ClipboardBox.Text = item.Value; ContinuityService.SetClipboard(item.Value); StatusText.Text = "Clipboard remota copiata."; }
    private async void SendFile_Click(object sender, RoutedEventArgs e)
    {
        var picker = new FileOpenPicker(); picker.FileTypeFilter.Add("*"); if (App.MainWindow is not null) InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.MainWindow)); var file = await picker.PickSingleFileAsync(); if (file is null) return;
        var upload = await GatewayService.UploadContinuityFileAsync(AppSettingsStore.Load(), file.Path); if (string.IsNullOrWhiteSpace(upload.Url)) { StatusText.Text = upload.Status; return; }
        StatusText.Text = await ContinuityService.PublishAsync(AppSettingsStore.Load(), "continuity.file", file.Name, fileUrl: upload.Url, fileName: file.Name); await RefreshAsync();
    }
}
