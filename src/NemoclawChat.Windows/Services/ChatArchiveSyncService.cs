namespace NemoclawChat_Windows.Services;

public sealed class ChatArchiveSyncService : IDisposable, IAsyncDisposable
{
    private readonly TimeSpan _uploadDebounce = TimeSpan.FromSeconds(5);
    private readonly TimeSpan _pollInterval = TimeSpan.FromMinutes(2);
    private readonly SemaphoreSlim _syncLock = new(1, 1);
    private readonly object _stateGate = new();
    private CancellationTokenSource? _lifetimeCts;
    private CancellationTokenSource? _uploadDebounceCts;
    private Task? _pollTask;
    private Task? _eventsTask;
    private readonly HashSet<Task> _uploadTasks = [];
    private int _applyingRemote;
    private bool _started;
    private bool _disposed;

    public void Start()
    {
        lock (_stateGate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_started)
            {
                return;
            }

            _started = true;
            _lifetimeCts = new CancellationTokenSource();
            ChatArchiveStore.Changed += ChatArchiveStore_Changed;
            _pollTask = Task.Run(() => PollLoopAsync(_lifetimeCts.Token));
            _eventsTask = Task.Run(() => ListenForRemoteChangesAsync(_lifetimeCts.Token));
        }
    }

    public async Task StopAsync()
    {
        Task[] tasks;
        CancellationTokenSource? lifetime;
        CancellationTokenSource? debounce;
        lock (_stateGate)
        {
            if (_disposed)
            {
                return;
            }

            _disposed = true;
            _started = false;
            ChatArchiveStore.Changed -= ChatArchiveStore_Changed;
            lifetime = _lifetimeCts;
            debounce = _uploadDebounceCts;
            lifetime?.Cancel();
            debounce?.Cancel();
            tasks = new[] { _pollTask, _eventsTask }
                .Where(task => task is not null)
                .Cast<Task>()
                .Concat(_uploadTasks)
                .ToArray();
        }

        try
        {
            await Task.WhenAll(tasks).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            System.Diagnostics.Trace.WriteLine($"[ChatArchiveSync] stop: {ex}");
        }
        finally
        {
            debounce?.Dispose();
            lifetime?.Dispose();
            _syncLock.Dispose();
        }
    }

    public void Dispose() => StopAsync().GetAwaiter().GetResult();

    public async ValueTask DisposeAsync() => await StopAsync().ConfigureAwait(false);

    private void ChatArchiveStore_Changed()
    {
        if (Volatile.Read(ref _applyingRemote) != 0)
        {
            return;
        }

        lock (_stateGate)
        {
            if (_disposed || _lifetimeCts is null)
            {
                return;
            }

            _uploadDebounceCts?.Cancel();
            _uploadDebounceCts?.Dispose();
            _uploadDebounceCts = CancellationTokenSource.CreateLinkedTokenSource(_lifetimeCts.Token);
            var token = _uploadDebounceCts.Token;
            var uploadTask = Task.Run(async () =>
            {
                try
                {
                    await Task.Delay(_uploadDebounce, token).ConfigureAwait(false);
                    await PushAsync(token).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (token.IsCancellationRequested)
                {
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Trace.WriteLine($"[ChatArchiveSync] upload failed: {ex}");
                }
            }, CancellationToken.None);
            _uploadTasks.Add(uploadTask);
            _ = uploadTask.ContinueWith(
                completed =>
                {
                    lock (_stateGate)
                    {
                        _uploadTasks.Remove(completed);
                    }
                },
                CancellationToken.None,
                TaskContinuationOptions.ExecuteSynchronously,
                TaskScheduler.Default);
        }
    }

    private async Task PollLoopAsync(CancellationToken cancellationToken)
    {
        await Task.Delay(TimeSpan.FromSeconds(3), cancellationToken).ConfigureAwait(false);
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await PullThenPushAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Trace.WriteLine($"[ChatArchiveSync] sync failed: {ex}");
            }

            await Task.Delay(_pollInterval, cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task PullThenPushAsync(CancellationToken cancellationToken)
    {
        await PullAsync(cancellationToken).ConfigureAwait(false);
        await PushAsync(cancellationToken).ConfigureAwait(false);
    }

    private async Task<int> PullAsync(CancellationToken cancellationToken)
    {
        await _syncLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var result = await GatewayService.LoadHubConversationsAsync(AppSettingsStore.Load(), cancellationToken).ConfigureAwait(false);
            if (!result.Success)
            {
                throw new InvalidOperationException(result.Status);
            }
            if (result.Items.Count == 0)
            {
                return 0;
            }

            Interlocked.Exchange(ref _applyingRemote, 1);
            try
            {
                return ChatArchiveStore.Merge(result.Items);
            }
            finally
            {
                Interlocked.Exchange(ref _applyingRemote, 0);
            }
        }
        finally
        {
            _syncLock.Release();
        }
    }

    private async Task PushAsync(CancellationToken cancellationToken)
    {
        await _syncLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var result = await GatewayService.SaveHubConversationsAsync(
                    AppSettingsStore.Load(),
                    ChatArchiveStore.Load(includeDeleted: true),
                    cancellationToken)
                .ConfigureAwait(false);
            if (!result.Success)
            {
                throw new InvalidOperationException(result.Status);
            }
        }
        finally
        {
            _syncLock.Release();
        }
    }

    private async Task ListenForRemoteChangesAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            var delay = TimeSpan.FromSeconds(2);
            try
            {
                await GatewayService.StreamHubConversationEventsAsync(
                        AppSettingsStore.Load(),
                        token => PullAsync(token),
                        cancellationToken)
                    .ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Trace.WriteLine($"[ChatArchiveSync] events failed: {ex}");
                delay = TimeSpan.FromSeconds(10);
            }

            await Task.Delay(delay, cancellationToken).ConfigureAwait(false);
        }
    }
}
