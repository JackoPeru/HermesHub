using Microsoft.UI;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Documents;
using Microsoft.UI.Xaml.Media;
using Windows.UI;
using Windows.UI.Text;

namespace NemoclawChat_Windows.Pages;

internal static class MarkdownRenderer
{
    private const int MaxBlocks = 500;
    private const int MaxInputChars = 200_000;

    public static UIElement Render(string markdown, Color textColor)
    {
        var panel = new StackPanel { Spacing = 6 };
        if (string.IsNullOrEmpty(markdown))
        {
            return panel;
        }

        var safeMarkdown = markdown.Length > MaxInputChars
            ? markdown[..MaxInputChars] + "\n\n[troncato: limite 200000 caratteri raggiunto]"
            : markdown;
        var lines = safeMarkdown.Replace("\r\n", "\n").Split('\n');
        var paragraph = new System.Text.StringBuilder();
        var renderedBlocks = 0;

        bool CanRender() => renderedBlocks < MaxBlocks;

        void AddBlock(UIElement element)
        {
            if (!CanRender())
            {
                return;
            }
            panel.Children.Add(element);
            renderedBlocks++;
        }

        void FlushParagraph()
        {
            if (paragraph.Length == 0)
            {
                return;
            }
            AddBlock(BuildInlineTextBlock(paragraph.ToString().Trim(), textColor, 14, FontWeights.Normal));
            paragraph.Clear();
        }

        for (int i = 0; i < lines.Length && CanRender(); i++)
        {
            var line = lines[i];
            if (LooksLikeTableStart(lines, i))
            {
                FlushParagraph();
                var tableLines = new List<string>();
                while (i < lines.Length && IsPipeRow(lines[i]))
                {
                    tableLines.Add(lines[i]);
                    i++;
                }
                i--;
                AddBlock(BuildTable(tableLines, textColor));
                continue;
            }
            if (line.StartsWith("```", System.StringComparison.Ordinal))
            {
                FlushParagraph();
                var lang = line[3..].Trim();
                var codeBuf = new System.Text.StringBuilder();
                i++;
                while (i < lines.Length && !lines[i].StartsWith("```", System.StringComparison.Ordinal))
                {
                    if (codeBuf.Length > 0) codeBuf.Append('\n');
                    codeBuf.Append(lines[i]);
                    i++;
                }
                AddBlock(BuildCodeBlock(lang, codeBuf.ToString(), textColor));
                continue;
            }
            if (line.StartsWith("# ", System.StringComparison.Ordinal))
            {
                FlushParagraph();
                AddBlock(BuildInlineTextBlock(line[2..].Trim(), textColor, 20, FontWeights.SemiBold));
                continue;
            }
            if (line.StartsWith("## ", System.StringComparison.Ordinal))
            {
                FlushParagraph();
                AddBlock(BuildInlineTextBlock(line[3..].Trim(), textColor, 17, FontWeights.SemiBold));
                continue;
            }
            if (line.StartsWith("### ", System.StringComparison.Ordinal))
            {
                FlushParagraph();
                AddBlock(BuildInlineTextBlock(line[4..].Trim(), textColor, 15, FontWeights.SemiBold));
                continue;
            }
            if (line.StartsWith("- ", System.StringComparison.Ordinal) || line.StartsWith("* ", StringComparison.Ordinal))
            {
                FlushParagraph();
                AddBlock(BuildInlineTextBlock($"- {line[2..].Trim()}", textColor, 14, FontWeights.Normal));
                continue;
            }
            if (IsOrderedListLine(line))
            {
                FlushParagraph();
                AddBlock(BuildInlineTextBlock(line.Trim(), textColor, 14, FontWeights.Normal));
                continue;
            }
            if (string.IsNullOrWhiteSpace(line))
            {
                FlushParagraph();
                continue;
            }
            if (paragraph.Length > 0) paragraph.Append(' ');
            paragraph.Append(line);
        }
        FlushParagraph();
        if (!CanRender())
        {
            panel.Children.Add(BuildInlineTextBlock("[troncato: limite 500 blocchi raggiunto]", textColor, 12, FontWeights.Normal));
        }
        return panel;
    }

