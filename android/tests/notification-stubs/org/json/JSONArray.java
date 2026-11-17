package org.json;
public final class JSONArray {
    private final java.util.List<Object> values = new java.util.ArrayList<>();
    public JSONArray put(Object value) { values.add(value); return this; }
    public int length() { return values.size(); }
    public Object opt(int index) { return index >= 0 && index < values.size() ? values.get(index) : null; }
    public String optString(int index) { Object value = opt(index); return value instanceof String ? (String) value : ""; }
    public JSONObject optJSONObject(int index) {
        Object value = index >= 0 && index < values.size() ? values.get(index) : null;
        return value instanceof JSONObject ? (JSONObject) value : null;
    }
}
