package android.view;
public class View {
    public static final int SYSTEM_UI_FLAG_LIGHT_STATUS_BAR=1,SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR=2,ACCESSIBILITY_LIVE_REGION_POLITE=1;
    public static final int VISIBLE=0,INVISIBLE=4,GONE=8;
    private boolean enabled=true;
    private float alpha=1f;
    private int visibility=VISIBLE;
    public void setEnabled(boolean value) {enabled=value;}
    public boolean isEnabled() {return enabled;}
    public void setAlpha(float value) {alpha=value;}
    public float getAlpha() {return alpha;}
    public void setVisibility(int value) {visibility=value;}
    public int getVisibility() {return visibility;}
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
