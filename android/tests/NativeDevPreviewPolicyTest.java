package com.agentmonitor.live;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class NativeDevPreviewPolicyTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        check("100.88.10.20".equals(NativeDevPreviewPolicy.validHost("100.88.10.20")), "Tailscale CGNAT IPv4 accepted");
        check("192.168.1.42".equals(NativeDevPreviewPolicy.validHost("192.168.1.42")), "LAN IPv4 accepted");
        check("devbox.tailnet-example.ts.net".equals(NativeDevPreviewPolicy.validHost("devbox.tailnet-example.ts.net.")), "MagicDNS accepted");
        check("".equals(NativeDevPreviewPolicy.validHost("127.0.0.1")), "loopback rejected as remote host");
        check("".equals(NativeDevPreviewPolicy.validHost("8.8.8.8")), "public IP rejected");
        check("".equals(NativeDevPreviewPolicy.validHost("evil.example.com")), "arbitrary domain rejected");
        check("http://100.88.10.20:5173/".equals(NativeDevPreviewPolicy.buildPreviewUrl("100.88.10.20", 5173)), "valid preview URL built");
        check("".equals(NativeDevPreviewPolicy.buildPreviewUrl("100.88.10.20", 80)), "privileged port rejected");

        String projA = "aaaaaaaaaaaaaaaaaaaaaaaa";
        String projB = "bbbbbbbbbbbbbbbbbbbbbbbb";
        JSONObject computer = new JSONObject();
        computer.put("id", "dev-1");
        computer.put("online", true);
        JSONArray hosts = new JSONArray();
        hosts.put("100.88.10.20");
        hosts.put("192.168.1.42");
        computer.put("preview_hosts", hosts);

        JSONArray ports = new JSONArray();
        JSONObject p1 = new JSONObject();
        p1.put("port", 5173);
        p1.put("bind_scope", "network");
        p1.put("process", "node");
        p1.put("project_id", projA);
        ports.put(p1);

        JSONObject p2 = new JSONObject();
        p2.put("port", 8000);
        p2.put("bind_scope", "loopback");
        p2.put("process", "python");
        p2.put("project_id", projB);
        ports.put(p2);
        computer.put("dev_ports", ports);

        JSONObject taskA = new JSONObject();
        taskA.put("project_id", projA);
        List<NativeDevPreviewPolicy.PreviewItem> itemsA = NativeDevPreviewPolicy.itemsFor(taskA, computer, false);
        check(itemsA.size() == 1, "matched exact project port");
        check(itemsA.get(0).networkReachable(), "network port reachable");
        check("http://100.88.10.20:5173/".equals(itemsA.get(0).primaryUrl), "prefers Tailscale first");
        check(itemsA.get(0).urls.size() == 2, "retains LAN fallback URL");

        JSONObject taskB = new JSONObject();
        taskB.put("project_id", projB);
        List<NativeDevPreviewPolicy.PreviewItem> itemsB = NativeDevPreviewPolicy.itemsFor(taskB, computer, false);
        check(itemsB.size() == 1 && itemsB.get(0).loopbackOnly(), "surfaces loopback-only hint for project B");
        check(itemsB.get(0).bindFixPrompt.contains("0.0.0.0:8000"), "includes 0.0.0.0 fix prompt");

        check(NativeDevPreviewPolicy.itemsFor(taskA, computer, true).isEmpty(), "stale snapshot hides live ports");
        System.out.println("NativeDevPreviewPolicyTest: " + checks + " checks passed");
    }
}
