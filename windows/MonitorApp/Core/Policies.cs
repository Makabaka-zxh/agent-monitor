using System.Globalization;
using System.Text;

namespace MonitorApp.Core;

public static class UsageFormatting
{
    public const string OtherModelLabel = "其他模型";

    public static string CompactTokens(long? value)
    {
        if (value is null or < 0) return "—";
        var n = value.Value;
        if (n >= 1_000_000_000) return $"{n / 1_000_000_000.0:0.##}B";
        if (n >= 1_000_000) return $"{n / 1_000_000.0:0.##}M";
        if (n >= 1_000) return $"{n / 1_000.0:0.#}K";
        return n.ToString("N0", CultureInfo.InvariantCulture);
    }

    public static string ExactTokens(long? value) =>
        value is null or < 0 ? "未提供" : value.Value.ToString("N0", CultureInfo.InvariantCulture);

    public static string Percentage(double? value)
    {
        if (value is null || double.IsNaN(value.Value) || double.IsInfinity(value.Value)) return "—";
        var clamped = Math.Clamp(value.Value, 0.0, 100.0);
        var rounded = Math.Round(clamped, 1);
        return Math.Abs(rounded - Math.Round(rounded)) < 0.05
            ? $"{Math.Round(rounded):0}%"
            : $"{rounded:0.0}%";
    }

    public static string WindowTitle(long? minutes, string? fallback)
    {
        if (minutes is > 0)
        {
            var m = minutes.Value;
            if (m == 300) return "5 小时额度";
            if (m == 10080) return "每周额度";
            if (m == 1440) return "每日额度";
            if (m % 1440 == 0) return $"{m / 1440} 天额度";
            if (m % 60 == 0) return $"{m / 60} 小时额度";
            return $"{m} 分钟额度";
        }
        return string.IsNullOrWhiteSpace(fallback) ? "官方额度" : fallback!.Trim();
    }

    public static string QuotaDisplayTitle(UsageQuotaDto quota)
    {
        var window = WindowTitle(quota.WindowMinutes, quota.Label);
        var rawTarget = !string.IsNullOrWhiteSpace(quota.BucketLabel) ? quota.BucketLabel!.Trim() : (quota.Model?.Trim() ?? "");
        if (string.IsNullOrEmpty(rawTarget)) return window;
        var clean = FormatModelName(rawTarget);
        if (clean.Contains("额度")) return clean;
        return $"{clean} · {window}";
    }

    public static string ResetText(UsageQuotaDto quota, DateTimeOffset now)
    {
        if (quota.ResetDate is not { } reset) return "重置时间待定";
        var diff = reset - now;
        if (diff <= TimeSpan.Zero) return "即将刷新";
        if (diff.TotalDays >= 1.0)
        {
            var days = (int)diff.TotalDays;
            var hours = diff.Hours;
            return hours > 0 ? $"约 {days} 天 {hours} 小时后重置" : $"约 {days} 天后重置";
        }
        if (diff.TotalHours >= 1.0)
        {
            var hours = (int)diff.TotalHours;
            var mins = diff.Minutes;
            return mins > 0 ? $"约 {hours} 小时 {mins} 分钟后重置" : $"约 {hours} 小时后重置";
        }
        var m = Math.Max(1, (int)Math.Ceiling(diff.TotalMinutes));
        return $"约 {m} 分钟后重置";
    }

    public static string FormatModelName(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return OtherModelLabel;
        var trimmed = raw.Trim();
        var lower = trimmed.ToLowerInvariant();
        if (lower is "unknown" or "other" or "unrecognized" or "未识别模型" or "其他模型")
            return OtherModelLabel;
        if (trimmed.Contains(" · ") || trimmed.Contains(" / "))
            return trimmed;
        if (lower.StartsWith("claude-opus-4-7")) return "Claude Opus 4.7";
        if (lower.StartsWith("claude-opus-4-6")) return "Claude Opus 4.6";
        if (lower.StartsWith("claude-sonnet-4-6")) return "Claude Sonnet 4.6";
        if (lower.StartsWith("claude-sonnet-4-5")) return "Claude Sonnet 4.5";
        if (lower.StartsWith("claude-haiku-4-5")) return "Claude Haiku 4.5";
        if (lower.StartsWith("gemini-3.1-pro")) return "Gemini 3.1 Pro";
        if (lower.StartsWith("gemini-3-flash")) return "Gemini 3 Flash";
        if (lower.StartsWith("gemini-2.5-pro")) return "Gemini 2.5 Pro";
        if (lower.StartsWith("gemini-2.5-flash")) return "Gemini 2.5 Flash";
        if (lower.StartsWith("gpt-5.4")) return "GPT-5.4";
        if (lower.StartsWith("gpt-5.3-codex")) return "GPT-5.3 Codex";
        if (lower.StartsWith("gpt-5.2-codex")) return "GPT-5.2 Codex";
        if (lower.StartsWith("gpt-5")) return trimmed.ToUpperInvariant().Replace("CODEX", "Codex");
        return trimmed;
    }

    public sealed record ModelShareItem(string Key, string DisplayName, long Tokens, double Percent);

