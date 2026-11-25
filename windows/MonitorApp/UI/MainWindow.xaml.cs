using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Shell;
using System.Windows.Threading;
using Microsoft.Win32;
using MonitorApp.Core;

namespace MonitorApp.UI;

public partial class MainWindow : Window
{
    private enum NavPage { Tasks, Devices, Usage, Account }

    private readonly MonitorController _controller = new();
    private readonly DispatcherTimer _timer;
    private readonly DispatcherTimer _secondTicker;
    private System.Windows.Forms.NotifyIcon? _trayIcon;
    private System.Drawing.Icon? _trayIconSource;
    private bool _allowExit;

    private NavPage _page = NavPage.Tasks;
    private bool _compactMode;
    private int _compactCardIndex;
    private int _compactSlideDirection;
    private DateTimeOffset _lastCompactWheelAt = DateTimeOffset.MinValue;
    private bool _dwmBackdropActive;
    private double _savedLeft = double.NaN;
    private double _savedTop = double.NaN;
    private double _savedWidth = 1180;
    private double _savedHeight = 768;

    private BitmapSource? _customWallpaper;
    private BingWallpaperInfo? _bingInfo;
    private bool _bingFetching;
    private double _accountScrollOffset;

    private bool _editingServer;
    private string _serverDraft = "";

    private string? _selectedTaskId;
    private string? _selectedDeviceId;
    private TaskDetailController? _taskDetail;

    // Per-tool Usage state matching macOS UsageView.swift
    private readonly Dictionary<string, string> _selectedQuotaSource = new();
    private readonly Dictionary<string, DateOnly> _selectedUsageWeek = new();
    private readonly Dictionary<string, DateOnly> _selectedUsageDay = new();

    // Persistent controls so typing is never interrupted by background polls
    private bool? _mountedConnectedState;
    private bool? _mountedCompactState;
    private NavPage? _mountedPage;
    private ColumnDefinition? _sidebarColDef;
    private Grid? _sidebarRailBorder;
    private DockPanel? _sidebarDockPanel;
    private StackPanel? _sidebarNavHost;
    private StackPanel? _sidebarBottomHost;
    private ColumnDefinition? _taskListColDef;
    private MainShellPlan? _lastMainPlan;
    private TextBox? _taskSearchBox;
    private TextBlock? _searchMatchSummary;
    private ContentControl? _pendingEntryHost;
    private StackPanel? _taskFilterHost;
    private StackPanel? _taskListHost;
    private ContentControl? _taskDetailRightHost;
    private string? _mountedDetailTaskId;
    private string _mountedTaskCardsSignature = "";
    private StackPanel? _taskDetailHeaderHost;
    private StackPanel? _taskDetailCardsHost;
    private StackPanel? _taskDetailInspectorHost;
    private ColumnDefinition? _taskDetailInspectorDivCol;
    private ColumnDefinition? _taskDetailInspectorCol;
    private WrapPanel? _replyQuickChipsHost;
    private string _mountedQuickChipsSignature = "";
    private bool _taskInfoAccordionExpanded;
    private Button? _replyModelCapsuleBtn;
    private TextBlock? _replyStatusText;
    private Border? _replyReceiptBadge;
    private TextBlock? _replyReceiptText;
    private TextBlock? _replyAppliedText;
    private TextBox? _replyInputBox;
    private Button? _replySendBtn;
    private ContentControl? _pageBodyHost;
    private readonly Dictionary<string, Dictionary<string, (HashSet<string> Choices, string FreeText)>> _attentionDrafts = new();

    public MainWindow()
    {
        InitializeComponent();

        SyncTheme(applyDwm: false);

        _serverDraft = !string.IsNullOrEmpty(_controller.Origin)
            ? _controller.Origin
            : (ServerOrigin.SuggestedOrigin() ?? "");

        SetupSystemTray();

        _controller.StateChanged += OnControllerStateChanged;
        _controller.LoginCompleted += () => Dispatcher.Invoke(RestoreMainWindow);
        _controller.QuotaAlertTriggered += OnQuotaAlertTriggered;
        SystemEvents.UserPreferenceChanged += OnSystemPreferenceChanged;

        _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(3500) };
        _timer.Tick += async (_, _) => await OnHeartbeatTickAsync();
        _timer.Start();

        _secondTicker = new DispatcherTimer { Interval = TimeSpan.FromSeconds(30) };
        _secondTicker.Tick += async (_, _) =>
        {
            if (!_controller.Connected) return;
            if (_page == NavPage.Usage)
                await _controller.RefreshUsageAsync();
            else if (_compactMode)
                RenderAll(forceShell: false);
        };
        _secondTicker.Start();

        SourceInitialized += (_, _) =>
        {
            ApplyMaximizedFramePadding();
            _dwmBackdropActive = DwmInterop.ApplyWindowStyling(this, UiKit.IsDark, transientAcrylic: _compactMode);
            Background = _dwmBackdropActive ? UiKit.MicaCanvasBrush : UiKit.CanvasBrush;
        };
        StateChanged += (_, _) =>
        {
            ApplyMaximizedFramePadding();
            RebuildTitleBar();
        };
        SizeChanged += (_, _) =>
        {
            if (!_controller.Connected) return;
            if (_compactMode)
            {
                RebuildTitleBar();
                RootHost.Content = BuildCompactView();
            }
            else
            {
                ApplyResponsiveMainShellInPlace();
            }
        };
        RootHost.SizeChanged += (_, e) =>
        {
            if (!_controller.Connected || !_compactMode) return;
            if (Math.Abs(e.NewSize.Width - e.PreviousSize.Width) >= 0.5
                || Math.Abs(e.NewSize.Height - e.PreviousSize.Height) >= 0.5)
            {
                RootHost.Content = BuildCompactView();
            }
        };
        PreviewKeyDown += OnWindowPreviewKeyDown;

