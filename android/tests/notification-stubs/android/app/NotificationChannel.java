package android.app;
public final class NotificationChannel {
    public final String id;
    private int importance;
    public NotificationChannel(String id, String name, int importance) { this.id = id; this.importance = importance; }
    public void setDescription(String description) {}
    public void setLockscreenVisibility(int visibility) {}
    public int getImportance() { return importance; }
    public void setImportance(int value) { importance = value; }
}