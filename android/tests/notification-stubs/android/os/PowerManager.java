package android.os;

public final class PowerManager {
    public boolean interactive = true, fail;
    public boolean isInteractive() {
        if (fail) throw new IllegalStateException("Simulated power service failure");
        return interactive;
    }
}
