package com.agentmonitor.live;

public final class NativeScreenPolicyTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    public static void main(String[] args) {
        String id = "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345", token = "nrd_" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefg";
        check("codex".equals(NativeScreenPolicy.tool("codex")), "preserve selected Codex");
        check("claude".equals(NativeScreenPolicy.tool("claude")), "preserve selected Claude");
        check("all".equals(NativeScreenPolicy.tool(null)), "unknown filter defaults safely");
        for (String actual : new String[]{"codex", "claude"}) for (String status : new String[]{"running", "waiting", "error", "completed", "idle", "unknown"}) {
            boolean active = status.equals("running") || status.equals("waiting") || status.equals("error");
            check(NativeScreenPolicy.includes("all", "active", actual, status, false, false) == active, "active state selection");
            check(!NativeScreenPolicy.includes("all", "active", actual, status, false, true), "offline task never shown as current activity");
            check(NativeScreenPolicy.includes(actual, "all", actual, status, false, true), "all retains last-known tasks");
            check(!NativeScreenPolicy.includes(actual.equals("codex") ? "claude" : "codex", "all", actual, status, false, false), "refresh cannot bypass tool choice");
            check(!NativeScreenPolicy.includes("all", "all", actual, status, true, false), "archived tasks excluded from ordinary lists");
            check(NativeScreenPolicy.includes("all", "archived", actual, status, true, true), "archive independent of stale/activity");
        }
        check(!NativeScreenPolicy.stale(1000, 17000, false), "16-second boundary remains fresh");
        check(NativeScreenPolicy.stale(1000, 17001, false), "older snapshot becomes stale");
        check(NativeScreenPolicy.stale(1001, 1000, false), "clock regression fails closed");
        check(NativeScreenPolicy.stale(0, 1000, false), "never synced is stale");
        check(NativeScreenPolicy.stale(1000, 1000, true), "failed refresh is stale");
        connectionRecovery();
        check(NativeScreenPolicy.connected("full_app", false, true, token, 1001, 1000), "approved full-app token accepted");
        check(!NativeScreenPolicy.connected("full_app", false, true, token, 1000, 1000), "expiry inclusive");
        check(!NativeScreenPolicy.connected("full_app", true, true, token, 1001, 1000), "pending logout denies use");
        check(!NativeScreenPolicy.connected("read_only", false, true, token, 1001, 1000), "read-only cannot become workbench");
        check(!NativeScreenPolicy.connected("full_app", false, false, token, 1001, 1000), "unready claim not connected");
        for (String invalid : new String[]{null, "", "token", "nwt_" + token.substring(4), token + "\r\n", token + "x"})
            check(!NativeScreenPolicy.connected("full_app", false, true, invalid, 1001, 1000), "malformed credential fails closed");
        String origin = NativeWebPolicy.ORIGIN, path = "/#/pairing-confirm/" + id;
        check(id.equals(NativeScreenPolicy.pairingId(origin + path)), "fixed-origin QR accepted");
        check(id.equals(NativeScreenPolicy.pairingId(origin + ":443" + path)), "HTTPS default port accepted");
        check(id.equals(NativeScreenPolicy.pairingId("  " + id + "  ")), "manually entered request ID accepted");
        for (String invalid : new String[]{null, "", "x" + id, origin.replace("https:", "http:") + path,
                origin + ".attacker.test" + path, origin + ":444" + path, origin + "." + path,
                origin.replace("https://", "https://user@") + path, "https://attacker.test" + path,
                origin + "/?external=x#/pairing-confirm/" + id, origin + "/nested" + path,
                origin + "/#/pairing-confirm/%41" + id.substring(1), origin + path + "?approve=true",
                origin + "/#/native-connect/" + id, origin + "/#/pairing-confirm/" + id + "/approve",
                "javascript:" + origin + path, "file:///" + id, "intent://" + id})
            check(NativeScreenPolicy.pairingId(invalid) == null, "reject expanded or foreign pairing URL");
        System.out.println("NativeScreenPolicyTest: " + checks + " checks passed");
    }
    private static void connectionRecovery() {
        long lastSuccess = 1000;
        check(NativeScreenPolicy.connectionMessage(false, false, false, false).isEmpty(), "healthy snapshot has no warning");
        check(NativeScreenPolicy.connectionMessage(false, false, true, false).isEmpty(), "routine polling does not flash a banner");
        boolean staleAfterResume = NativeScreenPolicy.stale(lastSuccess, 30000, false);
        check("状态待更新 · 点此重试".equals(NativeScreenPolicy.connectionMessage(staleAfterResume, false, false, false)), "returning from background does not claim connection failed");
        check("正在更新…".equals(NativeScreenPolicy.connectionMessage(staleAfterResume, false, true, false)), "resume refresh explains old data without claiming outage");
        boolean staleAfterFailure = NativeScreenPolicy.stale(lastSuccess, 31000, true);
        check("暂时无法连接 · 点此重试".equals(NativeScreenPolicy.connectionMessage(staleAfterFailure, true, false, false)), "completed failure exposes explicit retry");
        check("正在更新…".equals(NativeScreenPolicy.connectionMessage(staleAfterFailure, true, true, false)), "retry immediately replaces prior failure with progress");
        check("请求较多，稍后自动重试".equals(NativeScreenPolicy.connectionMessage(true, true, false, true)), "server cooldown does not promise clickable retry");
        check("请求较多，稍后自动重试".equals(NativeScreenPolicy.connectionMessage(true, true, true, true)), "cooldown remains clear while another request is in flight");
        check("正在更新…".equals(NativeScreenPolicy.connectionMessage(true, true, true, false)), "retry after cooldown shows progress");
        lastSuccess = 32000;
        check(NativeScreenPolicy.connectionMessage(NativeScreenPolicy.stale(lastSuccess, 32000, false), false, false, false).isEmpty(), "successful retry clears warning");
    }
}
