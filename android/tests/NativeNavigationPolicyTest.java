package com.agentmonitor.live;

import java.util.Arrays;

/** Notification identity and history boundaries, without Android or live accounts. */
public final class NativeNavigationPolicyTest {
    private static int checks;
    private static final String ORIGIN = NativeWebPolicy.ORIGIN;
    private static final String ACTION = NativeNavigationPolicy.NOTIFICATION_ACTION;
    private static void check(boolean condition, String scenario) {
        checks++;
        if (!condition) throw new AssertionError(scenario);
    }
    private static String repeated(int count) {
        char[] value = new char[count]; Arrays.fill(value, 'a'); return new String(value);
    }
    private static String target(String uri) { return NativeNavigationPolicy.notificationUrl(ACTION, uri); }

    public static void main(String[] args) {
        check(ACTION.equals("com.agentmonitor.live.OPEN_TASK"), "notification action is fixed and app-specific");
        String taskA = "local:codex:task_A", taskB = "home:claude:task-B";
        String a = NativeNavigationPolicy.notificationUri(taskA, 10);
        String b = NativeNavigationPolicy.notificationUri(taskB, 10);
        String laterA = NativeNavigationPolicy.notificationUri(taskA, 11);
        check(a.equals("agentmonitor://task/local%3Acodex%3Atask_A?generation=10"), "task identifier is encoded in notification data");
        check(!a.equals(b) && !a.equals(laterA) && !b.equals(laterA), "different task or generation has a distinct PendingIntent identity");
        check(target(a).equals(ORIGIN + "/#/task/local%3Acodex%3Atask_A"), "old A notification still opens A after B or later A is constructed");
        check(target(b).equals(ORIGIN + "/#/task/home%3Aclaude%3Atask-B"), "B notification keeps its own task");
        check(target(a).equals(target(laterA)), "generation distinguishes notifications without replacing their original task");
        check(target(NativeNavigationPolicy.notificationUri("a", 0)).equals(ORIGIN + "/#/task/a"), "zero generation is valid");
        check(target(NativeNavigationPolicy.notificationUri("a", Long.MAX_VALUE)).equals(ORIGIN + "/#/task/a"), "maximum nonnegative long round-trips");
        check(target(NativeNavigationPolicy.notificationUri("..", 1)).equals(ORIGIN + "/#/task/.."), "permitted dot identifier remains in a fixed hash route");
        check(target(NativeNavigationPolicy.notificationUri(repeated(512), 1)) != null, "maximum task length round-trips");
        check(NativeNavigationPolicy.notificationUri("a", -1) == null, "negative generation cannot create a notification");
        for (String invalid : new String[]{null, "", repeated(513), "a/b", "a%2Fb", "a b", "a?b", "a#b", "a&b", "任务", "a\n"}) {
            check(NativeNavigationPolicy.notificationUri(invalid, 1) == null, "invalid task cannot create notification data");
        }
        for (String wrongAction : new String[]{null, "", "android.intent.action.VIEW", "com.agentmonitor.live.OPEN_TASK.extra", "agentmonitor://track"}) {
            check(NativeNavigationPolicy.notificationUrl(wrongAction, a) == null, "ordinary or expanded intent actions do not consume notification navigation");
        }
        for (String invalid : new String[]{
                null, "", "not a URI", "https://task/a?generation=1", "agentmonitor://track/a?generation=1",
                "agentmonitor://task.evil/a?generation=1", "agentmonitor://user@task/a?generation=1",
                "agentmonitor://task:443/a?generation=1", "agentmonitor://task/a?generation=1#fragment",
                "agentmonitor://task/a", "agentmonitor://task/a?", "agentmonitor://task/a?generation=",
                "agentmonitor://task/a?generation=-1", "agentmonitor://task/a?generation=+1",
                "agentmonitor://task/a?generation=1.0", "agentmonitor://task/a?generation=%31",
                "agentmonitor://task/a?generation=9223372036854775808", "agentmonitor://task/a?generation=10000000000000000000",
                "agentmonitor://task/a?generation=1&generation=2", "agentmonitor://task/a?generation=1&task_id=b",
                "agentmonitor://task/a?generation=1;command=track", "agentmonitor://task/a?generation=1&",
                "agentmonitor://task/a?gener%61tion=1", "agentmonitor://task/?generation=1",
                "agentmonitor://task/a/b?generation=1", "agentmonitor://task//a?generation=1",
                "agentmonitor://task/a%2Fb?generation=1", "agentmonitor://task/a%252Fb?generation=1",
                "agentmonitor://task/%252e%252e?generation=1", "agentmonitor://task/a%3Ftask=b?generation=1",
                "agentmonitor://task/a%23fragment?generation=1", "agentmonitor://task/%00?generation=1",
                "agentmonitor://task/a+b?generation=1", "agentmonitor://task/%GG?generation=1",
                "agentmonitor://task/" + repeated(513) + "?generation=1"}) {
            check(target(invalid) == null, "malformed or expanded notification data cannot select a task");
        }
        check(target("agentmonitor://task/local:codex:a?generation=2").equals(ORIGIN + "/#/task/local%3Acodex%3Aa"), "accepted task IDs are re-encoded into a fixed origin hash URL");

        String tasks = ORIGIN + "/#/tasks", profile = ORIGIN + "/#/profile", detail = target(a);
        String[] history = {tasks, "about:blank", "https://evil.invalid/", profile, detail};
        String[] original = history.clone();
        check(NativeNavigationPolicy.previousTrustedIndex(history, 4) == 3, "back selects nearest trusted prior page");
        check(NativeNavigationPolicy.previousTrustedIndex(history, 3) == 0, "blank and foreign entries are skipped");
        check(NativeNavigationPolicy.previousTrustedIndex(history, 2) == 0, "untrusted current entry may return to a prior workbench");
        check(NativeNavigationPolicy.previousTrustedIndex(history, 0) == -1, "root page has no preceding entry");
        check(NativeNavigationPolicy.previousTrustedIndex(new String[]{"about:blank", detail}, 1) == -1, "blank-only past has no back target");
        check(Arrays.equals(history, original), "history selection never rewrites entries");
        for (int index : new int[]{-1, 5, Integer.MAX_VALUE}) {
            check(NativeNavigationPolicy.previousTrustedIndex(history, index) == -1, "invalid current index fails closed");
        }
        check(NativeNavigationPolicy.previousTrustedIndex(null, 0) == -1, "missing history has no back target");
        check(NativeNavigationPolicy.previousTrustedIndex(new String[0], 0) == -1, "empty history has no back target");
        check(NativeNavigationPolicy.previousTrustedIndex(new String[]{null, tasks}, 1) == -1, "null entry is not trusted");

        String[] safe = {tasks, profile, detail};
        check(NativeNavigationPolicy.restorableHistory(safe, 1, false), "trusted history restores current page with forward history");
        check(!NativeNavigationPolicy.restorableHistory(safe, 1, true), "logout pending blocks restoration even when URLs are trusted");
        check(!NativeNavigationPolicy.restorableHistory(history, 4, false), "mixed trusted and blank history is not restorable");
        check(!NativeNavigationPolicy.restorableHistory(new String[]{tasks, "https://evil.invalid/"}, 0, false), "untrusted forward entry also prevents restoration");
        check(!NativeNavigationPolicy.restorableHistory(new String[]{null, tasks}, 1, false), "null past entry prevents restoration");
        check(!NativeNavigationPolicy.restorableHistory(null, 0, false), "missing saved history rejected");
        check(!NativeNavigationPolicy.restorableHistory(new String[0], 0, false), "empty saved history rejected");
        check(!NativeNavigationPolicy.restorableHistory(safe, -1, false), "negative saved index rejected");
        check(!NativeNavigationPolicy.restorableHistory(safe, safe.length, false), "out-of-range saved index rejected");
        check(NativeNavigationPolicy.restorableHistory(new String[]{ORIGIN + ":443/#/tasks"}, 0, false), "explicit default HTTPS port remains same origin");
        check(!NativeNavigationPolicy.restorableHistory(new String[]{ORIGIN + ":444/#/tasks"}, 0, false), "different port cannot restore a workbench");
        System.out.println("NativeNavigationPolicyTest: " + checks + " checks passed");
    }
}
