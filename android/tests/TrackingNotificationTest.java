package com.agentmonitor.live;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes the production service and receivers with recording Android stubs.
 * Checks notification construction and session invalidation, not OEM System UI.
 */
public final class TrackingNotificationTest {
    private static final List<TrackingService> services = new ArrayList<>();
    private static int checks;

    private static void check(boolean condition, String label) {
        ++checks;
        if (!condition) throw new AssertionError(label);
    }
    private static Object invoke(TrackingService service, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = TrackingService.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(service, args);
    }
    private static TrackingService start(String id) throws Exception {
        return start(id, 30);
    }
    private static TrackingService start(String id, int minutes) throws Exception {
        return start(id, minutes, false);
    }
    private static TrackingService start(String id, int minutes, boolean deferDefaultForegroundNotifications) throws Exception {
        return start(id, minutes, deferDefaultForegroundNotifications, null);
    }
    private static TrackingService start(String id, int minutes, boolean deferDefaultForegroundNotifications,
                                         java.util.function.Consumer<TrackingService> setup) throws Exception {
        SystemClock.now = 1000000L + services.size() * 10000000L;
        SessionStore.currentToken = "offline-test-token";
        TrackingRemovalListener.connected = false;
        TrackingService service = new TrackingService();
        if (setup != null) setup.accept(service);
        service.deferDefaultForegroundNotifications = deferDefaultForegroundNotifications;
        services.add(service);
        service.onCreate();
        int result = service.onStartCommand(new Intent(service, TrackingService.class).setAction("track")
                .putExtra("task_id", id).putExtra("title", "Offline test task")
                .putExtra("tool", "codex").putExtra("duration_minutes", minutes), 0, services.size());
        check(result == TrackingService.START_NOT_STICKY, id + ": session never auto-restarts");
        check(service.foregroundNotification != null, id + ": starts with an actual foreground notification");
        check(id.equals(TrackingService.trackedId), id + ": starts the selected session");
        handler(service).queued.clear(); // Remove only the initial network ticker before any manual queue pump.
        return service;
    }
    private static Notification notification(TrackingService service, String message, boolean ongoing, boolean promote) throws Exception {
        return (Notification) invoke(service, "notification",
                new Class<?>[]{String.class, boolean.class, boolean.class}, message, ongoing, promote);
    }
    private static void active(Notification value, long epoch, String label) {
        check((value.flags & Notification.FLAG_NO_CLEAR) != 0, label + ": clear-all protection");
        check((value.flags & Notification.FLAG_ONGOING_EVENT) != 0, label + ": ongoing");
        check((value.flags & Notification.FLAG_AUTO_CANCEL) == 0, label + ": content click does not auto-cancel");
        check(value.timeoutAfter == 0, label + ": no completion timeout");
        int expectedBehavior = Build.VERSION.SDK_INT >= 31 ? Notification.FOREGROUND_SERVICE_IMMEDIATE : Notification.FOREGROUND_SERVICE_DEFAULT;
        check(value.foregroundServiceBehavior == expectedBehavior, label + ": ongoing foreground visibility policy");
        check(value.publicVersion.foregroundServiceBehavior == expectedBehavior, label + ": public ongoing visibility policy");
        check(value.actions != null && value.actions.length == 1, label + ": one explicit stop action");
        PendingIntent stop = value.actions[0].actionIntent;
        PendingIntent dismiss = value.deleteIntent;
        check(dismiss != null, label + ": individual-dismiss receiver");
        check(stop != dismiss, label + ": stop and dismiss are distinct pending-intent tokens");
        check(stop.intent.component == StopReceiver.class, label + ": action targets StopReceiver");
        check(dismiss.intent.component == DismissReceiver.class, label + ": deletion targets DismissReceiver");
        check("停止跟踪".contentEquals(value.actions[0].title), label + ": explicit stop label");
        String session = session(value), presentation = presentation(value);
        check(value.extras.getLong(TrackingService.EXTRA_GENERATION, -1) == epoch, label + ": notification has its epoch");
        check(session != null && !session.isEmpty() && presentation != null && !presentation.isEmpty(),
                label + ": notification has session and presentation nonces");
        check(("agentmonitor://tracking/" + epoch + "/" + session).equals(stop.intent.getDataString()), label + ": stable stop identity");
        check(("agentmonitor://tracking-dismiss/" + epoch + "/" + presentation).equals(dismiss.intent.getDataString()), label + ": presentation dismissal identity");
        check(session.equals(stop.intent.getStringExtra(TrackingService.EXTRA_SESSION))
                && stop.intent.getStringExtra(TrackingService.EXTRA_PRESENTATION) == null,
                label + ": stop authorizes the stable session without binding to a presentation");
        check(session.equals(dismiss.intent.getStringExtra(TrackingService.EXTRA_SESSION))
                && presentation.equals(dismiss.intent.getStringExtra(TrackingService.EXTRA_PRESENTATION)),
                label + ": dismissal targets only the current presentation");
        for (PendingIntent token : new PendingIntent[]{stop, dismiss}) {
            check("broadcast".equals(token.kind), label + ": explicit broadcast");
            check(token.intent.getLongExtra("generation", -1) == epoch, label + ": generation attached");
        }
        for (PendingIntent token : new PendingIntent[]{value.contentIntent, stop, dismiss}) {
            check((token.flags & PendingIntent.FLAG_IMMUTABLE) != 0, label + ": immutable pending intent");
            check((token.flags & PendingIntent.FLAG_UPDATE_CURRENT) != 0, label + ": updates same-session pending intent");
        }
    }
    private static void notificationStates() throws Exception {
        TrackingService service = start("notification-states");
        long epoch = TrackingService.generation;
        Notification confirming = service.foregroundNotification;
        active(confirming, epoch, "initial confirmation");
        check(!confirming.requestPromotedOngoing && confirming.shortCriticalText == null,
                "initial confirmation does not claim confirmed running");

        Notification running = notification(service, "执行中", true, true);
        active(running, epoch, "confirmed running");
        check(running.requestPromotedOngoing, "confirmed running requests permitted promotion on Android 36.1");
        check("执行中".contentEquals(running.shortCriticalText), "confirmed running critical text");
        check(confirming.deleteIntent == running.deleteIntent, "updates reuse the same session's delete token");
        check(confirming.actions[0].actionIntent == running.actions[0].actionIntent, "updates reuse the same session's stop token");

        invoke(service, "networkFailure", new Class<?>[]{long.class}, SystemClock.elapsedRealtime() + 1000);
        Notification reconnecting = service.notifications.lastNotification;
        active(reconnecting, epoch, "reconnecting");
        check(!reconnecting.requestPromotedOngoing && reconnecting.shortCriticalText == null,
                "reconnection removes running promotion request");
        check("Codex · 正在重连".contentEquals(reconnecting.contentText), "reconnection exposes its current state");

        invoke(service, "finish", new Class<?>[]{String.class, boolean.class}, "本轮结束", true);
        Notification finished = service.notifications.lastNotification;
        check((finished.flags & (Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT)) == 0,
                "finished notice can be cleared");
        check((finished.flags & Notification.FLAG_AUTO_CANCEL) != 0, "finished notice auto-cancels");
        check(finished.deleteIntent == null, "finished notice cannot dismiss a session");
        check(finished.actions == null || finished.actions.length == 0, "finished notice has no stop action");
        check(finished.timeoutAfter == 60000, "finished notice expires after one minute");
        check(!finished.requestPromotedOngoing && finished.shortCriticalText == null, "finished notice is not promoted");
        check(service.stopSelfCalls == 1 && service.stopForegroundCalls == 1, "finishing stops the service once");
    }
    private static void unchanged(TrackingService service, long epoch, String task, String label) {
        check(TrackingService.generation == epoch, label + ": current epoch retained");
        check(task.equals(TrackingService.trackedId), label + ": current task retained");
        check(TrackingService.endsAt > 0, label + ": deadline retained");
        check(service.stopServiceCalls == 0 && service.notifications.cancelCalls == 0, label + ": no cancellation");
        check(NotificationDiagnostics.stoppedReasons.isEmpty(), label + ": no stopped diagnostic");
    }
    private static void foregroundVisibilityCompatibility() throws Exception {
        int originalSdk = Build.VERSION.SDK_INT, originalFull = Build.VERSION.SDK_INT_FULL;
        try {
            for (int sdk : new int[]{29, 30, 31, 32, 36, 37}) {
                Build.VERSION.SDK_INT = sdk;
                Build.VERSION.SDK_INT_FULL = sdk >= 36 ? sdk * 100000 + 1 : 0;
                String id = "foreground-visibility-api-" + sdk;
                TrackingService service = start(id, 30, true);
                Notification initial = service.foregroundNotification;
                int expected = sdk >= 31 ? Notification.FOREGROUND_SERVICE_IMMEDIATE : Notification.FOREGROUND_SERVICE_DEFAULT;
                for (Notification version : new Notification[]{initial, initial.publicVersion}) {
                    check(version.foregroundServiceBehavior == expected, id + ": initial foreground policy");
                    check(version.foregroundServiceBehaviorCalls == (sdk >= 31 ? 1 : 0), id + ": invokes only supported foreground API");
                }
                check(service.notifications.getActiveNotifications().length == 1,
                        id + ": initial notification visible even when platform defers default foreground notifications");
                long epoch = TrackingService.generation, deadline = TrackingService.endsAt;
                // A real state reply arriving after POST_SETTLE_MS must not interpret OS deferral as user dismissal.
                SystemClock.now = (Long) field(service, "postSettlesAt") + 1;
                snapshot(service, "running");
                check(id.equals(TrackingService.trackedId) && epoch == TrackingService.generation,
                        id + ": first state update after settle keeps the tracking session");
                check(service.notifications.notifyCalls == 1 && service.stopServiceCalls == 0 && service.stopSelfCalls == 0,
                        id + ": state update publishes rather than ending the service");
                check(TrackingService.endsAt == deadline && service.startForegroundCalls == 1,
                        id + ": immediate visibility neither restarts nor extends tracking");
                active(service.notifications.lastNotification, epoch, id + ": confirmed state");
                invoke(service, "finish", new Class<?>[]{String.class, boolean.class}, "本轮结束", true);
                for (Notification version : new Notification[]{service.notifications.lastNotification, service.notifications.lastNotification.publicVersion}) {
                    check(version.foregroundServiceBehavior == Notification.FOREGROUND_SERVICE_DEFAULT
                                    && version.foregroundServiceBehaviorCalls == 0,
                            id + ": completion notice does not request foreground immediacy");
                    check(version.timeoutAfter == 60000 && (version.flags & Notification.FLAG_ONGOING_EVENT) == 0,
                            id + ": completion retains its normal expiry and dismissibility");
                }
            }
        } finally {
            Build.VERSION.SDK_INT = originalSdk; Build.VERSION.SDK_INT_FULL = originalFull;
        }
    }
    private static void receiverIsolation() throws Exception {
        NotificationDiagnostics.stoppedReasons.clear();
        TrackingService previous = start("previous-session");
        long oldEpoch = TrackingService.generation;
        PendingIntent oldStop = previous.foregroundNotification.actions[0].actionIntent;
        PendingIntent oldDismiss = previous.foregroundNotification.deleteIntent;
        TrackingService current = start("current-session");
        long currentEpoch = TrackingService.generation;
        PendingIntent stop = current.foregroundNotification.actions[0].actionIntent;
        PendingIntent dismiss = current.foregroundNotification.deleteIntent;
        check(oldEpoch != currentEpoch, "new session advances generation");
        check(oldStop != stop && oldDismiss != dismiss, "new generation gets distinct stop and dismissal tokens");
        check(oldStop.intent.getLongExtra("generation", -1) == oldEpoch
                && oldDismiss.intent.getLongExtra("generation", -1) == oldEpoch,
                "UPDATE_CURRENT cannot rewrite previous generation's pending-intent extras");

        new StopReceiver().onReceive(current, oldStop.intent);
        unchanged(current, currentEpoch, "current-session", "stale stop");
        new DismissReceiver().onReceive(current, oldDismiss.intent);
        unchanged(current, currentEpoch, "current-session", "stale dismissal");
        new DismissReceiver().onReceive(current, null);
        new DismissReceiver().onReceive(current, new Intent(current, DismissReceiver.class));
        unchanged(current, currentEpoch, "current-session", "missing dismissal intent or epoch");

        new DismissReceiver().onReceive(current, dismiss.intent);
        check(TrackingService.generation == currentEpoch + 1, "dismissal invalidates matching generation");
        check(TrackingService.trackedId.isEmpty() && TrackingService.trackingMessage.isEmpty()
                && TrackingService.endsAt == 0, "dismissal clears exposed session state");
        check(current.stopServiceCalls == 1 && current.lastStoppedService.component == TrackingService.class,
                "dismissal stops the real tracking service");
        check(current.notifications.cancelCalls == 1
                && current.notifications.lastCancelledId == TrackingService.NOTIFICATION_ID,
                "dismissal removes only the tracking notification");
        check(NotificationDiagnostics.stoppedReasons.size() == 1
                && "notification_dismissed".equals(NotificationDiagnostics.stoppedReasons.get(0)),
                "dismissal has its own diagnostic reason");
        check(!(Boolean) invoke(current, "accepts", new Class<?>[]{long.class}, currentEpoch),
                "dismissal rejects an in-flight callback before Android delivers onDestroy");
        new DismissReceiver().onReceive(current, dismiss.intent);
        new StopReceiver().onReceive(current, stop.intent);
        check(current.stopServiceCalls == 1 && current.notifications.cancelCalls == 1
                && NotificationDiagnostics.stoppedReasons.size() == 1, "repeat dismiss/stop cannot end the same session twice");

        TrackingService explicit = start("explicit-stop");
        long explicitEpoch = TrackingService.generation;
        new StopReceiver().onReceive(explicit, explicit.foregroundNotification.actions[0].actionIntent.intent);
        check(TrackingService.generation == explicitEpoch + 1 && TrackingService.trackedId.isEmpty(),
                "explicit stop invalidates matching generation");
        check(explicit.stopServiceCalls == 1 && explicit.notifications.cancelCalls == 1, "explicit stop ends tracking once");
        check(NotificationDiagnostics.stoppedReasons.size() == 2
                && "user_stop".equals(NotificationDiagnostics.stoppedReasons.get(1)),
                "explicit stop preserves user_stop reason");
    }

