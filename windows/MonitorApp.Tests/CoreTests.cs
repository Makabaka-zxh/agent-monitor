using System.Text.Json;
using MonitorApp.Core;
using MonitorApp.UI;
using Xunit;

namespace MonitorApp.Tests;

public class CoreTests
{
    [Fact]
    public void OpenResult_TracksNewRoundsAndPendingFilesUntilReady()
    {
        var cache = new TaskResultCache();
        var task = new MonitorTask { FinalResultId = "first" };
        cache.Observe(task, true);
        var firstRequest = cache.Generation;
        cache.Apply(firstRequest, new TaskResultDto { Available = true, ResultId = "first" });
        Assert.False(cache.NeedsRefresh);

        task.FinalResultId = "second";
        cache.Observe(task, true);
        Assert.Null(cache.Result);
        cache.Apply(firstRequest, new TaskResultDto { Available = true, ResultId = "first" });
        Assert.Null(cache.Result);
        cache.Apply(cache.Generation, new TaskResultDto { Available = true, ResultId = "first" });
        Assert.False(cache.Result!.Available);
        Assert.True(cache.NeedsRefresh);

        var pending = new TaskResultDto { Available = true, ResultId = "second", Files = [new() { Ready = false }] };
        cache.Apply(cache.Generation, pending);
        Assert.True(cache.NeedsRefresh);
        cache.Apply(cache.Generation, new TaskResultDto { Available = true, ResultId = "second", Files = [new() { Ready = true }] });
        task.UpdatedAt = "later heartbeat";
        cache.Observe(task, true);
        Assert.False(cache.NeedsRefresh);
        Assert.Equal("second", cache.Result!.ResultId);
    }

    [Fact]
    public void OpenResult_HandlesUnversionedTasksAndRevokedSync()
    {
        var cache = new TaskResultCache();
        var task = new MonitorTask { Status = "running" };
        cache.Observe(task, true);
        var response = new TaskResultDto { Available = true, ResultId = "legacy" };
        cache.Apply(cache.Generation, response);
        Assert.Same(response, cache.Result);

        task.Status = "completed";
        cache.Observe(task, true);
        Assert.True(cache.NeedsRefresh);
        var beforeRevocation = cache.Generation;
        cache.Observe(task, false);
        Assert.False(cache.NeedsRefresh);
        cache.Observe(task, true);
        cache.Apply(beforeRevocation, response);
        Assert.Null(cache.Result);
        Assert.True(cache.NeedsRefresh);
    }

    [Fact]
    public void ServerOrigin_NormalizesValidHttpsAndRejectsUnsafeUrls()
    {
        Assert.Equal("https://monitor.example.com", ServerOrigin.Normalize("https://Monitor.Example.com/"));
        Assert.Equal("https://monitor.example.com", ServerOrigin.Normalize("https://monitor.example.com:443"));
        Assert.Equal("https://monitor.example.com:8443", ServerOrigin.Normalize("https://monitor.example.com:8443/"));
        Assert.Equal("https://[2001:db8::1]:8443", ServerOrigin.Normalize("https://[2001:DB8::1]:8443"));

        Assert.Null(ServerOrigin.Normalize("http://monitor.example.com"));
        Assert.Null(ServerOrigin.Normalize("https://user:pass@monitor.example.com"));
        Assert.Null(ServerOrigin.Normalize("https://monitor.example.com/subpath"));
        Assert.Null(ServerOrigin.Normalize("https://monitor.example.com?a=1"));
        Assert.Null(ServerOrigin.Normalize("https://monitor.example.com#frag"));
    }

    [Fact]
    public void PkceChallenge_MatchesRfc7636Vector()
    {
        Assert.Equal(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            MonitorSecurity.Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));

