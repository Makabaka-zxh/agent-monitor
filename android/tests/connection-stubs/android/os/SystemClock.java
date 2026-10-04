package android.os;
public final class SystemClock { public static volatile long now = 100000; public static long elapsedRealtime() { return now; } }
