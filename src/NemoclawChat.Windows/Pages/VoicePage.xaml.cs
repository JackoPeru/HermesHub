using Microsoft.Graphics.Canvas;
using Microsoft.Graphics.Canvas.Effects;
using Microsoft.Graphics.Canvas.UI;
using Microsoft.Graphics.Canvas.UI.Xaml;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;
using System.Text;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.System;

namespace NemoclawChat_Windows.Pages;

public sealed partial class VoicePage : Page
{
    private readonly List<VoiceParticle> _particles = BuildParticles();
    private readonly ProjectedParticle[] _projectedParticles = new ProjectedParticle[520];
    private readonly List<ChatMessageRecord> _history = [];
    private readonly VoiceActivityRecorder _recorder = new();
    private CancellationTokenSource? _callCts;
    private Task? _callTask;
    private MediaPlayer? _voicePlayer;
    private MediaPlayer? _waitingPlayer;
    private string? _waitingTonePath;
    private DateTimeOffset _lastTranscriptAt = DateTimeOffset.MinValue;
    private VoiceCallPhase _phase = VoiceCallPhase.Idle;
    private double _time;
    private double _assembly;
    private bool _callActive;
    private bool _callReady;
    private string _lastTranscript = string.Empty;

    public VoicePage()
    {
        InitializeComponent();
    }

    private void Page_Loaded(object sender, RoutedEventArgs e)
    {
        Root.Focus(FocusState.Programmatic);
        ParticleCanvas.Paused = false;
    }

    private void Page_Unloaded(object sender, RoutedEventArgs e)
    {
        ParticleCanvas.Paused = true;
        _callActive = false;
        _callReady = false;
        _callCts?.Cancel();
        _recorder.Stop();
        _voicePlayer?.Dispose();
        StopWaitingTone();
        if (_waitingTonePath is not null)
        {
            TryDelete(_waitingTonePath);
            _waitingTonePath = null;
        }
    }

