package android.graphics.drawable;

import android.content.Context;

/** Records resource icon identity without decoding or rendering image data. */
public final class Icon {
    public static final int TYPE_RESOURCE = 2;
    private final String resourcePackage;
    private final int resourceId;
    private Icon(String resourcePackage, int resourceId) {
        this.resourcePackage = resourcePackage;
        this.resourceId = resourceId;
    }
    public static Icon createWithResource(Context context, int resourceId) {
        if (context == null || resourceId == 0) throw new IllegalArgumentException("A local resource is required");
        return new Icon(context.getPackageName(), resourceId);
    }
    public int getType() { return TYPE_RESOURCE; }
    public String getResPackage() { return resourcePackage; }
    public int getResId() { return resourceId; }
}
