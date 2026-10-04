package com.agentmonitor.live;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/** Anonymous, bounded GitHub release reads. Never uses the account transport or its credentials. */
final class UpdateClient {
    interface Progress { void downloaded(long bytes, long total); }
    static final class Release {
        final UpdatePolicy.Manifest manifest;
        final String tag, apkUrl, notes;
        Release(UpdatePolicy.Manifest manifest, String tag, String apkUrl, String notes) {
            this.manifest = manifest; this.tag = tag; this.apkUrl = apkUrl; this.notes = notes;
        }
    }
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor result = new ScheduledThreadPoolExecutor(1, action -> {
            Thread thread = new Thread(action, "MonitorUpdateDeadline"); thread.setDaemon(true); return thread;
        });
        result.setRemoveOnCancelPolicy(true); return result;
    }
    static final class Operation implements AutoCloseable {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final long until;
        private final ScheduledFuture<?> alarm;
        private volatile HttpsURLConnection connection;
        Operation(long budgetMillis) {
            until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
            alarm = DEADLINES.schedule(this::cancel, budgetMillis, TimeUnit.MILLISECONDS);
        }
        void check() throws IOException {
            if (System.nanoTime() >= until) throw new IOException("请求超时，请重试");
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("操作已取消");
        }
        private synchronized void bind(HttpsURLConnection value) throws IOException {
            check(); connection = value;
        }
        private synchronized void unbind(HttpsURLConnection value) { if (connection == value) connection = null; }
        void cancel() {
            HttpsURLConnection current;
            synchronized (this) { cancelled.set(true); current = connection; }
            if (current != null) try { current.disconnect(); } catch (RuntimeException ignored) { }
        }
        public void close() { alarm.cancel(false); cancel(); }
    }
    static Release check(Operation operation) throws Exception {
        JSONObject release = new JSONObject(new String(read(UpdatePolicy.LATEST_RELEASE_API, null, null,
                UpdatePolicy.MAX_METADATA_BYTES, operation), StandardCharsets.UTF_8));
        if (!Boolean.FALSE.equals(release.opt("draft")) || !Boolean.FALSE.equals(release.opt("prerelease")))
            throw new IOException("当前没有可用的正式版本");
        String tag = string(release, "tag_name");
        JSONArray assets = release.optJSONArray("assets");
        JSONObject metadata = asset(assets, "update.json");
        String metadataUrl = string(metadata, "browser_download_url");
        if (!UpdatePolicy.allowedAssetUrl(metadataUrl, tag, "update.json")
                || number(metadata, "size") <= 0 || number(metadata, "size") > UpdatePolicy.MAX_METADATA_BYTES)
            throw new IOException("更新信息不符合要求");
        byte[] metadataBytes = read(metadataUrl, tag, "update.json", UpdatePolicy.MAX_METADATA_BYTES, operation);
        if (metadataBytes.length != number(metadata, "size")) throw new IOException("更新信息下载不完整");
        JSONObject value = new JSONObject(new String(metadataBytes, StandardCharsets.UTF_8));
        UpdatePolicy.Manifest manifest = new UpdatePolicy.Manifest(integer(value, "schema"), string(value, "packageName"),
                string(value, "versionName"), integer(value, "versionCode"), integer(value, "minSdk"),
                string(value, "apkName"), string(value, "sha256"), number(value, "size"));
        JSONObject apk = asset(assets, manifest.apkName);
        String apkUrl = string(apk, "browser_download_url");
        if (!UpdatePolicy.allowedAssetUrl(apkUrl, tag, manifest.apkName) || number(apk, "size") != manifest.size)
            throw new IOException("安装包与更新信息不一致");
        operation.check();
        return new Release(manifest, tag, apkUrl, notes(release.opt("body")));
    }
    static File download(Context context, Release release, Operation operation, Progress progress) throws Exception {
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法保存安装包");
        File part = File.createTempFile("update-", ".part", directory);
        File complete = new File(directory, part.getName().replace(".part", ".apk"));
        boolean success = false;
        try {
            HttpsURLConnection connection = open(release.apkUrl, release.tag, release.manifest.apkName, operation);
            try {
                long announced = connection.getContentLengthLong();
                if (announced >= 0 && announced != release.manifest.size) throw new IOException("安装包大小不一致");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long count = 0;
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(part)) {
                    byte[] buffer = new byte[32768]; int read;
                    while ((read = input.read(buffer)) != -1) {
                        operation.check(); count += read;
                        if (count > release.manifest.size || count > UpdatePolicy.MAX_APK_BYTES) throw new IOException("安装包超出预期大小");
                        output.write(buffer, 0, read); digest.update(buffer, 0, read);
                        if (progress != null) progress.downloaded(count, release.manifest.size);
                    }
                    output.getFD().sync();
                }
                operation.check();
                if (count != release.manifest.size || !hex(digest.digest()).equalsIgnoreCase(release.manifest.sha256))
                    throw new IOException("安装包校验失败，请重新下载");
            } finally { operation.unbind(connection); connection.disconnect(); }
            if (!part.renameTo(complete)) throw new IOException("无法保存已校验的安装包");
            operation.check(); success = true; return complete;
        } finally { if (!success) { part.delete(); complete.delete(); } }
    }
    private static byte[] read(String url, String tag, String assetName, int limit, Operation operation) throws Exception {
        HttpsURLConnection connection = open(url, tag, assetName, operation);
        try {
            if (connection.getContentLengthLong() > limit) throw new IOException("更新信息过大");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) != -1) {
                    operation.check();
                    if (output.size() + read > limit) throw new IOException("更新信息过大");
                    output.write(buffer, 0, read);
                }
            }
            operation.check(); return output.toByteArray();
        } finally { operation.unbind(connection); connection.disconnect(); }
    }
    private static HttpsURLConnection open(String initial, String tag, String assetName, Operation operation) throws Exception {
        String target = initial;
        for (int redirects = 0; redirects <= 5; redirects++) {
            operation.check();
            if (tag == null ? !UpdatePolicy.LATEST_RELEASE_API.equals(target) : !UpdatePolicy.allowedRedirect(target, tag, assetName))
                throw new IOException("更新地址不受信任");
            HttpsURLConnection connection = (HttpsURLConnection) new URL(target).openConnection();
            boolean returned = false;
            try {
                connection.setConnectTimeout(12000); connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", tag == null ? "application/vnd.github+json" : "application/octet-stream");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", "Monitor-Android-Update");
                connection.setRequestProperty("Authorization", ""); connection.setRequestProperty("Cookie", "");
                operation.bind(connection);
                int status = connection.getResponseCode(); operation.check();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == 5 || tag == null) throw new IOException("更新地址跳转异常");
                    target = new URL(new URL(target), location).toString(); continue;
                }
                if (status == 404) throw new IOException("还没有可用的正式版本，请稍后再试");
                if (status == 403 || status == 429) throw new IOException("GitHub 暂时限制检查，请稍后重试");
                if (status != 200) throw new IOException("暂时无法读取更新，请重试");
                String encoding = connection.getContentEncoding();
                if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("更新文件编码不受支持");
                returned = true; return connection;
            } finally { if (!returned) { operation.unbind(connection); connection.disconnect(); } }
        }
        throw new IOException("更新地址跳转异常");
    }
    private static JSONObject asset(JSONArray assets, String name) throws IOException {
        if (assets == null || assets.length() > 100) throw new IOException("发布文件列表不符合要求");
        JSONObject found = null;
        for (int index = 0; index < assets.length(); index++) {
            JSONObject candidate = assets.optJSONObject(index);
            if (candidate == null || !name.equals(candidate.opt("name"))) continue;
            if (found != null || !"uploaded".equals(candidate.opt("state"))) throw new IOException("发布文件重复或尚未就绪");
            found = candidate;
        }
        if (found == null) throw new IOException("该版本尚未提供应用内更新文件");
        return found;
    }
    private static String string(JSONObject value, String key) throws IOException {
        Object item = value.opt(key); if (!(item instanceof String)) throw new IOException("更新信息字段无效"); return (String) item;
    }
    private static long number(JSONObject value, String key) throws IOException {
        Object item = value.opt(key);
        if (!(item instanceof Integer) && !(item instanceof Long)) throw new IOException("更新信息数字无效");
        return ((Number) item).longValue();
    }
    private static int integer(JSONObject value, String key) throws IOException {
        long number = number(value, key); if (number < 0 || number > Integer.MAX_VALUE) throw new IOException("更新信息数字无效"); return (int) number;
    }
    private static String notes(Object raw) {
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) return "此版本未提供更新说明。";
        String text = (String) raw; StringBuilder result = new StringBuilder();
        for (int index = 0; index < text.length() && result.length() < 8000; index++) {
            char c = text.charAt(index);
            if (c == '\n' || c == '\t' || !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT) result.append(c);
        }
        return result.toString().trim();
    }
    static String hex(byte[] digest) {
        StringBuilder result = new StringBuilder(); for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return result.toString();
    }
    private UpdateClient() { }
}
