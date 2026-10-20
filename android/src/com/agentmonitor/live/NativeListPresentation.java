package com.agentmonitor.live;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only fields drawn in a list row participate in its refresh key. */
public final class NativeListPresentation {
    private NativeListPresentation() { }

    public static String toolLabel(String tool) {
        return "claude".equals(tool) ? "Claude Code" : "codex".equals(tool) ? "Codex" : "任务";
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
