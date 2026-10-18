using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;
using Windows.System;

namespace NemoclawChat_Windows.Pages;

public sealed partial class ArtifactsPage : Page
{
    private ConversationRecord? _selected;

    public ArtifactsPage()
    {
        InitializeComponent();
        Loaded += (_, _) => Render();
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e) => Render();

    private void Render()
    {
        ArtifactPanel.Children.Clear();
        var artifacts = ChatArchiveStore.Artifacts(SearchBox.Text, ProjectFilterBox.Text);
        foreach (var artifact in artifacts)
        {
            var row = PremiumRows.LabeledRow("\uE7C3", artifact.Title, $"{artifact.ArtifactType} · v{artifact.Version} · {artifact.ProjectId}");
            var button = PremiumRows.RowButton(row);
            button.Tag = artifact;
            button.Click += Select_Click;
            ArtifactPanel.Children.Add(button);
        }
        if (artifacts.Count == 0) ArtifactPanel.Children.Add(new TextBlock { Text = "Nessun artifact indicizzato.", Foreground = (Brush)Application.Current.Resources["MutedTextBrush"] });
    }

    private void Select_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: ConversationRecord artifact }) return;
        _selected = artifact;
        TitleText.Text = artifact.Title;
        MetaText.Text = $"{artifact.ArtifactType} · {artifact.ArtifactMimeType} · versione {artifact.Version}\nFile: {artifact.ArtifactFileName}\nProgetto: {artifact.ProjectId}\nChat: {artifact.SourceConversationId}\nRun: {artifact.SourceRunId}";
        DescriptionText.Text = artifact.Description;
        RenameBox.Text = artifact.Title;
        TagsBox.Text = string.Join(", ", artifact.Tags);
        RenderVersions(artifact);
    }

    private void RenderVersions(ConversationRecord artifact)
    {
        VersionsPanel.Children.Clear();
        var key = string.IsNullOrWhiteSpace(artifact.ArtifactFileName) ? artifact.Title : artifact.ArtifactFileName;
        var versions = ChatArchiveStore.Artifacts().Where(item =>
            (string.IsNullOrWhiteSpace(item.ArtifactFileName) ? item.Title : item.ArtifactFileName).Equals(key, StringComparison.OrdinalIgnoreCase));
        foreach (var version in versions.OrderByDescending(item => item.Version))
        {
            VersionsPanel.Children.Add(new TextBlock { Text = $"v{version.Version} · {version.UpdatedAt.LocalDateTime:g} · {version.Description}", Foreground = new SolidColorBrush(Microsoft.UI.Colors.White), TextWrapping = TextWrapping.Wrap });
        }
    }

    private void SaveMetadata_Click(object sender, RoutedEventArgs e)
    {
        if (_selected is null) return;
        ChatArchiveStore.Rename(_selected.Id, RenameBox.Text.Trim());
        ChatArchiveStore.SetArtifactTags(_selected.Id, TagsBox.Text.Split([',', ';'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
        _selected = ChatArchiveStore.Find(_selected.Id);
        StatusText.Text = "Metadata artifact salvati; sync gateway in coda.";
        Render();
    }

    private void OpenSource_Click(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrWhiteSpace(_selected?.SourceConversationId)) Frame.Navigate(typeof(HomePage), new HomeNavigationRequest(_selected.SourceConversationId));
    }

    private async void OpenArtifact_Click(object sender, RoutedEventArgs e)
    {
        if (_selected is null || string.IsNullOrWhiteSpace(_selected.ArtifactUrl)) { StatusText.Text = "Artifact senza URL apribile."; return; }
        var raw = _selected.ArtifactUrl;
        var uri = Uri.TryCreate(raw, UriKind.Absolute, out var absolute) ? absolute : new Uri($"{GatewayService.HermesRoot(AppSettingsStore.Load()).TrimEnd('/')}/{raw.TrimStart('/')}");
        StatusText.Text = await Launcher.LaunchUriAsync(uri) ? "Artifact aperto nell'app predefinita." : "Apertura artifact fallita.";
    }

    private void Regenerate_Click(object sender, RoutedEventArgs e)
    {
        if (_selected is null) return;
        Frame.Navigate(typeof(HomePage), new HomeNavigationRequest(Prompt: $"Rigenera artifact '{_selected.Title}' versione {_selected.Version}, mantenendo progetto {_selected.ProjectId}. Sorgente: chat {_selected.SourceConversationId}."));
    }
}
