package android.content;
public final class Context {
    public static final int MODE_PRIVATE = 0;
    public final SharedPreferences preferences = new SharedPreferences();
    public String requestedFile;
    public SharedPreferences getSharedPreferences(String file, int mode) {
        requestedFile = file;
        return preferences;
    }
}
