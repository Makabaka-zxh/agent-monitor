package android.os;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Explicitly pump only chosen queues; tests remove the network ticker before pumping. */
public final class Handler {
    public static final List<Handler> instances = new CopyOnWriteArrayList<>();
    public final List<Runnable> queued = new CopyOnWriteArrayList<>();
    public final List<Runnable> delayed = new CopyOnWriteArrayList<>();
    public Handler(Looper looper) { instances.add(this); }
    public boolean post(Runnable action) { queued.add(action); return true; }
    public boolean postDelayed(Runnable action, long delay) { delayed.add(action); return true; }
    public void removeCallbacks(Runnable action) { while (queued.remove(action)) {} while (delayed.remove(action)) {} }
    public void removeCallbacksAndMessages(Object token) { queued.clear(); delayed.clear(); }
    public void runPosted() { List<Runnable> copy = new ArrayList<>(queued); queued.clear(); for (Runnable action : copy) action.run(); }
    public void runDelayed() { List<Runnable> copy = new ArrayList<>(delayed); delayed.clear(); for (Runnable action : copy) action.run(); }
}
