package com.agentmonitor.live;

/** Reply visibility and status copy. Transport and the saved draft are owned separately. */
public final class NativeReplyPresentationPolicy {
    private NativeReplyPresentationPolicy() { }

    public static boolean needsAttention(boolean sending, boolean unresolved, String state) {
        return sending || unresolved || "failed".equals(state) || "blocked".equals(state);
    }

    /** Temporary attention never changes the user's saved expanded/collapsed preference. */
    public static boolean expanded(boolean userExpanded, boolean needsAttention) {
        return userExpanded || needsAttention;
    }

    /** A missing draft uses the previous submission as fallback. An explicit clear must survive. */
    static boolean draftNeedsWrite(String saved, String current, String fallback) {
        return !current.equals(saved == null ? fallback : saved);
    }

    static String commandReason(String previous, boolean stateChanged, String reported) {
        return !reported.isEmpty() || stateChanged ? reported : previous;
    }

    static String commandStatus(boolean sending, boolean unresolved, String state, boolean retry, String reason) {
        if (sending) return "正在发送…";
        switch (state) {
            case "dispatching": return "等待电脑确认";
            case "queued": return "等待电脑接收";
            case "running": return "正在回复";
            case "succeeded": return "上一条回复已完成";
            case "blocked": return reason.isEmpty() ? "上一条回复需要处理，请查看上方请求或电脑端" : "上一条回复需要处理：" + reason;
            case "failed": return reason.isEmpty() ? "上一条回复未完成" : "上一条回复未完成：" + reason;
            case "not_found": return retry ? "电脑还没有接收这条回复" : "这条回复无法确认，请到电脑查看";
            default: return "uncertain".equals(state) || unresolved ? "发送结果尚未确认，请先查询" : "";
        }
    }

    static String capabilityStatus(boolean sending, boolean unresolved, boolean known, boolean available, boolean selectionReady, String reason) {
        // A new send is irrelevant while the existing request still needs confirmation.
        if (sending || unresolved) return "";
        if (!known) return reason.isEmpty() ? "正在确认是否可回复" : reason;
        if (!available) return reason.isEmpty() ? "当前任务暂不可回复" : reason;
        return selectionReady ? "" : "暂时无法确认模型选项，请稍后再试，或在模型设置中重新选择";
    }
}
