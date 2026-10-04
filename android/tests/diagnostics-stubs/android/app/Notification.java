package android.app;
public final class Notification {
    public static final String EXTRA_TEMPLATE = "android.template";
    public int flags, visibility;
    public Notification publicVersion;
    public android.os.Bundle extras = new android.os.Bundle();
    public android.graphics.drawable.Icon small;
    public String shortText;
    public boolean promotable;
    public android.graphics.drawable.Icon getSmallIcon() { return small; }
    public String getShortCriticalText() { return shortText; }
    public boolean hasPromotableCharacteristics() { return promotable; }
    public String getChannelId() { return "fixed-channel"; }
}
