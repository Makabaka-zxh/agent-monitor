package android.service.notification;

import android.app.Notification;

public final class StatusBarNotification {
    private final String packageName, tag;
    private final int uid, id;
    private final Notification notification;
    public int notificationReads;
    public StatusBarNotification(String packageName, int uid, int id, String tag, Notification notification) {
        this.packageName = packageName; this.uid = uid; this.id = id; this.tag = tag; this.notification = notification;
    }
    public String getPackageName() { return packageName; }
    public int getUid() { return uid; }
    public int getId() { return id; }
    public String getTag() { return tag; }
    public Notification getNotification() { ++notificationReads; return notification; }
}