    private static bool IsOrderedListLine(string line)
    {
        var value = line.AsSpan().TrimStart();
        var i = 0;
        while (i < value.Length && char.IsDigit(value[i]))
        {
            i++;
        }

        return i > 0 &&
               i + 1 < value.Length &&
               (value[i] == '.' || value[i] == ')') &&
               char.IsWhiteSpace(value[i + 1]);
    }

    private static bool IsPipeRow(string line)
    {
        var trimmed = line.Trim();
        return trimmed.Length >= 3 &&
               trimmed.Contains('|') &&
               trimmed.Count(ch => ch == '|') >= 2;
    }

    private static bool LooksLikeTableStart(string[] lines, int index)
    {
        return index + 1 < lines.Length &&
               IsPipeRow(lines[index]) &&
               IsTableSeparator(lines[index + 1]);
    }

    private static bool IsTableSeparator(string line)
    {
        var cells = SplitTableRow(line);
        return cells.Count > 0 && cells.All(cell =>
        {
            var value = cell.Trim();
            if (value.Length < 3)
            {
                return false;
            }
            return value.All(ch => ch == '-' || ch == ':' || char.IsWhiteSpace(ch));
        });
    }

    private static List<string> SplitTableRow(string line)
    {
        var trimmed = line.Trim();
        if (trimmed.StartsWith('|')) trimmed = trimmed[1..];
        if (trimmed.EndsWith('|')) trimmed = trimmed[..^1];
        return trimmed.Split('|').Select(cell => cell.Trim()).ToList();
    }

    private static UIElement BuildTable(IReadOnlyList<string> tableLines, Color color)
    {
        var rows = tableLines
            .Where(line => !IsTableSeparator(line))
            .Select(SplitTableRow)
            .Where(cells => cells.Count > 0)
            .Take(60)
            .ToList();
        if (rows.Count == 0)
        {
            return BuildInlineTextBlock(string.Join('\n', tableLines), color, 14, FontWeights.Normal);
        }

        var columns = Math.Min(12, rows.Max(row => row.Count));
        var grid = new Grid
        {
            BorderBrush = (Brush)Application.Current.Resources["BorderBrushSoft"],
            BorderThickness = new Thickness(1)
        };
        for (var column = 0; column < columns; column++)
        {
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        }
        for (var row = 0; row < rows.Count; row++)
        {
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        }

        for (var row = 0; row < rows.Count; row++)
        {
            for (var column = 0; column < columns; column++)
            {
                var text = column < rows[row].Count ? rows[row][column] : string.Empty;
                var cell = new Border
                {
                    Padding = new Thickness(9, 7, 9, 7),
                    BorderBrush = (Brush)Application.Current.Resources["BorderBrushSoft"],
                    BorderThickness = new Thickness(column == 0 ? 0 : 1, row == 0 ? 0 : 1, 0, 0),
                    Background = row == 0
                        ? (Brush)Application.Current.Resources["ElevatedSurfaceBrush"]
                        : (Brush)Application.Current.Resources["ComposerBrush"],
                    Child = BuildInlineTextBlock(text, color, 13, row == 0 ? FontWeights.SemiBold : FontWeights.Normal)
                };
                Grid.SetRow(cell, row);
                Grid.SetColumn(cell, column);
                grid.Children.Add(cell);
            }
        }

        return new ScrollViewer
        {
            HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = grid
        };
    }

    private static TextBlock BuildInlineTextBlock(string text, Color color, double fontSize, FontWeight weight)
    {
        var tb = new TextBlock
        {
            TextWrapping = TextWrapping.WrapWholeWords,
            FontSize = fontSize,
            FontWeight = weight,
            Foreground = new SolidColorBrush(color),
            IsTextSelectionEnabled = true
        };
        foreach (var inline in BuildInlines(text, color))
        {
            tb.Inlines.Add(inline);
        }
        return tb;
    }

