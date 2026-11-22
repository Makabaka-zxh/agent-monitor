using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
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
    private int _compactTab; // 0 = 活跃任务, 1 = 官方额度
    private bool _dwmBackdropActive;
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
    private StackPanel? _sidebarNavHost;
    private TextBlock? _sidebarOnlineText;
    private TextBox? _taskSearchBox;
    private TextBlock? _searchMatchSummary;
    private StackPanel? _taskFilterHost;
    private StackPanel? _taskListHost;
    private ContentControl? _taskDetailRightHost;
    private string? _mountedDetailTaskId;
    private string _mountedTaskCardsSignature = "";
    private StackPanel? _taskDetailCardsHost;
    private TextBlock? _replyModelSummaryText;
    private TextBlock? _replyStatusText;
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

    // ==================== LIQUID GLASS TITLEBAR ====================

    private void RebuildTitleBar()
    {
        TitleBarHost.Background = UiKit.GlassHeaderBrush;
        TitleBarHost.BorderThickness = new Thickness(0);

        var dock = new DockPanel { LastChildFill = true };

        // Left: Clean App Identity (+ Live Online Micro-Badge in Compact HUD)
        var left = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(_compactMode ? 12 : 18, 0, 0, 0)
        };
        left.Children.Add(UiKit.BrandImage("app", _compactMode ? 18 : 19));
        left.Children.Add(new TextBlock
        {
            Text = "Monitor",
            FontFamily = UiKit.AppFont,
            FontSize = 13,
            FontWeight = FontWeights.SemiBold,
            Foreground = UiKit.TextBrush,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(7.5, 0, 0, 0)
        });

        if (_compactMode && _controller.Connected)
        {
            var online = _controller.IsFresh && _controller.OnlineDeviceCount > 0;
            var badge = new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(9),
                Padding = new Thickness(7, 2, 7, 2),
                Margin = new Thickness(8, 0, 0, 0),
                VerticalAlignment = VerticalAlignment.Center
            };
            var badgeRow = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            badgeRow.Children.Add(new Border
            {
                Width = 5.5,
                Height = 5.5,
                CornerRadius = new CornerRadius(3),
                Background = online ? UiKit.GreenBrush : UiKit.MutedBrush,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 4.5, 0)
            });
            badgeRow.Children.Add(new TextBlock
            {
                Text = _controller.IsFresh ? $"{_controller.OnlineDeviceCount} 在线" : "待同步",
                FontFamily = UiKit.AppFont,
                FontSize = 10.5,
                FontWeight = FontWeights.Medium,
                Foreground = UiKit.MutedBrush,
                VerticalAlignment = VerticalAlignment.Center
            });
            badge.Child = badgeRow;
            left.Children.Add(badge);
        }

        DockPanel.SetDock(left, Dock.Left);
        dock.Children.Add(left);

        // Right: Cohesive Glass Pill Action Cluster + Native Caption Buttons
        var right = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Stretch
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
                }, selected: Topmost, boxSize: 26, iconSize: 14);
                pinBtn.Margin = new Thickness(0, 0, 1.5, 0);
                quickRow.Children.Add(pinBtn);

                var cfgBtn = UiKit.IconButton("settings", "调整小窗尺寸与显示内容", () =>
                    Dialogs.ShowCompactWindowSettings(this, _controller, ApplyCompactSettingsLive), boxSize: 26, iconSize: 14);
                cfgBtn.Margin = new Thickness(0, 0, 1.5, 0);
                quickRow.Children.Add(cfgBtn);

                var refBtn = UiKit.IconButton("refresh", "立即同步刷新", async () =>
                {
                    await _controller.RefreshWorkbenchAsync();
                    await _controller.RefreshUsageAsync();
                }, boxSize: 26, iconSize: 14);
                refBtn.Margin = new Thickness(0, 0, 1.5, 0);
                quickRow.Children.Add(refBtn);

                var expandBtn = UiKit.IconButton("mini", "展开完整工作台", ToggleCompactMode, boxSize: 26, iconSize: 14);
                quickRow.Children.Add(expandBtn);
            }
            else
            {
                var miniBtn = UiKit.IconButton("mini", "切换桌面精简小窗", ToggleCompactMode, boxSize: 27, iconSize: 14.5);
                miniBtn.Margin = new Thickness(0, 0, 2, 0);
                quickRow.Children.Add(miniBtn);

                var refBtn = UiKit.IconButton("refresh", "立即同步刷新 (F5)", async () =>
                {
                    await _controller.RefreshWorkbenchAsync();
                    if (_page == NavPage.Usage) await _controller.RefreshUsageAsync();
                    if (_page == NavPage.Account) await _controller.RefreshAccountAsync();
                }, boxSize: 27, iconSize: 14.5);
                refBtn.Margin = new Thickness(0, 0, 2, 0);
                quickRow.Children.Add(refBtn);

                quickRow.Children.Add(UiKit.IconButton("help", "查看当前页面说明", ShowCurrentPageHelp, boxSize: 27, iconSize: 14.5));
            }

            var quickCapsule = new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(9.5),
                Padding = new Thickness(2.5, 2, 2.5, 2),
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, _compactMode ? 4 : 8, 0),
                SnapsToDevicePixels = true,
                Child = quickRow
            };
            right.Children.Add(quickCapsule);
        }

        var capWidth = _compactMode ? 34.0 : 44.0;
        right.Children.Add(CaptionButton("minimize", "最小化", () => WindowState = WindowState.Minimized, isClose: false, width: capWidth));
        if (!_compactMode)
        {
            right.Children.Add(CaptionButton("maximize", WindowState == WindowState.Maximized ? "向下还原" : "最大化", () =>
                WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized, isClose: false, width: capWidth));
        }
        right.Children.Add(CaptionButton("close", "关闭", Close, isClose: true, width: _compactMode ? 36.0 : 44.0));

        DockPanel.SetDock(right, Dock.Right);
        dock.Children.Add(right);
        dock.Children.Add(new Border()); // Drag spacer

        TitleBarHost.Child = dock;
    }

    private static Button CaptionButton(string glyph, string tooltip, Action onClick, bool isClose, double width = 44)
    {
        var icon = new MonitorIcon(glyph, UiKit.MutedBrush, 13.5, 1.5);
        var border = new Border
        {
            Width = width,
            Background = Brushes.Transparent,
            Child = icon
        };
        var btn = UiKit.BareButton(border, onClick, true);
        btn.ToolTip = tooltip;
        var closeBg = new SolidColorBrush(Color.FromRgb(0xC4, 0x2B, 0x1C));
        closeBg.Freeze();
        btn.MouseEnter += (_, _) =>
        {
            border.Background = isClose ? closeBg : UiKit.HoverBrush;
            icon.Ink = isClose ? Brushes.White : UiKit.TextBrush;
            icon.InvalidateVisual();
        };
        btn.MouseLeave += (_, _) =>
        {
            border.Background = Brushes.Transparent;
            icon.Ink = UiKit.MutedBrush;
            icon.InvalidateVisual();
        };
        return btn;
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

    private void ApplyCompactSettingsLive()
    {
        var ui = _controller.ReadUiSettings();
        if (_compactMode)
        {
            var (targetW, targetH) = ui.GetCompactDimensions();
            Width = targetW;
            Height = targetH;
        }
        RenderAll(forceShell: true);
    }

    private void ToggleCompactMode()
    {
        _compactMode = !_compactMode;
        if (_compactMode)
        {
            if (WindowState == WindowState.Maximized) WindowState = WindowState.Normal;
            _savedWidth = Width;
            _savedHeight = Height;
            var (targetW, targetH) = _controller.ReadUiSettings().GetCompactDimensions();
            Width = targetW;
            Height = targetH;
            Topmost = true;
            if (_controller.Usage is null)
                _ = _controller.RefreshUsageAsync();
        }
        else
        {
            Topmost = false;
            Width = Math.Max(1040, _savedWidth);
            Height = Math.Max(700, _savedHeight);
        }
        SyncTheme(applyDwm: true);
        RenderAll(forceShell: true);
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
            RootHost.Content = BuildLoginScreen();
            return;
        }

        if (_compactMode)
        {
            _mountedConnectedState = true;
            _mountedCompactState = true;
            RootHost.Content = BuildCompactView();
            return;
        }

        var shellChanged = forceShell
                           || _mountedConnectedState != true
                           || _mountedCompactState != false
                           || _mountedPage != _page;

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
                        CornerRadius = new CornerRadius(18),
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
                        CornerRadius = new CornerRadius(18),
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

        cardStack.Children.Add(UiKit.ElevatedGlass(inner, 32, 24));
        outer.Children.Add(cardStack);
        return outer;
    }

    // ==================== LIQUID GLASS DESKTOP HUD (COMPACT MINI-WINDOW) ====================

    private UIElement BuildCompactView()
    {
        var ui = _controller.ReadUiSettings();
        var isNano = ui.CompactSizePreset == "nano";
        var sidePad = isNano ? 9.0 : 12.0;

        var hudDock = new DockPanel { Background = Brushes.Transparent, LastChildFill = true };
        var topZone = new StackPanel { Margin = new Thickness(sidePad, isNano ? 7 : 10, sidePad, isNano ? 4 : 6) };

        var now = DateTimeOffset.UtcNow;
        var providers = _controller.Usage?.Providers ?? [];
        var lowest = FindLowestQuota(now);
        var todayTotal = providers.Sum(p => p.TodayTokens ?? 0);

        var showHero = ui.CompactContentMode != "tasks"
                       && (ui.CompactShowLowestQuota || ui.CompactShowTodayTokens || ui.CompactShowToolStrip);

        if (showHero)
        {
            var heroCol = new StackPanel();
            var hasHeroPrimary = false;

            if (ui.CompactShowLowestQuota && lowest is { } lq)
            {
                hasHeroPrimary = true;
                var rem = Math.Clamp(lq.Quota.Remaining ?? 0.0, 0.0, 100.0);
                var isCritical = rem <= 5.0;
                var isLow = rem <= 20.0;
                var meterBrush = isCritical ? UiKit.DangerBrush : isLow ? UiKit.WarningBrush : UiKit.AccentBrush;

                var topRow = new DockPanel();
                var pctBadge = new Border
                {
                    Background = UiKit.GlassTrackBrush,
                    BorderBrush = isLow ? meterBrush : UiKit.GlassSubtleRimBrush,
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(7),
                    Padding = new Thickness(7, 2, 7, 2),
                    VerticalAlignment = VerticalAlignment.Center,
                    Child = new TextBlock
                    {
                        Text = $"剩余 {UsageFormatting.Percentage(rem)}",
                        FontFamily = UiKit.AppFont,
                        FontSize = 11.5,
                        FontWeight = FontWeights.SemiBold,
                        Foreground = isLow ? meterBrush : UiKit.TextBrush
                    }
                };
                DockPanel.SetDock(pctBadge, Dock.Right);
                pctBadge.HorizontalAlignment = HorizontalAlignment.Right;
                topRow.Children.Add(pctBadge);

                var brandTitle = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
                brandTitle.Children.Add(UiKit.BrandImage(lq.Tool, 14.5));
                brandTitle.Children.Add(new TextBlock
                {
                    Text = $"{lq.Brand} · {UsageFormatting.QuotaDisplayTitle(lq.Quota)}",
                    FontFamily = UiKit.AppFont,
                    FontSize = 12,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = UiKit.TextBrush,
                    TextTrimming = TextTrimming.CharacterEllipsis,
                    Margin = new Thickness(6, 0, 6, 0),
                    VerticalAlignment = VerticalAlignment.Center
                });
                topRow.Children.Add(brandTitle);
                heroCol.Children.Add(topRow);

                var track = new Grid { Height = 6, Margin = new Thickness(0, isNano ? 6 : 8, 0, isNano ? 5 : 7) };
                track.Children.Add(new Border
                {
                    Background = UiKit.GlassTrackBrush,
                    BorderBrush = UiKit.GlassSubtleRimBrush,
                    BorderThickness = new Thickness(0.5),
                    CornerRadius = new CornerRadius(3)
                });
                if (rem <= 0.5)
                {
                    track.Children.Add(new Border
                    {
                        Width = 8,
                        HorizontalAlignment = HorizontalAlignment.Left,
                        Background = meterBrush,
                        CornerRadius = new CornerRadius(3),
                        Opacity = 0.85
                    });
                }
                else
                {
                    var ratio = Math.Clamp(rem / 100.0, 0.02, 1.0);
                    var barGrid = new Grid();
                    barGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(ratio, GridUnitType.Star) });
                    barGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(0.0001, 1.0 - ratio), GridUnitType.Star) });
                    var fill = new Border { Background = meterBrush, CornerRadius = new CornerRadius(3) };
                    Grid.SetColumn(fill, 0);
                    barGrid.Children.Add(fill);
                    track.Children.Add(barGrid);
                }
                heroCol.Children.Add(track);

                var metaRow = new DockPanel();
                if (ui.CompactShowTodayTokens)
                {
                    var tokLabel = new TextBlock
                    {
                        Text = $"今日 {UsageFormatting.CompactTokens(todayTotal)} Token",
                        FontFamily = UiKit.AppFont,
                        FontSize = 11,
                        FontWeight = FontWeights.Medium,
                        Foreground = UiKit.AccentBrush,
                        Margin = new Thickness(6, 0, 0, 0),
                        VerticalAlignment = VerticalAlignment.Center
                    };
                    DockPanel.SetDock(tokLabel, Dock.Right);
                    tokLabel.HorizontalAlignment = HorizontalAlignment.Right;
                    metaRow.Children.Add(tokLabel);
                }

                metaRow.Children.Add(new TextBlock
                {
                    Text = UsageFormatting.ResetText(lq.Quota, now),
                    FontFamily = UiKit.AppFont,
                    FontSize = 11,
                    Foreground = UiKit.MutedBrush,
                    TextTrimming = TextTrimming.CharacterEllipsis,
                    VerticalAlignment = VerticalAlignment.Center
                });
                heroCol.Children.Add(metaRow);
            }
            else if (ui.CompactShowTodayTokens)
            {
                hasHeroPrimary = true;
                var topRow = new DockPanel();
                topRow.Children.Add(UiKit.Body("今日已记录本地用量", 11.5, UiKit.MutedBrush));
                var onlineNote = new TextBlock
                {
                    Text = _controller.IsFresh ? "实时汇总" : "等待同步",
                    FontFamily = UiKit.AppFont,
                    FontSize = 11,
                    Foreground = UiKit.AccentBrush
                };
                DockPanel.SetDock(onlineNote, Dock.Right);
                topRow.Children.Add(onlineNote);
                heroCol.Children.Add(topRow);

                var bigTok = UiKit.Heading($"{UsageFormatting.CompactTokens(todayTotal)} Token", isNano ? 18 : 20);
                bigTok.Margin = new Thickness(0, 3, 0, 0);
                heroCol.Children.Add(bigTok);
            }

            if (ui.CompactShowToolStrip)
            {
                var toolSummaries = new List<(string Tool, string ShortLabel, string Metric, bool Low)>();
                foreach (var (toolId, shortName) in new[] { ("codex", "Codex"), ("claude", "Claude"), ("antigravity", "AGY") })
                {
                    var p = providers.FirstOrDefault(x => x.Tool == toolId);
                    var minQuota = p?.Quotas?
                        .Where(q => !q.Stale && !q.IsExpired(now) && q.Remaining.HasValue)
                        .OrderBy(q => q.Remaining!.Value)
                        .FirstOrDefault();
                    if (minQuota?.Remaining is { } r)
                        toolSummaries.Add((toolId, shortName, UsageFormatting.Percentage(r), r <= 15.0));
                    else if (p?.TodayTokens is > 0)
                        toolSummaries.Add((toolId, shortName, UsageFormatting.CompactTokens(p.TodayTokens), false));
                }

                if (toolSummaries.Count > 0)
                {
                    if (hasHeroPrimary)
                    {
                        heroCol.Children.Add(new Border
                        {
                            Height = 1,
                            Background = UiKit.GlassSubtleRimBrush,
                            Margin = new Thickness(0, isNano ? 6 : 9, 0, isNano ? 5 : 7),
                            Opacity = 0.65
                        });
                    }
                    var stripGrid = new UniformGrid { Columns = toolSummaries.Count };
                    foreach (var item in toolSummaries)
                    {
                        var pillRow = new StackPanel
                        {
                            Orientation = Orientation.Horizontal,
                            HorizontalAlignment = HorizontalAlignment.Center,
                            VerticalAlignment = VerticalAlignment.Center
                        };
                        pillRow.Children.Add(UiKit.BrandImage(item.Tool, 12));
                        pillRow.Children.Add(new TextBlock
                        {
                            Text = $"{item.ShortLabel} ",
                            FontFamily = UiKit.AppFont,
                            FontSize = 11,
                            Foreground = UiKit.MutedBrush,
                            Margin = new Thickness(4, 0, 0, 0),
                            VerticalAlignment = VerticalAlignment.Center
                        });
                        pillRow.Children.Add(new TextBlock
                        {
                            Text = item.Metric,
                            FontFamily = UiKit.AppFont,
                            FontSize = 11,
                            FontWeight = FontWeights.SemiBold,
                            Foreground = item.Low ? UiKit.WarningBrush : UiKit.TextBrush,
                            VerticalAlignment = VerticalAlignment.Center
                        });
                        var microBtn = UiKit.BareButton(pillRow, () =>
                        {
                            _compactTab = 1;
                            RenderAll(forceShell: false);
                        });
                        microBtn.ToolTip = "点击查看全部官方额度明细";
                        stripGrid.Children.Add(microBtn);
                    }
                    heroCol.Children.Add(stripGrid);
                }
            }

            if (heroCol.Children.Count > 0)
            {
                var heroPod = UiKit.ElevatedGlass(heroCol, isNano ? 10 : 13, isNano ? 14 : 16);
                heroPod.Margin = new Thickness(0, 0, 0, isNano ? 4 : 8);
                topZone.Children.Add(heroPod);
            }
        }

        var activeTasks = _controller.Tasks
            .Where(t => !t.Archived && t.EffectiveStatus is "running" or "waiting" or "error")
            .Take(24)
            .ToList();

        // Effective tab: "tasks" -> 0, "quotas" -> 1, "overview" -> _compactTab (or compact single-view in nano)
        var effectiveTab = ui.CompactContentMode switch
        {
            "tasks" => 0,
            "quotas" => 1,
            _ => _compactTab
        };

        if (ui.CompactContentMode == "overview" && !isNano)
        {
            var switcher = UiKit.SegmentedControl(
            [
                ($"活跃任务 ({activeTasks.Count})", effectiveTab == 0, () => { _compactTab = 0; RenderAll(forceShell: false); }),
                ("官方额度", effectiveTab == 1, () => { _compactTab = 1; RenderAll(forceShell: false); })
            ], equalColumns: false, compact: true);
            topZone.Children.Add(switcher);
        }

        if (topZone.Children.Count > 0)
        {
            DockPanel.SetDock(topZone, Dock.Top);
            hudDock.Children.Add(topZone);
        }

        // In Nano capsule mode with Hero visible & Overview mode, if height is tiny, still allow smooth scroll of active tasks below
        var scroll = new ScrollViewer
        {
            Background = Brushes.Transparent,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Padding = new Thickness(sidePad, 2, sidePad, isNano ? 8 : 12)
        };
        var bodyStack = new StackPanel();

        if (effectiveTab == 0)
        {
            if (activeTasks.Count == 0)
            {
                if (!isNano || !showHero)
                {
                    var emptyCol = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, isNano ? 6 : 16, 0, isNano ? 6 : 16) };
                    var t1 = UiKit.Heading("当前没有进行中的任务", 13.5);
                    t1.HorizontalAlignment = HorizontalAlignment.Center;
                    emptyCol.Children.Add(t1);
                    if (!isNano)
                    {
                        var t2 = UiKit.Body("所有电脑会话均已完成或待命", 12, UiKit.MutedBrush);
                        t2.HorizontalAlignment = HorizontalAlignment.Center;
                        t2.Margin = new Thickness(0, 4, 0, 10);
                        emptyCol.Children.Add(t2);
                        var openFull = UiKit.SecondaryButton("展开工作台查看历史记录", ToggleCompactMode, glyph: "mini");
                        openFull.HorizontalAlignment = HorizontalAlignment.Center;
                        emptyCol.Children.Add(openFull);
                    }
                    bodyStack.Children.Add(UiKit.Card(emptyCol, isNano ? 10 : 16, 14));
                }
            }
            else
            {
                foreach (var task in activeTasks)
                {
                    var captured = task;
                    var statusColor = TaskStatusColor(task);
                    var statusText = _controller.IsFresh && task.Stale != true ? task.StatusTitle : "待更新";

                    var row1 = new DockPanel();
                    var statusChip = new StackPanel
                    {
                        Orientation = Orientation.Horizontal,
                        VerticalAlignment = VerticalAlignment.Center,
                        Margin = new Thickness(8, 0, 0, 0)
                    };
                    statusChip.Children.Add(new Border
                    {
                        Width = 5.5,
                        Height = 5.5,
                        CornerRadius = new CornerRadius(3),
                        Background = statusColor,
                        VerticalAlignment = VerticalAlignment.Center,
                        Margin = new Thickness(0, 0, 4, 0)
                    });
                    statusChip.Children.Add(new TextBlock
                    {
                        Text = statusText,
                        FontFamily = UiKit.AppFont,
                        FontSize = 11,
                        FontWeight = FontWeights.Medium,
                        Foreground = statusColor,
                        VerticalAlignment = VerticalAlignment.Center
                    });
                    DockPanel.SetDock(statusChip, Dock.Right);
                    row1.Children.Add(statusChip);

                    var titleWrap = new DockPanel { VerticalAlignment = VerticalAlignment.Center };
                    var tIcon = UiKit.BrandImage(task.Tool, 14);
                    tIcon.Margin = new Thickness(0, 0, 6, 0);
                    DockPanel.SetDock(tIcon, Dock.Left);
                    titleWrap.Children.Add(tIcon);
                    titleWrap.Children.Add(new TextBlock
                    {
                        Text = task.Title,
                        FontFamily = UiKit.AppFont,
                        FontSize = isNano ? 12.5 : 13,
                        FontWeight = FontWeights.SemiBold,
                        Foreground = UiKit.TextBrush,
                        TextWrapping = TextWrapping.NoWrap,
                        TextTrimming = TextTrimming.CharacterEllipsis,
                        VerticalAlignment = VerticalAlignment.Center
                    });
                    row1.Children.Add(titleWrap);

                    var podCol = new StackPanel();
                    podCol.Children.Add(row1);

                    if (ui.CompactShowTaskMeta && !isNano)
                    {
                        var row2 = new DockPanel { Margin = new Thickness(20, 3.5, 0, 0) };
                        var rightMetaParts = new List<string>();
                        if (task.Usage?.TotalTokens is > 0)
                            rightMetaParts.Add(UsageFormatting.CompactTokens(task.Usage.TotalTokens));
                        if (!string.IsNullOrEmpty(task.UpdatedAt))
                            rightMetaParts.Add(UsageFormatting.RelativeTime(task.UpdatedAt));
                        if (rightMetaParts.Count > 0)
                        {
                            var rightMeta = new TextBlock
                            {
                                Text = string.Join(" · ", rightMetaParts),
                                FontFamily = UiKit.AppFont,
                                FontSize = 11,
                                Foreground = UiKit.MutedBrush,
                                Margin = new Thickness(8, 0, 0, 0),
                                VerticalAlignment = VerticalAlignment.Center
                            };
                            DockPanel.SetDock(rightMeta, Dock.Right);
                            row2.Children.Add(rightMeta);
                        }

                        var leftSub = MonitorTask.BrandFor(task.Tool)
                                      + (string.IsNullOrWhiteSpace(task.DeviceName) ? "" : $" · {task.DeviceName!.Trim()}")
                                      + (string.IsNullOrWhiteSpace(task.Project) ? "" : $" · {task.Project!.Trim()}");
                        row2.Children.Add(new TextBlock
                        {
                            Text = leftSub,
                            FontFamily = UiKit.AppFont,
                            FontSize = 11,
                            Foreground = UiKit.MutedBrush,
                            TextWrapping = TextWrapping.NoWrap,
                            TextTrimming = TextTrimming.CharacterEllipsis,
                            VerticalAlignment = VerticalAlignment.Center
                        });
                        podCol.Children.Add(row2);
                    }

                    var podBorder = new Border
                    {
                        Background = UiKit.GlassCardBrush,
                        BorderBrush = UiKit.GlassRimBrush,
                        BorderThickness = new Thickness(1),
                        CornerRadius = new CornerRadius(isNano ? 11 : 13),
                        Padding = isNano ? new Thickness(9, 6.5, 9, 6.5) : new Thickness(11, 8.5, 11, 8.5),
                        SnapsToDevicePixels = true,
                        Child = podCol
                    };
                    var btn = UiKit.BareButton(podBorder, () =>
                    {
                        _selectedTaskId = captured.Id;
                        _page = NavPage.Tasks;
                        ToggleCompactMode();
                    });
                    btn.ToolTip = $"{task.Title}\n点击展开工作台查看详情";
                    btn.MouseEnter += (_, _) =>
                    {
                        podBorder.Background = UiKit.GlassCardHoverBrush;
                        podBorder.BorderBrush = UiKit.AccentBrush;
                    };
                    btn.MouseLeave += (_, _) =>
                    {
                        podBorder.Background = UiKit.GlassCardBrush;
                        podBorder.BorderBrush = UiKit.GlassRimBrush;
                    };
                    btn.Margin = new Thickness(0, 0, 0, isNano ? 4.5 : 6);
                    bodyStack.Children.Add(btn);
                }
            }
        }
        else
        {
            // Compact Official Quota Inspector Tab
            var checks = _controller.Usage?.QuotaChecks ?? [];
            var tools = new List<string> { "codex", "claude" };
            if (providers.Any(p => p.Tool == "antigravity")) tools.Add("antigravity");

            foreach (var tool in tools)
            {
                var p = providers.FirstOrDefault(x => x.Tool == tool);
                var brand = MonitorTask.BrandFor(tool);
                var sources = UsageFormatting.VisibleSources(p, now);
                var cardCol = new StackPanel();

                var head = new DockPanel { Margin = new Thickness(0, 0, 0, 6) };
                if (p?.TodayTokens is >= 0)
                {
                    var tokTxt = new TextBlock
                    {
                        Text = $"今日 {UsageFormatting.CompactTokens(p.TodayTokens)} Token",
                        FontFamily = UiKit.AppFont,
                        FontSize = 11,
                        Foreground = UiKit.MutedBrush,
                        VerticalAlignment = VerticalAlignment.Center
                    };
                    DockPanel.SetDock(tokTxt, Dock.Right);
                    head.Children.Add(tokTxt);
                }
                var bLeft = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
                bLeft.Children.Add(UiKit.BrandImage(tool, 15));
                bLeft.Children.Add(new TextBlock
                {
                    Text = brand,
                    FontFamily = UiKit.AppFont,
                    FontSize = 13,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = UiKit.TextBrush,
                    Margin = new Thickness(6, 0, 0, 0),
                    VerticalAlignment = VerticalAlignment.Center
                });
                head.Children.Add(bLeft);
                cardCol.Children.Add(head);

                if (sources.Count > 0)
                {
                    foreach (var src in sources)
                    {
                        if (sources.Count > 1)
                        {
                            var sName = UiKit.Body($"来源 · {src.Name}", 11, UiKit.MutedBrush);
                            sName.Margin = new Thickness(0, 2, 0, 4);
                            cardCol.Children.Add(sName);
                        }
                        foreach (var q in src.Quotas)
                        {
                            var rem = Math.Clamp(q.Remaining ?? 0.0, 0.0, 100.0);
                            var low = rem <= 15.0;
                            var barColor = rem <= 5.0 ? UiKit.DangerBrush : low ? UiKit.WarningBrush : UiKit.AccentBrush;

                            var qTop = new DockPanel { Margin = new Thickness(0, 3, 0, 3) };
                            var qPct = new TextBlock
                            {
                                Text = $"剩余 {UsageFormatting.Percentage(rem)}",
                                FontFamily = UiKit.AppFont,
                                FontSize = 11.5,
                                FontWeight = FontWeights.SemiBold,
                                Foreground = low ? barColor : UiKit.TextBrush,
                                Margin = new Thickness(8, 0, 0, 0)
                            };
                            DockPanel.SetDock(qPct, Dock.Right);
                            qTop.Children.Add(qPct);
                            qTop.Children.Add(new TextBlock
                            {
                                Text = UsageFormatting.QuotaDisplayTitle(q),
                                FontFamily = UiKit.AppFont,
                                FontSize = 11.5,
                                Foreground = UiKit.TextBrush,
                                TextTrimming = TextTrimming.CharacterEllipsis
                            });
                            cardCol.Children.Add(qTop);

                            var qTrack = new Grid { Height = 5, Margin = new Thickness(0, 0, 0, 3) };
                            qTrack.Children.Add(new Border { Background = UiKit.GlassTrackBrush, CornerRadius = new CornerRadius(2.5) });
                            if (rem <= 0.5)
                            {
                                qTrack.Children.Add(new Border { Width = 6, HorizontalAlignment = HorizontalAlignment.Left, Background = barColor, CornerRadius = new CornerRadius(2.5) });
                            }
                            else
                            {
                                var g = new Grid();
                                var r = Math.Clamp(rem / 100.0, 0.02, 1.0);
                                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(r, GridUnitType.Star) });
                                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(0.0001, 1.0 - r), GridUnitType.Star) });
                                var f = new Border { Background = barColor, CornerRadius = new CornerRadius(2.5) };
                                Grid.SetColumn(f, 0);
                                g.Children.Add(f);
                                qTrack.Children.Add(g);
                            }
                            cardCol.Children.Add(qTrack);
                            cardCol.Children.Add(UiKit.Body(UsageFormatting.ResetText(q, now), 10.5, UiKit.MutedBrush));
                        }
                    }
                }
                else
                {
                    var chk = checks.FirstOrDefault(c => c.Tool == tool);
                    var msg = chk is not null ? UsageFormatting.QuotaCheckText(chk.EffectiveState) : "暂未收到官方额度读数";
                    cardCol.Children.Add(UiKit.Body(msg, 11.5, UiKit.MutedBrush));
                }

                var qCard = UiKit.Card(cardCol, 12, 14);
                qCard.Margin = new Thickness(0, 0, 0, 7);
                bodyStack.Children.Add(qCard);
            }

            var fullUsageBtn = UiKit.LinkButton("在完整工作台中查看 90 天柱状图与模型占比 →", () =>
            {
                _page = NavPage.Usage;
                ToggleCompactMode();
            });
            fullUsageBtn.HorizontalAlignment = HorizontalAlignment.Center;
            fullUsageBtn.Margin = new Thickness(0, 4, 0, 4);
            bodyStack.Children.Add(fullUsageBtn);
        }

        scroll.Content = bodyStack;
        hudDock.Children.Add(scroll);
        return hudDock;
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
        var grid = new Grid { Background = Brushes.Transparent };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(208) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var sidebarDock = new DockPanel { LastChildFill = true };

        var bottomStatus = new StackPanel { Margin = new Thickness(6, 8, 6, 4) };
        var miniBtn = UiKit.SecondaryButton("切换精简小窗", ToggleCompactMode, glyph: "mini");
        miniBtn.Margin = new Thickness(0, 0, 0, 12);
        bottomStatus.Children.Add(miniBtn);

        _sidebarOnlineText = UiKit.Body("", 12, UiKit.MutedBrush);
        _sidebarOnlineText.Margin = new Thickness(6, 0, 4, 2);
        bottomStatus.Children.Add(_sidebarOnlineText);
        DockPanel.SetDock(bottomStatus, Dock.Bottom);
        sidebarDock.Children.Add(bottomStatus);

        _sidebarNavHost = new StackPanel { Margin = new Thickness(2, 4, 2, 0) };
        sidebarDock.Children.Add(_sidebarNavHost);

        var floatingRail = UiKit.ElevatedGlass(sidebarDock, padding: 10, radius: 18, fill: UiKit.GlassRailBrush);
        floatingRail.Margin = new Thickness(12, 6, 6, 12);
        Grid.SetColumn(floatingRail, 0);
        grid.Children.Add(floatingRail);

        if (_page == NavPage.Tasks)
        {
            var tasksLayout = BuildTasksSplitShell();
            Grid.SetColumn(tasksLayout, 1);
            grid.Children.Add(tasksLayout);
        }
        else
        {
            _pageBodyHost = new ContentControl { HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch };
            Grid.SetColumn(_pageBodyHost, 1);
            grid.Children.Add(_pageBodyHost);
        }

        return grid;
    }

    private void UpdateSidebarState()
    {
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
                var itemDock = new DockPanel { LastChildFill = true };

                var badgeCount = nav == NavPage.Tasks ? activeTaskCount : nav == NavPage.Devices ? onlineDevCount : 0;
                if (badgeCount > 0)
                {
                    var isAttention = nav == NavPage.Tasks && hasWaitingTask;
                    var badgePill = new Border
                    {
                        Background = isAttention ? UiKit.WarningBrush : UiKit.GlassTrackBrush,
                        BorderBrush = isAttention ? Brushes.Transparent : UiKit.GlassSubtleRimBrush,
                        BorderThickness = new Thickness(1),
                        CornerRadius = new CornerRadius(8),
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

                var bg = active ? UiKit.GlassTrayBrush : Brushes.Transparent;
                var rim = active ? UiKit.GlassTrayRimBrush : Brushes.Transparent;
                var box = new Border
                {
                    Background = bg,
                    BorderBrush = rim,
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(11),
                    Padding = new Thickness(12, 9.5, 12, 9.5),
                    Margin = new Thickness(0, 0, 0, 4),
                    SnapsToDevicePixels = true,
                    Child = itemDock
                };
                var target = nav;
                var btn = UiKit.BareButton(box, () => SwitchPage(target));
                if (!active)
                {
                    btn.MouseEnter += (_, _) => box.Background = UiKit.HoverBrush;
                    btn.MouseLeave += (_, _) => box.Background = Brushes.Transparent;
                }
                _sidebarNavHost.Children.Add(btn);
            }
        }
        if (_sidebarOnlineText is not null)
        {
            var userLabel = _controller.Account?.User?.DisplayOrUsername;
            var onlinePart = _controller.IsFresh
                ? $"● {_controller.OnlineDeviceCount} 台在线"
                : "○ 连接待更新";
            _sidebarOnlineText.Text = string.IsNullOrWhiteSpace(userLabel)
                ? onlinePart
                : $"{onlinePart} · {userLabel}";
            _sidebarOnlineText.TextTrimming = TextTrimming.CharacterEllipsis;
            _sidebarOnlineText.TextWrapping = TextWrapping.NoWrap;
            _sidebarOnlineText.Foreground = _controller.IsFresh ? UiKit.GreenBrush : UiKit.MutedBrush;
        }
    }

    // ==================== TASKS MASTER-DETAIL ====================

    private UIElement BuildTasksSplitShell()
    {
        var split = new Grid();
        split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(396) });
        split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1) });
        split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var leftPane = new DockPanel { Background = Brushes.Transparent };
        var topControls = new StackPanel { Margin = new Thickness(14, 16, 14, 8) };

        var headerRow = new DockPanel { Margin = new Thickness(2, 0, 2, 11) };
        headerRow.Children.Add(UiKit.Heading("工作台", 23));
        _searchMatchSummary = UiKit.Body("", 12, UiKit.MutedBrush);
        _searchMatchSummary.VerticalAlignment = VerticalAlignment.Bottom;
        DockPanel.SetDock(_searchMatchSummary, Dock.Right);
        _searchMatchSummary.HorizontalAlignment = HorizontalAlignment.Right;
        headerRow.Children.Add(_searchMatchSummary);
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
            CornerRadius = new CornerRadius(11),
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
        var visible = _controller.VisibleTasks;
        if (_searchMatchSummary is not null)
        {
            _searchMatchSummary.Text = string.IsNullOrWhiteSpace(_controller.SearchQuery)
                ? ""
                : $"匹配 {visible.Count} 项";
        }

        if (_taskFilterHost is not null)
        {
            _taskFilterHost.Children.Clear();

            var toolItems = Enum.GetValues<ToolFilter>().Select(tf =>
            {
                var target = tf;
                var countInScope = _controller.Tasks.Count(t => t.Matches(tf, _controller.Scope, _controller.IsFresh, _controller.SearchQuery));
                var shortTitle = tf == ToolFilter.Claude ? "Claude" : tf.Title();
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
                            MaxWidth = 145,
                            VerticalAlignment = VerticalAlignment.Center
                        });
                    }
                    col.Children.Add(statusRow);

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
                        CornerRadius = new CornerRadius(15),
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
                    CornerRadius = new CornerRadius(16),
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

                var t2 = UiKit.Body("在左侧点选任意电脑会话，即可查看最终结果、下载交付附件、回答选择题或继续发送指令。", 13, UiKit.MutedBrush);
                t2.TextAlignment = TextAlignment.Center;
                t2.Margin = new Thickness(0, 7, 0, 16);
                emptyCardInner.Children.Add(t2);

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

                var emptyGlass = UiKit.ElevatedGlass(emptyCardInner, 28, 22);
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

    private UIElement BuildTaskDetailPane(MonitorTask task, TaskDetailController detail)
    {
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(28, 22, 28, 30) };
        var root = new StackPanel { MaxWidth = 840 };

        _taskDetailCardsHost = new StackPanel();
        root.Children.Add(_taskDetailCardsHost);

        if (task.SupportsReply)
        {
            var replyStack = new StackPanel();
            var topRow = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
            topRow.Children.Add(UiKit.Heading("继续任务", 18));

            var rightTools = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
            _replyModelSummaryText = UiKit.Body(detail.ModelSettingsSummary, 12, UiKit.MutedBrush);
            _replyModelSummaryText.VerticalAlignment = VerticalAlignment.Center;
            _replyModelSummaryText.Margin = new Thickness(0, 0, 10, 0);
            rightTools.Children.Add(_replyModelSummaryText);
            rightTools.Children.Add(UiKit.SecondaryButton("模型设置", () => Dialogs.ShowModelSettings(this, task, detail), glyph: "settings"));
            DockPanel.SetDock(rightTools, Dock.Right);
            topRow.Children.Add(rightTools);
            replyStack.Children.Add(topRow);

            _replyInputBox = UiKit.StyledTextBox(detail.DraftText, "告诉它下一步做什么（Ctrl + Enter 快捷发送）…", multiline: true);
            _replyInputBox.MinHeight = 96;
            _replyInputBox.MaxHeight = 190;
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
            replyStack.Children.Add(_replyInputBox);

            var footer = new DockPanel { Margin = new Thickness(0, 12, 0, 0) };
            _replyStatusText = UiKit.Body(detail.ReplyMessage, 12.5, UiKit.MutedBrush);
            _replyStatusText.VerticalAlignment = VerticalAlignment.Center;
            footer.Children.Add(_replyStatusText);

            var sendBtn = UiKit.PrimaryButton("发送回复", async () =>
            {
                await detail.SendReplyAsync(_replyInputBox.Text);
                if (string.IsNullOrEmpty(detail.DraftText) && _replyInputBox is not null)
                    _replyInputBox.Text = "";
            });
            _replySendBtn = sendBtn;
            DockPanel.SetDock(sendBtn, Dock.Right);
            sendBtn.HorizontalAlignment = HorizontalAlignment.Right;
            footer.Children.Add(sendBtn);
            replyStack.Children.Add(footer);

            var replyCard = UiKit.Card(replyStack, 22);
            replyCard.Margin = new Thickness(0, 18, 0, 0);
            root.Children.Add(replyCard);
        }

        scroll.Content = root;
        RefreshMountedTaskDetail();
        return scroll;
    }

    private void UpdateReplyComposerControls(TaskDetailController detail)
    {
        if (_replyModelSummaryText is not null)
            _replyModelSummaryText.Text = detail.ModelSettingsSummary;
        if (_replyStatusText is not null)
            _replyStatusText.Text = detail.ReplyMessage;
        if (_replySendBtn is not null)
        {
            var mayRetry = detail.PendingReply?.MayRetry == true;
            var label = detail.ReplySending ? "正在发送…" : mayRetry ? "重试原消息" : "发送回复";
            var hasDraft = !string.IsNullOrWhiteSpace(_replyInputBox?.Text ?? detail.DraftText);
            var enabled = !detail.ReplySending && (mayRetry || (detail.ReplyAvailable && hasDraft));
            UiKit.SetButtonState(_replySendBtn, label, enabled, primary: true);
        }
    }

    private string ComputeTaskDetailSignature(MonitorTask task, TaskDetailController detail)
    {
        var sb = new System.Text.StringBuilder(256);
        sb.Append(UiKit.IsDark ? 'D' : 'L').Append('|')
          .Append(_controller.IsFresh ? '1' : '0').Append('|')
          .Append(_controller.Mutating ? '1' : '0').Append('|')
          .Append(_controller.SyncOutput ? '1' : '0').Append('|')
          .Append(task.Id).Append('|')
          .Append(task.Title).Append('|')
          .Append(task.Status).Append('|')
          .Append(task.EffectivePendingKind).Append('|')
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
          .Append(detail.Controls is not null ? '1' : '0').Append('|')
          .Append(detail.Controls?.Reason).Append('|')
          .Append(detail.DecisionSending ? '1' : '0').Append('|')
          .Append(detail.ControlsFeedback).Append('|');

        if (detail.Result?.Files is { Count: > 0 } files)
        {
            foreach (var f in files)
                sb.Append(f.Id).Append(':').Append(f.Ready == false ? '0' : '1').Append(';');
        }
        if (detail.Controls?.Pending is { Count: > 0 } pendings)
        {
            foreach (var p in pendings)
                sb.Append(SessionControlPolicy.AttentionFingerprint(p)).Append(':').Append(p.State).Append(';');
        }
        return sb.ToString();
    }

    private void RefreshMountedTaskDetail()
    {
        if (_taskDetailCardsHost is null || _taskDetail is null || _selectedTaskId is null) return;
        var liveTask = _controller.Tasks.FirstOrDefault(t => t.Id == _selectedTaskId);
        if (liveTask is null) return;

        UpdateReplyComposerControls(_taskDetail);

        var nextSignature = ComputeTaskDetailSignature(liveTask, _taskDetail);
        if (nextSignature == _mountedTaskCardsSignature && _taskDetailCardsHost.Children.Count > 0)
            return;
        _mountedTaskCardsSignature = nextSignature;

        _taskDetailCardsHost.Children.Clear();

        // Identity + Actions row
        var topIdentityRow = new DockPanel { Margin = new Thickness(0, 0, 0, 10) };
        var idBadge = UiKit.TaskIdentityBadge(liveTask.Tool, liveTask.DeviceName ?? "电脑", 18);
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
        topIdentityRow.Children.Add(idBtn);

        var archiveBtn = UiKit.SecondaryButton(
            liveTask.Archived ? "取消归档" : "归档任务",
            async () => await _controller.ArchiveTaskAsync(liveTask),
            !_controller.Mutating,
            glyph: liveTask.Archived ? "restore" : "archive");
        DockPanel.SetDock(archiveBtn, Dock.Right);
        archiveBtn.HorizontalAlignment = HorizontalAlignment.Right;
        topIdentityRow.Children.Add(archiveBtn);
        _taskDetailCardsHost.Children.Add(topIdentityRow);

        // Large MiSans Task Title
        var titleBlock = UiKit.Heading(liveTask.Title, 25);
        titleBlock.Margin = new Thickness(0, 2, 0, 8);
        _taskDetailCardsHost.Children.Add(titleBlock);

        // Status + Project Row
        var statusRow = BuildStatusDotRow(liveTask);
        if (!string.IsNullOrWhiteSpace(liveTask.Project))
        {
            statusRow.Children.Add(new TextBlock
            {
                Text = " · 目录：" + liveTask.Project,
                FontFamily = UiKit.AppFont,
                FontSize = 12,
                Foreground = UiKit.MutedBrush,
                VerticalAlignment = VerticalAlignment.Center
            });
        }
        statusRow.Margin = new Thickness(0, 0, 0, 14);
        _taskDetailCardsHost.Children.Add(statusRow);

        if (!string.IsNullOrWhiteSpace(liveTask.Preview))
        {
            var prev = UiKit.Body(liveTask.Preview!, 13.5, UiKit.MutedBrush);
            prev.Margin = new Thickness(0, 0, 0, 16);
            _taskDetailCardsHost.Children.Add(prev);
        }

        // Top-placed Pending Attention Requests (Structured Questions / Single-Turn Approvals — Parity with Mobile & macOS)
        if (_taskDetail.Controls?.Pending is { Count: > 0 } pendings)
        {
            foreach (var req in pendings.Where(p => p.State is "pending" or "submitted" or "forwarded" or "acknowledged"))
            {
                var attCard = BuildAttentionCard(req, _taskDetail);
                attCard.Margin = new Thickness(0, 0, 0, 16);
                _taskDetailCardsHost.Children.Add(attCard);
            }
        }
        else if (liveTask.SupportsReply && (!string.IsNullOrEmpty(liveTask.EffectivePendingKind) || liveTask.EffectiveStatus == "waiting"))
        {
            var waitStack = new StackPanel();
            var waitHead = new DockPanel { Margin = new Thickness(0, 0, 0, 8) };
            var headTitle = liveTask.EffectivePendingKind == "question" ? "等待回答" : "待你处理";
            waitHead.Children.Add(UiKit.Heading(headTitle, 16.5));
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

            var waitCard = UiKit.Card(waitStack, 18, 16);
            waitCard.BorderBrush = UiKit.WarningBrush;
            waitCard.Margin = new Thickness(0, 0, 0, 16);
            _taskDetailCardsHost.Children.Add(waitCard);
        }

        // Error Analysis Card (Parity with Android NativeErrorPresentation)
        if (liveTask.ErrorAnalysis is { } err && !string.IsNullOrWhiteSpace(err.Summary))
        {
            var errStack = new StackPanel();
            errStack.Children.Add(UiKit.Heading("错误报告摘要", 15.5));
            var sum = UiKit.Body(err.Summary!, 13.5, UiKit.WarningBrush);
            sum.Margin = new Thickness(0, 6, 0, 0);
            errStack.Children.Add(sum);
            if (!string.IsNullOrWhiteSpace(err.Suggestion))
            {
                var sug = UiKit.Body("建议核查：" + err.Suggestion!, 13, UiKit.MutedBrush);
                sug.Margin = new Thickness(0, 6, 0, 0);
                errStack.Children.Add(sug);
            }
            var errCard = UiKit.Card(errStack, 18, 16);
            errCard.BorderBrush = UiKit.WarningBrush;
            errCard.Margin = new Thickness(0, 0, 0, 16);
            _taskDetailCardsHost.Children.Add(errCard);
        }

        // Conversation Token Card
        if (liveTask.Usage?.TotalTokens is { } totalTokens)
        {
            var tokRow = new DockPanel();
            var left = new StackPanel();
            left.Children.Add(UiKit.Body("本次对话已记录", 12, UiKit.MutedBrush));
            var valText = UsageFormatting.CompactTokens(totalTokens) + " Token" + (liveTask.Usage.Coverage == "complete" ? "" : " · 部分记录");
            var val = UiKit.Heading(valText, 19);
            val.Margin = new Thickness(0, 4, 0, 0);
            left.Children.Add(val);
            tokRow.Children.Add(left);

            var detailBtn = UiKit.SecondaryButton("查看明细", () => Dialogs.ShowTaskTokenBreakdown(this, liveTask.Usage));
            DockPanel.SetDock(detailBtn, Dock.Right);
            detailBtn.VerticalAlignment = VerticalAlignment.Center;
            detailBtn.HorizontalAlignment = HorizontalAlignment.Right;
            tokRow.Children.Add(detailBtn);

            var tokCard = UiKit.Card(tokRow, 18, 18);
            tokCard.Margin = new Thickness(0, 0, 0, 16);
            _taskDetailCardsHost.Children.Add(tokCard);
        }

        // Final Result Card
        var resStack = new StackPanel();
        var resHeader = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
        var reloadResBtn = UiKit.IconButton("refresh", "刷新最终结果", async () => await _taskDetail.LoadResultAsync(true), enabled: !_taskDetail.ResultLoading);
        DockPanel.SetDock(reloadResBtn, Dock.Right);
        reloadResBtn.HorizontalAlignment = HorizontalAlignment.Right;
        resHeader.Children.Add(reloadResBtn);

        var resLeftTitle = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        resLeftTitle.Children.Add(UiKit.Heading("最终结果", 18));
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
                MaxHeight = 360,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto
            };
            resStack.Children.Add(new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(12),
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

            var actionRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 14, 0, 0) };
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
                var filesLabel = UiKit.Body("配套文件（SHA-256 完整性校验下载）", 12.5, UiKit.MutedBrush);
                filesLabel.Margin = new Thickness(0, 16, 0, 8);
                resStack.Children.Add(filesLabel);

                foreach (var file in files)
                {
                    var capturedFile = file;
                    var fRow = new DockPanel();
                    fRow.Children.Add(UiKit.Body(file.Name, 13.5));
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
                        Background = UiKit.BgBrush,
                        BorderBrush = UiKit.LineBrush,
                        BorderThickness = new Thickness(1),
                        CornerRadius = new CornerRadius(12),
                        Padding = new Thickness(12, 9, 12, 9),
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
        _taskDetailCardsHost.Children.Add(UiKit.Card(resStack, 22));

        if (_replyModelSummaryText is not null)
            _replyModelSummaryText.Text = _taskDetail.ModelSettingsSummary;
        if (_replyStatusText is not null)
            _replyStatusText.Text = _taskDetail.ReplyMessage;
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
            Text = SessionControlPolicy.StateLabel(req.State),
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

        var defaultTitle = req.Kind == "question" ? "电脑正在等待你选择选项" : "等待确认操作";
        stack.Children.Add(UiKit.Heading(string.IsNullOrWhiteSpace(req.Title) ? defaultTitle : req.Title, 16.5));
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
                CornerRadius = new CornerRadius(10),
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

        if (req.State != "pending")
        {
            var receiptNote = req.State switch
            {
                "submitted" => "已发送你的选择，正在等待电脑确认接收…",
                "forwarded" => "电脑已接收你的选择，正在继续执行任务…",
                "acknowledged" => "电脑已确认处理完成",
                _ => SessionControlPolicy.StateLabel(req.State)
            };
            var receiptBox = new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = UiKit.GlassSubtleRimBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(12, 9, 12, 9),
                Margin = new Thickness(0, 12, 0, 0),
                Child = UiKit.Body(receiptNote, 13, UiKit.MutedBrush)
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
                            CornerRadius = new CornerRadius(11),
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

        if (!string.IsNullOrEmpty(detail.ControlsFeedback))
        {
            var fb = UiKit.Body(detail.ControlsFeedback, 12.5, UiKit.MutedBrush);
            fb.Margin = new Thickness(0, 8, 0, 0);
            stack.Children.Add(fb);
        }

        var border = UiKit.Card(stack, 20, 18);
        border.BorderBrush = req.State == "pending" ? UiKit.WarningBrush : UiKit.GlassRimBrush;
        return border;
    }

    // ==================== COMPUTERS & DEVICE DETAIL ====================

    private UIElement BuildDevicesContent()
    {
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(28, 24, 28, 30) };
        var stack = new StackPanel { MaxWidth = 820 };
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

        // Connected computers list (clickable for Device Detail)
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

            var card = UiKit.Card(row, 18, 18);
            var btn = UiKit.BareButton(card, () =>
            {
                _selectedDeviceId = captured.Id;
                RenderAll(false);
            });
            btn.MouseEnter += (_, _) => card.Background = UiKit.GlassCardHoverBrush;
            btn.MouseLeave += (_, _) => card.Background = UiKit.GlassCardBrush;
            btn.Margin = new Thickness(0, 0, 0, 10);
            stack.Children.Add(btn);
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
            DockPanel.SetDock(rmBtn, Dock.Right);
            rmBtn.HorizontalAlignment = HorizontalAlignment.Right;
            topRow.Children.Add(rmBtn);
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

                var card = UiKit.Card(col, 16, 16);
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
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(28, 24, 28, 32) };
        var stack = new StackPanel { MaxWidth = 1080 };

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

        // Section 1: Official Quotas (Adaptive Side-by-Side Cards)
        if (officialTools.Count > 0)
        {
            var qSecTitle = UiKit.Heading("官方额度", 20);
            qSecTitle.Margin = new Thickness(0, 0, 0, 14);
            stack.Children.Add(qSecTitle);

            var qWrap = new WrapPanel { Margin = new Thickness(0, 0, -16, 18) };
            foreach (var tool in officialTools)
            {
                var p = providers.FirstOrDefault(x => x.Tool == tool);
                var card = BuildOfficialQuotaCard(tool, p, checks, now);
                card.Width = 342;
                card.VerticalAlignment = VerticalAlignment.Top;
                card.Margin = new Thickness(0, 0, 16, 16);
                qWrap.Children.Add(card);
            }
            stack.Children.Add(qWrap);
        }

        // Section 2: Local Tokens (Adaptive Side-by-Side Mon-Sun 90-Day Cards)
        var lSecTitle = UiKit.Heading("本地 Token", 20);
        lSecTitle.Margin = new Thickness(0, 4, 0, 14);
        stack.Children.Add(lSecTitle);

        var lWrap = new WrapPanel { Margin = new Thickness(0, 0, -16, 0) };
        foreach (var tool in displayedTools)
        {
            var p = providers.FirstOrDefault(x => x.Tool == tool);
            var card = BuildLocalTokenProviderCard(tool, p, _controller.Usage?.Day);
            card.Width = 342;
            card.VerticalAlignment = VerticalAlignment.Top;
            card.Margin = new Thickness(0, 0, 16, 16);
            lWrap.Children.Add(card);
        }
        stack.Children.Add(lWrap);

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
                    lines.Add($"{UsageFormatting.QuotaDisplayTitle(q)}\n已用 {UsageFormatting.Percentage(q.Used)}（剩余 {UsageFormatting.Percentage(q.Remaining)}）\n{UsageFormatting.ResetText(q, now)}");
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

                // Remaining bar matching UsageRemainingBar in UsageView.swift
                var track = new Grid { Height = 7, Margin = new Thickness(0, 6, 0, 6) };
                track.Children.Add(new Border { Background = UiKit.SoftBrush, CornerRadius = new CornerRadius(3.5) });
                var ratio = Math.Clamp((q.Remaining ?? 0.0) / 100.0, 0.0, 1.0);
                var barGrid = new Grid();
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
                qRow.Children.Add(track);

                qRow.Children.Add(UiKit.Body($"已用 {UsageFormatting.Percentage(q.Used)} · {UsageFormatting.ResetText(q, now)}", 11.5, UiKit.MutedBrush));
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

        var stack = new StackPanel { MaxWidth = 780 };
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
                ("bing", "Bing 每日壁纸"),
                ("custom", "自定义图片")
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
                    CornerRadius = new CornerRadius(10),
                    BorderBrush = UiKit.GlassRimBrush,
                    BorderThickness = new Thickness(1),
                    Margin = new Thickness(0, 0, 14, 0),
                    ClipToBounds = true,
                    VerticalAlignment = VerticalAlignment.Center,
                    Child = new Border
                    {
                        CornerRadius = new CornerRadius(9),
                        Clip = new RectangleGeometry(new Rect(0, 0, 94, 56), 9, 9),
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
                CornerRadius = new CornerRadius(12),
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
        appCard.Margin = new Thickness(0, 0, 0, 18);
        stack.Children.Add(appCard);

        // 2b. Compact Mini-Window Size & Content Customization Card
        var miniStack = new StackPanel();
        var miniHeadRow = new DockPanel { Margin = new Thickness(0, 0, 0, 10) };
        miniHeadRow.Children.Add(UiKit.Heading("精简小窗尺寸与显示内容", 17));
        var previewMiniBtn = UiKit.SecondaryButton("立即切换小窗预览", ToggleCompactMode, glyph: "mini");
        DockPanel.SetDock(previewMiniBtn, Dock.Right);
        previewMiniBtn.HorizontalAlignment = HorizontalAlignment.Right;
        miniHeadRow.Children.Add(previewMiniBtn);
        miniStack.Children.Add(miniHeadRow);

        miniStack.Children.Add(UiKit.Body("小窗尺寸预设", 12.5, UiKit.MutedBrush));
        var miniSizeSeg = UiKit.SegmentedControl(
            new[]
            {
                ("nano", "胶囊微窗 (308×196)"),
                ("small", "紧凑小窗 (336×356)"),
                ("medium", "标准小窗 (384×504)"),
                ("large", "宽展看板 (468×640)")
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
                ("overview", "智能总览（额度 + 任务）"),
                ("tasks", "仅显示活跃任务"),
                ("quotas", "仅显示官方额度")
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
        miniStack.Children.Add(UiKit.SettingToggleRow("任务行显示来源电脑、目录与会话 Token", "在标准与宽展小窗中显示完整上下文", uiSettings.CompactShowTaskMeta, v => SaveMiniToggle((s, val) => s.CompactShowTaskMeta = val, v)));

        var miniCard = UiKit.Card(miniStack, 22);
        miniCard.Margin = new Thickness(0, 0, 0, 18);
        stack.Children.Add(miniCard);

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
