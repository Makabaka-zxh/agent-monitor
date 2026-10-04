package com.agentmonitor.live;

import com.agentmonitor.live.NotificationRecoveryPolicy.Decision;

/** Pure Java checks for notification callback reordering and bounded recovery. */
public final class NotificationRecoveryPolicyTest {
    private static final long EPOCH = 7, DEADLINE = 10000;
    private static final String SESSION = "session-a", FIRST = "presentation-a", SECOND = "presentation-b";
    private static int checks;
    private static NotificationRecoveryPolicy policy() { return policy(DEADLINE); }
    private static NotificationRecoveryPolicy policy(long deadline) {
        return new NotificationRecoveryPolicy(EPOCH, SESSION, FIRST, deadline);
    }
    private static void check(boolean condition, String label) {
        ++checks;
        if (!condition) throw new AssertionError(label);
    }
    private static void decision(Decision actual, Decision expected, String label) {
        check(actual == expected, label + ": expected " + expected + ", got " + actual);
    }
    private static Decision dismiss(NotificationRecoveryPolicy value, String presentation, long now, boolean ready) {
        return value.onDismiss(EPOCH, SESSION, presentation, now, ready);
    }
    private static Decision removed(NotificationRecoveryPolicy value, String presentation, boolean clearAll,
                                    long now, boolean eligible, String next) {
        return value.onRemoved(EPOCH, SESSION, presentation, clearAll, now, eligible, next);
    }
    private static void ended(NotificationRecoveryPolicy value, String label) {
        check(value.isEnded() && !value.isPending(), label + ": END permanently clears pending state");
        check(!value.matches(EPOCH, SESSION, value.presentationNonce())
                && !value.matchesSession(EPOCH, SESSION), label + ": ended identity cannot authorize an action");
        decision(value.tick(1), Decision.IGNORE, label + ": tick cannot revive an ended policy");
        decision(dismiss(value, value.presentationNonce(), 1, true), Decision.IGNORE, label + ": delete cannot revive");
        decision(removed(value, value.presentationNonce(), true, 1, true, "late-presentation"),
                Decision.IGNORE, label + ": listener cannot revive");
    }
    private static void deleteBeforeListener() {
        NotificationRecoveryPolicy value = policy();
        decision(dismiss(value, FIRST, 1000, true), Decision.WAIT, "delete starts classification");
        check(value.isPending() && !value.isEnded(), "pending session stays alive with publication paused");
        decision(removed(value, FIRST, true, 1500, true, SECOND), Decision.RESTORE, "clear all classifies pending delete");
        check(!value.isPending() && !value.isEnded(), "restore clears pending");
        check(value.epoch() == EPOCH && SESSION.equals(value.sessionNonce()), "recovery preserves session identity");
        check(SECOND.equals(value.presentationNonce()) && value.matches(EPOCH, SESSION, SECOND), "recovery rotates presentation");
        decision(dismiss(value, FIRST, 1600, true), Decision.IGNORE, "old presentation delete is harmless");
        decision(removed(value, FIRST, false, 1700, true, "ignored"), Decision.IGNORE, "old non-clear-all callback is harmless");
        decision(removed(value, FIRST, true, 1800, true, "ignored"), Decision.IGNORE, "duplicate clear-all callback cannot rotate again");
        check(SECOND.equals(value.presentationNonce()), "stale callbacks cannot replace current presentation");
        check(value.matchesSession(EPOCH, SESSION), "explicit stop from prior presentation still matches session");
        check(!value.matches(EPOCH, SESSION, FIRST), "presentation events still require the new presentation");
    }
    private static void listenerBeforeDelete() {
        NotificationRecoveryPolicy value = policy();
        decision(removed(value, FIRST, true, 1000, true, SECOND), Decision.RESTORE, "listener can classify before delete");
        decision(dismiss(value, FIRST, 1001, true), Decision.IGNORE, "late delete cannot stop restored session");
        decision(removed(value, SECOND, true, 1500, true, "presentation-c"), Decision.RESTORE, "a later clear all can rotate again");
        decision(dismiss(value, SECOND, 1501, true), Decision.IGNORE, "second late delete also ignored");
        check(SESSION.equals(value.sessionNonce()) && "presentation-c".equals(value.presentationNonce()),
                "multiple recoveries preserve S and advance P");
        decision(dismiss(value, "presentation-c", 2000, true), Decision.WAIT, "current presentation still classifies");
        decision(removed(value, "presentation-c", false, 2001, true, "unused"), Decision.END, "individual dismissal ends session");
        ended(value, "individual dismissal");
    }
    private static void identityIsolation() {
        NotificationRecoveryPolicy value = policy();
        decision(value.onDismiss(EPOCH - 1, SESSION, FIRST, 1000, true), Decision.IGNORE, "old epoch delete");
        decision(value.onRemoved(EPOCH - 1, SESSION, FIRST, true, 1000, true, SECOND), Decision.IGNORE, "old epoch listener");
        decision(value.onDismiss(EPOCH, "old-session", FIRST, 1000, true), Decision.IGNORE, "different session delete");
        decision(value.onRemoved(EPOCH, "old-session", FIRST, false, 1000, true, SECOND), Decision.IGNORE, "different session removal");
        decision(value.onDismiss(EPOCH, SESSION, "old-presentation", 1000, true), Decision.IGNORE, "different presentation delete");
        decision(value.onRemoved(EPOCH, SESSION, "old-presentation", false, 1000, true, SECOND), Decision.IGNORE, "different presentation removal");
        check(!value.isPending() && !value.isEnded(), "wrong identities cannot alter state");
        check(!value.matchesSession(EPOCH - 1, SESSION) && !value.matchesSession(EPOCH, "old-session"),
                "old task or session stop cannot match");
        check(!value.matchesSession(EPOCH, null) && !value.matches(EPOCH, SESSION, null), "missing identity cannot match");

        NotificationRecoveryPolicy replacement = new NotificationRecoveryPolicy(EPOCH, "new-session", FIRST, DEADLINE);
        decision(replacement.onRemoved(EPOCH, SESSION, FIRST, true, 1100, true, SECOND), Decision.IGNORE,
                "new S rejects old event even if epoch and P coincide");
        check(replacement.matchesSession(EPOCH, "new-session"), "new session is independently stoppable");
    }
    private static void pendingDeadline() {
        check(NotificationRecoveryPolicy.CLASSIFY_WAIT_MS == 2000, "classification window is two seconds");
        NotificationRecoveryPolicy value = policy();
        decision(dismiss(value, FIRST, 1000, true), Decision.WAIT, "first delete fixes timeout");
        decision(dismiss(value, FIRST, 2999, true), Decision.WAIT, "duplicate delete waits without extending");
        decision(value.tick(2999), Decision.IGNORE, "classification is allowed before timeout");
        decision(value.tick(3000), Decision.END, "duplicate delete does not renew classification deadline");
        ended(value, "classification timeout");
        decision(removed(value, FIRST, true, 3001, true, SECOND), Decision.IGNORE, "late clear all cannot revive expired session");

        NotificationRecoveryPolicy late = policy();
        dismiss(late, FIRST, 1000, true);
        decision(removed(late, FIRST, true, 3000, true, SECOND), Decision.END, "listener checks timeout even before tick");
        ended(late, "listener at classification deadline");

        NotificationRecoveryPolicy duplicate = policy();
        dismiss(duplicate, FIRST, 1000, true);
        decision(dismiss(duplicate, FIRST, 3000, true), Decision.END, "duplicate delete checks its original timeout");

        NotificationRecoveryPolicy disconnected = policy();
        dismiss(disconnected, FIRST, 1000, true);
        decision(dismiss(disconnected, FIRST, 1100, false), Decision.END, "listener loss cannot keep pending delete alive");
    }
    private static void originalDeadline() {
        NotificationRecoveryPolicy bounded = policy(2000);
        decision(dismiss(bounded, FIRST, 1000, true), Decision.WAIT, "classification starts near session deadline");
        decision(bounded.tick(1999), Decision.IGNORE, "classification can wait within original deadline");
        decision(bounded.tick(2000), Decision.END, "classification is capped by original deadline");

        NotificationRecoveryPolicy restored = policy(2000);
        decision(removed(restored, FIRST, true, 1999, true, SECOND), Decision.RESTORE, "last moment recovery");
        decision(restored.tick(2000), Decision.END, "recovery does not extend original session deadline");
        ended(restored, "original deadline");

        NotificationRecoveryPolicy tooLate = policy(2000);
        decision(removed(tooLate, FIRST, true, 2000, true, SECOND), Decision.END, "no recovery at original deadline");
        NotificationRecoveryPolicy lateDelete = policy(2000);
        decision(dismiss(lateDelete, FIRST, 2000, true), Decision.END, "delete cannot start classification after original deadline");

        NotificationRecoveryPolicy overflow = policy(Long.MAX_VALUE);
        decision(dismiss(overflow, FIRST, Long.MAX_VALUE - 500, true), Decision.WAIT, "classification math does not overflow");
        decision(overflow.tick(Long.MAX_VALUE - 1), Decision.IGNORE, "overflow-safe classification waits until session deadline");
        decision(overflow.tick(Long.MAX_VALUE), Decision.END, "overflow-safe classification still expires");
    }
    private static void onlyEligibleClearAll() {
        NotificationRecoveryPolicy unavailable = policy();
        decision(dismiss(unavailable, FIRST, 1000, false), Decision.END, "no listener means no recovery classification");
        ended(unavailable, "listener unavailable");
        for (boolean pending : new boolean[]{false, true}) {
            NotificationRecoveryPolicy otherReason = policy();
            if (pending) dismiss(otherReason, FIRST, 1000, true);
            decision(removed(otherReason, FIRST, false, 1100, true, SECOND), Decision.END, "individual or unknown removal never restores");
            ended(otherReason, "other removal reason");

            NotificationRecoveryPolicy ineligible = policy();
            if (pending) dismiss(ineligible, FIRST, 1000, true);
            decision(removed(ineligible, FIRST, true, 1100, false, SECOND), Decision.END, "ineligible task cannot restore");
            ended(ineligible, "ineligible task");
        }
        for (String invalid : new String[]{null, "", FIRST}) {
            NotificationRecoveryPolicy value = policy();
            decision(removed(value, FIRST, true, 1000, true, invalid), Decision.END, "restore must rotate to a valid different P");
            check(FIRST.equals(value.presentationNonce()), "invalid replacement never changes P");
        }
    }
    private static void explicitEnd() {
        NotificationRecoveryPolicy value = policy();
        dismiss(value, FIRST, 1000, true);
        value.end();
        ended(value, "explicit end during classification");
        value.end();
        check(value.isEnded(), "end is idempotent");

        NotificationRecoveryPolicy restored = policy();
        removed(restored, FIRST, true, 1000, true, SECOND);
        check(restored.matchesSession(EPOCH, SESSION), "explicit stop remains authorized after presentation rotation");
        restored.end();
        ended(restored, "explicit stop after recovery");
        decision(removed(restored, FIRST, true, 1001, true, "presentation-c"), Decision.IGNORE, "old clear-all callback cannot undo stop");
    }
    private static void untilTaskEndKeepsClassificationBounded() {
        long day = 86400000L;
        NotificationRecoveryPolicy running = policy(Long.MAX_VALUE);
        decision(running.tick(day), Decision.IGNORE, "until-task-end recovery has no hidden daily deadline");
        decision(dismiss(running, FIRST, day, true), Decision.WAIT, "long-running session classifies a removal");
        decision(removed(running, FIRST, true, day + 1000, true, SECOND), Decision.RESTORE,
                "eligible clear all restores the same no-countdown session");
        decision(running.tick(day * 2), Decision.IGNORE, "restoration does not install a default countdown");
        decision(dismiss(running, SECOND, day * 2, true), Decision.WAIT, "later removal starts its own short classification");
        decision(running.tick(day * 2 + 1999), Decision.IGNORE, "classification waits within two seconds");
        decision(running.tick(day * 2 + 2000), Decision.END, "no-countdown mode never removes classification timeout");
        decision(removed(running, SECOND, true, day * 2 + 2001, true, "third"), Decision.IGNORE,
                "late classification cannot revive a no-countdown session");
    }
    public static void main(String[] args) {
        deleteBeforeListener();
        listenerBeforeDelete();
        identityIsolation();
        pendingDeadline();
        originalDeadline();
        onlyEligibleClearAll();
        explicitEnd();
        untilTaskEndKeepsClassificationBounded();
        System.out.println("NotificationRecoveryPolicyTest: " + checks + " checks passed");
    }
}
