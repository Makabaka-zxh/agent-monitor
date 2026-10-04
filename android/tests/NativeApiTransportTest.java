package com.agentmonitor.live;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Exercises production I/O, deadlines, cleanup and diagnostics without any network. */
public final class NativeApiTransportTest {
    private static final String TOKEN = "synthetic-private-transport-token";
    private static final String PAYLOAD = "{\"message\":\"synthetic-private-prompt\"}";
    private static final String TASK = "synthetic-private-task";
    // Wide enough for host scheduling while still far below production's I/O timeout.
    private static final int STALL_BUDGET_MS = 500, TRICKLE_BUDGET_MS = 600;
    private static final List<FakeHttps> opened = Collections.synchronizedList(new ArrayList<>());
    private static volatile Fixture next;
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        // Avoid initializing the JVM's real TLS entropy source for synthetic connections.
        // Every socket operation fails closed; this isolated test JVM cannot open TLS.
        HttpsURLConnection.setDefaultSSLSocketFactory(new NoNetworkSocketFactory());
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            protected URLConnection openConnection(URL url) throws IOException {
                Fixture fixture = next;
                if (fixture == null) throw new AssertionError("Unexpected request; no real transport is allowed");
                delay(fixture.prepareDelayMs);
                FakeHttps connection = new FakeHttps(url, fixture); opened.add(connection); return connection;
            }
        } : null);
        // These synthetic I/O budgets must exercise a stalled connection,
        // not the one-time host OS random-provider initialization used by UUID4.
        java.util.UUID.randomUUID();
        // The controlled success also initializes the synthetic connection/decoder classes
        // before short I/O deadline cases. This is test setup, never production prewarming.
        run("separate phase intervals", NativeApiTransportTest::phasesMeasureControlledWork);
        run("header deadline", NativeApiTransportTest::headersStall);
        run("trickle budget", NativeApiTransportTest::bodyTrickle);
        run("successful cleanup", NativeApiTransportTest::successCancelsDeadline);
        run("empty response", NativeApiTransportTest::emptyResponse);
        run("HTTP failures", NativeApiTransportTest::httpErrorsCloseWithoutRetry);
        run("POST deadline", NativeApiTransportTest::postNeverRetries);
        run("write deadline", NativeApiTransportTest::requestBodyDeadline);
        run("invalid response", NativeApiTransportTest::invalidResponse);
        run("diagnostic privacy", NativeApiTransportTest::diagnosticsAreSanitized);
        run("anonymous request correlation", NativeApiTransportTest::tracesAreScoped);
        run("failed preparation interval", NativeApiTransportTest::failedPreparationIsMeasured);
        run("phase failure attribution", NativeApiTransportTest::phaseFailuresAreMeasured);
        run("empty response phases", NativeApiTransportTest::emptyResponsePhases);
        run("persistent snapshot timing", NativeApiTransportTest::snapshotTimingIsScoped);
        run("TLS cause categories", NativeApiTransportTest::tlsFailureCategories);
        run("I/O cause categories", NativeApiTransportTest::ioFailureCategories);
        run("bounded diagnostic causes", NativeApiTransportTest::failureCausesAreBounded);
        run("failure diagnostic scope", NativeApiTransportTest::failureDiagnosticsAreScoped);
        run("failure diagnostic isolation", NativeApiTransportTest::failureDiagnosticsCannotReplaceFailure);
        System.out.println("NativeApiTransportTest: " + checks + " checks passed");
    }
    private interface Checked { void run() throws Exception; }
    private static void run(String name, Checked action) throws Exception {
        FutureTask<Void> work = new FutureTask<>(() -> { action.run(); return null; });
        Thread thread = new Thread(work, "SyntheticTransportTest"); thread.setDaemon(true); thread.start();
        try { work.get(4, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException failure) { throw new AssertionError(name, failure.getCause()); }
        catch (java.util.concurrent.TimeoutException failure) {
            for (StackTraceElement frame : thread.getStackTrace()) System.err.println("  synthetic test at " + frame);
            synchronized (opened) { for (FakeHttps connection : opened) connection.disconnect(); }
            work.cancel(true); throw new AssertionError(name + " exceeded the test watchdog", failure);
        }
    }
    private static void setup(Fixture fixture) { next = fixture; opened.clear(); android.util.Log.clear(); }
    private static FakeHttps only() { check(opened.size() == 1, "one transport attempt only"); return opened.get(0); }
    private static NativeApi.Reply call(String method, String path, JSONObject body, long budget) throws NativeApi.Failure {
        return NativeApi.callReply(method, path, body, TOKEN, budget, source -> null);
    }
    private static NativeApi.Failure failure(String method, String path, JSONObject body, long budget) throws Exception {
        try { call(method, path, body, budget); throw new AssertionError("Request unexpectedly succeeded"); }
        catch (NativeApi.Failure expected) { return expected; }
    }
    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private static void delay(long milliseconds) throws IOException {
        if (milliseconds <= 0) return;
        try { new CountDownLatch(1).await(milliseconds, TimeUnit.MILLISECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("synthetic delay interrupted"); }
    }
    /** Checks both schemas so phase diagnostics cannot silently break old anchored parsers. */
    private static long[] phases() {
        String uuid = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
        String oldSchema = "MonitorTiming op=(workbench|live_snapshot|usage) status=(\\d+) headers_ms=(-?\\d+) total_ms=(\\d+) bytes=(\\d+) complete=(true|false) stage=([a-z_]+) failure=([a-z_]+) trace=(" + uuid + ")";
        String phaseSchema = "MonitorPhases op=(workbench|live_snapshot|usage) trace=(" + uuid + ") prepare_ms=(-?\\d+) headers_wait_ms=(-?\\d+) body_ms=(-?\\d+) decode_ms=(-?\\d+)";
        java.util.regex.Matcher original = null, intervals = null;
        int oldCount = 0, newCount = 0;
        for (String line : android.util.Log.recorded().split("\\n")) {
            if (line.startsWith("MonitorTiming ")) {
                oldCount++; original = java.util.regex.Pattern.compile(oldSchema).matcher(line);
                check(original.matches(), "original MonitorTiming retains its complete anchored schema");
            } else if (line.startsWith("MonitorPhases ")) {
                newCount++; intervals = java.util.regex.Pattern.compile(phaseSchema).matcher(line);
                check(intervals.matches(), "phase log accepts only the fixed anonymous schema");
            }
        }
        check(oldCount == 1 && newCount == 1, "each eligible request emits one old timing and one phase timing");
        check(original.group(1).equals(intervals.group(1)) && original.group(9).equals(intervals.group(2)), "phase timing reuses the same operation and trace");
        long[] result = new long[6];
        for (int i = 0; i < 4; i++) result[i] = Long.parseLong(intervals.group(i + 3));
        result[4] = Long.parseLong(original.group(3)); result[5] = Long.parseLong(original.group(4));
        long sum = 0;
        for (int i = 0; i < 4; i++) {
            check(result[i] >= -1, "phase is either unentered or a nonnegative duration");
            if (result[i] >= 0) sum += result[i];
        }
        check(sum <= result[5], "separate phases do not double count time or include post-timing diagnostics");
        if (result[4] >= 0 && result[1] >= 0)
            check(result[0] + result[1] <= result[4], "original headers_ms remains cumulative preparation plus header wait");
        for (String privateValue : new String[]{TOKEN, TASK, PAYLOAD, NativeApi.ORIGIN, "synthetic-private-cookie", "synthetic-private-server-body", "Authorization", "task_id"})
            check(!android.util.Log.recorded().contains(privateValue), "phase timing never logs request, response or credential content");
        return result;
    }
    private static void headersStall() throws Exception {
        Fixture fixture = new Fixture(); fixture.stallHeaders = true; setup(fixture);
        long started = System.nanoTime(); NativeApi.Failure result = failure("GET", "/api/native/workbench", null, STALL_BUDGET_MS);
        check(elapsed(started) < 2000, "budget interrupts headers rather than waiting for the 10-second I/O timeout");
        check(result.status == 0 && result.getMessage().contains("超时"), "deadline reported without an HTTP status");
        FakeHttps connection = only(); check(connection.disconnected.getCount() == 0, "header stall aborted by disconnect");
        check(connection.responseCalls == 1, "deadline check exercises response I/O rather than failing during preparation");
        check(connection.body.reads == 0, "header timeout does not read the body");
        check(android.util.Log.recorded().contains("stage=headers failure=deadline"), "header deadline is distinguishable in sanitized timing: " + android.util.Log.recorded());
    }
    private static void bodyTrickle() throws Exception {
        Fixture fixture = new Fixture(); fixture.trickleMs = 18; setup(fixture);
        long started = System.nanoTime(); NativeApi.Failure result = failure("GET", "/api/native/workbench", null, TRICKLE_BUDGET_MS);
        FakeHttps connection = only();
        check(result.status == 0 && elapsed(started) < 2000, "continuous small reads cannot reset the overall budget");
        check(connection.body.reads > 1 && connection.body.reads < 100, "several chunks arrive before the overall deadline");
        check(connection.body.closed, "trickling body closes after timeout");
        check(android.util.Log.recorded().contains("stage=body failure=deadline"), "body budget failure is identifiable");
    }
    private static void successCancelsDeadline() throws Exception {
        Fixture fixture = new Fixture(); fixture.bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8); setup(fixture);
        final String[] decoded = {null};
        NativeApi.Reply result = NativeApi.callReply("GET", "/api/native/workbench", null, TOKEN, 250, source -> { decoded[0] = source; return null; });
        FakeHttps connection = only();
        check(result != null && "{\"ok\":true}".equals(decoded[0]), "complete response reaches the injected decoder exactly once");
        check(connection.body.closed && connection.disconnects.get() == 1, "success closes its stream and connection");
        check(connection.getConnectTimeout() == NativeApi.TIMEOUT_MS && connection.getReadTimeout() == NativeApi.TIMEOUT_MS, "per-operation I/O limits remain configured");
        check(!connection.getInstanceFollowRedirects() && !connection.getUseCaches(), "redirect and cache policy retained");
        check(("Bearer " + TOKEN).equals(connection.getRequestProperty("Authorization")), "credential sent only in the request header");
        check(result.cookies.size() == 1, "response cookie handling remains intact");
        Thread.sleep(330);
        check(connection.disconnects.get() == 1, "cancelled success deadline never disconnects again later");
        check(android.util.Log.recorded().contains("complete=true stage=decode failure=none"), "success has final safe timing");
    }
    private static void emptyResponse() throws Exception {
        Fixture fixture = new Fixture(); fixture.status = 204; setup(fixture);
        final String[] decoded = {null};
        NativeApi.callReply("GET", "/api/native/workbench", null, TOKEN, 150, source -> { decoded[0] = source; return null; });
        FakeHttps connection = only();
        check("{}".equals(decoded[0]) && connection.inputOpens == 0, "204 decodes an empty object without reading a body");
        Thread.sleep(210); check(connection.disconnects.get() == 1, "204 also cancels its deadline");
    }
    private static void httpErrorsCloseWithoutRetry() throws Exception {
        for (int code : new int[]{401, 403, 429, 500, 503}) {
            Fixture fixture = new Fixture(); fixture.status = code; setup(fixture);
            NativeApi.Failure result = failure("GET", "/api/native/workbench", null, 500);
            FakeHttps connection = only();
            check(result.status == code, "HTTP status preserved for caller policy");
            check(connection.error.closed && connection.error.reads == 0, "error body is closed without parsing or unbounded draining");
            check(connection.inputOpens == 0, "HTTP error never opens a success body");
            check(connection.disconnects.get() == 1, "HTTP error disconnects once");
            check(android.util.Log.recorded().contains("failure=http"), "HTTP error has a bounded diagnostic label");
            if (code == 429) check(result.retryAfterMs == 2000, "server retry-after passed through without automatic retry");
        }
        Fixture redirect = new Fixture(); redirect.status = 302; setup(redirect);
        check(failure("GET", "/api/native/workbench", null, 500).status == 302, "redirect is rejected");
        check(only().inputOpens == 0, "redirect does not follow or fetch a body");
        Fixture throwingClose = new Fixture(); throwingClose.status = 500; throwingClose.closeErrorThrows = true; setup(throwingClose);
        check(failure("GET", "/api/native/workbench", null, 500).status == 500, "cleanup exception does not replace the HTTP failure");
        check(only().disconnects.get() == 1, "connection still closes when the error stream close throws");
    }
    private static void postNeverRetries() throws Exception {
        Fixture fixture = new Fixture(); fixture.stallHeaders = true; setup(fixture);
        check(failure("POST", "/api/native/tasks/reply", new JSONObject(PAYLOAD), STALL_BUDGET_MS).status == 0, "POST can fail after its body was already sent");
        FakeHttps connection = only();
        check("POST".equals(connection.getRequestMethod()), "write uses the original method");
        check(PAYLOAD.equals(new String(connection.sent.toByteArray(), StandardCharsets.UTF_8)), "POST body emitted exactly once");
        check(connection.outputOpens == 1 && connection.outputClosed, "POST stream closes before response timeout");
        Thread.sleep(180); check(opened.size() == 1, "no delayed POST retry after timeout");
        check(android.util.Log.recorded().contains("op=reply_post"), "write timing keeps only the operation category");
    }
    private static void requestBodyDeadline() throws Exception {
        Fixture fixture = new Fixture(); fixture.stallWrite = true; setup(fixture);
        check(failure("POST", "/api/native/tasks/reply", new JSONObject(PAYLOAD), STALL_BUDGET_MS).status == 0, "stalled request body reaches its budget");
        FakeHttps connection = only();
        check(connection.outputClosed && connection.responseCalls == 0, "stalled output closes without reading response headers");
        check(android.util.Log.recorded().contains("stage=request_body failure=deadline"), "request-body stall has its own diagnostic stage");
    }
    private static void invalidResponse() throws Exception {
        setup(new Fixture());
        try {
            NativeApi.callReply("GET", "/api/native/workbench", null, TOKEN, 150, source -> { throw new Exception("synthetic-private-server-body"); });
            throw new AssertionError("Decoder failure unexpectedly succeeded");
        } catch (NativeApi.Failure failure) { check(failure.status == 0, "decoder errors become a transport-level failure"); }
        FakeHttps connection = only(); check(connection.body.closed, "body closes before decoder failure");
        Thread.sleep(210); check(connection.disconnects.get() == 1, "failure cancels the pending deadline too");
        check(android.util.Log.recorded().contains("failure=invalid_response"), "decoder failure remains recognizable without exception text");
    }
    private static void diagnosticsAreSanitized() throws Exception {
        Fixture fixture = new Fixture(); fixture.status = 503; setup(fixture);
        failure("GET", NativeApi.taskPath("result", TASK), null, 500);
        String log = android.util.Log.recorded();
        check(log.contains("op=result status=503"), "diagnostics retain useful aggregate operation and status");
        for (String sensitive : new String[]{TOKEN, TASK, PAYLOAD, "synthetic-private-server-body", "synthetic-private-cookie", NativeApi.ORIGIN, "Authorization", "task_id"})
            check(!log.contains(sensitive), "credentials, queries, cookies and response content absent from logs");
        setup(new Fixture());
        call("GET", "/api/native/account", null, 500);
        check(android.util.Log.recorded().isEmpty(), "unlisted account endpoint has no transport timing output");
    }
    private static void snapshotTimingIsScoped() throws Exception {
        Fixture fixture = new Fixture(); fixture.status = 503; setup(fixture);
        long before = System.currentTimeMillis(); failure("GET", "/api/native/snapshot", null, 500);
        NativeApi.SnapshotTiming failed = NativeApi.takeSnapshotTiming();
        check(failed != null && failed.time >= before && failed.durationMs >= 0, "completed snapshot retains anonymous timestamp and duration independently of logcat");
        check(failed.status == 503 && !failed.success && "headers".equals(failed.stage) && "http".equals(failed.failure), "actual transport status and fixed category retained");
        check(NativeApi.takeSnapshotTiming() == null, "timing is consumed once so a later attempt cannot reuse it");
        setup(new Fixture()); call("GET", "/api/native/snapshot", null, 500);
        NativeApi.SnapshotTiming success = NativeApi.takeSnapshotTiming();
        check(success != null && success.success && success.status == 200 && "none".equals(success.failure), "successful snapshot is separately recorded");
        setup(new Fixture()); call("GET", "/api/native/snapshot", null, 500);
        final NativeApi.SnapshotTiming[] other = new NativeApi.SnapshotTiming[1];
        Thread thread = new Thread(() -> other[0] = NativeApi.takeSnapshotTiming()); thread.start(); thread.join();
        check(other[0] == null, "another worker cannot consume this snapshot's evidence");
        call("GET", "/api/native/workbench", null, 500);
        check(NativeApi.takeSnapshotTiming() == null, "a non-snapshot request clears prior thread-local timing");
        for (java.lang.reflect.Field field : NativeApi.SnapshotTiming.class.getDeclaredFields()) {
            check(Arrays.asList("time", "durationMs", "status", "success", "stage", "failure").contains(field.getName()), "immutable timing schema has no payload/URL/identity field");
            check(java.lang.reflect.Modifier.isFinal(field.getModifiers()), "snapshot timing cannot be modified after completion");
        }
    }

    private static void tracesAreScoped() throws Exception {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String path : new String[]{"/api/native/workbench", "/api/native/snapshot", "/api/native/usage"}) {
            for (int attempt = 0; attempt < 2; attempt++) {
                Fixture fixture = new Fixture(); fixture.status = attempt == 0 ? 200 : 503; setup(fixture);
                if (fixture.status == 200) call("GET", path, null, 500);
                else failure("GET", path, null, 500);
                FakeHttps connection = only(); String trace = connection.getRequestProperty("X-Monitor-Trace");
                check(trace != null && trace.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), "trace uses an exact random UUID4 schema");
                check(seen.add(trace), "each independent request has a new trace");
                check((NativeApi.ORIGIN + path).equals(connection.getURL().toString()), "trace is sent only to the configured hub");
                String log = android.util.Log.recorded();
                check(log.contains("trace=" + trace), "success and HTTP failure can be matched to server evidence");
                long[] measured = phases();
                check(measured[0] >= 0 && measured[1] >= 0, "eligible success and error reads retain preparation and headers intervals");
                if (fixture.status == 503) check(measured[2] == -1 && measured[3] == -1, "HTTP errors never claim body or JSON work");
                for (String sensitive : new String[]{TOKEN, TASK, PAYLOAD, "synthetic-private-cookie", NativeApi.ORIGIN, path})
                    check(!log.contains(sensitive), "correlation does not add user data to diagnostics");
            }
        }
        for (String path : new String[]{"/api/native/account", "/api/native/session",
                "/api/native/computers/pairing/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                NativeApi.taskPath("result", TASK), NativeApi.taskPath("reply", TASK)}) {
            setup(new Fixture()); call("GET", path, null, 500);
            check(only().getRequestProperty("X-Monitor-Trace") == null, "account, pairing and task reads are not traced");
            check(!android.util.Log.recorded().contains("trace="), "unselected routes have no trace in logs");
            check(!android.util.Log.recorded().contains("MonitorPhases"), "unselected reads have no phase diagnostics");
        }
        setup(new Fixture()); call("POST", "/api/native/tasks/reply", new JSONObject(PAYLOAD), 500);
        check(only().getRequestProperty("X-Monitor-Trace") == null, "reply writes remain untraced and single-attempt");
        check(!android.util.Log.recorded().contains("MonitorPhases"), "reply writes retain the original timing only");
        Fixture stalled = new Fixture(); stalled.stallHeaders = true; setup(stalled);
        failure("GET", "/api/native/workbench", null, STALL_BUDGET_MS);
        String stalledTrace = only().getRequestProperty("X-Monitor-Trace");
        check(android.util.Log.recorded().contains("failure=deadline trace=" + stalledTrace), "no-response timeout retains the outgoing trace");
    }

    private static void phasesMeasureControlledWork() throws Exception {
        Fixture fixture = new Fixture();
        fixture.prepareDelayMs = 60; fixture.headersDelayMs = 90; fixture.bodyDelayMs = 120;
        // Cookie inspection is intentionally separate from JSON decoding.
        fixture.cookiesDelayMs = 100;
        setup(fixture);
        NativeApi.callReply("GET", "/api/native/workbench", null, TOKEN, 2000, source -> { delay(150); return null; });
        long[] measured = phases();
        check(measured[0] >= 60, "local connection preparation including UUID precedes the header-wait boundary");
        check(measured[1] >= 90, "getResponseCode waiting is timed on its own");
        check(measured[2] >= 120, "body reading has its own controlled interval");
        check(measured[3] >= 150, "decoder work has its own controlled interval");
        check(measured[5] - (measured[0] + measured[1] + measured[2] + measured[3]) >= 100,
                "cookie inspection and cleanup are not mislabeled as JSON decoding");
        FakeHttps connection = only();
        check(connection.connectCalls == 0 && connection.responseCalls == 1 && connection.inputOpens == 1,
                "measurement adds no eager connection, retries or duplicate body reads");
        check(("Bearer " + TOKEN).equals(connection.getRequestProperty("Authorization")), "phase instrumentation retains authentication");
    }

    private static void failedPreparationIsMeasured() throws Exception {
        Fixture fixture = new Fixture(); fixture.traceHeaderDelayMs = 80; fixture.rejectTraceHeader = true; setup(fixture);
        check(failure("GET", "/api/native/workbench", null, 500).status == 0, "preparation failure retains transport failure behavior");
        long[] measured = phases();
        check(measured[0] >= 80 && measured[1] == -1 && measured[2] == -1 && measured[3] == -1,
                "failed preparation is timed before cleanup and later phases are unentered");
        check(only().responseCalls == 0 && only().inputOpens == 0, "preparation failure never starts response I/O");
    }

    private static void phaseFailuresAreMeasured() throws Exception {
        Fixture headers = new Fixture(); headers.stallHeaders = true; setup(headers);
        failure("GET", "/api/native/workbench", null, STALL_BUDGET_MS);
        long[] measured = phases();
        check(measured[0] >= 0 && measured[1] > 0 && measured[2] == -1 && measured[3] == -1,
                "failed response wait is timed and unentered body/decode remain -1");
        check(measured[4] == -1, "failed getResponseCode retains the old unavailable cumulative header value");

        Fixture body = new Fixture(); body.trickleMs = 18; setup(body);
        failure("GET", "/api/native/workbench", null, TRICKLE_BUDGET_MS);
        measured = phases();
        check(measured[1] >= 0 && measured[2] > 0 && measured[3] == -1, "failed body read retains its elapsed interval without a fake decode");

        setup(new Fixture());
        try {
            NativeApi.callReply("GET", "/api/native/workbench", null, TOKEN, 500, source -> { delay(80); throw new IOException("synthetic-private-server-body"); });
            throw new AssertionError("Synthetic decoder should fail");
        } catch (NativeApi.Failure expected) { check(expected.status == 0, "decoder failure keeps existing error semantics"); }
        measured = phases();
        check(measured[2] >= 0 && measured[3] >= 80, "a throwing decoder retains work already spent");
    }

    private static void emptyResponsePhases() throws Exception {
        Fixture fixture = new Fixture(); fixture.status = 204; setup(fixture);
        final String[] decoded = {null};
        NativeApi.callReply("GET", "/api/native/workbench", null, TOKEN, 500, source -> { delay(80); decoded[0] = source; return null; });
        long[] measured = phases();
        check(measured[0] >= 0 && measured[1] >= 0 && measured[2] == -1 && measured[3] >= 80,
                "204 measures empty-object decode but never invents a body phase");
        check("{}".equals(decoded[0]) && only().inputOpens == 0, "204 behavior stays intact");
        check(android.util.Log.recorded().contains("complete=true stage=headers failure=none"), "204 keeps the original timing stage for old consumers");
    }

    private static IOException withCause(IOException outer, Throwable cause) {
        outer.initCause(cause); return outer;
    }
    private static IOException handshake(Throwable cause) {
        return withCause(new javax.net.ssl.SSLHandshakeException("synthetic-private-handshake"), cause);
    }
    private static void assertFailureDiagnostic(String tlsType, String cause) {
        String uuid = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
        String schema = "MonitorTransportFailure op=(workbench|live_snapshot|usage) trace=(" + uuid + ")"
                + " tls_type=(none|handshake|peer_unverified|protocol|other)"
                + " cause=(other|io|socket|connect|no_route|dns|eof|timeout|certificate|cert_path|certificate_expired|certificate_not_yet_valid)";
        int count = 0;
        for (String line : android.util.Log.recorded().split("\\n")) if (line.startsWith("MonitorTransportFailure ")) {
            count++;
            java.util.regex.Matcher match = java.util.regex.Pattern.compile(schema).matcher(line);
            check(match.matches(), "supplemental failure diagnostic contains only the fixed schema");
            check(match.group(2).equals(only().getRequestProperty("X-Monitor-Trace")), "failure diagnostic reuses the outgoing request trace");
            check(match.group(3).equals(tlsType) && match.group(4).equals(cause), "failure diagnostic reports the expected fixed categories");
            check(android.util.Log.recorded().contains("MonitorPhases op=" + match.group(1) + " trace=" + match.group(2)), "failure operation and trace match phase diagnostics");
        }
        check(count == 1, "one supplemental failure record per eligible exception");
        for (String sensitive : new String[]{"synthetic-private", TOKEN, PAYLOAD, TASK, NativeApi.ORIGIN, "Authorization", "task_id", "java.", "javax.", "CauseProbe", "NativeApiTransportTest"})
            check(!android.util.Log.recorded().contains(sensitive), "failure diagnostic excludes messages, class names, stacks and private content");
        phases(); // Existing anchored log schemas and timing semantics are still valid.
    }
    private static void checkHeaderFailure(IOException exception, String legacyKind, String tlsType, String cause) throws Exception {
        Fixture fixture = new Fixture(); fixture.headerFailure = exception; setup(fixture);
        NativeApi.Failure result = failure("GET", "/api/native/workbench", null, 2000);
        check(result.status == 0 && result.getMessage().equals("连接失败，请检查网络和个人服务"), "supplemental categories do not change caller-visible failure");
        check(android.util.Log.recorded().contains("stage=headers failure=" + legacyKind + " trace="), "legacy outer-exception classification is unchanged");
        FakeHttps connection = only();
        check(connection.responseCalls == 1 && connection.connectCalls == 0 && connection.inputOpens == 0, "failure diagnostics add no requests, eager connect or body reads");
        check(connection.disconnects.get() == 1, "failed transport still disconnects exactly once");
        check(connection.getConnectTimeout() == 10000 && connection.getReadTimeout() == 10000, "failure diagnostics preserve transport timeouts");
        assertFailureDiagnostic(tlsType, cause);
    }
    private static void tlsFailureCategories() throws Exception {
        checkHeaderFailure(handshake(new java.net.SocketTimeoutException("synthetic-private-timeout")), "tls", "handshake", "timeout");
        checkHeaderFailure(handshake(new java.security.cert.CertificateExpiredException("synthetic-private-certificate")), "tls", "handshake", "certificate_expired");
        checkHeaderFailure(handshake(new java.security.cert.CertificateNotYetValidException("synthetic-private-certificate")), "tls", "handshake", "certificate_not_yet_valid");
        checkHeaderFailure(handshake(new java.security.cert.CertPathValidatorException("synthetic-private-path")), "tls", "handshake", "cert_path");
        checkHeaderFailure(handshake(new java.security.cert.CertPathBuilderException("synthetic-private-path")), "tls", "handshake", "cert_path");
        checkHeaderFailure(handshake(new java.security.cert.CertificateException("synthetic-private-certificate")), "tls", "handshake", "certificate");
        java.security.cert.CertPathValidatorException path = new java.security.cert.CertPathValidatorException("synthetic-private-path", new java.security.cert.CertificateExpiredException("synthetic-private-certificate"));
        checkHeaderFailure(handshake(path), "tls", "handshake", "certificate_expired");
        checkHeaderFailure(new javax.net.ssl.SSLProtocolException("synthetic-private-protocol"), "tls", "protocol", "other");
        checkHeaderFailure(new javax.net.ssl.SSLPeerUnverifiedException("synthetic-private-peer"), "tls", "peer_unverified", "other");
        checkHeaderFailure(new javax.net.ssl.SSLException("synthetic-private-tls"), "tls", "other", "other");
        checkHeaderFailure(withCause(new javax.net.ssl.SSLException("synthetic-private-wrapper"), handshake(new java.io.EOFException("synthetic-private-eof"))), "tls", "handshake", "eof");
        checkHeaderFailure(new IOException("synthetic-private-wrapper", handshake(new java.net.SocketTimeoutException("synthetic-private-timeout"))), "io", "handshake", "timeout");
    }
    private static void ioFailureCategories() throws Exception {
        checkHeaderFailure(new IOException("synthetic-private-io"), "io", "none", "io");
        checkHeaderFailure(new java.net.SocketException("synthetic-private-socket"), "io", "none", "socket");
        checkHeaderFailure(new java.net.ConnectException("synthetic-private-connect"), "io", "none", "connect");
        checkHeaderFailure(new java.net.NoRouteToHostException("synthetic-private-route"), "io", "none", "no_route");
        checkHeaderFailure(new java.net.UnknownHostException("synthetic-private-host"), "dns", "none", "dns");
        checkHeaderFailure(new java.io.EOFException("synthetic-private-eof"), "io", "none", "eof");
        checkHeaderFailure(new java.net.SocketTimeoutException("synthetic-private-timeout"), "io_timeout", "none", "timeout");
    }
    private static void failureCausesAreBounded() throws Exception {
        CauseProbe first = new CauseProbe(), second = new CauseProbe();
        first.nextCause = second; second.nextCause = first;
        checkHeaderFailure(first, "io", "none", "io");
        check(first.causeReads == 1 && second.causeReads == 1, "identity cycle stops before inspecting an object twice");
        CauseProbe[] chain = new CauseProbe[9];
        for (int i = 0; i < chain.length; i++) chain[i] = new CauseProbe();
        for (int i = 0; i < chain.length - 1; i++) chain[i].nextCause = chain[i + 1];
        chain[8].nextCause = handshake(new java.net.SocketTimeoutException("synthetic-private-deep-timeout"));
        checkHeaderFailure(chain[0], "io", "none", "io");
        for (int i = 0; i < chain.length; i++) check(chain[i].causeReads == (i < 8 ? 1 : 0), "cause traversal has a strict eight-object bound");
        IOException boundary = new java.net.SocketTimeoutException("synthetic-private-boundary-timeout");
        for (int i = 0; i < 7; i++) boundary = new IOException("synthetic-private-wrapper", boundary);
        checkHeaderFailure(boundary, "io", "none", "timeout");
    }
    private static void failureDiagnosticsAreScoped() throws Exception {
        for (String path : new String[]{"/api/native/workbench", "/api/native/snapshot", "/api/native/usage"}) {
            Fixture fixture = new Fixture(); fixture.headerFailure = handshake(new java.net.SocketTimeoutException()); setup(fixture);
            failure("GET", path, null, 2000);
            assertFailureDiagnostic("handshake", "timeout");
        }
        for (String path : new String[]{"/api/native/account", "/api/native/session", NativeApi.taskPath("result", TASK)}) {
            Fixture fixture = new Fixture(); fixture.headerFailure = handshake(new java.net.SocketTimeoutException()); setup(fixture);
            failure("GET", path, null, 2000);
            check(!android.util.Log.recorded().contains("MonitorTransportFailure"), "unselected reads have no supplemental failure record");
        }
        Fixture write = new Fixture(); write.headerFailure = handshake(new java.net.SocketTimeoutException()); setup(write);
        failure("POST", "/api/native/tasks/reply", new JSONObject(PAYLOAD), 2000);
        check(!android.util.Log.recorded().contains("MonitorTransportFailure"), "writes remain outside supplemental failure diagnostics");
        setup(new Fixture()); call("GET", "/api/native/workbench", null, 2000);
        check(!android.util.Log.recorded().contains("MonitorTransportFailure"), "successful response has no failure record");
        Fixture http = new Fixture(); http.status = 503; setup(http);
        failure("GET", "/api/native/workbench", null, 2000);
        check(!android.util.Log.recorded().contains("MonitorTransportFailure"), "HTTP rejection remains in the original diagnostic only");
    }
    private static void failureDiagnosticsCannotReplaceFailure() throws Exception {
        Fixture fixture = new Fixture(); fixture.headerFailure = new IOException("synthetic-private-diagnostic") {
            public synchronized Throwable getCause() { throw new IllegalStateException("synthetic-private-cause-access"); }
        }; setup(fixture);
        NativeApi.Failure result = failure("GET", "/api/native/workbench", null, 2000);
        check(result.status == 0 && result.getMessage().equals("连接失败，请检查网络和个人服务"), "diagnostic traversal failure cannot replace transport failure");
        check(only().disconnects.get() == 1, "cleanup precedes fallible supplemental diagnostics");
        check(!android.util.Log.recorded().contains("MonitorTransportFailure"), "unavailable diagnostics do not fabricate a result");
        phases();
    }
    /** Adversarial cause objects verify identity checks and prohibit accidental formatting. */
    private static final class CauseProbe extends IOException {
        Throwable nextCause; int causeReads;
        public synchronized Throwable getCause() { causeReads++; return nextCause; }
        public String getMessage() { throw new AssertionError("Exception messages must not be inspected"); }
        public String toString() { throw new AssertionError("Exception text must not be formatted"); }
        public boolean equals(Object other) { throw new AssertionError("Cycle checks must use identity"); }
        public int hashCode() { throw new AssertionError("Cycle checks must use identity"); }
    }

    private static final class Fixture {
        int status = 200; boolean stallHeaders, stallWrite, closeErrorThrows; long trickleMs;
        long prepareDelayMs, headersDelayMs, bodyDelayMs, cookiesDelayMs, traceHeaderDelayMs;
        boolean rejectTraceHeader;
        IOException headerFailure;
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    }
    private static final class NoNetworkSocketFactory extends javax.net.ssl.SSLSocketFactory {
        public String[] getDefaultCipherSuites() { return new String[0]; }
        public String[] getSupportedCipherSuites() { return new String[0]; }
        private java.net.Socket denied() { throw new AssertionError("Synthetic transport must never open a socket"); }
        public java.net.Socket createSocket(java.net.Socket socket, String host, int port, boolean autoClose) { return denied(); }
        public java.net.Socket createSocket(String host, int port) { return denied(); }
        public java.net.Socket createSocket(String host, int port, java.net.InetAddress local, int localPort) { return denied(); }
        public java.net.Socket createSocket(java.net.InetAddress host, int port) { return denied(); }
        public java.net.Socket createSocket(java.net.InetAddress host, int port, java.net.InetAddress local, int localPort) { return denied(); }
    }
    private static final class FakeHttps extends HttpsURLConnection {
        final Fixture fixture; final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicInteger disconnects = new AtomicInteger();
        final RecordedInput body, error;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        int inputOpens, outputOpens, responseCalls, connectCalls; volatile boolean outputClosed;
        FakeHttps(URL url, Fixture fixture) { super(url); this.fixture = fixture; body = new RecordedInput(this, false); error = new RecordedInput(this, true); }
        public void disconnect() { disconnects.incrementAndGet(); disconnected.countDown(); }
        public boolean usingProxy() { return false; }
        public void connect() { connectCalls++; }
        public void setRequestProperty(String key, String value) {
            if ("X-Monitor-Trace".equals(key)) {
                try { delay(fixture.traceHeaderDelayMs); }
                catch (IOException failure) { throw new IllegalStateException("synthetic-private-server-body"); }
                if (fixture.rejectTraceHeader) throw new IllegalStateException("synthetic-private-server-body");
            }
            super.setRequestProperty(key, value);
        }
        public int getResponseCode() throws IOException { responseCalls++; delay(fixture.headersDelayMs); if (fixture.stallHeaders) awaitDisconnect(); if (fixture.headerFailure != null) throw fixture.headerFailure; return fixture.status; }
        void awaitDisconnect() throws IOException {
            try { if (!disconnected.await(3, TimeUnit.SECONDS)) throw new AssertionError("Deadline did not interrupt synthetic I/O"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
            throw new IOException("synthetic-private-server-body");
        }
        public InputStream getInputStream() { inputOpens++; return body; }
        public InputStream getErrorStream() { return error; }
        public OutputStream getOutputStream() {
            outputOpens++;
            return new OutputStream() {
                public void write(int value) throws IOException { if (fixture.stallWrite) awaitDisconnect(); sent.write(value); }
                public void write(byte[] bytes, int offset, int count) throws IOException { if (fixture.stallWrite) awaitDisconnect(); sent.write(bytes, offset, count); }
                public void close() { outputClosed = true; }
            };
        }
        public String getHeaderField(String key) { return "Retry-After".equalsIgnoreCase(key) ? "2" : null; }
        public Map<String, List<String>> getHeaderFields() {
            try { delay(fixture.cookiesDelayMs); }
            catch (IOException failure) { throw new IllegalStateException("synthetic-private-cookie"); }
            return Collections.singletonMap("Set-Cookie", Arrays.asList("synthetic-private-cookie"));
        }
        public String getCipherSuite() { return "synthetic"; }
        public java.security.cert.Certificate[] getLocalCertificates() { return null; }
        public java.security.cert.Certificate[] getServerCertificates() { return null; }
        public java.security.Principal getPeerPrincipal() { return null; }
        public java.security.Principal getLocalPrincipal() { return null; }
    }
    private static final class RecordedInput extends InputStream {
        final FakeHttps connection; final boolean error; int offset, reads; volatile boolean closed;
        RecordedInput(FakeHttps connection, boolean error) { this.connection = connection; this.error = error; }
        public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
        public int read(byte[] bytes, int start, int count) throws IOException {
            reads++;
            if (reads == 1 && !error) delay(connection.fixture.bodyDelayMs);
            if (connection.fixture.trickleMs > 0 && !error) {
                try { if (connection.disconnected.await(connection.fixture.trickleMs, TimeUnit.MILLISECONDS)) throw new IOException("disconnected"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
                bytes[start] = ' '; return 1;
            }
            byte[] source = error ? "synthetic-private-server-body".getBytes(StandardCharsets.UTF_8) : connection.fixture.bytes;
            if (offset == source.length) return -1;
            int copied = Math.min(count, source.length - offset); System.arraycopy(source, offset, bytes, start, copied); offset += copied; return copied;
        }
        public void close() throws IOException { closed = true; if (error && connection.fixture.closeErrorThrows) throw new IOException("synthetic-private-close-error"); }
    }
}
