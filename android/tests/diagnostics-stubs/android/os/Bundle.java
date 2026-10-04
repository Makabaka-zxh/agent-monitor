package android.os;
public final class Bundle {
    private final java.util.Map<String, Object> values = new java.util.HashMap<>();
    public final java.util.List<String> readKeys = new java.util.ArrayList<>();
    public boolean broken;
    public Bundle put(String key, Object value) { values.put(key, value); return this; }
    public Object get(String key) {
        readKeys.add(key);
        if (broken) throw new IllegalArgumentException("private-bundle-error");
        return values.get(key);
    }
    public boolean containsKey(String key) { readKeys.add(key); return values.containsKey(key); }
    public String getString(String key, String fallback) { Object value = get(key); return value instanceof String ? (String) value : fallback; }
}
