using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace NemoclawChat_Windows.Services;

/// <summary>
/// Righe stile Apple costruite in codice (liste generate a runtime):
/// niente box, solo tile icona + testi + accessorio e hairline sotto.
/// Speculare ai pattern XAML (HairlineBrush, FormSectionHeaderStyle).
/// </summary>
public static class PremiumRows
{
    private static Brush Res(string key) =>
        (Brush)Application.Current.Resources[key];

    public static Border Hairline() => new()
    {
        Height = 1,
        Background = Res("HairlineBrush"),
        Margin = new Thickness(0, 6, 0, 6)
    };

    public static TextBlock SectionHeader(string text) => new()
    {
        Text = (text ?? string.Empty).ToUpperInvariant(),
        FontSize = 12,
        FontWeight = FontWeights.SemiBold,
        CharacterSpacing = 60,
        Foreground = Res("FaintTextBrush")
    };

    public static TextBlock Chevron() => new()
    {
        Text = "›",
        FontSize = 22,
        Foreground = Res("FaintTextBrush"),
        VerticalAlignment = VerticalAlignment.Center
    };

    /// <summary>
    /// Tile icona 40px stondato con tinta. Solo decorativo.
    /// </summary>
    public static Border IconTile(string glyph, string? tintBrushKey = null, string? glyphBrushKey = null) => new()
    {
        Width = 40,
        Height = 40,
        CornerRadius = new CornerRadius(12),
        Background = Res(tintBrushKey ?? "AccentSubtleBrush"),
        Child = new FontIcon
        {
            FontSize = 17,
            Glyph = glyph,
            Foreground = Res(glyphBrushKey ?? "AccentBrush"),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        }
    };

    /// <summary>
    /// Bottone-riga full-width trasparente con hairline sotto. Il contenuto
    /// e a cura del chiamante (di solito LabeledRow). Stessi stati del resto
    /// dell'app via Template premium quando applicato? Qui volutamente
    /// minimale: sfondo trasparente, feedback dallo style Ghost opzionale.
    /// </summary>
    public static Button RowButton(object content) => new()
    {
        HorizontalAlignment = HorizontalAlignment.Stretch,
        HorizontalContentAlignment = HorizontalAlignment.Stretch,
        VerticalContentAlignment = VerticalAlignment.Center,
        Padding = new Thickness(4, 12, 4, 12),
        Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
        BorderBrush = Res("HairlineBrush"),
        BorderThickness = new Thickness(0, 0, 0, 1),
        CornerRadius = new CornerRadius(0),
        Content = content
    };

    /// <summary>
    /// Riga icona + testi + accessorio (chevron, pill, bottone). Ritorna la Grid.
    /// </summary>
    public static Grid LabeledRow(
        string glyph,
        string title,
        string? subtitle = null,
        UIElement? accessory = null,
        string? tintBrushKey = null,
        string? glyphBrushKey = null)
    {
        var grid = new Grid { ColumnSpacing = 12 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.Children.Add(IconTile(glyph, tintBrushKey, glyphBrushKey));

        var texts = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        texts.Children.Add(new TextBlock
        {
            Text = title,
            Foreground = new SolidColorBrush(Microsoft.UI.Colors.White),
            FontWeight = FontWeights.SemiBold,
            FontSize = 15,
            TextTrimming = TextTrimming.CharacterEllipsis
        });
        if (!string.IsNullOrWhiteSpace(subtitle))
        {
            texts.Children.Add(new TextBlock
            {
                Text = subtitle,
                Foreground = Res("MutedTextBrush"),
                FontSize = 12,
                TextTrimming = TextTrimming.CharacterEllipsis,
                TextWrapping = TextWrapping.WrapWholeWords,
                MaxLines = 2
            });
        }
        Grid.SetColumn(texts, 1);
        grid.Children.Add(texts);

        var right = accessory ?? Chevron();
        right.SetValue(Grid.ColumnProperty, 2);
        if (right is FrameworkElement fe)
        {
            fe.VerticalAlignment = VerticalAlignment.Center;
        }
        grid.Children.Add(right);
        return grid;
    }

    /// <summary>
    /// Testo caps attenuato per metadati (kind, date, conteggi).
    /// </summary>
    public static TextBlock MetaLine(string text) => new()
    {
        Text = (text ?? string.Empty).ToUpperInvariant(),
        FontSize = 11,
        Foreground = Res("FaintTextBrush"),
        CharacterSpacing = 40
    };
}
