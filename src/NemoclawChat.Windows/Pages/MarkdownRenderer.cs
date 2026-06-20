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

    private static TextBlock BuildInlineTextBlock(string text, Color color, double fontSize, FontWeight weight)
    {
        var tb = new TextBlock
        {
            TextWrapping = TextWrapping.WrapWholeWords,
            FontSize = fontSize,
            FontWeight = weight,
            Foreground = new SolidColorBrush(color)
        };
        foreach (var inline in BuildInlines(text, color))
        {
            tb.Inlines.Add(inline);
        }
        return tb;
    }

    private static IEnumerable<Inline> BuildInlines(string text, Color color)
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

    private static UIElement BuildCodeBlock(string language, string code, Color color)
    {
        var panel = new StackPanel { Spacing = 4 };
        if (!string.IsNullOrWhiteSpace(language))
        {
            panel.Children.Add(new TextBlock
            {
                Text = language,
                FontSize = 11,
                Foreground = (Brush)Application.Current.Resources["MutedTextBrush"]
            });
        }
        panel.Children.Add(new TextBlock
        {
            Text = code,
            FontFamily = new FontFamily("Consolas"),
            FontSize = 12,
            Foreground = new SolidColorBrush(color),
            TextWrapping = TextWrapping.NoWrap
        });
        return new Border
        {
            Background = (Brush)Application.Current.Resources["ComposerBrush"],
            CornerRadius = new CornerRadius(10),
            Padding = new Thickness(10),
            Child = new ScrollViewer
            {
                HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
                Content = panel
            }
        };
    }
}
