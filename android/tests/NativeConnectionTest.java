package com.agentmonitor.live;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Production NativeConnection with controlled transport, disk failures and UI queue. */
public final class NativeConnectionTest {
    private static final Context CONTEXT = new Context();
    private static final String ID = "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345";
    private static final String CURRENT = "nrd_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefg";
    private static final String NEXT = "nrd_BBCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefg";
    private static final String OLD = "nrd_CBCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefg";
    private static int checks;
    private static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
    private static ExecutorService executor(String name) throws Exception {
        Field field = NativeConnection.class.getDeclaredField(name); field.setAccessible(true); return (ExecutorService) field.get(null);
    }
    private static void settle() throws Exception {
        executor("WORK").submit(() -> {}).get(5, TimeUnit.SECONDS);
        executor("REAPER").submit(() -> {}).get(5, TimeUnit.SECONDS);
        check(!NativeConnection.isBusy(), "single-flight released");
    }
    private static String future() { return Instant.ofEpochMilli(System.currentTimeMillis() + 600000).toString(); }
    private static JSONObject pairing() { return new JSONObject().put("request_id", ID).put("phase", "pending").put("code_verifier", "SyntheticPkceVerifier").put("expires_at", future()).put("verification_url", NativeApi.ORIGIN + "/#/native-connect/" + ID); }
    private static JSONObject approved() { return new JSONObject().put("status", "approved").put("mode", "full_app").put("reader_token", NEXT).put("expires_at", future()); }
    private static JSONObject connected() { return new JSONObject().put("reader_token", CURRENT).put("mode", "full_app").put("cookie_ready", true).put("expires_at", future()); }
    private static void reset() throws Exception {
        settle(); Handler.runPosted();
        SessionStore.disk = new JSONObject(); SessionStore.writes = 0; SessionStore.failOnWrite = -1;
        NativeConnection.cancel(CONTEXT); SessionStore.writes = 0;
        NativeApi.calls.clear(); TrackingService.stops = 0; SystemClock.now += 120000;
        NativeConnection.consumeError();
        NativeApi.transport = (method, path, body, token) -> {
            if (path.endsWith("/poll")) return approved();
            if (path.endsWith("/start")) return new JSONObject().put("request_id", ID).put("verification_url", NativeApi.ORIGIN + "/#/native-connect/" + ID).put("expires_at", future());
            return new JSONObject();
        };
    }
    private static boolean called(String method, String path, String token) { return NativeApi.calls.contains(method + " " + path + " " + token); }
    private static void tick() throws Exception { SystemClock.now += 5000; NativeConnection.tick(CONTEXT); settle(); }
    private static void happy() throws Exception {
        reset(); SessionStore.disk = connected().put("pairing", pairing()).put("retired_reader", OLD);
        tick(); JSONObject result = SessionStore.read(CONTEXT);
        check(result.optBoolean("native_ready") && NEXT.equals(result.optString("reader_token")), "claim becomes native connection");
        check(result.optJSONObject("pairing") == null && !result.optBoolean("cookie_ready"), "one-use claim and legacy ready removed");
        tick(); SystemClock.now += 35000; tick();
        check(called("DELETE", "/api/native/session", CURRENT) && called("DELETE", "/api/native/session", OLD), "prior queue and replaced connection both retired");
        check(TrackingService.stops == 1, "old tracking stopped once after ready commit");
        for (String call : NativeApi.calls) check(!call.contains("web-session"), "no cookie exchange");
    }
    private static void cancellation() throws Exception {
        reset(); AtomicInteger opened = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        NativeApi.transport = (method, path, body, token) -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return new JSONObject().put("request_id", ID).put("verification_url", NativeApi.ORIGIN + "/#/native-connect/" + ID).put("expires_at", future()); };
        NativeConnection.start(CONTEXT, url -> opened.incrementAndGet());
        check(entered.await(5, TimeUnit.SECONDS), "start reached transport");
        check(NativeConnection.cancel(CONTEXT), "cancel saved");
        check(!"opening".equals(state().stage), "cancel removes obsolete opening presentation before transport finishes");
        release.countDown(); settle(); Handler.runPosted();
        check(SessionStore.read(CONTEXT).optJSONObject("pairing") == null && opened.get() == 0, "late start cannot revive cancelled login");
        reset(); NativeConnection.start(CONTEXT, url -> opened.incrementAndGet()); settle();
        check(SessionStore.read(CONTEXT).optJSONObject("pairing") != null, "start saved before delayed callback");
        NativeConnection.cancel(CONTEXT); Handler.runPosted(); check(opened.get() == 0, "cancel invalidates queued browser callback");
    }
    private static void retiredRetryAndLogout() throws Exception {
        reset(); AtomicInteger deletes = new AtomicInteger();
        SessionStore.disk = connected().put("retired_reader", OLD);
        NativeApi.transport = (method, path, body, token) -> { if (deletes.incrementAndGet() == 1) throw new NativeApi.Failure(0); return new JSONObject(); };
        tick(); check(SessionStore.read(CONTEXT).toString().contains(OLD), "failed retirement remains durable");
        check(!NativeConnection.isBusy(), "retirement does not block login single-flight");
        SystemClock.now += 35000; tick();
        check(deletes.get() == 2 && !SessionStore.read(CONTEXT).toString().contains(OLD), "no-pairing tick retries retirement");
        reset(); SessionStore.disk = connected().put("logout_pending", true).put("retired_readers", new JSONArray().put(OLD));
        NativeApi.transport = (method, path, body, token) -> { throw new NativeApi.Failure(0); };
        NativeConnection.finishLogout(CONTEXT, NEXT); check(CURRENT.equals(SessionStore.read(CONTEXT).optString("reader_token")), "late logout cannot clear different credential");
        NativeConnection.finishLogout(CONTEXT, CURRENT); settle();
        check(SessionStore.read(CONTEXT).optString("reader_token").isEmpty() && SessionStore.read(CONTEXT).toString().contains(OLD), "logout clears account but retains failed revocation");
        reset(); SessionStore.disk = connected().put("logout_pending", true).put("pairing", pairing().put("phase", "native_claimed").put("reader_token", NEXT));
        NativeApi.transport = (method, path, body, token) -> { throw new NativeApi.Failure(0); };
        SessionStore.failOnWrite = 1; check(!NativeConnection.cancel(CONTEXT), "failed cancel reports persistence failure");
        NativeConnection.finishLogout(CONTEXT, CURRENT); settle();
        check(SessionStore.read(CONTEXT).toString().contains(NEXT), "completed logout retains claimed credential even if cancel save failed");
    }
    private static void legacyClaims() throws Exception {
        for (String phase : new String[]{"claimed", "exchanging", "native_claimed"}) {
            reset(); SessionStore.disk = connected().put("pairing", pairing().put("phase", phase).put("reader_token", NEXT).put("reader_expires_at", future()).put("web_session_ticket_expires_at", "2000-01-01T00:00:00Z"));
            tick(); check(called("GET", "/api/native/account", NEXT), "legacy claim checked against full-app API");
            check(SessionStore.read(CONTEXT).optBoolean("native_ready") && NEXT.equals(SessionStore.read(CONTEXT).optString("reader_token")), "legacy full-app claim recovered without ticket");
            check(!NativeApi.calls.toString().contains("/poll"), "consumed claim never polled again");
        }
        reset(); SessionStore.disk = connected().put("pairing", pairing().put("phase", "claimed").put("reader_token", NEXT).put("reader_expires_at", future()));
        NativeApi.transport = (method, path, body, token) -> { if (method.equals("GET")) throw new NativeApi.Failure(403); return new JSONObject(); };
        tick(); check(CURRENT.equals(SessionStore.read(CONTEXT).optString("reader_token")), "read-only legacy token never promoted");
        check(called("DELETE", "/api/native/session", NEXT), "rejected claim revoked");
        reset(); SessionStore.disk = connected().put("pairing", pairing().put("phase", "claimed").put("reader_token", NEXT).put("reader_expires_at", "2000-01-01T00:00:00Z"));
        tick(); check(SessionStore.read(CONTEXT).optJSONObject("pairing") == null, "expired legacy claim does not retry forever");
    }
    private static void diskFailures() throws Exception {
        reset(); SessionStore.disk = connected().put("pairing", pairing()); SessionStore.failOnWrite = 1;
        tick(); check(CURRENT.equals(SessionStore.read(CONTEXT).optString("reader_token")), "failed claim persistence preserves current connection");
        check(called("DELETE", "/api/native/session", NEXT) || SessionStore.read(CONTEXT).toString().contains(NEXT), "issued token is revoked or durably retained after disk failure");
        reset(); SessionStore.disk = connected().put("pairing", pairing()); SessionStore.failOnWrite = 2;
        tick(); JSONObject saved = SessionStore.read(CONTEXT).optJSONObject("pairing");
        check(saved != null && "native_claimed".equals(saved.optString("phase")), "ready-write failure retains durable one-use claim");
        tick(); check(NEXT.equals(SessionStore.read(CONTEXT).optString("reader_token")), "durable claim recovers without second poll");
        int polls = 0; for (String call : NativeApi.calls) if (call.contains("/poll ")) polls++;
        check(polls == 1, "one-use claim consumed exactly once");
    }
    private static void latePollAndUnknownOutcome() throws Exception {
        reset(); SessionStore.disk = connected().put("pairing", pairing());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        NativeApi.transport = (method, path, body, token) -> { if (path.endsWith("/poll")) { entered.countDown(); release.await(5, TimeUnit.SECONDS); return approved(); } return new JSONObject(); };
        NativeConnection.tick(CONTEXT); check(entered.await(5, TimeUnit.SECONDS), "poll entered"); NativeConnection.cancel(CONTEXT); release.countDown(); settle();
        check(CURRENT.equals(SessionStore.read(CONTEXT).optString("reader_token")) && called("DELETE", "/api/native/session", NEXT), "cancelled late approval never replaces current and is revoked");
        reset(); SessionStore.disk = connected().put("pairing", pairing()); AtomicInteger polls = new AtomicInteger();
        NativeApi.transport = (method, path, body, token) -> { throw new NativeApi.Failure(polls.incrementAndGet() == 1 ? 0 : 410); };
        tick(); check(SessionStore.read(CONTEXT).optJSONObject("pairing") != null, "uncertain poll retains request for status check");
        tick(); check(SessionStore.read(CONTEXT).optJSONObject("pairing") == null && CURRENT.equals(SessionStore.read(CONTEXT).optString("reader_token")), "already-consumed unknown response requires reauthorization without losing old connection");
    }
    private static int polls() { int count = 0; for (String call : NativeApi.calls) if (call.contains("/poll ")) count++; return count; }
    private static NativeConnection.LoginState state() { return NativeConnection.loginState(CONTEXT); }
    private static void loginPresentation() throws Exception {
        reset(); check("idle".equals(state().stage) && state().primaryEnabled, "fresh login is actionable");
        AtomicInteger starts = new AtomicInteger(), opened = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        NativeApi.transport = (method, path, body, token) -> {
            starts.incrementAndGet(); entered.countDown(); release.await(5, TimeUnit.SECONDS);
            return new JSONObject().put("request_id", ID).put("verification_url", NativeApi.ORIGIN + "/#/native-connect/" + ID).put("expires_at", future());
        };
        NativeConnection.start(CONTEXT, url -> opened.incrementAndGet()); check(entered.await(5, TimeUnit.SECONDS), "opening request held");
        check("opening".equals(state().stage) && !state().primaryEnabled && state().secondary.isEmpty(), "opening disables duplicate login controls");
        NativeConnection.start(CONTEXT, url -> opened.incrementAndGet()); check(starts.get() == 1, "repeated start cannot create another request");
        release.countDown(); settle(); Handler.runPosted();
        check(opened.get() == 1 && "waiting".equals(state().stage), "durable pending request shows browser confirmation instead of logged in");
        check("继续确认".equals(state().primary) && state().note.contains("连接这台设备"), "pending guidance names the explicit approval step");
        NativeConnection.start(CONTEXT, url -> opened.incrementAndGet());
        check(opened.get() == 2 && starts.get() == 1, "continue confirmation reopens the saved request without another start POST");

        reset(); SessionStore.disk = new JSONObject().put("pairing", pairing());
        CountDownLatch checking = new CountDownLatch(1), checked = new CountDownLatch(1);
        NativeApi.transport = (method, path, body, token) -> { checking.countDown(); checked.await(5, TimeUnit.SECONDS); return new JSONObject().put("status", "pending"); };
        NativeConnection.check(CONTEXT); check(checking.await(5, TimeUnit.SECONDS), "manual check entered");
        check("checking".equals(state().stage) && !state().secondaryEnabled && state().primaryEnabled, "checking cannot claim browser approval and keeps its confirmation link available");
        NativeConnection.check(CONTEXT); NativeConnection.tick(CONTEXT); check(polls() == 1, "manual and automatic checks share a single-flight gate");
        checked.countDown(); settle();
        check("waiting".equals(state().stage) && !state().secondaryEnabled, "completed pending check retains normal poll cooldown");
        NativeConnection.check(CONTEXT); check(polls() == 1, "manual check cannot bypass cooldown");
        SystemClock.now += 3001; check(state().secondaryEnabled, "check re-enables after cooldown without rebuilding activity");

        reset(); NativeApi.transport = (method, path, body, token) -> { throw new NativeApi.Failure(0); };
        NativeConnection.start(CONTEXT, url -> {}); settle();
        check("retry".equals(state().stage) && "重试登录".equals(state().primary), "failed opening has a persistent retry state");
        NativeConnection.consumeError(); check("retry".equals(state().stage), "consuming a toast does not remove the persistent status");
        reset(); SessionStore.disk = new JSONObject().put("pairing", pairing());
        NativeApi.transport = (method, path, body, token) -> { throw new NativeApi.Failure(0); };
        tick(); check("retry".equals(state().stage) && "重试检查连接".equals(state().secondary), "network check failure is visible and retains original approval request");
        String text = state().heading + state().note + state().primary + state().secondary;
        check(!text.contains(ID) && !text.contains("SyntheticPkceVerifier") && !text.contains(NEXT), "presentation never exposes request identifiers or credentials");
        NativeApi.transport = (method, path, body, token) -> { throw new NativeApi.Failure(429); };
        tick(); check(state().note.contains("请求较多") && !state().secondaryEnabled, "429 explains and retains server cooldown");
        int before = polls(); SystemClock.now += 5000; NativeConnection.check(CONTEXT); check(polls() == before, "manual retry honors Retry-After");

        for (int code : new int[]{403, 410}) {
            reset(); SessionStore.disk = new JSONObject().put("pairing", pairing());
            NativeApi.transport = (method, path, body, token) -> { throw new NativeApi.Failure(code); };
            tick(); check((code == 403 ? "rejected" : "reauthorize").equals(state().stage) && "重新发起登录".equals(state().primary), "terminal response explicitly requires a new request " + code);
            check(SessionStore.read(CONTEXT).optJSONObject("pairing") == null && !SessionStore.read(CONTEXT).optString("pairing_notice").isEmpty(), "terminal reason survives activity and process recreation");
            NativeConnection.consumeError(); check(!"idle".equals(state().stage), "terminal status remains after toast");
        }
        reset(); SessionStore.disk = new JSONObject().put("pairing", pairing().put("expires_at", "2000-01-01T00:00:00Z"));
        check("expired".equals(state().stage), "expired request is actionable before next heartbeat");
        tick(); check("expired".equals(state().stage) && polls() == 0, "local expiration removes request without polling");
        NativeApi.transport = (method, path, body, token) -> { throw new NativeApi.Failure(0); };
        NativeConnection.start(CONTEXT, url -> {}); settle();
        check("retry".equals(state().stage) && state().heading.contains("无法打开"), "a failed fresh attempt replaces the old expiration notice accurately");
    }
    private static void claimedPresentation() throws Exception {
        reset(); SessionStore.disk = new JSONObject().put("pairing", pairing()); SessionStore.failOnWrite = 2;
        tick(); check("native_claimed".equals(SessionStore.read(CONTEXT).optJSONObject("pairing").optString("phase")), "test saved a real claim before ready persistence failed");
        check("retry".equals(state().stage) && state().secondary.isEmpty() && "重试完成连接".equals(state().primary), "saved claim offers completion rather than a new approval");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        NativeApi.transport = (method, path, body, token) -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return new JSONObject(); };
        SystemClock.now += 3001; NativeConnection.start(CONTEXT, url -> { throw new AssertionError("A saved claim must not reopen browser"); });
        check(entered.await(5, TimeUnit.SECONDS), "completion check entered account API");
        check("completing".equals(state().stage) && !state().primaryEnabled, "claimed credential shows completing and disables duplicate action");
        NativeConnection.check(CONTEXT); check(polls() == 1, "completion never repeats one-use poll");
        release.countDown(); settle(); check(SessionStore.read(CONTEXT).optBoolean("native_ready"), "completion saves native readiness");
    }
    /** Deterministic test-only provider avoids host entropy latency; never packaged. */
    public static final class FixtureRandom extends java.security.SecureRandomSpi {
        private int counter;
        protected void engineSetSeed(byte[] seed) {}
        protected void engineNextBytes(byte[] output) { for (int i=0; i<output.length; i++) output[i] = (byte) ++counter; }
        protected byte[] engineGenerateSeed(int count) { byte[] result = new byte[count]; engineNextBytes(result); return result; }
    }
    public static void main(String[] args) throws Exception {
        java.security.Security.insertProviderAt(new java.security.Provider("Fixture", 1.0, "Synthetic test entropy") {{ put("SecureRandom.Fixture", FixtureRandom.class.getName()); }}, 1);
        try { happy(); cancellation(); retiredRetryAndLogout(); legacyClaims(); diskFailures(); latePollAndUnknownOutcome(); loginPresentation(); claimedPresentation(); System.out.println("NativeConnectionTest: " + checks + " checks passed"); }
        finally { executor("WORK").shutdownNow(); executor("REAPER").shutdownNow(); }
    }
}
