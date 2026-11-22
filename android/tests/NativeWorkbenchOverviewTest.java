package com.agentmonitor.live;

import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic snapshots exercise conservative state reporting without any Android or device API. */
public final class NativeWorkbenchOverviewTest {
    private static final String SCOPE = "全部未归档 · ";
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void expect(String expected, JSONObject snapshot, boolean stale) {
        check((SCOPE + expected).equals(NativeWorkbenchOverview.describe(snapshot, stale)), expected);
    }
    private static JSONObject task(String status, String device, boolean archived) {
        return new JSONObject().put("tool", "codex").put("status", status)
                .put("device_id", device).put("archived", archived);
    }
    private static JSONObject device(String id, boolean online) {
        return new JSONObject().put("id", id).put("online", online);
    }
    private static JSONObject snapshot(JSONArray tasks, JSONArray devices) {
        return new JSONObject().put("tasks", tasks).put("devices", devices);
    }
    private static JSONArray online() { return new JSONArray().put(device("source", true)); }
    private static JSONObject one(JSONObject task) { return snapshot(new JSONArray().put(task), online()); }

    public static void main(String[] args) {
        availability(); activeCounts(); uncertainTasks(); sourceAvailability(); archivedExcluded(); inputsAndPrivacy();
        priorityDescription(); pendingTasksCrossFilter();
        System.out.println("NativeWorkbenchOverviewTest: " + checks + " checks passed");
    }

    private static void pendingTasksCrossFilter() {
        JSONArray computers = new JSONArray()
                .put(device("pc-codex", true))
                .put(device("pc-claude", true))
                .put(device("pc-offline", false));
        JSONObject codexRunning = task("running", "pc-codex", false).put("id", "t-codex");
        JSONObject genericWait = task("waiting", "pc-codex", false).put("id", "t-wait");
        JSONObject claudeQuestion = task("completed", "pc-claude", false)
                .put("id", "t-claude-q").put("tool", "claude").put("pending_interaction_kind", "question");
        JSONObject agApproval = task("waiting", "pc-claude", false)
                .put("id", "t-ag-a").put("tool", "antigravity").put("pending_interaction_kind", "approval");
        JSONObject offlineQuestion = task("waiting", "pc-offline", false)
                .put("id", "t-offline").put("tool", "claude").put("pending_interaction_kind", "question");
        JSONObject archivedQuestion = task("waiting", "pc-claude", true)
                .put("id", "t-archived").put("tool", "claude").put("pending_interaction_kind", "question");
        JSONObject data = snapshot(new JSONArray()
                .put(codexRunning).put(genericWait).put(claudeQuestion)
                .put(agApproval).put(offlineQuestion).put(archivedQuestion), computers)
                .put("tool_filter", "codex");
        java.util.List<JSONObject> pending = NativeWorkbenchOverview.pendingTasks(data, false);
        check(pending.size() == 3 && NativeWorkbenchOverview.waitingCount(data, false) == 3,
                "cross-computer waiting count includes Claude and Antigravity even when tool_filter is codex");
        check("t-claude-q".equals(pending.get(0).optString("id"))
                && "t-ag-a".equals(pending.get(1).optString("id"))
                && "t-wait".equals(pending.get(2).optString("id")),
                "answerable question and approval tasks are ordered ahead of generic waiting tasks");
        check(NativeWorkbenchOverview.pendingTasks(data, true).isEmpty(), "stale snapshot exposes zero clickable pending tasks");
    }

    private static void availability() {
        expect("正在读取任务状态", null, false);
        expect("正在读取任务状态", null, true);
        expect("正在读取任务状态", new JSONObject(), false);
        expect("正在读取任务状态", new JSONObject().put("tasks", new JSONArray()), false);
        expect("正在读取任务状态", new JSONObject().put("devices", online()), false);
        expect("正在读取任务状态", new JSONObject().put("tasks", "not an array").put("devices", online()), false);
        expect("正在读取任务状态", new JSONObject().put("tasks", new JSONArray()).put("devices", "not an array"), false);
        expect("状态待更新", one(task("running", "source", false)), true);
        expect("还没有连接电脑", snapshot(new JSONArray(), new JSONArray()), false);
        expect("电脑已离线", snapshot(new JSONArray(), new JSONArray().put(device("source", false))), false);
        expect("暂无进行中的任务", snapshot(new JSONArray(), online()), false);
        expect("暂无进行中的任务", snapshot(new JSONArray(), new JSONArray().put(device("source", false)).put(device("other", true))), false);
    }

