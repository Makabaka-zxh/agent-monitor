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
        check(rich.contains("家里的电脑 · 已跟踪2分"), "exact selected computer and monotonic tracking elapsed");
        check(rich.split("\n")[0].equals("已记 本次1.2万·今日234.5万"), "first capsule line preserves partial marker and both compact token counts");
        check(!rich.contains("99,999,999"), "other provider's tokens cannot bleed into current tool");
        check(rich.split("\n")[1].equals("5时余8%·30分重置"), "second capsule line shows real quota and UNIX-second reset before metadata");
        check(rich.split("\n").length == 5, "rich content bounded to five lines");
        LivePresentation.Options off = new LivePresentation.Options(false, false, false, false, false, false);
        check("Codex · 执行中".equals(render(data, off)), "all optional private content can be hidden");
        check("Codex".equals(LivePresentation.title("Private task", "Codex", off)), "title toggle suppresses task title");
        check("Task name".equals(LivePresentation.title(" Task\nname ", "Codex", ALL)), "title is single line");
        check("Codex".equals(LivePresentation.title("\n", "Codex", ALL)), "empty task name falls back to brand");
        for (Object invalid : new Object[]{null, -1, 1.5, "55", Double.NaN, Double.POSITIVE_INFINITY, 9007199254740992d}) {
            String missing = render(LivePresentation.read(snapshot(invalid, null), task(invalid), "codex"), ALL);
            check(!missing.contains("Token") && !missing.contains("剩余"), "invalid tokens never become synthetic zero: " + invalid);
        }
        check(render(LivePresentation.read(snapshot(0, null), task(0), "codex"), ALL).contains("已记 本次0·今日0"),
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
        check(!clean.contains("\u202e") && clean.split("\n").length == 5, "server text cannot inject direction or extra lines");
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
        check(mixed.startsWith("curr…余45%·1分重置") && !mixed.contains("expired") && !mixed.contains("Mac"),
                "stale first windows do not hide fresh matching quota; duplicate source name omitted");
        check("不足 1%".equals(LivePresentation.percent(.01)), "small positive quota never rounds to exhausted zero");
        check("超过 99%".equals(LivePresentation.percent(99.99)), "near-full quota never rounds to fully reset");
        check("0%".equals(LivePresentation.percent(0)) && "100%".equals(LivePresentation.percent(100)), "observed endpoints remain exact");
        JSONArray urgency = new JSONArray().put(quota("每周", 80, NOW + 180000, NOW, false))
                .put(quota("5 小时", 3, NOW + 1800000, NOW, false))
                .put(quota("另一窗口", 3, NOW + 600000, NOW, false));
        String[] sorted = render(LivePresentation.read(snapshot(99999999, urgency), task(123456789), "codex"), ALL).split("\n");
        check(sorted[0].equals("已记 本次1.2亿·今日9999.9万"), "large numbers compact downward without claiming a higher magnitude");
        check(sorted[1].startsWith("另一…余3%·10分重置"), "least remaining first; ties choose nearest reset in the visible second line");
        check(sorted[2].startsWith("5时余3%"), "second urgent quota remains available in notification drawer");
        check(!sorted[1].contains("80%"), "less urgent quota does not displace scarce quota from capsule");
        String onlySession = render(LivePresentation.read(snapshot(null, null), task(123), "codex"), ALL).split("\n")[0];
        check("Token 本次123".equals(onlySession) && !onlySession.contains("今日"), "missing daily count omitted, not synthetic zero");
        String onlyToday = render(LivePresentation.read(snapshot(456, null), task(null), "codex"), ALL).split("\n")[0];
        check("已记Token 今日456".equals(onlyToday) && !onlyToday.contains("本次"), "missing session count omitted and partial today retained");
        JSONObject partialTask = task(123); partialTask.optJSONObject("usage").put("coverage", "partial");
        check(render(LivePresentation.read(snapshot(null, null), partialTask, "codex"), ALL).startsWith("已记Token 本次123"),
                "session-only partial data keeps partial marker");
        LivePresentation.Options withoutUsage = new LivePresentation.Options(true, true, true, false, false, false);
        String fallback = render(data, withoutUsage);
        check(fallback.startsWith("Codex · 执行中\n家里的电脑 · 已跟踪2分"), "when usage toggles off, original state-first fallback remains");
        check(!fallback.contains("Token") && !fallback.contains("重置"), "display options remain authoritative despite available values");
        check(render(data, new LivePresentation.Options(true, true, false, false, false, false)).endsWith("家里的电脑"),
                "computer-only presentation has no trailing separator when elapsed disabled");
        LivePresentation.Data huge = LivePresentation.read(snapshot(9007199254740991L, quotas), task(9007199254740991L), "codex");
        for (String line : render(huge, ALL).split("\n")) check(LivePresentation.width(line) <= 32, "approximately sixteen fullwidth glyphs per body line");
        check(render(huge, ALL).startsWith("已记 本次9007万亿·今日9007万亿"), "extreme valid counts stay compact and keep units");
        JSONObject longSource = snapshot(123, new JSONArray().put(quota("非常长的套餐窗口名称", .4, NOW + 90000000, NOW, false)
                .put("source_name", "非常长的电脑名称供显示测试")));
        String[] bounded = render(LivePresentation.read(longSource, task(456), "codex"), ALL).split("\n");
        check(LivePresentation.width(bounded[1]) <= 32 && bounded[1].contains("余<1%") && bounded[1].endsWith("1天1时重置"),
                "long source/window clipped before meaningful balance and compact reset");
        JSONObject window = snapshot(null, new JSONArray().put(quota("周额度", 100, NOW + 86340000, NOW, false)
                .put("window_minutes", 10080).put("source_name", "办公室电脑")));
        String actual = render(LivePresentation.read(window, task(123), "codex"), ALL).split("\n")[1];
        check(actual.equals("7天余100%·23时59分重置"), "real window and reset remain complete without redundant device prefix");
        check(LivePresentation.width(actual) <= 24, "quota line has extra width margin for the actual Find capsule font");
        JSONArray accounts = new JSONArray().put(quota("other account", 1, NOW + 60000, NOW, false).put("source_device_id", "mac"))
                .put(quota("matched", 73, NOW + 60000, NOW, false))
                .put(quota("unattributed", 0, NOW + 60000, NOW, false).put("source_device_id", null));
        String own = render(LivePresentation.read(snapshot(100, accounts), task(200), "codex"), ALL);
        check(own.split("\n")[1].contains("73%") && !own.contains("余1%") && !own.contains("余0%"),
                "only selected task's matching device quota is eligible; another account and unknown source omitted");
        JSONObject noDevice = task(200).put("device_id", "");
        check(!render(LivePresentation.read(snapshot(100, accounts), noDevice, "codex"), ALL).contains("重置"),
                "task with no source device cannot inherit any provider quota");
        JSONObject findWeek = snapshot(14060000, new JSONArray().put(quota("7 天", 77, NOW + (6 * 24 + 19) * 3600000L, NOW, false)
                .put("window_minutes", 10080).put("source_name", "Makabaka")));
        String realCapsule = render(LivePresentation.read(findWeek, task(140000000), "codex"), ALL).split("\n")[1];
        check(realCapsule.equals("7天余77%·6天19时重置"), "Find weekly capsule regression: reset suffix fits after removing redundant source");
        check(LivePresentation.width(realCapsule) <= 22, "Find reported weekly quota stays within twenty-two halfwidth units");
        System.out.println("LivePresentationTest: " + checks + " checks passed");
    }
}
