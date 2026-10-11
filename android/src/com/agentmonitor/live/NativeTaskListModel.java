package com.agentmonitor.live;

import java.util.HashMap;
import java.util.Map;
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
        JSONArray shown = new JSONArray(); int[] counts = {0, 0, 0}; int uncertain = 0;
        if (tasks != null) for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index); if (task == null) continue;
            JSONObject device = computers.get(task.optString("device_id"));
            // The public snapshot's project field is `project`, not a filesystem lookup.
            if (!search.matches(task.optString("title"), task.optString("project"), device == null ? "" : device.optString("name"))) continue;
            String actualTool = task.optString("tool"), status = task.optString("status");
            boolean archived = task.optBoolean("archived"), unknown = stale || device == null || !device.optBoolean("online");
            if (NativeWorkbenchPolicy.uncertainTask(tool, actualTool, status, archived, unknown)) uncertain++;
            if (deviceId != null) {
                if (deviceId.equals(task.optString("device_id")) && !archived) shown.put(task);
            } else {
                if (NativeScreenPolicy.includes("all", scope, actualTool, status, archived, unknown)) {
                    counts[0]++; if ("claude".equals(actualTool)) counts[1]++; if ("codex".equals(actualTool)) counts[2]++;
                }
                if (NativeScreenPolicy.includes(tool, scope, actualTool, status, archived, unknown)) shown.put(task);
            }
        }
        return new Result(shown, counts, uncertain);
    }

    private NativeTaskListModel() { }
}
