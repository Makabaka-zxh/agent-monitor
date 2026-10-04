package com.agentmonitor.live;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Read-only Xiaomi-documented queries. Call query off the UI thread; no result proves display. */
final class XiaomiLiveCapabilities {
    enum State { UNKNOWN, ENABLED, DISABLED }
    private static final Object LOCK = new Object();
    private static final List<Consumer<XiaomiLiveCapabilities>> pending = new ArrayList<>();
    private static volatile XiaomiLiveCapabilities latest = new XiaomiLiveCapabilities(-1, State.UNKNOWN, State.UNKNOWN);
    private static boolean refreshing;

    // -1 means unreadable; 0 means the OS did not advertise a focus protocol.
    final int protocolVersion;
    final State island;
    final State focusPermission;

    private XiaomiLiveCapabilities(int protocolVersion, State island, State focusPermission) {
        this.protocolVersion = protocolVersion;
        this.island = island;
        this.focusPermission = focusPermission;
    }

    interface Probe {
        int protocolVersion() throws Exception;
        Boolean islandFeature() throws Exception;
        Boolean focusPermission() throws Exception;
    }

    /** Last observation in this process only; never performs binder calls. */
    static XiaomiLiveCapabilities cached() { return latest; }

    /** Coalesce concurrent requests and deliver each optional callback on the main thread. */
    static void refresh(Context context, Consumer<XiaomiLiveCapabilities> callback) {
        Context application = context.getApplicationContext();
        Context source = application == null ? context : application;
        synchronized (LOCK) {
            if (callback != null) pending.add(callback);
            if (refreshing) return;
            refreshing = true;
        }
        new Thread(() -> {
            XiaomiLiveCapabilities value = query(source);
            List<Consumer<XiaomiLiveCapabilities>> callbacks;
            synchronized (LOCK) {
                latest = value;
                callbacks = new ArrayList<>(pending);
                pending.clear();
                refreshing = false;
            }
            Handler main = new Handler(Looper.getMainLooper());
            for (Consumer<XiaomiLiveCapabilities> listener : callbacks) main.post(() -> listener.accept(value));
        }, "monitor-xiaomi-capabilities").start();
    }

    static XiaomiLiveCapabilities query(Context context) {
        return read(new Probe() {
            @Override public int protocolVersion() {
                return Settings.System.getInt(context.getContentResolver(), "notification_focus_protocol", 0);
            }
            @Override public Boolean islandFeature() throws Exception {
                Class<?> properties = Class.forName("android.os.SystemProperties");
                // Distinguish a missing property from a reported false value. Restricted reflection
                // is treated as unknown; the app does not bypass Android's hidden-API restrictions.
                Object raw = properties.getMethod("get", String.class, String.class)
                        .invoke(null, "persist.sys.feature.island", "");
                if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) return null;
                Object result = properties.getMethod("getBoolean", String.class, boolean.class)
                        .invoke(null, "persist.sys.feature.island", false);
                return result instanceof Boolean ? (Boolean) result : null;
            }
            @Override public Boolean focusPermission() {
                Bundle request = new Bundle();
                request.putString("package", context.getPackageName());
                Bundle result = context.getContentResolver().call(
                        Uri.parse("content://miui.statusbar.notification.public"), "canShowFocus", null, request);
                if (result == null || !result.containsKey("canShowFocus")) return null;
                Object allowed = result.get("canShowFocus");
                return allowed instanceof Boolean ? (Boolean) allowed : null;
            }
        });
    }

    static XiaomiLiveCapabilities read(Probe probe) {
        int protocol = -1;
        State island = State.UNKNOWN, permission = State.UNKNOWN;
        // Failure of one query must not suppress the other independent observations.
        try { int value = probe.protocolVersion(); if (value >= 0) protocol = value; }
        catch (Exception | LinkageError ignored) { }
        try { island = state(probe.islandFeature()); }
        catch (Exception | LinkageError ignored) { }
        try { permission = state(probe.focusPermission()); }
        catch (Exception | LinkageError ignored) { }
        return new XiaomiLiveCapabilities(protocol, island, permission);
    }

    private static State state(Boolean value) {
        return value == null ? State.UNKNOWN : value ? State.ENABLED : State.DISABLED;
    }
}
