package android.os;
import java.util.*;
/** Explicit virtual main-loop time: delayed work never runs just because a test drains deliveries. */
public class Handler {
    private static final List<Pending> queue=new ArrayList<>();
    private static long now;
    private static final class Pending {
        final Handler owner; final Runnable action; final long due;
        Pending(Handler h,Runnable r,long d) {owner=h;action=r;due=d;}
    }
    public Handler(Looper l) {
    }
    public boolean post(Runnable r) {
        return postDelayed(r,0);
    }
    public boolean postDelayed(Runnable r,long delay) {
        synchronized(queue) {queue.add(new Pending(this,r,now+Math.max(0,delay)));}
        return true;
    }
    public void removeCallbacks(Runnable r) {
        synchronized(queue) {queue.removeIf(p->p.owner==this&&p.action==r);}
    }
    public static long uptime() {
        synchronized(queue) {return now;}
    }
    public static int pending() {
        synchronized(queue) {return queue.size();}
    }
    public static int delayedPending() {
        synchronized(queue) {int count=0;for(Pending p:queue)if(p.due>now)count++;return count;}
    }
    public static List<Runnable> delayedSnapshot() {
        synchronized(queue) {List<Runnable> found=new ArrayList<>();for(Pending p:queue)if(p.due>now)found.add(p.action);return found;}
    }
    public static void advanceBy(long elapsed) {
        if(elapsed<0)throw new IllegalArgumentException("time cannot go backwards");
        synchronized(queue) {now+=elapsed;}
        drain();
    }
    public static void drain() {
        for(int delivered=0;delivered<10000;delivered++) {
            Pending next=null;
            synchronized(queue) {
                for(Pending p:queue)if(p.due<=now&&(next==null||p.due<next.due))next=p;
                if(next==null)return;
                queue.remove(next);
            }
            next.action.run();
        }
        throw new AssertionError("main-loop deliveries did not quiesce");
    }
}
