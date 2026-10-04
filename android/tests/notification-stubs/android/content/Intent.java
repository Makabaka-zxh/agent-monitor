package android.content;

import android.net.Uri;
import java.util.HashMap;
import java.util.Map;

public final class Intent {
    public static final String ACTION_SCREEN_OFF = "android.intent.action.SCREEN_OFF";
    public static final String ACTION_SCREEN_ON = "android.intent.action.SCREEN_ON";
    public static final String ACTION_USER_PRESENT = "android.intent.action.USER_PRESENT";
    public final Class<?> component;
    private String action;
    private Uri data;
    private final Map<String, Object> extras = new HashMap<>();
    public Intent(String action) { this.component = null; this.action = action; }
    public Intent(Context context, Class<?> component) { this.component = component; }
    public Intent(Intent source) {
        component = source.component; action = source.action; data = source.data;
        extras.putAll(source.extras);
    }
    public Intent setAction(String value) { action = value; return this; }
    public String getAction() { return action; }
    public Intent setData(Uri value) { data = value; return this; }
    public String getDataString() { return data == null ? null : data.toString(); }
    public Intent putExtra(String key, long value) { extras.put(key, value); return this; }
    public Intent putExtra(String key, int value) { extras.put(key, value); return this; }
    public Intent putExtra(String key, String value) { extras.put(key, value); return this; }
    public long getLongExtra(String key, long fallback) { Object value = extras.get(key); return value instanceof Long ? (Long) value : fallback; }
    public int getIntExtra(String key, int fallback) { Object value = extras.get(key); return value instanceof Integer ? (Integer) value : fallback; }
    public String getStringExtra(String key) { Object value = extras.get(key); return value instanceof String ? (String) value : null; }
}
