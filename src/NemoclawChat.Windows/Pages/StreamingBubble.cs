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
    private const int MaxLivePreviewChars = 120_000;
    private const int MaxMarkdownRenderChars = 32_000;

    private readonly Page _page;
    private readonly Action<UIElement> _addElement;
    private readonly ScrollViewer _scroll;
    private readonly StackPanel _content;
    private readonly TextBlock _thinkingLabel;
    private readonly Expander _thinkingExpander;
    private readonly TextBlock _thinkingText;
    private readonly StackPanel _toolCallsPanel;
    private readonly Expander _toolsExpander;
    private readonly TextBlock _toolsLabel;
    private readonly StackPanel _rawEventsPanel;
    private readonly ContentControl _assistantContainer;
    private readonly TextBlock _assistantTextPreview;
    private readonly TextBlock _statusText;
    private readonly ProgressBar _promptProgressBar;
    private readonly TextBlock _statsText;
    private readonly Grid _footerGrid;
    private readonly Button _speakButton;
    private readonly Button _copyButton;
    private readonly Func<string, Button?, Task> _speakMessageAsync;
    private readonly bool _showAdvanced;
    private readonly bool _showTools;
    private readonly bool _showMetrics;
    private readonly LinearGradientBrush _shimmerBrush;
    private readonly DispatcherTimer _shimmerTimer;
    private readonly DispatcherTimer _renderTimer;
    private readonly Dictionary<string, ToolCallView> _toolViews = new();
    private readonly StringBuilder _thinkingBuilder = new();
    private readonly StringBuilder _textBuilder = new();
    private string _phaseStatusBase = "Invio prompt a Hermes...";
    private bool _hasThinking;
    private bool _hasText;
    private bool _renderPending;
    private DateTime _lastScroll = DateTime.MinValue;
    private double _shimmerPhase;
    private DateTime _started = DateTime.UtcNow;

    public UIElement Container { get; }

    public StreamingBubble(Page page, Action<UIElement> addElement, ScrollViewer scroll, bool showAdvanced, bool showTools, bool showMetrics, Func<string, Button?, Task> speakMessageAsync)
    {
        _page = page;
        _addElement = addElement;
        _scroll = scroll;
        _showAdvanced = showAdvanced;
        _showTools = showTools;
        _showMetrics = showMetrics;
        _speakMessageAsync = speakMessageAsync;
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
            Text = "Ragionamento",
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
            Visibility = Visibility.Collapsed
        };

        _content.Children.Add(_thinkingExpander);

        _toolsLabel = new TextBlock
        {
            Text = "Azioni",
            FontSize = 14,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            Foreground = (Brush)Application.Current.Resources["MutedTextBrush"]
        };

        _toolCallsPanel = new StackPanel { Spacing = 8 };

        _toolsExpander = new Expander
        {
            Header = _toolsLabel,
            Content = new ScrollViewer
            {
                MaxHeight = 350,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                Content = _toolCallsPanel
            },
            Background = new SolidColorBrush(Colors.Transparent),
            BorderThickness = new Thickness(0),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Visibility = Visibility.Collapsed
        };

        _content.Children.Add(_toolsExpander);

        _rawEventsPanel = new StackPanel { Spacing = 8 };
        _content.Children.Add(_rawEventsPanel);

        _statusText = new TextBlock
        {
            Text = "Invio prompt a Hermes...",
            FontSize = 12,
            Foreground = _shimmerBrush,
            TextWrapping = TextWrapping.WrapWholeWords,
            Visibility = Visibility.Visible
        };
        _content.Children.Add(_statusText);

        _promptProgressBar = new ProgressBar
        {
            Minimum = 0,
            Maximum = 100,
            Value = 0,
            Height = 3,
            Visibility = Visibility.Collapsed
        };
        _content.Children.Add(_promptProgressBar);

        _assistantContainer = new ContentControl
        {
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            Visibility = Visibility.Collapsed
        };
        _assistantTextPreview = new TextBlock
        {
            Text = string.Empty,
            Foreground = new SolidColorBrush(Colors.White),
            TextWrapping = TextWrapping.WrapWholeWords
        };
        _assistantContainer.Content = _assistantTextPreview;
        _content.Children.Add(_assistantContainer);

        _footerGrid = new Grid { Margin = new Thickness(0, 4, 0, 0), Visibility = Visibility.Collapsed };
        _footerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        _footerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _footerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        _statsText = new TextBlock
        {
            Text = string.Empty,
            FontSize = 11,
            Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
            VerticalAlignment = VerticalAlignment.Center
        };
        Grid.SetColumn(_statsText, 0);
        _footerGrid.Children.Add(_statsText);

        _speakButton = new Button
        {
            Content = new FontIcon { Glyph = "\uE768", FontSize = 12 },
            Background = new SolidColorBrush(Windows.UI.Color.FromArgb(0, 0, 0, 0)),
            BorderThickness = new Thickness(0),
            Padding = new Thickness(6),
            VerticalAlignment = VerticalAlignment.Center
        };
        ToolTipService.SetToolTip(_speakButton, "Leggi messaggio");
        _speakButton.Click += async (s, e) => await _speakMessageAsync(_textBuilder.ToString(), _speakButton);
        Grid.SetColumn(_speakButton, 1);
        _footerGrid.Children.Add(_speakButton);

        _copyButton = new Button
        {
            Content = new FontIcon { Glyph = "\uE8C8", FontSize = 12 },
            Background = new SolidColorBrush(Windows.UI.Color.FromArgb(0, 0, 0, 0)),
            BorderThickness = new Thickness(0),
            Padding = new Thickness(6),
            VerticalAlignment = VerticalAlignment.Center
        };
        ToolTipService.SetToolTip(_copyButton, "Copia messaggio");
        _copyButton.Click += (s, e) =>
        {
            var dp = new Windows.ApplicationModel.DataTransfer.DataPackage();
            dp.SetText(_textBuilder.ToString());
            Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(dp);
            _copyButton.Content = new FontIcon { Glyph = "\uE8FB", FontSize = 12 };
        };
        Grid.SetColumn(_copyButton, 2);
        _footerGrid.Children.Add(_copyButton);

        _content.Children.Add(_footerGrid);

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

        Container = bubble;
        _addElement(bubble);
        _ = _scroll.ChangeView(null, _scroll.ScrollableHeight, null);

        _shimmerTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(30) };
        _shimmerTimer.Tick += (_, _) => AdvanceShimmer();
        _shimmerTimer.Start();

        _renderTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(66) };
        _renderTimer.Tick += (_, _) => FlushTextPreview();
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
        if (!_hasText && !_hasThinking && _statusText.Visibility == Visibility.Visible)
        {
            var elapsed = (DateTime.UtcNow - _started).TotalSeconds;
            _statusText.Text = $"{_phaseStatusBase} ({elapsed:0.0}s)";
        }
    }

    public void AppendText(string delta)
    {
        if (string.IsNullOrEmpty(delta))
        {
            return;
        }
        _textBuilder.Append(delta);
        ScheduleTextRender();
        if (!_hasText)
        {
            _hasText = true;
            _assistantContainer.Visibility = Visibility.Visible;
            SetStatus("Generazione risposta...");
            _promptProgressBar.Visibility = Visibility.Collapsed;
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

    public void SetStatus(string status)
    {
        if (string.IsNullOrWhiteSpace(status))
        {
            return;
        }

        _phaseStatusBase = status.Trim();
        _statusText.Text = _phaseStatusBase;
        _statusText.Foreground = _hasText
            ? (Brush)Application.Current.Resources["MutedTextBrush"]
            : _shimmerBrush;
        _statusText.Visibility = Visibility.Visible;
    }

    public void SetPromptProgress(StreamPromptProgress progress)
    {
        if (progress.Estimated)
        {
            return;
        }

        var clamped = Math.Clamp(progress.Percent, 0, 100);
        var status = string.IsNullOrWhiteSpace(progress.Label) ? "llama.cpp: prefill prompt" : progress.Label.Trim();
        var details = new List<string>();
        if (progress.ProcessedTokens is { } processed && progress.TotalTokens is { } total && total > 0)
        {
            details.Add($"{processed}/{total} tok");
        }
        if (progress.CachedTokens is { } cached && cached > 0)
        {
            details.Add($"cache {cached}");
        }
        if (progress.TimeMs is { } timeMs && timeMs >= 0)
        {
            details.Add($"{timeMs / 1000.0:0.0}s");
        }
        var suffix = details.Count > 0 ? $" ({string.Join(", ", details)})" : string.Empty;
        SetStatus($"{status}: {clamped}%{suffix}");
        _promptProgressBar.Value = clamped;
        _promptProgressBar.Visibility = Visibility.Visible;
    }

    public void ResumeLiveIndicators()
    {
        if (!_hasText || _hasThinking)
        {
            _shimmerTimer.Start();
        }
        if (_renderPending)
        {
            _renderTimer.Start();
        }
    }

    public void AppendThinking(string delta)
    {
        if (string.IsNullOrEmpty(delta))
        {
            return;
        }
        AppendDeduped(_thinkingBuilder, delta);
        _thinkingText.Text = _thinkingBuilder.ToString();
        _hasThinking = true;
        _promptProgressBar.Visibility = Visibility.Collapsed;
        SetStatus("Ragionamento in corso...");
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
        if (!_showTools)
        {
            return;
        }
        if (_toolViews.ContainsKey(id))
        {
            return;
        }

        if (_toolsExpander.Visibility == Visibility.Collapsed)
        {
            _toolsExpander.Visibility = Visibility.Visible;
        }
        _toolsLabel.Text = $"Azioni ({_toolViews.Count + 1})";

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
        if (!_showTools)
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
        if (!_showTools)
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
        if (!_showTools)
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
        FlushTextPreview();
        _renderTimer.Stop();
        _statusText.Visibility = Visibility.Collapsed;
        _promptProgressBar.Visibility = Visibility.Collapsed;
        if (!_showAdvanced && !_hasThinking)
        {
            _thinkingExpander.Visibility = Visibility.Collapsed;
            _shimmerTimer.Stop();
            ScheduleScroll();
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

        if (_textBuilder.Length > 0)
        {
            var finalText = _textBuilder.ToString();
            _assistantContainer.Content = finalText.Length <= MaxMarkdownRenderChars
                ? MarkdownRenderer.Render(finalText, Colors.White)
                : _assistantTextPreview;
        }

        var settings = AppSettingsStore.Load();
        var parts = new List<string>();
        if (settings.MetricTtft && stats.TimeToFirstTokenMs is { } ttft && ttft > 0)
        {
            parts.Add($"TTFT {ttft / 1000.0:0.0}s");
        }
        if (settings.MetricTokensPerSecond && stats.TokensPerSecond is { } tps && tps > 0)
        {
            parts.Add($"{tps:0.0} t/s");
        }
        if (settings.MetricOutputTokens && stats.TokensOut is { } toks && toks > 0)
        {
            parts.Add($"{toks} tok");
        }
        if (settings.MetricPromptTokens && stats.PromptTokens is { } pt && pt > 0)
        {
            parts.Add($"prompt {pt}");
        }
        if (settings.MetricContextTokens && stats.ContextTokens is { } ctx && ctx > 0)
        {
            parts.Add($"ctx {ctx}");
        }
        if (settings.MetricContextTokens && stats.ContextLength is { } maxCtx && maxCtx > 0)
        {
            parts.Add($"max {maxCtx}");
        }
        if (settings.MetricDuration && stats.TotalMs is { } total && total > 0)
        {
            parts.Add($"{total / 1000.0:0.0}s");
        }
        if (_showMetrics && parts.Count > 0)
        {
            _statsText.Text = string.Join("  ·  ", parts);
        }
        _footerGrid.Visibility = Visibility.Visible;
        ScheduleScroll();
    }

    public void SynchronizeFinalContent(string? finalText, string? finalThinking)
    {
        if (!string.IsNullOrEmpty(finalText) && !string.Equals(_textBuilder.ToString(), finalText, StringComparison.Ordinal))
        {
            _textBuilder.Clear();
            _textBuilder.Append(finalText);
            _hasText = _textBuilder.Length > 0;
            if (_hasText)
            {
                _assistantContainer.Visibility = Visibility.Visible;
            }
            _renderPending = true;
        }

        if (!string.IsNullOrEmpty(finalThinking) && !string.Equals(_thinkingBuilder.ToString(), finalThinking, StringComparison.Ordinal))
        {
            _thinkingBuilder.Clear();
            _thinkingBuilder.Append(finalThinking);
            _thinkingText.Text = finalThinking;
            _hasThinking = _thinkingBuilder.Length > 0;
            _thinkingExpander.Visibility = _hasThinking ? Visibility.Visible : Visibility.Collapsed;
        }

        FlushTextPreview();
    }

    public void StopShimmer()
    {
        if (_shimmerTimer.IsEnabled)
        {
            _shimmerTimer.Stop();
        }
        if (_renderTimer.IsEnabled)
        {
            _renderTimer.Stop();
        }
    }

    public void FlushPreview()
    {
        FlushTextPreview();
    }

    private void ScheduleScroll()
    {
        if ((DateTime.UtcNow - _lastScroll).TotalMilliseconds < 80)
        {
            return;
        }
        _lastScroll = DateTime.UtcNow;
        _ = _scroll.ChangeView(null, _scroll.ScrollableHeight, null, true);
    }

    private void ScheduleTextRender()
    {
        _renderPending = true;
        if (!_renderTimer.IsEnabled)
        {
            _renderTimer.Start();
        }
    }

    private void FlushTextPreview()
    {
        if (!_renderPending)
        {
            return;
        }

        _renderPending = false;
        var text = PreviewText(_textBuilder);
        if (text.Length <= MaxMarkdownRenderChars)
        {
            _assistantContainer.Content = MarkdownRenderer.Render(text, Colors.White);
        }
        else
        {
            _assistantTextPreview.Text = text;
            _assistantContainer.Content = _assistantTextPreview;
        }
        ScheduleScroll();
    }

    private static string PreviewText(StringBuilder builder)
    {
        if (builder.Length <= MaxLivePreviewChars)
        {
            return builder.ToString();
        }

        return builder.ToString(0, MaxLivePreviewChars) +
               "\n\n[anteprima live limitata per stabilita UI; risposta completa salvata nella chat]";
    }

    private static void AppendDeduped(StringBuilder builder, string delta)
    {
        if (delta.Length == 0)
        {
            return;
        }

        if (builder.Length == 0)
        {
            builder.Append(delta);
            return;
        }

        var current = builder.ToString();
        if (delta == current || current.EndsWith(delta, StringComparison.Ordinal))
        {
            return;
        }

        if (delta.StartsWith(current, StringComparison.Ordinal))
        {
            builder.Clear();
            builder.Append(delta);
            return;
        }

        builder.Append(delta);
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
