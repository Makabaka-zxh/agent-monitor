package android.util;

/** Recording logger for offline transport checks; never included in the APK. */
public final class Log {
    private static final java.util.List<String> entries = new java.util.ArrayList<>();
    private Log() { }
    public static synchronized int i(String tag, String message) { entries.add(tag + " " + message); return 0; }
    public static synchronized void clear() { entries.clear(); }
    public static synchronized String recorded() { return String.join("\n", entries); }
}
