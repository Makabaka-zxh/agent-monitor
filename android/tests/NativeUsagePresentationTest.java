package com.agentmonitor.live;

import java.time.Instant;
import java.time.LocalDate;

public final class NativeUsagePresentationTest {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++; if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static LocalDate day(String value) { return LocalDate.parse(value); }
    public static void main(String[] args) {
        equal(null, NativeUsagePresentation.date("2026-02-30"));
        equal(null, NativeUsagePresentation.date("26-09-26"));
        equal(null, NativeUsagePresentation.date(null));
        equal(day("2024-02-29"), NativeUsagePresentation.date("2024-02-29"));
        equal(day("2026-09-25"), NativeUsagePresentation.today(Instant.parse("2026-09-25T15:59:59Z").toEpochMilli()));
        equal(day("2026-09-26"), NativeUsagePresentation.today(Instant.parse("2026-09-25T16:00:00Z").toEpochMilli()));
        equal(day("2025-12-29"), NativeUsagePresentation.monday(day("2026-01-01")));
        LocalDate today = day("2026-09-26"), start = today.minusDays(89), week = day("2026-09-21");
        equal(week, NativeUsagePresentation.clampWeek(null, start, today));
        equal(week, NativeUsagePresentation.clampWeek(day("2027-01-01"), start, today));
        equal(NativeUsagePresentation.monday(start), NativeUsagePresentation.clampWeek(day("2020-01-01"), start, today));
        LocalDate previous = day("2026-09-14"), selected = day("2026-09-16");
        equal(previous, NativeUsagePresentation.clampWeek(previous, start, today));
        equal(selected, NativeUsagePresentation.selection(selected, previous, start, today));
        // Refreshes and Monday rollover preserve an explicitly selected older week/day.
        equal(previous, NativeUsagePresentation.clampWeek(previous, start.plusDays(2), today.plusDays(2)));
        equal(selected, NativeUsagePresentation.selection(selected, previous, start.plusDays(2), today.plusDays(2)));
        equal(today, NativeUsagePresentation.selection(null, week, start, today));
        equal(day("2026-09-20"), NativeUsagePresentation.selection(today, previous, start, today));
        equal(start, NativeUsagePresentation.selection(start, NativeUsagePresentation.monday(start), start, today));
        equal("9.21 – 9.27", NativeUsagePresentation.range(week));
        equal("12.29 – 1.4", NativeUsagePresentation.range(day("2025-12-29")));
        equal("—", NativeUsagePresentation.exact(null));
        equal("0", NativeUsagePresentation.exact(0L));
        equal("180,472,748", NativeUsagePresentation.exact(180472748L));
        equal(0d, NativeUsagePresentation.ratio(null, 100));
        equal(0d, NativeUsagePresentation.ratio(0L, 100));
        equal(.5d, NativeUsagePresentation.ratio(50L, 100));
        equal(1d, NativeUsagePresentation.ratio(Long.MAX_VALUE, Long.MAX_VALUE));
        equal(1d, NativeUsagePresentation.ratio(200L, 100));
        equal(0d, NativeUsagePresentation.ratio(100L, 0));
        equal(null, NativeUsagePresentation.remaining(null, null));
        equal(0d, NativeUsagePresentation.remaining(0d, null));
        equal(35d, NativeUsagePresentation.remaining(null, 65d));
        equal(null, NativeUsagePresentation.remaining(Double.NaN, 101d));
        equal(null, NativeUsagePresentation.used(null, null));
        equal(0d, NativeUsagePresentation.used(0d, null));
        equal(100d, NativeUsagePresentation.used(null, 0d));
        equal(65d, NativeUsagePresentation.used(null, 35d));
        equal(false, NativeUsagePresentation.quotaVisible(null, null, 0, 1000));
        equal(false, NativeUsagePresentation.quotaVisible(Double.NaN, 101d, 2000, 1000));
        equal(true, NativeUsagePresentation.quotaVisible(null, 0d, 2000, 1000));
        equal(true, NativeUsagePresentation.quotaVisible(0d, null, 2000, 1000));
        equal(false, NativeUsagePresentation.quotaIdentity("model-a", "weekly", 10080, 0)
                .equals(NativeUsagePresentation.quotaIdentity("model-b", "weekly", 10080, 0)));
        equal(false, NativeUsagePresentation.quotaIdentity("account", "weekly", 10080, 0)
                .equals(NativeUsagePresentation.quotaIdentity("account", "short", 300, 0)));
        equal(false, NativeUsagePresentation.quotaIdentity("a|b", "c", 10080, 0)
                .equals(NativeUsagePresentation.quotaIdentity("a", "b|c", 10080, 0)));
        equal(false, NativeUsagePresentation.quotaIdentity("", "", 300, 0)
                .equals(NativeUsagePresentation.quotaIdentity("", "", 300, 1)));
        modelShares();
        modelNames();
        long now = 1790000000000L;
        quotaExpiry(now);
        equal(0, NativeUsagePresentation.quotaRank(false, false, now + 1000, now));
        equal(1, NativeUsagePresentation.quotaRank(true, false, now, now));
        equal(2, NativeUsagePresentation.quotaRank(true, true, now + 1000, now));
        equal(3, NativeUsagePresentation.quotaRank(true, false, now + 1000, now));
        System.out.println("NativeUsagePresentationTest: " + checks + " checks passed");
    }
    private static void quotaExpiry(long now) {
        // An expired legacy window with no bucket has the same visibility rule as any other window.
        equal(false, NativeUsagePresentation.quotaVisible(null, 0d, now, now));
        equal(false, NativeUsagePresentation.quotaVisible(0d, null, now - 1, now));
        equal(false, NativeUsagePresentation.quotaVisible(35d, 65d, now - 1, now));
        equal(true, NativeUsagePresentation.quotaVisible(null, 0d, now + 1, now));
        equal(true, NativeUsagePresentation.quotaVisible(0d, null, now + 1, now));
        equal(false, NativeUsagePresentation.quotaVisible(null, null, now + 1, now));
        equal(false, NativeUsagePresentation.quotaVisible(Double.POSITIVE_INFINITY, -1d, now + 1, now));
        equal(true, NativeUsagePresentation.quotaVisible(null, 25d, 0, now));
        equal(true, NativeUsagePresentation.quotaVisible(null, 25d, -1, now));
        // A stale but unexpired observation can remain explicitly labelled as an old reading.
        equal(true, NativeUsagePresentation.quotaVisible(75d, null, now + 5000, now));
        long earliest = NativeUsagePresentation.nextQuotaExpiry(Long.MAX_VALUE, now + 5000, now);
        earliest = NativeUsagePresentation.nextQuotaExpiry(earliest, now + 1000, now);
        equal(now + 1000, earliest);
        equal(earliest, NativeUsagePresentation.nextQuotaExpiry(earliest, 0, now));
        equal(earliest, NativeUsagePresentation.nextQuotaExpiry(earliest, now - 1, now));
        equal(false, NativeUsagePresentation.quotaVisible(null, 0d, earliest, earliest));
        equal(true, NativeUsagePresentation.quotaVisible(null, 20d, now + 5000, earliest));
        equal(now + 5000, NativeUsagePresentation.nextQuotaExpiry(Long.MAX_VALUE, now + 5000, earliest));
        equal(false, NativeUsagePresentation.quotaVisible(null, 20d, now + 5000, now + 5000));
        equal(Long.MAX_VALUE, NativeUsagePresentation.nextQuotaExpiry(Long.MAX_VALUE, now + 5000, now + 5000));
    }
    private static void modelNames() {
        equal("GPT-5.4", NativeUsageFormat.modelName("gpt-5.4"));
        equal("GPT-5.3-Codex-Spark", NativeUsageFormat.modelName("gpt-5.3-codex-spark"));
        equal("GPT-4o mini", NativeUsageFormat.modelName("openai/gpt-4o-mini-2024-07-18"));
        equal("GPT-6 Astra", NativeUsageFormat.modelName("gpt-6-astra"));
        equal("GPT-6.1 Terra", NativeUsageFormat.modelName("gpt-6.1-terra"));
        equal("o3-mini", NativeUsageFormat.modelName("o3-mini"));
        equal("Claude Sonnet 4.5", NativeUsageFormat.modelName("anthropic/claude-sonnet-4-5-20250929"));
        equal("Claude 3.5 Sonnet", NativeUsageFormat.modelName("claude-3-5-sonnet-latest"));
        equal("Claude Opus 4.6", NativeUsageFormat.modelName("claude-opus-4.6"));
        equal("Claude Haiku 4.5", NativeUsageFormat.modelName("claude-haiku-4-5"));
        equal("Claude Opus", NativeUsageFormat.modelName("claude-opus"));
        equal("Gemini 3.8 Flash (Low)", NativeUsageFormat.modelName("gemini-3.8-flash-low"));
        equal("Gemini 4 Hydrogen (XHigh)", NativeUsageFormat.modelName("gemini-4-hydrogen-xhigh"));
        equal("Gemini Pro Orchestrator", NativeUsageFormat.modelName("gemini-pro-orchestrator"));
        equal("GPT-OSS 120B (Medium)", NativeUsageFormat.modelName("gpt-oss-120b-medium"));
        equal("gemini-future-unknown-tier", NativeUsageFormat.modelName("gemini-future-unknown-tier"));
        equal("Google · 2026-05-01", NativeUsageFormat.modelVariant("google/gemini-2.5-pro-2026-05-01"));
        equal("Sonnet", NativeUsageFormat.modelName("Sonnet"));
        equal("全部模型", NativeUsageFormat.modelName("全部模型"));
        equal("partner/chat-model-x", NativeUsageFormat.modelName("partner/chat-model-x"));
        equal("gpt-5.4-unknown-tier", NativeUsageFormat.modelName("gpt-5.4-unknown-tier"));
        equal("", NativeUsageFormat.modelName(null));
        NativeUsagePresentation.ModelShare[] variants = NativeUsagePresentation.modelShares(
                new String[]{"claude-sonnet-4-5-20250929", "claude-sonnet-4-5"}, new Long[]{3L, 1L}, 1L, 5L);
        equal(3, variants.length);
        equal("Claude Sonnet 4.5", variants[0].displayName);
        equal("claude-sonnet-4-5-20250929", variants[0].model);
        equal("未识别模型", variants[2].displayName);
        equal(60d, variants[0].percent); equal(20d, variants[1].percent);
        java.util.Map<String, String> notes = NativeUsagePresentation.modelVariantNotes(variants);
        equal(2, notes.size()); equal("2025-09-29", notes.get(variants[0].model)); equal("未指定快照", notes.get(variants[1].model));
        equal("OpenAI · 2024-07-18", NativeUsageFormat.modelVariant("openai/gpt-4o-mini-2024-07-18"));
        NativeUsagePresentation.ModelShare[] aliases = NativeUsagePresentation.modelShares(
                new String[]{"claude-opus-4-6", "claude-opus-4.6", "claude-fable-5"}, new Long[]{3L, 1L, 1L}, 0L, 5L);
        notes = NativeUsagePresentation.modelVariantNotes(aliases);
        equal("claude-opus-4-6", notes.get("claude-opus-4-6")); equal("claude-opus-4.6", notes.get("claude-opus-4.6"));
        equal(false, notes.containsKey("claude-fable-5"));
    }
    private static void modelShares() {
        NativeUsagePresentation.ModelShare[] shares = NativeUsagePresentation.modelShares(
                new String[]{"model-a", "model-b"}, new Long[]{30L, 50L}, 20L, 100L);
        equal(3, shares.length); equal("model-b", shares[0].model); equal(50d, shares[0].percent);
        equal("model-a", shares[1].model); equal(30d, shares[1].percent);
        equal(null, shares[2].model); equal(20L, shares[2].tokens); equal(20d, shares[2].percent);
        equal(0, NativeUsagePresentation.modelShares(new String[0], new Long[0], 100L, 100L).length);
        equal(0, NativeUsagePresentation.modelShares(new String[]{"model-a"}, new Long[]{0L}, 0L, 0L).length);
        equal(0, NativeUsagePresentation.modelShares(new String[]{"model-a"}, new Long[]{10L}, 0L, null).length);
        equal(0, NativeUsagePresentation.modelShares(new String[]{"model-a"}, new Long[]{10L}, null, 10L).length);
        equal(0, NativeUsagePresentation.modelShares(new String[]{"model-a"}, new Long[]{10L}, 0L, 20L).length);
        equal(0, NativeUsagePresentation.modelShares(new String[]{"model-a"}, new Long[]{10L}, 0L, 5L).length);
        equal(0, NativeUsagePresentation.modelShares(new String[]{"model-a"}, new Long[]{-1L}, 11L, 10L).length);
        equal(0, NativeUsagePresentation.modelShares(new String[]{"model-a", "model-a"}, new Long[]{5L, 5L}, 0L, 10L).length);
        equal(0, NativeUsagePresentation.modelShares(new String[]{"bad\nmodel"}, new Long[]{10L}, 0L, 10L).length);
        equal(0, NativeUsagePresentation.modelShares(new String[]{null}, new Long[]{10L}, 0L, 10L).length);
        equal(0, NativeUsagePresentation.modelShares(new String[]{"model-a"}, new Long[]{Long.MAX_VALUE}, 1L, Long.MAX_VALUE).length);
        equal(100d, NativeUsagePresentation.modelShares(new String[]{"model-a"}, new Long[]{Long.MAX_VALUE}, 0L, Long.MAX_VALUE)[0].percent);
        NativeUsagePresentation.ModelShare[] measuredZero = NativeUsagePresentation.modelShares(new String[]{"model-a"}, new Long[]{0L}, 10L, 10L);
        equal(2, measuredZero.length); equal(0d, measuredZero[0].percent); equal(100d, measuredZero[1].percent);
    }
}
