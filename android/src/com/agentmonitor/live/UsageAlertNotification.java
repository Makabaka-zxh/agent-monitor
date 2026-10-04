package com.agentmonitor.live;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** Shared rendering only: no requests, usage data writes or reminder scheduling. */
final class UsageAlertNotification {
    static final int ID = 47018;
    static final String TEST_TAG = "usage:test";
    private UsageAlertNotification() { }

    static Notification real(Context context, String channel, String tool, String text, String detail, boolean locked) {
        return builder(context, channel, tool, text, detail, locked).build();
    }
    static Notification test(Context context, String channel, boolean locked) {
        return builder(context, channel, "codex", "测试提醒", "用于检查通知显示，不代表真实额度。", locked)
                .setOnlyAlertOnce(true).setTimeoutAfter(120000).build();
    }
    private static Notification.Builder builder(Context context, String channel, String tool,
                                                 String text, String detail, boolean locked) {
        String brand = "claude".equals(tool) ? "Claude Code" : "Codex";
        int icon = "claude".equals(tool) ? R.drawable.ic_claude : R.drawable.ic_codex;
        Intent open = new Intent(context, MainActivity.class).setAction("com.agentmonitor.live.OPEN_USAGE");
        PendingIntent content = PendingIntent.getActivity(context, ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification publicView = new Notification.Builder(context, channel).setSmallIcon(icon).setContentTitle(brand)
                .setVisibility(Notification.VISIBILITY_PUBLIC).build();
        // SECRET lets System UI hide details on the lockscreen while preserving them
        // for unlock. Clearing the body here would permanently empty this delivered notice.
        // Real notices are already deduplicated by reason in UsageAlerts: a later reset
        // reminder must alert even when its earlier low-balance notice remains in the tray.
        Notification.Builder result = new Notification.Builder(context, channel).setSmallIcon(icon).setContentTitle(brand)
                .setContentText(text).setVisibility(Notification.VISIBILITY_SECRET).setPublicVersion(publicView)
                .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(false);
        result.setStyle(new Notification.BigTextStyle().bigText(text + (detail.isEmpty() ? "" : "\n" + detail)));
        return result;
    }
}
