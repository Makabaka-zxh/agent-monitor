package com.agentmonitor.live;

/** A native credential may reach only the fixed HTTPS API's intended methods. */
public final class NativeApiRequestTest {
    private static int checks;
    private static void check(boolean result, String label) {
        checks++;
        if (!result) throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        String id = "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345";
        String uuid = "aabbccdd-1234-4abc-8abc-001122334455";
        String digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String task = uuid + ":claude:C:/project/session-一";
        check(NativeApi.allowedRequest("GET", "/api/native/workbench"), "native workbench accepted");
        check(NativeApi.allowedRequest("GET", "/api/native/usage"), "usage read accepted");
        check(!NativeApi.allowedRequest("POST", "/api/native/usage"), "usage cannot write");
        check(NativeApi.allowedRequest("GET", "/api/native/account"), "account accepted");
        check(NativeApi.allowedRequest("GET", "/api/native/snapshot"), "notification snapshot unchanged");
        check(NativeApi.allowedRequest("GET", "/api/native/session"), "payload-free check unchanged");
        check(NativeApi.allowedRequest("DELETE", "/api/native/session"), "disconnect unchanged");
        check(NativeApi.allowedRequest("PATCH", "/api/native/profile"), "profile mutation accepted");
        check(NativeApi.allowedRequest("PATCH", "/api/native/preferences"), "preference mutation accepted");
        check(NativeApi.allowedRequest("PATCH", "/api/native/tasks/archive"), "task id stays in JSON");
        check(NativeApi.allowedRequest("POST", "/api/native/tasks/reply"), "explicit native reply admitted");
        String resultPath = NativeApi.taskPath("result", task);
        String txtPath = NativeApi.taskPath("result.txt", task, "result_id", digest);
        String filePath = NativeApi.taskPath("result/file", task, "result_id", digest, "file_id", digest);
        check(NativeApi.allowedRequest("GET", resultPath), "historical colon slash and unicode task id accepted via canonical encoding");
        check(NativeApi.allowedRequest("GET", NativeApi.taskPath("reply", task)), "reply capability query admitted");
        check(NativeApi.allowedRequest("GET", NativeApi.taskPath("reply", task, "request_id", uuid)), "same request can be queried after a lost response");
        check(NativeApi.allowedDownload(txtPath), "pinned result TXT accepted");
        check(NativeApi.allowedDownload(filePath), "pinned attachment accepted");
        check(!NativeApi.allowedDownload(resultPath), "JSON response not a file download");
        check(!NativeApi.allowedRequest("POST", resultPath), "reading results does not mutate");
        check(!NativeApi.allowedRequest("GET", "/api/native/tasks/reply"), "query requires an explicit task");
        check(NativeApi.taskPath("result/file", task, "result_id", digest, "path", "C:/secrets").isEmpty(), "raw paths cannot choose files");
        check(NativeApi.taskPath("result.txt", task, "result_id", uuid).isEmpty(), "result identity requires digest");
        check(NativeApi.taskPath("result", "task\r\nAuthorization:secret").isEmpty(), "control characters cannot cross the route gate");
        for (String attack : new String[]{txtPath + "&token=private", txtPath + "#fragment", txtPath + "&result_id=" + digest,
                filePath + "&path=..%2Fprivate", filePath.replace("?task_id=", "?path="), resultPath.replace("%3A", "%3a"),
                "/api/native/tasks/result?task_id=", "/api/native/tasks/result?task_id=%zz", "https://other.test" + filePath,
                "/api/native/tasks/result/file?task_id=a&result_id=" + digest + "&file_id=..%2Fprivate"}) {
            check(!NativeApi.allowedRequest("GET", attack), "file and result query confusion rejected");
            check(!NativeApi.allowedDownload(attack), "download cannot expand its query or origin");
        }
        check(NativeApi.allowedRequest("POST", "/api/native/computers/pairing/" + id + "/approve"), "explicit computer consent accepted");
        check(NativeApi.allowedRequest("DELETE", "/api/native/connections/" + id), "reader revoke accepted");
        check(NativeApi.allowedRequest("DELETE", "/api/native/sessions/" + uuid), "session revoke accepted");
        check(NativeApi.allowedRequest("DELETE", "/api/native/computers/" + uuid), "computer removal accepted");
        for (String path : new String[]{"https://attacker.test/api/native/account", "//attacker.test/account",
                "/api/native/../profile", "/api/native/%2e%2e/profile", "/api/native/account?target=other",
                "/api/native/account#fragment", "/api/native/account/", "/api/native/account\r\nX-Test: injected",
                "/api/native/account?token=secret", "/api/profile", "/api/snapshot", "/api/agent/heartbeat",
                "/api/native/computers/pairing/" + id + "/poll", "/api/native/devices", null}) {
            for (String method : new String[]{"GET", "POST", "PATCH", "DELETE"}) {
                check(!NativeApi.allowedRequest(method, path), "expanded route rejected before connection");
            }
        }
        for (String method : new String[]{"POST", "PATCH", "DELETE", "HEAD", "PUT", "get", null})
            check(!NativeApi.allowedRequest(method, "/api/native/account"), "account cannot be repurposed through another method");
        check(!NativeApi.allowedRequest("GET", "/api/native/computers/pairing/" + id + "/approve"), "GET cannot grant pairing");
        check(!NativeApi.allowedRequest("POST", "/api/native/connections/" + id), "POST cannot revoke reader");
        try {
            NativeApi.call("GET", "https://attacker.test", null, "Synthetic-sensitive-token");
            throw new AssertionError("Invalid origin reached transport");
        } catch (NativeApi.Failure expected) {
            check(expected.status == 0, "production call rejects expanded URL before transport");
        }
        downloadTransportChecks(filePath, digest);
        System.out.println("NativeApiRequestTest: " + checks + " checks passed");
    }
    private static void downloadTransportChecks(String path, String ignoredDigest) throws Exception {
        final FakeHttps[] last = new FakeHttps[1];
        java.net.URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new java.net.URLStreamHandler() {
            protected java.net.URLConnection openConnection(java.net.URL url) { return last[0] = new FakeHttps(url); }
        } : null);
        byte[] bytes = "最终结果\nUTF-8 ✓\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FakeHttps.nextBytes = bytes;
        StringBuilder hash = new StringBuilder(); for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        java.io.ByteArrayOutputStream destination = new java.io.ByteArrayOutputStream();
        long size = NativeApi.download(path, "synthetic-token-never-live", destination, bytes.length, hash.toString(), () -> false);
        check(size == bytes.length && java.util.Arrays.equals(bytes, destination.toByteArray()), "UTF8 file streamed unchanged with verified digest");
        check(last[0].getURL().toString().equals(NativeApi.ORIGIN + path), "download stays on the fixed HTTPS origin");
        check(!last[0].getInstanceFollowRedirects(), "redirect following disabled before bearer transport");
        check(last[0].closed, "download connection closed after success");
        check("Bearer synthetic-token-never-live".equals(last[0].getRequestProperty("Authorization")), "bearer remains a header");
        final FakeHttps original = last[0];
        expectDownloadFailure("https://other.test/file", bytes.length, hash.toString(), () -> false, "external URL rejected before transport");
        check(last[0] == original, "invalid download never creates a connection");
        expectDownloadFailure(path, NativeApi.MAX_DOWNLOAD_BYTES + 1, hash.toString(), () -> false, "oversized attachment metadata rejected");
        expectDownloadFailure(path, bytes.length + 1, hash.toString(), () -> false, "changed ContentLength fails");
        check(last[0].closed, "connection closed after changed file");
        expectDownloadFailure(path, bytes.length, "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", () -> false, "changed digest fails");
        expectDownloadFailure(path, bytes.length, hash.toString(), () -> true, "cancelled screen cannot fetch a file");
        FakeHttps.nextCode = 302;
        expectDownloadFailure(path, bytes.length, hash.toString(), () -> false, "redirect cannot move bearer to another origin");
        check(last[0].reads == 0, "redirect body is not copied");
        FakeHttps.nextCode = 401;
        expectDownloadFailure(path, bytes.length, hash.toString(), () -> false, "expired credential cannot save an error response as a file");
        FakeHttps.nextCode = 200; FakeHttps.nextLength = bytes.length + 2;
        expectDownloadFailure(path, -1, "", () -> false, "truncated response fails even without expected metadata length");
        FakeHttps.nextLength = -2;
    }
    private static void expectDownloadFailure(String path, long size, String hash, NativeApi.Cancellation cancelled, String label) throws Exception {
        try { NativeApi.download(path, "synthetic-token-never-live", new java.io.ByteArrayOutputStream(), size, hash, cancelled); throw new AssertionError(label); }
        catch (NativeApi.Failure expected) { check(true, label); }
    }
    private static final class FakeHttps extends javax.net.ssl.HttpsURLConnection {
        static byte[] nextBytes; static int nextCode = 200; static long nextLength = -2;
        boolean closed; int reads;
        FakeHttps(java.net.URL url) { super(url); }
        public void disconnect() { closed = true; }
        public boolean usingProxy() { return false; }
        public void connect() { }
        public int getResponseCode() { return nextCode; }
        public long getContentLengthLong() { return nextLength == -2 ? nextBytes.length : nextLength; }
        public String getHeaderField(String name) { return null; }
        public java.io.InputStream getInputStream() { reads++; return new java.io.ByteArrayInputStream(nextBytes); }
        public String getCipherSuite() { return "synthetic"; }
        public java.security.cert.Certificate[] getLocalCertificates() { return null; }
        public java.security.cert.Certificate[] getServerCertificates() { return null; }
        public java.security.Principal getPeerPrincipal() { return null; }
        public java.security.Principal getLocalPrincipal() { return null; }
    }
}
