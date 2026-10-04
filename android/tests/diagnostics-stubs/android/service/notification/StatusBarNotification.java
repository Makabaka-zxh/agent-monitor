package android.service.notification;
public final class StatusBarNotification {
    private final String pkg, tag;
    private final int uid, id;
    private final android.app.Notification notification;
    public int reads;
    public StatusBarNotification(String pkg, int uid, int id, String tag, android.app.Notification notification) {
        this.pkg = pkg; this.uid = uid; this.id = id; this.tag = tag; this.notification = notification;
    }
    public String getPackageName() { return pkg; }
    public int getUid() { return uid; }
    public int getId() { return id; }
    public String getTag() { return tag; }
    public android.app.Notification getNotification() { reads++; return notification; }
}
