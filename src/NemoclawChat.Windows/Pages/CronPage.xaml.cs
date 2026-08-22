using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;

namespace NemoclawChat_Windows.Pages;

public sealed partial class CronPage : Page
{
    private IReadOnlyList<CronJobRecord> _jobs = [];
    private HermesBotRoster? _botRoster;
    private bool _updatingProfileSelector;
    private string? _editingId;

    public CronPage()
    {
        InitializeComponent();
        Loaded += CronPage_Loaded;
        ProjectBox.Text = AppSettingsStore.Load().ActiveProjectId;
    }

    private async void CronPage_Loaded(object sender, RoutedEventArgs e)
    {
        Loaded -= CronPage_Loaded;
        await RefreshAsync();
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e)
    {
        await RefreshAsync();
    }

    private async Task RefreshAsync()
    {
        StatusText.Text = "Carico cron Hermes...";
        CronPanel.Children.Clear();
        await RefreshBotProfilesAsync();
        var result = await LoadJobsAsync();
        _jobs = result.Jobs;
        StatusText.Text = result.Status;
        RenderJobs();
    }

    private async Task RefreshBotProfilesAsync()
    {
        var selected = SelectedProfile;
        var roster = await GatewayService.LoadBotRosterAsync(AppSettingsStore.Load());
        _botRoster = roster;
        _updatingProfileSelector = true;
        try
        {
            BotProfileBox.Items.Clear();
            BotProfileBox.Items.Add(new ComboBoxItem { Content = "Globale / default", Tag = string.Empty });
            foreach (var bot in roster.Items)
            {
                BotProfileBox.Items.Add(new ComboBoxItem
                {
                    Content = $"{bot.DisplayName} ({bot.Profile})",
                    Tag = bot.Profile,
                    IsEnabled = roster.MultiplexEnabled
                });
            }

            SelectProfile(string.IsNullOrWhiteSpace(selected) ? string.Empty : selected);
            BotScopeHint.Text = roster.MultiplexEnabled
                ? "Le routine del profilo selezionato sono isolate dal gateway Hermes."
                : "Routine bot bloccate: il gateway non dichiara multiplexing profili attivo.";
        }
        finally
        {
            _updatingProfileSelector = false;
        }
    }

    private async Task<CronJobsResult> LoadJobsAsync()
    {
        var profile = SelectedProfile;
        var mux = string.IsNullOrWhiteSpace(profile) || _botRoster?.MultiplexEnabled == true;
        return await GatewayService.LoadCronJobsAsync(
            AppSettingsStore.Load(),
            includeDisabled: true,
            profile: string.IsNullOrWhiteSpace(profile) ? null : profile,
            profileMultiplexEnabled: mux);
    }

    private string SelectedProfile => (BotProfileBox.SelectedItem as ComboBoxItem)?.Tag as string ?? string.Empty;

    private bool SelectedProfileMultiplexEnabled =>
        string.IsNullOrWhiteSpace(SelectedProfile) || _botRoster?.MultiplexEnabled == true;

    private void SelectProfile(string profile)
    {
        var normalized = profile.Trim();
        for (var index = 0; index < BotProfileBox.Items.Count; index++)
        {
            if (BotProfileBox.Items[index] is ComboBoxItem item &&
                string.Equals(item.Tag as string, normalized, StringComparison.OrdinalIgnoreCase))
            {
                BotProfileBox.SelectedIndex = index;
                return;
            }
        }

        BotProfileBox.SelectedIndex = 0;
    }

