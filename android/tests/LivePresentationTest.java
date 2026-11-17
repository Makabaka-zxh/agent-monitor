package com.agentmonitor.live;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;

public final class LivePresentationTest {
    private static int checks;
    private static final long NOW = 1800000000000L;
    private static final LivePresentation.Options ALL = new LivePresentation.Options(true, true, true, true, true, true);
    private static void check(boolean good, String label) { ++checks; if (!good) throw new AssertionError(label); }
    private static JSONObject quota(String label, double remaining, long reset, long observed, boolean stale) {
        return new JSONObject().put("label", label).put("remaining_percent", remaining).put("resets_at", reset / 1000)
                .put("observed_at", Instant.ofEpochMilli(observed).toString()).put("stale", stale).put("source_device_id", "desktop");
    }
    private static JSONObject snapshot(Object today, JSONArray quotas) {
        return new JSONObject().put("devices", new JSONArray().put(new JSONObject().put("id", "desktop").put("name", "家里的电脑")))
                .put("usage", new JSONObject().put("providers", new JSONArray()
                        .put(new JSONObject().put("tool", "claude").put("today_tokens", 99999999))
                        .put(new JSONObject().put("tool", "codex").put("today_tokens", today).put("coverage", "partial").put("quotas", quotas))));
    }
    private static JSONObject task(Object tokens) {
        return new JSONObject().put("device_id", "desktop").put("usage", new JSONObject().put("total_tokens", tokens).put("coverage", "complete"));
    }
    private static String render(LivePresentation.Data data, LivePresentation.Options options) {
        return LivePresentation.expanded("Codex", "执行中", data, options, 125000, NOW);
    }
    public static void main(String[] args) {
        JSONArray quotas = new JSONArray().put(quota("5 小时", 8, NOW + 1800000, NOW, false))
                .put(quota("每周", 72, NOW + 86400000, NOW, false));
        LivePresentation.Data data = LivePresentation.read(snapshot(2345678, quotas), task(12345), "codex");
        String rich = render(data, ALL);
        check("Codex · 家里的电脑".equals(LivePresentation.identity("Codex", data.computer, true)), "computer is joined to the top provider identity");
        check("Claude Code · Test workstation".equals(LivePresentation.identity("Claude Code", "Test workstation", true)), "both providers share the same provenance format");
        check("Codex".equals(LivePresentation.identity("Codex", data.computer, false)), "computer toggle removes the whole name and separator");
        check("Codex".equals(LivePresentation.identity("Codex", " ", true)), "unavailable computer does not invent a name or dangling separator");
        check("任务 · 未知电脑".equals(LivePresentation.identity("任务", "未知电脑", true)), "caller-provided unknown identity remains explicit");
        check("Codex · Demo stati on".equals(LivePresentation.identity("Codex", "Demo\nstati\u202eon", true)), "identity strips line and directional controls");
        StringBuilder longComputer = new StringBuilder();
        for (int i = 0; i < 80; i++) longComputer.append("\ud83d\ude80");
        String compact = LivePresentation.compactIdentity("Claude Code", longComputer.toString(), true);
        check(compact.startsWith("Claude Code · ") && compact.endsWith("…") && LivePresentation.width(compact) <= LivePresentation.TITLE_LINE_UNITS,
                "long computer truncates after the explicit tool identity within the header budget");
        for (int i = 0; i < compact.length(); i++) {
            char point = compact.charAt(i);
            if (Character.isHighSurrogate(point)) check(i + 1 < compact.length() && Character.isLowSurrogate(compact.charAt(++i)), "identity truncation preserves surrogate pairs");
            else check(!Character.isLowSurrogate(point), "identity has no isolated low surrogate");
        }

        check(rich.startsWith("执行中 · 已跟踪 2分\n") && !rich.contains("家里的电脑"), "body starts with state; computer is owned by the identity header");
        check(rich.split("\n")[1].equals("已记 本次 1.2万 · 今日 234.5万"), "second capsule line preserves partial marker and both readable token counts");
        check(!rich.contains("99,999,999"), "other provider's tokens cannot bleed into current tool");
        check(rich.split("\n")[2].equals("5时余 8% · 30分后重置"), "quota follows state and usage with a real UNIX-second reset");
        check(rich.split("\n").length == 4, "rich body has four lines without duplicated identity");
        LivePresentation.Options off = new LivePresentation.Options(false, false, false, false, false, false);
        check("执行中".equals(render(data, off)), "all optional content can be hidden without hiding useful state");
        check("Codex".equals(LivePresentation.title("Private task", "Codex", off)), "title toggle suppresses task title");
        check("Task name".equals(LivePresentation.title(" Task\nname ", "Codex", ALL)), "title is single line");
        check("Codex".equals(LivePresentation.title("\n", "Codex", ALL)), "empty task name falls back to brand");
        StringBuilder longTitle = new StringBuilder(), emojiTitle = new StringBuilder();
        for (int index = 0; index < 64; index++) { longTitle.append("任务"); emojiTitle.append("\ud83d\ude80"); }
        String boundedTitle = LivePresentation.title(longTitle.toString(), "Codex", ALL);
        check(boundedTitle.endsWith("…") && LivePresentation.width(boundedTitle) <= LivePresentation.TITLE_LINE_UNITS,
                "daily notification title has a visible bounded truncation instead of an abrupt long sentence");
        String boundedEmoji = LivePresentation.title(emojiTitle.toString(), "Codex", ALL);
        check(boundedEmoji.endsWith("…") && LivePresentation.width(boundedEmoji) <= LivePresentation.TITLE_LINE_UNITS,
                "non-BMP daily titles keep the same glyph budget");
        for (int index = 0; index < boundedEmoji.length(); index++) {
            char item = boundedEmoji.charAt(index);
            if (Character.isHighSurrogate(item)) check(index + 1 < boundedEmoji.length() && Character.isLowSurrogate(boundedEmoji.charAt(++index)),
                    "daily title cannot split a surrogate pair");
            else check(!Character.isLowSurrogate(item), "daily title has no isolated low surrogate");
        }
        check("Codex".equals(LivePresentation.title(longTitle.toString(), "Codex", off)), "title truncation never overrides the title privacy choice");
        for (Object invalid : new Object[]{null, -1, 1.5, "55", Double.NaN, Double.POSITIVE_INFINITY, 9007199254740992d}) {
            String missing = render(LivePresentation.read(snapshot(invalid, null), task(invalid), "codex"), ALL);
            check(!missing.contains("Token") && !missing.contains("剩余"), "invalid tokens never become synthetic zero: " + invalid);
        }
        check(render(LivePresentation.read(snapshot(0, null), task(0), "codex"), ALL).contains("已记 本次 0 · 今日 0"),
                "observed zero is distinguished from unavailable");
        for (JSONObject bad : new JSONObject[]{
                quota("hidden", 10, NOW + 60000, NOW, true),
                quota("hidden", 10, NOW + 60000, NOW - LivePresentation.QUOTA_FRESH_MS - 1, false),
                quota("hidden", 10, NOW + 60000, NOW + 60001, false),
                quota("hidden", 10, NOW, NOW, false), quota("hidden", -1, NOW + 60000, NOW, false),
                quota("hidden", 101, NOW + 60000, NOW, false), quota("hidden", Double.NaN, NOW + 60000, NOW, false),
                quota("hidden", 10, NOW + 60000, NOW, false).put("observed_at", "invalid"),
                new JSONObject().put("label", "hidden").put("remaining_percent", 10)}) {
            check(!render(LivePresentation.read(snapshot(null, new JSONArray().put(bad)), task(null), "codex"), ALL).contains("hidden"),
                    "invalid, stale or past-reset quota hidden; never claim reset restored quota");
        }
        JSONObject contaminated = snapshot(123, quotas);
        contaminated.optJSONArray("devices").optJSONObject(0).put("name", "Sensitive\ncomputer\u202eevil");
        String clean = render(LivePresentation.read(contaminated, task(12), "codex"), ALL);
        check(!clean.contains("\u202e") && clean.split("\n").length == 4, "server text cannot inject direction or extra lines");
        check(LivePresentation.read(null, task(10), "codex").session == null, "missing snapshot carries no old usage");
        check(LivePresentation.read(snapshot(10, quotas), null, "codex").today == null, "missing task carries no unrelated daily usage");
        check(LivePresentationPreferences.defaultEnabled("OPPO", LivePresentationPreferences.COMPUTER), "OPPO default enables richer device content");
        check(LivePresentationPreferences.defaultEnabled("oppo", LivePresentationPreferences.QUOTA), "OPPO rich default includes available quota");
        check(!LivePresentationPreferences.defaultEnabled("Samsung", LivePresentationPreferences.QUOTA), "other vendors retain restrained default");
        check(LivePresentationPreferences.defaultEnabled("Samsung", LivePresentationPreferences.TITLE), "title remains default across vendors");
        check(!LivePresentationPreferences.defaultEnabled("oppo", "unknown"), "unknown preference never silently enabled");
        android.content.Context context = new android.content.Context();
        LivePresentationPreferences.set(context, LivePresentationPreferences.QUOTA, true);
        check(LivePresentationPreferences.enabled(context, LivePresentationPreferences.QUOTA), "manual choice persists for non-OPPO");
        LivePresentationPreferences.set(context, LivePresentationPreferences.QUOTA, false);
        check(!LivePresentationPreferences.enabled(context, LivePresentationPreferences.QUOTA), "manual hide persists");
        JSONArray mixedWindows = new JSONArray().put(quota("expired A", 8, NOW, NOW, false))
                .put(quota("expired B", 8, NOW, NOW, false))
                .put(quota("current", 45, NOW + 60000, NOW, false).put("source_name", "Mac mini"));
        String mixed = render(LivePresentation.read(snapshot(null, mixedWindows), task(null), "codex"), ALL);
        check(mixed.contains("curr…余 45% · 1分后重置") && !mixed.contains("expired") && !mixed.contains("Mac"),
                "stale first windows do not hide fresh matching quota; duplicate source name omitted");
        check("不足 1%".equals(LivePresentation.percent(.01)), "small positive quota never rounds to exhausted zero");
        check("超过 99%".equals(LivePresentation.percent(99.99)), "near-full quota never rounds to fully reset");
        check("0%".equals(LivePresentation.percent(0)) && "100%".equals(LivePresentation.percent(100)), "observed endpoints remain exact");
        JSONArray urgency = new JSONArray().put(quota("每周", 80, NOW + 180000, NOW, false))
                .put(quota("5 小时", 3, NOW + 1800000, NOW, false))
                .put(quota("另一窗口", 3, NOW + 600000, NOW, false));
        String[] sorted = render(LivePresentation.read(snapshot(99999999, urgency), task(123456789), "codex"), ALL).split("\n");
        check(sorted[1].equals("已记 本次 1.2亿 · 今日 9999.9万"), "large numbers compact downward without claiming a higher magnitude");
        check(sorted[2].startsWith("另一…余 3% · 10分后重置"), "least remaining first; ties choose nearest reset after state and usage");
        check(sorted[3].startsWith("5时余 3%"), "second urgent quota remains available in notification drawer");
        check(!sorted[2].contains("80%"), "less urgent quota does not displace scarce quota");
        String onlySession = render(LivePresentation.read(snapshot(null, null), task(123), "codex"), ALL).split("\n")[1];
        check("Token · 本次 123".equals(onlySession) && !onlySession.contains("今日"), "missing daily count omitted, not synthetic zero");
        String onlyToday = render(LivePresentation.read(snapshot(456, null), task(null), "codex"), ALL).split("\n")[1];
        check("已记 Token · 今日 456".equals(onlyToday) && !onlyToday.contains("本次"), "missing session count omitted and partial today retained");
        JSONObject partialTask = task(123); partialTask.optJSONObject("usage").put("coverage", "partial");
        check(render(LivePresentation.read(snapshot(null, null), partialTask, "codex"), ALL).contains("已记 Token · 本次 123"),
                "session-only partial data keeps partial marker");
        LivePresentation.Options withoutUsage = new LivePresentation.Options(true, true, true, false, false, false);
        String fallback = render(data, withoutUsage);
        check(fallback.equals("执行中 · 已跟踪 2分"), "when usage toggles off, body contains only state and elapsed");
        check(!fallback.contains("Token") && !fallback.contains("重置"), "display options remain authoritative despite available values");
        check(render(data, new LivePresentation.Options(true, true, false, false, false, false)).equals("执行中"),
                "computer choice does not create a duplicate body row");
        LivePresentation.Data huge = LivePresentation.read(snapshot(9007199254740991L, quotas), task(9007199254740991L), "codex");
        for (String line : render(huge, ALL).split("\n")) check(LivePresentation.width(line) <= 32, "approximately sixteen fullwidth glyphs per body line");
        check(render(huge, ALL).contains("已记 本次9007万亿·今日9007万亿"), "extreme valid counts stay compact and keep units");
        JSONObject longSource = snapshot(123, new JSONArray().put(quota("非常长的套餐窗口名称", .4, NOW + 90000000, NOW, false)
                .put("source_name", "非常长的电脑名称供显示测试")));
        String[] bounded = render(LivePresentation.read(longSource, task(456), "codex"), ALL).split("\n");
        check(LivePresentation.width(bounded[2]) <= 32 && bounded[2].contains("余 <1%") && bounded[2].endsWith("1天1时后重置"),
                "long source/window clipped before meaningful balance and compact reset");
        JSONObject window = snapshot(null, new JSONArray().put(quota("周额度", 100, NOW + 86340000, NOW, false)
                .put("window_minutes", 10080).put("source_name", "办公室电脑")));
        String actual = render(LivePresentation.read(window, task(123), "codex"), ALL).split("\n")[2];
        check(actual.equals("7天余 100% · 23时59分后重置"), "real window and reset remain complete without redundant device prefix");
        check(LivePresentation.width(actual) <= 32, "quota line stays within bounded glyph budget");
        JSONArray accounts = new JSONArray().put(quota("other account", 1, NOW + 60000, NOW, false).put("source_device_id", "mac"))
                .put(quota("matched", 73, NOW + 60000, NOW, false))
                .put(quota("unattributed", 0, NOW + 60000, NOW, false).put("source_device_id", null));
        String own = render(LivePresentation.read(snapshot(100, accounts), task(200), "codex"), ALL);
        check(own.split("\n")[2].contains("73%") && !own.contains("余 1%") && !own.contains("余 0%"),
                "only selected task's matching device quota is eligible; another account and unknown source omitted");
        JSONObject noDevice = task(200).put("device_id", "");
        check(!render(LivePresentation.read(snapshot(100, accounts), noDevice, "codex"), ALL).contains("重置"),
                "task with no source device cannot inherit any provider quota");
        JSONObject findWeek = snapshot(14060000, new JSONArray().put(quota("7 天", 77, NOW + (6 * 24 + 19) * 3600000L, NOW, false)
                .put("window_minutes", 10080).put("source_name", "Makabaka")));
        String realCapsule = render(LivePresentation.read(findWeek, task(140000000), "codex"), ALL).split("\n")[2];
        check(realCapsule.equals("7天余 77% · 6天19时后重置"), "weekly quota retains complete reset suffix without redundant source");
        check(LivePresentation.width(realCapsule) <= 32, "weekly quota stays within bounded glyph budget");
        check("执行中 · 已跟踪 不足1分".equals(LivePresentation.statusLine("执行中", true, -1)), "negative elapsed is clamped without displaying negative time");
        check("正在重连".equals(LivePresentation.statusLine("更新暂停，正在重连", false, 60000)), "public reconnect state keeps useful information when elapsed is hidden");
        String[][] states = {{"正在更新任务状态", "正在确认"}, {"正在确认任务状态", "正在确认"},
                {"等待批准，请在电脑上处理", "等待批准"}, {"本轮结束", "本轮结束"},
                {"任务已归档，已停止跟踪", "任务已归档"}, {"电脑已离线，已停止跟踪", "电脑已离线"},
                {"执行出错，请打开工作台查看", "执行出错"}, {"任务已空闲，已停止跟踪", "任务已空闲"},
                {"连接中断，已停止跟踪", "连接已中断"}, {"状态未能确认，已停止跟踪", "状态未确认，跟踪已停止"},
                {"系统已结束本次跟踪", "系统已结束跟踪"}, {"30 分钟跟踪已结束", "跟踪已结束"},
                {"1439 分钟跟踪已结束", "跟踪已结束"}};
        for (String[] pair : states) check(pair[1].equals(LivePresentation.publicState(pair[0])), "known tracking reason has a bounded public summary");
        for (String unknown : new String[]{null, "", "执行中 private", "任务 secret 已完成", "0 分钟跟踪已结束", "1440 分钟跟踪已结束", "30 分钟跟踪已结束\nsecret"})
            check("状态待确认".equals(LivePresentation.publicState(unknown)), "unknown or malformed public state cannot leak arbitrary text");
        check(!LivePresentation.expanded("Secret brand", "Secret state", data, off, 0, NOW).contains("Secret"), "body state never interpolates arbitrary provider or status text");
        System.out.println("LivePresentationTest: " + checks + " checks passed");
    }
}
