using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media.Imaging;
using NemoclawChat_Windows.Services;
using Windows.ApplicationModel.DataTransfer;

namespace NemoclawChat_Windows.Pages;

public sealed class ImageGridItem
{
    public required LocalImageRecord Record { get; init; }
    public required Uri ThumbUri { get; init; }
    public required Uri FullUri { get; init; }
    public string Title => Record.Title;
    public string Meta => $"{Record.Extension} · {Record.SizeLabel}";
}

public sealed partial class ImagesPage : Page
{
    private List<ImageGridItem> _items = [];
    private bool _syncingSelection;

    public ImagesPage()
    {
        InitializeComponent();
        Loaded += ImagesPage_Loaded;
    }

    private async void ImagesPage_Loaded(object sender, RoutedEventArgs e)
    {
        Loaded -= ImagesPage_Loaded;
        var settings = AppSettingsStore.Load();
        if (!string.IsNullOrWhiteSpace(settings.ImageLibraryPath))
        {
            LibraryPathBox.Text = settings.ImageLibraryPath;
        }
        await RefreshFeedAsync(null);
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await RefreshFeedAsync(null);

    private async void LoadPath_Click(object sender, RoutedEventArgs e)
    {
        var path = LibraryPathBox.Text.Trim();
        await RefreshFeedAsync(string.IsNullOrWhiteSpace(path) ? null : path);
    }

    private async Task RefreshFeedAsync(string? path)
    {
        PageStatusText.Text = "Carico immagini...";
        ImageGridView.ItemsSource = null;
        ImagePager.ItemsSource = null;
        _items = [];
        try
        {
            var settings = AppSettingsStore.Load();
            var (loaded, status, libraryPath) = await ImageLibraryService.LoadAsync(settings, path);
            LibraryPathText.Text = string.IsNullOrWhiteSpace(libraryPath)
                ? "Hermes non ha ancora annunciato la cartella immagini."
                : libraryPath;
            if (!string.IsNullOrWhiteSpace(libraryPath) && string.IsNullOrWhiteSpace(LibraryPathBox.Text))
            {
                LibraryPathBox.Text = libraryPath;
            }
            _items = loaded.Select(record => new ImageGridItem
            {
                Record = record,
                ThumbUri = ImageLibraryService.ResolveImageUri(settings, record.MediaUrl),
                FullUri = ImageLibraryService.ResolveImageUri(settings, record.MediaUrl)
            }).ToList();
            ImageGridView.ItemsSource = _items;
            ImagePager.ItemsSource = _items;
            PageStatusText.Text = status;
            if (_items.Count > 0)
            {
                SelectIndex(0);
            }
            else
            {
                UpdateDetail(null);
            }
        }
        catch (Exception ex)
        {
            PageStatusText.Text = $"Errore feed immagini: {ex.Message}";
        }
    }

    private void ImageGridView_ItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is ImageGridItem item)
        {
            SelectIndex(_items.IndexOf(item));
        }
    }

    private void ImageGridView_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingSelection)
        {
            return;
        }
        if (ImageGridView.SelectedItem is ImageGridItem item)
        {
            SelectIndex(_items.IndexOf(item));
        }
    }

    private void ImagePager_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingSelection)
        {
            return;
        }
        SelectIndex(ImagePager.SelectedIndex);
    }

    private void SelectIndex(int index)
    {
        if (index < 0 || index >= _items.Count)
        {
            return;
        }
        _syncingSelection = true;
        try
        {
            ImageGridView.SelectedIndex = index;
            if (ImagePager.SelectedIndex != index)
            {
                ImagePager.SelectedIndex = index;
            }
        }
        finally
        {
            _syncingSelection = false;
        }
        UpdateDetail(_items[index]);
    }

    private void UpdateDetail(ImageGridItem? item)
    {
        if (item is null)
        {
            SelectedImageTitleText.Text = "Seleziona un'immagine";
            SelectedImageMetaText.Text = string.Empty;
            PagerCounterText.Text = "-";
            return;
        }
        var index = _items.IndexOf(item);
        SelectedImageTitleText.Text = item.Title;
        SelectedImageMetaText.Text = $"{item.Record.FileName} · {item.Meta} · {item.Record.ModifiedLabel}";
        PagerCounterText.Text = $"{index + 1} / {_items.Count}";
    }

    private void Prev_Click(object sender, RoutedEventArgs e)
    {
        if (_items.Count == 0)
        {
            return;
        }
        SelectIndex((ImagePager.SelectedIndex <= 0 ? _items.Count : ImagePager.SelectedIndex) - 1);
    }

    private void Next_Click(object sender, RoutedEventArgs e)
    {
        if (_items.Count == 0)
        {
            return;
        }
        SelectIndex((ImagePager.SelectedIndex + 1) % _items.Count);
    }

    private void Viewer_DoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if (sender is ScrollViewer scroller)
        {
            scroller.ZoomToFactor(1);
        }
    }

    private void FullScreen_Click(object sender, RoutedEventArgs e)
    {
        if (ImagePager.SelectedItem is not ImageGridItem item)
        {
            return;
        }
        ShowFullScreen(item);
    }

    private void ShowFullScreen(ImageGridItem item)
    {
        FullScreenImage.Source = new BitmapImage { UriSource = item.FullUri };
        FullScreenImageTitleText.Text = item.Title;
        FullScreenImageMetaText.Text = $"{item.Record.FileName} · {item.Meta}";
        var index = _items.IndexOf(item);
        FullScreenCounterText.Text = $"{index + 1} / {_items.Count}";
        FullScreenImageOverlay.Visibility = Visibility.Visible;
    }

    private void CloseFullScreen_Click(object sender, RoutedEventArgs e)
    {
        FullScreenImageOverlay.Visibility = Visibility.Collapsed;
        FullScreenImage.Source = null;
    }

    private void FullPrev_Click(object sender, RoutedEventArgs e)
    {
        if (_items.Count == 0)
        {
            return;
        }
        var next = (ImagePager.SelectedIndex <= 0 ? _items.Count : ImagePager.SelectedIndex) - 1;
        SelectIndex(next);
        ShowFullScreen(_items[next]);
    }

    private void FullNext_Click(object sender, RoutedEventArgs e)
    {
        if (_items.Count == 0)
        {
            return;
        }
        var next = (ImagePager.SelectedIndex + 1) % _items.Count;
        SelectIndex(next);
        ShowFullScreen(_items[next]);
    }

    private void ResetZoom_Click(object sender, RoutedEventArgs e) => FullScreenScroller.ZoomToFactor(1);

    private void CopyPath_Click(object sender, RoutedEventArgs e)
    {
        if (ImagePager.SelectedItem is not ImageGridItem item)
        {
            return;
        }
        var package = new DataPackage();
        package.SetText(string.IsNullOrWhiteSpace(item.Record.SourcePath) ? item.Record.FileName : item.Record.SourcePath);
        Clipboard.SetContent(package);
        PageStatusText.Text = $"Path copiato: {item.Record.FileName}";
    }
}
