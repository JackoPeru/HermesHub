using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using NemoclawChat_Windows.Services;
using System.Text.Json;

namespace NemoclawChat_Windows.Pages;

/// <summary>
/// Renderer Generative UI v1 (partial di HomePage). Stato locale per istanza:
/// slider/filtri/step non chiamano il modello; azioni "agent" viaggiano come
/// messaggi [genui ...] via SendBlockReplyAsync. WinUI non e' reattivo: dopo
/// ogni interazione locale il contenuto viene ricostruito (throttle per slider).
/// </summary>
public sealed partial class HomePage : Page
{
    private sealed class GenUiSession
    {
        public required string UiId;
        public required string SpecRaw;
        public Dictionary<string, GenUiValue> State = new(StringComparer.Ordinal);
        public Dictionary<string, GenUiValue> Initial = new(StringComparer.Ordinal);
        public readonly Dictionary<string, object?> Locals = new(StringComparer.Ordinal);
        public long LastTapMs;
        public long LastRebuildMs;

        public void Refresh(Dictionary<string, GenUiValue> live)
        {
            State = live;
        }
    }

    private static readonly Windows.UI.Color[] GenUiPalette =
    [
        ColorHelper.FromArgb(0xFF, 0x6E, 0xA8, 0xFE), ColorHelper.FromArgb(0xFF, 0xFB, 0xBF, 0x24),
        ColorHelper.FromArgb(0xFF, 0xF4, 0x72, 0xB6), ColorHelper.FromArgb(0xFF, 0xA3, 0xE6, 0x35),
        ColorHelper.FromArgb(0xFF, 0xC0, 0x84, 0xFC), ColorHelper.FromArgb(0xFF, 0x5E, 0xEA, 0xD4),
        ColorHelper.FromArgb(0xFF, 0xFC, 0xA5, 0xA5), ColorHelper.FromArgb(0xFF, 0x93, 0xC5, 0xFD),
    ];

    private static Brush MutedBrush => (Brush)Application.Current.Resources["MutedTextBrush"];

    private StackPanel RenderGenUi(VisualBlockRecord block)
    {
        var panel = new StackPanel { Spacing = 8 };
        var neverMode = string.Equals(AppSettingsStore.Load().VisualBlocksMode, "never", StringComparison.OrdinalIgnoreCase);
        if (neverMode || !GenUiValidator.IsValidBlock(block) || block.Spec is not { ValueKind: JsonValueKind.Object })
        {
            panel.Children.Add(new TextBlock
            {
                Text = string.IsNullOrWhiteSpace(block.FallbackText)
                    ? "Interfaccia non disponibile in questa versione dell'app."
                    : block.FallbackText,
                Foreground = new SolidColorBrush(Colors.White),
                TextWrapping = TextWrapping.WrapWholeWords,
            });
            return panel;
        }
        var specRaw = block.Spec.Value.GetRawText();
        var session = new GenUiSession
        {
            UiId = block.Id,
            SpecRaw = specRaw,
            Initial = GenUiValidator.InitialState(block.State),
        };
        session.State = GenUiStateStore.GetOrSeed(GenUiStateStore.SnapshotKey(block.Id, specRaw), session.Initial);
        var host = new StackPanel { Spacing = 8, Tag = new object[] { "genui-host", block, session } };
        RebuildGenUi(host, block, session);
        panel.Children.Add(host);
        return panel;
    }

    private void RebuildGenUi(StackPanel host, VisualBlockRecord block, GenUiSession session)
    {
        host.Children.Clear();
        if (block.Spec is { ValueKind: JsonValueKind.Object } spec)
            host.Children.Add(RenderGenUiNode(spec, block, session, 0));
    }

    private static StackPanel? FindGenUiHost(DependencyObject element)
    {
        while (element is not null)
        {
            if (element is StackPanel panel && panel.Tag is object[] tag && tag.Length == 3 && tag[0] as string == "genui-host")
                return panel;
            element = VisualTreeHelper.GetParent(element);
        }
        return null;
    }

    private void RefreshGenUiFrom(DependencyObject element, VisualBlockRecord block, GenUiSession session)
    {
        var host = FindGenUiHost(element);
        if (host is not null && host.Tag is object[] tag && ReferenceEquals(tag[1], block) && ReferenceEquals(tag[2], session))
            RebuildGenUi(host, block, session);
    }

    private FrameworkElement RenderGenUiNode(JsonElement node, VisualBlockRecord block, GenUiSession session, int level)
    {
        if (level > 12) return new TextBlock { Text = "…" };
        var state = session.State;
        if (node.TryGetProperty("visible_if", out var visibleIf) && visibleIf.ValueKind == JsonValueKind.Object &&
            !GenUiEvents.EvalCondition(visibleIf, state))
            return new Border { Width = 0, Height = 0 };
        var enabled = true;
        if (node.TryGetProperty("enabled_if", out var enabledIf) && enabledIf.ValueKind == JsonValueKind.Object)
            enabled = GenUiEvents.EvalCondition(enabledIf, state);
        var kind = GetGenUiString(node, "kind") ?? "text";
        return kind switch
        {
            "column" => StackGenUi(children: ChildrenOf(node), vertical: true, block, session, level),
            "row" => RowGenUi(ChildrenOf(node), block, session, level),
            "grid" => GridGenUi(node, block, session, level),
            "card" => CardGenUi(node, block, session, level),
            "section" => SectionGenUi(node, block, session, level),
            "tabs" => TabsGenUi(node, block, session, level, enabled),
            "accordion" => AccordionGenUi(node, block, session, level),
            "text" => new TextBlock
            {
                Text = GenUiEvents.RenderText(GetGenUiString(node, "text") ?? string.Empty, state),
                Foreground = new SolidColorBrush(Colors.White),
                TextWrapping = TextWrapping.WrapWholeWords,
            },
            "markdown" => RenderMarkdown(GenUiEvents.RenderText(GetGenUiString(node, "text") ?? string.Empty, state)),
            "metric" => RenderMetric(SynthGenUiMetric(node, state)),
            "progress" => RenderProgress(SynthGenUiProgress(node, state)),
            "button" => ButtonGenUi(node, block, session, enabled),
            "slider" => SliderGenUi(node, block, session, enabled),
            "switch" => ToggleGenUi(node, block, session, enabled, true),
            "checkbox" => ToggleGenUi(node, block, session, enabled, false),
            "radio" => RadioGenUi(node, block, session, enabled),
            "select" => SelectGenUi(node, block, session, enabled),
            "input" or "textarea" => TextGenUi(node, block, session, enabled, kind == "textarea"),
            "number" => NumberGenUi(node, block, session, enabled),
            "date" => DateGenUi(node, block, session, enabled),
            "table" => TableGenUi(node, block.Id, state),
            "chart" => ChartGenUi(node, state),
            "steps" => StepsGenUi(node, block, session, enabled),
            "nodes" => NodesGenUi(node, block, session, enabled),
            "image" => RenderMediaFile(new VisualBlockRecord
            {
                Id = GetGenUiString(node, "id") ?? block.Id,
                Type = "media_file",
                MediaKind = "image",
                MediaUrl = GetGenUiString(node, "src"),
                Alt = GetGenUiString(node, "alt") ?? string.Empty,
            }),
            "code" => RenderCode(GetGenUiString(node, "language") ?? "plaintext",
                GetGenUiString(node, "code") ?? string.Empty, GetGenUiString(node, "filename")),
            "alert" or "callout" => RenderCallout(new VisualBlockRecord
            {
                Id = GetGenUiString(node, "id") ?? block.Id,
                Type = "callout",
                Variant = GetGenUiString(node, "variant") ?? "info",
                Text = GenUiEvents.RenderText(GetGenUiString(node, "text") ?? string.Empty, state),
            }),
            "divider" => new Border
            {
                Height = 1,
                Background = (Brush)Application.Current.Resources["BorderBrushSoft"],
                Margin = new Thickness(0, 4, 0, 4),
            },
            "spacer" => new Border { Height = GetGenUiDouble(node, "height", 8).Clamp(2, 64) },
            _ => new TextBlock { Text = "Nodo non supportato.", Foreground = MutedBrush, FontSize = 12 },
        };
    }

