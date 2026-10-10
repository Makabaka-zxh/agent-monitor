package android.content.pm;

public final class SigningInfo {
    public Signature[] signers;
    public Signature[] getApkContentsSigners() { return signers; }
}
