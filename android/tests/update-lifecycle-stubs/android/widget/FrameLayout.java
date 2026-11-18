package android.widget;
import android.content.Context;
import android.view.View;
public class FrameLayout extends LinearLayout {
    public FrameLayout(Context c) {
    }
    public static class LayoutParams extends LinearLayout.LayoutParams {
        public LayoutParams(int w,int h) {super(w,h);}
        public LayoutParams(int w,int h,int g) {super(w,h,g);}
    }
    protected void measureChildWithMargins(View child,int parentWidthMeasureSpec,int widthUsed,int parentHeightMeasureSpec,int heightUsed) {
    }
}
