package com.agentmonitor.live;

/** Pure quota decisions: no fabricated balances and no elapsed-reset assumption. */
public final class UsageAlertPolicy {
    private UsageAlertPolicy() {}
    public static boolean fresh(double remaining, long observedMs, long resetSeconds, boolean stale, long now) {
        return !stale && Double.isFinite(remaining) && remaining >= 0 && remaining <= 100
                && observationFresh(observedMs, now)
                && resetSeconds > now / 1000 && resetSeconds < now / 1000 + 366L * 86400;
    }
    public static boolean observationFresh(long observedMs, long now) {
        return observedMs > 0 && observedMs <= now + 60000 && now - observedMs <= 15 * 60000L;
    }
    /** A recorded future reset can remain useful after the balance itself goes stale. */
    public static boolean recordedReset(long observedMs, long resetSeconds, long windowMinutes, long now) {
        if (windowMinutes <= 0 || windowMinutes > 366L * 1440 || observedMs <= 0
                || observedMs > now + 60000 || resetSeconds <= now / 1000) return false;
        long windowMs = windowMinutes * 60000L;
        return now - observedMs <= windowMs
                && resetSeconds <= (observedMs + windowMs + 60000) / 1000;
    }
    public static boolean low(double remaining, int threshold) { return threshold > 0 && remaining <= threshold; }
    public static boolean nearReset(long resetSeconds, int minutes, long now) {
        return minutes > 0 && resetSeconds > now / 1000 && resetSeconds - now / 1000 <= minutes * 60L;
    }
    /** Stable window identity: provider source and native account are never silently merged. */
    public static String windowIdentity(String account, String tool, String source, String device, long resetSeconds) {
        return field(account) + field(tool) + field(source) + field(device) + resetSeconds;
    }
    private static String field(String value) { return value == null ? "-1:" : value.length() + ":" + value; }
    /** Bit 1 = quota low, bit 2 = reset soon. Each kind is delivered once in this source window. */
    public static int pendingKinds(double remaining, int lowThreshold, long resets, int resetMinutes, long now,
                                   boolean lowAlreadySent, boolean resetAlreadySent) {
        return (low(remaining, lowThreshold) && !lowAlreadySent ? 1 : 0)
                | (nearReset(resets, resetMinutes, now) && !resetAlreadySent ? 2 : 0);
    }
    /** Balance freshness and recorded-reset validity are deliberately independent. */
    public static int eligibleKinds(double remaining, long observedMs, long resets, long windowMinutes, boolean stale,
                                   int lowThreshold, int resetMinutes, long now, boolean lowSent, boolean resetSent) {
        int pending = pendingKinds(remaining, lowThreshold, resets, resetMinutes, now, lowSent, resetSent);
        return (fresh(remaining, observedMs, resets, stale, now) ? pending & 1 : 0)
                | (recordedReset(observedMs, resets, windowMinutes, now) ? pending & 2 : 0);
    }
}
