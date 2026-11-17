package com.agentmonitor.live;

import java.net.URI;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Set;

/** Public-image transport and resource limits; independent of user or task data. */
public final class NativeBingWallpaperPolicy {
    private NativeBingWallpaperPolicy() { }

    public static final String METADATA_URL = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN";
    public static final int MAX_METADATA_BYTES = 65536;
    public static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    public static final long FAILURE_BACKOFF_MS = 15 * 60 * 1000L;
    public static final int MAX_DECODED_EDGE = 2048;
    public static final long MAX_DECODED_PIXELS = 2097152L;

    /** A missing or coerced flag is not permission to download. */
    public static boolean wallpaperAllowed(Object wp) { return Boolean.TRUE.equals(wp); }

    /** Keep only the public image identifier; never forward unrelated query parameters. */
    public static String imageUrl(String value) {
        if (value == null || value.length() > 2048) throw new IllegalArgumentException("image_url_invalid");
        try {
            URI uri = new URI(value);
            boolean relative = uri.getScheme() == null && uri.getRawAuthority() == null;
            boolean absolute = "https".equals(uri.getScheme()) && "www.bing.com".equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1 && uri.getRawUserInfo() == null;
            if ((!relative && !absolute) || !"/th".equals(uri.getRawPath()) || uri.getRawFragment() != null)
                throw new IllegalArgumentException("image_url_invalid");
            String query = uri.getRawQuery(), id = null;
            if (query == null) throw new IllegalArgumentException("image_url_invalid");
            Set<String> keys = new HashSet<>();
            for (String item : query.split("&", -1)) {
                int equals = item.indexOf('=');
                if (equals <= 0) throw new IllegalArgumentException("image_url_invalid");
                String key = item.substring(0, equals), part = item.substring(equals + 1);
                if (!keys.add(key) || !("id".equals(key) || "rf".equals(key) || "pid".equals(key))
                        || !part.matches("[A-Za-z0-9_.-]{1,240}"))
                    throw new IllegalArgumentException("image_url_invalid");
                if ("id".equals(key)) id = part;
            }
            if (id == null || !id.matches("OHR\\.[A-Za-z0-9_-]{1,210}_[0-9]{3,4}x[0-9]{3,4}\\.jpg"))
                throw new IllegalArgumentException("image_url_invalid");
            return "https://www.bing.com/th?id=" + id;
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException("image_url_invalid");
        }
    }

    public static String publicLink(String value) {
        try {
            if (value == null || value.length() > 4096) return "https://www.bing.com/";
            URI uri = new URI(value);
            if ("https".equals(uri.getScheme()) && "www.bing.com".equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1 && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                    && ("/search".equals(uri.getRawPath()) || "/".equals(uri.getRawPath())))
                return uri.toASCIIString();
        } catch (java.net.URISyntaxException ignored) { }
        return "https://www.bing.com/";
    }

    public static boolean validDate(String value) {
        if (value == null || !value.matches("20[0-9]{6}")) return false;
        try {
            GregorianCalendar calendar = new GregorianCalendar();
            calendar.setLenient(false);
            calendar.clear();
            calendar.set(Integer.parseInt(value.substring(0, 4)), Integer.parseInt(value.substring(4, 6)) - 1,
                    Integer.parseInt(value.substring(6, 8)));
            calendar.getTimeInMillis();
            return true;
        } catch (IllegalArgumentException ignored) { return false; }
    }

    public static boolean shouldFetch(String today, String successfulDay, boolean hasImage,
                                      long failedAt, long elapsedNow) {
        if (!validDate(today) || elapsedNow < 0) return false;
        if (hasImage && today.equals(successfulDay)) return false;
        return failedAt <= 0 || elapsedNow < failedAt || elapsedNow - failedAt >= FAILURE_BACKOFF_MS;
    }

    /** Bounds decode output independently of compressed byte size. */
    public static int sampleSize(int width, int height) {
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192
                || (long) width * height > 33554432L) throw new IllegalArgumentException("image_dimensions_invalid");
        int sample = 1;
        while (((width + sample - 1L) / sample) > MAX_DECODED_EDGE
                || ((height + sample - 1L) / sample) > MAX_DECODED_EDGE
                || ((width + sample - 1L) / sample) * ((height + sample - 1L) / sample) > MAX_DECODED_PIXELS)
            sample *= 2;
        return sample;
    }

    public static boolean decodedSizeAllowed(int width, int height) {
        return width > 0 && height > 0 && width <= MAX_DECODED_EDGE && height <= MAX_DECODED_EDGE
                && (long) width * height <= MAX_DECODED_PIXELS;
    }

    public static String publicText(Object value, int limit) {
        if (!(value instanceof String) || ((String) value).length() > limit) return "";
        StringBuilder text = new StringBuilder();
        for (char character : ((String) value).toCharArray()) {
            if (Character.isISOControl(character)) { text.append(' '); continue; }
            if ((character >= '\u202a' && character <= '\u202e') || (character >= '\u2066' && character <= '\u2069')) continue;
            text.append(character);
        }
        return text.toString().trim();
    }
}
