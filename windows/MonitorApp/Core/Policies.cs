using System.Globalization;
using System.Text;

namespace MonitorApp.Core;

public sealed record UsageSourceGroup(string Id, string Name, List<UsageQuotaDto> Quotas);

public static class UsageCalendar
{
    private static readonly TimeSpan BeijingOffset = TimeSpan.FromHours(8);

    public static DateOnly TodayBeijing(DateTimeOffset? now = null)
    {
        var bj = (now ?? DateTimeOffset.UtcNow).ToOffset(BeijingOffset);
        return DateOnly.FromDateTime(bj.DateTime);
    }

    public static DateOnly? ParseDay(string? text)
    {
        if (string.IsNullOrWhiteSpace(text) || text.Length != 10) return null;
        return DateOnly.TryParseExact(text, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var d) ? d : null;
    }

    public static string FormatDay(DateOnly d) => d.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
    public static string ShortDay(DateOnly d) => $"{d.Month}.{d.Day}";

    public static DateOnly MondayOf(DateOnly d)
    {
        var diff = ((int)d.DayOfWeek + 6) % 7;
        return d.AddDays(-diff);
    }

    public static DateOnly ClampWeek(DateOnly? selectedWeek, DateOnly firstDay, DateOnly today)
    {
        var minMonday = MondayOf(firstDay);
        var maxMonday = MondayOf(today);
        var candidate = MondayOf(selectedWeek ?? today);
        if (candidate < minMonday) return minMonday;
        if (candidate > maxMonday) return maxMonday;
        return candidate;
    }

    public static DateOnly ClampDayInWeek(DateOnly? selectedDay, DateOnly weekMonday, DateOnly firstDay, DateOnly today)
    {
        var lower = weekMonday > firstDay ? weekMonday : firstDay;
        var sunday = weekMonday.AddDays(6);
        var upper = sunday < today ? sunday : today;
        if (selectedDay is { } s && s >= lower && s <= upper) return s;
        return upper;
    }

    public static string WeekRangeLabel(DateOnly weekMonday, DateOnly today)
    {
        var prefix = weekMonday == MondayOf(today) ? "本周 · " : "";
        return $"{prefix}{ShortDay(weekMonday)} – {ShortDay(weekMonday.AddDays(6))}";
    }
}

public static class UsageFormatting
{
    public const string OtherModelLabel = "其他模型";

    public static string CompactTokens(long? value)
    {
        if (value is null or < 0) return "—";
        var n = value.Value;
        if (n < 10_000) return ExactTokens(n);
        if (n < 100_000_000)
        {
            var whole = n / 10_000;
            var tenth = (n % 10_000) / 1_000;
            return tenth == 0 ? $"{whole} 万" : $"{whole}.{tenth} 万";
        }
        else
        {
            var whole = n / 100_000_000;
            var tenth = (n % 100_000_000) / 10_000_000;
            return tenth == 0 ? $"{whole} 亿" : $"{whole}.{tenth} 亿";
        }
    }

    public static string ExactTokens(long? value) =>
        value is null or < 0 ? "—" : value.Value.ToString("N0", CultureInfo.InvariantCulture);

    public static string ExactOrMissing(long? value) =>
        value is null or < 0 ? "未提供" : value.Value.ToString("N0", CultureInfo.InvariantCulture);

    public static string Percentage(double? value)
    {
        if (value is null || double.IsNaN(value.Value) || double.IsInfinity(value.Value)) return "未提供";
        var clamped = Math.Clamp(value.Value, 0.0, 100.0);
        var rounded = Math.Round(clamped, 1);
        return Math.Abs(rounded - Math.Round(rounded)) < 0.05
            ? $"{Math.Round(rounded):0}%"
            : $"{rounded:0.0}%";
    }

    public static string RelativeTime(string? isoText, DateTimeOffset? now = null)
    {
        if (MonitorDate.Parse(isoText) is not { } stamp) return "尚无记录";
        var current = now ?? DateTimeOffset.UtcNow;
        var age = current - stamp;
        if (age < TimeSpan.FromMinutes(1)) return "刚刚";
        if (age < TimeSpan.FromHours(1)) return $"{Math.Max(1, (int)age.TotalMinutes)} 分钟前";
        if (age < TimeSpan.FromHours(24)) return $"{Math.Max(1, (int)age.TotalHours)} 小时前";
        var local = stamp.ToLocalTime();
        return $"{local.Month}/{local.Day} {local:HH:mm}";
    }

    public static string WindowTitle(long? minutes, string? fallback)
    {
        if (minutes is > 0)
        {
            var m = minutes.Value;
            if (m % 10080 == 0) return m == 10080 ? "每周额度" : $"{m / 10080} 周额度";
            if (m % 1440 == 0) return m == 1440 ? "每日额度" : $"{m / 1440} 天额度";
            if (m % 60 == 0) return $"{m / 60} 小时额度";
            return $"{m} 分钟额度";
        }
        return string.IsNullOrWhiteSpace(fallback) ? "套餐额度" : fallback!.Trim();
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

    public static string ResetText(UsageQuotaDto quota, DateTimeOffset now, bool stale = false)
    {
        if (quota.IsExpired(now)) return "已到重置时间，等待新额度";
        if (quota.ResetDate is not { } reset) return (stale || quota.Stale) ? "额度信息待更新" : "未提供重置时间";
        var minutes = Math.Max(1, (int)Math.Ceiling((reset - now).TotalMinutes));
        var duration = minutes >= 1440
            ? $"{minutes / 1440} 天 {(minutes % 1440) / 60} 小时"
            : minutes >= 60
            ? $"{minutes / 60} 小时 {minutes % 60} 分钟"
            : $"{minutes} 分钟";
        var isOld = stale || quota.Stale;
        return (isOld ? "预计 " : "") + duration + "后重置" + (isOld ? " · 待更新" : "");
    }

    public static string QuotaCheckText(string? state) => state switch
    {
        "waiting" => "等待后台读取",
        "updated" => "最近一次读取成功",
        "no_live_data" => "服务方暂未返回新额度",
        "sign_in_required" or "unauthenticated" => "需要在来源电脑登录对应账号",
        "update_required" => "来源电脑的额度读取组件需要更新",
        _ => "暂时无法读取新额度"
    };

    public static List<UsageSourceGroup> VisibleSources(UsageProviderDto? provider, DateTimeOffset now)
    {
        if (provider?.Quotas is not { Count: > 0 } quotas) return [];
        var map = new Dictionary<string, UsageSourceGroup>(StringComparer.Ordinal);
        var order = new List<string>();
        for (var i = 0; i < quotas.Count; i++)
        {
            var q = quotas[i];
            if (q.Remaining is null || q.IsExpired(now)) continue;
            var srcId = string.IsNullOrWhiteSpace(q.SourceDeviceId)
                ? $"unknown:{q.Key}:{i}"
                : $"device:{q.SourceDeviceId!.Trim()}";
            var srcName = string.IsNullOrWhiteSpace(q.SourceName) ? "来源未命名" : q.SourceName!.Trim();
            if (!map.TryGetValue(srcId, out var group))
            {
                group = new UsageSourceGroup(srcId, srcName, []);
                map[srcId] = group;
                order.Add(srcId);
            }
            group.Quotas.Add(q);
        }
        return order.Select(k => map[k]).Where(g => g.Quotas.Count > 0).ToList();
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

public sealed class LocalUiSettings
{
    public string ThemeMode { get; set; } = "system"; // "system" | "light" | "dark"
    public bool CloseToTray { get; set; } = true;
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
