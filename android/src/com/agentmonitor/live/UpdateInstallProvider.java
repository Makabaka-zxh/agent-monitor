package com.agentmonitor.live;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.UUID;

/** One expiring, exact, read-only APK grant. No directory traversal or arbitrary file sharing. */
public final class UpdateInstallProvider extends ContentProvider {
    private static final String AUTHORITY = "com.agentmonitor.live.updates", PREFS = "update_install_grant";
    private static final String MIME = "application/vnd.android.package-archive";
    private static final long GRANT_MS = 60 * 60 * 1000L;
    static synchronized Uri grant(Context context, UpdatePackage.Verified verified) throws IOException {
        File directory = new File(context.getCacheDir(), "updates").getCanonicalFile();
        File file = verified.file.getCanonicalFile();
        if (!directory.equals(file.getParentFile()) || !file.getName().matches("update-[A-Za-z0-9_-]+\\.apk")
                || !file.isFile() || file.length() != verified.manifest.size) throw new IOException("安装包已失效，请重新下载");
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String old = preferences.getString("uri", "");
        String oldName = preferences.getString("file", "");
        if (!old.isEmpty()) context.revokeUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Uri uri = Uri.parse("content://" + AUTHORITY + "/verified/" + UUID.randomUUID() + "/monitor.apk");
        if (!preferences.edit().clear().putString("uri", uri.toString()).putString("file", file.getName())
                .putString("sha256", verified.manifest.sha256).putLong("size", verified.manifest.size)
                .putLong("expires", System.currentTimeMillis() + GRANT_MS).commit()) throw new IOException("无法准备安装包");
        if (!oldName.equals(file.getName()) && oldName.matches("update-[A-Za-z0-9_-]+\\.apk")) {
            File previous = new File(directory, oldName).getCanonicalFile();
            if (directory.equals(previous.getParentFile())) previous.delete();
        }
        return uri;
    }
    /** Keep the current installer grant, and remove abandoned or expired private downloads. */
    static synchronized void prune(Context context) {
        try {
            File directory = new File(context.getCacheDir(), "updates").getCanonicalFile();
            SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long now = System.currentTimeMillis(), remaining = preferences.getLong("expires", 0) - now;
            String active = remaining > 0 && remaining <= GRANT_MS ? preferences.getString("file", "") : "";
            if (active.isEmpty()) {
                String uri = preferences.getString("uri", "");
                if (!uri.isEmpty()) context.revokeUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                preferences.edit().clear().commit();
            }
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) {
                if (!file.getName().equals(active) && file.getName().matches("update-[A-Za-z0-9_-]+\\.(apk|part)")
                        && directory.equals(file.getCanonicalFile().getParentFile()) && now - file.lastModified() > GRANT_MS) file.delete();
            }
        } catch (IOException | RuntimeException ignored) { }
    }
    @Override public boolean onCreate() { return true; }
    private File allowed(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        if (context == null || uri == null) throw new FileNotFoundException("Unavailable");
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long remaining = preferences.getLong("expires", 0) - System.currentTimeMillis();
        String name = preferences.getString("file", "");
        if (!uri.toString().equals(preferences.getString("uri", "")) || !"content".equals(uri.getScheme())
                || !AUTHORITY.equals(uri.getAuthority()) || remaining <= 0 || remaining > GRANT_MS
                || !name.matches("update-[A-Za-z0-9_-]+\\.apk")) throw new FileNotFoundException("Expired or invalid grant");
        try {
            File directory = new File(context.getCacheDir(), "updates").getCanonicalFile();
            File file = new File(directory, name).getCanonicalFile();
            if (!directory.equals(file.getParentFile()) || !file.isFile() || file.length() != preferences.getLong("size", -1))
                throw new FileNotFoundException("Package unavailable");
            return file;
        } catch (IOException invalid) { throw new FileNotFoundException("Package unavailable"); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        File file = allowed(uri);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            String expected = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("sha256", "");
            if (!UpdateClient.hex(digest.digest()).equalsIgnoreCase(expected)) throw new FileNotFoundException("Package changed");
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception invalid) { throw new FileNotFoundException("Package unavailable"); }
    }
    @Override public String getType(Uri uri) { try { allowed(uri); return MIME; } catch (FileNotFoundException invalid) { return null; } }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = allowed(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns, 1); MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) row.add(OpenableColumns.DISPLAY_NAME.equals(column) ? "monitor.apk" : OpenableColumns.SIZE.equals(column) ? file.length() : null);
            return cursor;
        } catch (FileNotFoundException invalid) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
