package com.agentmonitor.live;

public final class NativeUsageFormatTest {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++; if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        equal(null, NativeUsageFormat.count(null));
        equal(null, NativeUsageFormat.count("12"));
        equal(null, NativeUsageFormat.count(-1));
        equal(null, NativeUsageFormat.count(1.5));
        equal(0L, NativeUsageFormat.count(0));
        equal(9007199254740993L, NativeUsageFormat.count(9007199254740993L));
        equal(null, NativeUsageFormat.count(Double.NaN));
        equal(null, NativeUsageFormat.count(Double.POSITIVE_INFINITY));
        equal("—", NativeUsageFormat.compact(null));
        equal("0", NativeUsageFormat.compact(0L));
        equal("9,999", NativeUsageFormat.compact(9999L));
        equal("1 万", NativeUsageFormat.compact(10000L));
        equal("12.3 万", NativeUsageFormat.compact(123999L));
        equal("9999.9 万", NativeUsageFormat.compact(99999999L));
        equal("1 亿", NativeUsageFormat.compact(100000000L));
        equal("9,007,199,254,740,993", NativeUsageFormat.exact(9007199254740993L));
        equal("未提供", NativeUsageFormat.exact(null));
        equal(null, NativeUsageFormat.percent(null));
        equal(null, NativeUsageFormat.percent(101));
        equal(null, NativeUsageFormat.percent(Double.NaN));
        equal("0%", NativeUsageFormat.percentage(0d));
        equal("7.5%", NativeUsageFormat.percentage(7.49));
        equal("5 小时额度", NativeUsageFormat.window(300, ""));
        equal("每周额度", NativeUsageFormat.window(10080, "primary"));
        equal("30 分钟额度", NativeUsageFormat.window(30, ""));
        equal("7 小时额度", NativeUsageFormat.window(420, ""));
        equal("套餐额度", NativeUsageFormat.window(0, ""));
        equal("自定义", NativeUsageFormat.window(0, "自定义"));
        long now = 1790000000000L;
        equal(now, NativeUsageFormat.epochMillis(1790000000L));
        equal(0L, NativeUsageFormat.epochMillis(1790000000000L));
        equal(0L, NativeUsageFormat.epochMillis(null));
        equal("暂未提供", NativeUsageFormat.remaining(null, null, false, now + 1000, now));
        equal("剩余 0%", NativeUsageFormat.remaining(0d, 100d, false, now + 1000, now));
        equal("剩余 12%", NativeUsageFormat.remaining(null, 88d, false, now + 1000, now));
        equal("上次剩余 12%", NativeUsageFormat.remaining(12d, 88d, true, now + 1000, now));
        equal("待更新", NativeUsageFormat.remaining(100d, 0d, false, now, now));
        equal("待更新", NativeUsageFormat.remaining(null, null, true, now - 1, now));
        equal("未提供重置时间", NativeUsageFormat.reset(0, now, false));
        equal("额度信息待更新", NativeUsageFormat.reset(0, now, true));
        equal("1 分钟后重置", NativeUsageFormat.reset(now + 1, now, false));
        equal("1 小时 1 分钟后重置", NativeUsageFormat.reset(now + 3600001, now, false));
        equal("预计 1 天 0 小时后重置 · 待更新", NativeUsageFormat.reset(now + 86400000, now, true));
        equal("已到重置时间，等待新额度", NativeUsageFormat.reset(now, now, false));
        equal("1970-01-01", NativeUsageFormat.day(57599999));
        equal("1970-01-02", NativeUsageFormat.day(57600000));
        equal("服务方暂未返回新额度", NativeUsageFormat.quotaCheck("no_live_data"));
        equal("最近一次读取成功", NativeUsageFormat.quotaCheck("updated"));
        equal("暂时无法读取新额度", NativeUsageFormat.quotaCheck("PRIVATE token /path"));
        equal("暂时无法读取新额度", NativeUsageFormat.quotaCheck(null));
        equal(true, NativeUsageFormat.quotaCheckSource("device:windows", "windows"));
        equal(false, NativeUsageFormat.quotaCheckSource("device:mac", "windows"));
        equal(false, NativeUsageFormat.quotaCheckSource("unknown:windows", "windows"));
        equal(false, NativeUsageFormat.quotaCheckSource(null, ""));
        equal(false, NativeUsageFormat.quotaCheckSource(null, "   "));
        equal(true, NativeUsageFormat.quotaCheckSource(null, "windows"));
        System.out.println("NativeUsageFormatTest: " + checks + " checks passed");
    }
}
