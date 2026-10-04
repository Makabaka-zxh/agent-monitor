package org.json;

/** Minimal fixed payload object for offline service callback tests. */
public final class JSONObject {
    private final java.util.Map<String, Object> values = new java.util.HashMap<>();
    public JSONObject put(String key, Object value) { values.put(key, value); return this; }
    public JSONArray optJSONArray(String key) { Object value = values.get(key); return value instanceof JSONArray ? (JSONArray) value : null; }
    public Object opt(String key) { return values.get(key); }
    public JSONObject optJSONObject(String key) { Object value = values.get(key); return value instanceof JSONObject ? (JSONObject) value : null; }
    public boolean optBoolean(String key) { return Boolean.TRUE.equals(values.get(key)); }
    public boolean optBoolean(String key, boolean fallback) { Object value = values.get(key); return value instanceof Boolean ? (Boolean) value : fallback; }
    public String optString(String key) { return optString(key, ""); }
    public String optString(String key, String fallback) { Object value = values.get(key); return value instanceof String ? (String) value : fallback; }
}
