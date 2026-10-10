package android.os;
import java.util.concurrent.*;
/** Worker deliveries remain queued until the test explicitly advances the main thread. */
public class Handler {
    private static final ConcurrentLinkedQueue<Runnable> queue=new ConcurrentLinkedQueue<>();
    public Handler(Looper l) {
    }
    public boolean post(Runnable r) {
        queue.add(r);
        return true;
    }
    public static int pending() {
        return queue.size();
    }
    public static void drain() {
        Runnable r;
        while((r=queue.poll())!=null)r.run();
    }
}
