package com.agentmonitor.live;

import android.app.Notification;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

final class MainActivity {}
final class R {
    static final class drawable { static final int ic_claude = 1, ic_codex = 2, claude = 3, codex = 4; }
}
final class SessionStore {
    static String currentToken = "offline-test-token";
    static String token(Context context) { return currentToken; }
    static void clearIfToken(Context context, String token) { if (currentToken.equals(token)) currentToken = ""; }
}
final class NativeApi {
    static final class SnapshotTiming {
        long time = System.currentTimeMillis(), durationMs = 9;
        int status; boolean success; String stage = "headers", failure;
        SnapshotTiming(int status, boolean success, String failure) { this.status = status; this.success = success; this.failure = failure; }
    }
    private static final ThreadLocal<SnapshotTiming> snapshotTiming = new ThreadLocal<>();
    static SnapshotTiming takeSnapshotTiming() { SnapshotTiming result = snapshotTiming.get(); snapshotTiming.remove(); return result; }
    static final class Failure extends Exception { int status; long retryAfterMs; }
    static volatile JSONObject nextResult;
    static volatile Failure nextFailure;
    static JSONObject call(String method, String path, JSONObject body, String token) throws Failure {
        if (!"GET".equals(method) || !"/api/native/snapshot".equals(path) || body != null)
            throw new AssertionError("Only the fixed snapshot request is allowed in the offline suite");
        if (nextFailure != null) { Failure failure = nextFailure; nextFailure = null; snapshotTiming.set(new SnapshotTiming(failure.status, false, failure.status >= 300 ? "http" : "io")); throw failure; }
        JSONObject result = nextResult; nextResult = null;
        if (result == null) throw new AssertionError("An offline result must be explicitly supplied before fetching");
        snapshotTiming.set(new SnapshotTiming(200, true, "none"));
        return result;
    }
    static JSONObject task(JSONObject value, String id) { return value.optJSONObject("selectedTask"); }
    static boolean online(JSONObject value, JSONObject task) { return value.optBoolean("online"); }
}
final class NotificationDiagnostics {
    static final List<String> stoppedReasons = new ArrayList<>();
    static final List<String> endedReasons = new ArrayList<>();
    static void write(Context context, String event, boolean promoted, Notification notification) { write(context, event, promoted, notification, ""); }
    static void write(Context context, String event, boolean promoted, Notification notification, String reason) {
        if ("stopped".equals(event)) stoppedReasons.add(reason);
        if ("ended".equals(event)) endedReasons.add(reason);
    }
    static String endReason(String message) { return message; }
}
