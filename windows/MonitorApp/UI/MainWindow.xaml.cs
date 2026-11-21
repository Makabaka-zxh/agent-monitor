using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
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
    private double _savedWidth = 1160;
    private double _savedHeight = 760;

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

        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
        _timer.Tick += async (_, _) => await OnHeartbeatTickAsync();
        _timer.Start();

        _secondTicker = new DispatcherTimer { Interval = TimeSpan.FromSeconds(30) };
        _secondTicker.Tick += (_, _) =>
        {
            if (_controller.Connected && (_page == NavPage.Usage || _compactMode))
                RenderAll(forceShell: false);
        };
        _secondTicker.Start();

        SourceInitialized += (_, _) =>
        {
            ApplyMaximizedFramePadding();
            DwmInterop.ApplyWindowStyling(this, UiKit.IsDark);
        };
        StateChanged += (_, _) =>
        {
            ApplyMaximizedFramePadding();
            RebuildTitleBar();
        };
        PreviewKeyDown += OnWindowPreviewKeyDown;

        Loaded += async (_, _) =>
        {
            RenderAll(forceShell: true);
            if (_controller.Connected)
            {
                await _controller.Collector.RefreshAsync(_controller.Origin);
                await _controller.RefreshWorkbenchAsync();
                await _controller.RefreshAccountAsync();
                await _controller.RefreshUsageAsync();
            }
            else if (!string.IsNullOrEmpty(_controller.Origin) && !_controller.PendingBrowserLogin)
            {
                await _controller.StartQrLoginAsync();
            }
        };
    }

    private void SyncTheme(bool applyDwm = true)
    {
        var ui = _controller.ReadUiSettings();
        var dark = ui.ThemeMode switch
        {
            "dark" => true,
            "light" => false,
            _ => DwmInterop.IsSystemDarkTheme()
        };
        UiKit.ApplyTheme(dark);
        Background = UiKit.BgBrush;
        if (applyDwm) DwmInterop.ApplyWindowStyling(this, dark);
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

    // ==================== WINDOWS 11 FLUENT TITLEBAR ====================

    private void RebuildTitleBar()
    {
        TitleBarHost.Background = _compactMode ? UiKit.SurfaceBrush : UiKit.HeaderBrush;
        TitleBarHost.BorderBrush = UiKit.LineBrush;
        TitleBarHost.BorderThickness = new Thickness(0, 0, 0, 1);

        var dock = new DockPanel { LastChildFill = true };

        // Left: App Identity
        var left = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(14, 0, 0, 0)
        };
        left.Children.Add(UiKit.BrandImage("app", 20));
        var titleLabel = new TextBlock
        {
            Text = _compactMode ? "Monitor · 精简小窗" : "Monitor",
            FontFamily = UiKit.AppFont,
            FontSize = 13,
            FontWeight = FontWeights.SemiBold,
            Foreground = UiKit.TextBrush,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(8, 0, 0, 0)
        };
        left.Children.Add(titleLabel);
        DockPanel.SetDock(left, Dock.Left);
        dock.Children.Add(left);

        // Right: Quick Tools + Native Windows Caption Buttons
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
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 8, 0)
            };
            if (_compactMode)
            {
                quickRow.Children.Add(UiKit.IconButton("pin", Topmost ? "取消置顶" : "窗口置顶", () =>
                {
                    Topmost = !Topmost;
                    RebuildTitleBar();
                }, selected: Topmost));
            }
            quickRow.Children.Add(UiKit.IconButton("mini", _compactMode ? "展开完整工作台" : "切换置顶小窗", ToggleCompactMode, selected: _compactMode));
            quickRow.Children.Add(UiKit.IconButton("refresh", "立即同步刷新", async () =>
            {
                await _controller.RefreshWorkbenchAsync();
                if (_page == NavPage.Usage || _compactMode) await _controller.RefreshUsageAsync();
                if (_page == NavPage.Account) await _controller.RefreshAccountAsync();
            }));
            quickRow.Children.Add(UiKit.IconButton("help", "查看当前页面说明", ShowCurrentPageHelp));
            right.Children.Add(quickRow);
        }

        right.Children.Add(CaptionButton("minimize", "最小化", () => WindowState = WindowState.Minimized, isClose: false));
        if (!_compactMode)
        {
            right.Children.Add(CaptionButton("maximize", WindowState == WindowState.Maximized ? "向下还原" : "最大化", () =>
                WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized, isClose: false));
        }
        right.Children.Add(CaptionButton("close", "关闭", Close, isClose: true));

        DockPanel.SetDock(right, Dock.Right);
        dock.Children.Add(right);
        dock.Children.Add(new Border()); // Drag spacer

        TitleBarHost.Child = dock;
    }

    private static Button CaptionButton(string glyph, string tooltip, Action onClick, bool isClose)
    {
        var icon = new MonitorIcon(glyph, UiKit.MutedBrush, 14, 1.5);
        var border = new Border
        {
            Width = 46,
            Background = Brushes.Transparent,
            Child = icon
        };
        var btn = UiKit.BareButton(border, onClick, true);
        btn.ToolTip = tooltip;
        var closeBg = new SolidColorBrush(Color.FromRgb(0xC4, 0x2B, 0x1C));
        closeBg.Freeze();
        btn.MouseEnter += (_, _) =>
        {
            border.Background = isClose ? closeBg : UiKit.SoftBrush;
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
        if (_page == NavPage.Usage || _compactMode)
            await _controller.RefreshUsageAsync();
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

    private void ToggleCompactMode()
    {
        _compactMode = !_compactMode;
        if (_compactMode)
        {
            if (WindowState == WindowState.Maximized) WindowState = WindowState.Normal;
            _savedWidth = Width;
            _savedHeight = Height;
            Width = 392;
            Height = 520;
            Topmost = true;
        }
        else
        {
            Topmost = false;
            Width = Math.Max(1020, _savedWidth);
            Height = Math.Max(700, _savedHeight);
        }
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
        var outer = new Grid { Background = UiKit.BgBrush };
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

        cardStack.Children.Add(UiKit.Card(inner, 32, 24));
        outer.Children.Add(cardStack);
        return outer;
    }

    // ==================== COMPACT FLOATING MINI-WINDOW ====================

    private UIElement BuildCompactView()
    {
        var scroll = new ScrollViewer { Background = UiKit.BgBrush, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(14) };
        var stack = new StackPanel();

        var now = DateTimeOffset.UtcNow;
        var lowest = FindLowestQuota(now);
        var todayTotal = (_controller.Usage?.Providers ?? []).Sum(p => p.TodayTokens ?? 0);

        // Top Summary Card (Lowest Quota + Today's Tokens)
        var summaryCol = new StackPanel();
        if (lowest is { } lq)
        {
            summaryCol.Children.Add(UiKit.TaskIdentityBadge(lq.Tool, "最低剩余官方额度", 15));
            var pctRow = new DockPanel { Margin = new Thickness(0, 8, 0, 4) };
            pctRow.Children.Add(UiKit.Heading($"剩余 {UsageFormatting.Percentage(lq.Quota.Remaining)}", 22));
            var tokRight = UiKit.Body($"今日 {UsageFormatting.CompactTokens(todayTotal)} Token", 12, UiKit.MutedBrush);
            tokRight.VerticalAlignment = VerticalAlignment.Bottom;
            DockPanel.SetDock(tokRight, Dock.Right);
            tokRight.HorizontalAlignment = HorizontalAlignment.Right;
            pctRow.Children.Add(tokRight);
            summaryCol.Children.Add(pctRow);
            summaryCol.Children.Add(UiKit.Body($"{UsageFormatting.QuotaDisplayTitle(lq.Quota)} · {UsageFormatting.ResetText(lq.Quota, now)}", 12, UiKit.MutedBrush));
        }
        else
        {
            summaryCol.Children.Add(UiKit.Body("今日已记录本地用量", 12, UiKit.MutedBrush));
            var tokBig = UiKit.Heading($"{UsageFormatting.CompactTokens(todayTotal)} Token", 22);
            tokBig.Margin = new Thickness(0, 4, 0, 0);
            summaryCol.Children.Add(tokBig);
        }

        var summaryCard = UiKit.Card(summaryCol, 15, 18);
        summaryCard.Margin = new Thickness(0, 0, 0, 12);
        stack.Children.Add(summaryCard);

        // Active tasks section
        var active = _controller.Tasks.Where(t => !t.Archived && t.Status is "running" or "waiting" or "error").Take(10).ToList();
        var secHead = new DockPanel { Margin = new Thickness(2, 0, 2, 8) };
        secHead.Children.Add(UiKit.Body($"进行中任务 ({active.Count})", 12.5, UiKit.MutedBrush));
        var expandLink = UiKit.LinkButton("展开完整工作台", ToggleCompactMode);
        DockPanel.SetDock(expandLink, Dock.Right);
        expandLink.HorizontalAlignment = HorizontalAlignment.Right;
        secHead.Children.Add(expandLink);
        stack.Children.Add(secHead);

        if (active.Count == 0)
        {
            stack.Children.Add(UiKit.Card(UiKit.Body("这里很清爽，当前没有需要处理的进行中任务", 13, UiKit.MutedBrush), 16, 16));
        }
        else
        {
            foreach (var task in active)
            {
                var captured = task;
                var col = new StackPanel();
                col.Children.Add(UiKit.TaskIdentityBadge(task.Tool, task.DeviceName ?? "电脑", 14));
                var tTitle = UiKit.Heading(task.Title, 13.5);
                tTitle.Margin = new Thickness(0, 6, 0, 4);
                col.Children.Add(tTitle);
                col.Children.Add(BuildStatusDotRow(task));

                var btn = UiKit.BareButton(UiKit.Card(col, 13, 15), () =>
                {
                    _selectedTaskId = captured.Id;
                    _page = NavPage.Tasks;
                    ToggleCompactMode();
                });
                btn.Margin = new Thickness(0, 0, 0, 8);
                stack.Children.Add(btn);
            }
        }

        scroll.Content = stack;
        return scroll;
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

    // ==================== WORKSPACE SHELL & SIDEBAR ====================

    private UIElement BuildWorkspaceShell()
    {
        var grid = new Grid { Background = UiKit.BgBrush };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(202) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var sidebar = new DockPanel { Background = UiKit.SidebarBrush, LastChildFill = true };

        var bottomStatus = new StackPanel { Margin = new Thickness(16, 10, 16, 18) };
        var miniBtn = UiKit.SecondaryButton("切换置顶小窗", ToggleCompactMode, glyph: "mini");
        miniBtn.Margin = new Thickness(0, 0, 0, 14);
        bottomStatus.Children.Add(miniBtn);

        _sidebarOnlineText = UiKit.Body("", 12, UiKit.MutedBrush);
        bottomStatus.Children.Add(_sidebarOnlineText);
        DockPanel.SetDock(bottomStatus, Dock.Bottom);
        sidebar.Children.Add(bottomStatus);

        _sidebarNavHost = new StackPanel { Margin = new Thickness(12, 16, 12, 0) };
        sidebar.Children.Add(_sidebarNavHost);

        Grid.SetColumn(sidebar, 0);
        grid.Children.Add(sidebar);

        var sep = new Border { Background = UiKit.LineBrush };
        Grid.SetColumn(sep, 1);
        grid.Children.Add(sep);

        if (_page == NavPage.Tasks)
        {
            var tasksLayout = BuildTasksSplitShell();
            Grid.SetColumn(tasksLayout, 2);
            grid.Children.Add(tasksLayout);
        }
        else
        {
            _pageBodyHost = new ContentControl { HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch };
            Grid.SetColumn(_pageBodyHost, 2);
            grid.Children.Add(_pageBodyHost);
        }

        return grid;
    }

    private void UpdateSidebarState()
    {
        if (_sidebarNavHost is not null)
        {
            _sidebarNavHost.Children.Clear();
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
                var row = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
                row.Children.Add(new MonitorIcon(glyph, active ? UiKit.AccentBrush : UiKit.MutedBrush, 18) { Margin = new Thickness(0, 0, 11, 0) });
                row.Children.Add(new TextBlock
                {
                    Text = label,
                    FontFamily = UiKit.AppFont,
                    FontSize = 14.5,
                    FontWeight = active ? FontWeights.SemiBold : FontWeights.Normal,
                    Foreground = ink,
                    VerticalAlignment = VerticalAlignment.Center
                });

                var bg = active ? UiKit.SurfaceBrush : Brushes.Transparent;
                var box = new Border
                {
                    Background = bg,
                    BorderBrush = active ? UiKit.LineBrush : Brushes.Transparent,
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(13),
                    Padding = new Thickness(14, 10, 14, 10),
                    Margin = new Thickness(0, 0, 0, 5),
                    Child = row
                };
                var target = nav;
                var btn = UiKit.BareButton(box, () => SwitchPage(target));
                if (!active)
                {
                    btn.MouseEnter += (_, _) => box.Background = UiKit.SoftBrush;
                    btn.MouseLeave += (_, _) => box.Background = Brushes.Transparent;
                }
                _sidebarNavHost.Children.Add(btn);
            }
        }
        if (_sidebarOnlineText is not null)
        {
            _sidebarOnlineText.Text = _controller.IsFresh
                ? $"● {_controller.OnlineDeviceCount} 台在线"
                : "○ 连接待更新";
            _sidebarOnlineText.Foreground = _controller.IsFresh ? UiKit.GreenBrush : UiKit.MutedBrush;
        }
    }

    // ==================== TASKS MASTER-DETAIL ====================

    private UIElement BuildTasksSplitShell()
    {
        var split = new Grid();
        split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(376) });
        split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1) });
        split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var leftPane = new DockPanel { Background = UiKit.BgBrush };
        var topControls = new StackPanel { Margin = new Thickness(18, 18, 18, 8) };

        var headerRow = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
        headerRow.Children.Add(UiKit.Heading("工作台", 23));
        _searchMatchSummary = UiKit.Body("", 12, UiKit.MutedBrush);
        _searchMatchSummary.VerticalAlignment = VerticalAlignment.Bottom;
        DockPanel.SetDock(_searchMatchSummary, Dock.Right);
        _searchMatchSummary.HorizontalAlignment = HorizontalAlignment.Right;
        headerRow.Children.Add(_searchMatchSummary);
        topControls.Children.Add(headerRow);

        // Family Search Bar with icon + placeholder + clear button
        var searchDock = new DockPanel();
        var searchIcon = new MonitorIcon("search", UiKit.MutedBrush, 16) { Margin = new Thickness(10, 0, 6, 0), VerticalAlignment = VerticalAlignment.Center };
        DockPanel.SetDock(searchIcon, Dock.Left);
        searchDock.Children.Add(searchIcon);

        var searchInputLayer = new Grid();
        var searchPlaceholder = new TextBlock
        {
            Text = "搜索任务、项目或电脑（空格分隔多词）",
            FontFamily = UiKit.AppFont,
            FontSize = 13,
            Foreground = UiKit.MutedBrush,
            Padding = new Thickness(4, 8, 6, 8),
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
            Padding = new Thickness(2, 8, 6, 8),
            VerticalContentAlignment = VerticalAlignment.Center
        };
        _taskSearchBox = searchBox;
        searchInputLayer.Children.Add(searchPlaceholder);
        searchInputLayer.Children.Add(searchBox);

        var clearBtn = UiKit.IconButton("close", "清除搜索", () => searchBox.Text = "");
        clearBtn.Width = 28;
        clearBtn.Height = 28;
        clearBtn.Visibility = string.IsNullOrEmpty(searchBox.Text) ? Visibility.Collapsed : Visibility.Visible;
        DockPanel.SetDock(clearBtn, Dock.Right);
        searchDock.Children.Add(clearBtn);
        searchDock.Children.Add(searchInputLayer);

        var searchContainer = new Border
        {
            Background = UiKit.SurfaceBrush,
            BorderBrush = UiKit.LineBrush,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(14),
            Padding = new Thickness(2, 1, 6, 1),
            Margin = new Thickness(0, 0, 0, 12),
            Child = searchDock
        };

        searchBox.GotKeyboardFocus += (_, _) => searchContainer.BorderBrush = UiKit.AccentBrush;
        searchBox.LostKeyboardFocus += (_, _) => searchContainer.BorderBrush = UiKit.LineBrush;
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

        var listScroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(18, 2, 18, 20) };
        _taskListHost = new StackPanel();
        listScroll.Content = _taskListHost;
        leftPane.Children.Add(listScroll);

        Grid.SetColumn(leftPane, 0);
        split.Children.Add(leftPane);

        var divider = new Border { Background = UiKit.LineBrush };
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
        return task.Status switch
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
            var toolWrap = new WrapPanel();
            foreach (var tf in Enum.GetValues<ToolFilter>())
            {
                var target = tf;
                var countInScope = _controller.Tasks.Count(t => t.Matches(tf, _controller.Scope, _controller.IsFresh, _controller.SearchQuery));
                var label = countInScope > 0 ? $"{tf.Title()} {countInScope}" : tf.Title();
                toolWrap.Children.Add(UiKit.PillButton(label, _controller.Tool == tf, () =>
                {
                    _ = _controller.SaveToolFilterAsync(target);
                }));
            }
            _taskFilterHost.Children.Add(toolWrap);

            var scopeWrap = new WrapPanel { Margin = new Thickness(0, 2, 0, 0) };
            foreach (var sc in Enum.GetValues<TaskScope>())
            {
                var target = sc;
                scopeWrap.Children.Add(UiKit.PillButton(sc.Title(), _controller.Scope == sc, () =>
                {
                    _controller.Scope = target;
                    UpdateTasksPageInPlace();
                }));
            }
            _taskFilterHost.Children.Add(scopeWrap);
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
                    var col = new StackPanel();
                    col.Children.Add(UiKit.TaskIdentityBadge(task.Tool, task.DeviceName ?? "电脑", 15));

                    var titleBlock = UiKit.Heading(task.Title, 14.5);
                    titleBlock.Margin = new Thickness(0, 6, 0, 6);
                    col.Children.Add(titleBlock);
                    col.Children.Add(BuildStatusDotRow(task));

                    var box = new Border
                    {
                        Background = isSelected ? UiKit.SoftBrush : UiKit.SurfaceBrush,
                        BorderBrush = isSelected ? UiKit.AccentBrush : UiKit.LineBrush,
                        BorderThickness = new Thickness(1),
                        CornerRadius = new CornerRadius(16),
                        Padding = new Thickness(15, 13, 15, 13),
                        Margin = new Thickness(0, 0, 0, 9),
                        Child = col
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
                        btn.MouseEnter += (_, _) => box.Background = UiKit.HoverBrush;
                        btn.MouseLeave += (_, _) => box.Background = UiKit.SurfaceBrush;
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
                var emptyRight = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
                emptyRight.Children.Add(new MonitorIcon("tasks", UiKit.MutedBrush, 36) { Margin = new Thickness(0, 0, 0, 12) });
                var t1 = UiKit.Heading("选择一个任务", 18);
                t1.HorizontalAlignment = HorizontalAlignment.Center;
                emptyRight.Children.Add(t1);
                var t2 = UiKit.Body("查看最终结果、下载交付文件，或继续发送回复", 13.5, UiKit.MutedBrush);
                t2.Margin = new Thickness(0, 6, 0, 0);
                emptyRight.Children.Add(t2);
                _taskDetailRightHost.Content = emptyRight;
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
          .Append(detail.DecisionSending ? '1' : '0').Append('|')
          .Append(detail.ControlsFeedback).Append('|');

        if (detail.Result?.Files is { Count: > 0 } files)
        {
            foreach (var f in files)
                sb.Append(f.Id).Append(':').Append(f.Ready == false ? '0' : '1').Append(';');
        }
        if (detail.Controls?.Pending is { Count: > 0 } pendings)
        {
            foreach (var p in pendings.Where(x => x.State is "pending" or "submitted"))
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

        // Pending Attention Requests (Single-Turn Approval / Structured Questions)
        if (_taskDetail.Controls?.Pending is { Count: > 0 } pendings)
        {
            foreach (var req in pendings.Where(p => p.State is "pending" or "submitted"))
            {
                var attCard = BuildAttentionCard(req, _taskDetail);
                attCard.Margin = new Thickness(0, 0, 0, 16);
                _taskDetailCardsHost.Children.Add(attCard);
            }
        }

        // Final Result Card
        var resStack = new StackPanel();
        var resHeader = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
        resHeader.Children.Add(UiKit.Heading("最终结果", 18));
        var reloadResBtn = UiKit.IconButton("refresh", "刷新最终结果", async () => await _taskDetail.LoadResultAsync(true), enabled: !_taskDetail.ResultLoading);
        DockPanel.SetDock(reloadResBtn, Dock.Right);
        reloadResBtn.HorizontalAlignment = HorizontalAlignment.Right;
        resHeader.Children.Add(reloadResBtn);
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
                Background = UiKit.BgBrush,
                BorderBrush = UiKit.LineBrush,
                BorderThickness = new Thickness(1),
                Padding = new Thickness(14),
                TextWrapping = TextWrapping.Wrap,
                MaxHeight = 360,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto
            };
            resStack.Children.Add(textBox);

            if (resDto.Truncated == true)
            {
                var trunc = UiKit.Body("内容较长，完整输出可保存 TXT 或在原电脑查看", 12.5, UiKit.MutedBrush);
                trunc.Margin = new Thickness(0, 8, 0, 0);
                resStack.Children.Add(trunc);
            }

            var actionRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 14, 0, 0) };
            actionRow.Children.Add(UiKit.SecondaryButton("复制", () => Clipboard.SetText(resultText), glyph: "copy"));
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
        stack.Children.Add(UiKit.Heading(string.IsNullOrWhiteSpace(req.Title) ? "等待确认操作" : req.Title, 16.5));
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

        if (req.Kind == "question" && req.Questions is { Count: > 0 } questions)
        {
            var fingerprint = SessionControlPolicy.AttentionFingerprint(req);
            if (!_attentionDrafts.TryGetValue(fingerprint, out var draftMap))
            {
                draftMap = new Dictionary<string, (HashSet<string> Choices, string FreeText)>(StringComparer.Ordinal);
                _attentionDrafts[fingerprint] = draftMap;
            }

            var answerEditors = new List<(string QId, List<ToggleButton> Toggles, TextBox? FreeText)>();
            foreach (var q in questions)
            {
                if (!draftMap.TryGetValue(q.Id, out var qDraft))
                {
                    qDraft = (new HashSet<string>(StringComparer.Ordinal), "");
                    draftMap[q.Id] = qDraft;
                }
                var capturedQId = q.Id;

                var qTitle = UiKit.Body(q.Text, 13.5);
                qTitle.FontWeight = FontWeights.Medium;
                qTitle.Margin = new Thickness(0, 12, 0, 6);
                stack.Children.Add(qTitle);

                var toggles = new List<ToggleButton>();
                var groupName = $"q_{req.Id}_{q.Id}";
                if (q.Options is { Count: > 0 })
                {
                    foreach (var opt in q.Options)
                    {
                        var labelText = string.IsNullOrWhiteSpace(opt.Description) ? opt.Label : $"{opt.Label} — {opt.Description}";
                        var isSavedChecked = qDraft.Choices.Contains(opt.Id);
                        ToggleButton tb = q.Multiple == true
                            ? new CheckBox { Content = labelText, Tag = opt.Id, IsChecked = isSavedChecked, Foreground = UiKit.TextBrush, Margin = new Thickness(0, 3, 0, 4), FontSize = 13 }
                            : new RadioButton { Content = labelText, GroupName = groupName, Tag = opt.Id, IsChecked = isSavedChecked, Foreground = UiKit.TextBrush, Margin = new Thickness(0, 3, 0, 4), FontSize = 13 };
                        tb.Checked += (_, _) =>
                        {
                            var entry = draftMap[capturedQId];
                            if (q.Multiple != true) entry.Choices.Clear();
                            entry.Choices.Add((string)tb.Tag);
                        };
                        tb.Unchecked += (_, _) => draftMap[capturedQId].Choices.Remove((string)tb.Tag);
                        toggles.Add(tb);
                        stack.Children.Add(tb);
                    }
                }
                TextBox? freeBox = null;
                if (q.AllowsText == true || toggles.Count == 0)
                {
                    freeBox = UiKit.StyledTextBox(qDraft.FreeText, "补充说明…");
                    freeBox.Margin = new Thickness(0, 4, 0, 4);
                    var capturedBox = freeBox;
                    freeBox.TextChanged += (_, _) =>
                    {
                        var cur = draftMap[capturedQId];
                        draftMap[capturedQId] = (cur.Choices, capturedBox.Text);
                    };
                    stack.Children.Add(freeBox);
                }
                answerEditors.Add((q.Id, toggles, freeBox));
            }

            var submitBtn = UiKit.PrimaryButton("提交回答", async () =>
            {
                var payload = answerEditors.Select(ed => new SessionAnswerPayloadDto
                {
                    QuestionId = ed.QId,
                    Choices = ed.Toggles.Where(c => c.IsChecked == true).Select(c => (string)c.Tag).ToList(),
                    Text = string.IsNullOrWhiteSpace(ed.FreeText?.Text) ? null : ed.FreeText!.Text.Trim()
                }).ToList();
                await detail.SubmitDecisionAsync(req, "answer", payload);
            }, !detail.DecisionSending && req.State == "pending");
            submitBtn.HorizontalAlignment = HorizontalAlignment.Left;
            submitBtn.Margin = new Thickness(0, 12, 0, 0);
            stack.Children.Add(submitBtn);
        }
        else
        {
            var btnRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 14, 0, 0) };
            btnRow.Children.Add(UiKit.PrimaryButton("仅允许这一次", async () => await detail.SubmitDecisionAsync(req, "approve_once"), !detail.DecisionSending && req.State == "pending", glyph: "check"));
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
        border.BorderBrush = UiKit.WarningBrush;
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
            btn.MouseEnter += (_, _) => card.Background = UiKit.HoverBrush;
            btn.MouseLeave += (_, _) => card.Background = UiKit.SurfaceBrush;
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
                card.Width = 340;
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
            card.Width = 340;
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

        // Mon-Sun 7-day interactive bar chart
        var weekDates = Enumerable.Range(0, 7).Select(i => weekMonday.AddDays(i)).ToList();
        var weekMax = Math.Max(1L, weekDates.Select(TokensOn).Where(v => v.HasValue).Select(v => v!.Value).DefaultIfEmpty(0L).Max());
        var weekdays = new[] { "一", "二", "三", "四", "五", "六", "日" };

        var chartGrid = new UniformGrid { Columns = 7, Height = 108, Margin = new Thickness(0, 2, 0, 10) };
        for (var i = 0; i < 7; i++)
        {
            var d = weekDates[i];
            var avail = d >= firstDay && d <= today;
            var isSel = d == activeDate;
            var tok = TokensOn(d);
            var barH = tok is > 0 ? Math.Max(6.0, (tok.Value / (double)weekMax) * 68.0) : 4.0;

            var barCol = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(2.5, 0, 2.5, 0) };
            barCol.Children.Add(new Border
            {
                Height = barH,
                Background = !avail ? Brushes.Transparent : isSel ? UiKit.AccentBrush : (tok is > 0 ? UiKit.SoftBrush : UiKit.LineBrush),
                CornerRadius = new CornerRadius(4),
                Opacity = avail ? 1.0 : 0.25
            });
            var wLbl = new TextBlock
            {
                Text = weekdays[i],
                FontFamily = UiKit.AppFont,
                FontSize = 11,
                FontWeight = isSel ? FontWeights.SemiBold : FontWeights.Normal,
                Foreground = isSel ? UiKit.AccentBrush : UiKit.MutedBrush,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 5, 0, 0)
            };
            barCol.Children.Add(wLbl);

            var capturedDate = d;
            var cellBtn = UiKit.BareButton(barCol, () =>
            {
                _selectedUsageDay[capturedTool] = capturedDate;
                RenderAll(false);
            }, enabled: avail);
            cellBtn.ToolTip = avail ? $"{UsageCalendar.FormatDay(d)}：{UsageFormatting.ExactTokens(tok)} Token" : "";
            chartGrid.Children.Add(cellBtn);
        }
        col.Children.Add(chartGrid);

        // Per-Model Token Breakdown
        if (dayMap.TryGetValue(activeDate, out var selectedDayDto))
        {
            var shares = UsageFormatting.ComputeModelShares(selectedDayDto);
            if (shares.Count > 0)
            {
                col.Children.Add(new Border { Height = 1, Background = UiKit.LineBrush, Margin = new Thickness(0, 6, 0, 12) });
                var mHead = UiKit.Heading("模型 Token 占比", 14);
                mHead.Margin = new Thickness(0, 0, 0, 10);
                col.Children.Add(mHead);

                foreach (var s in shares)
                {
                    var itemCol = new StackPanel { Margin = new Thickness(0, 0, 0, 9) };
                    var top = new DockPanel();
                    top.Children.Add(UiKit.Body(s.DisplayName, 13));
                    var pct = new TextBlock
                    {
                        Text = UsageFormatting.Percentage(s.Percent),
                        FontFamily = UiKit.AppFont,
                        FontSize = 13,
                        FontWeight = FontWeights.Medium,
                        Foreground = UiKit.TextBrush
                    };
                    DockPanel.SetDock(pct, Dock.Right);
                    pct.HorizontalAlignment = HorizontalAlignment.Right;
                    top.Children.Add(pct);
                    itemCol.Children.Add(top);
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

        // 2. Windows Appearance & Behavior Card
        var uiSettings = _controller.ReadUiSettings();
        var appStack = new StackPanel();
        appStack.Children.Add(UiKit.Heading("显示与窗口行为", 17));
        var themeRow = new WrapPanel { Margin = new Thickness(0, 12, 0, 10) };
        foreach (var (modeKey, label) in new[] { ("system", "跟随 Windows 系统"), ("light", "浅色家族主题"), ("dark", "深色家族主题") })
        {
            var captured = modeKey;
            themeRow.Children.Add(UiKit.PillButton(label, uiSettings.ThemeMode == modeKey, () =>
            {
                var next = _controller.ReadUiSettings();
                next.ThemeMode = captured;
                _controller.SaveUiSettings(next);
                SyncTheme(applyDwm: true);
                RenderAll(forceShell: true);
            }));
        }
        appStack.Children.Add(themeRow);

        var trayCheck = new CheckBox
        {
            Content = "点击右上角关闭按钮时最小化到系统托盘（保持后台额度提醒在线）",
            Foreground = UiKit.TextBrush,
            IsChecked = uiSettings.CloseToTray,
            FontSize = 13.5,
            Margin = new Thickness(0, 4, 0, 12)
        };
        trayCheck.Click += (_, _) =>
        {
            var next = _controller.ReadUiSettings();
            next.CloseToTray = trayCheck.IsChecked == true;
            _controller.SaveUiSettings(next);
        };
        appStack.Children.Add(trayCheck);

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
