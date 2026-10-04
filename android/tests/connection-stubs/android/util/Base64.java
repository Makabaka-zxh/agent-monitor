package android.util;
public final class Base64 {
    public static final int NO_PADDING = 1, NO_WRAP = 2, URL_SAFE = 8;
    public static String encodeToString(byte[] value, int flags) { return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
}
