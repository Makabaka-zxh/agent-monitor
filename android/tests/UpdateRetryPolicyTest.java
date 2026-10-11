package com.agentmonitor.live;

/** Pure Java retry-header boundaries; no clock changes, sockets, files or Android runtime. */
public final class UpdateRetryPolicyTest {
    private static int checks;
    private static final long NOW = 1700000000000L;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static long wait(int status, String retry, String remaining, String reset) {
        return UpdateRetryPolicy.waitMillis(status, retry, remaining, reset, NOW);
    }
    public static void main(String[] args) {
        check(UpdateRetryPolicy.MAX_WAIT_MS == 86400000L, "one-day maximum wait");
        check(UpdateRetryPolicy.DEFAULT_WAIT_MS == 60000L, "missing hints wait one minute");
        check(wait(429, null, null, null) == 60000, "429 alone is explicit throttling");
        check(wait(403, null, null, null) == 0, "ordinary forbidden is not inferred throttling");
        for (int status : new int[]{200, 301, 302, 401, 404, 500, 503})
            check(wait(status, "120", "0", "1700009999") == 0, "other status ignores retry hints");
        check(wait(429, "1", null, null) == 1000, "valid short server wait is respected");
        check(wait(403, "75", null, null) == 75000, "valid Retry-After identifies limited forbidden");
        check(wait(429, "2", "0", "1700000060") == 2000, "Retry-After precedes reset");
        check(wait(429, "86400", null, null) == 86400000, "maximum numeric wait accepted");
        check(wait(429, "86401", null, null) == 86400000, "numeric wait clamped before multiplication");
        check(wait(403, "9223372036854775807", null, null) == 86400000, "long maximum cannot overflow millis");
        check(wait(403, " \t30\t ", null, null) == 30000, "outer HTTP optional whitespace accepted");
        for (String bad : new String[]{null, "", " ", "0", "-1", "+2", "1.5", "1e3", "1, 2", "2 0", "2\t0", "\n60", "60\r\nX: x",
                "9223372036854775808", "00000000000000000001", "９０", repeat('1', 65)}) {
            check(wait(403, bad, null, null) == 0, "invalid or nonpositive hint cannot classify forbidden");
            check(wait(429, bad, null, null) == 60000, "invalid or nonpositive 429 hint has bounded fallback");
        }
        check(wait(403, null, "0", "1700000060") == 60000, "exhausted primary limit uses reset epoch");
        check(wait(403, null, "0", "1700000001") == 1000, "one second primary reset");
        check(wait(403, null, "0", "1700999999") == 86400000, "distant reset is capped");
        check(wait(403, "invalid", "0", "1700000065") == 65000, "invalid Retry-After permits valid reset");
        check(wait(403, null, "0", null) == 60000, "exhausted count is explicit even without reset");
        for (String bad : new String[]{null, "", "-1", "+1", "0", "1699999999", "1700000000", "9223372036854775807", "9223372036854775808", "1700000100\n"})
            check(wait(403, null, "0", bad) == 60000, "bad or elapsed exhausted reset safely falls back");
        for (String count : new String[]{null, "", "1", "60", "-0", "+0", "0.0", "zero", "0\n", repeat('0', 65)})
            check(wait(403, null, count, "1700001000") == 0, "reset alone is not evidence of exhaustion");
        check(wait(429, null, "3", "1700001000") == 60000, "nonexhausted reset ignored for secondary limit");
        check(wait(403, "Tue, 14 Nov 2023 22:14:20 GMT", null, null) == 60000, "strict HTTP-date accepted");
        check(wait(403, "Thu, 16 Nov 2023 22:13:20 GMT", null, null) == 86400000, "HTTP-date cap");
        for (String date : new String[]{"Tue, 14 Nov 2023 22:13:20 GMT", "Tue, 14 Nov 2023 22:12:20 GMT",
                "Wed, 14 Nov 2023 22:14:20 GMT", "Tue, 14 Nov 2023 22:14:20 UTC", "Tue, 14 Nov 2023 22:14:20 +0000",
                "Tue, 14 Nov 2023 22:14:60 GMT", "Tue, 14 Nov 2023 24:14:20 GMT", "Fri, 31 Nov 2023 22:14:20 GMT",
                "Tuesday, 14-Nov-23 22:14:20 GMT", "Tue Nov 14 22:14:20 2023", "Tue, 14 Nov 2023 22:14:20 GMTjunk"}) {
            check(wait(403, date, null, null) == 0, "malformed or elapsed date does not classify forbidden");
            check(wait(429, date, null, null) == 60000, "malformed or elapsed date keeps bounded fallback");
        }
        check(UpdateRetryPolicy.waitMillis(429, null, "0", "9223372036854775", Long.MAX_VALUE - 50) == 60000,
                "extreme current wall clock cannot overflow into a positive reset");
        check(UpdateRetryPolicy.waitMillis(403, null, "0", "1700000060", -1) == 60000,
                "invalid wall clock cannot create an unbounded wait");
        System.out.println("UpdateRetryPolicyTest: " + checks + " checks passed");
    }
    private static String repeat(char c, int length) { char[] value = new char[length]; java.util.Arrays.fill(value, c); return new String(value); }
}
