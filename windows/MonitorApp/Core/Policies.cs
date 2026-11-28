using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace MonitorApp.Core;

public sealed record UsageSourceGroup(string Id, string Name, List<UsageQuotaDto> Quotas);

public sealed record QuotaPaceInfo(
    double IdealRemainingPercent,
    double DeltaPercent,
    string State,
    string Label,
    string CompactLabel)
{
    public bool IsWarning => State is "behind" or "exhausted";
}

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

    public static string PaceDuration(double seconds)
    {
        var minutes = Math.Max(1, (int)Math.Round(seconds / 60.0));
        if (minutes >= 1440)
        {
            var days = minutes / 1440;
            var hours = (minutes % 1440) / 60;
            return hours > 0 ? $"{days} 天 {hours} 小时" : $"{days} 天";
        }
        if (minutes >= 60)
        {
            var hours = minutes / 60;
            var remMin = minutes % 60;
            return remMin > 0 ? $"{hours} 小时 {remMin} 分钟" : $"{hours} 小时";
        }
        return $"{minutes} 分钟";
    }

    public static QuotaPaceInfo? ComputeQuotaPace(UsageQuotaDto? quota, DateTimeOffset now, bool stale = false)
    {
        if (quota is null || stale || quota.Stale || quota.IsExpired(now))
            return null;
        if (quota.Remaining is not { } rawRem || double.IsNaN(rawRem) || double.IsInfinity(rawRem))
            return null;
        if (quota.ResetDate is not { } reset || quota.WindowMinutes is not (> 0 and <= 100800))
            return null;

        var windowSec = quota.WindowMinutes.Value * 60.0;
        var remSec = (reset - now).TotalSeconds;
        var rem = Math.Clamp(rawRem, 0.0, 100.0);
        if (rem <= 0.05 && remSec > windowSec && quota.WindowMinutes.Value <= 360)
            return new QuotaPaceInfo(-1, 0, "exhausted", "受周额度限制", "受周额度限制");
        if (remSec <= 0 || remSec > windowSec)
            return null;

        var used = Math.Clamp(100.0 - rem, 0.0, 100.0);
        var elapsedSec = windowSec - remSec;
        var idealRem = Math.Clamp((remSec / windowSec) * 100.0, 0.0, 100.0);
        var delta = rem - idealRem;

        if (rem <= 0.05)
            return new QuotaPaceInfo(idealRem, delta, "exhausted", "额度已用尽", "已用尽");

        if (elapsedSec < Math.Max(180.0, windowSec * 0.03))
            return new QuotaPaceInfo(idealRem, delta, "steady", "窗口刚开启", "");

        if (delta >= 5.0)
        {
            var pts = (int)Math.Round(delta);
            var text = $"富余 +{pts}%";
            return new QuotaPaceInfo(idealRem, delta, "ahead", text, text);
        }

        if (delta >= -5.0)
            return new QuotaPaceInfo(idealRem, delta, "steady", "配速均衡", "均速");

        var ratePerSec = used / Math.Max(1.0, elapsedSec);
        if (ratePerSec > 0)
        {
            var secToEmpty = rem / ratePerSec;
            if (secToEmpty < remSec)
            {
                var warn = $"超速 · 约 {PaceDuration(secToEmpty)}耗尽";
                return new QuotaPaceInfo(idealRem, delta, "behind", warn, warn);
            }
        }
        var mild = $"偏快 {(int)Math.Round(delta)}%";
        return new QuotaPaceInfo(idealRem, delta, "behind", mild, mild);
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

    public static string NormalizeQuotaHorizon(string? value) =>
        string.Equals(value, "weekly", StringComparison.OrdinalIgnoreCase) ? "weekly" : "5h";

    public static bool HasDualQuotaHorizons(IReadOnlyList<UsageQuotaDto>? quotas)
    {
        if (quotas is null || quotas.Count < 2) return false;
        var hasShort = false;
        var hasWeekly = false;
        foreach (var q in quotas)
        {
            if (q.WindowMinutes is >= 10080) hasWeekly = true;
            else if (q.WindowMinutes is > 0) hasShort = true;
        }
        return hasShort && hasWeekly;
    }

    public static List<UsageQuotaDto> OrderAntigravityQuotas(IReadOnlyList<UsageQuotaDto> quotas) =>
        quotas
            .OrderBy(q => q.BucketLabel?.StartsWith("Gemini", StringComparison.OrdinalIgnoreCase) == true ? 0
                : q.BucketLabel?.StartsWith("Claude", StringComparison.OrdinalIgnoreCase) == true ? 1 : 2)
            .ThenBy(q => q.WindowMinutes ?? 0)
            .ToList();

    public static List<UsageQuotaDto> FilterQuotasByHorizon(IReadOnlyList<UsageQuotaDto> quotas, string? horizon)
    {
        var mode = NormalizeQuotaHorizon(horizon);
        var filtered = quotas.Where(q => mode == "weekly"
            ? q.WindowMinutes is >= 10080
            : q.WindowMinutes is > 0 and < 10080).ToList();
        return filtered.Count > 0 ? filtered : quotas.ToList();
    }

    private static readonly Regex ClaudeModelRegex = new(
        @"^(?:anthropic/)?claude-(opus|sonnet|haiku|fable|mythos)(?:-([0-9]{1,2}(?:[.-][0-9]{1,2})?))?(?:-(low|medium|high|xhigh|thinking))?(?:-(?:[0-9]{8}|latest))?$",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly Regex EarlyClaudeModelRegex = new(
        @"^(?:anthropic/)?claude-([0-9]{1,2}(?:[.-][0-9]{1,2})?)-(opus|sonnet|haiku|fable|mythos)(?:-(?:[0-9]{8}|latest))?$",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly Regex GeminiModelRegex = new(
        @"^(?:google/)?gemini(?:-([0-9]+(?:[.-][0-9]+)?))?((?:-(?:pro|flash|lite|ultra|nano|neon|carbon|hydrogen|argon|orchestrator|tiered|worker|thinking|exp))+)(?:-(low|medium|high|xhigh))?(?:-(?:agy|[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{8}|latest))?$",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly Regex GptOssModelRegex = new(
        @"^(?:openai/)?gpt-oss-([0-9]+b)(?:-(low|medium|high|xhigh))?$",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly Regex GptModelRegex = new(
        @"^(?:openai/)?(gpt-[0-9]+(?:\.[0-9]+)?o?|o[1-9][0-9]*)((?:-(?:codex|spark|pro|mini|nano|max|turbo|astra|sol|luna|terra))*)(?:-(low|medium|high|xhigh))?(?:-(?:[0-9]{4}-?[0-9]{2}-?[0-9]{2}|latest))?$",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly Regex DatedIdRegex = new(
        @"^.*-([0-9]{4})-?([0-9]{2})-?([0-9]{2})$",
        RegexOptions.Compiled);

    private static readonly Regex SizeTokenRegex = new(
        @"^[0-9]+(?:\.[0-9]+)?[bmk]$",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static string TitleWord(string token) =>
        string.IsNullOrEmpty(token)
            ? ""
            : char.ToUpperInvariant(token[0]) + (token.Length > 1 ? token[1..].ToLowerInvariant() : "");

    private static string EffortLabel(string effort) =>
        string.Equals(effort, "xhigh", StringComparison.OrdinalIgnoreCase) ? "XHigh" : TitleWord(effort);

    public static string ModelVariant(string id)
    {
        var trimmed = id.Trim();
        var m = DatedIdRegex.Match(trimmed);
        var version = m.Success
            ? $"{m.Groups[1].Value}-{m.Groups[2].Value}-{m.Groups[3].Value}"
            : trimmed.EndsWith("-latest", StringComparison.OrdinalIgnoreCase) ? "最新版" : "未指定快照";
        if (trimmed.StartsWith("openai/", StringComparison.OrdinalIgnoreCase)) return "OpenAI · " + version;
        if (trimmed.StartsWith("anthropic/", StringComparison.OrdinalIgnoreCase)) return "Anthropic · " + version;
        if (trimmed.StartsWith("google/", StringComparison.OrdinalIgnoreCase)) return "Google · " + version;
        return version;
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

        if (ClaudeModelRegex.Match(trimmed) is { Success: true } claude)
        {
            var version = claude.Groups[2].Success ? " " + claude.Groups[2].Value.Replace('-', '.') : "";
            var baseName = "Claude " + TitleWord(claude.Groups[1].Value) + version;
            return claude.Groups[3].Success ? $"{baseName} ({EffortLabel(claude.Groups[3].Value)})" : baseName;
        }

        if (EarlyClaudeModelRegex.Match(trimmed) is { Success: true } early)
        {
            return $"Claude {early.Groups[1].Value.Replace('-', '.')} {TitleWord(early.Groups[2].Value)}";
        }

        if (GeminiModelRegex.Match(trimmed) is { Success: true } gemini)
        {
            var sb = new StringBuilder("Gemini");
            if (gemini.Groups[1].Success)
                sb.Append(' ').Append(gemini.Groups[1].Value.Replace('-', '.'));
            foreach (var word in gemini.Groups[2].Value.TrimStart('-').Split('-', StringSplitOptions.RemoveEmptyEntries))
                sb.Append(' ').Append(TitleWord(word));
            if (gemini.Groups[3].Success)
                sb.Append(" (").Append(EffortLabel(gemini.Groups[3].Value)).Append(')');
            return sb.ToString();
        }

        if (GptOssModelRegex.Match(trimmed) is { Success: true } gptOss)
        {
            var baseName = "GPT-OSS " + gptOss.Groups[1].Value.ToUpperInvariant();
            return gptOss.Groups[2].Success ? $"{baseName} ({EffortLabel(gptOss.Groups[2].Value)})" : baseName;
        }

        if (GptModelRegex.Match(trimmed) is { Success: true } gpt)
        {
            var basePart = gpt.Groups[1].Value.ToLowerInvariant();
            var sb = new StringBuilder(
                basePart.StartsWith("gpt-", StringComparison.Ordinal)
                    ? "GPT-" + basePart[4..]
                    : basePart.ToUpperInvariant());
            var modifiers = gpt.Groups[2].Value.ToLowerInvariant();
            if (!string.IsNullOrEmpty(modifiers))
            {
                foreach (var word in modifiers.TrimStart('-').Split('-', StringSplitOptions.RemoveEmptyEntries))
                {
                    if (modifiers.StartsWith("-codex", StringComparison.Ordinal) || word == "spark")
                        sb.Append('-').Append(TitleWord(word));
                    else
                        sb.Append(' ').Append(TitleWord(word));
                }
            }
            if (gpt.Groups[3].Success)
                sb.Append(" (").Append(EffortLabel(gpt.Groups[3].Value)).Append(')');
            return sb.ToString();
        }

        // Future-proof fallback for any lowercase slug ID (e.g. gpt-6.3-nova, deepseek-r1, qwen-2.5-coder-32b)
        if (trimmed == lower && lower.Any(c => c is >= 'a' and <= 'z'))
        {
            var clean = Regex.Replace(lower, @"^(?:openai|anthropic|google|deepseek|meta|qwen|mistral)/", "");
            clean = Regex.Replace(clean, @"-(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{8}|latest|agy)$", "");
            if (clean.StartsWith("gpt-", StringComparison.Ordinal))
            {
                var rest = clean[4..].Split('-', StringSplitOptions.RemoveEmptyEntries);
                if (rest.Length > 0)
                {
                    var head = "GPT-" + (rest[0] == "oss" ? "OSS" : rest[0]);
                    var tail = rest.Skip(1).Select(FormatSlugToken).ToList();
                    return tail.Count == 0 ? head : head + " " + string.Join(" ", tail);
                }
            }
            var parts = clean.Split('-', StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length > 0)
            {
                string? trailingEffort = null;
                if (parts.Length > 1 && parts[^1] is "low" or "medium" or "high" or "xhigh")
                {
                    trailingEffort = EffortLabel(parts[^1]);
                    parts = parts[..^1];
                }
                var formatted = string.Join(" ", parts.Select(FormatSlugToken));
                return trailingEffort is null ? formatted : $"{formatted} ({trailingEffort})";
            }
        }

        return trimmed;
    }

    private static string FormatSlugToken(string token) => token switch
    {
        "gpt" => "GPT",
        "oss" => "OSS",
        "glm" => "GLM",
        "vl" => "VL",
        "r1" => "R1",
        "v2" or "v3" or "v4" => token.ToUpperInvariant(),
        "deepseek" => "DeepSeek",
        "minimax" => "MiniMax",
        _ when SizeTokenRegex.IsMatch(token) => token.ToUpperInvariant(),
        _ when Regex.IsMatch(token, @"^o[1-9][0-9]*$") => token.ToUpperInvariant(),
        _ => TitleWord(token)
    };

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
            var display = nameCounts[row.Name] > 1 ? $"{row.Name} ({ModelVariant(row.Raw)})" : row.Name;
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

    public static string StateLabel(string? state, string? outcome = null)
    {
        if (outcome == "handled_on_desktop") return "已在电脑端处理";
        if (outcome == "adopted") return "电脑已收到并已采用";
        if (outcome == "rejected") return "电脑已收到并已拒绝";
        return state switch
        {
            "pending" => "待你处理",
            "submitted" => "已发送，等待电脑确认",
            "forwarded" => "已交给电脑，等待任务结果",
            "acknowledged" => "电脑已收到",
            "cancelled" => "已在电脑处理或取消",
            "expired" => "请求已过期",
            _ => "状态确认中"
        };
    }

    public static string LocalizedEffort(string? effort) => effort switch
    {
        "none" => "关闭",
        "minimal" => "最低",
        "low" => "低",
        "medium" => "中",
        "high" => "高",
        "xhigh" => "极高",
        "max" => "最高",
        _ => effort ?? ""
    };

    public static string ReceiptTrail(SessionPendingRequestDto req, bool sendingNow, bool hasLocalRecord)
    {
        if (req.Outcome == "handled_on_desktop")
            return "已在电脑端处理";
        if (sendingNow || (req.State == "pending" && hasLocalRecord))
            return "已发送 → 等待电脑接收";
        if (req.State == "submitted")
            return req.Delivered == true ? "已发送 → 电脑已收到" : "已发送 → 等待电脑接收";
        if (req.Outcome == "adopted")
            return "已发送 → 电脑已收到 → 已采用";
        if (req.Outcome == "rejected" || (req.State is "forwarded" or "acknowledged" && req.DecisionKind == "deny"))
            return "已发送 → 电脑已收到 → 已拒绝";
        if (req.State is "forwarded" or "acknowledged")
            return "已发送 → 电脑已收到";
        return "";
    }

    public static string ReplyReceiptTrail(bool sending, string? state, bool deliveredToComputer)
    {
        if (sending) return "已发送 → 等待电脑接收";
        return state switch
        {
            "queued" => deliveredToComputer ? "已发送 → 电脑已收到" : "已发送 → 等待电脑接收",
            "dispatching" => deliveredToComputer ? "已发送 → 电脑已收到" : "已发送 → 等待电脑确认",
            "running" => "已发送 → 电脑已收到",
            "succeeded" => "已发送 → 电脑已收到 → 本轮已完成",
            "blocked" => deliveredToComputer ? "已发送 → 电脑已收到 · 等待处理" : "",
            "failed" => deliveredToComputer ? "已发送 → 电脑已收到 · 执行未完成" : "",
            _ => ""
        };
    }

    public static string FormatSettingsReceipt(AppliedSettingsDto? applied, SessionModelSelectionDto? requested)
    {
        var hasApplied = applied is not null && (!string.IsNullOrWhiteSpace(applied.Model)
                                                 || !string.IsNullOrWhiteSpace(applied.Effort)
                                                 || !string.IsNullOrWhiteSpace(applied.ServiceTier)
                                                 || !string.IsNullOrWhiteSpace(applied.CollaborationMode));
        var model = hasApplied ? applied!.Model : requested?.Model;
        var effort = hasApplied ? applied!.Effort : requested?.Effort;
        var tier = hasApplied ? applied!.ServiceTier : requested?.ServiceTier;
        var mode = hasApplied ? applied!.CollaborationMode : requested?.CollaborationMode;

        var parts = new List<string>();
        if (!string.IsNullOrWhiteSpace(model))
            parts.Add(UsageFormatting.FormatModelName(model));
        if (!string.IsNullOrWhiteSpace(effort))
            parts.Add($"思考强度 {LocalizedEffort(effort)}");
        if (tier is "priority" or "fast")
            parts.Add("快速模式");
        if (mode == "plan")
            parts.Add("计划模式");
        if (parts.Count == 0) return "";
        return (hasApplied ? "本轮实际使用：" : "已随回复提交：") + string.Join(" · ", parts);
    }

    public static bool ValidAnswers(SessionPendingRequestDto req, IReadOnlyList<SessionAnswerPayloadDto>? answers)
    {
        if (req.Kind != "question" || req.Questions is not { Count: > 0 } questions || answers is null)
            return false;
        if (answers.Count != questions.Count)
            return false;

        var byId = new Dictionary<string, SessionAnswerPayloadDto>(StringComparer.Ordinal);
        foreach (var ans in answers)
        {
            if (string.IsNullOrEmpty(ans.QuestionId) || !byId.TryAdd(ans.QuestionId, ans))
                return false;
        }

        foreach (var q in questions)
        {
            if (!byId.TryGetValue(q.Id, out var ans))
                return false;

            var choices = ans.Choices ?? [];
            var hasChoices = choices.Count > 0;
            var trimmedText = ans.Text?.Trim() ?? "";
            var hasText = trimmedText.Length > 0;

            if (hasChoices == hasText)
                return false;

            if (hasChoices)
            {
                if (q.Options is not { Count: > 0 } opts)
                    return false;
                if (q.Multiple != true && choices.Count != 1)
                    return false;
                var allowed = opts.Select(o => o.Id).ToHashSet(StringComparer.Ordinal);
                var seen = new HashSet<string>(StringComparer.Ordinal);
                foreach (var c in choices)
                {
                    if (!allowed.Contains(c) || !seen.Add(c))
                        return false;
                }
            }
            else
            {
                var allowsFreeText = q.AllowsText == true || q.Options is null or { Count: 0 };
                if (!allowsFreeText || trimmedText.Length > 4000)
                    return false;
            }
        }
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

    // Personalizable Background ("cloud" | "blue" | "sand" | "mist" | "bing" | "custom")
    public string BackgroundMode { get; set; } = "cloud";
    public string WallpaperStyle { get; set; } = "balanced"; // "frosted" | "balanced" | "clear" | "vivid" | "custom"
    public double? WallpaperOpacity { get; set; } // 0.05 .. 0.95 (stepless transparency / show-through)
    public double? WallpaperBlur { get; set; } // 0.0 .. 32.0 px (stepless Gaussian blur)

    // Compact Mini-Window Size & Content Customization
    public string CompactSizePreset { get; set; } = "medium"; // "nano" | "small" | "medium" | "large"
    public string CompactContentMode { get; set; } = "overview"; // "overview" | "tasks" | "quotas"
    public bool CompactShowLowestQuota { get; set; } = true;
    public bool CompactShowToolStrip { get; set; } = true;
    public bool CompactShowTodayTokens { get; set; } = true;
    public bool CompactShowTaskMeta { get; set; } = true;
    public string AntigravityQuotaHorizon { get; set; } = "5h"; // "5h" | "weekly"

    public static (double Opacity, double Blur) PresetValues(string? style) => style switch
    {
        "frosted" => (0.24, 22.0),
        "clear" => (0.70, 3.0),
        "vivid" => (0.88, 0.0),
        _ => (0.48, 10.0) // "balanced"
    };

    public double GetEffectiveWallpaperOpacity() =>
        Math.Clamp(WallpaperOpacity ?? PresetValues(WallpaperStyle).Opacity, 0.05, 0.95);

    public double GetEffectiveWallpaperBlur() =>
        Math.Clamp(WallpaperBlur ?? PresetValues(WallpaperStyle).Blur, 0.0, 32.0);

    public void ApplyWallpaperPreset(string preset)
    {
        WallpaperStyle = preset;
        var (op, bl) = PresetValues(preset);
        WallpaperOpacity = op;
        WallpaperBlur = bl;
    }

    public string MatchingWallpaperPreset()
    {
        var op = GetEffectiveWallpaperOpacity();
        var bl = GetEffectiveWallpaperBlur();
        foreach (var k in new[] { "frosted", "balanced", "clear", "vivid" })
        {
            var (po, pb) = PresetValues(k);
            if (Math.Abs(op - po) <= 0.025 && Math.Abs(bl - pb) <= 1.5)
                return k;
        }
        return "custom";
    }

    public (double Width, double Height) GetCompactDimensions() => CompactSizePreset switch
    {
        "nano" => (368, 240),
        "small" => (384, 356),
        "large" => (648, 436),
        _ => (420, 484)
    };
}

public enum CompactLayoutKind
{
    MicroCapsule,   // Narrow & short (< 450w, < 250h): focused quota + anchored tool chips
    WideRibbon,     // Wide & short (>= 450w, < 250h): 2-column horizontal split [Quota | Focus Tasks]
    CompactTile,    // Balanced widget (< 500w, 250..415h): Hero + tool chips + 2 focus tasks
    StandardStack,  // Rich portrait stack (< 500w, >= 415h): visual tool tiles + 3-4 focus tasks
    WideBento       // Widescreen 2-column Bento dashboard (>= 500w, >= 250h): [Quota + Stats] + [All Quotas | Active Tasks]
}

public readonly record struct CompactViewportPlan(
    CompactLayoutKind Kind,
    int Columns,
    bool ShowTaskMeta,
    bool ShowTaskPreview,
    bool ShowVisualToolTiles,
    int TasksPerPage,
    int QuotasPerPage,
    int OverviewTaskCount,
    int OverviewQuotaCount);

public readonly record struct MainShellPlan(
    bool CollapseSidebar,
    double SidebarWidth,
    double TaskListWidth,
    int UsageColumns,
    int DevicesColumns,
    bool AccountTwoColumnBento,
    bool TaskDetailInspector = false);

public static class ResponsiveLayoutPolicy
{
    public static CompactViewportPlan PlanCompact(double width, double height, bool userWantsTaskMeta = true)
    {
        var w = Math.Max(280.0, width);
        var h = Math.Max(175.0, height);
        var isMicroH = h < 250.0;
        var cardPad = isMicroH ? 11.5 : 14.0;
        var innerH = Math.Max(72.0, h - 34.0 - 12.0 - cardPad * 2.0 - 26.0);

        if (isMicroH)
        {
            if (w >= 450.0)
            {
                // Horizontal Ribbon: 2 columns side-by-side
                var canMeta = userWantsTaskMeta && innerH >= 104.0;
                var rowsPerCol = Math.Clamp((int)Math.Floor((innerH - 18.0) / (canMeta ? 50.0 : 38.0)), 1, 2);
                return new CompactViewportPlan(
                    Kind: CompactLayoutKind.WideRibbon,
                    Columns: 2,
                    ShowTaskMeta: canMeta,
                    ShowTaskPreview: false,
                    ShowVisualToolTiles: false,
                    TasksPerPage: rowsPerCol * 2,
                    QuotasPerPage: 2,
                    OverviewTaskCount: rowsPerCol,
                    OverviewQuotaCount: 1);
            }
            else
            {
                // Sleek 5:3 Capsule: 2 compact tasks/quotas per page when height >= 205, 1 when ultra-short
                var canTwoRows = innerH >= 102.0;
                var canMeta = userWantsTaskMeta && !canTwoRows && innerH >= 84.0;
                return new CompactViewportPlan(
                    Kind: CompactLayoutKind.MicroCapsule,
                    Columns: 1,
                    ShowTaskMeta: canMeta,
                    ShowTaskPreview: false,
                    ShowVisualToolTiles: false,
                    TasksPerPage: canTwoRows ? 2 : 1,
                    QuotasPerPage: canTwoRows ? 2 : 1,
                    OverviewTaskCount: 0,
                    OverviewQuotaCount: 1);
            }
        }

        if (w >= 500.0)
        {
            // Widescreen 2-Column Bento Dashboard
            var showPreview = w >= 580.0 && innerH >= 340.0;
            var taskRowH = showPreview ? 72.0 : (userWantsTaskMeta ? 54.0 : 40.0);
            var splitBodyH = Math.Max(96.0, innerH - 92.0);
            var bentoOverviewTasks = Math.Clamp((int)Math.Floor((splitBodyH - 22.0) / taskRowH), 2, 5);
            var bentoOverviewQuotas = Math.Clamp((int)Math.Floor((splitBodyH - 22.0) / 50.0), 2, 5);
            var fullPageTaskRows = Math.Clamp((int)Math.Floor((innerH - 28.0) / taskRowH), 2, 5);
            var fullPageQuotaRows = Math.Clamp((int)Math.Floor((innerH - 28.0) / 52.0), 2, 5);

            return new CompactViewportPlan(
                Kind: CompactLayoutKind.WideBento,
                Columns: 2,
                ShowTaskMeta: userWantsTaskMeta,
                ShowTaskPreview: showPreview,
                ShowVisualToolTiles: w >= 660.0,
                TasksPerPage: fullPageTaskRows * 2,
                QuotasPerPage: fullPageQuotaRows * 2,
                OverviewTaskCount: bentoOverviewTasks,
                OverviewQuotaCount: bentoOverviewQuotas);
        }

        if (h >= 415.0)
        {
            // Standard Rich Portrait Stack: visual tool progress tiles + 3-4 focus tasks
            var showPreview = innerH >= 320.0;
            var taskRowH = showPreview ? 72.0 : (userWantsTaskMeta ? 56.0 : 42.0);
            var overviewTasks = Math.Clamp((int)Math.Floor((innerH - 160.0) / (userWantsTaskMeta ? 56.0 : 42.0)), 2, 4);
            var tasksPerPage = Math.Clamp((int)Math.Floor((innerH - 28.0) / taskRowH), 3, 6);
            var quotasPerPage = Math.Clamp((int)Math.Floor((innerH - 30.0) / 54.0), 3, 6);

            return new CompactViewportPlan(
                Kind: CompactLayoutKind.StandardStack,
                Columns: 1,
                ShowTaskMeta: userWantsTaskMeta,
                ShowTaskPreview: showPreview,
                ShowVisualToolTiles: true,
                TasksPerPage: tasksPerPage,
                QuotasPerPage: quotasPerPage,
                OverviewTaskCount: overviewTasks,
                OverviewQuotaCount: 0);
        }
        else
        {
            // Balanced Medium Widget (250 <= h < 415, w < 500)
            var overviewTasks = innerH >= 185.0 ? Math.Clamp((int)Math.Floor((innerH - 118.0) / (userWantsTaskMeta ? 54.0 : 42.0)), 1, 3) : 0;
            var tasksPerPage = Math.Clamp((int)Math.Floor((innerH - 28.0) / (userWantsTaskMeta ? 56.0 : 42.0)), 2, 4);
            var quotasPerPage = Math.Clamp((int)Math.Floor((innerH - 30.0) / 54.0), 2, 4);

            return new CompactViewportPlan(
                Kind: CompactLayoutKind.CompactTile,
                Columns: 1,
                ShowTaskMeta: userWantsTaskMeta,
                ShowTaskPreview: false,
                ShowVisualToolTiles: false,
                TasksPerPage: tasksPerPage,
                QuotasPerPage: quotasPerPage,
                OverviewTaskCount: overviewTasks,
                OverviewQuotaCount: 0);
        }
    }

    public static MainShellPlan PlanMainShell(double windowWidth)
    {
        var w = Math.Max(600.0, windowWidth);
        var collapseRail = w < 940.0;
        var railW = collapseRail ? 72.0 : 208.0;
        var contentW = Math.Max(480.0, w - railW - 24.0);
        var taskListW = w < 840.0 ? 284.0 : w < 1080.0 ? 342.0 : w < 1320.0 ? 364.0 : 392.0;
        var taskDetailW = contentW - taskListW;
        var usageCols = contentW >= 1040.0 ? 3 : contentW >= 660.0 ? 2 : 1;
        var devCols = contentW >= 820.0 ? 2 : 1;
        var accountBento = contentW >= 1020.0;
        var detailInspector = taskDetailW >= 600.0;

        return new MainShellPlan(
            CollapseSidebar: collapseRail,
            SidebarWidth: railW,
            TaskListWidth: taskListW,
            UsageColumns: usageCols,
            DevicesColumns: devCols,
            AccountTwoColumnBento: accountBento,
            TaskDetailInspector: detailInspector);
    }

    /// <summary>
    /// Partitions ordered items into the minimum number of pages where every multi-item page fits within
    /// <paramref name="maxPageHeight"/>, while balancing page heights via dynamic programming so pages split
    /// evenly (e.g. 2+2, 4+3, 3+2+2) instead of leaving sparse 1-item orphan tail pages (e.g. 3+1, 3+3+1).
    /// </summary>
    public static List<List<T>> PartitionBalanced<T>(
        IReadOnlyList<T> items,
        Func<T, double> getHeight,
        double maxPageHeight,
        double itemGap)
    {
        if (items.Count == 0) return [];
        if (items.Count == 1) return [[items[0]]];

        var n = items.Count;
        var cap = Math.Max(1.0, maxPageHeight);
        var gap = Math.Max(0.0, itemGap);
        var h = new double[n];
        var prefix = new double[n + 1];
        for (var i = 0; i < n; i++)
        {
            h[i] = Math.Max(0.0, getHeight(items[i]));
            prefix[i + 1] = prefix[i] + h[i];
        }

        double RangeH(int start, int endInclusive) =>
            (prefix[endInclusive + 1] - prefix[start]) + (endInclusive - start) * gap;

        bool ValidRange(int start, int endInclusive) =>
            start == endInclusive || RangeH(start, endInclusive) <= cap + 0.05;

        // 1. Greedy interval scan determines the exact minimum valid page count K
        var minPages = 0;
        var idx = 0;
        while (idx < n)
        {
            var end = idx;
            while (end + 1 < n && ValidRange(idx, end + 1))
                end++;
            minPages++;
            idx = end + 1;
        }

        if (minPages <= 1)
            return [items.ToList()];

        // 2. DP over (pageIndex 1..K, endItem 1..N) minimizing sum of squared page heights
        //    (with a tiny front-loading bias so ties prefer 4+3 / 3+2+2 over 3+4 / 2+2+3).
        var dp = new double[minPages + 1, n + 1];
        var splitPrev = new int[minPages + 1, n + 1];
        for (var p = 0; p <= minPages; p++)
            for (var i = 0; i <= n; i++)
                dp[p, i] = double.PositiveInfinity;
        dp[0, 0] = 0.0;

        for (var p = 1; p <= minPages; p++)
        {
            for (var end = p; end <= n - (minPages - p); end++)
            {
                for (var start = p - 1; start < end; start++)
                {
                    if (double.IsPositiveInfinity(dp[p - 1, start]))
                        continue;
                    if (!ValidRange(start, end - 1))
                        continue;

                    var rH = RangeH(start, end - 1);
                    var frontBias = -(minPages - p + 1) * rH * 0.01;
                    var cost = dp[p - 1, start] + rH * rH + frontBias;
                    if (cost < dp[p, end])
                    {
                        dp[p, end] = cost;
                        splitPrev[p, end] = start;
                    }
                }
            }
        }

        if (double.IsPositiveInfinity(dp[minPages, n]))
        {
            // Defensive fallback to greedy if numeric edge case occurs
            var fallback = new List<List<T>>();
            var cur = 0;
            while (cur < n)
            {
                var end = cur;
                while (end + 1 < n && ValidRange(cur, end + 1))
                    end++;
                var page = new List<T>(end - cur + 1);
                for (var k = cur; k <= end; k++) page.Add(items[k]);
                fallback.Add(page);
                cur = end + 1;
            }
            return fallback;
        }

        var pages = new List<List<T>>(minPages);
        var curEnd = n;
        for (var p = minPages; p >= 1; p--)
        {
            var start = splitPrev[p, curEnd];
            var slice = new List<T>(curEnd - start);
            for (var k = start; k < curEnd; k++)
                slice.Add(items[k]);
            pages.Add(slice);
            curEnd = start;
        }
        pages.Reverse();
        return pages;
    }
}

public sealed class SyncDiagnosticsSample
{
    public long FirstScreenMs { get; set; } = -1;
    public long LastRefreshMs { get; set; } = -1;
    public DateTimeOffset? ComputerCompletedAt { get; set; }
    public DateTimeOffset? ServerReceivedAt { get; set; }
    public DateTimeOffset? ClientDisplayedAt { get; set; }
}

public static class WorkbenchVisibilityPolicy
{
    public static List<MonitorTask> PendingTasks(IReadOnlyList<MonitorTask> tasks, IReadOnlyList<Computer> devices, bool fresh)
    {
        if (!fresh || tasks.Count == 0 || devices.Count == 0) return [];
        var onlineIds = devices
            .Where(d => !string.IsNullOrWhiteSpace(d.Id) && d.Online)
            .Select(d => d.Id)
            .ToHashSet(StringComparer.Ordinal);
        return tasks
            .Where(t => !t.Archived && t.Stale != true && onlineIds.Contains(t.DeviceId) && t.EffectiveStatus == "waiting")
            .OrderBy(t => !string.IsNullOrEmpty(t.EffectivePendingKind) ? 0 : 1)
            .ToList();
    }

    public static (int HiddenCount, string Message, bool ShowResetAction) HiddenFilterSummary(
        IReadOnlyList<MonitorTask> tasks,
        IReadOnlyList<Computer> devices,
        ToolFilter tool,
        TaskScope scope,
        string? query,
        bool fresh,
        int visibleCount)
    {
        if (!fresh || visibleCount > 0 || tasks.Count == 0)
            return (0, "", false);
        var hasFilter = tool != ToolFilter.All || scope != TaskScope.All || !string.IsNullOrWhiteSpace(query);
        if (!hasFilter)
            return (0, "", false);
        var unarchived = tasks.Where(t => !t.Archived).ToList();
        if (unarchived.Count == 0)
            return (0, "", false);
        var distinctDevs = unarchived.Select(t => t.DeviceId).Where(id => !string.IsNullOrWhiteSpace(id)).Distinct(StringComparer.Ordinal).Count();
        var singleComputer = devices.Count <= 1 && distinctDevs <= 1;
        var msg = singleComputer
            ? $"这台电脑有 {unarchived.Count} 条任务，当前筛选隐藏了 {unarchived.Count} 条"
            : $"已连接电脑共有 {unarchived.Count} 条任务，当前筛选隐藏了 {unarchived.Count} 条";
        return (unarchived.Count, msg, true);
    }

    public static string FormatDuration(long ms) =>
        ms < 0 ? "尚未记录" : ms < 1000 ? $"{ms} ms" : $"{ms / 1000.0:0.00} 秒 ({ms} ms)";

    public static string FormatTimestamp(DateTimeOffset? stamp) =>
        stamp is { } s ? s.ToLocalTime().ToString("HH:mm:ss.fff", CultureInfo.InvariantCulture) : "暂未提供";

    public static string FormatLeg(DateTimeOffset? from, DateTimeOffset? to)
    {
        if (from is not { } f || to is not { } t) return "暂未提供";
        var deltaMs = (long)Math.Round((t - f).TotalMilliseconds);
        return deltaMs < 0 ? $"时钟偏差 ({deltaMs} ms)" : FormatDuration(deltaMs);
    }

    public static string Diagnosis(SyncDiagnosticsSample sample)
    {
        if (sample.FirstScreenMs < 0 && sample.ClientDisplayedAt is null)
            return "完成一次工作台同步后，这里会保留最近一次首屏耗时及电脑完成、服务收到、本机显示三个时间点。";
        var leg1 = sample.ComputerCompletedAt is { } c && sample.ServerReceivedAt is { } s && s >= c
            ? (long)(s - c).TotalMilliseconds : -1;
        var leg2 = sample.ServerReceivedAt is { } sr && sample.ClientDisplayedAt is { } d && d >= sr
            ? (long)(d - sr).TotalMilliseconds : -1;
        if (sample.LastRefreshMs >= 3000 || sample.FirstScreenMs >= 3000)
        {
            if (leg1 >= 2500 && leg1 >= leg2)
                return "最近一次耗时主要在「电脑完成 → 服务收到」，可检查来源电脑采集上报网络。";
            var reqMs = sample.LastRefreshMs >= 0 ? sample.LastRefreshMs : sample.FirstScreenMs;
            return $"最近一次耗时主要在「服务收到 → 本机显示」（请求耗时 {FormatDuration(reqMs)}），可检查网络或服务响应。";
        }
        return "最近一次三段同步耗时正常；若再次遇到 4–6 秒延迟，可直接对比三个时间点定位慢在哪一段。";
    }
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

public sealed record DevPreviewItem(
    int Port,
    string Process,
    string BindScope,
    bool MatchedProject,
    bool LocalMachine,
    string? OpenUrl,
    string? ShareUrl,
    string ButtonLabel,
    string Subtitle,
    string? LoopbackFixPrompt);

public sealed record QuickSuggestionItem(string Label, string Prompt);

public static class QuickReplySuggestionPolicy
{
    public const int MaxAttachments = 3;

    public static List<QuickSuggestionItem> SuggestionsFor(
        string? status,
        bool hasError,
        int diffFiles,
        bool planMode,
        int loopbackPort = 0,
        string? loopbackPrompt = null)
    {
        var list = new List<QuickSuggestionItem>(5);
        if (loopbackPort > 0 && !string.IsNullOrWhiteSpace(loopbackPrompt))
            list.Add(new QuickSuggestionItem($"开放 :{loopbackPort} 手机预览", loopbackPrompt!.Trim()));

        if (status is "failed" or "error" || hasError)
        {
            list.Add(new QuickSuggestionItem("定位报错并修复", "请定位上述报错或失败的根本原因，直接修复并运行验证确认恢复正常。"));
            list.Add(new QuickSuggestionItem("最小复现并跑测试", "请先用最小范围复现该问题，修复后运行相关测试汇报结果。"));
        }
        else if (planMode)
        {
            list.Add(new QuickSuggestionItem("按此方案执行", "方案确认无误，请按此方案逐步实施修改并运行测试验证。"));
            list.Add(new QuickSuggestionItem("只保留核心改动", "请精简上述方案，只做最核心、改动最小的部分并执行。"));
        }
        else if (diffFiles > 0)
        {
            list.Add(new QuickSuggestionItem("运行测试验证改动", "请针对刚才修改的代码运行相关测试与构建检查，确认没有引入回归。"));
            list.Add(new QuickSuggestionItem("自查边界与安全", "请审视本轮代码变更，检查是否有遗漏的边界情况、异常处理或权限安全问题。"));
            list.Add(new QuickSuggestionItem("总结变更与提交说明", "请简要总结本轮修改的文件与核心逻辑，并草拟一段清晰的 Git 提交说明。"));
        }
        else
        {
            list.Add(new QuickSuggestionItem("继续推进下一步", "请继续推进下一步，完成后汇报验证结果。"));
            list.Add(new QuickSuggestionItem("运行测试检查状态", "请运行当前项目的测试套件，汇报通过情况与待处理项。"));
            list.Add(new QuickSuggestionItem("总结进展与待办", "请简要总结目前已完成的内容，以及接下来还需要处理的事项。"));
        }

        if (list.Count > 4)
            list.RemoveRange(4, list.Count - 4);
        return list;
    }
}

public static class DevPreviewPolicy
{
    private static readonly string[] CanonicalTools = ["codex", "claude", "antigravity"];

    public static List<string> SupportedToolsFor(Computer? computer)
    {
        if (computer?.SupportedTools is not { Count: > 0 } raw) return [];
        var result = new List<string>(3);
        foreach (var t in CanonicalTools)
        {
            if (raw.Any(x => string.Equals(x, t, StringComparison.Ordinal)))
                result.Add(t);
        }
        return result;
    }

    public static List<ProjectSummaryDto> VerifiedProjectsFor(Computer? computer)
    {
        if (computer?.Projects is not { Count: > 0 } raw) return [];
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var list = new List<ProjectSummaryDto>();
        foreach (var p in raw)
        {
            if (p is null || string.IsNullOrWhiteSpace(p.Name)) continue;
            if (!MonitorSecurity.Matches(p.Id, "[0-9a-f]{24}")) continue;
            if (seen.Add(p.Id))
                list.Add(p);
        }
        return list;
    }

    public static string? ValidNetworkHost(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        var host = raw.Trim().ToLowerInvariant();
        if (host.Length is < 1 or > 253) return null;
        foreach (var ch in host)
        {
            if (ch <= ' ' || ch is '/' or '\\' or ':' or '@' or '?' or '#')
                return null;
        }

        if (host.EndsWith(".ts.net", StringComparison.Ordinal))
        {
            var labels = host.Split('.');
            if (labels.Length < 3) return null;
            foreach (var label in labels)
            {
                if (label.Length is < 1 or > 63 || label[0] == '-' || label[^1] == '-')
                    return null;
                foreach (var c in label)
                {
                    if (!((c is >= 'a' and <= 'z') || (c is >= '0' and <= '9') || c == '-'))
                        return null;
                }
            }
            return host;
        }

        var octets = host.Split('.');
        if (octets.Length != 4) return null;
        var nums = new int[4];
        for (var i = 0; i < 4; i++)
        {
            var part = octets[i];
            if (part.Length is < 1 or > 3 || (part.Length > 1 && part[0] == '0'))
                return null;
            if (!int.TryParse(part, NumberStyles.None, CultureInfo.InvariantCulture, out var v) || v is < 0 or > 255)
                return null;
            nums[i] = v;
        }

        var a = nums[0];
        var b = nums[1];
        var isPrivateOrTailnet =
            a == 10
            || (a == 172 && b is >= 16 and <= 31)
            || (a == 192 && b == 168)
            || (a == 100 && b is >= 64 and <= 127);
        return isPrivateOrTailnet ? host : null;
    }

    public static bool ValidPreviewUrl(string? url, bool allowLoopback)
    {
        if (string.IsNullOrWhiteSpace(url)) return false;
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return false;
        if (!string.Equals(uri.Scheme, "http", StringComparison.Ordinal)) return false;
        if (uri.Port is < 1024 or > 65535) return false;
        if (!string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Fragment)) return false;
        if (uri.PathAndQuery != "/") return false;
        if (allowLoopback && string.Equals(uri.Host, "127.0.0.1", StringComparison.Ordinal))
            return true;
        return ValidNetworkHost(uri.Host) is not null;
    }

    public static List<DevPreviewItem> ItemsFor(MonitorTask? task, Computer? computer, bool fresh)
    {
        if (!fresh || computer is not { Online: true, DevPorts.Count: > 0 })
            return [];

        var validHosts = new List<string>();
        if (computer.PreviewHosts is { Count: > 0 } rawHosts)
        {
            foreach (var h in rawHosts)
            {
                if (ValidNetworkHost(h) is { } clean && !validHosts.Contains(clean, StringComparer.Ordinal))
                    validHosts.Add(clean);
            }
        }

        var taskProjId = task?.ProjectId is { } pid && MonitorSecurity.Matches(pid, "[0-9a-f]{24}") ? pid : "";
        var seenPorts = new HashSet<int>();
        var matched = new List<DevPortDto>();
        var all = new List<DevPortDto>();

        foreach (var dp in computer.DevPorts)
        {
            if (dp is null || dp.Port is < 1024 or > 65535) continue;
            if (dp.BindScope is not ("network" or "loopback")) continue;
            if (!seenPorts.Add(dp.Port)) continue;
            all.Add(dp);
            if (!string.IsNullOrEmpty(taskProjId) && string.Equals(dp.ProjectId, taskProjId, StringComparison.Ordinal))
                matched.Add(dp);
        }

        if (all.Count == 0) return [];
        var chosen = matched.Count > 0 ? matched : all;
        var matchedFlag = matched.Count > 0;
        var primaryHost = validHosts.Count > 0 ? validHosts[0] : null;
        var items = new List<DevPreviewItem>(Math.Min(4, chosen.Count));

        foreach (var dp in chosen.Take(4))
        {
            var proc = string.IsNullOrWhiteSpace(dp.Process) ? "dev" : dp.Process!.Trim();
            var fixPrompt = $"请把当前监听 127.0.0.1:{dp.Port} 的本地开发服务改为绑定 0.0.0.0:{dp.Port}（例如加上 --host 0.0.0.0），方便手机或其他设备直接真机预览。";

            if (computer.Local)
            {
                var localUrl = $"http://127.0.0.1:{dp.Port}/";
                var shareUrl = dp.BindScope == "network" && primaryHost is not null
                    ? $"http://{primaryHost}:{dp.Port}/"
                    : null;
                var sub = shareUrl is not null
                    ? $"{proc} · 本机与手机均可访问 ({primaryHost}:{dp.Port})"
                    : $"{proc} · 本机可直连 (仅 127.0.0.1，手机预览需绑定 0.0.0.0)";
                items.Add(new DevPreviewItem(
                    Port: dp.Port,
                    Process: proc,
                    BindScope: dp.BindScope,
                    MatchedProject: matchedFlag,
                    LocalMachine: true,
                    OpenUrl: localUrl,
                    ShareUrl: shareUrl,
                    ButtonLabel: $"打开预览 (:{dp.Port})",
                    Subtitle: sub,
                    LoopbackFixPrompt: dp.BindScope == "loopback" ? fixPrompt : null));
            }
            else if (dp.BindScope == "network" && primaryHost is not null)
            {
                var remoteUrl = $"http://{primaryHost}:{dp.Port}/";
                items.Add(new DevPreviewItem(
                    Port: dp.Port,
                    Process: proc,
                    BindScope: dp.BindScope,
                    MatchedProject: matchedFlag,
                    LocalMachine: false,
                    OpenUrl: remoteUrl,
                    ShareUrl: remoteUrl,
                    ButtonLabel: $"打开远程预览 (:{dp.Port})",
                    Subtitle: $"{proc} · {primaryHost}:{dp.Port}",
                    LoopbackFixPrompt: null));
            }
            else if (dp.BindScope == "loopback")
            {
                items.Add(new DevPreviewItem(
                    Port: dp.Port,
                    Process: proc,
                    BindScope: dp.BindScope,
                    MatchedProject: matchedFlag,
                    LocalMachine: false,
                    OpenUrl: null,
                    ShareUrl: null,
                    ButtonLabel: $":{dp.Port} 仅限该电脑本机",
                    Subtitle: $"{proc} · 仅监听 127.0.0.1，需绑定 0.0.0.0 才可跨设备预览",
                    LoopbackFixPrompt: fixPrompt));
            }
            else
            {
                items.Add(new DevPreviewItem(
                    Port: dp.Port,
                    Process: proc,
                    BindScope: dp.BindScope,
                    MatchedProject: matchedFlag,
                    LocalMachine: false,
                    OpenUrl: null,
                    ShareUrl: null,
                    ButtonLabel: $":{dp.Port}",
                    Subtitle: $"{proc} · 未检测到可用的 Tailscale 或局域网地址",
                    LoopbackFixPrompt: null));
            }
        }
        return items;
    }
}
