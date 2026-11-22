package com.agentmonitor.live;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.json.JSONObject;

/** Pure timing model for My Account -> Sync Diagnostics. No sockets or private task text. */
public final class NativeSyncDiagnostics {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static final class Sample {
        public final long firstScreenMs;
        public final long lastRefreshMs;
        public final long computerCompletedMs;
        public final long serverReceivedMs;
        public final long phoneDisplayedMs;

        public Sample(long firstScreenMs, long lastRefreshMs,
                      long computerCompletedMs, long serverReceivedMs, long phoneDisplayedMs) {
            this.firstScreenMs = firstScreenMs >= 0 ? firstScreenMs : -1;
            this.lastRefreshMs = lastRefreshMs >= 0 ? lastRefreshMs : -1;
            this.computerCompletedMs = Math.max(0, computerCompletedMs);
            this.serverReceivedMs = Math.max(0, serverReceivedMs);
            this.phoneDisplayedMs = Math.max(0, phoneDisplayedMs);
        }
    }

    private NativeSyncDiagnostics() { }

    public static Sample empty() {
        return new Sample(-1, -1, 0, 0, 0);
    }

    public static Sample recordWorkbench(Sample previous, JSONObject snapshot,
                                         long requestDurationMs, long displayedAtMs, boolean wasFirstScreen) {
        long prevFirst = previous == null ? -1 : previous.firstScreenMs;
        long firstScreen = wasFirstScreen && requestDurationMs >= 0
                ? requestDurationMs
                : (prevFirst >= 0 ? prevFirst : requestDurationMs);
        long refresh = requestDurationMs >= 0 ? requestDurationMs
                : (previous == null ? -1 : previous.lastRefreshMs);
        JSONObject timings = snapshot == null ? null : snapshot.optJSONObject("sync_timings");
        long computerMs = parseIsoMillis(timings == null ? "" : nullableString(timings, "computer_completed_at"));
        long serverMs = parseIsoMillis(timings == null ? "" : nullableString(timings, "server_received_at"));
        if (serverMs <= 0 && snapshot != null) {
            serverMs = parseIsoMillis(nullableString(snapshot, "generated_at"));
        }
        return new Sample(firstScreen, refresh, computerMs, serverMs, Math.max(0, displayedAtMs));
    }

    public static long parseIsoMillis(String iso) {
        if (iso == null || iso.trim().isEmpty()) return 0;
        try {
            return Instant.parse(iso.trim()).toEpochMilli();
        } catch (Exception invalid) {
            return 0;
        }
    }

    public static String formatDuration(long ms) {
        if (ms < 0) return "尚未记录";
        if (ms < 1000) return ms + " ms";
        return String.format(Locale.ROOT, "%.2f 秒 (%d ms)", ms / 1000.0, ms);
    }

    public static String formatTimestamp(long epochMs, ZoneId zone) {
        if (epochMs <= 0) return "暂未提供";
        ZoneId targetZone = zone == null ? ZoneId.systemDefault() : zone;
        return CLOCK.withZone(targetZone).format(Instant.ofEpochMilli(epochMs));
    }

    public static String formatLeg(long fromMs, long toMs) {
        if (fromMs <= 0 || toMs <= 0) return "暂未提供";
        long delta = toMs - fromMs;
        if (delta < 0) return "时钟偏差 (" + delta + " ms)";
        return formatDuration(delta);
    }

    public static String diagnosis(Sample sample) {
        if (sample == null || (sample.firstScreenMs < 0 && sample.phoneDisplayedMs <= 0)) {
            return "打开工作台完成一次同步后，这里会记录首屏耗时以及电脑完成、服务收到、手机显示三个时间点";
        }
        long computerToServer = sample.computerCompletedMs > 0 && sample.serverReceivedMs >= sample.computerCompletedMs
                ? sample.serverReceivedMs - sample.computerCompletedMs : -1;
        long serverToPhone = sample.serverReceivedMs > 0 && sample.phoneDisplayedMs >= sample.serverReceivedMs
                ? sample.phoneDisplayedMs - sample.serverReceivedMs : -1;
        if (sample.lastRefreshMs >= 3000 || sample.firstScreenMs >= 3000) {
            if (computerToServer >= 2500 && computerToServer >= serverToPhone) {
                return "最近一次耗时主要在「电脑完成 → 服务收到」，可检查电脑端采集间隔或到服务的上传网络";
            }
            return "最近一次耗时主要在「服务收到 → 手机显示」（手机请求耗时 "
                    + formatDuration(sample.lastRefreshMs >= 0 ? sample.lastRefreshMs : sample.firstScreenMs)
                    + "），可检查手机网络或服务响应速度";
        }
        if (computerToServer >= 3000) {
            return "手机读取正常，但电脑最近一次上报距服务收到间隔较长（" + formatDuration(computerToServer) + "）";
        }
        return "最近一次三段同步耗时正常；若再次遇到 4–6 秒延迟，可直接对比下方三个时间点定位慢在哪一段";
    }

    private static String nullableString(JSONObject obj, String key) {
        if (obj == null || obj.isNull(key)) return "";
        Object val = obj.opt(key);
        return val instanceof String ? (String) val : "";
    }
}
