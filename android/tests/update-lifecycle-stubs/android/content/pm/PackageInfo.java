package android.content.pm;
public class PackageInfo {
    public String versionName,packageName;
    public int versionCode;
    public ApplicationInfo applicationInfo;
    public SigningInfo signingInfo;
    public Signature[] signatures;
    public long getLongVersionCode() {
        return versionCode;
    }
}
