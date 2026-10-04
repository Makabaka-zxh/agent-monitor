package com.agentmonitor.live;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Debug-only, private, fixed-schema metadata. Never include notification text or identity. */
public final class NotificationDiagnostics {
    private static final int MAX_PARAM_CHARS = 16384, MAX_PARAM_BYTES = 16384;
    private NotificationDiagnostics() {}
    public static synchronized void write(Context context, String phase, boolean requested, Notification notification) {
        write(context, phase, requested, notification, "");
    }
    /** Only fixed reason codes reach the file; notification text is never recorded. */
    public static String endReason(String message) {
        if (message == null || message.isEmpty()) return "internal_or_permission";
        if (message.matches("[1-9][0-9]{0,3} 分钟跟踪已结束")) return "duration_elapsed";
        if (message.equals("连接中断，已停止跟踪")) return "connection_lost";
        if (message.equals("状态未能确认，已停止跟踪")) return "state_unknown";
        if (message.equals("任务已归档，已停止跟踪")) return "task_archived";
        if (message.equals("电脑已离线，已停止跟踪")) return "device_offline";
        if (message.equals("本轮结束")) return "task_completed";
        if (message.equals("等待批准，请在电脑上处理")) return "task_waiting";
        if (message.equals("执行出错，请打开工作台查看")) return "task_error";
        if (message.equals("任务已空闲，已停止跟踪")) return "task_idle";
        if (message.equals("系统已结束本次跟踪")) return "system_timeout";
        return "other";
    }
    public static synchronized void write(Context context, String phase, boolean requested, Notification notification, String reason) {
        try {
            if (context == null) return;
            ApplicationInfo app = context.getApplicationInfo();
            if (app == null || (app.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            JSONArray flags = new JSONArray();
            Notification activeTracking = null;
            int ownActiveCount = 0, trackingCount = 0;
            boolean activeQuerySucceeded = false;
            try {
                // NotificationManager returns only this app's notifications. Still check ownership
                // before reading a notification, and inspect only the untagged tracking slot.
                StatusBarNotification[] active = manager.getActiveNotifications();
                if (active != null) {
                    activeQuerySucceeded = true;
                    for (StatusBarNotification item : active) {
                        if (item == null || !context.getPackageName().equals(item.getPackageName())
                                || item.getUid() != app.uid) continue;
                        ownActiveCount++;
                        if (item.getId() != TrackingService.NOTIFICATION_ID || item.getTag() != null) continue;
                        trackingCount++;
                        activeTracking = item.getNotification();
                        if (activeTracking != null) flags.put(activeTracking.flags);
                    }
                }
            } catch (RuntimeException | LinkageError unavailable) { }
            boolean allows = Build.VERSION.SDK_INT >= 36 && manager.canPostPromotedNotifications();
            boolean characteristics = Build.VERSION.SDK_INT >= 36 && notification != null && notification.hasPromotableCharacteristics();
            NotificationChannel channel = notification == null ? null : manager.getNotificationChannel(notification.getChannelId());
            String template = "";
            try {
                if (notification != null && notification.extras != null)
                    template = notification.extras.getString(Notification.EXTRA_TEMPLATE, "");
            } catch (RuntimeException | LinkageError unavailable) { }
            boolean progressStyle = "android.app.Notification$ProgressStyle".equals(template);
            boolean shortText = Build.VERSION.SDK_INT >= 36 && notification != null
                    && notification.getShortCriticalText() != null && !notification.getShortCriticalText().isEmpty();
            XiaomiLiveCapabilities cached = XiaomiLiveCapabilities.cached();
            JSONObject status = new JSONObject().put("schemaVersion", 2)
                    .put("evidenceScope", "app_notification_metadata_only")
                    // notify() and this snapshot are not an atomic System UI observation. A
                    // differing active payload may still be the previous update, not rejection.
                    .put("activeSnapshotMayLag", true)
                    .put("phase", phase(phase)).put("promoteRequested", requested)
                    .put("endReason", reason(reason))
                    .put("systemAllowsPromotion", allows).put("hasPromotableCharacteristics", characteristics)
                    .put("activeQuerySucceeded", activeQuerySucceeded)
                    .put("activeNotificationCount", ownActiveCount).put("activeNotificationFlags", flags)
                    .put("activeTrackingCount", trackingCount)
                    .put("outgoing", metadata(context, notification))
                    .put("activeTracking", metadata(context, activeTracking))
                    .put("xiaomiAppIdConfigured", XiaomiOnboarding.configured(context))
                    .put("xiaomiCachedSystemProtocol", cached == null ? -1 : protocol(cached.protocolVersion))
                    .put("xiaomiCachedIsland", cached == null ? "unknown" : state(cached.island))
                    .put("xiaomiCachedFocusSwitch", cached == null ? "unknown" : state(cached.focusPermission))
                    .put("notificationFlags", notification == null ? 0 : notification.flags)
                    .put("hasShortCriticalText", shortText).put("progressStyle", progressStyle)
                    .put("channelImportance", channel == null ? -1 : channel.getImportance())
                    .put("notificationsEnabled", manager.areNotificationsEnabled())
                    .put("interruptionFilter", manager.getCurrentInterruptionFilter())
                    .put("clearAllRestores", TrackingService.clearAllRestores)
                    .put("trackingUntilTaskEnd", TrackingService.untilTaskEnd)
                    .put("trackingRemainingMs", Math.max(0, TrackingService.endsAt - SystemClock.elapsedRealtime()))
                    .put("time", System.currentTimeMillis());
            File temporary = new File(context.getFilesDir(), "notification-status.tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(status.toString().getBytes(StandardCharsets.UTF_8)); }
            temporary.renameTo(new File(context.getFilesDir(), "notification-status.json"));
        } catch (Exception | LinkageError ignored) { }
    }

    private static String phase(String value) {
        if ("started".equals(value) || "updated".equals(value) || "restored".equals(value)
                || "ended".equals(value) || "stopped".equals(value)) return value;
        return "unknown";
    }
    private static String reason(String value) {
        if (value == null || value.isEmpty()) return "";
        switch (value) {
            case "internal_or_permission": case "duration_elapsed": case "connection_lost":
            case "state_unknown": case "task_archived": case "device_offline":
            case "task_completed": case "task_waiting": case "task_error": case "task_idle":
            case "system_timeout": case "user_stop": case "notification_dismissed":
            case "clear_all_restored": return value;
            default: return "other";
        }
    }
    private static String state(XiaomiLiveCapabilities.State value) {
        if (value == XiaomiLiveCapabilities.State.ENABLED) return "enabled";
        if (value == XiaomiLiveCapabilities.State.DISABLED) return "disabled";
        return "unknown";
    }
    private static int protocol(int value) { return value >= 0 && value <= 64 ? value : -1; }

    /** No notification text, identity, resource name, arbitrary extras or OEM payload is serialized. */
    private static JSONObject metadata(Context context, Notification notification) throws Exception {
        JSONObject result = surface(context, notification);
        // Exactly one public level; malformed cyclic publicVersion links cannot recurse.
        result.put("publicVersion", surface(context, notification == null ? null : notification.publicVersion));
        return result;
    }
    private static JSONObject surface(Context context, Notification notification) throws Exception {
        JSONObject result = new JSONObject().put("present", notification != null);
        if (notification == null) return result;
        result.put("flags", notification.flags)
                .put("visibility", notification.visibility >= -1 && notification.visibility <= 1 ? notification.visibility : -2)
                .put("smallResourceIcon", resourceIcon(notification.getSmallIcon()))
                .put("xiaomi", xiaomi(context, notification.extras));
        return result;
    }
    private static boolean resourceIcon(Icon icon) {
        try { return icon != null && icon.getType() == Icon.TYPE_RESOURCE && icon.getResId() != 0; }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }
    private static JSONObject xiaomi(Context context, Bundle extras) throws Exception {
        JSONObject result = new JSONObject().put("inspectionSucceeded", false)
                .put("paramKeyPresent", false).put("paramString", false)
                .put("paramUtf8Bytes", 0).put("paramSizeLimitExceeded", false)
                .put("paramJsonObject", false).put("paramV2", false).put("payloadProtocol", -1)
                .put("islandTemplate", false).put("aodImageReferencesTool", false)
                .put("expandedImagesReferenceTool", false).put("smallIslandImageReferencesTool", false)
                .put("picturesBundle", false).put("toolImageKeyPresent", false)
                .put("toolResourceIcon", false).put("toolBundledResourceIcon", false);
        if (extras == null) return result.put("inspectionSucceeded", true);
        try {
            result.put("paramKeyPresent", extras.containsKey(XiaomiLiveNotification.PARAM));
            Object raw = extras.get(XiaomiLiveNotification.PARAM);
            if (raw instanceof String) {
                String payload = (String) raw;
                result.put("paramString", true);
                // -1 explicitly means an oversized value was not encoded or parsed.
                byte[] bytes = payload.length() <= MAX_PARAM_CHARS ? payload.getBytes(StandardCharsets.UTF_8) : null;
                result.put("paramUtf8Bytes", bytes == null ? -1 : bytes.length);
                boolean oversized = bytes == null || bytes.length > MAX_PARAM_BYTES;
                result.put("paramSizeLimitExceeded", oversized);
                if (!oversized) {
                    JSONObject document = null;
                    try {
                        document = new JSONObject(payload);
                    } catch (Exception | StackOverflowError invalidJson) {
                        // Even a short payload can be deeply nested. Isolate only decoder
                        // recursion exhaustion; diagnostics must never interrupt tracking.
                    }
                    if (document != null) {
                        result.put("paramJsonObject", true);
                        JSONObject v2 = document.optJSONObject("param_v2");
                        if (v2 != null) {
                            result.put("paramV2", true);
                            Object version = v2.opt("protocol");
                            if (version instanceof Integer || version instanceof Long) {
                                long number = ((Number) version).longValue();
                                result.put("payloadProtocol", number >= 0 && number <= 64 ? (int) number : -1);
                            }
                            result.put("aodImageReferencesTool", toolImage(v2.opt("aodPic")));
                            JSONObject pic = v2.optJSONObject("picInfo");
                            result.put("expandedImagesReferenceTool", pic != null
                                    && toolImage(pic.opt("pic")) && toolImage(pic.opt("picDark")));
                            JSONObject island = v2.optJSONObject("param_island");
                            result.put("islandTemplate", island != null);
                            JSONObject small = island == null ? null : island.optJSONObject("smallIslandArea");
                            JSONObject smallPic = small == null ? null : small.optJSONObject("picInfo");
                            result.put("smallIslandImageReferencesTool", smallPic != null && toolImage(smallPic.opt("pic")));
                        }
                    }
                }
            }
            Object pictures = extras.get(XiaomiLiveNotification.PICS);
            if (pictures instanceof Bundle) {
                Bundle bundle = (Bundle) pictures;
                result.put("picturesBundle", true)
                        .put("toolImageKeyPresent", bundle.containsKey(XiaomiLiveNotification.TOOL_ICON));
                Object image = bundle.get(XiaomiLiveNotification.TOOL_ICON);
                if (image instanceof Icon) {
                    Icon icon = (Icon) image;
                    boolean resource = resourceIcon(icon);
                    result.put("toolResourceIcon", resource)
                            .put("toolBundledResourceIcon", resource && context.getPackageName().equals(icon.getResPackage())
                                    && (icon.getResId() == R.drawable.codex || icon.getResId() == R.drawable.claude));
                }
            }
            return result.put("inspectionSucceeded", true);
        } catch (RuntimeException | LinkageError unreadable) { return result; }
    }
    private static boolean toolImage(Object value) { return XiaomiLiveNotification.TOOL_ICON.equals(value); }
}
