package android.provider;

import android.content.ContentResolver;

public final class Settings {
    private Settings() { }
    public static final class System {
        private System() { }
        public static int getInt(ContentResolver resolver, String name, int fallback) {
            return resolver.getSystemInt(name, fallback);
        }
    }
}
