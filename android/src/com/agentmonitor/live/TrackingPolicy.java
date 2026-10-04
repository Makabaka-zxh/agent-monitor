package com.agentmonitor.live;

/** Pure policy shared by the foreground service and offline boundary tests. */
public final class TrackingPolicy {
    public static final int DEFAULT_DURATION_MINUTES = 30;
    public static final int MAX_DURATION_MINUTES = 23 * 60 + 59;
    public static final int UNTIL_TASK_END_MINUTES = -1;
    public static final long UNTIL_TASK_END_MS = -1L;
    public static final long DEFAULT_DURATION_MS = DEFAULT_DURATION_MINUTES * 60 * 1000L;
    /** Default used by callers that have no explicit duration selection. */
    public static final long LIMIT_MS = DEFAULT_DURATION_MS;
    public static final long LOST_MS = 45000L;
    private TrackingPolicy() {}

    public static boolean validDurationMinutes(int value) {
        return value == UNTIL_TASK_END_MINUTES || (value >= 1 && value <= MAX_DURATION_MINUTES);
    }
    public static int sanitizeDurationMinutes(int value) {
        return validDurationMinutes(value) ? value : DEFAULT_DURATION_MINUTES;
    }
    public static long durationMs(int minutes) {
        int value = sanitizeDurationMinutes(minutes);
        return value == UNTIL_TASK_END_MINUTES ? UNTIL_TASK_END_MS : value * 60 * 1000L;
    }
    public static long sanitizeDurationMs(long value) {
        if (value == UNTIL_TASK_END_MS || (value >= 60000L
                && value <= MAX_DURATION_MINUTES * 60000L && value % 60000L == 0)) return value;
        return DEFAULT_DURATION_MS;
    }

    /** A dismissed choice cannot later confirm or start a second tracking request. */
    public static final class DurationChoice {
        private int minutes;
        private boolean open = true;
        public DurationChoice(int preferredMinutes) { minutes = sanitizeDurationMinutes(preferredMinutes); }
        public int selectedMinutes() { return minutes; }
        public void select(int value) { if (open && validDurationMinutes(value)) minutes = value; }
        public int confirm() { if (!open) return 0; open = false; return minutes; }
        public void cancel() { open = false; }
    }

    /** Called before accepting a response: a late success cannot renew an expired lease. */
    public static String deadline(long now, long started, long lastSuccess,
            long lastConfirmedRunning, boolean confirmingState) {
        return deadline(now, started, lastSuccess, lastConfirmedRunning, confirmingState, DEFAULT_DURATION_MS);
    }
    public static String deadline(long now, long started, long lastSuccess,
            long lastConfirmedRunning, boolean confirmingState, long durationMs) {
        long limit = sanitizeDurationMs(durationMs);
        if (limit != UNTIL_TASK_END_MS && now - started >= limit) return (limit / 60000L) + " 分钟跟踪已结束";
        if (now - lastSuccess >= LOST_MS) return "连接中断，已停止跟踪";
        if (confirmingState && now - lastConfirmedRunning >= LOST_MS) return "状态未能确认，已停止跟踪";
        return "";
    }
    public static boolean confirmedRunning(boolean exists, boolean archived, boolean online, String status) {
        return exists && !archived && online && "running".equals(status);
    }
    public static boolean confirmedWaiting(boolean exists, boolean archived, boolean online, String status, long durationMs) {
        return sanitizeDurationMs(durationMs) == UNTIL_TASK_END_MS
                && exists && !archived && online && "waiting".equals(status);
    }
    /** A fresh, authorized snapshot can confirm the task still exists without proving it is running. */
    public static boolean observesUnknown(boolean exists, boolean archived, boolean online, String status, long durationMs) {
        return sanitizeDurationMs(durationMs) == UNTIL_TASK_END_MS
                && exists && !archived && online && "unknown".equals(status);
    }
    public static String terminal(long now, long started, long lastSuccess, long lastConfirmedRunning,
            boolean networkFailed, boolean exists, boolean archived, boolean online, String status) {
        return terminal(now, started, lastSuccess, lastConfirmedRunning, networkFailed, exists, archived, online,
                status, DEFAULT_DURATION_MS);
    }
    public static String terminal(long now, long started, long lastSuccess, long lastConfirmedRunning,
            boolean networkFailed, boolean exists, boolean archived, boolean online, String status, long durationMs) {
        String cutoff = deadline(now, started, lastSuccess, lastConfirmedRunning, false, durationMs);
        if (!cutoff.isEmpty()) return cutoff;
        if (networkFailed) return "";
        if (exists && archived) return "任务已归档，已停止跟踪";
        if (exists && !online) return "电脑已离线，已停止跟踪";
        if (exists && "completed".equals(status)) return "本轮结束";
        if (exists && "waiting".equals(status))
            return sanitizeDurationMs(durationMs) == UNTIL_TASK_END_MS ? "" : "等待批准，请在电脑上处理";
        if (exists && "error".equals(status)) return "执行出错，请打开工作台查看";
        if (exists && "idle".equals(status)) return "任务已空闲，已停止跟踪";
        if (confirmedRunning(exists, archived, online, status)) return "";
        if (observesUnknown(exists, archived, online, status, durationMs)) return "";
        // Missing, empty, and unrecognized states are uncertainty, not task completion.
        return deadline(now, started, lastSuccess, lastConfirmedRunning, true, durationMs);
    }
    public static boolean accepts(boolean active, long currentGeneration, long callbackGeneration,
            long now, long started) {
        return accepts(active, currentGeneration, callbackGeneration, now, started, DEFAULT_DURATION_MS);
    }
    public static boolean accepts(boolean active, long currentGeneration, long callbackGeneration,
            long now, long started, long durationMs) {
        long limit = sanitizeDurationMs(durationMs);
        return active && currentGeneration == callbackGeneration
                && (limit == UNTIL_TASK_END_MS || now - started < limit);
    }
}
