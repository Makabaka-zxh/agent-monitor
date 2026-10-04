package android.app;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** Records builder inputs; it does not implement System UI or promotion eligibility. */
public final class Notification {
    public static final int FLAG_ONGOING_EVENT = 0x2, FLAG_NO_CLEAR = 0x20, FLAG_AUTO_CANCEL = 0x10, FLAG_ONLY_ALERT_ONCE = 0x8;
    public static final int VISIBILITY_PRIVATE = 0, VISIBILITY_PUBLIC = 1, VISIBILITY_SECRET = -1;
    public static final int FOREGROUND_SERVICE_DEFAULT = 0, FOREGROUND_SERVICE_IMMEDIATE = 1;
    private String channelId;
    public String getChannelId() { return channelId; }
    public int flags;
    public int visibility, smallIcon;
    public int foregroundServiceBehavior = FOREGROUND_SERVICE_DEFAULT, foregroundServiceBehaviorCalls;
    public Notification publicVersion;
    public android.graphics.drawable.Icon largeIcon;
    public android.os.Bundle extras = new android.os.Bundle();
    public long timeoutAfter;
    public boolean requestPromotedOngoing;
    public CharSequence contentTitle, contentText, shortCriticalText, bigText;
    public PendingIntent contentIntent, deleteIntent;
    public Action[] actions;

    public static final class Builder {
        private final Notification value = new Notification();
        private final List<Action> actions = new ArrayList<>();
        public Builder(Context context, String channel) { value.channelId = channel; }
        public Builder addExtras(android.os.Bundle extras) { value.extras = new android.os.Bundle(extras); return this; }
        public Builder setSmallIcon(int icon) { value.smallIcon = icon; return this; }
        public Builder setLargeIcon(android.graphics.drawable.Icon icon) { value.largeIcon = icon; return this; }
        public Builder setContentTitle(CharSequence text) { value.contentTitle = text; return this; }
        public Builder setContentText(CharSequence text) { value.contentText = text; return this; }
        public Builder setStyle(BigTextStyle style) { value.bigText = style.text; return this; }
        public Builder setColor(int color) { return this; }
        public Builder setColorized(boolean enabled) { return this; }
        public Builder setContentIntent(PendingIntent intent) { value.contentIntent = intent; return this; }
        public Builder setVisibility(int visibility) { value.visibility = visibility; return this; }
        public Builder setPublicVersion(Notification publicVersion) { value.publicVersion = publicVersion; return this; }
        public Builder setOnlyAlertOnce(boolean enabled) { return flag(FLAG_ONLY_ALERT_ONCE, enabled); }
        public Builder setOngoing(boolean enabled) { return flag(FLAG_ONGOING_EVENT, enabled); }
        public Builder setForegroundServiceBehavior(int behavior) {
            if (android.os.Build.VERSION.SDK_INT < 31) throw new NoSuchMethodError("setForegroundServiceBehavior requires API 31");
            value.foregroundServiceBehavior = behavior; ++value.foregroundServiceBehaviorCalls; return this;
        }
        public Builder setAutoCancel(boolean enabled) { return flag(FLAG_AUTO_CANCEL, enabled); }
        private Builder flag(int flag, boolean enabled) { if (enabled) value.flags |= flag; else value.flags &= ~flag; return this; }
        public Builder setShowWhen(boolean enabled) { return this; }
        public Builder setShortCriticalText(CharSequence text) { value.shortCriticalText = text; return this; }
        public Builder setTimeoutAfter(long timeout) { value.timeoutAfter = timeout; return this; }
        public Builder addAction(Action action) { actions.add(action); return this; }
        public Builder setDeleteIntent(PendingIntent intent) { value.deleteIntent = intent; return this; }
        public Builder setRequestPromotedOngoing(boolean requested) { value.requestPromotedOngoing = requested; return this; }
        public Notification build() { value.actions = actions.isEmpty() ? null : actions.toArray(new Action[0]); return value; }
    }
    public static final class BigTextStyle {
        CharSequence text;
        public BigTextStyle bigText(CharSequence value) { text = value; return this; }
    }
    public static final class Action {
        public final CharSequence title;
        public final PendingIntent actionIntent;
        private Action(CharSequence title, PendingIntent actionIntent) { this.title = title; this.actionIntent = actionIntent; }
        public static final class Builder {
            private final CharSequence title;
            private final PendingIntent actionIntent;
            public Builder(Object icon, CharSequence title, PendingIntent actionIntent) { this.title = title; this.actionIntent = actionIntent; }
            public Action build() { return new Action(title, actionIntent); }
        }
    }
}
