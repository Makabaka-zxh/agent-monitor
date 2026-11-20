using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;
using MonitorApp.Core;

namespace MonitorApp.UI;

public partial class MainWindow : Window
{
    private enum NavPage { Tasks, Devices, Usage, Account }

    private readonly MonitorController _controller = new();
    private readonly DispatcherTimer _timer;
    private System.Windows.Forms.NotifyIcon? _trayIcon;
    private bool _allowExit;

    private NavPage _page = NavPage.Tasks;
    private bool _compactMode;
    private double _savedWidth = 1120;
    private double _savedHeight = 740;

    private bool _editingServer;
    private string _serverDraft = "";

    private string? _selectedTaskId;
    private TaskDetailController? _taskDetail;

    // Usage page state
    private string _usageQuotaTool = "antigravity";
    private string _usageLocalTool = "codex";
    private bool _usageShowUsed;
    private string? _selectedHistoryDay;

    // Persistent controls so typing is never interrupted by 5s background polls
    private bool? _mountedConnectedState;
    private bool? _mountedCompactState;
    private NavPage? _mountedPage;
    private StackPanel? _sidebarNavHost;
    private TextBlock? _sidebarOnlineText;
    private StackPanel? _taskFilterHost;
    private StackPanel? _taskListHost;
    private ContentControl? _taskDetailRightHost;
    private string? _mountedDetailTaskId;
    private StackPanel? _taskDetailCardsHost;
    private TextBlock? _replyModelSummaryText;
    private TextBlock? _replyStatusText;
    private TextBox? _replyInputBox;
    private ContentControl? _pageBodyHost;

    public MainWindow()
    {
        InitializeComponent();

        _serverDraft = !string.IsNullOrEmpty(_controller.Origin)
            ? _controller.Origin
            : (ServerOrigin.SuggestedOrigin() ?? "");

        SetupSystemTray();

        _controller.StateChanged += OnControllerStateChanged;
        _controller.QuotaAlertTriggered += OnQuotaAlertTriggered;

        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
        _timer.Tick += async (_, _) => await OnHeartbeatTickAsync();
        _timer.Start();

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

            System.Drawing.Icon? trayIco = null;
            var streamInfo = Application.GetResourceStream(new Uri("pack://application:,,,/Resources/AppIcon.ico"));
            if (streamInfo?.Stream is { } s)
            {
                trayIco = new System.Drawing.Icon(s);
            }

            _trayIcon = new System.Windows.Forms.NotifyIcon
            {
                Text = "Monitor · AI 任务与额度工作台",
                Icon = trayIco ?? System.Drawing.SystemIcons.Application,
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
        catch
        {
            // Tray optional in headless test sessions.
        }
    }

    private void OnQuotaAlertTriggered(string title, string body)
    {
        try
        {
            _trayIcon?.ShowBalloonTip(6000, title, body, System.Windows.Forms.ToolTipIcon.Info);
        }
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
        if (!_allowExit && _trayIcon is not null)
        {
            e.Cancel = true;
            Hide();
            return;
        }
        _timer.Stop();
        _taskDetail?.Dispose();
        if (_trayIcon is not null)
        {
            _trayIcon.Visible = false;
            _trayIcon.Dispose();
        }
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
            Dispatcher.Invoke(() => OnControllerStateChanged());
            return;
        }
        if (!string.IsNullOrEmpty(_controller.Notice))
        {
            var msg = _controller.Notice!;
            _controller.Notice = null;
            Dialogs.ShowMessage(this, "提示", msg);
        }
        RenderAll(forceShell: false);
    }

