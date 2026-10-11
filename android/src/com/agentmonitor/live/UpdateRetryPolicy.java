package com.agentmonitor.live;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Bounded interpretation of GitHub retry hints; never retains or displays header text. */
final class UpdateRetryPolicy {
    static final long DEFAULT_WAIT_MS = 60 * 1000L;
    static final long MAX_WAIT_MS = 24 * 60 * 60 * 1000L;

    /** Zero means no positive, explicit rate-limit signal for this response. */
    static long waitMillis(int status, String retryAfter, String remaining, String reset, long nowMillis) {
        if (status != 403 && status != 429) return 0;
        long retry = retryAfterMillis(retryAfter, nowMillis);
        Long count = unsigned(remaining);
        boolean exhausted = count != null && count == 0;
        if (retry > 0) return retry;
        if (status != 429 && !exhausted) return 0;
        if (exhausted) {
            Long epoch = unsigned(reset);
            if (epoch != null && epoch <= Long.MAX_VALUE / 1000) {
                long wait = futureMillis(epoch * 1000, nowMillis);
                if (wait > 0) return wait;
            }
        }
        return DEFAULT_WAIT_MS;
    }

    private static long retryAfterMillis(String raw, long nowMillis) {
        String value = header(raw);
        if (value == null) return 0;
        Long seconds = unsigned(value);
        if (seconds != null) return seconds > 0 ? Math.min(seconds, MAX_WAIT_MS / 1000) * 1000 : 0;
        // Accept only IMF-fixdate in GMT, not locale-specific or lenient calendar dates.
        if (!value.matches("[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT")) return 0;
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT")); format.setLenient(false);
        ParsePosition position = new ParsePosition(0); Date date = format.parse(value, position);
        if (date == null || position.getIndex() != value.length() || !format.format(date).equals(value)) return 0;
        return futureMillis(date.getTime(), nowMillis);
    }

    private static long futureMillis(long target, long now) {
        if (now < 0 || target <= now) return 0;
        return Math.min(MAX_WAIT_MS, target - now);
    }

    private static Long unsigned(String raw) {
        String value = header(raw);
        if (value == null || !value.matches("[0-9]{1,19}")) return null;
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { return null; }
    }

    private static String header(String raw) {
        if (raw == null || raw.length() > 64) return null;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 32 && c != '\t' || c > 126) return null;
        }
        int start = 0, end = raw.length();
        while (start < end && (raw.charAt(start) == ' ' || raw.charAt(start) == '\t')) start++;
        while (end > start && (raw.charAt(end - 1) == ' ' || raw.charAt(end - 1) == '\t')) end--;
        return start == end ? null : raw.substring(start, end);
    }

    private UpdateRetryPolicy() { }
}
