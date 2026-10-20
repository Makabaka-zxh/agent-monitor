package com.agentmonitor.live;

import android.app.Activity;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small native design system. Static surfaces deliberately avoid WebView and blur layers. */
public final class NativeUi {
    public final Activity activity;
    public final boolean dark;
    public final int bg, surface, text, muted, accent, line, soft, warning, danger;
    public final Typeface regular, medium;

    public NativeUi(Activity activity) {
        this.activity = activity;
        dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = color(dark ? "#16191B" : "#F3F4F3");
        surface = color(dark ? "#202527" : "#FFFFFF");
        text = color(dark ? "#ECEFEC" : "#222C2B");
        muted = color(dark ? "#A9B3B0" : "#64706D");
        accent = color(dark ? "#AAD4C6" : "#356555");
        line = color(dark ? "#343D3B" : "#E1E6E3");
        soft = color(dark ? "#2A3933" : "#E7EFEA");
        warning = color(dark ? "#E1BE88" : "#866022");
        danger = color(dark ? "#EDABAB" : "#A44343");
        Typeface loaded;
        try { loaded = Typeface.createFromAsset(activity.getAssets(), "fonts/MiSans-Regular.ttf"); }
        catch (RuntimeException unavailable) { loaded = Typeface.create("sans-serif", Typeface.NORMAL); }
        regular = loaded;
        medium = Typeface.create(loaded, Typeface.BOLD);
    }

