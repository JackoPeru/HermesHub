using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace NemoclawChat_Windows.Pages;

[System.Diagnostics.CodeAnalysis.SuppressMessage(
    "Design",
    "CA1001:Types that own disposable fields should be disposable",
    Justification = "Pagina WinUI: il CTS viene cancellato e rilasciato nel ciclo Unloaded; il framework non consuma IDisposable.")]
public sealed partial class CharactersPage : Page
{
    private readonly DispatcherTimer _pollTimer;
    private CancellationTokenSource? _generateCts;
    private bool _polling;
    private bool _confirmDelete;

    private List<CharacterSummary> _items = [];
    private string? _detailId;
    private CharacterManifest? _detailManifest;

    private bool _wizardOpen;
    private int _wizardStep = 1;
    private string? _wizardId;

    public CharactersPage()
    {
        InitializeComponent();
        _pollTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
        _pollTimer.Tick += PollTimer_Tick;
        Loaded += CharactersPage_Loaded;
        Unloaded += CharactersPage_Unloaded;
    }

    private void CharactersPage_Loaded(object sender, RoutedEventArgs e)
    {
        _pollTimer.Start();
        _ = RefreshListAsync();
    }

    private void CharactersPage_Unloaded(object sender, RoutedEventArgs e)
    {
        _pollTimer.Stop();
        try { _generateCts?.Cancel(); } catch { }
    }

    private async void PollTimer_Tick(object? sender, object e)
    {
        if (_polling)
        {
            return;
        }

        _polling = true;
        try
        {
            if (_detailId is not null)
            {
                await RefreshDetailAsync(silent: true);
            }
            else if (_wizardOpen && _wizardId is not null && _wizardStep is 3 or 4)
            {
                await RefreshWizardStatusAsync();
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Trace.WriteLine($"[Characters] poll: {ex.Message}");
        }
        finally
        {
            _polling = false;
        }
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (_detailId is not null)
            {
                await RefreshDetailAsync(silent: false);
            }
            else if (_wizardOpen && _wizardId is not null)
            {
                await RefreshWizardStatusAsync();
            }
            else
            {
                await RefreshListAsync();
            }
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Aggiornamento fallito: {ex.Message}";
        }
    }

    private async Task RefreshListAsync()
    {
        StatusText.Text = "Carico personaggi…";
        var settings = AppSettingsStore.Load();
        var (list, message) = await CharactersService.LoadCharactersAsync(settings);
        _items = list.ToList();
        StatusText.Text = message;
        RenderCards();
    }

    private void RenderCards()
    {
        CardsPanel.Children.Clear();
        if (_items.Count == 0)
        {
            var stack = new StackPanel { Spacing = 8 };
            stack.Children.Add(new TextBlock { Text = "Nessun personaggio", Foreground = new SolidColorBrush(Microsoft.UI.Colors.White), FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
            stack.Children.Add(new TextBlock
            {
                Text = "Tocca + Nuovo: dai un nome, carica 20-80 foto della stessa persona, Hermes fa il resto.",
                Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
                TextWrapping = TextWrapping.Wrap,
            });
            CardsPanel.Children.Add(stack);
            return;
        }

        foreach (var item in _items)
        {
            var stack = new StackPanel { Spacing = 6 };

            var header = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
            var avatar = new Border
            {
                Width = 44,
                Height = 44,
                CornerRadius = new CornerRadius(999),
                Background = (Brush)Application.Current.Resources["ElevatedSurfaceBrush"],
                Child = new TextBlock
                {
                    Text = string.IsNullOrEmpty(item.Name) ? "?" : item.Name[..1].ToUpperInvariant(),
                    Foreground = new SolidColorBrush(Microsoft.UI.Colors.White),
                    FontSize = 20,
                    FontWeight = Microsoft.UI.Text.FontWeights.Bold,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center,
                    TextAlignment = TextAlignment.Center,
                },
            };
            var titles = new StackPanel { Spacing = 2 };
            titles.Children.Add(new TextBlock { Text = item.Name, Foreground = new SolidColorBrush(Microsoft.UI.Colors.White), FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
            titles.Children.Add(new TextBlock { Text = CharactersService.StatusLabel(item.Status), Foreground = (Brush)Application.Current.Resources["MutedTextBrush"], FontSize = 12 });
            header.Children.Add(avatar);
            header.Children.Add(titles);
            stack.Children.Add(header);

            stack.Children.Add(new TextBlock
            {
                Text = CharactersService.IdentityLine(item),
                Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
                FontSize = 12,
            });

            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            var useButton = new Button { Content = "Usa", Tag = item.Id, IsEnabled = item.Status == "ready" };
            useButton.Click += UseCard_Click;
            var openButton = new Button { Content = "Apri", Tag = item.Id };
            openButton.Click += OpenCard_Click;
            row.Children.Add(useButton);
            row.Children.Add(openButton);
            stack.Children.Add(row);

            CardsPanel.Children.Add(stack);
            CardsPanel.Children.Add(PremiumRows.Hairline());
        }
    }

    private void OpenCard_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (sender is Button { Tag: string id })
            {
                OpenDetail(id);
            }
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Apertura fallita: {ex.Message}";
        }
    }

    private void UseCard_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (sender is Button { Tag: string id })
            {
                OpenDetail(id, openGenerate: true);
            }
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Apertura fallita: {ex.Message}";
        }
    }

