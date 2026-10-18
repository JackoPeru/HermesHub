using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;
using Windows.System;

namespace NemoclawChat_Windows.Pages;

public sealed partial class SearchPage : Page
{
    private IReadOnlyList<UniversalSearchResult> _results = [];
    public SearchPage() { InitializeComponent(); Loaded += (_, _) => QueryBox.Focus(FocusState.Programmatic); }
    private async void Search_Click(object sender, RoutedEventArgs e) => await SearchAsync();
    private async void QueryBox_KeyDown(object sender, KeyRoutedEventArgs e) { if (e.Key == VirtualKey.Enter) { e.Handled = true; await SearchAsync(); } }
    private async Task SearchAsync()
    {
        StatusText.Text = "Ricerca in corso...";
        _results = await UniversalSearchService.SearchAsync(QueryBox.Text, AppSettingsStore.Load());
        var filter = (TypeFilter.SelectedItem as ComboBoxItem)?.Content?.ToString() ?? "Tutto";
        var visible = filter == "Tutto" ? _results : _results.Where(result => result.Kind.Equals(filter, StringComparison.OrdinalIgnoreCase)).ToList();
        ResultsPanel.Children.Clear();
        foreach (var result in visible)
        {
            var glyph = result.Kind switch
            {
                "Task" => "\uE7BE",
                "Progetto" => "\uE8F1",
                "Artifact" => "\uE160",
                "Cron" => "\uE7BE",
                "Notifica" => "\uE7B8",
                "Memoria" => "\uE1C6",
                _ => "\uE8BD"
            };
            var row = PremiumRows.LabeledRow(glyph, $"{result.Kind} · {result.Title}", result.Snippet);
            var button = PremiumRows.RowButton(row);
            button.Tag = result;
            button.Click += Open_Click; ResultsPanel.Children.Add(button);
        }
        StatusText.Text = $"{visible.Count} risultati.";
    }
    private void Open_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: UniversalSearchResult result }) return;
        if (result.Kind is "Chat" or "Task") Frame.Navigate(typeof(HomePage), new HomeNavigationRequest(result.Id));
        else if (result.Kind == "Progetto") Frame.Navigate(typeof(ProjectsPage));
        else if (result.Kind == "Artifact") Frame.Navigate(typeof(ArtifactsPage));
        else if (result.Kind == "Cron") Frame.Navigate(typeof(CronPage));
        else if (result.Kind == "Notifica") Frame.Navigate(typeof(NotificationsPage));
        else if (result.Kind == "Memoria") Frame.Navigate(typeof(SettingsPage));
    }
}
