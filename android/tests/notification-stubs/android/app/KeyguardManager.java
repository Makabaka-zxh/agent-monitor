package android.app;

public final class KeyguardManager {
    public boolean locked, fail;
    public boolean isKeyguardLocked() {
        if (fail) throw new IllegalStateException("Simulated keyguard failure");
        return locked;
    }
}
