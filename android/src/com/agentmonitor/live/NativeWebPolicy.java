package com.agentmonitor.live;

import java.net.URI;
import java.net.URLDecoder;

/** Pure URL/gesture policy. No payload other than a validated task identifier is accepted. */
public final class NativeWebPolicy {
    // Application initialization replaces this non-personal test default with the saved origin,
    // or an empty string. An unconfigured installation never sends account credentials.
    public static volatile String ORIGIN = "https://monitor.example.com";
    public static String canonicalOrigin(String input) {
        try {
            if (input == null || input.isEmpty() || !input.equals(input.trim()) || input.matches(".*[\\s\\p{Cntrl}].*")) return "";
            URI value = new URI(input);
            String host = value.getHost(), path = value.getRawPath();
            if (!"https".equals(value.getScheme()) || host == null || host.isEmpty() || host.endsWith(".")
                    || value.getRawUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null
                    || value.getPort() != -1 && value.getPort() != 443 || !(path == null || path.isEmpty() || "/".equals(path))) return "";
            return "https://" + host.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception invalid) { return ""; }
    }
    public static final class Command {
        public final String action, taskId;
        Command(String action, String taskId) { this.action = action; this.taskId = taskId; }
    }
    private NativeWebPolicy() {}
    public static boolean bundledFont(String method, String url) {
        return "GET".equals(method) && (ORIGIN + "/assets/fonts/MiSans-Regular.ttf").equals(url);
    }
    public static boolean validTaskId(String id) { return id != null && id.matches("[A-Za-z0-9._:-]{1,512}"); }
    public static boolean trusted(String url) {
        try {
            URI value = new URI(url);
            URI origin = new URI(ORIGIN);
            return !ORIGIN.isEmpty() && "https".equals(value.getScheme()) && origin.getHost() != null && origin.getHost().equals(value.getHost())
                    && value.getRawUserInfo() == null && (value.getPort() == -1 || value.getPort() == 443);
        } catch (Exception ignored) { return false; }
    }
    public static Command command(String committedUrl, String currentUrl, boolean mainFrame, boolean gesture,
            String url, String trackedId) {
        if (!mainFrame || !gesture || !trusted(committedUrl) || !trusted(currentUrl)) return null;
        try {
            URI value = new URI(url);
            if (!"agentmonitor".equals(value.getScheme()) || value.getRawUserInfo() != null || value.getPort() != -1
                    || value.getRawFragment() != null || (value.getRawPath() != null && !value.getRawPath().isEmpty())) return null;
            String action = value.getHost(), query = value.getRawQuery(), id = "";
            if ("track".equals(action) || "stop".equals(action)) {
                if (query == null || !query.startsWith("task_id=") || query.indexOf('&') >= 0 || query.indexOf(';') >= 0) return null;
                id = URLDecoder.decode(query.substring(8), "UTF-8");
                if (!validTaskId(id)) return null;
                if ("stop".equals(action) && !id.equals(trackedId)) return null;
            } else {
                if (query != null || !("connect".equals(action) || "settings".equals(action) || "logout".equals(action) || "google-profile".equals(action))) return null;
            }
            return new Command(action, id);
        } catch (Exception ignored) { return null; }
    }
}