    private async void BotProfileBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_updatingProfileSelector || BotProfileBox.SelectedItem is null) return;
        var result = await LoadJobsAsync();
        _jobs = result.Jobs;
        StatusText.Text = result.Status;
        RenderJobs();
    }

    private void RenderJobs()
    {
        CronPanel.Children.Clear();
        if (_jobs.Count == 0)
        {
            CronPanel.Children.Add(new Border
            {
                Padding = new Thickness(20),
                Background = (Brush)Application.Current.Resources["AssistantBubbleBrush"],
                CornerRadius = new CornerRadius(18),
                Child = new StackPanel
                {
                    Spacing = 8,
                    Children =
                    {
                        new TextBlock
                        {
                            Text = "Nessun cron trovato.",
                            Foreground = new SolidColorBrush(Microsoft.UI.Colors.White),
                            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold
                        },
                        new TextBlock
                        {
                            Text = "Crea automazioni dalla chat, per esempio: programma un briefing ogni mattina alle 8.",
                            Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
                            TextWrapping = TextWrapping.WrapWholeWords
                        }
                    }
                }
            });
            return;
        }

        foreach (var job in _jobs)
        {
            CronPanel.Children.Add(CreateJobCard(job));
        }
    }

    private Border CreateJobCard(CronJobRecord job)
    {
        var stateBrush = job.Enabled
            ? (Brush)Application.Current.Resources["AccentGreenBrush"]
            : new SolidColorBrush(Microsoft.UI.Colors.Goldenrod);

        var header = new Grid { ColumnSpacing = 12 };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.Children.Add(new TextBlock
        {
            Text = job.Name,
            Foreground = new SolidColorBrush(Microsoft.UI.Colors.White),
            FontSize = 18,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            TextWrapping = TextWrapping.WrapWholeWords
        });
        var state = new TextBlock
        {
            Text = job.Enabled ? "Attivo" : "In pausa",
            Foreground = stateBrush,
            FontSize = 13,
            VerticalAlignment = VerticalAlignment.Center
        };
        Grid.SetColumn(state, 1);
        header.Children.Add(state);

        var details = new StackPanel { Spacing = 6 };
        AddDetail(details, "ID", job.Id);
        AddDetail(details, "Programmazione", job.Schedule);
        AddDetail(details, "Prossima esecuzione", job.NextRunAt);
        AddDetail(details, "Ultima esecuzione", job.LastRunAt);
        AddDetail(details, "Ultimo output", job.LastStatus);
        AddDetail(details, "Stato", job.State);
        AddDetail(details, "Consegna", job.Deliver);
        AddDetail(details, "Origine", job.Origin);
        AddDetail(details, "Profilo", job.Profile);

        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        actions.Children.Add(CreateActionButton("Modifica", job.Id, Edit_Click));
        actions.Children.Add(CreateActionButton("Esegui ora", job.Id, Run_Click));
        actions.Children.Add(CreateActionButton(job.Enabled ? "Pausa" : "Riprendi", job.Id, job.Enabled ? Pause_Click : Resume_Click));
        actions.Children.Add(CreateActionButton("Elimina", job.Id, Delete_Click));

        return new Border
        {
            Padding = new Thickness(20),
            Background = (Brush)Application.Current.Resources["SurfaceBrush"],
            BorderBrush = new SolidColorBrush(Microsoft.UI.ColorHelper.FromArgb(255, 58, 58, 58)),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(18),
            Child = new StackPanel
            {
                Spacing = 12,
                Children =
                {
                    header,
                    details,
                    new TextBlock
                    {
                        Text = string.IsNullOrWhiteSpace(job.Prompt) ? "Prompt non disponibile." : job.Prompt,
                        Foreground = new SolidColorBrush(Microsoft.UI.Colors.White),
                        TextWrapping = TextWrapping.WrapWholeWords
                    },
                    actions
                }
            }
        };
    }

    private static void AddDetail(StackPanel panel, string label, string value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return;
        }

        panel.Children.Add(new TextBlock
        {
            Text = $"{label}: {value}",
            Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
            FontSize = 12,
            TextWrapping = TextWrapping.WrapWholeWords
        });
    }

    private static Button CreateActionButton(string label, string id, RoutedEventHandler handler)
    {
        var button = new Button
        {
            Content = label,
            Tag = id,
            Padding = new Thickness(12, 6, 12, 6)
        };
        button.Click += handler;
        return button;
    }

    private async void Run_Click(object sender, RoutedEventArgs e)
    {
        await ExecuteActionAsync(sender, (settings, id) => GatewayService.RunCronJobAsync(settings, id, SelectedProfile, SelectedProfileMultiplexEnabled));
    }

    private async void Pause_Click(object sender, RoutedEventArgs e)
    {
        await ExecuteActionAsync(sender, (settings, id) => GatewayService.PauseCronJobAsync(settings, id, SelectedProfile, SelectedProfileMultiplexEnabled));
    }

    private async void Resume_Click(object sender, RoutedEventArgs e)
    {
        await ExecuteActionAsync(sender, (settings, id) => GatewayService.ResumeCronJobAsync(settings, id, SelectedProfile, SelectedProfileMultiplexEnabled));
    }

    private async void Delete_Click(object sender, RoutedEventArgs e)
    {
        await ExecuteActionAsync(sender, (settings, id) => GatewayService.DeleteCronJobAsync(settings, id, SelectedProfile, SelectedProfileMultiplexEnabled));
    }

    private void Edit_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: string id }) return;
        var job = _jobs.FirstOrDefault(candidate => candidate.Id.Equals(id, StringComparison.OrdinalIgnoreCase));
        if (job is null) return;
        SelectProfile(job.Profile);
        var definition = AutomationPromptCodec.Decode(job.Prompt);
        _editingId = job.Id;
        EditorTitle.Text = $"Modifica · {job.Name}";
        NameBox.Text = job.Name;
        TaskPromptBox.Text = definition.TaskPrompt;
        ConditionBox.Text = definition.Condition;
        TimeoutBox.Value = definition.TimeoutSeconds;
        RetryBox.Value = definition.RetryCount;
        NotificationBox.Text = definition.NotificationTemplate;
        ProjectBox.Text = definition.ProjectId;
        DependenciesBox.Text = definition.Dependencies;
        DeliverBox.Text = job.Deliver;
        AdvancedCronBox.Text = job.Schedule;
        FrequencyBox.SelectedIndex = 3;
        UpdateSchedulePreview();
    }

    private void ClearEditor_Click(object sender, RoutedEventArgs e)
    {
        _editingId = null;
        EditorTitle.Text = "Nuova automazione";
        NameBox.Text = string.Empty;
        TaskPromptBox.Text = string.Empty;
        ConditionBox.Text = string.Empty;
        TimeoutBox.Value = 900;
        RetryBox.Value = 0;
        NotificationBox.Text = string.Empty;
        ProjectBox.Text = AppSettingsStore.Load().ActiveProjectId;
        DependenciesBox.Text = string.Empty;
        DeliverBox.Text = "local";
        FrequencyBox.SelectedIndex = 1;
        TimeBox.Text = "08:00";
        DaysBox.Text = "1,2,3,4,5";
        AdvancedCronBox.Text = string.Empty;
        UpdateSchedulePreview();
    }

    private void DuplicateEditor_Click(object sender, RoutedEventArgs e)
    {
        _editingId = null;
        NameBox.Text = string.IsNullOrWhiteSpace(NameBox.Text) ? string.Empty : $"{NameBox.Text.Trim()} copia";
        EditorTitle.Text = "Duplica automazione";
        StatusText.Text = "Copia pronta: modifica e salva.";
    }

    private async void SaveEditor_Click(object sender, RoutedEventArgs e)
    {
        var name = NameBox.Text.Trim();
        var task = TaskPromptBox.Text.Trim();
        var schedule = BuildSchedule();
        if (string.IsNullOrWhiteSpace(name) || string.IsNullOrWhiteSpace(task) || string.IsNullOrWhiteSpace(schedule))
        {
            StatusText.Text = "Nome, attività e programmazione obbligatori.";
            return;
        }

        var definition = ReadDefinition(task);
        StatusText.Text = "Salvo automazione...";
        var selectedProfile = SelectedProfile;
        if (!string.IsNullOrWhiteSpace(selectedProfile) && !SelectedProfileMultiplexEnabled)
        {
            StatusText.Text = "Routine bot non salvata: multiplexing profili non pronto.";
            return;
        }
        StatusText.Text = await GatewayService.SaveCronJobAsync(
            AppSettingsStore.Load(), _editingId, name, schedule,
            AutomationPromptCodec.Encode(definition), DeliverBox.Text,
            string.IsNullOrWhiteSpace(selectedProfile) ? null : selectedProfile,
            SelectedProfileMultiplexEnabled);
        if (!StatusText.Text.StartsWith("Automazione non", StringComparison.Ordinal))
        {
            ClearEditor_Click(sender, e);
            await RefreshAsync();
        }
    }

    private async void TestEditor_Click(object sender, RoutedEventArgs e)
    {
        var task = TaskPromptBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(task))
        {
            StatusText.Text = "Attività obbligatoria per prova.";
            return;
        }

        StatusText.Text = "Prova in corso, job non salvato...";
        var result = await GatewayService.SendWorkspaceRunAsync(
            AppSettingsStore.Load(), "Automation", AutomationPromptCodec.Encode(ReadDefinition(task)));
        StatusText.Text = $"{result.Status} {result.Result}".Trim();
    }

    private AutomationDefinition ReadDefinition(string task) => new(
        task,
        ConditionBox.Text.Trim(),
        (int)Math.Clamp(double.IsFinite(TimeoutBox.Value) ? TimeoutBox.Value : 900, 10, 86_400),
        (int)Math.Clamp(double.IsFinite(RetryBox.Value) ? RetryBox.Value : 0, 0, 10),
        NotificationBox.Text.Trim(),
        ProjectBox.Text.Trim(),
        DependenciesBox.Text.Trim());

    private void FrequencyBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (AdvancedCronBox is null) return;
        AdvancedCronBox.Visibility = FrequencyBox.SelectedIndex == 3 ? Visibility.Visible : Visibility.Collapsed;
        UpdateSchedulePreview();
    }

    private string BuildSchedule()
    {
        if (FrequencyBox.SelectedIndex == 0) return "0 * * * *";
        if (FrequencyBox.SelectedIndex == 3) return AdvancedCronBox.Text.Trim();
        if (!TimeOnly.TryParse(TimeBox.Text.Trim(), out var time)) return string.Empty;
        return FrequencyBox.SelectedIndex == 2
            ? $"{time.Minute} {time.Hour} * * {DaysBox.Text.Trim()}"
            : $"{time.Minute} {time.Hour} * * *";
    }

    private void UpdateSchedulePreview()
    {
        if (SchedulePreviewText is null) return;
        var schedule = BuildSchedule();
        SchedulePreviewText.Text = string.IsNullOrWhiteSpace(schedule)
            ? "Programmazione non valida."
            : $"Espressione: {schedule}. Anteprima: {DescribeSchedule()}";
    }

    private string DescribeSchedule()
    {
        return FrequencyBox.SelectedIndex switch
        {
            0 => "al minuto 0 di ogni ora",
            1 => $"ogni giorno alle {TimeBox.Text.Trim()}",
            2 => $"giorni {DaysBox.Text.Trim()} alle {TimeBox.Text.Trim()}",
            _ => "cron avanzato; prossime date calcolate dal gateway"
        };
    }

    private async Task ExecuteActionAsync(object sender, Func<AppSettings, string, Task<string>> action)
    {
        if (sender is not FrameworkElement { Tag: string id })
        {
            return;
        }

        StatusText.Text = "Aggiorno cron...";
        StatusText.Text = await action(AppSettingsStore.Load(), id);
        await RefreshAsync();
    }
}
