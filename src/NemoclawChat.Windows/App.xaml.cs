using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using NemoclawChat_Windows.Services;

[assembly: System.Diagnostics.CodeAnalysis.SuppressMessage(
    "Naming",
    "CA1707:Identifiers should not contain underscores",
    Scope = "namespace",
    Target = "~N:NemoclawChat_Windows",
    Justification = "Namespace storico vincolato ai nomi generati XAML e al package Windows esistente.")]

namespace NemoclawChat_Windows;

/// <summary>
/// Provides application-specific behavior to supplement the default Application class.
/// </summary>
public partial class App : Application
{
    private Window? _window;
    public static Window? MainWindow { get; private set; }

    /// <summary>
    /// Initializes the singleton application object.  This is the first line of authored code
    /// executed, and as such is the logical equivalent of main() or WinMain().
    /// </summary>
    public App()
    {
        InitializeComponent();
        AppDataMigration.Run();
        FileLogger.Initialize();
        ApplyHighContrastOverrides();
        UnhandledException += OnUnhandledException;
        System.AppDomain.CurrentDomain.UnhandledException += OnDomainUnhandledException;
        System.Threading.Tasks.TaskScheduler.UnobservedTaskException += OnUnobservedTaskException;
    }

    private static void ApplyHighContrastOverrides()
    {
        var settings = new Windows.UI.ViewManagement.AccessibilitySettings();
        if (!settings.HighContrast) return;
        Current.Resources["MutedTextBrush"] = new SolidColorBrush(Microsoft.UI.Colors.White);
        Current.Resources["FaintTextBrush"] = new SolidColorBrush(Microsoft.UI.Colors.White);
    }

    private void OnUnhandledException(object sender, Microsoft.UI.Xaml.UnhandledExceptionEventArgs e)
    {
        System.Diagnostics.Trace.WriteLine($"[App] unhandled {e.Exception.GetType().FullName}: {e.Message}\n{e.Exception.StackTrace}");
        e.Handled = false;
    }

    private static void OnDomainUnhandledException(object sender, System.UnhandledExceptionEventArgs e)
    {
        System.Diagnostics.Trace.WriteLine($"[App] domain-unhandled terminating={e.IsTerminating}: {e.ExceptionObject}");
    }

    private static void OnUnobservedTaskException(object? sender, System.Threading.Tasks.UnobservedTaskExceptionEventArgs e)
    {
        System.Diagnostics.Trace.WriteLine($"[App] unobserved-task {e.Exception.Flatten()}");
        e.SetObserved();
    }

    /// <summary>
    /// Invoked when the application is launched.
    /// </summary>
    /// <param name="args">Details about the launch request and process.</param>
    protected override void OnLaunched(Microsoft.UI.Xaml.LaunchActivatedEventArgs args)
    {
        _window = new MainWindow();
        MainWindow = _window;
        _window.Activate();
    }
}