    private async void CallButton_Click(object sender, RoutedEventArgs e)
    {
        if (_callActive)
        {
            await StopCallSessionAsync();
        }
        else
        {
            await StartCallSessionAsync();
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

    private async Task StartCallSessionAsync()
    {
        _callCts?.Cancel();
        _callCts = new CancellationTokenSource();
        _callActive = true;
        _callReady = false;
        _lastTranscript = string.Empty;
        SetPhase(VoiceCallPhase.Connecting, "Connessione a Hermes...");
        SetCallVisual(true);

        try
        {
            var settings = AppSettingsStore.Load();
            await SpeechGatewayService.EnsureReadyAsync(settings, _callCts.Token).ConfigureAwait(true);
            if (!_callActive)
            {
                return;
            }
            _callReady = true;
            SetPhase(VoiceCallPhase.Listening, "Ti ascolto.");
            _callTask = ListenLoopAsync(_callCts.Token);
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            _callActive = false;
            _callReady = false;
            SetPhase(VoiceCallPhase.Error, $"Voce non disponibile: {ex.Message}");
            SetCallVisual(false);
        }
    }

    private async Task StopCallSessionAsync()
    {
        _callActive = false;
        _callReady = false;
        _callCts?.Cancel();
        _recorder.Stop();
        _voicePlayer?.Dispose();
        _voicePlayer = null;
        var callTask = _callTask;
        _callTask = null;
        if (callTask is not null)
        {
            try { await callTask.ConfigureAwait(true); }
            catch (OperationCanceledException) { }
            catch { }
        }
        SetPhase(VoiceCallPhase.Idle, "Chiamata chiusa.");
        SetCallVisual(false);
    }

    private async Task ListenLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested && _callActive)
        {
            string? path = null;
            try
            {
                SetPhase(VoiceCallPhase.Listening, "Ti ascolto.");
                path = await _recorder.CaptureUtteranceAsync(cancellationToken).ConfigureAwait(false);
                if (string.IsNullOrWhiteSpace(path))
                {
                    continue;
                }

                SetPhase(VoiceCallPhase.Transcribing, "Trascrivo...");
                var settings = AppSettingsStore.Load();
                var text = await SpeechGatewayService.TranscribeFileAsync(settings, path, cancellationToken).ConfigureAwait(false);
                if (!ShouldSendTranscript(text))
                {
                    continue;
                }

                SetStatus($"Tu: {TrimForStatus(text)}");
                await SendVoiceTurnAsync(settings, text, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                SetPhase(VoiceCallPhase.Error, $"Errore voce: {ex.Message}");
                await Task.Delay(900, CancellationToken.None).ConfigureAwait(false);
            }
            finally
            {
                if (!string.IsNullOrWhiteSpace(path))
                {
                    TryDelete(path);
                }
            }
        }
    }

    private async Task SendVoiceTurnAsync(AppSettings settings, string prompt, CancellationToken cancellationToken)
    {
        var contextHistory = _history.TakeLast(10).ToList();
        _history.Add(new ChatMessageRecord("Tu", prompt, DateTimeOffset.Now));
        TrimHistory();

        var answer = new StringBuilder();
        var speechBuffer = new StringBuilder();
        Task playbackChain = Task.CompletedTask;
        SetPhase(VoiceCallPhase.Thinking, "Hermes sta pensando...");

        await foreach (var ev in ChatStreamClient.StreamChatAsync(
                           settings,
                           "Chat",
                           $"Sei in una chiamata vocale. Rispondi subito in italiano, con tono naturale e frasi brevi. Niente markdown, elenchi o preamboli. La prima frase deve avere al massimo 8 parole. Utente: {prompt}",
                           contextHistory,
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
                    SetStatus($"Hermes: {TrimForStatus(answer.ToString())}");
                    foreach (var segment in DrainSpeechSegments(speechBuffer, flush: false))
                    {
                        playbackChain = QueueSpeechSegmentAsync(playbackChain, settings, segment, cancellationToken);
                    }
                    break;
                case StreamDone done when !string.IsNullOrWhiteSpace(done.AccumulatedText):
                    answer.Clear();
                    answer.Append(done.AccumulatedText);
                    break;
                case StreamError error:
                    throw new InvalidOperationException(error.Message);
            }
        }

        foreach (var segment in DrainSpeechSegments(speechBuffer, flush: true))
        {
            playbackChain = QueueSpeechSegmentAsync(playbackChain, settings, segment, cancellationToken);
        }
        await playbackChain.ConfigureAwait(false);

        var finalText = answer.ToString().Trim();
        if (!string.IsNullOrWhiteSpace(finalText))
        {
            _history.Add(new ChatMessageRecord("Hermes", finalText, DateTimeOffset.Now));
            TrimHistory();
        }
        if (_callActive)
        {
            SetPhase(VoiceCallPhase.Listening, "Ti ascolto.");
        }
    }

    private Task QueueSpeechSegmentAsync(
        Task previous,
        AppSettings settings,
        string text,
        CancellationToken cancellationToken)
    {
        var synthesis = SpeechGatewayService.SynthesizeToFileAsync(settings, text, cancellationToken);
        return PlaySynthesizedAfterAsync(previous, synthesis, cancellationToken);
    }

    private async Task PlaySynthesizedAfterAsync(
        Task previous,
        Task<string> synthesis,
        CancellationToken cancellationToken)
    {
        var file = await synthesis.ConfigureAwait(false);
        try
        {
            await previous.ConfigureAwait(false);
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
        if (!DispatcherQueue.TryEnqueue(() =>
        {
            _voicePlayer?.Dispose();
            var player = new MediaPlayer
            {
                Source = MediaSource.CreateFromUri(new Uri(filePath))
            };
            _voicePlayer = player;
            var finished = 0;
            CancellationTokenRegistration registration = default;

            void Complete(Exception? error = null, bool canceled = false)
            {
                if (Interlocked.Exchange(ref finished, 1) != 0)
                {
                    return;
                }
                registration.Dispose();
                player.Dispose();
                if (ReferenceEquals(_voicePlayer, player))
                {
                    _voicePlayer = null;
                }
                if (canceled)
                {
                    completion.TrySetCanceled(cancellationToken);
                }
                else if (error is not null)
                {
                    completion.TrySetException(error);
                }
                else
                {
                    completion.TrySetResult();
                }
            }

            player.MediaEnded += (_, _) => Complete();
            player.MediaFailed += (_, args) => Complete(new InvalidOperationException(args.ErrorMessage));
            if (cancellationToken.IsCancellationRequested)
            {
                Complete(canceled: true);
                return;
            }
            registration = cancellationToken.Register(() => DispatcherQueue.TryEnqueue(() => Complete(canceled: true)));
            if (Volatile.Read(ref finished) == 0)
            {
                SetPhase(VoiceCallPhase.Speaking);
                player.Play();
            }
        }))
        {
            completion.TrySetException(new InvalidOperationException("Riproduzione audio non disponibile."));
        }
        return completion.Task;
    }

    private void ParticleCanvas_Update(ICanvasAnimatedControl sender, CanvasAnimatedUpdateEventArgs args)
    {
        var dt = Math.Min(0.05, args.Timing.ElapsedTime.TotalSeconds);
        _time += dt;
        var target = _callActive && _callReady ? 1.0 : 0.0;
        var duration = target > _assembly ? 2.2 : 1.2;
        var step = dt / duration;
        _assembly = target > _assembly
            ? Math.Min(target, _assembly + step)
            : Math.Max(target, _assembly - step);
    }

    private void ParticleCanvas_Draw(ICanvasAnimatedControl sender, CanvasAnimatedDrawEventArgs args)
    {
        var width = Math.Max(1, sender.Size.Width);
        var height = Math.Max(1, sender.Size.Height);
        var eased = EaseInOut(_assembly);
        var speaking = _phase == VoiceCallPhase.Speaking;
        var primary = speaking ? Math.Pow(Math.Max(0, Math.Sin(_time * 10.8)), 2) * 0.105 : 0;
        var secondary = speaking ? Math.Pow(Math.Max(0, Math.Sin(_time * 17.1 + 0.8)), 2) * 0.035 : 0;
        var breath = _assembly > 0 ? Math.Sin(_time * 1.35) * 0.012 : 0;
        var scale = Math.Min(width, height) * (0.355 + breath + primary + secondary);
        var camera = 4.1;
        var spin = _time * (0.17 + eased * 0.38);
        var tilt = Math.Sin(_time * 0.31) * 0.16;
        using var glowLayer = new CanvasCommandList(sender.Device);
        using (var glowSession = glowLayer.CreateDrawingSession())
        {
            for (var index = 0; index < _particles.Count; index++)
            {
                var particle = _particles[index];
                var idleX = particle.IdleX + Math.Sin(_time * particle.Speed + particle.Phase) * 0.14;
                var idleY = particle.IdleY + Math.Cos(_time * particle.Speed * 0.73 + particle.Phase) * 0.11;
                var idleZ = particle.IdleZ + Math.Sin(_time * particle.Speed * 0.41 + particle.Phase) * 0.19;
                var gatherArc = Math.Sin(Math.PI * eased) * 0.24;
                var x = Lerp(idleX, particle.SphereX, eased) + Math.Sin(particle.Phase + eased * Math.PI * 2) * gatherArc;
                var y = Lerp(idleY, particle.SphereY, eased) + Math.Cos(particle.Phase * 0.73 + eased * Math.PI * 2) * gatherArc * 0.65;
                var z = Lerp(idleZ, particle.SphereZ, eased);

                var cosSpin = Math.Cos(spin);
                var sinSpin = Math.Sin(spin);
                var spunX = x * cosSpin - z * sinSpin;
                var spunZ = x * sinSpin + z * cosSpin;
                var cosTilt = Math.Cos(tilt);
                var sinTilt = Math.Sin(tilt);
                var rotatedY = y * cosTilt - spunZ * sinTilt;
                var rotatedZ = y * sinTilt + spunZ * cosTilt;
                var perspective = camera / Math.Max(0.65, camera + rotatedZ);
                var screenX = width * 0.5 + spunX * scale * perspective;
                var screenY = height * 0.46 + rotatedY * scale * perspective;
                var dotSize = Math.Clamp(particle.Size * (0.7 + perspective * 0.55 + eased * 0.35), 1.1, 6.2);
                var alpha = Math.Clamp(0.3 + perspective * 0.22 + eased * 0.28, 0.28, 0.98);
                _projectedParticles[index] = new ProjectedParticle(screenX, screenY, dotSize, alpha, particle.Hot);

                var glowAlpha = Math.Clamp(0.16 + eased * 0.12 + (primary + secondary) * 1.35, 0.14, 0.48);
                var glowColor = Microsoft.UI.ColorHelper.FromArgb((byte)(glowAlpha * 255), 255, particle.Hot ? (byte)154 : (byte)96, 18);
                glowSession.FillCircle((float)screenX, (float)screenY, (float)(dotSize * 1.65), glowColor);
            }
        }

        using var blurredGlow = new GaussianBlurEffect
        {
            Source = glowLayer,
            BlurAmount = (float)(5.5 + (primary + secondary) * 24)
        };
        args.DrawingSession.DrawImage(blurredGlow);

        for (var index = 0; index < _particles.Count; index++)
        {
            var projected = _projectedParticles[index];
            var coreColor = projected.Hot
                ? Microsoft.UI.ColorHelper.FromArgb((byte)(projected.Alpha * 255), 255, 226, 118)
                : Microsoft.UI.ColorHelper.FromArgb((byte)(projected.Alpha * 255), 255, 132, 32);
            args.DrawingSession.FillCircle((float)projected.X, (float)projected.Y, (float)projected.Size, coreColor);
        }
    }

    private bool ShouldSendTranscript(string text)
    {
        var clean = text.Trim();
        if (clean.Length < 2)
        {
            return false;
        }
        var normalized = clean.Trim('.', ',', '!', '?', ' ').ToLowerInvariant();
        if (normalized is "grazie" or "sottotitoli e revisione a cura di qtss" or "sottotitoli creati dalla comunita amara.org")
        {
            return false;
        }

        var now = DateTimeOffset.Now;
        if (string.Equals(clean, _lastTranscript, StringComparison.OrdinalIgnoreCase) &&
            (now - _lastTranscriptAt).TotalSeconds < 8)
        {
            return false;
        }
        _lastTranscript = clean;
        _lastTranscriptAt = now;
        return true;
    }

    private static List<string> DrainSpeechSegments(StringBuilder buffer, bool flush)
    {
        var segments = new List<string>();
        while (true)
        {
            var text = buffer.ToString();
            var cut = FindSpeechCut(text, flush);
            if (cut <= 0)
            {
                return segments;
            }
            var segment = text[..cut].Trim();
            buffer.Remove(0, cut);
            if (segment.Length >= 2)
            {
                segments.Add(segment);
            }
        }
    }

    private static int FindSpeechCut(string text, bool flush)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return -1;
        }
        var searchLimit = Math.Min(text.Length, 76);
        for (var index = searchLimit - 1; index >= 0; index--)
        {
            if ((text[index] == '.' || text[index] == '!' || text[index] == '?' || text[index] == '\n') && index >= 9)
            {
                return index + 1;
            }
        }
        if (text.Length > 46)
        {
            var soft = text.LastIndexOfAny(new[] { ',', ';', ':', ' ' }, Math.Min(text.Length - 1, 46));
            return soft > 25 ? soft + 1 : 46;
        }
        return flush ? text.Length : -1;
    }

