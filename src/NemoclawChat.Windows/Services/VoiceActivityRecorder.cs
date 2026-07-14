using NAudio.Wave;

namespace NemoclawChat_Windows.Services;

public sealed class VoiceActivityRecorder : IDisposable
{
    private const int SampleRate = 16_000;
    private const int BitsPerSample = 16;
    private const int PreRollFrames = 15;
    private static readonly TimeSpan EndSilence = TimeSpan.FromMilliseconds(680);
    private static readonly TimeSpan MaxUtterance = TimeSpan.FromSeconds(18);

    private readonly object _gate = new();
    private readonly Queue<byte[]> _preRoll = new();
    private WaveInEvent? _capture;
    private MemoryStream? _pcm;
    private TaskCompletionSource<string?>? _completion;
    private CancellationTokenRegistration _cancellationRegistration;
    private CancellationToken _cancellationToken;
    private DateTimeOffset _speechStartedAt;
    private DateTimeOffset _lastVoiceAt;
    private double _noiseFloor;
    private int _triggerFrames;
    private bool _heardVoice;
    private bool _stopRequested;
    private bool _disposed;

    public Task<string?> CaptureUtteranceAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_capture is not null)
            {
                throw new InvalidOperationException("Registrazione voce gia attiva.");
            }

            ResetState(cancellationToken);
            _capture = new WaveInEvent
            {
                DeviceNumber = 0,
                WaveFormat = new WaveFormat(SampleRate, BitsPerSample, 1),
                BufferMilliseconds = 20,
                NumberOfBuffers = 3
            };
            _capture.DataAvailable += Capture_DataAvailable;
            _capture.RecordingStopped += Capture_RecordingStopped;
            try
            {
                _capture.StartRecording();
                _cancellationRegistration = cancellationToken.Register(RequestStop);
                if (_stopRequested)
                {
                    _cancellationRegistration.Dispose();
                    _cancellationRegistration = default;
                }
            }
            catch
            {
                var pcm = _pcm;
                CleanupCapture();
                pcm?.Dispose();
                throw;
            }

            return _completion!.Task;
        }
    }

    public void Stop() => RequestStop();

    private void ResetState(CancellationToken cancellationToken)
    {
        _preRoll.Clear();
        _pcm?.Dispose();
        _pcm = new MemoryStream(SampleRate * 2 * 8);
        _completion = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
        _cancellationToken = cancellationToken;
        _noiseFloor = 240;
        _triggerFrames = 0;
        _heardVoice = false;
        _stopRequested = false;
        _speechStartedAt = DateTimeOffset.MinValue;
        _lastVoiceAt = DateTimeOffset.MinValue;
    }

    private void Capture_DataAvailable(object? sender, WaveInEventArgs e)
    {
        var chunk = e.Buffer.AsSpan(0, e.BytesRecorded).ToArray();
        var rms = CalculateRms(chunk);
        var threshold = Math.Clamp(_noiseFloor * 2.75, 620, 4_200);
        var voiced = rms >= threshold;
        var now = DateTimeOffset.UtcNow;

        lock (_gate)
        {
            if (_capture is null || _stopRequested)
            {
                return;
            }

            if (!_heardVoice)
            {
                _preRoll.Enqueue(chunk);
                while (_preRoll.Count > PreRollFrames)
                {
                    _preRoll.Dequeue();
                }

                if (voiced)
                {
                    _triggerFrames++;
                }
                else
                {
                    _triggerFrames = Math.Max(0, _triggerFrames - 1);
                    _noiseFloor = _noiseFloor * 0.94 + rms * 0.06;
                }

                if (_triggerFrames < 3)
                {
                    return;
                }

                _heardVoice = true;
                _speechStartedAt = now;
                _lastVoiceAt = now;
                foreach (var preRollChunk in _preRoll)
                {
                    _pcm!.Write(preRollChunk);
                }
                _preRoll.Clear();
                return;
            }

            _pcm!.Write(chunk);
            if (voiced)
            {
                _lastVoiceAt = now;
            }

            var speechDuration = now - _speechStartedAt;
            if (speechDuration >= MaxUtterance ||
                (speechDuration >= TimeSpan.FromMilliseconds(320) && now - _lastVoiceAt >= EndSilence))
            {
                RequestStopLocked();
            }
        }
    }

    private void Capture_RecordingStopped(object? sender, StoppedEventArgs e)
    {
        TaskCompletionSource<string?>? completion;
        MemoryStream? pcm;
        bool heardVoice;
        CancellationToken cancellationToken;

        lock (_gate)
        {
            completion = _completion;
            pcm = _pcm;
            heardVoice = _heardVoice;
            cancellationToken = _cancellationToken;
            CleanupCapture();
        }

        if (completion is null)
        {
            pcm?.Dispose();
            return;
        }
        if (cancellationToken.IsCancellationRequested)
        {
            pcm?.Dispose();
            completion.TrySetCanceled(cancellationToken);
            return;
        }
        if (e.Exception is not null)
        {
            pcm?.Dispose();
            completion.TrySetException(e.Exception);
            return;
        }
        if (!heardVoice || pcm is null || pcm.Length < SampleRate / 4)
        {
            pcm?.Dispose();
            completion.TrySetResult(null);
            return;
        }

        try
        {
            var directory = Path.Combine(Path.GetTempPath(), "HermesHub", "voice");
            Directory.CreateDirectory(directory);
            var path = Path.Combine(directory, $"voice-call-{Guid.NewGuid():N}.wav");
            pcm.Position = 0;
            using (var writer = new WaveFileWriter(path, new WaveFormat(SampleRate, BitsPerSample, 1)))
            {
                pcm.CopyTo(writer);
            }
            pcm.Dispose();
            completion.TrySetResult(path);
        }
        catch (Exception ex)
        {
            pcm.Dispose();
            completion.TrySetException(ex);
        }
    }

    private void RequestStop()
    {
        lock (_gate)
        {
            RequestStopLocked();
        }
    }

    private void RequestStopLocked()
    {
        if (_capture is null || _stopRequested)
        {
            return;
        }
        _stopRequested = true;
        var capture = _capture;
        _ = Task.Run(() =>
        {
            try
            {
                capture.StopRecording();
            }
            catch (Exception ex)
            {
                TaskCompletionSource<string?>? completion = null;
                MemoryStream? pcm = null;
                lock (_gate)
                {
                    if (ReferenceEquals(_capture, capture))
                    {
                        completion = _completion;
                        pcm = _pcm;
                        CleanupCapture();
                    }
                }
                pcm?.Dispose();
                completion?.TrySetException(ex);
            }
        });
    }

    private void CleanupCapture()
    {
        _cancellationRegistration.Dispose();
        if (_capture is not null)
        {
            _capture.DataAvailable -= Capture_DataAvailable;
            _capture.RecordingStopped -= Capture_RecordingStopped;
            _capture.Dispose();
            _capture = null;
        }
        _completion = null;
        _pcm = null;
        _preRoll.Clear();
    }

    private static double CalculateRms(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length < 2)
        {
            return 0;
        }
        double sum = 0;
        var samples = 0;
        for (var index = 0; index + 1 < bytes.Length; index += 2)
        {
            var sample = (short)(bytes[index] | bytes[index + 1] << 8);
            sum += (double)sample * sample;
            samples++;
        }
        return samples == 0 ? 0 : Math.Sqrt(sum / samples);
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed)
            {
                return;
            }
            _disposed = true;
            RequestStopLocked();
        }
    }
}
