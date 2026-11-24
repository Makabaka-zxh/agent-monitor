package com.agentmonitor.live;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pure presentation and strict URL validation policy for local dev server previews.
 * Only RFC1918 LAN IPv4, Tailscale CGNAT IPv4 (100.64.0.0/10), or *.ts.net MagicDNS
 * hosts on ports 1024..65535 are accepted.
 */
public final class NativeDevPreviewPolicy {
    private static final Pattern HOST_RE = Pattern.compile(
            "^(?:[0-9]{1,3}(?:\\.[0-9]{1,3}){3}|[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+)$"
    );
    private static final Pattern PROCESS_RE = Pattern.compile("^[a-z0-9._-]{1,32}$");
    private static final Pattern PROJECT_ID_RE = Pattern.compile("^[0-9a-f]{24}$");

    private NativeDevPreviewPolicy() { }

    public static final class PreviewItem {
        public final int port;
        public final String bindScope;
        public final String process;
        public final String projectId;
        public final boolean matchedProject;
        public final List<String> urls;
        public final String primaryUrl;
        public final String badge;
        public final String buttonLabel;
        public final String subtitle;
        public final String bindFixPrompt;

        PreviewItem(int port, String bindScope, String process, String projectId,
                    boolean matchedProject, List<String> urls) {
            this.port = port;
            this.bindScope = bindScope;
            this.process = process;
            this.projectId = projectId;
            this.matchedProject = matchedProject;
            this.urls = Collections.unmodifiableList(urls);
            this.primaryUrl = urls.isEmpty() ? "" : urls.get(0);
            this.badge = ":" + port + (process.isEmpty() ? "" : " · " + process);
            if (!primaryUrl.isEmpty()) {
                this.buttonLabel = "在手机预览 (:" + port + ")";
                String hostText = primaryUrl.replaceFirst("^https?://", "").replaceAll("/$", "");
                this.subtitle = (matchedProject ? "当前项目开发端口 · " : "电脑活跃端口 · ") + hostText;
            } else if ("loopback".equals(bindScope)) {
                this.buttonLabel = ":" + port + " 仅本机监听";
                this.subtitle = "当前仅绑定 127.0.0.1:" + port + "，手机无法直连；可让代理改为 0.0.0.0";
            } else {
                this.buttonLabel = ":" + port + " 待同步预览地址";
                this.subtitle = "端口已监听网络，暂未识别电脑的局域网或 Tailscale 地址";
            }
            this.bindFixPrompt = "请将当前开发服务器改为监听 0.0.0.0:" + port
                    + "（例如添加 --host 0.0.0.0 参数），以便手机通过 Tailscale 或局域网直接预览。";
        }

        public boolean networkReachable() {
            return !primaryUrl.isEmpty();
        }

        public boolean loopbackOnly() {
            return "loopback".equals(bindScope);
        }
    }

    public static String validHost(String raw) {
        if (raw == null) return "";
        String host = raw.trim().toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.length() > 120 || !HOST_RE.matcher(host).matches()) return "";
        String[] octets = host.split("\\.", -1);
        if (octets.length == 4 && allDigits(octets)) {
            int[] nums = new int[4];
            for (int i = 0; i < 4; i++) {
                try {
                    nums[i] = Integer.parseInt(octets[i]);
                } catch (NumberFormatException invalid) {
                    return "";
                }
                if (nums[i] < 0 || nums[i] > 255) return "";
            }
            int a = nums[0], b = nums[1];
            if (a == 10) return host;
            if (a == 192 && b == 168) return host;
            if (a == 172 && b >= 16 && b <= 31) return host;
            if (a == 100 && b >= 64 && b <= 127) return host;
            return "";
        }
        if (host.endsWith(".ts.net") && octets.length >= 3) {
            return host;
        }
        return "";
    }

    private static boolean allDigits(String[] parts) {
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') return false;
            }
        }
        return true;
    }

    public static String buildPreviewUrl(String host, int port) {
        String cleanHost = validHost(host);
        if (cleanHost.isEmpty() || port < 1024 || port > 65535) return "";
        return "http://" + cleanHost + ":" + port + "/";
    }

    public static List<String> previewHosts(JSONObject computer) {
        if (computer == null) return Collections.emptyList();
        JSONArray raw = computer.optJSONArray("preview_hosts");
        if (raw == null || raw.length() == 0) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(raw.length(), 8); i++) {
            String candidate = raw.optString(i);
            String valid = validHost(candidate == null ? "" : candidate);
            if (!valid.isEmpty() && !result.contains(valid)) {
                result.add(valid);
            }
        }
        return result;
    }

    public static List<PreviewItem> itemsFor(JSONObject task, JSONObject computer, boolean stale) {
        if (computer == null || stale || !computer.optBoolean("online")) {
            return Collections.emptyList();
        }
        JSONArray ports = computer.optJSONArray("dev_ports");
        if (ports == null || ports.length() == 0) {
            return Collections.emptyList();
        }
        List<String> hosts = previewHosts(computer);
        String taskProjectId = "";
        if (task != null) {
            String candidate = task.optString("project_id", "").trim();
            if (PROJECT_ID_RE.matcher(candidate).matches()) {
                taskProjectId = candidate;
            }
        }
        List<PreviewItem> matched = new ArrayList<>();
        List<PreviewItem> fallback = new ArrayList<>();
        for (int i = 0; i < Math.min(ports.length(), 12); i++) {
            JSONObject entry = ports.optJSONObject(i);
            if (entry == null) continue;
            int port = entry.optInt("port", -1);
            if (port < 1024 || port > 65535) continue;
            String scope = entry.optString("bind_scope", "");
            if (!"network".equals(scope) && !"loopback".equals(scope)) continue;
            String proc = entry.optString("process", "").trim().toLowerCase(Locale.ROOT);
            if (!PROCESS_RE.matcher(proc).matches()) proc = "";
            String projId = entry.optString("project_id", "").trim();
            if (!PROJECT_ID_RE.matcher(projId).matches()) projId = "";

            List<String> urls = new ArrayList<>();
            if ("network".equals(scope)) {
                for (String host : hosts) {
                    String url = buildPreviewUrl(host, port);
                    if (!url.isEmpty()) urls.add(url);
                }
            }
            boolean exactMatch = !taskProjectId.isEmpty() && taskProjectId.equals(projId);
            if (task == null) {
                matched.add(new PreviewItem(port, scope, proc, projId, !projId.isEmpty(), urls));
            } else if (exactMatch) {
                matched.add(new PreviewItem(port, scope, proc, projId, true, urls));
            } else if (projId.isEmpty()) {
                fallback.add(new PreviewItem(port, scope, proc, projId, false, urls));
            }
        }
        if (task == null) {
            return matched.size() > 6 ? matched.subList(0, 6) : matched;
        }
        if (!matched.isEmpty()) {
            return matched.size() > 4 ? matched.subList(0, 4) : matched;
        }
        return fallback.size() > 3 ? fallback.subList(0, 3) : fallback;
    }
}
