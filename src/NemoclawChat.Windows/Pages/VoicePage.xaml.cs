using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using NemoclawChat_Windows.Services;
using System.Text;
using Windows.Foundation;
using Windows.Media.Capture;
using Windows.Media.Core;
using Windows.Media.MediaProperties;
using Windows.Media.Playback;
using Windows.Storage;
using Windows.System;

namespace NemoclawChat_Windows.Pages;

public sealed partial class VoicePage : Page
{
    private readonly DispatcherTimer _timer = new();
    private readonly List<VoiceStar> _stars = BuildStars();
    private readonly List<Ellipse> _dots = [];
    private readonly List<ChatMessageRecord> _history = [];
    private readonly SolidColorBrush _starBrush = new(Microsoft.UI.ColorHelper.FromArgb(235, 255, 255, 255));
    private readonly SolidColorBrush _warmBrush = new(Microsoft.UI.ColorHelper.FromArgb(235, 255, 154, 42));
    private readonly SolidColorBrush _glowBrush = new(Microsoft.UI.ColorHelper.FromArgb(70, 255, 132, 24));
    private MediaCapture? _mediaCapture;
    private StorageFile? _recordingFile;
    private MediaPlayer? _voicePlayer;
    private CancellationTokenSource? _turnCts;
    private DateTimeOffset _lastFrame = DateTimeOffset.Now;
    private Task _playbackChain = Task.CompletedTask;
    private double _time;
    private double _assembly;
    private bool _isRecording;
    private bool _isBusy;
    private bool _isSpeaking;

    public VoicePage()
    {
        InitializeComponent();
        _timer.Interval = TimeSpan.FromMilliseconds(16);
        _timer.Tick += Timer_Tick;
        BuildVisuals();
    }

    private void Page_Loaded(object sender, RoutedEventArgs e)
    {
        Root.Focus(FocusState.Programmatic);
        _lastFrame = DateTimeOffset.Now;
        _timer.Start();
    }

    private void Page_Unloaded(object sender, RoutedEventArgs e)
    {
        _timer.Stop();
        _turnCts?.Cancel();
        _mediaCapture?.Dispose();
        _voicePlayer?.Dispose();
    }

    private async void CallButton_Click(object sender, RoutedEventArgs e)
    {
        if (_isBusy)
        {
            return;
        }

        if (_isRecording)
        {
            await StopRecordingAndSendAsync();
        }
        else
        {
            await StartRecordingAsync();
        }
    }

    private void Back_Click(object sender, RoutedEventArgs e)
    {
        if (Frame.CanGoBack)
        {
            Frame.GoBack();
        }
        else
        {
            Frame.Navigate(typeof(HomePage));
        }
    }

