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
        long now = 1790000000000L;
        equal(0, NativeUsagePresentation.quotaRank(false, false, now + 1000, now));
        equal(1, NativeUsagePresentation.quotaRank(true, false, now, now));
        equal(2, NativeUsagePresentation.quotaRank(true, true, now + 1000, now));
        equal(3, NativeUsagePresentation.quotaRank(true, false, now + 1000, now));
        System.out.println("NativeUsagePresentationTest: " + checks + " checks passed");
    }
}
