using System.Text;
using System.Text.Json;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using NemoclawChat_Windows.Services;
using Windows.Foundation;
using Windows.UI;

namespace NemoclawChat_Windows.Pages;

internal sealed class StreamingBubble
{
    private readonly Page _page;
    private readonly Action<UIElement> _addElement;
    private readonly ScrollViewer _scroll;
    private readonly StackPanel _content;
    private readonly TextBlock _thinkingLabel;
    private readonly Expander _thinkingExpander;
    private readonly TextBlock _thinkingText;
    private readonly StackPanel _toolCallsPanel;
    private readonly StackPanel _rawEventsPanel;
    private readonly ContentControl _assistantContainer;
    private readonly TextBlock _statsText;
    private readonly bool _showAdvanced;
    private readonly LinearGradientBrush _shimmerBrush;
    private readonly DispatcherTimer _shimmerTimer;
    private readonly Dictionary<string, ToolCallView> _toolViews = new();
    private readonly StringBuilder _thinkingBuilder = new();
    private readonly StringBuilder _textBuilder = new();
    private bool _hasThinking;
    private bool _hasText;
    private double _shimmerPhase;
    private DateTime _started = DateTime.UtcNow;

    public StreamingBubble(Page page, Action<UIElement> addElement, ScrollViewer scroll, bool showAdvanced)
    {
        _page = page;
        _addElement = addElement;
        _scroll = scroll;
        _showAdvanced = showAdvanced;
        _content = new StackPanel { Spacing = 10 };

        if (_showAdvanced)
        {
            _content.Children.Add(new TextBlock
            {
                Text = "Hermes",
                FontSize = 12,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                Foreground = new SolidColorBrush(Colors.White)
            });
        }

        _shimmerBrush = new LinearGradientBrush
        {
            StartPoint = new Point(0, 0.5),
            EndPoint = new Point(1, 0.5)
        };
        _shimmerBrush.GradientStops.Add(new GradientStop { Color = Color.FromArgb(0xFF, 0x6F, 0x78, 0x88), Offset = 0.0 });
        _shimmerBrush.GradientStops.Add(new GradientStop { Color = Color.FromArgb(0xFF, 0xFF, 0xFF, 0xFF), Offset = 0.5 });
        _shimmerBrush.GradientStops.Add(new GradientStop { Color = Color.FromArgb(0xFF, 0x6F, 0x78, 0x88), Offset = 1.0 });

        _thinkingLabel = new TextBlock
        {
            Text = "Sto pensando",
            FontSize = 14,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            Foreground = _shimmerBrush
        };

        _thinkingText = new TextBlock
        {
            Text = string.Empty,
            Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
            TextWrapping = TextWrapping.WrapWholeWords,
            FontSize = 12
        };

        _thinkingExpander = new Expander
        {
            Header = _thinkingLabel,
            Content = new ScrollViewer
            {
                MaxHeight = 220,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                Content = _thinkingText
            },
            Background = new SolidColorBrush(Colors.Transparent),
            BorderThickness = new Thickness(0),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Visibility = Visibility.Visible
        };

        if (_showAdvanced)
        {
            _content.Children.Add(_thinkingExpander);
        }

        _toolCallsPanel = new StackPanel { Spacing = 8 };
        _content.Children.Add(_toolCallsPanel);

        _rawEventsPanel = new StackPanel { Spacing = 8 };
        _content.Children.Add(_rawEventsPanel);

        _assistantContainer = new ContentControl
        {
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            Visibility = Visibility.Collapsed
        };
        _content.Children.Add(_assistantContainer);

        _statsText = new TextBlock
        {
            Text = string.Empty,
            FontSize = 11,
            Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
            Visibility = Visibility.Collapsed
        };
        _content.Children.Add(_statsText);

        var bubble = new Border
        {
            MaxWidth = 820,
            Padding = _showAdvanced ? new Thickness(18, 14, 18, 14) : new Thickness(4, 2, 4, 2),
            CornerRadius = _showAdvanced ? new CornerRadius(20) : new CornerRadius(0),
            Background = _showAdvanced ? (Brush)Application.Current.Resources["AssistantBubbleBrush"] : new SolidColorBrush(Colors.Transparent),
            BorderBrush = _showAdvanced ? (Brush)Application.Current.Resources["BorderBrushSoft"] : new SolidColorBrush(Colors.Transparent),
            BorderThickness = _showAdvanced ? new Thickness(1) : new Thickness(0),
            HorizontalAlignment = HorizontalAlignment.Left,
            Child = _content
        };

        _addElement(bubble);
        _ = _scroll.ChangeView(null, _scroll.ScrollableHeight, null);

        _shimmerTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(30) };
        _shimmerTimer.Tick += (_, _) => AdvanceShimmer();
        _shimmerTimer.Start();
    }

    private void AdvanceShimmer()
    {
        _shimmerPhase += 0.025;
        if (_shimmerPhase > 1.5)
        {
            _shimmerPhase = -0.5;
        }
        _shimmerBrush.StartPoint = new Point(_shimmerPhase - 0.5, 0.5);
        _shimmerBrush.EndPoint = new Point(_shimmerPhase + 0.5, 0.5);
    }

    public void AppendText(string delta)
    {
        if (string.IsNullOrEmpty(delta))
        {
            return;
        }
        _textBuilder.Append(delta);
        _assistantContainer.Content = MarkdownRenderer.Render(_textBuilder.ToString(), Colors.White);
        if (!_hasText)
        {
            _hasText = true;
            _assistantContainer.Visibility = Visibility.Visible;
            if (_hasThinking)
            {
                FreezeThinkingLabel();
            }
            else
            {
                _thinkingExpander.Visibility = Visibility.Collapsed;
                _shimmerTimer.Stop();
            }
        }
        ScheduleScroll();
    }

    public void AppendThinking(string delta)
    {
        if (!_showAdvanced)
        {
            return;
        }
        if (string.IsNullOrEmpty(delta))
        {
            return;
        }
        _thinkingBuilder.Append(delta);
        _thinkingText.Text = _thinkingBuilder.ToString();
        _hasThinking = true;
        _thinkingExpander.Visibility = Visibility.Visible;
        ScheduleScroll();
    }

    private void FreezeThinkingLabel()
    {
        _shimmerTimer.Stop();
        var elapsed = (DateTime.UtcNow - _started).TotalSeconds;
        _thinkingLabel.Foreground = (Brush)Application.Current.Resources["MutedTextBrush"];
        _thinkingLabel.Text = elapsed >= 1
            ? $"Pensato per {elapsed:0.#}s"
            : "Ragionamento";
    }

    public void StartToolCall(string id, string name)
    {
        if (!_showAdvanced)
        {
            return;
        }
        if (_toolViews.ContainsKey(id))
        {
            return;
        }

        var statusIcon = new FontIcon
        {
            Glyph = "",
            FontSize = 14,
            Foreground = (Brush)Application.Current.Resources["AccentBrush"]
        };
        var statusText = new TextBlock
        {
            Text = "in esecuzione…",
            FontSize = 11,
            Foreground = (Brush)Application.Current.Resources["MutedTextBrush"]
        };
        var header = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 8,
            HorizontalAlignment = HorizontalAlignment.Stretch
        };
        header.Children.Add(statusIcon);
        header.Children.Add(new TextBlock
        {
            Text = $"Tool · {name}",
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            FontSize = 12,
            Foreground = new SolidColorBrush(Colors.White),
            VerticalAlignment = VerticalAlignment.Center
        });
        header.Children.Add(statusText);

        var detailPanel = new StackPanel { Spacing = 6, Padding = new Thickness(4, 6, 4, 4) };
        var argsLabel = new TextBlock
        {
            Text = "Argomenti",
            FontSize = 11,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            Foreground = (Brush)Application.Current.Resources["MutedTextBrush"]
        };
        var argsBlock = new TextBlock
        {
            Text = "—",
            FontFamily = new FontFamily("Consolas"),
            FontSize = 11,
            Foreground = new SolidColorBrush(Colors.White),
            TextWrapping = TextWrapping.Wrap
        };
        var argsBorder = new Border
        {
            Padding = new Thickness(10),
            Background = (Brush)Application.Current.Resources["ComposerBrush"],
            CornerRadius = new CornerRadius(8),
            Child = argsBlock
        };
        detailPanel.Children.Add(argsLabel);
        detailPanel.Children.Add(argsBorder);

        var resultLabel = new TextBlock
        {
            Text = "Risultato",
            FontSize = 11,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
            Visibility = Visibility.Collapsed
        };
        var resultBlock = new TextBlock
        {
            Text = string.Empty,
            FontFamily = new FontFamily("Consolas"),
            FontSize = 11,
            Foreground = new SolidColorBrush(Colors.White),
            TextWrapping = TextWrapping.Wrap
        };
        var resultBorder = new Border
        {
            Padding = new Thickness(10),
            Background = (Brush)Application.Current.Resources["ComposerBrush"],
            CornerRadius = new CornerRadius(8),
            Child = resultBlock,
            Visibility = Visibility.Collapsed
        };
        detailPanel.Children.Add(resultLabel);
        detailPanel.Children.Add(resultBorder);

        var outcomeText = new TextBlock
        {
            Text = "Esito: in corso",
            FontSize = 11,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            Foreground = (Brush)Application.Current.Resources["AccentBrush"]
        };
        detailPanel.Children.Add(outcomeText);

        var expander = new Expander
        {
            Header = header,
            Content = detailPanel,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            Background = (Brush)Application.Current.Resources["SurfaceBrush"],
            BorderBrush = (Brush)Application.Current.Resources["BorderBrushSoft"],
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(12)
        };

        _toolCallsPanel.Children.Add(expander);
        _toolViews[id] = new ToolCallView(expander, argsBlock, resultBlock, resultLabel, resultBorder, statusText, statusIcon, outcomeText, new StringBuilder());
        ScheduleScroll();
    }

    public void AppendToolCallArguments(string id, string delta)
    {
        if (!_showAdvanced)
        {
            return;
        }
        if (string.IsNullOrEmpty(delta))
        {
            return;
        }
        if (!_toolViews.TryGetValue(id, out var view))
        {
            StartToolCall(id, "tool");
            view = _toolViews[id];
        }
        view.Args.Append(delta);
        view.ArgsBlock.Text = PrettifyJson(view.Args.ToString());
        ScheduleScroll();
    }

    public void EndToolCall(string id)
    {
        if (!_showAdvanced)
        {
            return;
        }
        if (_toolViews.TryGetValue(id, out var view))
        {
            view.StatusBlock.Text = "completato";
            view.StatusIcon.Glyph = "";
            view.StatusIcon.Foreground = new SolidColorBrush(Color.FromArgb(0xFF, 0x34, 0xC7, 0x59));
            view.OutcomeText.Text = "Esito: riuscito";
            view.OutcomeText.Foreground = new SolidColorBrush(Color.FromArgb(0xFF, 0x34, 0xC7, 0x59));
        }
    }

    public void AddToolResult(string? id, string? name, string output)
    {
        if (!_showAdvanced)
        {
            return;
        }
        if (!string.IsNullOrWhiteSpace(id) && _toolViews.TryGetValue(id!, out var view))
        {
            view.StatusBlock.Text = "risultato pronto";
            view.ResultBlock.Text = PrettifyJson(output);
            view.ResultLabel.Visibility = Visibility.Visible;
            view.ResultBorder.Visibility = Visibility.Visible;
            var isError = output.Contains("\"error\"", StringComparison.OrdinalIgnoreCase);
            if (isError)
            {
                view.StatusIcon.Glyph = "";
                view.StatusIcon.Foreground = new SolidColorBrush(Color.FromArgb(0xFF, 0xFF, 0x45, 0x3A));
                view.OutcomeText.Text = "Esito: fallito";
                view.OutcomeText.Foreground = new SolidColorBrush(Color.FromArgb(0xFF, 0xFF, 0x45, 0x3A));
            }
            else
            {
                view.StatusIcon.Glyph = "";
                view.StatusIcon.Foreground = new SolidColorBrush(Color.FromArgb(0xFF, 0x34, 0xC7, 0x59));
                view.OutcomeText.Text = "Esito: riuscito";
                view.OutcomeText.Foreground = new SolidColorBrush(Color.FromArgb(0xFF, 0x34, 0xC7, 0x59));
            }
        }
        else
        {
            StartToolCall(id ?? "tool", name ?? "tool");
            AddToolResult(id ?? "tool", name, output);
        }
        ScheduleScroll();
    }

    public void SetVisualBlocks(IReadOnlyList<VisualBlockRecord> blocks, Func<VisualBlockRecord, UIElement> renderer)
    {
        if (blocks is null || blocks.Count == 0)
        {
            return;
        }
        foreach (var block in blocks.Where(VisualBlockParser.IsValid))
        {
            _content.Children.Add(renderer(block));
        }
        ScheduleScroll();
    }

    public void AddRawEvent(string name, string json)
    {
        if (!_showAdvanced)
        {
            return;
        }
        var body = new TextBlock
        {
            Text = PrettifyJson(json),
            FontFamily = new FontFamily("Consolas"),
            FontSize = 11,
            Foreground = new SolidColorBrush(Colors.White),
            TextWrapping = TextWrapping.Wrap
        };
        _rawEventsPanel.Children.Add(new Expander
        {
            Header = new TextBlock
            {
                Text = $"Evento Hermes · {name}",
                FontSize = 12,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                Foreground = (Brush)Application.Current.Resources["MutedTextBrush"]
            },
            Content = new Border
            {
                Padding = new Thickness(10),
                Background = (Brush)Application.Current.Resources["ComposerBrush"],
                CornerRadius = new CornerRadius(8),
                Child = body
            },
            Background = (Brush)Application.Current.Resources["SurfaceBrush"],
            BorderBrush = (Brush)Application.Current.Resources["BorderBrushSoft"],
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(12)
        });
        ScheduleScroll();
    }

    public void Complete(ChatStreamStats stats)
    {
        if (!_showAdvanced)
        {
            _thinkingExpander.Visibility = Visibility.Collapsed;
            _shimmerTimer.Stop();
            ScheduleScroll();
            return;
        }
        if (_hasThinking && _shimmerTimer.IsEnabled)
        {
            FreezeThinkingLabel();
        }
        else if (!_hasThinking)
        {
            _thinkingExpander.Visibility = Visibility.Collapsed;
            _shimmerTimer.Stop();
        }

        if (!_hasText && _textBuilder.Length > 0)
        {
            _assistantContainer.Visibility = Visibility.Visible;
            _hasText = true;
        }

        var parts = new List<string>();
        if (stats.TimeToFirstTokenMs is { } ttft && ttft > 0)
        {
            parts.Add($"TTFT {ttft:0}ms");
        }
        if (stats.TokensPerSecond is { } tps && tps > 0)
        {
            parts.Add($"{tps:0.0} t/s");
        }
        if (stats.TokensOut is { } toks && toks > 0)
        {
            parts.Add($"{toks} tok");
        }
        if (stats.PromptTokens is { } pt && pt > 0)
        {
            parts.Add($"prompt {pt}");
        }
        if (stats.TotalMs is { } total && total > 0)
        {
            parts.Add($"{total / 1000.0:0.0}s");
        }
        if (parts.Count > 0)
        {
            _statsText.Text = string.Join("  ·  ", parts);
            _statsText.Visibility = Visibility.Visible;
        }
        ScheduleScroll();
    }

    public void StopShimmer()
    {
        if (_shimmerTimer.IsEnabled)
        {
            _shimmerTimer.Stop();
        }
    }

    private void ScheduleScroll()
    {
        _ = _scroll.ChangeView(null, _scroll.ScrollableHeight, null, true);
    }

    private static string PrettifyJson(string raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
        {
            return "—";
        }
        var trimmed = raw.Trim();
        try
        {
            using var doc = JsonDocument.Parse(trimmed);
            return JsonSerializer.Serialize(doc.RootElement, new JsonSerializerOptions { WriteIndented = true });
        }
        catch
        {
            return raw;
        }
    }

    private sealed record ToolCallView(
        Expander Container,
        TextBlock ArgsBlock,
        TextBlock ResultBlock,
        TextBlock ResultLabel,
        Border ResultBorder,
        TextBlock StatusBlock,
        FontIcon StatusIcon,
        TextBlock OutcomeText,
        StringBuilder Args);
}
