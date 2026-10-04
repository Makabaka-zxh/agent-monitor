package android.content;
/** Isolated download-test cache; never included in the application. */
public final class Context {
    private final java.io.File cache;
    public Context(java.io.File cache) { this.cache = cache; }
    public java.io.File getCacheDir() { return cache; }
}
