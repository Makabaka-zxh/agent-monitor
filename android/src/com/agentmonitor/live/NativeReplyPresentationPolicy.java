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

    /** Explicit phone-to-computer receipt trail for a submitted reply. */
    public static String replyReceipt(boolean sending, String state, boolean deliveredToComputer) {
        if (sending) return "手机已发送 → 等待电脑接收";
        if (state == null) return "";
        switch (state) {
            case "queued":
                return deliveredToComputer ? "手机已发送 → 电脑已收到" : "手机已发送 → 等待电脑接收";
            case "dispatching":
                return deliveredToComputer ? "手机已发送 → 电脑已收到" : "手机已发送 → 等待电脑确认";
            case "running":
                return "手机已发送 → 电脑已收到";
            case "succeeded":
                return "手机已发送 → 电脑已收到 → 本轮已完成";
            case "blocked":
                return deliveredToComputer ? "手机已发送 → 电脑已收到 · 等待处理" : "";
            case "failed":
                return deliveredToComputer ? "手机已发送 → 电脑已收到 · 执行未完成" : "";
            default:
                return "";
        }
    }

    /** Formats either verified actual turn settings (`applied`) or requested settings before confirmation. */
    public static String settingsReceipt(String modelLabel, String effortLabel,
                                         boolean fastMode, boolean planMode, boolean verifiedApplied) {
        StringBuilder parts = new StringBuilder();
        if (modelLabel != null && !modelLabel.trim().isEmpty()) {
            parts.append(modelLabel.trim());
        }
        if (effortLabel != null && !effortLabel.trim().isEmpty()) {
            if (parts.length() > 0) parts.append(" · ");
            parts.append("思考强度 ").append(effortLabel.trim());
        }
        if (fastMode) {
            if (parts.length() > 0) parts.append(" · ");
            parts.append("快速模式");
        }
        if (planMode) {
            if (parts.length() > 0) parts.append(" · ");
            parts.append("计划模式");
        }
        if (parts.length() == 0) return "";
        return (verifiedApplied ? "本轮实际使用：" : "已随回复提交：") + parts;
    }
}
