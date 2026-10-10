package android.content.pm;

public final class Signature {
    private final byte[] value;
    public Signature(byte[] value) { this.value = value.clone(); }
    public byte[] toByteArray() { return value.clone(); }
}
