package com.agentmonitor.live;

import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic snapshots only; never reads task files, credentials, devices or the network. */
public final class NativePresentationTest {
    private static int checks;
    private static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
    private static JSONObject task(String id) {
        return new JSONObject().put("id", id).put("title", "Synthetic task").put("device_id", "synthetic-device")
                .put("tool", "codex").put("status", "running").put("updated_at", "2026-01-01T00:00:00Z").put("archived", false);
    }
    private static JSONObject device() {
        return new JSONObject().put("id", "synthetic-device").put("name", "Synthetic computer").put("platform", "Synthetic OS")
                .put("online", true).put("last_seen", "2026-01-01T00:00:00Z");
    }
    private static String key(JSONObject task, JSONObject device) { return NativeListPresentation.tasksKey(new JSONArray().put(task), new JSONArray().put(device), false, 1); }
    private static void listRefresh() {
        JSONObject task = task("synthetic-task"); JSONObject device = device(); String before = key(task, device);
        task.put("output", "synthetic nonvisual body").put("error_analysis", new JSONObject().put("summary", "synthetic detail"));
        device.put("usage", new JSONObject().put("synthetic", 100));
        check(before.equals(key(task, device)), "unshown output, error detail and usage do not rebind task rows");
        for (String field : new String[]{"id", "title", "device_id", "tool", "status", "updated_at"}) {
            JSONObject changed = task("synthetic-task"); changed.put(field, "changed");
            check(!before.equals(key(changed, device)), "visible task field invalidates: " + field);
        }
        check(!before.equals(key(task("synthetic-task").put("archived", true), device)), "archive action changes invalidates");
        check(!before.equals(key(task, device().put("name", "Renamed synthetic computer"))), "computer rename invalidates");
        check(!before.equals(key(task, device().put("online", false))), "online status invalidates");
        check(!before.equals(NativeListPresentation.tasksKey(new JSONArray().put(task), null, false, 1)), "missing computer changes status");
        check(!before.equals(NativeListPresentation.tasksKey(new JSONArray().put(task), new JSONArray().put(device), true, 1)), "stale transition invalidates");
        check(!before.equals(NativeListPresentation.tasksKey(new JSONArray().put(task), new JSONArray().put(device), false, 2)), "relative time refreshes each minute");
        JSONArray pair = new JSONArray().put(task("first")).put(task("second"));
        check(!NativeListPresentation.tasksKey(pair, null, false, 1).equals(NativeListPresentation.tasksKey(new JSONArray().put(task("second")).put(task("first")), null, false, 1)), "row order invalidates");
        JSONObject unrelated = device().put("id", "unrelated").put("name", "Unrelated computer");
        check(before.equals(NativeListPresentation.tasksKey(new JSONArray().put(task), new JSONArray().put(device).put(unrelated), false, 1)), "unreferenced computer changes leave task rows intact");
        check(before.equals(NativeListPresentation.tasksKey(new JSONArray().put(null).put(task), new JSONArray().put(device).put(device().put("name", "Ignored duplicate")), false, 1)), "first computer match agrees with filter model and invalid objects ignored");
        JSONObject first = task("ab").put("title", "c"), second = task("a").put("title", "bc");
        check(!key(first, device).equals(key(second, device)), "field lengths prevent ambiguous signatures");
        String deviceKey = NativeListPresentation.devicesKey(new JSONArray().put(device), false, 1);
        device.put("task_count", 9); check(deviceKey.equals(NativeListPresentation.devicesKey(new JSONArray().put(device), false, 1)), "unshown device metadata does not rebind");
        device.put("last_seen", "2026-01-01T00:00:05Z"); check(deviceKey.equals(NativeListPresentation.devicesKey(new JSONArray().put(device), false, 1)), "online heartbeat changes do not rebind rows");
        device.put("online", false); String offlineKey = NativeListPresentation.devicesKey(new JSONArray().put(device), false, 1);
        device.put("last_seen", "2026-01-01T00:00:06Z"); check(!offlineKey.equals(NativeListPresentation.devicesKey(new JSONArray().put(device), false, 1)), "offline last-seen remains visible and invalidates");
        device.put("platform", "Updated synthetic OS"); check(!deviceKey.equals(NativeListPresentation.devicesKey(new JSONArray().put(device), false, 1)), "platform text invalidates");
        check("Codex".equals(NativeListPresentation.toolLabel("codex")), "Codex source has explicit text");
        check("Claude Code".equals(NativeListPresentation.toolLabel("claude")), "Claude source has explicit text");
        check("任务".equals(NativeListPresentation.toolLabel("unexpected")), "unknown source does not impersonate known tools");
    }
    private static void errors() {
        check(!NativeErrorPresentation.from(null).visible, "no task has no error section");
        JSONObject task = task("synthetic-error");
        check(!NativeErrorPresentation.from(task).visible, "running task has no error section");
        task.put("status", "error"); NativeErrorPresentation.Detail absent = NativeErrorPresentation.from(task);
        check(absent.visible && !absent.hasAnalysis, "failure without evidence has only the unavailable note");
        check(absent.summary.contains("尚无可分析"), "failure without evidence does not invent diagnosis");
        JSONObject analysis = new JSONObject().put("summary", "依赖缺失").put("suggestion", "检查依赖").put("location", new JSONObject()
                .put("file", "synthetic/path/module.py").put("line", 42).put("column", 7).put("step", "运行测试"));
        task.put("error_analysis", analysis); NativeErrorPresentation.Detail known = NativeErrorPresentation.from(task);
        check(known.visible && known.hasAnalysis, "structured failure is shown");
        check(!NativeErrorPresentation.from(task, true, "unknown").visible, "stale or offline failure is not displayed as current error evidence");
        check(!NativeErrorPresentation.from(task, true, "running").visible, "effective state takes precedence over old error status");
        NativeErrorPresentation.Detail disabled = NativeErrorPresentation.from(task, false);
        check(disabled.visible && !disabled.hasAnalysis && disabled.location.isEmpty() && disabled.suggestion.isEmpty(), "disabled output synchronization never shows cached analysis");
        check(known.location.contains("module.py") && !known.location.contains("synthetic/path"), "location only renders basename");
        check(known.location.contains("42") && known.location.contains("7") && known.location.contains("运行测试"), "reported line column and step displayed");
        analysis.put("location", new JSONObject().put("file", "C:\\synthetic\\example.java").put("line", -1).put("column", 5));
        known = NativeErrorPresentation.from(task); check("example.java".equals(known.location), "Windows basename and invalid line handled without fabricated column");
        analysis.put("location", null); known = NativeErrorPresentation.from(task);
        check(known.location.contains("未定位"), "missing location explicitly unknown");
        analysis.put("summary", "\u0000").put("suggestion", ""); known = NativeErrorPresentation.from(task);
        check(known.summary.contains("尚不能确认") && !known.suggestion.isEmpty(), "empty malformed summary stays conservative");
        task.put("status", "completed"); check(!NativeErrorPresentation.from(task).visible, "recovered task hides prior error analysis");
        StringBuilder longText = new StringBuilder(); for (int i = 0; i < 2000; i++) longText.append('x');
        task.put("status", "error"); analysis.put("summary", longText.toString());
        check(NativeErrorPresentation.from(task).summary.length() == 600, "malformed long summaries remain bounded");
    }
    public static void main(String[] args) {
        listRefresh(); errors(); System.out.println("NativePresentationTest: " + checks + " synthetic checks passed");
    }
}