        var verifier = MonitorSecurity.CreateVerifier();
        Assert.True(MonitorSecurity.Matches(verifier, "[A-Za-z0-9_-]{43}"));
    }

    [Fact]
    public void ModelShares_UsesOtherModelLabelNeverUnrecognized()
    {
        var day = new UsageDayDto
        {
            Day = "2026-11-20",
            Tokens = 1000,
            UnknownModelTokens = 250,
            Models =
            [
                new UsageDayModelDto { Model = "gemini-3.1-pro-high", Tokens = 500 },
                new UsageDayModelDto { Model = "claude-opus-4-6-thinking", Tokens = 250 }
            ]
        };

        var shares = UsageFormatting.ComputeModelShares(day);
        Assert.Equal(3, shares.Count);
        Assert.Equal("Gemini 3.1 Pro (High)", shares[0].DisplayName);
        Assert.Equal("Claude Opus 4.6 (Thinking)", shares[1].DisplayName);
        Assert.Equal("其他模型", shares[2].DisplayName);
        Assert.DoesNotContain(shares, s => s.DisplayName.Contains("未识别"));
    }

    [Theory]
    [InlineData("gpt-6.2-astra", "GPT-6.2 Astra")]
    [InlineData("gpt-6.1-sol", "GPT-6.1 Sol")]
    [InlineData("gpt-6-astra", "GPT-6 Astra")]
    [InlineData("gpt-6.2-sol", "GPT-6.2 Sol")]
    [InlineData("gpt-5.3-codex", "GPT-5.3-Codex")]
    [InlineData("gpt-5.3-codex-spark", "GPT-5.3-Codex-Spark")]
    [InlineData("gpt-oss-120b-high", "GPT-OSS 120B (High)")]
    [InlineData("claude-opus-4-7-high", "Claude Opus 4.7 (High)")]
    [InlineData("gemini-3.1-pro-high-agy", "Gemini 3.1 Pro (High)")]
    [InlineData("gpt-6.4-nova", "GPT-6.4 Nova")]
    public void FormatModelName_FormatsFamilyAndFutureSlugsWithoutRawLowercase(string raw, string expected)
    {
        Assert.Equal(expected, UsageFormatting.FormatModelName(raw));
    }

    [Fact]
    public void UnifiedQrPairingDtos_DeserializeSnakeCasePayloads()
    {
        var pollJson = """
        {
          "status": "approved",
          "reader_token": "nrd_ddddddddddddddddddddddddddddddddddddddddddd",
          "reader_expires_at": "2026-12-20T00:00:00Z",
          "reader_mode": "full_app",
          "collector_code": "AABBCCDDEEFF"
        }
        """;
        var poll = JsonSerializer.Deserialize<QrPairingPollDto>(pollJson);
        Assert.NotNull(poll);
        Assert.Equal("approved", poll!.Status);
        Assert.Equal("full_app", poll.ReaderMode);
        Assert.Equal("AABBCCDDEEFF", poll.CollectorCode);
    }

    [Fact]
    public void UsageAlertEvaluator_FiresOncePerWindowAndNotifiesOnReset()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);
        var state = new UsageAlertRecord();
        var settings = new UsageAlertSettings { Enabled = true, ThresholdPercent = 20, NotifyReset = true };
        var summary = new UsageSummaryDto
        {
            Providers =
            [
                new UsageProviderDto
                {
                    Tool = "antigravity",
                    Quotas =
                    [
                        new UsageQuotaDto
                        {
                            Key = "five_hour",
                            BucketId = "gemini",
                            BucketLabel = "Gemini · 5 小时额度",
                            RemainingPercent = 15,
                            WindowMinutes = 300,
                            ResetsAt = 1_800_003_600,
                            Stale = false
                        }
                    ]
                }
            ]
        };

        var first = UsageAlertEvaluator.Evaluate(summary, settings, state, now);
        Assert.Single(first);
        Assert.Contains("15%", first[0].Title);

        // Duplicate poll on same reset window does not re-notify
        Assert.Empty(UsageAlertEvaluator.Evaluate(summary, settings, state, now));

        // Window resets with fresh quota -> fires reset recovery notification
        summary.Providers[0].Quotas![0].RemainingPercent = 100;
        summary.Providers[0].Quotas![0].ResetsAt = 1_800_021_600;
        var afterReset = UsageAlertEvaluator.Evaluate(summary, settings, state, now);
        Assert.Single(afterReset);
        Assert.Contains("已重置", afterReset[0].Title);
    }

    [Fact]
    public void QrDrawing_ProducesValidVectorImage()
    {
        var img = QrDrawing.CreateImage("https://monitor.example.com/#/pairing-confirm/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        Assert.NotNull(img);
        Assert.Null(QrDrawing.CreateImage(""));
    }

    [Fact]
    public void FamilyFormatting_UsesChineseCompactUnitsAndBeijingCalendar()
    {
        Assert.Equal("9,876", UsageFormatting.CompactTokens(9876));
        Assert.Equal("12.8 万", UsageFormatting.CompactTokens(128_400));
        Assert.Equal("2 亿", UsageFormatting.CompactTokens(200_000_000));

        var friday = new DateOnly(2026, 11, 20);
        var monday = UsageCalendar.MondayOf(friday);
        Assert.Equal(new DateOnly(2026, 11, 16), monday);
        Assert.StartsWith("本周 · 11.16 – 11.22", UsageCalendar.WeekRangeLabel(monday, friday));
    }

    [Fact]
    public void FamilyTheme_MatchesMonitorNordicSlatePalette()
    {
        UiKit.ApplyTheme(false);
        Assert.Equal("#FF466987", UiKit.AccentBrush.Color.ToString());
        Assert.Equal("#FF96664F", UiKit.ClaudeInkBrush.Color.ToString());
        Assert.NotNull(UiKit.GlassChromeCapsuleBrush);
        Assert.NotNull(UiKit.GlassChromeCapsuleRimBrush);

        UiKit.ApplyTheme(true);
        Assert.Equal("#FFAFC8DF", UiKit.AccentBrush.Color.ToString());
        Assert.NotNull(UiKit.GlassChromeCapsuleBrush);
        Assert.NotNull(UiKit.GlassChromeCapsuleRimBrush);
        UiKit.ApplyTheme(false);
    }

    [Fact]
    public void TaskMatches_SupportsMultiKeywordSearchAcrossTitleProjectDeviceAndBrand()
    {
        var task = new MonitorTask
        {
            Id = "codex:1",
            Title = "重构双端扫码配对协议",
            Tool = "codex",
            Status = "running",
            DeviceName = "主力工作站",
            Project = @"C:\Projects\agent-monitor"
        };

        Assert.True(task.Matches(ToolFilter.All, TaskScope.Active, fresh: true, query: "扫码 工作站"));
        Assert.True(task.Matches(ToolFilter.All, TaskScope.Active, fresh: true, query: "codex agent-monitor"));
        Assert.False(task.Matches(ToolFilter.All, TaskScope.Active, fresh: true, query: "扫码 不存在的词"));
    }

    [Fact]
    public void SafeFileName_NeutralizesWindowsReservedDeviceNamesAndPathSeparators()
    {
        Assert.Equal("_CON.txt", MonitorSecurity.SafeFileName("CON.txt"));
        Assert.Equal("_NUL", MonitorSecurity.SafeFileName("NUL"));
        Assert.Equal("_LPT1.log", MonitorSecurity.SafeFileName("LPT1.log"));
        Assert.Equal("report_2026_final_.md", MonitorSecurity.SafeFileName("report/2026:final?.md"));
        Assert.Equal("附件", MonitorSecurity.SafeFileName("...   "));
    }

    [Fact]
    public void WallpaperService_ValidatesBingImageUrlsAndRejectsUntrustedHosts()
    {
        Assert.Equal(
            "https://www.bing.com/th?id=OHR.NordicFjord_ZH-CN1234567890_1920x1080.jpg",
            WallpaperService.NormalizeBingImageUrl("/th?id=OHR.NordicFjord_ZH-CN1234567890_1920x1080.jpg&rf=LaDigue_1920x1080.jpg&pid=hp"));

        Assert.Throws<InvalidOperationException>(() =>
            WallpaperService.NormalizeBingImageUrl("https://evil.example.com/th?id=OHR.Test_1920x1080.jpg"));
        Assert.Throws<InvalidOperationException>(() =>
            WallpaperService.NormalizeBingImageUrl("http://www.bing.com/th?id=OHR.Test_1920x1080.jpg"));

        Assert.Equal("https://www.bing.com/", WallpaperService.SafeBingLink("https://evil.example.com/phish"));
    }

    [Fact]
    public void CompactSizePresets_ReturnDistinctDimensions()
    {
        Assert.Equal((360, 216), new LocalUiSettings { CompactSizePreset = "nano" }.GetCompactDimensions());
        Assert.Equal((376, 332), new LocalUiSettings { CompactSizePreset = "small" }.GetCompactDimensions());
        Assert.Equal((412, 468), new LocalUiSettings { CompactSizePreset = "medium" }.GetCompactDimensions());
        Assert.Equal((640, 420), new LocalUiSettings { CompactSizePreset = "large" }.GetCompactDimensions());
    }

    [Fact]
    public void ResponsiveLayoutPolicy_AdaptsCompactAndMainLayoutsAcrossWindowSizes()
    {
        var microTiny = ResponsiveLayoutPolicy.PlanCompact(308, 185);
        Assert.Equal(CompactLayoutKind.MicroCapsule, microTiny.Kind);
        Assert.Equal(1, microTiny.Columns);
        Assert.Equal(1, microTiny.TasksPerPage);
        Assert.Equal(1, microTiny.QuotasPerPage);

        var micro = ResponsiveLayoutPolicy.PlanCompact(360, 216);
        Assert.Equal(CompactLayoutKind.MicroCapsule, micro.Kind);
        Assert.Equal(1, micro.Columns);
        Assert.Equal(2, micro.TasksPerPage);
        Assert.Equal(2, micro.QuotasPerPage);

        var ribbon = ResponsiveLayoutPolicy.PlanCompact(520, 216);
        Assert.Equal(CompactLayoutKind.WideRibbon, ribbon.Kind);
        Assert.Equal(2, ribbon.Columns);
        Assert.True(ribbon.TasksPerPage >= 2);

        var tile = ResponsiveLayoutPolicy.PlanCompact(376, 332);
        Assert.Equal(CompactLayoutKind.CompactTile, tile.Kind);
        Assert.Equal(1, tile.Columns);
        Assert.False(tile.ShowVisualToolTiles);

        var standard = ResponsiveLayoutPolicy.PlanCompact(412, 468);
        Assert.Equal(CompactLayoutKind.StandardStack, standard.Kind);
        Assert.Equal(1, standard.Columns);
        Assert.True(standard.ShowVisualToolTiles);
        Assert.True(standard.ShowTaskPreview);

        var bento = ResponsiveLayoutPolicy.PlanCompact(640, 420);
        Assert.Equal(CompactLayoutKind.WideBento, bento.Kind);
        Assert.Equal(2, bento.Columns);
        Assert.True(bento.TasksPerPage >= 4);
        Assert.True(bento.QuotasPerPage >= 4);

        var narrowMain = ResponsiveLayoutPolicy.PlanMainShell(800);
        Assert.True(narrowMain.CollapseSidebar);
        Assert.Equal(72.0, narrowMain.SidebarWidth);
        Assert.Equal(284.0, narrowMain.TaskListWidth);
        Assert.False(narrowMain.AccountTwoColumnBento);

        var ultraNarrowMain = ResponsiveLayoutPolicy.PlanMainShell(680);
        Assert.True(ultraNarrowMain.CollapseSidebar);
        Assert.Equal(1, ultraNarrowMain.UsageColumns);

        var defaultMain = ResponsiveLayoutPolicy.PlanMainShell(1180);
        Assert.False(defaultMain.CollapseSidebar);
        Assert.Equal(208.0, defaultMain.SidebarWidth);
        Assert.Equal(2, defaultMain.UsageColumns);
        Assert.Equal(2, defaultMain.DevicesColumns);

        var wideMain = ResponsiveLayoutPolicy.PlanMainShell(1440);
        Assert.False(wideMain.CollapseSidebar);
        Assert.Equal(3, wideMain.UsageColumns);
        Assert.Equal(2, wideMain.DevicesColumns);
        Assert.True(wideMain.AccountTwoColumnBento);
    }

    [Fact]
    public void PendingInteractionKind_SurfacesQuestionAndApprovalAcrossRunningOrIdleTasks()
    {
        var json = """
        {
          "id": "dev:claude:q1",
          "title": "确认数据库迁移方案",
          "tool": "claude",
          "status": "running",
          "pending_interaction_kind": "question",
          "archived": false,
          "stale": false
        }
        """;
        var questionTask = JsonSerializer.Deserialize<MonitorTask>(json)!;
        Assert.Equal("question", questionTask.EffectivePendingKind);
        Assert.Equal("waiting", questionTask.EffectiveStatus);
        Assert.Equal("等待回答", questionTask.StatusTitle);
        Assert.True(questionTask.Matches(ToolFilter.All, TaskScope.Active, fresh: true));

        var approvalTask = new MonitorTask { Status = "idle", PendingInteractionKind = "approval", Tool = "codex" };
        Assert.Equal("approval", approvalTask.EffectivePendingKind);
        Assert.Equal("waiting", approvalTask.EffectiveStatus);
        Assert.Equal("等待批准", approvalTask.StatusTitle);
        Assert.True(approvalTask.Matches(ToolFilter.All, TaskScope.Active, fresh: true));

        var legacyWaiting = new MonitorTask { Status = "waiting", Tool = "codex" };
        Assert.Equal("", legacyWaiting.EffectivePendingKind);
        Assert.Equal("waiting", legacyWaiting.EffectiveStatus);
        Assert.Equal("待处理", legacyWaiting.StatusTitle);
    }

    [Fact]
    public void SessionControlPolicy_ValidatesOptionChoicesAndCustomTextExclusivity()
    {
        var req = new SessionPendingRequestDto
        {
            Id = "req-1",
            Kind = "question",
            Questions =
            [
                new SessionQuestionDto
                {
                    Id = "q1",
                    Text = "选择部署环境",
                    Multiple = false,
                    AllowsText = true,
                    Options =
                    [
                        new SessionChoiceDto { Id = "staging", Label = "预发布环境" },
                        new SessionChoiceDto { Id = "prod", Label = "生产环境" }
                    ]
                },
                new SessionQuestionDto
                {
                    Id = "q2",
                    Text = "选择通知渠道",
                    Multiple = true,
                    AllowsText = false,
                    Options =
                    [
                        new SessionChoiceDto { Id = "slack", Label = "Slack" },
                        new SessionChoiceDto { Id = "email", Label = "Email" }
                    ]
                }
            ]
        };

        // Valid: single option for q1 + multi options for q2
        Assert.True(SessionControlPolicy.ValidAnswers(req,
        [
            new SessionAnswerPayloadDto { QuestionId = "q1", Choices = ["staging"], Text = null },
            new SessionAnswerPayloadDto { QuestionId = "q2", Choices = ["slack", "email"], Text = null }
        ]));

        // Valid: custom text for q1 (allows_text=true, choices empty) + single option for q2
        Assert.True(SessionControlPolicy.ValidAnswers(req,
        [
            new SessionAnswerPayloadDto { QuestionId = "q1", Choices = [], Text = "canary-eu" },
            new SessionAnswerPayloadDto { QuestionId = "q2", Choices = ["slack"], Text = null }
        ]));

        // Invalid: both choice and custom text on q1
        Assert.False(SessionControlPolicy.ValidAnswers(req,
        [
            new SessionAnswerPayloadDto { QuestionId = "q1", Choices = ["staging"], Text = "canary-eu" },
            new SessionAnswerPayloadDto { QuestionId = "q2", Choices = ["slack"], Text = null }
        ]));

        // Invalid: multiple choices on single-select q1
        Assert.False(SessionControlPolicy.ValidAnswers(req,
        [
            new SessionAnswerPayloadDto { QuestionId = "q1", Choices = ["staging", "prod"], Text = null },
            new SessionAnswerPayloadDto { QuestionId = "q2", Choices = ["slack"], Text = null }
        ]));

        // Invalid: custom text on q2 where allows_text=false
        Assert.False(SessionControlPolicy.ValidAnswers(req,
        [
            new SessionAnswerPayloadDto { QuestionId = "q1", Choices = ["staging"], Text = null },
            new SessionAnswerPayloadDto { QuestionId = "q2", Choices = [], Text = "webhook" }
        ]));
    }

    [Fact]
    public void WallpaperSteplessSettings_SupportPresetsClampingAndCustomSliderValues()
    {
        var ui = new LocalUiSettings();
        Assert.Equal("balanced", ui.MatchingWallpaperPreset());
        Assert.Equal(0.48, ui.GetEffectiveWallpaperOpacity(), 3);
        Assert.Equal(10.0, ui.GetEffectiveWallpaperBlur(), 3);

        ui.ApplyWallpaperPreset("vivid");
        Assert.Equal("vivid", ui.MatchingWallpaperPreset());
        Assert.Equal(0.88, ui.GetEffectiveWallpaperOpacity(), 3);
        Assert.Equal(0.0, ui.GetEffectiveWallpaperBlur(), 3);

        ui.WallpaperOpacity = 0.61;
        ui.WallpaperBlur = 14.5;
        Assert.Equal("custom", ui.MatchingWallpaperPreset());

        ui.WallpaperOpacity = 5.0;
        ui.WallpaperBlur = -8.0;
        Assert.Equal(0.95, ui.GetEffectiveWallpaperOpacity(), 3);
        Assert.Equal(0.0, ui.GetEffectiveWallpaperBlur(), 3);

        var lowOpacityVeil = UiKit.ComputeWallpaperVeilAlpha(0.10, dark: false);
        var midOpacityVeil = UiKit.ComputeWallpaperVeilAlpha(0.50, dark: false);
        var highOpacityVeil = UiKit.ComputeWallpaperVeilAlpha(0.90, dark: false);
        Assert.True(lowOpacityVeil > midOpacityVeil);
        Assert.True(midOpacityVeil > highOpacityVeil);
    }

    [Fact]
    public void WorkbenchVisibilityPolicy_FindsCrossFilterPendingTasksAndExplainsHiddenFilters()
    {
        var devices = new List<Computer>
        {
            new() { Id = "pc-1", Name = "Main PC", Online = true },
            new() { Id = "pc-2", Name = "Mac Studio", Online = true },
            new() { Id = "pc-offline", Name = "Offline Laptop", Online = false }
        };
        var tasks = new List<MonitorTask>
        {
            new() { Id = "codex-done", Title = "Codex Done", Tool = "codex", Status = "completed", DeviceId = "pc-1" },
            new() { Id = "claude-q", Title = "Claude Question", Tool = "claude", Status = "completed", PendingInteractionKind = "question", DeviceId = "pc-2" },
            new() { Id = "ag-a", Title = "AGY Approval", Tool = "antigravity", Status = "waiting", PendingInteractionKind = "approval", DeviceId = "pc-2" },
            new() { Id = "offline-q", Title = "Offline Question", Tool = "claude", Status = "waiting", PendingInteractionKind = "question", DeviceId = "pc-offline" }
        };

        var pending = WorkbenchVisibilityPolicy.PendingTasks(tasks, devices, fresh: true);
        Assert.Equal(2, pending.Count);
        Assert.Equal("claude-q", pending[0].Id);
        Assert.Equal("ag-a", pending[1].Id);

        // Single-computer hidden filter message
        var onePc = new List<Computer> { new() { Id = "pc-1", Name = "Main PC", Online = true } };
        var sixClaude = Enumerable.Range(1, 6)
            .Select(i => new MonitorTask { Id = $"c{i}", Title = $"Task {i}", Tool = "claude", Status = "completed", DeviceId = "pc-1" })
            .ToList();
        var hiddenSingle = WorkbenchVisibilityPolicy.HiddenFilterSummary(
            sixClaude, onePc, ToolFilter.Codex, TaskScope.Active, "", fresh: true, visibleCount: 0);
        Assert.True(hiddenSingle.ShowResetAction);
        Assert.Equal(6, hiddenSingle.HiddenCount);
        Assert.Equal("这台电脑有 6 条任务，当前筛选隐藏了 6 条", hiddenSingle.Message);

        // Multi-computer hidden filter message
        sixClaude[5].DeviceId = "pc-2";
        var hiddenMulti = WorkbenchVisibilityPolicy.HiddenFilterSummary(
            sixClaude, devices, ToolFilter.Codex, TaskScope.All, "", fresh: true, visibleCount: 0);
        Assert.Equal("已连接电脑共有 6 条任务，当前筛选隐藏了 6 条", hiddenMulti.Message);
    }

    [Fact]
    public void ReceiptsAndContinuationLabels_ReflectDeliveryAdoptionDesktopHandlingAndAppliedSettings()
    {
        var req = new SessionPendingRequestDto { Id = "r1", State = "submitted", Delivered = false };
        Assert.Equal("已发送 → 等待电脑接收", SessionControlPolicy.ReceiptTrail(req, sendingNow: false, hasLocalRecord: false));

        req.Delivered = true;
        Assert.Equal("已发送 → 电脑已收到", SessionControlPolicy.ReceiptTrail(req, sendingNow: false, hasLocalRecord: false));

        req.State = "acknowledged";
        req.Outcome = "adopted";
        Assert.Equal("已发送 → 电脑已收到 → 已采用", SessionControlPolicy.ReceiptTrail(req, sendingNow: false, hasLocalRecord: false));

        req.Outcome = "rejected";
        Assert.Equal("已发送 → 电脑已收到 → 已拒绝", SessionControlPolicy.ReceiptTrail(req, sendingNow: false, hasLocalRecord: false));

        req.State = "cancelled";
        req.Outcome = "handled_on_desktop";
        Assert.Equal("已在电脑端处理", SessionControlPolicy.ReceiptTrail(req, sendingNow: false, hasLocalRecord: false));

        var applied = new AppliedSettingsDto
        {
            Model = "gpt-5.4",
            Effort = "high",
            ServiceTier = "priority",
            CollaborationMode = "plan"
        };
        Assert.Equal(
            "本轮实际使用：GPT-5.4 · 思考强度 高 · 快速模式 · 计划模式",
            SessionControlPolicy.FormatSettingsReceipt(applied, null));

        var task = new MonitorTask { Id = "t1", Tool = "codex", Status = "running", ContinuationState = "computer_in_use" };
        Assert.Equal("电脑正在使用", MonitorTask.ContinuationTitleFor(task.EffectiveContinuation(fresh: true, deviceOnline: true)));
        task.ContinuationState = "continuable";
        Assert.Equal("可在手机继续", MonitorTask.ContinuationTitleFor(task.EffectiveContinuation(fresh: true, deviceOnline: true)));
        task.ContinuationState = "view_only";
        Assert.Equal("仅可查看", MonitorTask.ContinuationTitleFor(task.EffectiveContinuation(fresh: true, deviceOnline: true)));
    }

    [Fact]
    public void SyncDiagnostics_FormatsLegsAndDetectsSlowLeg()
    {
        var t0 = DateTimeOffset.Parse("2026-11-22T15:00:01.100Z");
        var t1 = DateTimeOffset.Parse("2026-11-22T15:00:01.350Z");
        var t2 = DateTimeOffset.Parse("2026-11-22T15:00:05.600Z");
        var sample = new SyncDiagnosticsSample
        {
            FirstScreenMs = 4250,
            LastRefreshMs = 4250,
            ComputerCompletedAt = t0,
            ServerReceivedAt = t1,
            ClientDisplayedAt = t2
        };
        Assert.Equal("250 ms", WorkbenchVisibilityPolicy.FormatLeg(sample.ComputerCompletedAt, sample.ServerReceivedAt));
        Assert.Equal("4.25 秒 (4250 ms)", WorkbenchVisibilityPolicy.FormatLeg(sample.ServerReceivedAt, sample.ClientDisplayedAt));
        Assert.Contains("服务收到 → 本机显示", WorkbenchVisibilityPolicy.Diagnosis(sample));
    }

    [Fact]
    public void AmbientStage_DoesNotInflateHeightWhenWallpaperIsTallUnderSizeToContent()
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var tallWallpaper = new System.Windows.Media.Imaging.WriteableBitmap(
                    1080, 2400, 96, 96, System.Windows.Media.PixelFormats.Bgra32, null);
                tallWallpaper.Freeze();

                UiKit.ApplyTheme(false, backgroundMode: "custom", wallpaperStyle: "balanced", wallpaper: tallWallpaper);
                var foreground = new System.Windows.Controls.Border { Width = 448, Height = 360 };
                var stage = UiKit.AmbientStage(foreground, compact: true);
                stage.Measure(new System.Windows.Size(448, double.PositiveInfinity));

                Assert.Equal(360.0, stage.DesiredSize.Height, 1);
                UiKit.ApplyTheme(false);
            }
            catch (Exception ex)
            {
                failure = ex;
            }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        thread.Join();

        if (failure is not null)
            throw new AggregateException(failure);
    }
}
