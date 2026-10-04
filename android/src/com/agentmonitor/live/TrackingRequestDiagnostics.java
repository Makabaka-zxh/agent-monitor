package com.agentmonitor.live;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fixed-schema private ring. No request/response, identity, URLs or exception messages accepted. */
public final class TrackingRequestDiagnostics {
    public static final String FILE_NAME = "tracking-requests.json";
    static final int CAPACITY = 24, MAX_BYTES = 16384;
    private static final String PREFIX = "{\"version\":1,\"attempts\":[", SUFFIX = "]}";
    private static final Pattern ENTRY = Pattern.compile("\\{\"time\":([0-9]{1,19}),\"duration_ms\":([0-9]{1,19}),\"status\":([0-9]{1,3}),\"success\":(true|false),\"stage\":\"([a-z_]{1,32})\",\"failure\":\"([a-z_]{1,32})\"\\}");
    private TrackingRequestDiagnostics() { }
    private static String stage(String value) {
        if ("prepare".equals(value) || "request_body".equals(value) || "headers".equals(value)
                || "body".equals(value) || "decode".equals(value) || "snapshot_shape".equals(value)) return value;
        return "unknown";
    }
    private static String failure(String value) {
        if ("none".equals(value) || "http".equals(value) || "response_rejected".equals(value)
                || "deadline".equals(value) || "io_timeout".equals(value) || "dns".equals(value)
                || "tls".equals(value) || "io".equals(value) || "invalid_response".equals(value)
                || "snapshot_shape".equals(value)) return value;
        return "unclassified";
    }
    private static String entry(long time, long duration, int status, boolean success, String stage, String failure) {
        int safeStatus = status >= 100 && status <= 599 ? status : 0;
        return "{\"time\":" + Math.max(0, time) + ",\"duration_ms\":" + Math.max(0, duration)
                + ",\"status\":" + safeStatus + ",\"success\":" + success + ",\"stage\":\"" + stage(stage)
                + "\",\"failure\":\"" + (success ? "none" : failure(failure)) + "\"}";
    }
    static List<String> read(File file) {
        List<String> entries = new ArrayList<>();
        if (!file.isFile() || file.length() > MAX_BYTES) return entries;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[MAX_BYTES + 1]; int total = 0, count;
            while (total < bytes.length && (count = input.read(bytes, total, bytes.length - total)) != -1) total += count;
            if (total > MAX_BYTES) return entries;
            String source = new String(bytes, 0, total, StandardCharsets.UTF_8);
            if (!source.startsWith(PREFIX) || !source.endsWith(SUFFIX)) return entries;
            String body = source.substring(PREFIX.length(), source.length() - SUFFIX.length());
            Matcher matcher = ENTRY.matcher(body); int position = 0;
            while (matcher.find()) {
                if (matcher.start() != position || entries.size() >= CAPACITY) return new ArrayList<>();
                entries.add(entry(Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2)), Integer.parseInt(matcher.group(3)),
                        Boolean.parseBoolean(matcher.group(4)), matcher.group(5), matcher.group(6)));
                position = matcher.end();
                if (position < body.length()) { if (body.charAt(position) != ',') return new ArrayList<>(); position++; }
            }
            if (position != body.length() || body.endsWith(",")) entries.clear();
        } catch (Exception invalid) { entries.clear(); }
        return entries;
    }
    /** Caller explicitly opts in for debuggable builds. A write failure is never a request failure. */
    public static synchronized void record(boolean enabled, File directory, long time, long durationMs,
                                           int status, boolean success, String stage, String failure) {
        if (!enabled || directory == null) return;
        File temporary = null;
        try {
            File target = new File(directory, FILE_NAME);
            if (!directory.isDirectory() || !target.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) return;
            List<String> entries = read(target);
            while (entries.size() >= CAPACITY) entries.remove(0);
            entries.add(entry(time, durationMs, status, success, stage, failure));
            String encoded = PREFIX + String.join(",", entries) + SUFFIX;
            byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8); if (bytes.length > MAX_BYTES) return;
            temporary = File.createTempFile("tracking-requests-", ".tmp", directory);
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(bytes); }
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception unavailable) { /* Debug evidence must never change service behavior. */ }
        finally { if (temporary != null) temporary.delete(); }
    }
}