    private void Root_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == VirtualKey.Escape)
        {
            Back_Click(sender, new RoutedEventArgs());
            e.Handled = true;
        }
    }

    private async Task StartRecordingAsync()
    {
        try
        {
            _turnCts?.Cancel();
            _turnCts = new CancellationTokenSource();
            _mediaCapture = new MediaCapture();
            await _mediaCapture.InitializeAsync(new MediaCaptureInitializationSettings
            {
                StreamingCaptureMode = StreamingCaptureMode.Audio
            });

            _recordingFile = await ApplicationData.Current.TemporaryFolder.CreateFileAsync(
                $"voice-call-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}.m4a",
                CreationCollisionOption.GenerateUniqueName);
            await _mediaCapture.StartRecordToStorageFileAsync(
                MediaEncodingProfile.CreateM4a(AudioEncodingQuality.Medium),
                _recordingFile);

            _isRecording = true;
            _isSpeaking = false;
            StatusText.Text = "Ti ascolto...";
            CallIcon.Glyph = "\uE71A";
            CallButton.BorderBrush = new SolidColorBrush(Microsoft.UI.Colors.Red);
        }
        catch (Exception ex)
        {
            CleanupRecorder();
            StatusText.Text = $"Microfono non disponibile: {ex.Message}";
        }
    }

    private async Task StopRecordingAndSendAsync()
    {
        _isBusy = true;
        _isRecording = false;
        CallIcon.Glyph = "\uE720";
        CallButton.BorderBrush = new SolidColorBrush(Microsoft.UI.ColorHelper.FromArgb(85, 255, 255, 255));
        StatusText.Text = "Trascrivo...";

        try
        {
            if (_mediaCapture is not null)
            {
                await _mediaCapture.StopRecordAsync();
            }
            var path = _recordingFile?.Path;
            CleanupRecorder();
            if (string.IsNullOrWhiteSpace(path))
            {
                StatusText.Text = "Audio vuoto.";
                return;
            }

            var settings = AppSettingsStore.Load();
            var text = await SpeechGatewayService.TranscribeFileAsync(settings, path, _turnCts?.Token ?? CancellationToken.None);
            TryDelete(path);
            if (string.IsNullOrWhiteSpace(text))
            {
                StatusText.Text = "Non ho sentito parole utili.";
                return;
            }

            StatusText.Text = $"Tu: {TrimForStatus(text)}";
            await SendVoiceTurnAsync(settings, text, _turnCts?.Token ?? CancellationToken.None);
        }
        catch (OperationCanceledException)
        {
            StatusText.Text = "Voce annullata.";
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Errore voce: {ex.Message}";
        }
        finally
        {
            _isBusy = false;
            if (!_isRecording)
            {
                CallIcon.Glyph = "\uE720";
            }
        }
    }

    private async Task SendVoiceTurnAsync(AppSettings settings, string prompt, CancellationToken cancellationToken)
    {
        _history.Add(new ChatMessageRecord("Tu", prompt, DateTimeOffset.Now));
        var answer = new StringBuilder();
        var speechBuffer = new StringBuilder();
        StatusText.Text = "Hermes sta rispondendo...";

        await foreach (var ev in ChatStreamClient.StreamChatAsync(
                           settings,
                           "Chat",
                           $"Rispondi in italiano in modo naturale e conversazionale. Utente in chiamata vocale: {prompt}",
                           _history,
                           conversationId: null,
                           previousResponseId: null,
                           cancellationToken: cancellationToken))
        {
            cancellationToken.ThrowIfCancellationRequested();
            switch (ev)
            {
                case StreamTextDelta delta:
                    answer.Append(delta.Delta);
                    speechBuffer.Append(delta.Delta);
                    StatusText.Text = $"Hermes: {TrimForStatus(answer.ToString())}";
                    QueueCompleteSpeechSegments(settings, speechBuffer, false, cancellationToken);
                    break;
                case StreamDone done:
                    if (!string.IsNullOrWhiteSpace(done.AccumulatedText))
                    {
                        answer.Clear();
                        answer.Append(done.AccumulatedText);
                    }
                    break;
                case StreamError error:
                    StatusText.Text = $"Hermes: {error.Message}";
                    break;
            }
        }

        QueueCompleteSpeechSegments(settings, speechBuffer, true, cancellationToken);
        await _playbackChain.ConfigureAwait(true);
        var finalText = answer.ToString().Trim();
        if (!string.IsNullOrWhiteSpace(finalText))
        {
            _history.Add(new ChatMessageRecord("Hermes", finalText, DateTimeOffset.Now));
        }
        _isSpeaking = false;
        StatusText.Text = "Premi il microfono e parla.";
    }

    private void QueueCompleteSpeechSegments(AppSettings settings, StringBuilder buffer, bool flush, CancellationToken cancellationToken)
    {
        while (true)
        {
            var text = buffer.ToString();
            var cut = FindSpeechCut(text, flush);
            if (cut <= 0)
            {
                return;
            }

            var chunk = text[..cut].Trim();
            buffer.Remove(0, cut);
            if (chunk.Length < 2)
            {
                continue;
            }

            _playbackChain = _playbackChain
                .ContinueWith(_ => SpeakSegmentAsync(settings, chunk, cancellationToken), CancellationToken.None, TaskContinuationOptions.None, TaskScheduler.Default)
                .Unwrap();
        }
    }

    private async Task SpeakSegmentAsync(AppSettings settings, string text, CancellationToken cancellationToken)
    {
        _isSpeaking = true;
        DispatcherQueue.TryEnqueue(() => StatusText.Text = $"Hermes parla: {TrimForStatus(text)}");
        var file = await SpeechGatewayService.SynthesizeToFileAsync(settings, text, cancellationToken).ConfigureAwait(false);
        try
        {
            await PlayAudioFileAsync(file, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            TryDelete(file);
        }
    }

    private Task PlayAudioFileAsync(string filePath, CancellationToken cancellationToken)
    {
        var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        cancellationToken.Register(() => completion.TrySetCanceled(cancellationToken));
        DispatcherQueue.TryEnqueue(() =>
        {
            _voicePlayer?.Dispose();
            _voicePlayer = new MediaPlayer
            {
                Source = MediaSource.CreateFromUri(new Uri(filePath))
            };
            _voicePlayer.MediaEnded += (_, _) => completion.TrySetResult();
            _voicePlayer.MediaFailed += (_, args) => completion.TrySetException(new InvalidOperationException(args.ErrorMessage));
            _voicePlayer.Play();
        });
        return completion.Task;
    }

    private void CleanupRecorder()
    {
        _mediaCapture?.Dispose();
        _mediaCapture = null;
        _recordingFile = null;
    }

    private void ParticleCanvas_SizeChanged(object sender, SizeChangedEventArgs e) => UpdateScene(0);

    private void Timer_Tick(object? sender, object e)
    {
        var now = DateTimeOffset.Now;
        var dt = Math.Min(0.05, (now - _lastFrame).TotalSeconds);
        _lastFrame = now;
        _time += dt;
        UpdateScene(dt);
    }

    private void BuildVisuals()
    {
        ParticleCanvas.Children.Clear();
        foreach (var star in _stars)
        {
            var glow = new Ellipse
            {
                Fill = _glowBrush,
                Width = star.Size * 9,
                Height = star.Size * 9,
                Opacity = 0
            };
            var dot = new Ellipse
            {
                Fill = star.Warm ? _warmBrush : _starBrush,
                Width = star.Size,
                Height = star.Size,
                Opacity = 0.72,
                Tag = glow
            };
            _dots.Add(dot);
            ParticleCanvas.Children.Add(glow);
            ParticleCanvas.Children.Add(dot);
        }
    }

    private void UpdateScene(double dt)
    {
        var w = Math.Max(1, ParticleCanvas.ActualWidth);
        var h = Math.Max(1, ParticleCanvas.ActualHeight);
        var target = _isSpeaking ? 1.0 : 0.0;
        _assembly += (target - _assembly) * Math.Min(1, dt * (_isSpeaking ? 5.8 : 3.2));
        var eased = EaseInOut(_assembly);
        var scale = Math.Min(w, h) * 0.34;
        var camera = 4.2;
        var spin = _time * (0.18 + eased * 0.55);

        for (var i = 0; i < _stars.Count; i++)
        {
            var s = _stars[i];
            var idleX = s.IdleX + Math.Sin(_time * s.Speed + s.Phase) * 0.12;
            var idleY = s.IdleY + Math.Cos(_time * s.Speed * 0.77 + s.Phase) * 0.1;
            var idleZ = s.IdleZ + Math.Sin(_time * s.Speed * 0.43 + s.Phase) * 0.2;
            var x = Lerp(idleX, s.SphereX, eased);
            var y = Lerp(idleY, s.SphereY, eased);
            var z = Lerp(idleZ, s.SphereZ, eased);
            var cos = Math.Cos(spin);
            var sin = Math.Sin(spin);
            var rx = x * cos - z * sin;
            var rz = x * sin + z * cos;
            var perspective = camera / (camera + rz);
            var screenX = w * 0.5 + rx * scale * perspective;
            var screenY = h * 0.48 + y * scale * perspective;
            var dotSize = Math.Clamp(s.Size * (0.8 + perspective * 0.85 + eased * 0.45), 1.1, 6.5);
            var dot = _dots[i];
            var glow = (Ellipse)dot.Tag;
            dot.Width = dotSize;
            dot.Height = dotSize;
            dot.Opacity = Math.Clamp(0.24 + perspective * 0.32 + eased * 0.36, 0.18, 0.96);
            glow.Width = dotSize * (5 + eased * 4);
            glow.Height = dotSize * (5 + eased * 4);
            glow.Opacity = eased * 0.16 * Math.Clamp(perspective, 0.4, 1.25);
            Canvas.SetLeft(dot, screenX - dot.Width / 2);
            Canvas.SetTop(dot, screenY - dot.Height / 2);
            Canvas.SetLeft(glow, screenX - glow.Width / 2);
            Canvas.SetTop(glow, screenY - glow.Height / 2);
        }
    }

    private static int FindSpeechCut(string text, bool flush)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return -1;
        }

        var searchLimit = Math.Min(text.Length, 420);
        for (var i = searchLimit - 1; i >= 0; i--)
        {
            if ((text[i] == '.' || text[i] == '!' || text[i] == '?' || text[i] == '\n') && i >= 36)
            {
                return i + 1;
            }
        }

        if (text.Length > 520)
        {
            var comma = text.LastIndexOfAny(new[] { ',', ';', ':' }, Math.Min(text.Length - 1, 420));
            return comma > 80 ? comma + 1 : 420;
        }

        return flush ? text.Length : -1;
    }

    private static List<VoiceStar> BuildStars()
    {
        var random = new Random(8642);
        var stars = new List<VoiceStar>();
        for (var i = 0; i < 520; i++)
        {
            var u = random.NextDouble();
            var v = random.NextDouble();
            var theta = 2 * Math.PI * u;
            var phi = Math.Acos(2 * v - 1);
            var radius = 0.92 + random.NextDouble() * 0.12;
            var sphereX = radius * Math.Sin(phi) * Math.Cos(theta);
            var sphereY = radius * Math.Cos(phi);
            var sphereZ = radius * Math.Sin(phi) * Math.Sin(theta);
            stars.Add(new VoiceStar(
                (random.NextDouble() - 0.5) * 5.8,
                (random.NextDouble() - 0.5) * 3.2,
                (random.NextDouble() - 0.5) * 5.2,
                sphereX,
                sphereY,
                sphereZ,
                0.35 + random.NextDouble() * 1.4,
                random.NextDouble() * Math.PI * 2,
                1.4 + random.NextDouble() * 2.4,
                random.NextDouble() > 0.72));
        }
        return stars;
    }

    private static string TrimForStatus(string text)
    {
        var clean = text.Replace("\r", " ").Replace("\n", " ").Trim();
        return clean.Length > 110 ? clean[..110] + "..." : clean;
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); } catch { }
    }

    private static double EaseInOut(double t) => t * t * (3 - 2 * t);
    private static double Lerp(double start, double stop, double amount) => start + (stop - start) * amount;

    private sealed record VoiceStar(
        double IdleX,
        double IdleY,
        double IdleZ,
        double SphereX,
        double SphereY,
        double SphereZ,
        double Speed,
        double Phase,
        double Size,
        bool Warm);
}
