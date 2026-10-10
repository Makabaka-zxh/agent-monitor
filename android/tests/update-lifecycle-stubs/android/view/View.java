package android.view;
public class View {
    public static final int SYSTEM_UI_FLAG_LIGHT_STATUS_BAR=1,SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR=2,ACCESSIBILITY_LIVE_REGION_POLITE=1;
    public interface InsetsListener {
        WindowInsets apply(View v,WindowInsets i);
    }
    public void setPadding(int l,int t,int r,int b) {
    }
    public void setSystemUiVisibility(int f) {
    }
    public void setOnApplyWindowInsetsListener(InsetsListener l) {
    }
    public void requestApplyInsets() {
    }
    public void setBackground(Object b) {
    }
}
