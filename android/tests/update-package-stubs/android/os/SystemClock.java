package android.os;
/** Host-only elapsed-time boundary; real Android owns the suspend-aware clock. */
public final class SystemClock {
    private SystemClock() { }
    public static long elapsedRealtimeNanos() {return System.nanoTime();}
}
