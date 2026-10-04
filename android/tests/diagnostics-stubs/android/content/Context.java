package android.content;
public final class Context {
    public final android.content.pm.ApplicationInfo app = new android.content.pm.ApplicationInfo();
    public final android.app.NotificationManager manager = new android.app.NotificationManager();
    public java.io.File directory;
    public int appInfoFailure;
    public final android.content.pm.PackageManager packages = new android.content.pm.PackageManager(app);
    public android.content.pm.ApplicationInfo getApplicationInfo() {
        if (appInfoFailure == 1) return null;
        if (appInfoFailure == 2) throw new SecurityException("private-app-info-error");
        if (appInfoFailure == 3) throw new NoSuchMethodError("private-app-info-linkage-error");
        return app;
    }
    public android.content.pm.PackageManager getPackageManager() { return packages; }
    public <T> T getSystemService(Class<T> type) { return type.cast(manager); }
    public String getPackageName() { return "com.agentmonitor.live"; }
    public java.io.File getFilesDir() { return directory; }
}