    public int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static int color(String value) { return Color.parseColor(value); }
    public LinearLayout column() { LinearLayout result = new LinearLayout(activity); result.setOrientation(LinearLayout.VERTICAL); return result; }
    public LinearLayout row() { LinearLayout result = new LinearLayout(activity); result.setOrientation(LinearLayout.HORIZONTAL); result.setGravity(Gravity.CENTER_VERTICAL); return result; }
    public TextView text(String value, float size, boolean bold) {
        TextView result = new TextView(activity);
        result.setText(value == null ? "" : value); result.setTextColor(text);
        result.setTextSize(size); result.setTypeface(bold ? medium : regular);
        result.setIncludeFontPadding(false); result.setLineSpacing(dp(2), 1f);
        return result;
    }
    public TextView text(String value, float size) { return text(value, size, false); }
    public TextView text(String value) { return text(value, 15, false); }
    public void applyFont(View view) {
        if (view instanceof TextView) ((TextView) view).setTypeface(regular);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) applyFont(((ViewGroup) view).getChildAt(i));
    }
    public Drawable rounded(int fill, float radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    public Drawable bordered(int fill, float radius) {
        GradientDrawable drawable = (GradientDrawable) rounded(fill, radius); drawable.setStroke(dp(1), line); return drawable;
    }
    public Drawable ripple(int fill, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x25FFFFFF : 0x15355F58), rounded(fill, radius), rounded(Color.WHITE, radius));
    }
    public Drawable borderedRipple(int fill, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x20FFFFFF : 0x12356555), bordered(fill, radius), rounded(Color.WHITE, radius));
    }
    public LinearLayout card() {
        LinearLayout result = column(); result.setPadding(dp(16), dp(16), dp(16), dp(16));
        result.setBackground(bordered(surface, 20)); return result;
    }
    public View space(float height) { View result = new View(activity); result.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height))); return result; }
    public TextView button(String label, Runnable action) { return button(label, true, action); }
    public TextView button(String label, boolean primary, Runnable action) {
        TextView result = text(label, 15, true); result.setGravity(Gravity.CENTER);
        result.setPadding(dp(16), dp(12), dp(16), dp(12)); result.setMinHeight(dp(48));
        result.setTextColor(primary ? (dark ? bg : Color.WHITE) : accent);
        result.setBackground(ripple(primary ? accent : soft, 16));
        result.setFocusable(true); result.setClickable(true); result.setOnClickListener(v -> { if (action != null) action.run(); });
        pressFeedback(result, .98f);
        result.setAccessibilityDelegate(buttonAccessibility());
        return result;
    }
    public View iconButton(String name, String description, Runnable action) {
        Icon result = new Icon(name, accent); result.setMinimumWidth(dp(48)); result.setMinimumHeight(dp(48));
        result.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48))); result.setBackground(ripple(Color.TRANSPARENT, 16));
        result.setContentDescription(description); result.setFocusable(true); result.setClickable(true);
        result.setOnClickListener(v -> { if (action != null) action.run(); }); result.setAccessibilityDelegate(buttonAccessibility());
        pressFeedback(result, .96f);
        return result;
    }
    public View icon(String name, int size) {
        Icon result = new Icon(name, muted); result.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return result;
    }
    public ImageView brand(String tool, int size) {
        ImageView result = new ImageView(activity); result.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        result.setScaleType(ImageView.ScaleType.FIT_CENTER); bindBrand(result, tool);
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return result;
    }
    public ImageView monitorBrand(int size) {
        ImageView result = new ImageView(activity); result.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        result.setScaleType(ImageView.ScaleType.FIT_CENTER); result.setImageResource(R.drawable.monitor_brand);
        result.setContentDescription("Monitor"); return result;
    }
    public void bindBrand(ImageView view, String tool) {
        int resource = "claude".equals(tool) ? R.drawable.claude : "codex".equals(tool) ? R.drawable.codex : R.drawable.app_icon;
        Object last = view.getTag(); if (!(last instanceof Integer) || ((Integer)last) != resource) { view.setImageResource(resource); view.setTag(resource); }
    }
    /** Text is always present: source identity never relies on icon recognition or color. */
    public LinearLayout toolBadge(String tool) {
        LinearLayout result = row(); result.setPadding(dp(8), dp(5), dp(10), dp(5));
        result.addView(brand(tool, 16));
        TextView label = text("", 12, true); label.setMaxLines(1); label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams words = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        words.setMarginStart(dp(6)); result.addView(label, words); bindToolBadge(result, tool);
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return result;
    }
    public void bindToolBadge(LinearLayout badge, String tool) {
        String source = "claude".equals(tool) ? "claude" : "codex".equals(tool) ? "codex" : "other";
        if (source.equals(badge.getTag())) return;
        bindBrand((ImageView) badge.getChildAt(0), source);
        TextView label = (TextView) badge.getChildAt(1); label.setText(NativeListPresentation.toolLabel(source));
        boolean claude = "claude".equals(source);
        int fill = claude ? color(dark ? "#362E29" : "#F4EDE7") : soft;
        int ink = claude ? color(dark ? "#DBC0A9" : "#79533C") : accent;
        if ("other".equals(source)) { fill = dark ? 0xFF303636 : 0xFFEDF0EE; ink = muted; }
        label.setTextColor(ink); badge.setBackground(rounded(fill, 9)); badge.setTag(source);
    }
    public boolean motionEnabled() { return ValueAnimator.areAnimatorsEnabled(); }
    /** Uses drawable-state animations, leaving scrolling and accessibility gestures untouched. */
    public void pressFeedback(View view, float pressedScale) {
        if (!motionEnabled()) { view.setStateListAnimator(null); return; }
        StateListAnimator states = new StateListAnimator();
        ObjectAnimator press = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, pressedScale), PropertyValuesHolder.ofFloat(View.SCALE_Y, pressedScale));
        press.setDuration(90); press.setInterpolator(new DecelerateInterpolator());
        ObjectAnimator release = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f));
        release.setDuration(150); release.setInterpolator(new DecelerateInterpolator());
        states.addState(new int[]{android.R.attr.state_pressed, android.R.attr.state_enabled}, press);
        states.addState(new int[]{}, release); view.setStateListAnimator(states);
    }
    /** A short, local transition for newly opened content; refreshing rows never calls this. */
    public void reveal(View view) {
        view.animate().cancel(); view.setAlpha(1f); view.setTranslationY(0f);
        if (!motionEnabled()) return;
        view.setAlpha(0f); view.setTranslationY(dp(4));
        view.animate().alpha(1f).translationY(0f).setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
    }
    public static View.AccessibilityDelegate buttonAccessibility() {
        return new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName("android.widget.Button");
            }
        };
    }
    public Drawable background() {
        return new ColorDrawable(bg);
    }

    /** Code-owned line icons have consistent optical weight and no font-dependent glyphs. */
    public final class Icon extends View {
        private String name; private int ink;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        Icon(String name, int ink) { super(activity); this.name = name; this.ink = ink; }
        public void setName(String name) { if (!this.name.equals(name)) { this.name = name; invalidate(); } }
        public void setInk(int ink) { if (this.ink != ink) { this.ink = ink; invalidate(); } }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.save(); float size = Math.min(Math.min(getWidth(), getHeight()), dp(24));
            canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f); canvas.scale(size / 24f, size / 24f);
            paint.setColor(ink); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.7f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            path.reset();
            switch (name) {
                case "back": path.moveTo(14, 5); path.lineTo(7, 12); path.lineTo(14, 19); break;
                case "chevron": path.moveTo(9, 6); path.lineTo(15, 12); path.lineTo(9, 18); break;
                case "plus": path.moveTo(12, 5); path.lineTo(12, 19); path.moveTo(5, 12); path.lineTo(19, 12); break;
                case "close": path.moveTo(6, 6); path.lineTo(18, 18); path.moveTo(18, 6); path.lineTo(6, 18); break;
                case "check": path.moveTo(5, 12); path.lineTo(10, 17); path.lineTo(19, 7); break;
                case "search": canvas.drawCircle(10.5f, 10.5f, 6.5f, paint); path.moveTo(15.5f, 15.5f); path.lineTo(21, 21); break;
                case "usage": canvas.drawRoundRect(new RectF(4, 13, 7, 21), 1, 1, paint); canvas.drawRoundRect(new RectF(10.5f, 8, 13.5f, 21), 1, 1, paint); canvas.drawRoundRect(new RectF(17, 3, 20, 21), 1, 1, paint); break;
                case "error": canvas.drawCircle(12, 12, 9, paint); path.moveTo(12, 7); path.lineTo(12, 13); canvas.drawCircle(12, 17, .65f, paint); break;
                case "account": case "user": canvas.drawCircle(12, 7, 3.4f, paint); canvas.drawArc(new RectF(4, 12, 20, 28), 180, 180, false, paint); break;
                case "monitor": case "devices": canvas.drawRoundRect(new RectF(3, 3.5f, 21, 16.5f), 2, 2, paint); path.moveTo(12, 17); path.lineTo(12, 21); path.moveTo(8, 21); path.lineTo(16, 21); break;
                case "tasks": path.moveTo(3, 7); path.lineTo(12, 2.5f); path.lineTo(21, 7); path.lineTo(12, 11.5f); path.close(); path.moveTo(3, 12); path.lineTo(12, 16.5f); path.lineTo(21, 12); path.moveTo(3, 17); path.lineTo(12, 21.5f); path.lineTo(21, 17); break;
                case "archive": canvas.drawRoundRect(new RectF(3, 3, 21, 8), 1.5f, 1.5f, paint); path.moveTo(5, 8); path.lineTo(5, 20); path.lineTo(19, 20); path.lineTo(19, 8); path.moveTo(10, 12); path.lineTo(14, 12); break;
                case "restore": canvas.drawArc(new RectF(5, 5, 21, 21), -80, 300, false, paint); path.moveTo(5, 2); path.lineTo(5, 9); path.lineTo(12, 9); break;
                case "refresh": canvas.drawArc(new RectF(4, 4, 20, 20), 40, 285, false, paint); path.moveTo(20, 3); path.lineTo(20, 9); path.lineTo(14, 9); break;
                case "help": case "info": canvas.drawCircle(12, 12, 9, paint); path.moveTo(12, 11); path.lineTo(12, 17); canvas.drawCircle(12, 7, .65f, paint); break;
                case "bell": path.moveTo(5, 17); path.lineTo(6.5f, 15); path.lineTo(6.5f, 9); path.cubicTo(6.5f, 2, 17.5f, 2, 17.5f, 9); path.lineTo(17.5f, 15); path.lineTo(19, 17); path.close(); canvas.drawArc(new RectF(9, 17, 15, 23), 0, 180, false, paint); break;
                case "cloud": path.moveTo(6, 18); path.cubicTo(-1, 17, 2, 9, 7, 10); path.cubicTo(6, 1, 19, 2, 19, 10); path.cubicTo(25, 11, 22, 18, 18, 18); path.close(); break;
                case "shield": path.moveTo(12, 2.5f); path.lineTo(20, 6); path.lineTo(19, 14); path.quadTo(17, 19, 12, 22); path.quadTo(7, 19, 5, 14); path.lineTo(4, 6); path.close(); break;
                case "link": canvas.drawRoundRect(new RectF(2, 8, 14, 16), 4, 4, paint); canvas.drawRoundRect(new RectF(10, 8, 22, 16), 4, 4, paint); break;
                case "scan": path.moveTo(8, 3); path.lineTo(3, 3); path.lineTo(3, 8); path.moveTo(16, 3); path.lineTo(21, 3); path.lineTo(21, 8); path.moveTo(21, 16); path.lineTo(21, 21); path.lineTo(16, 21); path.moveTo(8, 21); path.lineTo(3, 21); path.lineTo(3, 16); path.moveTo(6, 12); path.lineTo(18, 12); break;
                case "logout": path.moveTo(10, 3); path.lineTo(4, 3); path.lineTo(4, 21); path.lineTo(10, 21); path.moveTo(9, 12); path.lineTo(21, 12); path.moveTo(17, 8); path.lineTo(21, 12); path.lineTo(17, 16); break;
                default: canvas.drawCircle(12, 12, 8, paint); break;
            }
            canvas.drawPath(path, paint); canvas.restore();
        }
    }
}
