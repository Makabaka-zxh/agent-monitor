package com.agentmonitor.live;
import android.content.Context;
import org.json.JSONObject;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class SessionStore {
    static JSONObject disk = new JSONObject();
    static int writes, failOnWrite = -1;
    public static synchronized JSONObject read(Context context) { return disk.copy(); }
    public static synchronized void write(Context context, JSONObject value) throws Exception {
        writes++; if (writes == failOnWrite) throw new Exception("Synthetic disk failure"); disk = value.copy();
    }
}
final class TrackingService {
    static long generation = 7; static int stops;
    static void requestStop(Context context, long expected) { if (expected == generation) stops++; }
}
final class NativeApi {
    static final String ORIGIN = "https://monitor.example.com";
    static final class Failure extends Exception {
        final int status; final long retryAfterMs;
        Failure(int value) { this(value, 30000); }
        Failure(int value, long retryAfter) { status=value; retryAfterMs=retryAfter; }
    }
    interface Transport { JSONObject call(String method, String path, JSONObject body, String token) throws Exception; }
    static volatile Transport transport;
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static JSONObject call(String method, String path, JSONObject body, String token) throws Failure {
        calls.add(method + " " + path + " " + (token == null ? "" : token));
        try { return transport.call(method, path, body, token); } catch (Failure failure) { throw failure; } catch (Exception failure) { throw new Failure(0); }
    }
    static boolean validId(String value) { return value != null && value.matches("[A-Za-z0-9_-]{32}"); }
    static boolean validToken(String value) { return value != null && value.matches("[!-~]{20,8192}"); }
    static long time(String value) { try { return Instant.parse(value).toEpochMilli(); } catch (Exception ignored) { return 0; } }
}
