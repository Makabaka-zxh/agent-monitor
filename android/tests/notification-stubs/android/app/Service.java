package android.app;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

public abstract class Service extends Context {
    public static final int START_NOT_STICKY = 2, STOP_FOREGROUND_REMOVE = 1;
    public Notification foregroundNotification;
    public boolean deferDefaultForegroundNotifications;
    public int stopSelfCalls, stopForegroundCalls, startForegroundCalls;
    public void onCreate() {}
    public int onStartCommand(Intent intent, int flags, int startId) { return 0; }
    public void startForeground(int id, Notification notification) {
        ++startForegroundCalls; foregroundNotification = notification;
        // Opt-in test simulation of Android 12+'s allowed initial foreground-notification deferral.
        if (!deferDefaultForegroundNotifications || android.os.Build.VERSION.SDK_INT < 31
                || notification.foregroundServiceBehavior == Notification.FOREGROUND_SERVICE_IMMEDIATE) notifications.expose(id, notification);
    }
    public void startForeground(int id, Notification notification, int type) { startForeground(id, notification); }
    public void stopForeground(int flags) { ++stopForegroundCalls; }
    public void stopSelf() { ++stopSelfCalls; }
    public void onTimeout(int startId, int type) {}
    public void onDestroy() {}
    public abstract IBinder onBind(Intent intent);
}
