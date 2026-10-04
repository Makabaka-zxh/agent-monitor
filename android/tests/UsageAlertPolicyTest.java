package com.agentmonitor.live;

public final class UsageAlertPolicyTest {
    private static int checks;
    private static final long NOW = 1800000000000L;
    private static final long SOON = NOW / 1000 + 300, LATER = NOW / 1000 + 3600;
    private static void check(boolean value, String label) { ++checks; if (!value) throw new AssertionError(label); }
    public static void main(String[] args) {
        check(UsageAlertPolicy.fresh(0, NOW, SOON, false, NOW), "real zero remaining can trigger exhausted notice");
        check(UsageAlertPolicy.fresh(100, NOW, SOON, false, NOW), "real full balance can trigger reset notice");
        for (double bad : new double[]{-1, 101, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            check(!UsageAlertPolicy.fresh(bad, NOW, SOON, false, NOW), "invalid balance does not trigger: " + bad);
        check(!UsageAlertPolicy.fresh(5, NOW, SOON, true, NOW), "server stale overrides numeric balance");
        check(!UsageAlertPolicy.fresh(5, 0, SOON, false, NOW), "missing observation cannot trigger");
        check(UsageAlertPolicy.fresh(5, NOW - 15 * 60000L, SOON, false, NOW), "fifteen-minute boundary accepted");
        check(!UsageAlertPolicy.fresh(5, NOW - 15 * 60000L - 1, SOON, false, NOW), "past fifteen minutes is stale");
        check(UsageAlertPolicy.fresh(5, NOW + 60000, SOON, false, NOW), "bounded source clock skew accepted");
        check(!UsageAlertPolicy.fresh(5, NOW + 60001, SOON, false, NOW), "large future timestamp rejected");
        check(!UsageAlertPolicy.fresh(5, NOW, NOW / 1000, false, NOW), "elapsed reset is not evidence of new quota");
        check(!UsageAlertPolicy.fresh(5, NOW, NOW / 1000 - 1, false, NOW), "past reset never triggers stale near-reset");
        check(!UsageAlertPolicy.fresh(5, NOW, NOW / 1000 + 366L * 86400, false, NOW), "implausible reset excluded");
        check(UsageAlertPolicy.pendingKinds(5, 10, LATER, 15, NOW, false, false) == 1, "low quota alone");
        check(UsageAlertPolicy.pendingKinds(5, 10, LATER, 15, NOW, true, false) == 0, "repeat polling cannot repeat low notice");
        check(UsageAlertPolicy.pendingKinds(5, 10, SOON, 15, NOW, true, false) == 2, "later near-reset remains independently eligible");
        check(UsageAlertPolicy.pendingKinds(5, 10, SOON, 15, NOW, false, false) == 3, "simultaneous reasons combined");
        check(UsageAlertPolicy.pendingKinds(5, 10, SOON, 15, NOW, true, true) == 0, "both delivered kinds remain silent");
        check(UsageAlertPolicy.pendingKinds(0, 0, SOON, 0, NOW, false, false) == 0, "each reminder can be independently disabled");
        check(UsageAlertPolicy.pendingKinds(10, 10, SOON, 0, NOW, false, false) == 1, "threshold equality triggers");
        check(UsageAlertPolicy.pendingKinds(10.1, 10, SOON, 0, NOW, false, false) == 0, "just above threshold waits");
        String base = UsageAlertPolicy.windowIdentity("account", "codex", "five_hour", "desktop", SOON);
        check(base.equals(UsageAlertPolicy.windowIdentity("account", "codex", "five_hour", "desktop", SOON)), "same source window deduplicates");
        check(!base.equals(UsageAlertPolicy.windowIdentity("other", "codex", "five_hour", "desktop", SOON)), "another native account isolated");
        check(!base.equals(UsageAlertPolicy.windowIdentity("account", "claude", "five_hour", "desktop", SOON)), "provider windows isolated");
        check(!base.equals(UsageAlertPolicy.windowIdentity("account", "codex", "weekly", "desktop", SOON)), "independent quota windows isolated");
        check(!base.equals(UsageAlertPolicy.windowIdentity("account", "codex", "five_hour", "mac", SOON)), "unverified computer accounts never merged");
        check(!base.equals(UsageAlertPolicy.windowIdentity("account", "codex", "five_hour", "desktop", SOON + 1)), "new observed reset creates new window");
        check(!UsageAlertPolicy.windowIdentity("a", "codex", "source|device", "part", SOON)
                .equals(UsageAlertPolicy.windowIdentity("a", "codex", "source", "device|part", SOON)), "separators cannot collide across fields");
        long lastActive = NOW - 4 * 3600000L;
        check(UsageAlertPolicy.eligibleKinds(3, lastActive, SOON, 300, true, 10, 15, NOW, false, false) == 2,
                "Claude inactive four hours: stale balance cannot alert low, recorded five-hour reset can alert soon");
        check(UsageAlertPolicy.eligibleKinds(3, lastActive, SOON, 300, false, 10, 15, NOW, false, false) == 2,
                "local age protects against stale=false cached by backend");
        check(UsageAlertPolicy.eligibleKinds(3, NOW, SOON, 300, false, 10, 15, NOW, false, false) == 3,
                "fresh balance and valid recorded reset combine");
        check(UsageAlertPolicy.eligibleKinds(3, NOW, SOON, 300, true, 10, 15, NOW, false, false) == 2,
                "explicitly stale reading never triggers low even with recent observation");
        check(UsageAlertPolicy.eligibleKinds(Double.NaN, lastActive, SOON, 300, true, 10, 15, NOW, false, false) == 2,
                "recorded reset is independent of missing balance");
        check(UsageAlertPolicy.eligibleKinds(3, lastActive, SOON, 300, true, 10, 15, NOW, false, true) == 0,
                "stale recorded reset still deduplicates");
        check(UsageAlertPolicy.eligibleKinds(3, lastActive, SOON, 300, true, 10, 0, NOW, false, false) == 0,
                "reset toggle still disables estimated reminder");
        check(!UsageAlertPolicy.recordedReset(NOW - 300 * 60000L - 1, SOON, 300, NOW),
                "observation older than actual window cannot schedule reset reminder");
        check(UsageAlertPolicy.recordedReset(NOW - 6 * 86400000L, SOON, 10080, NOW),
                "weekly recorded reset stays usable for its actual seven-day window");
        check(!UsageAlertPolicy.recordedReset(NOW - 8 * 86400000L, SOON, 10080, NOW),
                "eight-day-old weekly observation rejected");
        check(!UsageAlertPolicy.recordedReset(NOW + 60001, SOON, 300, NOW), "implausibly future observation rejected");
        check(!UsageAlertPolicy.recordedReset(0, SOON, 300, NOW), "missing observation rejected for reset too");
        check(!UsageAlertPolicy.recordedReset(lastActive, SOON, 0, NOW), "missing actual window rejected");
        check(!UsageAlertPolicy.recordedReset(lastActive, SOON, -1, NOW), "negative actual window rejected");
        check(!UsageAlertPolicy.recordedReset(lastActive, SOON, Long.MAX_VALUE, NOW), "unbounded actual window rejected");
        check(!UsageAlertPolicy.recordedReset(lastActive, NOW / 1000, 300, NOW), "at reset instant no claim of restored quota");
        check(!UsageAlertPolicy.recordedReset(lastActive, NOW / 1000 - 1, 300, NOW), "past reset never repeats reminder");
        check(!UsageAlertPolicy.recordedReset(lastActive, NOW / 1000 + 2 * 3600, 300, NOW),
                "reset inconsistent with recorded five-hour source window rejected");
        check(!UsageAlertPolicy.observationFresh(NOW - 15 * 60000L - 1, NOW), "UI locally marks age stale without a backend refresh");
        check(UsageAlertPolicy.observationFresh(NOW - 15 * 60000L, NOW), "UI freshness boundary agrees with alerts");
        System.out.println("UsageAlertPolicyTest: " + checks + " checks passed");
    }
}
