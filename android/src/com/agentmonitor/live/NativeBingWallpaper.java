package com.agentmonitor.live;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Opt-in, foreground-owned Bing image loader. Never changes the system wallpaper. */
public final class NativeBingWallpaper implements AutoCloseable {
    private static final Object FETCH_LOCK = new Object();
    private static long failedAt;
    private static final int CACHE_MAGIC = 0x42494e47;
    private static final int CACHE_METADATA_LIMIT = 16384;
    private final AtomicFile disk;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile HttpURLConnection active;
    private Future<?> future;
    private Callback callback;
    private boolean closed, cacheRead;
    private long revision;
    private Cached cached;

    public interface Callback { void onResult(Result result); }

    /** The delivered bitmap remains owned by the UI; cancel/close never recycle it. */
    public static final class Result {
        public final Bitmap bitmap;
        public final String title, copyright, link, date, state;
        public final boolean cached;
        private Result(Cached value, boolean cached, String state) {
            bitmap = value == null ? null : value.bitmap;
            title = value == null ? "" : value.meta.title;
            copyright = value == null ? "" : value.meta.copyright;
            link = value == null ? "https://www.bing.com/" : value.meta.link;
            date = value == null ? "" : value.meta.date;
            this.cached = cached && value != null;
            this.state = state;
        }
    }

    public NativeBingWallpaper(Context context) {
        if (context == null) throw new IllegalArgumentException("context_required");
        Context application = context.getApplicationContext();
        disk = new AtomicFile(new File((application == null ? context : application).getCacheDir(), "monitor-bing-wallpaper-v1.bin"));
    }

    /** Call only when Bing is selected and the activity is foreground. No constructor fetch. */
    public synchronized void load(Callback listener) {
        if (closed || listener == null) return;
        callback = listener;
        if (future != null && !future.isDone()) return;
        final long token = ++revision;
        future = worker.submit(() -> run(token));
    }

