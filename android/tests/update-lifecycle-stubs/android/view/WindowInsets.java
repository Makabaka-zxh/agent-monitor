package android.view;
import android.graphics.Insets;
public class WindowInsets {
    public static class Type {
        public static int systemBars() {
            return 1;
        }
        public static int displayCutout() {
            return 2;
        }
    }
    public Insets getInsets(int mask) {
        return new Insets();
    }
    public int getSystemWindowInsetLeft() {
        return 0;
    }
    public int getSystemWindowInsetTop() {
        return 0;
    }
    public int getSystemWindowInsetRight() {
        return 0;
    }
    public int getSystemWindowInsetBottom() {
        return 0;
    }
}
