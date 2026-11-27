using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shell;
using System.Windows.Threading;

namespace MonitorApp.UI;

/// <summary>
/// Non-activating Liquid Glass desktop notification banner anchored at the bottom-right work area.
/// Replaces legacy Win32 ShowBalloonTip so Monitor always renders crisp brand icons without
/// broken unpacked-app header icons or dated Win32 exclamation/info badges.
/// </summary>
public static class NotificationBanner
{
    private static readonly List<Window> ActiveBanners = [];
    private const int MaxVisibleBanners = 3;
    private const double BannerWidth = 364.0;
    private const double EdgeMargin = 16.0;
    private const double StackGap = 10.0;

    public static string InferToolBrandKey(string? title)
    {
        if (string.IsNullOrWhiteSpace(title)) return "app";
        if (title.Contains("Codex", StringComparison.OrdinalIgnoreCase)) return "codex";
        if (title.Contains("Claude", StringComparison.OrdinalIgnoreCase)) return "claude";
        if (title.Contains("Antigravity", StringComparison.OrdinalIgnoreCase)) return "antigravity";
        return "app";
    }

    public static void CloseAll()
    {
        foreach (var w in ActiveBanners.ToArray())
        {
            try { w.Close(); } catch { }
        }
        ActiveBanners.Clear();
    }

    public static void Show(string title, string body, Action? onActivate = null)
    {
        Application.Current?.Dispatcher.Invoke(() => ShowOnUiThread(title, body, onActivate));
    }

    private static void ShowOnUiThread(string title, string body, Action? onActivate)
    {
        while (ActiveBanners.Count >= MaxVisibleBanners)
        {
            var oldest = ActiveBanners[0];
            ActiveBanners.RemoveAt(0);
            try { oldest.Close(); } catch { }
        }

        var win = new Window
        {
            Title = "Monitor 提醒",
            Width = BannerWidth,
            SizeToContent = SizeToContent.Height,
            WindowStyle = WindowStyle.None,
            ResizeMode = ResizeMode.NoResize,
            ShowInTaskbar = false,
            ShowActivated = false,
            Topmost = true,
            WindowStartupLocation = WindowStartupLocation.Manual,
            UseLayoutRounding = true,
            SnapsToDevicePixels = true,
            Background = UiKit.CanvasBrush,
            Foreground = UiKit.TextBrush,
            FontFamily = UiKit.AppFont,
            Opacity = 0.0
        };

        WindowChrome.SetWindowChrome(win, new WindowChrome
        {
            CaptionHeight = 0,
            ResizeBorderThickness = new Thickness(0),
            GlassFrameThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            UseAeroCaptionButtons = false
        });

        win.SourceInitialized += (_, _) =>
        {
            if (DwmInterop.ApplyNonActivatingPopup(win, UiKit.IsDark))
                win.Background = UiKit.MicaCanvasBrush;
        };

        var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(6) };
        var dismissed = false;

        void Dismiss(bool invokeCallback)
        {
            if (dismissed) return;
            dismissed = true;
            timer.Stop();
            ActiveBanners.Remove(win);
            RepositionAll();
            try { win.Close(); } catch { }
            if (invokeCallback)
            {
                try { onActivate?.Invoke(); } catch { }
            }
        }

        timer.Tick += (_, _) => Dismiss(invokeCallback: false);
        win.Closed += (_, _) =>
        {
            timer.Stop();
            ActiveBanners.Remove(win);
            RepositionAll();
        };

        var cardBorder = BuildBannerVisual(
            title,
            body,
            onDismiss: () => Dismiss(invokeCallback: false),
            onActivate: onActivate is not null ? () => Dismiss(invokeCallback: true) : null,
            onHoverEnter: () => timer.Stop(),
            onHoverLeave: () => { if (!dismissed) timer.Start(); });

        win.Content = cardBorder;
        ActiveBanners.Add(win);

        win.Loaded += (_, _) =>
        {
            RepositionAll(animateWindow: win);
            timer.Start();
        };

