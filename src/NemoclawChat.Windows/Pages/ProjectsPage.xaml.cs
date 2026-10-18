using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;

namespace NemoclawChat_Windows.Pages;

public sealed partial class ProjectsPage : Page
{
    private ConversationRecord? _selected;

    public ProjectsPage()
    {
        InitializeComponent();
        Loaded += ProjectsPage_Loaded;
    }

    private void ProjectsPage_Loaded(object sender, RoutedEventArgs e)
    {
        Loaded -= ProjectsPage_Loaded;
        RenderProjectList();
        var projects = ChatArchiveStore.Projects();
        var activeId = AppSettingsStore.Load().ActiveProjectId;
        var initial = projects.FirstOrDefault(project => project.Id.Equals(activeId, StringComparison.OrdinalIgnoreCase))
                      ?? (projects.Count > 0 ? projects[0] : null);
        if (initial is not null)
        {
            SelectProject(initial, activate: false);
        }
    }

    private void RenderProjectList()
    {
        ProjectListPanel.Children.Clear();
        var projects = ChatArchiveStore.Projects();
        if (projects.Count == 0)
        {
            ProjectListPanel.Children.Add(new TextBlock
            {
                Text = "Nessun progetto.",
                Foreground = (Brush)Application.Current.Resources["MutedTextBrush"]
            });
            return;
        }

        var activeId = AppSettingsStore.Load().ActiveProjectId;
        foreach (var project in projects)
        {
            var isActive = project.Id.Equals(activeId, StringComparison.OrdinalIgnoreCase);
            var row = PremiumRows.LabeledRow("\uE8F1", project.Title, isActive ? "Attivo" : null);
            var button = PremiumRows.RowButton(row);
            button.Tag = project;
            button.Click += Project_Click;
            ProjectListPanel.Children.Add(button);
        }
    }

    private void Project_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: ConversationRecord project })
        {
            SelectProject(project, activate: true);
            StatusText.Text = "Progetto selezionato. Il contesto verra' applicato automaticamente.";
        }
    }

    private void SelectProject(ConversationRecord project, bool activate)
    {
        _selected = project;
        DashboardTitle.Text = project.Title;
        TitleBox.Text = project.Title;
        SystemPromptBox.Text = project.ProjectInstructions;
        NewChatButton.IsEnabled = true;
        if (activate)
        {
            SetActiveProject(project);
            RenderProjectList();
        }
        ActiveBadge.Text = AppSettingsStore.Load().ActiveProjectId.Equals(project.Id, StringComparison.OrdinalIgnoreCase)
            ? "PROGETTO ATTIVO"
            : string.Empty;
    }

    private void NewProject_Click(object sender, RoutedEventArgs e)
    {
        _selected = null;
        DashboardTitle.Text = "Nuovo progetto";
        ActiveBadge.Text = string.Empty;
        TitleBox.Text = string.Empty;
        SystemPromptBox.Text = string.Empty;
        NewChatButton.IsEnabled = false;
        StatusText.Text = "Inserisci nome e system prompt facoltativo.";
        TitleBox.Focus(FocusState.Programmatic);
    }

    private void SaveProject_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var project = ChatArchiveStore.SaveProjectWorkspace(
                _selected?.Id,
                TitleBox.Text,
                _selected?.Description ?? string.Empty,
                _selected?.WorkspacePath ?? string.Empty,
                _selected?.RepositoryUrl ?? string.Empty,
                SystemPromptBox.Text,
                _selected?.ProjectMemory ?? string.Empty,
                _selected?.AuthorizedTools ?? []);
            _selected = project;
            SetActiveProject(project);
            RenderProjectList();
            SelectProject(project, activate: false);
            StatusText.Text = "Progetto salvato e attivato.";
        }
        catch (ArgumentException ex)
        {
            StatusText.Text = ex.Message;
        }
    }

    private static void SetActiveProject(ConversationRecord project)
    {
        var settings = AppSettingsStore.Load();
        settings.ActiveProjectId = project.Id;
        settings.ActiveProjectName = project.Title;
        AppSettingsStore.Save(settings);
    }

    private void NewProjectChat_Click(object sender, RoutedEventArgs e)
    {
        if (_selected is null)
        {
            return;
        }
        SetActiveProject(_selected);
        Frame.Navigate(typeof(HomePage), new HomeNavigationRequest());
    }
}
