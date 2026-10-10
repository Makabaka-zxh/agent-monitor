package android.content;

import android.content.pm.PackageManager;
import android.net.Uri;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Isolated cache, synthetic package metadata and in-memory install-grant preferences only. */
public final class Context {
    public static final int MODE_PRIVATE = 0;
    private final File cache;
    public final SharedPreferences prefs = new SharedPreferences();
    public final PackageManager manager = new PackageManager();
    public String packageName = "com.agentmonitor.live";
    public final List<String> revoked = new ArrayList<>();

    public Context(File cache) { this.cache = cache; }
    public File getCacheDir() { return cache; }
    public SharedPreferences getSharedPreferences(String name, int mode) {
        if (!"update_install_grant".equals(name) || mode != MODE_PRIVATE)
            throw new AssertionError("Unrelated preferences access");
        return prefs;
    }
    public PackageManager getPackageManager() { return manager; }
    public String getPackageName() { return packageName; }
    public void revokeUriPermission(Uri uri, int flags) {
        if (flags != Intent.FLAG_GRANT_READ_URI_PERMISSION) throw new AssertionError("Unexpected permission");
        revoked.add(uri.toString());
    }
}
