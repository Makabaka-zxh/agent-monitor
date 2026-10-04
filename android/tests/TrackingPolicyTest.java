package com.agentmonitor.live;

/** Pure Java regression checks. Run without an Android device or credentials. */
public final class TrackingPolicyTest {
    private static final long STARTED = 1_000_000L;
    private static int checks;

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static void continues(String reason, String label) {
        check("".equals(reason), label + ": expected continued tracking");
    }

    private static void stops(String reason, String label) {
        check(reason != null && !reason.isEmpty(), label + ": expected a terminal reason");
    }

    private static String state(long now, long lastSuccess, boolean networkFailed,
                                boolean exists, boolean archived, boolean online,
                                String status) {
        return TrackingPolicy.terminal(now, STARTED, lastSuccess, lastSuccess, networkFailed,
                exists, archived, online, status);
    }

    private static void testConfirmedTaskState() {
        continues(state(STARTED, STARTED, false, true, false, true, "running"),
                "new explicitly followed running task");
        continues(state(STARTED + 15_000, STARTED + 15_000, false,
                true, false, true, "running"), "successful next poll");

        for (String status : new String[]{"waiting", "error", "completed", "idle"}) {
            stops(state(STARTED + 1, STARTED + 1, false, true, false, true, status),
                    "confirmed " + status + " stops immediately");
        }
        continues(state(STARTED + 1, STARTED + 1, false, false, false, true, "running"),
                "temporarily missing task enters confirmation grace");
        stops(state(STARTED + 1, STARTED + 1, false, true, true, true, "running"),
                "archived running task stops");
        stops(state(STARTED + 1, STARTED + 1, false, true, false, false, "running"),
                "offline device overrides last known running state");
        for (String status : new String[]{"unknown", "", "future_unrecognized_status", null}) {
            continues(state(STARTED + 1, STARTED + 1, false, true, false, true, status),
                    "absent or unrecognized status is not confirmed running");
        }
    }

    private static void testTrackingDeadline() {
        check(TrackingPolicy.LIMIT_MS == 30 * 60 * 1000L, "30 minute default duration");
        continues(state(STARTED + TrackingPolicy.LIMIT_MS - 1,
                STARTED + TrackingPolicy.LIMIT_MS - 1, false, true, false, true, "running"),
                "one millisecond before deadline");
        for (long elapsed : new long[]{TrackingPolicy.LIMIT_MS, TrackingPolicy.LIMIT_MS + 1,
                TrackingPolicy.LIMIT_MS + 60_000}) {
            stops(state(STARTED + elapsed, STARTED + elapsed, false,
                    true, false, true, "running"), "deadline applies even after fresh success");
            stops(state(STARTED + elapsed, STARTED + elapsed - 1, true,
                    true, false, true, "running"), "deadline wins over network grace");
        }
    }

    private static void testNetworkGrace() {
        check(TrackingPolicy.LOST_MS == 45_000L, "45 second connectivity grace");
        for (long elapsed : new long[]{0, 15_000, 30_000, TrackingPolicy.LOST_MS - 1}) {
            continues(state(STARTED + elapsed, STARTED, true,
                    true, false, true, "running"), "bounded retry before disconnection deadline");
        }
        for (long elapsed : new long[]{TrackingPolicy.LOST_MS, TrackingPolicy.LOST_MS + 1}) {
            stops(state(STARTED + elapsed, STARTED, true,
                    true, false, true, "running"), "network grace expires at its boundary");
        }

        long recoveredAt = STARTED + 40_000;
        continues(state(recoveredAt, recoveredAt, false, true, false, true, "running"),
                "confirmed recovery continues an active run");
        continues(state(recoveredAt + TrackingPolicy.LOST_MS - 1, recoveredAt, true,
                true, false, true, "running"), "later outage is timed from last success");
        stops(state(recoveredAt + TrackingPolicy.LOST_MS, recoveredAt, true,
                true, false, true, "running"), "later outage has its own bounded grace");

        stops(state(STARTED + 50_000, STARTED, true, true, false, true, "running"),
                "first failure already beyond last-success grace stops immediately");
        stops(state(recoveredAt, recoveredAt, false, true, false, true, "completed"),
                "successful response with terminal task state does not restart tracking");
    }

