package android.os;
import java.util.concurrent.ConcurrentLinkedQueue;
public final class Handler {
    public static final ConcurrentLinkedQueue<Runnable> posted = new ConcurrentLinkedQueue<>();
    public Handler(Looper looper) {}
    public boolean post(Runnable action) { posted.add(action); return true; }
    public static void runPosted() { Runnable next; while ((next = posted.poll()) != null) next.run(); }
}