    private void New_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            OpenWizard();
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Wizard fallito: {ex.Message}";
        }
    }

    // ---------- Wizard ----------

    private void OpenWizard()
    {
        _wizardOpen = true;
        _wizardStep = 1;
        _wizardId = null;
        _confirmDelete = false;
        WizardNameBox.Text = string.Empty;
        WizardMessageText.Text = string.Empty;
        WizardCountsText.Text = string.Empty;
        WizardProgress.Visibility = Visibility.Collapsed;
        WizardProgressText.Visibility = Visibility.Collapsed;
        WizardStateText.Text = "Stato: Bozza";
        ShowWizardStep();
    }

    private void ShowWizardStep()
    {
        ListPanel.Visibility = Visibility.Collapsed;
        DetailPanel.Visibility = Visibility.Collapsed;
        WizardPanel.Visibility = Visibility.Visible;
        WizardTitleText.Text = $"Crea personaggio · passo {_wizardStep} di 5";
        WizardStep1Panel.Visibility = _wizardStep == 1 ? Visibility.Visible : Visibility.Collapsed;
        WizardStep2Panel.Visibility = _wizardStep == 2 ? Visibility.Visible : Visibility.Collapsed;
        WizardStep3Panel.Visibility = _wizardStep is 3 or 4 ? Visibility.Visible : Visibility.Collapsed;
        WizardStep5Panel.Visibility = _wizardStep == 5 ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void WizardCreate_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var name = WizardNameBox.Text.Trim();
            if (string.IsNullOrWhiteSpace(name))
            {
                WizardMessageText.Text = "Scrivi un nome per continuare.";
                return;
            }

            WizardMessageText.Text = "Creo personaggio…";
            var (created, message) = await CharactersService.CreateCharacterAsync(AppSettingsStore.Load(), name);
            if (created is null)
            {
                WizardMessageText.Text = message;
                return;
            }

            _wizardId = created;
            _wizardStep = 2;
            WizardMessageText.Text = string.Empty;
            ShowWizardStep();
        }
        catch (Exception ex)
        {
            WizardMessageText.Text = $"Creazione fallita: {ex.Message}";
        }
    }

    private async void PickPhotos_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var id = _wizardId ?? _detailId;
            if (string.IsNullOrWhiteSpace(id))
            {
                WizardMessageText.Text = "Crea prima il personaggio.";
                return;
            }

            var picker = new FileOpenPicker
            {
                ViewMode = PickerViewMode.Thumbnail,
                SuggestedStartLocation = PickerLocationId.PicturesLibrary,
            };
            picker.FileTypeFilter.Add(".jpg");
            picker.FileTypeFilter.Add(".jpeg");
            picker.FileTypeFilter.Add(".png");
            picker.FileTypeFilter.Add(".webp");
            picker.FileTypeFilter.Add(".bmp");
            if (App.MainWindow is not null)
            {
                InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.MainWindow));
            }

            var files = await picker.PickMultipleFilesAsync();
            if (files is null || files.Count == 0)
            {
                SetPhotoMessage("Selezione foto annullata.");
                return;
            }

            var paths = files
                .Take(80)
                .Select(f => f.Path)
                .Where(p => !string.IsNullOrWhiteSpace(p))
                .ToList();
            if (paths.Count == 0)
            {
                SetPhotoMessage("Nessuna foto leggibile.");
                return;
            }

            SetPhotoMessage($"Carico {paths.Count} foto…");
            var result = await CharactersService.UploadPhotosAsync(AppSettingsStore.Load(), id, paths);
            SetPhotoMessage(result.Message);
            if (result.Uploaded > 0 && _wizardOpen && _wizardStep == 2)
            {
                _wizardStep = 3;
                ShowWizardStep();
                await RefreshWizardStatusAsync();
            }
            else if (_detailId is not null)
            {
                await RefreshDetailAsync(silent: true);
            }
        }
        catch (Exception ex)
        {
            SetPhotoMessage($"Upload fallito: {ex.Message}");
        }
    }

    private void SetPhotoMessage(string message)
    {
        if (_wizardOpen)
        {
            WizardMessageText.Text = message;
        }

        if (_detailId is not null)
        {
            DetailMessageText.Text = message;
        }
    }

    private async void WizardAnalyze_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(_wizardId))
            {
                WizardMessageText.Text = "Crea prima il personaggio.";
                return;
            }

            var (code, _) = await CharactersService.AnalyzeAsync(AppSettingsStore.Load(), _wizardId);
            WizardMessageText.Text = code is >= 200 and <= 299 ? "Analisi avviata" : $"Analisi HTTP {code}";
            await RefreshWizardStatusAsync();
        }
        catch (Exception ex)
        {
            WizardMessageText.Text = $"Analisi fallita: {ex.Message}";
        }
    }

    private async Task RefreshWizardStatusAsync()
    {
        var id = _wizardId;
        if (string.IsNullOrWhiteSpace(id))
        {
            return;
        }

        var settings = AppSettingsStore.Load();
        var (status, job) = await CharactersService.LoadStatusAsync(settings, id);
        WizardStateText.Text = $"Stato: {CharactersService.StatusLabel(status)}";
        if (job is not null && status == "analyzing")
        {
            WizardProgress.Visibility = Visibility.Visible;
            WizardProgressText.Visibility = Visibility.Visible;
            WizardProgress.Value = job.Progress * 100;
            WizardProgressText.Text = $"Analisi {(int)Math.Round(job.Progress * 100)}% · {job.Detail}";
        }
        else
        {
            WizardProgress.Visibility = Visibility.Collapsed;
            WizardProgressText.Visibility = Visibility.Collapsed;
        }

        if (!string.IsNullOrWhiteSpace(job?.Error) && job.Status == "failed")
        {
            WizardMessageText.Text = job.Error;
        }

        if (status == "ready_to_train")
        {
            var manifest = await CharactersService.LoadManifestAsync(settings, id);
            var count = manifest?.ImageCount ?? 0;
            _wizardStep = 5;
            WizardReadyText.Text = $"Pronto a creare {(string.IsNullOrWhiteSpace(manifest?.Name) ? string.Empty : manifest!.Name)}".Trim();
            WizardQualityText.Text = $"{count} foto pronte per il training.";
            WizardQualityText.Text += "\n" + BuildQualityLines(manifest);
            WizardTrainButton.IsEnabled = count >= 20;
            WizardTrainButton.Content = $"Crea {(string.IsNullOrWhiteSpace(manifest?.Name) ? "personaggio" : manifest!.Name)}";
            WizardTrainWarning.Visibility = count >= 20 ? Visibility.Collapsed : Visibility.Visible;
            ShowWizardStep();
            return;
        }

        var (good, warn, bad) = await CharactersService.LoadImageVerdictsAsync(settings, id);
        if (good + warn + bad > 0)
        {
            WizardCountsText.Text = $"✓ {good} buone · ⚠ {warn} dubbie · ✕ {bad} scartate";
        }
    }

    private async void WizardTrain_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(_wizardId))
            {
                return;
            }

            WizardMessageText.Text = "Avvio creazione…";
            var (code, _) = await CharactersService.TrainAsync(AppSettingsStore.Load(), _wizardId);
            if (code is >= 200 and <= 299)
            {
                var id = _wizardId;
                _wizardOpen = false;
                _wizardId = null;
                OpenDetail(id);
            }
            else
            {
                WizardMessageText.Text = $"Training HTTP {code}";
            }
        }
        catch (Exception ex)
        {
            WizardMessageText.Text = $"Creazione fallita: {ex.Message}";
        }
    }

    private void WizardGotoDetail_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (!string.IsNullOrWhiteSpace(_wizardId))
            {
                var id = _wizardId;
                _wizardOpen = false;
                _wizardId = null;
                OpenDetail(id);
            }
        }
        catch (Exception ex)
        {
            WizardMessageText.Text = $"Apertura fallita: {ex.Message}";
        }
    }

    private void WizardCancel_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (_wizardStep > 1)
            {
                _wizardStep--;
                ShowWizardStep();
                return;
            }

            _wizardOpen = false;
            _wizardId = null;
            WizardPanel.Visibility = Visibility.Collapsed;
            ListPanel.Visibility = Visibility.Visible;
            _ = RefreshListAsync();
        }
        catch (Exception ex)
        {
            WizardMessageText.Text = $"Chiusura fallita: {ex.Message}";
        }
    }

    // ---------- Detail ----------

    private void OpenDetail(string id, bool openGenerate = false)
    {
        _detailId = id;
        _confirmDelete = false;
        DetailDeleteButton.Content = "Elimina";
        DetailMessageText.Text = string.Empty;
        GenerateResultText.Text = string.Empty;
        DetailGeneratePanel.Visibility = openGenerate ? Visibility.Visible : Visibility.Collapsed;
        ListPanel.Visibility = Visibility.Collapsed;
        WizardPanel.Visibility = Visibility.Collapsed;
        DetailPanel.Visibility = Visibility.Visible;
        _ = RefreshDetailAsync(silent: false);
    }

    private async Task RefreshDetailAsync(bool silent)
    {
        var id = _detailId;
        if (string.IsNullOrWhiteSpace(id))
        {
            return;
        }

        var settings = AppSettingsStore.Load();
        var manifest = await CharactersService.LoadManifestAsync(settings, id);
        var (status, job) = await CharactersService.LoadStatusAsync(settings, id);
        var (good, warn, bad) = await CharactersService.LoadImageVerdictsAsync(settings, id);
        _detailManifest = manifest;

        var name = manifest?.Name;
        if (string.IsNullOrWhiteSpace(name))
        {
            name = _items.FirstOrDefault(i => i.Id == id)?.Name ?? "Personaggio";
        }

        DetailNameText.Text = name;
        DetailStatusText.Text = CharactersService.StatusLabel(status);
        DetailInfoText.Text = $"Foto: {manifest?.ImageCount ?? 0} · Versione: v{manifest?.TrainingVersion ?? 0}";
        if (manifest?.IdentityScore.HasValue == true)
        {
            var pct = (int)Math.Round(manifest.IdentityScore.Value * 100);
            DetailIdentityText.Visibility = Visibility.Visible;
            DetailIdentityText.Text = $"Identità {pct}% · Consigliato: {manifest.RecommendedEngine}";
        }
        else
        {
            DetailIdentityText.Visibility = Visibility.Collapsed;
        }

        DetailQualityText.Text = BuildQualityLines(manifest);
        DetailVerdictsText.Text = good + warn + bad > 0
            ? $"✓ {good} buone · ⚠ {warn} dubbie · ✕ {bad} scartate"
            : "Nessun verdetto foto.";

        if (job is not null)
        {
            DetailJobPanel.Visibility = Visibility.Visible;
            DetailJobTitleText.Text = $"Creazione di {name}";
            DetailJobProgress.Value = job.Progress * 100;
            DetailJobText.Text = CharactersService.TrainingPhaseLabel(job);
            DetailJobDetailText.Text = job.Detail;
            if (!silent && job.Status == "failed" && !string.IsNullOrWhiteSpace(job.Error))
            {
                DetailMessageText.Text = job.Error;
            }
        }
        else
        {
            DetailJobPanel.Visibility = Visibility.Collapsed;
        }

        DetailAnalyzeButton.IsEnabled = status is "draft" or "failed" or "interrupted";
        DetailTrainButton.IsEnabled = status is "ready_to_train" or "failed" or "interrupted" or "needs_retrain";
        DetailUseButton.IsEnabled = status == "ready";
    }

    private static string BuildQualityLines(CharacterManifest? manifest)
    {
        if (manifest is null)
        {
            return "Qualità dataset non disponibile.";
        }

        var lines = new List<string> { "Qualità dataset" };
        foreach (var (key, label) in CharactersService.ReferenceSlots)
        {
            var ok = manifest.References.TryGetValue(key, out var present) && present;
            lines.Add($"{(ok ? "✓" : "·")} {label}");
        }

        return string.Join("\n", lines);
    }

    private void DetailBack_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            try { _generateCts?.Cancel(); } catch { }
            _detailId = null;
            _detailManifest = null;
            _confirmDelete = false;
            DetailPanel.Visibility = Visibility.Collapsed;
            ListPanel.Visibility = Visibility.Visible;
            _ = RefreshListAsync();
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Chiusura fallita: {ex.Message}";
        }
    }

    private async void DetailAnalyze_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(_detailId))
            {
                return;
            }

            var (code, _) = await CharactersService.AnalyzeAsync(AppSettingsStore.Load(), _detailId);
            DetailMessageText.Text = code is >= 200 and <= 299 ? "Analisi avviata" : $"Analisi HTTP {code}";
            await RefreshDetailAsync(silent: true);
        }
        catch (Exception ex)
        {
            DetailMessageText.Text = $"Analisi fallita: {ex.Message}";
        }
    }

    private async void DetailTrain_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(_detailId))
            {
                return;
            }

            var (code, _) = await CharactersService.TrainAsync(AppSettingsStore.Load(), _detailId);
            DetailMessageText.Text = code is >= 200 and <= 299 ? "Creazione avviata" : $"Training HTTP {code}";
            await RefreshDetailAsync(silent: true);
        }
        catch (Exception ex)
        {
            DetailMessageText.Text = $"Creazione fallita: {ex.Message}";
        }
    }

    private async void DetailCancel_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(_detailId))
            {
                return;
            }

            var (code, _) = await CharactersService.CancelAsync(AppSettingsStore.Load(), _detailId);
            DetailMessageText.Text = code is >= 200 and <= 299 ? "Creazione annullata" : $"Annulla HTTP {code}";
            await RefreshDetailAsync(silent: true);
        }
        catch (Exception ex)
        {
            DetailMessageText.Text = $"Annullamento fallito: {ex.Message}";
        }
    }

    private async void DetailRename_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(_detailId))
            {
                return;
            }

            var box = new TextBox
            {
                Text = DetailNameText.Text,
                PlaceholderText = "Nome personaggio",
            };
            var dialog = new ContentDialog
            {
                Title = "Rinomina personaggio",
                Content = box,
                PrimaryButtonText = "OK",
                CloseButtonText = "Annulla",
                XamlRoot = XamlRoot,
            };
            var result = await dialog.ShowAsync();
            if (result != ContentDialogResult.Primary)
            {
                return;
            }

            var next = box.Text.Trim();
            if (string.IsNullOrWhiteSpace(next))
            {
                DetailMessageText.Text = "Nome vuoto, rinomina annullata.";
                return;
            }

            var (code, _) = await CharactersService.RenameAsync(AppSettingsStore.Load(), _detailId, next);
            DetailMessageText.Text = code is >= 200 and <= 299 ? "Rinominato" : $"Rinomina HTTP {code}";
            await RefreshDetailAsync(silent: true);
        }
        catch (Exception ex)
        {
            DetailMessageText.Text = $"Rinomina fallita: {ex.Message}";
        }
    }

    private async void DetailExport_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(_detailId))
            {
                return;
            }

            var (code, file, _) = await CharactersService.ExportAsync(AppSettingsStore.Load(), _detailId);
            DetailMessageText.Text = code is >= 200 and <= 299
                ? $"Esportato: {file}"
                : $"Export HTTP {code}";
        }
        catch (Exception ex)
        {
            DetailMessageText.Text = $"Export fallito: {ex.Message}";
        }
    }

    private async void DetailDelete_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(_detailId))
            {
                return;
            }

            if (!_confirmDelete)
            {
                _confirmDelete = true;
                DetailDeleteButton.Content = "Confermi?";
                DetailMessageText.Text = "Tocca di nuovo Elimina per confermare (cancella tutto).";
                return;
            }

            var (code, _) = await CharactersService.DeleteAsync(AppSettingsStore.Load(), _detailId);
            if (code is >= 200 and <= 299)
            {
                _detailId = null;
                _confirmDelete = false;
                DetailDeleteButton.Content = "Elimina";
                DetailPanel.Visibility = Visibility.Collapsed;
                ListPanel.Visibility = Visibility.Visible;
                await RefreshListAsync();
            }
            else
            {
                DetailMessageText.Text = $"Elimina HTTP {code}";
            }
        }
        catch (Exception ex)
        {
            DetailMessageText.Text = $"Eliminazione fallita: {ex.Message}";
        }
    }

    private void DetailUse_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            DetailGeneratePanel.Visibility = DetailGeneratePanel.Visibility == Visibility.Visible
                ? Visibility.Collapsed
                : Visibility.Visible;
        }
        catch (Exception ex)
        {
            DetailMessageText.Text = $"Apertura fallita: {ex.Message}";
        }
    }

    private void GenerateClose_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            try { _generateCts?.Cancel(); } catch { }
            DetailGeneratePanel.Visibility = Visibility.Collapsed;
        }
        catch (Exception ex)
        {
            DetailMessageText.Text = $"Chiusura fallita: {ex.Message}";
        }
    }

    private async void GenerateStart_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(_detailId))
            {
                return;
            }

            var prompt = GeneratePromptBox.Text.Trim();
            if (string.IsNullOrWhiteSpace(prompt))
            {
                GenerateResultText.Text = "Scrivi un prompt prima di generare.";
                return;
            }

            var mode = (GenerateModeBox.SelectedItem as ComboBoxItem)?.Tag as string ?? "auto";
            var duration = int.TryParse((GenerateDurationBox.SelectedItem as ComboBoxItem)?.Tag as string, out var d) ? d : 5;
            var aspect = (GenerateAspectBox.SelectedItem as ComboBoxItem)?.Tag as string ?? "16:9";
            var seed = long.TryParse(GenerateSeedBox.Text.Trim(), out var s) ? s : 42L;

            try { _generateCts?.Cancel(); } catch { }
            _generateCts = new CancellationTokenSource();
            var ct = _generateCts.Token;

            GenerateResultText.Text = "Accodo generazione…";
            var (jobId, message) = await CharactersService.GenerateAsync(
                AppSettingsStore.Load(), _detailId, prompt, mode, duration, aspect, seed, ct);
            if (jobId is null)
            {
                GenerateResultText.Text = message;
                return;
            }

            GenerateResultText.Text = await PollGenerateJobAsync(jobId, ct);
        }
        catch (OperationCanceledException)
        {
            GenerateResultText.Text = "Generazione annullata.";
        }
        catch (Exception ex)
        {
            GenerateResultText.Text = $"Generazione fallita: {ex.Message}";
        }
    }

    private static async Task<string> PollGenerateJobAsync(string jobId, CancellationToken ct)
    {
        var settings = AppSettingsStore.Load();
        for (var i = 0; i < 120; i++)
        {
            await Task.Delay(TimeSpan.FromSeconds(5), ct);
            var state = await CharactersService.GetJobAsync(settings, jobId, ct);
            if (state is null)
            {
                return $"Job {jobId} in coda (stato non leggibile)";
            }

            switch (state.Status)
            {
                case "done":
                    return $"Video pronto ({state.ResultCount} file). Lo trovi nella sezione Video.";
                case "failed":
                    return $"Generazione fallita: {Truncate(state.Error, 160)}";
                case "cancelled":
                    return "Generazione annullata.";
            }
        }

        return $"Job {jobId} ancora in coda: controlla la sezione Video.";
    }

    private static string Truncate(string value, int max) =>
        string.IsNullOrEmpty(value) || value.Length <= max ? value : value[..max];
}
