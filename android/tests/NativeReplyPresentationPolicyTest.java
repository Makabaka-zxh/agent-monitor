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
        explicitReplyAndSettingsReceipts();
        contextualQuickSuggestions();
        System.out.println("NativeReplyPresentationPolicyTest: " + checks + " checks passed");
    }
    private static void contextualQuickSuggestions() {
        java.util.List<NativeReplyPresentationPolicy.Suggestion> errChips =
                NativeReplyPresentationPolicy.quickSuggestions("failed", true, 0, false, 5173, "bind 0.0.0.0");
        check(errChips.size() == 3, "error + loopback produces 3 chips");
        check(errChips.get(0).label.contains(":5173"), "loopback dev port chip is prioritized first");
        check(errChips.get(1).label.contains("定位报错"), "failed status surfaces root-cause fix chip");

        java.util.List<NativeReplyPresentationPolicy.Suggestion> diffChips =
                NativeReplyPresentationPolicy.quickSuggestions("completed", false, 4, false, 0, "");
        check(diffChips.size() == 3 && diffChips.get(0).label.contains("运行测试验证改动"),
                "diff presence surfaces test verification and review chips");

        java.util.List<NativeReplyPresentationPolicy.Suggestion> planChips =
                NativeReplyPresentationPolicy.quickSuggestions("completed", false, 0, true, 0, "");
        check(planChips.size() == 2 && planChips.get(0).label.contains("按此方案执行"),
                "plan mode surfaces plan execution chip");
    }
    private static void explicitReplyAndSettingsReceipts() {
        check("手机已发送 → 等待电脑接收".equals(NativeReplyPresentationPolicy.replyReceipt(true, "", false)),
                "in-flight send displays initial receipt");
        check("手机已发送 → 等待电脑接收".equals(NativeReplyPresentationPolicy.replyReceipt(false, "queued", false)),
                "unclaimed queued reply waits for computer");
        check("手机已发送 → 电脑已收到".equals(NativeReplyPresentationPolicy.replyReceipt(false, "queued", true)),
                "claimed reply transitions to computer received");
        check("手机已发送 → 电脑已收到".equals(NativeReplyPresentationPolicy.replyReceipt(false, "running", false)),
                "running reply confirms computer received");
        check("手机已发送 → 电脑已收到 → 本轮已完成".equals(NativeReplyPresentationPolicy.replyReceipt(false, "succeeded", true)),
                "completed reply shows terminal receipt");
        check("本轮实际使用：GPT-5.4 · 思考强度 高 · 快速模式 · 计划模式".equals(
                NativeReplyPresentationPolicy.settingsReceipt("GPT-5.4", "高", true, true, true)),
                "verified applied settings display model, effort, fast mode and plan mode");
        check("已随回复提交：Claude Sonnet 4.6 · 思考强度 中".equals(
                NativeReplyPresentationPolicy.settingsReceipt("Claude Sonnet 4.6", "中", false, false, false)),
                "unverified requested settings never claim verified application");
        check("".equals(NativeReplyPresentationPolicy.settingsReceipt("", "", false, false, true)),
                "empty settings omit receipt line");
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
