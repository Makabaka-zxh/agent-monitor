package android.content;

import android.app.NotificationManager;
import android.app.KeyguardManager;
import android.content.pm.ApplicationInfo;
import android.os.PowerManager;
import java.util.ArrayList;
import java.util.List;

public class Context {
    public static final int RECEIVER_NOT_EXPORTED = 4;
    public static final int MODE_PRIVATE = 0;
    public final ContentResolver contentResolver = new ContentResolver();
    public Context applicationContext = this;
    public Context getApplicationContext() { return applicationContext; }
    public ContentResolver getContentResolver() { return contentResolver; }
    private final java.util.Map<String, SharedPreferences> preferences = new java.util.HashMap<>();
    public SharedPreferences getSharedPreferences(String name, int mode) { return preferences.computeIfAbsent(name, key -> new SharedPreferences()); }
    public final NotificationManager notifications = new NotificationManager(this);
    public final KeyguardManager keyguard = new KeyguardManager();
    public final PowerManager power = new PowerManager();
    public boolean missingKeyguard, missingPower;
    public final List<Registration> registrations = new ArrayList<>();
    public int receiverUnregistrations;
    private final ApplicationInfo applicationInfo = new ApplicationInfo();
    public int stopServiceCalls;
    public Intent lastStoppedService;
    public String getPackageName() { return "com.agentmonitor.live"; }
    public java.io.File filesDirectory;
    public java.io.File getFilesDir() { return filesDirectory; }
    public ApplicationInfo getApplicationInfo() { return applicationInfo; }
    public <T> T getSystemService(Class<T> type) {
        if (type == NotificationManager.class) return type.cast(notifications);
        if (type == KeyguardManager.class) return missingKeyguard ? null : type.cast(keyguard);
        if (type == PowerManager.class) return missingPower ? null : type.cast(power);
        throw new AssertionError("Unexpected service " + type);
    }
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        registrations.add(new Registration(receiver, filter, 0)); return null;
    }
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
        registrations.add(new Registration(receiver, filter, flags)); return null;
    }
    public void unregisterReceiver(BroadcastReceiver receiver) {
        registrations.removeIf(item -> item.receiver == receiver); ++receiverUnregistrations;
    }
    public void dispatchBroadcast(Intent intent) {
        for (Registration item : new ArrayList<>(registrations))
            if (item.filter.hasAction(intent.getAction())) item.receiver.onReceive(this, intent);
    }
    public static final class Registration {
        public final BroadcastReceiver receiver;
        public final IntentFilter filter;
        public final int flags;
        Registration(BroadcastReceiver receiver, IntentFilter filter, int flags) {
            this.receiver = receiver; this.filter = filter; this.flags = flags;
        }
    }
    public boolean stopService(Intent intent) {
        ++stopServiceCalls; lastStoppedService = new Intent(intent); return true;
    }
}