        Loaded += async (_, _) =>
        {
            _ = RefreshBingWallpaperIfNeededAsync(force: false);
            RenderAll(forceShell: true);
            if (_controller.Connected)
            {
                await Task.WhenAll(
                    _controller.RefreshWorkbenchAsync(),
                    _controller.RefreshAccountAsync(),
                    _controller.RefreshUsageAsync(),
                    _controller.Collector.RefreshAsync(_controller.Origin));
            }
            else if (!string.IsNullOrEmpty(_controller.Origin) && !_controller.PendingBrowserLogin)
            {
                await _controller.StartQrLoginAsync();
            }
        };
    }

    private void SyncTheme(bool applyDwm = true, bool rebuildBackdrop = true)
    {
        var ui = _controller.ReadUiSettings();
        var dark = ui.ThemeMode switch
        {
            "dark" => true,
            "light" => false,
            _ => DwmInterop.IsSystemDarkTheme()
        };

        BitmapSource? activeWp = null;
        if (ui.BackgroundMode == "custom")
        {
            _customWallpaper ??= WallpaperService.LoadCustomWallpaper();
            activeWp = _customWallpaper;
        }
        else if (ui.BackgroundMode == "bing")
        {
            _bingInfo ??= WallpaperService.TryReadCachedBing();
            activeWp = _bingInfo?.Image;
        }

        UiKit.ApplyTheme(
            dark,
            ui.BackgroundMode,
            ui.WallpaperStyle,
            activeWp,
            ui.GetEffectiveWallpaperOpacity(),
            ui.GetEffectiveWallpaperBlur());

        if (rebuildBackdrop && BackdropHost is not null)
            UiKit.PopulateBackdrop(BackdropHost, compact: _compactMode);

        if (applyDwm)
            _dwmBackdropActive = DwmInterop.ApplyWindowStyling(this, dark, transientAcrylic: _compactMode);
        Background = _dwmBackdropActive ? UiKit.MicaCanvasBrush : UiKit.CanvasBrush;
    }

    private async Task RefreshBingWallpaperIfNeededAsync(bool force)
    {
        var ui = _controller.ReadUiSettings();
        if (ui.BackgroundMode != "bing" || _bingFetching) return;
        _bingFetching = true;
        try
        {
            var info = await WallpaperService.LoadBingWallpaperAsync(forceRefresh: force);
            _bingInfo = info;
            if (_controller.ReadUiSettings().BackgroundMode == "bing")
            {
                SyncTheme(applyDwm: false);
                RenderAll(forceShell: true);
            }
        }
        finally
        {
            _bingFetching = false;
        }
    }

    private void OnSystemPreferenceChanged(object sender, UserPreferenceChangedEventArgs e)
    {
        if (e.Category is not (UserPreferenceCategory.General or UserPreferenceCategory.Color or UserPreferenceCategory.VisualStyle))
            return;
        Dispatcher.InvokeAsync(() =>
        {
            if (_controller.ReadUiSettings().ThemeMode != "system") return;
            var prev = UiKit.IsDark;
            SyncTheme(applyDwm: true);
            if (prev != UiKit.IsDark)
                RenderAll(forceShell: true);
        });
    }

    private void ApplyMaximizedFramePadding()
    {
        if (WindowState == WindowState.Maximized)
        {
            var frame = SystemParameters.WindowResizeBorderThickness;
            OuterFrame.Padding = new Thickness(frame.Left + 4, frame.Top + 4, frame.Right + 4, frame.Bottom + 4);
        }
        else
        {
            OuterFrame.Padding = new Thickness(0);
        }
    }

    private async void OnWindowPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (!_controller.Connected) return;
        if (_compactMode)
        {
            if (e.Key is Key.Up or Key.PageUp)
            {
                e.Handled = true;
                StepCompactCard(-1);
                return;
            }
            if (e.Key is Key.Down or Key.PageDown)
            {
                e.Handled = true;
                StepCompactCard(1);
                return;
            }
        }
        var ctrl = (Keyboard.Modifiers & ModifierKeys.Control) == ModifierKeys.Control;
        if (ctrl && e.Key == Key.F && !_compactMode && _page == NavPage.Tasks && _taskSearchBox is not null)
        {
            e.Handled = true;
            _taskSearchBox.Focus();
            _taskSearchBox.SelectAll();
            return;
        }
        if (e.Key == Key.F5 || (ctrl && e.Key == Key.R))
        {
            e.Handled = true;
            await _controller.RefreshWorkbenchAsync();
            if (_page == NavPage.Usage || _compactMode) await _controller.RefreshUsageAsync();
            if (_page == NavPage.Account) await _controller.RefreshAccountAsync();
        }
    }

    private void StepCompactCard(int delta)
    {
        if (!_compactMode) return;
        _compactCardIndex += delta;
        _compactSlideDirection = delta < 0 ? -1 : 1;
        RootHost.Content = BuildCompactView();
        _compactSlideDirection = 0;
    }

    private void JumpCompactCard(int targetIndex)
    {
        if (!_compactMode || targetIndex == _compactCardIndex) return;
        _compactSlideDirection = targetIndex < _compactCardIndex ? -1 : 1;
        _compactCardIndex = targetIndex;
        RootHost.Content = BuildCompactView();
        _compactSlideDirection = 0;
    }

    // ==================== LIQUID GLASS TITLEBAR ====================

    private void RebuildTitleBar()
    {
        var barH = _compactMode ? 34.0 : 40.0;
        TitleBarHost.Height = barH;
        TitleBarHost.Background = UiKit.GlassHeaderBrush;
        TitleBarHost.BorderThickness = new Thickness(0);
        if (WindowChrome.GetWindowChrome(this) is { } chrome && Math.Abs(chrome.CaptionHeight - barH) > 0.5)
            chrome.CaptionHeight = barH;

        // Strict 2-column [*, Auto] Grid guarantees left identity and right controls NEVER collide or overlap at any width
        var barGrid = new Grid();
        barGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        barGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var effectiveW = ActualWidth > 180 ? ActualWidth : Width;
        var compactSideInset = effectiveW < 340 ? 10.0 : 12.0;

        var leftGrid = new Grid
        {
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(_compactMode ? compactSideInset : 16, 0, 8, 0),
            ClipToBounds = true
        };
        leftGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        leftGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        leftGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var appIcon = UiKit.BrandImage("app", _compactMode ? 15.5 : 19);
        Grid.SetColumn(appIcon, 0);
        leftGrid.Children.Add(appIcon);

        var appTitle = new TextBlock
        {
            Text = "Monitor",
            FontFamily = UiKit.AppFont,
            FontSize = _compactMode ? 12.2 : 13,
            FontWeight = FontWeights.SemiBold,
            Foreground = UiKit.TextBrush,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(6, 0, 0, 0)
        };
        Grid.SetColumn(appTitle, 1);
        leftGrid.Children.Add(appTitle);

        if (_compactMode && _controller.Connected)
        {
            var online = _controller.IsFresh && _controller.OnlineDeviceCount > 0;
            var statusTip = _controller.IsFresh ? $"{_controller.OnlineDeviceCount} 台电脑在线" : "等待同步";

            var statusRow = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                HorizontalAlignment = HorizontalAlignment.Left,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(7, 0, 0, 0),
                Background = Brushes.Transparent,
                ToolTip = statusTip
            };
            WindowChrome.SetIsHitTestVisibleInChrome(statusRow, true);
            statusRow.Children.Add(new Border
            {
                Width = 6,
                Height = 6,
                CornerRadius = new CornerRadius(3),
                Background = online ? UiKit.GreenBrush : UiKit.MutedBrush,
                VerticalAlignment = VerticalAlignment.Center
            });
            if (effectiveW >= 348)
            {
                statusRow.Children.Add(new TextBlock
                {
                    Text = _controller.IsFresh ? $"{_controller.OnlineDeviceCount} 在线" : "待同步",
                    FontFamily = UiKit.AppFont,
                    FontSize = 10.8,
                    FontWeight = FontWeights.Medium,
                    Foreground = UiKit.MutedBrush,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(4.5, 0, 0, 0)
                });
            }
            Grid.SetColumn(statusRow, 2);
            leftGrid.Children.Add(statusRow);
        }

        Grid.SetColumn(leftGrid, 0);
        barGrid.Children.Add(leftGrid);

        // Right: Cohesive Liquid Glass Action Capsule + Mac-Inspired 3-Color Liquid Glass Window Controls
        var right = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, _compactMode ? compactSideInset : 14, 0)
        };
        WindowChrome.SetIsHitTestVisibleInChrome(right, true);

        if (_controller.Connected)
        {
            var quickRow = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                VerticalAlignment = VerticalAlignment.Center
            };

            if (_compactMode)
            {
                var pinBtn = UiKit.IconButton("pin", Topmost ? "取消窗口置顶" : "窗口置顶", () =>
                {
                    Topmost = !Topmost;
                    RebuildTitleBar();
                }, selected: Topmost, boxSize: 20, iconSize: 12.2, highContrast: true, inCapsule: true);
                pinBtn.Margin = new Thickness(0, 0, 1.5, 0);
                quickRow.Children.Add(pinBtn);

                var cfgBtn = UiKit.IconButton("settings", "调整小窗尺寸与显示内容", () =>
                    Dialogs.ShowCompactWindowSettings(this, _controller, ApplyCompactSettingsLive), boxSize: 20, iconSize: 12.2, highContrast: true, inCapsule: true);
                cfgBtn.Margin = new Thickness(0, 0, 1.5, 0);
                quickRow.Children.Add(cfgBtn);

                var refBtn = UiKit.IconButton("refresh", "立即同步刷新", async () =>
                {
                    await _controller.RefreshWorkbenchAsync();
                    await _controller.RefreshUsageAsync();
                }, boxSize: 20, iconSize: 12.2, highContrast: true, inCapsule: true);
                quickRow.Children.Add(refBtn);
            }
            else
            {
                var miniBtn = UiKit.IconButton("mini", "切换桌面精简小窗", ToggleCompactMode, boxSize: 24, iconSize: 13.8, highContrast: true, inCapsule: true);
                miniBtn.Margin = new Thickness(0, 0, 2, 0);
                quickRow.Children.Add(miniBtn);

                var refBtn = UiKit.IconButton("refresh", "立即同步刷新 (F5)", async () =>
                {
                    await _controller.RefreshWorkbenchAsync();
                    if (_page == NavPage.Usage) await _controller.RefreshUsageAsync();
                    if (_page == NavPage.Account) await _controller.RefreshAccountAsync();
                }, boxSize: 24, iconSize: 13.8, highContrast: true, inCapsule: true);
                refBtn.Margin = new Thickness(0, 0, 2, 0);
                quickRow.Children.Add(refBtn);

                quickRow.Children.Add(UiKit.IconButton("help", "查看当前页面说明", ShowCurrentPageHelp, boxSize: 24, iconSize: 13.8, highContrast: true, inCapsule: true));
            }

            var quickCapsule = new Border
            {
                Height = _compactMode ? 24.0 : 28.0,
                Background = UiKit.GlassChromeCapsuleBrush,
                BorderBrush = UiKit.GlassChromeCapsuleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(_compactMode ? 6.5 : 7.0),
                Padding = new Thickness(_compactMode ? 2.5 : 3.0, 0, _compactMode ? 2.5 : 3.0, 0),
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, _compactMode ? 5.5 : 8, 0),
                SnapsToDevicePixels = true,
                Child = quickRow
            };
            right.Children.Add(quickCapsule);
        }

        var macControls = UiKit.MacWindowControlsCapsule(
            onMinimize: () => WindowState = WindowState.Minimized,
            onZoomOrRestore: _compactMode
                ? ToggleCompactMode
                : () => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized,
            onClose: Close,
            isMaximized: !_compactMode && WindowState == WindowState.Maximized,
            compact: _compactMode,
            zoomTooltip: _compactMode ? "展开完整工作台" : null);
        right.Children.Add(macControls);

        Grid.SetColumn(right, 1);
        barGrid.Children.Add(right);

        TitleBarHost.Child = barGrid;
    }

    private void ShowCurrentPageHelp()
    {
        var (title, text) = _page switch
        {
            NavPage.Tasks => ("工作台说明",
                "• 「进行中」展示正在运行、等待批准和需要关注的错误；归档只整理列表，不会停止电脑上的原会话。\n\n" +
                "• 支持查看最终结果、SHA-256 完整性校验下载配套文件、调整下轮模型与思考强度，以及远程批准单轮操作。"),
            NavPage.Devices => ("电脑连接说明",
                "• 每台 Windows 或 Mac 电脑配对一次后，任务状态与本地 Token 会自动汇总到同一账号工作台。\n\n" +
                "• 连接程序仅按本机设定的同步范围工作，不会读取或复制 AI 工具的账号密码。"),
            NavPage.Usage => ("用量与官方额度说明",
                "• 「官方额度」展示服务方真实返回的剩余比例与重置时间，不同来源电脑可能登录不同账号，不进行混合推算。\n\n" +
                "• 「本地 Token」按北京时间（周一至周日）展示最近 90 天去重记录与模型占比，未提供归属的计入「其他模型」。"),
            _ => ("账号与安全说明",
                "• 原生桌面端使用 Windows DPAPI 当前用户加密凭据存储，并按服务器根地址严格隔离。\n\n" +
                "• 可在「已登录设备」随时撤销任意手机、桌面端或浏览器连接。")
        };
        Dialogs.ShowMessage(this, title, text);
    }

    // ==================== SYSTEM TRAY ====================

    private void SetupSystemTray()
    {
        try
        {
            var menu = new System.Windows.Forms.ContextMenuStrip();
            menu.Items.Add("打开工作台", null, (_, _) => Dispatcher.Invoke(RestoreMainWindow));
            menu.Items.Add("切换精简小窗", null, (_, _) => Dispatcher.Invoke(ToggleCompactMode));
            menu.Items.Add("查看官方额度与 Token", null, (_, _) => Dispatcher.Invoke(() =>
            {
                RestoreMainWindow();
                if (_compactMode) ToggleCompactMode();
                SwitchPage(NavPage.Usage);
            }));
            menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
            menu.Items.Add("退出 Monitor", null, (_, _) => Dispatcher.Invoke(() =>
            {
                _allowExit = true;
                Close();
            }));

            var streamInfo = Application.GetResourceStream(new Uri("pack://application:,,,/Resources/AppIcon.ico"));
            if (streamInfo?.Stream is { } s)
            {
                using (s)
                {
                    _trayIconSource = new System.Drawing.Icon(s);
                }
            }

            _trayIcon = new System.Windows.Forms.NotifyIcon
            {
                Text = "Monitor · AI 工作台",
                Icon = _trayIconSource ?? System.Drawing.SystemIcons.Application,
                ContextMenuStrip = menu,
                Visible = true
            };
            _trayIcon.DoubleClick += (_, _) => Dispatcher.Invoke(RestoreMainWindow);
            _trayIcon.BalloonTipClicked += (_, _) => Dispatcher.Invoke(() =>
            {
                RestoreMainWindow();
                if (_compactMode) ToggleCompactMode();
                SwitchPage(NavPage.Usage);
            });
        }
        catch { }
    }

    private void OnQuotaAlertTriggered(string title, string body)
    {
        try { _trayIcon?.ShowBalloonTip(6000, title, body, System.Windows.Forms.ToolTipIcon.Info); }
        catch { }
    }

    private void RestoreMainWindow()
    {
        Show();
        if (WindowState == WindowState.Minimized)
            WindowState = WindowState.Normal;
        Activate();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!_allowExit && _trayIcon is not null && _controller.ReadUiSettings().CloseToTray)
        {
            e.Cancel = true;
            Hide();
            return;
        }
        SystemEvents.UserPreferenceChanged -= OnSystemPreferenceChanged;
        _timer.Stop();
        _secondTicker.Stop();
        _taskDetail?.Dispose();
        if (_trayIcon is not null)
        {
            _trayIcon.Visible = false;
            _trayIcon.Dispose();
        }
        _trayIconSource?.Dispose();
        _controller.Dispose();
        base.OnClosing(e);
    }

    private async Task OnHeartbeatTickAsync()
    {
        if (!_controller.Connected) return;
        await _controller.RefreshWorkbenchAsync();
    }

    private void OnControllerStateChanged()
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.Invoke(OnControllerStateChanged);
            return;
        }
        if (!string.IsNullOrEmpty(_controller.Notice))
        {
            var msg = _controller.Notice!;
            _controller.Notice = null;
            Dialogs.ShowMessage(this, "Monitor", msg);
        }
        _taskDetail?.ObserveWorkbench();
        RenderAll(forceShell: false);
    }

    private void SwitchPage(NavPage next)
    {
        if (_page == next && _selectedDeviceId is null) return;
        _page = next;
        if (next != NavPage.Devices) _selectedDeviceId = null;
        RenderAll(forceShell: true);
        if (next == NavPage.Devices)
            _ = _controller.Collector.RefreshAsync(_controller.Origin).ContinueWith(_ => Dispatcher.Invoke(() => RenderAll(false)));
        else if (next == NavPage.Usage)
            _ = _controller.RefreshUsageAsync();
        else if (next == NavPage.Account)
            _ = _controller.RefreshAccountAsync();
    }

    private Rect GetCurrentMonitorWorkAreaDip()
    {
        try
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            if (hwnd != IntPtr.Zero)
            {
                var screen = System.Windows.Forms.Screen.FromHandle(hwnd);
                var dpi = VisualTreeHelper.GetDpi(this);
                var sx = dpi.DpiScaleX > 0 ? dpi.DpiScaleX : 1.0;
                var sy = dpi.DpiScaleY > 0 ? dpi.DpiScaleY : 1.0;
                var wa = screen.WorkingArea;
                return new Rect(wa.Left / sx, wa.Top / sy, wa.Width / sx, wa.Height / sy);
            }
        }
        catch { }
        return SystemParameters.WorkArea;
    }

    private void ApplyCompactSettingsLive()
    {
        var ui = _controller.ReadUiSettings();
        if (_compactMode)
        {
            var (targetW, targetH) = ui.GetCompactDimensions();
            var work = GetCurrentMonitorWorkAreaDip();
            var curW = ActualWidth > 120 ? ActualWidth : Width;
            var curH = ActualHeight > 100 ? ActualHeight : Height;
            var cx = Left + curW / 2.0;
            var cy = Top + curH / 2.0;
            Width = targetW;
            Height = targetH;
            Left = Math.Round(Math.Clamp(cx - targetW / 2.0, work.Left + 8, Math.Max(work.Left + 8, work.Right - targetW - 8)));
            Top = Math.Round(Math.Clamp(cy - targetH / 2.0, work.Top + 8, Math.Max(work.Top + 8, work.Bottom - targetH - 8)));
        }
        RenderAll(forceShell: true);
    }

    private void ToggleCompactMode()
    {
        _compactMode = !_compactMode;
        var work = GetCurrentMonitorWorkAreaDip();
        if (_compactMode)
        {
            var normalBounds = WindowState == WindowState.Maximized ? RestoreBounds : new Rect(Left, Top, Width, Height);
            if (normalBounds.Width >= 600 && normalBounds.Height >= 400)
            {
                _savedLeft = normalBounds.Left;
                _savedTop = normalBounds.Top;
                _savedWidth = normalBounds.Width;
                _savedHeight = normalBounds.Height;
            }
            if (WindowState == WindowState.Maximized) WindowState = WindowState.Normal;
            MinWidth = 280;
            MinHeight = 175;
            var (targetW, targetH) = _controller.ReadUiSettings().GetCompactDimensions();
            Width = targetW;
            Height = targetH;
            Left = Math.Round(work.Left + (work.Width - targetW) / 2.0);
            Top = Math.Round(work.Top + (work.Height - targetH) / 2.0);
            Topmost = true;
            if (_controller.Usage is null)
                _ = _controller.RefreshUsageAsync();
        }
        else
        {
            Topmost = false;
            var targetW = Math.Min(Math.Max(1040, _savedWidth), Math.Max(660, work.Width));
            var targetH = Math.Min(Math.Max(700, _savedHeight), Math.Max(480, work.Height));
            Width = targetW;
            Height = targetH;
            MinWidth = 660;
            MinHeight = 480;
            var restoredLeft = !double.IsNaN(_savedLeft) ? _savedLeft : work.Left + (work.Width - targetW) / 2.0;
            var restoredTop = !double.IsNaN(_savedTop) ? _savedTop : work.Top + (work.Height - targetH) / 2.0;
            Left = Math.Round(Math.Clamp(restoredLeft, work.Left, Math.Max(work.Left, work.Right - targetW)));
            Top = Math.Round(Math.Clamp(restoredTop, work.Top, Math.Max(work.Top, work.Bottom - targetH)));
        }
        SyncTheme(applyDwm: true);
        RenderAll(forceShell: true);
    }

    private MainShellPlan CurrentMainShellPlan()
    {
        var effW = ActualWidth >= 560 ? ActualWidth : Math.Max(600, Width);
        return ResponsiveLayoutPolicy.PlanMainShell(effW);
    }

    private void ApplyResponsiveMainShellInPlace()
    {
        if (_compactMode || !_controller.Connected || _mountedConnectedState != true)
            return;

        var plan = CurrentMainShellPlan();
        var prev = _lastMainPlan;
        if (prev is { } prevVal && prevVal == plan)
            return;

        _lastMainPlan = plan;

        if (_sidebarColDef is not null)
            _sidebarColDef.Width = new GridLength(plan.SidebarWidth);
        if (_sidebarDockPanel is not null)
            _sidebarDockPanel.Margin = plan.CollapseSidebar ? new Thickness(0, 2, 0, 2) : new Thickness(4);
        if (_sidebarRailBorder is not null)
            _sidebarRailBorder.Margin = plan.CollapseSidebar ? new Thickness(10, 6, 4, 12) : new Thickness(12, 6, 6, 12);

        if (prev is null || prev.Value.CollapseSidebar != plan.CollapseSidebar)
            UpdateSidebarState();

        if (_page == NavPage.Tasks && _taskListColDef is not null)
        {
            _taskListColDef.Width = new GridLength(plan.TaskListWidth);
            if (prev is null
                || (prev.Value.TaskListWidth < 340) != (plan.TaskListWidth < 340)
                || (prev.Value.TaskListWidth >= 380) != (plan.TaskListWidth >= 380)
                || prev.Value.TaskDetailInspector != plan.TaskDetailInspector)
            {
                UpdateTasksPageInPlace();
            }
        }
        else if (_page == NavPage.Devices && _selectedDeviceId is null && _pageBodyHost is not null && prev?.DevicesColumns != plan.DevicesColumns)
        {
            _pageBodyHost.Content = BuildDevicesContent();
        }
        else if (_page == NavPage.Usage && _pageBodyHost is not null && prev?.UsageColumns != plan.UsageColumns)
        {
            _pageBodyHost.Content = BuildUsageContent();
        }
        else if (_page == NavPage.Account && _pageBodyHost is not null && prev?.AccountTwoColumnBento != plan.AccountTwoColumnBento)
        {
            _pageBodyHost.Content = BuildAccountContent();
        }
    }

    private void RenderAll(bool forceShell)
    {
        RebuildTitleBar();

        if (!_controller.Connected)
        {
            _taskDetail?.Dispose();
            _taskDetail = null;
            _selectedTaskId = null;
            _mountedConnectedState = false;
            _mountedPage = null;
            _lastMainPlan = null;
            RootHost.Content = BuildLoginScreen();
            return;
        }

        if (_compactMode)
        {
            _mountedConnectedState = true;
            _mountedCompactState = true;
            _lastMainPlan = null;
            RootHost.Content = BuildCompactView();
            return;
        }

        var shellChanged = forceShell
                           || _mountedConnectedState != true
                           || _mountedCompactState != false
                           || _mountedPage != _page;

        _lastMainPlan = CurrentMainShellPlan();

        if (shellChanged)
        {
            _mountedConnectedState = true;
            _mountedCompactState = false;
            _mountedPage = _page;
            RootHost.Content = BuildWorkspaceShell();
        }

        UpdateSidebarState();
        switch (_page)
        {
            case NavPage.Tasks:
                UpdateTasksPageInPlace();
                break;
            case NavPage.Devices:
                if (_pageBodyHost is not null)
                    _pageBodyHost.Content = _selectedDeviceId is null ? BuildDevicesContent() : BuildDeviceDetailContent(_selectedDeviceId);
                break;
            case NavPage.Usage:
                if (_pageBodyHost is not null) _pageBodyHost.Content = BuildUsageContent();
                break;
            case NavPage.Account:
                if (_pageBodyHost is not null) _pageBodyHost.Content = BuildAccountContent();
                break;
        }
    }

    // ==================== LOGIN SCREEN ====================

    private UIElement BuildLoginScreen()
    {
        var outer = new Grid { Background = Brushes.Transparent };
        var cardStack = new StackPanel
        {
            Width = 460,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(20)
        };

        var inner = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center };
        var icon = UiKit.BrandImage("app", 72);
        icon.Margin = new Thickness(0, 0, 0, 16);
        inner.Children.Add(icon);

        var heading = UiKit.Heading("工作，尽在眼前", 28);
        heading.HorizontalAlignment = HorizontalAlignment.Center;
        inner.Children.Add(heading);

        var envLocked = ServerOrigin.IsEnvironmentControlled;
        var showEditor = !envLocked && !_controller.PendingBrowserLogin && (string.IsNullOrEmpty(_controller.Origin) || _editingServer);

        var subtitleText = envLocked && string.IsNullOrEmpty(_controller.Origin)
            ? "环境变量 MONITOR_SERVER_URL 无效，请改为有效的 HTTPS 根地址后重启。"
            : _controller.PendingBrowserLogin
            ? "请在打开的浏览器页面确认本次连接，确认后自动进入工作台"
            : !string.IsNullOrEmpty(_controller.Origin) && !showEditor
            ? (_controller.QrLogin?.IncludeCollector == true
                ? "打开手机 Monitor 点右上角「扫一扫」，一键登录并连接这台电脑"
                : "打开手机 Monitor 点右上角「扫一扫」，确认登录桌面端")
            : "查看每台电脑上的 Claude Code、Codex 与 Antigravity";

        var sub = UiKit.Body(subtitleText, 13.5, UiKit.MutedBrush);
        sub.TextAlignment = TextAlignment.Center;
        sub.Margin = new Thickness(0, 8, 0, 20);
        inner.Children.Add(sub);

        if (showEditor)
        {
            var label = UiKit.Body("自建服务器 HTTPS 地址", 12.5, UiKit.MutedBrush);
            label.Margin = new Thickness(0, 0, 0, 6);
            inner.Children.Add(label);

            var serverBox = UiKit.StyledTextBox(_serverDraft, "https://monitor.example.com");
            serverBox.Width = 370;
            serverBox.TextChanged += (_, _) => _serverDraft = serverBox.Text;
            inner.Children.Add(serverBox);

            var btnRow = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 18, 0, 0) };
            btnRow.Children.Add(UiKit.PrimaryButton("扫码登录", async () =>
            {
                await _controller.StartQrLoginAsync(_serverDraft);
                if (!string.IsNullOrEmpty(_controller.Origin)) _editingServer = false;
                RenderAll(forceShell: true);
            }, !_controller.QrBusy, glyph: "scan"));

            var browserBtn = UiKit.SecondaryButton("浏览器登录", async () =>
            {
                await _controller.BeginBrowserLoginAsync(_serverDraft);
                if (!string.IsNullOrEmpty(_controller.Origin)) _editingServer = false;
                RenderAll(forceShell: true);
            }, !_controller.LoginBusy);
            browserBtn.Margin = new Thickness(10, 0, 0, 0);
            btnRow.Children.Add(browserBtn);
            inner.Children.Add(btnRow);

            if (!string.IsNullOrEmpty(_controller.Origin) && _editingServer)
            {
                var cancelLink = UiKit.LinkButton("取消修改", () =>
                {
                    _serverDraft = _controller.Origin;
                    _editingServer = false;
                    if (_controller.QrLogin is null) _ = _controller.StartQrLoginAsync();
                    RenderAll(forceShell: true);
                }, color: UiKit.MutedBrush);
                cancelLink.HorizontalAlignment = HorizontalAlignment.Center;
                cancelLink.Margin = new Thickness(0, 12, 0, 0);
                inner.Children.Add(cancelLink);
            }
        }
        else if (!string.IsNullOrEmpty(_controller.Origin))
        {
            var originRow = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 0, 0, 16) };
            originRow.Children.Add(new MonitorIcon("shield", UiKit.MutedBrush, 14) { Margin = new Thickness(0, 0, 6, 0) });
            originRow.Children.Add(UiKit.Body(_controller.Origin, 12.5, UiKit.MutedBrush));
            if (!envLocked && !_controller.PendingBrowserLogin)
            {
                var changeBtn = UiKit.LinkButton("更改", () =>
                {
                    _serverDraft = _controller.Origin;
                    _controller.ClearQrLogin();
                    _editingServer = true;
                    RenderAll(forceShell: true);
                });
                changeBtn.Margin = new Thickness(8, 0, 0, 0);
                originRow.Children.Add(changeBtn);
            }
            inner.Children.Add(originRow);

            if (_controller.PendingBrowserLogin)
            {
                inner.Children.Add(UiKit.PrimaryButton("重新打开浏览器确认", async () => await _controller.BeginBrowserLoginAsync(), !_controller.LoginBusy));
                var backQr = UiKit.LinkButton("改用手机扫码一键登录", async () =>
                {
                    _controller.ClearPendingBrowserLogin();
                    await _controller.StartQrLoginAsync();
                });
                backQr.HorizontalAlignment = HorizontalAlignment.Center;
                backQr.Margin = new Thickness(0, 14, 0, 0);
                inner.Children.Add(backQr);
            }
            else
            {
                if (_controller.QrLogin is { } qr && QrDrawing.CreateImage(qr.VerificationUrl) is { } qrSource)
                {
                    var qrImg = new Image { Source = qrSource, Width = 180, Height = 180, Stretch = Stretch.Uniform };
                    RenderOptions.SetBitmapScalingMode(qrImg, BitmapScalingMode.NearestNeighbor);
                    var qrPad = new Border
                    {
                        Background = Brushes.White,
                        BorderBrush = UiKit.LineBrush,
                        BorderThickness = new Thickness(1),
                        CornerRadius = new CornerRadius(UiKit.CardRadius),
                        Padding = new Thickness(14),
                        HorizontalAlignment = HorizontalAlignment.Center,
                        Child = qrImg
                    };
                    inner.Children.Add(qrPad);
                }
                else if (_controller.QrBusy)
                {
                    var loadingBox = new Border
                    {
                        Width = 208,
                        Height = 208,
                        Background = UiKit.SoftBrush,
                        CornerRadius = new CornerRadius(UiKit.CardRadius),
                        HorizontalAlignment = HorizontalAlignment.Center,
                        Child = new TextBlock
                        {
                            Text = "正在生成登录二维码…",
                            FontFamily = UiKit.AppFont,
                            FontSize = 13,
                            Foreground = UiKit.MutedBrush,
                            HorizontalAlignment = HorizontalAlignment.Center,
                            VerticalAlignment = VerticalAlignment.Center
                        }
                    };
                    inner.Children.Add(loadingBox);
                }
                else if (!string.IsNullOrEmpty(_controller.QrError))
                {
                    var err = UiKit.Body(_controller.QrError, 13, UiKit.WarningBrush);
                    err.TextAlignment = TextAlignment.Center;
                    err.Margin = new Thickness(0, 12, 0, 12);
                    inner.Children.Add(err);
                }

                var links = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 18, 0, 0) };
                links.Children.Add(UiKit.LinkButton(_controller.QrLogin is null ? "生成二维码" : "刷新二维码", async () => await _controller.StartQrLoginAsync(), !_controller.QrBusy));
                var sep = UiKit.Body(" · ", 13, UiKit.MutedBrush);
                sep.Margin = new Thickness(10, 0, 10, 0);
                links.Children.Add(sep);
                links.Children.Add(UiKit.LinkButton("改用浏览器确认登录", async () => await _controller.BeginBrowserLoginAsync(), !_controller.LoginBusy, UiKit.MutedBrush));
                inner.Children.Add(links);
            }
        }

        cardStack.Children.Add(UiKit.ElevatedGlass(inner, 32, UiKit.CardRadius));
        outer.Children.Add(cardStack);
        return outer;
    }

    // ==================== LIQUID GLASS DESKTOP HUD (COMPACT MINI-WINDOW) ====================

    private static FrameworkElement BuildResponsiveCardGrid(IReadOnlyList<FrameworkElement> items, int columns, double gap = 14.0)
    {
        if (items.Count == 0) return new Border();
        var cols = Math.Max(1, Math.Min(columns, items.Count));
        if (cols == 1)
        {
            var stack = new StackPanel();
            for (var i = 0; i < items.Count; i++)
            {
                items[i].Margin = new Thickness(0, 0, 0, i < items.Count - 1 ? gap : 0);
                stack.Children.Add(items[i]);
            }
            return stack;
        }

        var grid = new Grid();
        for (var c = 0; c < cols; c++)
        {
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            if (c < cols - 1)
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(gap) });
        }

        var rows = (int)Math.Ceiling(items.Count / (double)cols);
        for (var r = 0; r < rows; r++)
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        for (var idx = 0; idx < items.Count; idx++)
        {
            var r = idx / cols;
            var c = idx % cols;
            var el = items[idx];
            el.VerticalAlignment = VerticalAlignment.Top;
            el.Margin = new Thickness(0, 0, 0, r < rows - 1 ? gap : 0);
            Grid.SetRow(el, r);
            Grid.SetColumn(el, c * 2);
            grid.Children.Add(el);
        }
        return grid;
    }

    private UIElement BuildCompactView()
    {
        var ui = _controller.ReadUiSettings();
        var (presetW, presetH) = ui.GetCompactDimensions();
        var winW = ActualWidth > 180 ? ActualWidth : presetW;
        var winH = ActualHeight > 130 ? ActualHeight : presetH;

        var plan = ResponsiveLayoutPolicy.PlanCompact(winW, winH, ui.CompactShowTaskMeta);
        var isNano = plan.Kind is CompactLayoutKind.MicroCapsule or CompactLayoutKind.WideRibbon;
        var sidePad = winW < 340 ? 9.0 : 11.0;
        var cardPad = isNano ? 10.5 : 13.0;

        // Exact client viewport derived from RootHost (eliminates WindowChrome non-client border estimation drift)
        var frameDeltaW = (ActualWidth > 140 && OuterFrame.ActualWidth > 140)
            ? Math.Clamp(ActualWidth - OuterFrame.ActualWidth, 0.0, 28.0)
            : 16.0;
        var frameDeltaH = (ActualHeight > 120 && OuterFrame.ActualHeight > 100)
            ? Math.Clamp(ActualHeight - OuterFrame.ActualHeight, 0.0, 28.0)
            : 16.0;
        var rootW = (RootHost.ActualWidth > 140 && Math.Abs(ActualWidth - winW) < 24.0)
            ? RootHost.ActualWidth
            : Math.Max(180.0, winW - frameDeltaW);
        var rootH = (RootHost.ActualHeight > 80 && Math.Abs(ActualHeight - winH) < 24.0)
            ? RootHost.ActualHeight
            : Math.Max(96.0, winH - frameDeltaH - 34.0);

        var footerTopMargin = isNano ? 5.5 : 7.5;
        var footerDivMargin = isNano ? 3.5 : 4.5;
        var footerBarH = isNano ? 19.0 : 21.0;
        var footerReservedH = footerTopMargin + 1.0 + footerDivMargin + footerBarH;
        var breathingBuffer = isNano ? 5.0 : 8.0;

        var exactBodyW = Math.Max(140.0, Math.Floor(rootW - sidePad * 2.0 - 2.0 - cardPad * 2.0));
        var exactBodyH = Math.Max(64.0, Math.Floor(rootH - (2.0 + sidePad) - 2.0 - cardPad * 2.0 - footerReservedH - breathingBuffer));

        static double MeasureH(UIElement el, double availW)
        {
            el.Measure(new Size(Math.Max(64.0, availW), double.PositiveInfinity));
            return Math.Ceiling(el.DesiredSize.Height);
        }

        var now = DateTimeOffset.UtcNow;
        var providers = _controller.Usage?.Providers ?? [];
        var checks = _controller.Usage?.QuotaChecks ?? [];
        var lowest = FindLowestQuota(now);
        var todayTotal = providers.Sum(p => p.TodayTokens ?? 0);

        // Priority-order active tasks: waiting for answer/approval first, then errors, then running
        var activeTasks = _controller.Tasks
            .Where(t => !t.Archived && t.EffectiveStatus is "running" or "waiting" or "error")
            .OrderBy(t => t.EffectiveStatus switch { "waiting" => 0, "error" => 1, _ => 2 })
            .Take(24)
            .ToList();

        FrameworkElement BuildCompactTaskPod(MonitorTask task, bool showMetaRow, bool showPreviewRow, bool expandedDetail = false, bool tightPad = false)
        {
            var captured = task;
            var statusColor = TaskStatusColor(task);
            var statusText = _controller.IsFresh && task.Stale != true ? task.StatusTitle : "待更新";
            var isWaiting = _controller.IsFresh && task.Stale != true && task.EffectiveStatus == "waiting";

            var row1 = new Grid();
            row1.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row1.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row1.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row1.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var tIcon = UiKit.BrandImage(task.Tool, expandedDetail ? 15 : 14);
            tIcon.Margin = new Thickness(0, 0, 6, 0);
            tIcon.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(tIcon, 0);
            row1.Children.Add(tIcon);

            var titleBlock = new TextBlock
            {
                Text = task.Title,
                FontFamily = UiKit.AppFont,
                FontSize = isNano ? 12.0 : (expandedDetail ? 13.0 : 12.5),
                FontWeight = FontWeights.SemiBold,
                Foreground = UiKit.TextBrush,
                TextWrapping = TextWrapping.NoWrap,
                TextTrimming = TextTrimming.CharacterEllipsis,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 8, 0)
            };
            Grid.SetColumn(titleBlock, 1);
            row1.Children.Add(titleBlock);

            if (expandedDetail)
            {
                var devOnline = _controller.Devices.Any(d => d.Id == task.DeviceId && d.Online);
                var contState = task.EffectiveContinuation(_controller.IsFresh, devOnline);
                var contLabel = MonitorTask.ContinuationTitleFor(contState);
                if (!string.IsNullOrEmpty(contLabel))
                {
                    var contBadge = new Border
                    {
                        Background = UiKit.SurfaceBrush,
                        BorderBrush = UiKit.GlassSubtleRimBrush,
                        BorderThickness = new Thickness(0.8),
                        CornerRadius = new CornerRadius(4.5),
                        Padding = new Thickness(5.5, 1, 5.5, 1),
                        Margin = new Thickness(0, 0, 7, 0),
                        VerticalAlignment = VerticalAlignment.Center,
                        Opacity = 0.88,
                        Child = new TextBlock
                        {
                            Text = contLabel,
                            FontFamily = UiKit.AppFont,
                            FontSize = 10.0,
                            Foreground = contState == "continuable" ? UiKit.AccentBrush : UiKit.MutedBrush
                        }
                    };
                    Grid.SetColumn(contBadge, 2);
                    row1.Children.Add(contBadge);
                }
            }

            var statusChip = new Grid { VerticalAlignment = VerticalAlignment.Center };
            statusChip.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            statusChip.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var sDot = new Border
            {
                Width = 5.5,
                Height = 5.5,
                CornerRadius = new CornerRadius(3),
                Background = statusColor,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 4, 0)
            };
            Grid.SetColumn(sDot, 0);
            statusChip.Children.Add(sDot);

            var sTxt = new TextBlock
            {
                Text = statusText,
                FontFamily = UiKit.AppFont,
                FontSize = 11,
                FontWeight = FontWeights.Medium,
                Foreground = statusColor,
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(sTxt, 1);
            statusChip.Children.Add(sTxt);
            Grid.SetColumn(statusChip, 3);
            row1.Children.Add(statusChip);

            var podCol = new StackPanel();
            podCol.Children.Add(row1);

            if ((showPreviewRow || expandedDetail) && !string.IsNullOrWhiteSpace(task.Preview))
            {
                var cleanPreview = task.Preview!.Replace("\r", " ").Replace("\n", " ").Trim();
                podCol.Children.Add(new TextBlock
                {
                    Text = cleanPreview,
                    FontFamily = UiKit.AppFont,
                    FontSize = 11.1,
                    Foreground = UiKit.TextBrush,
                    Opacity = 0.80,
                    TextWrapping = expandedDetail ? TextWrapping.Wrap : TextWrapping.NoWrap,
                    MaxHeight = expandedDetail ? 31.0 : 17.5,
                    TextTrimming = TextTrimming.CharacterEllipsis,
                    Margin = new Thickness(20, 2.5, 0, 0)
                });
            }

            if (showMetaRow || expandedDetail)
            {
                var row2 = new Grid { Margin = new Thickness(20, expandedDetail ? 3.5 : 2.0, 0, 0) };
                row2.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row2.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

                var projShort = string.IsNullOrWhiteSpace(task.Project)
                    ? ""
                    : (expandedDetail ? task.Project!.Trim() : Path.GetFileName(task.Project!.TrimEnd('/', '\\')));
                if (string.IsNullOrWhiteSpace(projShort) && !string.IsNullOrWhiteSpace(task.Project))
                    projShort = task.Project!.Trim();

                var leftSub = MonitorTask.BrandFor(task.Tool)
                              + (string.IsNullOrWhiteSpace(task.DeviceName) ? "" : $" · {task.DeviceName!.Trim()}")
                              + (string.IsNullOrWhiteSpace(projShort) ? "" : $" · {projShort}");
                var leftSubBlock = new TextBlock
                {
                    Text = leftSub,
                    FontFamily = UiKit.AppFont,
                    FontSize = 10.5,
                    Foreground = UiKit.MutedBrush,
                    TextWrapping = TextWrapping.NoWrap,
                    TextTrimming = TextTrimming.CharacterEllipsis,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(0, 0, 8, 0)
                };
                Grid.SetColumn(leftSubBlock, 0);
                row2.Children.Add(leftSubBlock);

                var rightMetaParts = new List<string>();
                if (expandedDetail && task.Usage is { TotalTokens: > 0 } tu)
                {
                    if (tu.InputTokens is > 0 && tu.OutputTokens is > 0)
                        rightMetaParts.Add($"入 {UsageFormatting.CompactTokens(tu.InputTokens)} · 出 {UsageFormatting.CompactTokens(tu.OutputTokens)}");
                    else
                        rightMetaParts.Add($"{UsageFormatting.CompactTokens(tu.TotalTokens)} Token");
                }
                else if (task.Usage?.TotalTokens is > 0)
                {
                    rightMetaParts.Add(UsageFormatting.CompactTokens(task.Usage.TotalTokens));
                }
                if (!string.IsNullOrEmpty(task.UpdatedAt))
                    rightMetaParts.Add(UsageFormatting.RelativeTime(task.UpdatedAt));
                if (rightMetaParts.Count > 0)
                {
                    var rightMeta = new TextBlock
                    {
                        Text = string.Join(" · ", rightMetaParts),
                        FontFamily = UiKit.AppFont,
                        FontSize = 10.5,
                        Foreground = UiKit.MutedBrush,
                        TextWrapping = TextWrapping.NoWrap,
                        VerticalAlignment = VerticalAlignment.Center
                    };
                    Grid.SetColumn(rightMeta, 1);
                    row2.Children.Add(rightMeta);
                }
                podCol.Children.Add(row2);
            }

            var defaultRim = isWaiting ? UiKit.WarningBrush : UiKit.GlassSubtleRimBrush;
            var pad = isNano
                ? new Thickness(9, 4.8, 9, 4.8)
                : expandedDetail
                ? new Thickness(11, 8.5, 11, 8.5)
                : tightPad
                ? new Thickness(9.5, 5.0, 9.5, 5.0)
                : new Thickness(10, 6.0, 10, 6.0);

            var podBorder = new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = defaultRim,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(UiKit.PodRadius),
                Padding = pad,
                SnapsToDevicePixels = true,
                Child = podCol
            };
            var btn = UiKit.BareButton(podBorder, () =>
            {
                _selectedTaskId = captured.Id;
                _page = NavPage.Tasks;
                ToggleCompactMode();
            });
            btn.ToolTip = $"{task.Title}\n点击展开完整工作台处理";
            btn.MouseEnter += (_, _) =>
            {
                podBorder.Background = UiKit.GlassCardHoverBrush;
                podBorder.BorderBrush = isWaiting ? UiKit.WarningBrush : UiKit.AccentBrush;
            };
            btn.MouseLeave += (_, _) =>
            {
                podBorder.Background = UiKit.GlassTrackBrush;
                podBorder.BorderBrush = defaultRim;
            };
            return btn;
        }

        UIElement BuildProgressBar(double remPercent, Brush fillBrush, double height = 5.0, double? idealTickPercent = null, bool tickWarning = false)
        {
            var track = new Grid { Height = height };
            track.Children.Add(new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = remPercent <= 0.5 ? fillBrush : UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(0.6),
                CornerRadius = new CornerRadius(height / 2.0),
                Opacity = remPercent <= 0.5 ? 0.65 : 1.0
            });
            if (remPercent <= 0.5)
            {
                track.Children.Add(new Border
                {
                    Background = fillBrush,
                    CornerRadius = new CornerRadius(height / 2.0),
                    Opacity = 0.14
                });
            }
            else
            {
                var ratio = Math.Clamp(remPercent / 100.0, 0.02, 1.0);
                var g = new Grid();
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(ratio, GridUnitType.Star) });
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(0.0001, 1.0 - ratio), GridUnitType.Star) });
                var fill = new Border { Background = fillBrush, CornerRadius = new CornerRadius(height / 2.0) };
                Grid.SetColumn(fill, 0);
                g.Children.Add(fill);
                track.Children.Add(g);
            }
            if (idealTickPercent is { } idealPct && !double.IsNaN(idealPct))
            {
                var tickRatio = Math.Clamp(idealPct / 100.0, 0.018, 0.982);
                var tickGrid = new Grid { IsHitTestVisible = false };
                tickGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(tickRatio, GridUnitType.Star) });
                tickGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(0.0001, 1.0 - tickRatio), GridUnitType.Star) });
                var tick = new Border
                {
                    Width = 2.2,
                    HorizontalAlignment = HorizontalAlignment.Right,
                    Margin = new Thickness(0, -1.3, -1.1, -1.3),
                    CornerRadius = new CornerRadius(1.1),
                    Background = tickWarning ? UiKit.WarningBrush : UiKit.TextBrush,
                    BorderBrush = UiKit.SurfaceBrush,
                    BorderThickness = new Thickness(0.45),
                    Opacity = tickWarning ? 0.96 : 0.82
                };
                Grid.SetColumn(tick, 0);
                tickGrid.Children.Add(tick);
                track.Children.Add(tickGrid);
            }
            return track;
        }

        FrameworkElement BuildQuotaItemRow(string? toolIcon, string titleText, UsageQuotaDto q)
        {
            var rem = Math.Clamp(q.Remaining ?? 0.0, 0.0, 100.0);
            var low = rem <= 15.0;
            var barColor = rem <= 5.0 ? UiKit.DangerBrush : low ? UiKit.WarningBrush : UiKit.AccentBrush;
            var pace = UsageFormatting.ComputeQuotaPace(q, now);

            var itemStack = new StackPanel();
            var qTop = new Grid { Margin = new Thickness(0, 0, 0, 2.5) };
            qTop.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            qTop.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            qTop.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            if (!string.IsNullOrEmpty(toolIcon))
            {
                var ico = UiKit.BrandImage(toolIcon, 13);
                ico.Margin = new Thickness(0, 0, 5, 0);
                ico.VerticalAlignment = VerticalAlignment.Center;
                Grid.SetColumn(ico, 0);
                qTop.Children.Add(ico);
            }

            var qTitleBlock = new TextBlock
            {
                Text = titleText,
                ToolTip = titleText,
                FontFamily = UiKit.AppFont,
                FontSize = isNano ? 11.2 : 11.5,
                FontWeight = !string.IsNullOrEmpty(toolIcon) ? FontWeights.Medium : FontWeights.Normal,
                Foreground = UiKit.TextBrush,
                TextWrapping = TextWrapping.NoWrap,
                TextTrimming = TextTrimming.CharacterEllipsis,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 8, 0)
            };
            Grid.SetColumn(qTitleBlock, 1);
            qTop.Children.Add(qTitleBlock);

            var qPct = new TextBlock
            {
                Text = $"剩余 {UsageFormatting.Percentage(rem)}",
                FontFamily = UiKit.AppFont,
                FontSize = isNano ? 11.2 : 11.5,
                FontWeight = FontWeights.SemiBold,
                Foreground = low ? barColor : UiKit.TextBrush,
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(qPct, 2);
            qTop.Children.Add(qPct);
            itemStack.Children.Add(qTop);

            var bar = BuildProgressBar(rem, barColor, 4.6, pace?.IdealRemainingPercent, pace?.IsWarning == true);
            if (bar is FrameworkElement bfe) bfe.Margin = new Thickness(0, 0, 0, 2.0);
            itemStack.Children.Add(bar);

            var subGrid = new Grid();
            subGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            subGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var resetBlock = new TextBlock
            {
                Text = UsageFormatting.ResetText(q, now),
                FontFamily = UiKit.AppFont,
                FontSize = 10.2,
                Foreground = UiKit.MutedBrush,
                TextWrapping = TextWrapping.NoWrap,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Margin = new Thickness(0, 0, 6, 0)
            };
            Grid.SetColumn(resetBlock, 0);
            subGrid.Children.Add(resetBlock);

            if (!string.IsNullOrEmpty(pace?.CompactLabel))
            {
                var paceBrush = pace.State == "exhausted"
                    ? UiKit.DangerBrush
                    : pace.IsWarning
                        ? UiKit.WarningBrush
                        : pace.State == "ahead"
                            ? UiKit.GreenBrush
                            : UiKit.MutedBrush;
                var paceBlock = new TextBlock
                {
                    Text = pace.CompactLabel,
                    ToolTip = $"线性时间基准剩余 {UsageFormatting.Percentage(pace.IdealRemainingPercent)} · {pace.Label}",
                    FontFamily = UiKit.AppFont,
                    FontSize = 10.0,
                    FontWeight = pace.IsWarning ? FontWeights.SemiBold : FontWeights.Normal,
                    Foreground = paceBrush,
                    TextWrapping = TextWrapping.NoWrap
                };
                Grid.SetColumn(paceBlock, 1);
                subGrid.Children.Add(paceBlock);
            }

            itemStack.Children.Add(subGrid);
            return itemStack;
        }

        // Collect all visible cross-provider quotas for Bento / Quota pages
        var allFlatQuotas = new List<(string Tool, string Brand, string? SourceLabel, UsageQuotaDto Quota)>();
        var displayedTools = new List<string> { "codex", "claude" };
        if (providers.Any(p => p.Tool == "antigravity") || checks.Any(c => c.Tool == "antigravity"))
            displayedTools.Add("antigravity");
        foreach (var tool in displayedTools)
        {
            var p = providers.FirstOrDefault(x => x.Tool == tool);
            var brand = MonitorTask.BrandFor(tool);
            var sources = UsageFormatting.VisibleSources(p, now);
            foreach (var src in sources)
            {
                var srcLbl = sources.Count > 1 ? src.Name : null;
                foreach (var q in src.Quotas)
                    allFlatQuotas.Add((tool, brand, srcLbl, q));
            }
        }

        var cards = new List<(string Title, UIElement Content)>();
        var toolFirstQuotaCardIndex = new Dictionary<string, int>(StringComparer.Ordinal);

        var wantOverview = ui.CompactContentMode != "tasks"
                           && (ui.CompactShowLowestQuota || ui.CompactShowTodayTokens || ui.CompactShowToolStrip);
        var wantTasks = ui.CompactContentMode is "overview" or "tasks";
        var wantQuotas = ui.CompactContentMode is "overview" or "quotas";

        if (wantOverview)
            cards.Add(("总览", new Border()));
        var firstTaskCardIdx = cards.Count;

        string? GetProviderTopModelsLine(UsageProviderDto? p)
        {
            if (p?.History?.Days is not { Count: > 0 } days) return null;
            var todayStr = UsageCalendar.FormatDay(UsageCalendar.TodayBeijing(now));
            var targetDay = days.FirstOrDefault(d => d.Day == todayStr && (d.Tokens ?? 0) > 0)
                            ?? days.LastOrDefault(d => (d.Tokens ?? 0) > 0);
            if (targetDay is null) return null;
            var shares = UsageFormatting.ComputeModelShares(targetDay);
            if (shares.Count == 0) return null;
            var topParts = shares.Take(2)
                .Select(s => $"{s.DisplayName} {UsageFormatting.Percentage(s.Percent)}")
                .ToList();
            return string.Join(" · ", topParts);
        }

        FrameworkElement BuildSevenDayTrendPod(double barHeight = 44.0, bool stretchVertical = false)
        {
            var bjToday = UsageCalendar.TodayBeijing(now);
            var dates = Enumerable.Range(0, 7).Select(i => bjToday.AddDays(i - 6)).ToList();
            var dayTotals = new long[7];
            for (var i = 0; i < 7; i++)
            {
                var ds = UsageCalendar.FormatDay(dates[i]);
                long sum = 0;
                foreach (var p in providers)
                {
                    var match = p.History?.Days?.FirstOrDefault(x => x.Day == ds);
                    if (match?.Tokens is > 0) sum += match.Tokens.Value;
                }
                if (i == 6 && sum == 0 && todayTotal > 0)
                    sum = todayTotal;
                dayTotals[i] = sum;
            }

            var maxTok = Math.Max(1L, dayTotals.Max());
            var weekSum = dayTotals.Sum();
            string WeekdayShort(DayOfWeek dow) => dow switch
            {
                DayOfWeek.Monday => "一",
                DayOfWeek.Tuesday => "二",
                DayOfWeek.Wednesday => "三",
                DayOfWeek.Thursday => "四",
                DayOfWeek.Friday => "五",
                DayOfWeek.Saturday => "六",
                _ => "日"
            };

            var rootGrid = new Grid();
            rootGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            rootGrid.RowDefinitions.Add(new RowDefinition { Height = stretchVertical ? new GridLength(1, GridUnitType.Star) : GridLength.Auto });

            var head = new Grid { Margin = new Thickness(0, 0, 0, 5.5) };
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            head.Children.Add(new TextBlock
            {
                Text = "近 7 日总 Token 趋势",
                FontFamily = UiKit.AppFont,
                FontSize = 11.6,
                FontWeight = FontWeights.SemiBold,
                Foreground = UiKit.TextBrush,
                VerticalAlignment = VerticalAlignment.Center
            });
            var sumTxt = new TextBlock
            {
                Text = $"7 日 {UsageFormatting.CompactTokens(weekSum)}",
                FontFamily = UiKit.AppFont,
                FontSize = 10.8,
                FontWeight = FontWeights.Medium,
                Foreground = UiKit.AccentBrush,
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(sumTxt, 1);
            head.Children.Add(sumTxt);
            Grid.SetRow(head, 0);
            rootGrid.Children.Add(head);

            var chartGrid = new UniformGrid
            {
                Columns = 7,
                VerticalAlignment = stretchVertical ? VerticalAlignment.Bottom : VerticalAlignment.Top
            };
            for (var i = 0; i < 7; i++)
            {
                var isToday = i == 6;
                var tok = dayTotals[i];
                var hRatio = tok > 0 ? Math.Max(0.12, tok / (double)maxTok) : 0.06;
                var fillH = Math.Round(barHeight * hRatio, 1);

                var slot = new Grid { Height = barHeight, Margin = new Thickness(2.5, 0, 2.5, 0) };
                slot.Children.Add(new Border
                {
                    Background = UiKit.SurfaceBrush,
                    CornerRadius = new CornerRadius(3.5),
                    Opacity = 0.42
                });
                slot.Children.Add(new Border
                {
                    Height = fillH,
                    VerticalAlignment = VerticalAlignment.Bottom,
                    Background = isToday ? UiKit.AccentBrush : (tok > 0 ? UiKit.MutedBrush : UiKit.LineBrush),
                    CornerRadius = new CornerRadius(3.5),
                    Opacity = isToday ? 0.96 : (tok > 0 ? 0.48 : 0.35)
                });

                var col = new StackPanel
                {
                    Background = Brushes.Transparent,
                    ToolTip = $"{UsageCalendar.ShortDay(dates[i])}：{UsageFormatting.ExactTokens(tok)} Token"
                };
                col.Children.Add(slot);
                col.Children.Add(new TextBlock
                {
                    Text = WeekdayShort(dates[i].DayOfWeek),
                    FontFamily = UiKit.AppFont,
                    FontSize = 10.0,
                    FontWeight = isToday ? FontWeights.SemiBold : FontWeights.Normal,
                    Foreground = isToday ? UiKit.AccentBrush : UiKit.MutedBrush,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    Margin = new Thickness(0, 3.0, 0, 0)
                });
                chartGrid.Children.Add(col);
            }
            Grid.SetRow(chartGrid, 1);
            rootGrid.Children.Add(chartGrid);

            return new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(UiKit.PodRadius),
                Padding = new Thickness(10, 7.5, 10, 7.0),
                SnapsToDevicePixels = true,
                Child = rootGrid
            };
        }

        FrameworkElement? BuildTodayModelBreakdownPod(int maxModels = 3)
        {
            var todayStr = UsageCalendar.FormatDay(UsageCalendar.TodayBeijing(now));
            var aggregated = new List<(string Tool, string DisplayName, long Tokens)>();
            var usingToday = false;

            foreach (var p in providers)
            {
                if (p.History?.Days is not { Count: > 0 } days) continue;
                var todayEntry = days.FirstOrDefault(d => d.Day == todayStr && (d.Tokens ?? 0) > 0);
                if (todayEntry is not null)
                {
                    usingToday = true;
                    foreach (var s in UsageFormatting.ComputeModelShares(todayEntry))
                        if (s.Key != "__other__" && s.Tokens > 0)
                            aggregated.Add((p.Tool, s.DisplayName, s.Tokens));
                }
            }

            if (aggregated.Count == 0)
            {
                foreach (var p in providers)
                {
                    var latest = p.History?.Days?.LastOrDefault(d => (d.Tokens ?? 0) > 0);
                    if (latest is null) continue;
                    foreach (var s in UsageFormatting.ComputeModelShares(latest))
                        if (s.Key != "__other__" && s.Tokens > 0)
                            aggregated.Add((p.Tool, s.DisplayName, s.Tokens));
                }
            }

            if (aggregated.Count == 0) return null;

            var merged = aggregated
                .GroupBy(x => (x.Tool, x.DisplayName))
                .Select(g => (g.Key.Tool, g.Key.DisplayName, Tokens: g.Sum(v => v.Tokens)))
                .OrderByDescending(x => x.Tokens)
                .ToList();

            var totalTok = Math.Max(1L, merged.Sum(x => x.Tokens));
            var topRows = merged.Take(Math.Max(1, maxModels)).ToList();

            var stack = new StackPanel();
            var head = new Grid { Margin = new Thickness(0, 0, 0, 5.5) };
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            head.Children.Add(new TextBlock
            {
                Text = usingToday ? "今日主力模型占比" : "最近主力模型占比",
                FontFamily = UiKit.AppFont,
                FontSize = 11.6,
                FontWeight = FontWeights.SemiBold,
                Foreground = UiKit.TextBrush,
                VerticalAlignment = VerticalAlignment.Center
            });
            var sumLabel = new TextBlock
            {
                Text = $"{UsageFormatting.CompactTokens(totalTok)} Token",
                FontFamily = UiKit.AppFont,
                FontSize = 10.6,
                FontWeight = FontWeights.Medium,
                Foreground = UiKit.AccentBrush,
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(sumLabel, 1);
            head.Children.Add(sumLabel);
            stack.Children.Add(head);

            for (var i = 0; i < topRows.Count; i++)
            {
                var item = topRows[i];
                var pct = item.Tokens * 100.0 / totalTok;
                var rowWrap = new StackPanel { Margin = new Thickness(0, 0, 0, i < topRows.Count - 1 ? 5.0 : 0) };
                var line = new Grid { Margin = new Thickness(0, 0, 0, 2.0) };
                line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

                var ico = UiKit.BrandImage(item.Tool, 12);
                ico.Margin = new Thickness(0, 0, 5, 0);
                ico.VerticalAlignment = VerticalAlignment.Center;
                Grid.SetColumn(ico, 0);
                line.Children.Add(ico);

                var mName = new TextBlock
                {
                    Text = item.DisplayName,
                    FontFamily = UiKit.AppFont,
                    FontSize = 10.8,
                    Foreground = UiKit.TextBrush,
                    TextWrapping = TextWrapping.NoWrap,
                    TextTrimming = TextTrimming.CharacterEllipsis,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(0, 0, 8, 0)
                };
                Grid.SetColumn(mName, 1);
                line.Children.Add(mName);

                var mRight = new TextBlock
                {
                    Text = $"{UsageFormatting.CompactTokens(item.Tokens)} · {UsageFormatting.Percentage(pct)}",
                    FontFamily = UiKit.AppFont,
                    FontSize = 10.4,
                    FontWeight = FontWeights.Medium,
                    Foreground = UiKit.MutedBrush,
                    VerticalAlignment = VerticalAlignment.Center
                };
                Grid.SetColumn(mRight, 2);
                line.Children.Add(mRight);
                rowWrap.Children.Add(line);

                rowWrap.Children.Add(BuildProgressBar(pct, UiKit.AccentBrush, 3.6));
                stack.Children.Add(rowWrap);
            }

            return new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(UiKit.PodRadius),
                Padding = new Thickness(10, 7.5, 10, 7.5),
                SnapsToDevicePixels = true,
                Child = stack
            };
        }

        FrameworkElement BuildClusterSummaryFillerTile()
        {
            var waitingCnt = activeTasks.Count(t => t.EffectiveStatus == "waiting");
            var runningCnt = activeTasks.Count(t => t.EffectiveStatus == "running");
            var col = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            var top = new Grid();
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var dot = new Border
            {
                Width = 6,
                Height = 6,
                CornerRadius = new CornerRadius(3),
                Background = _controller.OnlineDeviceCount > 0 ? UiKit.GreenBrush : UiKit.MutedBrush,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 6, 0)
            };
            Grid.SetColumn(dot, 0);
            top.Children.Add(dot);
            var title = new TextBlock
            {
                Text = $"在线电脑 {_controller.OnlineDeviceCount} 台",
                FontFamily = UiKit.AppFont,
                FontSize = 12.0,
                FontWeight = FontWeights.SemiBold,
                Foreground = UiKit.TextBrush,
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(title, 1);
            top.Children.Add(title);
            var tok = new TextBlock
            {
                Text = $"今日 {UsageFormatting.CompactTokens(todayTotal)}",
                FontFamily = UiKit.AppFont,
                FontSize = 10.8,
                Foreground = UiKit.AccentBrush,
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(tok, 2);
            top.Children.Add(tok);
            col.Children.Add(top);

            var sub = waitingCnt > 0
                ? $"待处理 {waitingCnt} 项 · 运行中 {runningCnt} 项 · 点击任一任务展开处理"
                : $"运行中 {runningCnt} 项 · 点击任一任务直达详情或继续对话";
            col.Children.Add(new TextBlock
            {
                Text = sub,
                FontFamily = UiKit.AppFont,
                FontSize = 10.5,
                Foreground = UiKit.MutedBrush,
                TextWrapping = TextWrapping.NoWrap,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Margin = new Thickness(12, 3, 0, 0)
            });

            return new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(0.8),
                CornerRadius = new CornerRadius(UiKit.PodRadius),
                Padding = new Thickness(10, 6.0, 10, 6.0),
                Opacity = 0.88,
                SnapsToDevicePixels = true,
                Child = col
            };
        }

        Grid BuildCompactSectionHeader(string leftTitle, string rightLabel, double bottomMargin = 6.5)
        {
            var headGrid = new Grid { Margin = new Thickness(0, 0, 0, bottomMargin) };
            headGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            headGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            headGrid.Children.Add(new TextBlock
            {
                Text = leftTitle,
                FontFamily = UiKit.AppFont,
                FontSize = 12.6,
                FontWeight = FontWeights.SemiBold,
                Foreground = UiKit.TextBrush,
                VerticalAlignment = VerticalAlignment.Center
            });
            var hRight = new TextBlock
            {
                Text = rightLabel,
                FontFamily = UiKit.AppFont,
                FontSize = 10.8,
                Foreground = UiKit.MutedBrush,
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(hRight, 1);
            headGrid.Children.Add(hRight);
            return headGrid;
        }

        // 1. Measured + Balanced DP Active Task Cards (Zero bottom clipping, zero 1-item orphans!)
        if (wantTasks)
        {
            if (activeTasks.Count == 0)
            {
                if (!wantOverview || ui.CompactContentMode == "tasks")
                {
                    var emptyCol = new StackPanel
                    {
                        HorizontalAlignment = HorizontalAlignment.Center,
                        VerticalAlignment = VerticalAlignment.Center
                    };
                    var t1 = UiKit.Heading("当前没有进行中的任务", 13.5);
                    t1.HorizontalAlignment = HorizontalAlignment.Center;
                    emptyCol.Children.Add(t1);
                    if (!isNano)
                    {
                        var t2 = UiKit.Body("所有已连接电脑会话均已完成或待命", 11.5, UiKit.MutedBrush);
                        t2.HorizontalAlignment = HorizontalAlignment.Center;
                        t2.Margin = new Thickness(0, 4, 0, 10);
                        emptyCol.Children.Add(t2);
                        var openBtn = UiKit.SecondaryButton("展开工作台", ToggleCompactMode, glyph: "mini");
                        openBtn.HorizontalAlignment = HorizontalAlignment.Center;
                        emptyCol.Children.Add(openBtn);
                    }
                    cards.Add(("活跃任务 (0)", emptyCol));
                }
            }
            else if (plan.Columns >= 2)
            {
                var showHeader = !isNano;
                var headerH = showHeader ? MeasureH(BuildCompactSectionHeader("活跃任务双栏看板", "99/99"), exactBodyW) : 0.0;
                var availTaskH = Math.Max(52.0, exactBodyH - headerH);
                var colGap = isNano ? 5.0 : 6.5;
                var colW = Math.Max(80.0, (exactBodyW - colGap) / 2.0);

                var rowPairs = new List<(MonitorTask Left, MonitorTask? Right, FrameworkElement LeftPod, FrameworkElement? RightPod, double RowH)>();
                for (var idx = 0; idx < activeTasks.Count; idx += 2)
                {
                    var leftTask = activeTasks[idx];
                    var rightTask = idx + 1 < activeTasks.Count ? activeTasks[idx + 1] : null;
                    var leftPod = BuildCompactTaskPod(leftTask, plan.ShowTaskMeta, plan.ShowTaskPreview);
                    var rightPod = rightTask is not null ? BuildCompactTaskPod(rightTask, plan.ShowTaskMeta, plan.ShowTaskPreview) : null;
                    var rH = Math.Max(MeasureH(leftPod, colW), rightPod is not null ? MeasureH(rightPod, colW) : 0.0);
                    rowPairs.Add((leftTask, rightTask, leftPod, rightPod, rH));
                }

                var rowPages = ResponsiveLayoutPolicy.PartitionBalanced(rowPairs, r => r.RowH, availTaskH, colGap);
                var startOffset = 0;
                for (var pIdx = 0; pIdx < rowPages.Count; pIdx++)
                {
                    var pageRows = rowPages[pIdx];
                    var sliceTasks = new List<MonitorTask>();
                    var slicePods = new List<FrameworkElement>();
                    foreach (var rp in pageRows)
                    {
                        sliceTasks.Add(rp.Left);
                        slicePods.Add(rp.LeftPod);
                        if (rp.Right is not null && rp.RightPod is not null)
                        {
                            sliceTasks.Add(rp.Right);
                            slicePods.Add(rp.RightPod);
                        }
                    }

                    var startNo = startOffset + 1;
                    var endNo = startOffset + sliceTasks.Count;
                    startOffset = endNo;
                    var rangeLabel = rowPages.Count == 1
                        ? $"共 {activeTasks.Count} 项"
                        : $"{startNo}–{endNo} / {activeTasks.Count}";

                    var pageCol = new StackPanel { ClipToBounds = true };
                    if (showHeader)
                        pageCol.Children.Add(BuildCompactSectionHeader("活跃任务双栏看板", rangeLabel));

                    var naturalGridH = pageRows.Sum(r => r.RowH) + Math.Max(0, pageRows.Count - 1) * colGap;
                    var usedGridH = naturalGridH;

                    if (!isNano && sliceTasks.Count <= 4 && naturalGridH < availTaskH * 0.62)
                    {
                        var richPods = sliceTasks.Select(t => BuildCompactTaskPod(t, plan.ShowTaskMeta, showPreviewRow: true, expandedDetail: true)).ToList();
                        if (richPods.Count % 2 == 1 && richPods.Count >= 3)
                            richPods.Add(BuildClusterSummaryFillerTile());
                        var richGrid = BuildResponsiveCardGrid(richPods, 2, gap: colGap);
                        var richH = MeasureH(richGrid, exactBodyW);
                        if (richH <= availTaskH)
                        {
                            slicePods = richPods;
                            usedGridH = richH;
                        }
                    }

                    if (!isNano && slicePods.Count % 2 == 1 && slicePods.Count >= 3)
                    {
                        var filler = BuildClusterSummaryFillerTile();
                        var fillerH = MeasureH(filler, colW);
                        var lastRowDelta = Math.Max(0.0, fillerH - pageRows[^1].RowH);
                        if (usedGridH + lastRowDelta <= availTaskH)
                        {
                            slicePods.Add(filler);
                            usedGridH += lastRowDelta;
                        }
                    }

                    var finalGrid = BuildResponsiveCardGrid(slicePods, 2, gap: colGap);
                    usedGridH = MeasureH(finalGrid, exactBodyW);
                    pageCol.Children.Add(finalGrid);

                    if (!isNano && sliceTasks.Count <= 2 && availTaskH - usedGridH >= 106.0)
                    {
                        var trend = BuildSevenDayTrendPod(barHeight: 36.0);
                        var trendH = MeasureH(trend, exactBodyW);
                        if (usedGridH + 7.5 + trendH <= availTaskH)
                        {
                            trend.Margin = new Thickness(0, 7.5, 0, 0);
                            pageCol.Children.Add(trend);
                        }
                    }

                    var pageTitle = rowPages.Count > 1
                        ? $"活跃任务 ({startNo}–{endNo}/{activeTasks.Count})"
                        : $"活跃任务 ({activeTasks.Count})";
                    cards.Add((pageTitle, pageCol));
                }
            }
            else
            {
                // 1-Column Exact-Measured Balanced DP Packer (eliminates bottom clipping AND 1-item tail orphans!)
                var showHeader = plan.Kind != CompactLayoutKind.MicroCapsule;
                var headerH = showHeader ? MeasureH(BuildCompactSectionHeader("活跃任务", "99/99"), exactBodyW) : 0.0;
                var availTaskH = Math.Max(48.0, exactBodyH - headerH);
                var rowGap = isNano ? 5.0 : 5.5;

                var measuredTasks = activeTasks.Select(t =>
                {
                    var pod = BuildCompactTaskPod(t, plan.ShowTaskMeta, plan.ShowTaskPreview);
                    return (Task: t, Pod: pod, Height: MeasureH(pod, exactBodyW));
                }).ToList();

                var taskPages = ResponsiveLayoutPolicy.PartitionBalanced(measuredTasks, x => x.Height, availTaskH, rowGap);
                var startOffset = 0;
                for (var pIdx = 0; pIdx < taskPages.Count; pIdx++)
                {
                    var slice = taskPages[pIdx];
                    var startNo = startOffset + 1;
                    var endNo = startOffset + slice.Count;
                    startOffset = endNo;
                    var rangeLabel = taskPages.Count == 1
                        ? $"共 {activeTasks.Count} 项"
                        : $"{startNo}–{endNo} / {activeTasks.Count}";

                    var pageCol = new StackPanel { ClipToBounds = true };
                    if (showHeader)
                        pageCol.Children.Add(BuildCompactSectionHeader("活跃任务", rangeLabel));

                    var naturalH = slice.Sum(x => x.Height) + Math.Max(0, slice.Count - 1) * rowGap;
                    FrameworkElement chosenList = BuildResponsiveCardGrid(slice.Select(x => x.Pod).ToList(), 1, gap: rowGap);
                    var usedListH = naturalH;

                    // Only upgrade sparse pages to rich expanded detail when exact measurement confirms zero clipping!
                    if (!isNano && slice.Count <= 3 && naturalH < availTaskH * 0.62)
                    {
                        var richPods = slice.Select(x => BuildCompactTaskPod(x.Task, plan.ShowTaskMeta, showPreviewRow: true, expandedDetail: true)).ToList();
                        var richList = BuildResponsiveCardGrid(richPods, 1, gap: 6.5);
                        var richH = MeasureH(richList, exactBodyW);
                        if (richH <= availTaskH)
                        {
                            chosenList = richList;
                            usedListH = richH;
                        }
                    }

                    pageCol.Children.Add(chosenList);

                    if (!isNano && slice.Count <= 3 && usedListH < availTaskH * 0.66)
                    {
                        var filler = BuildClusterSummaryFillerTile();
                        var fillerH = MeasureH(filler, exactBodyW);
                        if (usedListH + 7.0 + fillerH <= availTaskH)
                        {
                            filler.Margin = new Thickness(0, 7.0, 0, 0);
                            pageCol.Children.Add(filler);
                        }
                    }

                    var pageTitle = taskPages.Count > 1
                        ? $"活跃任务 ({startNo}–{endNo}/{activeTasks.Count})"
                        : $"活跃任务 ({activeTasks.Count})";
                    cards.Add((pageTitle, pageCol));
                }
            }
        }

        var firstQuotaCardIdx = cards.Count;

        // 2. Measured + Balanced DP Quota & Local Usage Cards (Zero empty orphan pages, zero bottom clipping!)
        if (wantQuotas)
        {
            List<(string? SourceLabel, UsageQuotaDto Quota)> CollectToolQuotas(string tool)
            {
                var p = providers.FirstOrDefault(x => x.Tool == tool);
                var sources = UsageFormatting.VisibleSources(p, now);
                var list = new List<(string? SourceLabel, UsageQuotaDto Quota)>();
                foreach (var src in sources)
                {
                    var srcLabel = sources.Count > 1 ? src.Name : null;
                    foreach (var q in src.Quotas)
                        list.Add((srcLabel, q));
                }
                return list;
            }

            FrameworkElement BuildSingleProviderPod(
                string tool,
                IReadOnlyList<(string? SourceLabel, UsageQuotaDto Quota)> shownQuotas,
                bool allowTopModels,
                bool stretchForBento,
                int partIdx = 0,
                int partTotal = 1)
            {
                var p = providers.FirstOrDefault(x => x.Tool == tool);
                var brand = MonitorTask.BrandFor(tool);
                var titleLabel = partTotal > 1 ? $"{brand} ({partIdx + 1}/{partTotal})" : brand;

                var col = new StackPanel { VerticalAlignment = stretchForBento ? VerticalAlignment.Center : VerticalAlignment.Top };
                var hGrid = new Grid { Margin = new Thickness(0, 0, 0, isNano ? 3.5 : 5.5) };
                hGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                hGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                hGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

                var icon = UiKit.BrandImage(tool, isNano ? 13.5 : 14.5);
                icon.Margin = new Thickness(0, 0, 6, 0);
                icon.VerticalAlignment = VerticalAlignment.Center;
                Grid.SetColumn(icon, 0);
                hGrid.Children.Add(icon);

                var bTxt = new TextBlock
                {
                    Text = titleLabel,
                    FontFamily = UiKit.AppFont,
                    FontSize = isNano ? 12.0 : 12.4,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = UiKit.TextBrush,
                    TextWrapping = TextWrapping.NoWrap,
                    TextTrimming = TextTrimming.CharacterEllipsis,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(0, 0, 6, 0)
                };
                Grid.SetColumn(bTxt, 1);
                hGrid.Children.Add(bTxt);

                var tokRight = p?.TodayTokens is > 0
                    ? $"今日 {UsageFormatting.CompactTokens(p.TodayTokens)}"
                    : "今日 0";
                var tokTxt = new TextBlock
                {
                    Text = tokRight,
                    FontFamily = UiKit.AppFont,
                    FontSize = 10.6,
                    FontWeight = FontWeights.Medium,
                    Foreground = p?.TodayTokens is > 0 ? UiKit.AccentBrush : UiKit.MutedBrush,
                    VerticalAlignment = VerticalAlignment.Center
                };
                Grid.SetColumn(tokTxt, 2);
                hGrid.Children.Add(tokTxt);
                col.Children.Add(hGrid);

                if (shownQuotas.Count == 0)
                {
                    var chk = checks.FirstOrDefault(c => c.Tool == tool);
                    var toolActiveCnt = activeTasks.Count(t => t.Tool == tool);
                    var fallbackMsg = chk is not null
                        ? UsageFormatting.QuotaCheckText(chk.EffectiveState)
                        : (toolActiveCnt > 0 ? $"当前 {toolActiveCnt} 个活跃会话 · 暂无官方窗口读数" : "暂未收到官方额度窗口读数");
                    col.Children.Add(new TextBlock
                    {
                        Text = fallbackMsg,
                        FontFamily = UiKit.AppFont,
                        FontSize = 10.6,
                        Foreground = UiKit.MutedBrush,
                        TextWrapping = TextWrapping.NoWrap,
                        TextTrimming = TextTrimming.CharacterEllipsis,
                        Margin = new Thickness(0, 1, 0, 1.5)
                    });
                }
                else
                {
                    for (var qi = 0; qi < shownQuotas.Count; qi++)
                    {
                        var (srcLbl, q) = shownQuotas[qi];
                        var rawTitle = UsageFormatting.QuotaDisplayTitle(q);
                        var fullTitle = string.IsNullOrEmpty(srcLbl) ? rawTitle : $"{srcLbl} · {rawTitle}";
                        var row = BuildQuotaItemRow(null, fullTitle, q);
                        if (qi < shownQuotas.Count - 1)
                            row.Margin = new Thickness(0, 0, 0, isNano ? 3.8 : 5.5);
                        col.Children.Add(row);
                    }
                }

                var topModels = allowTopModels && !isNano && shownQuotas.Count <= 2 ? GetProviderTopModelsLine(p) : null;
                if (!string.IsNullOrEmpty(topModels))
                {
                    col.Children.Add(new TextBlock
                    {
                        Text = $"今日模型：{topModels}",
                        FontFamily = UiKit.AppFont,
                        FontSize = 10.2,
                        Foreground = UiKit.MutedBrush,
                        TextWrapping = TextWrapping.NoWrap,
                        TextTrimming = TextTrimming.CharacterEllipsis,
                        Margin = new Thickness(0, 4.5, 0, 0)
                    });
                }

                return new Border
                {
                    Background = UiKit.GlassTrackBrush,
                    BorderBrush = UiKit.GlassSubtleRimBrush,
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(UiKit.PodRadius),
                    Padding = isNano ? new Thickness(9.5, 6.2, 9.5, 6.2) : new Thickness(10, 7.2, 10, 7.2),
                    SnapsToDevicePixels = true,
                    Child = col
                };
            }

            List<(FrameworkElement Element, double Height, int ShownCount)> BuildProviderSummaryPods(
                string tool,
                double maxPodH,
                double availW,
                bool stretchForBento = false,
                int hardCapPerPod = 4)
            {
                var flatQuotas = CollectToolQuotas(tool);
                if (flatQuotas.Count == 0)
                {
                    var emptyPod = BuildSingleProviderPod(tool, [], allowTopModels: !isNano, stretchForBento);
                    var eh = MeasureH(emptyPod, availW);
                    if (eh > maxPodH && !isNano)
                    {
                        emptyPod = BuildSingleProviderPod(tool, [], allowTopModels: false, stretchForBento);
                        eh = MeasureH(emptyPod, availW);
                    }
                    return [(emptyPod, eh, 0)];
                }

                if (flatQuotas.Count <= hardCapPerPod)
                {
                    if (!isNano)
                    {
                        var richPod = BuildSingleProviderPod(tool, flatQuotas, allowTopModels: true, stretchForBento);
                        var richH = MeasureH(richPod, availW);
                        if (richH <= maxPodH)
                            return [(richPod, richH, flatQuotas.Count)];
                    }

                    var compactPod = BuildSingleProviderPod(tool, flatQuotas, allowTopModels: false, stretchForBento);
                    var compactH = MeasureH(compactPod, availW);
                    if (compactH <= maxPodH || flatQuotas.Count == 1)
                        return [(compactPod, compactH, flatQuotas.Count)];
                }

                // Automatically chunk multi-quota providers so NO pod ever clips at the bottom!
                var chosenChunk = 1;
                for (var cand = Math.Min(hardCapPerPod, flatQuotas.Count - 1); cand >= 2; cand--)
                {
                    var allFit = true;
                    var totalParts = (int)Math.Ceiling(flatQuotas.Count / (double)cand);
                    for (var pi = 0; pi < totalParts; pi++)
                    {
                        var sub = flatQuotas.Skip(pi * cand).Take(cand).ToList();
                        var probe = BuildSingleProviderPod(tool, sub, allowTopModels: false, stretchForBento, pi, totalParts);
                        if (MeasureH(probe, availW) > maxPodH)
                        {
                            allFit = false;
                            break;
                        }
                    }
                    if (allFit)
                    {
                        chosenChunk = cand;
                        break;
                    }
                }

                var result = new List<(FrameworkElement Element, double Height, int ShownCount)>();
                var parts = (int)Math.Ceiling(flatQuotas.Count / (double)chosenChunk);
                for (var pi = 0; pi < parts; pi++)
                {
                    var sub = flatQuotas.Skip(pi * chosenChunk).Take(chosenChunk).ToList();
                    var pod = BuildSingleProviderPod(tool, sub, allowTopModels: false, stretchForBento, pi, parts);
                    result.Add((pod, MeasureH(pod, availW), sub.Count));
                }
                return result;
            }

            (FrameworkElement Tile, int ShownCount) BuildBentoProviderQuadrant(string tool, double quadrantW, double quadrantH)
            {
                var allQ = CollectToolQuotas(tool);
                for (var cap = Math.Min(2, allQ.Count); cap >= 1; cap--)
                {
                    var slice = allQ.Take(cap).ToList();
                    var withModels = BuildSingleProviderPod(tool, slice, allowTopModels: true, stretchForBento: true);
                    if (MeasureH(withModels, quadrantW) <= quadrantH)
                        return (withModels, cap);

                    var plain = BuildSingleProviderPod(tool, slice, allowTopModels: false, stretchForBento: true);
                    if (MeasureH(plain, quadrantW) <= quadrantH || cap == 1)
                        return (plain, cap);
                }

                var fallback = BuildSingleProviderPod(tool, [], allowTopModels: true, stretchForBento: true);
                if (MeasureH(fallback, quadrantW) > quadrantH)
                    fallback = BuildSingleProviderPod(tool, [], allowTopModels: false, stretchForBento: true);
                return (fallback, 0);
            }

            if (plan.Kind == CompactLayoutKind.WideBento)
            {
                // Exact-Measured 2×2 Executive Bento Matrix: [Codex | Antigravity] over [Claude Code | 7-Day Trend]
                var matrixCardIdx = cards.Count;
                foreach (var t in new[] { "codex", "claude", "antigravity" })
                    toolFirstQuotaCardIndex[t] = matrixCardIdx;

                var bentoGap = 9.0;
                var quadrantW = Math.Max(110.0, (exactBodyW - bentoGap) / 2.0);
                var quadrantH = Math.Max(76.0, Math.Floor((exactBodyH - bentoGap) / 2.0));

                var matrixGrid = new Grid
                {
                    Height = Math.Min(exactBodyH, quadrantH * 2.0 + bentoGap),
                    ClipToBounds = true
                };
                matrixGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                matrixGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(bentoGap) });
                matrixGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                matrixGrid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
                matrixGrid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(bentoGap) });
                matrixGrid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

                var shownByTool = new Dictionary<string, int>(StringComparer.Ordinal);

                var (codexTile, codexShown) = BuildBentoProviderQuadrant("codex", quadrantW, quadrantH);
                shownByTool["codex"] = codexShown;
                Grid.SetRow(codexTile, 0);
                Grid.SetColumn(codexTile, 0);
                matrixGrid.Children.Add(codexTile);

                var secondRightTool = providers.Any(p => p.Tool == "antigravity") || checks.Any(c => c.Tool == "antigravity")
                    ? "antigravity"
                    : "claude";
                var (trTile, trShown) = BuildBentoProviderQuadrant(secondRightTool, quadrantW, quadrantH);
                shownByTool[secondRightTool] = trShown;
                Grid.SetRow(trTile, 0);
                Grid.SetColumn(trTile, 2);
                matrixGrid.Children.Add(trTile);

                var bottomLeftTool = secondRightTool == "antigravity" ? "claude" : "antigravity";
                var (blTile, blShown) = BuildBentoProviderQuadrant(bottomLeftTool, quadrantW, quadrantH);
                shownByTool[bottomLeftTool] = blShown;
                Grid.SetRow(blTile, 2);
                Grid.SetColumn(blTile, 0);
                matrixGrid.Children.Add(blTile);

                var trendBarH = Math.Clamp(quadrantH - 54.0, 22.0, 60.0);
                var brTrend = BuildSevenDayTrendPod(barHeight: trendBarH, stretchVertical: true);
                Grid.SetRow(brTrend, 2);
                Grid.SetColumn(brTrend, 2);
                matrixGrid.Children.Add(brTrend);

                cards.Add(("额度与今日用量", matrixGrid));

                // Spill any unshown quota windows onto measured 2-column overflow pages so zero windows are lost or clipped
                var overflowQuotas = new List<(string Tool, string Brand, string? SourceLabel, UsageQuotaDto Quota)>();
                foreach (var tool in displayedTools)
                {
                    var brand = MonitorTask.BrandFor(tool);
                    var list = CollectToolQuotas(tool);
                    var skipCnt = shownByTool.GetValueOrDefault(tool, 0);
                    foreach (var extra in list.Skip(skipCnt))
                        overflowQuotas.Add((tool, brand, extra.SourceLabel, extra.Quota));
                }
                if (overflowQuotas.Count > 0)
                {
                    var colGap = 7.0;
                    var colW = Math.Max(90.0, (exactBodyW - colGap) / 2.0);
                    var extraTiles = overflowQuotas.Select(entry =>
                    {
                        var raw = UsageFormatting.QuotaDisplayTitle(entry.Quota);
                        var lbl = string.IsNullOrEmpty(entry.SourceLabel) ? $"{entry.Brand} · {raw}" : $"{entry.Brand} ({entry.SourceLabel}) · {raw}";
                        return (FrameworkElement)new Border
                        {
                            Background = UiKit.GlassTrackBrush,
                            BorderBrush = UiKit.GlassSubtleRimBrush,
                            BorderThickness = new Thickness(1),
                            CornerRadius = new CornerRadius(UiKit.PodRadius),
                            Padding = new Thickness(9.5, 6.5, 9.5, 6.5),
                            Child = BuildQuotaItemRow(entry.Tool, lbl, entry.Quota)
                        };
                    }).ToList();

                    var extraRows = new List<(FrameworkElement Left, FrameworkElement? Right, double RowH)>();
                    for (var idx = 0; idx < extraTiles.Count; idx += 2)
                    {
                        var l = extraTiles[idx];
                        var r = idx + 1 < extraTiles.Count ? extraTiles[idx + 1] : null;
                        var rH = Math.Max(MeasureH(l, colW), r is not null ? MeasureH(r, colW) : 0.0);
                        extraRows.Add((l, r, rH));
                    }

                    var extraPages = ResponsiveLayoutPolicy.PartitionBalanced(extraRows, x => x.RowH, exactBodyH, colGap);
                    for (var ep = 0; ep < extraPages.Count; ep++)
                    {
                        var pageTiles = new List<FrameworkElement>();
                        foreach (var row in extraPages[ep])
                        {
                            pageTiles.Add(row.Left);
                            if (row.Right is not null) pageTiles.Add(row.Right);
                        }
                        var title = extraPages.Count == 1 ? "更多额度窗口" : $"更多额度窗口 ({ep + 1}/{extraPages.Count})";
                        cards.Add((title, BuildResponsiveCardGrid(pageTiles, 2, gap: colGap)));
                    }
                }
            }
            else if (plan.Columns >= 2 && allFlatQuotas.Count > 0)
            {
                // WideRibbon (< 250h, >= 450w): exact-measured 2-column quota rows
                var colGap = 6.5;
                var colW = Math.Max(90.0, (exactBodyW - colGap) / 2.0);
                var rowItems = new List<(List<string> Tools, FrameworkElement Left, FrameworkElement? Right, double RowH)>();
                for (var idx = 0; idx < allFlatQuotas.Count; idx += 2)
                {
                    FrameworkElement MakeTile((string Tool, string Brand, string? SourceLabel, UsageQuotaDto Quota) entry)
                    {
                        var raw = UsageFormatting.QuotaDisplayTitle(entry.Quota);
                        var lbl = string.IsNullOrEmpty(entry.SourceLabel) ? $"{entry.Brand} · {raw}" : $"{entry.Brand} ({entry.SourceLabel}) · {raw}";
                        return new Border
                        {
                            Background = UiKit.GlassTrackBrush,
                            BorderBrush = UiKit.GlassSubtleRimBrush,
                            BorderThickness = new Thickness(1),
                            CornerRadius = new CornerRadius(UiKit.PodRadius),
                            Padding = new Thickness(9, 6, 9, 6),
                            Child = BuildQuotaItemRow(entry.Tool, lbl, entry.Quota)
                        };
                    }

                    var eL = allFlatQuotas[idx];
                    var tL = MakeTile(eL);
                    var tools = new List<string> { eL.Tool };
                    FrameworkElement? tR = null;
                    if (idx + 1 < allFlatQuotas.Count)
                    {
                        var eR = allFlatQuotas[idx + 1];
                        tR = MakeTile(eR);
                        tools.Add(eR.Tool);
                    }
                    var rH = Math.Max(MeasureH(tL, colW), tR is not null ? MeasureH(tR, colW) : 0.0);
                    rowItems.Add((tools, tL, tR, rH));
                }

                var ribbonPages = ResponsiveLayoutPolicy.PartitionBalanced(rowItems, r => r.RowH, exactBodyH, colGap);
                for (var qp = 0; qp < ribbonPages.Count; qp++)
                {
                    var cardIdx = cards.Count;
                    var tiles = new List<FrameworkElement>();
                    foreach (var row in ribbonPages[qp])
                    {
                        foreach (var t in row.Tools)
                            toolFirstQuotaCardIndex.TryAdd(t, cardIdx);
                        tiles.Add(row.Left);
                        if (row.Right is not null) tiles.Add(row.Right);
                    }
                    cards.Add((ribbonPages.Count > 1 ? $"官方额度 ({qp + 1}/{ribbonPages.Count})" : "官方额度",
                        BuildResponsiveCardGrid(tiles, 2, gap: colGap)));
                }
            }
            else
            {
                // 1-Column Exact-Measured + Balanced DP Quota & Usage Pods + Sparse-Page Enrichment
                var packGap = isNano ? 5.0 : 6.5;
                var podEntries = new List<(List<string> Tools, string Kind, FrameworkElement Element, double Height)>();

                foreach (var tool in displayedTools)
                {
                    var p = providers.FirstOrDefault(x => x.Tool == tool);
                    var hasData = (p?.Quotas?.Any(q => !q.Stale && !q.IsExpired(now)) == true)
                                  || (p?.TodayTokens is > 0)
                                  || checks.Any(c => c.Tool == tool)
                                  || ui.CompactContentMode == "quotas";
                    if (!hasData) continue;

                    var pods = BuildProviderSummaryPods(tool, maxPodH: exactBodyH, availW: exactBodyW, hardCapPerPod: isNano ? 2 : 4);
                    foreach (var (podEl, podH, _) in pods)
                        podEntries.Add(([tool], "provider", podEl, podH));
                }

                if (!isNano && exactBodyH >= 150.0 && (todayTotal > 0 || providers.Any(p => p.History?.Days?.Count > 0)))
                {
                    var trendPod = BuildSevenDayTrendPod(barHeight: 38.0);
                    var trendH = MeasureH(trendPod, exactBodyW);
                    if (trendH <= exactBodyH)
                        podEntries.Add(([], "trend", trendPod, trendH));
                }

                var packedPages = ResponsiveLayoutPolicy.PartitionBalanced(podEntries, e => e.Height, exactBodyH, packGap);
                var modelBreakdownPlaced = false;

                for (var pi = 0; pi < packedPages.Count; pi++)
                {
                    var pack = packedPages[pi];
                    var usedH = pack.Sum(x => x.Height) + Math.Max(0, pack.Count - 1) * packGap;

                    // Enrich sparse pages (< 65% fill) with cross-provider Today Model Share Breakdown when measured to fit!
                    if (!isNano && !modelBreakdownPlaced && usedH < exactBodyH * 0.65)
                    {
                        for (var maxM = 3; maxM >= 1; maxM--)
                        {
                            var modelPod = BuildTodayModelBreakdownPod(maxModels: maxM);
                            if (modelPod is null) break;
                            var mH = MeasureH(modelPod, exactBodyW);
                            if (usedH + packGap + mH <= exactBodyH)
                            {
                                pack.Add(([], "models", modelPod, mH));
                                usedH += packGap + mH;
                                modelBreakdownPlaced = true;
                                break;
                            }
                        }
                    }

                    // If this page holds the 7-day trend chart and still has clean vertical room, scale chart bar height proportionally!
                    var trendIdx = pack.FindIndex(x => x.Kind == "trend");
                    if (!isNano && trendIdx >= 0 && exactBodyH - usedH >= 20.0)
                    {
                        var extraBar = Math.Min(64.0, 38.0 + Math.Floor((exactBodyH - usedH) * 0.55));
                        if (extraBar > 40.0)
                        {
                            var tallerTrend = BuildSevenDayTrendPod(barHeight: extraBar);
                            var tallerH = MeasureH(tallerTrend, exactBodyW);
                            var newTotal = usedH - pack[trendIdx].Height + tallerH;
                            if (newTotal <= exactBodyH)
                            {
                                pack[trendIdx] = ([], "trend", tallerTrend, tallerH);
                                usedH = newTotal;
                            }
                        }
                    }

                    var cardIdx = cards.Count;
                    var pageCol = new StackPanel { ClipToBounds = true };
                    for (var ei = 0; ei < pack.Count; ei++)
                    {
                        foreach (var t in pack[ei].Tools)
                            toolFirstQuotaCardIndex.TryAdd(t, cardIdx);
                        pack[ei].Element.Margin = new Thickness(0, 0, 0, ei < pack.Count - 1 ? packGap : 0);
                        pageCol.Children.Add(pack[ei].Element);
                    }

                    var title = packedPages.Count == 1
                        ? "额度与今日用量"
                        : $"额度与用量 ({pi + 1}/{packedPages.Count})";
                    cards.Add((title, pageCol));
                }
            }
        }

        // 3. Populate Slot 0 Adaptive Overview Card — Exact-Measured Across All 5 Breakpoint Archetypes!
        if (wantOverview)
        {
            StackPanel BuildLowestQuotaBlock(bool includeTodayRight = true)
            {
                var block = new StackPanel();
                if (ui.CompactShowLowestQuota && lowest is { } lq)
                {
                    var rem = Math.Clamp(lq.Quota.Remaining ?? 0.0, 0.0, 100.0);
                    var isCritical = rem <= 5.0;
                    var isLow = rem <= 20.0;
                    var meterBrush = isCritical ? UiKit.DangerBrush : isLow ? UiKit.WarningBrush : UiKit.AccentBrush;

                    var topGrid = new Grid();
                    topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                    topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

                    var bIcon = UiKit.BrandImage(lq.Tool, 15);
                    bIcon.Margin = new Thickness(0, 0, 6, 0);
                    bIcon.VerticalAlignment = VerticalAlignment.Center;
                    Grid.SetColumn(bIcon, 0);
                    topGrid.Children.Add(bIcon);

                    var fullQuotaTitle = $"{lq.Brand} · {UsageFormatting.QuotaDisplayTitle(lq.Quota)}";
                    var bTitle = new TextBlock
                    {
                        Text = fullQuotaTitle,
                        ToolTip = fullQuotaTitle,
                        FontFamily = UiKit.AppFont,
                        FontSize = 12.0,
                        FontWeight = FontWeights.SemiBold,
                        Foreground = UiKit.TextBrush,
                        TextWrapping = TextWrapping.NoWrap,
                        TextTrimming = TextTrimming.CharacterEllipsis,
                        VerticalAlignment = VerticalAlignment.Center,
                        Margin = new Thickness(0, 0, 8, 0)
                    };
                    Grid.SetColumn(bTitle, 1);
                    topGrid.Children.Add(bTitle);

                    var pctBadge = new Border
                    {
                        Background = UiKit.GlassTrackBrush,
                        BorderBrush = isLow ? meterBrush : UiKit.GlassSubtleRimBrush,
                        BorderThickness = new Thickness(0.8),
                        CornerRadius = new CornerRadius(4.5),
                        Padding = new Thickness(5.5, 1, 5.5, 1),
                        VerticalAlignment = VerticalAlignment.Center,
                        Child = new TextBlock
                        {
                            Text = $"剩余 {UsageFormatting.Percentage(rem)}",
                            FontFamily = UiKit.AppFont,
                            FontSize = 10.6,
                            FontWeight = FontWeights.SemiBold,
                            Foreground = isLow ? meterBrush : UiKit.TextBrush
                        }
                    };
                    Grid.SetColumn(pctBadge, 2);
                    topGrid.Children.Add(pctBadge);
                    block.Children.Add(topGrid);

                    var heroPace = UsageFormatting.ComputeQuotaPace(lq.Quota, now);
                    var bar = BuildProgressBar(rem, meterBrush, 4.8, heroPace?.IdealRemainingPercent, heroPace?.IsWarning == true);
                    if (bar is FrameworkElement bfe)
                        bfe.Margin = new Thickness(0, isNano ? 5.0 : 6.5, 0, isNano ? 4.0 : 5.5);
                    block.Children.Add(bar);

                    var metaGrid = new Grid();
                    metaGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    metaGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

                    var baseReset = UsageFormatting.ResetText(lq.Quota, now);
                    var resetLine = !string.IsNullOrEmpty(heroPace?.CompactLabel)
                        ? $"{baseReset} · {heroPace.CompactLabel}"
                        : baseReset;
                    var resetTxt = new TextBlock
                    {
                        Text = resetLine,
                        ToolTip = heroPace is not null
                            ? $"{baseReset} · 线性时间基准剩余 {UsageFormatting.Percentage(heroPace.IdealRemainingPercent)} ({heroPace.Label})"
                            : baseReset,
                        FontFamily = UiKit.AppFont,
                        FontSize = 10.6,
                        Foreground = heroPace?.IsWarning == true ? UiKit.WarningBrush : UiKit.MutedBrush,
                        TextWrapping = TextWrapping.NoWrap,
                        TextTrimming = TextTrimming.CharacterEllipsis,
                        VerticalAlignment = VerticalAlignment.Center,
                        Margin = new Thickness(0, 0, 8, 0)
                    };
                    Grid.SetColumn(resetTxt, 0);
                    metaGrid.Children.Add(resetTxt);

                    if (includeTodayRight && ui.CompactShowTodayTokens)
                    {
                        var tokLabel = new TextBlock
                        {
                            Text = $"今日 {UsageFormatting.CompactTokens(todayTotal)} Token",
                            FontFamily = UiKit.AppFont,
                            FontSize = 10.6,
                            FontWeight = FontWeights.Medium,
                            Foreground = UiKit.AccentBrush,
                            VerticalAlignment = VerticalAlignment.Center
                        };
                        Grid.SetColumn(tokLabel, 1);
                        metaGrid.Children.Add(tokLabel);
                    }
                    block.Children.Add(metaGrid);
                }
                else if (ui.CompactShowTodayTokens)
                {
                    block.Children.Add(UiKit.Body("今日已记录本地用量", 11.2, UiKit.MutedBrush));
                    var bigTok = UiKit.Heading($"{UsageFormatting.CompactTokens(todayTotal)} Token", isNano ? 17.5 : 19.5);
                    bigTok.Margin = new Thickness(0, 3, 0, 0);
                    block.Children.Add(bigTok);
                }
                return block;
            }

            if (plan.Kind == CompactLayoutKind.WideRibbon)
            {
                // Archetype 2: 2-Column Horizontal Status Ribbon ([Left: Quota | Right: Focus Task])
                var ribbonGrid = new Grid { ClipToBounds = true };
                ribbonGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.05, GridUnitType.Star) });
                ribbonGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(12) });
                ribbonGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.0, GridUnitType.Star) });

                var leftBox = BuildLowestQuotaBlock(includeTodayRight: true);
                leftBox.VerticalAlignment = VerticalAlignment.Center;
                Grid.SetColumn(leftBox, 0);
                ribbonGrid.Children.Add(leftBox);

                var vSep = new Border { Width = 1, Background = UiKit.SplitDividerBrush, HorizontalAlignment = HorizontalAlignment.Center };
                Grid.SetColumn(vSep, 1);
                ribbonGrid.Children.Add(vSep);

                var rightColW = Math.Max(90.0, (exactBodyW - 12.0) * (1.0 / 2.05));
                var rightStack = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                if (activeTasks.Count > 0)
                {
                    var maxCand = Math.Min(Math.Max(1, plan.OverviewTaskCount), activeTasks.Count);
                    var fittedPods = new List<FrameworkElement>();
                    var usedR = 0.0;
                    for (var i = 0; i < maxCand; i++)
                    {
                        var pod = BuildCompactTaskPod(activeTasks[i], showMetaRow: plan.ShowTaskMeta && maxCand == 1, showPreviewRow: false);
                        var pH = MeasureH(pod, rightColW);
                        var needed = fittedPods.Count == 0 ? pH : pH + 4.5;
                        if (fittedPods.Count > 0 && usedR + needed > exactBodyH)
                            break;
                        fittedPods.Add(pod);
                        usedR += needed;
                    }
                    for (var i = 0; i < fittedPods.Count; i++)
                    {
                        if (i < fittedPods.Count - 1) fittedPods[i].Margin = new Thickness(0, 0, 0, 4.5);
                        rightStack.Children.Add(fittedPods[i]);
                    }
                }
                else
                {
                    var idleTxt = UiKit.Body("当前没有活跃任务\n所有电脑会话待命中", 11.2, UiKit.MutedBrush);
                    idleTxt.TextAlignment = TextAlignment.Center;
                    rightStack.Children.Add(idleTxt);
                }
                Grid.SetColumn(rightStack, 2);
                ribbonGrid.Children.Add(rightStack);

                cards[0] = ("横向双栏速览", ribbonGrid);
            }
            else if (plan.Kind == CompactLayoutKind.WideBento)
            {
                // Archetype 5: Exact-Measured 2-Column Bento Dashboard ([Quota | Stats] + [All Quotas | Active Tasks])!
                var bentoRoot = new Grid { ClipToBounds = true };
                bentoRoot.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                bentoRoot.RowDefinitions.Add(new RowDefinition { Height = new GridLength(9) });
                bentoRoot.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

                var topBento = new Grid();
                topBento.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.15, GridUnitType.Star) });
                topBento.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(9) });
                topBento.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.85, GridUnitType.Star) });

                var tlTile = new Border
                {
                    Background = UiKit.GlassTrackBrush,
                    BorderBrush = UiKit.GlassSubtleRimBrush,
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(UiKit.PodRadius),
                    Padding = new Thickness(10.5, 8, 10.5, 8),
                    Child = BuildLowestQuotaBlock(includeTodayRight: false)
                };
                Grid.SetColumn(tlTile, 0);
                topBento.Children.Add(tlTile);

                var waitingCount = activeTasks.Count(t => t.EffectiveStatus == "waiting");
                var runningCount = activeTasks.Count(t => t.EffectiveStatus == "running");
                var statsStack = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                statsStack.Children.Add(new TextBlock
                {
                    Text = "今日本地总用量",
                    FontFamily = UiKit.AppFont,
                    FontSize = 10.6,
                    Foreground = UiKit.MutedBrush
                });
                statsStack.Children.Add(new TextBlock
                {
                    Text = $"{UsageFormatting.CompactTokens(todayTotal)} Token",
                    FontFamily = UiKit.AppFont,
                    FontSize = 16.0,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = UiKit.TextBrush,
                    TextWrapping = TextWrapping.NoWrap,
                    TextTrimming = TextTrimming.CharacterEllipsis,
                    Margin = new Thickness(0, 1.5, 0, 3.5)
                });
                var statMeta = waitingCount > 0
                    ? $"待处理 {waitingCount} · 运行中 {runningCount}"
                    : $"活跃任务 {activeTasks.Count} · 在线 {_controller.OnlineDeviceCount} 台";
                statsStack.Children.Add(new TextBlock
                {
                    Text = statMeta,
                    FontFamily = UiKit.AppFont,
                    FontSize = 10.6,
                    FontWeight = waitingCount > 0 ? FontWeights.SemiBold : FontWeights.Normal,
                    Foreground = waitingCount > 0 ? UiKit.WarningBrush : UiKit.AccentBrush,
                    TextWrapping = TextWrapping.NoWrap,
                    TextTrimming = TextTrimming.CharacterEllipsis
                });

                var trTile = new Border
                {
                    Background = UiKit.GlassTrackBrush,
                    BorderBrush = waitingCount > 0 ? UiKit.WarningBrush : UiKit.GlassSubtleRimBrush,
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(UiKit.PodRadius),
                    Padding = new Thickness(10.5, 8, 10.5, 8),
                    Child = statsStack
                };
                Grid.SetColumn(trTile, 2);
                topBento.Children.Add(trTile);

                Grid.SetRow(topBento, 0);
                bentoRoot.Children.Add(topBento);

                var topBentoH = MeasureH(topBento, exactBodyW);
                var remSplitH = exactBodyH - topBentoH - 9.0;

                if (remSplitH >= 68.0)
                {
                    var bottomSplit = new Grid { ClipToBounds = true };
                    bottomSplit.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    bottomSplit.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(11) });
                    bottomSplit.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.08, GridUnitType.Star) });

                    var leftColW = Math.Max(96.0, (exactBodyW - 11.0) * (1.0 / 2.08));
                    var rightColW = Math.Max(96.0, (exactBodyW - 11.0) * (1.08 / 2.08));

                    // Bottom-Left: Exact-Measured Official Quotas Column
                    var qCol = new StackPanel { ClipToBounds = true };
                    var qHead = new Grid { Margin = new Thickness(2, 0, 2, 5.5) };
                    qHead.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    qHead.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                    qHead.Children.Add(new TextBlock
                    {
                        Text = "各工具官方额度",
                        FontFamily = UiKit.AppFont,
                        FontSize = 11.4,
                        FontWeight = FontWeights.SemiBold,
                        Foreground = UiKit.MutedBrush
                    });
                    var qHeadH = MeasureH(qHead, leftColW);
                    var availLeftH = Math.Max(36.0, remSplitH - qHeadH);

                    var fittedQuotaBoxes = new List<Border>();
                    var usedLeftH = 0.0;
                    for (var qi = 0; qi < allFlatQuotas.Count; qi++)
                    {
                        var entry = allFlatQuotas[qi];
                        var shortLbl = $"{entry.Brand} · {UsageFormatting.QuotaDisplayTitle(entry.Quota)}";
                        var qRowBox = new Border
                        {
                            Background = UiKit.GlassTrackBrush,
                            BorderBrush = UiKit.GlassSubtleRimBrush,
                            BorderThickness = new Thickness(1),
                            CornerRadius = new CornerRadius(UiKit.PodRadius),
                            Padding = new Thickness(9, 5.8, 9, 5.8),
                            Child = BuildQuotaItemRow(entry.Tool, shortLbl, entry.Quota)
                        };
                        var boxH = MeasureH(qRowBox, leftColW);
                        var needed = fittedQuotaBoxes.Count == 0 ? boxH : boxH + 5.0;
                        if (fittedQuotaBoxes.Count > 0 && usedLeftH + needed > availLeftH)
                            break;
                        if (fittedQuotaBoxes.Count == 0 && boxH > availLeftH)
                            break;
                        fittedQuotaBoxes.Add(qRowBox);
                        usedLeftH += needed;
                    }

                    if (allFlatQuotas.Count > fittedQuotaBoxes.Count && cards.Count > firstQuotaCardIdx)
                    {
                        var moreQ = UiKit.LinkButton($"全部 {allFlatQuotas.Count} 项 →", () => JumpCompactCard(firstQuotaCardIdx));
                        Grid.SetColumn(moreQ, 1);
                        qHead.Children.Add(moreQ);
                    }
                    qCol.Children.Add(qHead);

                    if (fittedQuotaBoxes.Count == 0)
                    {
                        var barH = Math.Clamp(availLeftH - 54.0, 22.0, 46.0);
                        var trend = BuildSevenDayTrendPod(barHeight: barH);
                        if (MeasureH(trend, leftColW) <= availLeftH)
                            qCol.Children.Add(trend);
                    }
                    else
                    {
                        for (var qi = 0; qi < fittedQuotaBoxes.Count; qi++)
                        {
                            fittedQuotaBoxes[qi].Margin = new Thickness(0, 0, 0, qi < fittedQuotaBoxes.Count - 1 ? 5.0 : 0);
                            qCol.Children.Add(fittedQuotaBoxes[qi]);
                        }
                        var leftRem = availLeftH - usedLeftH - 6.0;
                        if (fittedQuotaBoxes.Count <= 2 && leftRem >= 78.0)
                        {
                            var barH = Math.Clamp(leftRem - 54.0, 22.0, 42.0);
                            var miniTrend = BuildSevenDayTrendPod(barHeight: barH);
                            if (MeasureH(miniTrend, leftColW) <= leftRem)
                            {
                                miniTrend.Margin = new Thickness(0, 6, 0, 0);
                                qCol.Children.Add(miniTrend);
                            }
                        }
                    }
                    Grid.SetColumn(qCol, 0);
                    bottomSplit.Children.Add(qCol);

                    var midLine = new Border { Width = 1, Background = UiKit.SplitDividerBrush, HorizontalAlignment = HorizontalAlignment.Center };
                    Grid.SetColumn(midLine, 1);
                    bottomSplit.Children.Add(midLine);

                    // Bottom-Right: Exact-Measured Active Tasks Column
                    var tCol = new StackPanel { ClipToBounds = true };
                    var tHead = new Grid { Margin = new Thickness(2, 0, 2, 5.5) };
                    tHead.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    tHead.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                    tHead.Children.Add(new TextBlock
                    {
                        Text = $"活跃会话 ({activeTasks.Count})",
                        FontFamily = UiKit.AppFont,
                        FontSize = 11.4,
                        FontWeight = FontWeights.SemiBold,
                        Foreground = UiKit.MutedBrush
                    });
                    var tHeadH = MeasureH(tHead, rightColW);
                    var availRightH = Math.Max(36.0, remSplitH - tHeadH);

                    var fittedRightTasks = new List<FrameworkElement>();
                    var usedRightH = 0.0;
                    for (var ti = 0; ti < activeTasks.Count; ti++)
                    {
                        var pod = BuildCompactTaskPod(activeTasks[ti], plan.ShowTaskMeta, showPreviewRow: false);
                        var pH = MeasureH(pod, rightColW);
                        var needed = fittedRightTasks.Count == 0 ? pH : pH + 5.0;
                        if (fittedRightTasks.Count > 0 && usedRightH + needed > availRightH)
                            break;
                        if (fittedRightTasks.Count == 0 && pH > availRightH)
                            break;
                        fittedRightTasks.Add(pod);
                        usedRightH += needed;
                    }

                    if (activeTasks.Count > fittedRightTasks.Count && cards.Count > firstTaskCardIdx)
                    {
                        var moreT = UiKit.LinkButton("翻页 →", () => JumpCompactCard(firstTaskCardIdx));
                        Grid.SetColumn(moreT, 1);
                        tHead.Children.Add(moreT);
                    }
                    tCol.Children.Add(tHead);

                    if (activeTasks.Count == 0)
                    {
                        var emptyBox = new Border
                        {
                            Background = UiKit.GlassTrackBrush,
                            BorderBrush = UiKit.GlassSubtleRimBrush,
                            BorderThickness = new Thickness(1),
                            CornerRadius = new CornerRadius(UiKit.PodRadius),
                            Padding = new Thickness(12, 14, 12, 14),
                            Child = UiKit.Body("当前没有进行中的任务\n电脑会话均已完成或待命", 11.4, UiKit.MutedBrush)
                        };
                        if (MeasureH(emptyBox, rightColW) <= availRightH)
                            tCol.Children.Add(emptyBox);
                    }
                    else
                    {
                        for (var ti = 0; ti < fittedRightTasks.Count; ti++)
                        {
                            fittedRightTasks[ti].Margin = new Thickness(0, 0, 0, ti < fittedRightTasks.Count - 1 ? 5.0 : 0);
                            tCol.Children.Add(fittedRightTasks[ti]);
                        }
                    }
                    Grid.SetColumn(tCol, 2);
                    bottomSplit.Children.Add(tCol);

                    Grid.SetRow(bottomSplit, 2);
                    bentoRoot.Children.Add(bottomSplit);
                }

                cards[0] = ("双栏全览看板", bentoRoot);
            }
            else
            {
                // Archetypes 1, 3, 4: Exact-Measured Single-Column MicroCapsule / CompactTile / StandardStack
                var heroCol = new StackPanel { ClipToBounds = true };
                var usedHeroH = 0.0;

                var primaryBlock = BuildLowestQuotaBlock(includeTodayRight: true);
                var hasPrimary = primaryBlock.Children.Count > 0;
                if (hasPrimary)
                {
                    heroCol.Children.Add(primaryBlock);
                    usedHeroH += MeasureH(primaryBlock, exactBodyW);
                }

                if (ui.CompactShowToolStrip)
                {
                    var toolSummaries = new List<(string Tool, string ShortLabel, string Metric, double? RemPct, bool Low)>();
                    foreach (var (toolId, shortName) in new[] { ("codex", "Codex"), ("claude", "Claude"), ("antigravity", "AGY") })
                    {
                        var p = providers.FirstOrDefault(x => x.Tool == toolId);
                        var minQuota = p?.Quotas?
                            .Where(q => !q.Stale && !q.IsExpired(now) && q.Remaining.HasValue)
                            .OrderBy(q => q.Remaining!.Value)
                            .FirstOrDefault();
                        if (minQuota?.Remaining is { } r)
                            toolSummaries.Add((toolId, shortName, UsageFormatting.Percentage(r), r, r <= 15.0));
                        else if (p?.TodayTokens is > 0)
                            toolSummaries.Add((toolId, shortName, UsageFormatting.CompactTokens(p.TodayTokens), null, false));
                    }

                    if (toolSummaries.Count > 0)
                    {
                        FrameworkElement BuildPillChipStrip()
                        {
                            var chipList = toolSummaries.Select(item =>
                            {
                                var capturedTool = item.Tool;
                                var cellGrid = new Grid { VerticalAlignment = VerticalAlignment.Center };
                                cellGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                                cellGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                                cellGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

                                var ico = UiKit.BrandImage(item.Tool, 12.5);
                                ico.Margin = new Thickness(0, 0, 4.5, 0);
                                ico.VerticalAlignment = VerticalAlignment.Center;
                                Grid.SetColumn(ico, 0);
                                cellGrid.Children.Add(ico);

                                var nameTxt = new TextBlock
                                {
                                    Text = item.ShortLabel,
                                    FontFamily = UiKit.AppFont,
                                    FontSize = 10.8,
                                    Foreground = UiKit.MutedBrush,
                                    TextWrapping = TextWrapping.NoWrap,
                                    TextTrimming = TextTrimming.CharacterEllipsis,
                                    VerticalAlignment = VerticalAlignment.Center
                                };
                                Grid.SetColumn(nameTxt, 1);
                                cellGrid.Children.Add(nameTxt);

                                var valTxt = new TextBlock
                                {
                                    Text = item.Metric,
                                    FontFamily = UiKit.AppFont,
                                    FontSize = 11,
                                    FontWeight = FontWeights.SemiBold,
                                    Foreground = item.Low ? UiKit.WarningBrush : UiKit.TextBrush,
                                    VerticalAlignment = VerticalAlignment.Center,
                                    Margin = new Thickness(4, 0, 0, 0)
                                };
                                Grid.SetColumn(valTxt, 2);
                                cellGrid.Children.Add(valTxt);

                                var chipBd = new Border
                                {
                                    Background = UiKit.GlassTrackBrush,
                                    BorderBrush = UiKit.GlassSubtleRimBrush,
                                    BorderThickness = new Thickness(0.8),
                                    CornerRadius = new CornerRadius(5.5),
                                    Padding = new Thickness(7.5, 4.2, 7.5, 4.2),
                                    SnapsToDevicePixels = true,
                                    Child = cellGrid
                                };

                                var microBtn = UiKit.BareButton(chipBd, () =>
                                {
                                    if (toolFirstQuotaCardIndex.TryGetValue(capturedTool, out var targetIdx))
                                        JumpCompactCard(targetIdx);
                                });
                                microBtn.ToolTip = $"点击跳转至 {MonitorTask.BrandFor(item.Tool)} 额度卡片";
                                return (FrameworkElement)microBtn;
                            }).ToList();

                            return BuildResponsiveCardGrid(chipList, Math.Min(3, chipList.Count), gap: 6.0);
                        }

                        FrameworkElement? chosenStrip = null;
                        var divTop = isNano ? 4.5 : 7.0;
                        var divBottom = isNano ? 4.0 : 6.0;
                        var divTotalH = hasPrimary ? (1.0 + divTop + divBottom) : 0.0;

                        if (plan.ShowVisualToolTiles)
                        {
                            var tileList = toolSummaries.Select(item =>
                            {
                                var capturedTool = item.Tool;
                                var tStack = new StackPanel();
                                var top = new Grid();
                                top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                                top.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                                top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

                                var ico = UiKit.BrandImage(item.Tool, 12.5);
                                ico.Margin = new Thickness(0, 0, 4.5, 0);
                                Grid.SetColumn(ico, 0);
                                top.Children.Add(ico);

                                var nm = new TextBlock
                                {
                                    Text = item.ShortLabel,
                                    FontFamily = UiKit.AppFont,
                                    FontSize = 11,
                                    Foreground = UiKit.MutedBrush,
                                    TextWrapping = TextWrapping.NoWrap,
                                    TextTrimming = TextTrimming.CharacterEllipsis
                                };
                                Grid.SetColumn(nm, 1);
                                top.Children.Add(nm);

                                var val = new TextBlock
                                {
                                    Text = item.Metric,
                                    FontFamily = UiKit.AppFont,
                                    FontSize = 11,
                                    FontWeight = FontWeights.SemiBold,
                                    Foreground = item.Low ? UiKit.WarningBrush : UiKit.TextBrush,
                                    Margin = new Thickness(4, 0, 0, 0)
                                };
                                Grid.SetColumn(val, 2);
                                top.Children.Add(val);
                                tStack.Children.Add(top);

                                if (item.RemPct is { } rp)
                                {
                                    var miniBar = BuildProgressBar(rp, rp <= 5 ? UiKit.DangerBrush : item.Low ? UiKit.WarningBrush : UiKit.AccentBrush, 3.5);
                                    if (miniBar is FrameworkElement mb) mb.Margin = new Thickness(0, 4.0, 0, 0);
                                    tStack.Children.Add(miniBar);
                                }

                                var tileBd = new Border
                                {
                                    Background = UiKit.GlassTrackBrush,
                                    BorderBrush = UiKit.GlassSubtleRimBrush,
                                    BorderThickness = new Thickness(1),
                                    CornerRadius = new CornerRadius(5.5),
                                    Padding = new Thickness(7.5, 5.0, 7.5, 5.0),
                                    Child = tStack
                                };
                                var btn = UiKit.BareButton(tileBd, () =>
                                {
                                    if (toolFirstQuotaCardIndex.TryGetValue(capturedTool, out var targetIdx))
                                        JumpCompactCard(targetIdx);
                                });
                                btn.ToolTip = $"点击查看 {MonitorTask.BrandFor(item.Tool)} 详细官方额度";
                                return (FrameworkElement)btn;
                            }).ToList();

                            var visualStrip = BuildResponsiveCardGrid(tileList, Math.Min(3, tileList.Count), gap: 6.0);
                            if (usedHeroH + divTotalH + MeasureH(visualStrip, exactBodyW) <= exactBodyH)
                                chosenStrip = visualStrip;
                        }

                        if (chosenStrip is null)
                        {
                            var pillStrip = BuildPillChipStrip();
                            if (usedHeroH + divTotalH + MeasureH(pillStrip, exactBodyW) <= exactBodyH)
                                chosenStrip = pillStrip;
                        }

                        if (chosenStrip is not null)
                        {
                            if (hasPrimary)
                            {
                                heroCol.Children.Add(new Border
                                {
                                    Height = 1,
                                    Background = UiKit.GlassSubtleRimBrush,
                                    Margin = new Thickness(0, divTop, 0, divBottom),
                                    Opacity = 0.65
                                });
                            }
                            heroCol.Children.Add(chosenStrip);
                            usedHeroH += divTotalH + MeasureH(chosenStrip, exactBodyW);
                        }
                    }
                }

                if (ui.CompactContentMode == "overview" && plan.OverviewTaskCount > 0 && activeTasks.Count > 0)
                {
                    var secDivH = heroCol.Children.Count > 0 ? 14.5 : 0.0;
                    var spotHeader = new Grid { Margin = new Thickness(0, 0, 0, 5.0) };
                    spotHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    spotHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                    var shLeft = new TextBlock
                    {
                        Text = $"当前焦点任务（共 {activeTasks.Count} 项）",
                        FontFamily = UiKit.AppFont,
                        FontSize = 11.2,
                        FontWeight = FontWeights.Medium,
                        Foreground = UiKit.MutedBrush,
                        TextWrapping = TextWrapping.NoWrap,
                        TextTrimming = TextTrimming.CharacterEllipsis,
                        VerticalAlignment = VerticalAlignment.Center
                    };
                    Grid.SetColumn(shLeft, 0);
                    spotHeader.Children.Add(shLeft);

                    var spotHeadH = MeasureH(spotHeader, exactBodyW);
                    var remForTasks = exactBodyH - usedHeroH - secDivH - spotHeadH;
                    var spotGap = 5.0;

                    var fittedSpotTasks = new List<(MonitorTask Task, FrameworkElement Pod, double Height)>();
                    var usedSpotH = 0.0;
                    for (var si = 0; si < activeTasks.Count && fittedSpotTasks.Count < 5; si++)
                    {
                        var pod = BuildCompactTaskPod(activeTasks[si], plan.ShowTaskMeta, showPreviewRow: false);
                        var pH = MeasureH(pod, exactBodyW);
                        var needed = fittedSpotTasks.Count == 0 ? pH : pH + spotGap;
                        if (usedSpotH + needed > remForTasks)
                            break;
                        fittedSpotTasks.Add((activeTasks[si], pod, pH));
                        usedSpotH += needed;
                    }

                    if (fittedSpotTasks.Count > 0)
                    {
                        if (heroCol.Children.Count > 0)
                        {
                            heroCol.Children.Add(new Border
                            {
                                Height = 1,
                                Background = UiKit.GlassSubtleRimBrush,
                                Margin = new Thickness(0, 7.5, 0, 6.0),
                                Opacity = 0.65
                            });
                        }

                        if (activeTasks.Count > fittedSpotTasks.Count && cards.Count > firstTaskCardIdx)
                        {
                            var jumpTasksBtn = UiKit.LinkButton("更多 ↓", () => JumpCompactCard(firstTaskCardIdx));
                            Grid.SetColumn(jumpTasksBtn, 1);
                            spotHeader.Children.Add(jumpTasksBtn);
                        }
                        heroCol.Children.Add(spotHeader);

                        IReadOnlyList<FrameworkElement> finalSpotPods = fittedSpotTasks.Select(x => x.Pod).ToList();
                        if (!isNano && fittedSpotTasks.Count <= 2 && usedSpotH < remForTasks * 0.68)
                        {
                            var richPods = fittedSpotTasks
                                .Select(x => BuildCompactTaskPod(x.Task, plan.ShowTaskMeta, showPreviewRow: true, expandedDetail: true))
                                .ToList();
                            var richStack = BuildResponsiveCardGrid(richPods, 1, gap: 5.5);
                            if (MeasureH(richStack, exactBodyW) <= remForTasks)
                                finalSpotPods = richPods;
                        }

                        heroCol.Children.Add(BuildResponsiveCardGrid(finalSpotPods, 1, gap: spotGap));
                        usedHeroH += secDivH + spotHeadH + MeasureH(heroCol.Children[^1], exactBodyW);
                    }
                }
                else if (ui.CompactContentMode == "overview" && activeTasks.Count == 0 && exactBodyH - usedHeroH >= 104.0)
                {
                    var remIdle = exactBodyH - usedHeroH - 9.0;
                    var barH = Math.Clamp(remIdle - 54.0, 24.0, 48.0);
                    var idleTrend = BuildSevenDayTrendPod(barHeight: barH);
                    if (MeasureH(idleTrend, exactBodyW) <= remIdle)
                    {
                        idleTrend.Margin = new Thickness(0, 9, 0, 0);
                        heroCol.Children.Add(idleTrend);
                    }
                }

                cards[0] = ("今日额度总览", heroCol);
            }
        }

        if (cards.Count == 0)
        {
            var fallback = UiKit.Body("所有显示模块均已关闭，点击右上角齿轮开启要展示的卡片。", 12, UiKit.MutedBrush);
            fallback.VerticalAlignment = VerticalAlignment.Center;
            fallback.TextAlignment = TextAlignment.Center;
            cards.Add(("精简小窗", fallback));
        }

        var totalCards = cards.Count;
        _compactCardIndex = ((_compactCardIndex % totalCards) + totalCards) % totalCards;
        var activeCard = cards[_compactCardIndex];

        var hudGrid = new Grid
        {
            Background = Brushes.Transparent,
            ClipToBounds = true
        };

        hudGrid.PreviewMouseWheel += (_, e) =>
        {
            if (totalCards <= 1) return;
            var ts = DateTimeOffset.UtcNow;
            if ((ts - _lastCompactWheelAt).TotalMilliseconds < 140)
            {
                e.Handled = true;
                return;
            }
            _lastCompactWheelAt = ts;
            StepCompactCard(e.Delta < 0 ? 1 : -1);
            e.Handled = true;
        };

        var centeredBodyHost = UiKit.SafeVerticalCenterHost(activeCard.Content);
        if (_compactSlideDirection != 0)
        {
            var slideTransform = new TranslateTransform(0, _compactSlideDirection * 10.0);
            centeredBodyHost.RenderTransform = slideTransform;
            centeredBodyHost.Opacity = 0.38;
            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            slideTransform.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(0.0, TimeSpan.FromMilliseconds(145)) { EasingFunction = ease });
            centeredBodyHost.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(1.0, TimeSpan.FromMilliseconds(145)) { EasingFunction = ease });
        }

        // Single unified sculpted Liquid Glass card with integrated slim footer & unified 8.5px window-matched curvature!
        var unifiedCardGrid = new Grid();
        unifiedCardGrid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        unifiedCardGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        Grid.SetRow(centeredBodyHost, 0);
        unifiedCardGrid.Children.Add(centeredBodyHost);

        var footerStack = new StackPanel
        {
            Margin = new Thickness(0, footerTopMargin, 0, -2)
        };
        footerStack.Children.Add(new Border
        {
            Height = 1,
            Background = UiKit.GlassSubtleRimBrush,
            Opacity = 0.48,
            Margin = new Thickness(0, 0, 0, footerDivMargin)
        });

        var showCenterDots = totalCards > 1 && winW >= 364;
        var bottomBarGrid = new Grid { Height = footerBarH };
        if (showCenterDots)
        {
            bottomBarGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            bottomBarGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            bottomBarGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        }
        else
        {
            bottomBarGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            bottomBarGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        }

        var pageText = new TextBlock
        {
            FontFamily = UiKit.AppFont,
            FontSize = 10.6,
            TextWrapping = TextWrapping.NoWrap,
            TextTrimming = TextTrimming.CharacterEllipsis,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 6, 0)
        };
        if (totalCards > 1)
        {
            pageText.Inlines.Add(new System.Windows.Documents.Run($"{_compactCardIndex + 1}/{totalCards}")
            {
                FontWeight = FontWeights.SemiBold,
                Foreground = UiKit.AccentBrush
            });
            pageText.Inlines.Add(new System.Windows.Documents.Run($" · {activeCard.Title}")
            {
                FontWeight = FontWeights.Normal,
                Foreground = UiKit.MutedBrush
            });
        }
        else
        {
            pageText.Text = activeCard.Title;
            pageText.Foreground = UiKit.MutedBrush;
        }

        var pageLabelBtn = UiKit.BareButton(pageText, () =>
        {
            if (totalCards > 1) StepCompactCard(1);
        });
        pageLabelBtn.HorizontalAlignment = HorizontalAlignment.Left;
        pageLabelBtn.VerticalAlignment = VerticalAlignment.Center;
        if (totalCards > 1)
            pageLabelBtn.ToolTip = "滚轮上下滑动或点右侧 ▲ / ▼ 切换卡片；拖动小窗边缘可智能重排单栏/双栏布局";
        Grid.SetColumn(pageLabelBtn, 0);
        bottomBarGrid.Children.Add(pageLabelBtn);

        if (showCenterDots)
        {
            var dotsRow = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(4, 0, 4, 0)
            };
            var maxDots = Math.Min(totalCards, 7);
            var startDot = totalCards <= maxDots
                ? 0
                : Math.Clamp(_compactCardIndex - maxDots / 2, 0, totalCards - maxDots);

            for (var di = startDot; di < startDot + maxDots; di++)
            {
                var capturedIdx = di;
                var isCurrent = di == _compactCardIndex;
                var dotVisual = new Border
                {
                    Width = isCurrent ? 11.0 : 4.5,
                    Height = 4.5,
                    CornerRadius = new CornerRadius(2.25),
                    Background = isCurrent ? UiKit.AccentBrush : UiKit.MutedBrush,
                    Opacity = isCurrent ? 0.95 : 0.36
                };
                var dotHit = new Border
                {
                    Background = Brushes.Transparent,
                    Padding = new Thickness(2, 4, 2, 4),
                    Child = dotVisual
                };
                var dotBtn = UiKit.BareButton(dotHit, () => JumpCompactCard(capturedIdx));
                dotBtn.ToolTip = $"{di + 1} / {totalCards} · {cards[di].Title}";
                if (!isCurrent)
                {
                    dotBtn.MouseEnter += (_, _) => dotVisual.Opacity = 0.85;
                    dotBtn.MouseLeave += (_, _) => dotVisual.Opacity = 0.36;
                }
                dotsRow.Children.Add(dotBtn);
            }
            Grid.SetColumn(dotsRow, 1);
            bottomBarGrid.Children.Add(dotsRow);
        }

        var navCluster = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Center
        };
        var stepBtnSize = 19.0;
        var upBtn = UiKit.IconButton("chevron-up", "上一张卡片 (滚轮向上 / ↑)", () => StepCompactCard(-1), enabled: totalCards > 1, boxSize: stepBtnSize, iconSize: 11.5, highContrast: true, inCapsule: true);
        upBtn.Margin = new Thickness(0, 0, 1.5, 0);
        var downBtn = UiKit.IconButton("chevron-down", "下一张卡片 (滚轮向下 / ↓)", () => StepCompactCard(1), enabled: totalCards > 1, boxSize: stepBtnSize, iconSize: 11.5, highContrast: true, inCapsule: true);
        navCluster.Children.Add(upBtn);
        navCluster.Children.Add(downBtn);

        Grid.SetColumn(navCluster, showCenterDots ? 2 : 1);
        bottomBarGrid.Children.Add(navCluster);

        footerStack.Children.Add(bottomBarGrid);
        Grid.SetRow(footerStack, 1);
        unifiedCardGrid.Children.Add(footerStack);

        var glassCard = UiKit.ElevatedGlass(unifiedCardGrid, padding: cardPad, radius: UiKit.CardRadius);
        glassCard.Margin = new Thickness(sidePad, 2, sidePad, sidePad);
        hudGrid.Children.Add(glassCard);

        return hudGrid;
    }

    private (string Tool, string Brand, UsageQuotaDto Quota)? FindLowestQuota(DateTimeOffset now)
    {
        if (_controller.Usage?.Providers is null) return null;
        (string Tool, string Brand, UsageQuotaDto Quota)? best = null;
        foreach (var p in _controller.Usage.Providers)
        {
            if (p.Quotas is null) continue;
            foreach (var q in p.Quotas)
            {
                if (q.Stale || q.IsExpired(now) || q.Remaining is not { } rem) continue;
                if (best is null || rem < best.Value.Quota.Remaining)
                    best = (p.Tool, p.Brand, q);
            }
        }
        return best;
    }

    // ==================== WORKSPACE SHELL & FLOATING LIQUID GLASS RAIL ====================

    private UIElement BuildWorkspaceShell()
    {
        var plan = _lastMainPlan ?? CurrentMainShellPlan();
        var grid = new Grid { Background = Brushes.Transparent };
        _sidebarColDef = new ColumnDefinition { Width = new GridLength(plan.SidebarWidth) };
        grid.ColumnDefinitions.Add(_sidebarColDef);
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        _sidebarDockPanel = new DockPanel
        {
            LastChildFill = true,
            Margin = plan.CollapseSidebar ? new Thickness(0, 2, 0, 2) : new Thickness(4)
        };

        _sidebarBottomHost = new StackPanel { Margin = new Thickness(2, 8, 2, 2) };
        DockPanel.SetDock(_sidebarBottomHost, Dock.Bottom);
        _sidebarDockPanel.Children.Add(_sidebarBottomHost);

        _sidebarNavHost = new StackPanel { Margin = new Thickness(2, 4, 2, 0) };
        _sidebarDockPanel.Children.Add(_sidebarNavHost);

        _sidebarRailBorder = UiKit.ElevatedGlass(_sidebarDockPanel, padding: 6, radius: UiKit.CardRadius, fill: UiKit.GlassRailBrush);
        _sidebarRailBorder.Margin = plan.CollapseSidebar ? new Thickness(10, 6, 4, 12) : new Thickness(12, 6, 6, 12);
        Grid.SetColumn(_sidebarRailBorder, 0);
        grid.Children.Add(_sidebarRailBorder);

        if (_page == NavPage.Tasks)
        {
            var tasksLayout = BuildTasksSplitShell();
            Grid.SetColumn(tasksLayout, 1);
            grid.Children.Add(tasksLayout);
        }
        else
        {
            _taskListColDef = null;
            _pageBodyHost = new ContentControl { HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch };
            Grid.SetColumn(_pageBodyHost, 1);
            grid.Children.Add(_pageBodyHost);
        }

        return grid;
    }

    private void UpdateSidebarState()
    {
        var plan = _lastMainPlan ?? CurrentMainShellPlan();
        var collapsed = plan.CollapseSidebar;

        if (_sidebarNavHost is not null)
        {
            _sidebarNavHost.Children.Clear();
            var activeTaskCount = _controller.IsFresh
                ? _controller.Tasks.Count(t => !t.Archived && t.Stale != true && t.EffectiveStatus is "running" or "waiting" or "error")
                : 0;
            var hasWaitingTask = _controller.IsFresh
                && _controller.Tasks.Any(t => !t.Archived && t.Stale != true && t.EffectiveStatus == "waiting");
            var onlineDevCount = _controller.IsFresh ? _controller.OnlineDeviceCount : 0;

            foreach (var (nav, glyph, label) in new[]
                     {
                         (NavPage.Tasks, "tasks", "任务"),
                         (NavPage.Devices, "devices", "电脑"),
                         (NavPage.Usage, "usage", "用量"),
                         (NavPage.Account, "account", "我的")
                     })
            {
                var active = _page == nav;
                var ink = active ? UiKit.AccentBrush : UiKit.TextBrush;
                var badgeCount = nav == NavPage.Tasks ? activeTaskCount : nav == NavPage.Devices ? onlineDevCount : 0;
                var isAttention = nav == NavPage.Tasks && hasWaitingTask;

                Border box;
                if (collapsed)
                {
                    var iconCell = new Grid { Width = 38, Height = 36 };
                    if (active)
                    {
                        iconCell.Children.Add(new Border
                        {
                            Width = 3,
                            Height = 15,
                            CornerRadius = new CornerRadius(1.5),
                            Background = UiKit.AccentBrush,
                            HorizontalAlignment = HorizontalAlignment.Left,
                            VerticalAlignment = VerticalAlignment.Center,
                            Margin = new Thickness(-2, 0, 0, 0)
                        });
                    }
                    iconCell.Children.Add(new MonitorIcon(glyph, active ? UiKit.AccentBrush : UiKit.MutedBrush, 18.5)
                    {
                        HorizontalAlignment = HorizontalAlignment.Center,
                        VerticalAlignment = VerticalAlignment.Center
                    });

                    if (badgeCount > 0)
                    {
                        iconCell.Children.Add(new Border
                        {
                            Background = isAttention ? UiKit.WarningBrush : UiKit.AccentBrush,
                            CornerRadius = new CornerRadius(6.5),
                            MinWidth = 13,
                            Height = 13,
                            Padding = new Thickness(3, 0, 3, 0),
                            HorizontalAlignment = HorizontalAlignment.Right,
                            VerticalAlignment = VerticalAlignment.Top,
                            Margin = new Thickness(0, 2, 1, 0),
                            Child = new TextBlock
                            {
                                Text = badgeCount > 9 ? "9+" : badgeCount.ToString(),
                                FontFamily = UiKit.AppFont,
                                FontSize = 8.5,
                                FontWeight = FontWeights.Bold,
                                Foreground = UiKit.OnAccentBrush,
                                HorizontalAlignment = HorizontalAlignment.Center,
                                VerticalAlignment = VerticalAlignment.Center
                            }
                        });
                    }

                    box = new Border
                    {
                        Background = active ? UiKit.GlassTrayBrush : Brushes.Transparent,
                        BorderBrush = active ? UiKit.GlassTrayRimBrush : Brushes.Transparent,
                        BorderThickness = new Thickness(1),
                        CornerRadius = new CornerRadius(UiKit.PodRadius),
                        Padding = new Thickness(1),
                        Margin = new Thickness(0, 0, 0, 5),
                        HorizontalAlignment = HorizontalAlignment.Center,
                        SnapsToDevicePixels = true,
                        Child = iconCell
                    };
                }
                else
                {
                    var itemDock = new DockPanel { LastChildFill = true };
                    if (badgeCount > 0)
                    {
                        var badgePill = new Border
                        {
                            Background = isAttention ? UiKit.WarningBrush : UiKit.GlassTrackBrush,
                            BorderBrush = isAttention ? Brushes.Transparent : UiKit.GlassSubtleRimBrush,
                            BorderThickness = new Thickness(1),
                            CornerRadius = new CornerRadius(5.5),
                            Padding = new Thickness(6.5, 1.5, 6.5, 1.5),
                            VerticalAlignment = VerticalAlignment.Center,
                            Margin = new Thickness(6, 0, 0, 0),
                            Child = new TextBlock
                            {
                                Text = badgeCount.ToString(),
                                FontFamily = UiKit.AppFont,
                                FontSize = 11,
                                FontWeight = FontWeights.SemiBold,
                                Foreground = isAttention ? UiKit.OnAccentBrush : (active ? UiKit.AccentBrush : UiKit.MutedBrush)
                            }
                        };
                        DockPanel.SetDock(badgePill, Dock.Right);
                        itemDock.Children.Add(badgePill);
                    }

                    var itemGrid = new Grid();
                    if (active)
                    {
                        itemGrid.Children.Add(new Border
                        {
                            Width = 3,
                            Height = 15,
                            CornerRadius = new CornerRadius(1.5),
                            Background = UiKit.AccentBrush,
                            HorizontalAlignment = HorizontalAlignment.Left,
                            VerticalAlignment = VerticalAlignment.Center,
                            Margin = new Thickness(-6, 0, 0, 0)
                        });
                    }

                    var row = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
                    row.Children.Add(new MonitorIcon(glyph, active ? UiKit.AccentBrush : UiKit.MutedBrush, 17.5) { Margin = new Thickness(2, 0, 11, 0) });
                    row.Children.Add(new TextBlock
                    {
                        Text = label,
                        FontFamily = UiKit.AppFont,
                        FontSize = 14,
                        FontWeight = active ? FontWeights.SemiBold : FontWeights.Normal,
                        Foreground = ink,
                        VerticalAlignment = VerticalAlignment.Center
                    });
                    itemGrid.Children.Add(row);
                    itemDock.Children.Add(itemGrid);

                    box = new Border
                    {
                        Background = active ? UiKit.GlassTrayBrush : Brushes.Transparent,
                        BorderBrush = active ? UiKit.GlassTrayRimBrush : Brushes.Transparent,
                        BorderThickness = new Thickness(1),
                        CornerRadius = new CornerRadius(UiKit.PodRadius),
                        Padding = new Thickness(12, 9.5, 12, 9.5),
                        Margin = new Thickness(0, 0, 0, 4),
                        SnapsToDevicePixels = true,
                        Child = itemDock
                    };
                }

                var target = nav;
                var btn = UiKit.BareButton(box, () => SwitchPage(target));
                if (collapsed)
                    btn.ToolTip = badgeCount > 0 ? $"{label}（{badgeCount}）" : label;
                if (!active)
                {
                    btn.MouseEnter += (_, _) => box.Background = UiKit.HoverBrush;
                    btn.MouseLeave += (_, _) => box.Background = Brushes.Transparent;
                }
                _sidebarNavHost.Children.Add(btn);
            }
        }

        if (_sidebarBottomHost is not null)
        {
            _sidebarBottomHost.Children.Clear();
            var userLabel = _controller.Account?.User?.DisplayOrUsername;
            var onlinePart = _controller.IsFresh
                ? $"● {_controller.OnlineDeviceCount} 台在线"
                : "○ 连接待更新";
            var fullStatus = string.IsNullOrWhiteSpace(userLabel)
                ? onlinePart
                : $"{onlinePart} · {userLabel}";

            if (collapsed)
            {
                _sidebarBottomHost.Margin = new Thickness(0, 6, 0, 2);
                var miniIconBtn = UiKit.IconButton("mini", "切换精简小窗", ToggleCompactMode, boxSize: 36, iconSize: 16.5);
                miniIconBtn.HorizontalAlignment = HorizontalAlignment.Center;
                miniIconBtn.Margin = new Thickness(0, 0, 0, 8);
                _sidebarBottomHost.Children.Add(miniIconBtn);

                var dotHolder = new Border
                {
                    Width = 30,
                    Height = 20,
                    Background = Brushes.Transparent,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    ToolTip = fullStatus,
                    Child = new Border
                    {
                        Width = 8,
                        Height = 8,
                        CornerRadius = new CornerRadius(4),
                        Background = _controller.IsFresh ? UiKit.GreenBrush : UiKit.MutedBrush,
                        HorizontalAlignment = HorizontalAlignment.Center,
                        VerticalAlignment = VerticalAlignment.Center
                    }
                };
                _sidebarBottomHost.Children.Add(dotHolder);
            }
            else
            {
                _sidebarBottomHost.Margin = new Thickness(4, 8, 4, 2);
                var miniBtn = UiKit.SecondaryButton("切换精简小窗", ToggleCompactMode, glyph: "mini");
                miniBtn.Margin = new Thickness(0, 0, 0, 12);
                _sidebarBottomHost.Children.Add(miniBtn);

                var statusTxt = UiKit.Body(fullStatus, 12, _controller.IsFresh ? UiKit.GreenBrush : UiKit.MutedBrush);
                statusTxt.Margin = new Thickness(6, 0, 4, 2);
                statusTxt.TextTrimming = TextTrimming.CharacterEllipsis;
                statusTxt.TextWrapping = TextWrapping.NoWrap;
                statusTxt.ToolTip = fullStatus;
                _sidebarBottomHost.Children.Add(statusTxt);
            }
        }
    }

    // ==================== TASKS MASTER-DETAIL ====================

    private UIElement BuildTasksSplitShell()
    {
        var plan = _lastMainPlan ?? CurrentMainShellPlan();
        var split = new Grid();
        _taskListColDef = new ColumnDefinition { Width = new GridLength(plan.TaskListWidth) };
        split.ColumnDefinitions.Add(_taskListColDef);
        split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1) });
        split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var leftPane = new DockPanel { Background = Brushes.Transparent };
        var topControls = new StackPanel { Margin = new Thickness(14, 16, 14, 8) };

        var headerRow = new DockPanel { Margin = new Thickness(2, 0, 2, 11) };
        headerRow.Children.Add(UiKit.Heading("工作台", 23));
        var headerRight = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
        _searchMatchSummary = UiKit.Body("", 12, UiKit.MutedBrush);
        _searchMatchSummary.VerticalAlignment = VerticalAlignment.Center;
        _searchMatchSummary.Margin = new Thickness(0, 0, 6, 0);
        headerRight.Children.Add(_searchMatchSummary);
        _pendingEntryHost = new ContentControl { VerticalAlignment = VerticalAlignment.Center };
        headerRight.Children.Add(_pendingEntryHost);
        var newTaskBtn = UiKit.IconButton("plus", "在已连接电脑新建远程任务", () => OpenNewTaskFlow(), boxSize: 28, iconSize: 15.5, highContrast: true);
        newTaskBtn.Margin = new Thickness(6, 0, 0, 0);
        headerRight.Children.Add(newTaskBtn);
        DockPanel.SetDock(headerRight, Dock.Right);
        headerRow.Children.Add(headerRight);
        topControls.Children.Add(headerRow);

        // Liquid Glass Search Bar with icon + placeholder + clear button
        var searchDock = new DockPanel();
        var searchIcon = new MonitorIcon("search", UiKit.MutedBrush, 15.5) { Margin = new Thickness(10, 0, 6, 0), VerticalAlignment = VerticalAlignment.Center };
        DockPanel.SetDock(searchIcon, Dock.Left);
        searchDock.Children.Add(searchIcon);

        var searchInputLayer = new Grid();
        var searchPlaceholder = new TextBlock
        {
            Text = "搜索任务、项目或电脑（空格分隔多词）",
            FontFamily = UiKit.AppFont,
            FontSize = 13,
            Foreground = UiKit.MutedBrush,
            Padding = new Thickness(4, 7.5, 6, 7.5),
            VerticalAlignment = VerticalAlignment.Center,
            IsHitTestVisible = false,
            Visibility = string.IsNullOrEmpty(_controller.SearchQuery) ? Visibility.Visible : Visibility.Collapsed
        };
        var searchBox = new TextBox
        {
            Text = _controller.SearchQuery,
            FontFamily = UiKit.AppFont,
            FontSize = 13.5,
            Foreground = UiKit.TextBrush,
            CaretBrush = UiKit.AccentBrush,
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Padding = new Thickness(2, 7.5, 6, 7.5),
            VerticalContentAlignment = VerticalAlignment.Center
        };
        _taskSearchBox = searchBox;
        searchInputLayer.Children.Add(searchPlaceholder);
        searchInputLayer.Children.Add(searchBox);

        var clearBtn = UiKit.IconButton("close", "清除搜索", () => searchBox.Text = "", boxSize: 26, iconSize: 14);
        clearBtn.Visibility = string.IsNullOrEmpty(searchBox.Text) ? Visibility.Collapsed : Visibility.Visible;
        DockPanel.SetDock(clearBtn, Dock.Right);
        searchDock.Children.Add(clearBtn);
        searchDock.Children.Add(searchInputLayer);

        var searchContainer = new Border
        {
            Background = UiKit.GlassInputBrush,
            BorderBrush = UiKit.GlassSubtleRimBrush,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(UiKit.ControlRadius),
            Padding = new Thickness(2, 1, 5, 1),
            Margin = new Thickness(0, 0, 0, 10),
            SnapsToDevicePixels = true,
            Child = searchDock
        };

        searchBox.GotKeyboardFocus += (_, _) => searchContainer.BorderBrush = UiKit.AccentBrush;
        searchBox.LostKeyboardFocus += (_, _) => searchContainer.BorderBrush = UiKit.GlassSubtleRimBrush;
        searchBox.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape && !string.IsNullOrEmpty(searchBox.Text))
            {
                e.Handled = true;
                searchBox.Text = "";
            }
        };
        searchBox.TextChanged += (_, _) =>
        {
            _controller.SearchQuery = searchBox.Text;
            var empty = string.IsNullOrEmpty(searchBox.Text);
            searchPlaceholder.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
            clearBtn.Visibility = empty ? Visibility.Collapsed : Visibility.Visible;
            UpdateTasksPageInPlace();
        };
        topControls.Children.Add(searchContainer);

        _taskFilterHost = new StackPanel();
        topControls.Children.Add(_taskFilterHost);

        DockPanel.SetDock(topControls, Dock.Top);
        leftPane.Children.Add(topControls);

        var listScroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(14, 2, 14, 20) };
        _taskListHost = new StackPanel();
        listScroll.Content = _taskListHost;
        leftPane.Children.Add(listScroll);

        Grid.SetColumn(leftPane, 0);
        split.Children.Add(leftPane);

        var divider = new Border { Background = UiKit.SplitDividerBrush, Margin = new Thickness(0, 12, 0, 14) };
        Grid.SetColumn(divider, 1);
        split.Children.Add(divider);

        _taskDetailRightHost = new ContentControl { HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch };
        _mountedDetailTaskId = null;
        Grid.SetColumn(_taskDetailRightHost, 2);
        split.Children.Add(_taskDetailRightHost);

        return split;
    }

    private Brush TaskStatusColor(MonitorTask task)
    {
        if (!_controller.IsFresh || task.Stale == true) return UiKit.MutedBrush;
        return task.EffectiveStatus switch
        {
            "running" => UiKit.GreenBrush,
            "waiting" or "error" => UiKit.WarningBrush,
            _ => UiKit.MutedBrush
        };
    }

    private StackPanel BuildStatusDotRow(MonitorTask task)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        var color = TaskStatusColor(task);
        row.Children.Add(new Border
        {
            Width = 6.5,
            Height = 6.5,
            CornerRadius = new CornerRadius(4),
            Background = color,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 6, 0)
        });
        row.Children.Add(new TextBlock
        {
            Text = _controller.IsFresh && task.Stale != true ? task.StatusTitle : "状态待更新",
            FontFamily = UiKit.AppFont,
            FontSize = 12,
            Foreground = color,
            VerticalAlignment = VerticalAlignment.Center
        });
        var contMode = task.EffectiveContinuation(_controller.IsFresh, _controller.IsDeviceOnline(task.DeviceId));
        var contLabel = MonitorTask.ContinuationTitleFor(contMode);
        if (!string.IsNullOrEmpty(contLabel))
        {
            var isContinuable = contMode == "continuable";
            row.Children.Add(new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = isContinuable ? UiKit.AccentBrush : UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(6),
                Padding = new Thickness(6, 1, 6, 1),
                Margin = new Thickness(6, 0, 0, 0),
                VerticalAlignment = VerticalAlignment.Center,
                Child = new TextBlock
                {
                    Text = contLabel,
                    FontFamily = UiKit.AppFont,
                    FontSize = 11,
                    FontWeight = isContinuable ? FontWeights.SemiBold : FontWeights.Normal,
                    Foreground = isContinuable ? UiKit.AccentBrush : UiKit.MutedBrush
                }
            });
        }
        if (!string.IsNullOrEmpty(task.UpdatedAt))
        {
            row.Children.Add(new TextBlock
            {
                Text = " · " + UsageFormatting.RelativeTime(task.UpdatedAt),
                FontFamily = UiKit.AppFont,
                FontSize = 11.5,
                Foreground = UiKit.MutedBrush,
                VerticalAlignment = VerticalAlignment.Center
            });
        }
        return row;
    }

    private void UpdateTasksPageInPlace()
    {
        var plan = _lastMainPlan ?? CurrentMainShellPlan();
        var narrowList = plan.TaskListWidth < 340;
        var wideList = plan.TaskListWidth >= 380;
        var visible = _controller.VisibleTasks;
        if (_searchMatchSummary is not null)
        {
            _searchMatchSummary.Text = string.IsNullOrWhiteSpace(_controller.SearchQuery)
                ? ""
                : $"匹配 {visible.Count} 项";
        }

        if (_pendingEntryHost is not null)
        {
            var pendingTasks = _controller.PendingTasks;
            if (pendingTasks.Count > 0)
            {
                var pillBorder = new Border
                {
                    Background = UiKit.GlassTrackBrush,
                    BorderBrush = UiKit.WarningBrush,
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(UiKit.ControlRadius),
                    Padding = new Thickness(9, 3.5, 9, 3.5),
                    Child = new TextBlock
                    {
                        Text = $"待处理 {pendingTasks.Count}",
                        FontFamily = UiKit.AppFont,
                        FontSize = 12,
                        FontWeight = FontWeights.SemiBold,
                        Foreground = UiKit.WarningBrush
                    }
                };
                var pendingBtn = UiKit.BareButton(pillBorder, () =>
                    Dialogs.ShowPendingTasksDialog(this, _controller, SelectTask));
                pendingBtn.ToolTip = "查看所有在线电脑待批准或待回复选项的任务（不改动当前筛选）";
                pendingBtn.MouseEnter += (_, _) => pillBorder.Background = UiKit.GlassCardHoverBrush;
                pendingBtn.MouseLeave += (_, _) => pillBorder.Background = UiKit.GlassTrackBrush;
                _pendingEntryHost.Content = pendingBtn;
            }
            else
            {
                _pendingEntryHost.Content = null;
            }
        }

        if (_taskFilterHost is not null)
        {
            _taskFilterHost.Children.Clear();

            var toolItems = Enum.GetValues<ToolFilter>().Select(tf =>
            {
                var target = tf;
                var countInScope = _controller.Tasks.Count(t => t.Matches(tf, _controller.Scope, _controller.IsFresh, _controller.SearchQuery));
                var shortTitle = tf == ToolFilter.Claude
                    ? "Claude"
                    : (narrowList && tf == ToolFilter.Antigravity ? "AGY" : tf.Title());
                var label = countInScope > 0 ? $"{shortTitle} {countInScope}" : shortTitle;
                return (label, _controller.Tool == tf, (Action)(() => _ = _controller.SaveToolFilterAsync(target)));
            }).ToList();
            _taskFilterHost.Children.Add(UiKit.SegmentedControl(toolItems, equalColumns: false));

            var scopeItems = Enum.GetValues<TaskScope>().Select(sc =>
            {
                var target = sc;
                return (sc.Title(), _controller.Scope == sc, (Action)(() =>
                {
                    _controller.Scope = target;
                    UpdateTasksPageInPlace();
                }));
            }).ToList();
            var scopeSeg = UiKit.SegmentedControl(scopeItems, equalColumns: false);
            scopeSeg.Margin = new Thickness(0, 6, 0, 2);
            _taskFilterHost.Children.Add(scopeSeg);
        }

        if (_taskListHost is not null)
        {
            _taskListHost.Children.Clear();
            if (!string.IsNullOrEmpty(_controller.ConnectionMessage))
            {
                var warnBtn = UiKit.SecondaryButton(_controller.ConnectionMessage, async () => await _controller.RefreshWorkbenchAsync(), textBrush: UiKit.WarningBrush);
                warnBtn.Margin = new Thickness(0, 0, 0, 10);
                _taskListHost.Children.Add(warnBtn);
            }

            if (visible.Count == 0)
            {
                var hidden = WorkbenchVisibilityPolicy.HiddenFilterSummary(
                    _controller.Tasks, _controller.Devices, _controller.Tool, _controller.Scope,
                    _controller.SearchQuery, _controller.IsFresh, visible.Count);
                if (hidden.ShowResetAction && hidden.HiddenCount > 0)
                {
                    var hiddenStack = new StackPanel { Margin = new Thickness(8, 34, 8, 0), HorizontalAlignment = HorizontalAlignment.Center };
                    var hiddenMsg = UiKit.Body(hidden.Message, 13.5, UiKit.MutedBrush);
                    hiddenMsg.TextAlignment = TextAlignment.Center;
                    hiddenStack.Children.Add(hiddenMsg);

                    var showAllBtn = UiKit.SecondaryButton("查看全部", () =>
                    {
                        _controller.Scope = TaskScope.All;
                        _controller.SearchQuery = "";
                        if (_taskSearchBox is not null && !string.IsNullOrEmpty(_taskSearchBox.Text))
                            _taskSearchBox.Text = "";
                        _ = _controller.SaveToolFilterAsync(ToolFilter.All);
                        UpdateTasksPageInPlace();
                    });
                    showAllBtn.HorizontalAlignment = HorizontalAlignment.Center;
                    showAllBtn.Margin = new Thickness(0, 12, 0, 0);
                    hiddenStack.Children.Add(showAllBtn);
                    _taskListHost.Children.Add(hiddenStack);
                }
                else
                {
                    var emptyText = !string.IsNullOrWhiteSpace(_controller.SearchQuery)
                        ? "没有匹配的任务\n试试其他关键词，或调整来源与状态筛选"
                        : _controller.Scope == TaskScope.Archived
                        ? "暂无归档任务"
                        : "这里很清爽\n切换「全部任务」可查看历史记录";
                    var empty = UiKit.Body(emptyText, 13.5, UiKit.MutedBrush);
                    empty.Margin = new Thickness(8, 36, 8, 0);
                    empty.TextAlignment = TextAlignment.Center;
                    _taskListHost.Children.Add(empty);
                }
            }
            else
            {
                foreach (var task in visible)
                {
                    var isSelected = task.Id == _selectedTaskId;
                    var isWaiting = _controller.IsFresh && task.Stale != true && task.EffectiveStatus == "waiting";
                    var col = new StackPanel();
                    col.Children.Add(UiKit.TaskIdentityBadge(task.Tool, task.DeviceName ?? "电脑", 15));

                    var titleBlock = UiKit.Heading(task.Title, 14.5);
                    titleBlock.Margin = new Thickness(0, 6, 0, 6);
                    col.Children.Add(titleBlock);

                    var statusRow = BuildStatusDotRow(task);
                    if (!string.IsNullOrWhiteSpace(task.Project))
                    {
                        var proj = Path.GetFileName(task.Project!.TrimEnd('/', '\\'));
                        if (string.IsNullOrWhiteSpace(proj)) proj = task.Project.Trim();
                        statusRow.Children.Add(new TextBlock
                        {
                            Text = $" · {proj}",
                            FontFamily = UiKit.AppFont,
                            FontSize = 11.5,
                            Foreground = UiKit.MutedBrush,
                            TextTrimming = TextTrimming.CharacterEllipsis,
                            MaxWidth = narrowList ? 82 : 145,
                            VerticalAlignment = VerticalAlignment.Center
                        });
                    }
                    col.Children.Add(statusRow);

                    if (wideList && !string.IsNullOrWhiteSpace(task.Preview) && task.EffectiveStatus is "running" or "waiting" or "error")
                    {
                        var snippet = task.Preview!.Replace("\r", " ").Replace("\n", " ").Trim();
                        if (snippet.Length > 0)
                        {
                            col.Children.Add(new TextBlock
                            {
                                Text = snippet,
                                FontFamily = UiKit.AppFont,
                                FontSize = 11.5,
                                Foreground = UiKit.MutedBrush,
                                Opacity = 0.85,
                                TextTrimming = TextTrimming.CharacterEllipsis,
                                TextWrapping = TextWrapping.NoWrap,
                                Margin = new Thickness(0, 5, 0, 0)
                            });
                        }
                    }

                    var cardContent = new Grid();
                    if (isSelected)
                    {
                        cardContent.Children.Add(new Border
                        {
                            Width = 3,
                            CornerRadius = new CornerRadius(1.5),
                            Background = UiKit.AccentBrush,
                            HorizontalAlignment = HorizontalAlignment.Left,
                            Margin = new Thickness(-8, 2, 0, 2)
                        });
                    }
                    cardContent.Children.Add(col);

                    var defaultBorder = isSelected ? UiKit.AccentBrush : isWaiting ? UiKit.WarningBrush : UiKit.GlassRimBrush;
                    var box = new Border
                    {
                        Background = isSelected ? UiKit.GlassSelectedCardBrush : UiKit.GlassCardBrush,
                        BorderBrush = defaultBorder,
                        BorderThickness = new Thickness(1),
                        CornerRadius = new CornerRadius(UiKit.CardRadius),
                        Padding = new Thickness(15, 12.5, 15, 12.5),
                        Margin = new Thickness(0, 0, 0, 8),
                        SnapsToDevicePixels = true,
                        Child = cardContent
                    };
                    var captured = task;
                    var menu = new ContextMenu();
                    var archiveItem = new MenuItem { Header = captured.Archived ? "恢复任务" : "归档任务" };
                    archiveItem.Click += async (_, _) => await _controller.ArchiveTaskAsync(captured);
                    menu.Items.Add(archiveItem);
                    if (!string.IsNullOrEmpty(captured.DeviceId))
                    {
                        var devItem = new MenuItem { Header = "查看所属电脑" };
                        devItem.Click += (_, _) =>
                        {
                            _selectedDeviceId = captured.DeviceId;
                            _page = NavPage.Devices;
                            RenderAll(forceShell: true);
                        };
                        menu.Items.Add(devItem);
                    }
                    box.ContextMenu = menu;

                    var btn = UiKit.BareButton(box, () => SelectTask(captured));
                    if (!isSelected)
                    {
                        btn.MouseEnter += (_, _) =>
                        {
                            box.Background = UiKit.GlassCardHoverBrush;
                            box.BorderBrush = isWaiting ? UiKit.WarningBrush : UiKit.AccentBrush;
                        };
                        btn.MouseLeave += (_, _) =>
                        {
                            box.Background = UiKit.GlassCardBrush;
                            box.BorderBrush = defaultBorder;
                        };
                    }
                    _taskListHost.Children.Add(btn);
                }
            }
        }

        var selectedTask = _selectedTaskId is null ? null : _controller.Tasks.FirstOrDefault(t => t.Id == _selectedTaskId);
        if (selectedTask is null)
        {
            _taskDetail?.Dispose();
            _taskDetail = null;
            _mountedDetailTaskId = null;
            _mountedTaskCardsSignature = "";
            _mountedQuickChipsSignature = "";
            _taskDetailHeaderHost = null;
            _taskDetailCardsHost = null;
            _taskDetailInspectorHost = null;
            _taskDetailInspectorDivCol = null;
            _taskDetailInspectorCol = null;
            _replyQuickChipsHost = null;
            _replyModelCapsuleBtn = null;
            _replySendBtn = null;
            if (_taskDetailRightHost is not null)
            {
                var emptyCardInner = new StackPanel
                {
                    HorizontalAlignment = HorizontalAlignment.Center,
                    MaxWidth = 360
                };
                var iconRing = new Border
                {
                    Width = 54,
                    Height = 54,
                    CornerRadius = new CornerRadius(UiKit.CardRadius),
                    Background = UiKit.GlassTrackBrush,
                    BorderBrush = UiKit.GlassSubtleRimBrush,
                    BorderThickness = new Thickness(1),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    Margin = new Thickness(0, 0, 0, 14),
                    Child = new MonitorIcon("tasks", UiKit.AccentBrush, 24)
                };
                emptyCardInner.Children.Add(iconRing);

                var t1 = UiKit.Heading("选择一个任务", 19);
                t1.HorizontalAlignment = HorizontalAlignment.Center;
                emptyCardInner.Children.Add(t1);

                var t2 = UiKit.Body("在左侧点选任意电脑会话查看详情或继续对话，也可直接选择电脑与项目目录远程发起全新任务。", 13, UiKit.MutedBrush);
                t2.TextAlignment = TextAlignment.Center;
                t2.Margin = new Thickness(0, 7, 0, 14);
                emptyCardInner.Children.Add(t2);

                var startEmptyBtn = UiKit.PrimaryButton("新建远程任务", () => OpenNewTaskFlow());
                startEmptyBtn.HorizontalAlignment = HorizontalAlignment.Center;
                startEmptyBtn.Margin = new Thickness(0, 0, 0, 14);
                emptyCardInner.Children.Add(startEmptyBtn);

                var kbdRow = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
                foreach (var hint in new[] { "Ctrl + F 搜索任务", "F5 立即刷新" })
                {
                    kbdRow.Children.Add(new Border
                    {
                        Background = UiKit.GlassTrackBrush,
                        BorderBrush = UiKit.GlassSubtleRimBrush,
                        BorderThickness = new Thickness(1),
                        CornerRadius = new CornerRadius(7),
                        Padding = new Thickness(9, 4, 9, 4),
                        Margin = new Thickness(4, 0, 4, 0),
                        Child = new TextBlock
                        {
                            Text = hint,
                            FontFamily = UiKit.AppFont,
                            FontSize = 11.5,
                            Foreground = UiKit.MutedBrush
                        }
                    });
                }
                emptyCardInner.Children.Add(kbdRow);

                var emptyGlass = UiKit.ElevatedGlass(emptyCardInner, 28, UiKit.CardRadius);
                emptyGlass.HorizontalAlignment = HorizontalAlignment.Center;
                emptyGlass.VerticalAlignment = VerticalAlignment.Center;
                emptyGlass.Margin = new Thickness(32);
                _taskDetailRightHost.Content = emptyGlass;
            }
            return;
        }

        if (_mountedDetailTaskId != selectedTask.Id || _taskDetail is null)
        {
            _taskDetail?.Dispose();
            _taskDetail = new TaskDetailController(selectedTask, _controller);
            _taskDetail.Changed += () => Dispatcher.Invoke(RefreshMountedTaskDetail);
            _mountedDetailTaskId = selectedTask.Id;
            _mountedTaskCardsSignature = "";
            _mountedQuickChipsSignature = "";
            _taskInfoAccordionExpanded = false;
            _replySendBtn = null;
            if (_taskDetailRightHost is not null)
                _taskDetailRightHost.Content = BuildTaskDetailPane(selectedTask, _taskDetail);
            _taskDetail.StartPolling();
        }
        else
        {
            RefreshMountedTaskDetail();
        }
    }

    private void SelectTask(MonitorTask task)
    {
        if (_selectedTaskId == task.Id) return;
        _selectedTaskId = task.Id;
        UpdateTasksPageInPlace();
    }

    private void OpenNewTaskFlow(string? preferredDeviceId = null)
    {
        Dialogs.ShowNewTaskDialog(this, _controller, preferredDeviceId, createdTaskId =>
        {
            _selectedDeviceId = null;
            _page = NavPage.Tasks;
            _selectedTaskId = createdTaskId;
            if (_controller.Tasks.All(t => t.Id != createdTaskId))
                _controller.Scope = TaskScope.Active;
            RenderAll(forceShell: true);
        });
    }

    private Border? BuildDevPreviewCard(MonitorTask? task, Computer? computer, Action<string>? onPrefillPrompt = null)
    {
        var items = DevPreviewPolicy.ItemsFor(task, computer, _controller.IsFresh);
        if (items.Count == 0 || computer is null) return null;

        var stack = new StackPanel();
        var headerRow = new DockPanel { Margin = new Thickness(0, 0, 0, 8) };
        var leftHead = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        leftHead.Children.Add(new MonitorIcon("globe", UiKit.AccentBrush, 17) { Margin = new Thickness(0, 0, 7, 0) });
        leftHead.Children.Add(UiKit.Heading("本地开发预览", 16.5));
        headerRow.Children.Add(leftHead);

        var scopeHint = task is not null && items[0].MatchedProject
            ? "当前项目监听端口"
            : "电脑活跃开发端口";
        var hintLbl = UiKit.Body(scopeHint, 12, UiKit.MutedBrush);
        DockPanel.SetDock(hintLbl, Dock.Right);
        hintLbl.HorizontalAlignment = HorizontalAlignment.Right;
        hintLbl.VerticalAlignment = VerticalAlignment.Center;
        headerRow.Children.Add(hintLbl);
        stack.Children.Add(headerRow);

        foreach (var item in items)
        {
            var captured = item;
            var podRow = new DockPanel { LastChildFill = true };
            var rightBtns = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 0, 0) };

            if (!string.IsNullOrEmpty(captured.ShareUrl) && captured.LocalMachine)
            {
                var copyBtn = UiKit.SecondaryButton("复制真机链接", () =>
                {
                    try
                    {
                        Clipboard.SetText(captured.ShareUrl!);
                        _controller.Notice = $"已复制真机预览地址：{captured.ShareUrl}";
                        RenderAll(false);
                    }
                    catch { }
                }, glyph: "copy");
                copyBtn.Margin = new Thickness(0, 0, 8, 0);
                rightBtns.Children.Add(copyBtn);
            }

            if (!string.IsNullOrEmpty(captured.LoopbackFixPrompt))
            {
                var fixLabel = onPrefillPrompt is not null ? "让 AI 改为 0.0.0.0" : "复制开放指令";
                var fixBtn = UiKit.SecondaryButton(fixLabel, () =>
                {
                    if (onPrefillPrompt is not null)
                    {
                        onPrefillPrompt(captured.LoopbackFixPrompt!);
                    }
                    else
                    {
                        try
                        {
                            Clipboard.SetText(captured.LoopbackFixPrompt!);
                            _controller.Notice = "已复制改为绑定 0.0.0.0 的提示指令，可粘贴给对应会话";
                            RenderAll(false);
                        }
                        catch { }
                    }
                });
                if (!string.IsNullOrEmpty(captured.OpenUrl))
                    fixBtn.Margin = new Thickness(0, 0, 8, 0);
                rightBtns.Children.Add(fixBtn);
            }

            if (!string.IsNullOrEmpty(captured.OpenUrl))
            {
                var openBtn = UiKit.PrimaryButton(captured.ButtonLabel, () =>
                {
                    MonitorController.OpenDevPreviewUrl(captured.OpenUrl, allowLoopback: computer.Local);
                });
                rightBtns.Children.Add(openBtn);
            }

            if (rightBtns.Children.Count > 0)
            {
                DockPanel.SetDock(rightBtns, Dock.Right);
                podRow.Children.Add(rightBtns);
            }

            var leftInfo = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            leftInfo.Children.Add(new TextBlock
            {
                Text = $"端口 :{captured.Port}",
                FontFamily = UiKit.AppFont,
                FontSize = 13.5,
                FontWeight = FontWeights.SemiBold,
                Foreground = UiKit.TextBrush
            });
            leftInfo.Children.Add(new TextBlock
            {
                Text = captured.Subtitle,
                FontFamily = UiKit.AppFont,
                FontSize = 12,
                Foreground = captured.OpenUrl is null ? UiKit.WarningBrush : UiKit.MutedBrush,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 2, 0, 0)
            });
            podRow.Children.Add(leftInfo);

            stack.Children.Add(new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(UiKit.PodRadius),
                Padding = new Thickness(13, 9.5, 13, 9.5),
                Margin = new Thickness(0, 6, 0, 0),
                SnapsToDevicePixels = true,
                Child = podRow
            });
        }

        return UiKit.Card(stack, 18, UiKit.CardRadius);
    }

    private void PrefillTaskReplyComposer(string prompt)
    {
        if (_replyInputBox is null || string.IsNullOrWhiteSpace(prompt)) return;
        _replyInputBox.Text = prompt;
        _replyInputBox.Focus();
        _replyInputBox.CaretIndex = _replyInputBox.Text.Length;
    }

    private UIElement BuildTaskDetailPane(MonitorTask task, TaskDetailController detail)
    {
        var plan = _lastMainPlan ?? CurrentMainShellPlan();
        var outerSplit = new Grid();
        outerSplit.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        _taskDetailInspectorDivCol = new ColumnDefinition { Width = new GridLength(plan.TaskDetailInspector ? 1 : 0) };
        _taskDetailInspectorCol = new ColumnDefinition { Width = new GridLength(plan.TaskDetailInspector ? 268 : 0) };
        outerSplit.ColumnDefinitions.Add(_taskDetailInspectorDivCol);
        outerSplit.ColumnDefinitions.Add(_taskDetailInspectorCol);

        // Left Column: [Row 0: Pinned Compact Header] + [Row 1: Scrollable Main Canvas] + [Row 2: Sticky Bottom Glass Composer]
        var mainGrid = new Grid();
        mainGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        mainGrid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        mainGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        _taskDetailHeaderHost = new StackPanel { Margin = new Thickness(24, 16, 24, 0) };
        Grid.SetRow(_taskDetailHeaderHost, 0);
        mainGrid.Children.Add(_taskDetailHeaderHost);

        var scroll = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Padding = new Thickness(24, 14, 24, 22)
        };
        _taskDetailCardsHost = new StackPanel { MaxWidth = 860, HorizontalAlignment = HorizontalAlignment.Stretch };
        scroll.Content = _taskDetailCardsHost;
        Grid.SetRow(scroll, 1);
        mainGrid.Children.Add(scroll);

        if (task.SupportsReply)
        {
            var dockStack = new StackPanel();

            // Reserved Row 1: Quick-Action / Next-Step Suggestion Chips (#5)
            _replyQuickChipsHost = new WrapPanel
            {
                Orientation = Orientation.Horizontal,
                Margin = new Thickness(0, 0, 0, 8),
                Visibility = Visibility.Collapsed
            };
            dockStack.Children.Add(_replyQuickChipsHost);

            // Row 2: Input + Send Button
            var inputGrid = new Grid();
            inputGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            inputGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            _replyInputBox = UiKit.StyledTextBox(detail.DraftText, "继续此会话，告诉它下一步做什么（Ctrl + Enter 发送）…", multiline: true);
            _replyInputBox.MinHeight = 42;
            _replyInputBox.MaxHeight = 132;
            _replyInputBox.TextChanged += (_, _) =>
            {
                detail.DraftText = _replyInputBox.Text;
                UpdateReplyComposerControls(detail);
            };
            _replyInputBox.KeyDown += async (_, e) =>
            {
                if (e.Key == Key.Enter && (Keyboard.Modifiers & ModifierKeys.Control) == ModifierKeys.Control)
                {
                    e.Handled = true;
                    await detail.SendReplyAsync(_replyInputBox.Text);
                    if (string.IsNullOrEmpty(detail.DraftText) && _replyInputBox is not null)
                        _replyInputBox.Text = "";
                }
            };
            Grid.SetColumn(_replyInputBox, 0);
            inputGrid.Children.Add(_replyInputBox);

            var sendBtn = UiKit.PrimaryButton("发送回复", async () =>
            {
                await detail.SendReplyAsync(_replyInputBox.Text);
                if (string.IsNullOrEmpty(detail.DraftText) && _replyInputBox is not null)
                    _replyInputBox.Text = "";
            });
            _replySendBtn = sendBtn;
            sendBtn.VerticalAlignment = VerticalAlignment.Bottom;
            sendBtn.Margin = new Thickness(10, 0, 0, 0);
            Grid.SetColumn(sendBtn, 1);
            inputGrid.Children.Add(sendBtn);
            dockStack.Children.Add(inputGrid);

            // Row 3: Bottom-Left Model Selection Capsule + Right Receipt / Live Status
            var footer = new DockPanel { Margin = new Thickness(0, 8, 0, 0), LastChildFill = true };
            _replyModelCapsuleBtn = UiKit.SecondaryButton(
                detail.ModelSettingsSummary + " ▾",
                () =>
                {
                    var currentTask = _controller.Tasks.FirstOrDefault(t => t.Id == task.Id) ?? task;
                    Dialogs.ShowModelSettings(this, currentTask, detail);
                },
                glyph: "settings");
            _replyModelCapsuleBtn.VerticalAlignment = VerticalAlignment.Center;
            DockPanel.SetDock(_replyModelCapsuleBtn, Dock.Left);
            footer.Children.Add(_replyModelCapsuleBtn);

            var rightStatusStack = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(12, 0, 0, 0)
            };

            _replyReceiptText = new TextBlock
            {
                FontFamily = UiKit.AppFont,
                FontSize = 11.8,
                FontWeight = FontWeights.SemiBold,
                Foreground = UiKit.AccentBrush
            };
            _replyReceiptBadge = new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(UiKit.ControlRadius),
                Padding = new Thickness(9, 3.5, 9, 3.5),
                Margin = new Thickness(0, 0, 8, 0),
                VerticalAlignment = VerticalAlignment.Center,
                Visibility = Visibility.Collapsed,
                Child = _replyReceiptText
            };
            rightStatusStack.Children.Add(_replyReceiptBadge);

            _replyAppliedText = UiKit.Body("", 11.8, UiKit.AccentBrush);
            _replyAppliedText.FontWeight = FontWeights.SemiBold;
            _replyAppliedText.VerticalAlignment = VerticalAlignment.Center;
            _replyAppliedText.Margin = new Thickness(0, 0, 8, 0);
            _replyAppliedText.Visibility = Visibility.Collapsed;
            rightStatusStack.Children.Add(_replyAppliedText);

            _replyStatusText = UiKit.Body(detail.ReplyMessage, 12, UiKit.MutedBrush);
            _replyStatusText.VerticalAlignment = VerticalAlignment.Center;
            rightStatusStack.Children.Add(_replyStatusText);

            footer.Children.Add(rightStatusStack);
            dockStack.Children.Add(footer);

            var composerDock = new Border
            {
                Background = UiKit.GlassHeaderBrush,
                BorderBrush = UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(0, 1, 0, 0),
                Padding = new Thickness(24, 12, 24, 14),
                SnapsToDevicePixels = true,
                Child = dockStack
            };
            Grid.SetRow(composerDock, 2);
            mainGrid.Children.Add(composerDock);
        }

        Grid.SetColumn(mainGrid, 0);
        outerSplit.Children.Add(mainGrid);

        var inspectorDivider = new Border
        {
            Background = UiKit.SplitDividerBrush,
            Margin = new Thickness(0, 14, 0, 14)
        };
        Grid.SetColumn(inspectorDivider, 1);
        outerSplit.Children.Add(inspectorDivider);

        var inspectorScroll = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Padding = new Thickness(16, 16, 18, 22)
        };
        _taskDetailInspectorHost = new StackPanel();
        inspectorScroll.Content = _taskDetailInspectorHost;
        Grid.SetColumn(inspectorScroll, 2);
        outerSplit.Children.Add(inspectorScroll);

        RefreshMountedTaskDetail();
        return outerSplit;
    }

    private void UpdateReplyComposerControls(TaskDetailController detail)
    {
        if (_replyModelCapsuleBtn is not null)
        {
            UiKit.SetButtonState(_replyModelCapsuleBtn, detail.ModelSettingsSummary + " ▾", !detail.ReplySending, primary: false);
        }
        if (_replyStatusText is not null)
        {
            var fallback = detail.ReplyAvailable && string.IsNullOrEmpty(detail.ReplyReceiptTrail) && string.IsNullOrEmpty(detail.ReplyMessage)
                ? "电脑在线可直达"
                : detail.ReplyMessage;
            _replyStatusText.Text = fallback;
        }
        if (_replyReceiptBadge is not null && _replyReceiptText is not null)
        {
            _replyReceiptText.Text = detail.ReplyReceiptTrail;
            _replyReceiptText.Foreground = detail.ReplyDelivered ? UiKit.AccentBrush : UiKit.TextBrush;
            _replyReceiptBadge.Visibility = string.IsNullOrEmpty(detail.ReplyReceiptTrail)
                ? Visibility.Collapsed
                : Visibility.Visible;
        }
        if (_replyAppliedText is not null)
        {
            _replyAppliedText.Text = detail.SettingsReceiptText;
            _replyAppliedText.Visibility = string.IsNullOrEmpty(detail.SettingsReceiptText)
                ? Visibility.Collapsed
                : Visibility.Visible;
        }
        if (_replySendBtn is not null)
        {
            var mayRetry = detail.PendingReply?.MayRetry == true;
            var label = detail.ReplySending ? "正在发送…" : mayRetry ? "重试原消息" : "发送回复";
            var hasDraft = !string.IsNullOrWhiteSpace(_replyInputBox?.Text ?? detail.DraftText);
            var enabled = !detail.ReplySending && (mayRetry || (detail.ReplyAvailable && hasDraft));
            UiKit.SetButtonState(_replySendBtn, label, enabled, primary: true);
        }
    }

    private void SyncComposerQuickChips(MonitorTask task, Computer? computer)
    {
        if (_replyQuickChipsHost is null || !task.SupportsReply) return;
        var chips = new List<(string Label, string Prompt)>();
        var previewItems = DevPreviewPolicy.ItemsFor(task, computer, _controller.IsFresh);
        foreach (var item in previewItems)
        {
            if (!string.IsNullOrEmpty(item.LoopbackFixPrompt))
                chips.Add(($"开放 :{item.Port} 供手机预览", item.LoopbackFixPrompt!));
        }

        var sig = string.Join("|", chips.Select(c => c.Label + "=" + c.Prompt));
        if (sig == _mountedQuickChipsSignature) return;
        _mountedQuickChipsSignature = sig;

        _replyQuickChipsHost.Children.Clear();
        if (chips.Count == 0)
        {
            _replyQuickChipsHost.Visibility = Visibility.Collapsed;
            return;
        }

        foreach (var chip in chips)
        {
            var captured = chip;
            var pillBorder = new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(UiKit.ControlRadius),
                Padding = new Thickness(9, 4, 9, 4),
                Margin = new Thickness(0, 0, 7, 4),
                SnapsToDevicePixels = true,
                Child = new TextBlock
                {
                    Text = "✨ " + captured.Label,
                    FontFamily = UiKit.AppFont,
                    FontSize = 11.8,
                    Foreground = UiKit.AccentBrush
                }
            };
            var chipBtn = UiKit.BareButton(pillBorder, () => PrefillTaskReplyComposer(captured.Prompt));
            chipBtn.MouseEnter += (_, _) => { pillBorder.Background = UiKit.GlassCardHoverBrush; pillBorder.BorderBrush = UiKit.AccentBrush; };
            chipBtn.MouseLeave += (_, _) => { pillBorder.Background = UiKit.GlassTrackBrush; pillBorder.BorderBrush = UiKit.GlassSubtleRimBrush; };
            _replyQuickChipsHost.Children.Add(chipBtn);
        }
        _replyQuickChipsHost.Visibility = Visibility.Visible;
    }

    private string ComputeTaskDetailSignature(MonitorTask task, TaskDetailController detail, bool showInspector)
    {
        var sb = new System.Text.StringBuilder(256);
        sb.Append(UiKit.IsDark ? 'D' : 'L').Append('|')
          .Append(showInspector ? 'W' : 'N').Append('|')
          .Append(_taskInfoAccordionExpanded ? 'E' : 'C').Append('|')
          .Append(_controller.IsFresh ? '1' : '0').Append('|')
          .Append(_controller.Mutating ? '1' : '0').Append('|')
          .Append(_controller.SyncOutput ? '1' : '0').Append('|')
          .Append(task.Id).Append('|')
          .Append(task.Title).Append('|')
          .Append(task.Status).Append('|')
          .Append(task.EffectivePendingKind).Append('|')
          .Append(task.ContinuationState).Append('|')
          .Append(task.Archived ? '1' : '0').Append('|')
          .Append(task.Stale == true ? '1' : '0').Append('|')
          .Append(UsageFormatting.RelativeTime(task.UpdatedAt)).Append('|')
          .Append(task.DeviceName).Append('|')
          .Append(task.Project).Append('|')
          .Append(task.Preview).Append('|')
          .Append(task.ErrorAnalysis?.Summary).Append('|')
          .Append(task.ErrorAnalysis?.Suggestion).Append('|')
          .Append(task.Usage?.TotalTokens).Append('|')
          .Append(task.Usage?.Coverage).Append('|')
          .Append(detail.ResultLoading ? '1' : '0').Append('|')
          .Append(detail.Downloading ? '1' : '0').Append('|')
          .Append(detail.ResultError).Append('|')
          .Append(detail.Result?.Available == true ? '1' : '0').Append('|')
          .Append(detail.Result?.ResultId).Append('|')
          .Append(detail.Result?.Reason).Append('|')
          .Append(detail.SettingsReceiptText).Append('|')
          .Append(detail.Controls is not null ? '1' : '0').Append('|')
          .Append(detail.Controls?.Reason).Append('|')
          .Append(detail.DecisionSending ? '1' : '0').Append('|')
          .Append(detail.ControlsFeedback).Append('|')
          .Append(task.ProjectId).Append('|');

        var dev = _controller.Devices.FirstOrDefault(d => d.Id == task.DeviceId);
        if (dev is not null)
        {
            sb.Append(dev.Online ? '1' : '0').Append(':');
            if (dev.DevPorts is { Count: > 0 } dps)
            {
                foreach (var dp in dps)
                    sb.Append(dp.Port).Append(',').Append(dp.BindScope).Append(',').Append(dp.ProjectId).Append(';');
            }
            if (dev.PreviewHosts is { Count: > 0 } phs)
            {
                foreach (var ph in phs)
                    sb.Append(ph).Append(',');
            }
            sb.Append('|');
        }

        if (detail.Result?.Files is { Count: > 0 } files)
        {
            foreach (var f in files)
                sb.Append(f.Id).Append(':').Append(f.Ready == false ? '0' : '1').Append(';');
        }
        if (detail.Controls?.Pending is { Count: > 0 } pendings)
        {
            foreach (var p in pendings)
                sb.Append(SessionControlPolicy.AttentionFingerprint(p)).Append(':').Append(p.State)
                  .Append(':').Append(p.Delivered == true ? '1' : '0').Append(':').Append(p.Outcome).Append(';');
        }
        return sb.ToString();
    }

    private void RefreshMountedTaskDetail()
    {
        if (_taskDetailCardsHost is null || _taskDetail is null || _selectedTaskId is null) return;
        var liveTask = _controller.Tasks.FirstOrDefault(t => t.Id == _selectedTaskId);
        if (liveTask is null) return;

        var plan = _lastMainPlan ?? CurrentMainShellPlan();
        var showInspector = plan.TaskDetailInspector;
        if (_taskDetailInspectorDivCol is not null)
            _taskDetailInspectorDivCol.Width = new GridLength(showInspector ? 1 : 0);
        if (_taskDetailInspectorCol is not null)
            _taskDetailInspectorCol.Width = new GridLength(showInspector ? 268 : 0);

        var taskComputer = _controller.Devices.FirstOrDefault(d => d.Id == liveTask.DeviceId);
        UpdateReplyComposerControls(_taskDetail);
        SyncComposerQuickChips(liveTask, taskComputer);

        var nextSignature = ComputeTaskDetailSignature(liveTask, _taskDetail, showInspector);
        if (nextSignature == _mountedTaskCardsSignature && _taskDetailCardsHost.Children.Count > 0)
            return;
        _mountedTaskCardsSignature = nextSignature;

        // 1. Pinned Compact Hero Header
        if (_taskDetailHeaderHost is not null)
        {
            _taskDetailHeaderHost.Children.Clear();
            var topIdentityRow = new DockPanel { Margin = new Thickness(0, 0, 0, 6) };
            var leftMeta = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            var idBadge = UiKit.TaskIdentityBadge(liveTask.Tool, liveTask.DeviceName ?? "电脑", 16);
            var idBtn = UiKit.BareButton(idBadge, () =>
            {
                if (!string.IsNullOrEmpty(liveTask.DeviceId))
                {
                    _selectedDeviceId = liveTask.DeviceId;
                    _page = NavPage.Devices;
                    RenderAll(forceShell: true);
                }
            });
            idBtn.ToolTip = "查看所属电脑详情";
            leftMeta.Children.Add(idBtn);

            if (!string.IsNullOrWhiteSpace(liveTask.Project))
            {
                var shortProj = Path.GetFileName(liveTask.Project!.TrimEnd('/', '\\'));
                if (string.IsNullOrWhiteSpace(shortProj)) shortProj = liveTask.Project.Trim();
                leftMeta.Children.Add(new TextBlock
                {
                    Text = " · " + shortProj,
                    ToolTip = liveTask.Project,
                    FontFamily = UiKit.AppFont,
                    FontSize = 12,
                    Foreground = UiKit.MutedBrush,
                    VerticalAlignment = VerticalAlignment.Center
                });
            }
            topIdentityRow.Children.Add(leftMeta);

            if (!showInspector)
            {
                var archiveBtn = UiKit.SecondaryButton(
                    liveTask.Archived ? "取消归档" : "归档",
                    async () => await _controller.ArchiveTaskAsync(liveTask),
                    !_controller.Mutating,
                    glyph: liveTask.Archived ? "restore" : "archive");
                DockPanel.SetDock(archiveBtn, Dock.Right);
                archiveBtn.HorizontalAlignment = HorizontalAlignment.Right;
                topIdentityRow.Children.Add(archiveBtn);
            }
            _taskDetailHeaderHost.Children.Add(topIdentityRow);

            var titleBlock = UiKit.Heading(liveTask.Title, 21);
            titleBlock.Margin = new Thickness(0, 0, 0, 6);
            _taskDetailHeaderHost.Children.Add(titleBlock);

            var statusRow = BuildStatusDotRow(liveTask);
            statusRow.Margin = new Thickness(0, 0, 0, 12);
            _taskDetailHeaderHost.Children.Add(statusRow);

            _taskDetailHeaderHost.Children.Add(new Border
            {
                Height = 1,
                Background = UiKit.GlassSubtleRimBrush,
                Opacity = 0.75
            });
        }

        // 2. Main Scrollable Canvas
        _taskDetailCardsHost.Children.Clear();

        if (!string.IsNullOrWhiteSpace(liveTask.Preview) && liveTask.EffectiveStatus is "running" or "waiting" or "error")
        {
            var prev = UiKit.Body(liveTask.Preview!, 13, UiKit.MutedBrush);
            prev.Margin = new Thickness(0, 0, 0, 14);
            _taskDetailCardsHost.Children.Add(prev);
        }

        // Dynamic Priority Slot: Pending Approvals / Questions (0 height when idle)
        if (_taskDetail.Controls?.Pending is { Count: > 0 } pendings)
        {
            foreach (var req in pendings.Where(p => p.State is "pending" or "submitted" or "forwarded" or "acknowledged"
                                                    || p.Outcome == "handled_on_desktop"))
            {
                var attCard = BuildAttentionCard(req, _taskDetail);
                attCard.Margin = new Thickness(0, 0, 0, 14);
                _taskDetailCardsHost.Children.Add(attCard);
            }
        }
        else if (liveTask.SupportsReply && (!string.IsNullOrEmpty(liveTask.EffectivePendingKind) || liveTask.EffectiveStatus == "waiting"))
        {
            var waitStack = new StackPanel();
            var waitHead = new DockPanel { Margin = new Thickness(0, 0, 0, 8) };
            var headTitle = liveTask.EffectivePendingKind == "question" ? "等待回答" : "待你处理";
            waitHead.Children.Add(UiKit.Heading(headTitle, 16));
            var refreshWaitBtn = UiKit.IconButton("refresh", "刷新提问和批准状态", async () => await _taskDetail.RefreshControlsAsync(), enabled: !_taskDetail.DecisionSending);
            DockPanel.SetDock(refreshWaitBtn, Dock.Right);
            refreshWaitBtn.HorizontalAlignment = HorizontalAlignment.Right;
            waitHead.Children.Add(refreshWaitBtn);
            waitStack.Children.Add(waitHead);

            var waitMsg = _taskDetail.Controls is null
                ? "正在读取电脑提出的问题和选项…"
                : (!string.IsNullOrWhiteSpace(_taskDetail.Controls.Reason)
                    ? _taskDetail.Controls.Reason!
                    : "尚未收到可在此处理的请求，可先到电脑查看");
            waitStack.Children.Add(UiKit.Body(waitMsg, 13, UiKit.MutedBrush));
            if (!string.IsNullOrEmpty(_taskDetail.ControlsFeedback))
            {
                var fb = UiKit.Body(_taskDetail.ControlsFeedback, 12.5, UiKit.WarningBrush);
                fb.Margin = new Thickness(0, 6, 0, 0);
                waitStack.Children.Add(fb);
            }

            var waitCard = UiKit.Card(waitStack, 16, UiKit.CardRadius);
            waitCard.BorderBrush = UiKit.WarningBrush;
            waitCard.Margin = new Thickness(0, 0, 0, 14);
            _taskDetailCardsHost.Children.Add(waitCard);
        }

        // Dynamic Priority Slot: Error Analysis
        if (liveTask.ErrorAnalysis is { } err && !string.IsNullOrWhiteSpace(err.Summary))
        {
            var errStack = new StackPanel();
            errStack.Children.Add(UiKit.Heading("错误报告摘要", 15));
            var sum = UiKit.Body(err.Summary!, 13, UiKit.WarningBrush);
            sum.Margin = new Thickness(0, 5, 0, 0);
            errStack.Children.Add(sum);
            if (!string.IsNullOrWhiteSpace(err.Suggestion))
            {
                var sug = UiKit.Body("建议核查：" + err.Suggestion!, 12.5, UiKit.MutedBrush);
                sug.Margin = new Thickness(0, 5, 0, 0);
                errStack.Children.Add(sug);
            }
            var errCard = UiKit.Card(errStack, 16, UiKit.CardRadius);
            errCard.BorderBrush = UiKit.WarningBrush;
            errCard.Margin = new Thickness(0, 0, 0, 14);
            _taskDetailCardsHost.Children.Add(errCard);
        }

        // Narrow Mode: Compact Dev Preview Card right above Final Result
        if (!showInspector)
        {
            var narrowPreviewCard = BuildDevPreviewCard(
                liveTask,
                taskComputer,
                liveTask.SupportsReply ? PrefillTaskReplyComposer : null);
            if (narrowPreviewCard is not null)
            {
                narrowPreviewCard.Margin = new Thickness(0, 0, 0, 14);
                _taskDetailCardsHost.Children.Add(narrowPreviewCard);
            }
        }

        // Primary Reading Card: Final Result & Deliverables (plus clean space below for upcoming Diff #2)
        var resStack = new StackPanel();
        var resHeader = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
        var reloadResBtn = UiKit.IconButton("refresh", "刷新最终结果", async () => await _taskDetail.LoadResultAsync(true), enabled: !_taskDetail.ResultLoading);
        DockPanel.SetDock(reloadResBtn, Dock.Right);
        reloadResBtn.HorizontalAlignment = HorizontalAlignment.Right;
        resHeader.Children.Add(reloadResBtn);

        var resLeftTitle = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        resLeftTitle.Children.Add(UiKit.Heading("最终结果", 17.5));
        if (!string.IsNullOrEmpty(_taskDetail.Result?.CompletedAt))
        {
            resLeftTitle.Children.Add(new TextBlock
            {
                Text = " · " + UsageFormatting.RelativeTime(_taskDetail.Result.CompletedAt),
                FontFamily = UiKit.AppFont,
                FontSize = 12,
                Foreground = UiKit.MutedBrush,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(4, 2, 0, 0)
            });
        }
        resHeader.Children.Add(resLeftTitle);
        resStack.Children.Add(resHeader);

        if (_controller.SyncOutput && _taskDetail.Result is { Available: true, Text: { } resultText } resDto)
        {
            var textBox = new TextBox
            {
                Text = resultText,
                IsReadOnly = true,
                FontFamily = UiKit.AppFont,
                FontSize = 13.5,
                Foreground = UiKit.TextBrush,
                Background = Brushes.Transparent,
                BorderThickness = new Thickness(0),
                Padding = new Thickness(12, 10, 12, 10),
                TextWrapping = TextWrapping.Wrap,
                MaxHeight = 420,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto
            };
            resStack.Children.Add(new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(UiKit.PodRadius),
                Padding = new Thickness(2),
                SnapsToDevicePixels = true,
                Child = textBox
            });

            if (resDto.Truncated == true)
            {
                var trunc = UiKit.Body("内容较长，完整输出可保存 TXT 或在原电脑查看", 12.5, UiKit.MutedBrush);
                trunc.Margin = new Thickness(0, 8, 0, 0);
                resStack.Children.Add(trunc);
            }

            var actionRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 12, 0, 0) };
            Button? copyBtn = null;
            copyBtn = UiKit.SecondaryButton("复制", async () =>
            {
                try { Clipboard.SetText(resultText); } catch { return; }
                if (copyBtn is not null)
                {
                    UiKit.SetButtonState(copyBtn, "已复制", true, primary: false);
                    await Task.Delay(1400);
                    UiKit.SetButtonState(copyBtn, "复制", true, primary: false);
                }
            }, glyph: "copy");
            actionRow.Children.Add(copyBtn);
            var saveTxtBtn = UiKit.SecondaryButton("保存 TXT", async () =>
            {
                var bytes = await _taskDetail.DownloadTxtBytesAsync();
                if (bytes is null) return;
                var sfd = new SaveFileDialog
                {
                    FileName = MonitorSecurity.SafeFileName(liveTask.Title) + ".txt",
                    Filter = "文本文件 (*.txt)|*.txt"
                };
                if (sfd.ShowDialog(this) == true)
                    await File.WriteAllBytesAsync(sfd.FileName, bytes);
            }, _taskDetail.CanSaveTxt && !_taskDetail.Downloading, glyph: "download");
            saveTxtBtn.Margin = new Thickness(10, 0, 0, 0);
            actionRow.Children.Add(saveTxtBtn);
            resStack.Children.Add(actionRow);

            if (resDto.Files is { Count: > 0 } files)
            {
                var filesLabel = UiKit.Body("配套交付文件（SHA-256 完整性校验下载）", 12.5, UiKit.MutedBrush);
                filesLabel.Margin = new Thickness(0, 14, 0, 8);
                resStack.Children.Add(filesLabel);

                foreach (var file in files)
                {
                    var capturedFile = file;
                    var fRow = new DockPanel();
                    fRow.Children.Add(UiKit.Body(file.Name, 13));
                    var dlBtn = UiKit.SecondaryButton(
                        file.Ready == false ? "同步中" : $"下载 ({file.Size / 1024.0:0.#} KB)",
                        async () =>
                        {
                            var bytes = await _taskDetail.DownloadDeliverableAsync(capturedFile);
                            if (bytes is null) return;
                            var sfd = new SaveFileDialog { FileName = MonitorSecurity.SafeFileName(capturedFile.Name) };
                            if (sfd.ShowDialog(this) == true)
                                await File.WriteAllBytesAsync(sfd.FileName, bytes);
                        },
                        file.Ready != false && !_taskDetail.Downloading,
                        glyph: "download");
                    DockPanel.SetDock(dlBtn, Dock.Right);
                    dlBtn.HorizontalAlignment = HorizontalAlignment.Right;
                    fRow.Children.Add(dlBtn);

                    resStack.Children.Add(new Border
                    {
                        Background = UiKit.GlassTrackBrush,
                        BorderBrush = UiKit.GlassSubtleRimBrush,
                        BorderThickness = new Thickness(1),
                        CornerRadius = new CornerRadius(UiKit.PodRadius),
                        Padding = new Thickness(12, 8.5, 12, 8.5),
                        Margin = new Thickness(0, 0, 0, 6),
                        Child = fRow
                    });
                }
            }
        }
        else
        {
            var reason = !string.IsNullOrEmpty(_taskDetail.ResultError)
                ? _taskDetail.ResultError
                : (_taskDetail.Result?.Reason ?? (_taskDetail.ResultLoading ? "正在读取…" : "还没有最终结果"));
            resStack.Children.Add(UiKit.Body(reason, 13.5, UiKit.MutedBrush));
        }
        _taskDetailCardsHost.Children.Add(UiKit.Card(resStack, 20));

        // Narrow Mode: Default-Collapsed「会话详情」Accordion Card (A1)
        if (!showInspector)
        {
            var accCol = new StackPanel();
            var accHeader = new DockPanel { LastChildFill = true };
            var chev = new MonitorIcon(_taskInfoAccordionExpanded ? "chevron-up" : "chevron-down", UiKit.MutedBrush, 16)
            {
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(10, 0, 0, 0)
            };
            DockPanel.SetDock(chev, Dock.Right);
            accHeader.Children.Add(chev);

            var summaryParts = new List<string>();
            if (liveTask.Usage?.TotalTokens is { } tTok)
                summaryParts.Add(UsageFormatting.CompactTokens(tTok) + " Token");
            if (!string.IsNullOrWhiteSpace(liveTask.Project))
            {
                var sp = Path.GetFileName(liveTask.Project!.TrimEnd('/', '\\'));
                summaryParts.Add("目录 " + (string.IsNullOrWhiteSpace(sp) ? liveTask.Project.Trim() : sp));
            }
            var accTitleStack = new StackPanel();
            accTitleStack.Children.Add(UiKit.Heading("会话详情", 14.5));
            accTitleStack.Children.Add(UiKit.Body(
                summaryParts.Count > 0 ? string.Join(" · ", summaryParts) : "查看对话 Token 用量、项目目录与所属电脑",
                12, UiKit.MutedBrush));
            accHeader.Children.Add(accTitleStack);

            var accToggleBtn = UiKit.BareButton(accHeader, () =>
            {
                _taskInfoAccordionExpanded = !_taskInfoAccordionExpanded;
                RefreshMountedTaskDetail();
            });
            accToggleBtn.HorizontalContentAlignment = HorizontalAlignment.Stretch;
            accCol.Children.Add(accToggleBtn);

            if (_taskInfoAccordionExpanded)
            {
                accCol.Children.Add(new Border
                {
                    Height = 1,
                    Background = UiKit.GlassSubtleRimBrush,
                    Margin = new Thickness(0, 12, 0, 12)
                });
                accCol.Children.Add(BuildTaskEnvironmentMetaContent(liveTask, taskComputer, includeArchiveButton: false));
            }

            var accCard = UiKit.Card(accCol, 16, UiKit.CardRadius);
            accCard.Margin = new Thickness(0, 14, 0, 0);
            _taskDetailCardsHost.Children.Add(accCard);
        }

        // 3. Wide Mode: Right Environment Inspector Rail (A3)
        if (_taskDetailInspectorHost is not null)
        {
            _taskDetailInspectorHost.Children.Clear();
            if (showInspector)
            {
                var railTitle = UiKit.Heading("环境与会话", 15);
                railTitle.Margin = new Thickness(2, 2, 0, 12);
                _taskDetailInspectorHost.Children.Add(railTitle);

                // Inspector Section 1: Dev Server Previews
                var inspectorPreviews = DevPreviewPolicy.ItemsFor(liveTask, taskComputer, _controller.IsFresh);
                var prevStack = new StackPanel();
                var prevHead = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 8) };
                prevHead.Children.Add(new MonitorIcon("globe", UiKit.AccentBrush, 15.5) { Margin = new Thickness(0, 0, 6, 0) });
                prevHead.Children.Add(UiKit.Heading("本地开发预览", 14));
                prevStack.Children.Add(prevHead);

                if (inspectorPreviews.Count == 0 || taskComputer is null)
                {
                    prevStack.Children.Add(UiKit.Body(
                        _controller.IsFresh && taskComputer?.Online == true
                            ? "当前未检测到活跃的前端或 Web 监听端口。"
                            : "电脑在线并启动本地开发服务后，可在此一键打开预览。",
                        12, UiKit.MutedBrush));
                }
                else
                {
                    foreach (var item in inspectorPreviews)
                    {
                        var captured = item;
                        var pCol = new StackPanel();
                        pCol.Children.Add(new TextBlock
                        {
                            Text = $"端口 :{captured.Port} ({captured.Process})",
                            FontFamily = UiKit.AppFont,
                            FontSize = 12.8,
                            FontWeight = FontWeights.SemiBold,
                            Foreground = UiKit.TextBrush
                        });
                        pCol.Children.Add(new TextBlock
                        {
                            Text = captured.Subtitle,
                            FontFamily = UiKit.AppFont,
                            FontSize = 11.5,
                            Foreground = captured.OpenUrl is null ? UiKit.WarningBrush : UiKit.MutedBrush,
                            TextWrapping = TextWrapping.Wrap,
                            Margin = new Thickness(0, 2, 0, 7)
                        });
                        var pBtns = new WrapPanel { Orientation = Orientation.Horizontal };
                        if (!string.IsNullOrEmpty(captured.OpenUrl))
                        {
                            var openBtn = UiKit.PrimaryButton(captured.ButtonLabel, () =>
                                MonitorController.OpenDevPreviewUrl(captured.OpenUrl, allowLoopback: taskComputer.Local));
                            openBtn.Margin = new Thickness(0, 0, 6, 4);
                            pBtns.Children.Add(openBtn);
                        }
                        if (!string.IsNullOrEmpty(captured.ShareUrl) && captured.LocalMachine)
                        {
                            var cpBtn = UiKit.SecondaryButton("复制真机链接", () =>
                            {
                                try
                                {
                                    Clipboard.SetText(captured.ShareUrl!);
                                    _controller.Notice = $"已复制真机预览地址：{captured.ShareUrl}";
                                    RenderAll(false);
                                }
                                catch { }
                            });
                            cpBtn.Margin = new Thickness(0, 0, 6, 4);
                            pBtns.Children.Add(cpBtn);
                        }
                        if (!string.IsNullOrEmpty(captured.LoopbackFixPrompt))
                        {
                            var fixBtn = UiKit.SecondaryButton(liveTask.SupportsReply ? "改为 0.0.0.0" : "复制开放指令", () =>
                            {
                                if (liveTask.SupportsReply) PrefillTaskReplyComposer(captured.LoopbackFixPrompt!);
                                else
                                {
                                    try { Clipboard.SetText(captured.LoopbackFixPrompt!); } catch { }
                                }
                            });
                            fixBtn.Margin = new Thickness(0, 0, 0, 4);
                            pBtns.Children.Add(fixBtn);
                        }
                        pCol.Children.Add(pBtns);
                        prevStack.Children.Add(new Border
                        {
                            Background = UiKit.GlassTrackBrush,
                            BorderBrush = UiKit.GlassSubtleRimBrush,
                            BorderThickness = new Thickness(1),
                            CornerRadius = new CornerRadius(UiKit.PodRadius),
                            Padding = new Thickness(10, 8, 10, 6),
                            Margin = new Thickness(0, 0, 0, 6),
                            Child = pCol
                        });
                    }
                }
                var inspectorPrevCard = UiKit.Card(prevStack, 14, UiKit.CardRadius);
                inspectorPrevCard.Margin = new Thickness(0, 0, 0, 12);
                _taskDetailInspectorHost.Children.Add(inspectorPrevCard);

                // Inspector Section 2 & 3: Usage + Session Environment & Actions
                var metaCard = UiKit.Card(BuildTaskEnvironmentMetaContent(liveTask, taskComputer, includeArchiveButton: true), 14, UiKit.CardRadius);
                _taskDetailInspectorHost.Children.Add(metaCard);
            }
        }
    }

    private UIElement BuildTaskEnvironmentMetaContent(MonitorTask liveTask, Computer? computer, bool includeArchiveButton)
    {
        var stack = new StackPanel();

        // Conversation Token Block
        stack.Children.Add(UiKit.Body("本次对话用量", 11.8, UiKit.MutedBrush));
        if (liveTask.Usage?.TotalTokens is { } totalTokens)
        {
            var tokText = UsageFormatting.CompactTokens(totalTokens) + " Token" + (liveTask.Usage.Coverage == "complete" ? "" : " · 部分记录");
            var tokRow = new DockPanel { Margin = new Thickness(0, 3, 0, 10) };
            var detailBtn = UiKit.SecondaryButton("明细", () => Dialogs.ShowTaskTokenBreakdown(this, liveTask.Usage));
            DockPanel.SetDock(detailBtn, Dock.Right);
            detailBtn.HorizontalAlignment = HorizontalAlignment.Right;
            tokRow.Children.Add(detailBtn);
            var val = UiKit.Heading(tokText, 16);
            val.VerticalAlignment = VerticalAlignment.Center;
            tokRow.Children.Add(val);
            stack.Children.Add(tokRow);
        }
        else
        {
            var emptyTok = UiKit.Body("暂未提供此对话的本地用量记录", 12.5, UiKit.MutedBrush);
            emptyTok.Margin = new Thickness(0, 3, 0, 10);
            stack.Children.Add(emptyTok);
        }

        stack.Children.Add(new Border
        {
            Height = 1,
            Background = UiKit.GlassSubtleRimBrush,
            Margin = new Thickness(0, 2, 0, 10)
        });

        // Environment Facts
        void AddFact(string key, string value, bool mono = false)
        {
            if (string.IsNullOrWhiteSpace(value)) return;
            var row = new StackPanel { Margin = new Thickness(0, 0, 0, 8) };
            row.Children.Add(UiKit.Body(key, 11.5, UiKit.MutedBrush));
            row.Children.Add(new TextBlock
            {
                Text = value,
                FontFamily = mono ? UiKit.MonoFont : UiKit.AppFont,
                FontSize = 12.5,
                Foreground = UiKit.TextBrush,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 2, 0, 0)
            });
            stack.Children.Add(row);
        }

        var online = _controller.IsFresh && computer?.Online == true;
        AddFact("所属电脑", $"{liveTask.DeviceName ?? computer?.Name ?? "电脑"} · {(online ? "在线" : "离线")}");
        if (!string.IsNullOrWhiteSpace(liveTask.Project))
            AddFact("项目目录", liveTask.Project!, mono: true);

        var contLabel = MonitorTask.ContinuationTitleFor(liveTask.EffectiveContinuation(_controller.IsFresh, online));
        if (!string.IsNullOrEmpty(contLabel))
            AddFact("接续状态", contLabel);

        var actions = new WrapPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 6, 0, 0) };
        if (!string.IsNullOrEmpty(liveTask.DeviceId))
        {
            var devBtn = UiKit.SecondaryButton("查看电脑", () =>
            {
                _selectedDeviceId = liveTask.DeviceId;
                _page = NavPage.Devices;
                RenderAll(forceShell: true);
            }, glyph: "devices");
            devBtn.Margin = new Thickness(0, 0, 8, 6);
            actions.Children.Add(devBtn);
        }
        if (includeArchiveButton)
        {
            var archBtn = UiKit.SecondaryButton(
                liveTask.Archived ? "取消归档" : "归档任务",
                async () => await _controller.ArchiveTaskAsync(liveTask),
                !_controller.Mutating,
                glyph: liveTask.Archived ? "restore" : "archive");
            archBtn.Margin = new Thickness(0, 0, 0, 6);
            actions.Children.Add(archBtn);
        }
        if (actions.Children.Count > 0)
            stack.Children.Add(actions);

        return stack;
    }

    private Border BuildAttentionCard(SessionPendingRequestDto req, TaskDetailController detail)
    {
        var stack = new StackPanel();

        var headRow = new DockPanel { Margin = new Thickness(0, 0, 0, 8) };
        var kindLabel = req.Kind == "question" ? "等待回答" : "等待批准";
        headRow.Children.Add(UiKit.TaskIdentityBadge(req.Provider, kindLabel, 15));

        var rightHead = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        var stateChip = new TextBlock
        {
            Text = SessionControlPolicy.StateLabel(req.State, req.Outcome),
            FontFamily = UiKit.AppFont,
            FontSize = 11.5,
            FontWeight = FontWeights.Medium,
            Foreground = req.State == "pending" ? UiKit.WarningBrush : UiKit.MutedBrush,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 6, 0)
        };
        rightHead.Children.Add(stateChip);
        var refreshBtn = UiKit.IconButton("refresh", "刷新提问和批准状态", async () => await detail.RefreshControlsAsync(), enabled: !detail.DecisionSending);
        refreshBtn.Width = 26;
        refreshBtn.Height = 26;
        rightHead.Children.Add(refreshBtn);
        DockPanel.SetDock(rightHead, Dock.Right);
        rightHead.HorizontalAlignment = HorizontalAlignment.Right;
        headRow.Children.Add(rightHead);
        stack.Children.Add(headRow);

        detail.SessionDecisionMemos.TryGetValue(req.Id, out var memo);
        var defaultTitle = req.Kind == "question" ? "电脑正在等待你选择选项" : "等待确认操作";
        var displayTitle = string.IsNullOrWhiteSpace(req.Title)
            ? defaultTitle
            : (req.Title == "请求已结束" && !string.IsNullOrWhiteSpace(memo.Title) ? memo.Title : req.Title);
        stack.Children.Add(UiKit.Heading(displayTitle, 16.5));
        if (!string.IsNullOrWhiteSpace(req.Detail))
        {
            var d = UiKit.Body(req.Detail!, 13, UiKit.MutedBrush);
            d.Margin = new Thickness(0, 6, 0, 0);
            stack.Children.Add(d);
        }
        if (!string.IsNullOrWhiteSpace(req.Operation))
        {
            stack.Children.Add(new Border
            {
                Background = UiKit.SoftBrush,
                BorderBrush = UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(UiKit.PodRadius),
                Padding = new Thickness(12, 9, 12, 9),
                Margin = new Thickness(0, 8, 0, 0),
                Child = new TextBlock
                {
                    Text = req.Operation,
                    FontFamily = UiKit.MonoFont,
                    FontSize = 12.5,
                    Foreground = UiKit.TextBrush,
                    TextWrapping = TextWrapping.Wrap
                }
            });
        }

        var sendingThis = detail.DecisionSending && detail.DecisionSendingId == req.Id;
        var hasLocalRecord = detail.Decisions.ContainsKey(req.Id);
        var receiptTrail = SessionControlPolicy.ReceiptTrail(req, sendingThis, hasLocalRecord);

        if (req.State != "pending")
        {
            if (!string.IsNullOrWhiteSpace(memo.Summary))
            {
                var chosenTxt = UiKit.Body(memo.Summary, 13, UiKit.TextBrush);
                chosenTxt.Margin = new Thickness(0, 10, 0, 0);
                stack.Children.Add(chosenTxt);
            }
            var isRejected = req.Outcome == "rejected" || receiptTrail.Contains("已拒绝");
            var isAdopted = req.Outcome == "adopted" || receiptTrail.Contains("已采用");
            var receiptText = !string.IsNullOrEmpty(receiptTrail)
                ? receiptTrail
                : SessionControlPolicy.StateLabel(req.State, req.Outcome);
            var receiptBox = new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = isRejected ? UiKit.WarningBrush : isAdopted ? UiKit.AccentBrush : UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(UiKit.ControlRadius),
                Padding = new Thickness(12, 8.5, 12, 8.5),
                Margin = new Thickness(0, 10, 0, 0),
                HorizontalAlignment = HorizontalAlignment.Left,
                Child = new TextBlock
                {
                    Text = receiptText,
                    FontFamily = UiKit.AppFont,
                    FontSize = 12.8,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = isRejected ? UiKit.WarningBrush : isAdopted ? UiKit.AccentBrush : UiKit.TextBrush
                }
            };
            stack.Children.Add(receiptBox);
        }
        else if (req.Kind == "question" && req.Questions is { Count: > 0 } questions)
        {
            var fingerprint = SessionControlPolicy.AttentionFingerprint(req);
            if (!_attentionDrafts.TryGetValue(fingerprint, out var draftMap))
            {
                draftMap = new Dictionary<string, (HashSet<string> Choices, string FreeText)>(StringComparer.Ordinal);
                _attentionDrafts[fingerprint] = draftMap;
            }

            var validationBanner = UiKit.Body("", 12.5, UiKit.WarningBrush);
            validationBanner.Margin = new Thickness(0, 10, 0, 0);
            validationBanner.Visibility = Visibility.Collapsed;

            var answerEditors = new List<(string QId, List<ToggleButton> Toggles, TextBox? FreeText)>();
            foreach (var q in questions)
            {
                if (!draftMap.TryGetValue(q.Id, out var qDraft))
                {
                    qDraft = (new HashSet<string>(StringComparer.Ordinal), "");
                    draftMap[q.Id] = qDraft;
                }
                var capturedQId = q.Id;

                var qHeader = new DockPanel { Margin = new Thickness(0, 14, 0, 6) };
                if (q.Options is { Count: > 1 })
                {
                    var modeHint = new TextBlock
                    {
                        Text = q.Multiple == true ? "可多选" : "单选",
                        FontFamily = UiKit.AppFont,
                        FontSize = 11.5,
                        Foreground = UiKit.MutedBrush,
                        VerticalAlignment = VerticalAlignment.Center,
                        Margin = new Thickness(8, 0, 0, 0)
                    };
                    DockPanel.SetDock(modeHint, Dock.Right);
                    qHeader.Children.Add(modeHint);
                }
                var qTitle = UiKit.Body(q.Text, 13.5);
                qTitle.FontWeight = FontWeights.SemiBold;
                qHeader.Children.Add(qTitle);
                stack.Children.Add(qHeader);

                var toggles = new List<ToggleButton>();
                TextBox? freeBox = null;
                var syncing = false;
                var groupName = $"q_{req.Id}_{q.Id}";

                if (q.Options is { Count: > 0 })
                {
                    foreach (var opt in q.Options)
                    {
                        var isSavedChecked = qDraft.Choices.Contains(opt.Id) && string.IsNullOrWhiteSpace(qDraft.FreeText);

                        var optContent = new StackPanel { Margin = new Thickness(4, 0, 0, 0) };
                        optContent.Children.Add(new TextBlock
                        {
                            Text = opt.Label,
                            FontFamily = UiKit.AppFont,
                            FontSize = 13.5,
                            FontWeight = FontWeights.Medium,
                            Foreground = UiKit.TextBrush,
                            TextWrapping = TextWrapping.Wrap
                        });
                        if (!string.IsNullOrWhiteSpace(opt.Description))
                        {
                            optContent.Children.Add(new TextBlock
                            {
                                Text = opt.Description,
                                FontFamily = UiKit.AppFont,
                                FontSize = 12,
                                Foreground = UiKit.MutedBrush,
                                TextWrapping = TextWrapping.Wrap,
                                Margin = new Thickness(0, 2, 0, 0)
                            });
                        }

                        ToggleButton tb = q.Multiple == true
                            ? new CheckBox
                            {
                                Content = optContent,
                                Tag = opt.Id,
                                IsChecked = isSavedChecked,
                                Foreground = UiKit.TextBrush,
                                VerticalContentAlignment = VerticalAlignment.Top,
                                HorizontalAlignment = HorizontalAlignment.Stretch
                            }
                            : new RadioButton
                            {
                                Content = optContent,
                                GroupName = groupName,
                                Tag = opt.Id,
                                IsChecked = isSavedChecked,
                                Foreground = UiKit.TextBrush,
                                VerticalContentAlignment = VerticalAlignment.Top,
                                HorizontalAlignment = HorizontalAlignment.Stretch
                            };

                        var optCard = new Border
                        {
                            Background = isSavedChecked ? UiKit.GlassSelectedCardBrush : UiKit.GlassTrackBrush,
                            BorderBrush = isSavedChecked ? UiKit.AccentBrush : UiKit.GlassSubtleRimBrush,
                            BorderThickness = new Thickness(1),
                            CornerRadius = new CornerRadius(UiKit.PodRadius),
                            Padding = new Thickness(11, 8.5, 11, 8.5),
                            Margin = new Thickness(0, 0, 0, 6),
                            Cursor = Cursors.Hand,
                            SnapsToDevicePixels = true,
                            Child = tb
                        };

                        void SyncCardAppearance()
                        {
                            var active = tb.IsChecked == true;
                            optCard.Background = active ? UiKit.GlassSelectedCardBrush : UiKit.GlassTrackBrush;
                            optCard.BorderBrush = active ? UiKit.AccentBrush : UiKit.GlassSubtleRimBrush;
                        }

                        optCard.MouseLeftButtonUp += (_, e) =>
                        {
                            if (!e.Handled)
                            {
                                tb.IsChecked = q.Multiple == true ? !(tb.IsChecked == true) : true;
                                e.Handled = true;
                            }
                        };

                        tb.Checked += (_, _) =>
                        {
                            SyncCardAppearance();
                            if (syncing) return;
                            var entry = draftMap[capturedQId];
                            if (q.Multiple != true) entry.Choices.Clear();
                            entry.Choices.Add((string)tb.Tag);
                            draftMap[capturedQId] = (entry.Choices, "");
                            if (freeBox is not null && !string.IsNullOrEmpty(freeBox.Text))
                            {
                                syncing = true;
                                freeBox.Text = "";
                                syncing = false;
                            }
                            validationBanner.Visibility = Visibility.Collapsed;
                        };
                        tb.Unchecked += (_, _) =>
                        {
                            SyncCardAppearance();
                            if (syncing) return;
                            var entry = draftMap[capturedQId];
                            entry.Choices.Remove((string)tb.Tag);
                            draftMap[capturedQId] = (entry.Choices, entry.FreeText);
                        };

                        toggles.Add(tb);
                        stack.Children.Add(optCard);
                    }
                }

                if (q.AllowsText == true || toggles.Count == 0)
                {
                    var placeholder = toggles.Count > 0 ? "自定义回答（填写后代替上方选项）…" : "输入你的回答…";
                    freeBox = UiKit.StyledTextBox(qDraft.FreeText, placeholder);
                    freeBox.Margin = new Thickness(0, 2, 0, 4);
                    var capturedBox = freeBox;
                    freeBox.TextChanged += (_, _) =>
                    {
                        if (syncing) return;
                        var cur = draftMap[capturedQId];
                        if (!string.IsNullOrWhiteSpace(capturedBox.Text))
                        {
                            syncing = true;
                            foreach (var t in toggles) t.IsChecked = false;
                            cur.Choices.Clear();
                            syncing = false;
                        }
                        draftMap[capturedQId] = (cur.Choices, capturedBox.Text);
                        validationBanner.Visibility = Visibility.Collapsed;
                    };
                    stack.Children.Add(freeBox);
                }

                answerEditors.Add((q.Id, toggles, freeBox));
            }

            stack.Children.Add(validationBanner);

            var submitBtn = UiKit.PrimaryButton("发送回答", async () =>
            {
                var payload = answerEditors.Select(ed =>
                {
                    var selectedChoices = ed.Toggles.Where(c => c.IsChecked == true).Select(c => (string)c.Tag).ToList();
                    var customText = selectedChoices.Count > 0 || string.IsNullOrWhiteSpace(ed.FreeText?.Text)
                        ? null
                        : ed.FreeText!.Text.Trim();
                    return new SessionAnswerPayloadDto
                    {
                        QuestionId = ed.QId,
                        Choices = selectedChoices,
                        Text = customText
                    };
                }).ToList();

                if (!SessionControlPolicy.ValidAnswers(req, payload))
                {
                    validationBanner.Text = "请先为每个问题选择选项，或填写允许的自定义回答";
                    validationBanner.Visibility = Visibility.Visible;
                    return;
                }

                validationBanner.Visibility = Visibility.Collapsed;
                await detail.SubmitDecisionAsync(req, "answer", payload);
            }, !detail.DecisionSending && req.State == "pending", glyph: "check");
            submitBtn.HorizontalAlignment = HorizontalAlignment.Left;
            submitBtn.Margin = new Thickness(0, 12, 0, 0);
            stack.Children.Add(submitBtn);
        }
        else
        {
            var allowHint = UiKit.Body("允许后，电脑可执行当前列出的单次操作；此批准不适用于其他请求。", 12, UiKit.MutedBrush);
            allowHint.Margin = new Thickness(0, 8, 0, 0);
            stack.Children.Add(allowHint);

            var btnRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 12, 0, 0) };
            btnRow.Children.Add(UiKit.PrimaryButton("仅允许这一次", async () =>
            {
                var opSummary = string.IsNullOrWhiteSpace(req.Operation)
                    ? "允许后，电脑将继续执行本次请求的操作。此批准不适用于其他请求。"
                    : $"即将允许电脑执行：\n{req.Operation}\n\n此批准仅对当前这一次请求生效。";
                if (!Dialogs.Confirm(this, "确认本次批准？", opSummary, "仅允许这一次"))
                    return;
                await detail.SubmitDecisionAsync(req, "allow_once");
            }, !detail.DecisionSending && req.State == "pending", glyph: "check"));
            var rejectBtn = UiKit.SecondaryButton("拒绝", async () => await detail.SubmitDecisionAsync(req, "deny"), !detail.DecisionSending && req.State == "pending", UiKit.DangerBrush, glyph: "close");
            rejectBtn.Margin = new Thickness(10, 0, 0, 0);
            btnRow.Children.Add(rejectBtn);
            stack.Children.Add(btnRow);
        }

        if (req.State == "pending" && !string.IsNullOrEmpty(receiptTrail))
        {
            var liveReceiptBox = new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(9),
                Padding = new Thickness(11, 6.5, 11, 6.5),
                Margin = new Thickness(0, 10, 0, 0),
                HorizontalAlignment = HorizontalAlignment.Left,
                Child = new TextBlock
                {
                    Text = receiptTrail,
                    FontFamily = UiKit.AppFont,
                    FontSize = 12.5,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = UiKit.TextBrush
                }
            };
            stack.Children.Add(liveReceiptBox);
        }

        if (!string.IsNullOrEmpty(detail.ControlsFeedback))
        {
            var fb = UiKit.Body(detail.ControlsFeedback, 12.5, UiKit.MutedBrush);
            fb.Margin = new Thickness(0, 8, 0, 0);
            stack.Children.Add(fb);
        }

        var border = UiKit.Card(stack, 20, UiKit.CardRadius);
        border.BorderBrush = req.State == "pending" ? UiKit.WarningBrush : UiKit.GlassRimBrush;
        return border;
    }

    // ==================== COMPUTERS & DEVICE DETAIL ====================

    private UIElement BuildDevicesContent()
    {
        var plan = _lastMainPlan ?? CurrentMainShellPlan();
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(28, 24, 28, 30) };
        var stack = new StackPanel { MaxWidth = 1180 };
        stack.Children.Add(UiKit.Heading("我的电脑", 24));

        // This Windows PC Card
        var localStack = new StackPanel();
        var localHead = new StackPanel { Orientation = Orientation.Horizontal };
        localHead.Children.Add(new MonitorIcon("devices", UiKit.AccentBrush, 20) { Margin = new Thickness(0, 0, 10, 0) });
        localHead.Children.Add(UiKit.Heading($"这台 Windows 电脑 ({Environment.MachineName})", 18.5));
        localStack.Children.Add(localHead);

        var hubLocal = _controller.BuiltInLocalHubComputer;
        var collectorStatus = _controller.Collector.Status;
        if (collectorStatus is not null)
        {
            var modeName = collectorStatus.Mode switch
            {
                "full" => "结果、文件与回复",
                "results" => "结果与文件",
                _ => "仅任务状态"
            };
            var statusDesc = collectorStatus.Paired
                ? (collectorStatus.Service == "running" ? $"正在同步 · {modeName}" : "已连接 · 同步已暂停")
                : "连接后，手机与其他桌面端也能查看这台电脑上的任务";
            var sub = UiKit.Body(statusDesc, 13.5, UiKit.MutedBrush);
            sub.Margin = new Thickness(0, 8, 0, 14);
            localStack.Children.Add(sub);

            if (collectorStatus.Paired)
            {
                var btns = new StackPanel { Orientation = Orientation.Horizontal };
                btns.Children.Add(UiKit.SecondaryButton(collectorStatus.Service == "running" ? "暂停同步" : "开始同步",
                    async () =>
                    {
                        await _controller.Collector.SetRunningAsync(_controller.Origin, collectorStatus.Service != "running");
                        RenderAll(false);
                    }, !_controller.Collector.Busy));
                var settingsBtn = UiKit.SecondaryButton("同步设置", () =>
                {
                    Dialogs.ShowCollectorSettings(this, _controller);
                    RenderAll(false);
                }, glyph: "settings");
                settingsBtn.Margin = new Thickness(10, 0, 0, 0);
                btns.Children.Add(settingsBtn);
                localStack.Children.Add(btns);
            }
            else
            {
                localStack.Children.Add(UiKit.PrimaryButton("一键连接这台电脑", async () =>
                {
                    var code = await _controller.CreatePairingCodeAsync();
                    if (code is not null)
                    {
                        await _controller.Collector.PairAsync(_controller.Origin, code.Code);
                        await _controller.RefreshWorkbenchAsync();
                        RenderAll(false);
                    }
                }, !_controller.Collector.Busy && !_controller.Mutating, glyph: "link"));
            }
        }
        else if (hubLocal is not null && string.Equals(hubLocal.Name, Environment.MachineName, StringComparison.OrdinalIgnoreCase))
        {
            var sub = UiKit.Body("本机正在直接运行个人同步服务 Hub，任务状态、官方额度与本地 Token 已自动实时汇总。", 13.5, UiKit.GreenBrush);
            sub.Margin = new Thickness(0, 8, 0, 14);
            localStack.Children.Add(sub);
            var syncBtn = UiKit.SecondaryButton(
                _controller.SyncOutput ? "账号结果同步：已开启" : "开启账号结果同步",
                async () =>
                {
                    if (!_controller.SyncOutput && !Dialogs.Confirm(this, "开启账号结果同步？", "开启后，可在所有已登录设备查看最终结果和下载配套文件。", "开启"))
                        return;
                    await _controller.SetResultSyncAsync(!_controller.SyncOutput);
                }, !_controller.Mutating, glyph: "settings");
            syncBtn.HorizontalAlignment = HorizontalAlignment.Left;
            localStack.Children.Add(syncBtn);
        }
        else
        {
            var sub = UiKit.Body("本机独立采集器尚未安装。如需在其他服务器下采集本机任务，可运行 windows/scripts/collector_service.py install。", 13, UiKit.MutedBrush);
            sub.Margin = new Thickness(0, 8, 0, 0);
            localStack.Children.Add(sub);
        }

        if (!string.IsNullOrEmpty(_controller.Collector.Message))
        {
            var msg = UiKit.Body(_controller.Collector.Message, 12.5, UiKit.MutedBrush);
            msg.Margin = new Thickness(0, 8, 0, 0);
            localStack.Children.Add(msg);
        }

        var localCard = UiKit.Card(localStack, 22);
        localCard.Margin = new Thickness(0, 18, 0, 20);
        stack.Children.Add(localCard);

        // Connected computers list (adaptive 1 / 2 column Bento grid)
        var devCards = new List<FrameworkElement>();
        foreach (var dev in _controller.Devices)
        {
            var captured = dev;
            var row = new DockPanel();
            row.Children.Add(new MonitorIcon("devices", UiKit.MutedBrush, 24) { Margin = new Thickness(0, 0, 14, 0), VerticalAlignment = VerticalAlignment.Center });
            var chevron = new MonitorIcon("chevron", UiKit.MutedBrush, 16) { VerticalAlignment = VerticalAlignment.Center };
            DockPanel.SetDock(chevron, Dock.Right);
            row.Children.Add(chevron);

            var info = new StackPanel();
            info.Children.Add(UiKit.Heading(dev.Name + (dev.Local ? " · 服务主机" : ""), 16));
            var online = _controller.IsFresh && dev.Online;
            var availableTools = dev.Sources?.Where(s => s.Available).Select(s => MonitorTask.BrandFor(s.Tool)).ToList() ?? [];
            var srcLabel = availableTools.Count > 0 ? $" · 来源：{string.Join("、", availableTools)}" : "";
            var status = UiKit.Body($"{dev.Platform} · {(online ? "在线" : "离线")}{srcLabel}", 13, online ? UiKit.GreenBrush : UiKit.MutedBrush);
            status.Margin = new Thickness(0, 4, 0, 0);
            info.Children.Add(status);
            row.Children.Add(info);

            var card = UiKit.Card(row, 18, UiKit.CardRadius);
            var btn = UiKit.BareButton(card, () =>
            {
                _selectedDeviceId = captured.Id;
                RenderAll(false);
            });
            btn.HorizontalContentAlignment = HorizontalAlignment.Stretch;
            btn.MouseEnter += (_, _) => card.Background = UiKit.GlassCardHoverBrush;
            btn.MouseLeave += (_, _) => card.Background = UiKit.GlassCardBrush;
            devCards.Add(btn);
        }
        if (devCards.Count > 0)
        {
            var devCols = devCards.Count == 1 ? 1 : Math.Min(plan.DevicesColumns, devCards.Count);
            var devGrid = BuildResponsiveCardGrid(devCards, devCols, gap: 12.0);
            devGrid.Margin = new Thickness(0, 0, 0, 12);
            stack.Children.Add(devGrid);
        }

        var addBtn = UiKit.SecondaryButton("添加其他电脑", () => Dialogs.ShowAddComputerDialog(this, _controller), glyph: "plus");
        addBtn.HorizontalAlignment = HorizontalAlignment.Left;
        addBtn.Margin = new Thickness(0, 8, 0, 0);
        stack.Children.Add(addBtn);

        scroll.Content = stack;
        return scroll;
    }

    private UIElement BuildDeviceDetailContent(string deviceId)
    {
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(28, 22, 28, 30) };
        var stack = new StackPanel { MaxWidth = 820 };

        var backBtn = UiKit.SecondaryButton("返回我的电脑", () =>
        {
            _selectedDeviceId = null;
            RenderAll(false);
        }, glyph: "back");
        backBtn.HorizontalAlignment = HorizontalAlignment.Left;
        backBtn.Margin = new Thickness(0, 0, 0, 16);
        stack.Children.Add(backBtn);

        var computer = _controller.Devices.FirstOrDefault(d => d.Id == deviceId);
        if (computer is null)
        {
            stack.Children.Add(UiKit.Card(UiKit.Body("未找到这台电脑或已断开连接。", 14, UiKit.MutedBrush), 22));
            scroll.Content = stack;
            return scroll;
        }

        var headStack = new StackPanel();
        var topRow = new DockPanel();
        topRow.Children.Add(UiKit.Heading(computer.Name, 23));
        var topRightBtns = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
        if (_controller.IsFresh && computer.Online)
        {
            var startDevTaskBtn = UiKit.PrimaryButton("新建任务", () => OpenNewTaskFlow(computer.Id));
            if (!computer.Local)
                startDevTaskBtn.Margin = new Thickness(0, 0, 8, 0);
            topRightBtns.Children.Add(startDevTaskBtn);
        }
        if (!computer.Local)
        {
            var rmBtn = UiKit.SecondaryButton("移除电脑", async () =>
            {
                if (!Dialogs.Confirm(this, $"移除「{computer.Name}」？", "移除后，这台电脑需要重新配对才能同步；电脑上正在运行的任务不受影响。", "移除", danger: true))
                    return;
                if (await _controller.RemoveComputerAsync(computer.Id))
                {
                    _selectedDeviceId = null;
                    RenderAll(false);
                }
            }, !_controller.Mutating, UiKit.DangerBrush, "close");
            topRightBtns.Children.Add(rmBtn);
        }
        if (topRightBtns.Children.Count > 0)
        {
            DockPanel.SetDock(topRightBtns, Dock.Right);
            topRow.Children.Add(topRightBtns);
        }
        headStack.Children.Add(topRow);

        var online = _controller.IsFresh && computer.Online;
        var sub = UiKit.Body($"{computer.Platform} · {(online ? "在线" : "离线")} · 最近同步：{UsageFormatting.RelativeTime(computer.LastSeen)}", 13, online ? UiKit.GreenBrush : UiKit.MutedBrush);
        sub.Margin = new Thickness(0, 6, 0, 12);
        headStack.Children.Add(sub);

        if (computer.Sources is { Count: > 0 } sources)
        {
            foreach (var src in sources)
            {
                var line = UiKit.Body($"{MonitorTask.BrandFor(src.Tool)}：{(string.IsNullOrWhiteSpace(src.Detail) ? (src.Available ? "可读取任务记录" : "未发现本地会话") : src.Detail)}", 12.5, UiKit.MutedBrush);
                line.Margin = new Thickness(0, 2, 0, 2);
                headStack.Children.Add(line);
            }
        }

        var infoCard = UiKit.Card(headStack, 22);
        infoCard.Margin = new Thickness(0, 0, 0, 20);
        stack.Children.Add(infoCard);

        var devPreviewCard = BuildDevPreviewCard(null, computer);
        if (devPreviewCard is not null)
        {
            devPreviewCard.Margin = new Thickness(0, 0, 0, 20);
            stack.Children.Add(devPreviewCard);
        }

        var devTasks = _controller.Tasks.Where(t => t.DeviceId == computer.Id && !t.Archived).ToList();
        var taskHead = UiKit.Heading($"这台电脑上的任务 ({devTasks.Count})", 18);
        taskHead.Margin = new Thickness(0, 0, 0, 12);
        stack.Children.Add(taskHead);

        if (devTasks.Count == 0)
        {
            stack.Children.Add(UiKit.Card(UiKit.Body("暂无未归档的任务记录。", 13.5, UiKit.MutedBrush), 18));
        }
        else
        {
            foreach (var task in devTasks)
            {
                var captured = task;
                var col = new StackPanel();
                col.Children.Add(UiKit.TaskIdentityBadge(task.Tool, null, 15));
                var t = UiKit.Heading(task.Title, 14.5);
                t.Margin = new Thickness(0, 6, 0, 6);
                col.Children.Add(t);
                col.Children.Add(BuildStatusDotRow(task));

                var card = UiKit.Card(col, 16, UiKit.CardRadius);
                var btn = UiKit.BareButton(card, () =>
                {
                    _selectedTaskId = captured.Id;
                    _selectedDeviceId = null;
                    _page = NavPage.Tasks;
                    RenderAll(forceShell: true);
                });
                btn.Margin = new Thickness(0, 0, 0, 9);
                stack.Children.Add(btn);
            }
        }

        scroll.Content = stack;
        return scroll;
    }

    // ==================== USAGE PAGE (1:1 macOS UsageView.swift Multi-Card Grid) ====================

    private UIElement BuildUsageContent()
    {
        var plan = _lastMainPlan ?? CurrentMainShellPlan();
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(28, 24, 28, 32) };
        var stack = new StackPanel { MaxWidth = 1380 };

        var topBar = new DockPanel { Margin = new Thickness(0, 0, 0, 20) };
        topBar.Children.Add(UiKit.Heading("用量", 24));
        var topActions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        topActions.Children.Add(UiKit.SecondaryButton("提醒", () => Dialogs.ShowAlertSettings(this, _controller, OnQuotaAlertTriggered), glyph: "bell"));
        var refBtn = UiKit.SecondaryButton(_controller.UsageRefreshing ? "刷新中…" : "刷新", async () => await _controller.RefreshUsageAsync(), !_controller.UsageRefreshing, glyph: "refresh");
        refBtn.Margin = new Thickness(10, 0, 0, 0);
        topActions.Children.Add(refBtn);
        DockPanel.SetDock(topActions, Dock.Right);
        topBar.Children.Add(topActions);
        stack.Children.Add(topBar);

        if (!string.IsNullOrEmpty(_controller.UsageError))
        {
            var errBanner = UiKit.Body(_controller.UsageError, 13, UiKit.WarningBrush);
            errBanner.Margin = new Thickness(0, 0, 0, 14);
            stack.Children.Add(errBanner);
        }

        var now = DateTimeOffset.UtcNow;
        var providers = _controller.Usage?.Providers ?? [];
        var checks = _controller.Usage?.QuotaChecks ?? [];

        var displayedTools = new List<string> { "codex", "claude" };
        if (providers.Any(p => p.Tool == "antigravity")) displayedTools.Add("antigravity");

        var officialTools = displayedTools.Where(tool =>
        {
            var p = providers.FirstOrDefault(x => x.Tool == tool);
            var hasVisible = UsageFormatting.VisibleSources(p, now).Count > 0;
            var hasCheck = checks.Any(c => c.Tool == tool);
            var hasHistory = p?.TodayTokens is not null || p?.History?.Days?.Any(d => d.Tokens is not null) == true;
            return hasVisible || hasCheck || (hasHistory && tool is "claude" or "antigravity");
        }).ToList();

        // Section 1: Official Quotas (Fluid 1 / 2 / 3 Equal-Column Responsive Grid)
        if (officialTools.Count > 0)
        {
            var qSecTitle = UiKit.Heading("官方额度", 20);
            qSecTitle.Margin = new Thickness(0, 0, 0, 14);
            stack.Children.Add(qSecTitle);

            var qCards = new List<FrameworkElement>();
            foreach (var tool in officialTools)
            {
                var p = providers.FirstOrDefault(x => x.Tool == tool);
                var card = BuildOfficialQuotaCard(tool, p, checks, now);
                qCards.Add(card);
            }
            var qCols = qCards.Count == 1
                ? (plan.UsageColumns >= 2 ? 2 : 1)
                : Math.Min(plan.UsageColumns, qCards.Count);
            var qGrid = BuildResponsiveCardGrid(qCards, qCols, gap: 16.0);
            qGrid.Margin = new Thickness(0, 0, 0, 26);
            stack.Children.Add(qGrid);
        }

        // Section 2: Local Tokens (Fluid 1 / 2 / 3 Equal-Column Mon-Sun 90-Day Cards)
        var lSecTitle = UiKit.Heading("本地 Token", 20);
        lSecTitle.Margin = new Thickness(0, 4, 0, 14);
        stack.Children.Add(lSecTitle);

        var lCards = new List<FrameworkElement>();
        foreach (var tool in displayedTools)
        {
            var p = providers.FirstOrDefault(x => x.Tool == tool);
            var card = BuildLocalTokenProviderCard(tool, p, _controller.Usage?.Day);
            lCards.Add(card);
        }
        var lCols = lCards.Count == 1
            ? (plan.UsageColumns >= 2 ? 2 : 1)
            : Math.Min(plan.UsageColumns, lCards.Count);
        stack.Children.Add(BuildResponsiveCardGrid(lCards, lCols, gap: 16.0));

        scroll.Content = stack;
        return scroll;
    }

    private Border BuildOfficialQuotaCard(string tool, UsageProviderDto? provider, List<UsageQuotaCheckDto> checks, DateTimeOffset now)
    {
        var brand = MonitorTask.BrandFor(tool);
        var sources = UsageFormatting.VisibleSources(provider, now);
        var chosenSrcId = _selectedQuotaSource.TryGetValue(tool, out var savedId) ? savedId : null;
        var activeSource = sources.FirstOrDefault(s => s.Id == chosenSrcId) ?? sources.FirstOrDefault();

        var col = new StackPanel();
        var head = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
        var brandRow = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        brandRow.Children.Add(UiKit.BrandImage(tool, 22));
        var bName = UiKit.Heading(brand, 17);
        bName.Margin = new Thickness(9, 0, 0, 0);
        brandRow.Children.Add(bName);
        head.Children.Add(brandRow);

        var infoBtn = UiKit.IconButton("help", $"查看 {brand} 官方额度说明", () =>
        {
            var lines = new List<string>
            {
                "官方额度显示服务方实际返回的窗口及额度组，百分比只使用真实读数，不用 Token 推算。不同来源电脑可能登录不同账号，额度不合并。"
            };
            if (activeSource is not null)
            {
                foreach (var q in activeSource.Quotas)
                {
                    var paceInfo = UsageFormatting.ComputeQuotaPace(q, now);
                    var paceLine = paceInfo is not null
                        ? $"\n线性基准剩余 {UsageFormatting.Percentage(paceInfo.IdealRemainingPercent)} · {paceInfo.Label}"
                        : "";
                    lines.Add($"{UsageFormatting.QuotaDisplayTitle(q)}\n已用 {UsageFormatting.Percentage(q.Used)}（剩余 {UsageFormatting.Percentage(q.Remaining)}）\n{UsageFormatting.ResetText(q, now)}{paceLine}");
                }
            }
            foreach (var c in checks.Where(c => c.Tool == tool))
            {
                var src = string.IsNullOrWhiteSpace(c.SourceName) ? "" : $" · {c.SourceName}";
                lines.Add($"额度读取{src}：{UsageFormatting.QuotaCheckText(c.EffectiveState)}");
            }
            Dialogs.ShowMessage(this, $"{brand} 官方额度", string.Join("\n\n", lines));
        });
        infoBtn.Width = 28;
        infoBtn.Height = 28;
        DockPanel.SetDock(infoBtn, Dock.Right);
        infoBtn.HorizontalAlignment = HorizontalAlignment.Right;
        head.Children.Add(infoBtn);
        col.Children.Add(head);

        if (activeSource is not null)
        {
            if (sources.Count > 1)
            {
                var picker = UiKit.StyledComboBox();
                picker.Margin = new Thickness(0, 0, 0, 12);
                foreach (var s in sources)
                {
                    var item = UiKit.AddComboItem(picker, $"来源 · {s.Name}", s.Id);
                    if (s.Id == activeSource.Id) picker.SelectedItem = item;
                }
                var capturedTool = tool;
                picker.SelectionChanged += (_, _) =>
                {
                    if (picker.SelectedItem is ComboBoxItem sel && sel.Tag is string id && id != activeSource.Id)
                    {
                        _selectedQuotaSource[capturedTool] = id;
                        RenderAll(false);
                    }
                };
                col.Children.Add(picker);
            }
            else
            {
                var srcLabel = UiKit.Body($"来源 · {activeSource.Name}", 12, UiKit.MutedBrush);
                srcLabel.Margin = new Thickness(0, 0, 0, 12);
                col.Children.Add(srcLabel);
            }

            foreach (var q in activeSource.Quotas)
            {
                var qRow = new StackPanel { Margin = new Thickness(0, 0, 0, 14) };
                var l1 = new DockPanel();
                l1.Children.Add(UiKit.Body(UsageFormatting.QuotaDisplayTitle(q), 12.5));
                var isLow = (q.Remaining ?? 100) <= 15;
                var remTxt = new TextBlock
                {
                    Text = (q.Stale ? "上次剩余 " : "剩余 ") + UsageFormatting.Percentage(q.Remaining),
                    FontFamily = UiKit.AppFont,
                    FontSize = 12.5,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = isLow ? UiKit.WarningBrush : UiKit.TextBrush
                };
                DockPanel.SetDock(remTxt, Dock.Right);
                remTxt.HorizontalAlignment = HorizontalAlignment.Right;
                l1.Children.Add(remTxt);
                qRow.Children.Add(l1);

                var pace = UsageFormatting.ComputeQuotaPace(q, now);

                // Remaining bar with linear-time benchmark pace pointer
                var track = new Grid { Height = 9, Margin = new Thickness(0, 5, 0, 5) };
                track.Children.Add(new Border
                {
                    Height = 7,
                    VerticalAlignment = VerticalAlignment.Center,
                    Background = UiKit.SoftBrush,
                    CornerRadius = new CornerRadius(3.5)
                });
                var ratio = Math.Clamp((q.Remaining ?? 0.0) / 100.0, 0.0, 1.0);
                var barGrid = new Grid { Height = 7, VerticalAlignment = VerticalAlignment.Center };
                barGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(0.0001, ratio), GridUnitType.Star) });
                barGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(0.0001, 1.0 - ratio), GridUnitType.Star) });
                var fill = new Border
                {
                    Background = q.Stale ? UiKit.MutedBrush : (isLow ? UiKit.WarningBrush : UiKit.AccentBrush),
                    CornerRadius = new CornerRadius(3.5)
                };
                Grid.SetColumn(fill, 0);
                barGrid.Children.Add(fill);
                track.Children.Add(barGrid);

                if (pace is not null)
                {
                    var tickRatio = Math.Clamp(pace.IdealRemainingPercent / 100.0, 0.015, 0.985);
                    var tickGrid = new Grid { IsHitTestVisible = false };
                    tickGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(tickRatio, GridUnitType.Star) });
                    tickGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(0.0001, 1.0 - tickRatio), GridUnitType.Star) });
                    var tick = new Border
                    {
                        Width = 2.4,
                        Height = 9.5,
                        HorizontalAlignment = HorizontalAlignment.Right,
                        VerticalAlignment = VerticalAlignment.Center,
                        Margin = new Thickness(0, 0, -1.2, 0),
                        CornerRadius = new CornerRadius(1.2),
                        Background = pace.IsWarning ? UiKit.WarningBrush : UiKit.TextBrush,
                        BorderBrush = UiKit.SurfaceBrush,
                        BorderThickness = new Thickness(0.5),
                        Opacity = pace.IsWarning ? 0.98 : 0.85
                    };
                    Grid.SetColumn(tick, 0);
                    tickGrid.Children.Add(tick);
                    track.Children.Add(tickGrid);
                }

                track.ToolTip = pace is not null
                    ? $"剩余 {UsageFormatting.Percentage(q.Remaining)} · 线性时间基准剩余 {UsageFormatting.Percentage(pace.IdealRemainingPercent)} ({pace.Label})"
                    : null;
                qRow.Children.Add(track);

                var subDock = new DockPanel();
                if (!string.IsNullOrEmpty(pace?.Label))
                {
                    var paceColor = pace.State == "exhausted"
                        ? UiKit.DangerBrush
                        : pace.IsWarning
                            ? UiKit.WarningBrush
                            : pace.State == "ahead"
                                ? UiKit.GreenBrush
                                : UiKit.MutedBrush;
                    var paceTxt = new TextBlock
                    {
                        Text = pace.Label,
                        FontFamily = UiKit.AppFont,
                        FontSize = 11.5,
                        FontWeight = pace.IsWarning ? FontWeights.SemiBold : FontWeights.Normal,
                        Foreground = paceColor,
                        VerticalAlignment = VerticalAlignment.Center,
                        Margin = new Thickness(8, 0, 0, 0)
                    };
                    DockPanel.SetDock(paceTxt, Dock.Right);
                    paceTxt.HorizontalAlignment = HorizontalAlignment.Right;
                    subDock.Children.Add(paceTxt);
                }
                subDock.Children.Add(UiKit.Body($"已用 {UsageFormatting.Percentage(q.Used)} · {UsageFormatting.ResetText(q, now)}", 11.5, UiKit.MutedBrush));
                qRow.Children.Add(subDock);
                col.Children.Add(qRow);
            }
        }
        else
        {
            var chk = checks.FirstOrDefault(c => c.Tool == tool);
            var note = chk is not null
                ? (string.IsNullOrWhiteSpace(chk.SourceName) ? "" : $"来源 · {chk.SourceName}\n") + UsageFormatting.QuotaCheckText(chk.EffectiveState)
                : tool == "claude"
                ? "暂未收到官方额度读数 · 在电脑端登录 Claude Code 或开启状态栏同步后显示"
                : tool == "antigravity"
                ? "暂未收到官方额度读数 · 打开电脑端 Antigravity 后自动同步"
                : "等待电脑提供官方额度";
            col.Children.Add(UiKit.Body(note, 13, UiKit.MutedBrush));
        }

        return UiKit.Card(col, 20);
    }

    private Border BuildLocalTokenProviderCard(string tool, UsageProviderDto? provider, string? reportDay)
    {
        var brand = MonitorTask.BrandFor(tool);
        var today = UsageCalendar.TodayBeijing();
        var days = provider?.History?.Days ?? [];
        var dayMap = new Dictionary<DateOnly, UsageDayDto>();
        foreach (var d in days)
        {
            if (UsageCalendar.ParseDay(d.Day) is { } parsed)
                dayMap[parsed] = d;
        }

        var rawStart = UsageCalendar.ParseDay(provider?.History?.StartDay) ?? today;
        var min90 = today.AddDays(-89);
        var firstDay = rawStart > min90 ? rawStart : min90;
        if (firstDay > today) firstDay = today;

        var selectedWeekPref = _selectedUsageWeek.TryGetValue(tool, out var sw) ? sw : (DateOnly?)null;
        var weekMonday = UsageCalendar.ClampWeek(selectedWeekPref, firstDay, today);
        var selectedDayPref = _selectedUsageDay.TryGetValue(tool, out var sd) ? sd : (DateOnly?)null;
        var activeDate = UsageCalendar.ClampDayInWeek(selectedDayPref, weekMonday, firstDay, today);

        long? TokensOn(DateOnly d)
        {
            if (d < firstDay || d > today) return null;
            if (dayMap.TryGetValue(d, out var entry) && entry.Coverage != "unavailable" && entry.Tokens is >= 0)
                return entry.Tokens;
            if (d == today && UsageCalendar.ParseDay(reportDay) == today)
                return provider?.TodayTokens;
            return null;
        }

        var col = new StackPanel();

        // Brand Header
        var head = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
        var brandRow = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        brandRow.Children.Add(UiKit.BrandImage(tool, 22));
        var bName = UiKit.Heading(brand, 17);
        bName.Margin = new Thickness(9, 0, 0, 0);
        brandRow.Children.Add(bName);
        head.Children.Add(brandRow);

        var helpBtn = UiKit.IconButton("help", $"查看 {brand} 本地用量说明", () =>
        {
            var helpText = "柱状图汇总所有已授权电脑采集到的本地对话记录，按北京时间（周一至周日）展示最近 90 天。\n\n" +
                           "点选任意柱状图可查看当日精确数字与模型 Token 占比；未提供模型标识的部分计入「其他模型」。";
            Dialogs.ShowMessage(this, $"{brand} 本地用量", helpText);
        });
        helpBtn.Width = 28;
        helpBtn.Height = 28;
        DockPanel.SetDock(helpBtn, Dock.Right);
        helpBtn.HorizontalAlignment = HorizontalAlignment.Right;
        head.Children.Add(helpBtn);
        col.Children.Add(head);

        // Selected Day Headline
        var caption = activeDate == today ? "今日已记录" : $"{UsageCalendar.ShortDay(activeDate)} 已记录";
        col.Children.Add(UiKit.Body(caption, 12, UiKit.MutedBrush));

        var numRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 4, 0, 14) };
        var exactStr = UsageFormatting.ExactTokens(TokensOn(activeDate));
        numRow.Children.Add(new TextBlock
        {
            Text = exactStr,
            FontFamily = UiKit.AppFont,
            FontSize = 25,
            FontWeight = FontWeights.SemiBold,
            Foreground = UiKit.TextBrush,
            VerticalAlignment = VerticalAlignment.Bottom
        });
        numRow.Children.Add(new TextBlock
        {
            Text = " Token",
            FontFamily = UiKit.AppFont,
            FontSize = 12,
            Foreground = UiKit.MutedBrush,
            VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(5, 0, 0, 4)
        });
        col.Children.Add(numRow);

        // Week Navigation Bar (< 上一周 | 本周 · M.d - M.d | 下一周 >)
        var capturedTool = tool;
        var navRow = new DockPanel { Margin = new Thickness(0, 0, 0, 8) };
        var canPrev = weekMonday > UsageCalendar.MondayOf(firstDay);
        var canNext = weekMonday < UsageCalendar.MondayOf(today);

        var prevBtn = UiKit.IconButton("chevron-left", "上一周", () =>
        {
            var nextW = UsageCalendar.ClampWeek(weekMonday.AddDays(-7), firstDay, today);
            _selectedUsageWeek[capturedTool] = nextW;
            _selectedUsageDay[capturedTool] = UsageCalendar.ClampDayInWeek(null, nextW, firstDay, today);
            RenderAll(false);
        }, enabled: canPrev);
        prevBtn.Width = 26; prevBtn.Height = 26;
        DockPanel.SetDock(prevBtn, Dock.Left);
        navRow.Children.Add(prevBtn);

        var nextBtn = UiKit.IconButton("chevron-right", "下一周", () =>
        {
            var nextW = UsageCalendar.ClampWeek(weekMonday.AddDays(7), firstDay, today);
            _selectedUsageWeek[capturedTool] = nextW;
            _selectedUsageDay[capturedTool] = UsageCalendar.ClampDayInWeek(null, nextW, firstDay, today);
            RenderAll(false);
        }, enabled: canNext);
        nextBtn.Width = 26; nextBtn.Height = 26;
        DockPanel.SetDock(nextBtn, Dock.Right);
        navRow.Children.Add(nextBtn);

        var weekBtn = UiKit.LinkButton(UsageCalendar.WeekRangeLabel(weekMonday, today), () =>
        {
            _selectedUsageWeek[capturedTool] = UsageCalendar.MondayOf(today);
            _selectedUsageDay[capturedTool] = today;
            RenderAll(false);
        }, color: UiKit.MutedBrush);
        weekBtn.HorizontalAlignment = HorizontalAlignment.Center;
        weekBtn.VerticalAlignment = VerticalAlignment.Center;
        weekBtn.ToolTip = "点击回到本周";
        navRow.Children.Add(weekBtn);
        col.Children.Add(navRow);

        // Mon-Sun 7-day interactive bar chart with subtle slot tracks & hover highlight
        var weekDates = Enumerable.Range(0, 7).Select(i => weekMonday.AddDays(i)).ToList();
        var weekMax = Math.Max(1L, weekDates.Select(TokensOn).Where(v => v.HasValue).Select(v => v!.Value).DefaultIfEmpty(0L).Max());
        var weekdays = new[] { "一", "二", "三", "四", "五", "六", "日" };
        var barAccent = tool == "claude" ? UiKit.ClaudeInkBrush : UiKit.AccentBrush;

        var chartGrid = new UniformGrid { Columns = 7, Height = 112, Margin = new Thickness(0, 2, 0, 10) };
        for (var i = 0; i < 7; i++)
        {
            var d = weekDates[i];
            var avail = d >= firstDay && d <= today;
            var isSel = d == activeDate;
            var tok = TokensOn(d);
            var barH = tok is > 0 ? Math.Max(7.0, (tok.Value / (double)weekMax) * 72.0) : 4.0;

            var slotGrid = new Grid { Height = 72, Margin = new Thickness(3, 0, 3, 0) };
            slotGrid.Children.Add(new Border
            {
                Background = UiKit.GlassTrackBrush,
                CornerRadius = new CornerRadius(5),
                Opacity = avail ? 0.55 : 0.22
            });

            var baseFill = !avail
                ? Brushes.Transparent
                : isSel
                ? barAccent
                : (tok is > 0 ? UiKit.MutedBrush : UiKit.LineBrush);
            var baseOpacity = !avail ? 0.2 : isSel ? 1.0 : (tok is > 0 ? 0.42 : 0.55);

            var fillBar = new Border
            {
                Height = barH,
                VerticalAlignment = VerticalAlignment.Bottom,
                Background = baseFill,
                CornerRadius = new CornerRadius(5),
                Opacity = baseOpacity
            };
            slotGrid.Children.Add(fillBar);

            var barCol = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom };
            barCol.Children.Add(slotGrid);
            var wLbl = new TextBlock
            {
                Text = weekdays[i],
                FontFamily = UiKit.AppFont,
                FontSize = 11,
                FontWeight = isSel ? FontWeights.SemiBold : FontWeights.Normal,
                Foreground = isSel ? barAccent : UiKit.MutedBrush,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 6, 0, 0)
            };
            barCol.Children.Add(wLbl);

            var capturedDate = d;
            var cellBtn = UiKit.BareButton(barCol, () =>
            {
                _selectedUsageDay[capturedTool] = capturedDate;
                RenderAll(false);
            }, enabled: avail);
            if (avail && !isSel)
            {
                cellBtn.MouseEnter += (_, _) =>
                {
                    fillBar.Background = barAccent;
                    fillBar.Opacity = 0.78;
                    wLbl.Foreground = UiKit.TextBrush;
                };
                cellBtn.MouseLeave += (_, _) =>
                {
                    fillBar.Background = baseFill;
                    fillBar.Opacity = baseOpacity;
                    wLbl.Foreground = UiKit.MutedBrush;
                };
            }
            cellBtn.ToolTip = avail ? $"{UsageCalendar.FormatDay(d)}：{UsageFormatting.ExactTokens(tok)} Token" : "";
            chartGrid.Children.Add(cellBtn);
        }
        col.Children.Add(chartGrid);

        // Per-Model Token Breakdown with Proportional Progress Micro-Bars
        if (dayMap.TryGetValue(activeDate, out var selectedDayDto))
        {
            var shares = UsageFormatting.ComputeModelShares(selectedDayDto);
            if (shares.Count > 0)
            {
                col.Children.Add(new Border { Height = 1, Background = UiKit.LineBrush, Margin = new Thickness(0, 6, 0, 12) });
                var mHead = UiKit.Heading("模型 Token 占比", 14);
                mHead.Margin = new Thickness(0, 0, 0, 10);
                col.Children.Add(mHead);

                for (var idx = 0; idx < shares.Count; idx++)
                {
                    var s = shares[idx];
                    var itemCol = new StackPanel { Margin = new Thickness(0, 0, 0, 11) };
                    var top = new DockPanel();
                    top.Children.Add(UiKit.Body(s.DisplayName, 13));
                    var pct = new TextBlock
                    {
                        Text = UsageFormatting.Percentage(s.Percent),
                        FontFamily = UiKit.AppFont,
                        FontSize = 13,
                        FontWeight = FontWeights.SemiBold,
                        Foreground = UiKit.TextBrush
                    };
                    DockPanel.SetDock(pct, Dock.Right);
                    pct.HorizontalAlignment = HorizontalAlignment.Right;
                    top.Children.Add(pct);
                    itemCol.Children.Add(top);

                    var ratio = Math.Clamp(s.Percent / 100.0, 0.02, 1.0);
                    var shareTrack = new Grid { Height = 4.5, Margin = new Thickness(0, 4.5, 0, 4) };
                    shareTrack.Children.Add(new Border { Background = UiKit.GlassTrackBrush, CornerRadius = new CornerRadius(2.25) });
                    var shareGrid = new Grid();
                    shareGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(ratio, GridUnitType.Star) });
                    shareGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(0.0001, 1.0 - ratio), GridUnitType.Star) });
                    var shareFill = new Border
                    {
                        Background = barAccent,
                        CornerRadius = new CornerRadius(2.25),
                        Opacity = Math.Max(0.45, 1.0 - idx * 0.14)
                    };
                    Grid.SetColumn(shareFill, 0);
                    shareGrid.Children.Add(shareFill);
                    shareTrack.Children.Add(shareGrid);
                    itemCol.Children.Add(shareTrack);

                    itemCol.Children.Add(UiKit.Body($"{UsageFormatting.ExactTokens(s.Tokens)} Token", 11.5, UiKit.MutedBrush));
                    col.Children.Add(itemCol);
                }
            }
        }

        return UiKit.Card(col, 20);
    }

    // ==================== ACCOUNT & SETTINGS PAGE ====================

    private UIElement BuildAccountContent()
    {
        var plan = _lastMainPlan ?? CurrentMainShellPlan();
        var bento = plan.AccountTwoColumnBento;
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(28, 24, 28, 32) };
        var savedOffset = _accountScrollOffset;
        scroll.Loaded += (_, _) =>
        {
            if (savedOffset > 0)
                scroll.ScrollToVerticalOffset(savedOffset);
        };
        scroll.ScrollChanged += (_, _) =>
        {
            if (scroll.IsLoaded)
                _accountScrollOffset = scroll.VerticalOffset;
        };

        var stack = new StackPanel { MaxWidth = bento ? 1240 : 840 };
        stack.Children.Add(UiKit.Heading("我的", 24));

        // 1. Profile Card
        var user = _controller.Account?.User;
        var profileRow = new DockPanel();
        var avatar = UiKit.AvatarImage(user?.Avatar, 64);
        avatar.Margin = new Thickness(0, 0, 16, 0);
        DockPanel.SetDock(avatar, Dock.Left);
        profileRow.Children.Add(avatar);

        var editBtn = UiKit.SecondaryButton("编辑资料", () => Dialogs.ShowProfileEditor(this, _controller), _controller.Account is not null);
        DockPanel.SetDock(editBtn, Dock.Right);
        editBtn.VerticalAlignment = VerticalAlignment.Center;
        profileRow.Children.Add(editBtn);

        var nameCol = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        nameCol.Children.Add(UiKit.Heading(user?.DisplayOrUsername ?? "我的账号", 21));
        if (!string.IsNullOrEmpty(user?.Username))
        {
            var u = UiKit.Body(user!.Username, 13, UiKit.MutedBrush);
            u.Margin = new Thickness(0, 3, 0, 0);
            nameCol.Children.Add(u);
        }
        profileRow.Children.Add(nameCol);

        var profileCard = UiKit.Card(profileRow, 22);
        profileCard.Margin = new Thickness(0, 18, 0, 18);
        stack.Children.Add(profileCard);

        // 2. Appearance, Custom Wallpaper & Bing Daily Wallpaper Card
        var uiSettings = _controller.ReadUiSettings();
        var appStack = new StackPanel();
        appStack.Children.Add(UiKit.Heading("外观与个性化背景", 17));
        var themeLbl = UiKit.Body("深浅模式", 12.5, UiKit.MutedBrush);
        themeLbl.Margin = new Thickness(0, 12, 0, 6);
        appStack.Children.Add(themeLbl);

        var themeSeg = UiKit.SegmentedControl(
            new[] { ("system", "跟随系统"), ("light", "浅色主题"), ("dark", "深色主题") }
                .Select(opt =>
                {
                    var captured = opt.Item1;
                    return (opt.Item2, uiSettings.ThemeMode == opt.Item1, (Action)(() =>
                    {
                        var next = _controller.ReadUiSettings();
                        next.ThemeMode = captured;
                        _controller.SaveUiSettings(next);
                        SyncTheme(applyDwm: true);
                        RenderAll(forceShell: true);
                    }));
                }).ToList(),
            equalColumns: false);
        themeSeg.MaxWidth = 440;
        themeSeg.HorizontalAlignment = HorizontalAlignment.Left;
        appStack.Children.Add(themeSeg);

        var bgLbl = UiKit.Body("背景风格与壁纸（仅应用内生效，不影响系统桌面）", 12.5, UiKit.MutedBrush);
        bgLbl.Margin = new Thickness(0, 14, 0, 6);
        appStack.Children.Add(bgLbl);

        void PickCustomWallpaperFile()
        {
            var dlg = new OpenFileDialog
            {
                Title = "选择喜欢的背景图片",
                Filter = "图片文件 (*.jpg;*.jpeg;*.png;*.bmp;*.webp)|*.jpg;*.jpeg;*.png;*.bmp;*.webp"
            };
            if (dlg.ShowDialog(this) == true)
            {
                try
                {
                    _customWallpaper = WallpaperService.ImportCustomWallpaper(dlg.FileName);
                    var next = _controller.ReadUiSettings();
                    next.BackgroundMode = "custom";
                    _controller.SaveUiSettings(next);
                    SyncTheme(applyDwm: false);
                    RenderAll(forceShell: true);
                }
                catch (Exception ex)
                {
                    Dialogs.ShowMessage(this, "无法设置背景图片", ex.Message);
                }
            }
        }

        var bgSeg = UiKit.SegmentedControl(
            new[]
            {
                ("cloud", "云白"),
                ("blue", "浅海"),
                ("sand", "暖砂"),
                ("mist", "雾青"),
                ("bing", bento ? "必应日图" : "Bing 每日壁纸"),
                ("custom", bento ? "本地图片" : "自定义图片")
            }.Select(opt =>
            {
                var key = opt.Item1;
                return (opt.Item2, uiSettings.BackgroundMode == key, (Action)(() =>
                {
                    if (key == "custom" && !WallpaperService.HasCustomWallpaper)
                    {
                        PickCustomWallpaperFile();
                        return;
                    }
                    var next = _controller.ReadUiSettings();
                    next.BackgroundMode = key;
                    _controller.SaveUiSettings(next);
                    SyncTheme(applyDwm: false);
                    RenderAll(forceShell: true);
                    if (key == "bing")
                        _ = RefreshBingWallpaperIfNeededAsync(force: false);
                }));
            }).ToList(),
            equalColumns: false);
        bgSeg.Margin = new Thickness(0, 0, 0, 10);
        appStack.Children.Add(bgSeg);

        if (uiSettings.BackgroundMode is "bing" or "custom")
        {
            var previewImageSource = uiSettings.BackgroundMode == "custom"
                ? (_customWallpaper ??= WallpaperService.LoadCustomWallpaper())
                : (_bingInfo ??= WallpaperService.TryReadCachedBing())?.Image;

            var previewDock = new DockPanel { Margin = new Thickness(0, 2, 0, 0) };
            if (previewImageSource is not null)
            {
                var thumbImg = new Image
                {
                    Source = previewImageSource,
                    Stretch = Stretch.UniformToFill,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center
                };
                RenderOptions.SetBitmapScalingMode(thumbImg, BitmapScalingMode.HighQuality);

                var thumbFrame = new Border
                {
                    Width = 96,
                    Height = 58,
                    CornerRadius = new CornerRadius(UiKit.PodRadius),
                    BorderBrush = UiKit.GlassRimBrush,
                    BorderThickness = new Thickness(1),
                    Margin = new Thickness(0, 0, 14, 0),
                    ClipToBounds = true,
                    VerticalAlignment = VerticalAlignment.Center,
                    Child = new Border
                    {
                        CornerRadius = new CornerRadius(6),
                        Clip = new RectangleGeometry(new Rect(0, 0, 94, 56), 6, 6),
                        Child = thumbImg
                    }
                };
                DockPanel.SetDock(thumbFrame, Dock.Left);
                previewDock.Children.Add(thumbFrame);
            }

            var previewInfo = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            if (uiSettings.BackgroundMode == "custom")
            {
                var dimText = previewImageSource is not null
                    ? $"已启用本地自定义壁纸（{previewImageSource.PixelWidth} × {previewImageSource.PixelHeight}）"
                    : "尚未选择本地壁纸图片";
                previewInfo.Children.Add(UiKit.Body(dimText, 12.5));
                var actRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 6, 0, 0) };
                actRow.Children.Add(UiKit.SecondaryButton("更换本地图片…", PickCustomWallpaperFile, glyph: "plus"));
                previewInfo.Children.Add(actRow);
            }
            else
            {
                var bingStatus = _bingInfo is { Image: not null } bi
                    ? (string.IsNullOrEmpty(bi.Copyright) ? bi.Title : $"{bi.Title} — {bi.Copyright}")
                    : (_bingFetching ? "正在拉取今日必应高清壁纸…" : "暂未读取到壁纸，检查网络后可点击刷新");
                previewInfo.Children.Add(UiKit.Body(bingStatus, 12, UiKit.MutedBrush));

                var actRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 6, 0, 0) };
                actRow.Children.Add(UiKit.SecondaryButton(_bingFetching ? "正在获取…" : "刷新今日壁纸",
                    async () => await RefreshBingWallpaperIfNeededAsync(force: true),
                    enabled: !_bingFetching, glyph: "refresh"));
                if (_bingInfo is { Link.Length: > 0 } bInfo)
                {
                    var linkBtn = UiKit.LinkButton("查看 Bing 图片详情", () => MonitorController.OpenBrowserUrl(bInfo.Link));
                    linkBtn.VerticalAlignment = VerticalAlignment.Center;
                    linkBtn.Margin = new Thickness(12, 0, 0, 0);
                    actRow.Children.Add(linkBtn);
                }
                previewInfo.Children.Add(actRow);
            }
            previewDock.Children.Add(previewInfo);

            appStack.Children.Add(new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(UiKit.PodRadius),
                Padding = new Thickness(12, 10, 12, 10),
                Margin = new Thickness(0, 2, 0, 12),
                SnapsToDevicePixels = true,
                Child = previewDock
            });

            var presetLbl = UiKit.Body("快速预设（点选预设或下方无极微调）", 12.5, UiKit.MutedBrush);
            presetLbl.Margin = new Thickness(0, 0, 0, 6);
            appStack.Children.Add(presetLbl);

            var liveOp = uiSettings.GetEffectiveWallpaperOpacity();
            var liveBl = uiSettings.GetEffectiveWallpaperBlur();

            Action<double>? setOpSlider = null;
            Action<double>? setBlSlider = null;
            var opBadge = new TextBlock
            {
                Text = $"{Math.Round(liveOp * 100):0}%",
                FontFamily = UiKit.AppFont,
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                Foreground = UiKit.AccentBrush,
                VerticalAlignment = VerticalAlignment.Center
            };
            var blBadge = new TextBlock
            {
                Text = liveBl < 0.5 ? "无虚化" : $"{liveBl:0} px",
                FontFamily = UiKit.AppFont,
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                Foreground = UiKit.AccentBrush,
                VerticalAlignment = VerticalAlignment.Center
            };

            var presetHost = new ContentControl { Margin = new Thickness(0, 0, 0, 12) };
            void RebuildPresetControl(string matchedKey)
            {
                var seg = UiKit.SegmentedControl(
                    new[]
                    {
                        ("frosted", "轻雾隐约"),
                        ("balanced", "均衡沉浸"),
                        ("clear", "晶莹通透"),
                        ("vivid", "原画直显")
                    }.Select(opt =>
                    {
                        var k = opt.Item1;
                        return (opt.Item2, matchedKey == k, (Action)(() =>
                        {
                            var next = _controller.ReadUiSettings();
                            next.ApplyWallpaperPreset(k);
                            _controller.SaveUiSettings(next);
                            liveOp = next.GetEffectiveWallpaperOpacity();
                            liveBl = next.GetEffectiveWallpaperBlur();
                            opBadge.Text = $"{Math.Round(liveOp * 100):0}%";
                            blBadge.Text = liveBl < 0.5 ? "无虚化" : $"{liveBl:0} px";
                            setOpSlider?.Invoke(liveOp);
                            setBlSlider?.Invoke(liveBl);
                            UiKit.SetWallpaperVisualLive(liveOp, liveBl);
                            SyncTheme(applyDwm: false, rebuildBackdrop: false);
                            RenderAll(forceShell: true);
                        }));
                    }).ToList(),
                    equalColumns: false);
                seg.MaxWidth = 480;
                seg.HorizontalAlignment = HorizontalAlignment.Left;
                presetHost.Content = seg;
            }
            RebuildPresetControl(uiSettings.MatchingWallpaperPreset());
            appStack.Children.Add(presetHost);

            void CommitSteplessWallpaper()
            {
                var next = _controller.ReadUiSettings();
                next.WallpaperOpacity = liveOp;
                next.WallpaperBlur = liveBl;
                next.WallpaperStyle = next.MatchingWallpaperPreset();
                _controller.SaveUiSettings(next);
                RebuildPresetControl(next.WallpaperStyle);
                SyncTheme(applyDwm: false, rebuildBackdrop: false);
                RebuildTitleBar();
            }

            // Stepless Opacity Slider (5% - 95%)
            var opHead = new DockPanel { Margin = new Thickness(0, 0, 0, 2) };
            DockPanel.SetDock(opBadge, Dock.Right);
            opHead.Children.Add(opBadge);
            opHead.Children.Add(UiKit.Body("壁纸透明度（透出比例无极调节）", 12.5, UiKit.MutedBrush));
            appStack.Children.Add(opHead);

            var (opControl, opSetter) = UiKit.SteplessSlider(0.05, 0.95, liveOp,
                onLiveChange: val =>
                {
                    liveOp = val;
                    opBadge.Text = $"{Math.Round(val * 100):0}%";
                    UiKit.SetWallpaperVisualLive(liveOp, liveBl);
                },
                onCommit: _ => CommitSteplessWallpaper());
            setOpSlider = opSetter;
            opControl.Margin = new Thickness(0, 0, 0, 10);
            appStack.Children.Add(opControl);

            // Stepless Blur Slider (0 - 32 px)
            var blHead = new DockPanel { Margin = new Thickness(0, 0, 0, 2) };
            DockPanel.SetDock(blBadge, Dock.Right);
            blHead.Children.Add(blBadge);
            blHead.Children.Add(UiKit.Body("背景柔焦磨砂度（无极调节）", 12.5, UiKit.MutedBrush));
            appStack.Children.Add(blHead);

            var (blControl, blSetter) = UiKit.SteplessSlider(0.0, 32.0, liveBl,
                onLiveChange: val =>
                {
                    liveBl = val;
                    blBadge.Text = val < 0.5 ? "无虚化" : $"{val:0} px";
                    UiKit.SetWallpaperVisualLive(liveOp, liveBl);
                },
                onCommit: _ => CommitSteplessWallpaper());
            setBlSlider = blSetter;
            blControl.Margin = new Thickness(0, 0, 0, 12);
            appStack.Children.Add(blControl);
        }

        var trayToggle = UiKit.SettingToggleRow(
            "关闭窗口时最小化到系统托盘",
            "在后台静默保持连接与官方额度低余量提醒，双击托盘图标随时唤起",
            uiSettings.CloseToTray,
            v =>
            {
                var next = _controller.ReadUiSettings();
                next.CloseToTray = v;
                _controller.SaveUiSettings(next);
            });
        trayToggle.Margin = new Thickness(0, 4, 0, 14);
        appStack.Children.Add(trayToggle);

        var quickBtns = new StackPanel { Orientation = Orientation.Horizontal };
        quickBtns.Children.Add(UiKit.SecondaryButton("官方额度提醒设置", () => Dialogs.ShowAlertSettings(this, _controller, OnQuotaAlertTriggered), glyph: "bell"));
        var syncToggleBtn = UiKit.SecondaryButton(
            _controller.SyncOutput ? "账号结果同步：已开启" : "开启账号结果同步",
            async () =>
            {
                if (!_controller.SyncOutput && !Dialogs.Confirm(this, "同步最终结果？", "开启后，可在所有已登录设备查看最终结果和下载配套文件。", "开启"))
                    return;
                await _controller.SetResultSyncAsync(!_controller.SyncOutput);
            }, !_controller.Mutating, glyph: "shield");
        syncToggleBtn.Margin = new Thickness(10, 0, 0, 0);
        quickBtns.Children.Add(syncToggleBtn);
        appStack.Children.Add(quickBtns);

        var appCard = UiKit.Card(appStack, 22);

        // 2b. Compact Mini-Window Size & Content Customization Card
        var miniStack = new StackPanel();
        var miniHeadRow = new DockPanel { Margin = new Thickness(0, 0, 0, 10) };
        miniHeadRow.Children.Add(UiKit.Heading("精简小窗尺寸与显示内容", 17));
        var previewMiniBtn = UiKit.SecondaryButton(bento ? "小窗预览" : "立即切换小窗预览", ToggleCompactMode, glyph: "mini");
        DockPanel.SetDock(previewMiniBtn, Dock.Right);
        previewMiniBtn.HorizontalAlignment = HorizontalAlignment.Right;
        miniHeadRow.Children.Add(previewMiniBtn);
        miniStack.Children.Add(miniHeadRow);

        miniStack.Children.Add(UiKit.Body("小窗尺寸预设（支持手动拖拽窗口边缘，布局随宽高自动重排）", 12.5, UiKit.MutedBrush));
        var miniSizeSeg = UiKit.SegmentedControl(
            new[]
            {
                ("nano", bento ? "灵动胶囊" : "灵动胶囊 (368×240)"),
                ("small", bento ? "紧凑方卡" : "紧凑方卡 (384×356)"),
                ("medium", bento ? "标准小窗" : "标准小窗 (420×484)"),
                ("large", bento ? "双栏看板" : "双栏看板 (648×436)")
            }.Select(opt =>
            {
                var k = opt.Item1;
                return (opt.Item2, uiSettings.CompactSizePreset == k, (Action)(() =>
                {
                    var next = _controller.ReadUiSettings();
                    next.CompactSizePreset = k;
                    _controller.SaveUiSettings(next);
                    ApplyCompactSettingsLive();
                }));
            }).ToList(),
            equalColumns: false);
        miniSizeSeg.Margin = new Thickness(0, 6, 0, 12);
        miniStack.Children.Add(miniSizeSeg);

        miniStack.Children.Add(UiKit.Body("小窗默认显示模式", 12.5, UiKit.MutedBrush));
        var miniModeSeg = UiKit.SegmentedControl(
            new[]
            {
                ("overview", bento ? "智能总览" : "智能总览（额度 + 任务）"),
                ("tasks", bento ? "仅活跃任务" : "仅显示活跃任务"),
                ("quotas", bento ? "仅官方额度" : "仅显示官方额度")
            }.Select(opt =>
            {
                var k = opt.Item1;
                return (opt.Item2, uiSettings.CompactContentMode == k, (Action)(() =>
                {
                    var next = _controller.ReadUiSettings();
                    next.CompactContentMode = k;
                    _controller.SaveUiSettings(next);
                    ApplyCompactSettingsLive();
                }));
            }).ToList(),
            equalColumns: false);
        miniModeSeg.MaxWidth = 520;
        miniModeSeg.HorizontalAlignment = HorizontalAlignment.Left;
        miniModeSeg.Margin = new Thickness(0, 6, 0, 12);
        miniStack.Children.Add(miniModeSeg);

        var miniModLbl = UiKit.Body("小窗显示模块", 12.5, UiKit.MutedBrush);
        miniModLbl.Margin = new Thickness(0, 0, 0, 8);
        miniStack.Children.Add(miniModLbl);

        void SaveMiniToggle(Action<LocalUiSettings, bool> mutator, bool v)
        {
            var next = _controller.ReadUiSettings();
            mutator(next, v);
            _controller.SaveUiSettings(next);
        }

        miniStack.Children.Add(UiKit.SettingToggleRow("最低官方额度与重置倒计时", "在小窗顶部优先展示剩余比例最低的官方额度进度条", uiSettings.CompactShowLowestQuota, v => SaveMiniToggle((s, val) => s.CompactShowLowestQuota = val, v)));
        miniStack.Children.Add(UiKit.SettingToggleRow("Codex / Claude / AGY 多工具微缩速览条", "横向并排展示各工具实时剩余比例或当日用量", uiSettings.CompactShowToolStrip, v => SaveMiniToggle((s, val) => s.CompactShowToolStrip = val, v)));
        miniStack.Children.Add(UiKit.SettingToggleRow("今日本地已记录 Token 总量", "汇总各在线电脑今日采集到的本地 Token", uiSettings.CompactShowTodayTokens, v => SaveMiniToggle((s, val) => s.CompactShowTodayTokens = val, v)));
        miniStack.Children.Add(UiKit.SettingToggleRow("任务行显示来源电脑、目录与会话 Token", "在标准与双栏小窗中显示完整上下文与实时摘要", uiSettings.CompactShowTaskMeta, v => SaveMiniToggle((s, val) => s.CompactShowTaskMeta = val, v)));

        var miniCard = UiKit.Card(miniStack, 22);

        if (bento)
        {
            var settingsBento = BuildResponsiveCardGrid([appCard, miniCard], 2, gap: 18.0);
            settingsBento.Margin = new Thickness(0, 0, 0, 18);
            stack.Children.Add(settingsBento);
        }
        else
        {
            appCard.Margin = new Thickness(0, 0, 0, 18);
            miniCard.Margin = new Thickness(0, 0, 0, 18);
            stack.Children.Add(appCard);
            stack.Children.Add(miniCard);
        }

        // 2c. Sync Diagnostics Card (First-screen duration + 3 timestamps)
        var diagSample = _controller.SyncDiagnostics;
        var diagStack = new StackPanel();
        var diagHead = new DockPanel { Margin = new Thickness(0, 0, 0, 10) };
        diagHead.Children.Add(UiKit.Heading("同步诊断", 17));
        var probeSyncBtn = UiKit.SecondaryButton(
            _controller.Refreshing ? "测速中…" : "立即测速同步",
            async () => await _controller.RefreshWorkbenchAsync(),
            !_controller.Refreshing,
            glyph: "refresh");
        DockPanel.SetDock(probeSyncBtn, Dock.Right);
        probeSyncBtn.HorizontalAlignment = HorizontalAlignment.Right;
        diagHead.Children.Add(probeSyncBtn);
        diagStack.Children.Add(diagHead);

        void AddDiagRow(string keyLabel, string valLabel, bool highlight = false)
        {
            var r = new DockPanel { Margin = new Thickness(0, 4, 0, 4) };
            r.Children.Add(UiKit.Body(keyLabel, 13, UiKit.MutedBrush));
            var v = UiKit.Body(valLabel, 13, highlight ? UiKit.AccentBrush : UiKit.TextBrush);
            if (highlight) v.FontWeight = FontWeights.SemiBold;
            DockPanel.SetDock(v, Dock.Right);
            v.HorizontalAlignment = HorizontalAlignment.Right;
            r.Children.Add(v);
            diagStack.Children.Add(r);
        }

        AddDiagRow("最近一次首屏耗时", WorkbenchVisibilityPolicy.FormatDuration(diagSample.FirstScreenMs), highlight: true);
        AddDiagRow("最近一次刷新耗时", WorkbenchVisibilityPolicy.FormatDuration(diagSample.LastRefreshMs));
        diagStack.Children.Add(new Border { Height = 1, Background = UiKit.LineBrush, Margin = new Thickness(0, 8, 0, 8) });
        AddDiagRow("电脑完成", WorkbenchVisibilityPolicy.FormatTimestamp(diagSample.ComputerCompletedAt));
        AddDiagRow("服务收到", WorkbenchVisibilityPolicy.FormatTimestamp(diagSample.ServerReceivedAt));
        AddDiagRow("本机显示", WorkbenchVisibilityPolicy.FormatTimestamp(diagSample.ClientDisplayedAt), highlight: true);
        diagStack.Children.Add(new Border { Height = 1, Background = UiKit.LineBrush, Margin = new Thickness(0, 8, 0, 8) });
        AddDiagRow("电脑 → 服务", WorkbenchVisibilityPolicy.FormatLeg(diagSample.ComputerCompletedAt, diagSample.ServerReceivedAt));
        AddDiagRow("服务 → 本机", WorkbenchVisibilityPolicy.FormatLeg(diagSample.ServerReceivedAt, diagSample.ClientDisplayedAt));
        var diagExplain = UiKit.Body(WorkbenchVisibilityPolicy.Diagnosis(diagSample), 12.5, UiKit.MutedBrush);
        diagExplain.Margin = new Thickness(0, 8, 0, 0);
        diagStack.Children.Add(diagExplain);

        var diagCard = UiKit.Card(diagStack, 22);
        diagCard.Margin = new Thickness(0, 0, 0, 18);
        stack.Children.Add(diagCard);

        // 3. Logged-in Devices & Sessions Management (Parity with Android buildSessions)
        var nativeDevs = _controller.Account?.NativeDevices ?? [];
        var webSessions = _controller.Account?.Sessions ?? [];
        if (nativeDevs.Count > 0 || webSessions.Count > 0)
        {
            var devStack = new StackPanel();
            var devHead = new DockPanel { Margin = new Thickness(0, 0, 0, 10) };
            devHead.Children.Add(UiKit.Heading("登录设备管理", 17));
            var refAcc = UiKit.IconButton("refresh", "刷新设备列表", async () => await _controller.RefreshAccountAsync());
            refAcc.Width = 28; refAcc.Height = 28;
            DockPanel.SetDock(refAcc, Dock.Right);
            refAcc.HorizontalAlignment = HorizontalAlignment.Right;
            devHead.Children.Add(refAcc);
            devStack.Children.Add(devHead);

            foreach (var nd in nativeDevs)
            {
                var captured = nd;
                var row = new DockPanel { Margin = new Thickness(0, 8, 0, 8) };
                var left = new StackPanel();
                left.Children.Add(UiKit.Body(nd.Name, 14));
                var desc = nd.Current == true ? "当前桌面端" : $"App · 最近在线 {UsageFormatting.RelativeTime(nd.LastSeen)}";
                left.Children.Add(UiKit.Body(desc, 12, nd.Current == true ? UiKit.AccentBrush : UiKit.MutedBrush));
                row.Children.Add(left);

                if (nd.Current != true)
                {
                    var revokeBtn = UiKit.SecondaryButton("退出此设备", async () =>
                    {
                        if (!Dialogs.Confirm(this, $"退出「{captured.Name}」？", "退出后，该设备需要重新扫码或登录。", "退出", danger: true))
                            return;
                        await _controller.RevokeNativeConnectionAsync(captured.Id);
                    }, !_controller.Mutating, UiKit.DangerBrush);
                    DockPanel.SetDock(revokeBtn, Dock.Right);
                    revokeBtn.VerticalAlignment = VerticalAlignment.Center;
                    row.Children.Add(revokeBtn);
                }
                devStack.Children.Add(row);
            }

            foreach (var ws in webSessions)
            {
                var captured = ws;
                var row = new DockPanel { Margin = new Thickness(0, 8, 0, 8) };
                var left = new StackPanel();
                left.Children.Add(UiKit.Body(ws.Name, 14));
                var desc = ws.AuthorizesCurrent == true
                    ? "浏览器 · 授权了当前桌面端连接"
                    : $"浏览器 · 最近活动 {UsageFormatting.RelativeTime(ws.LastSeen)}";
                left.Children.Add(UiKit.Body(desc, 12, UiKit.MutedBrush));
                row.Children.Add(left);

                var revokeBtn = UiKit.SecondaryButton("退出会话", async () =>
                {
                    var warn = captured.AuthorizesCurrent == true
                        ? "此浏览器会话授权了当前桌面端登录，退出它也会让当前桌面端退出登录。"
                        : "退出后，该浏览器需要重新登录。";
                    if (!Dialogs.Confirm(this, $"退出「{captured.Name}」？", warn, "退出", danger: true))
                        return;
                    await _controller.RevokeBrowserSessionAsync(captured.Id, captured.AuthorizesCurrent == true);
                }, !_controller.Mutating, UiKit.DangerBrush);
                DockPanel.SetDock(revokeBtn, Dock.Right);
                revokeBtn.VerticalAlignment = VerticalAlignment.Center;
                row.Children.Add(revokeBtn);
                devStack.Children.Add(row);
            }

            var devCard = UiKit.Card(devStack, 22);
            devCard.Margin = new Thickness(0, 0, 0, 18);
            stack.Children.Add(devCard);
        }

        // 4. Open-Source & MiSans Attribution Card
        var licStack = new StackPanel();
        licStack.Children.Add(UiKit.Heading("开源与许可", 17));
        var licBody = UiKit.Body("本应用使用 MiSans 字体，由小米提供，按 MiSans 字体知识产权许可协议授权。Monitor 自有源码采用 MIT 许可证；二维码编码使用 QRCoder (MIT)；第三方组件与商标遵循各自条款。", 13, UiKit.MutedBrush);
        licBody.Margin = new Thickness(0, 8, 0, 12);
        licStack.Children.Add(licBody);

        var licLinks = new StackPanel { Orientation = Orientation.Horizontal };
        licLinks.Children.Add(UiKit.LinkButton("查看随附 MiSans 字体许可协议", Dialogs.OpenBundledMiSansLicense));
        var sep = UiKit.Body(" · ", 13, UiKit.MutedBrush);
        sep.Margin = new Thickness(10, 0, 10, 0);
        licLinks.Children.Add(sep);
        licLinks.Children.Add(UiKit.LinkButton("查看第三方开源声明", () => MonitorController.OpenBrowserUrl("https://github.com/Makabaka-zxh/agent-monitor/blob/main/THIRD_PARTY_NOTICES.md")));
        licStack.Children.Add(licLinks);

        var licCard = UiKit.Card(licStack, 22);
        licCard.Margin = new Thickness(0, 0, 0, 20);
        stack.Children.Add(licCard);

        // 5. Logout
        var logoutBtn = UiKit.SecondaryButton("退出这台电脑的登录", async () =>
        {
            if (!Dialogs.Confirm(this, "退出当前电脑登录？", "这台 Windows 电脑上的桌面端会断开账号连接，电脑上正在运行的任务不受影响。", "退出登录", danger: true))
                return;
            await _controller.LogoutAsync();
        }, !_controller.Mutating, UiKit.DangerBrush, "logout");
        logoutBtn.HorizontalAlignment = HorizontalAlignment.Left;
        stack.Children.Add(logoutBtn);

        scroll.Content = stack;
        return scroll;
    }
}
