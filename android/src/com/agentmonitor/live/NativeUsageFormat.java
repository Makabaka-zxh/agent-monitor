package com.agentmonitor.live;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/** Presentation rules deliberately keep missing readings distinct from measured zero. */
public final class NativeUsageFormat {
    private NativeUsageFormat() { }
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
    public static String day(long nowMillis) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new java.util.Date(nowMillis));
    }
}
