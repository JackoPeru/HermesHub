using Microsoft.UI.Dispatching;

namespace NemoclawChat_Windows.Services;

public sealed class WakeWordListener : IAsyncDisposable
{
    private readonly DispatcherQueue _dispatcherQueue;
    private readonly VoiceActivityRecorder _recorder = new();
    private readonly object _gate = new();
    private CancellationTokenSource? _cancellation;
    private Task? _listenerTask;
    private bool _disposed;

    public WakeWordListener(DispatcherQueue dispatcherQueue)
    {
        _dispatcherQueue = dispatcherQueue;
    }

    public event Action? Detected;

    public void Start()
    {
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_listenerTask is { IsCompleted: false })
            {
                if (_cancellation?.IsCancellationRequested == true)
                {
                    var previousTask = _listenerTask;
                    var previousCancellation = _cancellation;
                    _cancellation = new CancellationTokenSource();
                    var cancellationToken = _cancellation.Token;
                    _listenerTask = Task.Run(async () =>
                    {
                        try { await previousTask.ConfigureAwait(false); }
                        catch (OperationCanceledException) { }
                        finally { previousCancellation.Dispose(); }
                        await ListenAsync(cancellationToken).ConfigureAwait(false);
                    });
                }
                return;
            }

            _cancellation?.Dispose();
            _cancellation = new CancellationTokenSource();
            _listenerTask = Task.Run(() => ListenAsync(_cancellation.Token));
        }
    }

    public void Stop()
    {
        lock (_gate)
        {
            _cancellation?.Cancel();
            _recorder.Stop();
        }
    }

    private async Task ListenAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            string? path = null;
            try
            {
                var settings = AppSettingsStore.Load();
                var profile = VoicePreferencesStore.Load(settings.ActiveProjectId);
                if (!profile.WakeWord)
                {
                    return;
                }

                path = await _recorder.CaptureUtteranceAsync(cancellationToken).ConfigureAwait(false);
                if (string.IsNullOrWhiteSpace(path))
                {
                    continue;
                }

                var transcript = await SpeechGatewayService
                    .TranscribeFileAsync(settings, path, cancellationToken)
                    .ConfigureAwait(false);
                if (!VoicePreferencesStore.TryStripWakePhrase(transcript, profile.WakePhrase, out _))
                {
                    continue;
                }

                _dispatcherQueue.TryEnqueue(() => Detected?.Invoke());
                return;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Trace.WriteLine($"[WakeWordListener] {ex}");
                try
                {
                    await Task.Delay(TimeSpan.FromSeconds(2), cancellationToken).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    return;
                }
            }
            finally
            {
                if (!string.IsNullOrWhiteSpace(path))
                {
                    try { File.Delete(path); }
                    catch (Exception ex) { System.Diagnostics.Trace.WriteLine($"[WakeWordListener] Temp cleanup failed: {ex.Message}"); }
                }
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        Task? listenerTask;
        lock (_gate)
        {
            if (_disposed)
            {
                return;
            }
            _disposed = true;
            _cancellation?.Cancel();
            _recorder.Stop();
            listenerTask = _listenerTask;
        }

        if (listenerTask is not null)
        {
            try { await listenerTask.ConfigureAwait(false); }
            catch (OperationCanceledException) { }
        }

        _cancellation?.Dispose();
        _recorder.Dispose();
    }
}
