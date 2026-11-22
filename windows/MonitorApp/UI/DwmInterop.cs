using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Shell;
using Microsoft.Win32;

namespace MonitorApp.UI;

public static class DwmInterop
{
    private const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private const int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private const int DWMWA_SYSTEMBACKDROP_TYPE = 38;
    private const int DWMWCP_ROUND = 2;
    private const int DWMSBT_MAINWINDOW = 2;      // Windows 11 Mica
    private const int DWMSBT_TRANSIENTWINDOW = 3; // Windows 11 Acrylic for floating HUD/dialogs

    [DllImport("dwmapi.dll", PreserveSig = true)]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);

    public static bool IsSystemDarkTheme()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            if (key?.GetValue("AppsUseLightTheme") is int v)
                return v == 0;
        }
        catch { }
        return false;
    }

    public static bool ApplyWindowStyling(Window window, bool dark, bool transientAcrylic = false)
    {
        try
        {
            var hwnd = new WindowInteropHelper(window).Handle;
            if (hwnd == IntPtr.Zero) return false;

            var darkVal = dark ? 1 : 0;
            _ = DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, ref darkVal, sizeof(int));

            var corner = DWMWCP_ROUND;
            _ = DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, ref corner, sizeof(int));

            // Enable native Windows 11 22H2+ DWM Mica / Transient Acrylic beneath our Liquid Glass layers
            if (Environment.OSVersion.Version.Build >= 22621)
            {
                var backdrop = transientAcrylic ? DWMSBT_TRANSIENTWINDOW : DWMSBT_MAINWINDOW;
                if (DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, ref backdrop, sizeof(int)) == 0
                    && HwndSource.FromHwnd(hwnd)?.CompositionTarget is { } target)
                {
                    target.BackgroundColor = Colors.Transparent;
                    if (WindowChrome.GetWindowChrome(window) is { } chrome)
                    {
                        chrome.GlassFrameThickness = new Thickness(-1);
                    }
                    return true;
                }
            }
        }
        catch { }
        return false;
    }
}
