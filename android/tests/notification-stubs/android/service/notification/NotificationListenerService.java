package android.service.notification;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class NotificationListenerService extends Service {
    public static final int REASON_CANCEL = 2, REASON_CANCEL_ALL = 3;
    public static class RankingMap {}
    public void onListenerConnected() {}
    public void onListenerDisconnected() {}
    public void onNotificationRemoved(StatusBarNotification item, RankingMap rankings, int reason) {}
    @Override public IBinder onBind(Intent intent) { return null; }
}