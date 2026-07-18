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
using Windows.Storage;
using Windows.System;
using Microsoft.UI.Xaml.Navigation;

namespace NemoclawChat_Windows.Pages;

[System.Diagnostics.CodeAnalysis.SuppressMessage(
    "Design",
    "CA1001:Types that own disposable fields should be disposable",
    Justification = "La pagina WinUI rilascia recorder, token e player nel ciclo Unloaded; il framework non consuma IDisposable.")]
public sealed partial class VoicePage : Page
{
    private static readonly char[] SoftBreakCharacters = [',', ';', ':', ' '];
    private readonly List<VoiceParticle> _particles = BuildParticles();
    private readonly ProjectedParticle[] _projectedParticles = new ProjectedParticle[520];
    private readonly List<ChatMessageRecord> _history = [];
    private readonly VoiceActivityRecorder _recorder = new();
    private CancellationTokenSource? _callCts;
    private CancellationTokenSource? _turnCts;
    private Task? _callTask;
    private MediaPlayer? _voicePlayer;
    private MediaPlayer? _waitingPlayer;
    private string? _waitingTonePath;
    private DateTimeOffset _lastTranscriptAt = DateTimeOffset.MinValue;
    private volatile VoiceCallPhase _phase = VoiceCallPhase.Idle;
    private double _time;
    private double _assembly;
    private volatile bool _callActive;
    private volatile bool _callReady;
    private bool _callOperationInProgress;
    private string _lastTranscript = string.Empty;
    private string _selectedVoice = "if_sara";
    private double _voiceSpeed = 1.08;
    private bool _pushToTalkEnabled;
    private bool _showTranscript;
    private string _particleShape = VoicePreferencesStore.SphereShape;
    private bool _autoStartRequested;

