package org.json;

/** Minimal fixed payload object for offline service callback tests. */
public final class JSONObject {
    /** Reproduce Android's sentinel, including its printable literal. */
    public static final Object NULL = new Object() {
        @Override public boolean equals(Object value) { return value == null || value == this; }
        @Override public int hashCode() { return 0; }
        @Override public String toString() { return "null"; }
    };
    private final java.util.Map<String, Object> values = new java.util.HashMap<>();
    public JSONObject put(String key, Object value) { values.put(key, value); return this; }
    public JSONArray optJSONArray(String key) { Object value = values.get(key); return value instanceof JSONArray ? (JSONArray) value : null; }
    public Object opt(String key) { return values.get(key); }
    public boolean isNull(String key) { return values.get(key) == null || values.get(key) == NULL; }
    public int optInt(String key, int fallback) { Object value = values.get(key); return value instanceof Number ? ((Number) value).intValue() : fallback; }
    public JSONObject optJSONObject(String key) { Object value = values.get(key); return value instanceof JSONObject ? (JSONObject) value : null; }
    public boolean optBoolean(String key) { return Boolean.TRUE.equals(values.get(key)); }
    public boolean optBoolean(String key, boolean fallback) { Object value = values.get(key); return value instanceof Boolean ? (Boolean) value : fallback; }
    public String optString(String key) { return optString(key, ""); }
    public String optString(String key, String fallback) { Object value = values.get(key); return value == NULL ? "null" : value instanceof String ? (String) value : fallback; }
}
