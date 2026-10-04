package com.agentmonitor.live;

/** Deterministic timing and result-identity races; no Android runtime or network. */
public final class NativeTaskResultsRefreshTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static NativeTaskResults.ResultRefresh waiting(String id) {
        NativeTaskResults.ResultRefresh refresh = new NativeTaskResults.ResultRefresh();
        refresh.expect(id + "|completed", id, 0);
        return refresh;
    }
    private static void response(NativeTaskResults.ResultRefresh refresh, long now, String id, boolean available, boolean filesPending) {
        long request = refresh.startRequest(now);
        check(request >= 0, "one result request starts at " + now);
        check(refresh.finishRequest(request), "current result callback is accepted");
        refresh.received(id, available, filesPending);
    }
    public static void main(String[] args) {
        statusBeforeBody();
        snapshotDuringRequest();
        replyBeforeBody();
        bodyBeforePostAcknowledgement();
        displayedAndSnapshotBaselines();
        repeatedObservationsCannotExtendWindow();
        requestSpacingAndBackoff();
        attachmentWaitIsBounded();
        resetRejectsCallbacksAndKeepsTheRequestSlot();
        historicalSuccessDoesNotExpectAnotherResult();
        successWithoutKnownBaselineUsesSnapshot();
        newReplyGetsItsOwnBaseline();
        System.out.println("NativeTaskResultsRefreshTest: " + checks + " checks passed");
    }
    private static void statusBeforeBody() {
        NativeTaskResults.ResultRefresh refresh = waiting("B");
        response(refresh, 0, "", false, false);
        check(refresh.awaitingText, "unavailable result keeps waiting after completion");
        response(refresh, 5000, "A", true, false);
        check(refresh.awaitingText, "old result does not satisfy snapshot B");
        response(refresh, 10000, "B", true, false);
        check(!refresh.awaitingText, "exact snapshot result finishes text wait");
        check(refresh.startRequest(15000) < 0, "settled result stops polling");
    }
    private static void snapshotDuringRequest() {
        NativeTaskResults.ResultRefresh refresh = waiting("A");
        long old = refresh.startRequest(0);
        refresh.expect("B|completed", "B", 1000);
        refresh.expect("C|completed", "C", 2000);
        check(refresh.startRequest(5000) < 0, "new snapshots never start a second in-flight request");
        check(!refresh.finishRequest(old), "callback spanning newer snapshot cannot update UI");
        response(refresh, 5000, "B", true, false);
        check(refresh.awaitingText, "intermediate B cannot swallow pending C");
        response(refresh, 10000, "C", true, false);
        check(refresh.startRequest(15000) < 0, "latest pending snapshot is consumed exactly once");
    }
    private static void replyBeforeBody() {
        NativeTaskResults.ResultRefresh refresh = waiting("A");
        response(refresh, 0, "A", true, false);
        refresh.rememberReply("reply-1", "A");
        refresh.reply("reply-1", "succeeded", "A", true, 1000);
        response(refresh, 5000, "A", true, false);
        check(refresh.awaitingText, "success acknowledgement does not make old A the reply result");
        response(refresh, 10000, "B", true, false);
        check(refresh.awaitingText, "new body can display while old snapshot still needs reconciliation");
        refresh.expect("B|completed", "B", 11000);
        response(refresh, 15000, "B", true, false);
        check(!refresh.awaitingText, "new body and exact snapshot settle the reply");
    }
    private static void bodyBeforePostAcknowledgement() {
        NativeTaskResults.ResultRefresh refresh = waiting("A");
        response(refresh, 0, "A", true, false);
        refresh.rememberReply("reply-1", "A"); // The production send path records this before POST.
        refresh.expect("B|completed", "B", 1000);
        response(refresh, 5000, "B", true, false);
        refresh.reply("reply-1", "queued", "B", true, 6000);
        refresh.reply("reply-1", "succeeded", "B", true, 7000);
        response(refresh, 10000, "B", true, false);
        check(!refresh.awaitingText, "late POST acknowledgement cannot replace submission baseline A with B");
        check(refresh.startRequest(15000) < 0, "already delivered reply does not wait two minutes");
    }
    private static void displayedAndSnapshotBaselines() {
        NativeTaskResults.ResultRefresh refresh = waiting("B");
        refresh.rememberReply("reply-1", "A");
        refresh.reply("reply-1", "succeeded", "A", true, 1000);
        response(refresh, 1000, "B", true, false);
        check(refresh.awaitingText, "snapshot B existed before submission and is not the new reply");
        refresh.expect("C|running", "C", 2000);
        refresh.expect("C|completed", "C", 3000);
        response(refresh, 6000, "B", true, false);
        check(refresh.awaitingText, "snapshot updates retain reply baselines");
        response(refresh, 11000, "C", true, false);
        check(!refresh.awaitingText, "C is newer than both known pre-reply identities");

        refresh = waiting("A");
        refresh.rememberReply("reply-1", "B");
        refresh.reply("reply-1", "succeeded", "B", true, 0);
        response(refresh, 0, "A", true, false);
        check(refresh.awaitingText, "old snapshot A cannot satisfy reply when B was already displayed");
        refresh.expect("B|completed", "B", 1000);
        response(refresh, 5000, "B", true, false);
        check(refresh.awaitingText, "already displayed B is also excluded from reply result");
    }
    private static void repeatedObservationsCannotExtendWindow() {
        NativeTaskResults.ResultRefresh refresh = waiting("A");
        refresh.rememberReply("reply-1", "A");
        refresh.reply("reply-1", "succeeded", "A", true, 0);
        for (long now = 0; now < 120000; now += 5000) {
            refresh.expect("A|completed", "A", now);
            refresh.reply("reply-1", "succeeded", "A", true, now);
            response(refresh, now, "A", true, false);
        }
        check(refresh.expired(120000), "same snapshots, old bodies and duplicate success cannot renew deadline");
        check(refresh.startRequest(120000) < 0, "no request at the two-minute deadline");
        refresh.restart(125000);
        response(refresh, 125000, "A", true, false);
        check(!refresh.expired(244999), "explicit refresh creates one new bounded opportunity");
        check(refresh.expired(245000), "manual refresh also has a hard deadline");
    }
    private static void requestSpacingAndBackoff() {
        NativeTaskResults.ResultRefresh refresh = waiting("A");
        long request = refresh.startRequest(0);
        check(refresh.startRequest(1000) < 0, "duplicate tick cannot duplicate the request");
        check(refresh.finishRequest(request), "first request releases the slot");
        refresh.failed(1000, 30000);
        refresh.expect("B|completed", "B", 2000);
        refresh.restart(3000);
        check(refresh.startRequest(30999) < 0, "new snapshot and manual refresh respect Retry-After");
        response(refresh, 31000, "A", true, false);
        check(refresh.startRequest(35999) < 0, "requests remain at least five seconds apart");
        response(refresh, 36000, "B", true, false);
        check(refresh.startRequest(41000) < 0, "successful recovery settles polling");
    }
    private static void attachmentWaitIsBounded() {
        NativeTaskResults.ResultRefresh refresh = waiting("B");
        response(refresh, 0, "B", true, true);
        check(!refresh.awaitingText, "matching text does not imply attachments are ready");
        response(refresh, 5000, "B", true, true);
        check(refresh.expired(120000), "attachment synchronization shares the same bounded wait");
        check(refresh.startRequest(120000) < 0, "pending attachments cannot bypass deadline");
        refresh.restart(125000);
        response(refresh, 125000, "B", true, false);
        check(refresh.startRequest(130000) < 0, "attachment completion stops polling");
    }
    private static void resetRejectsCallbacksAndKeepsTheRequestSlot() {
        NativeTaskResults.ResultRefresh refresh = waiting("A");
        long old = refresh.startRequest(0);
        refresh.reset(); // Sync disabled, page disposed or account changed.
        check(refresh.startRequest(5000) < 0, "invalidated page cannot poll");
        refresh.expect("B|completed", "B", 1000);
        check(refresh.startRequest(5000) < 0, "re-enabled sync cannot overlap outstanding old request");
        check(!refresh.finishRequest(old), "off/on does not make the old response fresh again");
        response(refresh, 5000, "B", true, false);
        refresh.reset();
        check(refresh.startRequest(10000) < 0, "reset clears settled and pending poll intent");
    }
    private static void historicalSuccessDoesNotExpectAnotherResult() {
        NativeTaskResults.ResultRefresh refresh = waiting("B");
        response(refresh, 0, "B", true, false);
        refresh.reply("old-reply", "succeeded", "B", false, 1000);
        check(refresh.startRequest(5000) < 0, "restored succeeded preference does not restart a finished result wait");
        check(!refresh.expired(200000), "history does not produce a false synchronization timeout");
    }
    private static void successWithoutKnownBaselineUsesSnapshot() {
        NativeTaskResults.ResultRefresh refresh = waiting("B");
        refresh.reply("old-reply", "succeeded", "B", true, 0);
        response(refresh, 0, "B", true, false);
        check(!refresh.awaitingText, "first-seen historical success does not demand a result newer than itself");
        refresh.reply("old-reply", "succeeded", "B", false, 5000);
        check(refresh.startRequest(5000) < 0, "subsequent history lookup keeps polling stopped");
    }
    private static void newReplyGetsItsOwnBaseline() {
        NativeTaskResults.ResultRefresh refresh = waiting("B");
        refresh.rememberReply("reply-1", "A");
        refresh.reply("reply-1", "succeeded", "A", true, 0);
        response(refresh, 0, "C", true, false);
        refresh.expect("C|completed", "C", 1000);
        response(refresh, 5000, "C", true, false);
        refresh.rememberReply("reply-2", "C");
        refresh.reply("reply-2", "succeeded", "C", true, 6000);
        response(refresh, 10000, "C", true, false);
        check(refresh.awaitingText, "a later reply does not reuse an earlier reply's baseline");
        refresh.expect("D|completed", "D", 11000);
        response(refresh, 15000, "D", true, false);
        check(!refresh.awaitingText, "the later reply settles on its own new result");
    }
}
