using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

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

    public static string StateLabel(string? state) => state switch
    {
        "pending" => "待你处理",
        "submitted" => "已发送，等待电脑确认",
        "forwarded" => "已交给电脑，等待任务结果",
        "acknowledged" => "电脑已收到",
        "cancelled" => "已在电脑处理或取消",
        "expired" => "请求已过期",
        _ => "状态确认中"
    };

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
    public string WallpaperStyle { get; set; } = "balanced"; // "clear" | "balanced" | "frosted"

    // Compact Mini-Window Size & Content Customization
    public string CompactSizePreset { get; set; } = "medium"; // "nano" | "small" | "medium" | "large"
    public string CompactContentMode { get; set; } = "overview"; // "overview" | "tasks" | "quotas"
    public bool CompactShowLowestQuota { get; set; } = true;
    public bool CompactShowToolStrip { get; set; } = true;
    public bool CompactShowTodayTokens { get; set; } = true;
    public bool CompactShowTaskMeta { get; set; } = true;

    public (double Width, double Height) GetCompactDimensions() => CompactSizePreset switch
    {
        "nano" => (308, 196),
        "small" => (336, 356),
        "large" => (468, 640),
        _ => (384, 504)
    };
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
