package org.json;
public final class JSONArray {
    private final java.util.List<Object> values = new java.util.ArrayList<>();
    public JSONArray put(Object value) { values.add(value); return this; }
    public int length() { return values.size(); }
    public JSONObject optJSONObject(int index) {
        Object value = index >= 0 && index < values.size() ? values.get(index) : null;
        return value instanceof JSONObject ? (JSONObject) value : null;
    }
}
