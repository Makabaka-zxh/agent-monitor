package android.content;

import android.content.pm.PackageManager;
import android.net.Uri;
import java.io.File;
import java.lang.reflect.Proxy;

/** Isolated cleanup-only context. Any account, grant write or platform action fails the test. */
public final class Context {
    public static final int MODE_PRIVATE = 0;
    private final File cache;
    private volatile String grantedFile = "";
    private volatile long expires;
    public int preferenceReads;

    public Context(File cache) { this.cache = cache; }
    public File getCacheDir() { return cache; }
    public void setSyntheticGrant(String name, long expiry) { grantedFile = name; expires = expiry; }
    public SharedPreferences getSharedPreferences(String name, int mode) {
        if (!"update_install_grant".equals(name) || mode != MODE_PRIVATE)
            throw new AssertionError("Cleanup accessed preferences outside its update grant");
        preferenceReads++;
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if ("getString".equals(method.getName()) && "file".equals(args[0])) return grantedFile;
                    if ("getLong".equals(method.getName()) && "expires".equals(args[0])) return expires;
                    throw new AssertionError("Cleanup attempted a preference write or unrelated read");
                });
    }
    public PackageManager getPackageManager() { throw new AssertionError("Cleanup cannot inspect installed packages"); }
    public String getPackageName() { throw new AssertionError("Cleanup cannot inspect package identity"); }
    public void revokeUriPermission(Uri uri, int flags) { throw new AssertionError("Cleanup cannot revoke an active grant"); }
}
