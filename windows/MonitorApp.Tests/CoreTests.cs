using System.Text.Json;
using MonitorApp.Core;
using MonitorApp.UI;
using Xunit;

namespace MonitorApp.Tests;

public class CoreTests
{
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
        Assert.Equal("Gemini 3.1 Pro", shares[0].DisplayName);
        Assert.Equal("Claude Opus 4.6", shares[1].DisplayName);
        Assert.Equal("其他模型", shares[2].DisplayName);
        Assert.DoesNotContain(shares, s => s.DisplayName.Contains("未识别"));
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

        UiKit.ApplyTheme(true);
        Assert.Equal("#FFAFC8DF", UiKit.AccentBrush.Color.ToString());
        UiKit.ApplyTheme(false);
    }
}
