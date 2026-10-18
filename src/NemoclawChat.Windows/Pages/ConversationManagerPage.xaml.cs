using System.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;
using WinRT.Interop;
using Windows.Storage;
using Windows.Storage.Pickers;

namespace NemoclawChat_Windows.Pages;

public sealed partial class ConversationManagerPage : Page
{
    private string _conversationId = string.Empty;
    private ConversationRecord? _conversation;
    private readonly HashSet<string> _selectedMessageIds = new(StringComparer.OrdinalIgnoreCase);

    public ConversationManagerPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(Microsoft.UI.Xaml.Navigation.NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _conversationId = e.Parameter as string ?? string.Empty;
        Reload();
    }

    private void Reload()
    {
        _conversation = ChatArchiveStore.Find(_conversationId);
        if (_conversation is null)
        {
            StatusText.Text = "Conversazione non trovata.";
            return;
        }

        TitleText.Text = _conversation.Title;
        FolderBox.Text = _conversation.Folder;
        TagsBox.Text = string.Join(", ", _conversation.Tags);
        ProjectBox.Text = _conversation.ProjectId;
        LinksBox.Text = string.Join(", ", _conversation.LinkedConversationIds);
        SummaryBox.Text = _conversation.Summary;
        RenderMessages();
        RenderBranches();
    }

    private void RenderMessages()
    {
        MessagesPanel.Children.Clear();
        if (_conversation is null) return;
        foreach (var message in _conversation.Messages)
        {
            var editor = new TextBox { Text = message.Text, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, MinHeight = 68 };
            var selected = new CheckBox { Content = "Seleziona", IsChecked = _selectedMessageIds.Contains(message.Id), Tag = message.Id };
            selected.Checked += (_, _) => _selectedMessageIds.Add(message.Id);
            selected.Unchecked += (_, _) => _selectedMessageIds.Remove(message.Id);
            var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            actions.Children.Add(selected);
            actions.Children.Add(ActionButton("Salva modifica", () => { ChatArchiveStore.UpdateMessage(_conversationId, message.Id, editor.Text); Reload(); }));
            actions.Children.Add(ActionButton(message.IsBookmarked ? "Rimuovi segnalibro" : "Segnalibro", () => { ChatArchiveStore.ToggleBookmark(_conversationId, message.Id); Reload(); }));
            actions.Children.Add(ActionButton("Crea ramo", () => CreateBranch(message)));
            actions.Children.Add(ActionButton("Rigenera alternativa", () => RegenerateFrom(message)));
            actions.Children.Add(ActionButton("Nuova chat", () => Frame.Navigate(typeof(HomePage), new HomeNavigationRequest(Prompt: $"Continua da questo messaggio:\n\n{message.Text}"))));
            actions.Children.Add(ActionButton("Nuovo progetto", () => { var project = ChatArchiveStore.SaveProject($"Progetto da {_conversation.Title}", message.Text, message.Text); Frame.Navigate(typeof(ProjectsPage), project.Id); }));
            MessagesPanel.Children.Add(new StackPanel
            {
                Spacing = 8,
                Children = { PremiumRows.MetaLine($"{message.Author} · {message.Timestamp:g}"), editor, actions }
            });
            MessagesPanel.Children.Add(PremiumRows.Hairline());
        }
    }

    private void RenderBranches()
    {
        BranchesPanel.Children.Clear();
        if (_conversation is null) return;
        var branches = ChatArchiveStore.Load().Where(item => item.ParentConversationId == _conversation.Id || item.Id == _conversation.ParentConversationId || _conversation.LinkedConversationIds.Contains(item.Id)).DistinctBy(item => item.Id).ToList();
        foreach (var branch in branches)
        {
            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            row.Children.Add(new TextBlock { Text = $"{branch.Title} · {branch.Messages.Count} messaggi", Foreground = (Brush)Application.Current.Resources["TextBrush"], VerticalAlignment = VerticalAlignment.Center });
            row.Children.Add(ActionButton("Apri", () => { _conversationId = branch.Id; _selectedMessageIds.Clear(); Reload(); }));
            row.Children.Add(ActionButton("Confronta", () => CompareBranch(branch)));
            BranchesPanel.Children.Add(row);
        }
        if (branches.Count == 0) BranchesPanel.Children.Add(new TextBlock { Text = "Nessun ramo.", Foreground = (Brush)Application.Current.Resources["MutedTextBrush"] });
    }

    private static Button ActionButton(string text, Action action)
    {
        var button = new Button { Content = text };
        button.Click += (_, _) => action();
        return button;
    }

    private void SaveMetadata_Click(object sender, RoutedEventArgs e)
    {
        ChatArchiveStore.UpdateConversationMetadata(_conversationId, FolderBox.Text, Split(TagsBox.Text), ProjectBox.Text, Split(LinksBox.Text), SummaryBox.Text);
        StatusText.Text = "Cartella, tag, progetto, collegamenti e riepilogo salvati.";
        Reload();
    }