    private static List<JsonElement> ChildrenOf(JsonElement node)
    {
        var outList = new List<JsonElement>();
        if (node.TryGetProperty("children", out var children) && children.ValueKind == JsonValueKind.Array)
        {
            foreach (var child in children.EnumerateArray())
            {
                if (child.ValueKind == JsonValueKind.Object) outList.Add(child);
            }
        }
        return outList;
    }

    private StackPanel StackGenUi(List<JsonElement> children, bool vertical, VisualBlockRecord block, GenUiSession session, int level)
    {
        var panel = new StackPanel { Spacing = 8 };
        if (!vertical) panel.Orientation = Orientation.Horizontal;
        foreach (var child in children) panel.Children.Add(RenderGenUiNode(child, block, session, level + 1));
        return panel;
    }

    private Grid RowGenUi(List<JsonElement> children, VisualBlockRecord block, GenUiSession session, int level)
    {
        var grid = new Grid { ColumnSpacing = 8 };
        for (var i = 0; i < children.Count; i++)
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        for (var i = 0; i < children.Count; i++)
        {
            var child = RenderGenUiNode(children[i], block, session, level + 1);
            Grid.SetColumn(child, i);
            grid.Children.Add(child);
        }
        return grid;
    }

    private Grid GridGenUi(JsonElement node, VisualBlockRecord block, GenUiSession session, int level)
    {
        var cols = 2;
        if (node.TryGetProperty("columns", out var columns) && columns.ValueKind == JsonValueKind.Number)
            cols = Math.Clamp(columns.GetInt32(), 1, 6);
        var kids = ChildrenOf(node);
        var grid = new Grid { ColumnSpacing = 8, RowSpacing = 8 };
        for (var i = 0; i < cols; i++)
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var row = 0;
        for (var i = 0; i < kids.Count; i++)
        {
            if (i > 0 && i % cols == 0) row++;
            if (i % cols == 0) grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var child = RenderGenUiNode(kids[i], block, session, level + 1);
            Grid.SetRow(child, row);
            Grid.SetColumn(child, i % cols);
            grid.Children.Add(child);
        }
        return grid;
    }

    private Border CardGenUi(JsonElement node, VisualBlockRecord block, GenUiSession session, int level)
    {
        var inner = new StackPanel { Spacing = 8 };
        var title = GetGenUiString(node, "title");
        if (!string.IsNullOrWhiteSpace(title))
            inner.Children.Add(new TextBlock { Text = title, Foreground = new SolidColorBrush(Colors.White), FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, FontSize = 14 });
        foreach (var child in ChildrenOf(node)) inner.Children.Add(RenderGenUiNode(child, block, session, level + 1));
        return new Border
        {
            Padding = new Thickness(10),
            CornerRadius = new CornerRadius(10),
            Background = (Brush)Application.Current.Resources["ElevatedSurfaceBrush"],
            BorderBrush = (Brush)Application.Current.Resources["BorderBrushSoft"],
            BorderThickness = new Thickness(1),
            Child = inner,
        };
    }

    private StackPanel SectionGenUi(JsonElement node, VisualBlockRecord block, GenUiSession session, int level)
    {
        var panel = new StackPanel { Spacing = 6 };
        var title = GetGenUiString(node, "title");
        if (!string.IsNullOrWhiteSpace(title))
            panel.Children.Add(new TextBlock { Text = title.ToUpperInvariant(), Foreground = (Brush)Application.Current.Resources["AccentBrush"], FontSize = 12, FontWeight = Microsoft.UI.Text.FontWeights.Bold });
        foreach (var child in ChildrenOf(node)) panel.Children.Add(RenderGenUiNode(child, block, session, level + 1));
        return panel;
    }

