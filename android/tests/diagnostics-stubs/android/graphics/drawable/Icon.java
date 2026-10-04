package android.graphics.drawable;
public final class Icon {
    public static final int TYPE_RESOURCE = 2;
    private final int type, id;
    private final String pkg;
    public boolean resourceRead;
    public Icon(int type, int id, String pkg) { this.type = type; this.id = id; this.pkg = pkg; }
    public int getType() { return type; }
    public int getResId() { resourceRead = true; if (type != TYPE_RESOURCE) throw new AssertionError("Non-resource icon read"); return id; }
    public String getResPackage() { if (type != TYPE_RESOURCE) throw new AssertionError("Non-resource icon package read"); return pkg; }
}
