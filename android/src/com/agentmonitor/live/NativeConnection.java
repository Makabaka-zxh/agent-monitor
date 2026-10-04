package com.agentmonitor.live;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** PKCE browser authorization. Native pages use the approved bearer, never a WebView. */
public final class NativeConnection {
    private static final PairingGate GATE = new PairingGate();
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final ExecutorService REAPER = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final AtomicLong REVISION = new AtomicLong();
    private static final AtomicBoolean REAPING = new AtomicBoolean();
    private static final LinkedHashSet<String> MEMORY_RETIRED = new LinkedHashSet<>();
    private static volatile boolean busy;
    private static volatile long nextPoll, nextRetire;
    private static volatile String error = "";
    private static volatile String operation = "", issue = "", issueRequest = "";
    private static volatile long operationRevision;
    public interface Started { void ready(String url); }
    private NativeConnection() {}
    public static boolean isBusy() { return busy; }
    public static String consumeError() { String value = error; error = ""; return value; }
    /** Presentation contains no request identifiers, credentials, or inferred browser approval. */
    public static final class LoginState {
        public final String stage, heading, note, primary, secondary;
        public final boolean primaryEnabled, secondaryEnabled;
        private LoginState(String stage, String heading, String note, String primary, boolean primaryEnabled, String secondary, boolean secondaryEnabled) {
            this.stage = stage; this.heading = heading; this.note = note; this.primary = primary;
            this.primaryEnabled = primaryEnabled; this.secondary = secondary; this.secondaryEnabled = secondaryEnabled;
        }
    }
    public static LoginState loginState(Context context) {
        JSONObject store = SessionStore.read(context), pairing = store.optJSONObject("pairing");
        boolean working = busy && operationRevision == REVISION.get();
        if (working && "start".equals(operation))
            return new LoginState("opening", "正在打开登录", "稍后会打开浏览器。", "正在打开登录…", false, "", false);
        String phase = pairing == null ? "" : pairing.optString("phase"), id = request(pairing);
        boolean claimed = "native_claimed".equals(phase) || "claimed".equals(phase) || "exchanging".equals(phase);
        boolean pending = "pending".equals(phase) && NativeApi.validId(id)
                && (NativeApi.ORIGIN + "/#/native-connect/" + id).equals(pairing.optString("verification_url"));
        boolean expired = pairing != null && NativeApi.time(pairing.optString(claimed ? "reader_expires_at" : "expires_at")) <= System.currentTimeMillis();
        String problem = id.equals(issueRequest) ? issue : "";
        if (pairing == null && problem.isEmpty()) problem = store.optString("pairing_notice");
        boolean canCheck = !busy && SystemClock.elapsedRealtime() >= nextPoll;
        if ("start_failed".equals(problem)) return new LoginState("retry", "暂时无法打开登录", "请检查网络后重试。", "重试登录", !busy, "", false);
        if (expired || "expired".equals(problem)) return new LoginState("expired", "确认链接已过期", "重新发起后，请在浏览器确认连接这台设备。", "重新发起登录", !busy, "", false);
        if (pairing == null || !pending && !claimed) {
            if ("rejected".equals(problem)) return new LoginState("rejected", "本次连接未获允许", "可以重新发起，在浏览器再次确认。", "重新发起登录", !busy, "", false);
            if ("reauthorize".equals(problem)) return new LoginState("reauthorize", "需要重新确认连接", "上次连接已失效，或未能在本机完成。", "重新发起登录", !busy, "", false);
            return new LoginState("idle", "登录 Monitor", "在浏览器登录后，还需确认连接这台设备。", "登录 Monitor", !busy, "", false);
        }
        if (claimed) return new LoginState(working ? "completing" : "retry", working ? "正在完成连接" : "连接尚未完成",
                working ? "正在验证并保存本机连接。" : "已收到确认，等待连接恢复后继续。", working ? "正在完成连接…" : "重试完成连接", canCheck, "", false);
        if (working) return new LoginState("checking", "正在检查浏览器确认", "若尚未确认，可继续打开原来的确认页。", "继续确认", true, "正在检查连接…", false);
        if ("network".equals(problem) || "limited".equals(problem)) return new LoginState("retry", "暂时无法检查连接",
                "limited".equals(problem) ? "请求较多，稍后会自动再检查。" : "会自动重试，也可稍后手动检查。", "继续确认", true, "重试检查连接", canCheck);
        return new LoginState("waiting", "等待浏览器确认", "登录后点「连接这台设备」，再返回 Monitor。", "继续确认", true, "我已确认，检查连接", canCheck);
    }
    /** Same single-flight gate and server cooldown as automatic checks. Never force a second claim. */
    public static void check(Context context) { tick(context); }
    private static void setIssue(String id, long revision, String value) {
        if (REVISION.get() == revision) { issueRequest = id; issue = value; }
    }
    private static JSONObject pending(Context context) { return SessionStore.read(context).optJSONObject("pairing"); }
    private static String request(JSONObject value) { return value == null ? "" : value.optString("request_id"); }
    private static boolean own(JSONObject store, String id, long revision) {
        return REVISION.get() == revision && !store.optBoolean("logout_pending") && id.equals(request(store.optJSONObject("pairing")));
    }
    private static LinkedHashSet<String> retired(JSONObject store) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String old = store.optString("retired_reader"); if (!old.isEmpty()) result.add(old);
        JSONArray array = store.optJSONArray("retired_readers");
        if (array != null) for (int i = 0; i < array.length(); i++) { String token = array.optString(i); if (!token.isEmpty()) result.add(token); }
        return result;
    }
    private static void putRetired(JSONObject store, LinkedHashSet<String> values) throws Exception {
        store.remove("retired_reader");
        JSONArray array = new JSONArray(); for (String value : values) array.put(value);
        if (array.length() == 0) store.remove("retired_readers"); else store.put("retired_readers", array);
    }
    private static void retainRetired(Context context, String token) {
        if (!NativeApi.validToken(token)) return;
        synchronized (SessionStore.class) {
            JSONObject store = SessionStore.read(context);
            if (token.equals(store.optString("reader_token"))) return;
            LinkedHashSet<String> queue = retired(store); queue.add(token);
            try { putRetired(store, queue); SessionStore.write(context, store); }
            catch (Exception ignored) { synchronized (MEMORY_RETIRED) { MEMORY_RETIRED.add(token); } }
        }
        nextRetire = 0; scheduleRetirement(context);
    }
    /** Cancel invalidates in-flight calls and delayed browser callbacks. */
    public static boolean cancel(Context supplied) {
        Context context = supplied.getApplicationContext(); REVISION.incrementAndGet();
        issue = ""; issueRequest = "";
        synchronized (SessionStore.class) {
            JSONObject store = SessionStore.read(context), pairing = store.optJSONObject("pairing");
            LinkedHashSet<String> queue = retired(store);
            if (pairing != null) {
                String token = pairing.optString("reader_token");
                if (!token.isEmpty() && !token.equals(store.optString("reader_token"))) queue.add(token);
            }
            store.remove("pairing"); store.remove("pairing_notice");
            try { putRetired(store, queue); SessionStore.write(context, store); }
            catch (Exception ignored) { error = "未能保存取消操作，请重试"; return false; }
        }
        nextRetire = 0; scheduleRetirement(context); return true;
    }
    /** Drop current account state only after the matching logout was acknowledged. */
    public static void finishLogout(Context supplied, String expectedToken) throws Exception {
        Context context = supplied.getApplicationContext();
        synchronized (SessionStore.class) {
            JSONObject current = SessionStore.read(context);
            if (!current.optBoolean("logout_pending") || !expectedToken.equals(current.optString("reader_token"))) return;
            LinkedHashSet<String> queue = retired(current);
            JSONObject pairing = current.optJSONObject("pairing");
            if (pairing != null && !pairing.optString("reader_token").isEmpty() && !expectedToken.equals(pairing.optString("reader_token"))) queue.add(pairing.optString("reader_token"));
            JSONObject cleared = new JSONObject(); putRetired(cleared, queue); SessionStore.write(context, cleared);
        }
        nextRetire = 0; scheduleRetirement(context);
    }
    /** A server 401 removes only that connection, retaining newer login and revocations. */
    public static void invalidate(Context context, String expectedToken) throws Exception {
        synchronized (SessionStore.class) {
            JSONObject current = SessionStore.read(context);
            if (!expectedToken.equals(current.optString("reader_token"))) return;
            JSONObject cleared = new JSONObject();
            JSONObject pairing = current.optJSONObject("pairing"); if (pairing != null) cleared.put("pairing", pairing);
            putRetired(cleared, retired(current)); SessionStore.write(context, cleared);
        }
        scheduleRetirement(context.getApplicationContext());
    }
    public static void start(Context supplied, Started listener) {
        Context context = supplied.getApplicationContext();
        JSONObject before = SessionStore.read(context), existing = before.optJSONObject("pairing");
        if (before.optBoolean("logout_pending")) { error = "请先完成退出，再重新登录"; return; }
        if (existing != null && "pending".equals(existing.optString("phase")) && NativeApi.validId(request(existing))
                && (NativeApi.ORIGIN + "/#/native-connect/" + request(existing)).equals(existing.optString("verification_url"))
                && NativeApi.time(existing.optString("expires_at")) > System.currentTimeMillis()) {
            listener.ready(existing.optString("verification_url")); return;
        }
        if (existing != null && ("native_claimed".equals(existing.optString("phase")) || "claimed".equals(existing.optString("phase")) || "exchanging".equals(existing.optString("phase")))
                && NativeApi.time(existing.optString("reader_expires_at")) > System.currentTimeMillis()) { check(context); return; }
        if (!GATE.acquire()) return;
        final long revision = REVISION.incrementAndGet();
        final String previousId = request(existing), previousToken = before.optString("reader_token");
        issue = ""; issueRequest = ""; error = ""; operation = "start"; operationRevision = revision; busy = true;
        WORK.execute(() -> {
            String url = "", createdId = "";
            try {
                byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
                String verifier = Base64.encodeToString(random, Base64.NO_PADDING | Base64.NO_WRAP | Base64.URL_SAFE);
                String challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)), Base64.NO_PADDING | Base64.NO_WRAP | Base64.URL_SAFE);
                String name = "Monitor · " + Build.MODEL; if (name.length() > 60) name = name.substring(0, 60);
                JSONObject result = NativeApi.call("POST", "/api/native/pairing/start", new JSONObject().put("device_name", name).put("code_challenge", challenge).put("mode", "full_app"), null);
                String id = result.optString("request_id"); url = result.optString("verification_url");
                if (!NativeApi.validId(id) || !url.equals(NativeApi.ORIGIN + "/#/native-connect/" + id) || NativeApi.time(result.optString("expires_at")) <= System.currentTimeMillis()) throw new Exception();
                synchronized (SessionStore.class) {
                    JSONObject store = SessionStore.read(context);
                    if (REVISION.get() != revision || store.optBoolean("logout_pending") || !previousToken.equals(store.optString("reader_token"))
                            || !previousId.equals(request(store.optJSONObject("pairing")))) return;
                    JSONObject old = store.optJSONObject("pairing");
                    if (old != null && !old.optString("reader_token").isEmpty() && !old.optString("reader_token").equals(previousToken)) {
                        LinkedHashSet<String> queue = retired(store); queue.add(old.optString("reader_token")); putRetired(store, queue);
                    }
                    store.remove("pairing_notice");
                    store.put("pairing", new JSONObject().put("request_id", id).put("verification_url", url).put("code_verifier", verifier)
                            .put("expires_at", result.getString("expires_at")).put("phase", "pending"));
                    SessionStore.write(context, store); createdId = id;
                }
                nextPoll = 0;
            } catch (Exception ignored) { url = ""; setIssue(previousId, revision, "start_failed"); if (REVISION.get() == revision) error = "无法打开登录，请检查连接后重试"; }
            finally { busy = false; GATE.release(); scheduleRetirement(context); }
            String target = url, id = createdId;
            if (!id.isEmpty()) UI.post(() -> { if (own(SessionStore.read(context), id, revision)) listener.ready(target); });
        });
    }
    public static void tick(Context supplied) {
        Context context = supplied.getApplicationContext(); scheduleRetirement(context);
        if (SessionStore.read(context).optBoolean("logout_pending") || busy || SystemClock.elapsedRealtime() < nextPoll || !GATE.acquire()) return;
        JSONObject current = pending(context);
        if (current == null) { GATE.release(); return; }
        final long revision = REVISION.get(); operation = "check"; operationRevision = revision; busy = true;
        WORK.execute(() -> {
            String id = current.optString("request_id"), receivedToken = ""; boolean readyWritePending = false;
            try {
                if (!NativeApi.validId(id)) throw new Exception();
                JSONObject result;
                String phase = current.optString("phase");
                if ("pending".equals(phase)) {
                    if (NativeApi.time(current.optString("expires_at")) <= System.currentTimeMillis()) throw new Exception();
                    result = NativeApi.call("POST", "/api/native/pairing/" + id + "/poll", new JSONObject().put("code_verifier", current.getString("code_verifier")), null);
                    if ("pending".equals(result.optString("status"))) { setIssue(id, revision, ""); return; }
                    receivedToken = result.optString("reader_token");
                    if (!"approved".equals(result.optString("status")) || !"full_app".equals(result.optString("mode"))
                            || !NativeApi.validToken(receivedToken) || NativeApi.time(result.optString("expires_at")) <= System.currentTimeMillis()) throw new Exception();
                    synchronized (SessionStore.class) {
                        JSONObject store = SessionStore.read(context);
                        if (!own(store, id, revision)) { retainRetired(context, receivedToken); return; }
                        JSONObject saved = store.optJSONObject("pairing");
                        saved.put("phase", "native_claimed").put("mode", "full_app").put("reader_token", receivedToken).put("reader_expires_at", result.getString("expires_at"));
                        store.put("pairing", saved); SessionStore.write(context, store);
                    }
                } else if ("native_claimed".equals(phase) || "claimed".equals(phase) || "exchanging".equals(phase)) {
                    receivedToken = current.optString("reader_token");
                    if (!NativeApi.validToken(receivedToken) || NativeApi.time(current.optString("reader_expires_at")) <= System.currentTimeMillis()) throw new Exception();
                    // Legacy stages lack mode; this full-app-only API proves it.
                    NativeApi.call("GET", "/api/native/account", null, receivedToken);
                    result = new JSONObject().put("reader_token", receivedToken).put("expires_at", current.getString("reader_expires_at"));
                } else throw new Exception();
                synchronized (SessionStore.class) {
                    JSONObject store = SessionStore.read(context);
                    if (!own(store, id, revision)) { retainRetired(context, receivedToken); return; }
                    String previous = store.optString("reader_token");
                    readyWritePending = true; LinkedHashSet<String> queue = retired(store);
                    if (!previous.isEmpty() && !previous.equals(receivedToken)) queue.add(previous);
                    queue.remove(receivedToken); putRetired(store, queue);
                    store.remove("pairing"); store.remove("cookie_ready"); store.remove("pairing_notice");
                    store.put("reader_token", receivedToken).put("expires_at", result.getString("expires_at")).put("mode", "full_app").put("native_ready", true);
                    SessionStore.write(context, store);
                }
                setIssue(id, revision, "");
                TrackingService.requestStop(context, TrackingService.generation);
            } catch (Exception failure) {
                boolean transientFailure = failure instanceof NativeApi.Failure && (((NativeApi.Failure) failure).status == 0 || ((NativeApi.Failure) failure).status == 429 || ((NativeApi.Failure) failure).status >= 500);
                JSONObject persisted = pending(context);
                boolean savedClaim = persisted != null && id.equals(request(persisted)) && !persisted.optString("reader_token").isEmpty();
                if (transientFailure || (readyWritePending && savedClaim && !(failure instanceof NativeApi.Failure))) {
                    setIssue(id, revision, failure instanceof NativeApi.Failure && ((NativeApi.Failure) failure).status == 429 ? "limited" : "network");
                    if (failure instanceof NativeApi.Failure && ((NativeApi.Failure) failure).status == 429) nextPoll = SystemClock.elapsedRealtime() + ((NativeApi.Failure) failure).retryAfterMs;
                } else {
                    String problem = NativeApi.time(current.optString("pending".equals(current.optString("phase")) ? "expires_at" : "reader_expires_at")) <= System.currentTimeMillis() ? "expired"
                            : failure instanceof NativeApi.Failure && ((NativeApi.Failure) failure).status == 403 ? "rejected" : "reauthorize";
                    if (!receivedToken.isEmpty()) retainRetired(context, receivedToken);
                    synchronized (SessionStore.class) {
                        try { JSONObject store = SessionStore.read(context); if (own(store, id, revision)) { store.remove("pairing"); store.put("pairing_notice", problem); SessionStore.write(context, store); } } catch (Exception ignored) { }
                    }
                    setIssue(id, revision, problem);
                    if (REVISION.get() == revision) error = "expired".equals(problem) ? "确认链接已过期，请重新发起登录" : "rejected".equals(problem) ? "连接未获允许，可重新发起登录" : "登录未完成，请重新发起确认";
                }
            } finally { nextPoll = Math.max(nextPoll, SystemClock.elapsedRealtime() + 3000); busy = false; GATE.release(); scheduleRetirement(context); }
        });
    }
    private static void scheduleRetirement(Context context) {
        if (SystemClock.elapsedRealtime() < nextRetire || !REAPING.compareAndSet(false, true)) return;
        String candidate = "";
        synchronized (SessionStore.class) {
            JSONObject store = SessionStore.read(context); LinkedHashSet<String> queue = retired(store);
            synchronized (MEMORY_RETIRED) { queue.addAll(MEMORY_RETIRED); }
            queue.remove(store.optString("reader_token"));
            if (!queue.isEmpty()) candidate = queue.iterator().next();
        }
        if (candidate.isEmpty()) { REAPING.set(false); return; }
        final String token = candidate; nextRetire = SystemClock.elapsedRealtime() + 30000;
        REAPER.execute(() -> {
            boolean revoked = false;
            try { NativeApi.call("DELETE", "/api/native/session", null, token); revoked = true; }
            catch (NativeApi.Failure failure) {
                revoked = failure.status == 401;
                if (failure.status == 429) nextRetire = SystemClock.elapsedRealtime() + failure.retryAfterMs;
            } catch (Exception ignored) { }
            if (revoked) {
                synchronized (SessionStore.class) {
                    JSONObject store = SessionStore.read(context); LinkedHashSet<String> queue = retired(store); queue.remove(token);
                    try { putRetired(store, queue); SessionStore.write(context, store); synchronized (MEMORY_RETIRED) { MEMORY_RETIRED.remove(token); } }
                    catch (Exception ignored) { }
                }
                nextRetire = SystemClock.elapsedRealtime() + 2000;
            }
            REAPING.set(false);
        });
    }
}
