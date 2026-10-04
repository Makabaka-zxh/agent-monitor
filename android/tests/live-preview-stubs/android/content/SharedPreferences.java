package android.content;

import java.util.HashMap;
import java.util.Map;

/** Recording editor keeps pending writes separate until apply, like a single preference transaction. */
public final class SharedPreferences {
    public final Map<String, Boolean> values = new HashMap<>();
    public int edits, applies, lastAppliedKeys;
    public boolean getBoolean(String key, boolean fallback) {
        return values.containsKey(key) ? values.get(key) : fallback;
    }
    public Editor edit() { edits++; return new Editor(); }
    public final class Editor {
        private final Map<String, Boolean> pending = new HashMap<>();
        public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
        public void apply() { values.putAll(pending); applies++; lastAppliedKeys = pending.size(); }
    }
}
