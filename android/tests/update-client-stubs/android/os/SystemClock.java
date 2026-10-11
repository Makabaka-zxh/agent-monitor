package android.os;

/** Host clock double, never part of the APK. Tests may advance only elapsed time. */
public final class SystemClock {
    private static volatile long elapsedOffsetNanos;
    public static long elapsedRealtimeNanos() { return System.nanoTime() + elapsedOffsetNanos; }
    private SystemClock() { }
}
