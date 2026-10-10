package android.content.pm;
public class Signature {
    private final byte[] b;
    public Signature(byte[] x) {
        b=x.clone();
    }
    public byte[] toByteArray() {
        return b.clone();
    }
}