    public VoicePage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _autoStartRequested = e.Parameter is VoiceNavigationRequest { AutoStart: true };
    }

    private void Page_Loaded(object sender, RoutedEventArgs e)
    {
        Root.Focus(FocusState.Programmatic);
        ParticleCanvas.Paused = false;
        LoadVoiceProfile();
        if (_autoStartRequested && !_callActive)
        {
            _autoStartRequested = false;
            _ = StartCallSessionAsync();
        }
    }

    private async void Page_Unloaded(object sender, RoutedEventArgs e)
    {
        ParticleCanvas.Paused = true;
        try
        {
            await StopCallSessionAsync(updateVisual: false);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Trace.WriteLine($"[VoicePage] Cleanup failed: {ex}");
        }
        finally
        {
            StopWaitingTone();
            if (_waitingTonePath is not null)
            {
                TryDelete(_waitingTonePath);
                _waitingTonePath = null;
            }
        }
    }

    private async void CallButton_Click(object sender, RoutedEventArgs e)
    {
        if (_callOperationInProgress)
        {
            return;
        }

        _callOperationInProgress = true;
        CallButton.IsEnabled = false;
        try
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
        finally
        {
            _callOperationInProgress = false;
            CallButton.IsEnabled = true;
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
        else if (e.Key == VirtualKey.Space && _pushToTalkEnabled)
        {
            if (_callActive)
            {
                InterruptCurrentTurn();
            }
            else
            {
                _ = StartCallSessionAsync();
            }
            e.Handled = true;
        }
    }

    private async Task StartCallSessionAsync()
    {
        LoadVoiceProfile();
        _callCts?.Cancel();
        _callCts?.Dispose();
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
            _callActive = false;
            _callReady = false;
        }
        catch (Exception ex)
        {
            _callActive = false;
            _callReady = false;
            SetPhase(VoiceCallPhase.Error, $"Voce non disponibile: {ex.Message}");
            SetCallVisual(false);
        }
        finally
        {
            if (!_callActive && _callTask is null)
            {
                _callCts?.Dispose();
                _callCts = null;
            }
        }
    }

    private async Task StopCallSessionAsync(bool updateVisual = true)
    {
        _callActive = false;
        _callReady = false;
        var callCts = _callCts;
        _callCts = null;
        callCts?.Cancel();
        _recorder.Stop();
        _turnCts?.Cancel();
        _voicePlayer?.Dispose();
        _voicePlayer = null;
        var callTask = _callTask;
        _callTask = null;
        if (callTask is not null)
        {
            try { await callTask.ConfigureAwait(true); }
            catch (OperationCanceledException) when (callCts?.IsCancellationRequested == true)
            {
                // Arresto richiesto dall'utente o dalla navigazione.
            }
            catch (Exception ex) { System.Diagnostics.Trace.WriteLine($"[VoicePage] Call loop stop failed: {ex}"); }
        }
        callCts?.Dispose();
        StopWaitingTone();
        if (updateVisual)
        {
            var summary = await SaveVoiceConversationAsync();
            SetPhase(VoiceCallPhase.Idle, summary);
            SetCallVisual(false);
        }
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
                if (cancellationToken.IsCancellationRequested)
                {
                    break;
                }
                await Task.Delay(900, cancellationToken).ConfigureAwait(false);
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
        using var turnCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        _turnCts = turnCts;
        var turnToken = turnCts.Token;
        SetPhase(VoiceCallPhase.Thinking, "Hermes sta pensando...");

        try
        {
            await foreach (var ev in ChatStreamClient.StreamChatAsync(
                               settings,
                               "Chat",
                               $"Sei in una chiamata vocale. Rispondi subito in italiano, con tono naturale e frasi brevi. Niente markdown, elenchi o preamboli. La prima frase deve avere al massimo 8 parole. Utente: {prompt}",
                               contextHistory,
                               conversationId: null,
                               previousResponseId: null,
                               cancellationToken: turnToken))
            {
                turnToken.ThrowIfCancellationRequested();
                switch (ev)
                {
                    case StreamTextDelta delta:
                        answer.Append(delta.Delta);
                        speechBuffer.Append(delta.Delta);
                        SetStatus($"Hermes: {TrimForStatus(answer.ToString())}");
                        foreach (var segment in DrainSpeechSegments(speechBuffer, flush: false))
                        {
                            playbackChain = QueueSpeechSegmentAsync(playbackChain, settings, segment, turnToken);
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
                playbackChain = QueueSpeechSegmentAsync(playbackChain, settings, segment, turnToken);
            }
            await playbackChain.ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            turnCts.Cancel();
            try { await playbackChain.ConfigureAwait(false); } catch (OperationCanceledException) { }
            if (_callActive) SetPhase(VoiceCallPhase.Listening, "Interrotto. Ti ascolto.");
            return;
        }
        catch
        {
            turnCts.Cancel();
            try { await playbackChain.ConfigureAwait(false); }
            catch (Exception ex) { System.Diagnostics.Trace.WriteLine($"[VoicePage] Speech queue cleanup failed: {ex.Message}"); }
            throw;
        }

        var finalText = answer.ToString().Trim();
        if (!string.IsNullOrWhiteSpace(finalText))
        {
            _history.Add(new ChatMessageRecord("Hermes", finalText, DateTimeOffset.Now));
            TrimHistory();
            RefreshTranscript();
        }
        _turnCts = null;
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
        return SynthesizeAndPlayAfterAsync(previous, settings, text, cancellationToken);
    }

    private async Task SynthesizeAndPlayAfterAsync(
        Task previous,
        AppSettings settings,
        string text,
        CancellationToken cancellationToken)
    {
        await previous.ConfigureAwait(false);
        cancellationToken.ThrowIfCancellationRequested();
        var file = await SpeechGatewayService.SynthesizeToFileAsync(settings, text, _selectedVoice, _voiceSpeed, cancellationToken).ConfigureAwait(false);
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
            if (Volatile.Read(ref finished) != 0)
            {
                registration.Dispose();
                return;
            }
            if (Volatile.Read(ref finished) == 0)
            {
                SetPhase(VoiceCallPhase.Speaking);
                try
                {
                    player.Play();
                }
                catch (Exception ex)
                {
                    Complete(ex);
                }
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
        var shapeScale = _particleShape == VoicePreferencesStore.NeuralCoreShape ? 1.08 : 1.0;
        var scale = Math.Min(width, height) * (0.355 + breath + primary + secondary) * shapeScale;
        var camera = 4.1;
        var spin = _particleShape == VoicePreferencesStore.HermesHeadShape
            ? Math.Sin(_time * 0.28) * 0.34
            : _time * (0.17 + eased * (_particleShape == VoicePreferencesStore.NeuralCoreShape ? 0.52 : 0.38));
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
                var target = ParticleTargetFor(particle);
                var x = Lerp(idleX, target.X, eased) + Math.Sin(particle.Phase + eased * Math.PI * 2) * gatherArc;
                var y = Lerp(idleY, target.Y, eased) + Math.Cos(particle.Phase * 0.73 + eased * Math.PI * 2) * gatherArc * 0.65;
                var z = Lerp(idleZ, target.Z, eased);

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
            var soft = text.LastIndexOfAny(SoftBreakCharacters, Math.Min(text.Length - 1, 46));
            return soft > 25 ? soft + 1 : 46;
        }
        return flush ? text.Length : -1;
    }

    private void InterruptCurrentTurn()
    {
        _turnCts?.Cancel();
        _voicePlayer?.Dispose();
        _voicePlayer = null;
        SetPhase(VoiceCallPhase.Listening, "Hermes interrotto. Ti ascolto.");
    }

    private void LoadVoiceProfile()
    {
        var profile = VoicePreferencesStore.Load(AppSettingsStore.Load().ActiveProjectId);
        _selectedVoice = profile.Voice;
        _voiceSpeed = profile.Speed;
        _pushToTalkEnabled = profile.PushToTalk;
        _showTranscript = profile.ShowTranscript;
        _particleShape = VoicePreferencesStore.NormalizeParticleShape(profile.ParticleShape);
        TranscriptBorder.Visibility = _showTranscript ? Visibility.Visible : Visibility.Collapsed;
    }

    private void RefreshTranscript() => DispatcherQueue.TryEnqueue(() => TranscriptText.Text = string.Join("\n\n", _history.Select(message => $"{message.Author}\n{message.Text}")));

    private async Task<string> SaveVoiceConversationAsync()
    {
        if (_history.Count == 0) return "Chiamata chiusa.";
        var projectId = AppSettingsStore.Load().ActiveProjectId;
        var saved = ChatArchiveStore.SaveSnapshot(null, "Chat", "Chiamata vocale", _history, "voice-call", projectId: projectId);
        var transcript = string.Join("\n", _history.Select(message => $"{message.Author}: {message.Text}"));
        var summary = $"Chiamata con {_history.Count} interventi. Ultimo punto: {_history.Last().Text}";
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(45));
            var builder = new StringBuilder();
            await foreach (var ev in ChatStreamClient.StreamChatAsync(AppSettingsStore.Load(), "Chat", $"Riassumi in massimo 5 righe questa chiamata, indicando decisioni e prossimi passi.\n\n{transcript}", [], saved.Id, null, cancellationToken: timeout.Token))
            {
                if (ev is StreamTextDelta delta) builder.Append(delta.Delta);
                if (ev is StreamDone done && !string.IsNullOrWhiteSpace(done.AccumulatedText)) { builder.Clear(); builder.Append(done.AccumulatedText); }
            }
            if (!string.IsNullOrWhiteSpace(builder.ToString())) summary = builder.ToString().Trim();
        }
        catch (Exception ex) when (ex is OperationCanceledException or HttpRequestException or InvalidOperationException) { System.Diagnostics.Trace.WriteLine($"[VoicePage] summary: {ex.Message}"); }
        ChatArchiveStore.UpdateConversationMetadata(saved.Id, "Chiamate", ["voce"], projectId, [], summary);
        return $"Chiamata salvata. {TrimForStatus(summary)}";
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
        RefreshTranscript();
    }

    private static List<VoiceParticle> BuildParticles()
    {
        var random = new Random(8642);
        const int particleCount = 520;
        var particles = new List<VoiceParticle>(particleCount);
        for (var index = 0; index < particleCount; index++)
        {
            particles.Add(new VoiceParticle(
                (random.NextDouble() - 0.5) * 7.0,
                (random.NextDouble() - 0.5) * 4.8,
                (random.NextDouble() - 0.5) * 6.2,
                BuildSphereTarget(random),
                BuildNeuralCoreTarget(index, particleCount, random),
                BuildHermesHeadTarget(index, particleCount, random),
                0.42 + random.NextDouble() * 1.16,
                random.NextDouble() * Math.PI * 2,
                1.35 + random.NextDouble() * 2.4,
                random.NextDouble() > 0.72));
        }
        return particles;
    }

    private ParticleTarget ParticleTargetFor(VoiceParticle particle) => _particleShape switch
    {
        VoicePreferencesStore.NeuralCoreShape => particle.NeuralCore,
        VoicePreferencesStore.HermesHeadShape => particle.HermesHead,
        _ => particle.Sphere
    };

    private static ParticleTarget BuildSphereTarget(Random random)
    {
        var theta = 2 * Math.PI * random.NextDouble();
        var phi = Math.Acos(2 * random.NextDouble() - 1);
        var radius = 0.91 + random.NextDouble() * 0.18;
        return new ParticleTarget(
            radius * Math.Sin(phi) * Math.Cos(theta),
            radius * Math.Cos(phi),
            radius * Math.Sin(phi) * Math.Sin(theta));
    }

    private static ParticleTarget BuildNeuralCoreTarget(int index, int count, Random random)
    {
        if (index < count * 0.42)
        {
            var around = 2 * Math.PI * random.NextDouble();
            var tube = 2 * Math.PI * random.NextDouble();
            var tubeRadius = 0.105 + random.NextDouble() * 0.075;
            var radius = 0.72 + tubeRadius * Math.Cos(tube);
            return new ParticleTarget(
                radius * Math.Cos(around),
                radius * Math.Sin(around),
                tubeRadius * Math.Sin(tube));
        }

        if (index < count * 0.74)
        {
            var t = random.NextDouble();
            var branch = index % 2 == 0 ? 0 : Math.PI;
            var angle = t * Math.PI * 5 + branch;
            var jitter = (random.NextDouble() - 0.5) * 0.045;
            return new ParticleTarget(
                (0.27 + jitter) * Math.Cos(angle),
                (t - 0.5) * 1.48,
                (0.27 + jitter) * Math.Sin(angle));
        }

        var theta = 2 * Math.PI * random.NextDouble();
        var phi = Math.Acos(2 * random.NextDouble() - 1);
        var nucleusRadius = 0.19 + random.NextDouble() * 0.17;
        return new ParticleTarget(
            nucleusRadius * Math.Sin(phi) * Math.Cos(theta),
            nucleusRadius * Math.Cos(phi),
            nucleusRadius * Math.Sin(phi) * Math.Sin(theta));
    }

    private static ParticleTarget BuildHermesHeadTarget(int index, int count, Random random)
    {
        if (index < count * 0.46)
        {
            var theta = 2 * Math.PI * random.NextDouble();
            var phi = Math.Acos(2 * random.NextDouble() - 1);
            var y = 0.08 + 0.78 * Math.Cos(phi);
            var jawTaper = 1 - 0.27 * Math.Clamp((y - 0.18) / 0.68, 0, 1);
            return new ParticleTarget(
                0.58 * Math.Sin(phi) * Math.Cos(theta) * jawTaper,
                y,
                0.42 * Math.Sin(phi) * Math.Sin(theta));
        }

        if (index < count * 0.68)
        {
            var polar = random.NextDouble() * Math.PI * 0.5;
            var around = random.NextDouble() * Math.PI * 2;
            return new ParticleTarget(
                0.68 * Math.Sin(polar) * Math.Cos(around),
                -0.46 - 0.60 * Math.Cos(polar),
                0.52 * Math.Sin(polar) * Math.Sin(around));
        }

        if (index < count * 0.84)
        {
            var side = index % 2 == 0 ? -1.0 : 1.0;
            var t = random.NextDouble();
            var feather = (index % 4) / 3.0;
            return new ParticleTarget(
                side * (0.60 + 0.50 * t),
                -0.54 - (0.20 + feather * 0.24) * t + feather * 0.035,
                -0.03 + (random.NextDouble() - 0.5) * 0.16);
        }

        if (index < count * 0.94)
        {
            var side = index % 2 == 0 ? -1.0 : 1.0;
            var angle = random.NextDouble() * Math.PI;
            return new ParticleTarget(
                side * (0.20 + 0.15 * Math.Cos(angle)),
                -0.10 + 0.07 * Math.Sin(angle),
                -0.405 - 0.035 * Math.Sin(angle));
        }

        if (index % 2 == 0)
        {
            var t = random.NextDouble();
            return new ParticleTarget(
                (random.NextDouble() - 0.5) * 0.045,
                -0.12 + t * 0.43,
                -0.43 - Math.Sin(t * Math.PI) * 0.10);
        }

        var rim = random.NextDouble() * 2 - 1;
        return new ParticleTarget(
            rim * 0.64,
            -0.46 + Math.Abs(rim) * 0.055,
            -0.31 + (random.NextDouble() - 0.5) * 0.06);
    }

    private static string TrimForStatus(string text)
    {
        var clean = text.Replace("\r", " ").Replace("\n", " ").Trim();
        return clean.Length > 110 ? clean[..110] + "..." : clean;
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); }
        catch (Exception ex) { System.Diagnostics.Trace.WriteLine($"[VoicePage] Temp cleanup failed for '{path}': {ex.Message}"); }
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
        ParticleTarget Sphere,
        ParticleTarget NeuralCore,
        ParticleTarget HermesHead,
        double Speed,
        double Phase,
        double Size,
        bool Hot);

    private readonly record struct ParticleTarget(double X, double Y, double Z);
    private readonly record struct ProjectedParticle(double X, double Y, double Size, double Alpha, bool Hot);
}

public sealed record VoiceNavigationRequest(bool AutoStart = false);
