package android.widget;
import android.view.View;
public class TextView extends View {
    public String text;
    public Runnable click;
    public TextView(String s) {
        text=s;
    }
    public void setText(String s) {
        text=s;
    }
    public void setTextColor(int c) {
    }
    public void setAccessibilityLiveRegion(int x) {
    }
    public void setTextIsSelectable(boolean x) {
    }
}