    private static void activeCounts() {
        JSONArray entries = new JSONArray().put(task("running", "source", false)).put(task("running", "source", false))
                .put(task("waiting", "source", false)).put(task("error", "source", false))
                .put(task("idle", "source", false)).put(task("completed", "source", false));
        expect("执行中 2 · 待处理 1 · 出错 1", snapshot(entries, online()), false);
        expect("执行中 1", one(task("running", "source", false)), false);
        expect("待处理 1", one(task("waiting", "source", false)), false);
        expect("待处理 1", one(task("completed", "source", false).put("pending_interaction_kind", "question")), false);
        expect("出错 1", one(task("error", "source", false)), false);
        expect("暂无进行中的任务", one(task("idle", "source", false)), false);
        expect("暂无进行中的任务", one(task("completed", "source", false)), false);
        expect("执行中 1", one(task("running", "source", false).put("tool", "other")), false);
        check(!NativeWorkbenchOverview.describe(one(task("waiting", "source", false)), false).contains("批准"),
                "waiting does not assert an approval request");
    }

    private static void uncertainTasks() {
        for (Object malformed : new Object[]{null, "not a task", 7, new JSONArray()})
            expect("部分状态待确认", snapshot(new JSONArray().put(malformed), online()), false);
        expect("部分状态待确认", one(new JSONObject()), false);
        expect("部分状态待确认", one(task("future_status", "source", false)), false);
        expect("部分状态待确认", one(task("", "source", false)), false);
        expect("部分状态待确认", one(task("running", "source", false).put("status", 1)), false);
        expect("部分状态待确认", one(task("running", "source", false).put("archived", "false")), false);
        expect("部分状态待确认", one(task("running", "source", false).put("archived", "true")), false);
        expect("部分状态待确认", one(task("running", "source", false).put("tool", null)), false);
        expect("部分状态待确认", one(task("running", "source", false).put("tool", " \t ")), false);
        expect("部分状态待确认", one(task("running", "source", false).put("device_id", 7)), false);
        expect("部分状态待确认", snapshot(new JSONArray().put(task("running", " \t ", false)),
                new JSONArray().put(device(" \t ", true))), false);
        expect("执行中 1 · 部分状态待确认", snapshot(new JSONArray().put(task("running", "source", false))
                .put(task("future_status", "source", false)), online()), false);
        expect("部分状态待确认", snapshot(new JSONArray().put(task("idle", "source", false)).put("malformed"), online()), false);
    }

    private static void sourceAvailability() {
        expect("部分状态待确认", one(task("running", "missing", false)), false);
        expect("部分状态待确认", snapshot(new JSONArray().put(task("running", "source", false)), new JSONArray()), false);
        expect("电脑已离线 · 部分状态待确认", snapshot(new JSONArray().put(task("running", "source", false)),
                new JSONArray().put(device("source", false))), false);
        expect("电脑已离线 · 部分状态待确认", snapshot(new JSONArray().put(task("completed", "source", false)),
                new JSONArray().put(device("source", false))), false);
        expect("部分状态待确认", snapshot(new JSONArray().put(task("running", "source", false)),
                new JSONArray().put(device("source", true).put("online", "true"))), false);
        expect("部分状态待确认", snapshot(new JSONArray().put(task("running", "source", false)),
                new JSONArray().put(device("source", true)).put(device("source", false))), false);
        expect("部分状态待确认", snapshot(new JSONArray().put(task("running", "source", false)),
                new JSONArray().put(device("source", false)).put(device("source", true))), false);
        expect("部分状态待确认", snapshot(new JSONArray(), new JSONArray().put("malformed")), false);
        expect("部分状态待确认", snapshot(new JSONArray(), new JSONArray().put(new JSONObject().put("online", false))), false);
        expect("部分状态待确认", snapshot(new JSONArray(), new JSONArray().put(device("source", false).put("online", "false"))), false);
        expect("执行中 1", snapshot(new JSONArray().put(task("running", "source", false)),
                online().put(device("unrelated", false))), false);
    }

    private static void archivedExcluded() {
        JSONArray entries = new JSONArray().put(task("running", "source", true))
                .put(task("future_status", "missing", true)).put(new JSONObject().put("archived", true));
        expect("暂无进行中的任务", snapshot(entries, online()), false);
        expect("还没有连接电脑", snapshot(entries, new JSONArray()), false);
        expect("电脑已离线", snapshot(entries, new JSONArray().put(device("source", false))), false);
        entries.put(task("waiting", "source", false));
        expect("待处理 1", snapshot(entries, online()), false);
    }

    private static void compact(String expected, JSONObject data, boolean stale) {
        String full = NativeWorkbenchOverview.describe(data, stale);
        check(("未归档 · " + expected).equals(NativeWorkbenchOverview.compactDescription(full)), expected);
        check(full.equals(NativeWorkbenchOverview.describe(data, stale)), "complete summary is unchanged");
    }

