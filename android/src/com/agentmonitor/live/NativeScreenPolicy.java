package com.agentmonitor.live;

import java.net.URI;

/** Native navigation/filter rules; server refreshes never own the selected filter. */
public final class NativeScreenPolicy {
    private NativeScreenPolicy() {}
    public static String tool(String value) { return "claude".equals(value) || "codex".equals(value) ? value : "all"; }
    public static String scope(String value) { return "all".equals(value) || "archived".equals(value) ? value : "active"; }
    public static boolean includes(String selectedTool, String selectedScope, String actualTool, String status, boolean archived, boolean stale) {
        if (!"all".equals(tool(selectedTool)) && !tool(selectedTool).equals(actualTool)) return false;
        if ("archived".equals(scope(selectedScope))) return archived;
        if (archived) return false;
        return "all".equals(scope(selectedScope)) || (!stale && ("running".equals(status) || "waiting".equals(status) || "error".equals(status)));
    }
    public static boolean stale(long lastSuccess, long now, boolean failed) { return failed || lastSuccess <= 0 || now < lastSuccess || now - lastSuccess > 16000; }
    /** Old data alone does not prove a disconnection; only a completed failure does. */
    public static String connectionMessage(boolean stale, boolean failed, boolean refreshing, boolean coolingDown) {
        if (!stale) return "";
        if (coolingDown) return "请求较多，稍后自动重试";
        if (refreshing) return "正在更新…";
        if (failed) return "暂时无法连接 · 点此重试";
        return "状态待更新 · 点此重试";
    }
    public static boolean connected(String mode, boolean pendingLogout, boolean ready, String token, long expires, long now) {
        return "full_app".equals(mode) && !pendingLogout && ready && token != null && token.matches("nrd_[A-Za-z0-9_-]{43}") && expires > now;
    }
    public static String pairingId(String scanned) {
        if (scanned == null) return null;
        try {
            if (scanned.trim().matches("[A-Za-z0-9_-]{32}")) return scanned.trim();
            URI uri = new URI(scanned.trim());
            URI origin = new URI(NativeWebPolicy.ORIGIN);
            if (!origin.getScheme().equals(uri.getScheme()) || !origin.getHost().equals(uri.getHost()) || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || !"/".equals(uri.getRawPath()) || uri.getRawQuery() != null) return null;
            String fragment = uri.getRawFragment();
            if (fragment == null || !fragment.matches("/pairing-confirm/[A-Za-z0-9_-]{32}")) return null;
            return fragment.substring("/pairing-confirm/".length());
        } catch (Exception ignored) { return null; }
    }
}
