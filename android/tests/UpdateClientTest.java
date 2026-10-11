package com.agentmonitor.live;

import android.content.Context;
import android.os.SystemClock;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Production redirect/download/cancellation paths with a socket-free HTTPS transport. */
public final class UpdateClientTest {
    private static final byte[] APK = "synthetic-signed-package-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String TAG = "v1.0.1", NAME = "monitor-1.0.1.apk";
    private static final String URL = "https://github.com/Makabaka-zxh/agent-monitor/releases/download/" + TAG + "/" + NAME;
    private static final ArrayDeque<Fixture> fixtures = new ArrayDeque<>();
    private static final List<FakeHttps> opened = new ArrayList<>();
    private static int checks;
    private static Context context;
    private static File root;
    private static void check(boolean good, String label) { checks++; if (!good) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new AssertionError("Supply an isolated generated test directory");
        root = new File(args[0], "cache"); root.mkdirs(); context = new Context(root);
        // Keep host random-provider/TLS initialization outside the synthetic short deadlines.
        File warmup = File.createTempFile("update-test-", ".tmp", root); warmup.delete();
        HttpsURLConnection.setDefaultSSLSocketFactory(new NoNetworkSocketFactory());
        java.net.URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            protected URLConnection openConnection(java.net.URL url) throws IOException {
                Fixture next = fixtures.pollFirst(); if (next == null) throw new AssertionError("Unexpected transport; real sockets are forbidden");
                FakeHttps connection = new FakeHttps(url, next); opened.add(connection); return connection;
            }
        } : null);
        reset(new Fixture(200, APK));
        try (UpdateClient.Operation operation = new UpdateClient.Operation(2000)) {
            File result = UpdateClient.download(context, release(APK.length, hash(APK)), operation, null);
            check(java.util.Arrays.equals(Files.readAllBytes(result.toPath()), APK), "verified bytes saved intact");
            check(result.getName().matches("update-[A-Za-z0-9_-]+\\.apk"), "only random private APK basename is produced"); result.delete();
        }
        check(opened.get(0).getInstanceFollowRedirects() == false, "automatic redirects disabled");
        check("".equals(opened.get(0).getRequestProperty("Cookie")) && "".equals(opened.get(0).getRequestProperty("Authorization")), "account headers are not reused");
        check(opened.get(0).closed, "successful transport disconnected");
        Fixture redirect = new Fixture(302, new byte[0]); redirect.location = "https://release-assets.githubusercontent.com/github-production-release-asset/123/package?signature=synthetic";
        reset(redirect, new Fixture(200, APK));
        try (UpdateClient.Operation operation = new UpdateClient.Operation(2000)) {
            File result = UpdateClient.download(context, release(APK.length, hash(APK)), operation, null); result.delete();
        }
        check(opened.size() == 2 && opened.get(0).closed && opened.get(1).closed, "allowlisted CDN redirect is followed and both connections close");
        for (String target : new String[]{"http://release-assets.githubusercontent.com/package", "https://evil.example/package", "https://github.com/other/repo/releases/download/v1/" + NAME}) {
            Fixture bad = new Fixture(302, new byte[0]); bad.location = target; reset(bad);
            fails(release(APK.length, hash(APK)), null, 2000);
            check(opened.size() == 1 && opened.get(0).closed, "untrusted target is rejected before connection");
        }
        reset(new Fixture(200, APK)); fails(release(APK.length, hash(new byte[]{1})), null, 2000);
        reset(new Fixture(200, new byte[]{1})); fails(release(APK.length, hash(APK)), null, 2000);
        Fixture oversized = new Fixture(200, new byte[APK.length + 1]); oversized.announced = -1; reset(oversized);
        fails(release(APK.length, hash(APK)), null, 2000);
        Fixture truncated = new Fixture(200, new byte[]{1}); truncated.announced = -1; reset(truncated);
        fails(release(APK.length, hash(APK)), null, 2000);
        Fixture compressed = new Fixture(200, APK); compressed.encoding = "gzip"; reset(compressed);
        fails(release(APK.length, hash(APK)), null, 2000);
        for (int status : new int[]{401, 403, 404, 429, 500}) { reset(new Fixture(status, APK)); fails(release(APK.length, hash(APK)), null, 2000); }
        reset(new Fixture(200, APK));
        try (UpdateClient.Operation operation = new UpdateClient.Operation(2000)) {
            boolean rejected = false;
            try { UpdateClient.download(context, release(APK.length, hash(APK)), operation, (bytes, total) -> operation.cancel()); }
            catch (IOException expected) { rejected = true; }
            check(rejected && opened.get(0).closed, "cancellation during body rejects completion and disconnects");
            empty();
        }
        Fixture stalled = new Fixture(200, APK); stalled.stall = true; reset(stalled);
        long started = System.nanoTime(); fails(release(APK.length, hash(APK)), null, 150);
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500, "total deadline disconnects stalled response headers");
        reset();
        try (UpdateClient.Operation operation = new UpdateClient.Operation(2000)) {
            operation.cancel(); boolean rejected = false;
            try { UpdateClient.download(context, release(APK.length, hash(APK)), operation, null); }
            catch (IOException expected) { rejected = true; }
            check(rejected && opened.isEmpty(), "cancelled job never connects"); empty();
        }
        retryLimits();
        System.out.println("UpdateClientTest: " + checks + " checks passed");
    }
    private static String hash(byte[] value) throws Exception { return UpdateClient.hex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static UpdateClient.Release release(long size, String digest) {
        return new UpdateClient.Release(new UpdatePolicy.Manifest(1, UpdatePolicy.PACKAGE_NAME, "1.0.1", 30, 26, NAME, digest, size), TAG, URL, "Synthetic notes");
    }
    private static void reset(Fixture... values) { setElapsedOffset(0); setRetryDeadline(0); transport(values); }
    private static void transport(Fixture... values) { fixtures.clear(); opened.clear(); for (Fixture value : values) fixtures.add(value); }
    private static void setRetryDeadline(long value) {
        try {
            java.lang.reflect.Field field = UpdateClient.class.getDeclaredField("retryUntilNanos");
            field.setAccessible(true); field.setLong(null, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void setElapsedOffset(long value) {
        try {
            java.lang.reflect.Field field = SystemClock.class.getDeclaredField("elapsedOffsetNanos");
            field.setAccessible(true); field.setLong(null, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static IOException failedCheck() throws Exception {
        try (UpdateClient.Operation operation = new UpdateClient.Operation(2000)) {
            try { UpdateClient.check(operation); throw new AssertionError("expected failed check"); }
            catch (IOException expected) { return expected; }
        }
    }
    private static void retryLimits() throws Exception {
        reset(new Fixture(403, new byte[0]));
        IOException ordinary = failedCheck();
        check(ordinary.getClass() == IOException.class && "GitHub 暂时拒绝访问更新，请稍后重试".equals(ordinary.getMessage()),
                "plain 403 remains fixed access error, not rate limit");
        check(UpdateClient.retryRemainingMillis() == 0 && opened.get(0).closed, "ordinary forbidden neither waits nor leaks connection");
        Fixture malformed = new Fixture(403, new byte[0]); malformed.retryAfter = "secret.invalid/url\r\n";
        reset(malformed); ordinary = failedCheck();
        check(!(ordinary instanceof UpdateClient.RateLimitedException) && !ordinary.getMessage().contains("secret"), "untrusted hint never displayed or accepted");
        check(UpdateClient.retryRemainingMillis() == 0, "invalid forbidden hint does not install wait");

        reset(new Fixture(429, new byte[0]));
        IOException limited = failedCheck();
        check(limited instanceof UpdateClient.RateLimitedException && "GitHub 请求较多，请等待后重试".equals(limited.getMessage()), "429 has typed fixed failure");
        check(UpdateClient.retryRemainingMillis() > 59000 && UpdateClient.retryRemainingMillis() <= 60000, "missing 429 hint starts minute wait");
        check(opened.size() == 1 && opened.get(0).closed, "limited check closes response");
        transport();
        check(failedCheck() instanceof UpdateClient.RateLimitedException && opened.isEmpty(), "cooldown check sends zero requests");
        fails(release(APK.length, hash(APK)), null, 2000);
        check(opened.isEmpty(), "cooldown also gates direct APK entry before creating a connection");
        try (UpdateClient.Operation operation = new UpdateClient.Operation(2000)) {
            operation.cancel();
            try { UpdateClient.check(operation); throw new AssertionError("cancelled job ran"); }
            catch (IOException expected) { check(!(expected instanceof UpdateClient.RateLimitedException), "cancelled operation wins over cooldown error"); }
        }
        check(opened.isEmpty(), "cancelled cooldown request never opens transport");
        int requests = opened.size(); long awakeBefore = System.nanoTime();
        setElapsedOffset(TimeUnit.MINUTES.toNanos(2));
        check(System.nanoTime() - awakeBefore < TimeUnit.SECONDS.toNanos(10), "simulated sleep advances no actual awake minute");
        check(UpdateClient.retryRemainingMillis() == 0 && opened.size() == requests, "deep-sleep elapsed time exhausts wait without issuing a request");
        transport(new Fixture(200, APK));
        try (UpdateClient.Operation operation = new UpdateClient.Operation(2000)) {
            File file = UpdateClient.download(context, release(APK.length, hash(APK)), operation, null); file.delete();
        }
        check(opened.size() == 1 && opened.get(0).closed, "explicit manual request succeeds after expiry"); empty();

        Fixture shortWait = new Fixture(403, APK); shortWait.retryAfter = "3";
        reset(shortWait); fails(release(APK.length, hash(APK)), null, 2000);
        check(UpdateClient.retryRemainingMillis() > 2000 && UpdateClient.retryRemainingMillis() <= 3000, "APK forbidden with valid Retry-After records server wait");
        transport(); check(failedCheck() instanceof UpdateClient.RateLimitedException && opened.isEmpty(), "APK limit also gates API check");

        Fixture primary = new Fixture(403, new byte[0]); primary.remaining = "0";
        primary.reset = Long.toString(System.currentTimeMillis() / 1000 + 120);
        reset(primary); check(failedCheck() instanceof UpdateClient.RateLimitedException, "exhausted primary API limit is typed");
        check(UpdateClient.retryRemainingMillis() > 118000 && UpdateClient.retryRemainingMillis() <= 120000, "API reset translated once to monotonic deadline");

        Fixture redirect = new Fixture(302, new byte[0]); redirect.location = "https://release-assets.githubusercontent.com/download?sig=private-fixture";
        Fixture cdnLimit = new Fixture(429, new byte[0]); cdnLimit.retryAfter = "90";
        reset(redirect, cdnLimit); fails(release(APK.length, hash(APK)), null, 2000);
        check(opened.size() == 2 && opened.get(0).closed && opened.get(1).closed, "CDN limit closes original redirect and final response");
        check(UpdateClient.retryRemainingMillis() > 89000 && UpdateClient.retryRemainingMillis() <= 90000, "trusted CDN retry hint is retained");

        Fixture concurrent = new Fixture(302, new byte[0]); concurrent.location = redirect.location;
        concurrent.afterHeaders = () -> setRetryDeadline(SystemClock.elapsedRealtimeNanos() + TimeUnit.MINUTES.toNanos(1));
        reset(concurrent); fails(release(APK.length, hash(APK)), null, 2000);
        check(opened.size() == 1, "each redirect hop rechecks a concurrently installed wait before opening");

        Fixture cancelled = new Fixture(429, new byte[0]); cancelled.retryAfter = "86400";
        reset(cancelled);
        try (UpdateClient.Operation operation = new UpdateClient.Operation(2000)) {
            cancelled.afterHeaders = operation::cancel;
            try { UpdateClient.check(operation); throw new AssertionError("cancelled response accepted"); }
            catch (IOException expected) { check(!(expected instanceof UpdateClient.RateLimitedException), "cancelled response does not become rate limit"); }
        }
        check(UpdateClient.retryRemainingMillis() == 0 && opened.get(0).closed, "cancelled response cannot write process cooldown");
        Fixture cancelledHint = new Fixture(429, new byte[0]); cancelledHint.retryAfter = "86400";
        reset(cancelledHint);
        try (UpdateClient.Operation operation = new UpdateClient.Operation(2000)) {
            cancelledHint.onRetryHeader = operation::cancel;
            try { UpdateClient.download(context, release(APK.length, hash(APK)), operation, null); throw new AssertionError("cancelled hint accepted"); }
            catch (IOException expected) { check(!(expected instanceof UpdateClient.RateLimitedException), "cancellation while reading headers takes precedence"); }
        }
        check(UpdateClient.retryRemainingMillis() == 0 && opened.get(0).closed, "header cancellation leaves no cooldown and closes connection"); empty();
        reset();
    }
    private static void fails(UpdateClient.Release release, UpdateClient.Progress progress, long budget) throws Exception {
        boolean rejected = false;
        try (UpdateClient.Operation operation = new UpdateClient.Operation(budget)) {
            try { UpdateClient.download(context, release, operation, progress); } catch (IOException expected) { rejected = true; }
        }
        check(rejected, "unsafe or incomplete download rejected");
        for (FakeHttps connection : opened) check(connection.closed, "failed connection disconnected");
        empty();
    }
    private static void empty() {
        File[] files = new File(root, "updates").listFiles(); check(files == null || files.length == 0, "partial and rejected packages removed");
    }
    private static final class Fixture {
        final int status; final byte[] body; long announced; String location, encoding, retryAfter, remaining, reset; boolean stall;
        Runnable afterHeaders, onRetryHeader;
        Fixture(int status, byte[] body) { this.status = status; this.body = body; announced = body.length; }
    }
    private static final class NoNetworkSocketFactory extends javax.net.ssl.SSLSocketFactory {
        public String[] getDefaultCipherSuites() { return new String[0]; }
        public String[] getSupportedCipherSuites() { return new String[0]; }
        private java.net.Socket denied() { throw new AssertionError("Synthetic update transport cannot open sockets"); }
        public java.net.Socket createSocket(java.net.Socket socket, String host, int port, boolean close) { return denied(); }
        public java.net.Socket createSocket(String host, int port) { return denied(); }
        public java.net.Socket createSocket(String host, int port, java.net.InetAddress local, int localPort) { return denied(); }
        public java.net.Socket createSocket(java.net.InetAddress host, int port) { return denied(); }
        public java.net.Socket createSocket(java.net.InetAddress host, int port, java.net.InetAddress local, int localPort) { return denied(); }
    }
    private static final class FakeHttps extends HttpsURLConnection {
        final Fixture fixture; final CountDownLatch disconnected = new CountDownLatch(1); volatile boolean closed;
        FakeHttps(java.net.URL url, Fixture fixture) { super(url); this.fixture = fixture; }
        public void connect() { }
        public void disconnect() { closed = true; disconnected.countDown(); }
        public boolean usingProxy() { return false; }
        public String getCipherSuite() { return "synthetic"; }
        public Certificate[] getLocalCertificates() { return null; }
        public Certificate[] getServerCertificates() { return new Certificate[0]; }
        public int getResponseCode() throws IOException {
            if (fixture.stall) {
                try { if (!disconnected.await(2, TimeUnit.SECONDS)) throw new AssertionError("Deadline did not cancel I/O"); }
                catch (InterruptedException stopped) { throw new IOException("interrupted"); }
                throw new IOException("disconnected");
            }
            if (fixture.afterHeaders != null) fixture.afterHeaders.run();
            return fixture.status;
        }
        public long getContentLengthLong() { return fixture.announced; }
        public String getContentEncoding() { return fixture.encoding; }
        public String getHeaderField(String name) {
            if ("Location".equals(name)) return fixture.location;
            if ("Retry-After".equals(name)) { if (fixture.onRetryHeader != null) fixture.onRetryHeader.run(); return fixture.retryAfter; }
            if ("x-ratelimit-remaining".equals(name)) return fixture.remaining;
            if ("x-ratelimit-reset".equals(name)) return fixture.reset;
            return null;
        }
        public InputStream getInputStream() { return new ByteArrayInputStream(fixture.body); }
    }
}
