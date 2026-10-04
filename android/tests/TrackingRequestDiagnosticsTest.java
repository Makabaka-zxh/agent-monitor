package com.agentmonitor.live;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Production file writer: offline, no Android device, no request payload fixtures persisted. */
public final class TrackingRequestDiagnosticsTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static String content(File dir) throws Exception { return new String(Files.readAllBytes(new File(dir, TrackingRequestDiagnostics.FILE_NAME).toPath()), StandardCharsets.UTF_8); }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("monitor-tracking-diagnostics-test-").toFile();
        try {
            TrackingRequestDiagnostics.record(false, dir, 1, 2, 200, true, "decode", "none");
            check(dir.list().length == 0, "release build gate does not write diagnostics");
            for (int i = 0; i < 60; i++) TrackingRequestDiagnostics.record(true, dir, 1000 + i, i, 200, true, "decode", "none");
            List<String> records = TrackingRequestDiagnostics.read(new File(dir, TrackingRequestDiagnostics.FILE_NAME));
            check(records.size() == 24, "ring retains only last 24 completed attempts");
            check(records.get(0).contains("\"time\":1036,"), "oldest retained sample is bounded correctly");
            check(records.get(23).contains("\"time\":1059,"), "latest completion survives disk reload");
            String secret = "https://private.example/?task=private-task&token=private-bearer\nprivate-prompt";
            TrackingRequestDiagnostics.record(true, dir, 9999, 42, 429, false, secret, secret);
            String serialized = content(dir);
            check(!serialized.contains("private") && !serialized.contains("http://") && !serialized.contains("https://"), "arbitrary stage/reason strings cannot leak identities, URL or payload");
            check(serialized.contains("\"stage\":\"unknown\",\"failure\":\"unclassified\""), "unknown details collapse to fixed codes");
            check(serialized.contains("\"status\":429,\"success\":false"), "HTTP error status and success are retained");
            for (String reason : new String[]{"deadline", "io_timeout", "dns", "tls", "io", "invalid_response", "http", "response_rejected", "snapshot_shape"}) {
                TrackingRequestDiagnostics.record(true, dir, 10000, 25, 0, false, "headers", reason);
                check(content(dir).contains("\"failure\":\"" + reason + "\""), "bounded failure category retained: " + reason);
            }
            TrackingRequestDiagnostics.record(true, dir, -9, -8, 123456789, false, "body", "io");
            check(content(dir).contains("\"time\":0,\"duration_ms\":0,\"status\":0"), "unexpected numbers cannot become identity-like fields");
            File file = new File(dir, TrackingRequestDiagnostics.FILE_NAME);
            Files.write(file.toPath(), ("{\"private-token\":\"" + secret + "\"}").getBytes(StandardCharsets.UTF_8));
            TrackingRequestDiagnostics.record(true, dir, 20000, 3, 200, true, "decode", "none");
            check(TrackingRequestDiagnostics.read(file).size() == 1 && !content(dir).contains("private"), "malformed preexisting content is discarded, never copied forward");
            Files.write(file.toPath(), new byte[TrackingRequestDiagnostics.MAX_BYTES + 1]);
            TrackingRequestDiagnostics.record(true, dir, 21000, 4, 503, false, "headers", "http");
            check(TrackingRequestDiagnostics.read(file).size() == 1, "oversized file cannot grow or consume unbounded memory");
            List<Thread> writers = new ArrayList<>(); CountDownLatch start = new CountDownLatch(1);
            for (int thread = 0; thread < 4; thread++) {
                final int offset = thread * 20;
                Thread writer = new Thread(() -> { try { start.await(); for (int i = 0; i < 20; i++) TrackingRequestDiagnostics.record(true, dir, 30000 + offset + i, 5, 200, true, "decode", "none"); } catch (Exception failure) { throw new RuntimeException(failure); } });
                writers.add(writer); writer.start();
            }
            start.countDown(); for (Thread writer : writers) writer.join();
            check(TrackingRequestDiagnostics.read(file).size() == 24, "concurrent app workers still produce a valid bounded ring");
            check(file.length() <= TrackingRequestDiagnostics.MAX_BYTES, "persistent file remains below hard bound");
            check(dir.list().length == 1, "temporary files do not accumulate");
            TrackingRequestDiagnostics.record(true, file, 1, 1, 200, true, "decode", "none");
            check(file.isFile(), "unavailable destination is nonfatal and preserves prior evidence");
            System.out.println("TrackingRequestDiagnosticsTest: " + checks + " checks passed");
        } finally { for (File file : dir.listFiles()) file.delete(); dir.delete(); }
    }
}