    public static List<ModelShareItem> ComputeModelShares(UsageDayDto? day)
    {
        if (day?.Tokens is not > 0) return [];
        var total = day.Tokens.Value;
        var unknown = day.UnknownModelTokens ?? 0;
        if (unknown < 0 || day.Models is null || day.Models.Count == 0 || day.Models.Count > 100)
            return [];

        var seen = new HashSet<string>(StringComparer.Ordinal);
        long sum = unknown;
        var rows = new List<(string Raw, string Name, long Tokens)>();
        foreach (var item in day.Models)
        {
            if (string.IsNullOrWhiteSpace(item.Model) || item.Model != item.Model.Trim() || item.Model.Length > 120)
                return [];
            if (!seen.Add(item.Model) || item.Tokens is null or < 0)
                return [];
            sum += item.Tokens.Value;
            rows.Add((item.Model, FormatModelName(item.Model), item.Tokens.Value));
        }
        if (sum != total) return [];

        var sorted = rows.OrderByDescending(r => r.Tokens).ToList();
        var nameCounts = sorted.GroupBy(r => r.Name).ToDictionary(g => g.Key, g => g.Count());
        var result = new List<ModelShareItem>(sorted.Count + 1);
        foreach (var row in sorted)
        {
            var display = nameCounts[row.Name] > 1 ? $"{row.Name} ({row.Raw})" : row.Name;
            result.Add(new ModelShareItem(row.Raw, display, row.Tokens, row.Tokens * 100.0 / total));
        }
        if (unknown > 0)
        {
            result.Add(new ModelShareItem("__other__", OtherModelLabel, unknown, unknown * 100.0 / total));
        }
        return result;
    }
}

public static class SessionControlPolicy
{
    public static string AttentionFingerprint(SessionPendingRequestDto req)
    {
        var sb = new StringBuilder();
        void Add(string? s)
        {
            var v = s ?? "";
            sb.Append(Encoding.UTF8.GetByteCount(v)).Append(':').Append(v).Append('|');
        }
        Add(req.Id);
        Add(req.Revision);
        Add(req.TurnId);
        Add(req.Provider);
        Add(req.Kind);
        Add(req.Title);
        Add(req.Detail);
        Add(req.Operation);
        if (req.Questions is { } qs)
        {
            sb.Append("q:").Append(qs.Count).Append('|');
            foreach (var q in qs)
            {
                Add(q.Id);
                Add(q.Text);
                sb.Append(q.Multiple == true ? '1' : '0').Append(q.AllowsText == true ? '1' : '0').Append('|');
                if (q.Options is { } opts)
                {
                    sb.Append("o:").Append(opts.Count).Append('|');
                    foreach (var o in opts)
                    {
                        Add(o.Id);
                        Add(o.Label);
                    }
                }
            }
        }
        return MonitorSecurity.Sha256Hex(sb.ToString());
    }

    public static bool IsCatalogUsable(SessionModelCatalogDto? catalog, DateTimeOffset now)
    {
        if (catalog is null || string.IsNullOrWhiteSpace(catalog.CatalogRevision)) return false;
        if (catalog.Models is not { Count: > 0 }) return false;
        if (catalog.ValidUntil is { } until && until <= now.ToUnixTimeSeconds()) return false;
        return true;
    }
}

public sealed class UsageAlertSettings
{
    public bool Enabled { get; set; } = true;
    public int ThresholdPercent { get; set; } = 20;
    public bool NotifyReset { get; set; } = true;
}

public sealed class UsageAlertRecord
{
    public Dictionary<string, long> LowNotifiedResets { get; set; } = new();
    public Dictionary<string, long> KnownActiveResets { get; set; } = new();
}

public static class UsageAlertEvaluator
{
    public sealed record AlertNotification(string Title, string Body);

    public static List<AlertNotification> Evaluate(
        UsageSummaryDto summary,
        UsageAlertSettings settings,
        UsageAlertRecord state,
        DateTimeOffset now)
    {
        var notifications = new List<AlertNotification>();
        if (!settings.Enabled || summary.Providers is null) return notifications;

        foreach (var provider in summary.Providers)
        {
            if (provider.Quotas is null) continue;
            foreach (var quota in provider.Quotas)
            {
                if (quota.Stale || quota.IsExpired(now) || quota.Remaining is not { } rem)
                    continue;
                var identity = $"{provider.Tool}:{quota.SourceDeviceId}:{quota.BucketId}:{quota.Key}:{quota.WindowMinutes}";
                var currentReset = quota.ResetsAt ?? 0;

                if (settings.NotifyReset
                    && state.KnownActiveResets.TryGetValue(identity, out var prevReset)
                    && prevReset > 0
                    && currentReset > prevReset + 60
                    && state.LowNotifiedResets.ContainsKey(identity))
                {
                    state.LowNotifiedResets.Remove(identity);
                    notifications.Add(new AlertNotification(
                        $"{provider.Brand} 额度已重置",
                        $"{UsageFormatting.QuotaDisplayTitle(quota)} 已恢复至 {UsageFormatting.Percentage(rem)}"));
                }

                if (currentReset > 0)
                {
                    state.KnownActiveResets[identity] = currentReset;
                }

                if (rem <= settings.ThresholdPercent)
                {
                    if (!state.LowNotifiedResets.TryGetValue(identity, out var notifiedFor) || notifiedFor != currentReset)
                    {
                        state.LowNotifiedResets[identity] = currentReset;
                        notifications.Add(new AlertNotification(
                            $"{provider.Brand} 额度剩余 {UsageFormatting.Percentage(rem)}",
                            $"{UsageFormatting.QuotaDisplayTitle(quota)} · {UsageFormatting.ResetText(quota, now)}"));
                    }
                }
            }
        }
        return notifications;
    }
}
