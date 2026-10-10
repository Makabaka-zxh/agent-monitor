package android.content;

import android.net.Uri;
import android.database.Cursor;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;

public abstract class ContentProvider {
    private Context context;
    public void attachForTest(Context context) { this.context = context; }
    public Context getContext() { return context; }
    public abstract boolean onCreate();
    public abstract ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException;
    public abstract String getType(Uri uri);
    public abstract Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder);
    public abstract Uri insert(Uri uri, ContentValues values);
    public abstract int delete(Uri uri, String selection, String[] args);
    public abstract int update(Uri uri, ContentValues values, String selection, String[] args);
}
