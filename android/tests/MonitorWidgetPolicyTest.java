package com.agentmonitor.live;

import org.json.JSONArray;
import org.json.JSONObject;

/** Offline deterministic unit tests for Monitor 2×2 / 2×4 / 4×4 widget policy. */
public final class MonitorWidgetPolicyTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long nowMillis = 1_763_686_800_000L; // Fixed reference instant
        long nowSec = nowMillis / 1000L;
        String todayKey = NativeUsageFormat.day(nowMillis);

        JSONObject workbench = sampleWorkbench(nowSec);
        MonitorWidgetBridge.TrackingSnapshot activeTracking = new MonitorWidgetBridge.TrackingSnapshot(
                true,
                "codex:session-widget-01",
                "重构双端扫码配对与桌面小组件",
                "codex",
                "主力工作站",
                "执行中",
                7L,
                14L * 60_000L,
                46L * 60_000L,
                false,
                84_200L,
                385_000L);

        // 1. Disconnected privacy boundary
        MonitorWidgetPolicy.Model signedOut = MonitorWidgetPolicy.evaluate(
                MonitorWidgetPolicy.MODE_AUTO, false, workbench, null, activeTracking, nowMillis);
        check("未登录".equals(signedOut.primaryValue), "signed-out hides private metrics");
        check(MonitorWidgetPolicy.TARGET_HOME.equals(signedOut.clickTarget), "signed-out opens home login");
        check(!signedOut.showProgress, "signed-out hides progress bar");

        // 2. Auto mode + Idle -> selects lowest remaining non-expired fresh official quota + reset time
        MonitorWidgetPolicy.Model autoIdleQuota = MonitorWidgetPolicy.evaluate(
                MonitorWidgetPolicy.MODE_AUTO,
                true,
                workbench,
                null,
                MonitorWidgetBridge.TrackingSnapshot.idle(),
                nowMillis);
        check("antigravity".equals(autoIdleQuota.brandTool), "picks Antigravity with 12% over Codex 68% and expired 3%");
        check("剩余 12%".equals(autoIdleQuota.primaryValue), "formats lowest remaining percentage: " + autoIdleQuota.primaryValue);
        check(autoIdleQuota.valueWarning, "marks <=15% quota with warning color");
        check(autoIdleQuota.showProgress && autoIdleQuota.progress == 12, "progress bar matches 12%");
        check(autoIdleQuota.title.contains("Gemini 2.5 Pro") && autoIdleQuota.title.contains("5 小时额度"),
                "formats Antigravity model bucket + window: " + autoIdleQuota.title);
        check("1 小时 35 分钟后重置".equals(autoIdleQuota.footer),
                "attaches exact next reset countdown: " + autoIdleQuota.footer);
        check("今日 Token".equals(autoIdleQuota.sideLabel) && "38.5 万".equals(autoIdleQuota.sideValue),
                "2×4 side capsule shows today's compact token total: " + autoIdleQuota.sideValue);
        check(autoIdleQuota.row2.contains("Claude Code") && autoIdleQuota.row2.contains("剩余 45%"),
                "4×4 secondary row surfaces second-lowest quota: " + autoIdleQuota.row2);
        check(MonitorWidgetPolicy.TARGET_USAGE.equals(autoIdleQuota.clickTarget), "idle quota opens usage tab");

        // 3. Auto mode + Active Tracking -> switches immediately to tracked project details
        MonitorWidgetPolicy.Model autoTracking = MonitorWidgetPolicy.evaluate(
                MonitorWidgetPolicy.MODE_AUTO,
                true,
                workbench,
                null,
                activeTracking,
                nowMillis);
        check(autoTracking.primaryCompactTitle, "project title uses compact headline sizing");
        check("重构双端扫码配对与桌面小组件".equals(autoTracking.primaryValue), "shows tracked project title");
        check("codex".equals(autoTracking.brandTool), "uses tracked task tool icon");
        check(autoTracking.title.contains("执行中")
                && autoTracking.title.contains("主力工作站")
                && autoTracking.title.contains("已跟踪 14 分钟"),
                "shows live status + computer + elapsed time: " + autoTracking.title);
        check(autoTracking.footer.contains("本次 8.4 万")
                && autoTracking.footer.contains("今日 38.5 万")
                && autoTracking.footer.contains("剩余 46 分钟"),
                "tracking footer shows session/today tokens + remaining timer: " + autoTracking.footer);
        check(MonitorWidgetPolicy.TARGET_TASK.equals(autoTracking.clickTarget)
                && "codex:session-widget-01".equals(autoTracking.clickTaskId)
                && autoTracking.clickGeneration == 7L,
                "tapping tracking widget deep-links to tracked task");

        // 4. Auto mode + Idle without official quotas -> falls back to Today's Token Count (including history.days)
        JSONObject historyOnlyUsage = historyOnlyUsagePayload(todayKey);
        MonitorWidgetPolicy.Model autoIdleTokens = MonitorWidgetPolicy.evaluate(
                MonitorWidgetPolicy.MODE_AUTO,
                true,
                new JSONObject().put("devices", new JSONArray()).put("tasks", new JSONArray()),
                historyOnlyUsage,
                MonitorWidgetBridge.TrackingSnapshot.idle(),
                nowMillis);
        check("14.5 万".equals(autoIdleTokens.primaryValue),
                "falls back to today's token total when no official quota exists: " + autoIdleTokens.primaryValue);
        check("codex".equals(autoIdleTokens.brandTool), "uses dominant token tool icon");
        check(autoIdleTokens.title.contains("Codex 12 万") && autoIdleTokens.title.contains("Antigravity 2.5 万"),
                "shows per-tool token breakdown: " + autoIdleTokens.title);
        check(!autoIdleTokens.showProgress, "token-only view hides quota progress bar");

        // 5. Fixed modes stay pinned regardless of tracking state
        MonitorWidgetPolicy.Model fixedQuotaDuringTracking = MonitorWidgetPolicy.evaluate(
                MonitorWidgetPolicy.MODE_FIXED_QUOTA,
                true,
                workbench,
                null,
                activeTracking,
                nowMillis);
        check("剩余 12%".equals(fixedQuotaDuringTracking.primaryValue),
                "fixed_quota stays pinned to lowest official quota even while tracking");

        MonitorWidgetPolicy.Model fixedTokensDuringTracking = MonitorWidgetPolicy.evaluate(
                MonitorWidgetPolicy.MODE_FIXED_TOKENS,
                true,
                workbench,
                null,
                activeTracking,
                nowMillis);
        check("38.5 万".equals(fixedTokensDuringTracking.primaryValue),
                "fixed_tokens stays pinned to today's token count even while tracking");

        // 6. Fixed tasks mode prioritizes waiting approvals from workbench when TrackingService is idle
        MonitorWidgetPolicy.Model fixedTasksIdle = MonitorWidgetPolicy.evaluate(
                MonitorWidgetPolicy.MODE_FIXED_TASKS,
                true,
                workbench,
                null,
                MonitorWidgetBridge.TrackingSnapshot.idle(),
                nowMillis);
        check("审批数据库迁移脚本".equals(fixedTasksIdle.primaryValue),
                "fixed_tasks highlights waiting-approval task first: " + fixedTasksIdle.primaryValue);
        check(fixedTasksIdle.title.contains("等待批准") && fixedTasksIdle.title.contains("Mac Studio"),
                "fixed_tasks shows waiting status and computer: " + fixedTasksIdle.title);
        check(MonitorWidgetPolicy.TARGET_TASK.equals(fixedTasksIdle.clickTarget)
                && "claude:task-wait-02".equals(fixedTasksIdle.clickTaskId),
                "fixed_tasks deep-links to lead active task");

        System.out.println("MonitorWidgetPolicyTest passed (" + checks + " checks)");
    }

    private static JSONObject sampleWorkbench(long nowSec) {
        JSONArray devices = new JSONArray()
                .put(new JSONObject().put("id", "dev-win").put("name", "主力工作站").put("online", true))
                .put(new JSONObject().put("id", "dev-mac").put("name", "Mac Studio").put("online", true));

        JSONArray tasks = new JSONArray()
                .put(new JSONObject()
                        .put("id", "codex:session-widget-01")
                        .put("title", "重构双端扫码配对与桌面小组件")
                        .put("tool", "codex")
                        .put("device_id", "dev-win")
                        .put("status", "running")
                        .put("archived", false))
                .put(new JSONObject()
                        .put("id", "claude:task-wait-02")
                        .put("title", "审批数据库迁移脚本")
                        .put("tool", "claude")
                        .put("device_id", "dev-mac")
                        .put("status", "waiting")
                        .put("archived", false));

        JSONArray codexQuotas = new JSONArray()
                // Expired window with 3% left must be ignored!
                .put(new JSONObject()
                        .put("key", "5h")
                        .put("window_minutes", 300L)
                        .put("remaining_percent", 3.0)
                        .put("resets_at", nowSec - 60L)
                        .put("stale", false)
                        .put("source_name", "主力工作站"))
                .put(new JSONObject()
                        .put("key", "5h-live")
                        .put("window_minutes", 300L)
                        .put("remaining_percent", 68.0)
                        .put("resets_at", nowSec + 7200L)
                        .put("stale", false)
                        .put("source_name", "主力工作站"));

        JSONArray claudeQuotas = new JSONArray()
                .put(new JSONObject()
                        .put("key", "5h")
                        .put("window_minutes", 300L)
                        .put("remaining_percent", 45.0)
                        .put("resets_at", nowSec + 5400L)
                        .put("stale", false)
                        .put("source_name", "Mac Studio"));

        JSONArray agyQuotas = new JSONArray()
                .put(new JSONObject()
                        .put("key", "agy-pro")
                        .put("bucket_label", "gemini-2.5-pro")
                        .put("window_minutes", 300L)
                        .put("remaining_percent", 12.0)
                        .put("resets_at", nowSec + 95L * 60L)
                        .put("stale", false)
                        .put("source_name", "主力工作站"));

        JSONArray providers = new JSONArray()
                .put(new JSONObject().put("tool", "codex").put("today_tokens", 260_000L).put("quotas", codexQuotas))
                .put(new JSONObject().put("tool", "claude").put("today_tokens", 0L).put("quotas", claudeQuotas))
                .put(new JSONObject().put("tool", "antigravity").put("today_tokens", 125_000L).put("quotas", agyQuotas));

        return new JSONObject()
                .put("devices", devices)
                .put("tasks", tasks)
                .put("usage", new JSONObject().put("providers", providers));
    }

    private static JSONObject historyOnlyUsagePayload(String todayKey) {
        JSONArray codexDays = new JSONArray()
                .put(new JSONObject().put("day", todayKey).put("tokens", 120_000L).put("coverage", "complete"));
        JSONArray agyDays = new JSONArray()
                .put(new JSONObject().put("day", todayKey).put("tokens", 25_000L).put("coverage", "partial"));

        JSONArray providers = new JSONArray()
                .put(new JSONObject()
                        .put("tool", "codex")
                        .put("history", new JSONObject().put("days", codexDays))
                        .put("quotas", new JSONArray()))
                .put(new JSONObject()
                        .put("tool", "antigravity")
                        .put("history", new JSONObject().put("days", agyDays))
                        .put("quotas", new JSONArray()));
        return new JSONObject().put("providers", providers);
    }
}
