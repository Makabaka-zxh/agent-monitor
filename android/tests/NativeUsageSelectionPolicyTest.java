package com.agentmonitor.live;

import java.time.LocalDate;

/** Synthetic navigation/restoration cases, no Android account state or real source identities. */
public final class NativeUsageSelectionPolicyTest {
    private static int checks;
    private static void check(boolean result, String message) { checks++; if (!result) throw new AssertionError(message); }
    private static LocalDate day(String value) { return LocalDate.parse(value); }

    public static void main(String[] args) {
        LocalDate today = day("2026-10-21"), start = today.minusDays(89);
        NativeUsageSelectionPolicy.Selection saved = NativeUsageSelectionPolicy.restore("2026-10-12", "2026-10-14", "device:synthetic-b");
        NativeUsageSelectionPolicy.Calendar loading = NativeUsageSelectionPolicy.calendar(saved, today, today, false);
        check(loading.day.equals(today), "loading renders a usable current-day placeholder");
        check(saved.week.equals(day("2026-10-12")) && saved.day.equals(day("2026-10-14")), "loading does not overwrite restored history choices");
        NativeUsageSelectionPolicy.Calendar ready = NativeUsageSelectionPolicy.calendar(saved, start, today, true);
        check(ready.week.equals(day("2026-10-12")) && ready.day.equals(day("2026-10-14")), "first data arrival retains selected historical week and day");
        // Tab exit/recreation stores only the three bounded values.
        NativeUsageSelectionPolicy.Selection recreated = NativeUsageSelectionPolicy.restore(saved.week.toString(), saved.day.toString(), saved.source);
        NativeUsageSelectionPolicy.calendar(recreated, today, today, false);
        NativeUsageSelectionPolicy.Calendar returned = NativeUsageSelectionPolicy.calendar(recreated, start, today, true);
        check(returned.week.equals(ready.week) && returned.day.equals(ready.day), "tab return and process restoration keep historical selection");
        check(recreated.source.equals("device:synthetic-b"), "restoration retains the exact chosen synthetic source");
        NativeUsageSelectionPolicy.Calendar rollover = NativeUsageSelectionPolicy.calendar(recreated, start.plusDays(5), today.plusDays(5), true);
        check(rollover.week.equals(ready.week) && rollover.day.equals(ready.day), "a new calendar week does not replace an explicit selection");
        NativeUsageSelectionPolicy.Selection ancient = NativeUsageSelectionPolicy.restore("2020-01-06", "2020-01-08", "");
        NativeUsageSelectionPolicy.Calendar clamped = NativeUsageSelectionPolicy.calendar(ancient, start, today, true);
        check(clamped.week.equals(NativeUsagePresentation.monday(start)), "expired historical week clamps to available range");
        check(!clamped.day.isBefore(start), "expired selection never displays outside retained history");
        NativeUsageSelectionPolicy.Selection future = NativeUsageSelectionPolicy.restore("2099-01-05", "2099-01-06", "");
        check(NativeUsageSelectionPolicy.calendar(future, start, today, true).day.equals(today), "future restored day clamps to today");
        NativeUsageSelectionPolicy.Selection invalid = NativeUsageSelectionPolicy.restore("2026-02-30", "not-a-date", "bad\nsource");
        check(invalid.week == null && invalid.day == null && invalid.source.isEmpty(), "malformed saved fields are discarded");
        char[] oversized = new char[1025]; java.util.Arrays.fill(oversized, 'a');
        check(NativeUsageSelectionPolicy.source(new String(oversized)).isEmpty(), "source restoration is bounded");
        check(NativeUsageSelectionPolicy.source(null).isEmpty(), "missing source is safe");

        String[] available = {"device:synthetic-a", "device:synthetic-b"};
        check(NativeUsageSelectionPolicy.sourceAfterSnapshot(saved.source, available, available[0], true).equals(available[1]), "fresh data keeps selected source even if another ranks better");
        check(NativeUsageSelectionPolicy.sourceAfterSnapshot(saved.source, null, "", false).equals(saved.source), "loading does not discard source selection");
        check(NativeUsageSelectionPolicy.sourceAfterSnapshot(saved.source, new String[]{available[0]}, available[0], true).equals(available[0]), "removed source falls back to best available source");
        check(NativeUsageSelectionPolicy.sourceAfterSnapshot(saved.source, new String[0], "", true).isEmpty(), "authoritative empty quota list clears missing source");
        check(NativeUsageSelectionPolicy.sourceAfterSnapshot(saved.source, available, "missing", true).equals(available[1]), "invalid preferred source cannot replace a valid choice");
        check(NativeUsageSelectionPolicy.sourceAfterSnapshot("missing", available, "also-missing", true).equals(available[0]), "unavailable preferred source falls back to an actual source");
        check(NativeUsageSelectionPolicy.sourceAfterSnapshot("missing", new String[]{null, "", "bad\rkey"}, "", true).isEmpty(), "malformed sources are not restored");
        check(NativeUsageSelectionPolicy.restore("", "", "").source.isEmpty(), "new account state starts empty");
        System.out.println("NativeUsageSelectionPolicyTest: " + checks + " checks passed");
    }
}
