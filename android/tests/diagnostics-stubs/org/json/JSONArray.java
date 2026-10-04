package org.json;
public final class JSONArray {
    private final java.util.List<Object> values = new java.util.ArrayList<>();
    public JSONArray put(Object value) { values.add(value); return this; }
    public String toString() {
        StringBuilder out = new StringBuilder("[");
        for (Object value : values) { if (out.length() > 1) out.append(','); out.append(JSONObject.encode(value)); }
        return out.append(']').toString();
    }
}