    private static Object field(TrackingService service, String name) throws Exception {
        java.lang.reflect.Field field = TrackingService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(service);
    }
    private static void setField(TrackingService service, String name, Object value) throws Exception {
        java.lang.reflect.Field field = TrackingService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
    private static android.os.Handler handler(TrackingService service) throws Exception {
        return (android.os.Handler) field(service, "handler");
    }
    private static NotificationRecoveryPolicy recovery(TrackingService service) throws Exception {
        return (NotificationRecoveryPolicy) field(service, "recovery");
    }
    private static TrackingService runningSession(String task) throws Exception {
        return runningSession(task, 30);
    }
    private static TrackingService runningSession(String task, int minutes) throws Exception {
        TrackingService service = start(task, minutes);
        service.notifications.listenerAccessGranted = true;
        TrackingRemovalListener.connected = true;
        setField(service, "confirmingState", false);
        TrackingService.trackingMessage = "执行中";
        return service;
    }
    private static String session(Notification notification) { return notification.extras.getString(TrackingService.EXTRA_SESSION); }
    private static String presentation(Notification notification) { return notification.extras.getString(TrackingService.EXTRA_PRESENTATION); }
    private static void remove(TrackingService service, Notification notification, int reason) throws Exception {
        invoke(service, "onRemoved", new Class<?>[]{long.class, String.class, String.class, int.class},
                notification.extras.getLong(TrackingService.EXTRA_GENERATION, -1),
                session(notification), presentation(notification), reason);
    }
    private static void hide(TrackingService service) {
        service.notifications.activeNotifications = new android.service.notification.StatusBarNotification[0];
    }
    private static void recoveryIntegration() throws Exception {
        TrackingService service = runningSession("clear-all-recovery");
        Notification initial = service.foregroundNotification;
        PendingIntent originalStop = initial.actions[0].actionIntent;
        PendingIntent originalDelete = initial.deleteIntent;
        long epoch = TrackingService.generation, originalEndsAt = TrackingService.endsAt;
        String originalSession = session(initial), originalPresentation = presentation(initial);
        hide(service);
        new DismissReceiver().onReceive(service, originalDelete.intent);
        check(recovery(service).isPending() && service.notifications.notifyCalls == 0,
                "delete before listener pauses instead of prematurely restoring");
        remove(service, initial, android.service.notification.NotificationListenerService.REASON_CANCEL_ALL);
        Notification restored = service.notifications.lastNotification;
        check(restored != null && service.notifications.notifyCalls == 1, "classified clear all restores exactly once");
        active(restored, epoch, "clear-all restoration");
        check(TrackingService.clearAllRestores == 1, "restoration counter increments once");
        check(TrackingService.generation == epoch && TrackingService.endsAt == originalEndsAt,
                "recovery retains the original session and deadline");
        check(originalSession.equals(session(restored)) && !originalPresentation.equals(presentation(restored)),
                "recovery retains S and rotates P");
        check(service.startForegroundCalls == 1, "recovery does not start another foreground session");
        check(originalStop == restored.actions[0].actionIntent && originalDelete != restored.deleteIntent,
                "restore preserves explicit stop token while replacing delete token");
        new DismissReceiver().onReceive(service, originalDelete.intent);
        remove(service, initial, android.service.notification.NotificationListenerService.REASON_CANCEL);
        remove(service, initial, android.service.notification.NotificationListenerService.REASON_CANCEL_ALL);
        check(!recovery(service).isPending() && service.stopServiceCalls == 0
                && service.notifications.notifyCalls == 1, "late old P callbacks cannot stop or restore current P");
        new StopReceiver().onReceive(service, originalStop.intent);
        check(service.stopServiceCalls == 1 && TrackingService.endsAt == 0,
                "old presentation stop still stops the same stable session");
        remove(service, restored, android.service.notification.NotificationListenerService.REASON_CANCEL_ALL);
        check(service.notifications.notifyCalls == 1 && TrackingService.clearAllRestores == 1,
                "clear all cannot restore after explicit stop");
        check("user_stop".equals(NotificationDiagnostics.stoppedReasons.get(NotificationDiagnostics.stoppedReasons.size() - 1)),
                "stable-session stop retains user_stop reason");
    }
    private static void nonRestoringEvents() throws Exception {
        for (int reason : new int[]{android.service.notification.NotificationListenerService.REASON_CANCEL, 0, 999}) {
            TrackingService service = runningSession("other-removal-" + reason);
            Notification notification = service.foregroundNotification;
            hide(service);
            remove(service, notification, reason);
            check(service.notifications.notifyCalls == 0 && TrackingService.clearAllRestores == 0,
                    "individual or unknown removal reason does not restore: " + reason);
            check(service.stopServiceCalls == 1 && TrackingService.endsAt == 0,
                    "individual or unknown removal ends current session: " + reason);
        }
        for (String cause : new String[]{"permission", "disconnected", "channel", "token", "reconnecting", "deadline"}) {
            TrackingService service = runningSession("ineligible-" + cause);
            Notification notification = service.foregroundNotification;
            if ("permission".equals(cause)) service.notifications.listenerAccessGranted = false;
            if ("disconnected".equals(cause)) TrackingRemovalListener.connected = false;
            if ("channel".equals(cause)) service.notifications.getNotificationChannel("task_tracking_v1")
                    .setImportance(android.app.NotificationManager.IMPORTANCE_NONE);
            if ("token".equals(cause)) SessionStore.currentToken = "different-session-token";
            if ("reconnecting".equals(cause)) TrackingService.trackingMessage = "更新暂停，正在重连";
            if ("deadline".equals(cause)) SystemClock.now = TrackingService.endsAt;
            hide(service);
            remove(service, notification, android.service.notification.NotificationListenerService.REASON_CANCEL_ALL);
            check(service.notifications.notifyCalls == 0 && TrackingService.clearAllRestores == 0,
                    cause + " prevents clear-all recovery");
            check(service.stopServiceCalls == 1 && TrackingService.endsAt == 0, cause + " ends removed session");
        }
    }
    private static void publicationGates() throws Exception {
        TrackingService service = runningSession("publication-gates");
        Notification initial = service.foregroundNotification;
        Notification update = notification(service, "执行中", true, true);
        hide(service);
        invoke(service, "publish", new Class<?>[]{Notification.class}, update);
        check(service.notifications.notifyCalls == 0 && !recovery(service).isPending(),
                "initial active-list latency queues publication without duplicating initial post");
        check(handler(service).delayed.size() == 1, "initial latency schedules one visibility check");
        SystemClock.now += 100;
        invoke(service, "publish", new Class<?>[]{Notification.class}, update);
        check(service.notifications.notifyCalls == 0 && handler(service).delayed.size() == 1,
                "repeated update during initial settling neither duplicates post nor timer");
        service.notifications.expose(TrackingService.NOTIFICATION_ID, initial);
        handler(service).runDelayed();
        check(service.notifications.notifyCalls == 1 && TrackingService.clearAllRestores == 0,
                "deferred update publishes after matching initial presentation becomes visible");
        hide(service);
        invoke(service, "publish", new Class<?>[]{Notification.class}, notification(service, "执行中", true, true));
        check(service.notifications.notifyCalls == 1 && recovery(service).isPending(),
                "ordinary update detects missing active notification and waits instead of recreating it");
        invoke(service, "publish", new Class<?>[]{Notification.class}, notification(service, "执行中", true, true));
        check(service.notifications.notifyCalls == 1, "ordinary updates remain paused during classification");
        SystemClock.now += NotificationRecoveryPolicy.CLASSIFY_WAIT_MS;
        handler(service).runDelayed();
        check(service.stopServiceCalls == 1 && service.notifications.notifyCalls == 1,
                "unclassified missing notification times out without a replacement");
    }
    private static void listenerFiltering() throws Exception {
        TrackingService service = runningSession("listener-filtering");
        TrackingRemovalListener listener = new TrackingRemovalListener();
        Notification initial = service.foregroundNotification;
        int uid = service.getApplicationInfo().uid;
        String pkg = service.getPackageName();
        android.service.notification.StatusBarNotification[] outside = new android.service.notification.StatusBarNotification[]{
                new android.service.notification.StatusBarNotification("other.package", uid, 15, null, initial),
                new android.service.notification.StatusBarNotification(pkg, uid + 1, 15, null, initial),
                new android.service.notification.StatusBarNotification(pkg, uid, 16, null, initial),
                new android.service.notification.StatusBarNotification(pkg, uid, 15, "tagged", initial)
        };
        for (android.service.notification.StatusBarNotification item : outside) {
            listener.onNotificationRemoved(item, null, android.service.notification.NotificationListenerService.REASON_CANCEL_ALL);
            check(item.notificationReads == 0, "foreign package/UID/ID/tag is rejected before reading notification content");
        }
        Notification otherChannel = new Notification.Builder(service, "other_channel").addExtras(initial.extras).build();
        android.service.notification.StatusBarNotification wrongChannel =
                new android.service.notification.StatusBarNotification(pkg, uid, 15, null, otherChannel);
        otherChannel.extras.readKeys.clear();
        listener.onNotificationRemoved(wrongChannel, null, android.service.notification.NotificationListenerService.REASON_CANCEL_ALL);
        check(otherChannel.extras.readKeys.isEmpty(), "another channel is rejected before reading its extras");
        check(handler(service).queued.isEmpty() && service.notifications.notifyCalls == 0,
                "out-of-scope removals never reach the tracking service");

        android.service.notification.StatusBarNotification own =
                new android.service.notification.StatusBarNotification(pkg, uid, 15, null, initial);
        initial.extras.readKeys.clear();
        hide(service);
        listener.onNotificationRemoved(own, null, android.service.notification.NotificationListenerService.REASON_CANCEL_ALL);
        check(initial.extras.readKeys.size() == 3
                && initial.extras.readKeys.contains(TrackingService.EXTRA_GENERATION)
                && initial.extras.readKeys.contains(TrackingService.EXTRA_SESSION)
                && initial.extras.readKeys.contains(TrackingService.EXTRA_PRESENTATION),
                "listener reads only its three routing keys from an own-channel notification");
        check(service.notifications.notifyCalls == 0 && handler(service).queued.size() == 1,
                "listener callback serializes removal onto the service handler");
        handler(service).runPosted();
        check(service.notifications.notifyCalls == 1 && TrackingService.clearAllRestores == 1,
                "valid queued clear-all removal restores once through the actual listener");

        Notification restored = service.notifications.lastNotification;
        hide(service);
        new DismissReceiver().onReceive(service, restored.deleteIntent.intent);
        check(recovery(service).isPending(), "delete waits while listener remains ready");
        listener.onListenerDisconnected();
        check(service.stopServiceCalls == 0, "listener disconnect is serialized rather than mutating service inline");
        handler(service).runPosted();
        check(service.stopServiceCalls == 1 && service.notifications.notifyCalls == 1,
                "listener disconnect ends pending classification without restoring");
    }

    private static void listenerReconnectRaces() throws Exception {
        TrackingService removedFirst = runningSession("queued-removal-reconnect");
        TrackingRemovalListener firstListener = new TrackingRemovalListener();
        firstListener.onListenerConnected();
        Notification first = removedFirst.foregroundNotification;
        android.service.notification.StatusBarNotification item = new android.service.notification.StatusBarNotification(
                removedFirst.getPackageName(), removedFirst.getApplicationInfo().uid, 15, null, first);
        hide(removedFirst);
        firstListener.onNotificationRemoved(item, null, android.service.notification.NotificationListenerService.REASON_CANCEL_ALL);
        firstListener.onListenerDisconnected();
        firstListener.onListenerConnected();
        check(removedFirst.notifications.notifyCalls == 0 && removedFirst.stopServiceCalls == 0,
                "queued removal and reconnect remain serialized before handler pump");
        handler(removedFirst).runPosted();
        check(removedFirst.stopServiceCalls == 1 && removedFirst.notifications.notifyCalls == 0
                && TrackingService.clearAllRestores == 0, "a reconnect cannot authorize a queued removal from an older listener generation");

        TrackingService deletedFirst = runningSession("pending-delete-reconnect");
        TrackingRemovalListener secondListener = new TrackingRemovalListener();
        secondListener.onListenerConnected();
        Notification second = deletedFirst.foregroundNotification;
        hide(deletedFirst);
        new DismissReceiver().onReceive(deletedFirst, second.deleteIntent.intent);
        check(recovery(deletedFirst).isPending(), "delete starts pending classification before disconnect");
        secondListener.onListenerDisconnected();
        secondListener.onListenerConnected();
        handler(deletedFirst).runPosted();
        check(deletedFirst.stopServiceCalls == 1 && deletedFirst.notifications.notifyCalls == 0
                && TrackingService.clearAllRestores == 0, "quick reconnect cannot rescue classification interrupted by listener disconnect");
    }

    private static void queueSnapshot(TrackingService service, String status, boolean exists, boolean archived, boolean online) throws Exception {
        org.json.JSONObject result = new org.json.JSONObject().put("tasks", new org.json.JSONArray()).put("devices", new org.json.JSONArray()).put("online", online);
        if (exists) result.put("selectedTask", new org.json.JSONObject().put("status", status).put("archived", archived).put("title", "Offline task"));
        NativeApi.nextResult = result;
        NativeApi.nextFailure = null;
        invoke(service, "fetch", new Class<?>[0]);
        // The queue barrier waits for the real service worker, then only the test pumps its main callback.
        ((java.util.concurrent.ExecutorService) field(service, "worker")).submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
        check(handler(service).queued.size() == 1, "one actual snapshot callback is queued");
    }
    private static void snapshot(TrackingService service, String status) throws Exception {
        queueSnapshot(service, status, true, false, true);
        handler(service).runPosted();
    }
    private static void snapshotEvidencePrecedesMainCallback() throws Exception {
        TrackingService service = start("private-task-must-not-be-written");
        java.io.File directory = java.nio.file.Files.createTempDirectory("monitor-service-diagnostics-test-").toFile();
        service.filesDirectory = directory;
        service.getApplicationInfo().flags = android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE;
        try {
            queueSnapshot(service, "running", true, false, true);
            java.io.File file = new java.io.File(directory, TrackingRequestDiagnostics.FILE_NAME);
            check(TrackingRequestDiagnostics.read(file).size() == 1, "worker persists completion while main callback is still unprocessed");
            String recorded = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            check(recorded.contains("\"status\":200,\"success\":true"), "service records successful snapshot shape and transport status");
            check(!recorded.contains("private-task") && !recorded.contains("Offline task") && !recorded.contains(SessionStore.currentToken), "service never forwards task/title/token to persistent diagnostics");
            handler(service).runPosted();
            NativeApi.Failure limited = new NativeApi.Failure(); limited.status = 429; limited.retryAfterMs = 60000;
            NativeApi.nextFailure = limited;
            invoke(service, "fetch", new Class<?>[0]);
            ((java.util.concurrent.ExecutorService) field(service, "worker")).submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
            check(TrackingRequestDiagnostics.read(file).size() == 2, "failure completion also persists before main callback");
            check(new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("\"status\":429,\"success\":false"), "service preserves actual HTTP error rather than guessing a network cause");
            handler(service).runPosted();
            check((Long) field(service, "nextFetch") == SystemClock.now + 60000, "diagnostic capture does not change Retry-After scheduling");
        } finally {
            service.onDestroy(); service.filesDirectory = null;
            for (java.io.File file : directory.listFiles()) file.delete(); directory.delete();
        }
    }
    private static void freshAt(TrackingService service, long elapsed) throws Exception {
        SystemClock.now = (Long) field(service, "started") + elapsed;
        setField(service, "lastSuccess", SystemClock.now);
        setField(service, "lastConfirmedRunning", SystemClock.now);
        setField(service, "lastConfirmedTask", SystemClock.now);
    }
    private static void selectedDurationIntegration() throws Exception {
        for (int minutes : new int[]{1, 31, 121, 360, 1439}) {
            TrackingService service = runningSession("wheel-duration-" + minutes, minutes);
            long start = (Long) field(service, "started"), epoch = TrackingService.generation;
            check(!TrackingService.untilTaskEnd && TrackingService.endsAt == start + minutes * 60000L,
                    "wheel duration is preserved by actual service start: " + minutes);
            freshAt(service, minutes * 60000L - 1);
            check((Boolean) invoke(service, "accepts", new Class<?>[]{long.class}, epoch), "selected duration accepts pre-deadline callback");
            snapshot(service, "running");
            check(epoch == TrackingService.generation && service.notifications.lastNotification.requestPromotedOngoing,
                    "real callback stays running before selected deadline");
            SystemClock.now = TrackingService.endsAt;
            ((Runnable) field(service, "ticker")).run();
            check(TrackingService.trackedId.isEmpty() && TrackingService.endsAt == 0 && !TrackingService.untilTaskEnd,
                    "actual ticker clears countdown at exact selected boundary");
            check(service.notifications.lastNotification.timeoutAfter == 60000
                    && lastEndedReason().contains(minutes + " 分钟"),
                    "duration end records selected value and normal terminal notice timeout");
            brandOnly(service.notifications.lastNotification, "Codex", "duration terminal notice");
        }
        TrackingService cancelledMode = start("until-then-timed", TrackingPolicy.UNTIL_TASK_END_MINUTES);
        long priorEpoch = TrackingService.generation;
        TrackingService timed = start("replacement-timed", 47);
        check(!TrackingService.untilTaskEnd && TrackingService.endsAt == SystemClock.now + 47 * 60000L,
                "new timed request clears the prior until-task-end mode");
        check(!(Boolean) invoke(cancelledMode, "accepts", new Class<?>[]{long.class}, priorEpoch),
                "prior until-task-end callback cannot attach to replacement timer");
        timed.onDestroy();
    }
    private static void untilTaskEndIntegration() throws Exception {
        for (long elapsed : new long[]{121 * 60000L, 86400000L, 7 * 86400000L}) {
            TrackingService service = runningSession("until-long-run-" + elapsed, -1);
            long epoch = TrackingService.generation;
            check(TrackingService.untilTaskEnd && TrackingService.endsAt == 0 && ((Long) field(service, "durationMs")) == -1L,
                    "actual service exposes until-task-end with no countdown");
            freshAt(service, elapsed);
            snapshot(service, "running");
            check(epoch == TrackingService.generation && TrackingService.untilTaskEnd && service.notifications.lastNotification.requestPromotedOngoing,
                    "fresh long-run callback remains active beyond old time limits");
            Notification current = service.notifications.lastNotification;
            active(current, epoch, "until-task-end live notification");
            hide(service);
            new DismissReceiver().onReceive(service, current.deleteIntent.intent);
            check(recovery(service).isPending(), "no-countdown session still waits for removal classification");
            remove(service, current, android.service.notification.NotificationListenerService.REASON_CANCEL_ALL);
            check(TrackingService.generation == epoch && TrackingService.untilTaskEnd && TrackingService.endsAt == 0
                    && TrackingService.clearAllRestores == 1 && service.notifications.lastNotification.requestPromotedOngoing,
                    "clear all preserves long-running mode, generation and promoted state");
            Notification restored = service.notifications.lastNotification;
            hide(service);
            remove(service, restored, android.service.notification.NotificationListenerService.REASON_CANCEL);
            check(TrackingService.trackedId.isEmpty() && !TrackingService.untilTaskEnd,
                    "individual dismissal still ends an until-task-end run");
        }
        TrackingService waiting = runningSession("until-waiting", -1);
        long waitingEpoch = TrackingService.generation;
        for (int poll = 0; poll < 8; poll++) {
            SystemClock.now += 15000;
            snapshot(waiting, "waiting");
            check(TrackingService.generation == waitingEpoch && "等待批准".equals(TrackingService.trackingMessage)
                    && !(Boolean) field(waiting, "confirmingState") && (Long) field(waiting, "lastConfirmedRunning") == SystemClock.now,
                    "known waiting keeps its own confirmed-state clock through multiple 45-second intervals");
            check(!waiting.notifications.lastNotification.requestPromotedOngoing
                    && waiting.notifications.lastNotification.shortCriticalText == null,
                    "waiting remains visible as ordinary notification without claiming running");
        }
        Notification waitingNotification = waiting.notifications.lastNotification;
        hide(waiting);
        remove(waiting, waitingNotification, android.service.notification.NotificationListenerService.REASON_CANCEL_ALL);
        check(TrackingService.generation == waitingEpoch && TrackingService.untilTaskEnd && TrackingService.clearAllRestores == 1
                && !waiting.notifications.lastNotification.requestPromotedOngoing && waiting.notifications.lastNotification.shortCriticalText == null,
                "clear all restores waiting without incorrectly promoting it");
        SystemClock.now += 15000;
        snapshot(waiting, "running");
        check(waiting.notifications.lastNotification.requestPromotedOngoing && "执行中".equals(TrackingService.trackingMessage),
                "running after approval promotes the same session again");
        SystemClock.now += 15000;
        snapshot(waiting, "waiting");
        long lastWaiting = SystemClock.now;
        for (int poll = 1; poll <= 12; poll++) {
            SystemClock.now = lastWaiting + poll * 15000L;
            snapshot(waiting, "unknown");
            check(TrackingService.generation == waitingEpoch && (Long) field(waiting, "lastConfirmedRunning") == lastWaiting,
                    "unknown HTTP does not renew the last explicitly waiting confirmation");
            check((Long) field(waiting, "lastConfirmedTask") == SystemClock.now && (Boolean) field(waiting, "confirmingState"),
                    "online unknown renews only task-existence evidence during until-task-end");
            check(TrackingService.untilTaskEnd && TrackingService.endsAt == 0 && "正在确认任务状态".equals(TrackingService.trackingMessage)
                    && !waiting.notifications.lastNotification.requestPromotedOngoing && waiting.notifications.lastNotification.shortCriticalText == null,
                    "several unknown intervals preserve ordinary observation without a false running claim");
            ((Runnable) field(waiting, "ticker")).run();
            check(TrackingService.generation == waitingEpoch, "real ticker preserves freshly observed unknown beyond 45 seconds");
        }
        SystemClock.now += 15000;
        snapshot(waiting, "running");
        check(TrackingService.generation == waitingEpoch && TrackingService.untilTaskEnd
                && "执行中".equals(TrackingService.trackingMessage) && waiting.notifications.lastNotification.requestPromotedOngoing,
                "confirmed running resumes the same observation after sustained explicit unknown");

        for (String absent : new String[]{"missing", "", "future_state"}) {
            TrackingService invalid = runningSession("until-unknown-then-" + absent, -1);
            SystemClock.now += 15000;
            snapshot(invalid, "unknown");
            long observed = SystemClock.now;
            for (int elapsed : new int[]{15000, 30000, 44999}) {
                SystemClock.now = observed + elapsed;
                queueSnapshot(invalid, absent, !"missing".equals(absent), false, true);
                handler(invalid).runPosted();
                check(!TrackingService.trackedId.isEmpty() && (Long) field(invalid, "lastConfirmedTask") == observed,
                        "missing/malformed task cannot renew prior unknown observation");
            }
            SystemClock.now = observed + 45000;
            ((Runnable) field(invalid, "ticker")).run();
            check(TrackingService.trackedId.isEmpty() && lastEndedReason().contains("状态未能确认"),
                    "missing/malformed task remains bounded by the original 45-second grace");
        }

        TrackingService late = runningSession("until-late-http", -1);
        freshAt(late, 86400000L);
        queueSnapshot(late, "running", true, false, true);
        SystemClock.now += 45000;
        handler(late).runPosted();
        check(TrackingService.trackedId.isEmpty() && !TrackingService.untilTaskEnd
                && lastEndedReason().contains("连接中断"),
                "late HTTP success cannot reset expired network clock in until-task-end mode");

        for (String terminal : new String[]{"completed", "error", "idle", "archived", "offline"}) {
            TrackingService service = runningSession("until-terminal-" + terminal, -1);
            freshAt(service, 86400000L);
            snapshot(service, "unknown");
            queueSnapshot(service, "archived".equals(terminal) || "offline".equals(terminal) ? "waiting" : terminal,
                    true, "archived".equals(terminal), !"offline".equals(terminal));
            handler(service).runPosted();
            check(TrackingService.trackedId.isEmpty() && !TrackingService.untilTaskEnd && service.stopForegroundCalls == 1,
                    "real snapshot ends until-task-end on " + terminal);
        }
        TrackingService timedWaiting = runningSession("timed-waiting", 31);
        snapshot(timedWaiting, "waiting");
        check(TrackingService.trackedId.isEmpty(), "existing timed-mode waiting behavior is retained");
        TrackingService timedUnknown = runningSession("timed-unknown-unchanged", 31);
        long timedRunning = (Long) field(timedUnknown, "lastConfirmedRunning");
        for (int elapsed : new int[]{15000, 30000, 44999}) {
            SystemClock.now = timedRunning + elapsed;
            snapshot(timedUnknown, "unknown");
        }
        SystemClock.now = timedRunning + 45000;
        ((Runnable) field(timedUnknown, "ticker")).run();
        check(TrackingService.trackedId.isEmpty() && lastEndedReason().contains("状态未能确认"),
                "ordinary timed tracking still expires after 45 seconds of unknown state");

        TrackingService timeout = runningSession("until-android-timeout", -1);
        snapshot(timeout, "unknown");
        timeout.onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        check(TrackingService.trackedId.isEmpty() && !TrackingService.untilTaskEnd && timeout.stopForegroundCalls == 1
                && lastEndedReason().contains("系统已结束"),
                "Android foreground-service timeout still stops until-task-end without restarting");
        TrackingService explicit = runningSession("until-explicit-stop", -1);
        snapshot(explicit, "unknown");
        long explicitEpoch = TrackingService.generation;
        int beforeExplicitStop = explicit.notifications.notifyCalls;
        queueSnapshot(explicit, "running", true, false, true);
        TrackingService.requestStop(explicit, explicitEpoch);
        handler(explicit).runPosted();
        check(TrackingService.trackedId.isEmpty() && !TrackingService.untilTaskEnd && explicit.notifications.notifyCalls == beforeExplicitStop,
                "explicit stop cancels queued until-task-end callback and cannot recreate notice");
        TrackingService revoked = runningSession("until-revoked-session", -1);
        snapshot(revoked, "unknown");
        queueSnapshot(revoked, "running", true, false, true);
        SessionStore.currentToken = "replacement-token";
        handler(revoked).runPosted();
        check(TrackingService.trackedId.isEmpty() && !TrackingService.untilTaskEnd,
                "changed login session immediately rejects until-task-end snapshot");
        TrackingService unauthorized = runningSession("until-server-revocation", -1);
        snapshot(unauthorized, "unknown");
        NativeApi.Failure unauthorizedFailure = new NativeApi.Failure(); unauthorizedFailure.status = 401;
        NativeApi.nextFailure = unauthorizedFailure;
        invoke(unauthorized, "fetch", new Class<?>[0]);
        ((java.util.concurrent.ExecutorService) field(unauthorized, "worker")).submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
        handler(unauthorized).runPosted();
        check(SessionStore.currentToken.isEmpty() && TrackingService.trackedId.isEmpty() && !TrackingService.untilTaskEnd,
                "server revocation clears matching token and ends until-task-end without a new login");
        TrackingService outage = runningSession("until-outage", -1);
        freshAt(outage, 86400000L);
        snapshot(outage, "unknown");
        long lastFresh = SystemClock.now;
        setField(outage, "inFlight", true);
        SystemClock.now = lastFresh + 44999;
        ((Runnable) field(outage, "ticker")).run();
        check(!TrackingService.trackedId.isEmpty() && TrackingService.untilTaskEnd, "long-run outage retains bounded reconnect grace");
        SystemClock.now = lastFresh + 45000;
        ((Runnable) field(outage, "ticker")).run();
        check(TrackingService.trackedId.isEmpty() && !TrackingService.untilTaskEnd,
                "actual ticker ends disconnected until-task-end at exactly 45 seconds");
        TrackingService destroying = runningSession("until-service-destroyed", -1);
        snapshot(destroying, "unknown");
        destroying.onDestroy();
        check(TrackingService.trackedId.isEmpty() && !TrackingService.untilTaskEnd && TrackingService.endsAt == 0,
                "service destruction clears until-task-end UI state and never auto-restarts");
    }
    private static String lastEndedReason() {
        List<String> reasons = NotificationDiagnostics.endedReasons;
        return reasons.isEmpty() ? "" : reasons.get(reasons.size() - 1);
    }
    private static void brandOnly(Notification value, String brand, String label) {
        check(value != null, label + ": notification exists");
        check(brand.contentEquals(value.contentTitle), label + ": title is only the tool name");
        check(value.contentText == null || value.contentText.length() == 0, label + ": no task or status body");
        check(value.bigText == null || value.bigText.length() == 0, label + ": no expanded private content");
        check(value.actions == null || value.actions.length == 0, label + ": no extra action text");
        check(value.shortCriticalText == null || ("Claude Code".equals(brand) ? "Claude" : "Codex").contentEquals(value.shortCriticalText),
                label + ": compact live text contains only the tool name");
        check(value.smallIcon == ("Claude Code".equals(brand) ? R.drawable.ic_claude : R.drawable.ic_codex),
                label + ": matching tool icon");
        contentBrandIcon(value, brand, label);
    }
    private static void contentBrandIcon(Notification value, String brand, String label) {
        check(value.largeIcon != null && value.largeIcon.getType() == android.graphics.drawable.Icon.TYPE_RESOURCE,
                label + ": expanded content has an explicit resource icon");
        check("com.agentmonitor.live".equals(value.largeIcon.getResPackage())
                && value.largeIcon.getResId() == ("Claude Code".equals(brand) ? R.drawable.claude : R.drawable.codex),
                label + ": expanded content uses the matching bundled color logo");
        check(value.largeIcon.getResId() != value.smallIcon, label + ": content logo is distinct from monochrome status icon");
    }
    private static void refreshPrivacy(TrackingService service) throws Exception {
        invoke(service, "refreshLockscreenContent", new Class<?>[0]);
    }
    private static void screenEvent(TrackingService service, String action) throws Exception {
        service.dispatchBroadcast(new Intent(action));
        handler(service).runPosted();
    }
    private static void lockscreenContent() throws Exception {
        for (String tool : new String[]{"codex", "claude"}) {
            String brand = "claude".equals(tool) ? "Claude Code" : "Codex";
            TrackingService service = runningSession("lock-content-" + tool, 47);
            setField(service, "tool", tool);
            setField(service, "title", "Private task detail " + tool);
            Notification unlocked = notification(service, "执行中", true, true);
            invoke(service, "publish", new Class<?>[]{Notification.class}, unlocked);
            active(unlocked, TrackingService.generation, "unlocked " + tool);
            check(("Private task detail " + tool).contentEquals(unlocked.contentTitle)
                    && (brand + " · 执行中").contentEquals(unlocked.contentText), "unlocked task detail is retained");
            brandOnly(unlocked.publicVersion, brand, "unlocked public replacement");
            contentBrandIcon(unlocked, brand, "unlocked expanded content");
            check(unlocked.largeIcon == unlocked.publicVersion.largeIcon,
                    "private and public templates share the same full-color logo");
            long epoch = TrackingService.generation, deadline = TrackingService.endsAt;
            int starts = service.startForegroundCalls;
            String sessionNonce = session(unlocked), presentationNonce = presentation(unlocked);
            check(service.registrations.size() == 1
                    && service.registrations.get(0).flags == android.content.Context.RECEIVER_NOT_EXPORTED,
                    "current Android registers only a non-exported screen receiver");
            check(service.registrations.get(0).filter.hasAction(Intent.ACTION_SCREEN_OFF)
                    && service.registrations.get(0).filter.hasAction(Intent.ACTION_SCREEN_ON)
                    && service.registrations.get(0).filter.hasAction(Intent.ACTION_USER_PRESENT),
                    "receiver observes screen-off, screen-on and unlock");

            // SCREEN_OFF must redact even before the queried power/keyguard states catch up.
            screenEvent(service, Intent.ACTION_SCREEN_OFF);
            Notification locked = service.notifications.lastNotification;
            brandOnly(locked, brand, "screen-off content");
            brandOnly(locked.publicVersion, brand, "screen-off public replacement");
            check(locked.deleteIntent == unlocked.deleteIntent && sessionNonce.equals(session(locked))
                    && presentationNonce.equals(presentation(locked)), "privacy update retains dismissal identity and nonces");
            check(locked.requestPromotedOngoing && (locked.flags & Notification.FLAG_NO_CLEAR) != 0
                    && (locked.flags & Notification.FLAG_ONGOING_EVENT) != 0,
                    "lockscreen redaction retains ongoing promotion and clear-all protection");
            check(epoch == TrackingService.generation && deadline == TrackingService.endsAt
                    && service.startForegroundCalls == starts && TrackingService.clearAllRestores == 0,
                    "screen transition does not restart or extend the tracking session");
            int lockedPosts = service.notifications.notifyCalls;
            refreshPrivacy(service);
            check(service.notifications.notifyCalls == lockedPosts, "unchanged privacy does not repost every timer tick");

            service.power.interactive = true; service.keyguard.locked = true;
            screenEvent(service, Intent.ACTION_SCREEN_ON);
            brandOnly(service.notifications.lastNotification, brand, "awake but keyguard showing");
            service.keyguard.locked = false;
            screenEvent(service, Intent.ACTION_USER_PRESENT);
            Notification restored = service.notifications.lastNotification;
            active(restored, epoch, "unlocked detail restored");
            check(("Private task detail " + tool).contentEquals(restored.contentTitle)
                    && (brand + " · 执行中").contentEquals(restored.contentText), "unlock restores current title and state");
            check(restored.actions[0].actionIntent == unlocked.actions[0].actionIntent && restored.deleteIntent == unlocked.deleteIntent,
                    "unlock retains existing stop and dismissal pending intents");
            brandOnly(restored.publicVersion, brand, "restored private notification public fallback");
            contentBrandIcon(restored, brand, "expanded icon after unlocking");
            int unlockedPosts = service.notifications.notifyCalls;
            refreshPrivacy(service);
            check(service.notifications.notifyCalls == unlockedPosts, "unchanged unlocked privacy does not repost");

            service.keyguard.locked = true;
            setField(service, "inFlight", true); // Pump the real ticker without making a network request.
            ((Runnable) field(service, "ticker")).run();
            brandOnly(service.notifications.lastNotification, brand, "timer catches missing screen broadcast");
            service.keyguard.locked = false; refreshPrivacy(service);
            service.power.interactive = false; refreshPrivacy(service);
            brandOnly(service.notifications.lastNotification, brand, "power off without keyguard");

            int postsBeforeStop = service.notifications.notifyCalls;
            TrackingService.requestStop(service, epoch);
            service.power.interactive = true;
            screenEvent(service, Intent.ACTION_USER_PRESENT);
            screenEvent(service, Intent.ACTION_SCREEN_OFF);
            refreshPrivacy(service);
            check(service.notifications.notifyCalls == postsBeforeStop && service.startForegroundCalls == starts
                    && TrackingService.trackedId.isEmpty(), "screen events after stop cannot recreate a tracking notification");
            service.onDestroy();
            check(service.registrations.isEmpty() && service.receiverUnregistrations == 1,
                    "service destruction unregisters its screen receiver exactly once");
        }
    }
    private static void lockscreenFailureDefaults() throws Exception {
        TrackingService service = runningSession("privacy-failure-defaults");
        for (int failure = 0; failure < 4; failure++) {
            service.missingKeyguard = failure == 0;
            service.missingPower = failure == 1;
            service.keyguard.fail = failure == 2;
            service.power.fail = failure == 3;
            check((Boolean) invoke(service, "shouldRedactLockscreen", new Class<?>[0]),
                    "missing or failing platform lock query defaults to brand-only: " + failure);
            brandOnly(notification(service, "执行中", true, true), "Codex", "failed privacy query");
        }
        service.missingKeyguard = service.missingPower = service.keyguard.fail = service.power.fail = false;
        check(!(Boolean) invoke(service, "shouldRedactLockscreen", new Class<?>[0]), "available unlocked state permits task details");
        for (boolean locked : new boolean[]{false, true}) {
            service.keyguard.locked = locked;
            for (String reason : new String[]{"本轮结束", "连接中断", "状态未能确认", "已结束 47 分钟跟踪", "系统已结束本次跟踪"}) {
                Notification terminal = notification(service, reason, false, false);
                brandOnly(terminal, "Codex", "terminal survives service shutdown safely");
                brandOnly(terminal.publicVersion, "Codex", "terminal public fallback");
                check(!terminal.requestPromotedOngoing && terminal.deleteIntent == null && terminal.timeoutAfter == 60000,
                        "brand-only terminal remains bounded and cannot control an ended session");
            }
        }
        Build.VERSION.SDK_INT = 32;
        try {
            TrackingService legacy = start("legacy-screen-receiver");
            check(legacy.registrations.size() == 1 && legacy.registrations.get(0).flags == 0,
                    "older Android uses the supported dynamic receiver overload");
            screenEvent(legacy, Intent.ACTION_SCREEN_OFF);
            brandOnly(legacy.notifications.lastNotification, "Codex", "legacy screen-off notification");
        } finally { Build.VERSION.SDK_INT = 36; }
    }
    private static void deferredPrivacyAndClearAll() throws Exception {
        TrackingService service = runningSession("deferred-lockscreen");
        Notification initial = service.foregroundNotification;
        Notification staleUnlocked = notification(service, "执行中", true, true);
        long epoch = TrackingService.generation, deadline = TrackingService.endsAt;
        hide(service);
        invoke(service, "publish", new Class<?>[]{Notification.class}, staleUnlocked);
        check(service.notifications.notifyCalls == 0, "unlocked notification starts deferred during initial settle");
        service.power.interactive = false;
        screenEvent(service, Intent.ACTION_SCREEN_OFF);
        check(service.notifications.notifyCalls == 0, "lockscreen update does not bypass visibility gate");
        service.notifications.expose(TrackingService.NOTIFICATION_ID, initial);
        handler(service).runDelayed();
        Notification locked = service.notifications.lastNotification;
        brandOnly(locked, "Codex", "deferred notification uses current privacy at delivery");
        check(session(initial).equals(session(locked)) && presentation(initial).equals(presentation(locked)),
                "initial settle privacy refresh preserves the existing presentation");

        hide(service);
        new DismissReceiver().onReceive(service, locked.deleteIntent.intent);
        check(recovery(service).isPending(), "locked dismissal retains clear-all classification");
        invoke(service, "publish", new Class<?>[]{Notification.class}, staleUnlocked);
        int beforeRestore = service.notifications.notifyCalls;
        refreshPrivacy(service);
        check(service.notifications.notifyCalls == beforeRestore, "screen privacy does not bypass pending dismissal classification");
        remove(service, locked, android.service.notification.NotificationListenerService.REASON_CANCEL_ALL);
        Notification restored = service.notifications.lastNotification;
        brandOnly(restored, "Codex", "clear all restores brand-only on lockscreen");
        check(TrackingService.clearAllRestores == 1 && epoch == TrackingService.generation && deadline == TrackingService.endsAt
                && session(locked).equals(session(restored)) && !presentation(locked).equals(presentation(restored)),
                "clear all rotates presentation only and keeps original tracking duration");
        service.power.interactive = true; service.keyguard.locked = false;
        screenEvent(service, Intent.ACTION_USER_PRESENT);
        Notification afterUnlock = service.notifications.lastNotification;
        active(afterUnlock, epoch, "clear-all session after unlock");
        hide(service);
        remove(service, afterUnlock, android.service.notification.NotificationListenerService.REASON_CANCEL);
        int stoppedPosts = service.notifications.notifyCalls;
        screenEvent(service, Intent.ACTION_SCREEN_OFF);
        check(TrackingService.trackedId.isEmpty() && service.notifications.notifyCalls == stoppedPosts,
                "single dismissal remains stopped across later screen changes");
    }
    private static void richPresentationChanges() throws Exception {
        Build.MANUFACTURER = "OPPO";
        try {
            TrackingService service = runningSession("rich-private-presentation");
            long epoch = TrackingService.generation, deadline = TrackingService.endsAt;
            String sessionNonce = recovery(service).sessionNonce();
            LivePresentation.Data data = new LivePresentation.Data();
            data.computer = "Private PC"; data.session = 123L; data.today = 456L;
            setField(service, "presentationData", data);
            Notification rich = notification(service, "执行中", true, true);
            check(rich.bigText != null && rich.bigText.toString().contains("Private PC") && rich.bigText.toString().contains("123"),
                    "unlocked rich notification contains selected available data");
            brandOnly(rich.publicVersion, "Codex", "rich notification public fallback");
            LivePresentationPreferences.set(service, LivePresentationPreferences.COMPUTER, false);
            handler(service).runPosted();
            check(!service.notifications.lastNotification.bigText.toString().contains("Private PC"), "preference redraw immediately hides computer");
            check(epoch == TrackingService.generation && deadline == TrackingService.endsAt && service.startForegroundCalls == 1
                    && sessionNonce.equals(recovery(service).sessionNonce()), "preference redraw never extends or restarts session");
            service.power.interactive = false;
            screenEvent(service, Intent.ACTION_SCREEN_OFF);
            LivePresentationPreferences.set(service, LivePresentationPreferences.COMPUTER, true);
            handler(service).runPosted();
            brandOnly(service.notifications.lastNotification, "Codex", "setting changed while locked");
            service.power.interactive = true; service.keyguard.locked = false;
            screenEvent(service, Intent.ACTION_USER_PRESENT);
            check(service.notifications.lastNotification.bigText.toString().contains("Private PC"), "unlocked content restores new preference");
            TrackingService.requestStop(service, epoch);
            int posts = service.notifications.notifyCalls;
            LivePresentationPreferences.set(service, LivePresentationPreferences.COMPUTER, false);
            handler(service).runPosted();
            check(service.notifications.notifyCalls == posts && TrackingService.trackedId.isEmpty(), "settings never revive a stopped session");
        } finally { Build.MANUFACTURER = "test"; }
    }
    private static android.os.Handler completeXiaomiQuery(android.content.ContentResolver.QueryGate gate) throws Exception {
        return completeXiaomiQuery(gate, 1);
    }
    private static android.os.Handler completeXiaomiQuery(android.content.ContentResolver.QueryGate gate, int expectedCallbacks) throws Exception {
        int previousHandlers = android.os.Handler.instances.size();
        gate.release.countDown();
        long until = java.lang.System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (java.lang.System.nanoTime() < until) {
            for (int i = previousHandlers; i < android.os.Handler.instances.size(); i++) {
                android.os.Handler candidate = android.os.Handler.instances.get(i);
                if (candidate.queued.size() >= expectedCallbacks) return candidate;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("Xiaomi query did not enqueue its main-thread callback");
    }
    private static TrackingService startXiaomi(String id, android.content.ContentResolver.QueryGate[] gate) throws Exception {
        TrackingService service = start(id, 30, false, current -> {
            current.contentResolver.focusProtocol = 3;
            android.os.Bundle permitted = new android.os.Bundle();
            permitted.putBoolean("canShowFocus", true);
            current.contentResolver.focusResult = permitted;
            gate[0] = current.contentResolver.blockFocusQuery();
        });
        check(gate[0].entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "Xiaomi query runs off the service startup thread");
        setField(service, "confirmingState", false);
        TrackingService.trackingMessage = "执行中";
        return service;
    }
    private static void xiaomiServiceIntegration() throws Exception {
        String originalManufacturer = Build.MANUFACTURER, originalBrand = Build.BRAND;
        Build.MANUFACTURER = "Xiaomi"; Build.BRAND = "Xiaomi";
        android.os.SystemProperties.islandFeature = "true";
        try {
            android.content.ContentResolver.QueryGate[] gate = new android.content.ContentResolver.QueryGate[1];
            TrackingService service = startXiaomi("xiaomi-live", gate);
            check(service.foregroundNotification.extras.getString(XiaomiLiveNotification.PARAM) == null,
                    "startup confirmation never advertises a running OEM activity");
            long epoch = TrackingService.generation, deadline = TrackingService.endsAt;
            setField(service, "title", "Private title never sent to island");
            completeXiaomiQuery(gate[0]).runPosted();
            Notification running = service.notifications.lastNotification;
            String payload = running.extras.getString(XiaomiLiveNotification.PARAM);
            check(payload != null && payload.contains("Codex") && !payload.contains("Private"),
                    "real service applies fixed provider-only Xiaomi payload after confirmed running");
            check(payload.equals(running.publicVersion.extras.getString(XiaomiLiveNotification.PARAM)),
                    "public and private Xiaomi surfaces receive identical branding payloads");
            check(((android.graphics.drawable.Icon) running.extras.getBundle(XiaomiLiveNotification.PICS)
                    .get(XiaomiLiveNotification.TOOL_ICON)).getResId() == R.drawable.codex,
                    "real service attaches only bundled provider art");
            check(service.contentResolver.focusCalls == 1 && service.contentResolver.protocolCalls == 1,
                    "one capability lookup per started tracking session");
            check(epoch == TrackingService.generation && deadline == TrackingService.endsAt && service.startForegroundCalls == 1,
                    "capability redraw retains session identity and deadline");
            service.keyguard.locked = true;
            Notification locked = notification(service, "执行中", true, true);
            brandOnly(locked, "Codex", "Xiaomi locked content");
            check(payload.equals(locked.extras.getString(XiaomiLiveNotification.PARAM)), "locking never adds private OEM content");
            for (String message : new String[]{"等待批准", "更新暂停，正在重连", "正在更新任务状态"}) {
                Notification ordinary = notification(service, message, true, false);
                check(ordinary.extras.getString(XiaomiLiveNotification.PARAM) == null
                        && ordinary.publicVersion.extras.getString(XiaomiLiveNotification.PARAM) == null,
                        "non-running state carries no continuing Xiaomi activity: " + message);
            }
            check(notification(service, "本轮结束", false, false).extras.getString(XiaomiLiveNotification.PARAM) == null,
                    "terminal notice contains no Xiaomi ongoing content");
            check(service.contentResolver.focusCalls == 1, "notification construction reads cache without binder queries");

            for (String reason : new String[]{"stop", "destroy", "expiry", "grace", "logout", "permission"}) {
                TrackingService stale = startXiaomi("xiaomi-late-" + reason, gate);
                int posts = stale.notifications.notifyCalls;
                if ("stop".equals(reason)) TrackingService.requestStop(stale, TrackingService.generation);
                else if ("destroy".equals(reason)) stale.onDestroy();
                else if ("expiry".equals(reason)) SystemClock.now = TrackingService.endsAt;
                else if ("grace".equals(reason)) SystemClock.now += TrackingPolicy.LOST_MS;
                else if ("logout".equals(reason)) SessionStore.currentToken = "different-session";
                else stale.notifications.enabled = false;
                completeXiaomiQuery(gate[0]).runPosted();
                check(stale.notifications.notifyCalls == posts && stale.startForegroundCalls == 1,
                        "late Xiaomi capability callback cannot republish after " + reason);
            }

            TrackingService replaced = startXiaomi("xiaomi-old-task", gate);
            replaced.onStartCommand(new Intent(replaced, TrackingService.class).setAction("track")
                    .putExtra("task_id", "xiaomi-new-task").putExtra("title", "New private task")
                    .putExtra("tool", "claude").putExtra("duration_minutes", 30), 0, 2);
            handler(replaced).queued.clear();
            setField(replaced, "confirmingState", false);
            TrackingService.trackingMessage = "执行中";
            completeXiaomiQuery(gate[0], 2).runPosted();
            check(replaced.notifications.notifyCalls == 1 && "xiaomi-new-task".equals(TrackingService.trackedId),
                    "coalesced old epoch callback is dropped while the current epoch redraws once");
            check(replaced.notifications.lastNotification.extras.getString(XiaomiLiveNotification.PARAM).contains("Claude"),
                    "replacement notification uses the new task provider");
            check(replaced.contentResolver.focusCalls == 1, "overlapping starts share one in-flight capability query");

            Build.MANUFACTURER = "OPPO"; Build.BRAND = "OPPO";
            TrackingService oppo = runningSession("oppo-after-xiaomi");
            check(notification(oppo, "执行中", true, true).extras.getString(XiaomiLiveNotification.PARAM) == null
                    && oppo.contentResolver.focusCalls == 0,
                    "a cached Xiaomi observation never alters OPPO notifications or queries an OPPO provider");
        } finally {
            Build.MANUFACTURER = originalManufacturer; Build.BRAND = originalBrand;
            android.os.SystemProperties.reset();
        }
    }
    public static void main(String[] args) throws Exception {
        Build.VERSION.SDK_INT = 36;
        Build.VERSION.SDK_INT_FULL = 3600001;
        PendingIntent.reset();
        try {
            notificationStates();
            foregroundVisibilityCompatibility();
            receiverIsolation();
            recoveryIntegration();
            nonRestoringEvents();
            publicationGates();
            listenerFiltering();
            listenerReconnectRaces();
            selectedDurationIntegration();
            untilTaskEndIntegration();
            lockscreenContent();
            lockscreenFailureDefaults();
            deferredPrivacyAndClearAll();
            richPresentationChanges();
            snapshotEvidencePrecedesMainCallback();
            xiaomiServiceIntegration();
            System.out.println("TrackingNotificationTest: " + checks + " checks passed");
        } finally {
            for (TrackingService service : services) service.onDestroy();
        }
    }
}
