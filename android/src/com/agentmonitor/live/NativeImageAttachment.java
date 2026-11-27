package com.agentmonitor.live;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Downscales and re-encodes user-selected screenshots/photos off the UI thread
 * into metadata-free JPEG payloads (<= 850 KB each, max 3 attachments).
 */
public final class NativeImageAttachment {
    public static final int MAX_ATTACHMENTS = 3;
    private static final int MAX_LONG_EDGE = 1440;
    private static final int MAX_ENCODED_BYTES = 850 * 1024;
    private static final int THUMB_PX = 96;

    private NativeImageAttachment() { }

    public static final class Item {
        public final String mime;
        public final String base64Data;
        public final String label;
        public final int width;
        public final int height;
        public final int byteCount;
        public final Bitmap thumbnail;

        Item(String mime, String base64Data, String label, int width, int height, int byteCount, Bitmap thumbnail) {
            this.mime = mime;
            this.base64Data = base64Data;
            this.label = label;
            this.width = width;
            this.height = height;
            this.byteCount = byteCount;
            this.thumbnail = thumbnail;
        }

        public void recycle() {
            if (thumbnail != null && !thumbnail.isRecycled()) {
                thumbnail.recycle();
            }
        }
    }

    public static Intent pickerIntent(boolean allowMultiple) {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "image/webp"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    public static List<Uri> extractUris(Intent data, int limit) {
        List<Uri> out = new ArrayList<>();
        if (data == null || limit <= 0) return out;
        ClipData clip = data.getClipData();
        if (clip != null && clip.getItemCount() > 0) {
            for (int i = 0; i < clip.getItemCount() && out.size() < limit; i++) {
                Uri uri = clip.getItemAt(i) == null ? null : clip.getItemAt(i).getUri();
                if (uri != null && "content".equals(uri.getScheme()) && !out.contains(uri)) {
                    out.add(uri);
                }
            }
            if (!out.isEmpty()) return out;
        }
        Uri single = data.getData();
        if (single != null && "content".equals(single.getScheme())) {
            out.add(single);
        }
        return out;
    }

    public static Item fromUri(Context context, Uri uri, int displayIndex) throws IOException {
        Bitmap decoded = null;
        Bitmap scaled = null;
        Bitmap flattened = null;
        Bitmap thumb = null;
        boolean transferred = false;
        try {
            decoded = NativeAvatar.decodeDocument(context, uri, MAX_LONG_EDGE);
            int w = decoded.getWidth();
            int h = decoded.getHeight();
            int longest = Math.max(w, h);
            if (longest > MAX_LONG_EDGE) {
                float ratio = (float) MAX_LONG_EDGE / (float) longest;
                int nw = Math.max(1, Math.round(w * ratio));
                int nh = Math.max(1, Math.round(h * ratio));
                scaled = Bitmap.createScaledBitmap(decoded, nw, nh, true);
                w = nw;
                h = nh;
            } else {
                scaled = decoded;
            }

            // Composite over white before JPEG encoding so transparent PNGs never turn black.
            flattened = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(flattened);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(scaled, 0, 0, null);

            byte[] encodedBytes = null;
            for (int quality : new int[]{84, 72, 58}) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!flattened.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    throw new IOException("无法编码这张图片");
                }
                byte[] candidate = out.toByteArray();
                if (candidate.length <= MAX_ENCODED_BYTES) {
                    encodedBytes = candidate;
                    break;
                }
            }
            if (encodedBytes == null) {
                throw new IOException("图片压缩后仍超过上限，请裁剪后再试");
            }

            thumb = Bitmap.createBitmap(THUMB_PX, THUMB_PX, Bitmap.Config.ARGB_8888);
            Canvas tc = new Canvas(thumb);
            tc.drawColor(Color.rgb(238, 242, 243));
            int edge = Math.min(w, h);
            int left = (w - edge) / 2;
            int top = (h - edge) / 2;
            tc.drawBitmap(flattened, new Rect(left, top, left + edge, top + edge),
                    new Rect(0, 0, THUMB_PX, THUMB_PX),
                    new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));

            int kb = Math.max(1, Math.round(encodedBytes.length / 1024f));
            String label = "图 " + displayIndex + " · " + kb + " KB";
            String b64 = Base64.encodeToString(encodedBytes, Base64.NO_WRAP);
            Item item = new Item("image/jpeg", b64, label, w, h, encodedBytes.length, thumb);
            transferred = true;
            return item;
        } catch (OutOfMemoryError oom) {
            throw new IOException("图片尺寸过大，请换一张图片", oom);
        } finally {
            if (scaled != null && scaled != decoded) scaled.recycle();
            if (decoded != null) decoded.recycle();
            if (flattened != null) flattened.recycle();
            if (thumb != null && !transferred) thumb.recycle();
        }
    }

    public static JSONArray toJsonArray(List<Item> items) throws Exception {
        JSONArray arr = new JSONArray();
        if (items == null) return arr;
        for (Item item : items) {
            if (item == null) continue;
            JSONObject obj = new JSONObject();
            obj.put("mime", item.mime);
            obj.put("data", item.base64Data);
            arr.put(obj);
        }
        return arr;
    }
}
