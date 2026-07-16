using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using NemoclawChat_Windows.Services;
using Windows.ApplicationModel.DataTransfer;
using Windows.Media.Core;
using Windows.Storage;
using Windows.System;

namespace NemoclawChat_Windows.Pages;

public sealed partial class VideoPage : Page
{
    private LocalVideoRecord? _selectedVideo;
    private string? _manualVideoUrl;
    private string _currentPlaybackUrl = string.Empty;
    private bool _usingCompatPlayback;

    public VideoPage()
    {
        InitializeComponent();
        VideoPlayer.MediaPlayer.MediaFailed += (_, args) =>
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                if (TryUseCompatPlayback($"Player video: {args.ErrorMessage}"))
                {
                    return;
                }
                FeedbackStatusText.Text = $"Player video: {args.ErrorMessage}. Nessun fallback compatibile disponibile.";
            });
        };
        FullScreenVideoPlayer.MediaPlayer.MediaFailed += (_, args) =>
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                if (TryUseCompatPlayback($"Player fullscreen: {args.ErrorMessage}"))
                {
                    return;
                }
                FeedbackStatusText.Text = $"Player fullscreen: {args.ErrorMessage}. Nessun fallback compatibile disponibile.";
            });
        };
        UpdateAdaptiveLayout(ActualWidth);
        _ = RefreshFeedAsync();
    }

    private void VideoContentGrid_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        UpdateAdaptiveLayout(e.NewSize.Width);
    }

    private void UpdateAdaptiveLayout(double width)
    {
        if (VideoContentGrid.ColumnDefinitions.Count < 2 || VideoContentGrid.RowDefinitions.Count < 2)
        {
            return;
        }

        var narrow = width > 0 && width < 1040;
        if (narrow)
        {
            VideoContentGrid.ColumnDefinitions[0].Width = new GridLength(1, GridUnitType.Star);
            VideoContentGrid.ColumnDefinitions[1].Width = new GridLength(0);
            VideoContentGrid.RowDefinitions[1].Height = GridLength.Auto;
            Grid.SetColumn(VideoDetailPanel, 0);
            Grid.SetRow(VideoDetailPanel, 1);
        }
        else
        {
            VideoContentGrid.ColumnDefinitions[0].Width = new GridLength(1.2, GridUnitType.Star);
            VideoContentGrid.ColumnDefinitions[1].Width = new GridLength(0.95, GridUnitType.Star);
            VideoContentGrid.RowDefinitions[1].Height = new GridLength(0);
            Grid.SetColumn(VideoDetailPanel, 1);
            Grid.SetRow(VideoDetailPanel, 0);
        }
    }

    private async Task RefreshFeedAsync()
    {
        var settings = AppSettingsStore.Load();
        var folder = VideoLibraryService.EnsureLibraryPath(settings);
        var (loaded, loadStatus) = await VideoLibraryService.LoadAsync(settings);
        var videos = loaded.ToList();
        folder = VideoLibraryService.GetLibraryPath(settings);

        LibraryPathText.Text = string.IsNullOrWhiteSpace(folder)
            ? "Hermes non ha ancora annunciato la cartella video."
            : folder;
        VideoListView.ItemsSource = videos;

        PageStatusText.Text = string.IsNullOrWhiteSpace(folder)
            ? "In attesa di sync automatico da Hermes."
            : videos.Count == 0
                ? loadStatus
                : $"{videos.Count} video trovati in feed.";

        if (_selectedVideo is not null)
        {
            var updatedSelection = videos.FirstOrDefault(video => video.Path.Equals(_selectedVideo.Path, StringComparison.OrdinalIgnoreCase));
            if (updatedSelection is not null)
            {
                SelectVideo(updatedSelection);
                return;
            }
        }

        if (!string.IsNullOrWhiteSpace(_manualVideoUrl))
        {
            return;
        }

        if (videos.Count > 0)
        {
            SelectVideo(videos[0]);
        }
        else
        {
            _selectedVideo = null;
            _manualVideoUrl = null;
            VideoPlayer.Source = null;
            SelectedVideoTitleText.Text = "Seleziona un video";
            SelectedVideoMetaText.Text = "Nessun file video trovato nella cartella monitorata.";
            AgentResponseBox.Text = string.Empty;
        }
    }

    private async void OpenFolder_Click(object sender, RoutedEventArgs e)
    {
        var folder = VideoLibraryService.EnsureLibraryPath(AppSettingsStore.Load());
        if (string.IsNullOrWhiteSpace(folder))
        {
            FeedbackStatusText.Text = "Hermes non ha ancora inviato la cartella video.";
            return;
        }
        if (folder.StartsWith('/') && OperatingSystem.IsWindows())
        {
            FeedbackStatusText.Text = $"Cartella sul server Linux: {folder}. Usa il feed gateway o aprila via SSH/SFTP.";
            return;
        }
        var storageFolder = await StorageFolder.GetFolderFromPathAsync(folder);
        await Launcher.LaunchFolderAsync(storageFolder);
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e)
    {
        await RefreshFeedAsync();
    }

    private void FullScreen_Click(object sender, RoutedEventArgs e)
    {
        EnterFullScreen();
    }

    private void VideoSurface_DoubleTapped(object sender, Microsoft.UI.Xaml.Input.DoubleTappedRoutedEventArgs e)
    {
        EnterFullScreen();
    }

    private void EnterFullScreen()
    {
        if (_selectedVideo is null && string.IsNullOrWhiteSpace(_manualVideoUrl))
        {
            FeedbackStatusText.Text = "Seleziona un video prima di aprire lo schermo intero.";
            return;
        }

        if (string.IsNullOrWhiteSpace(_currentPlaybackUrl))
        {
            FeedbackStatusText.Text = "Il video non e' ancora pronto per la riproduzione.";
            return;
        }

        FullScreenVideoTitleText.Text = SelectedVideoTitleText.Text;
        FullScreenVideoMetaText.Text = SelectedVideoMetaText.Text;
        FullScreenVideoOverlay.Visibility = Visibility.Visible;
        VideoPlayer.MediaPlayer.Pause();
        SetFullScreenSource(_currentPlaybackUrl);
        FullScreenVideoPlayer.MediaPlayer.Play();
        if (App.MainWindow is MainWindow mainWindow)
        {
            mainWindow.ToggleFullScreenVideo(true);
        }
    }

    private void CloseFullScreen_Click(object sender, RoutedEventArgs e)
    {
        CloseFullScreen();
    }

    private void CloseFullScreen()
    {
        if (FullScreenVideoOverlay.Visibility != Visibility.Visible)
        {
            return;
        }

        FullScreenVideoPlayer.MediaPlayer.Pause();
        FullScreenVideoPlayer.Source = null;
        FullScreenVideoOverlay.Visibility = Visibility.Collapsed;
        if (!string.IsNullOrWhiteSpace(_currentPlaybackUrl))
        {
            VideoPlayer.MediaPlayer.Play();
        }
        if (App.MainWindow is MainWindow mainWindow)
        {
            mainWindow.ToggleFullScreenVideo(false);
        }
    }

    private void VideoListView_ItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is LocalVideoRecord model)
        {
            SelectVideo(model);
        }
    }

    private void SelectVideo(LocalVideoRecord video)
    {
        _selectedVideo = video;
        _manualVideoUrl = null;
        SelectedVideoTitleText.Text = video.Title;
        SelectedVideoMetaText.Text = $"{video.FileName}\n{video.ModifiedAt.LocalDateTime:g} · {VideoLibraryService.FormatSize(video.SizeBytes)}{(video.IsRemote ? "\nGateway media proxy · streaming originale" : "")}";
        FeedbackBox.Text = video.LastFeedback;
        FeedbackStatusText.Text = string.IsNullOrWhiteSpace(video.LastAgentStatus)
            ? "Pronto per nuovo feedback."
            : video.LastAgentStatus;
        AgentResponseBox.Text = video.LastAgentResponse;
        _usingCompatPlayback = false;
        SetVideoSource(string.IsNullOrWhiteSpace(video.PlaybackPath) ? video.Path : video.PlaybackPath);
    }

    private void OpenManualVideoUrl_Click(object sender, RoutedEventArgs e)
    {
        var rawUrl = ManualVideoUrlBox.Text.Trim();
        if (!Uri.TryCreate(rawUrl, UriKind.Absolute, out var uri) ||
            (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
        {
            PageStatusText.Text = "URL video non valido. Usa un link http/https diretto.";
            return;
        }

        _selectedVideo = null;
        _manualVideoUrl = uri.ToString();
        VideoListView.SelectedItem = null;
        SelectedVideoTitleText.Text = "URL video manuale";
        SelectedVideoMetaText.Text = _manualVideoUrl;
        FeedbackBox.Text = string.Empty;
        FeedbackStatusText.Text = "URL manuale pronto. Puoi riprodurlo o lasciare feedback a Hermes.";
        AgentResponseBox.Text = string.Empty;
        _usingCompatPlayback = false;
        SetVideoSource(uri.ToString());
        PageStatusText.Text = "URL video manuale caricato.";
    }

    private void SetVideoSource(string url)
    {
        _currentPlaybackUrl = AddMediaPlaybackToken(url);
        var mediaSource = MediaSource.CreateFromUri(new Uri(_currentPlaybackUrl));
        VideoPlayer.Source = mediaSource;
        if (FullScreenVideoOverlay.Visibility == Visibility.Visible)
        {
            SetFullScreenSource(_currentPlaybackUrl);
            FullScreenVideoPlayer.MediaPlayer.Play();
        }
    }

    private void SetFullScreenSource(string playbackUrl)
    {
        FullScreenVideoPlayer.Source = MediaSource.CreateFromUri(new Uri(playbackUrl));
    }

    private bool TryUseCompatPlayback(string reason)
    {
        var compat = _selectedVideo?.CompatPath;
        if (_selectedVideo is null ||
            _usingCompatPlayback ||
            string.IsNullOrWhiteSpace(compat) ||
            compat.Equals(_selectedVideo.PlaybackPath, StringComparison.OrdinalIgnoreCase) ||
            compat.Equals(_currentPlaybackUrl, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        _usingCompatPlayback = true;
        FeedbackStatusText.Text = $"{reason}. Passo al proxy MP4 compatibile Hermes.";
        SelectedVideoMetaText.Text = $"{_selectedVideo.FileName}\n{_selectedVideo.ModifiedAt.LocalDateTime:g} · {VideoLibraryService.FormatSize(_selectedVideo.SizeBytes)}\nGateway media proxy · fallback MP4 compat";
        SetVideoSource(compat);
        return true;
    }

    private static string AddMediaPlaybackToken(string url)
    {
        if (string.IsNullOrWhiteSpace(url) ||
            !url.Contains("/v1/media/", StringComparison.OrdinalIgnoreCase) ||
            url.Contains("hub_token=", StringComparison.OrdinalIgnoreCase))
        {
            return url;
        }

        var settings = AppSettingsStore.Load();
        var resolved = Uri.TryCreate(url, UriKind.Absolute, out var absolute)
            ? absolute
            : new Uri(GatewayService.ResolveHermesUri(settings, url));
        if (!GatewayService.IsTrustedGatewayUri(settings, resolved))
        {
            return url;
        }

        var token = GatewayCredentialStore.LoadSecret();
        if (string.IsNullOrWhiteSpace(token))
        {
            return url;
        }

        var separator = url.Contains('?') ? "&" : "?";
        return $"{url}{separator}hub_token={Uri.EscapeDataString(token)}";
    }

    private void QuickFeedback_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button || button.Tag is not string snippet || string.IsNullOrWhiteSpace(snippet))
        {
            return;
        }

        var existing = FeedbackBox.Text.Trim();
        FeedbackBox.Text = string.IsNullOrWhiteSpace(existing) ? snippet : $"{existing}; {snippet}";
    }

    private async void SendFeedback_Click(object sender, RoutedEventArgs e)
    {
        var activePath = _selectedVideo?.Path ?? _manualVideoUrl;
        var activeTitle = _selectedVideo?.Title ?? "URL video manuale";
        if (string.IsNullOrWhiteSpace(activePath))
        {
            FeedbackStatusText.Text = "Seleziona un video prima di inviare feedback.";
            return;
        }

        var feedback = FeedbackBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(feedback))
        {
            FeedbackStatusText.Text = "Scrivi feedback prima di inviare.";
            return;
        }

        SendFeedbackButton.IsEnabled = false;
        FeedbackStatusText.Text = "Invio feedback a Hermes...";
        AgentResponseBox.Text = string.Empty;

        try
        {
            var settings = AppSettingsStore.Load();
            await GatewayService.SaveHubStateAsync(settings, "video_feedback", activePath, new
            {
                title = activeTitle,
                path = activePath,
                feedback,
                reaction = "written"
            });
            var prompt = $$"""
                Feedback editoriale su video in cartella monitorata Hermes Hub.
                Cartella video: {{VideoLibraryService.EnsureLibraryPath(settings)}}
                Riferimento video: {{activePath}}
                Titolo: {{activeTitle}}

                Feedback utente:
                {{feedback}}

                Istruzioni:
                - usa questo feedback per migliorare prossime versioni, montaggio, hook, pacing, script, voiceover e formato;
                - se feedback contiene preferenze stabili, salvale in memoria agente condivisa Hermes/CLI/app;
                - se serve rigenerare, considera questo file come riferimento sorgente nella cartella monitorata;
                - rispondi con next step concreti per migliorare video e pipeline.
                """;
            var result = await GatewayService.SendWorkspaceRunAsync(settings, "Video", prompt);
            VideoFeedbackStore.Save(activePath, activeTitle, feedback, result.Status, result.Result);
            FeedbackStatusText.Text = result.Status;
            AgentResponseBox.Text = result.Result;
            if (_selectedVideo is not null)
            {
                await RefreshFeedAsync();
            }
        }
        finally
        {
            SendFeedbackButton.IsEnabled = true;
        }
    }

    private void CopyPath_Click(object sender, RoutedEventArgs e)
    {
        var activePath = _selectedVideo?.Path ?? _manualVideoUrl;
        if (string.IsNullOrWhiteSpace(activePath))
        {
            FeedbackStatusText.Text = "Nessun video selezionato.";
            return;
        }

        var package = new DataPackage();
        package.SetText(activePath);
        Clipboard.SetContent(package);
        FeedbackStatusText.Text = "Riferimento video copiato.";
    }
}