    private static void testCallbackGenerationAndLifecycle() {
        check(TrackingPolicy.accepts(true, 7, 7, STARTED, STARTED),
                "current active callback accepted");
        check(TrackingPolicy.accepts(true, 7, 7, STARTED + TrackingPolicy.LIMIT_MS - 1, STARTED),
                "current callback just before deadline accepted");
        check(!TrackingPolicy.accepts(false, 7, 7, STARTED + 1, STARTED),
                "response after stop cannot resurrect a notification");
        check(!TrackingPolicy.accepts(false, 8, 7, STARTED + 1, STARTED),
                "response after logout and generation change is rejected");
        check(!TrackingPolicy.accepts(true, 8, 7, STARTED + 1, STARTED),
                "task A response after switching to task B is rejected");
        check(!TrackingPolicy.accepts(true, 8, 9, STARTED + 1, STARTED),
                "only exact generation equality is accepted");
        for (long elapsed : new long[]{TrackingPolicy.LIMIT_MS, TrackingPolicy.LIMIT_MS + 1,
                TrackingPolicy.LIMIT_MS + 60_000}) {
            check(!TrackingPolicy.accepts(true, 7, 7, STARTED + elapsed, STARTED),
                    "late success cannot bypass the hard deadline");
        }
        check(!TrackingPolicy.accepts(true, 9, 7, STARTED + 100, STARTED + 50),
                "restarting even the same task invalidates its previous callback");
        check(TrackingPolicy.accepts(true, 9, 9, STARTED + 100, STARTED + 50),
                "new run accepts its own callback");
    }

