package com.agentmonitor.live;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One explicitly started read-only session, bounded by task state and system lifetime. Never auto-restarts. */
public final class TrackingService extends Service {
    public static final int NOTIFICATION_ID = 15;
    public static final String EXTRA_GENERATION = "com.agentmonitor.live.GENERATION";
    public static final String EXTRA_SESSION = "com.agentmonitor.live.SESSION_NONCE";
    public static final String EXTRA_PRESENTATION = "com.agentmonitor.live.PRESENTATION_NONCE";
    static final String CHANNEL = "task_tracking_v1";
    private static final long POST_SETTLE_MS = 1000;
    private static volatile TrackingService instance;
    public static volatile String trackedId = "";
    public static volatile String trackingMessage = "";
    public static volatile long generation = 0;
    public static volatile long endsAt = 0;
    public static volatile boolean untilTaskEnd = false;
    public static volatile int clearAllRestores = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final DeviceBrand deviceBrand = DeviceBrand.detect(Build.MANUFACTURER, Build.BRAND);
    private NotificationManager manager;
    private String title = "任务", tool = "codex", token = "", notificationTaskId = "";
    private LivePresentation.Data presentationData = new LivePresentation.Data();
    private long started, lastSuccess, lastConfirmedRunning, lastConfirmedTask, nextFetch, epoch;
    // In until-task-end mode this confirmation clock also records an explicit waiting state.
    // lastConfirmedTask separately records an online task whose status may explicitly be unknown.
    private long durationMs = TrackingPolicy.durationMs(TrackingPolicy.DEFAULT_DURATION_MINUTES);
    private boolean active, confirmingState, inFlight, promotionRequested;
    private NotificationRecoveryPolicy recovery;
    private Notification deferredNotification;
    private long postSettlesAt;
    private boolean notificationSeen;
    private boolean screenOff, lockscreenRedacted = true, screenReceiverRegistered;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) screenOff = true;
            else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) screenOff = false;
            else return;
            refreshLockscreenContent();
        }
    };
    private final Runnable notificationCheck = new Runnable() {
        @Override public void run() {
            if (!active || epoch != generation || recovery == null) return;
            long now = SystemClock.elapsedRealtime();
            String reason = TrackingPolicy.deadline(now, started, lastSuccess, stateConfirmationTime(), confirmingState, durationMs);
            if (!reason.isEmpty()) { finish(reason, true); return; }
            if (recovery.tick(now) == NotificationRecoveryPolicy.Decision.END) { endDismissal(); return; }
            if (recovery.isPending()) { handler.postDelayed(this, 100); return; }
            if (deferredNotification != null) publish(deferredNotification);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!active || epoch != generation) return;
            if (!manager.areNotificationsEnabled()) { finish("", false); return; }
            long now = SystemClock.elapsedRealtime();
            String reason = TrackingPolicy.deadline(now, started, lastSuccess, stateConfirmationTime(), confirmingState, durationMs);
            if (!reason.isEmpty()) { finish(reason, true); return; }
            if (recovery != null && recovery.tick(now) == NotificationRecoveryPolicy.Decision.END) { endDismissal(); return; }
            refreshLockscreenContent();
            if (!active || epoch != generation) return;
            if (!inFlight && now >= nextFetch) fetch();
            handler.postDelayed(this, 1000);
        }
    };

    public static void requestStop(Context context, long expectedGeneration) {
        stopTracking(context, expectedGeneration, "user_stop");
    }
    /** Settings changes reuse the active publication/privacy/recovery gates; never start a service. */
    static void refreshPresentation() {
        TrackingService current = instance;
        if (current == null) return;
        long expectedEpoch = current.epoch;
        current.handler.post(() -> {
            if (current.epoch != expectedEpoch || !current.accepts(expectedEpoch)) return;
            current.publish(current.notification(trackingMessage, true,
                    !current.confirmingState && "执行中".equals(trackingMessage)));
        });
    }
    public static void requestNotificationStop(Context context, long expectedGeneration, String sessionNonce) {
        TrackingService current = instance;
        if (current != null && current.recovery != null && current.recovery.matchesSession(expectedGeneration, sessionNonce))
            requestStop(context, expectedGeneration);
    }
    public static void requestDismiss(Context context, long expectedGeneration, String sessionNonce, String presentationNonce) {
        TrackingService current = instance;
        if (current != null) current.onDismiss(expectedGeneration, sessionNonce, presentationNonce);
    }
    static void removed(long expectedGeneration, String sessionNonce, String presentationNonce, int reason, long listenerGeneration) {
        TrackingService current = instance;
        if (current != null) current.handler.post(() -> {
            if (listenerGeneration != TrackingRemovalListener.connectionGeneration) {
                if (current.active && current.epoch == generation && current.recovery != null
                        && current.recovery.matches(expectedGeneration, sessionNonce, presentationNonce)) current.endDismissal();
                return;
            }
            current.onRemoved(expectedGeneration, sessionNonce, presentationNonce, reason);
        });
    }
    static void listenerDisconnected() {
        TrackingService current = instance;
        if (current == null || current.recovery == null) return;
        long currentEpoch = current.epoch;
        String sessionNonce = current.recovery.sessionNonce(), presentationNonce = current.recovery.presentationNonce();
        current.handler.post(() -> {
            if (current.active && current.epoch == generation && current.recovery != null && current.recovery.isPending()
                    && current.recovery.matches(currentEpoch, sessionNonce, presentationNonce)) current.endDismissal();
        });
    }
    private static void stopTracking(Context context, long expectedGeneration, String reason) {
        if (expectedGeneration != generation) return;
        TrackingService current = instance;
        if (current != null && current.epoch == expectedGeneration) {
            current.active = false; current.invalidateNotification();
            current.handler.removeCallbacksAndMessages(null);
        }
        ++generation; trackedId = ""; trackingMessage = ""; endsAt = 0; untilTaskEnd = false;
        context.stopService(new Intent(context, TrackingService.class));
        context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        NotificationDiagnostics.write(context, "stopped", false, null, reason);
    }
    @Override public void onCreate() {
        super.onCreate(); manager = getSystemService(NotificationManager.class); instance = this;
        IntentFilter screenEvents = new IntentFilter();
        screenEvents.addAction(Intent.ACTION_SCREEN_OFF);
        screenEvents.addAction(Intent.ACTION_SCREEN_ON);
        screenEvents.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, screenEvents, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenReceiver, screenEvents);
            screenReceiverRegistered = true;
        } catch (RuntimeException unavailable) { screenReceiverRegistered = false; }
        lockscreenRedacted = shouldRedactLockscreen();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "任务跟踪", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("由你选择跟踪时长，任务结束后自动停止");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !"track".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        handler.removeCallbacksAndMessages(null);
        invalidateNotification();
        epoch = ++generation; active = true; confirmingState = true; inFlight = false;
        clearAllRestores = 0;
        trackedId = clean(intent.getStringExtra("task_id"), 512);
        notificationTaskId = trackedId;
        title = clean(intent.getStringExtra("title"), 160);
        tool = "claude".equals(intent.getStringExtra("tool")) ? "claude" : "codex";
        presentationData = new LivePresentation.Data();
        durationMs = TrackingPolicy.durationMs(intent.getIntExtra("duration_minutes", TrackingPolicy.DEFAULT_DURATION_MINUTES));
        untilTaskEnd = durationMs == TrackingPolicy.UNTIL_TASK_END_MS;
        started = lastSuccess = lastConfirmedRunning = lastConfirmedTask = SystemClock.elapsedRealtime();
        endsAt = untilTaskEnd ? 0 : started + durationMs; nextFetch = started;
        // No selected countdown does not remove the independent task/network/system termination rules.
        recovery = new NotificationRecoveryPolicy(epoch, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                untilTaskEnd ? Long.MAX_VALUE : endsAt);
        notificationSeen = false; postSettlesAt = started + POST_SETTLE_MS;
        token = SessionStore.token(this); trackingMessage = "正在更新任务状态";
        try {
            Notification notification = notification(trackingMessage, true, false);
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION_ID, notification);
            NotificationDiagnostics.write(this, "started", false, notification);
        } catch (RuntimeException ignored) { finish("", false); return START_NOT_STICKY; }
        if (trackedId.isEmpty() || token.isEmpty()) { finish("", false); return START_NOT_STICKY; }
        refreshXiaomiCapabilities(epoch);
        handler.post(ticker); return START_NOT_STICKY;
    }
    private void refreshXiaomiCapabilities(long requestEpoch) {
        if (deviceBrand != DeviceBrand.XIAOMI) return;
        XiaomiLiveCapabilities.refresh(this, value -> {
            // A slow OEM provider must not redraw a stopped, replaced, expired or signed-out session.
            if (instance != this || !accepts(requestEpoch) || !token.equals(SessionStore.token(this))
                    || !manager.areNotificationsEnabled()) return;
            String cutoff = TrackingPolicy.deadline(SystemClock.elapsedRealtime(), started, lastSuccess,
                    stateConfirmationTime(), confirmingState, durationMs);
            if (!cutoff.isEmpty()) return;
            publish(notification(trackingMessage, true, !confirmingState && "执行中".equals(trackingMessage)));
        });
    }
    private static String clean(String value, int limit) {
        if (value == null) return "";
        return value.length() > limit ? value.substring(0, limit) : value;
    }
    private boolean accepts(long requestEpoch) {
        return epoch == requestEpoch && TrackingPolicy.accepts(active, generation, requestEpoch, SystemClock.elapsedRealtime(), started, durationMs);
    }
    private long stateConfirmationTime() {
        return durationMs == TrackingPolicy.UNTIL_TASK_END_MS ? lastConfirmedTask : lastConfirmedRunning;
    }
    private void fetch() {
        inFlight = true;
        final long requestEpoch = epoch;
        final String requestToken = token, taskId = trackedId;
        worker.execute(() -> {
            final long diagnosticStarted = SystemClock.elapsedRealtime();
            JSONObject result = null; NativeApi.Failure failure = null;
            try { result = NativeApi.call("GET", "/api/native/snapshot", null, requestToken); }
            catch (NativeApi.Failure error) { failure = error; }
            recordSnapshotAttempt(NativeApi.takeSnapshotTiming(), diagnosticStarted, result, failure);
            final JSONObject value = result; final NativeApi.Failure error = failure;
            handler.post(() -> {
                if (!accepts(requestEpoch)) return;
                inFlight = false; long now = SystemClock.elapsedRealtime(); nextFetch = now + 15000;
                if (!requestToken.equals(SessionStore.token(this))) { finish("", false); return; }
                if (error != null) {
                    if (error.status == 401) { SessionStore.clearIfToken(this, requestToken); finish("", false); return; }
                    if (error.status == 429) nextFetch = Math.max(nextFetch, now + error.retryAfterMs);
                    networkFailure(now); return;
                }
                if (value == null || value.optJSONArray("tasks") == null || value.optJSONArray("devices") == null) { networkFailure(now); return; }
                // Check both clocks before refreshing HTTP freshness or accepting running again.
                String cutoff = TrackingPolicy.deadline(now, started, lastSuccess, stateConfirmationTime(), confirmingState, durationMs);
                if (!cutoff.isEmpty()) { finish(cutoff, true); return; }
                lastSuccess = now;
                UsageAlerts.observe(this, value.optJSONObject("usage"), requestToken);
                JSONObject task = NativeApi.task(value, taskId);
                presentationData = LivePresentation.read(value, task, tool);
                String reason = TrackingPolicy.terminal(now, started, lastSuccess, stateConfirmationTime(), false, task != null,
                        task != null && task.optBoolean("archived"), task != null && NativeApi.online(value, task),
                        task == null ? "unknown" : task.optString("status", "unknown"), durationMs);
                if (!reason.isEmpty()) { finish(reason, true); return; }
                if (TrackingPolicy.confirmedWaiting(task != null, task != null && task.optBoolean("archived"),
                        task != null && NativeApi.online(value, task), task == null ? null : task.optString("status", "unknown"), durationMs)) {
                    confirmingState = false; lastConfirmedRunning = lastConfirmedTask = now;
                    title = clean(task.optString("title", title), 160);
                    trackingMessage = "等待批准";
                    publish(notification(trackingMessage, true, false));
                    return;
                }
                if (!TrackingPolicy.confirmedRunning(task != null, task != null && task.optBoolean("archived"),
                        task != null && NativeApi.online(value, task), task == null ? null : task.optString("status", "unknown"))) {
                    confirmingState = true;
                    if (TrackingPolicy.observesUnknown(task != null, task != null && task.optBoolean("archived"),
                            task != null && NativeApi.online(value, task), task == null ? null : task.optString("status", ""), durationMs)) {
                        lastConfirmedTask = now;
                    }
                    trackingMessage = "正在确认任务状态";
                    publish(notification(trackingMessage, true, false));
                    return;
                }
                confirmingState = false; lastConfirmedRunning = lastConfirmedTask = now;
                title = clean(task.optString("title", title), 160);
                trackingMessage = "执行中";
                publish(notification(trackingMessage, true, true));
            });
        });
    }
    private void recordSnapshotAttempt(NativeApi.SnapshotTiming timing, long startedAt, JSONObject result, NativeApi.Failure error) {
        try {
            boolean enabled = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            boolean shape = error == null && result != null && result.optJSONArray("tasks") != null && result.optJSONArray("devices") != null;
            int status = timing == null ? error == null ? 0 : error.status : timing.status;
            boolean success = timing != null && timing.success && shape;
            String stage = timing == null ? "unknown" : timing.stage;
            String reason = timing == null ? "unclassified" : timing.failure;
            if (timing != null && timing.success && !shape) { stage = "snapshot_shape"; reason = "snapshot_shape"; }
            TrackingRequestDiagnostics.record(enabled, getFilesDir(), timing == null ? System.currentTimeMillis() : timing.time,
                    timing == null ? Math.max(0, SystemClock.elapsedRealtime() - startedAt) : timing.durationMs,
                    status, success, stage, reason);
        } catch (RuntimeException ignored) { /* Diagnostics cannot affect tracking, callbacks or grace rules. */ }
    }
    private void networkFailure(long now) {
        String cutoff = TrackingPolicy.deadline(now, started, lastSuccess, stateConfirmationTime(), confirmingState, durationMs);
        if (!cutoff.isEmpty()) { finish(cutoff, true); return; }
        trackingMessage = "更新暂停，正在重连";
        publish(notification(trackingMessage, true, false));
    }
    private void publish(Notification notification) {
        if (!accepts(epoch) || recovery == null || recovery.isEnded()) return;
        // A pending publication must not retain private content built before the screen locked.
        notification = notification(trackingMessage, true, !confirmingState && "执行中".equals(trackingMessage));
        deferredNotification = notification;
        if (recovery.isPending()) return;
        long now = SystemClock.elapsedRealtime();
        if (!currentNotificationVisible()) {
            if (!notificationSeen && now < postSettlesAt) { scheduleNotificationCheck(); return; }
            onDismiss(epoch, recovery.sessionNonce(), recovery.presentationNonce());
            return;
        }
        notificationSeen = true; deferredNotification = null;
        try { manager.notify(NOTIFICATION_ID, notification); NotificationDiagnostics.write(this, "updated", promotionRequested, notification); }
        catch (RuntimeException ignored) { finish("", false); }
    }
    private boolean currentNotificationVisible() {
        try {
            for (StatusBarNotification item : manager.getActiveNotifications()) {
                if (!TrackingRemovalListener.isOwnTracking(this, item)) continue;
                Bundle extras = item.getNotification().extras;
                if (extras != null && recovery.matches(extras.getLong(EXTRA_GENERATION, -1),
                        extras.getString(EXTRA_SESSION), extras.getString(EXTRA_PRESENTATION))) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }
    private void scheduleNotificationCheck() {
        handler.removeCallbacks(notificationCheck); handler.postDelayed(notificationCheck, 100);
    }
    private boolean listenerReady() {
        try {
            return Build.VERSION.SDK_INT >= 27 && TrackingRemovalListener.connected
                    && manager.isNotificationListenerAccessGranted(new ComponentName(this, TrackingRemovalListener.class));
        } catch (RuntimeException ignored) { return false; }
    }
    private boolean eligibleToRestore(long now) {
        boolean confirmedTracking = "执行中".equals(trackingMessage)
                || (durationMs == TrackingPolicy.UNTIL_TASK_END_MS && "等待批准".equals(trackingMessage));
        if (!active || epoch != generation || !listenerReady() || confirmingState || !confirmedTracking
                || token.isEmpty() || !token.equals(SessionStore.token(this))
                || (durationMs != TrackingPolicy.UNTIL_TASK_END_MS && now >= endsAt)
                || !TrackingPolicy.deadline(now, started, lastSuccess, stateConfirmationTime(), confirmingState, durationMs).isEmpty()) return false;
        try {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            return manager.areNotificationsEnabled() && channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        } catch (RuntimeException ignored) { return false; }
    }
    private void onDismiss(long expectedGeneration, String sessionNonce, String presentationNonce) {
        if (!active || epoch != generation || recovery == null) return;
        NotificationRecoveryPolicy.Decision decision = recovery.onDismiss(expectedGeneration, sessionNonce, presentationNonce,
                SystemClock.elapsedRealtime(), listenerReady());
        if (decision == NotificationRecoveryPolicy.Decision.END) endDismissal();
        else if (decision == NotificationRecoveryPolicy.Decision.WAIT) scheduleNotificationCheck();
    }
    private void onRemoved(long expectedGeneration, String sessionNonce, String presentationNonce, int reason) {
        if (!active || epoch != generation || recovery == null) return;
        long now = SystemClock.elapsedRealtime();
        NotificationRecoveryPolicy.Decision decision = recovery.onRemoved(expectedGeneration, sessionNonce, presentationNonce,
                reason == NotificationListenerService.REASON_CANCEL_ALL, now, eligibleToRestore(now), UUID.randomUUID().toString());
        if (decision == NotificationRecoveryPolicy.Decision.END) { endDismissal(); return; }
        if (decision != NotificationRecoveryPolicy.Decision.RESTORE) return;
        deferredNotification = null; handler.removeCallbacks(notificationCheck);
        notificationSeen = false; postSettlesAt = now + POST_SETTLE_MS;
        try {
            Notification restored = notification(trackingMessage, true, "执行中".equals(trackingMessage));
            manager.notify(NOTIFICATION_ID, restored);
            ++clearAllRestores;
            NotificationDiagnostics.write(this, "restored", promotionRequested, restored, "clear_all_restored");
        } catch (RuntimeException ignored) { endDismissal(); }
    }
    private void endDismissal() { stopTracking(this, epoch, "notification_dismissed"); }
    private void invalidateNotification() {
        if (recovery != null) recovery.end();
        deferredNotification = null; handler.removeCallbacks(notificationCheck);
    }
    private boolean shouldRedactLockscreen() {
        if (screenOff || !screenReceiverRegistered) return true;
        try {
            PowerManager power = getSystemService(PowerManager.class);
            KeyguardManager keyguard = getSystemService(KeyguardManager.class);
            return power == null || keyguard == null || !power.isInteractive() || keyguard.isKeyguardLocked();
        } catch (RuntimeException unavailable) { return true; }
    }
    private void refreshLockscreenContent() {
        boolean redacted = shouldRedactLockscreen();
        if (redacted == lockscreenRedacted) return;
        lockscreenRedacted = redacted;
        if (!active || epoch != generation || recovery == null) return;
        String cutoff = TrackingPolicy.deadline(SystemClock.elapsedRealtime(), started, lastSuccess,
                stateConfirmationTime(), confirmingState, durationMs);
        if (!cutoff.isEmpty()) { finish(cutoff, true); return; }
        publish(notification(trackingMessage, true, !confirmingState && "执行中".equals(trackingMessage)));
    }
    private Notification notification(String message, boolean ongoing, boolean promote) {
        Intent open = new Intent(this, MainActivity.class);
        String target = NativeNavigationPolicy.notificationUri(notificationTaskId, epoch);
        if (target != null) open.setAction(NativeNavigationPolicy.NOTIFICATION_ACTION).setData(Uri.parse(target));
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int accent = "claude".equals(tool) ? 0xffbf785c : 0xff7766df;
        String displayMessage = message.startsWith("更新暂停") ? "正在重连" : message;
        String toolLabel = "claude".equals(tool) ? "Claude Code" : "Codex";
        String compactTool = "claude".equals(tool) ? "Claude" : "Codex";
        int smallIcon = "claude".equals(tool) ? R.drawable.ic_claude : R.drawable.ic_codex;
        // The expanded content template needs its own full-color brand icon; the monochrome
        // small icon is reserved for the status bar and the compact live-update presentation.
        Icon contentIcon = Icon.createWithResource(this, "claude".equals(tool) ? R.drawable.claude : R.drawable.codex);
        lockscreenRedacted = shouldRedactLockscreen();
        // Completion notices outlive this service, so remain brand-only when the screen later locks.
        boolean brandOnly = lockscreenRedacted || !ongoing;
        LivePresentation.Options displayOptions = LivePresentationPreferences.options(this);
        Notification.Builder publicBuilder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(smallIcon).setLargeIcon(contentIcon).setContentTitle(toolLabel)
                .setColor(accent).setColorized(false).setContentIntent(content)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true)
                .setOngoing(ongoing).setAutoCancel(!ongoing).setShowWhen(false);
        if (Build.VERSION.SDK_INT >= 31 && ongoing) publicBuilder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (!ongoing) publicBuilder.setTimeoutAfter(60000);
        if (Build.VERSION.SDK_INT >= 36 && promote && ongoing) publicBuilder.setShortCriticalText(compactTool);
        Notification publicVersion = publicBuilder.build();
        if (ongoing) publicVersion.flags |= Notification.FLAG_NO_CLEAR;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(smallIcon).setLargeIcon(contentIcon)
                .setContentTitle(brandOnly ? toolLabel : LivePresentation.title(title, toolLabel, displayOptions))
                .setContentText(brandOnly ? null : toolLabel + " · " + displayMessage)
                .setColor(accent).setColorized(false)
                .setContentIntent(content).setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
                .setPublicVersion(publicVersion)
                .setOngoing(ongoing).setAutoCancel(!ongoing).setShowWhen(false);
        if (!brandOnly) builder.setStyle(new Notification.BigTextStyle().bigText(
                LivePresentation.expanded(toolLabel, displayMessage, presentationData, displayOptions,
                        SystemClock.elapsedRealtime() - started, System.currentTimeMillis())));
        // Android 12+ may defer foreground visibility beyond POST_SETTLE_MS; that is not a user dismissal.
        if (Build.VERSION.SDK_INT >= 31 && ongoing) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (Build.VERSION.SDK_INT >= 36 && promote && ongoing) {
            // BigTextStyle is eligible for Live Updates without inventing task progress.
            builder.setShortCriticalText(brandOnly ? compactTool : "执行中");
        }
        if (!ongoing) builder.setTimeoutAfter(60000);
        if (ongoing) {
            Bundle metadata = new Bundle();
            metadata.putLong(EXTRA_GENERATION, epoch);
            metadata.putString(EXTRA_SESSION, recovery.sessionNonce());
            metadata.putString(EXTRA_PRESENTATION, recovery.presentationNonce());
            builder.addExtras(metadata);
            Intent stop = new Intent(this, StopReceiver.class).setData(Uri.parse("agentmonitor://tracking/" + epoch + "/" + recovery.sessionNonce()))
                    .putExtra("generation", epoch).putExtra(EXTRA_SESSION, recovery.sessionNonce());
            PendingIntent action = PendingIntent.getBroadcast(this, 0, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Intent dismiss = new Intent(this, DismissReceiver.class).setData(Uri.parse("agentmonitor://tracking-dismiss/" + epoch + "/" + recovery.presentationNonce()))
                    .putExtra("generation", epoch).putExtra(EXTRA_SESSION, recovery.sessionNonce()).putExtra(EXTRA_PRESENTATION, recovery.presentationNonce());
            PendingIntent deleted = PendingIntent.getBroadcast(this, 0, dismiss, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (!brandOnly) builder.addAction(new Notification.Action.Builder(null, "停止跟踪", action).build());
            builder.setDeleteIntent(deleted);
        }
        promotionRequested = false;
        if (Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= 3600001) {
            promotionRequested = promote && ongoing && manager.canPostPromotedNotifications();
            builder.setRequestPromotedOngoing(promotionRequested);
        }
        Notification result = builder.build();
        // Request exclusion from Clear all; a delivered dismissal still ends this session.
        if (ongoing) result.flags |= Notification.FLAG_NO_CLEAR;
        // The Xiaomi adapter uses only bundled tool branding, including in publicVersion.
        // Waiting/reconnecting/confirmation/completion retain ordinary notification semantics.
        XiaomiLiveNotification.apply(this, result, deviceBrand, XiaomiLiveCapabilities.cached(), tool, promote && ongoing);
        return result;
    }
    private void finish(String message, boolean leaveNotice) {
        if (!active) { stopSelf(); return; }
        leaveNotice = leaveNotice && (recovery == null || !recovery.isPending());
        active = false; invalidateNotification(); handler.removeCallbacksAndMessages(null);
        if (epoch == generation) { ++generation; trackedId = ""; trackingMessage = ""; endsAt = 0; untilTaskEnd = false; }
        stopForeground(STOP_FOREGROUND_REMOVE); manager.cancel(NOTIFICATION_ID);
        if (leaveNotice && manager.areNotificationsEnabled()) {
            try { manager.notify(NOTIFICATION_ID, notification(message, false, false)); } catch (RuntimeException ignored) { }
        }
        NotificationDiagnostics.write(this, "ended", false, null, NotificationDiagnostics.endReason(message));
        stopSelf();
    }
    @Override public void onTimeout(int startId, int fgsType) { finish("系统已结束本次跟踪", true); }
    @Override public void onDestroy() {
        active = false; invalidateNotification(); handler.removeCallbacksAndMessages(null); worker.shutdownNow();
        if (screenReceiverRegistered) {
            screenReceiverRegistered = false;
            try { unregisterReceiver(screenReceiver); } catch (RuntimeException ignored) { }
        }
        if (epoch == generation) { ++generation; trackedId = ""; trackingMessage = ""; endsAt = 0; untilTaskEnd = false; }
        if (instance == this) instance = null;
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}




