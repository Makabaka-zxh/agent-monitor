package android.widget;
import android.view.*;
import java.util.*;
public class LinearLayout extends View {
    public final List<View> children=new ArrayList<>();
    public static class LayoutParams {
        public LayoutParams(int w,int h) {
        }
        public LayoutParams(int w,int h,int z) {
        }
    }
    public void addView(View v) {
        children.add(v);
    }
    public void addView(View v,LayoutParams p) {
        children.add(v);
    }
    public void removeAllViews() {
        children.clear();
    }
}