    private static void priorityDescription() {
        JSONArray mixed = new JSONArray().put(task("running", "source", false))
                .put(task("waiting", "source", false)).put(task("error", "source", false))
                .put(task("future_status", "source", false));
        JSONObject data = snapshot(mixed, online());
        compact("出错 1", data, false);
        check((SCOPE + "执行中 1 · 待处理 1 · 出错 1 · 部分状态待确认")
                .equals(NativeWorkbenchOverview.describe(data, false)), "all counts and uncertainty remain in full summary");
        compact("待处理 1", snapshot(new JSONArray().put(task("running", "source", false))
                .put(task("waiting", "source", false)).put(task("future_status", "source", false)), online()), false);
        compact("状态待确认", snapshot(new JSONArray().put(task("running", "source", false))
                .put(task("future_status", "source", false)), online()), false);
        compact("运行 1", one(task("running", "source", false)), false);
        compact("暂无进行中", one(task("completed", "source", false)), false);
        compact("暂无进行中", one(task("idle", "source", false)), false);
        compact("运行 1", snapshot(new JSONArray().put(task("error", "source", true))
                .put(task("running", "source", false)), online()), false);
        compact("读取中", null, false);
        compact("待更新", data, true);
        compact("未连接电脑", snapshot(new JSONArray(), new JSONArray()), false);
        compact("电脑离线", snapshot(new JSONArray(), new JSONArray().put(device("source", false))), false);
        compact("电脑离线", snapshot(new JSONArray().put(task("running", "source", false)),
                new JSONArray().put(device("source", false))), false);
        compact("状态待确认", one(task("running", "missing", false)), false);
        compact("状态待确认", snapshot(new JSONArray(), new JSONArray().put("malformed")), false);
        check("未归档 · 出错 12".equals(NativeWorkbenchOverview.compactDescription(
                SCOPE + "执行中 100 · 待处理 7 · 出错 12 · 部分状态待确认")), "only priority count is visible");
        check("未归档 · 出错 2147483647".equals(NativeWorkbenchOverview.compactDescription(
                SCOPE + "出错 2147483647")), "count is not clipped or wrapped");
        for (String invalid : new String[]{null, "", "synthetic-private-content", "未归档 · 执行中 1",
                SCOPE + "", SCOPE + "执行中 0", SCOPE + "出错 -1", SCOPE + "出错 01",
                SCOPE + "出错 2147483648", SCOPE + "执行中 1 · future",
                SCOPE + "执行中 1 · ", SCOPE + "出错 1 · 执行中 2",
                SCOPE + "待处理 1 · 待处理 2", SCOPE + "暂无进行中的任务 · 部分状态待确认"}) {
            check("未归档 · 状态待确认".equals(NativeWorkbenchOverview.compactDescription(invalid)),
                    "unknown summary cannot become idle or expose received text");
        }
    }

    private static void inputsAndPrivacy() {
        JSONObject entry = task("running", "source", false).put("title", "synthetic-private-title")
                .put("body", "synthetic-private-body").put("name", "synthetic-private-name")
                .put("result", "synthetic-private-result").put("unknown", "synthetic-private-extra");
        JSONObject source = device("source", true).put("name", "synthetic-private-computer");
        JSONArray tasks = new JSONArray().put(entry), devices = new JSONArray().put(source);
        JSONObject data = snapshot(tasks, devices).put("query", "synthetic-private-query").put("tool_filter", "claude");
        expect("执行中 1", data, false);
        check("synthetic-private-title".equals(entry.opt("title")) && "synthetic-private-body".equals(entry.opt("body"))
                && "synthetic-private-computer".equals(source.opt("name")) && "synthetic-private-query".equals(data.opt("query"))
                && "claude".equals(data.opt("tool_filter")), "private fields and selection metadata are unchanged");
        check(data.optJSONArray("tasks") == tasks && data.optJSONArray("devices") == devices
                && tasks.optJSONObject(0) == entry && devices.optJSONObject(0) == source,
                "input arrays and records retain their identity");
        check(tasks.length() == 1 && devices.length() == 1 && Boolean.FALSE.equals(entry.opt("archived"))
                && "running".equals(entry.opt("status")) && Boolean.TRUE.equals(source.opt("online")),
                "state and membership remain unchanged");
        Object neverSerialize = new Object() { @Override public String toString() { throw new AssertionError("private field serialized"); } };
        entry.put("title", neverSerialize).put("body", neverSerialize).put("name", neverSerialize).put("unknown", neverSerialize);
        source.put("name", neverSerialize);
        expect("执行中 1", data, false);
        entry.put("archived", true).put("tool", neverSerialize).put("status", neverSerialize).put("device_id", neverSerialize);
        expect("暂无进行中的任务", data, false);
    }
}
