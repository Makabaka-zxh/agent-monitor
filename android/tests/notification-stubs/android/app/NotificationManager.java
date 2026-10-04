package android.app;

import android.content.ComponentName;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import java.util.HashMap;
import java.util.Map;

public final class NotificationManager {
    public static final int IMPORTANCE_NONE = 0, IMPORTANCE_LOW = 2;
    private final Context context;
    private final Map<String, NotificationChannel> channels = new HashMap<>();
    public boolean enabled = true, promotionAllowed = true, listenerAccessGranted, autoExpose = true;
    public int cancelCalls, notifyCalls, lastCancelledId;
    public Notification lastNotification;
    public StatusBarNotification[] activeNotifications = new StatusBarNotification[0];
    public NotificationManager(Context context) { this.context = context; }
    public void createNotificationChannel(NotificationChannel channel) { channels.put(channel.id, channel); }
    public NotificationChannel getNotificationChannel(String id) { return channels.get(id); }
    public boolean areNotificationsEnabled() { return enabled; }
    public boolean canPostPromotedNotifications() { return promotionAllowed; }
    public boolean isNotificationListenerAccessGranted(ComponentName component) { return listenerAccessGranted; }
    public StatusBarNotification[] getActiveNotifications() { return activeNotifications; }
    public void expose(int id, Notification notification) {
        if (autoExpose) activeNotifications = new StatusBarNotification[]{new StatusBarNotification(
                context.getPackageName(), context.getApplicationInfo().uid, id, null, notification)};
    }
    public void cancel(int id) { ++cancelCalls; lastCancelledId = id; activeNotifications = new StatusBarNotification[0]; }
    public void notify(int id, Notification notification) { ++notifyCalls; lastNotification = notification; expose(id, notification); }
}