    private static List<Inline> BuildInlines(string text, Color color)
    {
        int i = 0;
        var buffer = new System.Text.StringBuilder();

        Inline MakeRun(string content, bool bold = false, bool italic = false, bool code = false)
        {
            var run = new Run { Text = content };
            if (bold) run.FontWeight = FontWeights.Bold;
            if (italic) run.FontStyle = Windows.UI.Text.FontStyle.Italic;
            if (code)
            {
                run.FontFamily = new FontFamily("Consolas");
            }
            run.Foreground = new SolidColorBrush(color);
            return run;
        }

        void Flush(List<Inline> sink)
        {
            if (buffer.Length > 0)
            {
                sink.Add(MakeRun(buffer.ToString()));
                buffer.Clear();
            }
        }

        var sink = new List<Inline>();
        while (i < text.Length)
        {
            var ch = text[i];
            if (ch == '*' && i + 1 < text.Length && text[i + 1] == '*')
            {
                var end = text.IndexOf("**", i + 2, System.StringComparison.Ordinal);
                if (end > i + 2)
                {
                    Flush(sink);
                    sink.Add(MakeRun(text.Substring(i + 2, end - i - 2), bold: true));
                    i = end + 2;
                    continue;
                }
            }
            if ((ch == '*' || ch == '_') && i + 1 < text.Length && text[i + 1] != ch)
            {
                var end = text.IndexOf(ch, i + 1);
                if (end > i + 1)
                {
                    Flush(sink);
                    sink.Add(MakeRun(text.Substring(i + 1, end - i - 1), italic: true));
                    i = end + 1;
                    continue;
                }
            }
            if (ch == '`')
            {
                var end = text.IndexOf('`', i + 1);
                if (end > i + 1)
                {
                    Flush(sink);
                    sink.Add(MakeRun(text.Substring(i + 1, end - i - 1), code: true));
                    i = end + 1;
                    continue;
                }
            }
            buffer.Append(ch);
            i++;
        }
        Flush(sink);
        return sink;
    }

    private static Border BuildCodeBlock(string language, string code, Color color)
    {
        var panel = new StackPanel { Spacing = 4 };

        var headerPanel = new Grid { Margin = new Thickness(0, 0, 0, 4) };
        headerPanel.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        headerPanel.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var langText = new TextBlock
        {
            Text = string.IsNullOrWhiteSpace(language) ? "code" : language,
            FontSize = 11,
            Foreground = (Brush)Application.Current.Resources["MutedTextBrush"],
            VerticalAlignment = VerticalAlignment.Center
        };
        Grid.SetColumn(langText, 0);
        headerPanel.Children.Add(langText);

        var copyButton = new Button
        {
            Content = new FontIcon { Glyph = "\uE8C8", FontSize = 12 },
            Background = new SolidColorBrush(Windows.UI.Color.FromArgb(0, 0, 0, 0)),
            BorderThickness = new Thickness(0),
            Padding = new Thickness(6),
            VerticalAlignment = VerticalAlignment.Center
        };
        ToolTipService.SetToolTip(copyButton, "Copia codice");
        copyButton.Click += (s, e) =>
        {
            var dp = new Windows.ApplicationModel.DataTransfer.DataPackage();
            dp.SetText(code);
            Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(dp);
            copyButton.Content = new FontIcon { Glyph = "\uE8FB", FontSize = 12 };
        };
        Grid.SetColumn(copyButton, 1);
        headerPanel.Children.Add(copyButton);

        panel.Children.Add(headerPanel);

        panel.Children.Add(new ScrollViewer
        {
            HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            Content = new TextBlock
            {
                Text = code,
                FontFamily = new FontFamily("Consolas"),
                FontSize = 12,
                Foreground = new SolidColorBrush(color),
                TextWrapping = TextWrapping.NoWrap,
                IsTextSelectionEnabled = true
            }
        });

        return new Border
        {
            Background = (Brush)Application.Current.Resources["ComposerBrush"],
            CornerRadius = new CornerRadius(10),
            Padding = new Thickness(10),
            Child = panel
        };
    }
}
