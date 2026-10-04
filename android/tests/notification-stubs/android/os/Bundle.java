package android.os;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Bundle {
    private final Map<String, Object> values = new HashMap<>();
    public final List<String> readKeys = new ArrayList<>();
    public Bundle() {}
    public Bundle(Bundle source) { values.putAll(source.values); }
    public void putLong(String key, long value) { values.put(key, value); }
    public void putString(String key, String value) { values.put(key, value); }
    public void putBoolean(String key, boolean value) { values.put(key, value); }
    public void putBundle(String key, Bundle value) { values.put(key, value); }
    public void putParcelable(String key, Object value) { values.put(key, value); }
    public Object get(String key) { readKeys.add(key); return values.get(key); }
    public boolean containsKey(String key) { return values.containsKey(key); }
    public Bundle getBundle(String key) {
        readKeys.add(key); Object value = values.get(key); return value instanceof Bundle ? (Bundle) value : null;
    }
    public boolean getBoolean(String key, boolean fallback) {
        readKeys.add(key); Object value = values.get(key); return value instanceof Boolean ? (Boolean) value : fallback;
    }
    public long getLong(String key, long fallback) {
        readKeys.add(key); Object value = values.get(key); return value instanceof Long ? (Long) value : fallback;
    }
    public String getString(String key) {
        readKeys.add(key); Object value = values.get(key); return value instanceof String ? (String) value : null;
    }
}