    private static void testUncertainSnapshotsDoNotRenewRunningLease() {
        long lastRunning = STARTED;
        for (String status : new String[]{"unknown", "", null, "future_unrecognized_status"}) {
            for (long elapsed : new long[]{15_000, 30_000, 44_999}) {
                long now = STARTED + elapsed;
                continues(TrackingPolicy.terminal(now, STARTED, now, lastRunning, false,
                        true, false, true, status), "fresh HTTP with unconfirmed state stays in bounded grace");
                check(!TrackingPolicy.confirmedRunning(true, false, true, status),
                        "unknown state immediately withdraws promotion");
            }
            String reason = TrackingPolicy.terminal(STARTED + 45_000, STARTED, STARTED + 45_000,
                    lastRunning, false, true, false, true, status);
            stops(reason, "repeated successful unknown snapshots cannot extend the running lease");
            check(!reason.contains("任务未在执行") && !reason.equals("本轮结束"),
                    "unknown timeout does not falsely report task completion");
        }
        continues(TrackingPolicy.terminal(STARTED + 20_000, STARTED, STARTED + 20_000,
                lastRunning, false, false, false, false, null), "missing task is not confirmed offline");
        check(!TrackingPolicy.confirmedRunning(false, false, false, null), "missing task cannot be promoted");
        stops(TrackingPolicy.terminal(STARTED + 45_000, STARTED, STARTED + 45_000,
                lastRunning, false, false, false, false, null), "missing task also has a bounded grace");

        // A successful running reply before the deadline can establish a new lease.
        long recovered = STARTED + 44_000;
        continues(TrackingPolicy.deadline(recovered, STARTED, recovered - 1000, lastRunning, true),
                "timely running response is eligible for processing");
        check(TrackingPolicy.confirmedRunning(true, false, true, "running"), "only confirmed running restores promotion");
        lastRunning = recovered;
        continues(TrackingPolicy.terminal(recovered + 44_999, STARTED, recovered + 44_999,
                lastRunning, false, true, false, true, "unknown"), "later uncertainty uses the new running lease");
        stops(TrackingPolicy.terminal(recovered + 45_000, STARTED, recovered + 45_000,
                lastRunning, false, true, false, true, "unknown"), "new lease expires exactly once");

        for (long elapsed : new long[]{45_000, 45_001, 70_000}) {
            stops(TrackingPolicy.deadline(STARTED + elapsed, STARTED, STARTED + elapsed,
                    STARTED, true), "late HTTP success must be rejected before it can renew running time");
        }
        continues(TrackingPolicy.deadline(STARTED + 44_999, STARTED, STARTED + 30_000, STARTED, true),
                "independent status and network clocks both have remaining grace");
        stops(TrackingPolicy.deadline(STARTED + 45_000, STARTED, STARTED + 30_000, STARTED, true),
                "fresh networking cannot conceal a stale running confirmation");
        stops(TrackingPolicy.deadline(STARTED + 45_000, STARTED, STARTED, STARTED + 30_000, false),
                "recent running state cannot conceal a disconnected network");
        for (String status : new String[]{"completed", "idle", "waiting", "error"}) {
            stops(TrackingPolicy.terminal(STARTED + 1, STARTED, STARTED + 1, STARTED, false,
                    true, false, true, status), "explicit terminal state still ends without a grace delay");
        }
        stops(TrackingPolicy.terminal(STARTED + 1, STARTED, STARTED + 1, STARTED, false,
                true, true, true, "unknown"), "archival immediately ends even if status is unknown");
        stops(TrackingPolicy.terminal(STARTED + 1, STARTED, STARTED + 1, STARTED, false,
                true, false, false, "unknown"), "confirmed device offline immediately ends");
    }
    private static void testDurationSelectionAndCancellation() {
        check(TrackingPolicy.DEFAULT_DURATION_MINUTES == 30, "first use defaults to 30 minutes");
        for (int preferred : new int[]{-1, 1, 14, 15, 16, 30, 31, 60, 90, 120, 121, 360, 1439}) {
            TrackingPolicy.DurationChoice choice = new TrackingPolicy.DurationChoice(preferred);
            check(choice.selectedMinutes() == preferred, "last legal preference is restored");
            check(choice.confirm() == preferred, "explicit confirmation emits the selected duration");
            check(choice.confirm() == 0, "a second callback cannot start a duplicate run");
        }
        for (int invalid : new int[]{Integer.MIN_VALUE, -2, 0, 1440, 1441, Integer.MAX_VALUE}) {
            check(!TrackingPolicy.validDurationMinutes(invalid), "unsupported durations are not accepted");
            check(TrackingPolicy.sanitizeDurationMinutes(invalid) == 30, "invalid intent/preference uses bounded default");
            check(TrackingPolicy.durationMs(invalid) == 30 * 60 * 1000L, "invalid minutes cannot overflow into a lease");
            TrackingPolicy.DurationChoice choice = new TrackingPolicy.DurationChoice(invalid);
            check(choice.selectedMinutes() == 30, "corrupt saved preference displays the default");
            choice.select(60); choice.select(invalid);
            check(choice.confirm() == 60, "invalid option does not replace a legal choice");
        }
        for (int duration : new int[]{-1, 1, 15, 30, 60, 120, 1439}) {
            TrackingPolicy.DurationChoice choice = new TrackingPolicy.DurationChoice(30);
            choice.select(duration);
            choice.cancel();
            choice.select(120);
            check(choice.confirm() == 0, "cancel/back/dismiss cannot later emit a start");
            choice.cancel();
            check(choice.confirm() == 0, "dismissal stays closed after repeated events");
        }
        TrackingPolicy.DurationChoice previous = new TrackingPolicy.DurationChoice(15);
        previous.cancel();
        TrackingPolicy.DurationChoice next = new TrackingPolicy.DurationChoice(120);
        check(previous.confirm() == 0 && next.confirm() == 120, "replaced dialog callbacks cannot start an old selection");
        for (long invalid : new long[]{Long.MIN_VALUE, -2L, 0L, 15L, 900001L, 7200001L, 86400000L, Long.MAX_VALUE}) {
            check(TrackingPolicy.sanitizeDurationMs(invalid) == TrackingPolicy.DEFAULT_DURATION_MS,
                    "only exact supported millisecond durations reach a deadline");
        }
    }

