using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using NemoclawChat_Windows.Services;
using System.IO;
using System.Net.Http;
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
    private const string DefaultImageLibraryPath = "~/.hermes/hub_uploads/denudate";
    private List<ImageGridItem> _items = [];
    private List<ImageBrowseFolder> _folders = [];
    private string? _currentPath;
    private string? _parentPath;
    private bool _syncingSelection;

    public ImagesPage()
    {
        InitializeComponent();
        Loaded += ImagesPage_Loaded;
    }

    private static string StartPath(AppSettings settings)
    {
        var configured = settings.ImageLibraryPath.Trim();
        return string.IsNullOrWhiteSpace(configured) ? DefaultImageLibraryPath : configured;
    }

    private async void ImagesPage_Loaded(object sender, RoutedEventArgs e)
    {
        Loaded -= ImagesPage_Loaded;
        var settings = AppSettingsStore.Load();
        if (!string.IsNullOrWhiteSpace(settings.ImageLibraryPath))
        {
            LibraryPathBox.Text = settings.ImageLibraryPath;
        }
        await BrowseAsync(StartPath(settings));
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await BrowseAsync(_currentPath ?? StartPath(AppSettingsStore.Load()));

    private async void LoadPath_Click(object sender, RoutedEventArgs e)
    {
        var path = LibraryPathBox.Text.Trim();
        await BrowseAsync(string.IsNullOrWhiteSpace(path) ? StartPath(AppSettingsStore.Load()) : path);
    }

    private async void NewImage_Click(object sender, RoutedEventArgs e)
    {
        var settings = AppSettingsStore.Load();
        var folder = string.IsNullOrWhiteSpace(settings.ImageLibraryPath) ? "quella del server" : settings.ImageLibraryPath;
        Frame.Navigate(typeof(HomePage), new HomeNavigationRequest(Prompt:
            $"Modalita Immagini Hermes Hub. Usa la cartella immagini monitorata del PC Hermes: {folder}. " +
            "Crea o modifica un'immagine e salva sempre il file finale in quella cartella, cosi appare automaticamente nella sezione Immagini. Richiesta: "));
    }

    /// <summary>
    /// Sfoglia come Android (Home/Su/breadcrumb/cartelle, fail-soft: in
    /// errore tiene la posizione buona e non ci si incastra mai).
    /// </summary>
    private async Task BrowseAsync(string? path)
    {
        PageStatusText.Text = "Sfoglio cartella immagini...";
        try
        {
            var settings = AppSettingsStore.Load();
            var (current, parent, folders, items, status, ok) = await ImageLibraryService.BrowseAsync(settings, path);
            if (!ok)
            {
                PageStatusText.Text = status;
                return;
            }
            _currentPath = current;
            _parentPath = parent;
            _folders = folders.ToList();
            FolderListView.ItemsSource = _folders;
            LibraryPathText.Text = string.IsNullOrWhiteSpace(current)
                ? "Hermes non ha ancora annunciato la cartella immagini."
                : current;
            if (!string.IsNullOrWhiteSpace(current) && string.IsNullOrWhiteSpace(LibraryPathBox.Text))
            {
                LibraryPathBox.Text = current;
            }
            _items = items.Select(record => new ImageGridItem
            {
                Record = record,
                ThumbUri = ImageLibraryService.ResolveImageUri(settings, record.MediaUrl),
                FullUri = ImageLibraryService.ResolveImageUri(settings, record.MediaUrl)
            }).ToList();
            ImageGridView.ItemsSource = _items;
            ImagePager.ItemsSource = _items;
            FullScreenImageOverlay.Visibility = Visibility.Collapsed;
            FullScreenImage.Source = null;
            PageStatusText.Text = status;
            RebuildBreadcrumb();
            var empty = _items.Count == 0 && _folders.Count == 0;
            EmptyHintPanel.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
            EmptyPathText.Text = current;
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

    private void RebuildBreadcrumb()
    {
        BreadcrumbPanel.Children.Clear();
        var start = StartPath(AppSettingsStore.Load());
        AddCrumb("Home", start, _currentPath == start);
        if (!string.IsNullOrWhiteSpace(_parentPath))
        {
            AddSeparator();
            AddCrumb("↑ Su", _parentPath, false);
        }
        if (!string.IsNullOrWhiteSpace(_currentPath))
        {
            foreach (var (label, segmentPath) in BrowseSegments(_currentPath).TakeLast(3))
            {
                AddSeparator();
                AddCrumb(label, segmentPath, segmentPath == _currentPath);
            }
        }
    }

    private void AddCrumb(string label, string? path, bool current)
    {
        var button = new Button
        {
            Content = label,
            Style = (Style)Application.Current.Resources["TextButtonStyle"]
        };
        if (current)
        {
            button.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
            button.Foreground = new SolidColorBrush(Microsoft.UI.Colors.White);
        }
        var target = path;
        button.Click += async (_, _) => await BrowseAsync(target);
        BreadcrumbPanel.Children.Add(button);
    }

    private void AddSeparator()
    {
        BreadcrumbPanel.Children.Add(new TextBlock
        {
            Text = "·",
            Foreground = (Brush)Application.Current.Resources["FaintTextBrush"],
            VerticalAlignment = VerticalAlignment.Center
        });
    }

    private static List<(string Label, string Path)> BrowseSegments(string currentPath)
    {
        var normalized = currentPath.Trim().Replace('\\', '/').TrimEnd('/');
        if (string.IsNullOrEmpty(normalized)) return [];
        var absolute = normalized.StartsWith('/');
        var parts = normalized.Split('/', StringSplitOptions.RemoveEmptyEntries);
        var outSegments = new List<(string, string)>();
        var acc = string.Empty;
        foreach (var part in parts)
        {
            acc = string.IsNullOrEmpty(acc) ? (absolute ? "/" + part : part) : acc + "/" + part;
            outSegments.Add((part, acc));
        }
        return outSegments;
    }

    private void FolderListView_ItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is ImageBrowseFolder folder)
        {
            _ = BrowseAsync(folder.Path);
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

    private async void Download_Click(object sender, RoutedEventArgs e)
    {
        if (ImagePager.SelectedItem is not ImageGridItem item)
        {
            return;
        }
        PageStatusText.Text = $"Scarico {item.Record.FileName}...";
        try
        {
            // hub_token gia nell'URL (ResolveImageUri): niente header, come i byte del viewer.
            using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(2) };
            var bytes = await http.GetByteArrayAsync(item.FullUri);
            var downloads = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
            Directory.CreateDirectory(downloads);
            var name = string.IsNullOrWhiteSpace(item.Record.FileName) ? "hermes-immagine" : item.Record.FileName;
            foreach (var invalid in Path.GetInvalidFileNameChars()) name = name.Replace(invalid, '_');
            var destination = Path.Combine(downloads, name);
            if (File.Exists(destination))
            {
                destination = Path.Combine(downloads, $"{Path.GetFileNameWithoutExtension(name)}-{DateTime.Now:yyyyMMdd-HHmmss}{Path.GetExtension(name)}");
            }
            await File.WriteAllBytesAsync(destination, bytes);
            PageStatusText.Text = $"Salvata in Download: {Path.GetFileName(destination)}";
        }
        catch (Exception ex)
        {
            PageStatusText.Text = $"Download fallito: {ex.Message}";
        }
    }

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
