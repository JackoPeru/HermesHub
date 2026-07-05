namespace NemoclawChat_Windows.Services;

public sealed class ChatArchiveSyncService : IDisposable
{
    private readonly TimeSpan _uploadDebounce = TimeSpan.FromSeconds(2);
    private readonly TimeSpan _pollInterval = TimeSpan.FromMinutes(2);
    private readonly SemaphoreSlim _syncLock = new(1, 1);
    private CancellationTokenSource? _uploadDebounceCts;
    private Timer? _pullTimer;
    private bool _applyingRemote;
    private bool _disposed;

    public void Start()
    {
        ChatArchiveStore.Changed += ChatArchiveStore_Changed;
        _pullTimer = new Timer(_ => _ = PullThenPushAsync(), null, TimeSpan.FromSeconds(3), _pollInterval);
    }

    public void Stop()
    {
        if (_disposed) return;
        _disposed = true;
        ChatArchiveStore.Changed -= ChatArchiveStore_Changed;
        _uploadDebounceCts?.Cancel();
        _uploadDebounceCts?.Dispose();
        _pullTimer?.Dispose();
        _syncLock.Dispose();
    }

    public void Dispose() => Stop();

    private void ChatArchiveStore_Changed()
    {
        if (_applyingRemote || _disposed)
        {
            return;
        }

        _uploadDebounceCts?.Cancel();
        _uploadDebounceCts?.Dispose();
        var cts = new CancellationTokenSource();
        _uploadDebounceCts = cts;
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(_uploadDebounce, cts.Token);
                await PushAsync(cts.Token);
            }
            catch (OperationCanceledException)
            {
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"[ChatArchiveSync] upload failed: {ex.Message}");
            }
        });
    }

    private async Task PullThenPushAsync()
    {
        try
        {
            await PullAsync(CancellationToken.None);
            await PushAsync(CancellationToken.None);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[ChatArchiveSync] sync failed: {ex.Message}");
        }
    }

    private async Task<int> PullAsync(CancellationToken cancellationToken)
    {
        if (!await _syncLock.WaitAsync(0, cancellationToken))
        {
            return 0;
        }

        try
        {
            var result = await GatewayService.LoadHubConversationsAsync(AppSettingsStore.Load());
            if (result.Items.Count == 0)
            {
                return 0;
            }

            _applyingRemote = true;
            try
            {
                return ChatArchiveStore.Merge(result.Items);
            }
            finally
            {
                _applyingRemote = false;
            }
        }
        finally
        {
            _syncLock.Release();
        }
    }

    private async Task PushAsync(CancellationToken cancellationToken)
    {
        if (!await _syncLock.WaitAsync(0, cancellationToken))
        {
            return;
        }

        try
        {
            await GatewayService.SaveHubConversationsAsync(AppSettingsStore.Load(), ChatArchiveStore.Load());
        }
        finally
        {
            _syncLock.Release();
        }
    }
}
