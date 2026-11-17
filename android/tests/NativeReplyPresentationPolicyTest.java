package com.agentmonitor.live;

/** Scenario checks for visibility across refreshes, saved-state recreation, and reply recovery. */
public final class NativeReplyPresentationPolicyTest {
    private static int checks;
    private static void check(boolean condition, String scenario) {
        checks++;
        if (!condition) throw new AssertionError(scenario);
    }
    private static boolean visible(boolean userExpanded, boolean sending, boolean pending, String state) {
        return NativeReplyPresentationPolicy.expanded(userExpanded,
            NativeReplyPresentationPolicy.needsAttention(sending, pending, state));
    }
    public static void main(String[] args) {
        check(!NativeReplyPresentationPolicy.draftNeedsWrite(null, "", ""), "untouched editor does not create a draft");
        check(NativeReplyPresentationPolicy.draftNeedsWrite(null, "", "old submission"), "explicit clear overrides recovery fallback");
        check(!NativeReplyPresentationPolicy.draftNeedsWrite("", "", "old submission"), "reopened blank draft stays blank");
        check(NativeReplyPresentationPolicy.draftNeedsWrite("preview", " preview\n", ""), "draft preserves whitespace the user typed");
        check(!NativeReplyPresentationPolicy.draftNeedsWrite(" preview\n", " preview\n", ""), "leaving a saved draft avoids a redundant write");
        collapsedDraftSurvivesRefreshAndRecreation();
        pendingReplyCannotBeHidden();
        temporaryAttentionDoesNotOverwriteUserChoice();
        failureAndRetryRemainVisible();
        System.out.println("NativeReplyPresentationPolicyTest: " + checks + " checks passed");
    }
    private static void collapsedDraftSurvivesRefreshAndRecreation() {
        // Draft content and keyboard focus are deliberately not authority to reopen the composer.
        boolean savedExpanded = false;
        for (String refreshedState : new String[]{"", "", "succeeded", "", null}) {
            check(!visible(savedExpanded, false, false, refreshedState), "collapsed draft stays closed on refresh");
        }
        boolean restoredExpanded = savedExpanded;
        check(!visible(restoredExpanded, false, false, ""), "recreation preserves collapsed preference");
        check(visible(true, false, false, ""), "explicit reopen is honored");
        check(!visible(false, false, false, ""), "another collapse does not depend on clearing the draft");
    }
    private static void pendingReplyCannotBeHidden() {
        check(visible(false, true, false, ""), "transport started before server state exists");
        for (String state : new String[]{"", "queued", "dispatching", "running", "uncertain", "not_found", "future_state", null}) {
            check(visible(false, false, true, state), "unresolved identity keeps the composer visible");
        }
        check(visible(false, false, false, "failed"), "terminal failure remains visible");
        check(visible(false, false, false, "blocked"), "blocked reply remains visible");
    }
    private static void temporaryAttentionDoesNotOverwriteUserChoice() {
        boolean collapsedChoice = false;
        check(visible(collapsedChoice, false, true, "running"), "pending reply temporarily expands");
        check(!visible(collapsedChoice, false, false, "succeeded"), "completion restores collapsed choice");
        boolean openedChoice = true;
        check(visible(openedChoice, false, true, "running"), "explicitly opened reply remains open while pending");
        check(visible(openedChoice, false, false, "succeeded"), "completion preserves explicit opened choice");
        check(visible(openedChoice, false, false, ""), "recreation preserves explicit opened choice");
    }
    private static void failureAndRetryRemainVisible() {
        boolean collapsedChoice = false;
        check(visible(collapsedChoice, false, false, "failed"), "failed reply cannot disappear on refresh");
        check(visible(collapsedChoice, true, true, ""), "retry transition has no hidden gap");
        check(visible(collapsedChoice, false, true, "uncertain"), "uncertain retry stays visible");
        check(visible(collapsedChoice, false, false, "blocked"), "server block stays visible");
        check(!visible(collapsedChoice, false, false, "succeeded"), "confirmed completion releases attention");
    }
}
