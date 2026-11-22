package com.agentmonitor.live;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only selection over a loaded snapshot. No requests, stored queries or task mutations. */
public final class NativeTaskListModel {
    public static final class Result {
        public final JSONArray tasks;
        public final int[] toolCounts;
        public final int uncertainSelectedTasks;
        private Result(JSONArray tasks, int[] toolCounts, int uncertainSelectedTasks) {
            this.tasks = tasks; this.toolCounts = toolCounts; this.uncertainSelectedTasks = uncertainSelectedTasks;
        }
    }

    public static final class HiddenSummary {
        public final int totalTasks;
        public final int hiddenTasks;
        public final boolean singleComputer;
        public final String message;
        public final boolean showResetAction;
        private HiddenSummary(int totalTasks, int hiddenTasks, boolean singleComputer,
                              String message, boolean showResetAction) {
            this.totalTasks = totalTasks;
            this.hiddenTasks = hiddenTasks;
            this.singleComputer = singleComputer;
            this.message = message;
            this.showResetAction = showResetAction;
        }
    }

    /** A non-null deviceId retains the computer page's existing unarchived-task behavior. */
    public static Result select(JSONArray tasks, JSONArray devices, String tool, String scope,
                                String deviceId, String query, boolean stale) {
        Map<String, JSONObject> computers = new HashMap<>();
        if (devices != null) for (int index = 0; index < devices.length(); index++) {
            JSONObject device = devices.optJSONObject(index);
            // Match the existing first-match lookup if a malformed snapshot repeats an ID.
            if (device != null && !computers.containsKey(device.optString("id"))) computers.put(device.optString("id"), device);
        }
        NativeTaskSearchPolicy.Query search = NativeTaskSearchPolicy.compile(deviceId == null ? query : "");
        JSONArray shown = new JSONArray(); int[] counts = new int[NativeScreenPolicy.toolFilterCount()]; int uncertain = 0;
        if (tasks != null) for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index); if (task == null) continue;
            JSONObject device = computers.get(task.optString("device_id"));
            // The public snapshot's project field is `project`, not a filesystem lookup.
            if (!search.matches(task.optString("title"), task.optString("project"), device == null ? "" : device.optString("name"))) continue;
            String actualTool = task.optString("tool"), status = task.optString("status");
            boolean archived = task.optBoolean("archived"), unknown = stale || device == null || !device.optBoolean("online");
            if (!unknown && !NativeListPresentation.pendingKind(task).isEmpty()) status = "waiting";
            if (NativeWorkbenchPolicy.uncertainTask(tool, actualTool, status, archived, unknown)) uncertain++;
            if (deviceId != null) {
                if (deviceId.equals(task.optString("device_id")) && !archived) shown.put(task);
            } else {
                if (NativeScreenPolicy.includes("all", scope, actualTool, status, archived, unknown)) {
                    counts[0]++;
                    int indexInFilter = NativeScreenPolicy.toolIndex(actualTool);
                    if (indexInFilter > 0) counts[indexInFilter]++;
                }
                if (NativeScreenPolicy.includes(tool, scope, actualTool, status, archived, unknown)) shown.put(task);
            }
        }
        return new Result(shown, counts, uncertain);
    }

    /**
     * Explains when active tool/scope/search filters hide all existing unarchived tasks,
     * so an empty workbench never looks like a broken computer sync.
     */
    public static HiddenSummary hiddenSummary(JSONArray tasks, JSONArray devices, String tool, String scope,
                                              String deviceId, String query, boolean stale, int visibleCount) {
        if (stale || visibleCount > 0 || tasks == null) {
            return new HiddenSummary(0, 0, false, "", false);
        }
        boolean hasFilter = !"all".equals(NativeScreenPolicy.tool(tool))
                || !"all".equals(NativeScreenPolicy.scope(scope))
                || NativeTaskSearchPolicy.isSearching(query);
        if (!hasFilter) {
            return new HiddenSummary(0, 0, false, "", false);
        }
        int totalUnarchived = 0;
        Set<String> taskDevices = new HashSet<>();
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null || task.optBoolean("archived")) continue;
            String taskDevice = task.optString("device_id");
            if (deviceId != null && !deviceId.equals(taskDevice)) continue;
            totalUnarchived++;
            if (!taskDevice.isEmpty()) taskDevices.add(taskDevice);
        }
        if (totalUnarchived <= 0) {
            return new HiddenSummary(0, 0, false, "", false);
        }
        int knownComputers = devices == null ? 0 : devices.length();
        boolean singleComputer = deviceId != null || knownComputers == 1
                || (knownComputers == 0 && taskDevices.size() <= 1)
                || taskDevices.size() == 1 && knownComputers <= 1;
        String text = singleComputer
                ? "这台电脑有 " + totalUnarchived + " 条任务，当前筛选隐藏了 " + totalUnarchived + " 条"
                : "已连接电脑共有 " + totalUnarchived + " 条任务，当前筛选隐藏了 " + totalUnarchived + " 条";
        return new HiddenSummary(totalUnarchived, totalUnarchived, singleComputer, text, true);
    }

    private NativeTaskListModel() { }
}
