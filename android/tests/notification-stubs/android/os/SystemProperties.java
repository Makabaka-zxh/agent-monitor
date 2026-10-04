package android.os;

/** Reflection target for documented Xiaomi capability queries; never reads the host system. */
public final class SystemProperties {
    public static volatile String islandFeature = "";
    public static volatile RuntimeException readFailure;

    private SystemProperties() { }

    public static String get(String key, String fallback) {
        if (!"persist.sys.feature.island".equals(key)) throw new AssertionError("Unexpected system property " + key);
        RuntimeException failure = readFailure;
        if (failure != null) throw failure;
        String value = islandFeature;
        return value == null || value.isEmpty() ? fallback : value;
    }

    public static boolean getBoolean(String key, boolean fallback) {
        String value = get(key, "");
        if ("1".equals(value) || "true".equalsIgnoreCase(value) || "y".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value)) return true;
        if ("0".equals(value) || "false".equalsIgnoreCase(value) || "n".equalsIgnoreCase(value)
                || "no".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value)) return false;
        return fallback;
    }

    public static void reset() { islandFeature = ""; readFailure = null; }
}
