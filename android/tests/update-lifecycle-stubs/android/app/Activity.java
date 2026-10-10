package android.app;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import java.util.*;
/** Host lifecycle boundary: record intents without launching any external component. */
public class Activity extends Context {
    public final List<Intent> launched=new ArrayList<>();
    public boolean finishing,destroyed;
    private final Window window=new Window();
    public void onCreate(Bundle b) {
    }
    protected void onResume() {
    }
    protected void onPause() {
    }
    protected void onSaveInstanceState(Bundle b) {
    }
    protected void onDestroy() {
        destroyed=true;
    }
    public void finish() {
        finishing=true;
    }
    public boolean isFinishing() {
        return finishing;
    }
    public boolean isDestroyed() {
        return destroyed;
    }
    public Window getWindow() {
        return window;
    }
    public void setContentView(View v) {
    }
    public void startActivity(Intent i) {
        launched.add(i);
    }
}