    public void cancel() {
        HttpURLConnection connection;
        synchronized (this) {
            revision++;
            callback = null;
            if (future != null) future.cancel(true);
            future = null;
            connection = active;
        }
        if (connection != null) connection.disconnect();
    }

    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; }
        cancel();
        worker.shutdownNow();
    }

    private synchronized boolean current(long token) { return !closed && revision == token; }

    private void check(long token) {
        if (!current(token) || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private void run(long token) {
        Result result;
        try {
            synchronized (FETCH_LOCK) {
                check(token);
                if (!cacheRead) { cached = readCache(); cacheRead = true; }
                check(token);
                String today = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
                if (!NativeBingWallpaperPolicy.shouldFetch(today, cached == null ? "" : cached.day,
                        cached != null, failedAt, SystemClock.elapsedRealtime())) {
                    result = new Result(cached, true, cached != null && today.equals(cached.day) ? "cached"
                            : cached == null ? "unavailable" : "offline");
                } else {
                    try {
                        long deadline = SystemClock.elapsedRealtime() + 25000L;
                        byte[] metadata = fetch(NativeBingWallpaperPolicy.METADATA_URL, true, token, deadline);
                        JSONObject document = new JSONObject(new String(metadata, StandardCharsets.UTF_8));
                        JSONArray images = document.optJSONArray("images");
                        if (images == null || images.length() != 1 || images.optJSONObject(0) == null)
                            throw new IOException("metadata_invalid");
                        Meta meta = Meta.parse(images.optJSONObject(0));
                        byte[] image = fetch(meta.url, false, token, deadline);
                        Bitmap bitmap = decode(image);
                        check(token);
                        writeCache(meta, today, image, token);
                        cached = new Cached(meta, today, bitmap);
                        failedAt = 0;
                        result = new Result(cached, false, "updated");
                    } catch (Restricted ignored) {
                        check(token);
                        failedAt = SystemClock.elapsedRealtime();
                        result = new Result(cached, true, "restricted");
                    } catch (CancellationException cancelled) {
                        throw cancelled;
                    } catch (Exception ignored) {
                        check(token);
                        failedAt = SystemClock.elapsedRealtime();
                        result = new Result(cached, true, cached == null ? "unavailable" : "offline");
                    }
                }
            }
            final Result delivered = result;
            main.post(() -> {
                Callback listener;
                synchronized (NativeBingWallpaper.this) {
                    if (!current(token)) return;
                    listener = callback;
                }
                if (listener != null) listener.onResult(delivered);
            });
        } catch (CancellationException ignored) { }
    }

    private byte[] fetch(String address, boolean metadata, long token, long deadline) throws IOException {
        check(token);
        if (metadata ? !NativeBingWallpaperPolicy.METADATA_URL.equals(address)
                : !NativeBingWallpaperPolicy.imageUrl(address).equals(address)) throw new IOException("endpoint_invalid");
        int remaining = (int) Math.max(0L, deadline - SystemClock.elapsedRealtime());
        if (remaining == 0) throw new IOException("fetch_deadline");
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        active = connection;
        try {
            check(token);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(Math.min(8000, remaining));
            connection.setReadTimeout(Math.min(6000, remaining));
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", metadata ? "application/json" : "image/jpeg");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "Monitor-Wallpaper/1");
            connection.setRequestProperty("Cookie", "");
            connection.setRequestProperty("Authorization", "");
            // Every redirect, challenge and alternate host is rejected rather than followed.
            if (connection.getResponseCode() != 200) throw new IOException("public_fetch_failed");
            String encoding = connection.getContentEncoding();
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new IOException("content_encoding_invalid");
            String type = connection.getContentType();
            type = type == null ? "" : type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (metadata ? !("application/json".equals(type) || "text/javascript".equals(type))
                    : !"image/jpeg".equals(type)) throw new IOException("content_type_invalid");
            int limit = metadata ? NativeBingWallpaperPolicy.MAX_METADATA_BYTES : NativeBingWallpaperPolicy.MAX_IMAGE_BYTES;
            long declared = connection.getContentLengthLong();
            if (declared == 0 || declared > limit) throw new IOException("content_length_invalid");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while (true) {
                    check(token);
                    if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("fetch_deadline");
                    count = input.read(buffer);
                    if (count < 0) break;
                    if (output.size() + count > limit) throw new IOException("content_too_large");
                    output.write(buffer, 0, count);
                }
                byte[] bytes = output.toByteArray();
                if (bytes.length == 0 || (declared >= 0 && declared != bytes.length)) throw new IOException("content_incomplete");
                return bytes;
            }
        } finally {
            if (active == connection) active = null;
            connection.disconnect();
        }
    }

    private static Bitmap decode(byte[] bytes) throws IOException {
        if (bytes.length < 4 || bytes.length > NativeBingWallpaperPolicy.MAX_IMAGE_BYTES
                || (bytes[0] & 255) != 255 || (bytes[1] & 255) != 216 || (bytes[2] & 255) != 255)
            throw new IOException("image_invalid");
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = NativeBingWallpaperPolicy.sampleSize(bounds.outWidth, bounds.outHeight);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) throw new IOException("image_decode_failed");
            if (!NativeBingWallpaperPolicy.decodedSizeAllowed(bitmap.getWidth(), bitmap.getHeight())) {
                bitmap.recycle(); // This rejected bitmap has never been delivered to the UI.
                throw new IOException("decoded_image_too_large");
            }
            return bitmap;
        } catch (OutOfMemoryError | IllegalArgumentException error) { throw new IOException("image_decode_failed"); }
    }

    private Cached readCache() {
        try (DataInputStream input = new DataInputStream(disk.openRead())) {
            if (input.readInt() != CACHE_MAGIC) return null;
            int size = input.readInt();
            if (size <= 0 || size > CACHE_METADATA_LIMIT) return null;
            byte[] metadata = new byte[size]; input.readFully(metadata);
            JSONObject record = new JSONObject(new String(metadata, StandardCharsets.UTF_8));
            Meta meta = Meta.parse(record);
            String day = record.optString("successfulDay", "");
            if (!NativeBingWallpaperPolicy.validDate(day)) return null;
            size = input.readInt();
            if (size <= 0 || size > NativeBingWallpaperPolicy.MAX_IMAGE_BYTES) return null;
            byte[] image = new byte[size]; input.readFully(image);
            if (input.read() != -1 || !sha(image).equals(record.optString("imageSha256", ""))) return null;
            return new Cached(meta, day, decode(image));
        } catch (Exception ignored) { return null; }
    }

    private void writeCache(Meta meta, String day, byte[] image, long token) throws Exception {
        JSONObject record = new JSONObject().put("wp", true).put("url", meta.url).put("title", meta.title)
                .put("copyright", meta.copyright).put("copyrightlink", meta.link).put("startdate", meta.date)
                .put("successfulDay", day).put("imageSha256", sha(image));
        byte[] metadata = record.toString().getBytes(StandardCharsets.UTF_8);
        if (metadata.length > CACHE_METADATA_LIMIT) throw new IOException("cache_metadata_too_large");
        FileOutputStream stream = null;
        try {
            check(token);
            stream = disk.startWrite();
            DataOutputStream output = new DataOutputStream(stream);
            output.writeInt(CACHE_MAGIC); output.writeInt(metadata.length); output.write(metadata);
            output.writeInt(image.length); output.write(image); output.flush();
            check(token);
            disk.finishWrite(stream);
        } catch (Exception failure) {
            if (stream != null) disk.failWrite(stream);
            throw failure;
        }
    }

    private static String sha(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte part : MessageDigest.getInstance("SHA-256").digest(bytes))
            value.append(String.format(Locale.ROOT, "%02x", part & 255));
        return value.toString();
    }

    private static final class Cached {
        final Meta meta; final String day; final Bitmap bitmap;
        Cached(Meta meta, String day, Bitmap bitmap) { this.meta = meta; this.day = day; this.bitmap = bitmap; }
    }

    private static final class Restricted extends IOException { }

    private static final class Meta {
        final String url, title, copyright, link, date;
        private Meta(String url, String title, String copyright, String link, String date) {
            this.url = url; this.title = title; this.copyright = copyright; this.link = link; this.date = date;
        }
        static Meta parse(JSONObject source) throws IOException {
            Object wp = source.opt("wp");
            if (Boolean.FALSE.equals(wp)) throw new Restricted();
            if (!NativeBingWallpaperPolicy.wallpaperAllowed(wp)) throw new IOException("wallpaper_permission_missing");
            Object rawUrl = source.opt("url"), rawDate = source.opt("startdate");
            if (!(rawUrl instanceof String) || !(rawDate instanceof String)
                    || !NativeBingWallpaperPolicy.validDate((String) rawDate)) throw new IOException("metadata_invalid");
            String title = NativeBingWallpaperPolicy.publicText(source.opt("title"), 160);
            String copyright = NativeBingWallpaperPolicy.publicText(source.opt("copyright"), 512);
            if (copyright.isEmpty()) throw new IOException("attribution_missing");
            Object rawLink = source.opt("copyrightlink");
            return new Meta(NativeBingWallpaperPolicy.imageUrl((String) rawUrl), title.isEmpty() ? "必应每日壁纸" : title,
                    copyright, NativeBingWallpaperPolicy.publicLink(rawLink instanceof String ? (String) rawLink : null),
                    (String) rawDate);
        }
    }
}
