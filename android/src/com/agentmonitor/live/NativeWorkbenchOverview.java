package com.agentmonitor.live;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Anonymous, read-only state counts from the loaded snapshot, independent of list filters. */
public final class NativeWorkbenchOverview {
    private static final String SCOPE = "全部未归档 · ";

    private NativeWorkbenchOverview() { }

    /** Show one priority state; the complete anonymous description remains in help and accessibility. */
    public static String compactDescription(String description) {
        final String scope = "未归档 · ", unknown = scope + "状态待确认";
        if (description == null || !description.startsWith(SCOPE)) return unknown;
        String state = description.substring(SCOPE.length());
        switch (state) {
            case "正在读取任务状态": return scope + "读取中";
            case "状态待更新": return scope + "待更新";
            case "还没有连接电脑": return scope + "未连接电脑";
            case "电脑已离线":
            case "电脑已离线 · 部分状态待确认": return scope + "电脑离线";
            case "暂无进行中的任务": return scope + "暂无进行中";
            default: break;
        }
        String[] labels = {"执行中 ", "待处理 ", "出错 "};
        int[] counts = new int[3]; int previous = -1;
        boolean uncertain = false;
        String[] parts = state.split(" · ", -1);
        if (parts.length > 4) return unknown;
        for (String part : parts) {
            int kind = -1;
            if (part.equals("部分状态待确认")) { kind = 3; uncertain = true; }
            else for (int i = 0; i < labels.length; i++) {
                if (!part.startsWith(labels[i])) continue;
                String value = part.substring(labels[i].length());
                if (!value.matches("[1-9][0-9]{0,9}")) return unknown;
                try { counts[i] = Integer.parseInt(value); }
                catch (NumberFormatException invalid) { return unknown; }
                kind = i; break;
            }
            // Only the complete known grammar may produce a confident summary.
            if (kind <= previous) return unknown;
            previous = kind;
        }
        if (counts[2] > 0) return scope + "出错 " + counts[2];
        if (counts[1] > 0) return scope + "待处理 " + counts[1];
        if (uncertain) return unknown;
        if (counts[0] > 0) return scope + "运行 " + counts[0];
        return unknown;
    }

