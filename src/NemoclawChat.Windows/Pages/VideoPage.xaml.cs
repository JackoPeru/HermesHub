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

    public VideoPage()
    {
        InitializeComponent();
        RefreshFeed();
    }

    private void RefreshFeed()
    {
        var settings = AppSettingsStore.Load();
        var folder = VideoLibraryService.EnsureLibraryPath(settings);
        var videos = VideoLibraryService.Scan(settings).ToList();

        LibraryPathText.Text = string.IsNullOrWhiteSpace(folder)
            ? "Hermes non ha ancora annunciato la cartella video."
            : folder;
        VideoListView.ItemsSource = videos;

        PageStatusText.Text = string.IsNullOrWhiteSpace(folder)
            ? "In attesa di sync automatico da Hermes."
            : videos.Count == 0
                ? "Cartella sincronizzata. I file video compariranno qui in automatico."
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

        if (videos.Count > 0)
        {
            SelectVideo(videos[0]);
        }
        else
        {
            _selectedVideo = null;
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
        var storageFolder = await StorageFolder.GetFolderFromPathAsync(folder);
        await Launcher.LaunchFolderAsync(storageFolder);
    }

    private void Refresh_Click(object sender, RoutedEventArgs e)
    {
        RefreshFeed();
    }

    private void FullScreen_Click(object sender, RoutedEventArgs e)
    {
        if (_selectedVideo is null)
        {
            FeedbackStatusText.Text = "Seleziona un video prima di aprire lo schermo intero.";
            return;
        }

        VideoPlayer.IsFullWindow = true;
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
        SelectedVideoTitleText.Text = video.Title;
        SelectedVideoMetaText.Text = $"{video.FileName}\n{video.ModifiedAt.LocalDateTime:g} · {VideoLibraryService.FormatSize(video.SizeBytes)}";
        FeedbackBox.Text = video.LastFeedback;
        FeedbackStatusText.Text = string.IsNullOrWhiteSpace(video.LastAgentStatus)
            ? "Pronto per nuovo feedback."
            : video.LastAgentStatus;
        AgentResponseBox.Text = video.LastAgentResponse;
        VideoPlayer.Source = MediaSource.CreateFromUri(new Uri(video.Path));
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
        if (_selectedVideo is null)
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
            await GatewayService.SaveHubStateAsync(settings, "video_feedback", _selectedVideo.Path, new
            {
                title = _selectedVideo.Title,
                path = _selectedVideo.Path,
                feedback,
                reaction = "written"
            });
            var prompt = $$"""
                Feedback editoriale su video in cartella monitorata Hermes Hub.
                Cartella video: {{VideoLibraryService.EnsureLibraryPath(settings)}}
                File video: {{_selectedVideo.Path}}
                Titolo: {{_selectedVideo.Title}}

                Feedback utente:
                {{feedback}}

                Istruzioni:
                - usa questo feedback per migliorare prossime versioni, montaggio, hook, pacing, script, voiceover e formato;
                - se feedback contiene preferenze stabili, salvale in memoria agente condivisa Hermes/CLI/app;
                - se serve rigenerare, considera questo file come riferimento sorgente nella cartella monitorata;
                - rispondi con next step concreti per migliorare video e pipeline.
                """;
            var result = await GatewayService.SendWorkspaceRunAsync(settings, "Video", prompt);
            VideoFeedbackStore.Save(_selectedVideo.Path, _selectedVideo.Title, feedback, result.Status, result.Result);
            FeedbackStatusText.Text = result.Status;
            AgentResponseBox.Text = result.Result;
            RefreshFeed();
        }
        finally
        {
            SendFeedbackButton.IsEnabled = true;
        }
    }

    private void CopyPath_Click(object sender, RoutedEventArgs e)
    {
        if (_selectedVideo is null)
        {
            FeedbackStatusText.Text = "Nessun video selezionato.";
            return;
        }

        var package = new DataPackage();
        package.SetText(_selectedVideo.Path);
        Clipboard.SetContent(package);
        FeedbackStatusText.Text = "Path video copiato.";
    }
}
