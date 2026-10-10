package android.content;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** In-memory boundary only; does not claim Android persistence/transaction semantics. */
public final class SharedPreferences {
    public final Map<String, Object> values = new HashMap<>();
    public boolean failCommit;
    public int commits;

    private static void key(String key) {
        if (!Arrays.asList("uri", "file", "sha256", "size", "expires").contains(key))
            throw new AssertionError("Unrelated preference key");
    }
    public String getString(String key, String fallback) {
        key(key);
        Object value = values.get(key);
        return value == null ? fallback : (String) value;
    }
    public long getLong(String key, long fallback) {
        key(key);
        Object value = values.get(key);
        return value == null ? fallback : (Long) value;
    }
    public Editor edit() { return new Editor(); }

    public final class Editor {
        private final Map<String, Object> additions = new HashMap<>();
        private boolean clear;
        public Editor clear() { clear = true; return this; }
        public Editor putString(String key, String value) {
            key(key); additions.put(key, value); return this;
        }
        public Editor putLong(String key, long value) {
            key(key); additions.put(key, value); return this;
        }
        public boolean commit() {
            commits++;
            if (failCommit) return false;
            if (clear) values.clear();
            values.putAll(additions);
            return true;
        }
    }
}
