package android.app;
public final class NotificationManager {
    public android.service.notification.StatusBarNotification[] active = new android.service.notification.StatusBarNotification[0];
    public boolean throwActive;
    public int activeCalls;
    public android.service.notification.StatusBarNotification[] getActiveNotifications() {
        activeCalls++;
        if (throwActive) throw new SecurityException("private-exception-detail");
        return active;
    }
    public boolean canPostPromotedNotifications() { return true; }
    public NotificationChannel getNotificationChannel(String id) { return null; }
    public boolean areNotificationsEnabled() { return true; }
    public int getCurrentInterruptionFilter() { return 1; }
}
