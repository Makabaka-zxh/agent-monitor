package android.content.pm;
public class SigningInfo {
    public Signature[] values;
    public Signature[] getApkContentsSigners() {
        return values;
    }
}