    public static String describe(JSONObject snapshot, boolean stale) {
        JSONArray tasks = snapshot == null ? null : snapshot.optJSONArray("tasks");
        JSONArray devices = snapshot == null ? null : snapshot.optJSONArray("devices");
        if (tasks == null || devices == null) return SCOPE + "正在读取任务状态";
        if (stale) return SCOPE + "状态待更新";

        Map<String, Boolean> onlineById = new HashMap<>();
        boolean deviceCatalogUncertain = false;
        boolean allDevicesOffline = devices.length() > 0;
        for (int index = 0; index < devices.length(); index++) {
            JSONObject device = devices.optJSONObject(index);
            String id = device == null ? null : nonemptyString(device.opt("id"));
            Object online = device == null ? null : device.opt("online");
            if (id == null || !(online instanceof Boolean)) {
                deviceCatalogUncertain = true;
                allDevicesOffline = false;
                if (id != null) onlineById.put(id, null);
                continue;
            }
            if (onlineById.containsKey(id)) {
                // A repeated source identity cannot prove which availability applies.
                onlineById.put(id, null);
                deviceCatalogUncertain = true;
                allDevicesOffline = false;
            } else {
                onlineById.put(id, (Boolean) online);
                if ((Boolean) online) allDevicesOffline = false;
            }
        }

        int running = 0, waiting = 0, error = 0, unarchived = 0;
        boolean uncertain = false;
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null) { uncertain = true; continue; }
            Object archived = task.opt("archived");
            // Archived tasks are completely outside this overview, including malformed ones.
            if (Boolean.TRUE.equals(archived)) continue;
            if (!Boolean.FALSE.equals(archived)) { uncertain = true; continue; }
            unarchived++;
            String tool = nonemptyString(task.opt("tool"));
            String status = nonemptyString(task.opt("status"));
            String deviceId = nonemptyString(task.opt("device_id"));
            boolean sourceUnavailable = deviceId == null || !Boolean.TRUE.equals(onlineById.get(deviceId));
            if (!sourceUnavailable && !NativeListPresentation.pendingKind(task).isEmpty()) status = "waiting";
            if (NativeWorkbenchPolicy.uncertainTask("all", tool, status, false, sourceUnavailable)
                    || tool == null) {
                uncertain = true;
                continue;
            }
            switch (status) {
                case "running": running++; break;
                case "waiting": waiting++; break;
                case "error": error++; break;
                default: break; // Known idle and completed states are not active work.
            }
        }

        // An empty task set does not establish a healthy connection to malformed sources.
        if (unarchived == 0 && deviceCatalogUncertain) uncertain = true;
        StringBuilder description = new StringBuilder();
        if (running > 0) append(description, "执行中 " + running);
        if (waiting > 0) append(description, "待处理 " + waiting);
        if (error > 0) append(description, "出错 " + error);
        if (description.length() == 0) {
            if (allDevicesOffline) append(description, "电脑已离线");
            else if (!uncertain) append(description, unarchived == 0 && devices.length() == 0
                    ? "还没有连接电脑" : "暂无进行中的任务");
        }
        if (uncertain) append(description, "部分状态待确认");
        return SCOPE + description;
    }

    /**
     * Returns all unarchived tasks on online computers currently waiting for user action,
     * independent of the workbench's active tool/scope/search filters.
     * Answerable interactions (questions and approvals) are ordered ahead of generic waiting tasks.
     */
    public static List<JSONObject> pendingTasks(JSONObject snapshot, boolean stale) {
        JSONArray tasks = snapshot == null ? null : snapshot.optJSONArray("tasks");
        JSONArray devices = snapshot == null ? null : snapshot.optJSONArray("devices");
        if (tasks == null || devices == null || stale) return Collections.emptyList();

        Map<String, Boolean> onlineById = new HashMap<>();
        for (int index = 0; index < devices.length(); index++) {
            JSONObject device = devices.optJSONObject(index);
            String id = device == null ? null : nonemptyString(device.opt("id"));
            Object online = device == null ? null : device.opt("online");
            if (id == null || !(online instanceof Boolean)) {
                if (id != null) onlineById.put(id, null);
                continue;
            }
            if (onlineById.containsKey(id)) onlineById.put(id, null);
            else onlineById.put(id, (Boolean) online);
        }

        List<JSONObject> answerable = new ArrayList<>();
        List<JSONObject> generalWaiting = new ArrayList<>();
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null || !Boolean.FALSE.equals(task.opt("archived"))) continue;
            String id = nonemptyString(task.opt("id"));
            String tool = nonemptyString(task.opt("tool"));
            String status = nonemptyString(task.opt("status"));
            String deviceId = nonemptyString(task.opt("device_id"));
            if (id == null || tool == null || deviceId == null || !Boolean.TRUE.equals(onlineById.get(deviceId))) {
                continue;
            }
            String kind = NativeListPresentation.pendingKind(task);
            String effective = !kind.isEmpty() ? "waiting" : status;
            if (NativeWorkbenchPolicy.uncertainTask("all", tool, effective, false, false)) continue;
            if (!"waiting".equals(effective)) continue;
            if (!kind.isEmpty()) answerable.add(task);
            else generalWaiting.add(task);
        }
        answerable.addAll(generalWaiting);
        return Collections.unmodifiableList(answerable);
    }

    public static int waitingCount(JSONObject snapshot, boolean stale) {
        return pendingTasks(snapshot, stale).size();
    }

    private static String nonemptyString(Object value) {
        return value instanceof String && !((String) value).trim().isEmpty() ? (String) value : null;
    }

    private static void append(StringBuilder target, String value) {
        if (target.length() > 0) target.append(" · ");
        target.append(value);
    }
}
