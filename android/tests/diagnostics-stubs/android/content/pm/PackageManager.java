package android.content.pm;
public final class PackageManager {
    public static final int GET_META_DATA = 128;
    public ApplicationInfo app;
    public boolean fail, linkage;
    public int calls;
    public PackageManager(ApplicationInfo app) { this.app = app; }
    public ApplicationInfo getApplicationInfo(String packageName, int flags) {
        calls++;
        if (!"com.agentmonitor.live".equals(packageName) || flags != GET_META_DATA)
            throw new AssertionError("Only own package metadata may be queried");
        if (fail) throw new SecurityException("private-package-manager-error");
        if (linkage) throw new NoSuchMethodError("private-platform-error");
        return app;
    }
}
