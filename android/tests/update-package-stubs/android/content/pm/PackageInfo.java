package android.content.pm;

public final class PackageInfo {
    public String packageName, versionName;
    public int versionCode;
    public long longVersionCode;
    public ApplicationInfo applicationInfo;
    public SigningInfo signingInfo;
    public Signature[] signatures;

    public long getLongVersionCode() { return longVersionCode; }
}
