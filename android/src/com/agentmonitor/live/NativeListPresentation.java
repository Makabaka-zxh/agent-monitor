package com.agentmonitor.live;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only fields drawn in a list row participate in its refresh key. */
public final class NativeListPresentation {
    private NativeListPresentation() { }

    public static String toolLabel(String tool) {
        return NativeScreenPolicy.toolIndex(tool) == 0 ? "任务" : NativeScreenPolicy.toolLabel(tool);
    }

    /** Only the server's reader-scoped hint can identify an answerable interaction. */
    public static String pendingKind(JSONObject task) {
        if (task == null || task.optBoolean("archived") || task.optBoolean("stale")
                || NativeScreenPolicy.toolIndex(task.optString("tool")) == 0) return "";
        String kind = task.optString("pending_interaction_kind");
        return "question".equals(kind) || "approval".equals(kind) ? kind : "";
    }

    public static String waitingLabel(String kind) {
        if ("question".equals(kind)) return "等待回答";
        if ("approval".equals(kind)) return "等待批准";
        return "待处理";
    }

    /** Only non-stale, unarchived tasks on online computers report a live continuation state. */
    public static String continuationState(JSONObject task, JSONObject device, boolean stale) {
        if (task == null || stale || task.optBoolean("archived")
                || device == null || !device.optBoolean("online")) return "";
        String value = task.optString("continuation_state");
        if ("continuable".equals(value) || "computer_in_use".equals(value) || "view_only".equals(value)) {
            return value;
        }
        return "";
    }

    public static String continuationLabel(String state) {
        if ("continuable".equals(state)) return "可在手机继续";
        if ("computer_in_use".equals(state)) return "电脑正在使用";
        if ("view_only".equals(state)) return "仅可查看";
        return "";
    }

    public static String availableSources(JSONArray sources) {
        boolean[] available = new boolean[NativeScreenPolicy.toolFilterCount()];
        if (sources != null) for (int i = 0; i < sources.length(); i++) {
            JSONObject entry = sources.optJSONObject(i);
            if (entry == null || !entry.optBoolean("available")) continue;
            int index = NativeScreenPolicy.toolIndex(entry.optString("tool"));
            if (index > 0) available[index] = true;
        }
        StringBuilder label = new StringBuilder(); String[] filters = NativeScreenPolicy.toolFilters();
        for (int i = 1; i < available.length; i++) if (available[i]) {
            if (label.length() > 0) label.append(" · ");
            label.append(NativeScreenPolicy.toolLabel(filters[i]));
        }
        return label.toString();
    }

    public static String tasksKey(JSONArray tasks, JSONArray computers, boolean stale, long minute) {
        Map<String, JSONObject> devices = new HashMap<>();
        if (computers != null) for (int i = 0; i < computers.length(); i++) {
            JSONObject device = computers.optJSONObject(i);
            if (device != null && !devices.containsKey(device.optString("id"))) devices.put(device.optString("id"), device);
        }
        StringBuilder result = new StringBuilder(); field(result, String.valueOf(stale)); field(result, String.valueOf(minute));
        if (tasks != null) for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
            for (String name : new String[]{"id", "title", "device_id", "tool", "status", "updated_at"}) field(result, value(task, name));
            field(result, String.valueOf(task.optBoolean("archived")));
            JSONObject device = devices.get(task.optString("device_id"));
            field(result, !stale && device != null && device.optBoolean("online") ? pendingKind(task) : "");
            field(result, continuationState(task, device, stale));
            field(result, value(device, "name")); field(result, String.valueOf(device != null));
            field(result, String.valueOf(device != null && device.optBoolean("online")));
        }
        return result.toString();
    }

    public static String devicesKey(JSONArray computers, boolean stale, long minute) {
        StringBuilder result = new StringBuilder(); field(result, String.valueOf(stale)); field(result, String.valueOf(minute));
        if (computers != null) for (int i = 0; i < computers.length(); i++) {
            JSONObject device = computers.optJSONObject(i); if (device == null) continue;
            for (String name : new String[]{"id", "name", "platform"}) field(result, value(device, name));
            field(result, String.valueOf(device.optBoolean("online")));
            // Heartbeat timestamps are invisible while online, so they should not rebind cards.
            field(result, stale || !device.optBoolean("online") ? value(device, "last_seen") : "");
        }
        return result.toString();
    }

    private static String value(JSONObject object, String key) { return object == null || object.isNull(key) ? "" : object.optString(key, ""); }
    private static void field(StringBuilder into, String value) { into.append(value.length()).append(':').append(value); }
}
