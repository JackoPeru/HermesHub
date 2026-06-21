using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;

namespace NemoclawChat_Windows.Pages;

public sealed partial class CronPage : Page
{
    private IReadOnlyList<CronJobRecord> _jobs = [];

    public CronPage()
    {
        InitializeComponent();
        Loaded += CronPage_Loaded;
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
        var result = await GatewayService.LoadCronJobsAsync(AppSettingsStore.Load(), includeDisabled: true);
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

    private UIElement CreateJobCard(CronJobRecord job)
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
        AddDetail(details, "Stato", job.State);
        AddDetail(details, "Consegna", job.Deliver);
        AddDetail(details, "Origine", job.Origin);

        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
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
        await ExecuteActionAsync(sender, GatewayService.RunCronJobAsync);
    }

    private async void Pause_Click(object sender, RoutedEventArgs e)
    {
        await ExecuteActionAsync(sender, GatewayService.PauseCronJobAsync);
    }

    private async void Resume_Click(object sender, RoutedEventArgs e)
    {
        await ExecuteActionAsync(sender, GatewayService.ResumeCronJobAsync);
    }

    private async void Delete_Click(object sender, RoutedEventArgs e)
    {
        await ExecuteActionAsync(sender, GatewayService.DeleteCronJobAsync);
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
