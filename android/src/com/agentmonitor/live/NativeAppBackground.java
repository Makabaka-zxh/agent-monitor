package com.agentmonitor.live;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Single still image with a fixed reading veil; no blur buffers or frame loop. */
public final class NativeAppBackground extends Drawable {
    private final int base, veil;
    private final Bitmap image;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF target = new RectF();
    public NativeAppBackground(int base, boolean dark, Bitmap image) {
        this.base = base; this.image = image;
        // Keep body text readable on every image, including white/black extremes.
        veil = (dark ? 0xda000000 : 0xde000000) | (base & 0x00ffffff);
    }
    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds(); canvas.drawColor(base);
        if (image == null || image.isRecycled() || image.getWidth() <= 0 || image.getHeight() <= 0
                || bounds.width() <= 0 || bounds.height() <= 0) return;
        float scale = Math.max((float) bounds.width() / image.getWidth(), (float) bounds.height() / image.getHeight());
        float w = image.getWidth() * scale, h = image.getHeight() * scale;
        float x = bounds.exactCenterX() - w / 2, y = bounds.exactCenterY() - h / 2;
        target.set(x, y, x + w, y + h);
        int saved = canvas.save(); canvas.clipRect(bounds); canvas.drawBitmap(image, null, target, paint);
        canvas.drawColor(veil); canvas.restoreToCount(saved);
    }
    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
