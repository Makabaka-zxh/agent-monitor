package android.content;
public final class SharedPreferences {
    private final java.util.Map<String, Boolean> values = new java.util.HashMap<>();
    public boolean getBoolean(String key, boolean fallback) { return values.containsKey(key) ? values.get(key) : fallback; }
    public Editor edit() { return new Editor(); }
    public final class Editor {
        public Editor putBoolean(String key, boolean value) { values.put(key, value); return this; }
        public void apply() { }
    }
}
