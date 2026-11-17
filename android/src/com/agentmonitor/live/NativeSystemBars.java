package com.agentmonitor.live;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/** Platform-only edge-to-edge bars for a full-window root with caller-owned inset padding.
 * The noninteractive overlay protects system icons even when the root paints a wallpaper.
 * Call onInsets from the existing root listener; this class never installs an inset listener.
 */
public final class NativeSystemBars {
    private final Window window;
    private final View root;
    private final Protection protection = new Protection();

    public static NativeSystemBars attach(Window window, View root, int statusColor, int navigationColor) {
        return new NativeSystemBars(window, root, statusColor, navigationColor);
    }

    private NativeSystemBars(Window window, View root, int statusColor, int navigationColor) {
        this.window = window;
        this.root = root;
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        // API 35+ ignores status-bar color: protection is drawn by the app, not by this setter.
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) window.setNavigationBarDividerColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        root.getOverlay().add(protection);
        root.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> resize());
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                applyAppearance();
                view.requestApplyInsets();
            }
            @Override public void onViewDetachedFromWindow(View view) {}
        });
        setColors(statusColor, navigationColor);
        resize();
        root.requestApplyInsets();
    }

    /** Supply the header/base surface colors, never a color sampled from a private image. */
    public void setColors(int statusColor, int navigationColor) {
        protection.statusColor = NativeSystemBarPolicy.opaque(statusColor);
        protection.navigationColor = NativeSystemBarPolicy.opaque(navigationColor);
        protection.invalidateSelf();
        applyAppearance();
    }

    /** Read only system/cutout bounds; never paint the IME inset as a navigation bar. */
    public void onInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets top = insets.getInsets(WindowInsets.Type.statusBars()
                    | WindowInsets.Type.captionBar() | WindowInsets.Type.displayCutout());
            android.graphics.Insets navigation = insets.getInsets(WindowInsets.Type.navigationBars());
            protection.top = top.top;
            protection.left = Math.max(top.left, navigation.left);
            protection.right = Math.max(top.right, navigation.right);
            protection.bottom = navigation.bottom;
        } else {
            protection.top = insets.getSystemWindowInsetTop();
            protection.left = insets.getSystemWindowInsetLeft();
            protection.right = insets.getSystemWindowInsetRight();
            // systemWindowInsetBottom includes the keyboard on older versions; stable does not.
            protection.bottom = Math.min(insets.getSystemWindowInsetBottom(), insets.getStableInsetBottom());
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    protection.top = Math.max(protection.top, cutout.getSafeInsetTop());
                    protection.left = Math.max(protection.left, cutout.getSafeInsetLeft());
                    protection.right = Math.max(protection.right, cutout.getSafeInsetRight());
                }
            }
        }
        resize();
        applyAppearance();
    }

    private void resize() {
        protection.setBounds(0, 0, root.getWidth(), root.getHeight());
        protection.invalidateSelf();
    }

    private void applyAppearance() {
        boolean darkStatus = NativeSystemBarPolicy.darkIcons(protection.statusColor);
        boolean darkNavigation = NativeSystemBarPolicy.darkIcons(protection.navigationColor);
        if (Build.VERSION.SDK_INT >= 30) {
            // Startup can precede decor creation. Ask the attached root, never the window.
            if (!root.isAttachedToWindow()) return;
            WindowInsetsController controller = root.getWindowInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                int appearance = (darkStatus ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0)
                        | (darkNavigation ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0);
                controller.setSystemBarsAppearance(appearance, mask);
            }
        } else {
            View decor = window.getDecorView();
            int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            int appearance = (darkStatus ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0)
                    | (darkNavigation ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
            decor.setSystemUiVisibility((decor.getSystemUiVisibility() & ~mask) | appearance);
        }
    }

    private static final class Protection extends Drawable {
        final Paint paint = new Paint();
        int statusColor, navigationColor, top, left, right, bottom;

        @Override public void draw(Canvas canvas) {
            int width = getBounds().width(), height = getBounds().height();
            paint.setColor(navigationColor);
            canvas.drawRect(0, 0, Math.min(left, width), height, paint);
            canvas.drawRect(Math.max(0, width - right), 0, width, height, paint);
            canvas.drawRect(0, Math.max(0, height - bottom), width, height, paint);
            paint.setColor(statusColor);
            canvas.drawRect(0, 0, width, Math.min(top, height), paint);
        }
        // This protection is always opaque where drawn; callers cannot fade its icon background.
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