    private void Summarize_Click(object sender, RoutedEventArgs e)
    {
        if (_conversation is null) return;
        var transcript = string.Join("\n", _conversation.Messages.TakeLast(40).Select(message => $"{message.Author}: {message.Text}"));
        Frame.Navigate(typeof(HomePage), new HomeNavigationRequest(Prompt: $"Riassumi questa conversazione in modo operativo. Restituisci decisioni, attività aperte e riferimenti.\n\n{transcript}"));
    }

    private void DeleteSelected_Click(object sender, RoutedEventArgs e)
    {
        if (_selectedMessageIds.Count == 0) { StatusText.Text = "Seleziona almeno un messaggio."; return; }
        ChatArchiveStore.DeleteMessages(_conversationId, _selectedMessageIds);
        _selectedMessageIds.Clear();
        StatusText.Text = "Porzione selezionata eliminata.";
        Reload();
    }

    private void CreateBranch(ChatMessageRecord message)
    {
        var branch = ChatArchiveStore.CreateBranch(_conversationId, message.Id);
        StatusText.Text = branch is null ? "Ramo non creato." : $"Ramo creato: {branch.Title}.";
        Reload();
    }

    private void RegenerateFrom(ChatMessageRecord message)
    {
        var branch = ChatArchiveStore.CreateBranch(_conversationId, message.Id, $"{_conversation?.Title} · alternativa");
        if (branch is null) return;
        Frame.Navigate(typeof(HomePage), new HomeNavigationRequest(branch.Id, "Rigenera una risposta alternativa all'ultimo messaggio, senza ripetere la risposta precedente."));
    }

    private async void CompareBranch(ConversationRecord branch)
    {
        if (_conversation is null) return;
        var left = string.Join("\n", _conversation.Messages.Select(message => $"{message.Author}: {message.Text}"));
        var right = string.Join("\n", branch.Messages.Select(message => $"{message.Author}: {message.Text}"));
        var dialog = new ContentDialog { XamlRoot = XamlRoot, Title = $"Confronto · {branch.Title}", Content = new TextBox { Text = $"RAMO CORRENTE\n{left}\n\nRAMO SELEZIONATO\n{right}", IsReadOnly = true, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, MinWidth = 720, MinHeight = 420 }, CloseButtonText = "Chiudi" };
        await dialog.ShowAsync();
    }

    private async void ExportMarkdown_Click(object sender, RoutedEventArgs e) => await ExportAsync("md", "Markdown", ".md");
    private async void ExportJson_Click(object sender, RoutedEventArgs e) => await ExportAsync("json", "JSON", ".json");
    private async void ExportPdf_Click(object sender, RoutedEventArgs e) => await ExportAsync("pdf", "PDF", ".pdf");
    private async void ExportHtml_Click(object sender, RoutedEventArgs e) => await ExportAsync("html", "HTML", ".html");

    private async Task ExportAsync(string format, string label, string extension)
    {
        if (_conversation is null) return;
        var picker = new FileSavePicker { SuggestedFileName = SafeFileName(_conversation.Title) };
        picker.FileTypeChoices.Add(label, [extension]);
        if (App.MainWindow is not null) InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.MainWindow));
        var file = await picker.PickSaveFileAsync();
        if (file is null) return;
        await FileIO.WriteBytesAsync(file, ConversationExportService.Export(_conversation, format));
        StatusText.Text = $"Esportato: {file.Name}.";
    }

    private async void Import_Click(object sender, RoutedEventArgs e)
    {
        var picker = new FileOpenPicker();
        picker.FileTypeFilter.Add(".json");
        picker.FileTypeFilter.Add(".md");
        picker.FileTypeFilter.Add(".markdown");
        if (App.MainWindow is not null) InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.MainWindow));
        var file = await picker.PickSingleFileAsync();
        if (file is null) return;
        var content = await FileIO.ReadTextAsync(file);
        var imported = file.FileType.Equals(".json", StringComparison.OrdinalIgnoreCase) ? ConversationExportService.ImportJson(content) : ConversationExportService.ImportMarkdown(content, Path.GetFileNameWithoutExtension(file.Name));
        if (imported is null) { StatusText.Text = "Formato import non valido."; return; }
        ChatArchiveStore.Merge([imported]);
        _conversationId = imported.Id;
        StatusText.Text = $"Importata: {imported.Title}.";
        Reload();
    }

    private static string[] Split(string value) => value.Split([',', ';', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
    private static string SafeFileName(string value) => string.Concat(value.Select(ch => Path.GetInvalidFileNameChars().Contains(ch) ? '_' : ch)).Trim();
}
