package com.agentmonitor.live;

/** Offline payload/privacy boundaries; no Android settings or real model request is made. */
public final class XiaomiLiveNotificationTest {
    private static int checks;

    public static void main(String[] args) {
        XiaomiLiveCapabilities enabled = capabilities(3, true, true);
        String codex = XiaomiLiveNotification.payload(DeviceBrand.XIAOMI, enabled, "codex", true);
        String claude = XiaomiLiveNotification.payload(DeviceBrand.XIAOMI, enabled, "claude", true);
        check(codex != null && claude != null, "both supported providers produce a minimal candidate");
        check(codex.contains("\"aodTitle\":\"Codex\"") && claude.contains("\"aodTitle\":\"Claude\""),
                "always-on content remains the provider label");
        check(codex.replace("Codex", "Claude").equals(claude), "provider is the only variable payload text");
        check(codex.length() < 1024, "minimal candidate has a bounded IPC payload");
        check(codex.contains("\"business\":\"agent_monitor\""), "scene does not impersonate taxi or media");
        check(codex.contains("\"filterWhenNoPermission\":false"), "OEM permission failure preserves ordinary notifications");
        for (String forbidden : new String[]{"task", "result", "token", "quota", "account", "reopen", "whiteList", "actionIntent", "shareData"}) {
            check(!codex.contains(forbidden), "payload excludes " + forbidden);
        }
        for (DeviceBrand brand : DeviceBrand.values()) {
            if (brand != DeviceBrand.XIAOMI) {
                check(XiaomiLiveNotification.payload(brand, enabled, "codex", true) == null,
                        "other brand keeps existing notification: " + brand);
            }
        }
        check(XiaomiLiveNotification.payload(null, enabled, "codex", true) == null, "unknown brand is ineligible");
        check(XiaomiLiveNotification.payload(DeviceBrand.XIAOMI, null, "codex", true) == null, "missing observation is ineligible");
        check(XiaomiLiveNotification.payload(DeviceBrand.XIAOMI, enabled, "codex", false) == null,
                "completion cannot leave a continuing OEM activity");
        for (int version : new int[]{-1, 0, 1, 2, 4, Integer.MAX_VALUE}) {
            check(XiaomiLiveNotification.payload(DeviceBrand.XIAOMI, capabilities(version, true, true), "codex", true) == null,
                    "unverified protocol is ineligible: " + version);
        }
        for (Boolean island : new Boolean[]{null, false, true}) {
            for (Boolean permission : new Boolean[]{null, false, true}) {
                boolean expected = Boolean.TRUE.equals(island) && Boolean.TRUE.equals(permission);
                check((XiaomiLiveNotification.payload(DeviceBrand.XIAOMI, capabilities(3, island, permission), "codex", true) != null) == expected,
                        "both independent capability gates must be explicitly enabled");
            }
        }
        for (String tool : new String[]{null, "", "Codex", "CLAUDE", "claude code", "codex ", "task-title", "codex\",\"title\":\"secret", "私密任务"}) {
            check(XiaomiLiveNotification.payload(DeviceBrand.XIAOMI, enabled, tool, true) == null,
                    "arbitrary display input cannot enter OEM content");
        }
        if (args.length > 0 && "--payloads".equals(args[0])) {
            System.out.println(codex);
            System.out.println(claude);
        } else System.out.println("XiaomiLiveNotificationTest: " + checks + " checks passed");
    }

    private static XiaomiLiveCapabilities capabilities(int protocol, Boolean island, Boolean permission) {
        return XiaomiLiveCapabilities.read(new XiaomiLiveCapabilities.Probe() {
            public int protocolVersion() { return protocol; }
            public Boolean islandFeature() { return island; }
            public Boolean focusPermission() { return permission; }
        });
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
