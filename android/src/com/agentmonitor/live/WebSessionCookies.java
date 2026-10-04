package com.agentmonitor.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import org.json.JSONObject;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Only native HTTPS may install authentication. Cookie backups remain in Keystore storage. */
public final class WebSessionCookies {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static long mutation;
    private WebSessionCookies() {}
    public static String backup() {
        String header = CookieManager.getInstance().getCookie(NativeApi.ORIGIN);
        if (header != null) for (String part : header.split(";")) {
            String cookie = part.trim();
            if (cookie.startsWith("agent_monitor_session=") && cookie.matches("agent_monitor_session=[!-~]+"))
                return cookie + "; Secure; HttpOnly; SameSite=Strict; Path=/";
        }
        return "";
    }
    public static String validatedCookie(NativeApi.Reply reply) throws Exception {
        String selected = null;
        for (String cookie : reply.cookies) {
            if (!cookie.startsWith("agent_monitor_session=")) continue;
            if (selected != null || cookie.indexOf('\r') >= 0 || cookie.indexOf('\n') >= 0) throw new Exception();
            boolean secure = false, httpOnly = false, strict = false, path = false;
            for (String part : cookie.split(";")) {
                String attribute = part.trim().toLowerCase(Locale.ROOT);
                if (attribute.equals("secure")) secure = true;
                if (attribute.equals("httponly")) httpOnly = true;
                if (attribute.equals("samesite=strict")) strict = true;
                if (attribute.equals("path=/")) path = true;
                if (attribute.startsWith("domain=")) throw new Exception();
            }
            if (!(secure && httpOnly && strict && path)) throw new Exception();
            selected = cookie;
        }
        if (selected == null || !reply.body.optBoolean("ok") || NativeApi.time(reply.body.optString("expires_at")) == 0) throw new Exception();
        return selected;
    }
    public static boolean install(Context context, JSONObject pending, NativeApi.Reply reply) throws Exception {
        final String id = pending.getString("request_id"), cookie = validatedCookie(reply), previous = pending.optString("previous_cookie");
        final AtomicBoolean installed = new AtomicBoolean(); final CountDownLatch completed = new CountDownLatch(1);
        UI.post(() -> {
            JSONObject current = SessionStore.read(context).optJSONObject("pairing");
            if (current == null || !id.equals(current.optString("request_id"))) { completed.countDown(); return; }
            final long ownMutation = ++mutation;
            CookieManager.getInstance().setCookie(NativeApi.ORIGIN, cookie, success -> {
                if (ownMutation != mutation) { completed.countDown(); return; }
                try {
                    JSONObject store = SessionStore.read(context), own = store.optJSONObject("pairing");
                    if (Boolean.TRUE.equals(success) && own != null && id.equals(own.optString("request_id"))) {
                        CookieManager.getInstance().flush(); // durability precedes the persisted ready flag
                        JSONObject ready = new JSONObject().put("reader_token", pending.getString("reader_token"))
                                .put("expires_at", reply.body.getString("expires_at")).put("mode", "full_app").put("cookie_ready", true);
                        String previousReader = store.optString("reader_token");
                        if (!previousReader.isEmpty() && !previousReader.equals(pending.optString("reader_token"))) ready.put("retired_reader", previousReader);
                        SessionStore.write(context, ready);
                        TrackingService.requestStop(context, TrackingService.generation);
                        installed.set(true);
                    }
                } catch (Exception ignored) { }
                if (installed.get()) completed.countDown();
                else restoreUi(previous, completed::countDown);
            });
        });
        return completed.await(10, TimeUnit.SECONDS) && installed.get();
    }
    private static void restoreUi(String cookie, Runnable completed) {
        ++mutation;
        if (cookie.isEmpty()) CookieManager.getInstance().removeAllCookies(value -> { CookieManager.getInstance().flush(); if (completed != null) completed.run(); });
        else CookieManager.getInstance().setCookie(NativeApi.ORIGIN, cookie, value -> { CookieManager.getInstance().flush(); if (completed != null) completed.run(); });
    }
    public static void restore(String cookie, Runnable completed) { UI.post(() -> restoreUi(cookie, completed)); }
    public static void clear() { clear(null); }
    public static void clear(Runnable completed) { restore("", completed); }
}
