package com.agentmonitor.live;

import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Optional system-authorized removal reasons, restricted immediately to this app's tracking card. */
public final class TrackingRemovalListener extends NotificationListenerService {
    static volatile boolean connected;
    static volatile long connectionGeneration;

    static boolean isOwnTracking(Context context, StatusBarNotification item) {
        return item != null && item.getId() == TrackingService.NOTIFICATION_ID && item.getTag() == null
                && context.getPackageName().equals(item.getPackageName()) && item.getUid() == context.getApplicationInfo().uid
                && item.getNotification() != null && TrackingService.CHANNEL.equals(item.getNotification().getChannelId());
    }

    @Override public void onListenerConnected() { super.onListenerConnected(); ++connectionGeneration; connected = true; }

    @Override public void onListenerDisconnected() {
        connected = false; ++connectionGeneration; TrackingService.listenerDisconnected(); super.onListenerDisconnected();
    }

    @Override public void onNotificationRemoved(StatusBarNotification item, RankingMap rankings, int reason) {
        if (!isOwnTracking(this, item)) return;
        try {
            // Read only our routing markers. Notification text never enters this path.
            Bundle metadata = item.getNotification().extras;
            if (metadata == null) return;
            TrackingService.removed(metadata.getLong(TrackingService.EXTRA_GENERATION, -1),
                    metadata.getString(TrackingService.EXTRA_SESSION), metadata.getString(TrackingService.EXTRA_PRESENTATION), reason, connectionGeneration);
        } catch (RuntimeException ignored) { }
    }

    @Override public void onDestroy() {
        connected = false; ++connectionGeneration; TrackingService.listenerDisconnected(); super.onDestroy();
    }
}