    private void SwitchPage(NavPage next)
    {
        if (_page == next) return;
        _page = next;
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
            _savedWidth = Width;
            _savedHeight = Height;
            Width = 390;
            Height = 500;
            Topmost = true;
        }
        else
        {
            Topmost = false;
            Width = Math.Max(960, _savedWidth);
            Height = Math.Max(680, _savedHeight);
        }
        RenderAll(forceShell: true);
    }

    private void RenderAll(bool forceShell)
    {
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
                if (_pageBodyHost is not null) _pageBodyHost.Content = BuildDevicesContent();
                break;
            case NavPage.Usage:
                if (_pageBodyHost is not null) _pageBodyHost.Content = BuildUsageContent();
                break;
            case NavPage.Account:
                if (_pageBodyHost is not null) _pageBodyHost.Content = BuildAccountContent();
                break;
        }
    }

    // ==================== LOGIN VIEW ====================

    private UIElement BuildLoginScreen()
    {
        var outer = new Grid { Background = UiKit.BgBrush };
        var cardStack = new StackPanel
        {
            Width = 440,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };

        var inner = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center };
        var icon = UiKit.BrandImage("app", 68);
        icon.Margin = new Thickness(0, 0, 0, 14);
        inner.Children.Add(icon);

        var heading = UiKit.Heading("工作，尽在眼前", 26);
        heading.HorizontalAlignment = HorizontalAlignment.Center;
        inner.Children.Add(heading);

        var envLocked = ServerOrigin.IsEnvironmentControlled;
        var showEditor = !envLocked && !_controller.PendingBrowserLogin && (string.IsNullOrEmpty(_controller.Origin) || _editingServer);

        var subtitleText = envLocked && string.IsNullOrEmpty(_controller.Origin)
            ? "环境变量 MONITOR_SERVER_URL 无效，请改为 HTTPS 根地址后重启。"
            : _controller.PendingBrowserLogin
            ? "请在打开的浏览器页面确认本次连接，确认后自动进入工作台"
            : !string.IsNullOrEmpty(_controller.Origin) && !showEditor
            ? (_controller.QrLogin?.IncludeCollector == true
                ? "打开手机 Monitor 点右上角「扫一扫」，一键登录并绑定这台 Windows 电脑"
                : "打开手机 Monitor 点右上角「扫一扫」，一键确认登录桌面端")
            : "查看并操控每台电脑上的 Claude Code、Codex 与 Antigravity";

        var sub = UiKit.Body(subtitleText, 13.5, UiKit.MutedBrush);
        sub.TextAlignment = TextAlignment.Center;
        sub.Margin = new Thickness(0, 8, 0, 18);
        inner.Children.Add(sub);

        if (showEditor)
        {
            var label = UiKit.Body("自建同步服务 HTTPS 地址", 12.5, UiKit.MutedBrush);
            label.Margin = new Thickness(0, 0, 0, 6);
            inner.Children.Add(label);

            var serverBox = UiKit.StyledTextBox(_serverDraft, "https://monitor.example.com");
            serverBox.Width = 360;
            serverBox.TextChanged += (_, _) => _serverDraft = serverBox.Text;
            inner.Children.Add(serverBox);

            var btnRow = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 16, 0, 0) };
            btnRow.Children.Add(UiKit.PrimaryButton("生成登录二维码", async () =>
            {
                await _controller.StartQrLoginAsync(_serverDraft);
                if (!string.IsNullOrEmpty(_controller.Origin)) _editingServer = false;
                RenderAll(forceShell: true);
            }, !_controller.QrBusy));

            var browserBtn = UiKit.SecondaryButton("改用浏览器登录", async () =>
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
                    RenderAll(forceShell: true);
                }, color: UiKit.MutedBrush);
                cancelLink.HorizontalAlignment = HorizontalAlignment.Center;
                cancelLink.Margin = new Thickness(0, 12, 0, 0);
                inner.Children.Add(cancelLink);
            }
        }
        else if (!string.IsNullOrEmpty(_controller.Origin))
        {
            var originRow = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 0, 0, 14) };
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
                    var qrImg = new Image { Source = qrSource, Width = 176, Height = 176, Stretch = Stretch.Uniform };
                    RenderOptions.SetBitmapScalingMode(qrImg, BitmapScalingMode.NearestNeighbor);
                    var qrPad = new Border
                    {
                        Background = Brushes.White,
                        BorderBrush = UiKit.LineBrush,
                        BorderThickness = new Thickness(1),
                        CornerRadius = new CornerRadius(16),
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
                        Width = 204,
                        Height = 204,
                        Background = UiKit.MutedBgBrush,
                        CornerRadius = new CornerRadius(16),
                        HorizontalAlignment = HorizontalAlignment.Center,
                        Child = new TextBlock
                        {
                            Text = "正在生成二维码…",
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
                    var err = UiKit.Body(_controller.QrError, 13, UiKit.OrangeBrush);
                    err.TextAlignment = TextAlignment.Center;
                    err.Margin = new Thickness(0, 12, 0, 12);
                    inner.Children.Add(err);
                }

                var links = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 16, 0, 0) };
                links.Children.Add(UiKit.LinkButton(_controller.QrLogin is null ? "生成二维码" : "刷新二维码", async () => await _controller.StartQrLoginAsync(), !_controller.QrBusy));
                var sep = UiKit.Body(" · ", 13, UiKit.MutedBrush);
                sep.Margin = new Thickness(8, 0, 8, 0);
                links.Children.Add(sep);
                links.Children.Add(UiKit.LinkButton("改用浏览器确认登录", async () => await _controller.BeginBrowserLoginAsync(), !_controller.LoginBusy, UiKit.MutedBrush));
                inner.Children.Add(links);
            }
        }

        cardStack.Children.Add(UiKit.Card(inner, 30, 20));
        outer.Children.Add(cardStack);
        return outer;
    }

    // ==================== COMPACT MINI WINDOW ====================

    private UIElement BuildCompactView()
    {
        var root = new DockPanel { Background = UiKit.BgBrush, Margin = new Thickness(14) };

        var header = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
        var left = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        left.Children.Add(UiKit.BrandImage("app", 24));
        var title = UiKit.Heading("Monitor 小窗", 15.5);
        title.Margin = new Thickness(8, 0, 0, 0);
        left.Children.Add(title);
        header.Children.Add(left);

        var right = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        var pinBtn = UiKit.PillButton(Topmost ? "已置顶" : "置顶", Topmost, () =>
        {
            Topmost = !Topmost;
            RenderAll(forceShell: true);
        });
        pinBtn.Margin = new Thickness(0, 0, 6, 0);
        right.Children.Add(pinBtn);
        right.Children.Add(UiKit.SecondaryButton("展开工作台", ToggleCompactMode));
        DockPanel.SetDock(right, Dock.Right);
        header.Children.Add(right);

        DockPanel.SetDock(header, Dock.Top);
        root.Children.Add(header);

        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        var stack = new StackPanel();

        // Lowest quota card
        var now = DateTimeOffset.UtcNow;
        var lowest = FindLowestQuota(now);
        if (lowest is { } lq)
        {
            var qStack = new StackPanel();
            qStack.Children.Add(UiKit.Body($"{lq.Brand} · 最低剩余官方额度", 12, UiKit.MutedBrush));
            var big = UiKit.Heading($"{UsageFormatting.Percentage(lq.Quota.Remaining)} 剩余", 22);
            big.Margin = new Thickness(0, 4, 0, 4);
            qStack.Children.Add(big);
            qStack.Children.Add(UiKit.Body($"{UsageFormatting.QuotaDisplayTitle(lq.Quota)} · {UsageFormatting.ResetText(lq.Quota, now)}", 12.5, UiKit.MutedBrush));
            var card = UiKit.Card(qStack, 14, 14);
            card.Margin = new Thickness(0, 0, 0, 10);
            stack.Children.Add(card);
        }

        // Active tasks
        var active = _controller.Tasks.Where(t => !t.Archived && t.Status is "running" or "waiting" or "error").Take(8).ToList();
        var taskHeader = UiKit.Body($"进行中任务 ({active.Count})", 12.5, UiKit.MutedBrush);
        taskHeader.Margin = new Thickness(2, 2, 0, 8);
        stack.Children.Add(taskHeader);

        if (active.Count == 0)
        {
            stack.Children.Add(UiKit.Card(UiKit.Body("当前没有正在运行或等待确认的任务", 13, UiKit.MutedBrush), 14, 14));
        }
        else
        {
            foreach (var task in active)
            {
                var row = new DockPanel();
                var icon = UiKit.BrandImage(task.Tool, 24);
                icon.Margin = new Thickness(0, 0, 10, 0);
                DockPanel.SetDock(icon, Dock.Left);
                row.Children.Add(icon);

                var info = new StackPanel();
                info.Children.Add(UiKit.Heading(task.Title, 13.5));
                info.Children.Add(UiKit.Body($"{task.DeviceName ?? "电脑"} · {task.StatusTitle}", 12, TaskStatusColor(task)));
                row.Children.Add(info);

                var cardBtn = UiKit.BareButton(UiKit.Card(row, 12, 12), () =>
                {
                    _selectedTaskId = task.Id;
                    _page = NavPage.Tasks;
                    ToggleCompactMode();
                });
                cardBtn.Margin = new Thickness(0, 0, 0, 8);
                stack.Children.Add(cardBtn);
            }
        }

        scroll.Content = stack;
        root.Children.Add(scroll);
        return root;
    }

    private (string Brand, UsageQuotaDto Quota)? FindLowestQuota(DateTimeOffset now)
    {
        if (_controller.Usage?.Providers is null) return null;
        (string Brand, UsageQuotaDto Quota)? best = null;
        foreach (var p in _controller.Usage.Providers)
        {
            if (p.Quotas is null) continue;
            foreach (var q in p.Quotas)
            {
                if (q.Stale || q.IsExpired(now) || q.Remaining is not { } rem) continue;
                if (best is null || rem < best.Value.Quota.Remaining)
                    best = (p.Brand, q);
            }
        }
        return best;
    }

    // ==================== FULL WORKSPACE SHELL ====================

    private UIElement BuildWorkspaceShell()
    {
        var grid = new Grid { Background = UiKit.BgBrush };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(196) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        // Sidebar
        var sidebar = new DockPanel { Background = UiKit.SidebarBrush, LastChildFill = true };
        var brandBar = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(18, 20, 18, 16) };
        brandBar.Children.Add(UiKit.BrandImage("app", 32));
        var appTitle = UiKit.Heading("Monitor", 17);
        appTitle.VerticalAlignment = VerticalAlignment.Center;
        appTitle.Margin = new Thickness(10, 0, 0, 0);
        brandBar.Children.Add(appTitle);
        DockPanel.SetDock(brandBar, Dock.Top);
        sidebar.Children.Add(brandBar);

        var bottomStatus = new StackPanel { Margin = new Thickness(16, 10, 16, 18) };
        var miniBtn = UiKit.SecondaryButton("切换精简小窗", ToggleCompactMode);
        miniBtn.Margin = new Thickness(0, 0, 0, 12);
        bottomStatus.Children.Add(miniBtn);
        _sidebarOnlineText = UiKit.Body("", 12, UiKit.MutedBrush);
        bottomStatus.Children.Add(_sidebarOnlineText);
        DockPanel.SetDock(bottomStatus, Dock.Bottom);
        sidebar.Children.Add(bottomStatus);

        _sidebarNavHost = new StackPanel { Margin = new Thickness(12, 4, 12, 0) };
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
            foreach (var (nav, label) in new[]
                     {
                         (NavPage.Tasks, "任务工作台"),
                         (NavPage.Devices, "我的电脑"),
                         (NavPage.Usage, "用量与额度"),
                         (NavPage.Account, "我的账号")
                     })
            {
                var active = _page == nav;
                var txt = new TextBlock
                {
                    Text = label,
                    FontFamily = UiKit.AppFont,
                    FontSize = 14,
                    FontWeight = active ? FontWeights.SemiBold : FontWeights.Normal,
                    Foreground = active ? UiKit.AccentBrush : UiKit.TextBrush
                };
                var box = new Border
                {
                    Background = active ? UiKit.SurfaceBrush : Brushes.Transparent,
                    BorderBrush = active ? UiKit.LineBrush : Brushes.Transparent,
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(10),
                    Padding = new Thickness(14, 10, 14, 10),
                    Margin = new Thickness(0, 0, 0, 4),
                    Child = txt
                };
                var target = nav;
                _sidebarNavHost.Children.Add(UiKit.BareButton(box, () => SwitchPage(target)));
            }
        }
        if (_sidebarOnlineText is not null)
        {
            _sidebarOnlineText.Text = _controller.IsFresh
                ? $"● {_controller.OnlineDeviceCount} 台电脑在线"
                : "○ 正在同步连接状态…";
            _sidebarOnlineText.Foreground = _controller.IsFresh ? UiKit.GreenBrush : UiKit.MutedBrush;
        }
    }

    // ==================== TASKS PAGE ====================

    private UIElement BuildTasksSplitShell()
    {
        var split = new Grid();
        split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(365) });
        split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1) });
        split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var leftPane = new DockPanel { Background = UiKit.BgBrush };
        var topControls = new StackPanel { Margin = new Thickness(16, 18, 16, 8) };

        var headerRow = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
        headerRow.Children.Add(UiKit.Heading("工作台", 21));
        var refreshBtn = UiKit.SecondaryButton("刷新", async () => await _controller.RefreshWorkbenchAsync());
        DockPanel.SetDock(refreshBtn, Dock.Right);
        refreshBtn.HorizontalAlignment = HorizontalAlignment.Right;
        headerRow.Children.Add(refreshBtn);
        topControls.Children.Add(headerRow);

        var searchBox = UiKit.StyledTextBox(_controller.SearchQuery, "搜索任务标题、项目或电脑…");
        searchBox.Margin = new Thickness(0, 0, 0, 10);
        searchBox.TextChanged += (_, _) =>
        {
            _controller.SearchQuery = searchBox.Text;
            UpdateTasksPageInPlace();
        };
        topControls.Children.Add(searchBox);

        _taskFilterHost = new StackPanel();
        topControls.Children.Add(_taskFilterHost);

        DockPanel.SetDock(topControls, Dock.Top);
        leftPane.Children.Add(topControls);

        var listScroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(16, 4, 16, 18) };
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
            "waiting" or "error" => UiKit.OrangeBrush,
            _ => UiKit.MutedBrush
        };
    }

    private void UpdateTasksPageInPlace()
    {
        if (_taskFilterHost is not null)
        {
            _taskFilterHost.Children.Clear();
            var toolWrap = new WrapPanel();
            foreach (var tf in Enum.GetValues<ToolFilter>())
            {
                var target = tf;
                toolWrap.Children.Add(UiKit.PillButton(tf.Title(), _controller.Tool == tf, () =>
                {
                    _controller.Tool = target;
                    UpdateTasksPageInPlace();
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
            var visible = _controller.VisibleTasks;
            if (visible.Count == 0)
            {
                var emptyText = string.IsNullOrWhiteSpace(_controller.SearchQuery)
                    ? "这里很清爽，可切换「全部任务」查看历史会话"
                    : "没有找到匹配的任务";
                var empty = UiKit.Body(emptyText, 13.5, UiKit.MutedBrush);
                empty.Margin = new Thickness(4, 28, 4, 0);
                empty.TextAlignment = TextAlignment.Center;
                _taskListHost.Children.Add(empty);
            }
            else
            {
                foreach (var task in visible)
                {
                    var isSelected = task.Id == _selectedTaskId;
                    var row = new DockPanel();
                    var icon = UiKit.BrandImage(task.Tool, 30);
                    icon.Margin = new Thickness(0, 2, 12, 0);
                    icon.VerticalAlignment = VerticalAlignment.Top;
                    DockPanel.SetDock(icon, Dock.Left);
                    row.Children.Add(icon);

                    var meta = new StackPanel();
                    meta.Children.Add(UiKit.Heading(task.Title, 14));
                    var sub = UiKit.Body($"{task.DeviceName ?? "电脑"} · {(_controller.IsFresh && task.Stale != true ? task.StatusTitle : "状态待更新")}", 12, TaskStatusColor(task));
                    sub.Margin = new Thickness(0, 4, 0, 0);
                    meta.Children.Add(sub);
                    row.Children.Add(meta);

                    var box = new Border
                    {
                        Background = isSelected ? UiKit.SoftBrush : UiKit.SurfaceBrush,
                        BorderBrush = isSelected ? UiKit.AccentBrush : UiKit.LineBrush,
                        BorderThickness = new Thickness(1),
                        CornerRadius = new CornerRadius(13),
                        Padding = new Thickness(13, 11, 13, 11),
                        Margin = new Thickness(0, 0, 0, 8),
                        Child = row
                    };
                    var captured = task;
                    _taskListHost.Children.Add(UiKit.BareButton(box, () => SelectTask(captured)));
                }
            }
        }

        var selectedTask = _selectedTaskId is null ? null : _controller.Tasks.FirstOrDefault(t => t.Id == _selectedTaskId);
        if (selectedTask is null)
        {
            _taskDetail?.Dispose();
            _taskDetail = null;
            _mountedDetailTaskId = null;
            if (_taskDetailRightHost is not null)
            {
                var placeholder = UiKit.Body("在左侧选择一个任务，查看最终结果、下载交付文件或继续回复对话", 14, UiKit.MutedBrush);
                placeholder.HorizontalAlignment = HorizontalAlignment.Center;
                placeholder.VerticalAlignment = VerticalAlignment.Center;
                _taskDetailRightHost.Content = placeholder;
            }
            return;
        }

        if (_mountedDetailTaskId != selectedTask.Id || _taskDetail is null)
        {
            _taskDetail?.Dispose();
            _taskDetail = new TaskDetailController(selectedTask, _controller);
            _taskDetail.Changed += () => Dispatcher.Invoke(RefreshMountedTaskDetail);
            _mountedDetailTaskId = selectedTask.Id;
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
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(26, 22, 26, 28) };
        var root = new StackPanel { MaxWidth = 820 };

        _taskDetailCardsHost = new StackPanel();
        root.Children.Add(_taskDetailCardsHost);

        if (task.SupportsReply)
        {
            var replyStack = new StackPanel();
            var topRow = new DockPanel { Margin = new Thickness(0, 0, 0, 10) };
            topRow.Children.Add(UiKit.Heading("继续任务", 17));

            var rightTools = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
            _replyModelSummaryText = UiKit.Body(detail.ModelSettingsSummary, 12, UiKit.MutedBrush);
            _replyModelSummaryText.VerticalAlignment = VerticalAlignment.Center;
            _replyModelSummaryText.Margin = new Thickness(0, 0, 10, 0);
            rightTools.Children.Add(_replyModelSummaryText);
            rightTools.Children.Add(UiKit.SecondaryButton("模型设置", () => Dialogs.ShowModelSettings(this, task, detail)));
            DockPanel.SetDock(rightTools, Dock.Right);
            topRow.Children.Add(rightTools);
            replyStack.Children.Add(topRow);

            _replyInputBox = UiKit.StyledTextBox(detail.DraftText, "告诉它下一步做什么…", multiline: true);
            _replyInputBox.MinHeight = 96;
            _replyInputBox.MaxHeight = 180;
            _replyInputBox.TextChanged += (_, _) => detail.DraftText = _replyInputBox.Text;
            replyStack.Children.Add(_replyInputBox);

            var footer = new DockPanel { Margin = new Thickness(0, 12, 0, 0) };
            _replyStatusText = UiKit.Body(detail.ReplyMessage, 12.5, UiKit.MutedBrush);
            _replyStatusText.VerticalAlignment = VerticalAlignment.Center;
            footer.Children.Add(_replyStatusText);

            var sendBtn = UiKit.PrimaryButton("发送", async () =>
            {
                await detail.SendReplyAsync(_replyInputBox.Text);
                if (string.IsNullOrEmpty(detail.DraftText) && _replyInputBox is not null)
                    _replyInputBox.Text = "";
            });
            DockPanel.SetDock(sendBtn, Dock.Right);
            sendBtn.HorizontalAlignment = HorizontalAlignment.Right;
            footer.Children.Add(sendBtn);
            replyStack.Children.Add(footer);

            var replyCard = UiKit.Card(replyStack, 20, 16);
            replyCard.Margin = new Thickness(0, 16, 0, 0);
            root.Children.Add(replyCard);
        }

        scroll.Content = root;
        RefreshMountedTaskDetail();
        return scroll;
    }

    private void RefreshMountedTaskDetail()
    {
        if (_taskDetailCardsHost is null || _taskDetail is null || _selectedTaskId is null) return;
        var liveTask = _controller.Tasks.FirstOrDefault(t => t.Id == _selectedTaskId);
        if (liveTask is null) return;

        _taskDetailCardsHost.Children.Clear();

        // Header
        var headerRow = new DockPanel { Margin = new Thickness(0, 0, 0, 14) };
        var icon = UiKit.BrandImage(liveTask.Tool, 40);
        icon.Margin = new Thickness(0, 2, 14, 0);
        DockPanel.SetDock(icon, Dock.Left);
        headerRow.Children.Add(icon);

        var archiveBtn = UiKit.SecondaryButton(liveTask.Archived ? "恢复任务" : "归档", async () => await _controller.ArchiveTaskAsync(liveTask), !_controller.Mutating);
        DockPanel.SetDock(archiveBtn, Dock.Right);
        archiveBtn.VerticalAlignment = VerticalAlignment.Top;
        headerRow.Children.Add(archiveBtn);

        var titleCol = new StackPanel();
        titleCol.Children.Add(UiKit.Heading(liveTask.Title, 22));
        var meta = UiKit.Body($"{liveTask.Brand} · {liveTask.DeviceName ?? "电脑"} · {liveTask.StatusTitle}", 13, TaskStatusColor(liveTask));
        meta.Margin = new Thickness(0, 6, 0, 0);
        titleCol.Children.Add(meta);
        headerRow.Children.Add(titleCol);
        _taskDetailCardsHost.Children.Add(headerRow);

        if (!string.IsNullOrWhiteSpace(liveTask.Preview))
        {
            var prev = UiKit.Body(liveTask.Preview!, 13.5, UiKit.MutedBrush);
            prev.Margin = new Thickness(0, 0, 0, 14);
            _taskDetailCardsHost.Children.Add(prev);
        }

        // Conversation Token Card
        if (liveTask.Usage?.TotalTokens is { } totalTokens)
        {
            var tokRow = new DockPanel();
            var left = new StackPanel();
            left.Children.Add(UiKit.Body("本次对话已记录", 12, UiKit.MutedBrush));
            var valText = UsageFormatting.CompactTokens(totalTokens) + " Token" + (liveTask.Usage.Coverage == "complete" ? "" : " · 部分记录");
            var val = UiKit.Heading(valText, 18);
            val.Margin = new Thickness(0, 4, 0, 0);
            left.Children.Add(val);
            tokRow.Children.Add(left);

            var detailBtn = UiKit.SecondaryButton("查看明细", () => Dialogs.ShowTaskTokenBreakdown(this, liveTask.Usage));
            DockPanel.SetDock(detailBtn, Dock.Right);
            detailBtn.VerticalAlignment = VerticalAlignment.Center;
            detailBtn.HorizontalAlignment = HorizontalAlignment.Right;
            tokRow.Children.Add(detailBtn);

            var tokCard = UiKit.Card(tokRow, 16, 14);
            tokCard.Margin = new Thickness(0, 0, 0, 16);
            _taskDetailCardsHost.Children.Add(tokCard);
        }

        // Attention Requests (Approvals / Questions)
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
        resHeader.Children.Add(UiKit.Heading("最终结果", 17));
        var reloadResBtn = UiKit.SecondaryButton(_taskDetail.ResultLoading ? "读取中…" : "更新结果", async () => await _taskDetail.LoadResultAsync(true), !_taskDetail.ResultLoading);
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
                Padding = new Thickness(12),
                TextWrapping = TextWrapping.Wrap,
                MaxHeight = 340,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto
            };
            resStack.Children.Add(textBox);

            var actionRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 12, 0, 0) };
            actionRow.Children.Add(UiKit.SecondaryButton("复制全文", () => Clipboard.SetText(resultText)));
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
            }, _taskDetail.CanSaveTxt && !_taskDetail.Downloading);
            saveTxtBtn.Margin = new Thickness(8, 0, 0, 0);
            actionRow.Children.Add(saveTxtBtn);
            resStack.Children.Add(actionRow);

            if (resDto.Files is { Count: > 0 } files)
            {
                var filesLabel = UiKit.Body("配套交付文件（SHA-256 完整性校验下载）", 12.5, UiKit.MutedBrush);
                filesLabel.Margin = new Thickness(0, 14, 0, 6);
                resStack.Children.Add(filesLabel);

                foreach (var file in files)
                {
                    var capturedFile = file;
                    var fRow = new DockPanel();
                    fRow.Children.Add(UiKit.Body(file.Name, 13.5));
                    var dlBtn = UiKit.SecondaryButton(file.Ready == false ? "同步中" : $"下载 ({file.Size / 1024.0:0.#} KB)", async () =>
                    {
                        var bytes = await _taskDetail.DownloadDeliverableAsync(capturedFile);
                        if (bytes is null) return;
                        var sfd = new SaveFileDialog { FileName = MonitorSecurity.SafeFileName(capturedFile.Name) };
                        if (sfd.ShowDialog(this) == true)
                            await File.WriteAllBytesAsync(sfd.FileName, bytes);
                    }, file.Ready != false && !_taskDetail.Downloading);
                    DockPanel.SetDock(dlBtn, Dock.Right);
                    dlBtn.HorizontalAlignment = HorizontalAlignment.Right;
                    fRow.Children.Add(dlBtn);

                    var fBorder = new Border
                    {
                        Background = UiKit.BgBrush,
                        CornerRadius = new CornerRadius(10),
                        Padding = new Thickness(12, 8, 12, 8),
                        Margin = new Thickness(0, 0, 0, 6),
                        Child = fRow
                    };
                    resStack.Children.Add(fBorder);
                }
            }
        }
        else
        {
            var reason = !string.IsNullOrEmpty(_taskDetail.ResultError)
                ? _taskDetail.ResultError
                : (_taskDetail.Result?.Reason ?? (_taskDetail.ResultLoading ? "正在读取最终结果…" : "当前还没有最终结果"));
            resStack.Children.Add(UiKit.Body(reason, 13.5, UiKit.MutedBrush));
        }
        _taskDetailCardsHost.Children.Add(UiKit.Card(resStack, 20, 16));

        // Sync reply status labels
        if (_replyModelSummaryText is not null)
            _replyModelSummaryText.Text = _taskDetail.ModelSettingsSummary;
        if (_replyStatusText is not null)
            _replyStatusText.Text = _taskDetail.ReplyMessage;
    }

    private Border BuildAttentionCard(SessionPendingRequestDto req, TaskDetailController detail)
    {
        var stack = new StackPanel();
        stack.Children.Add(UiKit.Heading(string.IsNullOrWhiteSpace(req.Title) ? "需要确认操作" : req.Title, 16));
        if (!string.IsNullOrWhiteSpace(req.Detail))
        {
            var d = UiKit.Body(req.Detail!, 13, UiKit.MutedBrush);
            d.Margin = new Thickness(0, 6, 0, 0);
            stack.Children.Add(d);
        }
        if (!string.IsNullOrWhiteSpace(req.Operation))
        {
            var opBox = new Border
            {
                Background = UiKit.MutedBgBrush,
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(10, 8, 10, 8),
                Margin = new Thickness(0, 8, 0, 0),
                Child = new TextBlock { Text = req.Operation, FontFamily = UiKit.MonoFont, FontSize = 12.5, TextWrapping = TextWrapping.Wrap }
            };
            stack.Children.Add(opBox);
        }

        if (req.Kind == "question" && req.Questions is { Count: > 0 } questions)
        {
            var answerEditors = new List<(string QId, List<CheckBox> Checks, TextBox? FreeText)>();
            foreach (var q in questions)
            {
                var qTitle = UiKit.Body(q.Text, 13.5);
                qTitle.FontWeight = FontWeights.Medium;
                qTitle.Margin = new Thickness(0, 12, 0, 6);
                stack.Children.Add(qTitle);

                var checks = new List<CheckBox>();
                if (q.Options is { Count: > 0 })
                {
                    foreach (var opt in q.Options)
                    {
                        var cb = new CheckBox { Content = opt.Label, Tag = opt.Id, Margin = new Thickness(0, 2, 0, 4), FontSize = 13 };
                        checks.Add(cb);
                        stack.Children.Add(cb);
                    }
                }
                TextBox? freeBox = null;
                if (q.AllowsText == true || checks.Count == 0)
                {
                    freeBox = UiKit.StyledTextBox("", "补充说明…");
                    freeBox.Margin = new Thickness(0, 4, 0, 4);
                    stack.Children.Add(freeBox);
                }
                answerEditors.Add((q.Id, checks, freeBox));
            }

            var submitBtn = UiKit.PrimaryButton("提交回答", async () =>
            {
                var payload = answerEditors.Select(ed => new SessionAnswerPayloadDto
                {
                    QuestionId = ed.QId,
                    Choices = ed.Checks.Where(c => c.IsChecked == true).Select(c => (string)c.Tag).ToList(),
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
            btnRow.Children.Add(UiKit.PrimaryButton("仅允许这一次", async () => await detail.SubmitDecisionAsync(req, "approve_once"), !detail.DecisionSending && req.State == "pending"));
            var rejectBtn = UiKit.SecondaryButton("拒绝", async () => await detail.SubmitDecisionAsync(req, "deny"), !detail.DecisionSending && req.State == "pending", UiKit.RedBrush);
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

        var border = UiKit.Card(stack, 18, 15);
        border.BorderBrush = UiKit.OrangeBrush;
        return border;
    }

    // ==================== COMPUTERS PAGE ====================

    private UIElement BuildDevicesContent()
    {
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(28, 24, 28, 28) };
        var stack = new StackPanel { MaxWidth = 780 };
        stack.Children.Add(UiKit.Heading("我的电脑", 24));

        // This Windows PC card
        var localStack = new StackPanel();
        localStack.Children.Add(UiKit.Heading($"这台 Windows 电脑 ({Environment.MachineName})", 18));

        var hubLocal = _controller.BuiltInLocalHubComputer;
        var collectorStatus = _controller.Collector.Status;
        if (collectorStatus is not null)
        {
            var statusDesc = collectorStatus.Paired
                ? (collectorStatus.Service == "running" ? $"正在同步 · 模式：{collectorStatus.Mode}" : "已连接 · 同步已暂停")
                : "连接后，手机与其他设备也能实时查看这台电脑上的 AI 任务";
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
                var settingsBtn = UiKit.SecondaryButton("同步范围设置", () =>
                {
                    Dialogs.ShowCollectorSettings(this, _controller);
                    RenderAll(false);
                });
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
                }, !_controller.Collector.Busy && !_controller.Mutating));
            }
        }
        else if (hubLocal is not null && string.Equals(hubLocal.Name, Environment.MachineName, StringComparison.OrdinalIgnoreCase))
        {
            var sub = UiKit.Body("本机正在直接运行个人同步服务 Hub，任务与官方额度已自动直连同步。", 13.5, UiKit.GreenBrush);
            sub.Margin = new Thickness(0, 8, 0, 12);
            localStack.Children.Add(sub);
            var syncBtn = UiKit.SecondaryButton(_controller.SyncOutput ? "账号结果同步：已开启（点击关闭）" : "开启账号结果同步",
                async () => await _controller.SetResultSyncAsync(!_controller.SyncOutput), !_controller.Mutating);
            syncBtn.HorizontalAlignment = HorizontalAlignment.Left;
            localStack.Children.Add(syncBtn);
        }
        else
        {
            var sub = UiKit.Body("本机独立采集器尚未安装（运行 python windows/scripts/collector_service.py install 可启用后台自启采集）。", 13, UiKit.MutedBrush);
            sub.Margin = new Thickness(0, 8, 0, 0);
            localStack.Children.Add(sub);
        }

        if (!string.IsNullOrEmpty(_controller.Collector.Message))
        {
            var msg = UiKit.Body(_controller.Collector.Message, 12.5, UiKit.MutedBrush);
            msg.Margin = new Thickness(0, 8, 0, 0);
            localStack.Children.Add(msg);
        }

        var localCard = UiKit.Card(localStack, 22, 16);
        localCard.Margin = new Thickness(0, 18, 0, 18);
        stack.Children.Add(localCard);

        // Connected computers list
        foreach (var dev in _controller.Devices)
        {
            var row = new DockPanel();
            var info = new StackPanel();
            info.Children.Add(UiKit.Heading(dev.Name + (dev.Local ? " · 服务主机" : ""), 15.5));
            var online = _controller.IsFresh && dev.Online;
            var status = UiKit.Body($"{dev.Platform} · {(online ? "在线" : "离线")}", 13, online ? UiKit.GreenBrush : UiKit.MutedBrush);
            status.Margin = new Thickness(0, 4, 0, 0);
            info.Children.Add(status);
            row.Children.Add(info);

            var card = UiKit.Card(row, 18, 14);
            card.Margin = new Thickness(0, 0, 0, 10);
            stack.Children.Add(card);
        }

        var addBtn = UiKit.SecondaryButton("+ 添加其他电脑", () => Dialogs.ShowAddComputerDialog(this, _controller));
        addBtn.HorizontalAlignment = HorizontalAlignment.Left;
        addBtn.Margin = new Thickness(0, 8, 0, 0);
        stack.Children.Add(addBtn);

        scroll.Content = stack;
        return scroll;
    }

    // ==================== USAGE PAGE ====================

    private UIElement BuildUsageContent()
    {
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(28, 24, 28, 28) };
        var stack = new StackPanel { MaxWidth = 840 };

        var topBar = new DockPanel { Margin = new Thickness(0, 0, 0, 18) };
        topBar.Children.Add(UiKit.Heading("用量与官方额度", 24));
        var topActions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        topActions.Children.Add(UiKit.SecondaryButton("提醒设置", () => Dialogs.ShowAlertSettings(this, _controller)));
        var refBtn = UiKit.SecondaryButton(_controller.UsageRefreshing ? "刷新中…" : "刷新", async () => await _controller.RefreshUsageAsync(), !_controller.UsageRefreshing);
        refBtn.Margin = new Thickness(8, 0, 0, 0);
        topActions.Children.Add(refBtn);
        DockPanel.SetDock(topActions, Dock.Right);
        topBar.Children.Add(topActions);
        stack.Children.Add(topBar);

        var now = DateTimeOffset.UtcNow;
        var providers = _controller.Usage?.Providers ?? [];

        // 1. Official Quotas Card
        var quotaStack = new StackPanel();
        var qHead = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
        qHead.Children.Add(UiKit.Heading("官方额度", 18));
        var modeToggle = UiKit.PillButton(_usageShowUsed ? "显示：已用 %" : "显示：剩余 %", true, () =>
        {
            _usageShowUsed = !_usageShowUsed;
            RenderAll(false);
        });
        DockPanel.SetDock(modeToggle, Dock.Right);
        modeToggle.HorizontalAlignment = HorizontalAlignment.Right;
        qHead.Children.Add(modeToggle);
        quotaStack.Children.Add(qHead);

        var qToolPills = new WrapPanel { Margin = new Thickness(0, 0, 0, 14) };
        foreach (var (toolKey, label) in new[] { ("codex", "Codex"), ("claude", "Claude Code"), ("antigravity", "Antigravity") })
        {
            var captured = toolKey;
            qToolPills.Children.Add(UiKit.PillButton(label, _usageQuotaTool == toolKey, () =>
            {
                _usageQuotaTool = captured;
                RenderAll(false);
            }));
        }
        quotaStack.Children.Add(qToolPills);

        var activeQuotaProvider = providers.FirstOrDefault(p => p.Tool == _usageQuotaTool);
        var visibleQuotas = activeQuotaProvider?.Quotas?.Where(q => q.Remaining is not null && !q.IsExpired(now)).ToList() ?? [];
        if (visibleQuotas.Count == 0)
        {
            var checkNote = _controller.Usage?.QuotaChecks?.FirstOrDefault(c => c.Tool == _usageQuotaTool)?.Status switch
            {
                "unauthenticated" => "电脑端尚未登录此工具账号，暂不读取官方额度。",
                "unavailable" => "电脑端未开启或暂未提供官方额度窗口。",
                _ => "暂无该工具的有效官方额度记录（请确保对应桌面客户端或会话已在电脑端打开）。"
            };
            quotaStack.Children.Add(UiKit.Body(checkNote, 13.5, UiKit.MutedBrush));
        }
        else
        {
            foreach (var q in visibleQuotas)
            {
                var qItem = new StackPanel { Margin = new Thickness(0, 0, 0, 14) };
                var line1 = new DockPanel();
                var srcSuffix = !string.IsNullOrWhiteSpace(q.SourceName) ? $" ({q.SourceName})" : "";
                line1.Children.Add(UiKit.Heading(UsageFormatting.QuotaDisplayTitle(q) + srcSuffix, 14.5));

                var pctVal = _usageShowUsed ? q.Used : q.Remaining;
                var pctLabel = (_usageShowUsed ? "已用 " : "剩余 ") + UsageFormatting.Percentage(pctVal);
                var rightPct = UiKit.Heading(pctLabel, 14.5);
                rightPct.Foreground = q.Remaining <= 20 ? UiKit.OrangeBrush : UiKit.TextBrush;
                DockPanel.SetDock(rightPct, Dock.Right);
                rightPct.HorizontalAlignment = HorizontalAlignment.Right;
                line1.Children.Add(rightPct);
                qItem.Children.Add(line1);

                var barTrack = new Grid { Height = 8, Margin = new Thickness(0, 7, 0, 6) };
                barTrack.Children.Add(new Border { Background = UiKit.MutedBgBrush, CornerRadius = new CornerRadius(4) });
                var ratio = Math.Clamp((pctVal ?? 0.0) / 100.0, 0.0, 1.0);
                var fillGrid = new Grid();
                fillGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(ratio, GridUnitType.Star) });
                fillGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(0.0001, 1.0 - ratio), GridUnitType.Star) });
                var fillBar = new Border
                {
                    Background = q.Remaining <= 20 ? UiKit.OrangeBrush : UiKit.AccentBrush,
                    CornerRadius = new CornerRadius(4)
                };
                Grid.SetColumn(fillBar, 0);
                fillGrid.Children.Add(fillBar);
                barTrack.Children.Add(fillGrid);
                qItem.Children.Add(barTrack);

                qItem.Children.Add(UiKit.Body(UsageFormatting.ResetText(q, now), 12, UiKit.MutedBrush));
                quotaStack.Children.Add(qItem);
            }
        }

        var quotaCard = UiKit.Card(quotaStack, 22, 16);
        quotaCard.Margin = new Thickness(0, 0, 0, 20);
        stack.Children.Add(quotaCard);

        // 2. Local Tokens + Bar Chart + Per-Model Breakdown
        var localStack = new StackPanel();
        localStack.Children.Add(UiKit.Heading("本地已记录 Token", 18));
        var localSub = UiKit.Body("根据各台已连接电脑本地日志去重统计，按模型展示精确归属。", 12.5, UiKit.MutedBrush);
        localSub.Margin = new Thickness(0, 4, 0, 12);
        localStack.Children.Add(localSub);

        var localPills = new WrapPanel { Margin = new Thickness(0, 0, 0, 14) };
        foreach (var (toolKey, label) in new[] { ("codex", "Codex"), ("claude", "Claude Code"), ("antigravity", "Antigravity") })
        {
            var captured = toolKey;
            localPills.Children.Add(UiKit.PillButton(label, _usageLocalTool == toolKey, () =>
            {
                _usageLocalTool = captured;
                _selectedHistoryDay = null;
                RenderAll(false);
            }));
        }
        localStack.Children.Add(localPills);

        var localProvider = providers.FirstOrDefault(p => p.Tool == _usageLocalTool);
        var days = localProvider?.History?.Days?.OrderBy(d => d.Day).ToList() ?? [];
        var todayEntry = days.LastOrDefault();
        var activeDay = (_selectedHistoryDay is not null ? days.FirstOrDefault(d => d.Day == _selectedHistoryDay) : null) ?? todayEntry;

        var summaryGrid = new UniformGrid { Columns = 3, Margin = new Thickness(0, 0, 0, 16) };
        var todayTotal = localProvider?.TodayTokens ?? todayEntry?.Tokens;
        var last7Total = days.TakeLast(7).Sum(d => d.Tokens ?? 0);
        var last30Total = days.TakeLast(30).Sum(d => d.Tokens ?? 0);

        summaryGrid.Children.Add(BuildStatMiniCard("今日 Token", UsageFormatting.CompactTokens(todayTotal)));
        summaryGrid.Children.Add(BuildStatMiniCard("近 7 天合计", UsageFormatting.CompactTokens(last7Total)));
        summaryGrid.Children.Add(BuildStatMiniCard("近 30 天合计", UsageFormatting.CompactTokens(last30Total)));
        localStack.Children.Add(summaryGrid);

        // 14-day clickable bar chart
        var recentDays = days.TakeLast(14).ToList();
        if (recentDays.Count > 0)
        {
            var maxVal = Math.Max(1L, recentDays.Max(d => d.Tokens ?? 0));
            var chartGrid = new UniformGrid { Columns = recentDays.Count, Height = 110, Margin = new Thickness(0, 4, 0, 14) };
            foreach (var d in recentDays)
            {
                var capturedDay = d.Day;
                var isSelected = activeDay?.Day == d.Day;
                var col = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(2, 0, 2, 0) };
                var h = Math.Max(4.0, ((d.Tokens ?? 0) / (double)maxVal) * 76.0);
                col.Children.Add(new Border
                {
                    Height = h,
                    Background = isSelected ? UiKit.AccentBrush : UiKit.SoftBrush,
                    CornerRadius = new CornerRadius(4, 4, 2, 2),
                    ToolTip = $"{d.Day}: {UsageFormatting.ExactTokens(d.Tokens)} Token"
                });
                var lbl = UiKit.Body(d.Day.Length >= 10 ? d.Day[5..] : d.Day, 10, isSelected ? UiKit.AccentBrush : UiKit.MutedBrush);
                lbl.HorizontalAlignment = HorizontalAlignment.Center;
                lbl.Margin = new Thickness(0, 4, 0, 0);
                col.Children.Add(lbl);
                chartGrid.Children.Add(UiKit.BareButton(col, () =>
                {
                    _selectedHistoryDay = capturedDay;
                    RenderAll(false);
                }));
            }
            localStack.Children.Add(chartGrid);
        }

        // Model Token Breakdown for activeDay
        if (activeDay is not null)
        {
            var modelHeader = UiKit.Heading($"模型 Token 占比 ({activeDay.Day} · 共 {UsageFormatting.CompactTokens(activeDay.Tokens)})", 15);
            modelHeader.Margin = new Thickness(0, 6, 0, 10);
            localStack.Children.Add(modelHeader);

            var shares = UsageFormatting.ComputeModelShares(activeDay);
            if (shares.Count == 0)
            {
                localStack.Children.Add(UiKit.Body("当日暂无细分模型用量记录", 13, UiKit.MutedBrush));
            }
            else
            {
                foreach (var share in shares)
                {
                    var mRow = new DockPanel { Margin = new Thickness(0, 0, 0, 8) };
                    mRow.Children.Add(UiKit.Body(share.DisplayName, 13.5));
                    var mRight = UiKit.Body($"{UsageFormatting.CompactTokens(share.Tokens)} · {UsageFormatting.Percentage(share.Percent)}", 13, UiKit.MutedBrush);
                    DockPanel.SetDock(mRight, Dock.Right);
                    mRight.HorizontalAlignment = HorizontalAlignment.Right;
                    mRow.Children.Add(mRight);
                    localStack.Children.Add(mRow);
                }
            }
        }

        stack.Children.Add(UiKit.Card(localStack, 22, 16));
        scroll.Content = stack;
        return scroll;
    }

    private static UIElement BuildStatMiniCard(string label, string value)
    {
        var s = new StackPanel();
        s.Children.Add(UiKit.Body(label, 12, UiKit.MutedBrush));
        var h = UiKit.Heading(value, 20);
        h.Margin = new Thickness(0, 4, 0, 0);
        s.Children.Add(h);
        var b = new Border
        {
            Background = UiKit.BgBrush,
            CornerRadius = new CornerRadius(12),
            Padding = new Thickness(14, 12, 14, 12),
            Margin = new Thickness(0, 0, 8, 0),
            Child = s
        };
        return b;
    }

    // ==================== ACCOUNT PAGE ====================

    private UIElement BuildAccountContent()
    {
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(28, 24, 28, 28) };
        var stack = new StackPanel { MaxWidth = 760 };
        stack.Children.Add(UiKit.Heading("我的账号", 24));

        var user = _controller.Account?.User;
        var profileRow = new DockPanel();
        var avatar = UiKit.AvatarImage(user?.Avatar, 60);
        avatar.Margin = new Thickness(0, 0, 16, 0);
        DockPanel.SetDock(avatar, Dock.Left);
        profileRow.Children.Add(avatar);

        var editBtn = UiKit.SecondaryButton("编辑资料", () => Dialogs.ShowProfileEditor(this, _controller), _controller.Account is not null);
        DockPanel.SetDock(editBtn, Dock.Right);
        editBtn.VerticalAlignment = VerticalAlignment.Center;
        profileRow.Children.Add(editBtn);

        var nameCol = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        nameCol.Children.Add(UiKit.Heading(user?.DisplayOrUsername ?? "我的账号", 20));
        if (!string.IsNullOrEmpty(user?.Username))
        {
            var u = UiKit.Body("@" + user!.Username, 13, UiKit.MutedBrush);
            u.Margin = new Thickness(0, 3, 0, 0);
            nameCol.Children.Add(u);
        }
        profileRow.Children.Add(nameCol);

        var profileCard = UiKit.Card(profileRow, 20, 16);
        profileCard.Margin = new Thickness(0, 18, 0, 16);
        stack.Children.Add(profileCard);

        var quickRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 18) };
        quickRow.Children.Add(UiKit.SecondaryButton("官方额度提醒设置", () => Dialogs.ShowAlertSettings(this, _controller)));
        var trayHintBtn = UiKit.SecondaryButton("最小化到系统托盘", Hide);
        trayHintBtn.Margin = new Thickness(10, 0, 0, 0);
        quickRow.Children.Add(trayHintBtn);
        stack.Children.Add(quickRow);

        if (_controller.Account?.NativeDevices is { Count: > 0 } devices)
        {
            var devStack = new StackPanel();
            devStack.Children.Add(UiKit.Heading("已登录原生设备", 17));
            foreach (var d in devices)
            {
                var dRow = new DockPanel { Margin = new Thickness(0, 10, 0, 0) };
                dRow.Children.Add(UiKit.Body(d.Name, 13.5));
                if (d.Current == true)
                {
                    var cur = UiKit.Body("当前桌面端", 12.5, UiKit.AccentBrush);
                    DockPanel.SetDock(cur, Dock.Right);
                    cur.HorizontalAlignment = HorizontalAlignment.Right;
                    dRow.Children.Add(cur);
                }
                devStack.Children.Add(dRow);
            }
            var devCard = UiKit.Card(devStack, 20, 16);
            devCard.Margin = new Thickness(0, 0, 0, 18);
            stack.Children.Add(devCard);
        }

        var licStack = new StackPanel();
        licStack.Children.Add(UiKit.Heading("开源与许可", 16));
        var licBody = UiKit.Body("Monitor 源码采用 MIT 许可证开放；界面优先使用 MiSans / Microsoft YaHei UI 字体；二维码矩阵采用 QRCoder (MIT)。", 13, UiKit.MutedBrush);
        licBody.Margin = new Thickness(0, 6, 0, 10);
        licStack.Children.Add(licBody);
        licStack.Children.Add(UiKit.LinkButton("查看开源与第三方许可说明", () => MonitorController.OpenBrowserUrl("https://github.com/Makabaka-zxh/agent-monitor/blob/main/THIRD_PARTY_NOTICES.md")));
        var licCard = UiKit.Card(licStack, 20, 16);
        licCard.Margin = new Thickness(0, 0, 0, 18);
        stack.Children.Add(licCard);

        var logoutBtn = UiKit.SecondaryButton("退出这台电脑的登录", async () => await _controller.LogoutAsync(), !_controller.Mutating, UiKit.RedBrush);
        logoutBtn.HorizontalAlignment = HorizontalAlignment.Left;
        stack.Children.Add(logoutBtn);

        scroll.Content = stack;
        return scroll;
    }
}
