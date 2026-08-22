using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using NemoclawChat_Windows.Services;
using System.Collections.ObjectModel;

namespace NemoclawChat_Windows.Pages;

public sealed record BotChatNavigation(
    string Profile,
    string SessionId,
    string DisplayName,
    string LocalConversationId,
    bool MultiplexEnabled,
    string ConnectionId = "primary",
    string Endpoint = "");

public static class BotNavigationContext
{
    private static BotChatNavigation? _pending;

    public static void SetPending(BotChatNavigation navigation) => _pending = navigation;

    public static BotChatNavigation? TakePending()
    {
        var value = _pending;
        _pending = null;
        return value;
    }
}

public sealed partial class BotsPage : Page
{
    private CancellationTokenSource? _activeGroupTurnCancellation;

    public ObservableCollection<HermesBotRecord> Bots { get; } = [];
    public ObservableCollection<HermesBotConnection> Connections { get; } = [];
    public ObservableCollection<HermesBotGroup> Groups { get; } = [];

    public BotsPage()
    {
        InitializeComponent();
        Loaded += BotsPage_Loaded;
    }

    private async void BotsPage_Loaded(object sender, RoutedEventArgs e)
    {
        Loaded -= BotsPage_Loaded;
        await RefreshAsync();
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await RefreshAsync();

    private async void Create_Click(object sender, RoutedEventArgs e)
    {
        await EditBotAsync(null);
    }

    private async Task RefreshAsync()
    {
        StatusText.Text = "Leggo il roster reale Hermes...";
        var settings = AppSettingsStore.Load();
        var connections = HermesBotConnectionStore.Load(settings);
        Connections.Clear();
        foreach (var connection in connections) Connections.Add(connection);
        ConnectionsList.ItemsSource = Connections;
        var result = await HermesBotConnectionService.LoadAllRostersAsync(settings);
        Bots.Clear();
        foreach (var bot in result.Items)
        {
            Bots.Add(bot);
        }
        BotsList.ItemsSource = Bots;
        Groups.Clear();
        foreach (var group in HermesBotGroupStore.Load()) Groups.Add(group);
        GroupsList.ItemsSource = Groups;
        StatusText.Text = result.Status;
        if (HermesBotConnectionStore.LastLoadWarnings.Count > 0)
            StatusText.Text += " " + string.Join(" ", HermesBotConnectionStore.LastLoadWarnings);
    }

    private async void Open_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: HermesBotRecord bot }) return;
        StatusText.Text = $"Apro Bot Chat di {bot.DisplayName}...";
        try
        {
            var context = await HermesBotConnectionService.OpenBotChatAsync(AppSettingsStore.Load(), bot);
            var chat = context.Chat;
            BotNavigationContext.SetPending(new BotChatNavigation(
                chat.Profile,
                chat.SessionId,
                bot.DisplayName,
                context.LocalConversationId,
                chat.MultiplexEnabled,
                context.Connection.Id,
                context.Connection.Endpoint));
            Frame.Navigate(typeof(HomePage), new HomeNavigationRequest());
        }
        catch (Exception ex)
        {
            StatusText.Text = ex.Message;
        }
    }

    private async void Edit_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: HermesBotRecord bot })
        {
            await EditBotAsync(bot);
        }
    }

    private async void Delete_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: HermesBotRecord bot }) return;
        if (bot.IsDefault || string.Equals(bot.Profile, "default", StringComparison.OrdinalIgnoreCase))
        {
            StatusText.Text = "Il profilo predefinito non può essere eliminato.";
            return;
        }

        var confirmation = new TextBox
        {
            Header = $"Scrivi esattamente {bot.Profile} per confermare",
            PlaceholderText = bot.Profile,
            MaxLength = 64
        };
        var content = new StackPanel { Spacing = 10 };
        content.Children.Add(new TextBlock
        {
            Text = $"L'eliminazione di {bot.Profile} rimuove il profilo Hermes e i suoi dati dal server. L'azione non è reversibile.",
            TextWrapping = TextWrapping.Wrap
        });
        content.Children.Add(confirmation);
        var dialog = new ContentDialog
        {
            Title = $"Elimina {bot.DisplayName}",
            Content = content,
            PrimaryButtonText = "Elimina",
            SecondaryButtonText = "Annulla",
            DefaultButton = ContentDialogButton.Secondary,
            XamlRoot = XamlRoot
        };
        dialog.PrimaryButtonClick += (_, args) =>
        {
            if (!string.Equals(confirmation.Text.Trim(), bot.Profile, StringComparison.Ordinal))
            {
                args.Cancel = true;
                StatusText.Text = "Per eliminare devi scrivere esattamente il nome del bot.";
            }
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;

        try
        {
            StatusText.Text = $"Elimino {bot.Profile}...";
            await HermesBotConnectionService.DeleteBotAsync(AppSettingsStore.Load(), bot, confirmation.Text);
            await RefreshAsync();
        }
        catch (Exception ex)
        {
            StatusText.Text = ex.Message;
        }
    }

    private void DeleteButton_Loaded(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: HermesBotRecord bot } button)
        {
            button.IsEnabled = !bot.IsDefault && !string.Equals(bot.Profile, "default", StringComparison.OrdinalIgnoreCase);
        }
    }

    private async Task EditBotAsync(HermesBotRecord? existing)
    {
        var settings = AppSettingsStore.Load();
        var connections = HermesBotConnectionStore.Load(settings).Where(item => item.Enabled && !string.IsNullOrWhiteSpace(item.Endpoint)).ToList();
        if (connections.Count == 0)
        {
            StatusText.Text = "Configura almeno una connessione Hermes con endpoint esplicito.";
            return;
        }
        var connectionPicker = new ComboBox
        {
            Header = "Connessione Hermes",
            ItemsSource = connections,
            DisplayMemberPath = "Label",
            SelectedValuePath = "Id",
            SelectedValue = existing?.ConnectionId ?? connections[0].Id
        };
        var profile = new TextBox
        {
            Header = "Nome profilo",
            Text = existing?.Profile ?? string.Empty,
            PlaceholderText = "es. assistente",
            MaxLength = 64,
            IsEnabled = existing is null
        };
        var displayName = new TextBox
        {
            Header = "Nome visualizzato",
            Text = existing?.DisplayName ?? string.Empty,
            MaxLength = 200
        };
        var description = new TextBox
        {
            Header = "Descrizione",
            Text = existing?.Description ?? string.Empty,
            MaxLength = 2_000,
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap
        };
        var soul = new TextBox
        {
            Header = "SOUL.md (opzionale)",
            PlaceholderText = existing is null ? "Istruzioni iniziali del bot" : "Lascia vuoto per non cambiare SOUL.md",
            MaxLength = 100_000,
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            MinHeight = 120
        };
        var content = new StackPanel { Spacing = 10 };
        content.Children.Add(connectionPicker);
        content.Children.Add(profile);
        content.Children.Add(displayName);
        content.Children.Add(description);
        content.Children.Add(soul);
        var dialog = new ContentDialog
        {
            Title = existing is null ? "Nuovo bot Hermes" : $"Modifica {existing.DisplayName}",
            Content = content,
            PrimaryButtonText = "Salva",
            SecondaryButtonText = "Annulla",
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = XamlRoot
        };
        dialog.PrimaryButtonClick += (_, args) =>
        {
            if (string.IsNullOrWhiteSpace(profile.Text))
            {
                args.Cancel = true;
                StatusText.Text = "Inserisci un nome profilo.";
            }
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;

        try
        {
            StatusText.Text = existing is null ? "Creo il bot..." : "Salvo le modifiche del bot...";
            if (existing is null)
            {
                await HermesBotConnectionService.CreateBotAsync(
                    settings,
                    connectionPicker.SelectedValue?.ToString() ?? connections[0].Id,
                    profile.Text,
                    displayName.Text,
                    description.Text,
                    soul.Text);
            }
            else
            {
                await HermesBotConnectionService.UpdateBotAsync(
                    settings,
                    existing,
                    description.Text,
                    displayName.Text,
                    string.IsNullOrWhiteSpace(soul.Text) ? null : soul.Text);
            }
            await RefreshAsync();
        }
        catch (Exception ex)
        {
            StatusText.Text = ex.Message;
        }
    }

    private async void AddConnection_Click(object sender, RoutedEventArgs e)
    {
        var label = new TextBox { Header = "Nome dispositivo", MaxLength = 120, PlaceholderText = "es. Server studio" };
        var endpoint = new TextBox { Header = "Endpoint Hermes esplicito", MaxLength = 500, PlaceholderText = "https://server-tailnet:8080" };
        var token = new PasswordBox { Header = "Token API (PasswordVault)", MaxLength = 2_000 };
        var content = new StackPanel { Spacing = 10 };
        content.Children.Add(label);
        content.Children.Add(endpoint);
        content.Children.Add(token);
        var dialog = new ContentDialog
        {
            Title = "Nuova connessione Hermes",
            Content = content,
            PrimaryButtonText = "Salva",
            SecondaryButtonText = "Annulla",
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = XamlRoot
        };
        dialog.PrimaryButtonClick += (_, args) =>
        {
            if (string.IsNullOrWhiteSpace(label.Text) || string.IsNullOrWhiteSpace(endpoint.Text))
            {
                args.Cancel = true;
                StatusText.Text = "Nome ed endpoint sono obbligatori.";
            }
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        HermesBotConnection? created = null;
        try
        {
            created = HermesBotConnectionStore.Add(label.Text, endpoint.Text, AppSettingsStore.Load());
            if (!GatewayCredentialStore.SaveConnectionSecret(created.Id, token.Password))
            {
                HermesBotConnectionStore.Delete(created.Id);
                throw new InvalidOperationException("Token non salvato nel PasswordVault.");
            }
            StatusText.Text = "Connessione Hermes salvata nel registry locale.";
            await RefreshAsync();
        }
        catch (Exception ex)
        {
            if (created is not null)
            {
                try { HermesBotConnectionStore.Delete(created.Id); } catch { }
            }
            StatusText.Text = ex.Message;
        }
    }

    private async void DeleteConnection_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: HermesBotConnection connection } || connection.IsPrimary) return;
        var dialog = new ContentDialog
        {
            Title = $"Rimuovi {connection.Label}?",
            Content = "Rimuovo endpoint e token dal dispositivo Windows. I bot sul server non vengono cancellati.",
            PrimaryButtonText = "Rimuovi",
            SecondaryButtonText = "Annulla",
            DefaultButton = ContentDialogButton.Secondary,
            XamlRoot = XamlRoot
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        HermesBotConnectionStore.Delete(connection.Id);
        await RefreshAsync();
    }

    private async void CreateGroup_Click(object sender, RoutedEventArgs e)
    {
        var selected = GroupMembersList.SelectedItems.OfType<HermesBotRecord>().ToList();
        if (selected.Count is < 2 or > 6)
        {
            StatusText.Text = "Un gruppo Hermes deve contenere da 2 a 6 bot.";
            return;
        }
        try
        {
            HermesBotGroupStore.Upsert(new HermesBotGroup
            {
                Name = GroupNameInput.Text,
                Members = selected.Select(bot => new HermesBotGroupMember(
                    bot.ConnectionId,
                    bot.Profile,
                    bot.DisplayName,
                    bot.Handle,
                    bot.IdentityKey)).ToList()
            });
            StatusText.Text = "Gruppo Hermes salvato.";
            GroupNameInput.Text = string.Empty;
            GroupMembersList.SelectedItems.Clear();
            await RefreshAsync();
        }
        catch (Exception ex)
        {
            StatusText.Text = ex.Message;
        }
    }

    private async void RunSelectedGroup_Click(object sender, RoutedEventArgs e)
    {
        if (GroupsList.SelectedItem is HermesBotGroup group)
        {
            await RunGroupAsync(group);
            return;
        }
        StatusText.Text = "Seleziona un gruppo Hermes da eseguire.";
    }

    private async void RunGroup_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: HermesBotGroup group }) await RunGroupAsync(group);
    }

    private async Task RunGroupAsync(HermesBotGroup group)
    {
        var prompt = new TextBox
        {
            Header = "Messaggio per il gruppo",
            PlaceholderText = "Scrivi la richiesta dell'utente",
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            MinHeight = 110,
            MaxLength = 20_000
        };
        var dialog = new ContentDialog
        {
            Title = $"Turno · {group.Name}",
            Content = prompt,
            PrimaryButtonText = "Esegui",
            SecondaryButtonText = "Annulla",
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = XamlRoot
        };
        dialog.PrimaryButtonClick += (_, args) =>
        {
            if (string.IsNullOrWhiteSpace(prompt.Text))
            {
                args.Cancel = true;
                StatusText.Text = "Il messaggio del gruppo è obbligatorio.";
            }
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;

        try
        {
            StatusText.Text = $"Eseguo il turno bounded di {group.Name}...";
            var cancellation = new CancellationTokenSource();
            var previous = Interlocked.Exchange(ref _activeGroupTurnCancellation, cancellation);
            previous?.Cancel();
            CancelGroupTurnButton.IsEnabled = true;
            HermesBotGroupTurnResult result;
            try
            {
                result = await HermesBotConnectionService.RunGroupTurnAsync(AppSettingsStore.Load(), group, prompt.Text, cancellation.Token);
            }
            finally
            {
                if (ReferenceEquals(Interlocked.CompareExchange(ref _activeGroupTurnCancellation, null, cancellation), cancellation))
                    CancelGroupTurnButton.IsEnabled = false;
                cancellation.Dispose();
            }
            var sourceLabels = HermesBotConnectionStore.Load(AppSettingsStore.Load())
                .ToDictionary(connection => connection.Id, connection => connection.Label, StringComparer.OrdinalIgnoreCase);
            var visibleReplies = result.MemberReplies
                .Where(memberReply => !memberReply.Silent)
                .Select(memberReply =>
                {
                    var source = sourceLabels.TryGetValue(memberReply.Member.ConnectionId, out var label)
                        ? label
                        : "Connessione Hermes";
                    return $"@{memberReply.Member.Handle} · {memberReply.Member.DisplayName} · {source}: {memberReply.Reply}";
                })
                .ToList();
            var passCount = result.MemberReplies.Count(memberReply => memberReply.Silent);
            var conversation = visibleReplies.Count == 0
                ? "Nessuna risposta bot non silenziosa."
                : string.Join("\n\n", visibleReplies);
            var failures = result.MemberFailures.Count == 0
                ? "Nessun errore membro."
                : "Fallimenti: " + string.Join("; ", result.MemberFailures.Select(failure => $"{failure.Label}: {failure.Message}"));
            var body = $"Esito: {result.Outcome}\nRound: {result.Rounds} · messaggi bot: {result.BotMessages} · pass: {passCount}\n\n{conversation}\n\n{failures}";
            var resultDialog = new ContentDialog
            {
                Title = $"Risultato · {group.Name}",
                Content = new ScrollViewer { Content = new TextBlock { Text = body, TextWrapping = TextWrapping.Wrap } },
                CloseButtonText = "Chiudi",
                XamlRoot = XamlRoot
            };
            await resultDialog.ShowAsync();
            StatusText.Text = $"Turno {group.Name}: {result.Outcome}.";
        }
        catch (OperationCanceledException)
        {
            StatusText.Text = "Turno gruppo annullato o sostituito da un nuovo turno.";
        }
        catch (Exception ex)
        {
            StatusText.Text = ex.Message;
        }
    }

    private void CancelGroupTurn_Click(object sender, RoutedEventArgs e) => _activeGroupTurnCancellation?.Cancel();

    private async void DeleteGroup_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: HermesBotGroup group }) return;
        HermesBotGroupStore.Delete(group.Name);
        await RefreshAsync();
    }

}
