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
            case "devices": case "device": case "profile": case "sync": case "sync-diagnostics": case "sessions": case "settings":
            case "settings-appearance": case "settings-background": case "settings-notifications": case "settings-support": case "settings-security":
            case "pairing": case "pairing-confirm": return page;
            default: return "tasks";
        }
    }

    /** The second tab is usage; computer management belongs to My Account. */
    public static String[] rootPages() { return new String[]{"tasks", "usage", "account"}; }

    public static boolean rootPage(String page) {
        return "tasks".equals(page) || "usage".equals(page) || "account".equals(page);
    }

    /** Presentation only: follows the existing page tree without changing navigation state. */
    public static int transitionDirection(String previous, String next) {
        return transitionDirection(previous, next, "");
    }

    public static int transitionDirection(String previous, String next, String taskReturnDevice) {
        if (previous == null || next == null || previous.equals(next)
                || !previous.equals(safePage(previous)) || !next.equals(safePage(next))) return 0;
        // Task details can belong to either the workbench or a computer's task list.
        if ("device".equals(previous) && "task".equals(next)) return 1;
        if ("task".equals(previous) && "device".equals(next)) return -1;
        if (ancestor(next, previous, taskReturnDevice)) return -1;
        if (ancestor(previous, next, taskReturnDevice)) return 1;
        if (rootPage(previous) || rootPage(next)) {
            return Integer.compare(rootOrder(next, taskReturnDevice), rootOrder(previous, taskReturnDevice));
        }
        // Sibling pages have no parent/child direction; keep them spatially stable.
        return 0;
    }

    private static boolean ancestor(String candidate, String page, String taskReturnDevice) {
        for (int depth = 0; depth < 5; depth++) {
            BackTarget parent = backTarget(page, taskReturnDevice);
            if (parent == null) return false;
            if (candidate.equals(parent.page)) return true;
            page = parent.page;
        }
        return false;
    }

    private static int rootOrder(String page, String taskReturnDevice) {
        String root = tabForPage(page, taskReturnDevice);
        return "tasks".equals(root) ? 0 : "usage".equals(root) ? 1 : 2;
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
            case "settings-background": return new BackTarget("settings-appearance", "");
            case "settings-appearance": case "settings-notifications": case "settings-support": case "settings-security":
                return new BackTarget("settings", "");
            default: return new BackTarget("account", "");
        }
    }

    /** The third level uses the existing settings sheets, above a stable second-level page. */
    public static String settingsSheetParent(String sheet) {
        if (sheet == null) return "";
        switch (sheet) {
            case "display": case "privacy": return "settings-appearance";
            case "notifications": case "background": case "alerts": return "settings-notifications";
            case "system-help": return "settings-support";
            default: return "";
        }
    }

    /** Restore local UI only for the same connected account and the sheet's exact owner. */
    public static String restorableSettingsSheet(String page, String sheet, String savedFingerprint, String currentFingerprint) {
        if (currentFingerprint == null || currentFingerprint.isEmpty() || !currentFingerprint.equals(savedFingerprint)) return "";
        String parent = settingsSheetParent(sheet);
        return !parent.isEmpty() && parent.equals(page) ? sheet : "";
    }

    public static String settingsTitle(String page) {
        if (page == null) return "";
        switch (page) {
            case "settings": return "设置";
            case "settings-appearance": return "显示与隐私";
            case "settings-background": return "应用背景";
            case "settings-notifications": return "通知与提醒";
            case "settings-support": return "连接与适配";
            case "settings-security": return "账号与安全";
            default: return "";
        }
    }

    public static String settingsHelp(String page) {
        if (page == null) return "";
        switch (page) {
            case "settings": return "按显示、通知、连接和账号分类管理\n选择分类后，再进入具体设置\n\n原有设置会保留，打开页面不会改变配置";
            case "settings-appearance": return "应用背景管理纯色与每日壁纸\n显示内容管理实况预览与信息密度\n锁屏隐私单独管理是否公开任务详情";
            case "settings-background": return "背景只影响 Monitor 内的显示\n\n选择 Bing 每日壁纸后，联网获取可用的当日图片\n离线时沿用缓存，图片署名可在此查看";
            case "settings-notifications": return "分别管理通知权限、后台运行和用量提醒\n\n打开系统设置不会自动授予权限；提醒仍使用原有规则";
            case "settings-support": return "查看当前设备的系统适配帮助，或管理服务器地址和应用更新\n\n切换服务器仍需确认，已有连接不会在浏览页面时改变";
            case "settings-security": return "退出登录会断开当前手机并结束实况\n电脑上的任务不受影响\n\n退出前仍会要求确认";
            default: return "";
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
