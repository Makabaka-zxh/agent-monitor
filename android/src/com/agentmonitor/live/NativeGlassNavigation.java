package com.agentmonitor.live;

import android.animation.ValueAnimator;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.function.Consumer;

/**
 * Floating native navigation with a local frosted material, not an OS Liquid Glass implementation.
 * Only the host background drawable and app body are drawn into a small, clipped RAM buffer. No window capture, file IO,
 * RenderEffect on children, or idle frame loop. A slow/unavailable sample falls back to a clear tint.
 */
public final class NativeGlassNavigation extends FrameLayout {
    private static final String[] KEYS = {"tasks", "usage", "account"};
    private static final String[] LABELS = {"任务", "用量", "我的"};
    private static final int MAX_SAMPLE_WIDTH = 192;
    private static final int MAX_SAMPLE_HEIGHT = 48;
    private static final long SAMPLE_INTERVAL_MS = 120L;
    private static final long SAMPLE_BUDGET_MS = 12L;
    private final NativeUi ui;
    private final View backdrop;
    private final View backgroundHost;
    private final NativeUi.Icon[] items = new NativeUi.Icon[3];
    private final Paint materialPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final RectF tray = new RectF();
    private final RectF scratchRect = new RectF();
    private final Path barClip = new Path();
    private final int[] ownLocation = new int[2];
    private final int[] bodyLocation = new int[2];
    private final int[] backgroundLocation = new int[2];
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::refreshBackdrop;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::refreshBackdrop;
    private final Runnable capture = this::captureBackdrop;
    private final Runnable settledCapture = this::refreshBackdrop;
    private final ContentObserver preferenceObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) {
            readAccessibilityPreferences();
            if (reduceTransparency) releaseSample();
            if (!ui.motionEnabled() && selectionAnimator != null) {
                selectionAnimator.cancel(); selectionAnimator = null; visualIndex = selectedIndex;
            }
            for (NativeUi.Icon item : items) ui.pressFeedback(item, .94f);
            refreshBackdrop(); invalidate();
        }
    };
    private ViewTreeObserver observedTree;
    private Bitmap sample;
    private Canvas sampleCanvas;
    private int[] pixels, scratchPixels;
    private LinearGradient material, rim, trayMaterial;
    private ValueAnimator selectionAnimator;
    private int selectedIndex, slowSamples;
    private float visualIndex;
    private long lastSampleAt;
    private boolean captureQueued, samplingUnavailable, reduceTransparency, highContrast;

    public NativeGlassNavigation(NativeUi ui, View backdrop, View backgroundHost, String selected, Consumer<String> navigate) {
        super(ui.activity);
        this.ui = ui; this.backdrop = backdrop; this.backgroundHost = backgroundHost;
        setWillNotDraw(false); setClipChildren(false); setClipToPadding(false);
        setMinimumWidth(ui.dp(168)); setMinimumHeight(ui.dp(72));
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout choices = ui.row(); choices.setGravity(Gravity.CENTER);
        choices.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        addView(choices, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        for (int index = 0; index < KEYS.length; index++) {
            final int position = index;
            NativeUi.Icon item = (NativeUi.Icon) ui.icon(KEYS[index], 24);
            item.setMinimumWidth(ui.dp(48)); item.setMinimumHeight(ui.dp(48));
            item.setFocusable(true); item.setClickable(true);
            item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            item.setContentDescription(LABELS[index]); item.setTooltipText(LABELS[index]);
            item.setBackground(ui.ripple(Color.TRANSPARENT, 24));
            item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.Button"); info.setSelected(host.isSelected());
                    info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(0, 1, position, 1, false, host.isSelected()));
                }
            });
            item.setOnClickListener(view -> {
                // The highlighted tab can still return from a detail/settings page to its root.
                if (selectedIndex != position) setSelected(KEYS[position], true);
                if (navigate != null) navigate.accept(KEYS[position]);
            });
            ui.pressFeedback(item, .94f);
            choices.addView(item, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
            items[index] = item;
        }
        readAccessibilityPreferences(); setSelected(selected, false);
    }

    public void setSelected(String selected, boolean animate) {
        int next = indexOf(selected);
        selectedIndex = next;
        for (int index = 0; index < items.length; index++) {
            boolean active = index == next;
            items[index].setSelected(active);
            items[index].setInk(active || highContrast ? ui.text : ui.muted);
            if (Build.VERSION.SDK_INT >= 30) items[index].setStateDescription(active ? "已选中" : null);
        }
        if (selectionAnimator != null) { selectionAnimator.cancel(); selectionAnimator = null; }
        if (animate && ui.motionEnabled() && isAttachedToWindow() && Math.abs(visualIndex - next) > .001f) {
            selectionAnimator = ValueAnimator.ofFloat(visualIndex, next);
            selectionAnimator.setDuration(320);
            selectionAnimator.setInterpolator(new PathInterpolator(.2f, .85f, .2f, 1f));
            selectionAnimator.addUpdateListener(animation -> {
                visualIndex = (float) animation.getAnimatedValue(); invalidate();
            });
            selectionAnimator.start();
        } else { visualIndex = next; invalidate(); }
        removeCallbacks(settledCapture);
        if (isAttachedToWindow()) { refreshBackdrop(); postDelayed(settledCapture, 340); }
    }

    /** Coalesced, event-driven refresh. Scrolling never schedules an unbounded animation loop. */
    public void refreshBackdrop() {
        if (ui == null || !isAttachedToWindow() || !isShown() || !hasWindowFocus() || captureQueued) return;
        captureQueued = true;
        postDelayed(capture, Math.max(0, SAMPLE_INTERVAL_MS - (SystemClock.uptimeMillis() - lastSampleAt)));
    }

    private void readAccessibilityPreferences() {
        highContrast = ui.highContrastEnabled(); reduceTransparency = ui.reducedTransparencyEnabled();
        for (int i = 0; i < items.length; i++) if (items[i] != null)
            items[i].setInk(i == selectedIndex || highContrast ? ui.text : ui.muted);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); samplingUnavailable = false; slowSamples = 0;
        if (safeBackdrop()) {
            observedTree = backdrop.getViewTreeObserver();
            observedTree.addOnScrollChangedListener(scrollListener);
            observedTree.addOnGlobalLayoutListener(layoutListener);
        }
        try {
            for (String key : new String[]{"high_text_contrast_enabled", "accessibility_reduce_transparency", "reduce_transparency"})
                ui.activity.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(key), false, preferenceObserver);
            for (String key : new String[]{"accessibility_reduce_transparency", "reduce_transparency"})
                ui.activity.getContentResolver().registerContentObserver(Settings.System.getUriFor(key), false, preferenceObserver);
            ui.activity.getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, preferenceObserver);
        } catch (RuntimeException unavailable) { /* Focus/layout refresh still checks readable settings. */ }
        refreshBackdrop();
    }

    @Override protected void onDetachedFromWindow() {
        cancelPendingCapture();
        if (selectionAnimator != null) { selectionAnimator.cancel(); selectionAnimator = null; }
        ViewTreeObserver cleanupTree = observedTree != null && observedTree.isAlive() ? observedTree
                : backdrop != null ? backdrop.getViewTreeObserver() : null;
        if (cleanupTree != null && cleanupTree.isAlive()) {
            cleanupTree.removeOnScrollChangedListener(scrollListener);
            cleanupTree.removeOnGlobalLayoutListener(layoutListener);
        }
        observedTree = null; releaseSample(); super.onDetachedFromWindow();
        try { ui.activity.getContentResolver().unregisterContentObserver(preferenceObserver); }
        catch (RuntimeException unavailable) { /* A vendor may not expose settings observers. */ }
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (ui == null) return;
        if (!isShown()) { cancelPendingCapture(); releaseSample(); }
        else refreshBackdrop();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) { readAccessibilityPreferences(); refreshBackdrop(); invalidate(); }
        else { cancelPendingCapture(); releaseSample(); }
    }

    private void cancelPendingCapture() {
        removeCallbacks(capture); removeCallbacks(settledCapture); captureQueued = false;
    }

    /** Rejects an ancestor as the backdrop so rendering cannot include or recurse into this bar. */
    private boolean safeBackdrop() {
        if (backdrop == null || backdrop == this) return false;
        for (ViewParent parent = getParent(); parent != null; parent = parent.getParent()) if (parent == backdrop) return false;
        return true;
    }

    /** The background must belong to a shared ancestor; never render that ancestor's children. */
    private boolean safeBackgroundHost() {
        if (backgroundHost == null || backgroundHost == this || backgroundHost == backdrop) return false;
        boolean ownsBar = false, ownsBody = false;
        for (ViewParent parent = getParent(); parent != null; parent = parent.getParent())
            if (parent == backgroundHost) { ownsBar = true; break; }
        for (ViewParent parent = backdrop.getParent(); parent != null; parent = parent.getParent())
            if (parent == backgroundHost) { ownsBody = true; break; }
        return ownsBar && ownsBody;
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        bar.set(ui.dp(8), ui.dp(8), Math.max(ui.dp(8), width - ui.dp(8)), Math.max(ui.dp(8), height - ui.dp(8)));
        barClip.reset(); barClip.addRoundRect(bar, bar.height() / 2f, bar.height() / 2f, Path.Direction.CW);
        material = new LinearGradient(0, bar.top, 0, bar.bottom,
                ui.dark ? 0xC1374655 : 0xBEFFFFFF, ui.dark ? 0xD1202C38 : 0xACF5F8FB, Shader.TileMode.CLAMP);
        rim = new LinearGradient(0, bar.top, width, bar.bottom,
                new int[]{ui.dark ? 0x667D93A8 : 0xF2FFFFFF, ui.dark ? 0x164F6274 : 0x4DFFFFFF, ui.dark ? 0x41687C8D : 0xA1D4DEE7},
                new float[]{0, .55f, 1}, Shader.TileMode.CLAMP);
        trayMaterial = new LinearGradient(0, bar.top, 0, bar.bottom,
                ui.dark ? 0xE2587087 : 0xF2FFFFFF, ui.dark ? 0xC1405365 : 0xD7E1EAF2, Shader.TileMode.CLAMP);
        releaseSample(); refreshBackdrop();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bar.isEmpty()) return;
        float radius = bar.height() / 2f;
        materialPaint.setShader(null); materialPaint.setStyle(Paint.Style.FILL);
        // Small concentric shadows avoid a software-rendered full view layer.
        for (int i = 3; i > 0; i--) {
            scratchRect.set(bar); scratchRect.inset(-ui.dp(i), -ui.dp(i)); scratchRect.offset(0, ui.dp(1));
            materialPaint.setColor(ui.dark ? 0x08000000 : 0x040F2D46);
            canvas.drawRoundRect(scratchRect, radius + ui.dp(i), radius + ui.dp(i), materialPaint);
        }
        canvas.save(); canvas.clipPath(barClip);
        if (!reduceTransparency && sample != null && !sample.isRecycled()) {
            materialPaint.setColor(Color.WHITE); canvas.drawBitmap(sample, null, bar, materialPaint);
        }
        if (reduceTransparency) { materialPaint.setColor(ui.surface); materialPaint.setShader(null); }
        else { materialPaint.setColor(Color.WHITE); materialPaint.setShader(material); }
        canvas.drawRoundRect(bar, radius, radius, materialPaint); materialPaint.setShader(null); canvas.restore();
        edgePaint.setStyle(Paint.Style.STROKE); edgePaint.setStrokeWidth(ui.dp(1));
        edgePaint.setColor(highContrast ? ui.muted : Color.WHITE); edgePaint.setShader(highContrast ? null : rim);
        canvas.drawRoundRect(bar, radius, radius, edgePaint); edgePaint.setShader(null);

        float slot = (getWidth() - ui.dp(24)) / 3f;
        float left = ui.dp(12) + slot * visualIndex + ui.dp(4);
        tray.set(left, bar.top + ui.dp(6), left + slot - ui.dp(8), bar.bottom - ui.dp(6));
        materialPaint.setColor(ui.soft); materialPaint.setShader(reduceTransparency ? null : trayMaterial);
        canvas.drawRoundRect(tray, tray.height() / 2f, tray.height() / 2f, materialPaint); materialPaint.setShader(null);
        edgePaint.setColor(highContrast ? ui.text : ui.dark ? 0x696E879D : 0xE6FFFFFF);
        edgePaint.setStrokeWidth(highContrast ? ui.dp(1.5f) : ui.dp(1));
        canvas.drawRoundRect(tray, tray.height() / 2f, tray.height() / 2f, edgePaint);
        // A tiny persistent mark makes selection legible without relying on tint alone.
        materialPaint.setColor(ui.accent);
        canvas.drawRoundRect(tray.centerX() - ui.dp(4), tray.bottom - ui.dp(4), tray.centerX() + ui.dp(4), tray.bottom - ui.dp(2), ui.dp(1), ui.dp(1), materialPaint);
    }

    private void captureBackdrop() {
        captureQueued = false; lastSampleAt = SystemClock.uptimeMillis();
        if (!isAttachedToWindow() || !isShown() || !hasWindowFocus()) return;
        readAccessibilityPreferences();
        if (reduceTransparency || samplingUnavailable || !safeBackdrop() || !safeBackgroundHost() || bar.isEmpty()
                || !backdrop.isAttachedToWindow() || backdrop.getWidth() <= 0 || backdrop.getHeight() <= 0
                || !backgroundHost.isAttachedToWindow() || backgroundHost.getWidth() <= 0 || backgroundHost.getHeight() <= 0) {
            releaseSample(); invalidate(); return;
        }
        long started = SystemClock.uptimeMillis();
        try {
            getLocationInWindow(ownLocation); backdrop.getLocationInWindow(bodyLocation);
            float sourceLeft = ownLocation[0] - bodyLocation[0] + bar.left;
            float sourceTop = ownLocation[1] - bodyLocation[1] + bar.top;
            // The body may not extend under the bar on a compact/keyboard layout.
            if (sourceLeft >= backdrop.getWidth() || sourceTop >= backdrop.getHeight()
                    || sourceLeft + bar.width() <= 0 || sourceTop + bar.height() <= 0) {
                releaseSample(); invalidate(); return;
            }
            int sampleWidth = Math.max(1, Math.min(MAX_SAMPLE_WIDTH, Math.round(bar.width() / 4f)));
            int sampleHeight = Math.max(1, Math.min(MAX_SAMPLE_HEIGHT, Math.round(bar.height() * sampleWidth / bar.width())));
            ensureSample(sampleWidth, sampleHeight);
            sample.eraseColor(ui.bg);
            drawHostBackground(sampleWidth, sampleHeight);
            int saved = sampleCanvas.save();
            try {
                sampleCanvas.scale(sampleWidth / bar.width(), sampleHeight / bar.height());
                sampleCanvas.translate(-sourceLeft, -sourceTop);
                sampleCanvas.clipRect(sourceLeft, sourceTop, sourceLeft + bar.width(), sourceTop + bar.height());
                backdrop.draw(sampleCanvas);
            } finally { sampleCanvas.restoreToCount(saved); }
            sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight);
            blurPass(pixels, scratchPixels, sampleWidth, sampleHeight, true);
            blurPass(scratchPixels, pixels, sampleWidth, sampleHeight, false);
            sample.setPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight);
            if (SystemClock.uptimeMillis() - started > SAMPLE_BUDGET_MS) slowSamples++; else slowSamples = 0;
            if (slowSamples >= 2) { samplingUnavailable = true; releaseSample(); }
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            samplingUnavailable = true; releaseSample();
        }
        invalidate();
    }

    private void drawHostBackground(int sampleWidth, int sampleHeight) {
        // Read the current drawable each time so wallpaper replacement cannot retain an old image.
        // No drawable reference survives this call or needs separate lifecycle cleanup.
        Drawable background = backgroundHost.getBackground();
        if (background == null) return;
        backgroundHost.getLocationInWindow(backgroundLocation);
        float sourceLeft = ownLocation[0] - backgroundLocation[0] + bar.left;
        float sourceTop = ownLocation[1] - backgroundLocation[1] + bar.top;
        Rect previousBounds = new Rect(background.getBounds());
        int saved = sampleCanvas.save();
        try {
            sampleCanvas.scale(sampleWidth / bar.width(), sampleHeight / bar.height());
            sampleCanvas.translate(-sourceLeft, -sourceTop);
            sampleCanvas.clipRect(sourceLeft, sourceTop, sourceLeft + bar.width(), sourceTop + bar.height());
            sampleCanvas.clipRect(0, 0, backgroundHost.getWidth(), backgroundHost.getHeight());
            background.setBounds(0, 0, backgroundHost.getWidth(), backgroundHost.getHeight());
            background.draw(sampleCanvas);
        } finally {
            try { background.setBounds(previousBounds); }
            finally { sampleCanvas.restoreToCount(saved); }
        }
    }

    private void ensureSample(int width, int height) {
        if (sample != null && sample.getWidth() == width && sample.getHeight() == height) return;
        releaseSample();
        sample = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); sample.setHasAlpha(false);
        sampleCanvas = new Canvas(sample); pixels = new int[width * height]; scratchPixels = new int[width * height];
    }

    private void releaseSample() {
        sampleCanvas = null;
        if (sample != null) { sample.recycle(); sample = null; }
        pixels = null; scratchPixels = null;
    }

    static int indexOf(String selected) {
        for (int index = 0; index < KEYS.length; index++) if (KEYS[index].equals(selected)) return index;
        return 0;
    }

    /** Fixed radius, bounded work and edge-clamped samples; input/output arrays must be distinct. */
    static void blurPass(int[] input, int[] output, int width, int height, boolean horizontal) {
        if (input == output || width < 1 || height < 1 || width > MAX_SAMPLE_WIDTH || height > MAX_SAMPLE_HEIGHT
                || input == null || output == null || input.length < width * height || output.length < width * height)
            throw new IllegalArgumentException("Invalid local blur buffers");
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int red = 0, green = 0, blue = 0;
            for (int offset = -3; offset <= 3; offset++) {
                int sampleX = horizontal ? Math.max(0, Math.min(width - 1, x + offset)) : x;
                int sampleY = horizontal ? y : Math.max(0, Math.min(height - 1, y + offset));
                int color = input[sampleY * width + sampleX];
                red += (color >>> 16) & 255; green += (color >>> 8) & 255; blue += color & 255;
            }
            output[y * width + x] = 0xFF000000 | ((red / 7) << 16) | ((green / 7) << 8) | (blue / 7);
        }
    }
}