    private void SetPhase(VoiceCallPhase phase, string? status = null)
    {
        var changed = _phase != phase;
        _phase = phase;
        if (changed)
        {
            SetWaitingToneEnabled(phase == VoiceCallPhase.Thinking);
        }
        if (status is not null)
        {
            SetStatus(status);
        }
    }

    private void SetWaitingToneEnabled(bool enabled)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!enabled || !_callActive)
            {
                StopWaitingTone();
                return;
            }

            StopWaitingTone();
            _waitingTonePath ??= CreateWaitingToneFile();
            _waitingPlayer = new MediaPlayer
            {
                Source = MediaSource.CreateFromUri(new Uri(_waitingTonePath)),
                IsLoopingEnabled = true,
                Volume = 0.25
            };
            _waitingPlayer.Play();
        });
    }

    private void StopWaitingTone()
    {
        _waitingPlayer?.Dispose();
        _waitingPlayer = null;
    }

    private void SetStatus(string text)
    {
        DispatcherQueue.TryEnqueue(() => StatusText.Text = text);
    }

    private void SetCallVisual(bool active)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            CallIcon.Glyph = active ? "\uE778" : "\uE717";
            CallButton.BorderBrush = new SolidColorBrush(active
                ? Microsoft.UI.Colors.OrangeRed
                : Microsoft.UI.ColorHelper.FromArgb(85, 255, 255, 255));
            CallButton.Background = new SolidColorBrush(active
                ? Microsoft.UI.ColorHelper.FromArgb(220, 139, 32, 32)
                : Microsoft.UI.ColorHelper.FromArgb(34, 255, 255, 255));
        });
    }

    private void TrimHistory()
    {
        while (_history.Count > 20)
        {
            _history.RemoveAt(0);
        }
    }

    private static List<VoiceParticle> BuildParticles()
    {
        var random = new Random(8642);
        var particles = new List<VoiceParticle>();
        for (var index = 0; index < 520; index++)
        {
            var theta = 2 * Math.PI * random.NextDouble();
            var phi = Math.Acos(2 * random.NextDouble() - 1);
            var radius = 0.91 + random.NextDouble() * 0.18;
            particles.Add(new VoiceParticle(
                (random.NextDouble() - 0.5) * 7.0,
                (random.NextDouble() - 0.5) * 4.8,
                (random.NextDouble() - 0.5) * 6.2,
                radius * Math.Sin(phi) * Math.Cos(theta),
                radius * Math.Cos(phi),
                radius * Math.Sin(phi) * Math.Sin(theta),
                0.42 + random.NextDouble() * 1.16,
                random.NextDouble() * Math.PI * 2,
                1.35 + random.NextDouble() * 2.4,
                random.NextDouble() > 0.72));
        }
        return particles;
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

    private static string CreateWaitingToneFile()
    {
        const int sampleRate = 24_000;
        const double cycleSeconds = 2.2;
        var sampleCount = (int)(sampleRate * cycleSeconds);
        var path = Path.Combine(Path.GetTempPath(), $"hermes-waiting-{Guid.NewGuid():N}.wav");
        using var stream = File.Create(path);
        using var writer = new BinaryWriter(stream);
        writer.Write("RIFF"u8.ToArray());
        writer.Write(36 + sampleCount * 2);
        writer.Write("WAVE"u8.ToArray());
        writer.Write("fmt "u8.ToArray());
        writer.Write(16);
        writer.Write((short)1);
        writer.Write((short)1);
        writer.Write(sampleRate);
        writer.Write(sampleRate * 2);
        writer.Write((short)2);
        writer.Write((short)16);
        writer.Write("data"u8.ToArray());
        writer.Write(sampleCount * 2);
        for (var index = 0; index < sampleCount; index++)
        {
            var time = index / (double)sampleRate;
            var sample = WaitingNote(time, 0.08, 0.24, 620) + WaitingNote(time, 0.40, 0.28, 780);
            writer.Write((short)Math.Clamp(sample * short.MaxValue * 0.78, short.MinValue, short.MaxValue));
        }
        return path;
    }

    private static double WaitingNote(double time, double start, double duration, double frequency)
    {
        var local = time - start;
        if (local < 0 || local > duration)
        {
            return 0;
        }
        var attack = Math.Min(1, local / 0.035);
        var release = Math.Min(1, (duration - local) / 0.09);
        var envelope = attack * release * 0.22;
        return envelope * (Math.Sin(2 * Math.PI * frequency * local) + 0.18 * Math.Sin(4 * Math.PI * frequency * local));
    }

    private static double EaseInOut(double value) => value * value * (3 - 2 * value);
    private static double Lerp(double start, double stop, double amount) => start + (stop - start) * amount;

    private enum VoiceCallPhase
    {
        Idle,
        Connecting,
        Listening,
        Transcribing,
        Thinking,
        Speaking,
        Error
    }

    private sealed record VoiceParticle(
        double IdleX,
        double IdleY,
        double IdleZ,
        double SphereX,
        double SphereY,
        double SphereZ,
        double Speed,
        double Phase,
        double Size,
        bool Hot);

    private readonly record struct ProjectedParticle(double X, double Y, double Size, double Alpha, bool Hot);
}
