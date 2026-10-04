package org.json;
/** Fixed decoder fixtures only: tests exercise production filtering, not Android's JSON parser. */
public final class JSONObject {
    private static final java.util.Map<String, JSONObject> fixtures = new java.util.HashMap<>();
    private static final java.util.Set<String> overflowInputs = new java.util.HashSet<>();
    public static JSONObject lastSerialized;
    public final java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
    public final java.util.List<String> readKeys = new java.util.ArrayList<>();
    public JSONObject() { }
    public JSONObject(String input) {
        if (overflowInputs.contains(input)) throw new StackOverflowError("private-parser-overflow-" + input);
        JSONObject fixture = fixtures.get(input);
        if (fixture == null) throw new IllegalArgumentException("private-parser-error");
        values.putAll(fixture.values);
    }
    public static void fixture(String input, JSONObject value) { fixtures.put(input, value); }
    public static void overflowFixture(String input) { overflowInputs.add(input); }
    public JSONObject put(String key, Object value) { values.put(key, value); return this; }
    public Object opt(String key) { readKeys.add(key); return values.get(key); }
    public JSONObject optJSONObject(String key) { Object value = opt(key); return value instanceof JSONObject ? (JSONObject) value : null; }
    static String encode(Object value) {
        if (value instanceof JSONObject) return ((JSONObject) value).json();
        if (value instanceof JSONArray) return value.toString();
        if (value instanceof String) return "\"" + ((String) value).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value == null) return "null";
        throw new AssertionError("Unexpected diagnostic value type");
    }
    private String json() {
        StringBuilder out = new StringBuilder("{");
        for (java.util.Map.Entry<String, Object> item : values.entrySet()) {
            if (out.length() > 1) out.append(',');
            out.append(encode(item.getKey())).append(':').append(encode(item.getValue()));
        }
        return out.append('}').toString();
    }
    @Override public String toString() { lastSerialized = this; return json(); }
}
