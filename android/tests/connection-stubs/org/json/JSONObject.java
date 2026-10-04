package org.json;
import java.util.LinkedHashMap;
import java.util.Map;
/** Deep copies model encrypted disk snapshots, not shared mutable references. */
public final class JSONObject {
    private final Map<String,Object> values = new LinkedHashMap<>();
    public JSONObject put(String key, Object value) { values.put(key, value); return this; }
    public String optString(String key) { Object value = values.get(key); return value instanceof String ? (String) value : ""; }
    public String getString(String key) { if (!values.containsKey(key)) throw new IllegalArgumentException(key); return optString(key); }
    public boolean optBoolean(String key) { return Boolean.TRUE.equals(values.get(key)); }
    public JSONObject optJSONObject(String key) { Object value = values.get(key); return value instanceof JSONObject ? (JSONObject) value : null; }
    public JSONArray optJSONArray(String key) { Object value = values.get(key); return value instanceof JSONArray ? (JSONArray) value : null; }
    public Object remove(String key) { return values.remove(key); }
    public boolean has(String key) { return values.containsKey(key); }
    public JSONObject copy() { JSONObject result = new JSONObject(); for (Map.Entry<String,Object> item : values.entrySet()) { Object value = item.getValue(); result.put(item.getKey(), value instanceof JSONObject ? ((JSONObject) value).copy() : value instanceof JSONArray ? ((JSONArray) value).copy() : value); } return result; }
    public String toString() { return values.toString(); }
}