    private static void testEveryDurationKeepsIndependentTerminationRules() {
        for (int minutes : new int[]{1, 15, 30, 31, 60, 90, 120, 121, 360, 1439}) {
            long duration = TrackingPolicy.durationMs(minutes);
            check(duration == minutes * 60000L && TrackingPolicy.sanitizeDurationMs(duration) == duration,
                    "supported duration is preserved in both policy units");
            long before = STARTED + duration - 1;
            continues(TrackingPolicy.deadline(before, STARTED, before, before, false, duration),
                    "selected duration continues immediately before its deadline");
            check(TrackingPolicy.accepts(true, 10, 10, before, STARTED, duration), "current callback before selected deadline is accepted");
            for (long late : new long[]{STARTED + duration, STARTED + duration + 1, STARTED + duration + 60000}) {
                stops(TrackingPolicy.deadline(late, STARTED, late, late, false, duration), "fresh response never extends the chosen deadline");
                check(!TrackingPolicy.accepts(true, 10, 10, late, STARTED, duration), "late response is rejected for every selected duration");
                stops(TrackingPolicy.terminal(late, STARTED, late, late, false, true, false, true, "running", duration),
                        "a running result cannot revive an expired selection");
            }
            check(TrackingPolicy.deadline(STARTED + duration, STARTED, STARTED + duration,
                    STARTED + duration, false, duration).contains(minutes + " 分钟"), "deadline reports actual selected duration");
            check(!TrackingPolicy.accepts(false, 10, 10, STARTED + 1, STARTED, duration), "stopped request stays stopped");
            check(!TrackingPolicy.accepts(true, 11, 10, STARTED + 1, STARTED, duration), "old task callback cannot inherit a new duration");
            for (String status : new String[]{"completed", "idle", "waiting", "error"}) {
                stops(TrackingPolicy.terminal(STARTED + 1, STARTED, STARTED + 1, STARTED,
                        false, true, false, true, status, duration), "terminal state does not wait for selected duration");
            }
            stops(TrackingPolicy.terminal(STARTED + 1, STARTED, STARTED + 1, STARTED,
                    false, true, true, true, "running", duration), "archiving remains immediate");
            stops(TrackingPolicy.terminal(STARTED + 1, STARTED, STARTED + 1, STARTED,
                    false, true, false, false, "running", duration), "device offline remains immediate");
            continues(TrackingPolicy.deadline(STARTED + 44999, STARTED, STARTED, STARTED, false, duration),
                    "network failure has its own 45 second grace");
            stops(TrackingPolicy.deadline(STARTED + 45000, STARTED, STARTED, STARTED + 45000, false, duration),
                    "longer duration cannot extend network loss grace");
            continues(TrackingPolicy.terminal(STARTED + 44999, STARTED, STARTED + 44999, STARTED,
                    false, true, false, true, "unknown", duration), "fresh unknown status still has bounded grace");
            stops(TrackingPolicy.terminal(STARTED + 45000, STARTED, STARTED + 45000, STARTED,
                    false, true, false, true, "unknown", duration), "successful HTTP never extends unknown status grace");
            stops(TrackingPolicy.deadline(STARTED + 45000, STARTED, STARTED + 45000, STARTED, true, duration),
                    "pre-response guard also enforces unknown status grace");
        }
        long atTwentyMinutes = STARTED + 20 * 60000L;
        stops(TrackingPolicy.deadline(atTwentyMinutes, STARTED, atTwentyMinutes, atTwentyMinutes,
                false, TrackingPolicy.durationMs(15)), "shorter run keeps its original deadline after another selection changes");
        continues(TrackingPolicy.deadline(atTwentyMinutes, STARTED, atTwentyMinutes, atTwentyMinutes,
                false, TrackingPolicy.durationMs(120)), "independent longer run is not cut at the former 15 minute default");
    }

