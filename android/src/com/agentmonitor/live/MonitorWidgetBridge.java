package com.agentmonitor.live;

import android.content.Context;

/**
 * Lightweight zero-AppWidget-dependency bridge between {@link TrackingService} and home screen widgets.
 * Keeping {@code android.appwidget.*} out of this class allows offline notification stub tests to compile
 * {@link TrackingService} without mocking AppWidget framework classes.
 */
public final class MonitorWidgetBridge {
    public interface Listener {
        void onWidgetStateChanged(Context context);
    }

    public static final class TrackingSnapshot {
        public final boolean active;
        public final String taskId;
        public final String title;
        public final String tool;
        public final String computer;
        public final String message;
        public final long generation;
        public final long elapsedMillis;
        public final long remainingMillis;
        public final boolean untilTaskEnd;
        public final Long sessionTokens;
        public final Long todayTokens;

        public TrackingSnapshot(
                boolean active,
                String taskId,
                String title,
                String tool,
                String computer,
                String message,
                long generation,
                long elapsedMillis,
                long remainingMillis,
                boolean untilTaskEnd,
                Long sessionTokens,
                Long todayTokens) {
            this.active = active;
            this.taskId = taskId == null ? "" : taskId;
            this.title = title == null ? "" : title;
            this.tool = tool == null ? "" : tool;
            this.computer = computer == null ? "" : computer;
            this.message = message == null ? "" : message;
            this.generation = generation;
            this.elapsedMillis = Math.max(0L, elapsedMillis);
            this.remainingMillis = Math.max(0L, remainingMillis);
            this.untilTaskEnd = untilTaskEnd;
            this.sessionTokens = sessionTokens;
            this.todayTokens = todayTokens;
        }

        public static TrackingSnapshot idle() {
            return new TrackingSnapshot(false, "", "", "", "", "", 0L, 0L, 0L, false, null, null);
        }
    }

    private static volatile Listener listener;
    private static volatile TrackingSnapshot current = TrackingSnapshot.idle();

    private MonitorWidgetBridge() { }

    public static void setListener(Listener value) {
        listener = value;
    }

    public static TrackingSnapshot current() {
        return current;
    }

    public static void updateTracking(
            Context context,
            boolean active,
            String taskId,
            String title,
            String tool,
            String computer,
            String message,
            long generation,
            long elapsedMillis,
            long remainingMillis,
            boolean untilTaskEnd,
            Long sessionTokens,
            Long todayTokens) {
        current = active
                ? new TrackingSnapshot(
                        true,
                        taskId,
                        title,
                        tool,
                        computer,
                        message,
                        generation,
                        elapsedMillis,
                        remainingMillis,
                        untilTaskEnd,
                        sessionTokens,
                        todayTokens)
                : TrackingSnapshot.idle();
        notifyWidgets(context);
    }

    public static void clearTracking(Context context) {
        boolean wasActive = current.active;
        current = TrackingSnapshot.idle();
        if (wasActive) notifyWidgets(context);
    }

    public static void notifyWidgets(Context context) {
        Listener target = listener;
        if (target == null || context == null) return;
        try {
            Context app = context.getApplicationContext();
            target.onWidgetStateChanged(app != null ? app : context);
        } catch (RuntimeException ignored) {
            // Widget updates are best-effort UI notifications and never interrupt foreground tracking.
        }
    }
}
