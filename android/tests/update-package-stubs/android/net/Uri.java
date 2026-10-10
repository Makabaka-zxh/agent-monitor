package android.net;

public final class Uri {
    private final String raw;
    private Uri(String raw) { this.raw = raw; }
    public static Uri parse(String raw) { return new Uri(raw); }
    public String getScheme() { return java.net.URI.create(raw).getScheme(); }
    public String getAuthority() { return java.net.URI.create(raw).getAuthority(); }
    @Override public String toString() { return raw; }
}
