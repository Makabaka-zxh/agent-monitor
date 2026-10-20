package com.agentmonitor.live;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;

/** Navigation targets only. These links never authorize a connection or start tracking. */
public final class NativeNavigationPolicy {
    public static final String NOTIFICATION_ACTION = "com.agentmonitor.live.OPEN_TASK";
    private NativeNavigationPolicy() {}
    private static String encode(String id) throws Exception { return URLEncoder.encode(id, "UTF-8").replace("+", "%20"); }

    public static String safePage(String page) {
        if (page == null) return "tasks";
        switch (page) {
            case "tasks": case "task": case "usage": case "account":
            case "devices": case "device": case "profile": case "sync": case "sessions":
            case "pairing": case "pairing-confirm": return page;
            default: return "tasks";
        }
    }

    /** The second tab is usage; computer management belongs to My Account. */
    public static String[] rootPages() { return new String[]{"tasks", "usage", "account"}; }

    public static boolean rootPage(String page) {
        return "tasks".equals(page) || "usage".equals(page) || "account".equals(page);
    }

    /** A task reached from a computer stays in that computer's navigation branch. */
    public static String tabForPage(String page, String taskReturnDevice) {
        switch (safePage(page)) {
            case "tasks": return "tasks";
            case "task": return validReturnDevice(taskReturnDevice) ? "account" : "tasks";
            case "usage": return "usage";
            default: return "account";
        }
    }

    public static final class BackTarget {
        public final String page, id;
        private BackTarget(String page, String id) { this.page = page; this.id = id; }
    }

    /** Null means a root page may finish; descendants always return to their owner. */
    public static BackTarget backTarget(String page, String taskReturnDevice) {
        switch (safePage(page)) {
            case "tasks": case "usage": case "account": return null;
            case "task": return validReturnDevice(taskReturnDevice)
                    ? new BackTarget("device", taskReturnDevice) : new BackTarget("tasks", "");
            case "device": case "pairing": return new BackTarget("devices", "");
            case "pairing-confirm": return new BackTarget("pairing", "");
            default: return new BackTarget("account", "");
        }
    }

    private static boolean validReturnDevice(String id) { return NativeWebPolicy.validTaskId(id); }

    public static String notificationUri(String taskId, long generation) {
        if (!NativeWebPolicy.validTaskId(taskId) || generation < 0) return null;
        try { return "agentmonitor://task/" + encode(taskId) + "?generation=" + generation; }
        catch (Exception ignored) { return null; }
    }

    public static String notificationUrl(String action, String data) {
        if (!NOTIFICATION_ACTION.equals(action)) return null;
        try {
            URI uri = new URI(data);
            if (!"agentmonitor".equals(uri.getScheme()) || !"task".equals(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().startsWith("/")
                    || uri.getRawQuery() == null || !uri.getRawQuery().matches("generation=[0-9]{1,19}")) return null;
            long generation = Long.parseLong(uri.getRawQuery().substring(11));
            String id = URLDecoder.decode(uri.getRawPath().substring(1), "UTF-8");
            if (generation < 0 || !NativeWebPolicy.validTaskId(id)) return null;
            return NativeWebPolicy.ORIGIN + "/#/task/" + encode(id);
        } catch (Exception ignored) { return null; }
    }

    public static int previousTrustedIndex(String[] urls, int currentIndex) {
        if (urls == null || currentIndex < 0 || currentIndex >= urls.length) return -1;
        for (int index = currentIndex - 1; index >= 0; index--) if (NativeWebPolicy.trusted(urls[index])) return index;
        return -1;
    }

    public static boolean restorableHistory(String[] urls, int currentIndex, boolean logoutPending) {
        if (logoutPending || urls == null || urls.length == 0 || currentIndex < 0 || currentIndex >= urls.length) return false;
        for (String url : urls) if (!NativeWebPolicy.trusted(url)) return false;
        return true;
    }
}
