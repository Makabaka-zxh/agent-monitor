package com.agentmonitor.live;

import android.content.Context;
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
        System.out.println("UpdateClientTest: " + checks + " checks passed");
    }
    private static String hash(byte[] value) throws Exception { return UpdateClient.hex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static UpdateClient.Release release(long size, String digest) {
        return new UpdateClient.Release(new UpdatePolicy.Manifest(1, UpdatePolicy.PACKAGE_NAME, "1.0.1", 30, 26, NAME, digest, size), TAG, URL, "Synthetic notes");
    }
    private static void reset(Fixture... values) { fixtures.clear(); opened.clear(); for (Fixture value : values) fixtures.add(value); }
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
        final int status; final byte[] body; long announced; String location, encoding; boolean stall;
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
            return fixture.status;
        }
        public long getContentLengthLong() { return fixture.announced; }
        public String getContentEncoding() { return fixture.encoding; }
        public String getHeaderField(String name) { return "Location".equals(name) ? fixture.location : null; }
        public InputStream getInputStream() { return new ByteArrayInputStream(fixture.body); }
    }
}
