package com.agentmonitor.live;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.net.ssl.HttpsURLConnection;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** All requests stay on the one configured HTTPS origin. No cookie or WebView auth. */
public final class NativeApi {
    public static volatile String ORIGIN = NativeWebPolicy.ORIGIN;
    public static final int TIMEOUT_MS = 10000;
    public static final int JSON_BUDGET_MS = 25000;
    public static final long MAX_DOWNLOAD_BYTES = 32L * 1024 * 1024;
    public interface Cancellation { boolean cancelled(); }
    public static final class Failure extends Exception {
        public final int status;
        public final long retryAfterMs;
        Failure(int status, String message) { this(status, message, 30000); }
        Failure(int status, String message, long retryAfterMs) { super(message); this.status = status; this.retryAfterMs = retryAfterMs; }
    }
    public static final class Reply {
        public final JSONObject body;
        public final java.util.List<String> cookies;
        Reply(JSONObject body, java.util.List<String> cookies) { this.body = body; this.cookies = cookies; }
    }
    private static java.util.List<String> cookies(HttpsURLConnection connection) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet())
            if ("set-cookie".equalsIgnoreCase(entry.getKey())) result.addAll(entry.getValue());
        return result;
    }
    private NativeApi() {}
    /** One immutable anonymous timing per request thread; never retains request arguments. */
    public static final class SnapshotTiming {
        public final long time, durationMs;
        public final int status;
        public final boolean success;
        public final String stage, failure;
        SnapshotTiming(long time, long durationMs, int status, boolean success, String stage, String failure) {
            this.time = time; this.durationMs = durationMs; this.status = status; this.success = success;
            this.stage = stage; this.failure = failure;
        }
    }
    private static final ThreadLocal<SnapshotTiming> SNAPSHOT_TIMING = new ThreadLocal<>();
    public static SnapshotTiming takeSnapshotTiming() {
        SnapshotTiming value = SNAPSHOT_TIMING.get(); SNAPSHOT_TIMING.remove(); return value;
    }
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, action -> {
            Thread thread = new Thread(action, "MonitorRequestDeadline"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
    /** Disconnect cancels established headers/body I/O; system DNS interruption is platform-owned. */
    private static final class RequestDeadline implements AutoCloseable {
        private final HttpsURLConnection connection;
        private final long started, budgetMs;
        private final AtomicInteger state = new AtomicInteger(); // active, expired, finished
        private final ScheduledFuture<?> alarm;
        RequestDeadline(HttpsURLConnection connection, long started, long budgetMs) {
            this.connection = connection; this.started = started; this.budgetMs = budgetMs;
            alarm = DEADLINES.schedule(this::expire, Math.max(0, budgetMs - elapsed(started)), TimeUnit.MILLISECONDS);
        }
        private void expire() { if (state.compareAndSet(0, 1)) disconnectQuietly(connection); }
        boolean expired() { return state.get() == 1; }
        void check() throws IOException {
            if (state.get() == 0 && elapsed(started) >= budgetMs) expire();
            if (expired()) throw new java.net.SocketTimeoutException("Request budget exceeded");
        }
        void finish() throws IOException {
            check();
            if (!state.compareAndSet(0, 2)) throw new java.net.SocketTimeoutException("Request budget exceeded");
            alarm.cancel(false);
        }
        public void close() { state.compareAndSet(0, 2); alarm.cancel(false); }
    }
    private static void disconnectQuietly(HttpsURLConnection connection) {
        if (connection != null) try { connection.disconnect(); } catch (RuntimeException ignored) { }
    }
    private static void closeErrorBody(HttpsURLConnection connection, int status) {
        if (connection == null || status < 400) return;
        // No unbounded drain and no parsing/logging untrusted error bodies. Android's stream
        // close may perform its own bounded discard before returning a socket to the pool.
        try { InputStream errorBody = connection.getErrorStream(); if (errorBody != null) errorBody.close(); }
        catch (IOException | RuntimeException ignored) { }
    }
    /** Method and path are checked together; no encoded path or arbitrary proxy. */
    public static boolean allowedRequest(String method, String path) {
        if (method == null || path == null) return false;
        if ("GET".equals(method)) return path.matches("/api/native/(snapshot|session|workbench|usage|account|profile|computers/pairing/[A-Za-z0-9_-]{32})") || allowedTaskQuery(path);
        if ("PATCH".equals(method)) return path.matches("/api/native/(profile|preferences|tasks/archive)");
        if ("POST".equals(method)) return path.matches("/api/native/(tasks/reply|web-session|pairing/start|pairing/[A-Za-z0-9_-]{32}/poll|computers/pairing|computers/pairing/[A-Za-z0-9_-]{32}/(approve|reject))");
        if ("DELETE".equals(method)) return path.matches("/api/native/(session|connections/[A-Za-z0-9_-]{32}|(computers|sessions)/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})");
        return false;
    }
    /** Only these exact query keys in this order are accepted. Values are identifiers, never URLs. */
    private static boolean allowedTaskQuery(String path) {
        int question = path.indexOf('?'); if (question < 0) return false;
        String route = path.substring(0, question), query = path.substring(question + 1);
        String[] expected;
        if ("/api/native/tasks/result".equals(route)) expected = new String[]{"task_id"};
        else if ("/api/native/tasks/result.txt".equals(route)) expected = new String[]{"task_id", "result_id"};
        else if ("/api/native/tasks/result/file".equals(route)) expected = new String[]{"task_id", "result_id", "file_id"};
        else if ("/api/native/tasks/reply".equals(route)) expected = query.contains("&") ? new String[]{"task_id", "request_id"} : new String[]{"task_id"};
        else return false;
        String[] entries = query.split("&", -1); if (entries.length != expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            String prefix = expected[i] + "="; if (!entries[i].startsWith(prefix)) return false;
            String encoded = entries[i].substring(prefix.length());
            try {
                String value = URLDecoder.decode(encoded, "UTF-8");
                if (value.isEmpty() || value.length() > 1024 || !URLEncoder.encode(value, "UTF-8").equals(encoded)) return false;
                for (int j = 0; j < value.length(); j++) if (Character.isISOControl(value.charAt(j))) return false;
                if (("result_id".equals(expected[i]) || "file_id".equals(expected[i])) && !value.matches("[0-9a-f]{64}")) return false;
                if ("request_id".equals(expected[i]) && !value.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return false;
            } catch (Exception invalid) { return false; }
        }
        return true;
    }
    public static String taskPath(String route, String taskId, String... ids) {
        try {
            StringBuilder path = new StringBuilder("/api/native/tasks/").append(route).append("?task_id=").append(URLEncoder.encode(taskId, "UTF-8"));
            if (ids.length % 2 != 0) return "";
            for (int i = 0; i < ids.length; i += 2) path.append('&').append(ids[i]).append('=').append(URLEncoder.encode(ids[i + 1], "UTF-8"));
            String result = path.toString(); return allowedRequest("GET", result) ? result : "";
        } catch (Exception invalid) { return ""; }
    }
    public static boolean allowedDownload(String path) {
        return path != null && (path.startsWith("/api/native/tasks/result.txt?") || path.startsWith("/api/native/tasks/result/file?")) && allowedTaskQuery(path);
    }
    /** Streaming only to an output chosen by the user; bearer credentials never leave this origin. */
    public static long download(String path, String token, OutputStream destination, long expectedSize, String expectedHash, Cancellation cancellation) throws Failure {
        if (!allowedDownload(path) || !validToken(token) || destination == null || expectedSize < -1 || expectedSize > MAX_DOWNLOAD_BYTES)
            throw new Failure(0, "下载信息无效，请刷新重试");
        if (expectedHash != null && !expectedHash.isEmpty() && !expectedHash.matches("[0-9a-fA-F]{64}")) throw new Failure(0, "文件校验信息无效");
        HttpsURLConnection connection = null;
        final long started = System.nanoTime(); long headersMs = -1, total = 0; int httpStatus = 0; boolean completed = false;
        try {
            if (cancellation != null && cancellation.cancelled()) throw new Failure(0, "下载已取消");
            connection = (HttpsURLConnection) new URL(ORIGIN + path).openConnection();
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false); connection.setConnectTimeout(TIMEOUT_MS); connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("GET"); connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/octet-stream"); connection.setRequestProperty("Accept-Encoding", "identity"); connection.setRequestProperty("Cache-Control", "no-store");
            int code = connection.getResponseCode(); httpStatus = code; headersMs = elapsed(started);
            if (code != 200) throw new Failure(code, code == 401 || code == 403 ? "连接已失效，请重新连接工作台" : code == 404 || code == 409 || code == 410 ? "这份文件已更新或不再可用，请刷新重试" : code >= 300 && code < 400 ? "下载地址发生跳转，已停止" : "下载失败，请稍后重试");
            String encoding = connection.getHeaderField("Content-Encoding");
            if (encoding != null && !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding)) throw new Failure(0, "下载格式无法确认");
            long announced = connection.getContentLengthLong();
            if (announced > MAX_DOWNLOAD_BYTES || expectedSize >= 0 && announced >= 0 && expectedSize != announced) throw new Failure(0, "文件已变化，请刷新重试");
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream input = connection.getInputStream()) {
                byte[] chunk = new byte[32768]; int count;
                while ((count = input.read(chunk)) != -1) {
                    if (Thread.currentThread().isInterrupted() || cancellation != null && cancellation.cancelled()) throw new Failure(0, "下载已取消");
                    if (elapsed(started) > 120000) throw new Failure(0, "下载超时，请稍后重试");
                    total += count;
                    if (total > MAX_DOWNLOAD_BYTES || expectedSize >= 0 && total > expectedSize) throw new Failure(0, "文件大小不符，请刷新重试");
                    destination.write(chunk, 0, count); digest.update(chunk, 0, count);
                }
            }
            if (cancellation != null && cancellation.cancelled()) throw new Failure(0, "下载已取消");
            if (expectedSize >= 0 && total != expectedSize || announced >= 0 && total != announced) throw new Failure(0, "文件下载不完整，请重试");
            StringBuilder actualHash = new StringBuilder(); for (byte b : digest.digest()) actualHash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (expectedHash != null && !expectedHash.isEmpty() && !expectedHash.equalsIgnoreCase(actualHash.toString())) throw new Failure(0, "文件校验未通过，请刷新重试");
            destination.flush(); completed = true; return total;
        } catch (Failure failure) { throw failure; }
        catch (Exception ignored) { throw new Failure(0, "下载失败，请检查网络和保存位置"); }
        finally { closeErrorBody(connection, httpStatus); disconnectQuietly(connection); timing("GET", path, httpStatus, headersMs, elapsed(started), total, completed); }
    }
    public static JSONObject call(String method, String path, JSONObject body, String token) throws Failure {
        return callReply(method, path, body, token).body;
    }
    public static Reply callReply(String method, String path, JSONObject body, String token) throws Failure {
        return callReply(method, path, body, token, JSON_BUDGET_MS, JSONObject::new);
    }
    /** Package-only seams exercise real transport cleanup/deadlines on a JVM without Android's JSON runtime. */
    interface JsonDecoder { JSONObject decode(String source) throws Exception; }
    static Reply callReply(String method, String path, JSONObject body, String token, long budgetMs, JsonDecoder decoder) throws Failure {
        SNAPSHOT_TIMING.remove();
        if (!allowedRequest(method, path)) throw new Failure(0, "请求地址无效");
        if (budgetMs <= 0 || budgetMs > JSON_BUDGET_MS || decoder == null) throw new Failure(0, "请求配置无效");
        HttpsURLConnection connection = null;
        RequestDeadline deadline = null;
        final long started = System.nanoTime(); long headersMs = -1, byteCount = 0; int httpStatus = 0; boolean completed = false;
        long prepareMs = -1, headersWaitMs = -1, bodyMs = -1, decodeMs = -1;
        String stage = "prepare", failureKind = "none", traceId = "";
        Exception requestException = null;
        try {
            connection = (HttpsURLConnection) new URL(ORIGIN + path).openConnection();
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setConnectTimeout(TIMEOUT_MS); connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod(method); connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            traceId = requestTrace(method, path);
            if (!traceId.isEmpty()) connection.setRequestProperty("X-Monitor-Trace", traceId);
            deadline = new RequestDeadline(connection, started, budgetMs); deadline.check();
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                stage = "request_body";
                try (OutputStream output = connection.getOutputStream()) { deadline.check(); output.write(bytes); }
                deadline.check();
            }
            stage = "headers";
            // Keep the existing cumulative headers_ms. This separate interval includes
            // lazy connection establishment inside getResponseCode, not just wire time.
            long headersStarted = System.nanoTime();
            prepareMs = (headersStarted - started) / 1000000;
            int status;
            try { status = connection.getResponseCode(); }
            finally { headersWaitMs = elapsed(headersStarted); }
            httpStatus = status; headersMs = elapsed(started);
            deadline.check();
            if (status >= 300 && status < 400) throw new Failure(status, "服务地址发生跳转，连接已停止");
            if (status < 200 || status >= 300) {
                String message = status == 401 ? "连接已失效，请重新连接工作台" : status == 403 ? "连接未获允许，请重新发起" : status == 404 ? "内容已不存在，请刷新重试" : status == 409 ? "当前状态无法完成此操作，请刷新后重试" : status == 410 ? "确认已过期，请重新发起" : status == 422 ? "输入格式不正确，请检查后重试" : status == 429 ? "请求较多，请稍后重试" : "个人服务暂时不可用";
                long retryMs = 30000;
                if (status == 429) { try { retryMs = Math.max(1000, Math.min(600000, Long.parseLong(connection.getHeaderField("Retry-After")) * 1000)); } catch (Exception ignored) { } }
                throw new Failure(status, message, retryMs);
            }
            if (status == 204) {
                JSONObject decoded;
                long decodeStarted = System.nanoTime();
                try { decoded = decoder.decode("{}"); }
                finally { decodeMs = elapsed(decodeStarted); }
                Reply reply = new Reply(decoded, cookies(connection)); deadline.finish(); completed = true; return reply;
            }
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            stage = "body";
            long bodyStarted = System.nanoTime();
            try (InputStream input = connection.getInputStream()) {
                byte[] chunk = new byte[8192]; int count;
                while (true) {
                    deadline.check(); count = input.read(chunk); deadline.check();
                    if (count == -1) break;
                    int limit = "/api/native/workbench".equals(path) ? 8 * 1024 * 1024 : 1024 * 1024;
                    if (result.size() + count > limit) throw new Failure(0, "服务返回的数据过大");
                    result.write(chunk, 0, count);
                    byteCount += count;
                }
            } finally { bodyMs = elapsed(bodyStarted); }
            stage = "decode"; deadline.check();
            JSONObject decoded;
            long decodeStarted = System.nanoTime();
            try { decoded = decoder.decode(result.toString("UTF-8")); }
            finally { decodeMs = elapsed(decodeStarted); }
            Reply reply = new Reply(decoded, cookies(connection));
            deadline.finish(); completed = true; return reply;
        } catch (Failure failure) { failureKind = failure.status >= 300 ? "http" : "response_rejected"; throw failure; }
        catch (Exception failure) {
            requestException = failure;
            failureKind = deadline != null && deadline.expired() ? "deadline"
                    : failure instanceof java.net.SocketTimeoutException ? "io_timeout"
                    : failure instanceof java.net.UnknownHostException ? "dns"
                    : failure instanceof javax.net.ssl.SSLException ? "tls"
                    : failure instanceof IOException ? "io" : "invalid_response";
            throw new Failure(0, "deadline".equals(failureKind) ? "连接超时，请稍后重试" : "连接失败，请检查网络和个人服务");
        } finally {
            // A failed preparation still has a duration; untouched later phases stay -1.
            // Capture before cleanup so transport cleanup is not called preparation.
            if (prepareMs < 0) prepareMs = elapsed(started);
            closeErrorBody(connection, httpStatus); disconnectQuietly(connection);
            if (deadline != null) deadline.close();
            timing(method, path, httpStatus, headersMs, elapsed(started), byteCount, completed, stage, failureKind, traceId);
            phaseTiming(method, path, traceId, prepareMs, headersWaitMs, bodyMs, decodeMs);
            transportFailure(method, path, traceId, requestException);
        }
    }
    /** Supplemental fixed categories only; never formats a throwable, message, class name or stack.
     * The outer failure classification and original timings remain unchanged. Classification runs
     * after cleanup/timing and inspects at most eight cause objects, with identity cycle detection.
     */
    private static void transportFailure(String method, String path, String traceId, Exception failure) {
        if (failure == null || !"GET".equals(method) || traceId.isEmpty()) return;
        String operation = "/api/native/workbench".equals(path) ? "workbench"
                : "/api/native/snapshot".equals(path) ? "live_snapshot"
                : "/api/native/usage".equals(path) ? "usage" : "";
        if (operation.isEmpty()) return;
        try {
            String tlsType = "none", cause = "other";
            int causeRank = 0;
            Throwable[] seen = new Throwable[8];
            Throwable current = failure;
            for (int depth = 0; current != null && depth < seen.length; depth++) {
                boolean repeated = false;
                for (int i = 0; i < depth; i++) if (seen[i] == current) { repeated = true; break; }
                if (repeated) break;
                seen[depth] = current;
                if ("none".equals(tlsType) || "other".equals(tlsType)) {
                    if (current instanceof javax.net.ssl.SSLHandshakeException) tlsType = "handshake";
                    else if (current instanceof javax.net.ssl.SSLPeerUnverifiedException) tlsType = "peer_unverified";
                    else if (current instanceof javax.net.ssl.SSLProtocolException) tlsType = "protocol";
                    else if (current instanceof javax.net.ssl.SSLException) tlsType = "other";
                }
                // Specific causes outrank their generic wrappers; the nearest wins equal ranks.
                String candidate = "other"; int rank = 0;
                if (current instanceof java.security.cert.CertificateExpiredException) { candidate = "certificate_expired"; rank = 4; }
                else if (current instanceof java.security.cert.CertificateNotYetValidException) { candidate = "certificate_not_yet_valid"; rank = 4; }
                else if (current instanceof java.security.cert.CertPathValidatorException
                        || current instanceof java.security.cert.CertPathBuilderException) { candidate = "cert_path"; rank = 3; }
                else if (current instanceof java.security.cert.CertificateException) { candidate = "certificate"; rank = 2; }
                else if (current instanceof java.net.SocketTimeoutException) { candidate = "timeout"; rank = 3; }
                else if (current instanceof java.net.UnknownHostException) { candidate = "dns"; rank = 3; }
                else if (current instanceof java.net.ConnectException) { candidate = "connect"; rank = 3; }
                else if (current instanceof java.net.NoRouteToHostException) { candidate = "no_route"; rank = 3; }
                else if (current instanceof java.io.EOFException) { candidate = "eof"; rank = 3; }
                else if (current instanceof java.net.SocketException) { candidate = "socket"; rank = 2; }
                else if (current instanceof IOException && !(current instanceof javax.net.ssl.SSLException)) { candidate = "io"; rank = 1; }
                if (rank > causeRank) { cause = candidate; causeRank = rank; }
                current = current.getCause();
            }
            android.util.Log.i("MonitorTransportFailure", "op=" + operation + " trace=" + traceId
                    + " tls_type=" + tlsType + " cause=" + cause);
        } catch (RuntimeException unavailable) { /* Diagnostics must not change transport results. */ }
    }
    /** Each eligible read gets a fresh random correlation value, unrelated to any user data. */
    private static String requestTrace(String method, String path) {
        if (!"GET".equals(method) || !("/api/native/workbench".equals(path)
                || "/api/native/snapshot".equals(path) || "/api/native/usage".equals(path))) return "";
        try { return java.util.UUID.randomUUID().toString(); }
        catch (RuntimeException unavailable) { return ""; }
    }
    private static long elapsed(long started) { return (System.nanoTime() - started) / 1000000; }
    /** Request-local intervals only: no new connection, UUID, request or sensitive data.
     * Separate from MonitorTiming so existing anchored parsers and SnapshotTiming stay intact.
     * Preparation includes first UUID use; time before this method's started marker does not.
     * Headers may include DNS/TLS/lazy provider work and cannot identify those separately.
     * Body includes stream close; decode excludes cookies and transport cleanup.
     */
    private static void phaseTiming(String method, String path, String traceId, long prepareMs,
                                    long headersWaitMs, long bodyMs, long decodeMs) {
        if (!"GET".equals(method) || traceId.isEmpty()) return;
        String operation = "/api/native/workbench".equals(path) ? "workbench"
                : "/api/native/snapshot".equals(path) ? "live_snapshot"
                : "/api/native/usage".equals(path) ? "usage" : "";
        if (operation.isEmpty()) return;
        try { android.util.Log.i("MonitorPhases", "op=" + operation + " trace=" + traceId
                + " prepare_ms=" + prepareMs + " headers_wait_ms=" + headersWaitMs
                + " body_ms=" + bodyMs + " decode_ms=" + decodeMs); }
        catch (RuntimeException unavailable) { /* Diagnostics must not change transport results. */ }
    }
    private static void timing(String method, String path, int status, long headersMs, long totalMs, long bytes, boolean completed) {
        timing(method, path, status, headersMs, totalMs, bytes, completed, "download", completed ? "none" : "download_failed", "");
    }
    private static void timing(String method, String path, int status, long headersMs, long totalMs, long bytes, boolean completed, String stage, String failureKind, String traceId) {
        String operation = "/api/native/workbench".equals(path) ? "workbench" : "/api/native/snapshot".equals(path) ? "live_snapshot" : "/api/native/usage".equals(path) ? "usage" : path.startsWith("/api/native/tasks/result.txt?") ? "result_txt" : path.startsWith("/api/native/tasks/result/file?") ? "result_file" : path.startsWith("/api/native/tasks/result?") ? "result" : path.startsWith("/api/native/tasks/reply") ? "POST".equals(method) ? "reply_post" : "reply_get" : "";
        if (operation.isEmpty()) return;
        if ("live_snapshot".equals(operation)) SNAPSHOT_TIMING.set(new SnapshotTiming(System.currentTimeMillis(), totalMs, status, completed, stage, failureKind));
        // No account/device/task identifiers, queries, credentials, filenames or payloads.
        // Only a fresh, locally generated trace may correlate these three reads with the hub.
        try { android.util.Log.i("MonitorTiming", "op=" + operation + " status=" + status + " headers_ms=" + headersMs + " total_ms=" + totalMs + " bytes=" + bytes + " complete=" + completed + " stage=" + stage + " failure=" + failureKind + (traceId.isEmpty() ? "" : " trace=" + traceId)); }
        catch (RuntimeException unavailable) { /* Offline JVM tests have no Android logger. */ }
    }
    public static long time(String iso) { try { return Instant.parse(iso).toEpochMilli(); } catch (Exception ignored) { return 0; } }
    public static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{32}"); }
    public static boolean validToken(String token) { return token != null && token.matches("[!-~]{20,8192}"); }
    public static JSONObject task(JSONObject snapshot, String id) {
        JSONArray tasks = snapshot.optJSONArray("tasks");
        if (tasks != null) for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task != null && id.equals(task.optString("id"))) return task;
        }
        return null;
    }
    public static boolean online(JSONObject snapshot, JSONObject task) {
        JSONArray devices = snapshot.optJSONArray("devices");
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device != null && device.optString("id").equals(task.optString("device_id"))) return device.optBoolean("online", false);
        }
        return false;
    }
    public static String status(String value) {
        switch (value) {
            case "running": return "执行中"; case "waiting": return "等待批准";
            case "completed": return "本轮结束"; case "error": return "执行出错";
            case "idle": return "空闲"; default: return "状态未知";
        }
    }
}