        try
        {
            win.Show();
        }
        catch
        {
            ActiveBanners.Remove(win);
        }
    }

    public static Border BuildBannerVisual(
        string title,
        string body,
        Action? onDismiss = null,
        Action? onActivate = null,
        Action? onHoverEnter = null,
        Action? onHoverLeave = null)
    {
        var headerGrid = new Grid();
        headerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        headerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        headerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var appBadge = UiKit.BrandImage("app", 15);
        appBadge.Margin = new Thickness(0, 0, 7, 0);
        appBadge.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(appBadge, 0);
        headerGrid.Children.Add(appBadge);

        var appSourceLabel = new TextBlock
        {
            Text = "Monitor · 额度提醒",
            FontFamily = UiKit.AppFont,
            FontSize = 11.5,
            FontWeight = FontWeights.Medium,
            Foreground = UiKit.MutedBrush,
            VerticalAlignment = VerticalAlignment.Center
        };
        Grid.SetColumn(appSourceLabel, 1);
        headerGrid.Children.Add(appSourceLabel);

        var closeBtn = UiKit.IconButton("close", "关闭通知", () => onDismiss?.Invoke(), boxSize: 22, iconSize: 12);
        closeBtn.Margin = new Thickness(6, -2, -4, -2);
        Grid.SetColumn(closeBtn, 2);
        headerGrid.Children.Add(closeBtn);

        var bodyGrid = new Grid { Margin = new Thickness(0, 9, 0, 0) };
        bodyGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        bodyGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var brandKey = InferToolBrandKey(title);
        var brandPod = new Border
        {
            Width = 38,
            Height = 38,
            CornerRadius = new CornerRadius(UiKit.PodRadius),
            Background = UiKit.GlassTrackBrush,
            BorderBrush = UiKit.GlassSubtleRimBrush,
            BorderThickness = new Thickness(1),
            Margin = new Thickness(0, 1, 12, 0),
            VerticalAlignment = VerticalAlignment.Top,
            SnapsToDevicePixels = true,
            Child = UiKit.BrandImage(brandKey, 23)
        };
        Grid.SetColumn(brandPod, 0);
        bodyGrid.Children.Add(brandPod);

        var textCol = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        var titleBlock = UiKit.Heading(title, 14);
        titleBlock.LineHeight = 19;
        textCol.Children.Add(titleBlock);

        if (!string.IsNullOrWhiteSpace(body))
        {
            var bodyBlock = UiKit.Body(body, 12.4, UiKit.MutedBrush);
            bodyBlock.Margin = new Thickness(0, 3, 0, 0);
            textCol.Children.Add(bodyBlock);
        }
        Grid.SetColumn(textCol, 1);
        bodyGrid.Children.Add(textCol);

        var clickableBody = new Border
        {
            Background = Brushes.Transparent,
            Cursor = onActivate is not null ? Cursors.Hand : Cursors.Arrow,
            Child = bodyGrid
        };
        if (onActivate is not null)
        {
            clickableBody.MouseLeftButtonUp += (_, e) =>
            {
                e.Handled = true;
                onActivate();
            };
        }

        var innerStack = new StackPanel { Margin = new Thickness(15, 11, 15, 14) };
        innerStack.Children.Add(headerGrid);
        innerStack.Children.Add(clickableBody);

        var frostedVeil = new Border
        {
            Background = new SolidColorBrush(UiKit.IsDark
                ? Color.FromArgb(228, 24, 31, 40)
                : Color.FromArgb(234, 248, 251, 255)),
            Child = innerStack
        };

        var cardBorder = new Border
        {
            Width = BannerWidth,
            CornerRadius = new CornerRadius(8),
            ClipToBounds = true,
            Background = UiKit.SurfaceBrush,
            BorderBrush = UiKit.GlassRimBrush,
            BorderThickness = new Thickness(1),
            Child = UiKit.AmbientStage(frostedVeil, compact: true)
        };

        cardBorder.MouseEnter += (_, _) =>
        {
            onHoverEnter?.Invoke();
            cardBorder.BorderBrush = UiKit.AccentBrush;
        };
        cardBorder.MouseLeave += (_, _) =>
        {
            cardBorder.BorderBrush = UiKit.GlassRimBrush;
            onHoverLeave?.Invoke();
        };
        return cardBorder;
    }

    private static void RepositionAll(Window? animateWindow = null)
    {
        var wa = SystemParameters.WorkArea;
        var currentBottom = wa.Bottom - EdgeMargin;

        // Newest banner sits closest to the bottom-right corner; older ones stack cleanly above it.
        for (var i = ActiveBanners.Count - 1; i >= 0; i--)
        {
            var w = ActiveBanners[i];
            var h = w.ActualHeight > 0 ? w.ActualHeight : 96.0;
            var targetLeft = Math.Max(wa.Left + EdgeMargin, wa.Right - BannerWidth - EdgeMargin);
            var targetTop = Math.Max(wa.Top + EdgeMargin, currentBottom - h);
            currentBottom = targetTop - StackGap;

            w.Left = targetLeft;
            if (ReferenceEquals(w, animateWindow))
            {
                var startTop = targetTop + 10.0;
                w.Top = startTop;
                var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
                w.BeginAnimation(Window.TopProperty, new DoubleAnimation(startTop, targetTop, TimeSpan.FromMilliseconds(180)) { EasingFunction = ease });
                w.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0.0, 1.0, TimeSpan.FromMilliseconds(170)) { EasingFunction = ease });
            }
            else
            {
                w.BeginAnimation(Window.TopProperty, null);
                w.Top = targetTop;
                w.Opacity = 1.0;
            }
        }
    }
}