    private FrameworkElement TabsGenUi(JsonElement node, VisualBlockRecord block, GenUiSession session, int level, bool enabled)
    {
        var tabs = new List<(string Title, JsonElement? Body)>();
        if (node.TryGetProperty("tabs", out var tabsElement) && tabsElement.ValueKind == JsonValueKind.Array)
        {
            foreach (var tab in tabsElement.EnumerateArray())
            {
                if (tab.ValueKind != JsonValueKind.Object) continue;
                var tabBody = tab.TryGetProperty("body", out var bodyElement) && bodyElement.ValueKind == JsonValueKind.Object
                    ? bodyElement : (JsonElement?)null;
                tabs.Add((GetGenUiString(tab, "title") ?? string.Empty, tabBody));
            }
        }
        if (tabs.Count == 0) return new TextBlock { Text = "Nessun tab.", Foreground = MutedBrush, FontSize = 12 };
        var bind = GetGenUiString(node, "bind") ?? string.Empty;
        var selected = LocalInt(session, node, "tabsel", 0);
        if (!string.IsNullOrEmpty(bind) && session.State.TryGetValue(bind, out var bound) && bound.IsNumber)
            selected = Math.Clamp((int)bound.Number, 0, tabs.Count - 1);
        var panel = new StackPanel { Spacing = 6 };
        var bar = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4 };
        for (var i = 0; i < tabs.Count; i++)
        {
            var index = i;
            var button = new Button { Content = tabs[i].Title, IsEnabled = enabled };
            if (index == selected) button.BorderBrush = (Brush)Application.Current.Resources["AccentBrush"];
            button.Click += (_, _) =>
            {
                if (!string.IsNullOrEmpty(bind)) session.State[bind] = new GenUiValue(true, index, null, false);
                session.Locals[LocalKey(node, "tabsel")] = index;
                RefreshGenUiFrom(button, block, session);
            };
            bar.Children.Add(button);
        }
        panel.Children.Add(bar);
        if (tabs[selected].Body is { } selectedBody)
            panel.Children.Add(RenderGenUiNode(selectedBody, block, session, level + 1));
        return panel;
    }

    private StackPanel AccordionGenUi(JsonElement node, VisualBlockRecord block, GenUiSession session, int level)
    {
        var panel = new StackPanel { Spacing = 6 };
        if (!node.TryGetProperty("items", out var items) || items.ValueKind != JsonValueKind.Array) return panel;
        foreach (var item in items.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object) continue;
            var expander = new Expander { Header = GetGenUiString(item, "title") ?? string.Empty, IsExpanded = false };
            var body = new StackPanel { Spacing = 6 };
            var text = GetGenUiString(item, "text");
            if (!string.IsNullOrWhiteSpace(text))
                body.Children.Add(new TextBlock { Text = text, Foreground = new SolidColorBrush(Colors.White), TextWrapping = TextWrapping.WrapWholeWords });
            if (item.TryGetProperty("body", out var bodyElement) && bodyElement.ValueKind == JsonValueKind.Object)
                body.Children.Add(RenderGenUiNode(bodyElement, block, session, level + 1));
            expander.Content = body;
            panel.Children.Add(expander);
        }
        return panel;
    }

    private static string LocalKey(JsonElement node, string suffix) =>
        $"{GetGenUiString(node, "id") ?? "n"}#{suffix}";

    private static int LocalInt(GenUiSession session, JsonElement node, string suffix, int fallback)
    {
        if (session.Locals.TryGetValue(LocalKey(node, suffix), out var value) && value is int i) return i;
        return fallback;
    }

    private StackPanel ButtonGenUi(JsonElement node, VisualBlockRecord block, GenUiSession session, bool enabled)
    {
        var panel = new StackPanel { Spacing = 4 };
        var button = new Button
        {
            Content = GetGenUiString(node, "label") ?? GetGenUiString(node, "title") ?? "Vai",
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        var hasAction = node.TryGetProperty("action", out var action) && action.ValueKind == JsonValueKind.Object;
        button.IsEnabled = enabled;
        button.Click += async (_, _) =>
        {
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            if (now - session.LastTapMs < 1500) return;
            session.LastTapMs = now;
            if (!hasAction) return;
            if (GetGenUiString(action, "kind") == "local")
            {
                ApplyGenUiLocal(session, action);
                RefreshGenUiFrom(button, block, session);
                return;
            }
            if (GetGenUiString(action, "kind") == "agent")
            {
                try
                {
                    var payload = action.TryGetProperty("payload", out var payloadElement) &&
                        payloadElement.ValueKind == JsonValueKind.Object
                        ? JsonDocument.Parse(payloadElement.GetRawText()).RootElement.Clone()
                        : JsonDocument.Parse("{}").RootElement.Clone();
                    using var stream = new System.IO.MemoryStream();
                    using (var writer = new System.Text.Json.Utf8JsonWriter(stream))
                    {
                        writer.WriteStartObject();
                        foreach (var property in payload.EnumerateObject())
                            property.WriteTo(writer);
                        if (action.TryGetProperty("state_keys", out var keys) && keys.ValueKind == JsonValueKind.Array)
                        {
                            foreach (var key in keys.EnumerateArray())
                            {
                                if (key.ValueKind != JsonValueKind.String) continue;
                                var name = key.GetString() ?? string.Empty;
                                if (!session.State.TryGetValue(name, out var value)) continue;
                                writer.WritePropertyName(name);
                                if (value.IsNumber) writer.WriteNumberValue(value.Number);
                                else if (value.Text is not null) writer.WriteStringValue(value.Text);
                                else writer.WriteBooleanValue(value.Bool);
                            }
                        }
                        writer.WriteEndObject();
                    }
                    stream.Position = 0;
                    var merged = JsonDocument.Parse(stream).RootElement.Clone();
                    var actionId = GetGenUiString(action, "action_id") ?? string.Empty;
                    await SendBlockReplyAsync(block.Id, GenUiEvents.BuildEvent(block.Id, actionId, merged));
                }
                catch { }
            }
        };
        panel.Children.Add(button);
        return panel;
    }

    private static void ApplyGenUiLocal(GenUiSession session, JsonElement action)
    {
        switch (GetGenUiString(action, "op"))
        {
            case "set":
            {
                var key = GetGenUiString(action, "key");
                if (string.IsNullOrEmpty(key)) return;
                if (action.TryGetProperty("value", out var value))
                    session.State[key] = GenUiValue.FromJson(value);
                break;
            }
            case "toggle":
            {
                var key = GetGenUiString(action, "key");
                if (string.IsNullOrEmpty(key)) return;
                var current = session.State.TryGetValue(key, out var existing) && existing.Bool;
                session.State[key] = new GenUiValue(false, double.NaN, null, !current);
                break;
            }
            case "step":
            {
                var key = GetGenUiString(action, "key");
                if (string.IsNullOrEmpty(key)) return;
                var delta = action.TryGetProperty("delta", out var deltaElement) && deltaElement.ValueKind == JsonValueKind.Number
                    ? deltaElement.GetDouble() : 1;
                var current = session.State.TryGetValue(key, out var existing) ? existing.AsNumber() : 0;
                if (!double.IsFinite(current)) current = 0;
                session.State[key] = new GenUiValue(true, current + delta, null, false);
                break;
            }
            case "goto":
            {
                var key = GetGenUiString(action, "key");
                if (string.IsNullOrEmpty(key)) return;
                if (action.TryGetProperty("value", out var value))
                    session.State[key] = GenUiValue.FromJson(value);
                break;
            }
            case "reset":
                GenUiStateStore.Reset(GenUiStateStore.SnapshotKey(session.UiId, session.SpecRaw), session.Initial);
                session.Refresh(GenUiStateStore.GetOrSeed(
                    GenUiStateStore.SnapshotKey(session.UiId, session.SpecRaw), session.Initial));
                break;
        }
    }

    private StackPanel SliderGenUi(JsonElement node, VisualBlockRecord block, GenUiSession session, bool enabled)
    {
        var panel = new StackPanel { Spacing = 2 };
        var bind = GetGenUiString(node, "bind") ?? string.Empty;
        var min = GetGenUiDouble(node, "min", 0);
        var max = GetGenUiDouble(node, "max", 1);
        var current = CurrentNumber(session, bind, GetGenUiDouble(node, "value", min)).Clamp(min, max);
        var header = new Grid { ColumnSpacing = 8 };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var label = new TextBlock { Text = GetGenUiString(node, "label") ?? string.Empty, Foreground = new SolidColorBrush(Colors.White), FontSize = 13 };
        Grid.SetColumn(label, 0);
        header.Children.Add(label);
        var valueText = new TextBlock { Foreground = MutedBrush, FontSize = 13 };
        Grid.SetColumn(valueText, 1);
        header.Children.Add(valueText);
        panel.Children.Add(header);
        var slider = new Slider
        {
            Minimum = min,
            Maximum = max,
            StepFrequency = GetGenUiDouble(node, "step", (max - min) / 100.0),
            Value = current,
            IsEnabled = enabled && !string.IsNullOrEmpty(bind),
        };
        void ShowValue(double value) => valueText.Text = FormatGenUiNumber(value);
        ShowValue(current);
        slider.ValueChanged += (_, args) =>
        {
            if (!string.IsNullOrEmpty(bind))
                session.State[bind] = new GenUiValue(true, args.NewValue, null, false);
            ShowValue(args.NewValue);
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            if (now - session.LastRebuildMs < 120) return;
            session.LastRebuildMs = now;
            RefreshGenUiFrom(slider, block, session);
        };
        panel.Children.Add(slider);
        return panel;
    }

    private Control ToggleGenUi(JsonElement node, VisualBlockRecord block, GenUiSession session, bool enabled, bool isSwitch)
    {
        var bind = GetGenUiString(node, "bind") ?? string.Empty;
        var checkedValue = session.State.TryGetValue(bind, out var existing) ? existing.Bool
            : node.TryGetProperty("value", out var valueElement) && valueElement.ValueKind == JsonValueKind.True;
        var label = GetGenUiString(node, "label") ?? string.Empty;
        Control control;
        if (isSwitch)
        {
            var toggle = new ToggleSwitch { IsOn = checkedValue, IsEnabled = enabled, OffContent = label, OnContent = label };
            toggle.Toggled += (_, _) =>
            {
                if (!string.IsNullOrEmpty(bind))
                    session.State[bind] = new GenUiValue(false, double.NaN, null, toggle.IsOn);
                RefreshGenUiFrom(toggle, block, session);
            };
            control = toggle;
        }
        else
        {
            var box = new CheckBox { Content = label, IsChecked = checkedValue, IsEnabled = enabled };
            box.Checked += (_, _) => { if (!string.IsNullOrEmpty(bind)) session.State[bind] = new GenUiValue(false, double.NaN, null, true); RefreshGenUiFrom(box, block, session); };
            box.Unchecked += (_, _) => { if (!string.IsNullOrEmpty(bind)) session.State[bind] = new GenUiValue(false, double.NaN, null, false); RefreshGenUiFrom(box, block, session); };
            control = box;
        }
        return control;
    }

    private StackPanel RadioGenUi(JsonElement node, VisualBlockRecord block, GenUiSession session, bool enabled)
    {
        var panel = new StackPanel { Spacing = 4 };
        var bind = GetGenUiString(node, "bind") ?? string.Empty;
        var current = session.State.TryGetValue(bind, out var existing) ? existing.AsText() : GetGenUiString(node, "value") ?? string.Empty;
        var label = GetGenUiString(node, "label");
        if (!string.IsNullOrWhiteSpace(label))
            panel.Children.Add(new TextBlock { Text = label, Foreground = new SolidColorBrush(Colors.White), FontSize = 13 });
        var group = $"genui-{block.Id}-{GetGenUiString(node, "id")}";
        foreach (var (value, text) in GenUiOptions(node))
        {
            var radio = new RadioButton { Content = text, GroupName = group, IsChecked = current == value, IsEnabled = enabled };
            radio.Checked += (_, _) =>
            {
                if (!string.IsNullOrEmpty(bind)) session.State[bind] = new GenUiValue(false, double.NaN, value, false);
                RefreshGenUiFrom(radio, block, session);
            };
            panel.Children.Add(radio);
        }
        return panel;
    }

    private StackPanel SelectGenUi(JsonElement node, VisualBlockRecord block, GenUiSession session, bool enabled)
    {
        var panel = new StackPanel { Spacing = 4 };
        var bind = GetGenUiString(node, "bind") ?? string.Empty;
        var current = session.State.TryGetValue(bind, out var existing) ? existing.AsText() : GetGenUiString(node, "value") ?? string.Empty;
        var label = GetGenUiString(node, "label");
        if (!string.IsNullOrWhiteSpace(label))
            panel.Children.Add(new TextBlock { Text = label, Foreground = new SolidColorBrush(Colors.White), FontSize = 13 });
        var combo = new ComboBox { HorizontalAlignment = HorizontalAlignment.Stretch, IsEnabled = enabled, PlaceholderText = "Scegli…" };
        var pairs = GenUiOptions(node);
        foreach (var (_, text) in pairs) combo.Items.Add(text);
        var selectedIndex = pairs.FindIndex(pair => pair.Value == current);
        combo.SelectedIndex = selectedIndex;
        combo.SelectionChanged += (_, _) =>
        {
            if (combo.SelectedIndex >= 0 && !string.IsNullOrEmpty(bind))
                session.State[bind] = new GenUiValue(false, double.NaN, pairs[combo.SelectedIndex].Value, false);
            RefreshGenUiFrom(combo, block, session);
        };
        panel.Children.Add(combo);
        return panel;
    }

    private static List<(string Value, string Label)> GenUiOptions(JsonElement node)
    {
        var outList = new List<(string, string)>();
        if (!node.TryGetProperty("options", out var options) || options.ValueKind != JsonValueKind.Array) return outList;
        foreach (var opt in options.EnumerateArray())
        {
            if (opt.ValueKind == JsonValueKind.Object)
            {
                var value = GetGenUiString(opt, "value") ?? GetGenUiString(opt, "label") ?? string.Empty;
                outList.Add((value, GetGenUiString(opt, "label") ?? value));
            }
            else if (opt.ValueKind == JsonValueKind.String)
            {
                outList.Add((opt.GetString() ?? string.Empty, opt.GetString() ?? string.Empty));
            }
        }
        return outList;
    }

    private StackPanel TextGenUi(JsonElement node, VisualBlockRecord block, GenUiSession session, bool enabled, bool multiline)
    {
        var panel = new StackPanel { Spacing = 4 };
        var bind = GetGenUiString(node, "bind") ?? string.Empty;
        var current = session.State.TryGetValue(bind, out var existing) ? existing.AsText() : GetGenUiString(node, "value") ?? string.Empty;
        var label = GetGenUiString(node, "label");
        if (!string.IsNullOrWhiteSpace(label))
            panel.Children.Add(new TextBlock { Text = label, Foreground = new SolidColorBrush(Colors.White), FontSize = 13 });
        var box = new TextBox
        {
            Text = current,
            IsEnabled = enabled,
            PlaceholderText = GetGenUiString(node, "placeholder") ?? string.Empty,
            AcceptsReturn = multiline,
            TextWrapping = multiline ? TextWrapping.Wrap : TextWrapping.NoWrap,
        };
        box.TextChanged += (_, _) =>
        {
            if (!string.IsNullOrEmpty(bind))
                session.State[bind] = new GenUiValue(false, double.NaN, box.Text, false);
        };
        box.LostFocus += (_, _) => RefreshGenUiFrom(box, block, session);
        panel.Children.Add(box);
        return panel;
    }

    private StackPanel NumberGenUi(JsonElement node, VisualBlockRecord block, GenUiSession session, bool enabled)
    {
        var panel = new StackPanel { Spacing = 4 };
        var bind = GetGenUiString(node, "bind") ?? string.Empty;
        var current = session.State.TryGetValue(bind, out var existing) ? existing.AsText() : GetGenUiString(node, "value") ?? string.Empty;
        var label = GetGenUiString(node, "label");
        if (!string.IsNullOrWhiteSpace(label))
            panel.Children.Add(new TextBlock { Text = label, Foreground = new SolidColorBrush(Colors.White), FontSize = 13 });
        var box = new TextBox
        {
            Text = current,
            IsEnabled = enabled,
            InputScope = new Microsoft.UI.Xaml.Input.InputScope
            {
                Names = { new Microsoft.UI.Xaml.Input.InputScopeName { NameValue = Microsoft.UI.Xaml.Input.InputScopeNameValue.Number } },
            },
        };
        box.TextChanged += (_, _) =>
        {
            if (!string.IsNullOrEmpty(bind))
                session.State[bind] = new GenUiValue(false, double.NaN, box.Text, false);
        };
        box.LostFocus += (_, _) => RefreshGenUiFrom(box, block, session);
        panel.Children.Add(box);
        return panel;
    }

    private StackPanel DateGenUi(JsonElement node, VisualBlockRecord block, GenUiSession session, bool enabled)
    {
        var panel = new StackPanel { Spacing = 4 };
        var bind = GetGenUiString(node, "bind") ?? string.Empty;
        var current = session.State.TryGetValue(bind, out var existing) ? existing.AsText() : GetGenUiString(node, "value") ?? string.Empty;
        var label = GetGenUiString(node, "label");
        if (!string.IsNullOrWhiteSpace(label))
            panel.Children.Add(new TextBlock { Text = label, Foreground = new SolidColorBrush(Colors.White), FontSize = 13 });
        var picker = new CalendarDatePicker { IsEnabled = enabled };
        if (DateTimeOffset.TryParse(current, out var parsed)) picker.Date = parsed;
        picker.DateChanged += (_, _) =>
        {
            if (picker.Date.HasValue && !string.IsNullOrEmpty(bind))
                session.State[bind] = new GenUiValue(false, double.NaN, picker.Date.Value.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture), false);
            RefreshGenUiFrom(picker, block, session);
        };
        panel.Children.Add(picker);
        return panel;
    }

    private StackPanel StepsGenUi(JsonElement node, VisualBlockRecord block, GenUiSession session, bool enabled)
    {
        var panel = new StackPanel { Spacing = 6 };
        var items = new List<JsonElement>();
        if (node.TryGetProperty("items", out var itemsElement) && itemsElement.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in itemsElement.EnumerateArray())
            {
                if (item.ValueKind == JsonValueKind.Object) items.Add(item);
            }
        }
        if (items.Count == 0) return panel;
        var bind = GetGenUiString(node, "bind") ?? string.Empty;
        var index = LocalInt(session, node, "stepidx", 0);
        if (!string.IsNullOrEmpty(bind) && session.State.TryGetValue(bind, out var bound) && bound.IsNumber)
            index = Math.Clamp((int)bound.Number, 0, items.Count - 1);
        var currentItem = items[index];
        panel.Children.Add(new TextBlock { Text = GetGenUiString(currentItem, "title") ?? string.Empty, Foreground = new SolidColorBrush(Colors.White), FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, FontSize = 14 });
        var stepText = GetGenUiString(currentItem, "text");
        if (!string.IsNullOrWhiteSpace(stepText))
            panel.Children.Add(new TextBlock { Text = GenUiEvents.RenderText(stepText, session.State), Foreground = new SolidColorBrush(Colors.White), TextWrapping = TextWrapping.WrapWholeWords });
        var dots = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        for (var i = 0; i < items.Count; i++)
        {
            var dotIndex = i;
            var dot = new Button
            {
                Content = $"●",
                FontSize = 10,
                Padding = new Thickness(6, 2, 6, 2),
                IsEnabled = enabled,
                Opacity = dotIndex == index ? 1 : 0.45,
            };
            dot.Click += (_, _) =>
            {
                if (!string.IsNullOrEmpty(bind)) session.State[bind] = new GenUiValue(true, dotIndex, null, false);
                session.Locals[LocalKey(node, "stepidx")] = dotIndex;
                RefreshGenUiFrom(dot, block, session);
            };
            dots.Children.Add(dot);
        }
        panel.Children.Add(dots);
        panel.Children.Add(new TextBlock { Text = $"Passo {index + 1} di {items.Count}", Foreground = MutedBrush, FontSize = 12 });
        return panel;
    }

    private StackPanel NodesGenUi(JsonElement node, VisualBlockRecord block, GenUiSession session, bool enabled)
    {
        var panel = new StackPanel { Spacing = 6 };
        var entries = new List<JsonElement>();
        if (node.TryGetProperty("nodes", out var nodesElement) && nodesElement.ValueKind == JsonValueKind.Array)
        {
            foreach (var entry in nodesElement.EnumerateArray())
            {
                if (entry.ValueKind == JsonValueKind.Object) entries.Add(entry);
            }
        }
        if (entries.Count == 0) return panel;
        var bind = GetGenUiString(node, "bind") ?? string.Empty;
        var selected = LocalString(session, node, "nodesel", GetGenUiString(entries[0], "id") ?? string.Empty);
        if (!string.IsNullOrEmpty(bind) && session.State.TryGetValue(bind, out var bound))
        {
            var candidate = bound.AsText();
            if (entries.Any(entry => GetGenUiString(entry, "id") == candidate)) selected = candidate;
        }
        foreach (var entry in entries)
        {
            var id = GetGenUiString(entry, "id") ?? string.Empty;
            var button = new Button
            {
                Content = GetGenUiString(entry, "label") ?? id,
                HorizontalAlignment = HorizontalAlignment.Stretch,
                HorizontalContentAlignment = HorizontalAlignment.Left,
                IsEnabled = enabled,
                Opacity = id == selected ? 1 : 0.75,
            };
            button.Click += (_, _) =>
            {
                session.Locals[LocalKey(node, "nodesel")] = id;
                if (!string.IsNullOrEmpty(bind)) session.State[bind] = new GenUiValue(false, double.NaN, id, false);
                RefreshGenUiFrom(button, block, session);
            };
            panel.Children.Add(button);
        }
        var current = entries.FirstOrDefault(entry => GetGenUiString(entry, "id") == selected);
        if (current.ValueKind == JsonValueKind.Object)
        {
            var detail = GetGenUiString(current, "detail");
            if (!string.IsNullOrWhiteSpace(detail))
                panel.Children.Add(new TextBlock { Text = detail, Foreground = MutedBrush, TextWrapping = TextWrapping.WrapWholeWords });
        }
        if (node.TryGetProperty("edges", out var edges) && edges.ValueKind == JsonValueKind.Array)
        {
            var links = new List<string>();
            foreach (var edge in edges.EnumerateArray())
            {
                if (edge.ValueKind == JsonValueKind.Array && edge.GetArrayLength() == 2)
                    links.Add($"{edge[0].GetString()} → {edge[1].GetString()}");
            }
            if (links.Count > 0)
                panel.Children.Add(new TextBlock { Text = string.Join("   ", links), Foreground = MutedBrush, FontSize = 12, TextWrapping = TextWrapping.Wrap });
        }
        return panel;
    }

    private static string LocalString(GenUiSession session, JsonElement node, string suffix, string fallback)
    {
        if (session.Locals.TryGetValue(LocalKey(node, suffix), out var value) && value is string text) return text;
        return fallback;
    }

    private StackPanel TableGenUi(JsonElement node, string uiId, IReadOnlyDictionary<string, GenUiValue> state)
    {
        var panel = new StackPanel { Spacing = 6 };
        var title = GetGenUiString(node, "title");
        if (!string.IsNullOrWhiteSpace(title))
            panel.Children.Add(new TextBlock { Text = GenUiEvents.RenderText(title, state), Foreground = new SolidColorBrush(Colors.White), FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, FontSize = 14 });
        var nodeId = uiId + "#" + (GetGenUiString(node, "id") ?? "t");
        var columns = new List<(string Key, string Label)>();
        if (node.TryGetProperty("columns", out var columnsElement) && columnsElement.ValueKind == JsonValueKind.Array)
        {
            foreach (var column in columnsElement.EnumerateArray())
            {
                if (column.ValueKind == JsonValueKind.Object)
                    columns.Add((GetGenUiString(column, "key") ?? string.Empty, GetGenUiString(column, "label") ?? string.Empty));
            }
        }
        if (columns.Count == 0) return panel;
        var rows = new List<Dictionary<string, string>>();
        if (node.TryGetProperty("rows", out var rowsElement) && rowsElement.ValueKind == JsonValueKind.Array)
        {
            foreach (var row in rowsElement.EnumerateArray())
            {
                if (row.ValueKind != JsonValueKind.Object) continue;
                var dict = new Dictionary<string, string>(StringComparer.Ordinal);
                foreach (var property in row.EnumerateObject())
                    dict[property.Name] = GenUiEvents.RenderText(CellText(property.Value), state);
                rows.Add(dict);
            }
        }
        var sortKey = _genUiTableSort.TryGetValue(nodeId, out var sort) ? sort.Key : null;
        var sortAsc = _genUiTableSort.TryGetValue(nodeId, out var sort2) && sort2.Asc;
        var filter = node.TryGetProperty("filter", out var filterElement) && filterElement.ValueKind == JsonValueKind.True;
        var filterText = _genUiTableFilter.TryGetValue(nodeId, out var existing) ? existing : string.Empty;
        if (filter)
        {
            var filterBox = new TextBox { PlaceholderText = "Filtra…", Text = filterText };
            filterBox.TextChanged += (_, _) =>
            {
                _genUiTableFilter[nodeId] = filterBox.Text;
                RefreshGenUiTable(panel, node, nodeId, state, sortKey, sortAsc);
            };
            panel.Children.Add(filterBox);
        }
        var tableHost = new StackPanel { Spacing = 0 };
        panel.Children.Add(new ScrollViewer { HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, Content = tableHost });
        BuildGenUiTableRows(tableHost, node, nodeId, columns, rows, filterText, sortKey, sortAsc);
        panel.Children.Add(new TextBlock { Text = $"{rows.Count} righe", Foreground = MutedBrush, FontSize = 12 });
        return panel;
    }

    private readonly Dictionary<string, (string Key, bool Asc)> _genUiTableSort = new(StringComparer.Ordinal);
    private readonly Dictionary<string, string> _genUiTableFilter = new(StringComparer.Ordinal);

    private void BuildGenUiTableRows(StackPanel host, JsonElement node, string uiId,
        List<(string Key, string Label)> columns, List<Dictionary<string, string>> rows,
        string filterText, string? sortKey, bool sortAsc)
    {
        host.Children.Clear();
        // uiId e' gia' qualificato (ui + nodo) dal chiamante: niente doppia qualifica.
        var nodeId = uiId;
        var view = rows;
        if (!string.IsNullOrWhiteSpace(filterText))
        {
            view = view.Where(row => row.Values.Any(cell =>
                cell.Contains(filterText, StringComparison.OrdinalIgnoreCase))).ToList();
        }
        if (!string.IsNullOrEmpty(sortKey))
        {
            view = view.OrderBy(row => row.TryGetValue(sortKey, out var cell) ? cell : string.Empty,
                Comparer<string>.Create((a, b) =>
                {
                    if (double.TryParse(a, System.Globalization.NumberStyles.Float,
                            System.Globalization.CultureInfo.InvariantCulture, out var an) &&
                        double.TryParse(b, System.Globalization.NumberStyles.Float,
                            System.Globalization.CultureInfo.InvariantCulture, out var bn))
                        return sortAsc ? an.CompareTo(bn) : bn.CompareTo(an);
                    return sortAsc ? string.Compare(a, b, StringComparison.OrdinalIgnoreCase)
                                   : string.Compare(b, a, StringComparison.OrdinalIgnoreCase);
                })).ToList();
        }
        var header = new Grid { ColumnSpacing = 1 };
        for (var i = 0; i < columns.Count; i++)
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        for (var i = 0; i < columns.Count; i++)
        {
            var column = columns[i];
            var headerButton = new Button
            {
                Content = column.Label + (sortKey == column.Key ? (sortAsc ? " ▲" : " ▼") : string.Empty),
                Padding = new Thickness(8, 6, 8, 6),
            };
            headerButton.Click += (_, _) =>
            {
                if (_genUiTableSort.TryGetValue(nodeId, out var current) && current.Key == column.Key)
                    _genUiTableSort[nodeId] = (column.Key, !current.Asc);
                else
                    _genUiTableSort[nodeId] = (column.Key, true);
                var updated = _genUiTableSort[nodeId];
                BuildGenUiTableRows(host, node, nodeId, columns, rows, filterText, updated.Key, updated.Asc);
            };
            Grid.SetColumn(headerButton, i);
            header.Children.Add(headerButton);
        }
        host.Children.Add(header);
        foreach (var row in view.Take(100))
        {
            var grid = new Grid { ColumnSpacing = 1, RowSpacing = 1 };
            for (var i = 0; i < columns.Count; i++)
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            for (var i = 0; i < columns.Count; i++)
            {
                row.TryGetValue(columns[i].Key, out var cell);
                var border = new Border
                {
                    Padding = new Thickness(8, 6, 8, 6),
                    Background = (Brush)Application.Current.Resources["ComposerBrush"],
                    Child = new TextBlock { Text = cell ?? string.Empty, Foreground = new SolidColorBrush(Colors.White) },
                };
                Grid.SetColumn(border, i);
                grid.Children.Add(border);
            }
            host.Children.Add(grid);
        }
    }

    private void RefreshGenUiTable(StackPanel panel, JsonElement node, string uiId,
        IReadOnlyDictionary<string, GenUiValue> state, string? sortKey, bool sortAsc)
    {
        // Ricostruzione mirata: il filtro non deve perdere il focus ad ogni tasto.
        if (panel.Children.Count < 2) return;
        // uiId e' gia' qualificato dal chiamante.
        var nodeId = uiId;
        _genUiTableFilter.TryGetValue(nodeId, out var filterText);
        var columns = new List<(string Key, string Label)>();
        if (node.TryGetProperty("columns", out var columnsElement) && columnsElement.ValueKind == JsonValueKind.Array)
        {
            foreach (var column in columnsElement.EnumerateArray())
            {
                if (column.ValueKind == JsonValueKind.Object)
                    columns.Add((GetGenUiString(column, "key") ?? string.Empty, GetGenUiString(column, "label") ?? string.Empty));
            }
        }
        var rows = new List<Dictionary<string, string>>();
        if (node.TryGetProperty("rows", out var rowsElement) && rowsElement.ValueKind == JsonValueKind.Array)
        {
            foreach (var row in rowsElement.EnumerateArray())
            {
                if (row.ValueKind != JsonValueKind.Object) continue;
                var dict = new Dictionary<string, string>(StringComparer.Ordinal);
                foreach (var property in row.EnumerateObject())
                    dict[property.Name] = GenUiEvents.RenderText(CellText(property.Value), state);
                rows.Add(dict);
            }
        }
        var host = new StackPanel { Spacing = 0 };
        BuildGenUiTableRows(host, node, nodeId, columns, rows, filterText ?? string.Empty, sortKey, sortAsc);
        var scroll = panel.Children.OfType<ScrollViewer>().FirstOrDefault();
        if (scroll is not null) scroll.Content = host;
    }

    private static string CellText(JsonElement element) => element.ValueKind switch
    {
        JsonValueKind.String => element.GetString() ?? string.Empty,
        JsonValueKind.Number => element.GetRawText(),
        JsonValueKind.True => "true",
        JsonValueKind.False => "false",
        _ => string.Empty,
    };

    private static StackPanel ChartGenUi(JsonElement node, IReadOnlyDictionary<string, GenUiValue> state)
    {
        var panel = new StackPanel { Spacing = 6 };
        var title = GetGenUiString(node, "title");
        if (!string.IsNullOrWhiteSpace(title))
            panel.Children.Add(new TextBlock { Text = GenUiEvents.RenderText(title, state), Foreground = new SolidColorBrush(Colors.White), FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, FontSize = 14 });
        var series = ResolveGenUiSeries(node, state);
        if (series.Count == 0)
        {
            panel.Children.Add(new TextBlock { Text = "Nessun dato.", Foreground = MutedBrush, FontSize = 13 });
            return panel;
        }
        var chartType = (GetGenUiString(node, "chart_type") ?? "line").ToLowerInvariant();
        var host = new Grid();
        host.SizeChanged += (_, _) => BuildGenUiChartShapes(host, node, series, chartType);
        host.Loaded += (_, _) => BuildGenUiChartShapes(host, node, series, chartType);
        panel.Children.Add(host);
        if (series.Count > 1 || !string.IsNullOrWhiteSpace(series[0].Name))
        {
            var legend = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
            for (var i = 0; i < series.Count; i++)
            {
                var item = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4 };
                item.Children.Add(new Border
                {
                    Width = 18, Height = 10, CornerRadius = new CornerRadius(2),
                    Background = new SolidColorBrush(GenUiPalette[i % GenUiPalette.Length]),
                    VerticalAlignment = VerticalAlignment.Center,
                });
                item.Children.Add(new TextBlock
                {
                    Text = string.IsNullOrWhiteSpace(series[i].Name) ? $"Serie {i + 1}" : series[i].Name,
                    Foreground = MutedBrush, FontSize = 12, VerticalAlignment = VerticalAlignment.Center,
                });
                legend.Children.Add(item);
            }
            panel.Children.Add(legend);
        }
        var axes = new List<string>();
        var xLabel = GetGenUiString(node, "x_label");
        var yLabel = GetGenUiString(node, "y_label");
        var unit = GetGenUiString(node, "unit");
        if (!string.IsNullOrWhiteSpace(xLabel)) axes.Add($"x: {xLabel}");
        if (!string.IsNullOrWhiteSpace(yLabel)) axes.Add($"y: {yLabel}");
        if (!string.IsNullOrWhiteSpace(unit)) axes.Add(unit);
        if (axes.Count > 0)
            panel.Children.Add(new TextBlock { Text = string.Join(" · ", axes), Foreground = MutedBrush, FontSize = 12 });
        return panel;
    }

    private sealed record GenUiPoint(string XText, double? XNum, double Y);
    private sealed record GenUiSeries(string Name, List<GenUiPoint> Points);

    private static List<GenUiSeries> ResolveGenUiSeries(JsonElement node, IReadOnlyDictionary<string, GenUiValue> state)
    {
        var outList = new List<GenUiSeries>();
        if (!node.TryGetProperty("series", out var seriesElement) || seriesElement.ValueKind != JsonValueKind.Array)
            return outList;
        foreach (var entry in seriesElement.EnumerateArray())
        {
            if (entry.ValueKind != JsonValueKind.Object) continue;
            var points = new List<GenUiPoint>();
            if (entry.TryGetProperty("points", out var pointsElement) && pointsElement.ValueKind == JsonValueKind.Array)
            {
                foreach (var point in pointsElement.EnumerateArray())
                {
                    if (point.ValueKind != JsonValueKind.Object) continue;
                    point.TryGetProperty("x", out var xElement);
                    double? xNum = xElement.ValueKind == JsonValueKind.Number ? xElement.GetDouble() : null;
                    var xText = xElement.ValueKind == JsonValueKind.Number
                        ? new GenUiValue(true, xElement.GetDouble(), null, false).AsText()
                        : xElement.ValueKind == JsonValueKind.String ? xElement.GetString() ?? string.Empty : string.Empty;
                    double y;
                    if (point.TryGetProperty("y_formula", out var yFormula) && yFormula.ValueKind == JsonValueKind.String && xNum.HasValue)
                    {
                        var extra = new Dictionary<string, GenUiValue>(StringComparer.Ordinal)
                        {
                            ["x"] = new GenUiValue(true, xNum.Value, null, false),
                        };
                        y = GenUiFormula.EvalNumber(yFormula.GetString() ?? string.Empty, state, extra);
                    }
                    else if (point.TryGetProperty("y", out var yElement) && yElement.ValueKind == JsonValueKind.Number)
                    {
                        y = yElement.GetDouble();
                    }
                    else
                    {
                        y = 0;
                    }
                    points.Add(new GenUiPoint(xText, xNum, double.IsFinite(y) ? y : 0));
                }
            }
            outList.Add(new GenUiSeries(GetGenUiString(entry, "name") ?? string.Empty, points));
        }
        return outList;
    }

    private static void BuildGenUiChartShapes(Grid host, JsonElement node, List<GenUiSeries> series, string chartType)
    {
        host.Children.Clear();
        var width = host.ActualWidth;
        if (width <= 0) width = 520;
        const double height = 240;
        const double left = 46, bottom = 26, top = 8, right = 8;
        var canvas = new Canvas { Width = width, Height = height };
        host.Children.Add(canvas);
        if (chartType == "pie")
        {
            BuildGenUiPie(canvas, series[0], width, height);
            return;
        }
        var numericX = series.All(s => s.Points.All(p => p.XNum.HasValue));
        var maxSlots = series.Max(s => s.Points.Count);
        if (maxSlots == 0) return;
        var allY = series.SelectMany(s => s.Points.Select(p => p.Y)).ToList();
        var lo = allY.Min();
        var hi = allY.Max();
        if (chartType == "bar") lo = Math.Min(lo, 0);
        if (hi - lo < 1e-9) hi = lo + 1;
        else
        {
            var pad = (hi - lo) * 0.08;
            hi += pad;
            if (chartType != "bar") lo -= pad;
        }
        var plotW = width - left - right;
        var plotH = height - top - bottom;
        double XPos(int s, int n)
        {
            if (numericX)
            {
                var xs = series.SelectMany(s2 => s2.Points.Select(p => p.XNum!.Value)).ToList();
                var xLo = xs.Min();
                var xHi = xs.Max();
                var span = xHi - xLo > 1e-9 ? xHi - xLo : 1;
                return left + (series[s].Points[n].XNum!.Value - xLo) / span * plotW;
            }
            return left + (n + 0.5) / maxSlots * plotW;
        }
        double YPos(double v) => top + (1 - (v - lo) / (hi - lo)) * plotH;
        var muted = new SolidColorBrush(Colors.Gray);
        for (var g = 0; g <= 4; g++)
        {
            var v = lo + (hi - lo) * g / 4.0;
            var y = YPos(v);
            canvas.Children.Add(new Line
            {
                X1 = left, Y1 = y, X2 = left + plotW, Y2 = y,
                Stroke = muted, StrokeThickness = 1, Opacity = 0.5,
            });
            var label = new TextBlock
            {
                Text = new GenUiValue(true, v, null, false).AsText(),
                Foreground = muted, FontSize = 10,
            };
            Canvas.SetLeft(label, 2);
            Canvas.SetTop(label, y - 8);
            canvas.Children.Add(label);
        }
        var labelIdx = Enumerable.Range(0, maxSlots).Where(n => maxSlots <= 6 || n % ((maxSlots + 5) / 6) == 0).ToList();
        foreach (var n in labelIdx)
        {
            if (n >= series[0].Points.Count) continue;
            var label = new TextBlock { Text = series[0].Points[n].XText, Foreground = muted, FontSize = 10 };
            Canvas.SetLeft(label, XPos(0, n) - 12);
            Canvas.SetTop(label, height - 18);
            canvas.Children.Add(label);
        }
        for (var s = 0; s < series.Count; s++)
        {
            var color = new SolidColorBrush(GenUiPalette[s % GenUiPalette.Length]);
            var serie = series[s];
            if (chartType == "bar")
            {
                var slotW = plotW / maxSlots;
                var barW = Math.Max(3, slotW / series.Count * 0.7);
                for (var n = 0; n < serie.Points.Count; n++)
                {
                    var cx = numericX ? XPos(s, n)
                        : left + (n + 0.5) / maxSlots * plotW + (s - (series.Count - 1) / 2.0) * barW;
                    var y0 = Math.Clamp(YPos(0), top, top + plotH);
                    var y1 = Math.Clamp(YPos(serie.Points[n].Y), top, top + plotH);
                    var bar = new Border
                    {
                        Width = barW, Height = Math.Max(2, Math.Abs(y0 - y1)),
                        CornerRadius = new CornerRadius(2), Background = color,
                    };
                    Canvas.SetLeft(bar, cx - barW / 2);
                    Canvas.SetTop(bar, Math.Min(y0, y1));
                    canvas.Children.Add(bar);
                }
            }
            else if (chartType == "scatter")
            {
                foreach (var (n, point) in serie.Points.Select((point, n) => (n, point)))
                {
                    var dot = new Ellipse { Width = 7, Height = 7, Fill = color };
                    Canvas.SetLeft(dot, XPos(s, n) - 3.5);
                    Canvas.SetTop(dot, YPos(point.Y) - 3.5);
                    canvas.Children.Add(dot);
                }
            }
            else
            {
                if (serie.Points.Count == 1)
                {
                    var dot = new Ellipse { Width = 8, Height = 8, Fill = color };
                    Canvas.SetLeft(dot, XPos(s, 0) - 4);
                    Canvas.SetTop(dot, YPos(serie.Points[0].Y) - 4);
                    canvas.Children.Add(dot);
                }
                else if (serie.Points.Count > 1)
                {
                    var polyline = new Polyline { Stroke = color, StrokeThickness = 2.5 };
                    foreach (var (n, point) in serie.Points.Select((point, n) => (n, point)))
                        polyline.Points.Add(new Windows.Foundation.Point(XPos(s, n), YPos(point.Y)));
                    canvas.Children.Add(polyline);
                    if (chartType == "area")
                    {
                        var polygon = new Polygon { Fill = color, Opacity = 0.25 };
                        foreach (var (n, point) in serie.Points.Select((point, n) => (n, point)))
                            polygon.Points.Add(new Windows.Foundation.Point(XPos(s, n), YPos(point.Y)));
                        var baseY = Math.Clamp(YPos(0), top, top + plotH);
                        polygon.Points.Add(new Windows.Foundation.Point(XPos(s, serie.Points.Count - 1), baseY));
                        polygon.Points.Add(new Windows.Foundation.Point(XPos(s, 0), baseY));
                        canvas.Children.Add(polygon);
                        canvas.Children.Remove(polyline);
                        canvas.Children.Add(polyline);
                    }
                    foreach (var (n, point) in serie.Points.Select((point, n) => (n, point)))
                    {
                        var dot = new Ellipse { Width = 6, Height = 6, Fill = color };
                        Canvas.SetLeft(dot, XPos(s, n) - 3);
                        Canvas.SetTop(dot, YPos(point.Y) - 3);
                        canvas.Children.Add(dot);
                    }
                }
            }
        }
    }

    private static void BuildGenUiPie(Canvas canvas, GenUiSeries serie, double width, double height)
    {
        var values = serie.Points.Select(point => Math.Max(0, point.Y)).ToList();
        var total = values.Sum();
        if (total <= 0) return;
        var radius = Math.Min(width, height) / 2 - 4;
        var centerX = width / 2;
        var centerY = height / 2;
        var start = -90.0;
        for (var i = 0; i < values.Count; i++)
        {
            var sweep = values[i] / total * 360.0;
            if (sweep <= 0) continue;
            var path = new PathFigure { StartPoint = new Windows.Foundation.Point(centerX, centerY), IsClosed = true };
            var endAngle = (start + sweep) * Math.PI / 180.0;
            var startAngle = start * Math.PI / 180.0;
            path.Segments.Add(new LineSegment { Point = new Windows.Foundation.Point(centerX + radius * Math.Cos(startAngle), centerY + radius * Math.Sin(startAngle)) });
            path.Segments.Add(new ArcSegment
            {
                Point = new Windows.Foundation.Point(centerX + radius * Math.Cos(endAngle), centerY + radius * Math.Sin(endAngle)),
                Size = new Windows.Foundation.Size(radius, radius),
                SweepDirection = SweepDirection.Clockwise,
                IsLargeArc = sweep > 180,
            });
            var geometry = new PathGeometry();
            geometry.Figures.Add(path);
            canvas.Children.Add(new Microsoft.UI.Xaml.Shapes.Path
            {
                Data = geometry,
                Fill = new SolidColorBrush(GenUiPalette[i % GenUiPalette.Length]),
            });
            start += sweep;
        }
    }

    private static VisualBlockRecord SynthGenUiMetric(JsonElement node, IReadOnlyDictionary<string, GenUiValue> state)
    {
        var formula = GetGenUiString(node, "formula");
        string value;
        if (!string.IsNullOrWhiteSpace(formula))
            value = GenUiFormula.EvalText(formula, state);
        else if (node.TryGetProperty("value", out var valueElement))
            value = valueElement.ValueKind == JsonValueKind.String
                ? GenUiEvents.RenderText(valueElement.GetString() ?? string.Empty, state)
                : valueElement.GetRawText();
        else value = string.Empty;
        return new VisualBlockRecord
        {
            Id = GetGenUiString(node, "id") ?? string.Empty,
            Type = "metric",
            Title = GenUiEvents.RenderText(GetGenUiString(node, "label") ?? GetGenUiString(node, "title") ?? string.Empty, state),
            Value = JsonSerializer.SerializeToElement(value),
            Unit = GetGenUiString(node, "unit"),
            Status = GenUiEvents.RenderText(GetGenUiString(node, "status") ?? string.Empty, state),
            Summary = GenUiEvents.RenderText(GetGenUiString(node, "summary") ?? string.Empty, state),
        };
    }

    private static VisualBlockRecord SynthGenUiProgress(JsonElement node, IReadOnlyDictionary<string, GenUiValue> state)
    {
        var formula = GetGenUiString(node, "formula");
        var value = !string.IsNullOrWhiteSpace(formula)
            ? GenUiFormula.EvalNumber(formula, state)
            : node.TryGetProperty("value", out var valueElement) && valueElement.ValueKind == JsonValueKind.Number
                ? valueElement.GetDouble() : 0;
        if (!double.IsFinite(value)) value = 0;
        return new VisualBlockRecord
        {
            Id = GetGenUiString(node, "id") ?? string.Empty,
            Type = "progress",
            Progress = Math.Clamp(value, 0, 1),
            Unit = GetGenUiString(node, "unit") ?? "%",
            Status = GenUiEvents.RenderText(GetGenUiString(node, "status") ?? string.Empty, state),
            Summary = GenUiEvents.RenderText(GetGenUiString(node, "summary") ?? string.Empty, state),
        };
    }

    private static double GetGenUiDouble(JsonElement node, string key, double fallback) =>
        node.TryGetProperty(key, out var element) && element.ValueKind == JsonValueKind.Number &&
        double.IsFinite(element.GetDouble()) ? element.GetDouble() : fallback;

    private static string? GetGenUiString(JsonElement element, string key)
    {
        return element.ValueKind == JsonValueKind.Object &&
               element.TryGetProperty(key, out var value) &&
               value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;
    }

    private static double CurrentNumber(GenUiSession session, string bind, double fallback)
    {
        if (!string.IsNullOrEmpty(bind) && session.State.TryGetValue(bind, out var existing))
        {
            var number = existing.AsNumber();
            if (double.IsFinite(number)) return number;
        }
        return fallback;
    }

    private static string FormatGenUiNumber(double value)
    {
        if (!double.IsFinite(value)) return "—";
        if (value == Math.Truncate(value) && Math.Abs(value) < 1e15)
            return ((long)value).ToString(System.Globalization.CultureInfo.InvariantCulture);
        return value.ToString("0.##", System.Globalization.CultureInfo.InvariantCulture);
    }
}

internal static class GenUiDoubleExtensions
{
    public static double Clamp(this double value, double min, double max) => Math.Clamp(value, min, max);
}
