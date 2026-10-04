package org.json;
import java.util.ArrayList;
public final class JSONArray {
    private final ArrayList<Object> values = new ArrayList<>();
    public JSONArray put(Object value) { values.add(value); return this; }
    public int length() { return values.size(); }
    public String optString(int index) { return index >= 0 && index < values.size() ? String.valueOf(values.get(index)) : ""; }
    public JSONArray copy() { JSONArray result = new JSONArray(); for (Object value : values) result.put(value); return result; }
    public String toString() { return values.toString(); }
}
