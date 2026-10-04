package android.content;
public final class ComponentName {
    public final Class<?> component;
    public ComponentName(Context context, Class<?> component) { this.component = component; }
}