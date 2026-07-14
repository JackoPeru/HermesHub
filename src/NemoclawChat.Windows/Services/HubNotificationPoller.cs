using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Windows.Data.Xml.Dom;
using Windows.Storage;
using Windows.UI.Notifications;

namespace NemoclawChat_Windows.Services;

public sealed class HubNotificationPoller
{
    private readonly DispatcherTimer _timer;
    private bool _running;

    public HubNotificationPoller(DispatcherQueue dispatcherQueue)
    {
        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(75) };
        _timer.Tick += async (_, _) => await PollAsync();
    }

    public void Start()
    {
        _timer.Start();
        _ = PollAsync();
    }

    public void Stop()
    {
        _timer.Stop();
    }

    private async Task PollAsync()
    {
        if (_running) return;
        _running = true;
        try
        {
            var settings = AppSettingsStore.Load();
            var result = await GatewayService.LoadHubNotificationsAsync(settings, unreadOnly: true);
            var seen = LoadSeen();
            foreach (var item in result.Items.OrderBy(item => item.CreatedAt))
            {
                if (!seen.Add(item.Id)) continue;
                ShowToast(item);
            }
            SaveSeen(seen);
        }
        catch (Exception ex)
        {
            // Polling must never crash the app.
            System.Diagnostics.Trace.WriteLine($"[HubNotificationPoller] PollAsync error: {ex}");
        }
        finally
        {
            _running = false;
        }
    }

    private static HashSet<string> LoadSeen()
    {
        var raw = ApplicationData.Current.LocalSettings.Values["SeenHubNotifications"] as string ?? "";
        return raw.Split('|', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).TakeLast(300).ToHashSet(StringComparer.Ordinal);
    }

    private static void SaveSeen(HashSet<string> seen)
    {
        ApplicationData.Current.LocalSettings.Values["SeenHubNotifications"] = string.Join('|', seen.TakeLast(300));
    }

    private static void ShowToast(HubNotificationRecord item)
    {
        var xml = ToastNotificationManager.GetTemplateContent(ToastTemplateType.ToastText02);
        var textNodes = xml.GetElementsByTagName("text");
        textNodes[0].AppendChild(xml.CreateTextNode(item.Title));
        textNodes[1].AppendChild(xml.CreateTextNode(item.Message.Length > 220 ? item.Message[..220] + "..." : item.Message));
        ToastNotificationManager.CreateToastNotifier().Show(new ToastNotification(xml));
    }
}
