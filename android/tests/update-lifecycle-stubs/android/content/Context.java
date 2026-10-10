package android.content;
import java.io.File;
import android.content.pm.PackageManager;
/** Application cache points only to a caller-provided isolated fixture directory. */
public class Context {
    public static File cache;
    public Context getApplicationContext() {
        return this;
    }
    public File getCacheDir() {
        return cache;
    }
    public String getPackageName() {
        return "com.agentmonitor.live";
    }
    public PackageManager getPackageManager() {
        return PackageManager.instance;
    }
}
