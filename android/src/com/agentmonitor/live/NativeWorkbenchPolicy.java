package com.agentmonitor.live;

/** UI-thread page reads, revision boundaries and empty states for the native workbench. */
public final class NativeWorkbenchPolicy {
    private NativeWorkbenchPolicy() { }

    public enum Change { TOOL_FILTER, WORKSPACE }
    public enum PageRead { NONE, WORKBENCH, USAGE, ACCOUNT }

    public static PageRead pageRead(String page) {
        if (page == null) return PageRead.NONE;
        switch (page) {
            case "tasks": case "task": case "devices": case "device": return PageRead.WORKBENCH;
            case "usage": return PageRead.USAGE;
            case "account": case "profile": case "sync": case "sessions": return PageRead.ACCOUNT;
            // Pairing has its own explicit create/details requests, not account data.
            default: return PageRead.NONE;
        }
    }

    /** Read only the visible page's data; each owner retains its existing in-flight gate. */
    public static void loadPage(String page, boolean visible, boolean connected,
                                Runnable workbench, Runnable usage, Runnable account) {
        if (!visible || !connected) return;
        switch (pageRead(page)) {
            case WORKBENCH: workbench.run(); break;
            case USAGE: usage.run(); break;
            case ACCOUNT: account.run(); break;
            default: break;
        }
    }

    public static final class RevisionGate {
        private long revision;

        public long capture() { return revision; }
        public boolean accepts(long requestedRevision) { return requestedRevision == revision; }
        public void started(Change change) { advance(change); }
        public void finished(Change change) { advance(change); }

        private void advance(Change change) {
            if (change == null) throw new IllegalArgumentException("A change category is required");
            // A saved display filter never changes task/device data. Other writes
            // invalidate reads both when they start and when their result arrives.
            if (change == Change.WORKSPACE) revision++;
        }
    }

    public static boolean uncertainTask(String selectedTool, String actualTool, String status, boolean archived, boolean sourceUnavailable) {
        if (!NativeScreenPolicy.includes(selectedTool, "all", actualTool, status, archived, sourceUnavailable)) return false;
        if (sourceUnavailable) return true;
        if (status == null) return true;
        switch (status) {
            case "running": case "waiting": case "completed": case "error": case "idle": return false;
            default: return true;
        }
    }

    public static String emptyActive(boolean stale, int deviceCount, int onlineDeviceCount, int uncertainSelectedTasks) {
        if (stale) return "连接中，暂时无法确认进行中的任务";
        if (uncertainSelectedTasks > 0) return deviceCount > 0 && onlineDeviceCount == 0
                ? "电脑已离线，暂时无法确认进行中的任务" : "暂时无法确认进行中的任务";
        if (deviceCount == 0) return "还没有连接电脑";
        return "暂无进行中的任务";
    }
}
