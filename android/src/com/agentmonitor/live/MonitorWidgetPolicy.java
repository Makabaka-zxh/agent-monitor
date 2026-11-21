package com.agentmonitor.live;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pure presentation policy for Monitor 2×2, 2×4, and 4×4 home screen widgets.
 *
 * Modes:
 * - {@link #MODE_AUTO}: When tracking is active, shows the tracked project/task title, tool brand,
 *   computer, and live state. When idle, shows whichever official quota window has the lowest remaining
 *   percentage together with its next reset time; if no official quota window is available, displays
 *   today's observed local Token total and per-tool breakdown.
 * - {@link #MODE_FIXED_QUOTA}: Always prioritizes lowest remaining official quota + next reset time.
 * - {@link #MODE_FIXED_TOKENS}: Always prioritizes today's observed local Token usage + per-tool split.
 * - {@link #MODE_FIXED_TASKS}: Always prioritizes the tracked project or active running/waiting tasks.
 */
public final class MonitorWidgetPolicy {
    public static final String MODE_AUTO = "auto";
    public static final String MODE_FIXED_QUOTA = "fixed_quota";
    public static final String MODE_FIXED_TOKENS = "fixed_tokens";
    public static final String MODE_FIXED_TASKS = "fixed_tasks";

    public static final String TARGET_HOME = "home";
    public static final String TARGET_TASKS = "tasks";
    public static final String TARGET_USAGE = "usage";
    public static final String TARGET_TASK = "task";

    public static final int TONE_ACCENT = 0;
    public static final int TONE_CLAUDE = 1;
    public static final int TONE_RUNNING = 2;
    public static final int TONE_WARNING = 3;

    private static final String[] TOOLS = {"codex", "claude", "antigravity"};

    private MonitorWidgetPolicy() { }

    public static String sanitizeMode(String mode) {
        if (MODE_FIXED_QUOTA.equals(mode) || MODE_FIXED_TOKENS.equals(mode) || MODE_FIXED_TASKS.equals(mode)) {
            return mode;
        }
        return MODE_AUTO;
    }

    public static String modeTitle(String mode) {
        switch (sanitizeMode(mode)) {
            case MODE_FIXED_QUOTA: return "固定 · 官方额度";
            case MODE_FIXED_TOKENS: return "固定 · 今日 Token";
            case MODE_FIXED_TASKS: return "固定 · 项目动态";
            default: return "自动模式";
        }
    }

    public static final class Model {
        public final String mode;
        public final String brandTool;
        public final int badgeTone;
        public final boolean valueWarning;
        public final boolean primaryCompactTitle;
        public final String smallBadge;
        public final String wideBadge;
        public final String primaryValue;
        public final String title;
        public final String footer;
        public final boolean showProgress;
        public final int progress;
        public final String sideLabel;
        public final String sideValue;
        public final String sectionHeading;
        public final String row1;
        public final String row2;
        public final String row3;
        public final String clickTarget;
        public final String clickTaskId;
        public final long clickGeneration;

        Model(
                String mode,
                String brandTool,
                int badgeTone,
                boolean valueWarning,
                boolean primaryCompactTitle,
                String smallBadge,
                String wideBadge,
                String primaryValue,
                String title,
                String footer,
                boolean showProgress,
                int progress,
                String sideLabel,
                String sideValue,
                String sectionHeading,
                String row1,
                String row2,
                String row3,
                String clickTarget,
                String clickTaskId,
                long clickGeneration) {
            this.mode = mode;
            this.brandTool = brandTool == null ? "" : brandTool;
            this.badgeTone = badgeTone;
            this.valueWarning = valueWarning;
            this.primaryCompactTitle = primaryCompactTitle;
            this.smallBadge = smallBadge;
            this.wideBadge = wideBadge;
            this.primaryValue = primaryValue;
            this.title = title;
            this.footer = footer;
            this.showProgress = showProgress;
            this.progress = Math.max(0, Math.min(100, progress));
            this.sideLabel = sideLabel;
            this.sideValue = sideValue;
            this.sectionHeading = sectionHeading;
            this.row1 = row1;
            this.row2 = row2;
            this.row3 = row3;
            this.clickTarget = clickTarget;
            this.clickTaskId = clickTaskId == null ? "" : clickTaskId;
            this.clickGeneration = clickGeneration;
        }
    }

    private static final class QuotaCandidate {
        final String tool;
        final String label;
        final String sourceName;
        final double remaining;
        final long resetsMillis;
        final boolean stale;

        QuotaCandidate(String tool, String label, String sourceName, double remaining, long resetsMillis, boolean stale) {
            this.tool = tool;
            this.label = label;
            this.sourceName = sourceName;
            this.remaining = remaining;
            this.resetsMillis = resetsMillis;
            this.stale = stale;
        }
    }

    private static final class TokenSummary {
        final Long total;
        final boolean anyRecorded;
        final String dominantTool;
        final String breakdown;
        final List<String> toolEntries;

        TokenSummary(Long total, boolean anyRecorded, String dominantTool, String breakdown, List<String> toolEntries) {
            this.total = total;
            this.anyRecorded = anyRecorded;
            this.dominantTool = dominantTool;
            this.breakdown = breakdown;
            this.toolEntries = toolEntries;
        }
    }

    private static final class TaskItem {
        final String id;
        final String title;
        final String tool;
        final String status;
        final String computer;

        TaskItem(String id, String title, String tool, String status, String computer) {
            this.id = id;
            this.title = title;
            this.tool = tool;
            this.status = status;
            this.computer = computer;
        }
    }

    private static final class TaskSummary {
        final int runningCount;
        final int waitingCount;
        final int errorCount;
        final List<TaskItem> activeItems;

        TaskSummary(int runningCount, int waitingCount, int errorCount, List<TaskItem> activeItems) {
            this.runningCount = runningCount;
            this.waitingCount = waitingCount;
            this.errorCount = errorCount;
            this.activeItems = activeItems;
        }

        int totalActive() {
            return runningCount + waitingCount + errorCount;
        }

        String shortStatus() {
            List<String> parts = new ArrayList<>();
            if (waitingCount > 0) parts.add(waitingCount + " 待批");
            if (errorCount > 0) parts.add(errorCount + " 需关注");
            if (runningCount > 0) parts.add(runningCount + (parts.isEmpty() ? " 项执行中" : " 执行"));
            if (parts.isEmpty()) return "当前空闲";
            if (parts.size() == 1 && waitingCount > 0) return waitingCount + " 项待批准";
            if (parts.size() == 1 && errorCount > 0) return errorCount + " 项需关注";
            return join(parts, " · ");
        }
    }

    public static Model evaluate(
            String requestedMode,
            boolean connected,
            JSONObject workbench,
            JSONObject usage,
            MonitorWidgetBridge.TrackingSnapshot tracking,
            long nowMillis) {
        return evaluate(requestedMode, connected, workbench, usage, true, false, tracking, nowMillis);
    }

    public static Model evaluate(
            String requestedMode,
            boolean connected,
            JSONObject workbench,
            JSONObject usage,
            boolean preferStandaloneUsage,
            boolean refreshing,
            MonitorWidgetBridge.TrackingSnapshot tracking,
            long nowMillis) {
        String mode = sanitizeMode(requestedMode);
        if (!connected) {
            return new Model(
                    mode,
                    "",
                    TONE_ACCENT,
                    false,
                    false,
                    "Monitor",
                    "Monitor · 未登录",
                    "未登录",
                    "点按打开并扫码登录",
                    "支持手机一扫登录与配对电脑",
                    false,
                    0,
                    "状态",
                    "等待登录",
                    "快速开始",
                    "1. 打开 Monitor 登录个人服务",
                    "2. 扫描电脑端二维码一键连接",
                    "支持 2×2、2×4、4×4 自动或固定显示",
                    TARGET_HOME,
                    "",
                    0L);
        }

        MonitorWidgetBridge.TrackingSnapshot live = tracking == null
                ? MonitorWidgetBridge.TrackingSnapshot.idle()
                : tracking;
        JSONObject effectiveUsage = pickUsage(usage, workbench, preferStandaloneUsage);
        List<QuotaCandidate> quotas = collectQuotas(effectiveUsage, nowMillis);
        TokenSummary tokens = summarizeTokens(effectiveUsage, nowMillis);
        TaskSummary tasks = summarizeTasks(workbench);

        Model result;
        if (MODE_AUTO.equals(mode)) {
            if (live.active) {
                result = buildTrackingModel(mode, live, quotas, tokens, tasks, nowMillis, true);
            } else if (!quotas.isEmpty()) {
                result = buildQuotaModel(mode, quotas, tokens, tasks, nowMillis, true);
            } else {
                result = buildTokensModel(mode, quotas, tokens, tasks, nowMillis, true);
            }
        } else if (MODE_FIXED_QUOTA.equals(mode)) {
            result = buildQuotaModel(mode, quotas, tokens, tasks, nowMillis, false);
        } else if (MODE_FIXED_TOKENS.equals(mode)) {
            result = buildTokensModel(mode, quotas, tokens, tasks, nowMillis, false);
        } else if (live.active) {
            result = buildTrackingModel(mode, live, quotas, tokens, tasks, nowMillis, false);
        } else {
            result = buildTasksModel(mode, quotas, tokens, tasks, nowMillis);
        }

        if (!refreshing) return result;
        return new Model(
                result.mode,
                result.brandTool,
                result.badgeTone,
                result.valueWarning,
                result.primaryCompactTitle,
                result.smallBadge + " · 同步中",
                result.wideBadge + " · 正在同步…",
                result.primaryValue,
                result.title,
                result.footer,
                result.showProgress,
                result.progress,
                result.sideLabel,
                result.sideValue,
                result.sectionHeading,
                result.row1,
                result.row2,
                result.row3,
                result.clickTarget,
                result.clickTaskId,
                result.clickGeneration);
    }

    private static JSONObject pickUsage(JSONObject usage, JSONObject workbench, boolean preferStandaloneUsage) {
        JSONObject workbenchUsage = workbench == null ? null : workbench.optJSONObject("usage");
        if (usage == null) return workbenchUsage;
        if (workbenchUsage == null) return usage;
        boolean standaloneHasProviders = usage.optJSONArray("providers") != null && usage.optJSONArray("providers").length() > 0;
        boolean workbenchHasProviders = workbenchUsage.optJSONArray("providers") != null && workbenchUsage.optJSONArray("providers").length() > 0;
        if (preferStandaloneUsage) {
            return standaloneHasProviders ? usage : workbenchUsage;
        }
        return workbenchHasProviders ? workbenchUsage : usage;
    }

    private static Model buildTrackingModel(
            String mode,
            MonitorWidgetBridge.TrackingSnapshot live,
            List<QuotaCandidate> quotas,
            TokenSummary tokens,
            TaskSummary tasks,
            long nowMillis,
            boolean autoMode) {
        String brandLabel = toolLabel(live.tool);
        String state = LivePresentation.publicState(live.message);
        boolean waiting = "等待批准".equals(state);
        int tone = waiting ? TONE_WARNING : "claude".equals(live.tool) ? TONE_CLAUDE : TONE_RUNNING;

        String projectTitle = LivePresentation.clean(live.title, 80);
        if (projectTitle.isEmpty()) projectTitle = brandLabel + " 实时任务";

        String computer = LivePresentation.clean(live.computer, 28);
        String elapsed = live.elapsedMillis >= 60000L
                ? "已跟踪 " + compactDuration(live.elapsedMillis, false)
                : "刚刚开始";
        String subtitle = state + (computer.isEmpty() ? "" : " · " + computer) + " · " + elapsed;

        String footer;
        List<String> usageParts = new ArrayList<>();
        if (live.sessionTokens != null) usageParts.add("本次 " + NativeUsageFormat.compact(live.sessionTokens));
        Long todayValue = live.todayTokens != null ? live.todayTokens : tokens.total;
        if (todayValue != null) usageParts.add("今日 " + NativeUsageFormat.compact(todayValue));
        if (live.untilTaskEnd) {
            usageParts.add("直至任务结束");
        } else if (live.remainingMillis > 0L) {
            usageParts.add("剩余 " + compactDuration(live.remainingMillis, true));
        }
        footer = usageParts.isEmpty() ? "正在实时跟踪项目进度" : join(usageParts, " · ");

        QuotaCandidate lowest = quotas.isEmpty() ? null : quotas.get(0);
        String sideLabel = lowest != null ? toolShortLabel(lowest.tool) + " 最低额度" : "今日 Token";
        String sideValue = lowest != null
                ? "剩余 " + NativeUsageFormat.percentage(lowest.remaining)
                : (tokens.anyRecorded ? NativeUsageFormat.compact(tokens.total) : "暂无记录");

        String row1 = lowest != null
                ? "最低额度：" + toolLabel(lowest.tool) + " " + lowest.label + " 剩余 "
                        + NativeUsageFormat.percentage(lowest.remaining) + "（"
                        + NativeUsageFormat.reset(lowest.resetsMillis, nowMillis, lowest.stale) + "）"
                : "官方额度：暂无有效窗口";
        String row2 = tokens.anyRecorded
                ? "今日 Token：" + NativeUsageFormat.compact(tokens.total)
                        + (tokens.breakdown.isEmpty() ? "" : "（" + tokens.breakdown + "）")
                : "今日 Token：暂无本地记录";
        String row3 = tasks.totalActive() > 1
                ? "并行动态：另有 " + (tasks.totalActive() - 1) + " 项活跃任务（" + tasks.shortStatus() + "）"
                : "点按卡片可直接进入当前跟踪项目";

        String smallBadge = state + (brandLabel.isEmpty() ? "" : " · " + brandLabel);
        String wideBadge = (autoMode ? "实时跟踪 · " : "项目跟踪 · ") + (brandLabel.isEmpty() ? "Monitor" : brandLabel);

        return new Model(
                mode,
                live.tool,
                tone,
                waiting,
                true,
                smallBadge,
                wideBadge,
                projectTitle,
                subtitle,
                footer,
                lowest != null,
                lowest != null ? (int) Math.round(lowest.remaining) : 0,
                sideLabel,
                sideValue,
                "额度与今日用量",
                row1,
                row2,
                row3,
                NativeNavigationPolicy.notificationUri(live.taskId, live.generation) != null ? TARGET_TASK : TARGET_TASKS,
                live.taskId,
                live.generation);
    }

    private static Model buildQuotaModel(
            String mode,
            List<QuotaCandidate> quotas,
            TokenSummary tokens,
            TaskSummary tasks,
            long nowMillis,
            boolean autoMode) {
        if (quotas.isEmpty()) {
            String fallbackValue = tokens.anyRecorded ? NativeUsageFormat.compact(tokens.total) : "暂无额度";
            String fallbackTitle = tokens.anyRecorded ? "官方额度暂未返回 · 显示今日 Token" : "等待已连接电脑上报官方额度";
            String fallbackFooter = tasks.totalActive() > 0
                    ? tasks.shortStatus() + " · 点按查看用量"
                    : "连接 Codex / Claude / Antigravity 后自动同步";
            return new Model(
                    mode,
                    tokens.dominantTool,
                    "claude".equals(tokens.dominantTool) ? TONE_CLAUDE : TONE_ACCENT,
                    false,
                    false,
                    "官方额度",
                    "Monitor · 官方额度",
                    fallbackValue,
                    fallbackTitle,
                    fallbackFooter,
                    false,
                    0,
                    "任务状态",
                    tasks.shortStatus(),
                    "本地 Token 与任务状态",
                    tokens.anyRecorded
                            ? "今日 Token：" + NativeUsageFormat.compact(tokens.total)
                                    + (tokens.breakdown.isEmpty() ? "" : "（" + tokens.breakdown + "）")
                            : "今日 Token：暂无已采集记录",
                    "任务状态：" + tasks.shortStatus(),
                    "在电脑端登录工具账号后可查看百分比与重置时间",
                    TARGET_USAGE,
                    "",
                    0L);
        }

        QuotaCandidate lowest = quotas.get(0);
        String providerName = toolLabel(lowest.tool);
        boolean lowWarning = lowest.remaining <= 15.0;
        int tone = lowWarning ? TONE_WARNING : "claude".equals(lowest.tool) ? TONE_CLAUDE : TONE_ACCENT;

        String primary = (lowest.stale ? "上次 " : "剩余 ") + NativeUsageFormat.percentage(lowest.remaining);
        String title = lowest.label + (lowest.sourceName.isEmpty() ? "" : " · " + lowest.sourceName);
        String resetText = NativeUsageFormat.reset(lowest.resetsMillis, nowMillis, lowest.stale);

        String sideLabel = tokens.anyRecorded ? "今日 Token" : "任务动态";
        String sideValue = tokens.anyRecorded ? NativeUsageFormat.compact(tokens.total) : tasks.shortStatus();

        String row1 = tokens.anyRecorded
                ? "今日 Token：" + NativeUsageFormat.compact(tokens.total)
                        + (tokens.breakdown.isEmpty() ? "" : "（" + tokens.breakdown + "）")
                : "今日 Token：暂无本地记录";
        String row2;
        if (quotas.size() > 1) {
            QuotaCandidate second = quotas.get(1);
            row2 = "次低额度：" + toolLabel(second.tool) + " " + second.label + " 剩余 "
                    + NativeUsageFormat.percentage(second.remaining) + " · "
                    + NativeUsageFormat.reset(second.resetsMillis, nowMillis, second.stale);
        } else {
            row2 = "其他窗口：当前无更低额度告警";
        }
        String row3 = tasks.totalActive() > 0
                ? "活跃项目：" + tasks.shortStatus()
                        + (tasks.activeItems.isEmpty() ? "" : " · " + tasks.activeItems.get(0).title)
                : "活跃项目：当前空闲，开始跟踪后将自动切换";

        return new Model(
                mode,
                lowest.tool,
                tone,
                lowWarning,
                false,
                providerName + (autoMode ? " · 自动" : " · 额度"),
                providerName + (autoMode ? " · 最低剩余额度" : " · 固定额度"),
                primary,
                title,
                resetText,
                true,
                (int) Math.round(lowest.remaining),
                sideLabel,
                sideValue,
                "其他额度与今日用量",
                row1,
                row2,
                 MODE_FIXED_QUOTA.equals(mode) && tasks.totalActive() == 0
                        ? "固定显示：始终展示最低剩余官方额度与重置时间"
                        : row3,
                TARGET_USAGE,
                "",
                0L);
    }

    private static Model buildTokensModel(
            String mode,
            List<QuotaCandidate> quotas,
            TokenSummary tokens,
            TaskSummary tasks,
            long nowMillis,
            boolean autoMode) {
        String primary = tokens.anyRecorded ? NativeUsageFormat.compact(tokens.total) : "—";
        String subtitle = tokens.anyRecorded
                ? (!tokens.breakdown.isEmpty() ? tokens.breakdown : "今日已采集本地 Token")
                : "今日暂无已记录 Token";
        QuotaCandidate lowest = quotas.isEmpty() ? null : quotas.get(0);
        String footer = lowest != null
                ? toolShortLabel(lowest.tool) + " 剩余 " + NativeUsageFormat.percentage(lowest.remaining)
                        + " · " + NativeUsageFormat.reset(lowest.resetsMillis, nowMillis, lowest.stale)
                : (tasks.totalActive() > 0 ? tasks.shortStatus() + " · 按北京时间每日 00:00 重置" : "按北京时间每日 00:00 重新统计");

        String sideLabel = lowest != null ? toolShortLabel(lowest.tool) + " 最低额度" : "活跃项目";
        String sideValue = lowest != null
                ? "剩余 " + NativeUsageFormat.percentage(lowest.remaining)
                : tasks.shortStatus();

        String row1 = tokens.toolEntries.size() > 0 ? tokens.toolEntries.get(0) : "Codex / Claude / Antigravity 暂无今日记录";
        String row2 = tokens.toolEntries.size() > 1
                ? join(tokens.toolEntries.subList(1, tokens.toolEntries.size()), " · ")
                : (lowest != null
                        ? "最低官方额度：" + toolLabel(lowest.tool) + " " + lowest.label + " 剩余 "
                                + NativeUsageFormat.percentage(lowest.remaining)
                        : "官方额度：暂无有效窗口");
        String row3 = tasks.totalActive() > 0
                ? "活跃项目：" + tasks.shortStatus()
                        + (tasks.activeItems.isEmpty() ? "" : " · " + tasks.activeItems.get(0).title)
                : (autoMode ? "当前空闲 · 开始跟踪项目后将自动显示实时进度" : "固定显示：按北京时间汇总已连接电脑今日 Token");

        return new Model(
                mode,
                tokens.dominantTool,
                "claude".equals(tokens.dominantTool) ? TONE_CLAUDE : TONE_ACCENT,
                false,
                false,
                autoMode ? "今日 Token · 自动" : "今日 Token",
                autoMode ? "Monitor · 今日 Token" : "Monitor · 固定今日 Token",
                primary,
                subtitle,
                footer,
                lowest != null,
                lowest != null ? (int) Math.round(lowest.remaining) : 0,
                sideLabel,
                sideValue,
                "各工具明细与状态",
                row1,
                row2,
                row3,
                TARGET_USAGE,
                "",
                0L);
    }

    private static Model buildTasksModel(
            String mode,
            List<QuotaCandidate> quotas,
            TokenSummary tokens,
            TaskSummary tasks,
            long nowMillis) {
        QuotaCandidate lowest = quotas.isEmpty() ? null : quotas.get(0);
        if (!tasks.activeItems.isEmpty()) {
            TaskItem lead = tasks.activeItems.get(0);
            boolean waiting = "waiting".equals(lead.status);
            boolean errored = "error".equals(lead.status);
            boolean needsAttention = waiting || errored;
            String statusText = waiting ? "等待批准" : errored ? "需要关注" : "执行中";
            String subtitle = statusText
                    + (lead.computer.isEmpty() ? "" : " · " + lead.computer)
                    + (tasks.totalActive() > 1 ? " · 共 " + tasks.totalActive() + " 项活跃" : "");
            String footer = lowest != null
                    ? toolShortLabel(lowest.tool) + " 剩余 " + NativeUsageFormat.percentage(lowest.remaining)
                            + " · " + NativeUsageFormat.reset(lowest.resetsMillis, nowMillis, lowest.stale)
                    : (tokens.anyRecorded ? "今日 Token " + NativeUsageFormat.compact(tokens.total) + " · 点按查看任务" : "点按打开工作台处理任务");

            String row1 = tasks.activeItems.size() > 1
                    ? "并行任务：" + formatTaskRow(tasks.activeItems.get(1))
                    : "当前仅此 1 项活跃任务，可在详情页开启实况跟踪";
            String row2 = lowest != null
                    ? "最低额度：" + toolLabel(lowest.tool) + " " + lowest.label + " 剩余 "
                            + NativeUsageFormat.percentage(lowest.remaining)
                    : "官方额度：暂无有效窗口";
            String row3 = tokens.anyRecorded
                    ? "今日 Token：" + NativeUsageFormat.compact(tokens.total)
                            + (tokens.breakdown.isEmpty() ? "" : "（" + tokens.breakdown + "）")
                    : "今日 Token：暂无本地记录";

            return new Model(
                    mode,
                    lead.tool,
                    needsAttention ? TONE_WARNING : "claude".equals(lead.tool) ? TONE_CLAUDE : TONE_RUNNING,
                    needsAttention,
                    true,
                    statusText + " · " + toolLabel(lead.tool),
                    "活跃项目 · " + toolLabel(lead.tool),
                    lead.title,
                    subtitle,
                    footer,
                    lowest != null,
                    lowest != null ? (int) Math.round(lowest.remaining) : 0,
                    tokens.anyRecorded ? "今日 Token" : "活跃数",
                    tokens.anyRecorded ? NativeUsageFormat.compact(tokens.total) : tasks.shortStatus(),
                    "并行任务与今日用量",
                    row1,
                    row2,
                    row3,
                    NativeWebPolicy.validTaskId(lead.id) ? TARGET_TASK : TARGET_TASKS,
                    lead.id,
                    0L);
        }

        String footer = lowest != null
                ? toolShortLabel(lowest.tool) + " 剩余 " + NativeUsageFormat.percentage(lowest.remaining)
                        + " · " + NativeUsageFormat.reset(lowest.resetsMillis, nowMillis, lowest.stale)
                : "电脑端启动新任务后自动在此显示";
        return new Model(
                mode,
                "",
                TONE_ACCENT,
                false,
                false,
                "项目动态",
                "Monitor · 项目动态",
                "当前空闲",
                "暂无正在执行或等待批准的项目",
                footer,
                lowest != null,
                lowest != null ? (int) Math.round(lowest.remaining) : 0,
                "今日 Token",
                tokens.anyRecorded ? NativeUsageFormat.compact(tokens.total) : "—",
                "额度与今日用量",
                lowest != null
                        ? "最低额度：" + toolLabel(lowest.tool) + " " + lowest.label + " 剩余 "
                                + NativeUsageFormat.percentage(lowest.remaining) + "（"
                                + NativeUsageFormat.reset(lowest.resetsMillis, nowMillis, lowest.stale) + "）"
                        : "官方额度：暂无有效窗口",
                tokens.anyRecorded
                        ? "今日 Token：" + NativeUsageFormat.compact(tokens.total)
                                + (tokens.breakdown.isEmpty() ? "" : "（" + tokens.breakdown + "）")
                        : "今日 Token：暂无本地记录",
                "点按可打开 Monitor 任务工作台",
                TARGET_TASKS,
                "",
                0L);
    }

    private static List<QuotaCandidate> collectQuotas(JSONObject usage, long nowMillis) {
        List<QuotaCandidate> fresh = new ArrayList<>();
        List<QuotaCandidate> staleFallback = new ArrayList<>();
        JSONArray providers = usage == null ? null : usage.optJSONArray("providers");
        if (providers == null) return fresh;

        for (int i = 0; i < Math.min(providers.length(), 8); i++) {
            JSONObject provider = providers.optJSONObject(i);
            if (provider == null) continue;
            String tool = provider.optString("tool");
            if (!isSupportedTool(tool)) continue;
            JSONArray quotas = provider.optJSONArray("quotas");
            if (quotas == null) continue;

            for (int j = 0; j < Math.min(quotas.length(), 64); j++) {
                JSONObject q = quotas.optJSONObject(j);
                if (q == null) continue;
                Double left = NativeUsageFormat.percent(q.opt("remaining_percent"));
                Double used = NativeUsageFormat.percent(q.opt("used_percent"));
                long resetsMillis = NativeUsageFormat.epochMillis(q.opt("resets_at"));
                if (!NativeUsagePresentation.quotaVisible(left, used, resetsMillis, nowMillis)) continue;
                Double remaining = NativeUsagePresentation.remaining(left, used);
                if (remaining == null) continue;

                boolean isStale = q.optBoolean("stale", false);
                String label = formatQuotaWindowLabel(q);
                String source = LivePresentation.clean(q.optString("source_name"), 20);
                QuotaCandidate candidate = new QuotaCandidate(tool, label, source, remaining, resetsMillis, isStale);
                if (isStale) staleFallback.add(candidate);
                else fresh.add(candidate);
            }
        }

        Comparator<QuotaCandidate> order = Comparator
                .comparingDouble((QuotaCandidate c) -> c.remaining)
                .thenComparingLong(c -> c.resetsMillis > 0 ? c.resetsMillis : Long.MAX_VALUE);
        fresh.sort(order);
        staleFallback.sort(order);
        if (!fresh.isEmpty()) {
            fresh.addAll(staleFallback);
            return fresh;
        }
        return staleFallback;
    }

    private static String formatQuotaWindowLabel(JSONObject q) {
        String bucket = q.opt("bucket_label") instanceof String ? LivePresentation.clean(q.optString("bucket_label"), 40) : "";
        String model = q.opt("model") instanceof String ? LivePresentation.clean(q.optString("model"), 40) : "";
        String rawLabel = q.opt("label") instanceof String ? LivePresentation.clean(q.optString("label"), 32) : "";
        Long minutesValue = NativeUsageFormat.count(q.opt("window_minutes"));
        long minutes = minutesValue == null ? 0L : minutesValue;
        String windowLabel = NativeUsageFormat.window(minutes, rawLabel.isEmpty() ? "套餐额度" : rawLabel);
        String subject = NativeUsageFormat.modelName(!bucket.isEmpty() ? bucket : model);
        if (!subject.isEmpty() && !windowLabel.contains(subject)) {
            return subject + " · " + windowLabel;
        }
        return windowLabel;
    }

    private static TokenSummary summarizeTokens(JSONObject usage, long nowMillis) {
        JSONArray providers = usage == null ? null : usage.optJSONArray("providers");
        if (providers == null) return new TokenSummary(null, false, "", "", new ArrayList<String>());

        String todayKey = NativeUsageFormat.day(nowMillis);
        long sum = 0L;
        boolean anyRecorded = false;
        long maxTokens = -1L;
        String dominantTool = "";
        List<String> nonZeroParts = new ArrayList<>();
        List<String> entries = new ArrayList<>();

        for (String tool : TOOLS) {
            JSONObject provider = null;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject candidate = providers.optJSONObject(i);
                if (candidate != null && tool.equals(candidate.optString("tool"))) {
                    provider = candidate;
                    break;
                }
            }
            if (provider == null) continue;
            Long todayTokens = extractTodayTokens(provider, todayKey);
            if (todayTokens == null) continue;
            anyRecorded = true;
            if (todayTokens <= Long.MAX_VALUE - sum) sum += todayTokens;
            else sum = Long.MAX_VALUE;
            if (todayTokens > maxTokens) {
                maxTokens = todayTokens;
                dominantTool = tool;
            }
            String compact = NativeUsageFormat.compact(todayTokens);
            entries.add(toolLabel(tool) + "：" + compact + " Token");
            if (todayTokens > 0L) {
                nonZeroParts.add(toolShortLabel(tool) + " " + compact);
            }
        }

        if (maxTokens <= 0L) dominantTool = "";
        String breakdown = nonZeroParts.isEmpty()
                ? (anyRecorded ? "各工具今日 0 Token" : "")
                : join(nonZeroParts, " · ");
        return new TokenSummary(anyRecorded ? sum : null, anyRecorded, dominantTool, breakdown, entries);
    }

    private static Long extractTodayTokens(JSONObject provider, String todayKey) {
        if (!"unavailable".equals(provider.optString("coverage"))) {
            Long direct = NativeUsageFormat.count(provider.opt("today_tokens"));
            if (direct != null) return direct;
        }
        JSONObject history = provider.optJSONObject("history");
        JSONArray days = history == null ? null : history.optJSONArray("days");
        if (days == null) return null;
        for (int i = 0; i < Math.min(days.length(), 95); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null || !todayKey.equals(day.optString("day"))) continue;
            if ("unavailable".equals(day.optString("coverage"))) return null;
            return NativeUsageFormat.count(day.opt("tokens"));
        }
        return null;
    }

    private static TaskSummary summarizeTasks(JSONObject workbench) {
        List<TaskItem> active = new ArrayList<>();
        if (workbench == null) return new TaskSummary(0, 0, 0, active);

        Map<String, JSONObject> devices = new HashMap<>();
        JSONArray deviceList = workbench.optJSONArray("devices");
        if (deviceList != null) {
            for (int i = 0; i < Math.min(deviceList.length(), 200); i++) {
                JSONObject d = deviceList.optJSONObject(i);
                if (d != null && !d.optString("id").isEmpty()) {
                    devices.put(d.optString("id"), d);
                }
            }
        }

        JSONArray taskList = workbench.optJSONArray("tasks");
        int running = 0;
        int waiting = 0;
        int errored = 0;
        if (taskList != null) {
            for (int i = 0; i < Math.min(taskList.length(), 500); i++) {
                JSONObject t = taskList.optJSONObject(i);
                if (t == null || t.optBoolean("archived")) continue;
                JSONObject dev = devices.get(t.optString("device_id"));
                boolean online = dev != null && dev.optBoolean("online");
                if (!online) continue;
                String status = t.optString("status");
                if (!"running".equals(status) && !"waiting".equals(status) && !"error".equals(status)) continue;
                if ("running".equals(status)) running++;
                else if ("waiting".equals(status)) waiting++;
                else errored++;

                if (active.size() < 8) {
                    String tool = t.optString("tool");
                    String title = LivePresentation.clean(t.optString("title"), 64);
                    if (title.isEmpty()) title = toolLabel(tool) + " 任务";
                    String computer = dev == null ? "" : LivePresentation.clean(dev.optString("name"), 24);
                    active.add(new TaskItem(t.optString("id"), title, tool, status, computer));
                }
            }
        }
        // Prioritize waiting approvals first, then errors needing attention, then running tasks.
        active.sort((a, b) -> Integer.compare(statusPriority(b.status), statusPriority(a.status)));
        return new TaskSummary(running, waiting, errored, active);
    }

    private static int statusPriority(String status) {
        if ("waiting".equals(status)) return 2;
        if ("error".equals(status)) return 1;
        return 0;
    }

    private static String formatTaskRow(TaskItem item) {
        String state = "waiting".equals(item.status) ? "待批准" : "error".equals(item.status) ? "需关注" : "执行中";
        return "[" + state + "] " + toolShortLabel(item.tool) + " · " + item.title;
    }

    private static boolean isSupportedTool(String tool) {
        return "codex".equals(tool) || "claude".equals(tool) || "antigravity".equals(tool);
    }

    private static String toolLabel(String tool) {
        if ("codex".equals(tool)) return "Codex";
        if ("claude".equals(tool)) return "Claude Code";
        if ("antigravity".equals(tool)) return "Antigravity";
        return "Monitor";
    }

    private static String toolShortLabel(String tool) {
        if ("codex".equals(tool)) return "Codex";
        if ("claude".equals(tool)) return "Claude";
        if ("antigravity".equals(tool)) return "Antigravity";
        return "Monitor";
    }

    private static String compactDuration(long millis, boolean ceilMinutes) {
        long minutes = ceilMinutes ? Math.max(1L, (millis + 59999L) / 60000L) : Math.max(1L, millis / 60000L);
        if (minutes < 60L) return minutes + " 分钟";
        long hours = minutes / 60L;
        long remMinutes = minutes % 60L;
        if (hours < 24L) return hours + " 小时" + (remMinutes > 0L ? " " + remMinutes + " 分钟" : "");
        long days = hours / 24L;
        long remHours = hours % 24L;
        return days + " 天" + (remHours > 0L ? " " + remHours + " 小时" : "");
    }

    private static String join(List<String> items, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
