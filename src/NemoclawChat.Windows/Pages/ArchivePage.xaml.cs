using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;

namespace NemoclawChat_Windows.Pages;

public sealed partial class ArchivePage : Page
{
    private List<ArchiveItem> _items = [];
    private ArchiveItem? _selected;

    public ArchivePage()
    {
        InitializeComponent();
        RenderResults();
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        RenderResults();
    }

    private void FilterBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ResultsPanel is not null)
        {
            RenderResults();
        }
    }

    private void NewChat_Click(object sender, RoutedEventArgs e)
    {
        Frame.Navigate(typeof(HomePage), new HomeNavigationRequest());
    }

    private async void UploadServer_Click(object sender, RoutedEventArgs e)
    {
        StatusText.Text = "Carico archivio sul gateway...";
        try
        {
            var result = await GatewayService.SaveHubConversationsAsync(AppSettingsStore.Load(), ChatArchiveStore.Load(includeDeleted: true));
            StatusText.Text = result.Status;
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Caricamento archivio non riuscito: {ex.Message}";
        }
    }

    private async void DownloadServer_Click(object sender, RoutedEventArgs e)
    {
        StatusText.Text = "Scarico archivio dal gateway...";
        try
        {
            var result = await GatewayService.LoadHubConversationsAsync(AppSettingsStore.Load());
            if (!result.Success || result.Items.Count == 0)
            {
                StatusText.Text = result.Status;
                return;
            }

            var changed = ChatArchiveStore.Merge(result.Items);
            ReloadItems();
            RenderResults();
            StatusText.Text = $"{result.Status} Import locale: {changed} aggiornati.";
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Download archivio non riuscito: {ex.Message}";
        }
    }

    private void OpenSelected_Click(object sender, RoutedEventArgs e)
    {
        if (_selected is null)
        {
            return;
        }

        StatusText.Text = $"Apro: {_selected.Title}.";
        Frame.Navigate(typeof(HomePage), new HomeNavigationRequest(_selected.ConversationId, _selected.Prompt));
    }

    private void PinSelected_Click(object sender, RoutedEventArgs e)
    {
        if (_selected is null)
        {
            return;
        }

        var project = ChatArchiveStore.SaveProject(_selected.Title, _selected.Description, _selected.Prompt);
        _selected = _selected with { ConversationId = project.Id, Kind = "Progetto" };
        StatusText.Text = $"Progetto salvato localmente: {_selected.Title}.";
        ReloadItems();
        RenderResults();
    }

    private async void DeleteSelected_Click(object sender, RoutedEventArgs e)
    {
        if (_selected?.ConversationId is null)
        {
            StatusText.Text = "Elemento non eliminabile.";
            return;
        }

        var title = _selected.Title;
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "Conferma eliminazione",
            Content = $"Vuoi eliminare davvero \"{title}\" dall'archivio locale?",
            PrimaryButtonText = "Elimina",
            CloseButtonText = "Annulla",
            DefaultButton = ContentDialogButton.Close
        };

        var result = await dialog.ShowAsync();
        if (result != ContentDialogResult.Primary)
        {
            StatusText.Text = "Eliminazione annullata.";
            return;
        }

        if (!ChatArchiveStore.Delete(_selected.ConversationId))
        {
            StatusText.Text = "Elemento non trovato.";
            return;
        }

        _selected = null;
        DetailTitleText.Text = "Seleziona elemento";
        DetailBodyText.Text = "Qui vedi dettagli e prompt dell'elemento selezionato.";
        OpenButton.IsEnabled = false;
        PinButton.IsEnabled = false;
        DeleteButton.IsEnabled = false;
        StatusText.Text = $"Eliminato: {title}.";
        RenderResults();
    }

    private void SelectItem_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: ArchiveItem item })
        {
            return;
        }

        _selected = item;
        DetailTitleText.Text = item.Title;
        DetailBodyText.Text = $"{item.Kind}\n{item.Description}\n\nPrompt:\n{item.Prompt}";
        OpenButton.IsEnabled = true;
        PinButton.IsEnabled = true;
        DeleteButton.IsEnabled = item.ConversationId is not null;
        ManageButton.IsEnabled = item.ConversationId is not null && item.Kind is "Chat" or "Task";
        StatusText.Text = "Elemento selezionato.";
    }

    private void ManageSelected_Click(object sender, RoutedEventArgs e)
    {
        if (_selected?.ConversationId is { } id) Frame.Navigate(typeof(ConversationManagerPage), id);
    }

    private void RenderResults()
    {
        ReloadItems();
        ResultsPanel.Children.Clear();

        var query = SearchBox.Text.Trim();
        var filter = SelectedFilter();
        var results = _items.Where(item =>
            (filter == "Tutto" || item.Kind == filter) &&
            (string.IsNullOrWhiteSpace(query) ||
             item.Title.Contains(query, StringComparison.OrdinalIgnoreCase) ||
             item.Description.Contains(query, StringComparison.OrdinalIgnoreCase) ||
             item.Prompt.Contains(query, StringComparison.OrdinalIgnoreCase)));

        foreach (var item in results)
        {
            ResultsPanel.Children.Add(CreateResultCard(item));
        }

        if (ResultsPanel.Children.Count == 0)
        {
            ResultsPanel.Children.Add(new TextBlock
            {
                Text = "Nessun risultato.",
                Foreground = (Brush)Application.Current.Resources["MutedTextBrush"]
            });
        }
    }

    private Button CreateResultCard(ArchiveItem item)
    {
        var glyph = item.Kind switch
        {
            "Task" => "\uE7BE",
            "Progetto" => "\uE8F1",
            _ => "\uE8BD"
        };
        var row = PremiumRows.LabeledRow(glyph, item.Title, item.Description);
        var button = PremiumRows.RowButton(row);
        button.Tag = item;
        return button.WithClick(SelectItem_Click);
    }

    private string SelectedFilter()
    {
        return (FilterBox.SelectedItem as ComboBoxItem)?.Content?.ToString() ?? "Tutto";
    }

    private void ReloadItems()
    {
        _items = ChatArchiveStore.Load()
            .Select(item => new ArchiveItem(
                item.Id,
                item.Title,
                item.Kind,
                string.IsNullOrWhiteSpace(item.Description)
                    ? $"Ultimo aggiornamento: {item.UpdatedAt:dd/MM/yyyy HH:mm}"
                    : item.Description,
                item.Prompt))
            .ToList();
    }

    private sealed record ArchiveItem(string? ConversationId, string Title, string Kind, string Description, string Prompt);
}

internal static class ArchiveButtonExtensions
{
    public static Button WithClick(this Button button, RoutedEventHandler handler)
    {
        button.Click += handler;
        return button;
    }
}
