using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shell;
using Microsoft.Win32;
using MonitorApp.Core;

namespace MonitorApp.UI;

public static class Dialogs
{
    private static Window CreateModal(Window owner, string title, double width = 450)
    {
        var maxModalH = Math.Max(480.0, SystemParameters.WorkArea.Height - 56.0);
        var win = new Window
        {
            Owner = owner,
            Title = title,
            Width = width,
            MaxHeight = maxModalH,
            SizeToContent = SizeToContent.Height,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            ResizeMode = ResizeMode.NoResize,
            ShowInTaskbar = false,
            UseLayoutRounding = true,
            Background = UiKit.CanvasBrush,
            Foreground = UiKit.TextBrush,
            FontFamily = UiKit.AppFont
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
            if (DwmInterop.ApplyWindowStyling(win, UiKit.IsDark, transientAcrylic: true))
                win.Background = UiKit.MicaCanvasBrush;
        };
        win.Loaded += (_, _) =>
        {
            var wa = SystemParameters.WorkArea;
            if (win.Top < wa.Top + 12) win.Top = wa.Top + 12;
            if (win.Top + win.ActualHeight > wa.Bottom - 12)
                win.Top = Math.Max(wa.Top + 12, wa.Bottom - win.ActualHeight - 12);
            if (win.Left < wa.Left + 12) win.Left = wa.Left + 12;
            if (win.Left + win.ActualWidth > wa.Right - 12)
                win.Left = Math.Max(wa.Left + 12, wa.Right - win.ActualWidth - 12);
        };
        win.MouseLeftButtonDown += (_, e) =>
        {
            if (e.ButtonState == MouseButtonState.Pressed)
            {
                try { win.DragMove(); } catch { }
            }
        };
        win.PreviewKeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape)
            {
                e.Handled = true;
                win.Close();
            }
        };
        return win;
    }

    private static void PresentModal(Window win, UIElement body)
    {
        var shell = new Grid();
        shell.Children.Add(body);
        var closeBtn = UiKit.MacCloseCapsule(win.Close, "关闭 (Esc)");
        closeBtn.HorizontalAlignment = HorizontalAlignment.Right;
        closeBtn.VerticalAlignment = VerticalAlignment.Top;
        closeBtn.Margin = new Thickness(0, 13, 14, 0);
        shell.Children.Add(closeBtn);

        win.Content = new Border
        {
            BorderBrush = UiKit.GlassRimBrush,
            BorderThickness = new Thickness(1),
            Child = UiKit.AmbientStage(shell, compact: true)
        };
        win.ShowDialog();
    }

    public static void ShowMessage(Window owner, string title, string message)
    {
        var win = CreateModal(owner, title, 436);
        var stack = new StackPanel { Margin = new Thickness(26, 24, 26, 24) };
        var head = UiKit.Heading(title, 19.5);
        head.Margin = new Thickness(0, 0, 28, 0);
        stack.Children.Add(head);
        var scroll = new ScrollViewer
        {
            MaxHeight = 360,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Margin = new Thickness(0, 14, 0, 22),
            Content = UiKit.Body(message, 13.5, UiKit.MutedBrush)
        };
        stack.Children.Add(scroll);
        var btn = UiKit.PrimaryButton("知道了", () => win.Close());
        btn.HorizontalAlignment = HorizontalAlignment.Right;
        stack.Children.Add(btn);
        PresentModal(win, stack);
    }

    public static bool Confirm(Window owner, string title, string message, string confirmLabel, bool danger = false)
    {
        var accepted = false;
        var win = CreateModal(owner, title, 426);
        var stack = new StackPanel { Margin = new Thickness(26, 24, 26, 24) };
        var head = UiKit.Heading(title, 19.5);
        head.Margin = new Thickness(0, 0, 28, 0);
        stack.Children.Add(head);
        var body = UiKit.Body(message, 13.5, UiKit.MutedBrush);
        body.Margin = new Thickness(0, 12, 0, 22);
        stack.Children.Add(body);

        var actions = new DockPanel();
        actions.Children.Add(UiKit.SecondaryButton("取消", () => win.Close(), textBrush: UiKit.MutedBrush));
        var okBtn = danger
            ? UiKit.SecondaryButton(confirmLabel, () => { accepted = true; win.Close(); }, textBrush: UiKit.DangerBrush)
            : UiKit.PrimaryButton(confirmLabel, () => { accepted = true; win.Close(); });
        DockPanel.SetDock(okBtn, Dock.Right);
        okBtn.HorizontalAlignment = HorizontalAlignment.Right;
        actions.Children.Add(okBtn);
        stack.Children.Add(actions);

        PresentModal(win, stack);
        return accepted;
    }

    public static void ShowTaskTokenBreakdown(Window owner, TaskUsageSummary? usage)
    {
        var text = usage is null
            ? "这台电脑暂未提供此对话的用量记录。\n未提供不代表没有使用。"
            : $"总量：{UsageFormatting.ExactOrMissing(usage.TotalTokens)} Token\n" +
              $"输入：{UsageFormatting.ExactOrMissing(usage.InputTokens)}\n" +
              $"输出：{UsageFormatting.ExactOrMissing(usage.OutputTokens)}\n" +
              $"缓存读取：{UsageFormatting.ExactOrMissing(usage.CachedInputTokens)}\n" +
              $"缓存写入：{UsageFormatting.ExactOrMissing(usage.CacheWriteTokens)}\n\n" +
              "这是已采集到的对话记录，可能不是完整历史。\n缓存相关分类依工具定义，总量由采集端去重计算，不把分类重复相加。";
        ShowMessage(owner, "对话用量", text);
    }

    public static void ShowModelSettings(Window owner, MonitorTask task, TaskDetailController detail)
    {
        var win = CreateModal(owner, "模型设置", 460);
        var root = new StackPanel { Margin = new Thickness(26) };
        root.Children.Add(UiKit.Heading($"{task.Brand} · 模型设置", 21));
        var sub = UiKit.Body("设置发送下一条回复时使用的模型与思考强度。选择「继承电脑当前设置」不会改动电脑端原有配置。", 13, UiKit.MutedBrush);
        sub.Margin = new Thickness(0, 8, 0, 16);
        root.Children.Add(sub);

        var catalog = detail.Controls?.Catalog;
        var models = catalog?.Models ?? [];

        var modelCombo = UiKit.StyledComboBox();
        modelCombo.Margin = new Thickness(0, 6, 0, 14);
        UiKit.AddComboItem(modelCombo, "继承电脑当前设置", "");
        foreach (var opt in models)
        {
            var displayLabel = string.IsNullOrWhiteSpace(opt.Label) || string.Equals(opt.Label, opt.Id, StringComparison.Ordinal)
                ? UsageFormatting.FormatModelName(opt.Id)
                : opt.Label;
            UiKit.AddComboItem(modelCombo, displayLabel, opt.Id);
        }

        var currentSelectedModel = detail.ModelOverrides.Selection?.Model ?? "";
        modelCombo.SelectedIndex = 0;
        for (var i = 1; i < modelCombo.Items.Count; i++)
        {
            if (modelCombo.Items[i] is ComboBoxItem item && (string?)item.Tag == currentSelectedModel)
            {
                modelCombo.SelectedIndex = i;
                break;
            }
        }

        var effortCombo = UiKit.StyledComboBox();
        effortCombo.Margin = new Thickness(0, 6, 0, 14);
        var fastCheck = new CheckBox { Content = "下一轮启用 Codex Fast 快速层", Foreground = UiKit.TextBrush, IsChecked = detail.ModelOverrides.FastNext, Margin = new Thickness(0, 4, 0, 8), FontSize = 13 };
        var planCheck = new CheckBox { Content = "下一轮启用 Plan 规划模式", Foreground = UiKit.TextBrush, IsChecked = detail.ModelOverrides.PlanNext, Margin = new Thickness(0, 0, 0, 14), FontSize = 13 };

        void RebuildEfforts(string? preferredEffort)
        {
            effortCombo.Items.Clear();
            UiKit.AddComboItem(effortCombo, "默认推理强度", "");
            var chosenId = (modelCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? "";
            var opt = models.FirstOrDefault(m => m.Id == chosenId);
            if (opt?.Efforts is { Count: > 0 })
            {
                foreach (var eff in opt.Efforts)
                    UiKit.AddComboItem(effortCombo, eff, eff);
            }
            effortCombo.SelectedIndex = 0;
            if (!string.IsNullOrEmpty(preferredEffort))
            {
                for (var i = 1; i < effortCombo.Items.Count; i++)
                {
                    if (effortCombo.Items[i] is ComboBoxItem item && (string?)item.Tag == preferredEffort)
                    {
                        effortCombo.SelectedIndex = i;
                        break;
                    }
                }
            }
            effortCombo.IsEnabled = !string.IsNullOrEmpty(chosenId) && effortCombo.Items.Count > 1;
            var supportsFast = opt?.ServiceTiers?.Any(t => t is "priority" or "fast") == true;
            var supportsPlan = catalog?.CollaborationModes?.Contains("plan") == true && !string.IsNullOrEmpty(chosenId);
            fastCheck.Visibility = supportsFast ? Visibility.Visible : Visibility.Collapsed;
            planCheck.Visibility = supportsPlan ? Visibility.Visible : Visibility.Collapsed;
        }

        RebuildEfforts(detail.ModelOverrides.Selection?.Effort);
        modelCombo.SelectionChanged += (_, _) => RebuildEfforts(null);

        if (detail.Controls?.CatalogStatus == "pending" && models.Count == 0)
        {
            var waitNote = UiKit.Body("正在向电脑端获取可用模型目录（每 2 秒自动更新）…", 13, UiKit.WarningBrush);
            waitNote.Margin = new Thickness(0, 0, 0, 12);
            root.Children.Add(waitNote);
        }
        else if (models.Count == 0)
        {
            var emptyNote = UiKit.Body(detail.Controls?.Reason ?? "当前会话暂未返回可选模型列表，仍可继承电脑当前设置直接发送。", 13, UiKit.MutedBrush);
            emptyNote.Margin = new Thickness(0, 0, 0, 12);
            root.Children.Add(emptyNote);
        }

        root.Children.Add(UiKit.Body("模型", 13, UiKit.MutedBrush));
        root.Children.Add(modelCombo);
        root.Children.Add(UiKit.Body("思考强度", 13, UiKit.MutedBrush));
        root.Children.Add(effortCombo);
        root.Children.Add(fastCheck);
        root.Children.Add(planCheck);

        var actions = new DockPanel { Margin = new Thickness(0, 10, 0, 0) };
        actions.Children.Add(UiKit.SecondaryButton("取消", () => win.Close(), textBrush: UiKit.MutedBrush));

        var saveBtn = UiKit.PrimaryButton("保存设置", () =>
        {
            var chosenModel = (modelCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? "";
            if (string.IsNullOrEmpty(chosenModel) || catalog is null)
            {
                detail.SaveModelOverrides(null, false, false);
            }
            else
            {
                var chosenEffort = (effortCombo.SelectedItem as ComboBoxItem)?.Tag as string;
                if (string.IsNullOrWhiteSpace(chosenEffort)) chosenEffort = null;
                detail.SaveModelOverrides(
                    new SessionModelSelectionDto
                    {
                        Provider = catalog.Provider,
                        CatalogRevision = catalog.CatalogRevision,
                        Model = chosenModel,
                        Effort = chosenEffort,
                        ApplyAt = "next_message"
                    },
                    fastCheck.Visibility == Visibility.Visible && fastCheck.IsChecked == true,
                    planCheck.Visibility == Visibility.Visible && planCheck.IsChecked == true);
            }
            win.Close();
        });
        DockPanel.SetDock(saveBtn, Dock.Right);
        saveBtn.HorizontalAlignment = HorizontalAlignment.Right;
        actions.Children.Add(saveBtn);
        root.Children.Add(actions);

        PresentModal(win, root);
    }

    public static void ShowCollectorSettings(Window owner, MonitorController controller)
    {
        var win = CreateModal(owner, "这台电脑的同步", 450);
        var root = new StackPanel { Margin = new Thickness(26) };
        root.Children.Add(UiKit.Heading("这台 Windows 电脑的同步", 21));
        var desc = UiKit.Body("本机设置决定采集器允许同步的范围；查看最终结果与下载配套文件还需开启账号结果同步。", 13, UiKit.MutedBrush);
        desc.Margin = new Thickness(0, 8, 0, 16);
        root.Children.Add(desc);

        var currentMode = controller.Collector.Status?.Mode ?? "status-only";
        var rStatus = new RadioButton { Content = "仅任务状态", Foreground = UiKit.TextBrush, GroupName = "mode", IsChecked = currentMode == "status-only", Margin = new Thickness(0, 4, 0, 8), FontSize = 13.5 };
        var rResults = new RadioButton { Content = "结果与文件", Foreground = UiKit.TextBrush, GroupName = "mode", IsChecked = currentMode == "results", Margin = new Thickness(0, 4, 0, 8), FontSize = 13.5 };
        var rFull = new RadioButton { Content = "结果、文件与回复", Foreground = UiKit.TextBrush, GroupName = "mode", IsChecked = currentMode == "full", Margin = new Thickness(0, 4, 0, 14), FontSize = 13.5 };
        root.Children.Add(rStatus);
        root.Children.Add(rResults);
        root.Children.Add(rFull);

        var syncAccountCheck = new CheckBox
        {
            Content = "开启账号结果同步（所有已登录设备可查看最终结果与配套文件）",
            Foreground = UiKit.TextBrush,
            IsChecked = controller.SyncOutput,
            Margin = new Thickness(0, 4, 0, 20),
            FontSize = 13
        };
        root.Children.Add(syncAccountCheck);

        var actions = new DockPanel();
        actions.Children.Add(UiKit.SecondaryButton("取消", () => win.Close(), textBrush: UiKit.MutedBrush));

        var saveBtn = UiKit.PrimaryButton("保存", async () =>
        {
            var targetMode = rFull.IsChecked == true ? "full" : rResults.IsChecked == true ? "results" : "status-only";
            var wantSync = syncAccountCheck.IsChecked == true;
            if (wantSync != controller.SyncOutput)
                await controller.SetResultSyncAsync(wantSync);
            if (controller.Collector.Status is not null && targetMode != controller.Collector.Status.Mode)
                await controller.Collector.SetModeAsync(controller.Origin, targetMode);
            win.Close();
        });
        DockPanel.SetDock(saveBtn, Dock.Right);
        saveBtn.HorizontalAlignment = HorizontalAlignment.Right;
        actions.Children.Add(saveBtn);
        root.Children.Add(actions);

        PresentModal(win, root);
    }

    public static void ShowAlertSettings(Window owner, MonitorController controller, Action<string, string>? onTestAlert = null)
    {
        var current = controller.ReadAlertSettings();
        var win = CreateModal(owner, "额度提醒", 430);
        var root = new StackPanel { Margin = new Thickness(26) };
        root.Children.Add(UiKit.Heading("官方额度提醒", 21));
        var desc = UiKit.Body("当 Codex、Claude Code 或 Antigravity 官方窗口剩余额度低于设定比例，或窗口重置恢复时，通过 Windows 系统托盘发送通知。", 13, UiKit.MutedBrush);
        desc.Margin = new Thickness(0, 8, 0, 16);
        root.Children.Add(desc);

        var enabledCheck = new CheckBox { Content = "开启低额度托盘提醒", Foreground = UiKit.TextBrush, IsChecked = current.Enabled, FontSize = 13.5, Margin = new Thickness(0, 0, 0, 12) };
        var resetCheck = new CheckBox { Content = "额度窗口重置恢复时通知我", Foreground = UiKit.TextBrush, IsChecked = current.NotifyReset, FontSize = 13.5, Margin = new Thickness(0, 0, 0, 14) };

        var thresholdCombo = UiKit.StyledComboBox();
        thresholdCombo.Margin = new Thickness(0, 6, 0, 18);
        foreach (var pct in new[] { 10, 15, 20, 30, 50 })
        {
            var item = UiKit.AddComboItem(thresholdCombo, $"剩余低于 {pct}% 时提醒", pct);
            if (pct == current.ThresholdPercent) thresholdCombo.SelectedItem = item;
        }
        if (thresholdCombo.SelectedIndex < 0) thresholdCombo.SelectedIndex = 2;

        root.Children.Add(enabledCheck);
        root.Children.Add(UiKit.Body("提醒阈值", 13, UiKit.MutedBrush));
        root.Children.Add(thresholdCombo);
        root.Children.Add(resetCheck);

        if (onTestAlert is not null)
        {
            var testBtn = UiKit.SecondaryButton("发送一条测试托盘通知", () =>
                onTestAlert("Monitor 额度提醒测试", "系统托盘通知工作正常，点击通知可直达用量与额度页面。"), glyph: "bell");
            testBtn.HorizontalAlignment = HorizontalAlignment.Left;
            testBtn.Margin = new Thickness(0, 0, 0, 18);
            root.Children.Add(testBtn);
        }

        var actions = new DockPanel();
        actions.Children.Add(UiKit.SecondaryButton("取消", () => win.Close(), textBrush: UiKit.MutedBrush));
        var saveBtn = UiKit.PrimaryButton("保存", () =>
        {
            var pct = (thresholdCombo.SelectedItem as ComboBoxItem)?.Tag is int v ? v : 20;
            controller.SaveAlertSettings(new UsageAlertSettings
            {
                Enabled = enabledCheck.IsChecked == true,
                ThresholdPercent = pct,
                NotifyReset = resetCheck.IsChecked == true
            });
            win.Close();
        });
        DockPanel.SetDock(saveBtn, Dock.Right);
        saveBtn.HorizontalAlignment = HorizontalAlignment.Right;
        actions.Children.Add(saveBtn);
        root.Children.Add(actions);

        PresentModal(win, root);
    }

    public static void ShowProfileEditor(Window owner, MonitorController controller)
    {
        var user = controller.Account?.User;
        var currentAvatar = user?.Avatar ?? "";
        var win = CreateModal(owner, "个人资料", 400);
        var root = new StackPanel { Margin = new Thickness(26) };
        root.Children.Add(UiKit.Heading("个人资料", 21));

        var avatarHolder = new ContentControl
        {
            Content = UiKit.AvatarImage(currentAvatar, 80),
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 18, 0, 12)
        };
        root.Children.Add(avatarHolder);

        var avatarButtons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 0, 0, 18) };
        avatarButtons.Children.Add(UiKit.SecondaryButton("更换头像", () =>
        {
            var dlg = new OpenFileDialog { Filter = "图片文件 (*.png;*.jpg;*.jpeg)|*.png;*.jpg;*.jpeg" };
            if (dlg.ShowDialog(win) == true)
            {
                try
                {
                    var info = new FileInfo(dlg.FileName);
                    if (info.Length > 10 * 1024 * 1024)
                    {
                        ShowMessage(win, "图片过大", "请选择小于 10 MB 的 JPG 或 PNG 图片。");
                        return;
                    }
                    var bmp = new BitmapImage();
                    bmp.BeginInit();
                    bmp.UriSource = new Uri(dlg.FileName);
                    bmp.CacheOption = BitmapCacheOption.OnLoad;
                    bmp.EndInit();

                    // Center-crop to square and scale to 256×256 JPEG (matching Android NativeAvatar 256KB limit)
                    var edge = Math.Min(bmp.PixelWidth, bmp.PixelHeight);
                    if (edge < 1) return;
                    var cropped = new CroppedBitmap(
                        bmp,
                        new Int32Rect((bmp.PixelWidth - edge) / 2, (bmp.PixelHeight - edge) / 2, edge, edge));
                    var scale = 256.0 / edge;
                    var scaled = new TransformedBitmap(cropped, new ScaleTransform(scale, scale));
                    var encoder = new JpegBitmapEncoder { QualityLevel = 88 };
                    encoder.Frames.Add(BitmapFrame.Create(scaled));
                    using var ms = new MemoryStream();
                    encoder.Save(ms);
                    var bytes = ms.ToArray();
                    if (bytes.Length > 256 * 1024)
                    {
                        ShowMessage(win, "头像过大", "处理后的头像超过 256 KB 上限，请换一张图片。");
                        return;
                    }
                    currentAvatar = "data:image/jpeg;base64," + Convert.ToBase64String(bytes);
                    avatarHolder.Content = UiKit.AvatarImage(currentAvatar, 80);
                }
                catch
                {
                    ShowMessage(win, "无法读取图片", "这张图片已损坏或格式暂不支持，请重新选择。");
                }
            }
        }));
        var clearBtn = UiKit.SecondaryButton("移除头像", () =>
        {
            currentAvatar = "";
            avatarHolder.Content = UiKit.AvatarImage("", 80);
        }, textBrush: UiKit.MutedBrush);
        clearBtn.Margin = new Thickness(8, 0, 0, 0);
        avatarButtons.Children.Add(clearBtn);
        root.Children.Add(avatarButtons);

        root.Children.Add(UiKit.Body("昵称", 13, UiKit.MutedBrush));
        var nameBox = UiKit.StyledTextBox(user?.DisplayOrUsername ?? "");
        nameBox.Margin = new Thickness(0, 6, 0, 20);
        root.Children.Add(nameBox);

        var actions = new DockPanel();
        actions.Children.Add(UiKit.SecondaryButton("取消", () => win.Close(), textBrush: UiKit.MutedBrush));
        var saveBtn = UiKit.PrimaryButton("保存资料", async () =>
        {
            if (await controller.SaveProfileAsync(nameBox.Text, currentAvatar))
                win.Close();
        });
        DockPanel.SetDock(saveBtn, Dock.Right);
        saveBtn.HorizontalAlignment = HorizontalAlignment.Right;
        actions.Children.Add(saveBtn);
        root.Children.Add(actions);

        PresentModal(win, root);
    }

    public static void ShowAddComputerDialog(Window owner, MonitorController controller)
    {
        var win = CreateModal(owner, "添加其他电脑", 450);
        var root = new StackPanel { Margin = new Thickness(26) };
        root.Children.Add(UiKit.Heading("添加其他电脑", 21));
        var tip = UiKit.Body("推荐方式：在要连接的 Windows 或 Mac 电脑上打开 Monitor 桌面端，直接用手机 Monitor 右上角「扫一扫」即可一键登录并绑定采集器。", 13.5, UiKit.MutedBrush);
        tip.Margin = new Thickness(0, 10, 0, 16);
        root.Children.Add(tip);

        var activeCode = controller.ActivePairingCode;
        var codeDisplay = new TextBlock
        {
            Text = activeCode?.Code ?? "尚未生成",
            FontFamily = UiKit.MonoFont,
            FontSize = 26,
            FontWeight = FontWeights.SemiBold,
            Foreground = UiKit.TextBrush,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 8, 0, 8)
        };
        root.Children.Add(UiKit.Card(codeDisplay, 14, UiKit.PodRadius));

        string FormatCodeExpiry(ComputerPairingCodeDto? dto) =>
            dto is not null && MonitorDate.Parse(dto.ExpiresAt) is { } exp
                ? $"一次性配对码有效至 {exp.ToLocalTime():HH:mm}（已复制到剪贴板）"
                : "备用方式：在命令行连接器输入 12 位一次性配对码（10 分钟内有效）";

        var sub = UiKit.Body(FormatCodeExpiry(activeCode), 12.5, UiKit.MutedBrush);
        sub.HorizontalAlignment = HorizontalAlignment.Center;
        sub.Margin = new Thickness(0, 8, 0, 20);
        root.Children.Add(sub);

        var actions = new DockPanel();
        var genBtn = UiKit.SecondaryButton("生成并复制配对码", async () =>
        {
            var res = await controller.CreatePairingCodeAsync();
            if (res is not null)
            {
                codeDisplay.Text = res.Code;
                sub.Text = FormatCodeExpiry(res);
                Clipboard.SetText(res.Code);
            }
        });
        actions.Children.Add(genBtn);

        var doneBtn = UiKit.PrimaryButton("完成", () => win.Close());
        DockPanel.SetDock(doneBtn, Dock.Right);
        doneBtn.HorizontalAlignment = HorizontalAlignment.Right;
        actions.Children.Add(doneBtn);
        root.Children.Add(actions);

        PresentModal(win, root);
    }

    public static void ShowCompactWindowSettings(Window owner, MonitorController controller, Action onSettingsChanged)
    {
        var win = CreateModal(owner, "精简小窗设置", 448);
        var host = new ContentControl();

        void RebuildDialog()
        {
            var current = controller.ReadUiSettings();
            var root = new StackPanel { Margin = new Thickness(24, 22, 24, 24) };
            var head = UiKit.Heading("精简小窗定制", 20);
            head.Margin = new Thickness(0, 0, 28, 0);
            root.Children.Add(head);
            var desc = UiKit.Body("根据窗口宽高智能切换双栏看板、横向状态条或胶囊微窗（无滚动条），支持滚轮或 ↑/↓ 切换卡片。", 12.5, UiKit.MutedBrush);
            desc.Margin = new Thickness(0, 6, 0, 14);
            root.Children.Add(desc);

            root.Children.Add(UiKit.Body("窗口尺寸", 12.5, UiKit.MutedBrush));
            var sizeSeg = UiKit.SegmentedControl(
                new[]
                {
                    ("nano", "灵动胶囊"),
                    ("small", "紧凑方卡"),
                    ("medium", "标准小窗"),
                    ("large", "双栏看板")
                }.Select(opt =>
                {
                    var key = opt.Item1;
                    return (opt.Item2, current.CompactSizePreset == key, (Action)(() =>
                    {
                        var next = controller.ReadUiSettings();
                        next.CompactSizePreset = key;
                        controller.SaveUiSettings(next);
                        onSettingsChanged();
                        RebuildDialog();
                    }));
                }).ToList());
            sizeSeg.Margin = new Thickness(0, 6, 0, 14);
            root.Children.Add(sizeSeg);

            root.Children.Add(UiKit.Body("显示模式", 12.5, UiKit.MutedBrush));
            var modeSeg = UiKit.SegmentedControl(
                new[]
                {
                    ("overview", "智能总览"),
                    ("tasks", "仅活跃任务"),
                    ("quotas", "仅官方额度")
                }.Select(opt =>
                {
                    var key = opt.Item1;
                    return (opt.Item2, current.CompactContentMode == key, (Action)(() =>
                    {
                        var next = controller.ReadUiSettings();
                        next.CompactContentMode = key;
                        controller.SaveUiSettings(next);
                        onSettingsChanged();
                        RebuildDialog();
                    }));
                }).ToList());
            modeSeg.Margin = new Thickness(0, 6, 0, 14);
            root.Children.Add(modeSeg);

            var modLbl = UiKit.Body("小窗显示内容", 12.5, UiKit.MutedBrush);
            modLbl.Margin = new Thickness(0, 0, 0, 7);
            root.Children.Add(modLbl);

            void SaveToggle(Action<LocalUiSettings, bool> setter, bool v)
            {
                var next = controller.ReadUiSettings();
                setter(next, v);
                controller.SaveUiSettings(next);
                onSettingsChanged();
            }

            root.Children.Add(UiKit.SettingToggleRow("最低官方额度与重置倒计时", "顶部优先展示当前最紧迫的额度窗口", current.CompactShowLowestQuota, v => SaveToggle((s, val) => s.CompactShowLowestQuota = val, v)));
            root.Children.Add(UiKit.SettingToggleRow("多工具微缩速览条", "同排对比 Codex / Claude / AGY 读数", current.CompactShowToolStrip, v => SaveToggle((s, val) => s.CompactShowToolStrip = val, v)));
            root.Children.Add(UiKit.SettingToggleRow("今日本地 Token 总量", "显示今日各电脑累计本地记录", current.CompactShowTodayTokens, v => SaveToggle((s, val) => s.CompactShowTodayTokens = val, v)));
            root.Children.Add(UiKit.SettingToggleRow("任务行来源电脑与目录", "展开显示会话所在电脑、文件夹与耗时", current.CompactShowTaskMeta, v => SaveToggle((s, val) => s.CompactShowTaskMeta = val, v)));

            var doneBtn = UiKit.PrimaryButton("完成", () => win.Close());
            doneBtn.HorizontalAlignment = HorizontalAlignment.Right;
            doneBtn.Margin = new Thickness(0, 14, 0, 0);
            root.Children.Add(doneBtn);

            host.Content = root;
        }

        RebuildDialog();
        PresentModal(win, host);
    }

    public static void ShowPendingTasksDialog(Window owner, MonitorController controller, Action<MonitorTask> onSelectTask)
    {
        var pending = controller.PendingTasks;
        var win = CreateModal(owner, $"待处理 · {pending.Count}", 470);
        var root = new StackPanel { Margin = new Thickness(24, 22, 24, 24) };
        var head = UiKit.Heading($"待处理 · {pending.Count}", 20);
        head.Margin = new Thickness(0, 0, 28, 0);
        root.Children.Add(head);

        var hasActiveFilter = controller.Tool != ToolFilter.All
                              || controller.Scope != TaskScope.All
                              || !string.IsNullOrWhiteSpace(controller.SearchQuery);
        var descText = hasActiveFilter
            ? "汇总所有在线电脑上等你批准或回复选项的任务；直接打开并作答，不会修改你当前的筛选条件。"
            : "点击任意待处理任务，直接跳到对应的问题或单次批准。";
        var desc = UiKit.Body(descText, 12.5, UiKit.MutedBrush);
        desc.Margin = new Thickness(0, 6, 0, 14);
        root.Children.Add(desc);

        var listStack = new StackPanel();
        foreach (var task in pending)
        {
            var captured = task;
            var cardCol = new StackPanel();
            cardCol.Children.Add(UiKit.TaskIdentityBadge(task.Tool, task.DeviceName ?? "电脑", 15));
            var titleBlock = UiKit.Heading(task.Title, 14.5);
            titleBlock.Margin = new Thickness(0, 6, 0, 6);
            cardCol.Children.Add(titleBlock);

            var metaRow = new DockPanel();
            var kindText = task.StatusTitle;
            var badge = new Border
            {
                Background = UiKit.GlassTrackBrush,
                BorderBrush = UiKit.WarningBrush,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(5),
                Padding = new Thickness(8, 2, 8, 2),
                HorizontalAlignment = HorizontalAlignment.Left,
                Child = new TextBlock
                {
                    Text = kindText,
                    FontFamily = UiKit.AppFont,
                    FontSize = 11.5,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = UiKit.WarningBrush
                }
            };
            metaRow.Children.Add(badge);

            if (!string.IsNullOrEmpty(task.UpdatedAt))
            {
                var rel = UiKit.Body(UsageFormatting.RelativeTime(task.UpdatedAt), 11.5, UiKit.MutedBrush);
                DockPanel.SetDock(rel, Dock.Right);
                rel.HorizontalAlignment = HorizontalAlignment.Right;
                rel.VerticalAlignment = VerticalAlignment.Center;
                metaRow.Children.Add(rel);
            }
            cardCol.Children.Add(metaRow);

            var itemBorder = UiKit.Card(cardCol, 14, UiKit.PodRadius);
            itemBorder.BorderBrush = UiKit.WarningBrush;
            var btn = UiKit.BareButton(itemBorder, () =>
            {
                win.Close();
                onSelectTask(captured);
            });
            btn.HorizontalContentAlignment = HorizontalAlignment.Stretch;
            btn.Margin = new Thickness(0, 0, 0, 8);
            btn.MouseEnter += (_, _) => itemBorder.Background = UiKit.GlassCardHoverBrush;
            btn.MouseLeave += (_, _) => itemBorder.Background = UiKit.GlassCardBrush;
            listStack.Children.Add(btn);
        }

        var scroll = new ScrollViewer
        {
            MaxHeight = 380,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Content = listStack
        };
        root.Children.Add(scroll);

        PresentModal(win, root);
    }

    public static void ShowNewTaskDialog(
        Window owner,
        MonitorController controller,
        string? preferredDeviceId,
        Action<string> onTaskCreated)
    {
        var onlineComputers = controller.IsFresh
            ? controller.Devices.Where(d => d.Online).ToList()
            : [];

        if (onlineComputers.Count == 0)
        {
            ShowMessage(owner, "暂无在线电脑", "当前没有在线的已连接电脑。请确认目标电脑已开启同步，或点击刷新后再试。");
            return;
        }

        var win = CreateModal(owner, "新建远程任务", 478);
        var cts = new CancellationTokenSource();
        win.Closed += (_, _) =>
        {
            try { cts.Cancel(); cts.Dispose(); } catch { }
        };

        var root = new StackPanel { Margin = new Thickness(26, 24, 26, 24) };
        var head = UiKit.Heading("新建远程任务", 20.5);
        head.Margin = new Thickness(0, 0, 28, 0);
        root.Children.Add(head);

        var desc = UiKit.Body("无需提前在电脑打开终端窗口：选择在线电脑与历史活跃项目目录，发送首条指令即可启动全新会话。", 12.8, UiKit.MutedBrush);
        desc.Margin = new Thickness(0, 7, 0, 16);
        root.Children.Add(desc);

        root.Children.Add(UiKit.Body("目标电脑", 12.8, UiKit.MutedBrush));
        var devCombo = UiKit.StyledComboBox();
        devCombo.Margin = new Thickness(0, 6, 0, 13);
        var defaultDevIdx = 0;
        for (var i = 0; i < onlineComputers.Count; i++)
        {
            var dev = onlineComputers[i];
            var tag = dev.Local ? $"{dev.Name}（这台电脑）" : $"{dev.Name} · {dev.Platform}";
            UiKit.AddComboItem(devCombo, tag, dev.Id);
            if (!string.IsNullOrEmpty(preferredDeviceId) && dev.Id == preferredDeviceId)
                defaultDevIdx = i;
        }
        devCombo.SelectedIndex = defaultDevIdx;
        root.Children.Add(devCombo);

        root.Children.Add(UiKit.Body("项目目录（来自电脑端已验证的活跃工作区）", 12.8, UiKit.MutedBrush));
        var projCombo = UiKit.StyledComboBox();
        projCombo.Margin = new Thickness(0, 6, 0, 13);
        root.Children.Add(projCombo);

        root.Children.Add(UiKit.Body("编程工具", 12.8, UiKit.MutedBrush));
        var toolHost = new ContentControl { Margin = new Thickness(0, 6, 0, 13) };
        root.Children.Add(toolHost);

        var capabilityHint = UiKit.Body("", 12.2, UiKit.WarningBrush);
        capabilityHint.Margin = new Thickness(0, 0, 0, 12);
        capabilityHint.Visibility = Visibility.Collapsed;
        root.Children.Add(capabilityHint);

        root.Children.Add(UiKit.Body("首条任务指令", 12.8, UiKit.MutedBrush));
        var promptBox = UiKit.StyledTextBox("", "描述你想让它完成的任务（Ctrl + Enter 快捷发起）…", multiline: true);
        promptBox.MinHeight = 96;
        promptBox.MaxHeight = 180;
        promptBox.Margin = new Thickness(0, 6, 0, 12);
        root.Children.Add(promptBox);

        var statusText = UiKit.Body("", 12.5, UiKit.AccentBrush);
        statusText.Margin = new Thickness(0, 0, 0, 12);
        statusText.Visibility = Visibility.Collapsed;
        root.Children.Add(statusText);

        var selectedTool = "codex";
        var submitting = false;
        Button? startBtn = null;

        Computer SelectedComputer()
        {
            var id = (devCombo.SelectedItem as ComboBoxItem)?.Tag as string;
            return onlineComputers.FirstOrDefault(d => d.Id == id) ?? onlineComputers[0];
        }

        string SelectedProjectId() =>
            (projCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? "";

        void SyncStartButton()
        {
            if (startBtn is null) return;
            var comp = SelectedComputer();
            var tools = DevPreviewPolicy.SupportedToolsFor(comp);
            var hasProj = MonitorSecurity.Matches(SelectedProjectId(), "[0-9a-f]{24}");
            var hasPrompt = !string.IsNullOrWhiteSpace(promptBox.Text);
            var canSend = !submitting && tools.Contains(selectedTool) && hasProj && hasPrompt;
            UiKit.SetButtonState(startBtn, submitting ? "正在启动…" : "启动新任务", canSend, primary: true);
        }

        void RebuildToolChips(List<string> tools)
        {
            if (tools.Count == 0)
            {
                toolHost.Content = UiKit.Body("当前电脑暂未报告支持远程启动的工具", 12.5, UiKit.MutedBrush);
                return;
            }
            if (!tools.Contains(selectedTool))
                selectedTool = tools[0];

            var seg = UiKit.SegmentedControl(
                tools.Select(t =>
                {
                    var key = t;
                    return (MonitorTask.BrandFor(key), selectedTool == key, (Action)(() =>
                    {
                        if (submitting) return;
                        selectedTool = key;
                        RebuildToolChips(tools);
                        SyncStartButton();
                    }));
                }).ToList());
            toolHost.Content = seg;
        }

        void RebuildComputerContext()
        {
            var comp = SelectedComputer();
            var projects = DevPreviewPolicy.VerifiedProjectsFor(comp);
            var tools = DevPreviewPolicy.SupportedToolsFor(comp);

            projCombo.Items.Clear();
            if (projects.Count == 0)
            {
                UiKit.AddComboItem(projCombo, "暂无同步到的活跃项目目录", "");
                projCombo.SelectedIndex = 0;
                projCombo.IsEnabled = false;
            }
            else
            {
                foreach (var p in projects)
                {
                    var toolsBadge = p.Tools is { Count: > 0 } pt
                        ? " · " + string.Join("/", pt.Select(MonitorTask.BrandFor))
                        : "";
                    UiKit.AddComboItem(projCombo, p.Name + toolsBadge, p.Id);
                }
                projCombo.SelectedIndex = 0;
                projCombo.IsEnabled = !submitting;
            }

            if (projects.Count > 0 && projects[0].Tools is { Count: > 0 } preferredTools)
            {
                var firstSupported = preferredTools.FirstOrDefault(t => tools.Contains(t));
                if (!string.IsNullOrEmpty(firstSupported) && !tools.Contains(selectedTool))
                    selectedTool = firstSupported;
            }

            RebuildToolChips(tools);

            if (tools.Count == 0)
            {
                capabilityHint.Text = "这台电脑尚未开启「结果、文件与回复」同步模式，或未检测到可用的 Codex / Claude Code / Antigravity 命令程序。";
                capabilityHint.Visibility = Visibility.Visible;
            }
            else if (projects.Count == 0)
            {
                capabilityHint.Text = "这台电脑暂未同步到历史活跃项目目录；先在电脑端对应文件夹打开一次工具会话后即可在此选用。";
                capabilityHint.Visibility = Visibility.Visible;
            }
            else
            {
                capabilityHint.Visibility = Visibility.Collapsed;
            }

            SyncStartButton();
        }

        async Task SubmitAsync()
        {
            if (submitting) return;
            var comp = SelectedComputer();
            var tools = DevPreviewPolicy.SupportedToolsFor(comp);
            var projId = SelectedProjectId();
            var prompt = promptBox.Text;
            if (!tools.Contains(selectedTool) || !MonitorSecurity.Matches(projId, "[0-9a-f]{24}") || string.IsNullOrWhiteSpace(prompt))
                return;

            submitting = true;
            devCombo.IsEnabled = false;
            projCombo.IsEnabled = false;
            promptBox.IsReadOnly = true;
            statusText.Foreground = UiKit.AccentBrush;
            statusText.Text = "正在向电脑发送新建任务请求…";
            statusText.Visibility = Visibility.Visible;
            SyncStartButton();

            var (createdId, error) = await controller.StartNewTaskAsync(
                comp.Id,
                projId,
                selectedTool,
                prompt,
                msg =>
                {
                    statusText.Foreground = UiKit.AccentBrush;
                    statusText.Text = msg;
                    statusText.Visibility = Visibility.Visible;
                },
                cts.Token);

            if (cts.IsCancellationRequested) return;

            if (!string.IsNullOrWhiteSpace(createdId))
            {
                win.Close();
                onTaskCreated(createdId!);
                return;
            }

            submitting = false;
            devCombo.IsEnabled = true;
            projCombo.IsEnabled = projCombo.Items.Count > 0 && !string.IsNullOrEmpty(SelectedProjectId());
            promptBox.IsReadOnly = false;
            if (!string.IsNullOrWhiteSpace(error))
            {
                statusText.Foreground = UiKit.WarningBrush;
                statusText.Text = error!;
                statusText.Visibility = Visibility.Visible;
            }
            SyncStartButton();
        }

        devCombo.SelectionChanged += (_, _) => RebuildComputerContext();
        projCombo.SelectionChanged += (_, _) => SyncStartButton();
        promptBox.TextChanged += (_, _) => SyncStartButton();
        promptBox.KeyDown += async (_, e) =>
        {
            if (e.Key == Key.Enter && (Keyboard.Modifiers & ModifierKeys.Control) == ModifierKeys.Control)
            {
                e.Handled = true;
                await SubmitAsync();
            }
        };

        var actions = new DockPanel { Margin = new Thickness(0, 4, 0, 0) };
        actions.Children.Add(UiKit.SecondaryButton("取消", () => win.Close(), textBrush: UiKit.MutedBrush));
        startBtn = UiKit.PrimaryButton("启动新任务", async () => await SubmitAsync());
        DockPanel.SetDock(startBtn, Dock.Right);
        startBtn.HorizontalAlignment = HorizontalAlignment.Right;
        actions.Children.Add(startBtn);
        root.Children.Add(actions);

        RebuildComputerContext();
        PresentModal(win, root);
    }

    public static void OpenBundledMiSansLicense()
    {
        try
        {
            var path = Path.Combine(AppContext.BaseDirectory, "Resources", "MiSans-License.pdf");
            if (File.Exists(path))
            {
                Process.Start(new ProcessStartInfo { FileName = path, UseShellExecute = true });
                return;
            }
        }
        catch { }
        MonitorController.OpenBrowserUrl("https://github.com/Makabaka-zxh/agent-monitor/blob/main/licenses/MiSans-License.pdf");
    }
}
