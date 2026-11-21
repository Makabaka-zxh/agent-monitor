using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using MonitorApp.Core;

namespace MonitorApp.UI;

public static class Dialogs
{
    private static Window CreateModal(Window owner, string title, double width = 450)
    {
        var win = new Window
        {
            Owner = owner,
            Title = title,
            Width = width,
            SizeToContent = SizeToContent.Height,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            ResizeMode = ResizeMode.NoResize,
            Background = UiKit.SurfaceBrush,
            Foreground = UiKit.TextBrush,
            FontFamily = UiKit.AppFont
        };
        win.SourceInitialized += (_, _) => DwmInterop.ApplyWindowStyling(win, UiKit.IsDark);
        return win;
    }

    public static void ShowMessage(Window owner, string title, string message)
    {
        var win = CreateModal(owner, title, 430);
        var stack = new StackPanel { Margin = new Thickness(26) };
        stack.Children.Add(UiKit.Heading(title, 20));
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
        win.Content = stack;
        win.ShowDialog();
    }

    public static bool Confirm(Window owner, string title, string message, string confirmLabel, bool danger = false)
    {
        var accepted = false;
        var win = CreateModal(owner, title, 420);
        var stack = new StackPanel { Margin = new Thickness(26) };
        stack.Children.Add(UiKit.Heading(title, 20));
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

        win.Content = stack;
        win.ShowDialog();
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

        var modelCombo = new ComboBox { FontFamily = UiKit.AppFont, FontSize = 13.5, Padding = new Thickness(10, 7, 10, 7), Margin = new Thickness(0, 6, 0, 14) };
        modelCombo.Items.Add(new ComboBoxItem { Content = "继承电脑当前设置", Tag = "" });
        foreach (var opt in models)
        {
            modelCombo.Items.Add(new ComboBoxItem { Content = string.IsNullOrWhiteSpace(opt.Label) ? opt.Id : opt.Label, Tag = opt.Id });
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

        var effortCombo = new ComboBox { FontFamily = UiKit.AppFont, FontSize = 13.5, Padding = new Thickness(10, 7, 10, 7), Margin = new Thickness(0, 6, 0, 14) };
        var fastCheck = new CheckBox { Content = "下一轮启用 Codex Fast 快速层", Foreground = UiKit.TextBrush, IsChecked = detail.ModelOverrides.FastNext, Margin = new Thickness(0, 4, 0, 8), FontSize = 13 };
        var planCheck = new CheckBox { Content = "下一轮启用 Plan 规划模式", Foreground = UiKit.TextBrush, IsChecked = detail.ModelOverrides.PlanNext, Margin = new Thickness(0, 0, 0, 14), FontSize = 13 };

        void RebuildEfforts(string? preferredEffort)
        {
            effortCombo.Items.Clear();
            effortCombo.Items.Add(new ComboBoxItem { Content = "默认推理强度", Tag = "" });
            var chosenId = (modelCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? "";
            var opt = models.FirstOrDefault(m => m.Id == chosenId);
            if (opt?.Efforts is { Count: > 0 })
            {
                foreach (var eff in opt.Efforts)
                    effortCombo.Items.Add(new ComboBoxItem { Content = eff, Tag = eff });
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
            var supportsFast = opt?.ServiceTiers?.Contains("fast") == true;
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

        win.Content = root;
        win.ShowDialog();
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

        win.Content = root;
        win.ShowDialog();
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

        var thresholdCombo = new ComboBox { FontFamily = UiKit.AppFont, FontSize = 13.5, Padding = new Thickness(10, 6, 10, 6), Margin = new Thickness(0, 6, 0, 18) };
        foreach (var pct in new[] { 10, 15, 20, 30, 50 })
        {
            var item = new ComboBoxItem { Content = $"剩余低于 {pct}% 时提醒", Tag = pct };
            thresholdCombo.Items.Add(item);
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

        win.Content = root;
        win.ShowDialog();
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
                    if (info.Length <= 15 * 1024 * 1024)
                    {
                        var bmp = new BitmapImage();
                        bmp.BeginInit();
                        bmp.UriSource = new Uri(dlg.FileName);
                        bmp.DecodePixelWidth = 256;
                        bmp.CacheOption = BitmapCacheOption.OnLoad;
                        bmp.EndInit();
                        var encoder = new PngBitmapEncoder();
                        encoder.Frames.Add(BitmapFrame.Create(bmp));
                        using var ms = new MemoryStream();
                        encoder.Save(ms);
                        currentAvatar = "data:image/png;base64," + Convert.ToBase64String(ms.ToArray());
                        avatarHolder.Content = UiKit.AvatarImage(currentAvatar, 80);
                    }
                }
                catch { }
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

        win.Content = root;
        win.ShowDialog();
    }

    public static void ShowAddComputerDialog(Window owner, MonitorController controller)
    {
        var win = CreateModal(owner, "添加其他电脑", 450);
        var root = new StackPanel { Margin = new Thickness(26) };
        root.Children.Add(UiKit.Heading("添加其他电脑", 21));
        var tip = UiKit.Body("推荐方式：在要连接的 Windows 或 Mac 电脑上打开 Monitor 桌面端，直接用手机 Monitor 右上角「扫一扫」即可一键登录并绑定采集器。", 13.5, UiKit.MutedBrush);
        tip.Margin = new Thickness(0, 10, 0, 16);
        root.Children.Add(tip);

        var codeDisplay = new TextBlock
        {
            Text = controller.PairingCode?.Code ?? "尚未生成",
            FontFamily = UiKit.MonoFont,
            FontSize = 26,
            FontWeight = FontWeights.SemiBold,
            Foreground = UiKit.TextBrush,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 8, 0, 8)
        };
        root.Children.Add(UiKit.Card(codeDisplay, 14, 14));

        var sub = UiKit.Body("备用方式：在命令行连接器输入 12 位一次性配对码（10 分钟内有效）", 12.5, UiKit.MutedBrush);
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
                Clipboard.SetText(res.Code);
            }
        });
        actions.Children.Add(genBtn);

        var doneBtn = UiKit.PrimaryButton("完成", () => win.Close());
        DockPanel.SetDock(doneBtn, Dock.Right);
        doneBtn.HorizontalAlignment = HorizontalAlignment.Right;
        actions.Children.Add(doneBtn);
        root.Children.Add(actions);

        win.Content = root;
        win.ShowDialog();
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