    private static void testUntilTaskEnd() {
        long duration = TrackingPolicy.durationMs(TrackingPolicy.UNTIL_TASK_END_MINUTES);
        check(duration == TrackingPolicy.UNTIL_TASK_END_MS && duration == -1L,
                "until-task-end stays an explicit sentinel, not a negative minute conversion");
        check(TrackingPolicy.sanitizeDurationMs(duration) == duration, "until-task-end is preserved in stored duration");
        for (long elapsed : new long[]{120 * 60000L, 1439 * 60000L, 86400000L, 7 * 86400000L}) {
            long now = STARTED + elapsed;
            continues(TrackingPolicy.deadline(now, STARTED, now, now, false, duration),
                    "no hidden two-hour or daily application countdown");
            check(TrackingPolicy.accepts(true, 42, 42, now, STARTED, duration), "current long-run callback accepted");
            check(!TrackingPolicy.accepts(false, 42, 42, now, STARTED, duration), "long-run cannot outlive stop");
            check(!TrackingPolicy.accepts(true, 43, 42, now, STARTED, duration), "long-run cannot cross session generations");
            continues(TrackingPolicy.terminal(now, STARTED, now, now, false, true, false, true, "running", duration),
                    "confirmed long-running task continues");
            continues(TrackingPolicy.terminal(now, STARTED, now, now, false, true, false, true, "waiting", duration),
                    "waiting for approval is not completion in until-task-end mode");
            continues(TrackingPolicy.terminal(now, STARTED, now, STARTED, false, true, false, true, "unknown", duration),
                    "explicit online unknown cannot prove completion despite an old running timestamp");
            check(!TrackingPolicy.confirmedRunning(true, false, true, "unknown"), "unknown still never proves running");
            check(TrackingPolicy.confirmedWaiting(true, false, true, "waiting", duration), "waiting is a confirmed state for until-task-end");
            for (String status : new String[]{"completed", "error", "idle"})
                stops(TrackingPolicy.terminal(now, STARTED, now, now, false, true, false, true, status, duration),
                        "long-run ends on terminal state " + status);
            stops(TrackingPolicy.terminal(now, STARTED, now, now, false, true, true, true, "waiting", duration),
                    "archived waiting task stops");
            stops(TrackingPolicy.terminal(now, STARTED, now, now, false, true, false, false, "waiting", duration),
                    "offline waiting task stops");
            stops(TrackingPolicy.deadline(now, STARTED, now - 45000, now, false, duration),
                    "late HTTP cannot renew expired network grace in long-run");
            stops(TrackingPolicy.deadline(now, STARTED, now, now - 45000, true, duration),
                    "late state confirmation cannot renew expired uncertainty grace");
            continues(TrackingPolicy.deadline(now, STARTED, now - 44999, now - 44999, true, duration),
                    "both independent clocks retain their original 45-second grace");
        }
        check(!TrackingPolicy.confirmedWaiting(true, false, true, "waiting", 30 * 60000L), "timed waiting behavior stays unchanged");
        check(!TrackingPolicy.confirmedWaiting(true, false, false, "waiting", duration), "offline waiting is not confirmed");
        check(TrackingPolicy.observesUnknown(true, false, true, "unknown", duration), "known online task can remain under observation");
        check(!TrackingPolicy.observesUnknown(true, false, true, "unknown", 30 * 60000L), "timed unknown does not renew observation");
        check(!TrackingPolicy.observesUnknown(false, false, true, "unknown", duration), "missing task does not renew observation");
        check(!TrackingPolicy.observesUnknown(true, true, true, "unknown", duration), "archived task does not renew observation");
        check(!TrackingPolicy.observesUnknown(true, false, false, "unknown", duration), "offline task does not renew observation");
        for (String unsupported : new String[]{null, "", "future_state"}) {
            check(!TrackingPolicy.observesUnknown(true, false, true, unsupported, duration), "malformed or unrecognized states stay bounded");
            stops(TrackingPolicy.terminal(STARTED + 45000, STARTED, STARTED + 45000, STARTED, false,
                    true, false, true, unsupported, duration), "unsupported state still expires after 45 seconds");
        }
        stops(TrackingPolicy.terminal(STARTED + 45000, STARTED, STARTED + 45000, STARTED, false,
                false, false, true, "unknown", duration), "missing task still expires after 45 seconds");
        stops(TrackingPolicy.terminal(STARTED + 45000, STARTED, STARTED, STARTED, true,
                true, false, true, "unknown", duration), "unknown observation does not extend connectivity grace");
    }

    public static void main(String[] args) {
        testConfirmedTaskState();
        testTrackingDeadline();
        testNetworkGrace();
        testCallbackGenerationAndLifecycle();
        testUncertainSnapshotsDoNotRenewRunningLease();
        testDurationSelectionAndCancellation();
        testEveryDurationKeepsIndependentTerminationRules();
        testUntilTaskEnd();
        System.out.println("TrackingPolicyTest: " + checks + " checks passed");
    }
}

