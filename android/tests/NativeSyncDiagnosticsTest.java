package com.agentmonitor.live;

import java.time.ZoneOffset;
import org.json.JSONObject;

/** Offline unit checks for Sync Diagnostics timestamps, leg durations, and clock-skew safety. */
public final class NativeSyncDiagnosticsTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        NativeSyncDiagnostics.Sample empty = NativeSyncDiagnostics.empty();
        check("尚未记录".equals(NativeSyncDiagnostics.formatDuration(empty.firstScreenMs)), "empty first-screen duration");
        check("暂未提供".equals(NativeSyncDiagnostics.formatTimestamp(0, ZoneOffset.UTC)), "missing timestamp");

        JSONObject timings = new JSONObject()
                .put("computer_completed_at", "2026-11-22T15:00:01.100Z")
                .put("server_received_at", "2026-11-22T15:00:01.350Z")
                .put("server_generated_at", "2026-11-22T15:00:01.400Z");
        JSONObject snapshot = new JSONObject().put("sync_timings", timings);
        long phoneMs = NativeSyncDiagnostics.parseIsoMillis("2026-11-22T15:00:05.600Z");
        NativeSyncDiagnostics.Sample first = NativeSyncDiagnostics.recordWorkbench(
                empty, snapshot, 4250, phoneMs, true);
        check(first.firstScreenMs == 4250 && first.lastRefreshMs == 4250, "first screen records initial duration");
        check("15:00:01.100".equals(NativeSyncDiagnostics.formatTimestamp(first.computerCompletedMs, ZoneOffset.UTC)),
                "computer_completed_at formatted to milliseconds");
        check("15:00:01.350".equals(NativeSyncDiagnostics.formatTimestamp(first.serverReceivedMs, ZoneOffset.UTC)),
                "server_received_at formatted to milliseconds");
        check("15:00:05.600".equals(NativeSyncDiagnostics.formatTimestamp(first.phoneDisplayedMs, ZoneOffset.UTC)),
                "phone_displayed_at formatted to milliseconds");
        check("250 ms".equals(NativeSyncDiagnostics.formatLeg(first.computerCompletedMs, first.serverReceivedMs)),
                "computer-to-server leg duration");
        check("4.25 秒 (4250 ms)".equals(NativeSyncDiagnostics.formatLeg(first.serverReceivedMs, first.phoneDisplayedMs)),
                "server-to-phone leg duration pinpoints 4.25s bottleneck");
        check(NativeSyncDiagnostics.diagnosis(first).contains("服务收到 → 手机显示"),
                "diagnosis pinpoints server-to-phone leg when phone fetch took 4.25s");

        NativeSyncDiagnostics.Sample second = NativeSyncDiagnostics.recordWorkbench(
                first, snapshot, 180, phoneMs + 2000, false);
        check(second.firstScreenMs == 4250 && second.lastRefreshMs == 180,
                "subsequent refresh retains latest first-screen duration while updating refresh duration");

        check(NativeSyncDiagnostics.formatLeg(2000, 1500).startsWith("时钟偏差"),
                "clock skew never reports a misleading positive duration");
        System.out.println("NativeSyncDiagnosticsTest: " + checks + " checks passed");
    }
}
