package com.agentmonitor.live;

import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises production selection using isolated JSON doubles and synthetic snapshots. */
public final class NativeTaskListModelTest {
    private static int checks;
    private static JSONArray tasks, devices;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static JSONObject task(String id, String title, String project, String tool, String status, boolean archived, String device) {
        return new JSONObject().put("id", id).put("title", title).put("project", project).put("tool", tool)
                .put("status", status).put("archived", archived).put("device_id", device);
    }
    private static JSONObject device(String id, String name, boolean online) { return new JSONObject().put("id", id).put("name", name).put("online", online); }
    private static void fixture() {
        devices = new JSONArray().put(device("d1", "Office Laptop", true)).put(device("d2", "Home Desktop", false));
        tasks = new JSONArray()
                .put(task("t1", "Fix TLS", "Alpha", "codex", "running", false, "d1"))
                .put(task("t2", "Photo Backup", "Beta", "claude", "completed", false, "d1"))
                .put(task("t3", "Review Search", "Alpha", "claude", "waiting", false, "d1"))
                .put(task("t4", "Retry Login", "Gamma", "codex", "error", false, "d1"))
                .put(task("t5", "Old Search", "Alpha", "codex", "running", true, "d1"))
                .put(task("t6", "Remote Search", "Alpha", "codex", "running", false, "d2"))
                .put(task("t7", "Unknown Search", "Delta", "claude", "future_status", false, "d1"))
                .put(task("t8", "Lost Task", "Omega", "claude", "running", false, "missing"))
                .put(task("t9", "Notes", "Other", "other", "idle", false, "d1"))
                .put(task("t10", "Old Offline", "Archive", "claude", "completed", true, "d2"));
    }
    private static NativeTaskListModel.Result select(String tool, String scope, String query, boolean stale) {
        return NativeTaskListModel.select(tasks, devices, tool, scope, null, query, stale);
    }
    private static void ids(NativeTaskListModel.Result result, String... expected) {
        check(result.tasks.length() == expected.length, "selected task count");
        for (int i = 0; i < expected.length; i++) check(expected[i].equals(result.tasks.optJSONObject(i).optString("id")), "selection keeps snapshot order at " + i);
    }
    private static void counts(NativeTaskListModel.Result result, int all, int claude, int codex, int uncertain) {
        counts(result, all, claude, codex, 0, uncertain);
    }
    private static void counts(NativeTaskListModel.Result result, int all, int claude, int codex, int antigravity, int uncertain) {
        check(java.util.Arrays.equals(result.toolCounts, new int[]{all, claude, codex, antigravity}), "tool counts describe the query and current scope");
        check(result.uncertainSelectedTasks == uncertain, "uncertain selected-tool records retain their own source/status semantics");
    }
    public static void main(String[] args) {
        emptyQueryCompatibility(); searchAndFilters(); antigravityTasks(); scopedComputerPage(); refreshAndInputOwnership(); malformedSnapshots(); allowedFieldsOnly(); pendingQuestions(); hiddenFilterExplanation();
        System.out.println("NativeTaskListModelTest: " + checks + " checks passed");
    }
    private static void hiddenFilterExplanation() {
        JSONArray onePc = new JSONArray().put(device("d1", "Studio PC", true));
        JSONArray sixClaudeTasks = new JSONArray();
        for (int i = 1; i <= 6; i++) {
            sixClaudeTasks.put(task("c" + i, "Claude Task " + i, "Proj", "claude", "completed", false, "d1"));
        }
        NativeTaskListModel.Result codexOnly = NativeTaskListModel.select(sixClaudeTasks, onePc, "codex", "active", null, "", false);
        check(codexOnly.tasks.length() == 0, "Codex filter hides all 6 Claude tasks");
        NativeTaskListModel.HiddenSummary single = NativeTaskListModel.hiddenSummary(
                sixClaudeTasks, onePc, "codex", "active", null, "", false, codexOnly.tasks.length());
        check(single.hiddenTasks == 6 && single.showResetAction, "hidden task count and reset action reported");
        check("这台电脑有 6 条任务，当前筛选隐藏了 6 条".equals(single.message),
                "single-computer hidden filter message matches product copy: " + single.message);

        JSONArray twoPcs = new JSONArray().put(device("d1", "Studio PC", true)).put(device("d2", "MacBook", true));
        sixClaudeTasks.optJSONObject(5).put("device_id", "d2");
        NativeTaskListModel.HiddenSummary multi = NativeTaskListModel.hiddenSummary(
                sixClaudeTasks, twoPcs, "codex", "all", null, "", false, 0);
        check("已连接电脑共有 6 条任务，当前筛选隐藏了 6 条".equals(multi.message) && multi.showResetAction,
                "multi-computer hidden filter message distinguishes multiple computers");

        check(!NativeTaskListModel.hiddenSummary(sixClaudeTasks, onePc, "all", "all", null, "", false, 0).showResetAction,
                "unfiltered empty state does not blame filters");
        check(!NativeTaskListModel.hiddenSummary(sixClaudeTasks, onePc, "codex", "active", null, "", true, 0).showResetAction,
                "stale snapshot does not claim clean filter hiding");
    }
    private static void pendingQuestions() {
        for (String tool : new String[]{"codex", "claude", "antigravity"}) {
            JSONObject question = task("ask", "Choose", "", tool, "completed", false, "d1");
            question.put("pending_interaction_kind", "question");
            tasks = new JSONArray().put(question);
            devices = new JSONArray().put(device("d1", "Laptop", true));
            ids(select(tool, "active", "", false), "ask");
            check("completed".equals(question.optString("status")), "pending interaction does not overwrite source status");
            ids(select(tool, "active", "", true));
            question.put("pending_interaction_kind", JSONObject.NULL); ids(select(tool, "active", "", false));
            question.put("pending_interaction_kind", "other"); ids(select(tool, "active", "", false));
            question.put("pending_interaction_kind", "approval"); ids(select(tool, "active", "", false), "ask");
            devices = new JSONArray().put(device("d1", "Laptop", false));
            ids(select(tool, "active", "", false));
        }
    }
    private static void emptyQueryCompatibility() {
        fixture();
        for (String query : new String[]{null, "", "\t \u00a0\u3000"}) {
            NativeTaskListModel.Result all = select("all", "all", query, false);
            ids(all, "t1", "t2", "t3", "t4", "t6", "t7", "t8", "t9"); counts(all, 8, 4, 3, 3);
            NativeTaskListModel.Result active = select("all", "active", query, false);
            ids(active, "t1", "t3", "t4"); counts(active, 3, 1, 2, 3);
            NativeTaskListModel.Result archived = select("all", "archived", query, false);
            ids(archived, "t5", "t10"); counts(archived, 2, 1, 1, 3);
        }
        NativeTaskListModel.Result codex = select("codex", "all", "", false);
        ids(codex, "t1", "t4", "t6"); counts(codex, 8, 4, 3, 1);
        NativeTaskListModel.Result claude = select("claude", "active", "", false);
        ids(claude, "t3"); counts(claude, 3, 1, 2, 2);
        NativeTaskListModel.Result staleActive = select("all", "active", "", true);
        ids(staleActive); counts(staleActive, 0, 0, 0, 8);
        NativeTaskListModel.Result staleAll = select("all", "all", "", true);
        ids(staleAll, "t1", "t2", "t3", "t4", "t6", "t7", "t8", "t9"); counts(staleAll, 8, 4, 3, 8);
        NativeTaskListModel.Result defaults = select("bad", "bad", "", false);
        ids(defaults, "t1", "t3", "t4"); counts(defaults, 3, 1, 2, 3);
    }
    private static void searchAndFilters() {
        fixture();
        NativeTaskListModel.Result search = select("all", "all", "search", false);
        ids(search, "t3", "t6", "t7"); counts(search, 3, 2, 1, 2);
        NativeTaskListModel.Result active = select("all", "active", "ＳＥＡＲＣＨ", false);
        ids(active, "t3"); counts(active, 1, 1, 0, 2);
        NativeTaskListModel.Result codex = select("codex", "all", "search", false);
        ids(codex, "t6"); counts(codex, 3, 2, 1, 1);
        NativeTaskListModel.Result archived = select("all", "archived", "search", false);
        ids(archived, "t5"); counts(archived, 1, 0, 1, 2);
        NativeTaskListModel.Result fields = select("all", "all", "Alpha LAPTOP", false);
        ids(fields, "t1", "t3"); counts(fields, 2, 1, 1, 0);
        NativeTaskListModel.Result missing = select("all", "all", "absent", false);
        ids(missing); counts(missing, 0, 0, 0, 0);
        NativeTaskListModel.Result selected = select("codex", "active", "Gamma", false);
        ids(selected, "t4"); counts(selected, 1, 0, 1, 0);
        NativeTaskListModel.Result stale = select("all", "active", "search", true);
        ids(stale); counts(stale, 0, 0, 0, 3);
        NativeTaskListModel.Result unrelatedOffline = select("all", "active", "Photo", false);
        ids(unrelatedOffline); counts(unrelatedOffline, 0, 0, 0, 0);
        NativeTaskListModel.Result offlineMatches = select("all", "active", "Remote", false);
        ids(offlineMatches); counts(offlineMatches, 0, 0, 0, 1);
        ids(select("all", "all", "  ", false), "t1", "t2", "t3", "t4", "t6", "t7", "t8", "t9");
    }
    private static void antigravityTasks() {
        fixture();
        tasks = new JSONArray()
                .put(task("build", "Search feature", "Test", "antigravity", "running", false, "d1"))
                .put(task("archive", "Search old", "Test", "antigravity", "completed", true, "d1"))
                .put(task("offline", "Search offline", "Test", "antigravity", "running", false, "d2"))
                .put(task("completed", "Review changes", "Test", "codex", "completed", false, "d1"))
                .put(task("claude", "Search index", "Test", "claude", "running", false, "d1"));
        NativeTaskListModel.Result active = select("antigravity", "active", "search", false);
        ids(active, "build"); counts(active, 2, 1, 0, 1, 1);
        NativeTaskListModel.Result all = select("all", "all", "", false);
        ids(all, "build", "offline", "completed", "claude"); counts(all, 4, 1, 1, 2, 1);
        NativeTaskListModel.Result history = select("antigravity", "all", "search", false);
        ids(history, "build", "offline"); counts(history, 3, 1, 0, 2, 1);
        ids(select("antigravity", "archived", "", false), "archive");
        ids(NativeTaskListModel.select(tasks, devices, "all", "all", "d1", "", false), "build", "completed", "claude");
    }
    private static void scopedComputerPage() {
        fixture();
        NativeTaskListModel.Result computer = NativeTaskListModel.select(tasks, devices, "codex", "archived", "d1", "never matches", false);
        ids(computer, "t1", "t2", "t3", "t4", "t7", "t9"); counts(computer, 0, 0, 0, 1);
        ids(NativeTaskListModel.select(tasks, devices, "all", "active", "d2", "Laptop", true), "t6");
        ids(NativeTaskListModel.select(tasks, devices, "all", "all", "missing", "", false), "t8");
        ids(NativeTaskListModel.select(tasks, devices, "all", "all", "", "", false));
    }
    private static void refreshAndInputOwnership() {
        fixture();
        JSONObject before = tasks.optJSONObject(0);
        NativeTaskListModel.Result result = select("all", "all", "Alpha", false);
        ids(result, "t1", "t3", "t6");
        check(result.tasks != tasks && result.tasks.optJSONObject(0) == before, "model creates a selection array without cloning or editing task records");
        check(tasks.length() == 10 && "Fix TLS".equals(before.optString("title")) && !before.optBoolean("archived"), "selection does not alter original data or archival state");
        check(devices.length() == 2 && "Office Laptop".equals(devices.optJSONObject(0).optString("name")), "device input remains unchanged");
        result.toolCounts[0] = 999;
        counts(select("all", "all", "Alpha", false), 3, 1, 2, 1);
        tasks = new JSONArray().put(task("fresh", "Fresh Alpha", "Alpha", "codex", "running", false, "d1"))
                .put(task("replacement", "New Work", "Beta", "claude", "running", false, "d1"));
        ids(select("all", "all", "Alpha", false), "fresh");
        ids(select("all", "all", "", false), "fresh", "replacement");
        devices = new JSONArray().put(device("d1", "Renamed Laptop", true));
        ids(select("all", "all", "Office", false));
        ids(select("all", "all", "Renamed", false), "fresh", "replacement");
        devices = new JSONArray().put(device("d1", "Renamed Laptop", false));
        NativeTaskListModel.Result offline = select("all", "active", "Alpha", false);
        ids(offline); counts(offline, 0, 0, 0, 1);
    }
    private static void malformedSnapshots() {
        NativeTaskListModel.Result empty = NativeTaskListModel.select(null, null, "all", "all", null, "", false);
        ids(empty); counts(empty, 0, 0, 0, 0);
        tasks = new JSONArray().put(null).put("not a task").put(task("valid", "Valid", "Project", "codex", "running", false, "d1"));
        devices = new JSONArray().put(null).put("not a computer").put(device("d1", "First Computer", true)).put(device("d1", "Second Computer", false));
        ids(select("all", "active", "First", false), "valid");
        ids(select("all", "all", "Second", false));
        counts(select("all", "active", "", false), 1, 0, 1, 0);
        devices = null;
        NativeTaskListModel.Result absentDevice = select("all", "active", "Valid", false);
        ids(absentDevice); counts(absentDevice, 0, 0, 0, 1);
        ids(select("all", "all", "Valid", false), "valid");
    }
    private static void allowedFieldsOnly() {
        JSONObject entry = task("id-secret", "Visible Title", "Visible Project", "codex", "running", false, "d1")
                .put("body", "body-secret").put("result", "result-secret").put("output", "output-secret")
                .put("account", "account-secret").put("project_path", "path-secret");
        tasks = new JSONArray().put(entry); devices = new JSONArray().put(device("d1", "Visible Computer", true).put("account", "device-secret"));
        for (String excluded : new String[]{"id-secret", "body-secret", "result-secret", "output-secret", "account-secret", "path-secret", "device-secret"})
            ids(select("all", "all", excluded, false));
        ids(select("all", "all", "title project computer", false), "id-secret");
    }
}
