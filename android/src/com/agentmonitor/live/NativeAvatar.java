package com.agentmonitor.live;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Local document decoding only. Call the image-reading methods off the UI thread. */
public final class NativeAvatar {
    private static final int MAX_INPUT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_AVATAR_BYTES = 256 * 1024;
    private static final int MAX_DATA_URL_LENGTH = 4 * ((MAX_AVATAR_BYTES + 2) / 3) + 32;
    private NativeAvatar() { }

    public static final class Result {
        public final String dataUrl;
        public final Bitmap bitmap;
        private Result(String dataUrl, Bitmap bitmap) { this.dataUrl = dataUrl; this.bitmap = bitmap; }
    }

    public static Intent pickerIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/jpeg", "image/png", "image/webp"})
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    /** Produces a fresh 256 px JPEG with orientation corrected and no source metadata. */
    public static Result fromUri(Context context, Uri uri) throws IOException {
        Bitmap source = null, avatar = null;
        boolean transferred = false;
        try {
            source = decodeDocument(context, uri, 1024);
            avatar = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(avatar);
            canvas.drawColor(Color.rgb(238, 242, 243));
            int edge = Math.min(source.getWidth(), source.getHeight());
            int left = (source.getWidth() - edge) / 2, top = (source.getHeight() - edge) / 2;
            canvas.drawBitmap(source, new Rect(left, top, left + edge, top + edge),
                    new Rect(0, 0, 256, 256), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!avatar.compress(Bitmap.CompressFormat.JPEG, 88, encoded)) throw new IOException("无法处理这张图片");
            byte[] bytes = encoded.toByteArray();
            if (bytes.length > MAX_AVATAR_BYTES) throw new IOException("头像图片过大，请换一张图片");
            Result result = new Result("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP), avatar);
            transferred = true;
            return result;
        } catch (OutOfMemoryError failure) { throw new IOException("图片尺寸过大，请换一张图片", failure);
        } catch (RuntimeException failure) { throw new IOException("无法处理这张图片，请重新选择", failure);
        } finally {
            if (source != null) source.recycle();
            if (avatar != null && !transferred) avatar.recycle();
        }
    }

    /** Reads only a user-selected content URI, with encoded and decoded allocation limits. */
    static Bitmap decodeDocument(Context context, Uri uri, int longestEdge) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme())) throw new IOException("请选择相册中的图片");
        byte[] bytes;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取这张图片");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count, total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_INPUT_BYTES) throw new IOException("请选择小于 10 MB 的图片");
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        } catch (SecurityException error) { throw new IOException("图片读取权限已失效，请重新选择", error);
        } catch (OutOfMemoryError error) { throw new IOException("图片尺寸过大，请换一张图片", error); }
        Bitmap decoded = decodeBytes(bytes, Math.max(256, Math.min(longestEdge, 2048)), false);
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            orientation = new ExifInterface(new ByteArrayInputStream(bytes))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException ignored) { }
        Matrix transform = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: transform.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: transform.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: transform.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: transform.setRotate(90); transform.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: transform.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: transform.setRotate(-90); transform.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: transform.setRotate(-90); break;
            default: return decoded;
        }
        try {
            Bitmap rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), transform, true);
            if (rotated != decoded) decoded.recycle();
            return rotated;
        } catch (RuntimeException | OutOfMemoryError failure) {
            decoded.recycle();
            throw new IOException("图片尺寸过大，请换一张图片", failure);
        }
    }

    /** No HTTP or file URL handling: stored avatars are bounded data URLs only. */
    public static Bitmap decode(String value) {
        if (value == null || value.length() > MAX_DATA_URL_LENGTH) return null;
        int comma = value.indexOf(',');
        if (comma < 0 || !value.substring(0, comma).matches("data:image/(jpeg|png|webp);base64")) return null;
        String encoded = value.substring(comma + 1);
        if (!encoded.matches("[A-Za-z0-9+/]*={0,2}")) return null;
        try {
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
            if (bytes.length == 0 || bytes.length > MAX_AVATAR_BYTES) return null;
            BitmapFactory.Options header = new BitmapFactory.Options();
            header.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, header);
            if (!("data:" + header.outMimeType + ";base64").equals(value.substring(0, comma))) return null;
            return decodeBytes(bytes, 256, true);
        } catch (IOException | IllegalArgumentException | OutOfMemoryError ignored) { return null; }
    }

    private static Bitmap decodeBytes(byte[] bytes, int longestEdge, boolean storedAvatar) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        String mime = options.outMimeType;
        if (!("image/jpeg".equals(mime) || "image/png".equals(mime) || "image/webp".equals(mime)))
            throw new IOException("请选择 JPG、PNG 或 WebP 图片");
        int width = options.outWidth, height = options.outHeight;
        if (width < 1 || height < 1 || width > 32768 || height > 32768
                || (long) width * height > 100_000_000L || (storedAvatar && (width > 1024 || height > 1024)))
            throw new IOException("图片尺寸过大，请换一张图片");
        options.inSampleSize = 1;
        while (Math.max(width, height) / options.inSampleSize > longestEdge) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) throw new IOException("图片已损坏，无法读取");
            return bitmap;
        } catch (OutOfMemoryError failure) { throw new IOException("图片尺寸过大，请换一张图片", failure); }
    }
}
