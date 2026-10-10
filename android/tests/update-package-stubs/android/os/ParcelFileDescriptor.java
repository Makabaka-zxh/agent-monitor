package android.os;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Descriptor boundary uses a real host input stream; no Binder/Android descriptor claims. */
public final class ParcelFileDescriptor implements AutoCloseable {
    public static final int MODE_READ_ONLY = 268435456;
    public static int opens;
    private final FileInputStream input;

    private ParcelFileDescriptor(File file) throws FileNotFoundException { input = new FileInputStream(file); }
    public static ParcelFileDescriptor open(File file, int mode) throws FileNotFoundException {
        if (mode != MODE_READ_ONLY) throw new AssertionError("Non-read-only descriptor");
        opens++;
        return new ParcelFileDescriptor(file);
    }
    public byte[] readAll() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64];
        int count;
        while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }
    public void close() throws IOException { input.close(); }
}
