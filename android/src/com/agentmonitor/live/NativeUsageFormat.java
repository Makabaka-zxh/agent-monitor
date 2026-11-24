package com.agentmonitor.live;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Presentation rules deliberately keep missing readings distinct from measured zero. */
public final class NativeUsageFormat {
    private static final String FAMILY = "(opus|sonnet|haiku|fable|mythos)";
    private static final String VERSION = "([0-9]{1,2}(?:[.-][0-9]{1,2})?)";
    private static final String SNAPSHOT = "(?:-(?:[0-9]{8}|latest))?";
    private static final Pattern CLAUDE = Pattern.compile("(?:anthropic/)?claude-" + FAMILY
            + "(?:-" + VERSION + ")?(?:-(low|medium|high|xhigh))?" + SNAPSHOT, Pattern.CASE_INSENSITIVE);
    private static final Pattern EARLY_CLAUDE = Pattern.compile("(?:anthropic/)?claude-" + VERSION
            + "-" + FAMILY + SNAPSHOT, Pattern.CASE_INSENSITIVE);
    private static final Pattern GPT = Pattern.compile("(?:openai/)?(gpt-[0-9]+(?:\\.[0-9]+)?o?|o[1-9][0-9]*)"
            + "((?:-(?:codex|spark|pro|mini|nano|max|turbo|astra|sol|luna|terra))*)(?:-[0-9]{4}-[0-9]{2}-[0-9]{2})?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GPT_OSS = Pattern.compile("(?:openai/)?gpt-oss-([0-9]+b)(?:-(low|medium|high|xhigh))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GEMINI = Pattern.compile("(?:google/)?gemini(?:-([0-9]+(?:[.-][0-9]+)?))?"
            + "((?:-(?:pro|flash|lite|ultra|nano|neon|carbon|hydrogen|argon|orchestrator|tiered|worker|thinking|exp))+)"
            + "(?:-(low|medium|high|xhigh))?(?:-(?:agy|[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{8}|latest))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATED_ID = Pattern.compile(".*-([0-9]{4})-?([0-9]{2})-?([0-9]{2})$");
    private NativeUsageFormat() { }

    /** Format public model identifiers only. Provider labels and unfamiliar IDs retain their identity. */
    public static String modelName(String value) {
        if (value == null || value.isEmpty()) return "";
        Matcher claude = CLAUDE.matcher(value);
        if (claude.matches()) {
            String version = claude.group(2);
            String base = "Claude " + title(claude.group(1)) + (version == null ? "" : " " + version.replace('-', '.'));
            return claude.group(3) == null ? base : base + " (" + effortLabel(claude.group(3)) + ")";
        }
        Matcher early = EARLY_CLAUDE.matcher(value);
        if (early.matches()) return "Claude " + early.group(1).replace('-', '.') + " " + title(early.group(2));
        Matcher gemini = GEMINI.matcher(value);
        if (gemini.matches()) {
            StringBuilder label = new StringBuilder("Gemini");
            if (gemini.group(1) != null) label.append(' ').append(gemini.group(1).replace('-', '.'));
            for (String word : gemini.group(2).substring(1).split("-")) label.append(' ').append(title(word));
            if (gemini.group(3) != null) label.append(" (").append(effortLabel(gemini.group(3))).append(')');
            return label.toString();
        }
        Matcher gptOss = GPT_OSS.matcher(value);
        if (gptOss.matches()) {
            String base = "GPT-OSS " + gptOss.group(1).toUpperCase(Locale.ROOT);
            return gptOss.group(2) == null ? base : base + " (" + effortLabel(gptOss.group(2)) + ")";
        }
        Matcher gpt = GPT.matcher(value);
        if (!gpt.matches()) return value;
        String base = gpt.group(1).toLowerCase(Locale.ROOT);
        StringBuilder label = new StringBuilder(base.replace("gpt-", "GPT-"));
        String modifiers = gpt.group(2).toLowerCase(Locale.ROOT);
        if (!modifiers.isEmpty()) {
            for (String word : modifiers.substring(1).split("-")) {
                if (base.startsWith("o")) label.append('-').append(word);
                else if (modifiers.startsWith("-codex") || word.equals("spark"))
                    label.append('-').append(title(word));
                else if (word.equals("mini") || word.equals("nano") || word.equals("pro"))
                    label.append(' ').append(word);
                else label.append(' ').append(title(word));
            }
        }
        return label.toString();
    }
    private static String title(String token) {
        return token.substring(0, 1).toUpperCase(Locale.ROOT) + token.substring(1).toLowerCase(Locale.ROOT);
    }
    private static String effortLabel(String effort) {
        return "xhigh".equalsIgnoreCase(effort) ? "XHigh" : title(effort);
    }
    /** A short version note for otherwise identical product labels. */
    public static String modelVariant(String id) {
        String version;
        Matcher dated = DATED_ID.matcher(id);
        if (dated.matches()) version = dated.group(1) + "-" + dated.group(2) + "-" + dated.group(3);
        else version = id.endsWith("-latest") ? "最新版" : "未指定快照";
        if (id.startsWith("openai/")) return "OpenAI · " + version;
        if (id.startsWith("anthropic/")) return "Anthropic · " + version;
        if (id.startsWith("google/")) return "Google · " + version;
        return version;
    }
    public static Long count(Object value) {
        if (!(value instanceof Number)) return null;
        try { long result = new BigDecimal(value.toString()).longValueExact(); return result < 0 ? null : result; }
        catch (NumberFormatException | ArithmeticException invalid) { return null; }
    }
    public static Double percent(Object value) {
        if (!(value instanceof Number)) return null;
        double result = ((Number) value).doubleValue();
        return Double.isNaN(result) || Double.isInfinite(result) || result < 0 || result > 100 ? null : result;
    }
    public static String compact(Long tokens) {
        if (tokens == null || tokens < 0) return "—";
        if (tokens < 10000) return NumberFormat.getIntegerInstance(Locale.CHINA).format(tokens);
        long divisor = tokens < 100000000 ? 10000 : 100000000;
        return BigDecimal.valueOf(tokens).divide(BigDecimal.valueOf(divisor), 1, RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString() + (divisor == 10000 ? " 万" : " 亿");
    }
    public static String exact(Long tokens) {
        return tokens == null || tokens < 0 ? "未提供" : NumberFormat.getIntegerInstance(Locale.CHINA).format(tokens);
    }
    public static String percentage(Double value) {
        if (value == null || percent(value) == null) return "未提供";
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }
    public static long epochMillis(Object unixSeconds) {
        Long seconds = count(unixSeconds);
        // Quota timestamps are Unix seconds, not milliseconds; reject a mis-scaled payload.
        return seconds == null || seconds == 0 || seconds > 253402300799L ? 0 : seconds * 1000;
    }
    public static String window(long minutes, String fallback) {
        if (minutes <= 0) return fallback == null || fallback.trim().isEmpty() ? "套餐额度" : fallback;
        if (minutes % 10080 == 0) return (minutes == 10080 ? "每周" : minutes / 10080 + " 周") + "额度";
        if (minutes % 1440 == 0) return minutes / 1440 + " 天额度";
        if (minutes % 60 == 0) return minutes / 60 + " 小时额度";
        return minutes + " 分钟额度";
    }
    public static boolean expired(long resetsMillis, long nowMillis) { return resetsMillis > 0 && resetsMillis <= nowMillis; }
    public static String remaining(Double remaining, Double used, boolean stale, long resetsMillis, long nowMillis) {
        if (expired(resetsMillis, nowMillis)) return "待更新";
        Double value = percent(remaining);
        if (value == null && percent(used) != null) value = 100 - used;
        if (value == null) return "暂未提供";
        return (stale ? "上次剩余 " : "剩余 ") + percentage(value);
    }
    public static String reset(long resetsMillis, long nowMillis, boolean stale) {
        if (expired(resetsMillis, nowMillis)) return "已到重置时间，等待新额度";
        if (resetsMillis <= 0) return stale ? "额度信息待更新" : "未提供重置时间";
        long minutes = Math.max(1, (resetsMillis - nowMillis + 59999) / 60000);
        String duration = minutes >= 1440 ? minutes / 1440 + " 天 " + minutes % 1440 / 60 + " 小时"
                : minutes >= 60 ? minutes / 60 + " 小时 " + minutes % 60 + " 分钟" : minutes + " 分钟";
        return (stale ? "预计 " : "") + duration + "后重置" + (stale ? " · 待更新" : "");
    }
    /** Only product states are rendered; raw server diagnostics never become UI text. */
    public static String quotaCheck(String state) {
        if ("waiting".equals(state)) return "等待后台读取";
        if ("updated".equals(state)) return "最近一次读取成功";
        if ("no_live_data".equals(state)) return "服务方暂未返回新额度";
        if ("sign_in_required".equals(state)) return "需要在来源电脑登录 Claude Code";
        if ("update_required".equals(state)) return "来源电脑的额度读取组件需要更新";
        return "暂时无法读取新额度";
    }
    public static boolean quotaCheckSource(String selectedSource, String sourceId) {
        return sourceId != null && !sourceId.trim().isEmpty()
                && (selectedSource == null || selectedSource.equals("device:" + sourceId));
    }
    public static final class QuotaPace {
        public static final QuotaPace UNAVAILABLE = new QuotaPace("unavailable", -1, -1, 0, 0, "", "");
        public final String state;
        public final double idealUsedPercent;
        public final double idealRemainingPercent;
        public final double deltaPercent;
        public final long exhaustSeconds;
        public final String label;
        public final String compactLabel;

        QuotaPace(String state, double idealUsedPercent, double idealRemainingPercent,
                  double deltaPercent, long exhaustSeconds, String label, String compactLabel) {
            this.state = state;
            this.idealUsedPercent = idealUsedPercent;
            this.idealRemainingPercent = idealRemainingPercent;
            this.deltaPercent = deltaPercent;
            this.exhaustSeconds = exhaustSeconds;
            this.label = label;
            this.compactLabel = compactLabel;
        }

        public boolean hasTick() {
            return idealUsedPercent >= 0 && idealUsedPercent <= 100;
        }
    }

    public static String paceDuration(long seconds) {
        long minutes = Math.max(1L, (seconds + 59L) / 60L);
        if (minutes >= 1440L) {
            long days = minutes / 1440L;
            long hours = (minutes % 1440L) / 60L;
            return hours > 0 ? days + " 天 " + hours + " 小时" : days + " 天";
        }
        if (minutes >= 60L) {
            long hours = minutes / 60L;
            long remMin = minutes % 60L;
            return remMin > 0 ? hours + " 小时 " + remMin + " 分钟" : hours + " 小时";
        }
        return minutes + " 分钟";
    }

    public static QuotaPace computePace(Double remaining, Double used, long windowMinutes,
                                        long resetsMillis, long nowMillis, boolean stale) {
        if (stale || windowMinutes <= 0 || resetsMillis <= nowMillis) return QuotaPace.UNAVAILABLE;
        Double remVal = percent(remaining);
        Double usedVal = percent(used);
        if (remVal == null && usedVal == null) return QuotaPace.UNAVAILABLE;
        if (remVal == null) remVal = 100.0 - usedVal;
        if (usedVal == null) usedVal = 100.0 - remVal;
        long windowMs = windowMinutes * 60_000L;
        long remMs = resetsMillis - nowMillis;
        if (remMs <= 0 || remMs >= windowMs) return QuotaPace.UNAVAILABLE;
        long elapsedMs = windowMs - remMs;
        double idealRem = Math.max(0.0, Math.min(100.0, (remMs * 100.0) / windowMs));
        double idealUsed = 100.0 - idealRem;
        double delta = remVal - idealRem;
        if (remVal <= 0.5) {
            return new QuotaPace("exhausted", idealUsed, idealRem, delta, 0, "额度已耗尽", "已耗尽");
        }
        long warmupMs = Math.min(300_000L, (long) (windowMs * 0.03));
        if (elapsedMs < warmupMs && usedVal < 10.0) {
            return new QuotaPace("warming_up", idealUsed, idealRem, delta, 0, "", "");
        }
        if (delta <= -5.0) {
            double safeUsed = Math.max(0.5, usedVal);
            long exhaustSec = Math.max(60L, Math.round((elapsedMs / 1000.0) * (remVal / safeUsed)));
            String eta = paceDuration(exhaustSec);
            return new QuotaPace("deficit", idealUsed, idealRem, delta, exhaustSec,
                    "消耗超速 · 约 " + eta + "耗尽", "超速 · 约 " + eta + "耗尽");
        }
        if (delta >= 6.0) {
            long rounded = Math.round(delta);
            return new QuotaPace("reserve", idealUsed, idealRem, delta, 0,
                    "配速稳健 · 富余 +" + rounded + "%", "富余 +" + rounded + "%");
        }
        return new QuotaPace("on_track", idealUsed, idealRem, delta, 0, "配速均衡", "均速");
    }

    public static String day(long nowMillis) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new java.util.Date(nowMillis));
    }
}
